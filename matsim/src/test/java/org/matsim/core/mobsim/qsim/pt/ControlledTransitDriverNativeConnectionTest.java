package org.matsim.core.mobsim.qsim.pt;

import com.google.inject.multibindings.OptionalBinder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
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
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
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
 * Synthetic local-QSim adapter stress tests, not an operating-policy feasibility
 * test. One native vehicle performs A then B using the default real
 * ReconstructingUmlaufBuilder. The advance intentionally requests B before A's
 * physical completion; no minimum turnaround or full constraint approval is
 * claimed. All service starts, facility events and leg ends come from QSim.run().
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDriverNativeConnectionTest {
    private static final TripKey A = new TripKey("connection-line", "route-A", "departure-A");
    private static final TripKey B = new TripKey("connection-line", "route-B", "departure-B");
    private static final double A_BASELINE = 10;
    private static final double B_BASELINE = 120;
    private static final double DECISION_TIME = 25;
    private static final double B_FIRST_OFFSET = 7;
    private static final double END = 300;

    @Test
    void sameVehicleFutureAdvanceAndDelayRespectNativeReadinessAndTerminalInfinity() {
        Result advanced = runFixture(40);
        Result delayed = runFixture(140);

        assertCommonNativeEvidence(advanced);
        assertCommonNativeEvidence(delayed);
        assertEquals(advanced.trace.aScientificEvents, delayed.trace.aScientificEvents,
                "Changing future B only must not change or requeue running service A");
        assertEquals(advanced.trace.aReadyArrival, delayed.trace.aReadyArrival, 0.0,
                "Native completion of A must not depend on B's accepted future time");

        // Deliberately infeasible as a requested vehicle connection: this checks
        // physical protection by the adapter, not approval of a dispatch policy.
        assertTrue(40 < advanced.trace.aReadyArrival,
                "The stress case must actually request B before native A completion");
        assertEquals(40, advanced.ledger.snapshot().commitments().get(B), 0.0,
                "Do not rewrite the accepted-but-late commitment to the ready time");
        assertTrue(advanced.trace.bStart.getTime() >= advanced.trace.aReadyArrival);
        assertTrue(advanced.trace.firstBDeparture().getTime() >= advanced.trace.aReadyArrival);
        assertTrue(advanced.trace.firstBDeparture().getTime() > 40 + B_FIRST_OFFSET,
                "The observed physical shortfall must remain visible in actual facility departure");
        assertTrue(advanced.trace.bStart.getTime() <= advanced.trace.aReadyArrival + 1,
                "A one-second QSim step may quantize a newly available successor; old baseline 120 must not block the advance");
        assertTrue(advanced.trace.firstBDeparture().getTime() < B_BASELINE + B_FIRST_OFFSET,
                "Actual first-facility departure, not a getter alone, must demonstrate the advance");

        assertTrue(delayed.trace.aReadyArrival < 140, "Delay fixture needs a genuine interval of waiting after A completes");
        assertTrue(delayed.waitingBSamples > 0, "The native successor ACTIVITY must be observed waiting for its accepted time");
        assertEquals(140, delayed.trace.bStart.getTime(), 0.0);
        assertEquals(140 + B_FIRST_OFFSET, delayed.trace.firstBDeparture().getTime(), 0.0,
                "Native first-stop departure must honour the later commitment plus the unchanged nonzero offset");
        assertEquals(delayed.trace.firstBDeparture().getTime() - (B_BASELINE + B_FIRST_OFFSET),
                delayed.trace.firstBDeparture().getDelay(), 0.0,
                "Native delay remains relative to the unmodified published baseline");
    }

    private static void assertCommonNativeEvidence(Result result) {
        Trace trace = result.trace;
        assertEquals(1, result.factoryCalls, "One real native vehicle/work chain creates exactly one driver");
        assertEquals(List.of(A.departure(), B.departure()), trace.starts.stream().map(e -> e.getDepartureId().toString()).toList());
        assertEquals(2, trace.starts.size());
        assertEquals(result.vehicleId, trace.aStart.getVehicleId());
        assertEquals(result.vehicleId, trace.bStart.getVehicleId());
        assertEquals(List.of("stop-A-origin", "stop-join"), trace.aDepartures.stream().map(e -> e.getFacilityId().toString()).toList());
        assertEquals(List.of("stop-join", "stop-B-terminal"), trace.bDepartures.stream().map(e -> e.getFacilityId().toString()).toList());
        assertEquals(2, trace.aArrivals.size());
        assertEquals(2, trace.bArrivals.size());
        assertEquals(List.of("stop-A-origin", "stop-join"), trace.aArrivals.stream().map(e -> e.getFacilityId().toString()).toList());
        assertEquals(List.of("stop-join", "stop-B-terminal"), trace.bArrivals.stream().map(e -> e.getFacilityId().toString()).toList());
        assertTrue(trace.aDepartures.stream().allMatch(e -> e.getVehicleId().equals(result.vehicleId)));
        assertTrue(trace.bDepartures.stream().allMatch(e -> e.getVehicleId().equals(result.vehicleId)));
        assertEquals(A_BASELINE, trace.aStart.getTime(), 0.0);
        assertEquals(A_BASELINE + 10, trace.aDepartures.getFirst().getTime(), 0.0,
                "First-stop holding makes driver start distinct from actual facility departure");
        assertEquals(1, trace.aLegEndCount);
        assertEquals(1, trace.bLegEndCount);
        assertTrue(trace.aReadyArrival >= trace.aDepartures.getLast().getTime());
        assertTrue(trace.bTerminalArrival >= trace.bDepartures.getLast().getTime());
        assertEquals(trace.aReadyArrival, trace.physicalReadyAtBStart, 0.0,
                "Adapter readiness must equal the independently observed real native PersonArrival of A");
        assertEquals(0, result.controllerReschedules, "Future-only and terminal refreshes must not requeue the driver");
        assertEquals(ControlledTransitDriverAgent.RefreshOutcome.FUTURE_ONLY, result.futureRefresh.outcome());
        assertFalse(result.futureRefresh.requiresReschedule());
        assertEquals(2, result.terminalChecks);
        assertEquals(1, result.ledger.history().size(), "Exactly one real future-B commitment was accepted");
        assertEquals(trace.aStart.getTime(), result.ledger.snapshot().startedAtSeconds().get(A), 0.0);
        assertEquals(trace.bStart.getTime(), result.ledger.snapshot().startedAtSeconds().get(B), 0.0);
        assertEquals(0, trace.stuckEvents);
        assertEquals(0, result.qsim.getAgentCounter().getLiving());
        assertEquals(0, result.qsim.getAgentCounter().getLost());
        assertEquals(MobsimAgent.State.ACTIVITY, result.driver.getState());
        assertEquals(Double.POSITIVE_INFINITY, result.driver.getActivityEndTime());
        assertTrue(result.driver.currentExecutionState().terminal());
        assertTrue(result.driver.currentExecutionState().trip().isEmpty());
        assertNull(result.driver.getNextPlanElement());
        assertEquals(2, result.nativeWork.getUmlaufStuecke().size());
        assertTrue(result.nativeWork.getUmlaufStuecke().stream().allMatch(work -> work.isFahrt()),
                "This explicitly same-endpoint case needs no Wenden; native work must still contain both services");
        assertEquals(List.of(A.departure(), B.departure()), result.nativeWork.getUmlaufStuecke().stream()
                .map(work -> work.getDeparture().getId().toString()).toList());
        assertEquals(result.baselineBefore, baselineSnapshot(result.scenario),
                "Schedule objects, offsets, routes, departure IDs, vehicle assignments and capacity stay unchanged");
        assertEquals(1, result.scenario.getTransitVehicles().getVehicles().size());
        assertEquals(0, result.scenario.getPopulation().getPersons().size(), "No real or synthetic passenger population is needed for this adapter-only fixture");
        assertEquals(1, result.qsim.getVehicles().size());
        assertSame(result.driver, result.qsim.getAgents().get(result.driver.getId()));
    }

    private static Result runFixture(double committedB) {
        var config = ConfigUtils.createConfig();
        config.transit().setUseTransit(true);
        config.transit().setUsingTransitInMobsim(true);
        config.qsim().setNumberOfThreads(1);
        config.global().setNumberOfThreads(1);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(END);
        config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        // QSim and its in-memory handlers write no controller output. The unused
        // configured path remains inside the workspace if inspected by a module.
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-connection-output")
                .toAbsolutePath().toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        Link first = link(scenario, "link-A", "n0", "n1", 0, 10);
        Link join = link(scenario, "link-join", "n1", "n2", 10, 600);
        Link last = link(scenario, "link-B", "n2", "n3", 610, 100);
        // The native PrepareForSim check requires a strongly connected car
        // network. This return link closes the synthetic network only; neither
        // service route uses it, so A/B timing and their shared endpoint stay fixed.
        link(scenario, "link-unused-return", "n3", "n0", 710, 710);
        var schedule = scenario.getTransitSchedule();
        var sf = schedule.getFactory();
        TransitStopFacility origin = stop(scenario, "stop-A-origin", first);
        TransitStopFacility connection = stop(scenario, "stop-join", join);
        TransitStopFacility terminal = stop(scenario, "stop-B-terminal", last);
        VehicleType type = VehicleUtils.createVehicleType(Id.create("connection-type", VehicleType.class));
        type.getCapacity().setSeats(8);
        type.getCapacity().setStandingRoom(0);
        type.setMaximumVelocity(10);
        type.setPcuEquivalents(1);
        Id<Vehicle> vehicleId = Id.createVehicleId("connection-vehicle");
        scenario.getTransitVehicles().addVehicleType(type);
        scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(vehicleId, type));
        TransitLine line = sf.createTransitLine(Id.create(A.line(), TransitLine.class));
        schedule.addTransitLine(line);
        addRoute(scenario, line, A, first, join, origin, connection, 10, 40, A_BASELINE, vehicleId);
        addRoute(scenario, line, B, join, last, connection, terminal, B_FIRST_OFFSET, 20, B_BASELINE, vehicleId);

        var ledger = new DispatchCommitmentState(List.of(
                new DispatchCommitmentState.ScheduledTrip(A, vehicleId.toString(), A_BASELINE),
                new DispatchCommitmentState.ScheduledTrip(B, vehicleId.toString(), B_BASELINE)),
                new DispatchCommitmentState.Limits(90, 90, 0));
        long epoch = ledger.beginIteration(20, 0, true).version().epoch();
        Map<TripKey, ControlledTransitDriverAgent.AcceptedCommitment> accepted = new LinkedHashMap<>();
        Result result = new Result(scenario, vehicleId, ledger);
        var events = EventsUtils.createEventsManager();
        Trace trace = new Trace(result);
        result.trace = trace;
        events.addHandler(trace);
        TransitDriverAgentFactory factory = (umlauf, internal, tracker) -> {
            result.factoryCalls++;
            assertEquals(vehicleId, umlauf.getVehicleId());
            assertEquals(2, umlauf.getUmlaufStuecke().size(), "Default reconstruction must join the two same-vehicle services");
            assertEquals(List.of(A.departure(), B.departure()), umlauf.getUmlaufStuecke().stream()
                    .map(work -> work.getDeparture().getId().toString()).toList());
            result.nativeWork = umlauf;
            var driver = new ControlledTransitDriverAgent(umlauf, TransportMode.car, tracker, internal,
                    key -> Optional.ofNullable(accepted.get(key)), 0, epoch);
            result.driver = driver;
            return driver;
        };
        PrepareForSimUtils.createDefaultPrepareForSim(scenario).run();
        result.baselineBefore = baselineSnapshot(scenario);
        QSim qsim = new QSimBuilder(config).useDefaults().addQSimModule(new AbstractQSimModule() {
            @Override protected void configureQSim() {
                OptionalBinder.newOptionalBinder(binder(), TransitDriverAgentFactory.class).setBinding().toInstance(factory);
            }
        }).build(scenario, events);
        result.qsim = qsim;
        qsim.addQueueSimulationListeners((MobsimBeforeSimStepListener) event -> {
            double now = event.getSimulationTime();
            ControlledTransitDriverAgent driver = result.driver;
            if (now == DECISION_TIME) {
                assertNotNull(trace.aStart, "A must really have started before the future update");
                assertSame(driver, qsim.getAgents().get(driver.getId()));
                assertSame(driver, qsim.getVehicles().get(vehicleId).getDriver());
                assertEquals(MobsimAgent.State.LEG, driver.getState());
                assertEquals(Optional.of(A), driver.currentExecutionState().trip());
                var before = driver.currentExecutionState();
                var currentElement = driver.getCurrentPlanElement();
                var currentDeparture = driver.getDeparture();
                var batch = new DispatchCommitmentState.Batch(ledger.snapshot().version(), now,
                        List.of(new DispatchCommitmentState.ObservedFact("observed-native-A-start", trace.aStart.getTime(), trace.aStart.getTime())),
                        Map.of(B, committedB));
                var decision = ledger.commit(batch);
                assertEquals(1, decision.changes().size());
                for (var change : decision.changes()) {
                    accepted.put(change.key(), new ControlledTransitDriverAgent.AcceptedCommitment(
                            change.committedDepartureSeconds(), decision.resultingVersion()));
                }
                result.acceptedBVersion = decision.resultingVersion();
                result.futureRefresh = driver.refreshAcceptedCommitment(B, now);
                if (result.futureRefresh.requiresReschedule()) {
                    result.controllerReschedules++;
                    qsim.rescheduleActivityEnd(driver);
                }
                assertEquals(before, driver.currentExecutionState(), "Future B changes neither current A work nor its frozen leg anchor");
                assertSame(currentElement, driver.getCurrentPlanElement());
                assertSame(currentDeparture, driver.getDeparture());
            }
            var state = driver.currentExecutionState();
            if (state.trip().equals(Optional.of(B)) && state.state() == MobsimAgent.State.ACTIVITY) {
                result.waitingBSamples++;
                assertTrue(Double.isFinite(trace.aReadyArrival));
                assertEquals(trace.aReadyArrival, state.physicalReadySeconds(), 0.0);
                assertEquals(Math.max(committedB, trace.aReadyArrival), state.effectiveDeadlineSeconds(), 0.0);
                assertEquals(Optional.of(result.acceptedBVersion), state.acceptedVersion());
                assertEquals(committedB, ledger.snapshot().commitments().get(B), 0.0);
            }
            if (now == 260 || now == 290) {
                assertTrue(Double.isFinite(trace.bTerminalArrival), "B must have finished before the terminal refresh");
                assertTrue(state.terminal());
                assertTrue(state.trip().isEmpty());
                assertNull(driver.getNextPlanElement());
                assertEquals(Double.POSITIVE_INFINITY, driver.getActivityEndTime());
                assertEquals(MobsimAgent.State.ACTIVITY, driver.getState());
                assertEquals(0, qsim.getAgentCounter().getLiving());
                assertEquals(0, qsim.getAgentCounter().getLost());
                int startsBefore = trace.starts.size();
                int departuresBefore = trace.aDepartures.size() + trace.bDepartures.size();
                var refresh = driver.refreshAcceptedCommitment(B, now);
                assertFalse(refresh.requiresReschedule(), "A stale exposed native Departure must not revive final activity");
                assertEquals(ControlledTransitDriverAgent.RefreshOutcome.NO_CHANGE, refresh.outcome());
                assertEquals(Double.POSITIVE_INFINITY, driver.getActivityEndTime());
                assertEquals(startsBefore, trace.starts.size());
                assertEquals(departuresBefore, trace.aDepartures.size() + trace.bDepartures.size());
                result.terminalChecks++;
            }
        });
        qsim.run(); // QSim owns event processing, all native transitions and cleanup.
        return result;
    }

    private static Link link(Scenario scenario, String id, String fromId, String toId, double x, double length) {
        var network = scenario.getNetwork();
        Id<Node> from = Id.createNodeId(fromId), to = Id.createNodeId(toId);
        Node start = network.getNodes().get(from);
        if (start == null) start = NetworkUtils.createAndAddNode(network, from, new Coord(x, 0));
        Node end = network.getNodes().get(to);
        if (end == null) end = NetworkUtils.createAndAddNode(network, to, new Coord(x + length, 0));
        Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(id), start, end, length, 10, 3600, 1);
        link.setAllowedModes(Set.of(TransportMode.car));
        return link;
    }

    private static TransitStopFacility stop(Scenario scenario, String id, Link link) {
        var schedule = scenario.getTransitSchedule();
        var facility = schedule.getFactory().createTransitStopFacility(Id.create(id, TransitStopFacility.class), link.getCoord(), false);
        facility.setLinkId(link.getId());
        schedule.addStopFacility(facility);
        return facility;
    }

    private static void addRoute(Scenario scenario, TransitLine line, TripKey key, Link start, Link end,
                                 TransitStopFacility first, TransitStopFacility last, double firstOffset,
                                 double terminalOffset, double baseline, Id<Vehicle> vehicle) {
        var factory = scenario.getTransitSchedule().getFactory();
        TransitRouteStop firstStop = factory.createTransitRouteStop(first, 0, firstOffset);
        firstStop.setAwaitDepartureTime(true);
        TransitRouteStop lastStop = factory.createTransitRouteStop(last, terminalOffset, terminalOffset);
        lastStop.setAwaitDepartureTime(true);
        var networkRoute = RouteUtils.createLinkNetworkRouteImpl(start.getId(), end.getId());
        networkRoute.setLinkIds(start.getId(), List.of(), end.getId());
        TransitRoute route = factory.createTransitRoute(Id.create(key.route(), TransitRoute.class), networkRoute,
                List.of(firstStop, lastStop), TransportMode.pt);
        Departure departure = factory.createDeparture(Id.create(key.departure(), Departure.class), baseline);
        departure.setVehicleId(vehicle);
        route.addDeparture(departure);
        line.addRoute(route);
    }

    private static List<String> baselineSnapshot(Scenario scenario) {
        List<String> rows = new ArrayList<>();
        scenario.getTransitSchedule().getTransitLines().values().stream().sorted((a, b) -> a.getId().compareTo(b.getId())).forEach(line ->
                line.getRoutes().values().stream().sorted((a, b) -> a.getId().compareTo(b.getId())).forEach(route -> {
                    rows.add(line.getId() + ":" + route.getId() + ":" + route.getTransportMode() + ":" + route.getRoute().getStartLinkId()
                            + ":" + route.getRoute().getLinkIds() + ":" + route.getRoute().getEndLinkId());
                    for (var stop : route.getStops()) rows.add(stop.getStopFacility().getId() + ":" + stop.getStopFacility().getLinkId()
                            + ":" + stop.getArrivalOffset() + ":" + stop.getDepartureOffset() + ":" + stop.isAwaitDepartureTime());
                    route.getDepartures().values().stream().sorted((a, b) -> a.getId().compareTo(b.getId())).forEach(departure ->
                            rows.add(departure.getId() + ":" + departure.getDepartureTime() + ":" + departure.getVehicleId()));
                }));
        scenario.getTransitVehicles().getVehicles().values().forEach(vehicle -> rows.add(vehicle.getId() + ":" + vehicle.getType().getId()
                + ":" + vehicle.getType().getCapacity().getSeats() + ":" + vehicle.getType().getCapacity().getStandingRoom()
                + ":" + vehicle.getType().getMaximumVelocity() + ":" + vehicle.getType().getPcuEquivalents()));
        return List.copyOf(rows);
    }

    private static final class Result {
        final Scenario scenario;
        final Id<Vehicle> vehicleId;
        final DispatchCommitmentState ledger;
        Trace trace;
        QSim qsim;
        ControlledTransitDriverAgent driver;
        ControlledTransitDriverAgent.RefreshResult futureRefresh;
        DispatchCommitmentState.Version acceptedBVersion;
        Umlauf nativeWork;
        List<String> baselineBefore;
        int factoryCalls;
        int controllerReschedules;
        int waitingBSamples;
        int terminalChecks;

        Result(Scenario scenario, Id<Vehicle> vehicleId, DispatchCommitmentState ledger) {
            this.scenario = scenario;
            this.vehicleId = vehicleId;
            this.ledger = ledger;
        }
    }

    private static final class Trace implements BasicEventHandler {
        final Result result;
        final List<TransitDriverStartsEvent> starts = new ArrayList<>();
        final List<VehicleDepartsAtFacilityEvent> aDepartures = new ArrayList<>(), bDepartures = new ArrayList<>();
        final List<VehicleArrivesAtFacilityEvent> aArrivals = new ArrayList<>(), bArrivals = new ArrayList<>();
        final List<Map<String, String>> aScientificEvents = new ArrayList<>();
        TripKey activeTrip;
        TransitDriverStartsEvent aStart, bStart;
        double aReadyArrival = Double.NaN, bTerminalArrival = Double.NaN, physicalReadyAtBStart = Double.NaN;
        int aLegEndCount, bLegEndCount, stuckEvents;

        Trace(Result result) { this.result = result; }

        @Override public void handleEvent(Event event) {
            if (event instanceof PersonStuckEvent) stuckEvents++;
            if (event instanceof TransitDriverStartsEvent start) {
                starts.add(start);
                activeTrip = new TripKey(start.getTransitLineId().toString(), start.getTransitRouteId().toString(), start.getDepartureId().toString());
                result.ledger.recordStarted(activeTrip, event.getTime(), event.getTime());
                if (activeTrip.equals(A)) aStart = start;
                else if (activeTrip.equals(B)) {
                    bStart = start;
                    physicalReadyAtBStart = result.driver.currentExecutionState().physicalReadySeconds();
                    assertEquals(Optional.of(result.acceptedBVersion), result.driver.currentExecutionState().acceptedVersion());
                } else fail("Unexpected third service: " + activeTrip);
            }
            if (A.equals(activeTrip)) aScientificEvents.add(new TreeMap<>(event.getAttributes()));
            if (event instanceof VehicleDepartsAtFacilityEvent departure) {
                assertEquals(result.vehicleId, departure.getVehicleId());
                if (A.equals(activeTrip)) aDepartures.add(departure);
                else if (B.equals(activeTrip)) bDepartures.add(departure);
                else fail("Facility departure has no real native service-start association");
            }
            if (event instanceof VehicleArrivesAtFacilityEvent arrival) {
                assertEquals(result.vehicleId, arrival.getVehicleId());
                if (A.equals(activeTrip)) aArrivals.add(arrival);
                else if (B.equals(activeTrip)) bArrivals.add(arrival);
                else fail("Facility arrival has no real native service-start association");
            }
            if (event instanceof PersonArrivalEvent arrival && arrival.getPersonId().equals(result.driver.getId())) {
                if (A.equals(activeTrip)) { aReadyArrival = arrival.getTime(); aLegEndCount++; }
                else if (B.equals(activeTrip)) { bTerminalArrival = arrival.getTime(); bLegEndCount++; }
            }
        }

        VehicleDepartsAtFacilityEvent firstBDeparture() { return bDepartures.getFirst(); }
    }
}
