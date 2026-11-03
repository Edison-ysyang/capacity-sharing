package org.matsim.core.mobsim.qsim.pt;

import com.google.inject.multibindings.OptionalBinder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.PrepareForSimUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimAfterSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.mobsim.qsim.QSimPreparationCheck;
import org.matsim.core.mobsim.qsim.QSimRuntimeInspection;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngineI;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimRuntimeInspection;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimRuntimeInspection.ExecutionPhase.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;

/**
 * Real multi-runner queue/bridge boundary tests, not a freight experiment. A single
 * vehicle crosses native network partitions; all configured runner objects exist,
 * but this tiny network does not claim that seven workers are simultaneously busy.
 * The rolling toy policy uses an explicit test sequence, not a scientific forecast.
 * No native movement/start/stop events are synthesized. No historical inputs change.
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDispatchBridgeParallelNativeTest {
    private static final TripKey TRIP = new TripKey("parallel-line", "parallel-route", "parallel-departure");
    private static final String VEHICLE = "parallel-vehicle";
    private static final String ORIGIN = "parallel-origin";
    private static final String DESTINATION = "parallel-destination";

    @Test
    void optionalTrackingPreservesNativeEventsWithSevenRunnersAndSimStepParallelEvents() {
        Result original = run(7, true, false, false, false);
        Result tracked = run(7, true, true, false, false);
        assertEquals(original.events(), tracked.events());
        assertEquals(100, start(tracked), 0.0);
        assertEquals(110, departure(tracked), 0.0);
        assertEquals("org.matsim.core.events.SimStepParallelEventsManagerImpl", tracked.managerClass());
        assertEquals(original.managerClass(), tracked.managerClass());
        assertNull(original.barrier());
        assertEquals(CLOSED, tracked.barrier().snapshot().phase());
        assertEquals(7, tracked.barrier().snapshot().actualRunnerCount());
        assertTrue(tracked.beforeCalls() > 150);
        assertEquals(tracked.beforeCalls(), tracked.afterCalls());
    }

    @Test
    void actualTwoAndSevenRunnerBridgesApplyRollingChangesOnlyInsideRealBefore() {
        for (int runners : List.of(2, 7)) {
            Result controlled = run(runners, false, true, true, false);
            assertEquals(90, start(controlled), 0.0);
            assertEquals(100, departure(controlled), 0.0);
            assertEquals(runners, controlled.barrier().snapshot().actualRunnerCount());
            assertEquals(CLOSED, controlled.barrier().snapshot().phase());
            assertEquals(6, controlled.bridge().history().size());
            assertEquals(5, controlled.bridge().history().stream()
                    .filter(audit -> audit.status() == ControlledTransitDispatchBridge.Status.COMMITTED_APPLIED).count());
            assertEquals(1, controlled.bridge().history().stream()
                    .filter(audit -> audit.status() == ControlledTransitDispatchBridge.Status.REJECTED_PRECOMMIT).count());
            assertEquals(4, controlled.bridge().history().stream().mapToLong(audit -> audit.requeuedDriverIds().size()).sum());
            assertEquals(4, controlled.bridge().history().stream().mapToLong(audit -> audit.refreshedTrips().size()).sum());
            assertFalse(controlled.bridge().isPoisoned());
            assertTrue(controlled.workerCallbacks() > 0, "Real native QNetsim worker callbacks must exercise the running phase");
            assertTrue(controlled.barrier().snapshot().runnerIdentitiesUnchanged());
            assertEquals("org.matsim.core.events.SynchronizedEventsManagerImpl", controlled.managerClass());
        }
    }

    @Test
    void actualNativeWorkerFailureDoesNotPublishIdleAndRemainsFailedThroughCleanup() {
        Result failed = run(7, false, true, false, true);
        assertNotNull(failed.failure());
        assertEquals(FAILED, failed.barrier().snapshot().phase());
        assertTrue(failed.barrier().snapshot().lastCompletedStepSeconds().orElseThrow() < 110);
        assertEquals(110, departure(failed), 0.0,
                "The failure is injected into a real native first-stop callback, not a synthetic engine step");
        assertTrue(failed.workerCallbacks() > 0);
    }

    private static Result run(int runners, boolean parallelEvents, boolean track, boolean controlled, boolean failWorker) {
        Fixture fixture = fixture(runners);
        var config = fixture.scenario().getConfig();
        config.eventsManager().setNumberOfThreads(2);
        config.eventsManager().setSynchronizeOnSimSteps(true);
        var events = parallelEvents ? EventsUtils.createEventsManager(config) : EventsUtils.createEventsManager();
        List<NativeEvent> nativeTrace = new ArrayList<>();
        AtomicReference<QSim> qsimRef = new AtomicReference<>();
        AtomicReference<QNetsimRuntimeInspection.ExecutionBarrier> barrierRef = new AtomicReference<>();
        AtomicReference<ControlledTransitDispatchBridge> bridgeRef = new AtomicReference<>();
        AtomicInteger workerCallbacks = new AtomicInteger();
        events.addHandler((BasicEventHandler) event -> {
            nativeTrace.add(new NativeEvent(event.getClass().getName(), event.getTime(), Map.copyOf(event.getAttributes())));
            var barrier = barrierRef.get();
            if (barrier != null && Thread.currentThread().getName().startsWith("QNetsimEngine_PooledThread_")) {
                workerCallbacks.incrementAndGet();
                var snapshot = barrier.snapshot();
                assertFalse(snapshot.callerIsOwnerThread());
                assertTrue(snapshot.phase() == RUNNING_NODES || snapshot.phase() == RUNNING_LINKS);
                assertThrows(IllegalStateException.class, () -> barrier.requireBeforeStep(qsimRef.get(), event.getTime()));
            }
            if (failWorker && event instanceof VehicleDepartsAtFacilityEvent departure
                    && departure.getFacilityId().toString().equals(ORIGIN)) {
                throw new IllegalStateException("synthetic test exception inside a real native worker event");
            }
        });
        AtomicInteger beforeCalls = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        QSimPreparationCheck preparation = qsim -> {
            var engine = qsim.getChildInjector().getInstance(QNetsimEngineI.class);
            var initial = QNetsimRuntimeInspection.inspect(engine);
            assertEquals(runners, initial.constructionThreadCount());
            assertEquals(runners, initial.actualRunnerCount().orElseThrow());
            var barrier = QNetsimRuntimeInspection.trackPreparedExecution(qsim, engine);
            assertNull(barrierRef.getAndSet(barrier));
            assertDoesNotThrow(() -> barrier.requirePrepared(qsim));
            assertEquals(PREPARED, barrier.snapshot().phase());
            assertEquals(0, barrier.snapshot().completedSteps());
            assertThrows(IllegalStateException.class, () -> QNetsimRuntimeInspection.trackPreparedExecution(qsim, engine));
            assertThrows(IllegalStateException.class, () -> barrier.requireBeforeStep(qsim, 0));
            if (controlled) {
                bridgeRef.get().bindExecutionBarrier(barrier);
                assertThrows(IllegalStateException.class, () -> bridgeRef.get().bindExecutionBarrier(barrier));
                var before = bridgeRef.get().snapshot();
                assertThrows(IllegalStateException.class, () -> bridgeRef.get()
                        .notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(qsim, 0)));
                assertEquals(before, bridgeRef.get().snapshot(), "A fabricated callback must not publish or queue anything");
            }
        };
        var builder = new QSimBuilder(config).useDefaults().addOverridingQSimModule(new AbstractQSimModule() {
            @Override protected void configureQSim() {
                if (track) bind(QSimPreparationCheck.class).toInstance(preparation);
                if (controlled) OptionalBinder.newOptionalBinder(binder(), TransitDriverAgentFactory.class).setBinding()
                        .toInstance((umlauf, internal, tracker) -> bridgeRef.get().createTransitDriver(umlauf, internal, tracker));
            }
        });
        PrepareForSimUtils.createDefaultPrepareForSim(fixture.scenario()).run();
        QSim qsim = builder.build(fixture.scenario(), events, 20);
        qsimRef.set(qsim);
        var engine = qsim.getChildInjector().getInstance(QNetsimEngineI.class);
        assertThrows(IllegalStateException.class, () -> QNetsimRuntimeInspection.trackPreparedExecution(qsim, engine));
        if (controlled) {
            DispatchCommitmentState ledger = new DispatchCommitmentState(List.of(new ScheduledTrip(TRIP, VEHICLE, 100)), new Limits(30, 30, 5));
            ledger.beginIteration(20, 0, true);
            var point = new ControlPoint(new ControlPointKey(ORIGIN, "toy-outbound"), List.of(new Call(TRIP, 10)), 0, 0,
                    Optional.of(new FirstLastWindow(90, 130, 90, 130)));
            var bridge = new ControlledTransitDispatchBridge(qsim, ledger, 20, List.of(point), List.of(), (active, now) -> {
                assertTrue(QSimRuntimeInspection.isAtBeforeSimStep(qsim));
                var barrier = barrierRef.get();
                assertDoesNotThrow(() -> barrier.requireBeforeStep(qsim, now));
                if (!List.of(20.0, 30.0, 40.0, 50.0, 60.0, 70.0).contains(now)) return;
                double target = switch ((int) now) {
                    case 20 -> 80; case 30 -> 120; case 40, 60 -> 100; case 50 -> 75; case 70 -> 90;
                    default -> throw new AssertionError("Unexpected test decision time");
                };
                var before = active.snapshot();
                var driver = qsim.getAgents().values().stream().filter(ControlledTransitDriverAgent.class::isInstance)
                        .map(ControlledTransitDriverAgent.class::cast).findFirst().orElseThrow();
                assertEquals(before.commitments().get(TRIP), driver.getActivityEndTime(), 0.0);
                var batch = new Batch(before.version(), now, List.of(), Map.of(TRIP, target));
                if (now == 50) {
                    var rejected = assertThrows(ControlledTransitDispatchBridge.PrecommitRejected.class, () -> active.submit(batch));
                    assertEquals(before, active.snapshot());
                    assertEquals(before.commitments().get(TRIP), driver.getActivityEndTime(), 0.0);
                    assertTrue(rejected.audit().refreshedTrips().isEmpty());
                    assertTrue(rejected.audit().requeuedDriverIds().isEmpty());
                } else {
                    var applied = active.submit(batch);
                    assertEquals(ControlledTransitDispatchBridge.Status.COMMITTED_APPLIED, applied.status());
                    assertEquals(now == 60 ? 0 : 1, applied.requeuedDriverIds().size());
                    assertEquals(target, driver.getActivityEndTime(), 0.0);
                }
            });
            bridgeRef.set(bridge);
            qsim.addQueueSimulationListeners(bridge);
        }
        if (track) {
            qsim.addQueueSimulationListeners((MobsimInitializedListener) event -> {
                var barrier = barrierRef.get();
                assertThrows(IllegalStateException.class, () -> barrier.requirePrepared(qsim));
                assertThrows(IllegalStateException.class, () -> barrier.requireBeforeStep(qsim, qsim.getSimTimer().getTimeOfDay()));
            });
            qsim.addQueueSimulationListeners((MobsimBeforeSimStepListener) event -> {
                var barrier = barrierRef.get();
                double now = event.getSimulationTime();
                assertDoesNotThrow(() -> barrier.requireBeforeStep(qsim, now));
                var snapshot = barrier.snapshot();
                assertEquals(runners, snapshot.actualRunnerCount());
                assertEquals((long) now, snapshot.completedSteps());
                assertEquals(now == 0 ? PREPARED : BETWEEN_STEPS, snapshot.phase());
                if (now > 0) assertEquals(now - 1, snapshot.lastCompletedStepSeconds().orElseThrow(), 0.0);
                beforeCalls.incrementAndGet();
            });
            qsim.addQueueSimulationListeners((MobsimAfterSimStepListener) event -> {
                var barrier = barrierRef.get();
                assertEquals(BETWEEN_STEPS, barrier.snapshot().phase());
                assertEquals(event.getSimulationTime(), barrier.snapshot().lastCompletedStepSeconds().orElseThrow(), 0.0);
                assertThrows(IllegalStateException.class, () -> barrier.requireBeforeStep(qsim, event.getSimulationTime()));
                afterCalls.incrementAndGet();
            });
        }
        RuntimeException failure = failWorker ? assertThrows(RuntimeException.class, qsim::run) : null;
        if (!failWorker) qsim.run();
        if (track) assertThrows(IllegalStateException.class,
                () -> barrierRef.get().requireBeforeStep(qsim, qsim.getSimTimer().getTimeOfDay()));
        assertFalse(QSimRuntimeInspection.isAtBeforeSimStep(qsim));
        assertEquals(runners, config.qsim().getNumberOfThreads());
        assertEquals(100, fixture.departure().getDepartureTime(), 0.0);
        assertEquals(VEHICLE, fixture.departure().getVehicleId().toString());
        return new Result(List.copyOf(nativeTrace), barrierRef.get(), bridgeRef.get(), beforeCalls.get(), afterCalls.get(),
                workerCallbacks.get(), qsim.getEventsManager().getClass().getName(), failure);
    }

    private static double start(Result result) {
        return result.events().stream().filter(event -> event.type().equals(TransitDriverStartsEvent.class.getName()))
                .findFirst().orElseThrow().time();
    }
    private static double departure(Result result) {
        return result.events().stream().filter(event -> event.type().equals(VehicleDepartsAtFacilityEvent.class.getName())
                && ORIGIN.equals(event.attributes().get("facility"))).findFirst().orElseThrow().time();
    }

    private static Fixture fixture(int runners) {
        var config = ConfigUtils.createConfig();
        config.global().setNumberOfThreads(1); config.qsim().setNumberOfThreads(runners);
        config.transit().setUseTransit(true); config.transit().setUsingTransitInMobsim(true);
        config.qsim().setStartTime(0); config.qsim().setEndTime(180); config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-parallel-bridge-output")
                .toAbsolutePath().toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork();
        var a = NetworkUtils.createAndAddNode(network, Id.createNodeId("parallel-a"), new Coord(0, 0));
        var b = NetworkUtils.createAndAddNode(network, Id.createNodeId("parallel-b"), new Coord(100, 0));
        var c = NetworkUtils.createAndAddNode(network, Id.createNodeId("parallel-c"), new Coord(200, 0));
        Link first = NetworkUtils.createAndAddLink(network, Id.createLinkId("parallel-first"), a, b, 100, 10, 3600, 1);
        Link last = NetworkUtils.createAndAddLink(network, Id.createLinkId("parallel-last"), b, c, 100, 10, 3600, 1);
        Link back = NetworkUtils.createAndAddLink(network, Id.createLinkId("parallel-back"), c, a, 200, 10, 3600, 1);
        for (var link : List.of(first, last, back)) link.setAllowedModes(Set.of(TransportMode.car));
        var sf = scenario.getTransitSchedule().getFactory();
        var origin = sf.createTransitStopFacility(Id.create(ORIGIN, TransitStopFacility.class), first.getCoord(), false);
        var destination = sf.createTransitStopFacility(Id.create(DESTINATION, TransitStopFacility.class), last.getCoord(), false);
        origin.setLinkId(first.getId()); destination.setLinkId(last.getId());
        scenario.getTransitSchedule().addStopFacility(origin); scenario.getTransitSchedule().addStopFacility(destination);
        var firstCall = sf.createTransitRouteStop(origin, 0, 10); firstCall.setAwaitDepartureTime(true);
        var lastCall = sf.createTransitRouteStop(destination, 20, 30); lastCall.setAwaitDepartureTime(true);
        var path = RouteUtils.createLinkNetworkRouteImpl(first.getId(), last.getId());
        path.setLinkIds(first.getId(), List.of(), last.getId());
        var route = sf.createTransitRoute(Id.create(TRIP.route(), TransitRoute.class), path, List.of(firstCall, lastCall), TransportMode.pt);
        var line = sf.createTransitLine(Id.create(TRIP.line(), TransitLine.class));
        line.addRoute(route); scenario.getTransitSchedule().addTransitLine(line);
        var type = VehicleUtils.createVehicleType(Id.create("parallel-type", VehicleType.class));
        type.getCapacity().setSeats(4); type.getCapacity().setStandingRoom(2); type.setMaximumVelocity(10); type.setPcuEquivalents(1);
        scenario.getTransitVehicles().addVehicleType(type);
        var vehicle = VehicleUtils.createVehicle(Id.createVehicleId(VEHICLE), type); scenario.getTransitVehicles().addVehicle(vehicle);
        var departure = sf.createDeparture(Id.create(TRIP.departure(), Departure.class), 100);
        departure.setVehicleId(vehicle.getId()); route.addDeparture(departure);
        return new Fixture(scenario, departure);
    }

    private record Fixture(Scenario scenario, Departure departure) { }
    private record NativeEvent(String type, double time, Map<String, String> attributes) { }
    private record Result(List<NativeEvent> events, QNetsimRuntimeInspection.ExecutionBarrier barrier,
                          ControlledTransitDispatchBridge bridge, int beforeCalls, int afterCalls,
                          int workerCallbacks, String managerClass, RuntimeException failure) { }
}
