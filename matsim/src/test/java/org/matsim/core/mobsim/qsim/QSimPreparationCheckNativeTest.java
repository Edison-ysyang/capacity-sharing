package org.matsim.core.mobsim.qsim;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonInitializedEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeCleanupEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeCleanupListener;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;
import org.matsim.core.mobsim.qsim.interfaces.ActivityHandler;
import org.matsim.core.mobsim.qsim.interfaces.MobsimEngine;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngineI;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimRuntimeInspection;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.core.mobsim.qsim.QSimRuntimeInspection.Role.*;

/**
 * Real one-person, one-runner QSim runs, with no hand-fired lifecycle or movement
 * events. The added engine/handler only records native callbacks and always
 * declines activities, leaving all actual movement to the default engines.
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class QSimPreparationCheckNativeTest {
    @Test
    void nativeBoundariesSurroundPreparationAndPassiveOptInPreservesEveryEvent() {
        Fixture unbound = fixture(false);
        Fixture bound = fixture(true);
        assertFalse(QSimRuntimeInspection.isAtPreparationStart(bound.qsim));
        assertFalse(QSimRuntimeInspection.isAtPreparationCheck(bound.qsim));
        unbound.qsim.run();
        bound.qsim.run();

        assertEquals(unbound.probe.events, bound.probe.events);
        assertFalse(bound.probe.events.isEmpty());
        assertTrue(bound.probe.events.stream().anyMatch(e -> e.eventClass.equals(PersonInitializedEvent.class.getName())));
        assertTrue(bound.probe.events.stream().anyMatch(e -> e.eventClass.equals(PersonArrivalEvent.class.getName())));
        assertEquals(bound.probe.events, bound.check.receivedFromPreparationStart,
                "An observer installed at the first boundary includes native preparation events too");
        assertEquals(List.of("start", "engine-before", "prepared", "initialized", "first-activity",
                "first-step", "cleanup-listener", "engine-after"), bound.probe.lifecycle);
        assertEquals(List.of("engine-before", "initialized", "first-activity", "first-step",
                "cleanup-listener", "engine-after"), unbound.probe.lifecycle);
        assertEquals(1, bound.check.startCalls);
        assertEquals(1, bound.check.preparedCalls);
        assertEquals(1, bound.probe.initializedCalls);
        assertEquals(bound.probe.stepCalls, bound.check.drains.size());
        assertEquals(1, bound.check.finishedCalls);
        assertTrue(bound.probe.activityCalls >= 2, "Native initial and destination activities both reached the handler");
        assertTrue(bound.probe.cleanupSawClearedScopes);
        assertScopesClear(bound.qsim);
        assertScopesClear(unbound.qsim);

        // Calling the same object directly after the run cannot manufacture native boundary evidence.
        assertThrows(IllegalStateException.class, () -> bound.check.beforePreparation(bound.qsim));
        assertThrows(IllegalStateException.class, () -> bound.check.afterPrepared(bound.qsim));
        assertThrows(IllegalStateException.class, () -> bound.check.afterEventsProcessed(bound.qsim, 0));
        assertThrows(IllegalStateException.class, () -> bound.check.afterEventsFinished(bound.qsim));
        assertEquals(1, bound.check.startCalls);
        assertEquals(1, bound.check.preparedCalls);
    }

    @Test
    void preparedRuntimeFailurePrecedesInitializedAndInitialQueueButStillCleansEngines() {
        Fixture fixture = fixture(true);
        IllegalStateException rejected = new IllegalStateException("synthetic prepared audit rejection");
        fixture.check.failure = rejected;
        assertSame(rejected, assertThrows(IllegalStateException.class, fixture.qsim::run));
        assertEquals(List.of("start", "engine-before", "prepared", "cleanup-listener", "engine-after"),
                fixture.probe.lifecycle);
        assertEquals(1, fixture.probe.beforeCalls);
        assertEquals(1, fixture.probe.afterCalls, "The real QSim finally path still invokes engine cleanup");
        assertEquals(0, fixture.probe.initializedCalls);
        assertEquals(0, fixture.probe.activityCalls, "Failure precedes even initial activity arrangement");
        assertEquals(0, fixture.probe.stepCalls);
        assertTrue(fixture.check.drains.isEmpty());
        assertEquals(1, fixture.check.finishedCalls, "Preparation rejection still reaches successful native events cleanup");
        assertTrue(fixture.probe.events.stream().anyMatch(e -> e.eventClass.equals(PersonInitializedEvent.class.getName())),
                "Agent creation really occurred; this is not a manually faked prepared callback");
        assertTrue(fixture.probe.events.stream().noneMatch(e -> e.eventClass.equals(ActivityEndEvent.class.getName())));
        assertEquals(fixture.probe.events, fixture.check.receivedFromPreparationStart);
        assertTrue(fixture.probe.cleanupSawClearedScopes);
        assertScopesClear(fixture.qsim);
    }

    @Test
    void directCallbacksHaveNoScopeAndOptionalRegistrationRejectsNullOrReplacement() {
        Fixture fixture = fixture(true);
        assertThrows(IllegalStateException.class, () -> fixture.check.beforePreparation(fixture.qsim));
        assertThrows(IllegalStateException.class, () -> fixture.check.afterPrepared(fixture.qsim));
        assertEquals(0, fixture.check.startCalls);
        assertEquals(0, fixture.check.preparedCalls);
        assertScopesClear(fixture.qsim);
        assertThrows(NullPointerException.class, () -> fixture.qsim.setPreparationCheck(null));
        assertThrows(IllegalStateException.class, () -> fixture.qsim.setPreparationCheck(qsim -> { }));
        assertThrows(NullPointerException.class, () -> fixture.qsim.setStepBoundaryCheck(null));
        assertThrows(IllegalStateException.class, () -> fixture.qsim.setStepBoundaryCheck((qsim, time) -> { }));
        Fixture unbound = fixture(false);
        assertThrows(NullPointerException.class, () -> unbound.qsim.setPreparationCheck(null));
        // A rejected null did not consume the single registration slot; default before remains a no-op.
        QSimPreparationCheck functional = qsim -> { };
        unbound.qsim.setPreparationCheck(functional);
        assertThrows(IllegalStateException.class, () -> unbound.qsim.setPreparationCheck(functional));
        assertThrows(NullPointerException.class, () -> unbound.qsim.setStepBoundaryCheck(null));
        unbound.qsim.setStepBoundaryCheck((qsim, time) -> { });
        assertThrows(IllegalStateException.class, () -> unbound.qsim.setStepBoundaryCheck((qsim, time) -> { }));
    }

    private static void assertScopesClear(QSim qsim) {
        assertFalse(QSimRuntimeInspection.isAtPreparationStart(qsim));
        assertFalse(QSimRuntimeInspection.isAtPreparationCheck(qsim));
        assertFalse(QSimRuntimeInspection.isAtEventsProcessed(qsim));
        assertFalse(QSimRuntimeInspection.isAtEventsFinished(qsim));
    }

    private static Fixture fixture(boolean bindCheck) {
        var config = ConfigUtils.createConfig();
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(40);
        config.qsim().setPersonInitializedEventsSetting(QSimConfigGroup.PersonInitializedEventsSetting.all);
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro",
                "preparation-check-unused-output").toAbsolutePath().toString());
        var scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork();
        var n0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("prep-n0"), new Coord(0, 0));
        var n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("prep-n1"), new Coord(100, 0));
        var outward = NetworkUtils.createAndAddLink(network, Id.createLinkId("prep-out"), n0, n1, 100, 10, 3600, 1);
        var inward = NetworkUtils.createAndAddLink(network, Id.createLinkId("prep-back"), n1, n0, 100, 10, 3600, 1);
        outward.setAllowedModes(Set.of(TransportMode.car));
        inward.setAllowedModes(Set.of(TransportMode.car));
        var population = scenario.getPopulation();
        var factory = population.getFactory();
        var person = factory.createPerson(Id.createPersonId("prep-person"));
        var plan = factory.createPlan();
        var origin = factory.createActivityFromLinkId("home", outward.getId());
        origin.setEndTime(10);
        var leg = factory.createLeg(TransportMode.walk);
        TripStructureUtils.setRoutingMode(leg, TransportMode.walk);
        var route = RouteUtils.createGenericRouteImpl(outward.getId(), inward.getId());
        route.setDistance(5);
        route.setTravelTime(5);
        leg.setTravelTime(5);
        leg.setRoute(route);
        plan.addActivity(origin);
        plan.addLeg(leg);
        plan.addActivity(factory.createActivityFromLinkId("work", inward.getId()));
        person.addPlan(plan);
        population.addPerson(person);

        var probe = new Probe();
        var manager = EventsUtils.createEventsManager();
        manager.addHandler((BasicEventHandler) event -> probe.events.add(EventValue.copy(event)));
        QSim qsim = new QSimBuilder(config).useDefaults().addQSimModule(new AbstractQSimModule() {
            @Override protected void configureQSim() {
                bind(Probe.class).toInstance(probe);
                if (bindCheck) {
                    // This singleton belongs to this newly built QSim child injector, not a controller session.
                    bind(RecordingCheck.class).in(Singleton.class);
                    bind(QSimPreparationCheck.class).to(RecordingCheck.class);
                    bind(QSimStepBoundaryCheck.class).to(RecordingCheck.class);
                }
            }
        }).build(scenario, manager);
        probe.qsim = qsim;
        qsim.addMobsimEngine(probe);
        qsim.addActivityHandler(probe);
        qsim.addQueueSimulationListeners(probe);
        probe.beforeRun = QSimRuntimeInspection.inspect(qsim);
        RecordingCheck check = bindCheck
                ? assertInstanceOf(RecordingCheck.class, qsim.getChildInjector().getInstance(QSimPreparationCheck.class)) : null;
        assertTrue(qsim.getAgents().isEmpty());
        assertFalse(QNetsimRuntimeInspection.inspect(qsim.getChildInjector().getInstance(QNetsimEngineI.class)).initialized());
        return new Fixture(qsim, probe, check);
    }

    private record Fixture(QSim qsim, Probe probe, RecordingCheck check) { }
    private record EventValue(double time, String eventClass, String type, Map<String, String> attributes) {
        static EventValue copy(Event event) {
            return new EventValue(event.getTime(), event.getClass().getName(), event.getEventType(), Map.copyOf(event.getAttributes()));
        }
    }

    private static final class RecordingCheck implements QSimPreparationCheck, QSimStepBoundaryCheck {
        private final Probe probe;
        private final List<EventValue> receivedFromPreparationStart = new ArrayList<>();
        private int startCalls;
        private int preparedCalls;
        private RuntimeException failure;
        private final List<Double> drains = new ArrayList<>();
        private int finishedCalls;

        @Inject RecordingCheck(Probe probe) { this.probe = probe; }

        @Override public void beforePreparation(QSim qsim) {
            if (!QSimRuntimeInspection.isAtPreparationStart(qsim)) throw new IllegalStateException("Not a native preparation start");
            assertFalse(QSimRuntimeInspection.isAtPreparationCheck(qsim));
            assertSame(probe.qsim, qsim);
            assertEquals(1, ++startCalls);
            probe.lifecycle.add("start");
            assertTrue(qsim.getAgents().isEmpty());
            var runners = QNetsimRuntimeInspection.inspect(qsim.getChildInjector().getInstance(QNetsimEngineI.class));
            assertEquals(1, runners.constructionThreadCount());
            assertEquals(1, runners.constructionRunnerCount());
            assertFalse(runners.initialized());
            assertTrue(runners.actualRunnerCount().isEmpty());
            assertEquals(0, probe.beforeCalls);
            assertEquals(0, probe.initializedCalls);
            assertEquals(0, probe.activityCalls);
            assertTrue(probe.events.isEmpty());
            assertDefaultsAppended(qsim);
            qsim.getEventsManager().addHandler((BasicEventHandler) event -> receivedFromPreparationStart.add(EventValue.copy(event)));
        }

        @Override public void afterPrepared(QSim qsim) {
            if (!QSimRuntimeInspection.isAtPreparationCheck(qsim)) throw new IllegalStateException("Not a native prepared boundary");
            assertFalse(QSimRuntimeInspection.isAtPreparationStart(qsim));
            assertSame(probe.qsim, qsim);
            assertEquals(1, ++preparedCalls);
            probe.lifecycle.add("prepared");
            assertEquals(Set.of(Id.createPersonId("prep-person")), qsim.getAgents().keySet());
            var engine = qsim.getChildInjector().getInstance(QNetsimEngineI.class);
            var runtime = QSimRuntimeInspection.inspect(qsim);
            assertTrue(runtime.activeNetworkEngineIs(engine));
            assertEquals(1, runtime.registeredEngineOccurrences(engine));
            assertEquals(1, runtime.stepEngineOccurrences(engine));
            var runners = QNetsimRuntimeInspection.inspect(engine);
            assertTrue(runners.initialized());
            assertEquals(1, runners.actualRunnerCount().orElseThrow());
            assertEquals(1, runners.actualRunnerClasses().size());
            assertEquals(1, probe.beforeCalls, "The added last engine's real beforeMobsim has returned too");
            assertEquals(0, probe.initializedCalls);
            assertEquals(0, probe.activityCalls);
            assertEquals(probe.events, receivedFromPreparationStart);
            assertEquals(1, receivedFromPreparationStart.stream()
                    .filter(event -> event.eventClass.equals(PersonInitializedEvent.class.getName())).count());
            assertDefaultsAppended(qsim);
            if (failure != null) throw failure;
        }

        @Override public void afterEventsProcessed(QSim qsim, double time) {
            if (!QSimRuntimeInspection.isAtEventsProcessed(qsim)) throw new IllegalStateException("Not a native drain boundary");
            assertSame(probe.qsim, qsim);
            assertFalse(QSimRuntimeInspection.isAtBeforeSimStep(qsim));
            assertFalse(QSimRuntimeInspection.isAtEventsFinished(qsim));
            assertEquals(qsim.getSimTimer().getTimeOfDay(), time);
            assertEquals(drains.size() + 1, probe.stepCalls);
            drains.add(time);
        }

        @Override public void afterEventsFinished(QSim qsim) {
            if (!QSimRuntimeInspection.isAtEventsFinished(qsim)) throw new IllegalStateException("Not native events finish");
            assertSame(probe.qsim, qsim);
            assertFalse(QSimRuntimeInspection.isAtBeforeSimStep(qsim));
            assertFalse(QSimRuntimeInspection.isAtEventsProcessed(qsim));
            assertEquals(1, probe.afterCalls);
            assertEquals(1, ++finishedCalls);
        }

        private void assertDefaultsAppended(QSim qsim) {
            var current = QSimRuntimeInspection.inspect(qsim);
            assertEquals(probe.beforeRun.components(ACTIVITY_HANDLER).size() + 1, current.components(ACTIVITY_HANDLER).size());
            assertEquals(probe.beforeRun.components(DEPARTURE_HANDLER).size() + 1, current.components(DEPARTURE_HANDLER).size());
            assertEquals(current.components(ACTIVE_ACTIVITY_ENGINE).getFirst().identityGroup(),
                    current.components(ACTIVITY_HANDLER).getLast().identityGroup());
            assertEquals(current.components(ACTIVE_TELEPORTATION_ENGINE).getFirst().identityGroup(),
                    current.components(DEPARTURE_HANDLER).getLast().identityGroup());
        }
    }

    private static final class Probe implements MobsimEngine, ActivityHandler,
            MobsimInitializedListener, MobsimBeforeSimStepListener, MobsimBeforeCleanupListener {
        private QSim qsim;
        private QSimRuntimeInspection.Snapshot beforeRun;
        private final List<EventValue> events = new ArrayList<>();
        private final List<String> lifecycle = new ArrayList<>();
        private int beforeCalls, afterCalls, initializedCalls, activityCalls, stepCalls;
        private boolean cleanupSawClearedScopes;
        @Override public void beforeMobsim() { beforeCalls++; lifecycle.add("engine-before"); }
        @Override public void afterMobsim() { afterCalls++; lifecycle.add("engine-after"); }
        @Override public void doSimStep(double time) { }
        @Override public void setInternalInterface(InternalInterface internalInterface) { }
        @Override public boolean handleActivity(MobsimAgent agent) {
            if (activityCalls++ == 0) lifecycle.add("first-activity");
            return false;
        }
        @Override public void rescheduleActivityEnd(MobsimAgent agent) { }
        @Override public void notifyMobsimInitialized(MobsimInitializedEvent event) {
            assertScopesClear(qsim);
            initializedCalls++;
            lifecycle.add("initialized");
        }
        @Override public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent event) {
            assertScopesClear(qsim);
            assertTrue(QSimRuntimeInspection.isAtBeforeSimStep(qsim));
            if (stepCalls++ == 0) lifecycle.add("first-step");
        }
        @Override public void notifyMobsimBeforeCleanup(MobsimBeforeCleanupEvent event) {
            // Deliberately non-throwing: an exception here could prevent native engine cleanup.
            cleanupSawClearedScopes = !QSimRuntimeInspection.isAtPreparationStart(qsim)
                    && !QSimRuntimeInspection.isAtPreparationCheck(qsim);
            lifecycle.add("cleanup-listener");
        }
    }
}
