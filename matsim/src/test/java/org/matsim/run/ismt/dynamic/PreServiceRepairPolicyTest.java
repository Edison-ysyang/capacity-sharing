package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;
import static org.matsim.run.ismt.dynamic.RollingDeparturePolicy.*;

/** Manually advanced observer-bound value fixtures; QSim is NEVER RUN and events are not execution evidence. */
class PreServiceRepairPolicyTest {
    private static final TripKey A = new TripKey("line", "route", "A");
    private static final TripKey B = new TripKey("line", "route", "B");
    private static final List<ScheduledTrip> BASE = List.of(new ScheduledTrip(A, "v1", 100), new ScheduledTrip(B, "v2", 100));
    private static final Map<TripKey, Double> ORIGINAL = Map.of(A, 100.0, B, 100.0);
    private static final Map<TripKey, Double> REPAIR = Map.of(A, 90.0);
    private static final Limits LIMITS = new Limits(20, 20, 5);

    private static DispatchConstraintChecker checker(double minimum) {
        return new DispatchConstraintChecker(BASE, List.of(new ControlPoint(new ControlPointKey("shared", "outbound"),
                List.of(new Call(A, 0), new Call(B, 0)), minimum, 100, Optional.empty())), List.of());
    }

    private static Forecast prediction(double waiting) {
        return Forecast.ready(new Projection("same-prefix-fixed-cohort", 300, Set.of(), new FreightCost(0, 0, 0),
                Map.of("all-affected", new PassengerCost(10, waiting))));
    }

    private static final class Rig {
        final List<PreServiceRepairPolicy.Audit> audits = new ArrayList<>();
        final List<Decision> rolling = new ArrayList<>();
        final AtomicInteger forecastCalls = new AtomicInteger();
        final DispatchConstraintChecker checker;
        final PreServiceRepairPolicy policy;
        Rig() { this(10, REPAIR, 2, 200, 10, (observations, times) -> prediction(50)); }
        Rig(double minimum, Map<TripKey, Double> repair, double repairTime, double lookahead,
                double passengerBound, ForecastModel model) {
            checker = checker(minimum);
            ForecastModel counted = (observations, times) -> { forecastCalls.incrementAndGet(); return model.project(observations, times); };
            policy = wrapper(checker, repair, repairTime, lookahead, passengerBound, counted, audits::add, rolling);
        }
    }

    private static PreServiceRepairPolicy wrapper(DispatchConstraintChecker checker, Map<TripKey, Double> repair,
            double repairTime, double lookahead, double bound, ForecastModel model,
            java.util.function.Consumer<PreServiceRepairPolicy.Audit> audits, List<Decision> rolling) {
        var delegate = new RollingDeparturePolicy(new RollingDepartureCandidates(BASE, LIMITS, 200, 10, 50),
                checker, model, 2, 3, bound, 3, rolling::add);
        return new PreServiceRepairPolicy(ORIGINAL, LIMITS, checker, repair, repairTime, lookahead, 2, 3, model, delegate, bound, audits);
    }

