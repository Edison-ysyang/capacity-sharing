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
import org.matsim.api.core.v01.population.Person;
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
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.core.mobsim.qsim.pt.ControlledTransitDispatchBridge.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;

/**
 * Real, in-memory QSim checks of the complete dispatch bridge, using default native
 * Umlauf reconstruction. No manual starts, leg transitions, queue calls or fabricated
 * movement/facility events occur in this fixture. There are no passengers, files or
 * formal inputs. Explicit synthetic windows/headways/connections are test inputs, not
 * calibrated operating standards. Passing them retains REGISTERED_PLANNED_TIMES_ONLY
 * scope; these small runs do not establish full policy feasibility, parallel execution
 * correctness, railway safety, passenger feedback or the formal M/T equivalence gate.
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDriverNativeBridgeWorkTest {
    // Keep names/TripKeys, not typed MATSim Ids, static across AutoResetIdCaches epochs.
    private static final TripKey A = new TripKey("bridge-work-line", "route-A", "departure-A");
    private static final TripKey B = new TripKey("bridge-work-line", "route-B", "departure-B");
    private static final double END = 300;
    private enum Shape { SAME_VEHICLE, TWO_VEHICLES }
    private enum Mode { NATIVE, FUTURE_B, VALID_BATCH, INVALID_TAIL }

    @Test
    void futureSameVehicleCommitmentPassesWholeBridgeAndExecutesAtItsNaturalWorkSegment() {
        Result baseline = run(Shape.SAME_VEHICLE, Mode.NATIVE);
        Result controlled = run(Shape.SAME_VEHICLE, Mode.FUTURE_B);
        assertCommon(baseline);
        assertCommon(controlled);
        assertEquals(baseline.trace.aServiceRecords, controlled.trace.aServiceRecords,
                "Changing future B must preserve all real events associated with running A");
        assertEquals(baseline.trace.legEnds.get(A), controlled.trace.legEnds.get(A));
        assertEquals(1, controlled.factoryCalls);
        assertSame(controlled.drivers.get(A), controlled.drivers.get(B));
        assertEquals(List.of(A, B), controlled.workByVehicle.values().iterator().next().getUmlaufStuecke().stream()
                .map(work -> new TripKey(work.getLine().getId().toString(), work.getRoute().getId().toString(),
                        work.getDeparture().getId().toString())).toList());
        ApplicationAudit audit = controlled.applied;
        assertEquals(Status.COMMITTED_APPLIED, audit.status());
        assertEquals(List.of(B), audit.decision().orElseThrow().changes().stream().map(CommitmentChange::key).toList());
        assertEquals(ControlledTransitDriverAgent.RefreshOutcome.FUTURE_ONLY, audit.refreshedTrips().getFirst().result().outcome());
        assertTrue(audit.requeuedDriverIds().isEmpty(), "Running A must not be removed/reinserted for a future-B update");
        assertRegisteredScope(audit);
        assertEquals(1, audit.constraints().orElseThrow().coverage().registeredVehicleConnections());
        assertTrue(controlled.successorWaitingSamples > 0, "B must naturally become a waiting native ACTIVITY before its accepted time");
        double actualReady = controlled.trace.legEnds.get(A).getFirst();
        assertTrue(actualReady < 100, "This accepted connection is not the earlier infeasible-ready stress fixture");
        assertEquals(actualReady, controlled.trace.readyAtBStart, 0.0);
        assertTrue(controlled.trace.starts.get(B).getTime() >= actualReady);
        assertEquals(10, controlled.trace.starts.get(A).getTime(), 0.0);
        assertEquals(20, controlled.trace.departures.get(A).getFirst().getTime(), 0.0);
        assertEquals(100, controlled.trace.starts.get(B).getTime(), 0.0);
        assertEquals(107, controlled.trace.departures.get(B).getFirst().getTime(), 0.0);
        assertEquals(-20, controlled.trace.departures.get(B).getFirst().getDelay(), 0.0);
        assertEquals(127, baseline.trace.departures.get(B).getFirst().getTime(), 0.0);
        assertEquals(100, controlled.ledger.snapshot().commitments().get(B), 0.0);
    }

    @Test
    void validTwoDriverBatchRequeuesEachOnceAndProducesBothRealFirstStopDepartures() {
        Result result = run(Shape.TWO_VEHICLES, Mode.VALID_BATCH);
        assertCommon(result);
        assertEquals(2, result.factoryCalls);
        assertNotSame(result.drivers.get(A), result.drivers.get(B));
        ApplicationAudit audit = result.applied;
        assertRegisteredScope(audit);
        assertEquals(2, audit.decision().orElseThrow().changes().size());
        assertEquals(2, audit.refreshedTrips().size());
        assertTrue(audit.refreshedTrips().stream().allMatch(r -> r.result().requiresReschedule()));
        assertEquals(List.of(result.drivers.get(A).getId().toString(), result.drivers.get(B).getId().toString()),
                audit.requeuedDriverIds(), "Exactly one returned native queue call per affected driver, in fixed ledger order");
        assertEquals(2, new LinkedHashSet<>(audit.requeuedDriverIds()).size());
        assertEquals(80, result.trace.starts.get(A).getTime(), 0.0);
        assertEquals(180, result.trace.starts.get(B).getTime(), 0.0);
        assertEquals(90, result.trace.departures.get(A).getFirst().getTime(), 0.0);
        assertEquals(190, result.trace.departures.get(B).getFirst().getTime(), 0.0);
        assertEquals(-20, result.trace.departures.get(A).getFirst().getDelay(), 0.0);
        assertEquals(20, result.trace.departures.get(B).getFirst().getDelay(), 0.0);
        assertEquals(1, audit.constraints().orElseThrow().coverage().registeredHeadwayPairs());
        assertEquals(1, result.ledger.history().size());
    }

    @Test
    void validFirstProposalWithInvalidTailRejectsWholeBatchAndMatchesAllDefaultNativeEvents() {
        Result baseline = run(Shape.TWO_VEHICLES, Mode.NATIVE);
        Result rejected = run(Shape.TWO_VEHICLES, Mode.INVALID_TAIL);
        assertCommon(baseline);
        assertCommon(rejected);
        assertEquals(baseline.trace.records, rejected.trace.records,
                "All real event types, times, attributes and ordering must match default native execution");
        ApplicationAudit audit = rejected.rejected;
        assertEquals(Status.REJECTED_PRECOMMIT, audit.status());
        assertEquals(List.of(A, B), new ArrayList<>(audit.batch().proposedDepartures().keySet()));
        assertEquals(80, audit.batch().proposedDepartures().get(A), 0.0);
        assertEquals(200, audit.batch().proposedDepartures().get(B), 0.0);
        assertTrue(audit.constraints().orElseThrow().baseline().satisfiesRegisteredConstraints());
        assertTrue(audit.constraints().orElseThrow().candidate().violations().stream()
                .anyMatch(check -> check.kind() == Kind.LAST_LATEST && check.trips().contains(B)));
        assertTrue(audit.decision().isEmpty());
        assertTrue(audit.refreshedTrips().isEmpty());
        assertTrue(audit.requeuedDriverIds().isEmpty());
        assertTrue(rejected.ledger.history().isEmpty());
        assertEquals(100, rejected.trace.starts.get(A).getTime(), 0.0);
        assertEquals(160, rejected.trace.starts.get(B).getTime(), 0.0);
        assertEquals(110, rejected.trace.departures.get(A).getFirst().getTime(), 0.0);
        assertEquals(170, rejected.trace.departures.get(B).getFirst().getTime(), 0.0);
    }

    private static void assertRegisteredScope(ApplicationAudit audit) {
        Comparison constraints = audit.constraints().orElseThrow();
        assertTrue(constraints.baseline().satisfiesRegisteredConstraints());
        assertTrue(constraints.candidate().satisfiesRegisteredConstraints());
        assertTrue(constraints.coverage().anyConstraintsRegistered());
        assertEquals(EvidenceScope.REGISTERED_PLANNED_TIMES_ONLY, audit.scope());
        assertFalse(constraints.coverage().sharedResourceCoverageVerified());
        assertFalse(constraints.coverage().actualVehicleCirculationVerified());
    }

    private static Result run(Shape shape, Mode mode) {
        Fixture fixture = fixture(shape);
        DispatchCommitmentState ledger = new DispatchCommitmentState(List.of(
                new ScheduledTrip(A, fixture.vehicles.get(A).getId().toString(), fixture.baselines.get(A)),
                new ScheduledTrip(B, fixture.vehicles.get(B).getId().toString(), fixture.baselines.get(B))),
                new Limits(60, 60, 5));
        ledger.beginIteration(20, 0, true);
        Result result = new Result(fixture, ledger, mode);
        var events = EventsUtils.createEventsManager();
        result.trace = new Trace(result);
        events.addHandler(result.trace);
        AtomicReference<ControlledTransitDispatchBridge> bridgeReference = new AtomicReference<>();
        TransitDriverAgentFactory factory = (umlauf, internal, tracker) -> {
            result.factoryCalls++;
            int expectedWorks = shape == Shape.SAME_VEHICLE ? 2 : 1;
            assertEquals(expectedWorks, umlauf.getUmlaufStuecke().size());
            assertTrue(umlauf.getUmlaufStuecke().stream().allMatch(work -> work.isFahrt()),
                    "Exact shared endpoint avoids Wenden; no extra native work may be inserted here");
            AbstractTransitDriverAgent driver = mode == Mode.NATIVE
                    ? new DefaultTransitDriverAgentFactory().createTransitDriver(umlauf, internal, tracker)
                    : bridgeReference.get().createTransitDriver(umlauf, internal, tracker);
            assertNull(result.workByVehicle.put(umlauf.getVehicleId().toString(), umlauf));
            for (var work : umlauf.getUmlaufStuecke()) {
                TripKey key = new TripKey(work.getLine().getId().toString(), work.getRoute().getId().toString(),
                        work.getDeparture().getId().toString());
                assertNull(result.drivers.put(key, driver), "Each original service must be registered exactly once");
            }
            assertSame(driver.getId(), Id.get(driver.getId().toString(), Person.class));
            assertSame(driver.getId(), Id.get(driver.getId().index(), Person.class));
            return driver;
        };
        PrepareForSimUtils.createDefaultPrepareForSim(fixture.scenario).run();
        result.originalSupply = supplySnapshot(fixture.scenario);
        QSim qsim = new QSimBuilder(fixture.scenario.getConfig()).useDefaults().addQSimModule(new AbstractQSimModule() {
            @Override protected void configureQSim() {
                OptionalBinder.newOptionalBinder(binder(), TransitDriverAgentFactory.class).setBinding().toInstance(factory);
            }
        }).build(fixture.scenario, events);
        result.qsim = qsim;
        double decisionTime = shape == Shape.SAME_VEHICLE ? 25 : 20;
        qsim.addQueueSimulationListeners((MobsimBeforeSimStepListener) event -> {
            double now = event.getSimulationTime();
            if (now == decisionTime - 1) result.priorStepObserved = true;
            if (now == decisionTime) result.observerDecisionVisits++;
            // This observer never assumes whether the bridge callback ran first at the same time.
            if (shape == Shape.SAME_VEHICLE && mode == Mode.FUTURE_B) {
                var state = ((ControlledTransitDriverAgent) result.drivers.get(B)).currentExecutionState();
                if (state.trip().equals(Optional.of(B)) && state.state() == MobsimAgent.State.ACTIVITY) {
                    result.successorWaitingSamples++;
                    assertEquals(result.trace.legEnds.get(A).getFirst(), state.physicalReadySeconds(), 0.0);
                    assertEquals(100, state.effectiveDeadlineSeconds(), 0.0);
                }
            }
            if (now == 270 || now == 295) {
                assertFinished(result);
                result.terminalChecks++;
            }
        });
        if (mode != Mode.NATIVE) {
            ControlledTransitDispatchBridge bridge = new ControlledTransitDispatchBridge(qsim, ledger, 20,
                    fixture.points, fixture.connections, (activeBridge, now) -> {
                        if (now != decisionTime) return;
                        result.controllerVisits++;
                        assertTrue(result.priorStepObserved, "Control occurs after initial queueing and at least one earlier native step");
                        Snapshot before = ledger.snapshot();
                        var historyBefore = ledger.history();
                        Map<TripKey, ControlledTransitDriverAgent.ExecutionState> states = new LinkedHashMap<>();
                        for (TripKey key : List.of(A, B)) {
                            ControlledTransitDriverAgent driver = (ControlledTransitDriverAgent) result.drivers.get(key);
                            assertSame(driver, qsim.getAgents().get(driver.getId()));
                            states.put(key, driver.currentExecutionState());
                        }
                        Map<TripKey, Double> proposed = new LinkedHashMap<>();
                        List<ObservedFact> observations;
                        if (mode == Mode.FUTURE_B) {
                            assertEquals(MobsimAgent.State.LEG, states.get(A).state());
                            assertEquals(Optional.of(A), states.get(A).trip());
                            TransitDriverStartsEvent realStart = result.trace.starts.get(A);
                            assertNotNull(realStart);
                            assertTrue(realStart.getTime() < now);
                            observations = List.of(new ObservedFact("native-A-start", realStart.getTime(), realStart.getTime()));
                            proposed.put(B, 100.0);
                        } else {
                            assertTrue(result.trace.starts.isEmpty());
                            assertEquals(MobsimAgent.State.ACTIVITY, states.get(A).state());
                            assertEquals(MobsimAgent.State.ACTIVITY, states.get(B).state());
                            assertEquals(100, result.drivers.get(A).getActivityEndTime(), 0.0);
                            assertEquals(160, result.drivers.get(B).getActivityEndTime(), 0.0);
                            observations = List.of();
                            proposed.put(A, 80.0); // First proposal is individually admissible against unchanged B.
                            proposed.put(B, mode == Mode.VALID_BATCH ? 180.0 : 200.0);
                        }
                        Batch batch = new Batch(before.version(), now, observations, proposed);
                        if (mode == Mode.INVALID_TAIL) {
                            PrecommitRejected rejected = assertThrows(PrecommitRejected.class, () -> activeBridge.submit(batch));
                            result.rejected = rejected.audit();
                            assertEquals(before, ledger.snapshot());
                            assertEquals(historyBefore, ledger.history());
                            for (TripKey key : List.of(A, B)) assertEquals(states.get(key),
                                    ((ControlledTransitDriverAgent) result.drivers.get(key)).currentExecutionState());
                        } else {
                            var driver = result.drivers.get(A);
                            var originalElement = driver.getCurrentPlanElement();
                            result.applied = activeBridge.submit(batch); // Bridge alone publishes, refreshes and requeues.
                            if (mode == Mode.FUTURE_B) {
                                assertEquals(states.get(A), ((ControlledTransitDriverAgent) driver).currentExecutionState());
                                assertSame(originalElement, driver.getCurrentPlanElement());
                                assertEquals(10, driver.getActivityEndTime(), 0.0, "A's active-leg anchor remains frozen");
                            }
                        }
                        assertEquals(result.originalSupply, supplySnapshot(fixture.scenario));
                    });
            result.bridge = bridge;
            bridgeReference.set(bridge);
            qsim.addQueueSimulationListeners(bridge);
        }
        qsim.run(); // Sole source of all real driver/facility/arrival events and lifecycle transitions.
        return result;
    }

    private static void assertCommon(Result result) {
        Fixture fixture = result.fixture;
        assertEquals(1, result.observerDecisionVisits);
        assertEquals(result.mode == Mode.NATIVE ? 0 : 1, result.controllerVisits);
        assertEquals(2, result.terminalChecks);
        assertEquals(2, result.trace.starts.size());
        assertEquals(0, result.trace.stuckEvents);
        assertEquals(fixture.shape == Shape.SAME_VEHICLE ? 1 : 2, result.factoryCalls);
        assertEquals(result.factoryCalls, result.qsim.getVehicles().size());
        assertEquals(result.originalSupply, supplySnapshot(fixture.scenario));
        assertTrue(fixture.scenario.getPopulation().getPersons().isEmpty());
        for (TripKey key : List.of(A, B)) {
            assertEquals(fixture.vehicles.get(key).getId(), result.trace.starts.get(key).getVehicleId());
            assertEquals(2, result.trace.departures.get(key).size());
            assertEquals(2, result.trace.arrivals.get(key).size());
            assertEquals(1, result.trace.legEnds.get(key).size());
            assertEquals(fixture.routes.get(key).getStops().stream().map(s -> s.getStopFacility().getId()).toList(),
                    result.trace.departures.get(key).stream().map(VehicleDepartsAtFacilityEvent::getFacilityId).toList());
            assertTrue(result.trace.departures.get(key).stream().allMatch(e -> e.getVehicleId().equals(fixture.vehicles.get(key).getId())));
            assertTrue(result.trace.legEnds.get(key).getFirst() >= result.trace.departures.get(key).getLast().getTime());
            assertEquals(result.trace.starts.get(key).getTime(), result.ledger.snapshot().startedAtSeconds().get(key), 0.0);
            assertSame(fixture.vehicles.get(key).getId(), Id.get(fixture.vehicles.get(key).getId().toString(), Vehicle.class));
        }
        if (result.bridge != null) {
            assertFalse(result.bridge.isPoisoned());
            assertEquals(1, result.bridge.history().size());
        } else assertTrue(result.ledger.history().isEmpty());
        assertFinished(result);
    }

    private static void assertFinished(Result result) {
        assertEquals(0, result.qsim.getAgentCounter().getLiving());
        assertEquals(0, result.qsim.getAgentCounter().getLost());
        for (AbstractTransitDriverAgent driver : new LinkedHashSet<>(result.drivers.values())) {
            assertEquals(MobsimAgent.State.ACTIVITY, driver.getState());
            assertNull(driver.getNextPlanElement());
            assertEquals(Double.POSITIVE_INFINITY, driver.getActivityEndTime());
            assertSame(driver, result.qsim.getAgents().get(driver.getId()));
            if (driver instanceof ControlledTransitDriverAgent controlled) {
                assertTrue(controlled.currentExecutionState().terminal());
                assertTrue(controlled.currentExecutionState().trip().isEmpty());
            }
        }
    }

    private static Fixture fixture(Shape shape) {
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
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-bridge-work-output")
                .toAbsolutePath().toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        boolean chain = shape == Shape.SAME_VEHICLE;
        Link first = link(scenario, "first", "n0", "n1", 0, chain ? 10 : 100);
        Link middle = link(scenario, "middle", "n1", "n2", chain ? 10 : 100, chain ? 400 : 100);
        Link terminal = chain ? link(scenario, "terminal", "n2", "n3", 410, 100) : middle;
        link(scenario, "unused-return", chain ? "n3" : "n2", "n0", chain ? 510 : 200, chain ? 510 : 200);
        TransitStopFacility origin = stop(scenario, "origin", first);
        TransitStopFacility middleStop = stop(scenario, "middle", middle);
        TransitStopFacility terminalStop = chain ? stop(scenario, "terminal", terminal) : middleStop;
        var schedule = scenario.getTransitSchedule();
        TransitLine line = schedule.getFactory().createTransitLine(Id.create(A.line(), TransitLine.class));
        schedule.addTransitLine(line);
        VehicleType type = VehicleUtils.createVehicleType(Id.create("bridge-work-type", VehicleType.class));
        type.getCapacity().setSeats(8);
        type.getCapacity().setStandingRoom(0);
        type.setMaximumVelocity(10);
        type.setPcuEquivalents(1);
        scenario.getTransitVehicles().addVehicleType(type);
        Vehicle vehicleA = VehicleUtils.createVehicle(Id.createVehicleId("bridge-work-vehicle-A"), type);
        Vehicle vehicleB = chain ? vehicleA : VehicleUtils.createVehicle(Id.createVehicleId("bridge-work-vehicle-B"), type);
        scenario.getTransitVehicles().addVehicle(vehicleA);
        if (!chain) scenario.getTransitVehicles().addVehicle(vehicleB);
        double baselineA = chain ? 10 : 100, baselineB = chain ? 120 : 160;
        TransitRoute routeA = route(scenario, line, A, first, middle, origin, middleStop, 10, chain ? 40 : 30, baselineA, vehicleA);
        TransitRoute routeB = route(scenario, line, B, chain ? middle : first, terminal,
                chain ? middleStop : origin, terminalStop, chain ? 7 : 10, chain ? 20 : 30, baselineB, vehicleB);
        List<ControlPoint> points = chain ? List.of(
                new ControlPoint(new ControlPointKey("A-origin", "synthetic"), List.of(new Call(A, 10)), 0, 0,
                        Optional.of(new FirstLastWindow(20, 20, 20, 20))),
                new ControlPoint(new ControlPointKey("B-origin", "synthetic"), List.of(new Call(B, 7)), 0, 0,
                        Optional.of(new FirstLastWindow(107, 147, 107, 147))))
                : List.of(new ControlPoint(new ControlPointKey("shared-origin", "synthetic-outbound"),
                        List.of(new Call(A, 10), new Call(B, 10)), 40, 130,
                        Optional.of(new FirstLastWindow(90, 130, 150, 190))));
        List<VehicleConnection> connections = chain ? List.of(new VehicleConnection("A-to-B", A, B, 40, 20)) : List.of();
        return new Fixture(shape, scenario, Map.of(A, vehicleA, B, vehicleB), Map.of(A, baselineA, B, baselineB),
                Map.of(A, routeA, B, routeB), points, connections);
    }

    private static Link link(Scenario scenario, String suffix, String fromName, String toName, double x, double length) {
        var network = scenario.getNetwork();
        Id<Node> from = Id.createNodeId("bridge-work-" + fromName), to = Id.createNodeId("bridge-work-" + toName);
        if (!network.getNodes().containsKey(from)) NetworkUtils.createAndAddNode(network, from, new Coord(x, 0));
        if (!network.getNodes().containsKey(to)) NetworkUtils.createAndAddNode(network, to, new Coord(x + length, 0));
        Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId("bridge-work-" + suffix),
                network.getNodes().get(from), network.getNodes().get(to), length, 10, 3_600, 1);
        link.setAllowedModes(Set.of(TransportMode.car));
        return link;
    }

    private static TransitStopFacility stop(Scenario scenario, String suffix, Link link) {
        var schedule = scenario.getTransitSchedule();
        TransitStopFacility stop = schedule.getFactory().createTransitStopFacility(
                Id.create("bridge-work-stop-" + suffix, TransitStopFacility.class), link.getCoord(), false);
        stop.setLinkId(link.getId());
        schedule.addStopFacility(stop);
        return stop;
    }

    private static TransitRoute route(Scenario scenario, TransitLine line, TripKey key, Link start, Link end,
                                      TransitStopFacility first, TransitStopFacility last, double firstOffset,
                                      double endOffset, double baseline, Vehicle vehicle) {
        var factory = scenario.getTransitSchedule().getFactory();
        var firstCall = factory.createTransitRouteStop(first, 0, firstOffset);
        firstCall.setAwaitDepartureTime(true);
        var lastCall = factory.createTransitRouteStop(last, endOffset, endOffset);
        lastCall.setAwaitDepartureTime(true);
        var networkRoute = RouteUtils.createLinkNetworkRouteImpl(start.getId(), end.getId());
        networkRoute.setLinkIds(start.getId(), List.of(), end.getId());
        TransitRoute route = factory.createTransitRoute(Id.create(key.route(), TransitRoute.class), networkRoute,
                List.of(firstCall, lastCall), TransportMode.pt);
        Departure departure = factory.createDeparture(Id.create(key.departure(), Departure.class), baseline);
        departure.setVehicleId(vehicle.getId());
        route.addDeparture(departure);
        line.addRoute(route);
        return route;
    }

    private static List<String> supplySnapshot(Scenario scenario) {
        List<String> rows = new ArrayList<>();
        scenario.getTransitSchedule().getTransitLines().values().forEach(line -> line.getRoutes().values().forEach(route -> {
            rows.add(line.getId() + ":" + route.getId() + ":" + route.getTransportMode() + ":" + route.getRoute().getStartLinkId()
                    + ":" + route.getRoute().getLinkIds() + ":" + route.getRoute().getEndLinkId());
            route.getStops().forEach(stop -> rows.add(stop.getStopFacility().getId() + ":" + stop.getStopFacility().getLinkId()
                    + ":" + stop.getArrivalOffset() + ":" + stop.getDepartureOffset() + ":" + stop.isAwaitDepartureTime()));
            route.getDepartures().values().forEach(departure -> rows.add(departure.getId() + ":" + departure.getDepartureTime()
                    + ":" + departure.getVehicleId()));
        }));
        scenario.getTransitVehicles().getVehicles().values().forEach(vehicle -> rows.add(vehicle.getId() + ":" + vehicle.getType().getId()
                + ":" + vehicle.getType().getCapacity().getSeats() + ":" + vehicle.getType().getCapacity().getStandingRoom()
                + ":" + vehicle.getType().getMaximumVelocity() + ":" + vehicle.getType().getPcuEquivalents()));
        return List.copyOf(rows);
    }

    private record Fixture(Shape shape, Scenario scenario, Map<TripKey, Vehicle> vehicles, Map<TripKey, Double> baselines,
                           Map<TripKey, TransitRoute> routes, List<ControlPoint> points, List<VehicleConnection> connections) { }

    private record NativeEvent(String type, double time, Map<String, String> attributes) { }

    private static final class Result {
        final Fixture fixture;
        final DispatchCommitmentState ledger;
        final Mode mode;
        final Map<TripKey, AbstractTransitDriverAgent> drivers = new LinkedHashMap<>();
        final Map<String, Umlauf> workByVehicle = new LinkedHashMap<>();
        QSim qsim;
        ControlledTransitDispatchBridge bridge;
        Trace trace;
        ApplicationAudit applied, rejected;
        List<String> originalSupply;
        int factoryCalls, observerDecisionVisits, controllerVisits, terminalChecks, successorWaitingSamples;
        boolean priorStepObserved;
        Result(Fixture fixture, DispatchCommitmentState ledger, Mode mode) {
            this.fixture = fixture;
            this.ledger = ledger;
            this.mode = mode;
        }
    }

    private static final class Trace implements BasicEventHandler {
        final Result result;
        final List<NativeEvent> records = new ArrayList<>(), aServiceRecords = new ArrayList<>();
        final Map<TripKey, TransitDriverStartsEvent> starts = new LinkedHashMap<>();
        final Map<TripKey, List<VehicleDepartsAtFacilityEvent>> departures = new LinkedHashMap<>();
        final Map<TripKey, List<VehicleArrivesAtFacilityEvent>> arrivals = new LinkedHashMap<>();
        final Map<TripKey, List<Double>> legEnds = new LinkedHashMap<>();
        final Map<Id<Vehicle>, TripKey> activeByVehicle = new LinkedHashMap<>();
        final Map<Id<Person>, TripKey> activeByDriver = new LinkedHashMap<>();
        double readyAtBStart = Double.NaN;
        int stuckEvents;
        Trace(Result result) { this.result = result; }

        @Override public void handleEvent(Event event) {
            NativeEvent record = new NativeEvent(event.getClass().getName(), event.getTime(), Map.copyOf(new TreeMap<>(event.getAttributes())));
            records.add(record);
            if (event instanceof TransitDriverStartsEvent start) {
                TripKey trip = new TripKey(start.getTransitLineId().toString(), start.getTransitRouteId().toString(), start.getDepartureId().toString());
                assertTrue(result.fixture.baselines.containsKey(trip));
                assertNull(starts.put(trip, start), "No duplicate native service start may occur");
                activeByVehicle.put(start.getVehicleId(), trip);
                activeByDriver.put(start.getDriverId(), trip);
                result.ledger.recordStarted(trip, start.getTime(), start.getTime()); // Only real QSim events update starts.
                if (trip.equals(B) && result.fixture.shape == Shape.SAME_VEHICLE && result.mode == Mode.FUTURE_B) {
                    var state = ((ControlledTransitDriverAgent) result.drivers.get(B)).currentExecutionState();
                    readyAtBStart = state.physicalReadySeconds();
                    assertEquals(Optional.of(result.applied.decision().orElseThrow().resultingVersion()), state.acceptedVersion());
                }
            }
            if (result.fixture.shape == Shape.SAME_VEHICLE && A.equals(activeByVehicle.get(result.fixture.vehicles.get(A).getId()))) {
                aServiceRecords.add(record);
            }
            if (event instanceof VehicleDepartsAtFacilityEvent departure) {
                TripKey trip = activeByVehicle.get(departure.getVehicleId());
                assertNotNull(trip, "Facility departure must have a real service-start association");
                departures.computeIfAbsent(trip, ignored -> new ArrayList<>()).add(departure);
            }
            if (event instanceof VehicleArrivesAtFacilityEvent arrival) {
                TripKey trip = activeByVehicle.get(arrival.getVehicleId());
                assertNotNull(trip, "Facility arrival must have a real service-start association");
                arrivals.computeIfAbsent(trip, ignored -> new ArrayList<>()).add(arrival);
            }
            if (event instanceof PersonArrivalEvent arrival) {
                TripKey trip = activeByDriver.get(arrival.getPersonId());
                assertNotNull(trip, "Empty-population fixture should contain only registered driver arrivals");
                legEnds.computeIfAbsent(trip, ignored -> new ArrayList<>()).add(arrival.getTime());
            }
            if (event instanceof PersonStuckEvent) stuckEvents++;
        }
    }
}
