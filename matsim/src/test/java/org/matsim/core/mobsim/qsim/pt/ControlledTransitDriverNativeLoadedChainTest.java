package org.matsim.core.mobsim.qsim.pt;

import com.google.inject.multibindings.OptionalBinder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonContinuesInVehicleEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.PrepareForSimUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.utils.TransitScheduleValidator;
import org.matsim.run.ismt.dynamic.ClosedCoTransportObservationState;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.KnownFreightReleaseState;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.StepView;
import org.matsim.run.ismt.dynamic.ObservedTransitRiderState;
import org.matsim.run.ismt.dynamic.ObservedTransitRiderState.Phase;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two loaded native ChainedDeparture microfixtures, each run with default native
 * drivers without/with the passive source-bound observation pipeline, and again
 * with empty-provider adapter drivers plus that pipeline. One passenger and one registered
 * freight unit follow one real chained PT leg. Default QSim engines and native
 * Umlauf reconstruction own stops, passenger relocation, driver release and queues.
 * No event, driver attachment, readiness callback, route switch or clock is forged.
 *
 * <p>The adapter has an empty commitment provider in these isolated fixtures; this
 * is not production OFF/zero wiring or a dispatch-policy feasibility gate. Capacity
 * is deliberately ample; no freight boarding policy/score/delivery-accounting claim
 * is made. CoTransportRuntimeMonitor is not attached: its continuation-aware freight
 * and exposure accounts are an independent, still-unverified production gate.
 * Native physical occupancy and event-accounted membership are checked
 * separately. The full trace is used only AFTER the run as an assertion oracle,
 * never supplied to an observation consumer or controller as future information.
 *
 * <p>Positive same-vehicle expectations deliberately exercise the native detached
 * driver lifecycle. Target line/route metadata must come from the registered chain,
 * not a detached source driver or an obsolete source route. A source end and its
 * continuation may close in the same delta: per-event audit verifies intermediate
 * retention, but does not claim every intermediate phase is a decision-time view.
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDriverNativeLoadedChainTest {
    private static final TripKey A = new TripKey("loaded-line-A", "loaded-route-A", "loaded-departure-A");
    private static final TripKey B = new TripKey("loaded-line-B", "loaded-route-B", "loaded-departure-B");
    private static final String PASSENGER = "loaded-passenger";
    private static final String FREIGHT = "loaded-freight-unit";
    private static final List<String> PERSONS = List.of(PASSENGER, FREIGHT);
    private static final double RELEASE = 1;
    private static final double END = 260;
    private static final long EPOCH = 1;

    @Test void sameVehicleLoadedChainPreservesRidersUntilRealSuccessorAndFinalAlighting() {
        Result plain = run(true, false, false);
        Result observed = run(true, true, false);
        Result adapted = run(true, true, true);
        assertPair(plain, observed);
        assertPair(plain, adapted);
        assertEquals(observed.views.stream().map(StepView::closedPrefixSha256).toList(), adapted.views.stream().map(StepView::closedPrefixSha256).toList());
        assertEquals(1, observed.drivers.size());
        assertEquals(observed.trace.starts.get(A).attributes().get("driverId"), observed.trace.starts.get(B).attributes().get("driverId"));
        assertTrue(observed.snapshots.stream().anyMatch(snapshot -> snapshot.riders().riders().values().stream()
                        .anyMatch(rider -> rider.phase() == Phase.PENDING_CONTINUATION)),
                "Same-vehicle baseline B is later, leaving an actual closed pending interval");
    }

    @Test void crossVehicleLoadedChainTransfersOnlyOnNativeContinuationAndEndsWithoutResidualRiders() {
        Result plain = run(false, false, false);
        Result observed = run(false, true, false);
        Result adapted = run(false, true, true);
        assertPair(plain, observed);
        assertPair(plain, adapted);
        assertEquals(observed.views.stream().map(StepView::closedPrefixSha256).toList(), adapted.views.stream().map(StepView::closedPrefixSha256).toList());
        assertEquals(2, observed.drivers.size());
        assertNotEquals(observed.trace.starts.get(A).attributes().get("driverId"), observed.trace.starts.get(B).attributes().get("driverId"));
        assertTrue(observed.trace.starts.get(B).time() >= observed.trace.ends.get(A).time(),
                "Only real predecessor completion releases the dependent native vehicle");
    }

    private static Result run(boolean sameVehicle, boolean observe, boolean adapter) {
        Fixture fixture = fixture(sameVehicle);
        Result result = new Result(fixture, observe, adapter);
        var events = EventsUtils.createEventsManager();
        events.addHandler(result.trace);
        TransitDriverAgentFactory factory = (umlauf, internal, tracker) -> {
            assertTrue(umlauf.getUmlaufStuecke().stream().allMatch(work -> work.isFahrt()),
                    "This contiguous-endpoint fixture needs no synthetic Wenden");
            var workKeys = umlauf.getUmlaufStuecke().stream().map(work -> new TripKey(work.getLine().getId().toString(),
                    work.getRoute().getId().toString(), work.getDeparture().getId().toString())).toList();
            if (sameVehicle) assertEquals(List.of(A, B), workKeys);
            else assertEquals(1, workKeys.size());
            AbstractTransitDriverAgent driver = adapter
                    ? new ControlledTransitDriverAgent(umlauf, TransportMode.car, tracker, internal, ignored -> Optional.empty(), 0, EPOCH)
                    : new DefaultTransitDriverAgentFactory().createTransitDriver(umlauf, internal, tracker);
            assertEquals(adapter ? ControlledTransitDriverAgent.class : TransitDriverAgentImpl.class, driver.getClass());
            assertNull(result.drivers.put(umlauf.getVehicleId().toString(), driver), "One native driver construction per vehicle");
            for (TripKey key : workKeys) {
                assertTrue(List.of(A, B).contains(key));
                assertEquals(fixture.services.get(key).vehicle().getId(), umlauf.getVehicleId());
                assertNull(result.workOwners.put(key, driver));
            }
            return driver;
        };
        PrepareForSimUtils.createDefaultPrepareForSim(fixture.scenario).run();
        result.preparedInput = inputSignature(fixture);
        QSim qsim = new QSimBuilder(fixture.scenario.getConfig()).useDefaults().addQSimModule(new AbstractQSimModule() {
            @Override protected void configureQSim() {
                OptionalBinder.newOptionalBinder(binder(), TransitDriverAgentFactory.class).setBinding().toInstance(factory);
            }
        }).build(fixture.scenario, events);
        result.qsim = qsim;
        qsim.addQueueSimulationListeners((MobsimBeforeSimStepListener) event -> {
            if (event.getSimulationTime() == 200 || event.getSimulationTime() == 240) {
                result.terminalChecks++;
                assertNativeTerminal(result);
            }
        });
        NativeEventPrefixObserver observer = null;
        if (observe) {
            observer = NativeEventPrefixObserver.attach(qsim, 20, EPOCH, 0, view -> {
                // Only the actual newly closed source view reaches the live composition.
                result.snapshots.add(result.combined.accept(view));
                result.views.add(view);
            });
            result.combined.bindTo(observer);
            result.preparedInspection = new NativePreparedInputInspection(qsim, true, false);
            qsim.addQueueSimulationListeners(result.preparedInspection);
        }
        try {
            qsim.run();
        } finally {
            if (observer != null) observer.close();
        }
        if (observer != null) {
            assertFalse(observer.isPoisoned());
            result.closeReceipt = observer.closeReceipt();
            result.preparedInspection.assertCompleted(result.views.size());
        }
        assertEquals(result.preparedInput, inputSignature(fixture), "Neither native run nor observation rewrites schedule/plan inputs");
        return result;
    }

    private static void assertPair(Result plain, Result observed) {
        assertFalse(plain.observe); assertTrue(observed.observe);
        assertNull(plain.preparedInspection); assertNotNull(observed.preparedInspection);
        assertEquals(plain.trace.events, observed.trace.events,
                "The entire class/time/type/attribute/receipt sequence must be equal with and without the observer");
        assertEquals(plain.preparedInput, observed.preparedInput);
        assertNativeEvidence(plain); assertNativeEvidence(observed);
        assertClosedEvidence(observed);
    }

    private static void assertNativeEvidence(Result result) {
        Fixture fixture = result.fixture;
        assertEquals(2, fixture.scenario.getPopulation().getPersons().size());
        assertEquals(fixture.sameVehicle ? 1 : 2, result.drivers.size());
        assertEquals(Set.of(A, B), result.workOwners.keySet());
        assertEquals(Set.of(A, B), result.trace.starts.keySet()); assertEquals(Set.of(A, B), result.trace.ends.keySet());
        assertEquals(2, result.terminalChecks);
        assertEquals(0, result.trace.events.stream().filter(event -> event.eventClass().equals(PersonStuckEvent.class.getName())).count());
        assertEquals(0, result.trace.events.stream().filter(event -> event.eventClass().equals(BoardingDeniedEvent.class.getName())).count());
        assertEquals(2, result.trace.continueBeforePhysicalAddChecks);
        assertEquals(1, result.trace.detachedSourceDriverChecks);
        for (TripKey key : List.of(A, B)) {
            RunService service = fixture.services.get(key);
            assertEquals(service.vehicle().getId().toString(), result.trace.starts.get(key).attributes().get("vehicleId"));
            assertEquals(service.route().getStops().stream().map(stop -> stop.getStopFacility().getId().toString()).toList(),
                    result.trace.facilityDepartures.get(key).stream().map(event -> event.attributes().get("facility")).toList());
            assertEquals(2, result.trace.facilityArrivals.get(key).size());
            assertTrue(result.trace.ends.get(key).time() >= result.trace.facilityDepartures.get(key).getLast().time());
        }
        String aVehicle = fixture.services.get(A).vehicle().getId().toString();
        String bVehicle = fixture.services.get(B).vehicle().getId().toString();
        for (String person : PERSONS) {
            List<NativeRecord> waiting = ofPerson(result, person, AgentWaitingForPtEvent.class);
            List<NativeRecord> entering = ofPerson(result, person, PersonEntersPtVehicleEvent.class);
            List<NativeRecord> continuing = ofPerson(result, person, PersonContinuesInVehicleEvent.class);
            List<NativeRecord> leaving = ofPerson(result, person, PersonLeavesPtVehicleEvent.class);
            assertEquals(1, waiting.size()); assertEquals(1, entering.size()); assertEquals(1, continuing.size()); assertEquals(1, leaving.size());
            NativeRecord wait = waiting.getFirst(), board = entering.getFirst(), continuation = continuing.getFirst(), alight = leaving.getFirst();
            assertEquals(fixture.origin.getId().toString(), wait.attributes().get("atStop"));
            assertEquals(fixture.terminal.getId().toString(), wait.attributes().get("destinationStop"));
            assertEquals(aVehicle, board.attributes().get("vehicle"));
            assertEquals(aVehicle, continuation.attributes().get("fromVehicle")); assertEquals(bVehicle, continuation.attributes().get("vehicle"));
            assertEquals(fixture.join.getId().toString(), continuation.attributes().get("facility"));
            assertEquals(B.line(), continuation.attributes().get("transitLine")); assertEquals(B.route(), continuation.attributes().get("transitRoute"));
            assertFalse(continuation.attributes().containsKey("departureId"));
            assertFalse(continuation.attributes().containsKey("departure"));
            assertEquals(bVehicle, alight.attributes().get("vehicle"));
            assertEquals(B.line(), alight.attributes().get("transitLine")); assertEquals(B.route(), alight.attributes().get("transitRoute"));
            assertTrue(result.trace.events.indexOf(wait) < result.trace.events.indexOf(board));
            assertTrue(result.trace.events.indexOf(board) < result.trace.events.indexOf(result.trace.ends.get(A)));
            assertTrue(result.trace.events.indexOf(result.trace.ends.get(A)) < result.trace.events.indexOf(continuation),
                    "Actual driver arrival precedes native passenger relocation; arrival is not alighting");
            assertTrue(result.trace.events.indexOf(continuation) < result.trace.events.indexOf(result.trace.starts.get(B)));
            assertTrue(result.trace.events.indexOf(result.trace.starts.get(B)) < result.trace.events.indexOf(alight));
            assertEquals(result.trace.ends.get(A).time(), continuation.time(), 0.0);
            assertEquals(1, ofPerson(result, person, PersonArrivalEvent.class).size());
            assertEquals(1, ofPerson(result, person, ActivityStartEvent.class).stream()
                    .filter(event -> event.attributes().get("actType").equals(person.equals(FREIGHT) ? "cargo_destination" : "work")).count());
        }
        NativeRecord release = ofPerson(result, FREIGHT, ActivityEndEvent.class).getFirst();
        NativeRecord wait = ofPerson(result, FREIGHT, AgentWaitingForPtEvent.class).getFirst();
        assertEquals("cargo_origin", release.attributes().get("actType")); assertEquals(RELEASE, release.time(), 0.0);
        assertTrue(result.trace.events.indexOf(release) < result.trace.events.indexOf(wait));
        assertTrue(TransitScheduleValidator.validateChainedDepartures(fixture.scenario.getTransitSchedule()).isValid());
        assertNativeTerminal(result);
    }

    private static void assertNativeTerminal(Result result) {
        assertEquals(0, result.qsim.getAgentCounter().getLiving()); assertEquals(0, result.qsim.getAgentCounter().getLost());
        for (var driver : result.drivers.values()) {
            assertSame(driver, result.qsim.getAgents().get(driver.getId()));
            if (driver instanceof ControlledTransitDriverAgent controlled) {
                assertTrue(controlled.currentExecutionState().terminal()); assertTrue(controlled.currentExecutionState().trip().isEmpty());
            }
            assertEquals(MobsimAgent.State.ACTIVITY, driver.getState()); assertNull(driver.getNextPlanElement());
            assertEquals(Double.POSITIVE_INFINITY, driver.getActivityEndTime());
        }
        result.fixture.scenario.getTransitVehicles().getVehicles().values().forEach(vehicle -> {
            var nativeVehicle = result.qsim.getVehicles().get(vehicle.getId());
            assertSame(vehicle, nativeVehicle.getVehicle()); assertNull(nativeVehicle.getDriver());
            assertTrue(nativeVehicle.getPassengers().isEmpty(), "Actual native capacity store has no remaining loaded rider");
        });
        for (var person : result.fixture.people.values()) {
            var agent = result.qsim.getAgents().get(person.getId());
            assertEquals(MobsimAgent.State.ACTIVITY, agent.getState());
            assertNull(assertInstanceOf(PTPassengerAgent.class, agent).getVehicle());
            assertEquals(result.fixture.terminal.getLinkId(), agent.getCurrentLinkId());
        }
    }

    /** Full trace is an independent POST-RUN oracle, never a live observation input. */
    private static void assertClosedEvidence(Result result) {
        assertFalse(result.closeReceipt.poisoned()); assertFalse(result.closeReceipt.terminalPrefixCertified());
        assertFalse(result.combined.isPoisoned()); assertFalse(result.views.isEmpty());
        assertEquals(result.views.size(), result.snapshots.size());
        var first = result.snapshots.getFirst();
        assertTrue(first.riders().riders().isEmpty()); assertTrue(first.releases().knownOrders().isEmpty());
        assertTrue(first.services().observedServices().isEmpty());
        var closedEvents = result.views.stream().flatMap(view -> view.newlyClosedEvents().stream()).toList();
        var closedTrace = closedEvents.stream().map(event -> new NativeRecord(event.eventClass(), event.eventType(), event.occurredAtSeconds(), event.attributes())).toList();
        assertEquals(result.trace.events.stream().filter(event -> event.time() <= result.views.getLast().closedThroughSeconds().orElseThrow()).toList(), closedTrace,
                "The source contains only the native callback prefix, without invented continuation facts");
        assertEquals(closedEvents.size(), result.closeReceipt.closedEvents());
        for (var view : result.views) assertFalse(view.evidence().allProducersCertified());
        var finalSnapshot = result.combined.snapshot().orElseThrow();
        assertEquals(Set.copyOf(PERSONS), finalSnapshot.riders().riders().keySet());
        assertTrue(finalSnapshot.riders().eventAccountedOccupantsByVehicle().isEmpty());
        for (String person : PERSONS) {
            var rider = finalSnapshot.riders().riders().get(person);
            assertEquals(Phase.ALIGHTED, rider.phase()); assertTrue(rider.vehicleId().isEmpty());
            assertTrue(rider.trip().isEmpty()); assertTrue(rider.pendingContinuation().isEmpty());
            assertEquals(0, rider.nativeDeniedCount());
            assertEquals(person.equals(FREIGHT) ? ObservedTransitRiderState.Kind.FREIGHT_UNIT : ObservedTransitRiderState.Kind.PASSENGER, rider.kind());
        }
        assertEquals(Map.of(FREIGHT, RELEASE), finalSnapshot.releases().knownOrders().get("loaded-order").releasedUnits());
        assertEquals(1, finalSnapshot.releases().knownOrders().get("loaded-order").contractedUnits());
        for (TripKey key : List.of(A, B)) assertEquals(ObservedTransitServiceState.Motion.ENDED, finalSnapshot.services().observedServices().get(key).motion());
        assertPerEventRetentionAndCommittedViews(result);
    }

    /** Audit the real closed events one at a time, including phases inside one atomic delta. */
    private static void assertPerEventRetentionAndCommittedViews(Result result) {
        var service = new ObservedTransitServiceState(serviceContracts(result.fixture));
        var releases = new KnownFreightReleaseState(freightContracts(result.fixture));
        var riders = new ObservedTransitRiderState(serviceContracts(result.fixture), Set.of(PASSENGER), Set.of(FREIGHT), Map.of(A, List.of(B)));
        var serviceState = service.initialState(); var releaseState = releases.initialState(); var riderState = riders.initialState();
        int sourceEndChecks = 0, pendingChecks = 0, successorChecks = 0, alightingChecks = 0;
        String sourceDriver = result.trace.starts.get(A).attributes().get("driverId");
        String aVehicle = result.fixture.services.get(A).vehicle().getId().toString();
        String bVehicle = result.fixture.services.get(B).vehicle().getId().toString();
        for (int i = 0; i < result.views.size(); i++) {
            StepView view = result.views.get(i);
            for (ReceivedEvent event : view.newlyClosedEvents()) {
                var beforeService = serviceState;
                serviceState = service.apply(serviceState, event); releaseState = releases.apply(releaseState, event);
                riderState = riders.apply(riderState, event, beforeService, serviceState, releaseState);
                if (event.eventClass().equals(PersonArrivalEvent.class.getName()) && sourceDriver.equals(event.attributes().get("person"))
                        && event.occurredAtSeconds() == result.trace.ends.get(A).time()) {
                    sourceEndChecks++;
                    for (String person : PERSONS) {
                        var rider = riderState.riders().get(person);
                        assertEquals(Phase.AWAITING_CONTINUATION_EVENT, rider.phase()); assertEquals(Optional.of(A), rider.trip());
                        assertEquals(Optional.of(aVehicle), rider.vehicleId());
                    }
                    assertEquals(Set.copyOf(PERSONS), riderState.eventAccountedOccupantsByVehicle().get(aVehicle));
                    assertFalse(serviceState.observedServices().containsKey(B));
                }
                if (event.eventClass().equals(PersonContinuesInVehicleEvent.class.getName())) {
                    pendingChecks++;
                    var rider = riderState.riders().get(event.attributes().get("person"));
                    assertEquals(Phase.PENDING_CONTINUATION, rider.phase()); assertTrue(rider.trip().isEmpty());
                    assertEquals(Optional.of(bVehicle), rider.vehicleId());
                    assertEquals(Set.of(B), rider.pendingContinuation().orElseThrow().candidates());
                    assertEquals(A, rider.pendingContinuation().orElseThrow().sourceTrip());
                    assertFalse(serviceState.observedServices().containsKey(B));
                }
                if (event.eventClass().equals(TransitDriverStartsEvent.class.getName()) && B.departure().equals(event.attributes().get("departureId"))) {
                    successorChecks++;
                    for (String person : PERSONS) {
                        var rider = riderState.riders().get(person);
                        assertEquals(Phase.RIDING, rider.phase()); assertEquals(Optional.of(B), rider.trip()); assertTrue(rider.pendingContinuation().isEmpty());
                    }
                    assertEquals(Map.of(bVehicle, Set.copyOf(PERSONS)), riderState.eventAccountedOccupantsByVehicle());
                }
                if (event.eventClass().equals(PersonLeavesPtVehicleEvent.class.getName())) {
                    alightingChecks++;
                    assertEquals(Phase.ALIGHTED, riderState.riders().get(event.attributes().get("person")).phase());
                }
            }
            var composed = result.snapshots.get(i);
            assertEquals(serviceState.observedServices(), composed.services().observedServices());
            assertEquals(releaseState.knownOrders(), composed.releases().knownOrders());
            assertEquals(riderState.riders(), composed.riders().riders());
            assertEquals(riderState.eventAccountedOccupantsByVehicle(), composed.riders().eventAccountedOccupantsByVehicle());
            assertEquals(view.closedEventCount(), composed.position().closedEventCount());
            assertEquals(view.closedPrefixSha256(), composed.position().closedPrefixSha256());
        }
        assertEquals(1, sourceEndChecks); assertEquals(2, pendingChecks); assertEquals(1, successorChecks); assertEquals(2, alightingChecks);
    }

    private static Fixture fixture(boolean sameVehicle) {
        var config = ConfigUtils.createConfig();
        config.transit().setUseTransit(true); config.transit().setUsingTransitInMobsim(true);
        config.global().setNumberOfThreads(1); config.qsim().setNumberOfThreads(1);
        config.qsim().setStartTime(0); config.qsim().setEndTime(END); config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.vspExperimental().setGeneratingBoardingDeniedEvent(true);
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-loaded-chain-output").toAbsolutePath().toString());
        for (String type : List.of("home", "work", "cargo_origin", "cargo_destination"))
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(type).setScoringThisActivityAtAll(false));
        Scenario scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork(); var nodes = new ArrayList<Node>(); var links = new ArrayList<Link>();
        for (int i = 0; i < 4; i++) nodes.add(NetworkUtils.createAndAddNode(network, Id.createNodeId("loaded-n" + i), new Coord(i * 100, 0)));
        for (int i = 0; i < 4; i++) {
            Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId("loaded-l" + i), nodes.get(i), nodes.get((i + 1) % 4),
                    i == 3 ? 300 : 100, 10, 3600, 1);
            link.setAllowedModes(Set.of(TransportMode.car)); links.add(link);
        }
        // l3 closes the real PrepareForSim SCC check and is unused by either service.
        var sf = scenario.getTransitSchedule().getFactory();
        TransitStopFacility origin = stop(scenario, "loaded-origin", links.get(0));
        TransitStopFacility join = stop(scenario, "loaded-join", links.get(1));
        TransitStopFacility terminal = stop(scenario, "loaded-terminal", links.get(2));
        VehicleType type = VehicleUtils.createVehicleType(Id.create("loaded-type", VehicleType.class));
        type.getCapacity().setSeats(4); type.getCapacity().setStandingRoom(0);
        type.setAccessTime(0); type.setEgressTime(0); type.setMaximumVelocity(10); type.setPcuEquivalents(1);
        scenario.getTransitVehicles().addVehicleType(type);
        Vehicle firstVehicle = VehicleUtils.createVehicle(Id.createVehicleId("loaded-vehicle-A"), type);
        scenario.getTransitVehicles().addVehicle(firstVehicle);
        Vehicle secondVehicle = sameVehicle ? firstVehicle : VehicleUtils.createVehicle(Id.createVehicleId("loaded-vehicle-B"), type);
        if (!sameVehicle) scenario.getTransitVehicles().addVehicle(secondVehicle);
        RunService a = addService(scenario, A, firstVehicle, origin, join, 10);
        RunService b = addService(scenario, B, secondVehicle, join, terminal, 100);
        a.departure().setChainedDepartures(List.of(sf.createChainedDeparture(b.line().getId(), b.route().getId(), b.departure().getId())));
        assertTrue(TransitScheduleValidator.validateChainedDepartures(scenario.getTransitSchedule()).isValid());
        assertEquals(1, UmlaufCache.calculateDeparturesDependingOnChains(scenario.getTransitSchedule()).getInt(b.departure().getId()));
        Map<String, Person> people = new LinkedHashMap<>();
        for (String name : PERSONS) {
            var pf = scenario.getPopulation().getFactory(); Person person = pf.createPerson(Id.createPersonId(name));
            var plan = pf.createPlan(); Activity initial = pf.createActivityFromLinkId(name.equals(FREIGHT) ? "cargo_origin" : "home", origin.getLinkId());
            initial.setEndTime(RELEASE); plan.addActivity(initial);
            var next = new DefaultTransitPassengerRoute(join, b.line(), b.route(), terminal);
            next.setBoardingTime(110); next.setTravelTime(30); next.setDistance(200);
            var full = new DefaultTransitPassengerRoute(origin, a.line(), a.route(), join, next);
            // Same construction as RaptorUtils.convertRoutePart: a chained PT leg
            // keeps first-service metadata but its link/stop egress is the ultimate destination.
            full.setEndLinkId(terminal.getLinkId()); full.setBoardingTime(20); full.setTravelTime(129); full.setDistance(400);
            Leg leg = pf.createLeg(TransportMode.pt); leg.setDepartureTime(RELEASE); leg.setTravelTime(129); leg.setRoute(full); plan.addLeg(leg);
            plan.addActivity(pf.createActivityFromLinkId(name.equals(FREIGHT) ? "cargo_destination" : "work", terminal.getLinkId()));
            person.addPlan(plan); person.setSelectedPlan(plan);
            if (name.equals(FREIGHT)) PopulationUtils.putSubpopulation(person, "freight");
            scenario.getPopulation().addPerson(person); people.put(name, person);
            assertSame(person.getId(), Id.get(name, Person.class), "Only current fixture canonical IDs may be used");
            assertEquals(terminal.getId(), full.getEgressStopId()); assertEquals(terminal.getLinkId(), full.getEndLinkId());
        }
        return new Fixture(scenario, sameVehicle, origin, join, terminal, Map.of(A, a, B, b), Map.copyOf(people));
    }

    private static TransitStopFacility stop(Scenario scenario, String id, Link link) {
        var stop = scenario.getTransitSchedule().getFactory().createTransitStopFacility(Id.create(id, TransitStopFacility.class), link.getCoord(), false);
        stop.setLinkId(link.getId()); scenario.getTransitSchedule().addStopFacility(stop); return stop;
    }
    private static RunService addService(Scenario scenario, TripKey key, Vehicle vehicle, TransitStopFacility first, TransitStopFacility last, double departureTime) {
        var sf = scenario.getTransitSchedule().getFactory();
        TransitLine line = sf.createTransitLine(Id.create(key.line(), TransitLine.class)); scenario.getTransitSchedule().addTransitLine(line);
        TransitRouteStop firstCall = sf.createTransitRouteStop(first, 0, 10); firstCall.setAwaitDepartureTime(true);
        TransitRouteStop lastCall = sf.createTransitRouteStop(last, 30, 30); lastCall.setAwaitDepartureTime(true);
        var route = RouteUtils.createLinkNetworkRouteImpl(first.getLinkId(), last.getLinkId()); route.setLinkIds(first.getLinkId(), List.of(), last.getLinkId());
        TransitRoute transitRoute = sf.createTransitRoute(Id.create(key.route(), TransitRoute.class), route, List.of(firstCall, lastCall), TransportMode.pt);
        Departure departure = sf.createDeparture(Id.create(key.departure(), Departure.class), departureTime); departure.setVehicleId(vehicle.getId());
        transitRoute.addDeparture(departure); line.addRoute(transitRoute); return new RunService(vehicle, line, transitRoute, departure);
    }
    private static List<ObservedTransitServiceState.Service> serviceContracts(Fixture fixture) {
        return List.of(A, B).stream().map(key -> new ObservedTransitServiceState.Service(key, fixture.services.get(key).vehicle().getId().toString(),
                fixture.services.get(key).route().getStops().stream().map(stop -> stop.getStopFacility().getId().toString()).toList())).toList();
    }
    private static List<KnownFreightReleaseState.Contract> freightContracts(Fixture fixture) {
        Person freight = fixture.people.get(FREIGHT);
        Activity initial = assertInstanceOf(Activity.class, freight.getSelectedPlan().getPlanElements().getFirst());
        assertEquals("cargo_origin", initial.getType()); assertEquals(RELEASE, initial.getEndTime().seconds(), 0.0);
        assertEquals(1, freight.getSelectedPlan().getPlanElements().stream().filter(element -> element instanceof Activity act && act.getType().equals(initial.getType())).count());
        return List.of(new KnownFreightReleaseState.Contract("loaded-order", fixture.origin.getId().toString(), fixture.terminal.getId().toString(),
                RELEASE, 180, List.of(FREIGHT), initial.getType()));
    }
    private static List<String> inputSignature(Fixture fixture) {
        var rows = new ArrayList<String>();
        for (TripKey key : List.of(A, B)) {
            RunService service = fixture.services.get(key);
            rows.add(key + ":" + service.vehicle().getId() + ":" + service.departure().getDepartureTime() + ":" + service.route().getRoute().getStartLinkId()
                    + ":" + service.route().getRoute().getLinkIds() + ":" + service.route().getRoute().getEndLinkId());
            service.route().getStops().forEach(stop -> rows.add("stop:" + stop.getStopFacility().getId() + ":" + stop.getArrivalOffset() + ":" + stop.getDepartureOffset()));
            service.departure().getChainedDepartures().forEach(chain -> rows.add("chain:" + chain.getChainedTransitLineId() + ":" + chain.getChainedRouteId() + ":" + chain.getChainedDepartureId()));
            rows.add("capacity:" + service.vehicle().getType().getCapacity().getSeats() + ":" + service.vehicle().getType().getCapacity().getStandingRoom());
        }
        for (String person : PERSONS) for (var element : fixture.people.get(person).getSelectedPlan().getPlanElements()) {
            if (element instanceof Activity activity) rows.add(person + ":act:" + activity.getType() + ":" + activity.getLinkId() + ":" + activity.getEndTime());
            if (element instanceof Leg leg) rows.add(person + ":leg:" + leg.getMode() + ":" + leg.getDepartureTime() + ":" + leg.getTravelTime() + ":"
                    + leg.getRoute().getStartLinkId() + ":" + leg.getRoute().getEndLinkId() + ":" + leg.getRoute().getRouteDescription());
        }
        return List.copyOf(rows);
    }
    private static List<NativeRecord> ofPerson(Result result, String person, Class<?> type) {
        return result.trace.events.stream().filter(event -> event.eventClass().equals(type.getName()) && person.equals(event.attributes().get("person"))).toList();
    }

    private record RunService(Vehicle vehicle, TransitLine line, TransitRoute route, Departure departure) { }
    private record Fixture(Scenario scenario, boolean sameVehicle, TransitStopFacility origin, TransitStopFacility join,
                           TransitStopFacility terminal, Map<TripKey, RunService> services, Map<String, Person> people) { }
    private record NativeRecord(String eventClass, String eventType, double time, Map<String, String> attributes) {
        private NativeRecord { attributes = Map.copyOf(new TreeMap<>(attributes)); }
    }
    private static final class Result {
        final Fixture fixture; final boolean observe; final boolean adapter; final Trace trace = new Trace(this);
        final Map<String, AbstractTransitDriverAgent> drivers = new LinkedHashMap<>();
        final Map<TripKey, AbstractTransitDriverAgent> workOwners = new LinkedHashMap<>();
        final List<StepView> views = new ArrayList<>(); final List<ClosedCoTransportObservationState.Snapshot> snapshots = new ArrayList<>();
        final ClosedCoTransportObservationState combined;
        NativePreparedInputInspection preparedInspection;
        QSim qsim; List<String> preparedInput; NativeEventPrefixObserver.CloseReceipt closeReceipt; int terminalChecks;
        Result(Fixture fixture, boolean observe, boolean adapter) {
            this.fixture = fixture; this.observe = observe; this.adapter = adapter;
            combined = observe ? new ClosedCoTransportObservationState(20, EPOCH, serviceContracts(fixture), freightContracts(fixture), Set.of(PASSENGER), Map.of(A, List.of(B))) : null;
        }
    }
    private static final class Trace implements BasicEventHandler {
        final Result result; final List<NativeRecord> events = new ArrayList<>();
        final Map<TripKey, NativeRecord> starts = new LinkedHashMap<>(), ends = new LinkedHashMap<>();
        final Map<String, TripKey> activeDrivers = new LinkedHashMap<>(), activeVehicles = new LinkedHashMap<>();
        final Map<TripKey, List<NativeRecord>> facilityArrivals = new LinkedHashMap<>(), facilityDepartures = new LinkedHashMap<>();
        int continueBeforePhysicalAddChecks, detachedSourceDriverChecks;
        Trace(Result result) {
            this.result = result;
            for (TripKey key : List.of(A, B)) { facilityArrivals.put(key, new ArrayList<>()); facilityDepartures.put(key, new ArrayList<>()); }
        }
        @Override public void handleEvent(Event event) {
            var record = new NativeRecord(event.getClass().getName(), event.getEventType(), event.getTime(), event.getAttributes()); events.add(record);
            if (event instanceof TransitDriverStartsEvent start) {
                TripKey key = new TripKey(start.getTransitLineId().toString(), start.getTransitRouteId().toString(), start.getDepartureId().toString());
                assertTrue(List.of(A, B).contains(key)); assertNull(starts.put(key, record));
                assertNull(activeDrivers.put(start.getDriverId().toString(), key)); assertNull(activeVehicles.put(start.getVehicleId().toString(), key));
                assertSame(result.workOwners.get(key), result.qsim.getAgents().get(start.getDriverId()));
            }
            if (event instanceof VehicleArrivesAtFacilityEvent arrival) facilityArrivals.get(activeVehicles.get(arrival.getVehicleId().toString())).add(record);
            if (event instanceof VehicleDepartsAtFacilityEvent departure) facilityDepartures.get(activeVehicles.get(departure.getVehicleId().toString())).add(record);
            if (event instanceof PersonArrivalEvent arrival && activeDrivers.containsKey(arrival.getPersonId().toString())) {
                TripKey key = activeDrivers.remove(arrival.getPersonId().toString()); assertNull(ends.put(key, record));
                activeVehicles.remove(result.fixture.services.get(key).vehicle().getId().toString());
                if (key.equals(A)) {
                    assertNull(result.qsim.getVehicles().get(result.fixture.services.get(A).vehicle().getId()).getDriver(),
                            "Native arrival detaches the source driver before endLeg and relocation");
                    detachedSourceDriverChecks++;
                }
            }
            if (event instanceof PersonContinuesInVehicleEvent continuation) {
                assertTrue(PERSONS.contains(continuation.getPersonId().toString())); assertTrue(ends.containsKey(A)); assertFalse(starts.containsKey(B));
                assertEquals(B.line(), continuation.getTransitLineId().toString()); assertEquals(B.route(), continuation.getTransitRouteId().toString());
                var target = result.qsim.getVehicles().get(continuation.getVehicleId());
                assertTrue(target.getPassengers().stream().noneMatch(person -> person.getId().equals(continuation.getPersonId())),
                        "The actual native continuation callback occurs before adding this rider to the target's physical store");
                continueBeforePhysicalAddChecks++;
            }
        }
    }
}
