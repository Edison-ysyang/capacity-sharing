package org.matsim.core.mobsim.qsim.pt;

import com.google.inject.multibindings.OptionalBinder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.cotransport.*;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.PrepareForSimUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.EventsToScore;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.CharyparNagelScoringFunctionFactory;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.run.ismt.dynamic.ClosedCoTransportObservationState;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.KnownFreightReleaseState;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.StepView;
import org.matsim.run.ismt.dynamic.ObservedTransitRiderState;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real two-person mixed-transit microfixtures, not a formal controller experiment.
 * One passenger and one registered freight unit share native capacity and the
 * existing boarding policy/monitor. Their prebuilt baseline-information routes
 * remain fixed: no within-day router or passenger replanning is installed.
 *
 * <p>EventsToScore reconstructs native experienced trips and uses the unmodified
 * Charypar-Nagel factory, including ScoreEventScoring. A zero-valued observer only
 * records the experienced inputs actually handed to native trip scoring. Synthetic
 * utilities isolate PT waiting and monitor score events; they are not calibrated
 * estimates. This checks one-day feedback, not subsequent iterative adaptation,
 * chain transfer, full-capacity denial, the formal common-history gate, or policy
 * feasibility. A separate passive closed-prefix observer checks source-bound
 * release/service/rider accounting against independent release/service folds;
 * native release and stop entry remain simultaneous in this fixture.
 * The only on-disk input is a tiny workspace-local order CSV.</p>
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDriverNativePassengerFeedbackTest {
    // AutoResetIdCaches resets MATSim's typed indices after each @Test. Keep
    // only names static; every fixture obtains current canonical typed IDs.
    private static final String PASSENGER = "feedback-passenger";
    private static final String FREIGHT = "feedback-cargo";
    private static final List<String> PERSONS = List.of(PASSENGER, FREIGHT);
    private static final TripKey FIRST = new TripKey("feedback-line", "feedback-route", "feedback-first");
    private static final TripKey SECOND = new TripKey("feedback-line", "feedback-route", "feedback-second");
    private static final double ARRIVE_AT_STOP = 95;
    private static final double OFFSET = 10;
    private static final double WAIT_UTILITY_PER_SECOND = -1;
    private enum Mode { MIXED_BASELINE, OFF, ZERO, ADVANCE }

    @Test
    void disabledAndZeroDynamicFactoriesMatchMixedNativeEventsAndExperiencedScores() throws IOException {
        Result mixed = run(Mode.MIXED_BASELINE);
        Result withoutObserver = run(Mode.MIXED_BASELINE, false);
        assertObserverIsInert(withoutObserver, mixed);
        assertCommon(withoutObserver);
        Result off = run(Mode.OFF);
        Result zero = run(Mode.ZERO);
        for (Result candidate : List.of(off, zero)) {
            assertEquals(mixed.trace.records, candidate.trace.records, "Complete native and runtime-monitor event records must agree");
            assertEquals(mixed.experienced, candidate.experienced, "Every experienced leg input delivered to scoring must agree");
            assertEquals(mixed.scores, candidate.scores);
            assertEquals(mixed.monitor.passengerExposureSnapshot(), candidate.monitor.passengerExposureSnapshot());
            assertEquals(freightReceipts(mixed), freightReceipts(candidate));
            assertEquals(mixed.monitor.runtimeCounts(), candidate.monitor.runtimeCounts());
            assertEquals(mixed.closedObservation.snapshots, candidate.closedObservation.snapshots);
            assertEquals(mixed.closedObservation.views.stream().map(StepView::closedPrefixSha256).toList(),
                    candidate.closedObservation.views.stream().map(StepView::closedPrefixSha256).toList());
            assertEquals(0, candidate.activeContextRequests);
            assertEquals(0, candidate.controllerReschedules);
            assertTrue(candidate.drivers.values().stream().allMatch(driver -> driver.getClass() == TransitDriverAgentImpl.class));
        }
        for (Result result : List.of(mixed, off, zero)) {
            assertCommon(result);
            for (String id : PERSONS) assertEquals(result.fixture.vehicles.get(FIRST).getId(), result.trace.boardings.get(id).getVehicleId());
        }
    }

    @Test
    void realAdvanceCanMissPassengersAndFreightAndFeedLongerWaitIntoNativeScoring() throws IOException {
        Result mixed = run(Mode.MIXED_BASELINE);
        Result advanced = run(Mode.ADVANCE);
        Result withoutObserver = run(Mode.ADVANCE, false);
        assertObserverIsInert(withoutObserver, advanced);
        assertCommon(withoutObserver);
        assertCommon(mixed);
        assertCommon(advanced);
        assertEquals(1, advanced.activeContextRequests);
        assertEquals(1, advanced.controllerReschedules);
        assertEquals(1, advanced.ledger.history().size());
        assertEquals(80, advanced.trace.starts.get(FIRST).getTime(), 0.0);
        assertEquals(90, advanced.trace.facilityDepartures.get(FIRST).getFirst().getTime(), 0.0);
        assertEquals(200, advanced.trace.starts.get(SECOND).getTime(), 0.0);
        assertEquals(210, advanced.trace.facilityDepartures.get(SECOND).getFirst().getTime(), 0.0);
        for (String id : PERSONS) {
            assertEquals(mixed.fixture.vehicles.get(FIRST).getId(), mixed.trace.boardings.get(id).getVehicleId());
            assertEquals(advanced.fixture.vehicles.get(SECOND).getId(), advanced.trace.boardings.get(id).getVehicleId());
            assertTrue(advanced.trace.facilityDepartures.get(FIRST).getFirst().getTime() < advanced.trace.waiting.get(id).getTime());
            ExperiencedLeg baseLeg = mixed.experienced.get(id).getFirst();
            ExperiencedLeg delayedLeg = advanced.experienced.get(id).getFirst();
            double extraWait = delayedLeg.waitSeconds() - baseLeg.waitSeconds();
            assertEquals(100, extraWait, 0.0, "Missing the advanced trip must create a full real headway of additional waiting");
            assertEquals(baseLeg.departure, delayedLeg.departure, 0.0);
            assertEquals(baseLeg.line, delayedLeg.line);
            assertEquals(baseLeg.route, delayedLeg.route);
            assertEquals(baseLeg.access, delayedLeg.access);
            assertEquals(baseLeg.egress, delayedLeg.egress);
            double scoreEventDifference = advanced.trace.scoreAmount(id) - mixed.trace.scoreAmount(id);
            assertEquals(WAIT_UTILITY_PER_SECOND * extraWait + scoreEventDifference,
                    advanced.scores.get(id) - mixed.scores.get(id), 1e-9,
                    "Native experienced waiting and actual monitor score events, not a test-generated penalty, explain the score change");
        }
        assertTrue(advanced.scores.get(PASSENGER) < mixed.scores.get(PASSENGER));
        assertEquals(0, mixed.monitor.freightOrderSnapshots(300).getFirst().outcome().latenessMinutes(), 0.0);
        assertTrue(advanced.monitor.freightOrderSnapshots(300).getFirst().outcome().latenessMinutes() > 0);
        assertTrue(advanced.trace.scoreEvents.stream().anyMatch(event -> event.getPersonId().toString().equals(FREIGHT)
                && event.getKind().equals("coTransportFreightLateWindow") && event.getAmount() < 0));
        assertEquals(mixed.planningBefore, advanced.planningBefore, "Every arm starts from the same immutable passenger information");
    }

    private static Result run(Mode mode) throws IOException {
        return run(mode, true);
    }

    private static Result run(Mode mode, boolean observeClosedPrefix) throws IOException {
        Fixture fixture = fixture();
        var events = EventsUtils.createEventsManager();
        var settings = CoTransportSettings.builder(fixture.orders)
                .armMode(CoTransportSettings.ArmMode.ALWAYS_ACTIVE).freightServiceWindow(0, 300)
                .freightShareCap(0.5).passengerLoadGate(1).comfortThreshold(0.5)
                .freightHandlingDwellSeconds(0).passengerPhysicalCrowdingPenaltyPerMinute(0.4)
                .passengerFreightCoPresencePenaltyPerMinute(0.6).freightLatePenaltyPerMinute(0.5).build();
        var registry = new FreightOrderRegistry(settings);
        registry.validateFixedPlans(fixture.scenario.getPopulation());
        assertCanonicalPersonIds(fixture);
        var phase = new CoTransportPhaseController(settings, registry, fixture.scenario, events,
                fixture.scenario.getConfig().controller(), new StrategyManager(), Map.of());
        assertTrue(phase.isFreightActive(), "Dynamic OFF is still mixed transport, not disabled freight");
        var policy = new CoTransportBoardingPolicy(settings, registry, phase, fixture.scenario);
        assertSame(policy, fixture.scenario.getScenarioElement(TransitBoardingPolicy.SCENARIO_ELEMENT));
        var monitor = new CoTransportRuntimeMonitor(settings, phase, registry, fixture.scenario, events);
        monitor.configureDetailedVehicleOutput(false);
        monitor.reset(20);
        events.addHandler(monitor);
        var ledger = new DispatchCommitmentState(List.of(
                new DispatchCommitmentState.ScheduledTrip(FIRST, fixture.vehicles.get(FIRST).getId().toString(), 100),
                new DispatchCommitmentState.ScheduledTrip(SECOND, fixture.vehicles.get(SECOND).getId().toString(), 200)),
                new DispatchCommitmentState.Limits(30, 30, 0));
        long epoch = ledger.beginIteration(20, 0, true).version().epoch();
        Result result = new Result(fixture, ledger, monitor);
        events.addHandler(result.trace);
        Map<TripKey, ControlledTransitDriverAgent.AcceptedCommitment> accepted = new LinkedHashMap<>();
        ControlledTransitDriverAgentFactory controlledFactory = mode == Mode.MIXED_BASELINE ? null
                : new ControlledTransitDriverAgentFactory(mode != Mode.OFF, 20, 20,
                mode == Mode.ZERO ? new DispatchCommitmentState.Limits(0, 0, 0) : new DispatchCommitmentState.Limits(30, 30, 0),
                () -> {
                    assertEquals(Mode.ADVANCE, mode, "OFF/zero must never touch active context");
                    result.activeContextRequests++;
                    return new ControlledTransitDriverAgentFactory.ActiveContext(key -> Optional.ofNullable(accepted.get(key)), 0, epoch);
                });
        TransitDriverAgentFactory factory = (umlauf, internal, tracker) -> {
            assertEquals(1, umlauf.getUmlaufStuecke().size());
            var work = umlauf.getUmlaufStuecke().getFirst();
            TripKey key = new TripKey(work.getLine().getId().toString(), work.getRoute().getId().toString(), work.getDeparture().getId().toString());
            var driver = controlledFactory == null ? new DefaultTransitDriverAgentFactory().createTransitDriver(umlauf, internal, tracker)
                    : controlledFactory.createTransitDriver(umlauf, internal, tracker);
            assertSame(driver.getId(), Id.get(driver.getId().index(), Person.class));
            for (Id<Person> personId : fixture.personIds.values()) assertNotEquals(personId.index(), driver.getId().index(),
                    "Current native driver and real person must never alias an IdMap slot");
            for (var preceding : result.drivers.values()) assertNotEquals(preceding.getId().index(), driver.getId().index(),
                    "The two original drivers must also occupy distinct current typed-index slots");
            assertNull(result.drivers.put(key, driver));
            return driver;
        };
        PrepareForSimUtils.createDefaultPrepareForSim(fixture.scenario).run();
        result.planningBefore = planningSnapshot(fixture);
        result.supplyBefore = supplySnapshot(fixture);
        QSim qsim = new QSimBuilder(fixture.scenario.getConfig()).useDefaults().addQSimModule(new AbstractQSimModule() {
            @Override protected void configureQSim() {
                OptionalBinder.newOptionalBinder(binder(), TransitDriverAgentFactory.class).setBinding().toInstance(factory);
            }
        }).build(fixture.scenario, events);
        result.qsim = qsim;
        var nativeScoringFactory = new CharyparNagelScoringFunctionFactory(fixture.scenario);
        EventsToScore scorer = EventsToScore.createWithScoreUpdating(fixture.scenario, person -> {
            var nativeFunction = (SumScoringFunction) nativeScoringFactory.createNewScoringFunction(person);
            nativeFunction.addScoringFunction(new SumScoringFunction.TripScoring() {
                @Override public void handleTrip(TripStructureUtils.Trip trip) {
                    for (Leg leg : trip.getLegsOnly()) {
                        assertEquals(TransportMode.pt, leg.getMode());
                        assertInstanceOf(DefaultTransitPassengerRoute.class, leg.getRoute());
                        var route = (DefaultTransitPassengerRoute) leg.getRoute();
                        result.experienced.computeIfAbsent(person.getId().toString(), ignored -> new ArrayList<>()).add(new ExperiencedLeg(
                                leg.getMode(), leg.getDepartureTime().seconds(), leg.getTravelTime().seconds(), route.getBoardingTime().seconds(),
                                route.getDistance(), route.getLineId().toString(), route.getRouteId().toString(),
                                route.getAccessStopId().toString(), route.getEgressStopId().toString()));
                    }
                }
                @Override public void finish() { }
                @Override public double getScore() { return 0; }
            });
            return nativeFunction;
        }, events);
        qsim.addQueueSimulationListeners((MobsimBeforeSimStepListener) event -> {
            if (event.getSimulationTime() == 20) {
                result.decisionVisits++;
                assertCanonicalPersonIds(fixture);
                assertEquals(2, result.drivers.size());
                assertTrue(result.trace.waiting.isEmpty());
                if (mode == Mode.ADVANCE) {
                    var decision = ledger.commit(new DispatchCommitmentState.Batch(ledger.snapshot().version(), 20, List.of(), Map.of(FIRST, 80.0)));
                    assertEquals(1, decision.changes().size());
                    accepted.put(FIRST, new ControlledTransitDriverAgent.AcceptedCommitment(80, decision.resultingVersion()));
                    var driver = (ControlledTransitDriverAgent) result.drivers.get(FIRST);
                    var refresh = driver.refreshAcceptedCommitment(FIRST, 20);
                    assertTrue(refresh.requiresReschedule());
                    assertEquals(100, refresh.previousDeadlineSeconds(), 0.0);
                    assertEquals(80, refresh.effectiveDeadlineSeconds(), 0.0);
                    qsim.rescheduleActivityEnd(driver);
                    result.controllerReschedules++;
                }
                assertEquals(result.planningBefore, planningSnapshot(fixture));
                assertEquals(result.supplyBefore, supplySnapshot(fixture));
            }
            if (event.getSimulationTime() == 280) {
                result.terminalVisits++;
                assertEquals(2, result.trace.personArrivals.size());
                assertEquals(0, qsim.getAgentCounter().getLiving());
                assertEquals(0, qsim.getAgentCounter().getLost());
            }
        });
        // Observation is passive: the existing decision listener, path information,
        // native execution and scoring remain untouched. Contracts are assembled
        // explicitly here, never exposed by iterating the whole-day order registry.
        NativeEventPrefixObserver observer = null;
        if (observeClosedPrefix) {
            result.closedObservation = new ClosedObservation(fixture, epoch);
            observer = NativeEventPrefixObserver.attach(qsim, 20, epoch, 0, result.closedObservation::accept);
            result.closedObservation.combined.bindTo(observer);
            result.preparedInspection = new NativePreparedInputInspection(qsim, false, true);
            qsim.addQueueSimulationListeners(result.preparedInspection);
        }
        scorer.beginIteration(20, false);
        try {
            qsim.run();
        } finally {
            if (observer != null) observer.close();
        }
        if (observer != null) {
            assertFalse(observer.isPoisoned());
            result.observerCloseReceipt = observer.closeReceipt();
            assertFalse(result.observerCloseReceipt.terminalPrefixCertified());
            result.preparedInspection.assertCompleted(result.closedObservation.views.size());
        }
        scorer.finish();
        PERSONS.forEach(id -> {
            Id<Person> personId = fixture.personIds.get(id);
            result.scores.put(id, scorer.getAgentScore(personId));
            assertEquals(scorer.getAgentScore(personId), fixture.scenario.getPopulation().getPersons().get(personId).getSelectedPlan().getScore());
        });
        return result;
    }

    private static void assertObserverIsInert(Result withoutObserver, Result observed) {
        assertNull(withoutObserver.closedObservation);
        assertNull(withoutObserver.preparedInspection);
        assertNotNull(observed.closedObservation);
        assertNotNull(observed.preparedInspection);
        assertEquals(withoutObserver.trace.records, observed.trace.records,
                "Passive closed-prefix mapping must leave the complete native and monitor event sequence unchanged");
        assertEquals(withoutObserver.experienced, observed.experienced);
        assertEquals(withoutObserver.scores, observed.scores);
        assertEquals(withoutObserver.monitor.passengerExposureSnapshot(), observed.monitor.passengerExposureSnapshot());
        assertEquals(freightReceipts(withoutObserver), freightReceipts(observed));
        assertEquals(withoutObserver.monitor.runtimeCounts(), observed.monitor.runtimeCounts());
        assertEquals(withoutObserver.activeContextRequests, observed.activeContextRequests);
        assertEquals(withoutObserver.controllerReschedules, observed.controllerReschedules);
        assertEquals(withoutObserver.planningBefore, observed.planningBefore);
        assertEquals(withoutObserver.supplyBefore, observed.supplyBefore);
    }

    private static void assertCommon(Result result) {
        assertEquals(1, result.decisionVisits);
        assertEquals(1, result.terminalVisits);
        assertEquals(2, result.fixture.scenario.getPopulation().getPersons().size());
        assertEquals(2, result.trace.starts.size());
        assertEquals(2, result.trace.boardings.size());
        assertEquals(2, result.trace.alightings.size());
        assertEquals(2, result.trace.waiting.size());
        assertEquals(2, result.experienced.size());
        assertEquals(2, result.qsim.getVehicles().size());
        assertEquals(0, result.qsim.getAgentCounter().getLiving());
        assertEquals(0, result.qsim.getAgentCounter().getLost());
        for (TripKey key : List.of(FIRST, SECOND)) {
            assertEquals(2, result.trace.facilityDepartures.get(key).size());
            var vehicle = result.qsim.getVehicles().get(result.fixture.vehicles.get(key).getId());
            assertSame(result.fixture.vehicles.get(key), vehicle.getVehicle());
            assertTrue(vehicle.getPassengers().isEmpty());
            assertNull(vehicle.getDriver(), "Native terminal lifecycle detaches the completed driver");
            assertEquals(Double.POSITIVE_INFINITY, result.drivers.get(key).getActivityEndTime());
        }
        assertEquals(0, result.trace.stuckEvents);
        assertEquals(0, result.trace.deniedEvents, "Missing an already-departed vehicle is not fabricated as a denied boarding");
        assertEquals(1, result.sharedNativeOccupancyChecks, "Exactly one real departure must show passenger and freight together in the native vehicle");
        assertEquals(result.trace.boardings.get(PASSENGER).getVehicleId(), result.trace.boardings.get(FREIGHT).getVehicleId());
        assertEquals(result.planningBefore, planningSnapshot(result.fixture));
        assertEquals(result.supplyBefore, supplySnapshot(result.fixture));
        for (String id : PERSONS) {
            assertEquals(1, result.experienced.get(id).size());
            ExperiencedLeg experienced = result.experienced.get(id).getFirst();
            assertEquals(ARRIVE_AT_STOP, result.trace.waiting.get(id).getTime(), 0.0);
            assertEquals(result.trace.personDepartures.get(id).getTime(), experienced.departure, 0.0);
            assertEquals(result.trace.boardings.get(id).getTime(), experienced.boarding, 0.0);
            assertEquals(result.trace.personArrivals.get(id).getTime() - experienced.departure, experienced.travelTime, 0.0);
            assertEquals(result.trace.boardings.get(id).getTime() - result.trace.waiting.get(id).getTime(), experienced.waitSeconds(), 0.0);
            assertEquals(WAIT_UTILITY_PER_SECOND * experienced.waitSeconds() + result.trace.scoreAmount(id), result.scores.get(id), 1e-9,
                    "Actual native scoring must include experienced wait and each runtime-monitor score event exactly once");
            assertEquals(FIRST.line(), experienced.line);
            assertEquals(FIRST.route(), experienced.route);
        }
        var counts = result.monitor.runtimeCounts();
        assertTrue(counts.phaseActive());
        assertEquals(1, counts.passengerBoardings());
        assertEquals(1, counts.freightBoardings());
        assertEquals(0, counts.passengerDeniedBoardings());
        assertEquals(0, counts.freightDeniedBoardings());
        assertEquals(0, counts.freightUnitsStillOnboard());
        assertEquals(1, counts.maxLoadRatio(), 0.0);
        assertEquals(0.5, counts.maxFreightShareOfCapacity(), 0.0);
        var exposure = result.monitor.passengerExposureSnapshot();
        assertTrue(exposure.validEventState());
        assertTrue(exposure.allObservedRidesClosed());
        assertEquals(Set.of(PASSENGER), exposure.persons().keySet());
        assertTrue(exposure.appliedPenalties().physical() > 0);
        assertTrue(exposure.appliedPenalties().freightCoPresence() > 0);
        assertEquals(-exposure.appliedPenalties().total(), result.trace.scoreAmount(PASSENGER), 1e-12);
        var order = result.monitor.freightOrderSnapshots(300).getFirst();
        assertEquals(1, order.boardingEvents());
        assertEquals(1, order.deliveryEvents());
        assertTrue(order.outcome().completed());
        assertEquals(result.trace.boardings.get(FREIGHT).getTime(), order.outcome().firstUnitBoardTime(), 0.0);
        assertEquals(result.trace.personArrivals.get(FREIGHT).getTime(), order.outcome().completionTime(), 0.0);
        if (result.closedObservation != null) assertClosedObservation(result);
    }

    private static void assertClosedObservation(Result result) {
        ClosedObservation observation = result.closedObservation;
        assertFalse(result.observerCloseReceipt.poisoned());
        assertFalse(result.observerCloseReceipt.terminalPrefixCertified());
        assertFalse(observation.combined.isPoisoned());
        assertTrue(observation.snapshots.getFirst().orders().isEmpty());
        assertTrue(observation.snapshots.getFirst().services().isEmpty());
        assertTrue(observation.snapshots.getFirst().riders().isEmpty());
        assertTrue(observation.snapshots.getFirst().eventAccountedOccupants().isEmpty());
        assertTrue(observation.snapshots.stream().filter(snapshot -> snapshot.time() <= ARRIVE_AT_STOP)
                .allMatch(snapshot -> snapshot.orders().isEmpty()), "A future contract is not a released order");
        ClosedFeedback releasedView = observation.snapshots.stream().filter(snapshot -> snapshot.time() == ARRIVE_AT_STOP + 1)
                .findFirst().orElseThrow();
        assertEquals(Set.of(FREIGHT), releasedView.orders().keySet());
        KnownFreightReleaseState.KnownOrder known = releasedView.orders().get(FREIGHT);
        assertEquals(1, known.contractedUnits());
        assertEquals(Map.of(FREIGHT, ARRIVE_AT_STOP), known.releasedUnits());
        assertEquals(180, known.deadlineSeconds(), 0.0);
        assertEquals(result.fixture.route.getStops().getFirst().getStopFacility().getId().toString(), known.originStopId());
        assertEquals(result.fixture.route.getStops().getLast().getStopFacility().getId().toString(), known.destinationStopId());
        ReceivedEvent release = observation.closedEvents.stream().filter(event ->
                        event.eventClass().equals(ActivityEndEvent.class.getName())
                                && FREIGHT.equals(event.attributes().get("person"))
                                && "cargo_origin".equals(event.attributes().get("actType")))
                .findFirst().orElseThrow();
        ReceivedEvent waiting = observation.closedEvents.stream().filter(event ->
                        event.eventClass().equals(AgentWaitingForPtEvent.class.getName())
                                && FREIGHT.equals(event.attributes().get("person")))
                .findFirst().orElseThrow();
        assertEquals(result.trace.waiting.get(FREIGHT).getTime(), waiting.occurredAtSeconds(), 0.0);
        assertEquals(ARRIVE_AT_STOP, release.occurredAtSeconds(), 0.0);
        assertEquals(release.occurredAtSeconds(), waiting.occurredAtSeconds(), 0.0);
        assertTrue(release.closedSequence() < waiting.closedSequence(), "Actual origin activity ends before the native stop-entry callback");
        // There is no access walk in this existing fixture: release and stop entry
        // occur at the SAME time and close in one view. This proves event ordering,
        // not a positive-duration released-but-not-at-stop control state.
        StepView source = observation.views.stream().filter(view -> view.decisionTimeSeconds() == ARRIVE_AT_STOP + 1)
                .findFirst().orElseThrow();
        assertTrue(source.newlyClosedEvents().contains(release));
        assertTrue(source.newlyClosedEvents().contains(waiting));
        assertEquals(known, observation.releases.get().knownOrders().get(FREIGHT));
        for (ClosedFeedback snapshot : observation.snapshots) {
            for (TripKey trip : List.of(FIRST, SECOND)) {
                double actualStart = result.trace.starts.get(trip).getTime();
                boolean startHasClosed = snapshot.closedThrough().isPresent()
                        && actualStart <= snapshot.closedThrough().getAsDouble();
                assertEquals(startHasClosed, snapshot.services().containsKey(trip),
                        "Only closed real starts make services visible; private timetable contracts do not");
            }
        }
        var finalServices = observation.services.get();
        assertEquals(Set.of(FIRST, SECOND), finalServices.observedServices().keySet());
        for (TripKey trip : List.of(FIRST, SECOND)) {
            var work = finalServices.observedServices().get(trip);
            assertEquals(ObservedTransitServiceState.Motion.ENDED, work.motion());
            assertEquals(2, work.nextStopIndex());
            assertTrue(work.atStop().isEmpty());
            assertEquals(Optional.of(known.destinationStopId()), work.lastObservedStop());
            assertEquals(result.trace.starts.get(trip).getTime(), work.startedAtSeconds(), 0.0);
            assertEquals(result.trace.starts.get(trip).getDriverId().toString(), work.driverId());
            assertEquals(work, finalServices.latestWorkByVehicle().get(work.vehicleId()));
            assertTrue(finalServices.hasObservedDriver(work.driverId()));
            assertFalse(work.active());
        }
        assertClosedRiders(result);
    }

    /** Post-run native trace is an oracle here, never input to a live observation or decision. */
    private static void assertClosedRiders(Result result) {
        ClosedObservation observation = result.closedObservation;
        var finalSnapshot = observation.combined.snapshot().orElseThrow();
        assertEquals(Set.copyOf(PERSONS), finalSnapshot.riders().riders().keySet());
        assertTrue(finalSnapshot.riders().eventAccountedOccupantsByVehicle().isEmpty());
        for (String person : PERSONS) {
            AgentWaitingForPtEvent waiting = result.trace.waiting.get(person);
            PersonEntersPtVehicleEvent board = assertInstanceOf(PersonEntersPtVehicleEvent.class, result.trace.boardings.get(person));
            PersonLeavesPtVehicleEvent alight = assertInstanceOf(PersonLeavesPtVehicleEvent.class, result.trace.alightings.get(person));
            ClosedFeedback waitingClosed = firstClosedViewOf(observation, waiting);
            ClosedFeedback boardingClosed = firstClosedViewOf(observation, board);
            ClosedFeedback alightingClosed = firstClosedViewOf(observation, alight);
            assertEquals(ObservedTransitRiderState.Phase.WAITING, waitingClosed.riders().get(person).phase());
            assertEquals(ObservedTransitRiderState.Phase.RIDING, boardingClosed.riders().get(person).phase());
            assertEquals(ObservedTransitRiderState.Phase.ALIGHTED, alightingClosed.riders().get(person).phase());
            String vehicle = board.getVehicleId().toString();
            TripKey boardedTrip = result.fixture.vehicles.entrySet().stream()
                    .filter(entry -> entry.getValue().getId().equals(board.getVehicleId()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            String origin = result.fixture.route.getStops().getFirst().getStopFacility().getId().toString();
            String destination = result.fixture.route.getStops().getLast().getStopFacility().getId().toString();
            for (ClosedFeedback snapshot : observation.snapshots) {
                boolean waitClosed = isClosed(snapshot, waiting.getTime());
                boolean boardClosed = isClosed(snapshot, board.getTime());
                boolean alightClosed = isClosed(snapshot, alight.getTime());
                var rider = snapshot.riders().get(person);
                assertEquals(waitClosed, rider != null, "Static actor identities alone must not publish a rider");
                if (!waitClosed) continue;
                assertEquals(person.equals(FREIGHT) ? ObservedTransitRiderState.Kind.FREIGHT_UNIT
                        : ObservedTransitRiderState.Kind.PASSENGER, rider.kind());
                assertEquals(waiting.getTime(), rider.firstObservedAtSeconds(), 0.0);
                assertEquals(Optional.of(destination), rider.destinationStopId());
                assertEquals(0, rider.nativeDeniedCount(), "A missed earlier departure is not a native denial");
                assertTrue(rider.pendingContinuation().isEmpty(), "This direct trip does not invent a continuation");
                ObservedTransitRiderState.Phase expected = alightClosed ? ObservedTransitRiderState.Phase.ALIGHTED
                        : boardClosed ? ObservedTransitRiderState.Phase.RIDING : ObservedTransitRiderState.Phase.WAITING;
                assertEquals(expected, rider.phase(), "A live event changes the published phase only once its source prefix closes");
                assertEquals(Optional.of(alightClosed ? destination : origin), rider.lastInteractionStopId());
                assertEquals(boardClosed && !alightClosed ? Optional.of(vehicle) : Optional.empty(), rider.vehicleId());
                assertEquals(boardClosed && !alightClosed ? Optional.of(boardedTrip) : Optional.empty(), rider.trip());
                assertEquals(boardClosed && !alightClosed,
                        snapshot.eventAccountedOccupants().getOrDefault(vehicle, Set.of()).contains(person));
            }
            var finalRider = finalSnapshot.riders().riders().get(person);
            assertEquals(ObservedTransitRiderState.Phase.ALIGHTED, finalRider.phase());
            assertEquals(result.trace.personArrivals.get(person).getTime(), finalRider.lastObservedAtSeconds(), 0.0);
            assertEquals(0, finalRider.nativeDeniedCount());
            assertTrue(finalRider.vehicleId().isEmpty());
            assertTrue(finalRider.trip().isEmpty());
        }
        assertEquals(result.trace.deniedEvents, finalSnapshot.riders().riders().values().stream()
                .mapToLong(ObservedTransitRiderState.RiderObservation::nativeDeniedCount).sum());
    }

    private static boolean isClosed(ClosedFeedback snapshot, double time) {
        return snapshot.closedThrough().isPresent() && time <= snapshot.closedThrough().getAsDouble();
    }

    private static ClosedFeedback firstClosedViewOf(ClosedObservation observation, Event source) {
        ReceivedEvent closed = observation.closedEvents.stream().filter(event -> event.eventClass().equals(source.getClass().getName())
                        && event.occurredAtSeconds() == source.getTime() && event.attributes().equals(source.getAttributes()))
                .findFirst().orElseThrow();
        for (int index = 0; index < observation.views.size(); index++) {
            if (!observation.views.get(index).newlyClosedEvents().contains(closed)) continue;
            ClosedFeedback snapshot = observation.snapshots.get(index);
            assertTrue(snapshot.time() > source.getTime(), "Current-step native events are not immediately published as closed");
            assertTrue(isClosed(snapshot, source.getTime()));
            if (index > 0) assertFalse(isClosed(observation.snapshots.get(index - 1), source.getTime()));
            return snapshot;
        }
        throw new AssertionError("Closed native source was not delivered in a view");
    }

    private static Fixture fixture() throws IOException {
        Path base = Path.of(".build-cache", "ismt-native-dispatch-micro", "passenger-feedback").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "fixture-");
        Path orders = directory.resolve("orders.csv");
        Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
                + "feedback-cargo,00:01:35,00:03:00,0.1,1.0,standard,1,1\n");
        var config = ConfigUtils.createConfig();
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1);
        config.transit().setUseTransit(true);
        config.transit().setUsingTransitInMobsim(true);
        config.vspExperimental().setGeneratingBoardingDeniedEvent(true);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(300);
        config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.controller().setFirstIteration(20);
        config.controller().setLastIteration(20);
        config.controller().setOutputDirectory(directory.resolve("unused-controller-output").toString());
        config.scoring().setLearningRate(1);
        config.scoring().setPerforming_utils_hr(0);
        config.scoring().setMarginalUtlOfWaitingPt_utils_hr(WAIT_UTILITY_PER_SECOND * 3600);
        config.scoring().setUtilityOfLineSwitch(0);
        for (String type : List.of("home", "work", "cargo_origin", "freight_delivery"))
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(type).setScoringThisActivityAtAll(false));
        for (var mode : config.scoring().getModes().values()) mode.setMarginalUtilityOfTraveling(0)
                .setMarginalUtilityOfDistance(0).setMonetaryDistanceRate(0).setConstant(0)
                .setDailyUtilityConstant(0).setDailyMonetaryConstant(0);
        Scenario scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork();
        Node n0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("feedback-n0"), new Coord(0, 0));
        Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("feedback-n1"), new Coord(100, 0));
        Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("feedback-n2"), new Coord(200, 0));
        Link first = NetworkUtils.createAndAddLink(network, Id.createLinkId("feedback-l0"), n0, n1, 100, 10, 3600, 1);
        Link last = NetworkUtils.createAndAddLink(network, Id.createLinkId("feedback-l1"), n1, n2, 100, 10, 3600, 1);
        Link returning = NetworkUtils.createAndAddLink(network, Id.createLinkId("feedback-return"), n2, n0, 200, 10, 3600, 1);
        for (Link link : List.of(first, last, returning)) link.setAllowedModes(Set.of(TransportMode.car));
        var sf = scenario.getTransitSchedule().getFactory();
        TransitStopFacility origin = sf.createTransitStopFacility(Id.create("feedback-origin", TransitStopFacility.class), first.getCoord(), false);
        TransitStopFacility destination = sf.createTransitStopFacility(Id.create("feedback-destination", TransitStopFacility.class), last.getCoord(), false);
        origin.setLinkId(first.getId());
        destination.setLinkId(last.getId());
        scenario.getTransitSchedule().addStopFacility(origin);
        scenario.getTransitSchedule().addStopFacility(destination);
        TransitRouteStop firstCall = sf.createTransitRouteStop(origin, 0, OFFSET);
        firstCall.setAwaitDepartureTime(true);
        TransitRouteStop lastCall = sf.createTransitRouteStop(destination, 40, 40);
        lastCall.setAwaitDepartureTime(true);
        var networkRoute = RouteUtils.createLinkNetworkRouteImpl(first.getId(), last.getId());
        networkRoute.setLinkIds(first.getId(), List.of(), last.getId());
        TransitRoute route = sf.createTransitRoute(Id.create(FIRST.route(), TransitRoute.class), networkRoute,
                List.of(firstCall, lastCall), TransportMode.pt);
        TransitLine line = sf.createTransitLine(Id.create(FIRST.line(), TransitLine.class));
        line.addRoute(route);
        scenario.getTransitSchedule().addTransitLine(line);
        VehicleType type = VehicleUtils.createVehicleType(Id.create("feedback-type", VehicleType.class));
        type.getCapacity().setSeats(2);
        type.getCapacity().setStandingRoom(0);
        type.setAccessTime(0);
        type.setEgressTime(0);
        type.setMaximumVelocity(10);
        type.setPcuEquivalents(1);
        scenario.getTransitVehicles().addVehicleType(type);
        Map<TripKey, Vehicle> vehicles = new LinkedHashMap<>();
        for (TripKey key : List.of(FIRST, SECOND)) {
            Vehicle vehicle = VehicleUtils.createVehicle(Id.createVehicleId(key.departure() + "-vehicle"), type);
            scenario.getTransitVehicles().addVehicle(vehicle);
            vehicles.put(key, vehicle);
            Departure departure = sf.createDeparture(Id.create(key.departure(), Departure.class), key.equals(FIRST) ? 100 : 200);
            departure.setVehicleId(vehicle.getId());
            route.addDeparture(departure);
        }
        Map<String, Id<Person>> personIds = new LinkedHashMap<>();
        for (String name : PERSONS) {
            Id<Person> id = Id.createPersonId(name);
            personIds.put(name, id);
            var pf = scenario.getPopulation().getFactory();
            Person person = pf.createPerson(id);
            Plan plan = pf.createPlan();
            Activity home = pf.createActivityFromLinkId(name.equals(FREIGHT) ? "cargo_origin" : "home", first.getId());
            home.setEndTime(ARRIVE_AT_STOP);
            plan.addActivity(home);
            Leg leg = pf.createLeg(TransportMode.pt);
            leg.setDepartureTime(ARRIVE_AT_STOP);
            leg.setTravelTime(45);
            var plannedRoute = new DefaultTransitPassengerRoute(origin, line, route, destination);
            plannedRoute.setBoardingTime(110); // Published baseline information, never the accepted/live commitment.
            plannedRoute.setTravelTime(45);
            plannedRoute.setDistance(200);
            leg.setRoute(plannedRoute);
            plan.addLeg(leg);
            plan.addActivity(pf.createActivityFromLinkId(name.equals(FREIGHT) ? "freight_delivery" : "work", last.getId()));
            person.addPlan(plan);
            person.setSelectedPlan(plan);
            if (name.equals(FREIGHT)) PopulationUtils.putSubpopulation(person, "freight");
            scenario.getPopulation().addPerson(person);
        }
        return new Fixture(scenario, orders, route, vehicles, Collections.unmodifiableMap(personIds));
    }

    private static void assertCanonicalPersonIds(Fixture fixture) {
        assertEquals(Set.copyOf(PERSONS), fixture.personIds.keySet());
        assertEquals(2, fixture.personIds.values().stream().map(Id::index).distinct().count());
        fixture.personIds.forEach((name, id) -> {
            assertSame(id, Id.get(name, Person.class), "Fixture must use this test epoch's canonical person ID");
            assertSame(id, Id.get(id.index(), Person.class), "Typed IdMap indices must refer to the same canonical object");
            assertSame(id, fixture.scenario.getPopulation().getPersons().get(id).getId());
        });
    }

    private static List<String> planningSnapshot(Fixture fixture) {
        List<String> rows = new ArrayList<>();
        for (String id : PERSONS) {
            Person person = fixture.scenario.getPopulation().getPersons().get(fixture.personIds.get(id));
            assertEquals(1, person.getPlans().size());
            rows.add(id + ":" + PopulationUtils.getSubpopulation(person));
            for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
                if (element instanceof Activity act) rows.add("act:" + act.getType() + ":" + act.getLinkId() + ":"
                        + act.getStartTime() + ":" + act.getEndTime() + ":" + act.getMaximumDuration());
                if (element instanceof Leg leg) rows.add("leg:" + leg.getMode() + ":" + leg.getDepartureTime() + ":"
                        + leg.getTravelTime() + ":" + leg.getRoute().getRouteDescription() + ":" + leg.getRoute().getDistance());
            }
        }
        return List.copyOf(rows);
    }

    private static List<FreightReceipt> freightReceipts(Result result) {
        // FreightOrder is an identity-valued domain object, so compare every
        // immutable field rather than unrelated order instances across runs.
        return result.monitor.freightOrderSnapshots(300).stream().map(snapshot -> {
            FreightOrder order = snapshot.order();
            return new FreightReceipt(order.orderId(), order.releaseTime(), order.deadlineTime(), order.volumeM3(), order.massKg(),
                    order.serviceClass(), order.priority(), order.capacityUnits(), snapshot.outcome(), snapshot.deniedBoardings(),
                    snapshot.boardingEvents(), snapshot.deliveryEvents());
        }).toList();
    }

    private static List<String> supplySnapshot(Fixture fixture) {
        List<String> rows = new ArrayList<>();
        rows.add(fixture.route.getId() + ":" + fixture.route.getTransportMode() + ":" + fixture.route.getRoute().getStartLinkId()
                + ":" + fixture.route.getRoute().getLinkIds() + ":" + fixture.route.getRoute().getEndLinkId());
        fixture.route.getStops().forEach(stop -> rows.add(stop.getStopFacility().getId() + ":" + stop.getStopFacility().getLinkId()
                + ":" + stop.getArrivalOffset() + ":" + stop.getDepartureOffset() + ":" + stop.isAwaitDepartureTime()));
        fixture.route.getDepartures().values().forEach(departure -> rows.add(departure.getId() + ":"
                + departure.getDepartureTime() + ":" + departure.getVehicleId()));
        fixture.scenario.getTransitVehicles().getVehicles().values().forEach(vehicle -> rows.add(vehicle.getId() + ":"
                + vehicle.getType().getId() + ":" + vehicle.getType().getCapacity().getSeats() + ":"
                + vehicle.getType().getCapacity().getStandingRoom()));
        return List.copyOf(rows);
    }

    private record Fixture(Scenario scenario, Path orders, TransitRoute route, Map<TripKey, Vehicle> vehicles,
                           Map<String, Id<Person>> personIds) { }
    private record NativeRecord(String type, double time, Map<String, String> attributes) { }
    private record FreightReceipt(String orderId, double release, double deadline, double volume, double mass, String serviceClass,
                                  int priority, int units, OrderOutcomeReconstructor.Outcome outcome, int denials, int boardings, int deliveries) { }
    private record ExperiencedLeg(String mode, double departure, double travelTime, double boarding, double distance,
                                  String line, String route, String access, String egress) {
        double waitSeconds() { return boarding - departure; }
    }

    /** Assembly inputs are private; published evidence contains only closed observations. */
    private static final class ClosedObservation {
        final KnownFreightReleaseState releaseMapper;
        final ObservedTransitServiceState serviceMapper;
        final ClosedCoTransportObservationState combined;
        final AtomicReference<KnownFreightReleaseState.State> releases;
        final AtomicReference<ObservedTransitServiceState.State> services;
        final List<StepView> views = new ArrayList<>();
        final List<ReceivedEvent> closedEvents = new ArrayList<>();
        final List<ClosedFeedback> snapshots = new ArrayList<>();

        ClosedObservation(Fixture fixture, long epoch) {
            Person freight = fixture.scenario.getPopulation().getPersons().get(fixture.personIds.get(FREIGHT));
            List<PlanElement> elements = freight.getSelectedPlan().getPlanElements();
            assertEquals(3, elements.size());
            Activity initial = assertInstanceOf(Activity.class, elements.getFirst());
            assertEquals("cargo_origin", initial.getType());
            assertEquals(ARRIVE_AT_STOP, initial.getEndTime().seconds(), 0.0);
            assertEquals(1, elements.stream().filter(element -> element instanceof Activity activity
                    && activity.getType().equals(initial.getType())).count(), "Contracted release activity is explicitly initial and unique");
            Leg leg = assertInstanceOf(Leg.class, elements.get(1));
            assertEquals(TransportMode.pt, leg.getMode());
            DefaultTransitPassengerRoute route = assertInstanceOf(DefaultTransitPassengerRoute.class, leg.getRoute());
            String originStop = route.getAccessStopId().toString();
            String destinationStop = route.getEgressStopId().toString();
            assertEquals(fixture.route.getStops().getFirst().getStopFacility().getId().toString(), originStop);
            assertEquals(fixture.route.getStops().getLast().getStopFacility().getId().toString(), destinationStop);
            var freightContracts = List.of(new KnownFreightReleaseState.Contract(
                    FREIGHT, originStop, destinationStop, initial.getEndTime().seconds(), 180,
                    List.of(FREIGHT), initial.getType()));
            releaseMapper = new KnownFreightReleaseState(freightContracts);
            releases = new AtomicReference<>(releaseMapper.initialState());
            var serviceContracts = List.of(FIRST, SECOND).stream().map(trip ->
                    new ObservedTransitServiceState.Service(trip, fixture.vehicles.get(trip).getId().toString(),
                            List.of(originStop, destinationStop))).toList();
            serviceMapper = new ObservedTransitServiceState(serviceContracts);
            services = new AtomicReference<>(serviceMapper.initialState());
            combined = new ClosedCoTransportObservationState(20, epoch, serviceContracts, freightContracts,
                    Set.of(PASSENGER), Map.of());
            assertTrue(releases.get().knownOrders().isEmpty());
            assertTrue(services.get().observedServices().isEmpty());
        }

        void accept(StepView view) {
            assertEquals(20, view.iteration());
            assertFalse(view.evidence().allProducersCertified());
            KnownFreightReleaseState.State beforeReleases = releases.get();
            ObservedTransitServiceState.State beforeServices = services.get();
            var priorOrders = Map.copyOf(beforeReleases.knownOrders());
            var priorServices = Map.copyOf(beforeServices.observedServices());
            KnownFreightReleaseState.State nextReleases = beforeReleases;
            for (ReceivedEvent event : view.newlyClosedEvents()) nextReleases = releaseMapper.apply(nextReleases, event);
            ObservedTransitServiceState.State nextServices = serviceMapper.applyAll(beforeServices, view.newlyClosedEvents());
            assertEquals(priorOrders, beforeReleases.knownOrders());
            assertEquals(priorServices, beforeServices.observedServices());
            // The source-bound composite consumes exactly the same closed delta.
            // Independent pure folds remain oracles, not a second live data source.
            var composed = combined.accept(view);
            assertEquals(nextReleases.knownOrders(), composed.releases().knownOrders());
            assertEquals(nextServices.observedServices(), composed.services().observedServices());
            assertEquals(nextServices.latestWorkByVehicle(), composed.services().latestWorkByVehicle());
            assertEquals(view.iteration(), composed.position().iteration());
            assertEquals(view.epoch(), composed.position().epoch());
            assertEquals(view.viewSequence(), composed.position().viewSequence());
            assertEquals(view.decisionTimeSeconds(), composed.position().decisionTimeSeconds(), 0.0);
            assertEquals(view.closedThroughSeconds(), composed.position().closedThroughSeconds());
            assertEquals(view.closedEventCount(), composed.position().closedEventCount());
            assertEquals(view.closedPrefixSha256(), composed.position().closedPrefixSha256());
            assertEquals(view.evidence(), composed.position().sourceEvidence());
            releases.set(nextReleases);
            services.set(nextServices);
            views.add(view);
            closedEvents.addAll(view.newlyClosedEvents());
            snapshots.add(new ClosedFeedback(view.decisionTimeSeconds(), view.closedThroughSeconds(),
                    nextReleases.knownOrders(), nextServices.observedServices(), composed.riders().riders(),
                    composed.riders().eventAccountedOccupantsByVehicle()));
        }
    }

    private record ClosedFeedback(double time, OptionalDouble closedThrough,
                                  Map<String, KnownFreightReleaseState.KnownOrder> orders,
                                  Map<TripKey, ObservedTransitServiceState.WorkObservation> services,
                                  Map<String, ObservedTransitRiderState.RiderObservation> riders,
                                  Map<String, Set<String>> eventAccountedOccupants) {
        private ClosedFeedback {
            orders = Map.copyOf(orders); services = Map.copyOf(services); riders = Map.copyOf(riders);
            var occupants = new LinkedHashMap<String, Set<String>>();
            eventAccountedOccupants.forEach((vehicle, people) -> occupants.put(vehicle, Set.copyOf(people)));
            eventAccountedOccupants = Map.copyOf(occupants);
        }
    }

    private static final class Result {
        final Fixture fixture;
        final DispatchCommitmentState ledger;
        final CoTransportRuntimeMonitor monitor;
        final Trace trace;
        final Map<TripKey, AbstractTransitDriverAgent> drivers = new LinkedHashMap<>();
        final Map<String, List<ExperiencedLeg>> experienced = new LinkedHashMap<>();
        final Map<String, Double> scores = new LinkedHashMap<>();
        QSim qsim;
        ClosedObservation closedObservation;
        NativePreparedInputInspection preparedInspection;
        NativeEventPrefixObserver.CloseReceipt observerCloseReceipt;
        List<String> planningBefore, supplyBefore;
        int activeContextRequests, controllerReschedules, decisionVisits, terminalVisits, sharedNativeOccupancyChecks;
        Result(Fixture fixture, DispatchCommitmentState ledger, CoTransportRuntimeMonitor monitor) {
            this.fixture = fixture; this.ledger = ledger; this.monitor = monitor; this.trace = new Trace(this);
        }
    }
    private static final class Trace implements BasicEventHandler {
        final Result result;
        final List<NativeRecord> records = new ArrayList<>();
        final Map<TripKey, TransitDriverStartsEvent> starts = new LinkedHashMap<>();
        final Map<TripKey, List<VehicleDepartsAtFacilityEvent>> facilityDepartures = new LinkedHashMap<>();
        final Map<String, AgentWaitingForPtEvent> waiting = new LinkedHashMap<>();
        final Map<String, PersonEntersVehicleEvent> boardings = new LinkedHashMap<>();
        final Map<String, PersonLeavesVehicleEvent> alightings = new LinkedHashMap<>();
        final Map<String, PersonDepartureEvent> personDepartures = new LinkedHashMap<>();
        final Map<String, PersonArrivalEvent> personArrivals = new LinkedHashMap<>();
        final List<PersonScoreEvent> scoreEvents = new ArrayList<>();
        int stuckEvents, deniedEvents;
        Trace(Result result) {
            this.result = result;
            facilityDepartures.put(FIRST, new ArrayList<>());
            facilityDepartures.put(SECOND, new ArrayList<>());
        }
        @Override public void handleEvent(Event event) {
            records.add(new NativeRecord(event.getClass().getName(), event.getTime(), Map.copyOf(event.getAttributes())));
            if (event instanceof PersonStuckEvent) stuckEvents++;
            if (event instanceof BoardingDeniedEvent) deniedEvents++;
            if (event instanceof TransitDriverStartsEvent start) {
                TripKey key = new TripKey(start.getTransitLineId().toString(), start.getTransitRouteId().toString(), start.getDepartureId().toString());
                assertNull(starts.put(key, start));
                result.ledger.recordStarted(key, start.getTime(), start.getTime());
            }
            if (event instanceof VehicleDepartsAtFacilityEvent departure) {
                TripKey key = result.fixture.vehicles.entrySet().stream().filter(entry -> entry.getValue().getId().equals(departure.getVehicleId()))
                        .map(Map.Entry::getKey).findFirst().orElseThrow();
                facilityDepartures.get(key).add(departure);
                var vehicle = result.qsim.getVehicles().get(departure.getVehicleId());
                if (!vehicle.getPassengers().isEmpty()) {
                    assertEquals(Set.copyOf(PERSONS), vehicle.getPassengers().stream().map(agent -> agent.getId().toString()).collect(java.util.stream.Collectors.toSet()));
                    assertEquals(2, vehicle.getPassengerCapacity());
                    assertSame(result.drivers.get(key), vehicle.getDriver());
                    result.sharedNativeOccupancyChecks++;
                }
            }
            if (event instanceof AgentWaitingForPtEvent wait && PERSONS.contains(wait.getPersonId().toString())) assertNull(waiting.put(wait.getPersonId().toString(), wait));
            if (event instanceof PersonEntersVehicleEvent board && PERSONS.contains(board.getPersonId().toString())) assertNull(boardings.put(board.getPersonId().toString(), board));
            if (event instanceof PersonLeavesVehicleEvent leave && PERSONS.contains(leave.getPersonId().toString())) assertNull(alightings.put(leave.getPersonId().toString(), leave));
            if (event instanceof PersonDepartureEvent departure && PERSONS.contains(departure.getPersonId().toString())) assertNull(personDepartures.put(departure.getPersonId().toString(), departure));
            if (event instanceof PersonArrivalEvent arrival && PERSONS.contains(arrival.getPersonId().toString())) assertNull(personArrivals.put(arrival.getPersonId().toString(), arrival));
            if (event instanceof PersonScoreEvent score && PERSONS.contains(score.getPersonId().toString())) scoreEvents.add(score);
        }
        double scoreAmount(String id) { return scoreEvents.stream().filter(event -> event.getPersonId().toString().equals(id)).mapToDouble(PersonScoreEvent::getAmount).sum(); }
    }
}