    private static final class Source implements AutoCloseable {
        final EventsManager events = EventsUtils.createEventsManager();
        final QSim qsim;
        final NativeEventPrefixObserver observer;
        final ClosedCoTransportObservationState composition;
        final DispatchCommitmentState ledger = new DispatchCommitmentState(BASE, LIMITS);
        private int last = -1;
        Source() { this(20); }
        Source(int iteration) {
            var config = ConfigUtils.createConfig(); config.qsim().setNumberOfThreads(1); config.global().setNumberOfThreads(1);
            config.qsim().setStartTime(0); config.qsim().setEndTime(200);
            config.controller().setOutputDirectory(Path.of(".build-cache", "pre-service-repair-never-run").toAbsolutePath().toString());
            qsim = new QSimBuilder(config).useDefaults().build(ScenarioUtils.createScenario(config), events);
            composition = new ClosedCoTransportObservationState(iteration, 1,
                    List.of(new ObservedTransitServiceState.Service(A, "v1", List.of("s0", "s1")),
                            new ObservedTransitServiceState.Service(B, "v2", List.of("s0", "s1"))),
                    List.of(new KnownFreightReleaseState.Contract("order", "s0", "s1", 0, 150, List.of("unit"), "origin")),
                    Set.of(), Map.of());
            observer = NativeEventPrefixObserver.attach(qsim, iteration, 1, 0, composition::accept);
            composition.bindTo(observer); qsim.getSimTimer().setTime(0);
            observer.notifyMobsimInitialized(new MobsimInitializedEvent<>(qsim)); ledger.beginIteration(iteration, 0, true);
        }
        ClosedCoTransportObservationState.Snapshot at(int time) {
            if (last >= 0) observer.notifyMobsimAfterSimStep(new MobsimAfterSimStepEvent<>(qsim, last));
            qsim.getSimTimer().setTime(time); ledger.advanceTo(time); last = time;
            observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(qsim, time));
            return composition.snapshot().orElseThrow();
        }
        Map<TripKey, Double> call(PreServiceRepairPolicy policy, int time) { return policy.propose(at(time), ledger.snapshot()); }
        void commit(Map<TripKey, Double> proposal) {
            var current = ledger.snapshot(); ledger.commit(new Batch(current.version(), current.nowSeconds(), List.of(), proposal));
        }
        @Override public void close() {
            if (last >= 0) observer.notifyMobsimAfterSimStep(new MobsimAfterSimStepEvent<>(qsim, last));
            observer.close();
        }
    }

    @Test void pendingInfeasibleBaselineNeverReachesRollingOrInventsFeasibleFallback() {
        var rig = new Rig();
        try (Source source = new Source()) {
            assertTrue(source.call(rig.policy, 0).isEmpty()); assertTrue(source.call(rig.policy, 1).isEmpty());
            assertEquals(1, rig.audits.size());
            assertEquals(PreServiceRepairPolicy.Outcome.PENDING_REGISTERED_REPAIR, rig.audits.getFirst().outcome());
            assertFalse(rig.audits.getFirst().originalAssessment().satisfiesRegisteredConstraints());
            assertTrue(rig.rolling.isEmpty()); assertEquals(0, rig.forecastCalls.get());
            assertEquals(ORIGINAL, source.ledger.snapshot().commitments()); assertTrue(source.ledger.history().isEmpty());
        }
    }

    @Test void proposalThenObservedCommitmentThenCorrectRollingCadenceAreDifferentEvidence() {
        var rig = new Rig();
        try (Source source = new Source()) {
            source.call(rig.policy, 0); source.call(rig.policy, 1);
            var proposal = source.call(rig.policy, 2); assertEquals(REPAIR, proposal);
            assertEquals(ORIGINAL, source.ledger.snapshot().commitments()); assertTrue(source.ledger.history().isEmpty());
            assertEquals(PreServiceRepairPolicy.Outcome.REPAIR_PROPOSED, rig.audits.getLast().outcome());
            assertFalse(rig.audits.getLast().repairComparison().orElseThrow().baseline().satisfiesRegisteredConstraints());
            assertTrue(rig.audits.getLast().repairComparison().orElseThrow().candidate().satisfiesRegisteredConstraints());
            source.commit(proposal); source.call(rig.policy, 3);
            assertEquals(PreServiceRepairPolicy.Outcome.REPAIR_COMMITMENT_OBSERVED, rig.audits.getLast().outcome());
            assertFalse(rig.audits.getLast().actualExecutionVerified());
            assertTrue(source.ledger.snapshot().startedAtSeconds().isEmpty());
            source.call(rig.policy, 4); assertTrue(rig.rolling.isEmpty());
            for (int time = 5; time <= 8; time++) source.call(rig.policy, time);
            assertEquals(List.of(5.0, 8.0), rig.rolling.stream().map(Decision::decisionTimeSeconds).toList());
            assertEquals(2, rig.forecastCalls.get(), "No freight: only the two repair predictions are needed");
            assertTrue(rig.audits.stream().noneMatch(PreServiceRepairPolicy.Audit::actualExecutionVerified));
        }
    }

    @Test void uncommittedProposalFailsAtTheNextCallAndCannotBeRetried() {
        var rig = new Rig();
        try (Source source = new Source()) {
            source.call(rig.policy, 0); assertEquals(REPAIR, source.call(rig.policy, 2));
            assertThrows(IllegalArgumentException.class, () -> source.call(rig.policy, 3));
            assertEquals(PreServiceRepairPolicy.Outcome.FAILED, rig.audits.getLast().outcome());
            assertFalse(rig.audits.stream().anyMatch(a -> a.outcome() == PreServiceRepairPolicy.Outcome.REPAIR_COMMITMENT_OBSERVED));
            assertThrows(IllegalArgumentException.class, () -> source.call(rig.policy, 4)); assertTrue(rig.rolling.isEmpty());
        }
    }

    @Test void partialOrAdditionalCommitmentChangesAreNotAcceptedAsTheRegisteredRepair() {
        for (Map<TripKey, Double> wrong : List.of(Map.of(A, 80.0), Map.of(A, 90.0, B, 110.0), Map.of(B, 110.0))) {
            var rig = new Rig();
            try (Source source = new Source()) {
                source.call(rig.policy, 0); source.call(rig.policy, 2); source.commit(wrong);
                assertThrows(IllegalArgumentException.class, () -> source.call(rig.policy, 3)); assertTrue(rig.rolling.isEmpty());
            }
        }
    }

    @Test void preRepairMutationOfAnyTripIsRejectedWithoutForecastOrDelegate() {
        var rig = new Rig();
        try (Source source = new Source()) {
            source.call(rig.policy, 0); source.ledger.advanceTo(1); source.commit(Map.of(B, 110.0));
            assertThrows(IllegalArgumentException.class, () -> source.call(rig.policy, 2));
            assertEquals(0, rig.forecastCalls.get()); assertTrue(rig.rolling.isEmpty());
        }
    }

    @Test void repairShiftBudgetRemainsRelativeToOriginalRatherThanRepairedTimes() {
        assertThrows(IllegalArgumentException.class, () -> new Rig(10, Map.of(A, 79.0), 2, 200, 10, (o, t) -> prediction(50)));
        assertThrows(IllegalArgumentException.class, () -> new Rig(10, Map.of(B, 121.0), 2, 200, 10, (o, t) -> prediction(50)));
        var rig = new Rig();
        try (Source source = new Source()) {
            source.call(rig.policy, 0); source.commit(source.call(rig.policy, 2));
            source.call(rig.policy, 3); source.call(rig.policy, 4); var observations = source.at(5);
            var current = source.ledger.snapshot();
            var outside = new Snapshot(current.iteration(), current.version(), current.nowSeconds(), true,
                    Map.of(A, 79.0, B, 100.0), current.lockedTrips(), current.startedAtSeconds());
            assertThrows(IllegalArgumentException.class, () -> rig.policy.propose(observations, outside));
        }
    }

    @Test void originalAndCandidateBothMustBeOutsideLockAndInsideLookahead() {
        rejectAt(95, 200, REPAIR); rejectAt(85, 200, REPAIR); rejectAt(2, 97, REPAIR); rejectAt(2, 100, Map.of(B, 110.0));
    }

    private static void rejectAt(int decision, double horizon, Map<TripKey, Double> repair) {
        var rig = new Rig(10, repair, decision, horizon, 10, (o, t) -> prediction(50));
        try (Source source = new Source()) {
            source.call(rig.policy, 0);
            assertThrows(IllegalArgumentException.class, () -> source.call(rig.policy, decision));
            assertEquals(0, rig.forecastCalls.get());
        }
    }

    @Test void explicitlyStartedAndLatchedLockedRepairTripsAreRejected() {
        for (boolean started : List.of(false, true)) {
            var rig = new Rig();
            try (Source source = new Source()) {
                source.call(rig.policy, 0); var observations = source.at(2); var current = source.ledger.snapshot();
                var blocked = new Snapshot(current.iteration(), current.version(), 2, true, current.commitments(),
                        Set.of(A), started ? Map.of(A, 1.0) : Map.of());
                assertThrows(IllegalArgumentException.class, () -> rig.policy.propose(observations, blocked));
                assertEquals(0, rig.forecastCalls.get());
            }
        }
    }

    @Test void infeasibleRepairIsRejectedBeforeCallingAnyForecast() {
        var rig = new Rig(10, Map.of(A, 95.0), 2, 200, 10, (o, t) -> prediction(50));
        try (Source source = new Source()) {
            source.call(rig.policy, 0); assertThrows(IllegalArgumentException.class, () -> source.call(rig.policy, 2));
            assertEquals(0, rig.forecastCalls.get()); assertTrue(source.ledger.history().isEmpty());
        }
    }

    @Test void matureForecastIsRequiredForBothReferenceAndCandidate() {
        for (ForecastStatus status : List.of(ForecastStatus.INSUFFICIENT_HISTORY, ForecastStatus.UNSUPPORTED_STATE)) {
            for (boolean candidateOnly : List.of(false, true)) {
                var rig = new Rig(10, REPAIR, 2, 200, 10, (o, t) -> !candidateOnly || t.get(A) == 90
                        ? new Forecast(status, "explicit-unavailable", Optional.empty()) : prediction(50));
                try (Source source = new Source()) {
                    source.call(rig.policy, 0); assertThrows(IllegalArgumentException.class, () -> source.call(rig.policy, 2));
                    assertTrue(source.ledger.history().isEmpty()); assertTrue(rig.rolling.isEmpty());
                }
            }
        }
    }

    @Test void passengerProtectionUsesWeightedFixedCohortAndInclusiveBound() {
        for (double bound : new double[]{8.99, 9}) {
            var rig = new Rig(10, REPAIR, 2, 200, bound, (o, t) -> Forecast.ready(new Projection("fixed", 300, Set.of(),
                    new FreightCost(0, 0, 0), Map.of("large", new PassengerCost(9, t.get(A) == 90 ? 60 : 50),
                    "small", new PassengerCost(1, 50)))));
            try (Source source = new Source()) {
                source.call(rig.policy, 0);
                if (bound < 9) assertThrows(IllegalArgumentException.class, () -> source.call(rig.policy, 2));
                else { assertEquals(REPAIR, source.call(rig.policy, 2)); assertEquals(9.0, rig.audits.getLast().meanPassengerWaitingIncrementSeconds().orElseThrow()); }
            }
        }
    }

    @Test void projectionIdentityHorizonCohortAndMassCannotChangeBetweenRepairPredictions() {
        Projection reference = prediction(50).projection().orElseThrow();
        List<Projection> invalid = List.of(
                new Projection("other", 300, Set.of(), reference.freight(), reference.affectedPassengers()),
                new Projection(reference.demandProjectionId(), 301, Set.of(), reference.freight(), reference.affectedPassengers()),
                new Projection(reference.demandProjectionId(), 300, Set.of(), reference.freight(), Map.of()),
                new Projection(reference.demandProjectionId(), 300, Set.of(), reference.freight(), Map.of("all-affected", new PassengerCost(9, 50))),
                new Projection(reference.demandProjectionId(), 300, Set.of("invented"), new FreightCost(0, 0, 0), reference.affectedPassengers()),
                new Projection(reference.demandProjectionId(), 2, Set.of(), reference.freight(), reference.affectedPassengers()));
        for (Projection candidate : invalid) {
            var rig = new Rig(10, REPAIR, 2, 200, 10, (o, t) -> Forecast.ready(t.get(A) == 100 ? reference : candidate));
            try (Source source = new Source()) {
                source.call(rig.policy, 0); assertThrows(IllegalArgumentException.class, () -> source.call(rig.policy, 2));
                assertTrue(source.ledger.history().isEmpty());
            }
        }
    }

    @Test void releasedUnitIdsMustBeCoveredEvenBeforeTheirStopArrival() {
        var rig = new Rig();
        try (Source source = new Source()) {
            source.call(rig.policy, 0);
            source.events.processEvent(new ActivityEndEvent(0, Id.createPersonId("unit"), Id.createLinkId("origin-link"), null, "origin", null));
            assertThrows(IllegalArgumentException.class, () -> source.call(rig.policy, 2));
            assertEquals(Set.of("unit"), source.composition.snapshot().orElseThrow().releases().knownOrders().get("order").releasedUnits().keySet());
            assertTrue(source.composition.snapshot().orElseThrow().riders().riders().isEmpty());
        }
    }

    @Test void missedRepairTimeUnknownHistoryAndFirstRollingCadenceFailExplicitly() {
        var missed = new Rig();
        try (Source source = new Source()) {
            source.call(missed.policy, 0); assertThrows(IllegalArgumentException.class, () -> source.call(missed.policy, 3));
        }
        var unknown = new Rig(10, REPAIR, 0, 200, 10, (o, t) -> prediction(50));
        try (Source source = new Source()) { assertThrows(IllegalArgumentException.class, () -> source.call(unknown.policy, 0)); }
        var lateRolling = new Rig();
        try (Source source = new Source()) {
            source.call(lateRolling.policy, 0); source.commit(source.call(lateRolling.policy, 2)); source.call(lateRolling.policy, 3);
            assertThrows(IllegalArgumentException.class, () -> source.call(lateRolling.policy, 6));
            assertTrue(lateRolling.rolling.isEmpty());
        }
    }

    @Test void conditionalRepairDoesNotModifyAnAlreadyFeasibleOriginalPlan() {
        var rig = new Rig(0, REPAIR, 2, 200, 10, (o, t) -> { throw new AssertionError("No unnecessary repair prediction"); });
        try (Source source = new Source()) {
            for (int time = 0; time <= 5; time++) assertTrue(source.call(rig.policy, time).isEmpty());
            assertEquals(PreServiceRepairPolicy.Outcome.REPAIR_NOT_REQUIRED, rig.audits.getFirst().outcome());
            assertEquals(ORIGINAL, source.ledger.snapshot().commitments()); assertTrue(source.ledger.history().isEmpty());
            assertEquals(List.of(5.0), rig.rolling.stream().map(Decision::decisionTimeSeconds).toList());
        }
    }

    @Test void wrapperCannotBeReusedAcrossDaysButFreshObjectsStartPendingAgain() {
        var rig = new Rig();
        try (Source dayOne = new Source(20); Source dayTwo = new Source(21)) {
            dayOne.call(rig.policy, 0); dayOne.commit(dayOne.call(rig.policy, 2)); dayOne.call(rig.policy, 3);
            var next = dayTwo.at(0);
            assertThrows(IllegalArgumentException.class, () -> rig.policy.propose(next, dayTwo.ledger.snapshot()));
            var fresh = new Rig(); assertTrue(fresh.policy.propose(next, dayTwo.ledger.snapshot()).isEmpty());
            assertEquals(PreServiceRepairPolicy.Outcome.PENDING_REGISTERED_REPAIR, fresh.audits.getFirst().outcome());
            assertEquals(ORIGINAL, dayTwo.ledger.snapshot().commitments());
        }
    }

    @Test void sourceClockDisabledControllerAndReplayCannotBeHiddenByPendingState() {
        try (Source source = new Source()) {
            var rig = new Rig(); var observations = source.at(0); var current = source.ledger.snapshot();
            var disabled = new Snapshot(20, current.version(), 0, false, current.commitments(), Set.of(), Map.of());
            assertThrows(IllegalArgumentException.class, () -> rig.policy.propose(observations, disabled));
        }
        try (Source source = new Source()) {
            var rig = new Rig(); var observations = source.at(0); rig.policy.propose(observations, source.ledger.snapshot());
            assertThrows(IllegalArgumentException.class, () -> rig.policy.propose(observations, source.ledger.snapshot()));
        }
        try (Source source = new Source()) {
            var rig = new Rig(); var observations = source.at(0); source.ledger.advanceTo(1);
            assertThrows(IllegalArgumentException.class, () -> rig.policy.propose(observations, source.ledger.snapshot()));
        }
    }

    @Test void mapsAndAuditEvidenceAreDetachedAndImmutable() {
        var original = new LinkedHashMap<>(ORIGINAL); var repair = new LinkedHashMap<>(REPAIR);
        var checker = checker(10); var audits = new ArrayList<PreServiceRepairPolicy.Audit>();
        ForecastModel forecast = (o, t) -> prediction(50);
        var delegate = new RollingDeparturePolicy(new RollingDepartureCandidates(BASE, LIMITS, 200, 10, 50), checker,
                forecast, 2, 3, 10, 2, ignored -> {});
        var policy = new PreServiceRepairPolicy(original, LIMITS, checker, repair, 2, 200, 2, 3, forecast, delegate, 10, audits::add);
        original.clear(); repair.clear();
        try (Source source = new Source()) {
            source.call(policy, 0); var proposal = source.call(policy, 2); assertEquals(REPAIR, proposal);
            assertThrows(UnsupportedOperationException.class, proposal::clear);
            assertThrows(UnsupportedOperationException.class, () -> audits.getLast().proposedDepartures().clear());
            assertFalse(audits.getLast().actualExecutionVerified());
            assertEquals(DispatchConstraintChecker.EvidenceScope.REGISTERED_PLANNED_TIMES_ONLY, audits.getLast().evidenceScope());
        }
    }

    @Test void auditFailureAndCaughtReentrancyPoisonTheWrapper() {
        var checker = checker(10); ForecastModel model = (o, t) -> prediction(50);
        try (Source source = new Source()) {
            var policy = wrapper(checker, REPAIR, 2, 200, 10, model, value -> { throw new IllegalStateException("audit unavailable"); }, new ArrayList<>());
            assertThrows(IllegalStateException.class, () -> source.call(policy, 0));
            assertThrows(IllegalArgumentException.class, () -> source.call(policy, 1));
        }
        try (Source source = new Source()) {
            var observations = source.at(0); var current = source.ledger.snapshot();
            PreServiceRepairPolicy[] self = new PreServiceRepairPolicy[1];
            self[0] = wrapper(checker, REPAIR, 2, 200, 10, model, value -> {
                if (value.outcome() == PreServiceRepairPolicy.Outcome.PENDING_REGISTERED_REPAIR)
                    assertThrows(IllegalStateException.class, () -> self[0].propose(observations, current));
            }, new ArrayList<>());
            assertThrows(IllegalArgumentException.class, () -> self[0].propose(observations, current));
            assertThrows(IllegalArgumentException.class, () -> source.call(self[0], 1));
        }
    }

    @Test void incompleteRegistrationIdentityEntriesAndBadNumbersAreRejected() {
        ForecastModel forecast = (o, t) -> prediction(50);
        assertThrows(IllegalArgumentException.class, () -> new Rig(10, Map.of(), 2, 200, 10, forecast));
        assertThrows(IllegalArgumentException.class, () -> new Rig(10, Map.of(A, 100.0), 2, 200, 10, forecast));
        assertThrows(IllegalArgumentException.class, () -> new Rig(10, Map.of(new TripKey("unknown", "r", "d"), 90.0), 2, 200, 10, forecast));
        assertThrows(IllegalArgumentException.class, () -> new Rig(10, REPAIR, -1, 200, 10, forecast));
        assertThrows(IllegalArgumentException.class, () -> new Rig(10, REPAIR, 2, 0, 10, forecast));
        assertThrows(IllegalArgumentException.class, () -> new Rig(10, REPAIR, 2, 200, -1, forecast));
        assertThrows(IllegalArgumentException.class, () -> new Rig(10, REPAIR, Double.MAX_VALUE, 200, 10, forecast));
        var empty = new DispatchConstraintChecker(BASE, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> wrapper(empty, REPAIR, 2, 200, 10, forecast, ignored -> {}, new ArrayList<>()));
    }
}
