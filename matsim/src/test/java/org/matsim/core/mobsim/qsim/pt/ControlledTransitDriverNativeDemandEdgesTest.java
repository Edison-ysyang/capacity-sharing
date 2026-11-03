package org.matsim.core.mobsim.qsim.pt;

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
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.PrepareForSimUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.PtConstants;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.run.ismt.dynamic.ClosedCoTransportObservationState;
import org.matsim.run.ismt.dynamic.ClosedCoTransportObservationState.Snapshot;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native demand-accounting boundaries, not an operating-policy experiment.
 * A registered freight unit executes an initial activity, a positive-duration
 * native teleportation access walk, a PT interaction and a real shared ride.
 * The second fixture adds an explicitly synthetic one-candidate policy rejection;
 * ComplexTransitStopHandler and PassengerAccessEgressImpl still perform every
 * physical boarding and emit every wait, entry, denial and alighting event.
 * No test event is emitted, no native stop duration is replaced, and no capacity
 * is reserved by the event mapper. Observer on/off pairs include the existing
 * runtime monitor's derived score events. This does not test EventsToScore,
 * iterative passenger adaptation, dispatch feasibility or complete producer sealing.
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDriverNativeDemandEdgesTest {
    // Never retain typed IDs across AutoResetIdCaches test epochs.
    private static final String PASSENGER = "edges-passenger";
    private static final String FREIGHT = "edges-cargo";
    private static final Set<String> ACTORS = Set.of(PASSENGER, FREIGHT);
    private static final TripKey TRIP = new TripKey("edges-line", "edges-route", "edges-trip");
    private static final double RELEASE = 30;
    private static final double ACCESS_WALK = 20;
    private static final int ITERATION = 20;
    private static final long EPOCH = 1;

    @Test
    void nativeFreightAlreadyOnboardContinuesAndAlightsAfterBoardingCloses() throws IOException {
        Result baseline = run(false, false);
        double firstEntry = baseline.trace.one(PersonEntersPtVehicleEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT)).getTime();
        double cutoff = firstEntry + 1;
        Result continued = run(false, false, new CoTransportBoardingPolicy.SharedBoardingWindow(0, cutoff));
        assertNativeCompletion(continued);
        assertTrue(continued.trace.one(PersonEntersPtVehicleEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT)).getTime() < cutoff);
        assertTrue(continued.trace.one(PersonLeavesPtVehicleEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT)).getTime() > cutoff);
        assertTrue(continued.trace.matching(TransitBoardingDeniedDetailEvent.class,
                event -> event.getReason() == TransitBoardingPolicy.DenialReason.OUTSIDE_SHARED_WINDOW).isEmpty());
    }

    @Test
    void nativePhysicalEntryAtTheExclusiveWindowEndIsPreventedWithoutLosingTheUnit() throws IOException {
        Result baseline = run(false, false);
        double firstEntry = baseline.trace.one(PersonEntersPtVehicleEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT)).getTime();
        Result restricted = run(false, false, new CoTransportBoardingPolicy.SharedBoardingWindow(0, firstEntry));
        assertTrue(restricted.trace.matching(PersonEntersPtVehicleEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT)).isEmpty());
        assertEquals(0, restricted.monitor.runtimeCounts().freightBoardings());
        assertEquals(1, restricted.monitor.runtimeCounts().passengerBoardings());
        assertFalse(restricted.monitor.freightOrderSnapshots(240).getFirst().outcome().completed());
        assertEquals(0, restricted.monitor.runtimeCounts().freightUnitsStillOnboard());
        assertEquals(1, restricted.trace.matching(TransitBoardingDeniedDetailEvent.class,
                event -> event.getReason() == TransitBoardingPolicy.DenialReason.OUTSIDE_SHARED_WINDOW).size());
        assertEquals(1, restricted.trace.matching(PersonStuckEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT)).size(), "The unserved unit is explicit at the execution horizon, not deleted");
        assertTrue(restricted.policy.originCalls.stream().anyMatch(call -> call.time < firstEntry && call.accepted.contains(FREIGHT)),
                "A unit accepted during earlier door handling must still be rejected at the native cutoff");
    }

    @Test
    void nativeBoardingWindowRejectsBeforeOpeningAndAllowsBoardingWhenOpen() throws IOException {
        Result closed = run(false, false, new CoTransportBoardingPolicy.SharedBoardingWindow(200, 240));
        assertTrue(closed.trace.matching(PersonEntersPtVehicleEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT)).isEmpty());
        assertEquals(1, closed.monitor.runtimeCounts().passengerBoardings());
        assertFalse(closed.monitor.freightOrderSnapshots(240).getFirst().outcome().completed());
        Result open = run(false, false, new CoTransportBoardingPolicy.SharedBoardingWindow(0, 240));
        assertNativeCompletion(open);
    }

    @Test
    void nativeAccessWalkSeparatesReleasedFreightFromObservedStopAvailability() throws IOException {
        Result observed = run(false, true);
        Result unobserved = run(false, false);
        assertPassivePair(unobserved, observed);
        assertNativeCompletion(observed);
        assertNativeCompletion(unobserved);
        assertReleaseBeforeWaiting(observed);
        assertTrue(observed.trace.matching(BoardingDeniedEvent.class, event -> true).isEmpty());
        assertEquals(0, observed.policy.syntheticRejections);
    }

    @Test
    void nativeNonzeroStopCanEmitRememberedDenialAfterSuccessfulEntryWithoutResettingRider() throws IOException {
        Result observed = run(true, true);
        Result unobserved = run(true, false);
        assertPassivePair(unobserved, observed);
        assertNativeCompletion(observed);
        assertNativeCompletion(unobserved);
        assertReleaseBeforeWaiting(observed);

        var entry = observed.trace.one(PersonEntersPtVehicleEvent.class, event -> event.getPersonId().toString().equals(FREIGHT));
        var denial = observed.trace.one(BoardingDeniedEvent.class, event -> event.getPersonId().toString().equals(FREIGHT));
        var detail = observed.trace.one(TransitBoardingDeniedDetailEvent.class, event -> event.getAttributes().get("person").equals(FREIGHT));
        var departure = observed.trace.one(VehicleDepartsAtFacilityEvent.class,
                event -> event.getFacilityId().equals(observed.fixture.origin.getId()));
        assertEquals(1, observed.policy.syntheticRejections);
        assertTrue(observed.policy.rejectedAt < entry.getTime(), "A real later stop invocation must actually board the previously rejected candidate");
        assertTrue(entry.getTime() < denial.getTime(), "Native denial is flushed only when the nonzero-duration stop finishes");
        assertEquals(denial.getTime(), detail.getTime(), 0.0);
        assertEquals(denial.getTime(), departure.getTime(), 0.0,
                "The fixture has no residual scheduled holding: native stop completion and departure agree");
        assertTrue(observed.trace.events.indexOf(entry) < observed.trace.events.indexOf(denial));
        assertTrue(observed.trace.events.indexOf(denial) < observed.trace.events.indexOf(detail));
        assertTrue(observed.trace.events.indexOf(detail) < observed.trace.events.indexOf(departure));
        assertEquals(TransitBoardingPolicy.DenialReason.INELIGIBLE_SERVICE, detail.getReason(),
                "This deliberately synthetic refusal is not a physical-capacity denial");
        assertTrue(observed.policy.originCalls.stream().anyMatch(call -> call.time == observed.policy.rejectedAt
                && call.accepted.equals(Set.of(PASSENGER)) && call.nativeOccupants.isEmpty()));
        assertTrue(observed.policy.originDurations.stream().anyMatch(stop -> stop.time == observed.policy.rejectedAt && stop.baseSeconds > 0));
        assertTrue(observed.policy.originDurations.stream().anyMatch(stop -> stop.time == denial.getTime() && stop.baseSeconds == 0));

        Snapshot entered = observed.capture.firstClosing(entry);
        Snapshot denied = observed.capture.firstClosing(denial);
        var beforeDenial = entered.riders().riders().get(FREIGHT);
        var afterDenial = denied.riders().riders().get(FREIGHT);
        assertEquals(ObservedTransitRiderState.Phase.RIDING, beforeDenial.phase());
        assertEquals(0, beforeDenial.nativeDeniedCount());
        assertEquals(ObservedTransitRiderState.Phase.RIDING, afterDenial.phase());
        assertEquals(1, afterDenial.nativeDeniedCount());
        assertEquals(beforeDenial.vehicleId(), afterDenial.vehicleId());
        assertEquals(beforeDenial.trip(), afterDenial.trip());
        assertEquals(Set.of(PASSENGER, FREIGHT), denied.riders().eventAccountedOccupantsByVehicle()
                .get(observed.fixture.vehicle.getId().toString()));
        assertEquals(1, observed.capture.combined.snapshot().orElseThrow().riders().riders().get(FREIGHT).nativeDeniedCount());
        assertEquals(1, observed.monitor.runtimeCounts().freightDeniedBoardings());
        var penalty = observed.trace.one(PersonScoreEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT) && event.getKind().equals("coTransportFreightDeniedBoarding"));
        assertEquals(denial.getTime(), penalty.getTime(), 0.0);
        assertEquals(-3, penalty.getAmount(), 0.0);
        assertEquals(1, observed.capture.detailNoops, "A diagnostic detail must not count the same native denial twice");
        assertTrue(observed.capture.scoreNoops > 0, "Derived score events belong to the prefix but do not move riders");
    }

    private static void assertReleaseBeforeWaiting(Result result) {
        var release = result.trace.one(ActivityEndEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT) && event.getActType().equals("cargo_origin"));
        var walkStart = result.trace.one(PersonDepartureEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT) && event.getLegMode().equals(TransportMode.walk));
        var walkEnd = result.trace.one(PersonArrivalEvent.class,
                event -> event.getPersonId().toString().equals(FREIGHT) && event.getLegMode().equals(TransportMode.walk));
        var wait = result.trace.one(AgentWaitingForPtEvent.class, event -> event.getPersonId().toString().equals(FREIGHT));
        assertEquals(RELEASE, release.getTime(), 0.0);
        assertEquals(release.getTime(), walkStart.getTime(), 0.0);
        assertEquals(ACCESS_WALK, walkEnd.getTime() - walkStart.getTime(), 0.0);
        assertTrue(wait.getTime() >= walkEnd.getTime());
        assertTrue(wait.getTime() > release.getTime());
        assertTrue(result.trace.events.indexOf(release) < result.trace.events.indexOf(walkStart));
        assertTrue(result.trace.events.indexOf(walkEnd) < result.trace.events.indexOf(wait));
        assertEquals(1, result.capture.walkArrivalNoops);

        var intermediate = result.capture.snapshots.stream().filter(snapshot ->
                snapshot.position().closedThroughSeconds().isPresent()
                        && snapshot.position().closedThroughSeconds().getAsDouble() >= release.getTime()
                        && snapshot.position().closedThroughSeconds().getAsDouble() < walkEnd.getTime()).toList();
        assertTrue(intermediate.size() >= 10, "The positive native access walk must create multiple decision opportunities, not just same-timestamp ordering");
        for (Snapshot snapshot : intermediate) {
            assertEquals(Map.of(FREIGHT, RELEASE), snapshot.releases().knownOrders().get(FREIGHT).releasedUnits());
            assertFalse(snapshot.riders().riders().containsKey(FREIGHT), "Released does not mean observed at the PT stop");
            assertTrue(snapshot.riders().eventAccountedOccupantsByVehicle().isEmpty());
        }
        Snapshot beforeRelease = result.capture.snapshots.stream().filter(snapshot ->
                snapshot.position().decisionTimeSeconds() == RELEASE).findFirst().orElseThrow();
        assertTrue(beforeRelease.releases().knownOrders().isEmpty());
        Snapshot waiting = result.capture.firstClosing(wait);
        assertEquals(ObservedTransitRiderState.Phase.WAITING, waiting.riders().riders().get(FREIGHT).phase());
        assertEquals(Optional.of(result.fixture.origin.getId().toString()), waiting.riders().riders().get(FREIGHT).lastInteractionStopId());
        assertTrue(waiting.riders().riders().get(FREIGHT).vehicleId().isEmpty());
        // A retained pre-arrival snapshot remains unchanged after the whole native ride.
        assertFalse(intermediate.getFirst().riders().riders().containsKey(FREIGHT));
        assertEquals(1, intermediate.getFirst().releases().knownOrders().get(FREIGHT).releasedUnits().size());
    }

    private static Result run(boolean rejectFirstCandidate, boolean observe) throws IOException {
        return run(rejectFirstCandidate, observe, null);
    }

    private static Result run(boolean rejectFirstCandidate, boolean observe,
            CoTransportBoardingPolicy.SharedBoardingWindow window) throws IOException {
        Fixture fixture = fixture();
        var events = EventsUtils.createEventsManager();
        var settings = CoTransportSettings.builder(fixture.orders).armMode(CoTransportSettings.ArmMode.ALWAYS_ACTIVE)
                .freightServiceWindow(window == null ? 0 : window.startInclusiveSeconds(),
                        window == null ? 240 : window.endExclusiveSeconds()).freightShareCap(0.5).passengerLoadGate(1).comfortThreshold(0.5)
                .freightHandlingDwellSeconds(0).deniedBoardingPenalty(3)
                .passengerPhysicalCrowdingPenaltyPerMinute(0.4).passengerFreightCoPresencePenaltyPerMinute(0.6).build();
        var registry = new FreightOrderRegistry(settings);
        registry.validateFixedPlans(fixture.scenario.getPopulation());
        var phase = new CoTransportPhaseController(settings, registry, fixture.scenario, events,
                fixture.scenario.getConfig().controller(), new StrategyManager(), Map.of());
        assertTrue(phase.isFreightActive());
        if (window != null) fixture.scenario.addScenarioElement(CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT, window);
        var delegate = new CoTransportBoardingPolicy(settings, registry, phase, fixture.scenario);
        var monitor = new CoTransportRuntimeMonitor(settings, phase, registry, fixture.scenario, events);
        monitor.configureDetailedVehicleOutput(false);
        monitor.reset(ITERATION);
        events.addHandler(monitor);
        Result result = new Result(fixture, monitor);
        result.boardingWindow = window;
        result.policy = new OneCandidateProbe(delegate, result, rejectFirstCandidate);
        assertSame(delegate, ((MutableScenario) fixture.scenario).removeScenarioElement(TransitBoardingPolicy.SCENARIO_ELEMENT));
        fixture.scenario.addScenarioElement(TransitBoardingPolicy.SCENARIO_ELEMENT, result.policy);
        events.addHandler(result.trace);
        PrepareForSimUtils.createDefaultPrepareForSim(fixture.scenario).run();
        result.plansBefore = planningSnapshot(fixture);
        result.qsim = new QSimBuilder(fixture.scenario.getConfig()).useDefaults().build(fixture.scenario, events);
        NativeEventPrefixObserver observer = null;
        if (observe) {
            result.capture = new Capture(fixture);
            observer = NativeEventPrefixObserver.attach(result.qsim, ITERATION, EPOCH, 0, result.capture::accept);
            result.capture.combined.bindTo(observer);
        }
        try {
            result.qsim.run();
        } finally {
            if (observer != null) observer.close();
        }
        if (observer != null) {
            assertFalse(observer.isPoisoned());
            assertFalse(observer.closeReceipt().terminalPrefixCertified());
            assertFalse(result.capture.combined.isPoisoned());
        }
        assertEquals(result.plansBefore, planningSnapshot(fixture));
        return result;
    }

    private static void assertPassivePair(Result off, Result on) {
        assertNull(off.capture);
        assertNotNull(on.capture);
        assertEquals(off.trace.records, on.trace.records, "The complete native and derived-monitor event sequence must be observer-invariant");
        assertEquals(off.policy.originCalls, on.policy.originCalls);
        assertEquals(off.policy.originDurations, on.policy.originDurations);
        assertEquals(off.monitor.runtimeCounts(), on.monitor.runtimeCounts());
        assertEquals(off.monitor.passengerExposureSnapshot(), on.monitor.passengerExposureSnapshot());
        assertEquals(off.monitor.freightOrderSnapshots(240).getFirst().outcome(), on.monitor.freightOrderSnapshots(240).getFirst().outcome());
        assertEquals(off.plansBefore, on.plansBefore);
    }

    private static void assertNativeCompletion(Result result) {
        assertEquals(0, result.qsim.getAgentCounter().getLost());
        assertEquals(0, result.qsim.getAgentCounter().getLiving());
        assertTrue(result.trace.matching(PersonStuckEvent.class, event -> true).isEmpty());
        assertEquals(1, result.trace.matching(TransitDriverStartsEvent.class, event -> true).size());
        assertEquals(2, result.trace.matching(VehicleArrivesAtFacilityEvent.class, event -> true).size());
        assertEquals(2, result.trace.matching(VehicleDepartsAtFacilityEvent.class, event -> true).size());
        assertEquals(1, result.nativeSharedDepartures);
        var nativeVehicle = result.qsim.getVehicles().get(result.fixture.vehicle.getId());
        assertSame(result.fixture.vehicle, nativeVehicle.getVehicle());
        assertTrue(nativeVehicle.getPassengers().isEmpty());
        assertNull(nativeVehicle.getDriver());
        for (String actor : ACTORS) {
            result.trace.one(AgentWaitingForPtEvent.class, event -> event.getPersonId().toString().equals(actor));
            result.trace.one(PersonEntersPtVehicleEvent.class, event -> event.getPersonId().toString().equals(actor));
            result.trace.one(PersonLeavesPtVehicleEvent.class, event -> event.getPersonId().toString().equals(actor));
            result.trace.one(PersonArrivalEvent.class, event -> event.getPersonId().toString().equals(actor) && event.getLegMode().equals(TransportMode.pt));
        }
        assertEquals(1, result.monitor.runtimeCounts().freightBoardings());
        assertEquals(1, result.monitor.runtimeCounts().passengerBoardings());
        assertEquals(0, result.monitor.runtimeCounts().freightUnitsStillOnboard());
        assertTrue(result.monitor.freightOrderSnapshots(240).getFirst().outcome().completed());
        if (result.capture != null) {
            Snapshot last = result.capture.combined.snapshot().orElseThrow();
            assertEquals(ObservedTransitServiceState.Motion.ENDED, last.services().observedServices().get(TRIP).motion());
            assertTrue(last.riders().eventAccountedOccupantsByVehicle().isEmpty());
            for (String actor : ACTORS) assertEquals(ObservedTransitRiderState.Phase.ALIGHTED, last.riders().riders().get(actor).phase());
        }
    }

    private static Fixture fixture() throws IOException {
        Path base = Path.of(".build-cache", "ismt-native-dispatch-micro", "demand-edges").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "fixture-");
        Path orders = directory.resolve("orders.csv");
        Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
                + FREIGHT + ",00:00:30,00:03:00,0.1,1.0,standard,1,1\n");
        var config = ConfigUtils.createConfig();
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1);
        config.transit().setUseTransit(true);
        config.transit().setUsingTransitInMobsim(true);
        config.vspExperimental().setGeneratingBoardingDeniedEvent(true);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(240);
        config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.controller().setFirstIteration(ITERATION);
        config.controller().setLastIteration(ITERATION);
        config.controller().setOutputDirectory(directory.resolve("unused-controller-output").toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork();
        Node n0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("edges-n0"), new Coord(0, 0));
        Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("edges-n1"), new Coord(100, 0));
        Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("edges-n2"), new Coord(200, 0));
        Link first = NetworkUtils.createAndAddLink(network, Id.createLinkId("edges-l0"), n0, n1, 100, 10, 3600, 1);
        Link last = NetworkUtils.createAndAddLink(network, Id.createLinkId("edges-l1"), n1, n2, 100, 10, 3600, 1);
        Link returning = NetworkUtils.createAndAddLink(network, Id.createLinkId("edges-return"), n2, n0, 200, 10, 3600, 1);
        for (Link link : List.of(first, last, returning)) link.setAllowedModes(Set.of(TransportMode.car));
        var sf = scenario.getTransitSchedule().getFactory();
        TransitStopFacility origin = sf.createTransitStopFacility(Id.create("edges-origin", TransitStopFacility.class), first.getCoord(), false);
        TransitStopFacility destination = sf.createTransitStopFacility(Id.create("edges-destination", TransitStopFacility.class), last.getCoord(), false);
        origin.setLinkId(first.getId()); destination.setLinkId(last.getId());
        scenario.getTransitSchedule().addStopFacility(origin); scenario.getTransitSchedule().addStopFacility(destination);
        var firstCall = sf.createTransitRouteStop(origin, 0, 0);
        firstCall.setAwaitDepartureTime(true);
        var lastCall = sf.createTransitRouteStop(destination, 40, 40);
        lastCall.setAwaitDepartureTime(true);
        var networkRoute = RouteUtils.createLinkNetworkRouteImpl(first.getId(), last.getId());
        networkRoute.setLinkIds(first.getId(), List.of(), last.getId());
        TransitRoute route = sf.createTransitRoute(Id.create(TRIP.route(), TransitRoute.class), networkRoute, List.of(firstCall, lastCall), TransportMode.pt);
        TransitLine line = sf.createTransitLine(Id.create(TRIP.line(), TransitLine.class));
        line.addRoute(route); scenario.getTransitSchedule().addTransitLine(line);
        VehicleType type = VehicleUtils.createVehicleType(Id.create("edges-type", VehicleType.class));
        type.getCapacity().setSeats(2); type.getCapacity().setStandingRoom(0);
        type.setAccessTime(2); type.setEgressTime(2); type.setDoorOperationMode(VehicleType.DoorOperationMode.parallel);
        type.setMaximumVelocity(10); type.setPcuEquivalents(1);
        scenario.getTransitVehicles().addVehicleType(type);
        Vehicle vehicle = VehicleUtils.createVehicle(Id.createVehicleId("edges-vehicle"), type);
        scenario.getTransitVehicles().addVehicle(vehicle);
        Departure departure = sf.createDeparture(Id.create(TRIP.departure(), Departure.class), 100);
        departure.setVehicleId(vehicle.getId()); route.addDeparture(departure);
        var pf = scenario.getPopulation().getFactory();
        for (String name : List.of(PASSENGER, FREIGHT)) {
            Person person = pf.createPerson(Id.createPersonId(name));
            Plan plan = pf.createPlan();
            Activity initial = pf.createActivityFromLinkId(name.equals(FREIGHT) ? "cargo_origin" : "home", first.getId());
            initial.setEndTime(name.equals(FREIGHT) ? RELEASE : RELEASE + ACCESS_WALK);
            plan.addActivity(initial);
            if (name.equals(FREIGHT)) {
                Leg walk = pf.createLeg(TransportMode.walk);
                walk.setDepartureTime(RELEASE); walk.setTravelTime(ACCESS_WALK);
                var access = RouteUtils.createGenericRouteImpl(first.getId(), first.getId());
                access.setDistance(20); access.setTravelTime(ACCESS_WALK); walk.setRoute(access);
                TripStructureUtils.setRoutingMode(walk, TransportMode.pt);
                plan.addLeg(walk);
                Activity interaction = pf.createActivityFromLinkId(PtConstants.TRANSIT_ACTIVITY_TYPE, first.getId());
                interaction.setMaximumDuration(0); plan.addActivity(interaction);
            }
            Leg pt = pf.createLeg(TransportMode.pt);
            pt.setDepartureTime(RELEASE + ACCESS_WALK); pt.setTravelTime(90);
            var planned = new DefaultTransitPassengerRoute(origin, line, route, destination);
            planned.setBoardingTime(100); planned.setTravelTime(90); planned.setDistance(200); pt.setRoute(planned);
            TripStructureUtils.setRoutingMode(pt, TransportMode.pt);
            plan.addLeg(pt);
            plan.addActivity(pf.createActivityFromLinkId(name.equals(FREIGHT) ? "freight_delivery" : "work", last.getId()));
            person.addPlan(plan); person.setSelectedPlan(plan);
            if (name.equals(FREIGHT)) PopulationUtils.putSubpopulation(person, "freight");
            scenario.getPopulation().addPerson(person);
        }
        return new Fixture(scenario, orders, route, origin, destination, vehicle);
    }

    private static List<String> planningSnapshot(Fixture fixture) {
        List<String> rows = new ArrayList<>();
        for (String name : List.of(PASSENGER, FREIGHT)) {
            Person person = fixture.scenario.getPopulation().getPersons().get(Id.createPersonId(name));
            assertSame(person.getId(), Id.get(person.getId().index(), Person.class));
            assertEquals(1, person.getPlans().size());
            for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
                if (element instanceof Activity activity) rows.add(name + ":" + activity.getType() + ":" + activity.getLinkId()
                        + ":" + activity.getEndTime() + ":" + activity.getMaximumDuration());
                if (element instanceof Leg leg) rows.add(name + ":" + leg.getMode() + ":" + leg.getTravelTime() + ":"
                        + leg.getRoute().getRouteDescription() + ":" + leg.getRoute().getTravelTime());
            }
        }
        return List.copyOf(rows);
    }

    /** Only this named test probe changes an admission result, once; all timing and events stay native. */
    private static final class OneCandidateProbe implements TransitBoardingPolicy {
        final TransitBoardingPolicy delegate;
        final Result result;
        final boolean rejectFirst;
        final List<StopCall> originCalls = new ArrayList<>();
        final List<StopDuration> originDurations = new ArrayList<>();
        int syntheticRejections;
        double rejectedAt = Double.NaN;
        OneCandidateProbe(TransitBoardingPolicy delegate, Result result, boolean rejectFirst) {
            this.delegate = delegate; this.result = result; this.rejectFirst = rejectFirst;
        }
        @Override public int priority(PTPassengerAgent passenger, TransitVehicle vehicle, TransitLine line, TransitRoute route, TransitStopFacility stop) {
            return delegate.priority(passenger, vehicle, line, route, stop);
        }
        @Override public BoardingDecision boardingTimeDecision(PTPassengerAgent passenger, TransitVehicle vehicle,
                TransitLine line, TransitRoute route, TransitStopFacility stop, double nowSeconds) {
            return delegate.boardingTimeDecision(passenger, vehicle, line, route, stop, nowSeconds);
        }
        @Override public BoardingDecision decideBoarding(PTPassengerAgent passenger, TransitVehicle vehicle, TransitLine line, TransitRoute route,
                                                          TransitStopFacility stop, List<PTPassengerAgent> leaving, List<PTPassengerAgent> accepted) {
            BoardingDecision ordinary = delegate.decideBoarding(passenger, vehicle, line, route, stop, leaving, accepted);
            if (rejectFirst && syntheticRejections == 0 && passenger.getId().toString().equals(FREIGHT)
                    && stop.getId().equals(result.fixture.origin.getId()) && ordinary.allowed()) {
                syntheticRejections++;
                rejectedAt = result.qsim.getSimTimer().getTimeOfDay();
                return BoardingDecision.deny(DenialReason.INELIGIBLE_SERVICE);
            }
            return ordinary;
        }
        @Override public DenialDetails denialDetails(PTPassengerAgent passenger, TransitVehicle vehicle, TransitLine line, TransitRoute route,
                                                     TransitStopFacility stop, List<PTPassengerAgent> leaving, List<PTPassengerAgent> accepted) {
            return delegate.denialDetails(passenger, vehicle, line, route, stop, leaving, accepted);
        }
        @Override public void observeStopInteraction(TransitVehicle vehicle, TransitLine line, TransitRoute route, TransitStopFacility stop,
                                                       List<PTPassengerAgent> leaving, List<PTPassengerAgent> entering) {
            assertEquals(ComplexTransitStopHandler.class, vehicle.getStopHandler().getClass());
            if (stop.getId().equals(result.fixture.origin.getId())) originCalls.add(new StopCall(result.qsim.getSimTimer().getTimeOfDay(),
                    entering.stream().map(agent -> agent.getId().toString()).collect(Collectors.toUnmodifiableSet()),
                    vehicle.getPassengers().stream().map(agent -> agent.getId().toString()).collect(Collectors.toUnmodifiableSet())));
            delegate.observeStopInteraction(vehicle, line, route, stop, leaving, entering);
        }
        @Override public double adjustStopTime(TransitVehicle vehicle, TransitLine line, TransitRoute route, TransitStopFacility stop,
                                               List<PTPassengerAgent> leaving, List<PTPassengerAgent> entering, double base) {
            double unmodified = delegate.adjustStopTime(vehicle, line, route, stop, leaving, entering, base);
            assertEquals(base, unmodified, 0.0, "No synthetic dwell or override can manufacture a later native event");
            if (stop.getId().equals(result.fixture.origin.getId())) originDurations.add(new StopDuration(result.qsim.getSimTimer().getTimeOfDay(), base));
            return unmodified;
        }
    }

    private static final class Capture {
        final ClosedCoTransportObservationState combined;
        final ObservedTransitServiceState serviceMapper;
        final KnownFreightReleaseState releaseMapper;
        final ObservedTransitRiderState riderMapper;
        ObservedTransitServiceState.State services;
        KnownFreightReleaseState.State releases;
        ObservedTransitRiderState.State riders;
        final List<StepView> views = new ArrayList<>();
        final List<Snapshot> snapshots = new ArrayList<>();
        int scoreNoops, detailNoops, walkArrivalNoops;
        Capture(Fixture fixture) {
            var elements = fixture.scenario.getPopulation().getPersons().get(Id.createPersonId(FREIGHT)).getSelectedPlan().getPlanElements();
            assertEquals(5, elements.size());
            Activity initial = assertInstanceOf(Activity.class, elements.getFirst());
            assertEquals("cargo_origin", initial.getType()); assertEquals(RELEASE, initial.getEndTime().seconds(), 0.0);
            assertEquals(1, elements.stream().filter(element -> element instanceof Activity activity && activity.getType().equals(initial.getType())).count());
            assertEquals(TransportMode.walk, assertInstanceOf(Leg.class, elements.get(1)).getMode());
            var transit = assertInstanceOf(DefaultTransitPassengerRoute.class, assertInstanceOf(Leg.class, elements.get(3)).getRoute());
            assertEquals(fixture.origin.getId(), transit.getAccessStopId());
            assertEquals(fixture.destination.getId(), transit.getEgressStopId());
            var serviceContracts = List.of(new ObservedTransitServiceState.Service(TRIP, fixture.vehicle.getId().toString(),
                    List.of(fixture.origin.getId().toString(), fixture.destination.getId().toString())));
            var freightContracts = List.of(new KnownFreightReleaseState.Contract(FREIGHT, fixture.origin.getId().toString(),
                    fixture.destination.getId().toString(), RELEASE, 180, List.of(FREIGHT), initial.getType()));
            combined = new ClosedCoTransportObservationState(ITERATION, EPOCH, serviceContracts, freightContracts, Set.of(PASSENGER), Map.of());
            serviceMapper = new ObservedTransitServiceState(serviceContracts); services = serviceMapper.initialState();
            releaseMapper = new KnownFreightReleaseState(freightContracts); releases = releaseMapper.initialState();
            riderMapper = new ObservedTransitRiderState(serviceContracts, Set.of(PASSENGER), Set.of(FREIGHT), Map.of()); riders = riderMapper.initialState();
        }
        void accept(StepView view) {
            assertFalse(view.evidence().allProducersCertified());
            for (ReceivedEvent event : view.newlyClosedEvents()) {
                var priorServices = services; var priorReleases = releases; var priorRiders = riders;
                services = serviceMapper.apply(services, event);
                releases = releaseMapper.apply(releases, event);
                riders = riderMapper.apply(riders, event, priorServices, services, releases);
                boolean score = event.eventClass().equals(PersonScoreEvent.class.getName());
                boolean detail = event.eventClass().equals(TransitBoardingDeniedDetailEvent.class.getName());
                boolean walkArrival = event.eventClass().equals(PersonArrivalEvent.class.getName())
                        && TransportMode.walk.equals(event.attributes().get("legMode")) && FREIGHT.equals(event.attributes().get("person"));
                if (score || detail || walkArrival) {
                    assertSame(priorServices, services); assertSame(priorReleases, releases); assertSame(priorRiders, riders);
                    if (score) scoreNoops++; if (detail) detailNoops++; if (walkArrival) walkArrivalNoops++;
                }
            }
            Snapshot composed = combined.accept(view);
            assertEquals(services.observedServices(), composed.services().observedServices());
            assertEquals(releases.knownOrders(), composed.releases().knownOrders());
            assertEquals(riders.riders(), composed.riders().riders());
            views.add(view); snapshots.add(composed);
        }
        Snapshot firstClosing(Event nativeEvent) {
            for (int i = 0; i < views.size(); i++) {
                if (views.get(i).newlyClosedEvents().stream().anyMatch(event -> event.eventClass().equals(nativeEvent.getClass().getName())
                        && event.occurredAtSeconds() == nativeEvent.getTime() && event.attributes().equals(nativeEvent.getAttributes()))) {
                    assertTrue(views.get(i).decisionTimeSeconds() > nativeEvent.getTime());
                    return snapshots.get(i);
                }
            }
            throw new AssertionError("Native event was not published in a closed decision view: " + nativeEvent);
        }
    }

    private record Fixture(Scenario scenario, Path orders, TransitRoute route, TransitStopFacility origin, TransitStopFacility destination, Vehicle vehicle) { }
    private record NativeRecord(String eventClass, double time, Map<String, String> attributes) { }
    private record StopCall(double time, Set<String> accepted, Set<String> nativeOccupants) { }
    private record StopDuration(double time, double baseSeconds) { }
    private static final class Result {
        final Fixture fixture;
        final CoTransportRuntimeMonitor monitor;
        final Trace trace = new Trace(this);
        QSim qsim;
        OneCandidateProbe policy;
        Capture capture;
        List<String> plansBefore;
        CoTransportBoardingPolicy.SharedBoardingWindow boardingWindow;
        int nativeSharedDepartures;
        Result(Fixture fixture, CoTransportRuntimeMonitor monitor) { this.fixture = fixture; this.monitor = monitor; }
    }
    private static final class Trace implements BasicEventHandler {
        final Result result;
        final List<Event> events = new ArrayList<>();
        final List<NativeRecord> records = new ArrayList<>();
        Trace(Result result) { this.result = result; }
        @Override public void handleEvent(Event event) {
            events.add(event); records.add(new NativeRecord(event.getClass().getName(), event.getTime(), Map.copyOf(event.getAttributes())));
            if (event instanceof VehicleDepartsAtFacilityEvent departure && departure.getFacilityId().equals(result.fixture.origin.getId())) {
                var nativeVehicle = result.qsim.getVehicles().get(departure.getVehicleId());
                assertEquals(2, nativeVehicle.getPassengerCapacity());
                var occupants = nativeVehicle.getPassengers().stream().map(agent -> agent.getId().toString()).collect(Collectors.toSet());
                if (result.boardingWindow == null) assertEquals(ACTORS, occupants);
                else {
                    assertTrue(occupants.contains(PASSENGER));
                    assertTrue(ACTORS.containsAll(occupants));
                }
                if (occupants.equals(ACTORS)) result.nativeSharedDepartures++;
            }
            if (event instanceof PersonEntersPtVehicleEvent entry && ACTORS.contains(entry.getPersonId().toString())) {
                assertTrue(result.qsim.getVehicles().get(entry.getVehicleId()).getPassengers().stream()
                        .anyMatch(passenger -> passenger.getId().equals(entry.getPersonId())), "Entry event follows a real native addPassenger");
            }
        }
        <T extends Event> List<T> matching(Class<T> type, Predicate<T> condition) {
            return events.stream().filter(event -> event.getClass() == type).map(type::cast).filter(condition).toList();
        }
        <T extends Event> T one(Class<T> type, Predicate<T> condition) {
            List<T> found = matching(type, condition);
            assertEquals(1, found.size(), "Expected exactly one native " + type.getSimpleName());
            return found.getFirst();
        }
    }
}
