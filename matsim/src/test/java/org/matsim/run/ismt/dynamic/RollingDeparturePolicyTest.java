package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;
import static org.matsim.run.ismt.dynamic.RollingDeparturePolicy.*;

/** Quantitative selection fixtures, not a physical demand/capacity predictor. */
class RollingDeparturePolicyTest {
    private static final TripKey A = new TripKey("line", "route", "A");
    private static final TripKey B = new TripKey("line", "route", "B");
    private static final Set<String> RELEASED = Set.of("u1", "u2");
    private static final List<ScheduledTrip> BASELINE = List.of(new ScheduledTrip(A, "v1", 100), new ScheduledTrip(B, "v2", 200));

    private static Snapshot current() {
        return new DispatchCommitmentState(BASELINE, new Limits(20, 20, 5)).beginIteration(20, 10, true);
    }

    private static RollingDeparturePolicy policy(double hmin, double passengerBound, int budget) {
        var candidates = new RollingDepartureCandidates(BASELINE, new Limits(20, 20, 5), 300, 10, 50);
        var checker = new DispatchConstraintChecker(BASELINE, List.of(new ControlPoint(new ControlPointKey("shared", "out"),
                List.of(new Call(A, 0), new Call(B, 0)), hmin, 200, Optional.empty())), List.of());
        return new RollingDeparturePolicy(candidates, checker, (observations, times) -> {
            throw new AssertionError("Unit selection seam must use its explicit oracle");
        }, 10, 60, passengerBound, budget, ignored -> {});
    }

