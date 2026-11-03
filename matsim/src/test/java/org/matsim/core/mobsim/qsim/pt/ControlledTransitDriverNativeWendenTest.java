package org.matsim.core.mobsim.qsim.pt;

import com.google.inject.multibindings.OptionalBinder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.PrepareForSimUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.Umlauf;
import org.matsim.pt.Wenden;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.ClosedCoTransportObservationState;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.KnownFreightReleaseState;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.StepView;
import org.matsim.run.ismt.dynamic.ObservedTransitRiderState;
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
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empty-population engineering fixture: the default ReconstructingUmlaufBuilder
 * must insert and execute Wenden between two original same-vehicle Fahrt whose
 * endpoint links differ. No test constructs Wenden, injects a movement/event,
 * changes a clock or reschedules a queue. Synthetic distances/times are not an
 * operating policy, railway safety model, calibrated turnaround or loaded transfer.
 *
 * <p>The active adapter receives no accepted override. This is not an OFF/zero
 * factory test: those modes must still use a default native driver. Comparing its
 * full event stream with default-native runs checks the adapter's no-action path
 * through a genuine interpolated work segment. Closed observation is passive and
 * publishes no timetable-only service or fictitious Wenden passenger trip.</p>
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDriverNativeWendenTest {
    private static final TripKey A = new TripKey("wenden-line", "wenden-route-A", "wenden-departure-A");
    private static final TripKey B = new TripKey("wenden-line", "wenden-route-B", "wenden-departure-B");
    // Only a trace key for the native event's reserved IDs; never a service contract.
    private static final TripKey WENDEN = new TripKey("Wenden", "Wenden", "Wenden");
    private static final double A_BASELINE = 10;
    private static final double B_BASELINE = 160;
    private static final double END = 240;
    private static final String VEHICLE = "wenden-vehicle";
    private static final String CONNECTOR = "wenden-connector";

    @Test
    void nativeInterpolationAndClosedObservationPreserveRealWendenForDefaultAndActiveAdapter() {
        Result nativeOff = run(false, false);
        Result nativeOn = run(false, true);
        Result adapterOn = run(true, true);
        for (Result result : List.of(nativeOff, nativeOn, adapterOn)) assertNativeLifecycle(result);
        assertNull(nativeOff.observation);
        assertEquals(nativeOff.trace.records, nativeOn.trace.records,
                "A bound passive observer and composite must leave every native event unchanged");
        assertEquals(nativeOff.trace.records, adapterOn.trace.records,
                "The active adapter with no accepted action must preserve the native Wenden event stream");
        assertEquals(nativeOff.supplyBefore, nativeOn.supplyBefore);
        assertEquals(nativeOff.supplyBefore, adapterOn.supplyBefore);
        assertEquals(nativeOn.observation.samples, adapterOn.observation.samples);
        assertEquals(nativeOn.observation.views.stream().map(StepView::closedPrefixSha256).toList(),
                adapterOn.observation.views.stream().map(StepView::closedPrefixSha256).toList());
        assertClosedLifecycle(nativeOn);
        assertClosedLifecycle(adapterOn);
    }

    private static Result run(boolean activeAdapter, boolean observe) {
        Scenario scenario = scenario();
        Id<Vehicle> vehicleId = Id.createVehicleId(VEHICLE);
        var ledger = new DispatchCommitmentState(List.of(
                new DispatchCommitmentState.ScheduledTrip(A, VEHICLE, A_BASELINE),
                new DispatchCommitmentState.ScheduledTrip(B, VEHICLE, B_BASELINE)),
                new DispatchCommitmentState.Limits(20, 20, 0));
        long epoch = ledger.beginIteration(20, 0, true).version().epoch();
        Result result = new Result(scenario, vehicleId, ledger, activeAdapter);
        result.supplyBefore = supplySnapshot(scenario);
        var events = EventsUtils.createEventsManager();
        events.addHandler(result.trace);
        TransitDriverAgentFactory factory = (umlauf, internal, tracker) -> {
            result.factoryCalls++;
            assertEquals(vehicleId, umlauf.getVehicleId());
            assertNativeWork(umlauf, scenario);
            result.nativeWork = umlauf;
            result.workBefore = workSnapshot(umlauf);
            // Only the default builder above creates the work. The test changes
            // which driver reads it, never the work list or a native transition.
            result.driver = activeAdapter
                    ? new ControlledTransitDriverAgent(umlauf, TransportMode.car, tracker, internal,
                    ignored -> Optional.empty(), 0, epoch)
                    : (TransitDriverAgentImpl) new DefaultTransitDriverAgentFactory().createTransitDriver(umlauf, internal, tracker);
            return result.driver;
        };
        PrepareForSimUtils.createDefaultPrepareForSim(scenario).run();
        assertEquals(result.supplyBefore, supplySnapshot(scenario));
        QSim qsim = new QSimBuilder(scenario.getConfig()).useDefaults().addQSimModule(new AbstractQSimModule() {
            @Override protected void configureQSim() {
                OptionalBinder.newOptionalBinder(binder(), TransitDriverAgentFactory.class).setBinding().toInstance(factory);
            }
        }).build(scenario, events);
        result.qsim = qsim;
        qsim.addQueueSimulationListeners((MobsimBeforeSimStepListener) event -> {
            TransitDriverAgentImpl driver = result.driver;
            if (driver.getState() == MobsimAgent.State.LEG && driver.getTransitRoute() == null) {
                result.wendenLegSamples++;
                assertNull(driver.getDeparture());
                assertNotNull(driver.getNextPlanElement());
                if (driver instanceof ControlledTransitDriverAgent controlled) {
                    var state = controlled.currentExecutionState();
                    assertTrue(state.trip().isEmpty());
                    assertFalse(state.terminal());
                    assertFalse(state.chainBlocked());
                    assertTrue(state.acceptedVersion().isEmpty());
                    assertEquals(result.trace.ends.get(A).getTime(), state.physicalReadySeconds(), 0.0);
                }
            }
            if (driver.getState() == MobsimAgent.State.ACTIVITY && driver.getDeparture() != null
                    && driver.getDeparture().getId().toString().equals(B.departure())
                    && !result.trace.starts.containsKey(B)) {
                result.waitingBSamples++;
                assertNotNull(result.trace.ends.get(WENDEN));
                assertEquals(B_BASELINE, driver.getActivityEndTime(), 0.0);
                if (driver instanceof ControlledTransitDriverAgent controlled) {
                    var state = controlled.currentExecutionState();
                    assertEquals(Optional.of(B), state.trip());
                    assertEquals(result.trace.ends.get(WENDEN).getTime(), state.physicalReadySeconds(), 0.0);
                    assertTrue(state.acceptedVersion().isEmpty());
                }
            }
            if (event.getSimulationTime() == 220 || event.getSimulationTime() == 235) {
                result.terminalChecks++;
                assertTrue(result.trace.ends.containsKey(B));
                assertEquals(MobsimAgent.State.ACTIVITY, driver.getState());
                assertNull(driver.getNextPlanElement());
                assertEquals(Double.POSITIVE_INFINITY, driver.getActivityEndTime());
                assertEquals(0, qsim.getAgentCounter().getLiving());
                assertEquals(0, qsim.getAgentCounter().getLost());
                if (driver instanceof ControlledTransitDriverAgent controlled) {
                    assertTrue(controlled.currentExecutionState().terminal());
                    assertTrue(controlled.currentExecutionState().trip().isEmpty());
                }
            }
        });
        NativeEventPrefixObserver observer = null;
        if (observe) {
            result.observation = new Observation(epoch);
            observer = NativeEventPrefixObserver.attach(qsim, 20, epoch, 0, result.observation::accept);
            result.observation.combined.bindTo(observer);
        }
        try {
            qsim.run(); // Owns all work starts, link traversal, stop calls, leg ends and cleanup.
        } finally {
            if (observer != null) observer.close();
        }
        if (observer != null) result.closeReceipt = observer.closeReceipt();
        return result;
    }

    private static void assertNativeLifecycle(Result result) {
        assertEquals(1, result.factoryCalls);
        assertEquals(List.of(A, WENDEN, B), List.copyOf(result.trace.starts.keySet()));
        assertEquals(List.of(A, WENDEN, B), List.copyOf(result.trace.ends.keySet()));
        assertEquals(A_BASELINE, result.trace.starts.get(A).getTime(), 0.0);
        assertEquals(B_BASELINE, result.trace.starts.get(B).getTime(), 0.0);
        double aEnd = result.trace.ends.get(A).getTime();
        double wendenStart = result.trace.starts.get(WENDEN).getTime();
        double wendenEnd = result.trace.ends.get(WENDEN).getTime();
        assertTrue(wendenStart >= aEnd && wendenStart <= aEnd + 1,
                "Native work transition may be quantized by one QSim step");
        assertTrue(wendenEnd > wendenStart, "A real connector must take positive simulated travel time");
        assertTrue(wendenEnd < B_BASELINE, "This baseline includes an actual ready-but-waiting interval before B");
        assertTrue(result.trace.starts.get(B).getTime() >= wendenEnd);
        assertEquals(1, result.trace.connectorEntries.size(), "Only native Wenden traverses the interpolated connector");
        LinkEnterEvent connector = result.trace.connectorEntries.getFirst();
        assertEquals(result.vehicleId, connector.getVehicleId());
        assertTrue(connector.getTime() >= wendenStart && connector.getTime() < wendenEnd);
        for (TripKey trip : List.of(A, B)) {
            assertEquals(result.vehicleId, result.trace.starts.get(trip).getVehicleId());
            assertEquals(result.driver.getId(), result.trace.starts.get(trip).getDriverId());
            assertEquals(2, result.trace.arrivals.get(trip).size());
            assertEquals(2, result.trace.departures.get(trip).size());
            assertEquals(stops(trip), result.trace.arrivals.get(trip).stream().map(e -> e.getFacilityId().toString()).toList());
            assertEquals(stops(trip), result.trace.departures.get(trip).stream().map(e -> e.getFacilityId().toString()).toList());
            assertTrue(result.trace.ends.get(trip).getTime() >= result.trace.departures.get(trip).getLast().getTime());
        }
        assertEquals(A_BASELINE + 10, result.trace.departures.get(A).getFirst().getTime(), 0.0);
        assertEquals(B_BASELINE + 7, result.trace.departures.get(B).getFirst().getTime(), 0.0);
        assertEquals(result.vehicleId, result.trace.starts.get(WENDEN).getVehicleId());
        assertEquals(result.driver.getId(), result.trace.starts.get(WENDEN).getDriverId());
        assertTrue(result.wendenLegSamples > 0);
        assertTrue(result.waitingBSamples > 0);
        assertEquals(2, result.terminalChecks);
        assertEquals(0, result.trace.stuck);
        assertEquals(0, result.qsim.getAgentCounter().getLiving());
        assertEquals(0, result.qsim.getAgentCounter().getLost());
        assertEquals(0, result.scenario.getPopulation().getPersons().size());
        assertEquals(1, result.scenario.getTransitVehicles().getVehicles().size());
        assertEquals(1, result.qsim.getVehicles().size());
        assertSame(result.driver, result.qsim.getAgents().get(result.driver.getId()));
        var nativeVehicle = result.qsim.getVehicles().get(result.vehicleId);
        assertSame(result.scenario.getTransitVehicles().getVehicles().get(result.vehicleId), nativeVehicle.getVehicle());
        assertNull(nativeVehicle.getDriver());
        assertTrue(nativeVehicle.getPassengers().isEmpty());
        assertEquals(Double.POSITIVE_INFINITY, result.driver.getActivityEndTime());
        assertNull(result.driver.getNextPlanElement());
        assertEquals(result.supplyBefore, supplySnapshot(result.scenario));
        assertEquals(result.workBefore, workSnapshot(result.nativeWork));
        assertTrue(result.ledger.history().isEmpty(), "No dispatch decision was submitted");
        assertEquals(Set.of(A, B), result.ledger.snapshot().startedAtSeconds().keySet());
        assertNativeWork(result.nativeWork, result.scenario);
        if (result.activeAdapter) {
            assertEquals(aEnd, result.trace.physicalReadyAtWendenStart, 0.0);
            assertEquals(wendenEnd, result.trace.physicalReadyAtBStart, 0.0,
                    "B's physical-ready bound must include the real Wenden leg, not just the end of A");
        } else assertEquals(TransitDriverAgentImpl.class, result.driver.getClass());
    }

    private static void assertClosedLifecycle(Result result) {
        Observation observation = result.observation;
        assertFalse(result.closeReceipt.poisoned());
        assertFalse(result.closeReceipt.terminalPrefixCertified());
        assertFalse(observation.combined.isPoisoned());
        assertTrue(observation.samples.getFirst().services().isEmpty());
        assertTrue(observation.samples.getFirst().latestWork().isEmpty());
        long activeWendenViews = 0, endedWendenViews = 0;
        for (Sample sample : observation.samples) {
            assertTrue(sample.orders().isEmpty());
            assertTrue(sample.riders().isEmpty());
            assertTrue(sample.occupants().isEmpty());
            assertFalse(sample.services().containsKey(WENDEN), "Deadhead work is not a passenger service");
            for (TripKey trip : List.of(A, B)) assertEquals(closed(sample, result.trace.starts.get(trip).getTime()),
                    sample.services().containsKey(trip), "Private future work contracts do not publish an unstarted service");
            boolean wendenStarted = closed(sample, result.trace.starts.get(WENDEN).getTime());
            boolean bStarted = closed(sample, result.trace.starts.get(B).getTime());
            if (wendenStarted && !bStarted) {
                assertEquals(Set.of(A), sample.services().keySet());
                assertEquals(ObservedTransitServiceState.Motion.ENDED, sample.services().get(A).motion());
                var work = sample.latestWork().get(VEHICLE);
                assertTrue(work.trip().isEmpty());
                assertEquals(result.driver.getId().toString(), work.driverId());
                assertEquals(result.trace.starts.get(WENDEN).getTime(), work.startedAtSeconds(), 0.0);
                assertEquals(0, work.nextStopIndex());
                assertTrue(work.atStop().isEmpty());
                assertTrue(work.lastObservedStop().isEmpty());
                boolean ended = closed(sample, result.trace.ends.get(WENDEN).getTime());
                assertEquals(ended ? ObservedTransitServiceState.Motion.ENDED : ObservedTransitServiceState.Motion.STARTED, work.motion());
                assertEquals(ended ? result.trace.ends.get(WENDEN).getTime() : result.trace.starts.get(WENDEN).getTime(),
                        work.lastObservedAtSeconds(), 0.0);
                if (ended) endedWendenViews++; else activeWendenViews++;
            }
        }
        assertTrue(activeWendenViews > 0, "The genuine moving Wenden must be visible as closed vehicle work");
        assertTrue(endedWendenViews > 0, "Completed Wenden does not disclose the still-unstarted B service");
        var finalState = observation.combined.snapshot().orElseThrow().services();
        assertEquals(Set.of(A, B), finalState.observedServices().keySet());
        for (TripKey trip : List.of(A, B)) {
            var work = finalState.observedServices().get(trip);
            assertEquals(ObservedTransitServiceState.Motion.ENDED, work.motion());
            assertEquals(2, work.nextStopIndex());
            assertTrue(work.atStop().isEmpty());
            assertEquals(Optional.of(stops(trip).getLast()), work.lastObservedStop());
            assertEquals(result.trace.starts.get(trip).getTime(), work.startedAtSeconds(), 0.0);
            assertEquals(result.trace.ends.get(trip).getTime(), work.lastObservedAtSeconds(), 0.0);
        }
        assertEquals(finalState.observedServices().get(B), finalState.latestWorkByVehicle().get(VEHICLE));
        assertTrue(finalState.hasObservedDriver(result.driver.getId().toString()));
        // A remains a completed historical service after both Wenden and B.
        assertEquals(result.trace.ends.get(A).getTime(), finalState.observedServices().get(A).lastObservedAtSeconds(), 0.0);
    }

    private static boolean closed(Sample sample, double time) {
        return sample.closedThrough().isPresent() && time <= sample.closedThrough().getAsDouble();
    }

    private static Scenario scenario() {
        var config = ConfigUtils.createConfig();
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1);
        config.transit().setUseTransit(true);
        config.transit().setUsingTransitInMobsim(true);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(END);
        config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-wenden-output")
                .toAbsolutePath().toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        Link a0 = link(scenario, "wenden-A-origin-link", "w0", "w1", 0, 100);
        Link a1 = link(scenario, "wenden-A-terminal-link", "w1", "w2", 100, 100);
        link(scenario, CONNECTOR, "w2", "w3", 200, 300);
        Link b0 = link(scenario, "wenden-B-origin-link", "w3", "w4", 500, 100);
        Link b1 = link(scenario, "wenden-B-terminal-link", "w4", "w5", 600, 100);
        link(scenario, "wenden-unused-return", "w5", "w0", 700, 700);
        var sf = scenario.getTransitSchedule().getFactory();
        TransitLine line = sf.createTransitLine(Id.create(A.line(), TransitLine.class));
        scenario.getTransitSchedule().addTransitLine(line);
        VehicleType type = VehicleUtils.createVehicleType(Id.create("wenden-type", VehicleType.class));
        type.getCapacity().setSeats(8);
        type.getCapacity().setStandingRoom(0);
        type.setMaximumVelocity(10);
        type.setPcuEquivalents(1);
        Id<Vehicle> vehicleId = Id.createVehicleId(VEHICLE);
        scenario.getTransitVehicles().addVehicleType(type);
        scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(vehicleId, type));
        addRoute(scenario, line, A, a0, a1, 10, 40, A_BASELINE, vehicleId);
        addRoute(scenario, line, B, b0, b1, 7, 30, B_BASELINE, vehicleId);
        return scenario;
    }

    private static Link link(Scenario scenario, String name, String fromName, String toName, double x, double length) {
        var network = scenario.getNetwork();
        Id<Node> from = Id.createNodeId(fromName), to = Id.createNodeId(toName);
        Node start = network.getNodes().get(from), end = network.getNodes().get(to);
        if (start == null) start = NetworkUtils.createAndAddNode(network, from, new Coord(x, 0));
        if (end == null) end = NetworkUtils.createAndAddNode(network, to, new Coord(x + length, 0));
        Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(name), start, end, length, 10, 3600, 1);
        link.setAllowedModes(Set.of(TransportMode.car));
        return link;
    }

    private static List<String> stops(TripKey trip) {
        return trip.equals(A) ? List.of("wenden-A-origin", "wenden-A-terminal") : List.of("wenden-B-origin", "wenden-B-terminal");
    }

    private static void addRoute(Scenario scenario, TransitLine line, TripKey trip, Link firstLink, Link lastLink,
                                 double firstOffset, double lastOffset, double baseline, Id<Vehicle> vehicle) {
        var sf = scenario.getTransitSchedule().getFactory();
        List<TransitRouteStop> calls = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Link link = index == 0 ? firstLink : lastLink;
            TransitStopFacility stop = sf.createTransitStopFacility(Id.create(stops(trip).get(index), TransitStopFacility.class), link.getCoord(), false);
            stop.setLinkId(link.getId());
            scenario.getTransitSchedule().addStopFacility(stop);
            TransitRouteStop call = sf.createTransitRouteStop(stop, index == 0 ? 0 : lastOffset, index == 0 ? firstOffset : lastOffset);
            call.setAwaitDepartureTime(true);
            calls.add(call);
        }
        var networkRoute = RouteUtils.createLinkNetworkRouteImpl(firstLink.getId(), lastLink.getId());
        networkRoute.setLinkIds(firstLink.getId(), List.of(), lastLink.getId());
        TransitRoute route = sf.createTransitRoute(Id.create(trip.route(), TransitRoute.class), networkRoute, calls, TransportMode.pt);
        Departure departure = sf.createDeparture(Id.create(trip.departure(), Departure.class), baseline);
        departure.setVehicleId(vehicle);
        route.addDeparture(departure);
        line.addRoute(route);
    }

    private static void assertNativeWork(Umlauf umlauf, Scenario scenario) {
        var work = umlauf.getUmlaufStuecke();
        assertEquals(3, work.size(), "Default interpolation, not the test, must insert the connector work");
        assertTrue(work.getFirst().isFahrt());
        assertInstanceOf(Wenden.class, work.get(1));
        assertFalse(work.get(1).isFahrt());
        assertNull(work.get(1).getDeparture());
        assertNull(work.get(1).getRoute());
        assertNull(work.get(1).getLine());
        assertTrue(work.getLast().isFahrt());
        var line = scenario.getTransitSchedule().getTransitLines().get(Id.create(A.line(), TransitLine.class));
        var a = line.getRoutes().get(Id.create(A.route(), TransitRoute.class));
        var b = line.getRoutes().get(Id.create(B.route(), TransitRoute.class));
        assertSame(a, work.getFirst().getRoute());
        assertSame(b, work.getLast().getRoute());
        assertSame(a.getDepartures().get(Id.create(A.departure(), Departure.class)), work.getFirst().getDeparture());
        assertSame(b.getDepartures().get(Id.create(B.departure(), Departure.class)), work.getLast().getDeparture());
        assertNotEquals(a.getRoute().getEndLinkId(), b.getRoute().getStartLinkId());
        assertEquals(a.getRoute().getEndLinkId(), work.get(1).getCarRoute().getStartLinkId());
        assertEquals(b.getRoute().getStartLinkId(), work.get(1).getCarRoute().getEndLinkId());
        assertEquals(List.of(Id.createLinkId(CONNECTOR)), work.get(1).getCarRoute().getLinkIds());
    }

    private static List<String> workSnapshot(Umlauf umlauf) {
        return umlauf.getUmlaufStuecke().stream().map(work -> (work.isFahrt() ? work.getDeparture().getId().toString() : "Wenden")
                + ":" + work.getCarRoute().getStartLinkId() + ":" + work.getCarRoute().getLinkIds() + ":" + work.getCarRoute().getEndLinkId()).toList();
    }

    private static List<String> supplySnapshot(Scenario scenario) {
        List<String> rows = new ArrayList<>();
        scenario.getNetwork().getLinks().values().stream().sorted((a, b) -> a.getId().compareTo(b.getId())).forEach(link ->
                rows.add("network:" + link.getId() + ":" + link.getFromNode().getId() + ":" + link.getToNode().getId()
                        + ":" + link.getLength() + ":" + link.getFreespeed() + ":" + link.getCapacity() + ":" + link.getNumberOfLanes() + ":" + link.getAllowedModes()));
        scenario.getTransitSchedule().getTransitLines().values().stream().sorted((a, b) -> a.getId().compareTo(b.getId())).forEach(line ->
                line.getRoutes().values().stream().sorted((a, b) -> a.getId().compareTo(b.getId())).forEach(route -> {
                    rows.add(line.getId() + ":" + route.getId() + ":" + route.getTransportMode() + ":" + route.getRoute().getStartLinkId()
                            + ":" + route.getRoute().getLinkIds() + ":" + route.getRoute().getEndLinkId());
                    route.getStops().forEach(stop -> rows.add(stop.getStopFacility().getId() + ":" + stop.getStopFacility().getLinkId()
                            + ":" + stop.getArrivalOffset() + ":" + stop.getDepartureOffset() + ":" + stop.isAwaitDepartureTime()));
                    route.getDepartures().values().stream().sorted((a, b) -> a.getId().compareTo(b.getId())).forEach(departure ->
                            rows.add(departure.getId() + ":" + departure.getDepartureTime() + ":" + departure.getVehicleId()));
                }));
        scenario.getTransitVehicles().getVehicles().values().forEach(vehicle -> rows.add(vehicle.getId() + ":" + vehicle.getType().getId()
                + ":" + vehicle.getType().getCapacity().getSeats() + ":" + vehicle.getType().getCapacity().getStandingRoom()
                + ":" + vehicle.getType().getMaximumVelocity() + ":" + vehicle.getType().getPcuEquivalents()));
        return List.copyOf(rows);
    }

    private record NativeRecord(String eventClass, double time, Map<String, String> attributes) {
        private NativeRecord { attributes = Map.copyOf(attributes); }
    }

    private record Sample(double decisionTime, OptionalDouble closedThrough,
                          Map<TripKey, ObservedTransitServiceState.WorkObservation> services,
                          Map<String, ObservedTransitServiceState.WorkObservation> latestWork,
                          Map<String, KnownFreightReleaseState.KnownOrder> orders,
                          Map<String, ObservedTransitRiderState.RiderObservation> riders,
                          Map<String, Set<String>> occupants) {
        private Sample {
            services = Map.copyOf(services); latestWork = Map.copyOf(latestWork);
            orders = Map.copyOf(orders); riders = Map.copyOf(riders); occupants = Map.copyOf(occupants);
        }
    }

    private static final class Observation {
        final ClosedCoTransportObservationState combined;
        final List<StepView> views = new ArrayList<>();
        final List<Sample> samples = new ArrayList<>();
        Observation(long epoch) {
            var contracts = List.of(A, B).stream().map(trip -> new ObservedTransitServiceState.Service(trip, VEHICLE, stops(trip))).toList();
            combined = new ClosedCoTransportObservationState(20, epoch, contracts, List.of(), Set.of(), Map.of());
        }
        void accept(StepView view) {
            var snapshot = combined.accept(view);
            assertFalse(snapshot.position().sourceEvidence().allProducersCertified());
            assertEquals(view.viewSequence(), snapshot.position().viewSequence());
            assertEquals(view.closedPrefixSha256(), snapshot.position().closedPrefixSha256());
            views.add(view);
            samples.add(new Sample(view.decisionTimeSeconds(), view.closedThroughSeconds(), snapshot.services().observedServices(),
                    snapshot.services().latestWorkByVehicle(), snapshot.releases().knownOrders(), snapshot.riders().riders(),
                    snapshot.riders().eventAccountedOccupantsByVehicle()));
        }
    }

    private static final class Result {
        final Scenario scenario;
        final Id<Vehicle> vehicleId;
        final DispatchCommitmentState ledger;
        final boolean activeAdapter;
        final Trace trace;
        QSim qsim;
        TransitDriverAgentImpl driver;
        Umlauf nativeWork;
        List<String> supplyBefore, workBefore;
        Observation observation;
        NativeEventPrefixObserver.CloseReceipt closeReceipt;
        int factoryCalls, wendenLegSamples, waitingBSamples, terminalChecks;
        Result(Scenario scenario, Id<Vehicle> vehicleId, DispatchCommitmentState ledger, boolean activeAdapter) {
            this.scenario = scenario; this.vehicleId = vehicleId; this.ledger = ledger; this.activeAdapter = activeAdapter;
            trace = new Trace(this);
        }
    }

    private static final class Trace implements BasicEventHandler {
        final Result result;
        final List<NativeRecord> records = new ArrayList<>();
        final Map<TripKey, TransitDriverStartsEvent> starts = new LinkedHashMap<>();
        final Map<TripKey, PersonArrivalEvent> ends = new LinkedHashMap<>();
        final Map<TripKey, List<VehicleArrivesAtFacilityEvent>> arrivals = new LinkedHashMap<>();
        final Map<TripKey, List<VehicleDepartsAtFacilityEvent>> departures = new LinkedHashMap<>();
        final List<LinkEnterEvent> connectorEntries = new ArrayList<>();
        TripKey activeWork;
        double physicalReadyAtWendenStart = Double.NaN, physicalReadyAtBStart = Double.NaN;
        int stuck;
        Trace(Result result) {
            this.result = result;
            for (TripKey trip : List.of(A, B)) { arrivals.put(trip, new ArrayList<>()); departures.put(trip, new ArrayList<>()); }
        }
        @Override public void handleEvent(Event event) {
            records.add(new NativeRecord(event.getClass().getName(), event.getTime(), event.getAttributes()));
            if (event instanceof PersonStuckEvent) stuck++;
            if (event instanceof TransitDriverStartsEvent start) {
                activeWork = new TripKey(start.getTransitLineId().toString(), start.getTransitRouteId().toString(), start.getDepartureId().toString());
                assertTrue(Set.of(A, WENDEN, B).contains(activeWork));
                assertNull(starts.put(activeWork, start));
                if (!WENDEN.equals(activeWork)) result.ledger.recordStarted(activeWork, start.getTime(), start.getTime());
                if (result.driver instanceof ControlledTransitDriverAgent controlled) {
                    assertTrue(controlled.currentExecutionState().acceptedVersion().isEmpty());
                    if (WENDEN.equals(activeWork)) physicalReadyAtWendenStart = controlled.currentExecutionState().physicalReadySeconds();
                    if (B.equals(activeWork)) physicalReadyAtBStart = controlled.currentExecutionState().physicalReadySeconds();
                }
            }
            if (event instanceof VehicleArrivesAtFacilityEvent arrival) {
                assertTrue(arrivals.containsKey(activeWork), "Wenden must not generate a PT facility arrival");
                arrivals.get(activeWork).add(arrival);
            }
            if (event instanceof VehicleDepartsAtFacilityEvent departure) {
                assertTrue(departures.containsKey(activeWork), "Wenden must not generate a PT facility departure");
                departures.get(activeWork).add(departure);
            }
            if (event instanceof LinkEnterEvent enter && enter.getLinkId().toString().equals(CONNECTOR)) {
                assertEquals(WENDEN, activeWork);
                connectorEntries.add(enter);
            }
            if (event instanceof PersonArrivalEvent arrival && arrival.getPersonId().equals(result.driver.getId())) {
                assertNotNull(activeWork);
                assertNull(ends.put(activeWork, arrival));
            }
        }
    }
}
