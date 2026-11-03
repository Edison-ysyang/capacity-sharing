package org.matsim.core.mobsim.qsim.pt;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonInitializedEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.GenericEvent;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.PrepareForSimUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.SimStepEventsRuntimeInspection;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.listeners.MobsimAfterSimStepListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.mobsim.qsim.QSimPreparationCheck;
import org.matsim.core.mobsim.qsim.QSimRuntimeInspection;
import org.matsim.core.mobsim.qsim.QSimStepBoundaryCheck;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngineI;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimRuntimeInspection;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.EventsToScore;
import org.matsim.core.scoring.functions.CharyparNagelScoringFunctionFactory;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.ClosedCoTransportObservationState;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.run.ismt.dynamic.DynamicMixedDesign;
import org.matsim.run.ismt.dynamic.DynamicMixedQSimModule;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;

/**
 * Public child-module wiring through real QSim preparation, closed observations,
 * native PT movement and EventsToScore. No fabricated vehicle/observer callbacks.
 * These are explicit-iteration micro runs, NOT a Controller learning experiment:
 * phase metadata and continued operating control do not prove StrategyManager
 * innovation activation/deactivation, convergence, or passenger protection.
 * The deliberately cargo-free fixture tests assembly, not mixed-freight benefits.
 * The original two tests retain seven synchronous one-runner QSim runs; the three
 * compatibility tests add nine real seven-runner/default-event-manager runs.
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class DynamicMixedQSimModuleNativeTest {
    private static final String LINE = "module-line";
    private static final String ROUTE = "module-route";
    private static final String ORIGIN = "module-origin";
    private static final String DESTINATION = "module-destination";
    private static final String PASSENGER = "module-passenger";
    private static final String FIRST_VEHICLE = "module-first-vehicle";
    private static final String SECOND_VEHICLE = "module-second-vehicle";
    private static final String LATE_EVENT = "module-native-after-source";
    private static final String LATE_SCORE = "module-native-after-score";
    private static final String PARALLEL_MANAGER = "org.matsim.core.events.SimStepParallelEventsManagerImpl";
    private static final TripKey FIRST = new TripKey(LINE, ROUTE, "module-first");
    private static final TripKey SECOND = new TripKey(LINE, ROUTE, "module-second");
    private static final DynamicMixedDesign.Protocol PROTOCOL =
            new DynamicMixedDesign.Protocol(0, 20, 150, 199, 170, 199);

    @Test
    void mixedOffZeroAndPreactivationUseNativeBypassWithoutConstructingAPolicy() {
        AtomicInteger forbiddenFactoryCalls = new AtomicInteger();
        DynamicMixedQSimModule.PolicyFactory forbidden = iteration -> {
            forbiddenFactoryCalls.incrementAndGet();
            throw new AssertionError("A bypass must not even construct a policy");
        };
        Run mixed = run(fixture(), design(DynamicMixedDesign.Arm.MIXED, false, 20), 20, forbidden);
        Run off = run(fixture(), design(DynamicMixedDesign.Arm.DYNAMIC_MIXED, false, 20), 20, forbidden);
        Run zero = run(fixture(), design(DynamicMixedDesign.Arm.DYNAMIC_MIXED, true, 0), 20, forbidden);
        Run preactivation = run(fixture(), design(DynamicMixedDesign.Arm.DYNAMIC_MIXED, true, 20), 19, forbidden);
        assertEquals(0, forbiddenFactoryCalls.get());
        for (Run candidate : List.of(mixed, off, zero, preactivation)) {
            assertEquals(mixed.events(), candidate.events(), "Compare every native event, not only aggregates");
            assertEquals(mixed.score(), candidate.score(), 0.0);
            var receipt = candidate.session().receipt();
            assertFalse(receipt.phase().controlEligible());
            assertEquals(0, receipt.policyCalls());
            assertEquals(0, receipt.controlledDrivers());
            assertEquals(2, receipt.nativeDrivers());
            assertEquals(0, receipt.committedChanges());
            assertTrue(candidate.session().applications().isEmpty());
            assertEquals(60, driverStart(candidate, SECOND_VEHICLE), 0.0);
            assertEquals(70, firstStopDeparture(candidate, SECOND_VEHICLE), 0.0);
        }
        assertFalse(preactivation.session().phase().freightTreatmentActive());
        assertFalse(preactivation.session().phase().passengerInnovationEnabled(),
                "The day receipt must retain the historical preactivation innovation-weight phase");
        assertEquals(19, preactivation.session().iteration(), "Explicit native IterationCounter reaches the module");
    }

    @Test
    void activeDaysStartFreshAndKeepOperatingAfterInnovationClosesWithoutResettingPlanScoreMemory() {
        Fixture fixture = fixture();
        var selected = fixture.person().getSelectedPlan();
        var alternative = fixture.alternative();
        double previousScore = selected.getScore();
        List<Integer> createdAt = new ArrayList<>();
        List<Run> runs = new ArrayList<>();
        List<ToyClosedPolicy> policies = new ArrayList<>();
        for (int iteration : List.of(149, 150, 170)) {
            assertSame(selected, fixture.person().getSelectedPlan());
            assertEquals(previousScore, selected.getScore(), 0.0);
            Run result = run(fixture, design(DynamicMixedDesign.Arm.DYNAMIC_MIXED, true, 20), iteration, day -> {
                createdAt.add(day);
                ToyClosedPolicy policy = new ToyClosedPolicy();
                policies.add(policy);
                return policy;
            });
            runs.add(result);
            var session = result.session();
            var receipt = session.receipt();
            var policy = policies.getLast();
            assertEquals(iteration, session.iteration());
            assertTrue(session.phase().controlEligible());
            assertEquals(iteration < 150, session.phase().passengerInnovationEnabled());
            assertEquals(iteration >= 170, session.phase().reporting());
            assertEquals(0, receipt.nativeDrivers());
            assertEquals(2, receipt.controlledDrivers());
            assertEquals(receipt.closedViewCount(), receipt.policyCalls());
            assertEquals(receipt.policyCalls(), policy.calls);
            assertTrue(policy.firstViewWasEmpty, "Each real QSim starts with unknown observations, not yesterday's state");
            assertEquals(result.events().stream().filter(event -> event.time() <= 0).count(),
                    policy.firstCompletedStepEvents,
                    "Native preparation events are retained but close only after the first real step");
            assertEquals(Map.of(FIRST, 20.0, SECOND, 60.0), session.initialCommitments().commitments());
            assertTrue(session.initialCommitments().startedAtSeconds().isEmpty());
            assertTrue(session.initialCommitments().lockedTrips().isEmpty());
            assertEquals(1, policy.proposals.size());
            var proposal = policy.proposals.getFirst();
            assertEquals(31, proposal.decisionTime(), 0.0);
            assertEquals(30, proposal.closedThrough(), 0.0);
            assertEquals(30, proposal.observedFirstDeparture(), 0.0);
            assertEquals(1, receipt.committedChanges());
            assertEquals(1, session.applications().size());
            var application = session.applications().getFirst();
            assertEquals(ControlledTransitDispatchBridge.Status.COMMITTED_APPLIED, application.status());
            assertEquals(Map.of(SECOND, 50.0), application.batch().proposedDepartures());
            assertEquals(proposal.decisionTime(), application.batch().decisionTimeSeconds(), 0.0);
            assertEquals(1, application.refreshedTrips().size());
            assertEquals(SECOND, application.refreshedTrips().getFirst().trip());
            assertEquals(1, application.requeuedDriverIds().size());
            assertTrue(application.constraints().orElseThrow().candidate().satisfiesRegisteredConstraints());
            assertEquals(50, session.snapshot().commitments().get(SECOND), 0.0);
            assertEquals(50, driverStart(result, SECOND_VEHICLE), 0.0);
            assertEquals(60, firstStopDeparture(result, SECOND_VEHICLE), 0.0);
            assertEquals(30, firstStopDeparture(result, FIRST_VEHICLE), 0.0);
            assertSame(selected, fixture.person().getSelectedPlan());
            assertSame(alternative, fixture.person().getPlans().get(1));
            assertEquals(31.25, alternative.getScore(), 0.0);
            assertEquals(previousScore, result.scoreAtPreparation(), 0.0,
                    "Prior native score survives to the next real beforePreparation; the module does not reset learning memory");
            assertEquals(result.score(), selected.getScore(), 0.0);
            previousScore = result.score();
        }
        assertEquals(List.of(149, 150, 170), createdAt);
        assertNotEquals(123.5, runs.getFirst().score(), "Real EventsToScore must update the initial score");
        assertEquals(runs.getFirst().events(), runs.get(1).events());
        assertEquals(runs.getFirst().events(), runs.get(2).events());
        assertNotSame(runs.get(0).session(), runs.get(1).session());
        assertNotSame(runs.get(1).session(), runs.get(2).session());
    }

    @Test
    void sevenRunnersWithTheOldDefaultEventManagerPreserveEveryPassiveEventAndScore() {
        AtomicInteger forbiddenCalls = new AtomicInteger();
        DynamicMixedQSimModule.PolicyFactory forbidden = iteration -> {
            forbiddenCalls.incrementAndGet();
            throw new AssertionError("Passive days must not construct a policy");
        };
        Run unbound = runUnboundParallel(fixture(7));
        Run mixed = run(fixture(7), design(DynamicMixedDesign.Arm.MIXED, false, 20), 20, forbidden, true);
        Run off = run(fixture(7), design(DynamicMixedDesign.Arm.DYNAMIC_MIXED, false, 20), 20, forbidden, true);
        Run zero = run(fixture(7), design(DynamicMixedDesign.Arm.DYNAMIC_MIXED, true, 0), 20, forbidden, true);
        Run pre19 = run(fixture(7), design(DynamicMixedDesign.Arm.DYNAMIC_MIXED, true, 20), 19, forbidden, true);
        assertEquals(0, forbiddenCalls.get());
        for (Run candidate : List.of(mixed, off, zero, pre19)) {
            assertEquals(unbound.events(), candidate.events(), "No replacement manager, event sorting, or aggregate-only comparison");
            assertEquals(unbound.score(), candidate.score(), 0.0);
            var receipt = candidate.session().receipt();
            assertEquals(7, receipt.qsimRunnerCount());
            assertEquals(PARALLEL_MANAGER, receipt.eventManagerClass());
            assertEquals(2, receipt.closureLagSteps());
            assertEquals(0, receipt.policyCalls());
            assertEquals(0, receipt.controlledDrivers());
            assertEquals(2, receipt.nativeDrivers());
            assertEquals(0, receipt.committedChanges());
            assertTrue(candidate.session().applications().isEmpty());
            assertEquals(60, driverStart(candidate, SECOND_VEHICLE), 0.0);
            assertLatePair(candidate.events());
        }
        assertFalse(pre19.session().phase().freightTreatmentActive());
    }

    @Test
    void sevenRunnerActiveDaysUseTwoStepClosedObservationsAndStartFreshAt149150And170() {
        Fixture fixture = fixture(7);
        double previousScore = fixture.selected().getScore();
        List<Run> runs = new ArrayList<>();
        List<Integer> createdAt = new ArrayList<>();
        for (int iteration : List.of(149, 150, 170)) {
            AtomicReference<ToyClosedPolicy> policyRef = new AtomicReference<>();
            Run result = run(fixture, design(DynamicMixedDesign.Arm.DYNAMIC_MIXED, true, 20), iteration, day -> {
                createdAt.add(day);
                var policy = new ToyClosedPolicy(2);
                policyRef.set(policy);
                return policy;
            }, true);
            runs.add(result);
            var policy = policyRef.get();
            var session = result.session();
            var receipt = session.receipt();
            assertEquals(7, receipt.qsimRunnerCount());
            assertEquals(PARALLEL_MANAGER, receipt.eventManagerClass());
            assertEquals(2, receipt.closureLagSteps());
            assertEquals(iteration < 150, session.phase().passengerInnovationEnabled());
            assertEquals(iteration >= 170, session.phase().reporting());
            assertTrue(session.phase().controlEligible());
            assertEquals(0, receipt.nativeDrivers());
            assertEquals(2, receipt.controlledDrivers());
            assertEquals(receipt.closedViewCount(), receipt.policyCalls());
            assertEquals(receipt.policyCalls(), policy.calls);
            assertEquals(Set.of(Thread.currentThread()), policy.callbackThreads,
                    "The event worker must not invoke policy; callbacks belong to the native QSim owner");
            assertTrue(policy.firstViewWasEmpty);
            assertTrue(policy.positions.get(0.0).closedThroughSeconds().isEmpty());
            assertTrue(policy.positions.get(1.0).closedThroughSeconds().isEmpty());
            assertEquals(result.events().stream().filter(event -> event.time() <= 0).count(), policy.firstCompletedStepEvents);
            assertEquals(Map.of(FIRST, 20.0, SECOND, 60.0), session.initialCommitments().commitments());
            assertTrue(session.initialCommitments().startedAtSeconds().isEmpty());
            assertTrue(session.initialCommitments().lockedTrips().isEmpty());
            assertEquals(29, policy.positions.get(31.0).closedThroughSeconds().orElseThrow(), 0.0);
            assertEquals(30, policy.positions.get(32.0).closedThroughSeconds().orElseThrow(), 0.0);
            assertEquals(1, policy.proposals.size());
            assertEquals(new ProposalEvidence(32, 30, 30), policy.proposals.getFirst());
            assertEquals(1, receipt.committedChanges());
            var application = session.applications().getFirst();
            assertEquals(ControlledTransitDispatchBridge.Status.COMMITTED_APPLIED, application.status());
            assertEquals(32, application.batch().decisionTimeSeconds(), 0.0);
            assertEquals(Map.of(SECOND, 50.0), application.batch().proposedDepartures());
            assertEquals(50, driverStart(result, SECOND_VEHICLE), 0.0);
            assertEquals(60, firstStopDeparture(result, SECOND_VEHICLE), 0.0);
            assertEquals(30, firstStopDeparture(result, FIRST_VEHICLE), 0.0);
            assertLatePair(result.events());
            for (var position : policy.positions.values()) {
                if (position.closedThroughSeconds().isEmpty()) {
                    assertEquals(0, position.closedEventCount());
                } else {
                    double cutoff = position.closedThroughSeconds().orElseThrow();
                    assertEquals(result.events().stream().filter(event -> event.time() <= cutoff).count(),
                            position.closedEventCount(), "Every policy prefix includes exactly its closed native events, including the late pair");
                }
            }
            assertSame(fixture.selected(), fixture.person().getSelectedPlan());
            assertSame(fixture.alternative(), fixture.person().getPlans().get(1));
            assertEquals(31.25, fixture.alternative().getScore(), 0.0);
            assertEquals(previousScore, result.scoreAtPreparation(), 0.0);
            previousScore = result.score();
        }
        assertEquals(List.of(149, 150, 170), createdAt);
        assertEquals(runs.getFirst().events(), runs.get(1).events());
        assertEquals(runs.getFirst().events(), runs.get(2).events());
        assertEquals(runs.getFirst().score(), runs.get(1).score(), 0.0);
        assertEquals(runs.getFirst().score(), runs.get(2).score(), 0.0);
        assertNotSame(runs.get(0).session(), runs.get(1).session());
        assertNotSame(runs.get(1).session(), runs.get(2).session());
    }

    @Test
    void nativeParallelRawViewsRetainAfterPairUntilTheFollowingDrainWithoutInventingAReceiptClock() {
        Fixture fixture = fixture(7);
        var events = EventsUtils.createEventsManager(fixture.scenario().getConfig());
        var trace = new ArrayList<NativeEvent>();
        events.addHandler((BasicEventHandler) event -> trace.add(nativeValue(event)));
        var probe = new RawBoundaryProbe();
        QSim qsim = new QSimBuilder(fixture.scenario().getConfig()).useDefaults().addQSimModule(new AbstractQSimModule() {
            @Override protected void configureQSim() {
                bind(QSimPreparationCheck.class).toInstance(probe);
                bind(QSimStepBoundaryCheck.class).toInstance(probe);
            }
        }).build(fixture.scenario(), events, 20);
        assertSame(events, qsim.getEventsManager(), "The 7-runner QSim retains the original default parallel manager");
        installLatePair(qsim);
        assertFalse(QSimRuntimeInspection.isAtEventsProcessed(qsim));
        assertFalse(QSimRuntimeInspection.isAtEventsFinished(qsim));
        qsim.run();
        assertEquals(1, probe.finished);
        assertFalse(probe.observer.closeReceipt().poisoned());
        assertFalse(probe.observer.closeReceipt().terminalPrefixCertified());
        assertFalse(SimStepEventsRuntimeInspection.inspect(events).parallelMode());
        assertLatePair(trace);
        var at31 = probe.views.stream().filter(view -> view.decisionTimeSeconds() == 31).findFirst().orElseThrow();
        var at32 = probe.views.stream().filter(view -> view.decisionTimeSeconds() == 32).findFirst().orElseThrow();
        assertEquals(29, at31.closedThroughSeconds().orElseThrow(), 0.0);
        assertEquals(30, at32.closedThroughSeconds().orElseThrow(), 0.0);
        assertTrue(probe.views.stream().filter(view -> view.decisionTimeSeconds() < 32)
                .flatMap(view -> view.newlyClosedEvents().stream()).noneMatch(DynamicMixedQSimModuleNativeTest::isLatePair));
        var pair = at32.newlyClosedEvents().stream().filter(DynamicMixedQSimModuleNativeTest::isLatePair).toList();
        assertEquals(2, pair.size());
        assertEquals(List.of(GenericEvent.class.getName(), PersonScoreEvent.class.getName()),
                pair.stream().map(NativeEventPrefixObserver.ReceivedEvent::eventClass).toList());
        for (var event : pair) {
            assertEquals(30, event.occurredAtSeconds(), 0.0);
            assertEquals(31, event.firstCoveredByDrain().orElseThrow().timeSeconds(), 0.0);
            assertEquals(31, event.availableBySeconds(), 0.0);
            assertThrows(IllegalStateException.class, event::receivedAtSeconds);
            assertFalse(event.receivedOnControlThread());
        }
        for (var view : probe.views) {
            assertEquals(2, view.evidence().closureLagSteps());
            assertEquals(7, view.evidence().actualRunnerCount().orElseThrow());
            assertFalse(view.evidence().allProducersCertified());
            if (view.closedThroughSeconds().isPresent()) {
                double cutoff = view.closedThroughSeconds().orElseThrow();
                assertEquals(trace.stream().filter(event -> event.time() <= cutoff).count(), view.closedEventCount());
            } else assertTrue(view.newlyClosedEvents().isEmpty());
        }
        // These deliberately invalid calls happen only after real completion, never during a live native run.
        assertFalse(QSimRuntimeInspection.isAtEventsProcessed(qsim));
        assertFalse(QSimRuntimeInspection.isAtEventsFinished(qsim));
        assertThrows(IllegalStateException.class, () -> probe.observer.afterNativeEventsProcessed(qsim, 30));
        assertThrows(IllegalStateException.class, () -> probe.observer.afterNativeEventsFinished(qsim));
        assertThrows(IllegalStateException.class, () -> NativeEventPrefixObserver.attachForPreparation(qsim, 20, 1, 0, ignored -> { }));
    }

    /** Policy inputs are two value snapshots; recorded thread identity is test-only and never affects a proposal. */
    private static final class ToyClosedPolicy implements DynamicMixedQSimModule.Policy {
        final int closureLagSteps;
        long calls;
        boolean firstViewWasEmpty;
        long firstCompletedStepEvents = -1;
        final List<ProposalEvidence> proposals = new ArrayList<>();
        final Map<Double, ClosedCoTransportObservationState.PrefixPosition> positions = new LinkedHashMap<>();
        final Set<Thread> callbackThreads = new LinkedHashSet<>();
        ToyClosedPolicy() { this(1); }
        ToyClosedPolicy(int closureLagSteps) { this.closureLagSteps = closureLagSteps; }
        @Override public Map<TripKey, Double> propose(ClosedCoTransportObservationState.Snapshot observed,
                                                     DispatchCommitmentState.Snapshot commitments) {
            callbackThreads.add(Thread.currentThread());
            positions.put(observed.position().decisionTimeSeconds(), observed.position());
            if (calls++ == 0) {
                firstViewWasEmpty = observed.position().closedThroughSeconds().isEmpty()
                        && observed.position().closedEventCount() == 0
                        && observed.services().observedServices().isEmpty()
                        && observed.releases().knownOrders().isEmpty()
                        && observed.riders().riders().isEmpty();
                assertEquals(60, commitments.commitments().get(SECOND), 0.0);
            }
            if (observed.position().decisionTimeSeconds() == closureLagSteps)
                firstCompletedStepEvents = observed.position().closedEventCount();
            var first = observed.services().observedServices().get(FIRST);
            if (first == null || first.motion() != ObservedTransitServiceState.Motion.BETWEEN_STOPS
                    || first.nextStopIndex() != 1 || !first.lastObservedStop().equals(Optional.of(ORIGIN))
                    || commitments.commitments().get(SECOND) != 60.0) return Map.of();
            assertFalse(observed.services().observedServices().containsKey(SECOND));
            double cutoff = observed.position().closedThroughSeconds().orElseThrow();
            assertTrue(first.lastObservedAtSeconds() <= cutoff);
            assertTrue(cutoff < observed.position().decisionTimeSeconds());
            proposals.add(new ProposalEvidence(observed.position().decisionTimeSeconds(), cutoff,
                    first.lastObservedAtSeconds()));
            return Map.of(SECOND, 50.0);
        }
    }

    private static Run runUnboundParallel(Fixture fixture) {
        var events = EventsUtils.createEventsManager(fixture.scenario().getConfig());
        assertEquals(PARALLEL_MANAGER, events.getClass().getName());
        var trace = new ArrayList<NativeEvent>();
        events.addHandler((BasicEventHandler) event -> trace.add(nativeValue(event)));
        var scorer = EventsToScore.createWithScoreUpdating(fixture.scenario(),
                new CharyparNagelScoringFunctionFactory(fixture.scenario()), events);
        QSim qsim = new QSimBuilder(fixture.scenario().getConfig()).useDefaults().build(fixture.scenario(), events, 20);
        assertSame(events, qsim.getEventsManager());
        installLatePair(qsim);
        double carried = fixture.selected().getScore();
        scorer.beginIteration(20, false);
        qsim.run();
        scorer.finish();
        fixture.assertUnchangedInputs();
        assertLatePair(trace);
        assertEquals(7, QNetsimRuntimeInspection.inspect(qsim.getChildInjector().getInstance(QNetsimEngineI.class))
                .actualRunnerCount().orElseThrow());
        double score = scorer.getAgentScore(fixture.person().getId());
        assertTrue(Double.isFinite(score));
        assertEquals(score, fixture.selected().getScore(), 0.0);
        return new Run(List.copyOf(trace), null, score, carried);
    }

    /**
     * Both same-source values are submitted by this real native After callback.
     * The Score is NOT derived asynchronously by an event-worker handler: that
     * different producer can enqueue an old-time Score behind the next step's
     * marker, which this manager rejects. No test wait/flush hides that limit.
     * Time 30 is well before cleanup; this does not claim terminal-tail closure.
     */
    private static void installLatePair(QSim qsim) {
        qsim.addQueueSimulationListeners((MobsimAfterSimStepListener) event -> {
            if (event.getSimulationTime() != 30) return;
            assertFalse(QSimRuntimeInspection.isAtEventsProcessed(qsim));
            assertFalse(SimStepEventsRuntimeInspection.isCurrentWorkerThread(qsim.getEventsManager()));
            var source = new GenericEvent(LATE_EVENT, event.getSimulationTime());
            qsim.getEventsManager().processEvent(source);
            qsim.getEventsManager().processEvent(new PersonScoreEvent(source.getTime(),
                    Id.createPersonId(PASSENGER), -3.0, LATE_SCORE));
        });
    }

    private static boolean isLatePair(NativeEventPrefixObserver.ReceivedEvent event) {
        return LATE_EVENT.equals(event.eventType()) || LATE_SCORE.equals(event.attributes().get("kind"));
    }

    private static void assertLatePair(List<NativeEvent> trace) {
        var pair = trace.stream().filter(event -> LATE_EVENT.equals(event.attributes().get("type"))
                || LATE_SCORE.equals(event.attributes().get("kind"))).toList();
        assertEquals(2, pair.size());
        assertEquals(List.of(GenericEvent.class.getName(), PersonScoreEvent.class.getName()),
                pair.stream().map(NativeEvent::eventClass).toList());
        assertTrue(pair.stream().allMatch(event -> event.time() == 30));
        assertEquals("-3.0", pair.getLast().attributes().get("amount"));
    }

    private static NativeEvent nativeValue(Event event) {
        return new NativeEvent(event.getClass().getName(), event.getTime(), Map.copyOf(event.getAttributes()));
    }

    /** Separate observer-only run: a single native hook owns drain/finish forwarding. */
    private static final class RawBoundaryProbe implements QSimPreparationCheck, QSimStepBoundaryCheck {
        final List<NativeEventPrefixObserver.StepView> views = new ArrayList<>();
        NativeEventPrefixObserver observer;
        int finished;
        @Override public void beforePreparation(QSim qsim) {
            assertTrue(QSimRuntimeInspection.isAtPreparationStart(qsim));
            assertTrue(qsim.getAgents().isEmpty());
            observer = NativeEventPrefixObserver.attachForPreparation(qsim, 20, 1, 0, view -> {
                assertTrue(QSimRuntimeInspection.isAtBeforeSimStep(qsim));
                assertFalse(SimStepEventsRuntimeInspection.isCurrentWorkerThread(qsim.getEventsManager()));
                views.add(view);
            });
        }
        @Override public void afterPrepared(QSim qsim) {
            assertTrue(QSimRuntimeInspection.isAtPreparationCheck(qsim));
            assertEquals(7, QNetsimRuntimeInspection.inspect(qsim.getChildInjector().getInstance(QNetsimEngineI.class))
                    .actualRunnerCount().orElseThrow());
            var manager = SimStepEventsRuntimeInspection.inspect(qsim.getEventsManager());
            assertEquals(1, manager.constructionWorkerCount());
            assertEquals(1, manager.materializedWorkerCount().orElseThrow());
        }
        @Override public void afterEventsProcessed(QSim qsim, double time) {
            assertTrue(QSimRuntimeInspection.isAtEventsProcessed(qsim));
            observer.afterNativeEventsProcessed(qsim, time);
        }
        @Override public void afterEventsFinished(QSim qsim) {
            assertTrue(QSimRuntimeInspection.isAtEventsFinished(qsim));
            observer.afterNativeEventsFinished(qsim);
            finished++;
        }
    }

    private static Run run(Fixture fixture, DynamicMixedDesign design, int iteration,
                           DynamicMixedQSimModule.PolicyFactory policyFactory) {
        return run(fixture, design, iteration, policyFactory, false);
    }

    private static Run run(Fixture fixture, DynamicMixedDesign design, int iteration,
                           DynamicMixedQSimModule.PolicyFactory policyFactory, boolean latePair) {
        var selected = fixture.person().getSelectedPlan();
        double carriedScore = selected.getScore();
        boolean parallel = fixture.scenario().getConfig().qsim().getNumberOfThreads() > 1;
        var events = parallel ? EventsUtils.createEventsManager(fixture.scenario().getConfig()) : EventsUtils.createEventsManager();
        List<NativeEvent> traceForPostRunOnly = new ArrayList<>();
        events.addHandler((BasicEventHandler) event -> traceForPostRunOnly.add(new NativeEvent(
                event.getClass().getName(), event.getTime(), Map.copyOf(event.getAttributes()))));
        var scoringFactory = new CharyparNagelScoringFunctionFactory(fixture.scenario());
        var scorer = EventsToScore.createWithScoreUpdating(fixture.scenario(), scoringFactory, events);
        AtomicReference<DynamicMixedQSimModule.Session> session = new AtomicReference<>();
        AtomicReference<Double> preparationScore = new AtomicReference<>();
        AtomicInteger inputsCalls = new AtomicInteger();
        var module = new DynamicMixedQSimModule(design, day -> {
            assertEquals(iteration, day);
            inputsCalls.incrementAndGet();
            return fixture.inputs();
        }, policyFactory, created -> {
            assertNull(session.getAndSet(created));
            assertSame(selected, fixture.person().getSelectedPlan());
            assertEquals(carriedScore, selected.getScore(), 0.0);
            preparationScore.set(selected.getScore());
            assertEquals(iteration, created.initialCommitments().iteration());
            assertEquals(Map.of(FIRST, 20.0, SECOND, 60.0), created.initialCommitments().commitments());
            // No fabricated Initialized/Before event is used to install or authenticate this session.
        });
        var qsim = new QSimBuilder(fixture.scenario().getConfig()).useDefaults()
                .addOverridingQSimModule(module).build(fixture.scenario(), events, iteration);
        assertSame(events, qsim.getEventsManager());
        if (latePair) installLatePair(qsim);
        scorer.beginIteration(iteration, false);
        qsim.run();
        scorer.finish();
        assertEquals(1, inputsCalls.get());
        var completed = session.get();
        assertNotNull(completed);
        var receipt = completed.receipt();
        assertEquals(iteration, receipt.iteration());
        assertEquals(fixture.scenario().getConfig().qsim().getNumberOfThreads(), receipt.qsimRunnerCount());
        assertEquals(events.getClass().getName(), receipt.eventManagerClass());
        assertEquals(parallel ? 2 : 1, receipt.closureLagSteps());
        assertTrue(receipt.preparedInputVerified());
        assertTrue(receipt.closedViewCount() > 90);
        assertFalse(receipt.poisoned());
        assertTrue(receipt.nativeEventsProcessingFinished());
        assertFalse(receipt.terminalPrefixCertified());
        assertFalse(receipt.registrationSealed());
        assertFalse(receipt.allProducersCertified());
        var input = completed.inputReport();
        assertTrue(input.verifiedSupportedInput());
        assertDoesNotThrow(input::requireSupportedInput);
        assertEquals(Set.of(PASSENGER), input.expectedPopulationIds());
        assertEquals(1, input.counts().validatedPlans());
        assertEquals(2, input.counts().transitDrivers());
        var chain = input.throughChainReport().orElseThrow();
        assertTrue(chain.verifiedNoThroughChains());
        assertDoesNotThrow(chain::requireNoThroughChains);
        assertFalse(input.preparationBarrierCertified(), "The checker value alone is not the native barrier evidence");
        assertTrue(personEvents(traceForPostRunOnly, PersonInitializedEvent.class.getName()) > 0,
                "Native preparation must actually emit the enabled initialization events");
        double closedThrough = completed.closedSnapshot().position().closedThroughSeconds().orElseThrow();
        assertEquals(traceForPostRunOnly.stream().filter(event -> event.time() <= closedThrough).count(),
                completed.closedSnapshot().position().closedEventCount(),
                "The closed native prefix includes preparation events without claiming terminal-tail coverage");
        assertTrue(traceForPostRunOnly.stream().noneMatch(event -> event.eventClass().equals(PersonStuckEvent.class.getName())));
        assertEquals(1, personEvents(traceForPostRunOnly, PersonEntersPtVehicleEvent.class.getName()));
        assertEquals(1, personEvents(traceForPostRunOnly, PersonLeavesPtVehicleEvent.class.getName()));
        assertTrue(completed.closedSnapshot().riders().eventAccountedOccupantsByVehicle().isEmpty());
        assertEquals(Set.of(FIRST, SECOND), completed.closedSnapshot().services().observedServices().keySet());
        fixture.assertUnchangedInputs();
        double nativeScore = scorer.getAgentScore(fixture.person().getId());
        assertTrue(Double.isFinite(nativeScore));
        assertEquals(nativeScore, selected.getScore(), 0.0);
        return new Run(List.copyOf(traceForPostRunOnly), completed, nativeScore, preparationScore.get());
    }

    private static long personEvents(List<NativeEvent> events, String type) {
        return events.stream().filter(event -> event.eventClass().equals(type)
                && PASSENGER.equals(event.attributes().get("person"))).count();
    }

    private static DynamicMixedDesign design(DynamicMixedDesign.Arm arm, boolean enabled, double amplitude) {
        return new DynamicMixedDesign(arm, PROTOCOL, enabled, new Limits(amplitude, amplitude, 5));
    }

    private static double driverStart(Run run, String vehicle) {
        return run.events().stream().filter(event -> event.eventClass().equals(TransitDriverStartsEvent.class.getName())
                        && vehicle.equals(event.attributes().get(TransitDriverStartsEvent.ATTRIBUTE_VEHICLE_ID)))
                .findFirst().orElseThrow().time();
    }

    private static double firstStopDeparture(Run run, String vehicle) {
        return run.events().stream().filter(event -> event.eventClass().equals(VehicleDepartsAtFacilityEvent.class.getName())
                        && vehicle.equals(event.attributes().get("vehicle")) && ORIGIN.equals(event.attributes().get("facility")))
                .findFirst().orElseThrow().time();
    }

    private static Fixture fixture() {
        return fixture(1);
    }

    private static Fixture fixture(int runners) {
        var config = ConfigUtils.createConfig();
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(runners);
        config.qsim().setPersonInitializedEventsSetting(QSimConfigGroup.PersonInitializedEventsSetting.all);
        config.transit().setUseTransit(true);
        config.transit().setUsingTransitInMobsim(true);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(120);
        config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-module-output")
                .toAbsolutePath().toString());
        config.scoring().setLearningRate(1);
        config.scoring().setPerforming_utils_hr(0);
        config.scoring().setMarginalUtlOfWaitingPt_utils_hr(-3600);
        config.scoring().setUtilityOfLineSwitch(0);
        for (String activity : List.of("home", "work")) config.scoring().addActivityParams(
                new ScoringConfigGroup.ActivityParams(activity).setScoringThisActivityAtAll(false));
        for (var mode : config.scoring().getModes().values()) mode.setMarginalUtilityOfTraveling(0)
                .setMarginalUtilityOfDistance(0).setMonetaryDistanceRate(0).setConstant(0)
                .setDailyUtilityConstant(0).setDailyMonetaryConstant(0);
        Scenario scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork();
        var a = NetworkUtils.createAndAddNode(network, Id.createNodeId("module-n0"), new Coord(0, 0));
        var b = NetworkUtils.createAndAddNode(network, Id.createNodeId("module-n1"), new Coord(100, 0));
        var c = NetworkUtils.createAndAddNode(network, Id.createNodeId("module-n2"), new Coord(200, 0));
        Link first = NetworkUtils.createAndAddLink(network, Id.createLinkId("module-l0"), a, b, 100, 10, 3600, 1);
        Link last = NetworkUtils.createAndAddLink(network, Id.createLinkId("module-l1"), b, c, 100, 10, 3600, 1);
        Link back = NetworkUtils.createAndAddLink(network, Id.createLinkId("module-return"), c, a, 200, 10, 3600, 1);
        for (Link link : List.of(first, last, back)) link.setAllowedModes(Set.of(TransportMode.car));
        var sf = scenario.getTransitSchedule().getFactory();
        var origin = sf.createTransitStopFacility(Id.create(ORIGIN, TransitStopFacility.class), first.getCoord(), false);
        var destination = sf.createTransitStopFacility(Id.create(DESTINATION, TransitStopFacility.class), last.getCoord(), false);
        origin.setLinkId(first.getId()); destination.setLinkId(last.getId());
        scenario.getTransitSchedule().addStopFacility(origin); scenario.getTransitSchedule().addStopFacility(destination);
        var firstCall = sf.createTransitRouteStop(origin, 0, 10);
        // Avoid racing two independent link workers at t=50: the first vehicle's
        // terminal departure previously coincided with the advanced second start.
        // Raw event-order equality is intentional in this bounded fixture; native
        // parallel same-time interleavings are NOT generally deterministic. Do not
        // sort observed events or change the engine to force that stronger claim.
        var lastCall = sf.createTransitRouteStop(destination, 20, 29);
        firstCall.setAwaitDepartureTime(true); lastCall.setAwaitDepartureTime(true);
        var path = RouteUtils.createLinkNetworkRouteImpl(first.getId(), last.getId());
        path.setLinkIds(first.getId(), List.of(), last.getId());
        var route = sf.createTransitRoute(Id.create(ROUTE, TransitRoute.class), path, List.of(firstCall, lastCall), TransportMode.pt);
        var line = sf.createTransitLine(Id.create(LINE, TransitLine.class));
        line.addRoute(route); scenario.getTransitSchedule().addTransitLine(line);
        var type = VehicleUtils.createVehicleType(Id.create("module-type", VehicleType.class));
        type.getCapacity().setSeats(2); type.getCapacity().setStandingRoom(0);
        type.setAccessTime(0); type.setEgressTime(0); type.setMaximumVelocity(10); type.setPcuEquivalents(1);
        scenario.getTransitVehicles().addVehicleType(type);
        List<ScheduledTrip> baseline = List.of(new ScheduledTrip(FIRST, FIRST_VEHICLE, 20),
                new ScheduledTrip(SECOND, SECOND_VEHICLE, 60));
        for (var trip : baseline) {
            var vehicle = VehicleUtils.createVehicle(Id.createVehicleId(trip.vehicleId()), type);
            scenario.getTransitVehicles().addVehicle(vehicle);
            var departure = sf.createDeparture(Id.create(trip.key().departure(), Departure.class), trip.baselineDepartureSeconds());
            departure.setVehicleId(vehicle.getId()); route.addDeparture(departure);
        }
        var pf = scenario.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId(PASSENGER));
        Plan selected = pf.createPlan();
        Activity home = pf.createActivityFromLinkId("home", first.getId()); home.setEndTime(15);
        selected.addActivity(home);
        Leg leg = pf.createLeg(TransportMode.pt); leg.setDepartureTime(15); leg.setTravelTime(35);
        var publishedRoute = new DefaultTransitPassengerRoute(origin, line, route, destination);
        publishedRoute.setBoardingTime(30); publishedRoute.setTravelTime(35); publishedRoute.setDistance(200);
        leg.setRoute(publishedRoute); selected.addLeg(leg);
        selected.addActivity(pf.createActivityFromLinkId("work", last.getId()));
        person.addPlan(selected); person.setSelectedPlan(selected); selected.setScore(123.5);
        Plan alternative = pf.createPlan(); alternative.addActivity(pf.createActivityFromLinkId("home", first.getId()));
        person.addPlan(alternative); alternative.setScore(31.25);
        scenario.getPopulation().addPerson(person);
        var controlPoint = new ControlPoint(new ControlPointKey(ORIGIN, "toy-outbound"),
                List.of(new Call(FIRST, 10), new Call(SECOND, 10)), 20, 60, Optional.empty());
        var inputs = new DynamicMixedQSimModule.Inputs(baseline, baseline.stream().map(trip ->
                new ObservedTransitServiceState.Service(trip.key(), trip.vehicleId(), List.of(ORIGIN, DESTINATION))).toList(),
                List.of(), Set.of(PASSENGER), Set.of(PASSENGER), List.of(controlPoint), List.of(), 0);
        PrepareForSimUtils.createDefaultPrepareForSim(scenario).run();
        return new Fixture(scenario, person, selected, alternative, route, publishedRoute, inputs);
    }

    private record Fixture(Scenario scenario, Person person, Plan selected, Plan alternative,
                           TransitRoute route, DefaultTransitPassengerRoute publishedRoute,
                           DynamicMixedQSimModule.Inputs inputs) {
        void assertUnchangedInputs() {
            assertSame(selected, person.getSelectedPlan());
            assertEquals(2, person.getPlans().size());
            assertSame(alternative, person.getPlans().get(1));
            assertSame(publishedRoute, ((Leg) selected.getPlanElements().get(1)).getRoute());
            assertEquals(30, publishedRoute.getBoardingTime().seconds(), 0.0);
            assertEquals(15, ((Activity) selected.getPlanElements().getFirst()).getEndTime().seconds(), 0.0);
            assertEquals(2, route.getDepartures().size());
            assertEquals(2, scenario.getTransitVehicles().getVehicles().size());
            for (var trip : inputs.baselineTrips()) {
                var departure = route.getDepartures().get(Id.create(trip.key().departure(), Departure.class));
                assertEquals(trip.baselineDepartureSeconds(), departure.getDepartureTime(), 0.0);
                assertEquals(trip.vehicleId(), departure.getVehicleId().toString());
                var capacity = scenario.getTransitVehicles().getVehicles().get(departure.getVehicleId()).getType().getCapacity();
                assertEquals(Integer.valueOf(2), capacity.getSeats());
                assertEquals(Integer.valueOf(0), capacity.getStandingRoom());
            }
        }
    }

    private record NativeEvent(String eventClass, double time, Map<String, String> attributes) { }
    private record ProposalEvidence(double decisionTime, double closedThrough, double observedFirstDeparture) { }
    private record Run(List<NativeEvent> events, DynamicMixedQSimModule.Session session, double score,
                       double scoreAtPreparation) { }
}