    /** Real observer-bound values with manual callbacks; this fixture never calls QSim.run. */
    private static final class Source implements AutoCloseable {
        final QSim qsim;
        final NativeEventPrefixObserver observer;
        final ClosedCoTransportObservationState composition;
        final DispatchCommitmentState ledger = new DispatchCommitmentState(BASELINE, new Limits(20, 20, 5));
        Source() { this(20); }
        Source(int iteration) {
            EventsManager events = EventsUtils.createEventsManager();
            var config = ConfigUtils.createConfig(); config.qsim().setNumberOfThreads(1); config.global().setNumberOfThreads(1);
            config.qsim().setStartTime(0); config.qsim().setEndTime(20);
            config.controller().setOutputDirectory(Path.of(".build-cache", "rolling-policy-never-run").toAbsolutePath().toString());
            qsim = new QSimBuilder(config).useDefaults().build(ScenarioUtils.createScenario(config), events);
            composition = new ClosedCoTransportObservationState(iteration, 1,
                    List.of(new ObservedTransitServiceState.Service(A, "v1", List.of("s0", "s1")),
                            new ObservedTransitServiceState.Service(B, "v2", List.of("s0", "s1"))), List.of(), Set.of(), Map.of());
            observer = NativeEventPrefixObserver.attach(qsim, iteration, 1, 0, composition::accept);
            composition.bindTo(observer); qsim.getSimTimer().setTime(0);
            observer.notifyMobsimInitialized(new MobsimInitializedEvent<>(qsim)); ledger.beginIteration(iteration, 0, true);
        }
        ClosedCoTransportObservationState.Snapshot at(int time) {
            qsim.getSimTimer().setTime(time); ledger.advanceTo(time);
            observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(qsim, time));
            var value = composition.snapshot().orElseThrow();
            observer.notifyMobsimAfterSimStep(new MobsimAfterSimStepEvent<>(qsim, time)); return value;
        }
        @Override public void close() { observer.close(); }
    }

    @Test void publicPolicyUsesAnchoredCadenceWithoutReplayingMissingSlots() {
        try (Source source = new Source()) {
            List<Decision> decisions = new ArrayList<>();
            var checker = new DispatchConstraintChecker(BASELINE, List.of(new ControlPoint(new ControlPointKey("shared", "out"),
                    List.of(new Call(A, 0), new Call(B, 0)), 50, 200, Optional.empty())), List.of());
            var active = new RollingDeparturePolicy(new RollingDepartureCandidates(BASELINE, new Limits(20, 20, 5), 300, 10, 50),
                    checker, (observations, times) -> { throw new AssertionError("No released freight"); }, 2, 2, 10, 5, decisions::add);
            for (int time = 0; time <= 8; time++) {
                var observations = source.at(time);
                if (time != 4 && time != 5) assertTrue(active.propose(observations, source.ledger.snapshot()).isEmpty());
            }
            assertEquals(List.of(2.0, 6.0, 8.0), decisions.stream().map(Decision::decisionTimeSeconds).toList());
            assertTrue(decisions.stream().allMatch(decision -> decision.outcome() == Outcome.NO_RELEASED_FREIGHT));
        }
    }

    @Test void sharedDecisionWindowIncludesOpeningAndStopsWorkAtExclusiveCutoff() {
        try (Source source = new Source()) {
            List<Decision> decisions = new ArrayList<>();
            var checker = new DispatchConstraintChecker(BASELINE, List.of(new ControlPoint(new ControlPointKey("shared", "out"),
                    List.of(new Call(A, 0), new Call(B, 0)), 50, 200, Optional.empty())), List.of());
            var active = new RollingDeparturePolicy(new RollingDepartureCandidates(BASELINE, new Limits(20, 20, 5), 300, 10, 50),
                    checker, (observations, times) -> { throw new AssertionError("No released freight"); }, 2, 2, 10, 5,
                    decisions::add, new RollingDepartureCandidates.AdjustmentWindow(2, 6));
            for (int time = 0; time <= 10; time++)
                assertTrue(active.propose(source.at(time), source.ledger.snapshot()).isEmpty());
            assertEquals(List.of(2.0, 4.0), decisions.stream().map(Decision::decisionTimeSeconds).toList());
        }
    }

    @Test void publicPolicyRejectsReplayAndMismatchedObservationClock() {
        try (Source source = new Source()) {
            var observations = source.at(0); var active = policy(50, 10, 5);
            assertTrue(active.propose(observations, source.ledger.snapshot()).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> active.propose(observations, source.ledger.snapshot()));
        }
        try (Source source = new Source()) {
            var observations = source.at(0); source.ledger.advanceTo(1);
            assertThrows(IllegalArgumentException.class, () -> policy(50, 10, 5).propose(observations, source.ledger.snapshot()));
        }
    }

    @Test void aPolicyCannotCarrySearchClockAcrossDailyReset() {
        var active = policy(50, 10, 5);
        try (Source dayOne = new Source(20); Source dayTwo = new Source(21)) {
            assertTrue(active.propose(dayOne.at(0), dayOne.ledger.snapshot()).isEmpty());
            var next = dayTwo.at(0);
            var error = assertThrows(IllegalArgumentException.class, () -> active.propose(next, dayTwo.ledger.snapshot()));
            assertTrue(error.getMessage().contains("daily reset"));
            assertTrue(policy(50, 10, 5).propose(next, dayTwo.ledger.snapshot()).isEmpty());
        }
    }

    private static Forecast forecast(Map<TripKey, Double> times, double paxWait) {
        return forecast(new FreightCost(0, 0, times.get(A) + times.get(B)), paxWait);
    }
    private static Forecast forecast(FreightCost freight, double paxWait) {
        return Forecast.ready(new Projection("fixed-closed-demand", 500, RELEASED, freight,
                Map.of("fixed-affected-cohort", new PassengerCost(10, paxWait))));
    }

    @Test void searchesSeveralTripsAndReturnsOneAtomicDifference() {
        var result = policy(50, 10, 5).select(current(), "prefix", RELEASED, times -> forecast(times, 100));
        assertEquals(Outcome.PROPOSED, result.outcome());
        assertEquals(Map.of(A, 80.0, B, 180.0), result.proposedDepartures());
        assertEquals(2, result.improvingSweeps()); assertFalse(result.searchBudgetReached());
        assertEquals(260, result.selectedProjection().orElseThrow().freight().totalWaitingSeconds());
        assertEquals(Map.of(A, 100.0, B, 200.0), current().commitments());
    }

    @Test void allProtectionIsRelativeToDecisionStartNotPreviousSweep() {
        var result = policy(50, 10, 5).select(current(), "prefix", RELEASED,
                times -> forecast(times, 100 + .8 * (300 - times.get(A) - times.get(B))));
        assertEquals(1, result.proposedDepartures().size());
        assertEquals(290, result.selectedProjection().orElseThrow().freight().totalWaitingSeconds());
        assertEquals(108, result.selectedProjection().orElseThrow().affectedPassengers().get("fixed-affected-cohort").meanWaitingSeconds());
        assertTrue(result.trials().stream().anyMatch(trial -> trial.outcome() == TrialOutcome.PASSENGER_REJECTION));
    }

    @Test void protectsWeightedFixedCohortInsteadOfUnweightedMeanOfGroups() {
        var result = policy(50, 10, 5).select(current(), "prefix", RELEASED, times -> {
            double advance = 300 - times.get(A) - times.get(B);
            return Forecast.ready(new Projection("fixed", 500, RELEASED, new FreightCost(0, 0, 300 - advance),
                    Map.of("large", new PassengerCost(9, 100 + advance), "small", new PassengerCost(1, 100))));
        });
        assertEquals(290, result.selectedProjection().orElseThrow().freight().totalWaitingSeconds());
    }

    @Test void infeasibleCurrentStateIsNotCalledAFeasibleFallback() {
        AtomicInteger calls = new AtomicInteger();
        var error = assertThrows(IllegalStateException.class, () -> policy(120, 10, 5).select(current(), "prefix", RELEASED,
                times -> { calls.incrementAndGet(); return forecast(times, 100); }));
        assertTrue(error.getMessage().contains("no valid fallback")); assertEquals(0, calls.get());
    }

    @Test void checksConstraintsBeforeForecastAndCanImproveThroughFeasibleCoordinateSteps() {
        var result = policy(95, 10, 5).select(current(), "prefix", RELEASED, times -> {
            assertTrue(times.get(B) - times.get(A) >= 95, "An infeasible timetable reached the predictor");
            return forecast(times, 100);
        });
        assertEquals(Map.of(A, 80.0, B, 180.0), result.proposedDepartures());
        assertTrue(result.trials().stream().anyMatch(trial -> trial.outcome() == TrialOutcome.OPERATING_REJECTION));
    }

    @Test void noOrdersMeansNoFreightDrivenChangesOrForecastRead() {
        var result = policy(50, 10, 5).select(current(), "prefix", Set.of(), times -> { throw new AssertionError(); });
        assertEquals(Outcome.NO_RELEASED_FREIGHT, result.outcome()); assertTrue(result.proposedDepartures().isEmpty());
    }

    @Test void warmupOrUnsupportedReferenceDoesNotInventBenefits() {
        for (var status : List.of(ForecastStatus.INSUFFICIENT_HISTORY, ForecastStatus.UNSUPPORTED_STATE)) {
            var result = policy(50, 10, 5).select(current(), "prefix", RELEASED,
                    times -> new Forecast(status, "not-yet-predictable", Optional.empty()));
            assertEquals(Outcome.FORECAST_UNAVAILABLE, result.outcome()); assertTrue(result.proposedDepartures().isEmpty());
        }
    }

    @Test void unsupportedCandidateIsExcludedWithoutInventingZeroCost() {
        var result = policy(50, 10, 5).select(current(), "prefix", RELEASED, times -> {
            if (times.get(A) < 100 || times.get(B) < 200)
                return new Forecast(ForecastStatus.UNSUPPORTED_STATE, "unmodelled", Optional.empty());
            return forecast(times, 100);
        });
        assertEquals(Outcome.NO_IMPROVEMENT, result.outcome());
        assertTrue(result.trials().stream().anyMatch(trial -> trial.outcome() == TrialOutcome.FORECAST_UNAVAILABLE));
    }

    @Test void fixedDemandIdentityHorizonIdsAndWeightsCannotChangeDuringSearch() {
        var reference = forecast(current().commitments(), 100).projection().orElseThrow();
        List<Projection> bad = List.of(
                new Projection("different", 500, RELEASED, reference.freight(), reference.affectedPassengers()),
                new Projection(reference.demandProjectionId(), 501, RELEASED, reference.freight(), reference.affectedPassengers()),
                new Projection(reference.demandProjectionId(), 500, Set.of("u1"), reference.freight(), reference.affectedPassengers()),
                new Projection(reference.demandProjectionId(), 500, RELEASED, reference.freight(), Map.of()),
                new Projection(reference.demandProjectionId(), 500, RELEASED, reference.freight(),
                        Map.of("fixed-affected-cohort", new PassengerCost(9, 100))));
        for (Projection malformed : bad) {
            AtomicInteger calls = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> policy(50, 10, 5).select(current(), "prefix", RELEASED,
                    times -> Forecast.ready(calls.getAndIncrement() == 0 ? reference : malformed)));
        }
    }

    @Test void incompleteReferenceUnitCoverageAndExpiredHorizonAreRejected() {
        for (Projection malformed : List.of(
                new Projection("fixed", 500, Set.of("u1"), new FreightCost(0, 0, 2), Map.of()),
                new Projection("fixed", 10, RELEASED, new FreightCost(0, 0, 2), Map.of())))
            assertThrows(IllegalArgumentException.class, () -> policy(50, 10, 5).select(current(), "prefix", RELEASED,
                    times -> Forecast.ready(malformed)));
    }

    @Test void strictLexicographicFreightRankingPrefersLateCountBeforeLatenessAndWaiting() {
        var result = policy(50, 10, 1).select(current(), "prefix", RELEASED, times -> {
            if (times.get(A) == 80) return forecast(new FreightCost(0, 1000, 1000), 100);
            if (times.get(B) == 180) return forecast(new FreightCost(1, 0, 0), 100);
            return forecast(new FreightCost(2, 0, 0), 100);
        });
        assertEquals(Map.of(A, 80.0), result.proposedDepartures());
        assertTrue(result.searchBudgetReached());
    }

    @Test void latenessThenWaitingBreakTiesBeforeMovement() {
        var result = policy(50, 10, 1).select(current(), "prefix", RELEASED, times -> {
            if (times.get(A) == 80) return forecast(new FreightCost(1, 1, 1000), 100);
            if (times.get(B) == 180) return forecast(new FreightCost(1, 2, 0), 100);
            return forecast(new FreightCost(1, 3, 0), 100);
        });
        assertEquals(Map.of(A, 80.0), result.proposedDepartures());
        var waiting = policy(50, 10, 1).select(current(), "prefix", RELEASED, times ->
                forecast(new FreightCost(1, 1, times.get(A) == 80 ? 1 : 2), 100));
        assertEquals(Map.of(A, 80.0), waiting.proposedDepartures());
    }

    @Test void equalObjectivesRetainNoActionAndAvoidGratuitousMovement() {
        var result = policy(50, 10, 5).select(current(), "prefix", RELEASED,
                times -> forecast(new FreightCost(0, 0, 1), 100));
        assertEquals(Outcome.NO_IMPROVEMENT, result.outcome()); assertTrue(result.proposedDepartures().isEmpty());
    }

    private static Snapshot displaced(double a, double b) {
        var original = current();
        return new Snapshot(original.iteration(), original.version(), original.nowSeconds(), original.enabled(),
                Map.of(A, a, B, b), original.lockedTrips(), original.startedAtSeconds());
    }

    private static void assertSameSelection(Decision expected, Decision actual) {
        assertEquals(expected.outcome(), actual.outcome());
        assertEquals(expected.proposedDepartures(), actual.proposedDepartures());
        assertEquals(expected.noActionProjection(), actual.noActionProjection());
        assertEquals(expected.selectedProjection(), actual.selectedProjection());
        assertEquals(expected.improvingSweeps(), actual.improvingSweeps());
        assertEquals(expected.searchBudgetReached(), actual.searchBudgetReached());
    }

    @Test void genericForecastRemainsAFunctionAndDefaultsToNoDeliveredCostCapability() {
        ForecastModel ordinary = (observations, times) -> forecast(times, 100);
        var reference = forecast(current().commitments(), 100).projection().orElseThrow();
        assertTrue(ordinary.fixedObservedDeliveredFreightCost(null, reference).isEmpty());
        AtomicInteger calls = new AtomicInteger();
        var result = policy(50, 10, 1).select(current(), "prefix", RELEASED, times -> {
            calls.incrementAndGet(); return forecast(new FreightCost(1, 15, 20), 100);
        });
        assertTrue(calls.get() > 1);
        assertTrue(result.trials().stream().noneMatch(trial -> trial.outcome() == TrialOutcome.OBSERVED_DELIVERED_DOMINATED));
    }

    @Test void deliveredCostPruningPreservesNonzeroAccountsAndDoesNotPretendToProtectPassengers() {
        var fixed = new FreightCost(1, 15, 20); AtomicInteger calls = new AtomicInteger(), certifications = new AtomicInteger();
        var ordinary = policy(50, 10, 5).select(current(), "prefix", RELEASED, times -> forecast(fixed, 100));
        var pruned = policy(50, 10, 5).select(current(), "prefix", RELEASED, times -> {
            calls.incrementAndGet(); return forecast(fixed, 100);
        }, reference -> { certifications.incrementAndGet(); assertEquals(fixed, reference.freight()); return Optional.of(fixed); });
        assertSameSelection(ordinary, pruned); assertEquals(1, calls.get()); assertEquals(1, certifications.get());
        assertFalse(pruned.trials().isEmpty());
        for (var trial : pruned.trials()) {
            assertEquals(TrialOutcome.OBSERVED_DELIVERED_DOMINATED, trial.outcome());
            assertEquals(Optional.of(fixed), trial.freight()); assertTrue(trial.meanPassengerWaitingIncrementSeconds().isEmpty());
        }
    }

    @Test void equalShiftKeepsEarlierTieButSmallerShiftStillProjectsAcrossSeveralSweeps() {
        var fixed = new FreightCost(1, 15, 20); var initial = displaced(120, 220);
        AtomicInteger ordinaryCalls = new AtomicInteger(), prunedCalls = new AtomicInteger();
        var ordinary = policy(50, 10, 5).select(initial, "prefix", RELEASED,
                times -> { ordinaryCalls.incrementAndGet(); return forecast(fixed, 100); });
        var pruned = policy(50, 10, 5).select(initial, "prefix", RELEASED,
                times -> { prunedCalls.incrementAndGet(); return forecast(fixed, 100); }, reference -> Optional.of(reference.freight()));
        assertSameSelection(ordinary, pruned); assertEquals(Map.of(A, 100.0, B, 200.0), pruned.proposedDepartures());
        assertEquals(2, pruned.improvingSweeps()); assertTrue(prunedCalls.get() > 1 && prunedCalls.get() < ordinaryCalls.get());
        assertTrue(pruned.trials().stream().anyMatch(trial -> trial.sweep() == 0
                && trial.changesFromDecisionStart().equals(Map.of(B, 200.0))
                && trial.absoluteBaselineShiftSeconds() == 20 && trial.outcome() == TrialOutcome.OBSERVED_DELIVERED_DOMINATED));
    }

    @Test void smallerShiftCannotBypassPassengerProtectionAndProtectionStaysRelativeToOriginalReference() {
        var fixed = new FreightCost(1, 15, 20); var initial = displaced(120, 220);
        java.util.function.Function<Map<TripKey, Double>, Forecast> projection = times ->
                forecast(fixed, 100 + .8 * (340 - times.get(A) - times.get(B)));
        var ordinary = policy(50, 10, 5).select(initial, "prefix", RELEASED, projection);
        var pruned = policy(50, 10, 5).select(initial, "prefix", RELEASED, projection, reference -> Optional.of(reference.freight()));
        assertSameSelection(ordinary, pruned); assertEquals(Map.of(A, 110.0), pruned.proposedDepartures());
        assertEquals(108, pruned.selectedProjection().orElseThrow().affectedPassengers().get("fixed-affected-cohort").meanWaitingSeconds());
        assertTrue(pruned.trials().stream().anyMatch(trial -> trial.outcome() == TrialOutcome.PASSENGER_REJECTION));
    }

    @Test void constraintsStillRunBeforeDeliveredDominanceAndAnInfeasibleCurrentPlanStillFails() {
        var fixed = new FreightCost(1, 15, 20); AtomicInteger calls = new AtomicInteger();
        var result = policy(95, 10, 5).select(current(), "prefix", RELEASED,
                times -> { calls.incrementAndGet(); return forecast(fixed, 100); }, reference -> Optional.of(fixed));
        assertEquals(1, calls.get());
        assertTrue(result.trials().stream().anyMatch(trial -> trial.outcome() == TrialOutcome.OPERATING_REJECTION));
        assertTrue(result.trials().stream().anyMatch(trial -> trial.outcome() == TrialOutcome.OBSERVED_DELIVERED_DOMINATED));
        assertThrows(IllegalStateException.class, () -> policy(120, 10, 5).select(current(), "prefix", RELEASED,
                times -> { throw new AssertionError("infeasible current"); }, reference -> { throw new AssertionError("not ready"); }));
    }

    @Test void noReferenceOrNoReleasedUnitsCannotInvokeTheOptionalCertificate() {
        for (var status : List.of(ForecastStatus.INSUFFICIENT_HISTORY, ForecastStatus.UNSUPPORTED_STATE)) {
            var result = policy(50, 10, 5).select(current(), "prefix", RELEASED,
                    times -> new Forecast(status, "explicitly-unavailable", Optional.empty()),
                    reference -> { throw new AssertionError("An unavailable reference has no proof"); });
            assertEquals(Outcome.FORECAST_UNAVAILABLE, result.outcome());
        }
        var result = policy(50, 10, 5).select(current(), "prefix", Set.of(), times -> { throw new AssertionError(); },
                reference -> { throw new AssertionError(); });
        assertEquals(Outcome.NO_RELEASED_FREIGHT, result.outcome());
    }

    @Test void falseInvariantAndChangedFixedCohortOnImprovingCandidateFailExplicitly() {
        var fixed = new FreightCost(1, 15, 20); var initial = displaced(120, 200);
        assertThrows(IllegalArgumentException.class, () -> policy(50, 10, 5).select(initial, "prefix", RELEASED,
                times -> forecast(fixed, 100), reference -> Optional.of(new FreightCost(0, 0, 0))));
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> policy(50, 10, 5).select(initial, "prefix", RELEASED,
                times -> forecast(calls.getAndIncrement() == 0 ? fixed : new FreightCost(1, 15, 19), 100), reference -> Optional.of(fixed)));
        calls.set(0);
        assertThrows(IllegalArgumentException.class, () -> policy(50, 10, 5).select(initial, "prefix", RELEASED,
                times -> calls.getAndIncrement() == 0 ? forecast(fixed, 100) : Forecast.ready(
                        new Projection("different-cohort", 500, RELEASED, fixed, Map.of())), reference -> Optional.of(fixed)));
    }

    @Test void fixedCostDoesNotSuppressUnavailableForecastForAnOtherwiseImprovingCandidate() {
        var fixed = new FreightCost(1, 15, 20); var initial = displaced(120, 200);
        java.util.function.Function<Map<TripKey, Double>, Forecast> projection = times -> times.equals(initial.commitments())
                ? forecast(fixed, 100) : new Forecast(ForecastStatus.UNSUPPORTED_STATE, "not-evaluable", Optional.empty());
        var ordinary = policy(50, 10, 5).select(initial, "prefix", RELEASED, projection);
        var pruned = policy(50, 10, 5).select(initial, "prefix", RELEASED, projection, reference -> Optional.of(fixed));
        assertSameSelection(ordinary, pruned);
        assertTrue(pruned.trials().stream().anyMatch(trial -> trial.outcome() == TrialOutcome.FORECAST_UNAVAILABLE));
    }

    @Test void zeroAffectedMassIsOnlyRepresentedByAnExplicitEmptyCohort() {
        assertThrows(IllegalArgumentException.class, () -> new PassengerCost(0, 0));
        var result = policy(50, 0, 5).select(current(), "prefix", RELEASED, times ->
                Forecast.ready(new Projection("observed-empty", 500, RELEASED,
                        new FreightCost(0, 0, times.get(A) + times.get(B)), Map.of())));
        assertEquals(Map.of(A, 80.0, B, 180.0), result.proposedDepartures());
    }

    @Test void immutableEvidenceAndInvalidNumericPredictions() {
        var result = policy(50, 10, 5).select(current(), "prefix", RELEASED, times -> forecast(times, 100));
        assertThrows(UnsupportedOperationException.class, () -> result.proposedDepartures().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.trials().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.trials().getFirst().changesFromDecisionStart().clear());
        assertThrows(IllegalArgumentException.class, () -> new FreightCost(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FreightCost(0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new PassengerCost(1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Forecast(ForecastStatus.READY, "missing", Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> policy(50, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> policy(50, 10, 0));
    }
}
