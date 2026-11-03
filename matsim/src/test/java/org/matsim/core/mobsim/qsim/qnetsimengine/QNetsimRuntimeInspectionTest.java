package org.matsim.core.mobsim.qsim.qnetsimengine;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Construction-only checks: no test calls QSim.run, beforeMobsim or an engine step. */
class QNetsimRuntimeInspectionTest {
    @Test
    void constructionCountsRemainTwoAfterMutableConfigurationIsChangedToOne() {
        Fixture fixture = construct(2);
        QNetsimRuntimeInspection.Snapshot before = QNetsimRuntimeInspection.inspect(fixture.engine());
        assertEquals(2, before.constructionThreadCount());
        assertEquals(2, before.constructionRunnerCount());
        fixture.qsim().getScenario().getConfig().qsim().setNumberOfThreads(1);
        assertEquals(1, fixture.qsim().getScenario().getConfig().qsim().getNumberOfThreads());
        QNetsimRuntimeInspection.Snapshot after = QNetsimRuntimeInspection.inspect(fixture.engine());
        assertEquals(before, after, "Inspection reads construction fields, not today's mutable configuration");
        assertUninitialized(fixture, after);
    }

    @Test
    void repeatedInspectionDoesNotInitializeRunnersEmitEventsOrAdvanceTime() {
        Fixture fixture = construct(1);
        double time = fixture.qsim().getSimTimer().getTimeOfDay();
        var first = QNetsimRuntimeInspection.inspect(fixture.engine());
        var second = QNetsimRuntimeInspection.inspect(fixture.engine());
        assertEquals(first, second);
        assertEquals(1, first.constructionThreadCount());
        assertEquals(1, first.constructionRunnerCount());
        assertEquals(QNetsimEngineWithThreadpool.class.getName(), first.engineClass());
        assertEquals(time, fixture.qsim().getSimTimer().getTimeOfDay(), 0.0);
        assertUninitialized(fixture, second);
        assertThrows(UnsupportedOperationException.class, () -> second.actualRunnerClasses().add("not-a-runner"));
    }

    @Test
    void unknownImplementationAndNullAreRejectedWithoutCallingEngineMethods() {
        AtomicInteger calls = new AtomicInteger();
        QNetsimEngineI unknown = (QNetsimEngineI) Proxy.newProxyInstance(QNetsimEngineI.class.getClassLoader(),
                new Class<?>[]{QNetsimEngineI.class}, (proxy, method, args) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("Inspection must not invoke an unknown engine: " + method.getName());
                });
        assertThrows(IllegalArgumentException.class, () -> QNetsimRuntimeInspection.inspect(unknown));
        assertEquals(0, calls.get());
        assertThrows(NullPointerException.class, () -> QNetsimRuntimeInspection.inspect(null));
    }

    @Test
    void wrapperAroundExactNativeEngineIsNotCertifiedThroughItsDelegate() {
        Fixture fixture = construct(1);
        AtomicInteger delegatedCalls = new AtomicInteger();
        QNetsimEngineI wrapper = (QNetsimEngineI) Proxy.newProxyInstance(QNetsimEngineI.class.getClassLoader(),
                new Class<?>[]{QNetsimEngineI.class}, (proxy, method, args) -> {
                    delegatedCalls.incrementAndGet();
                    return method.invoke(fixture.engine(), args);
                });
        assertThrows(IllegalArgumentException.class, () -> QNetsimRuntimeInspection.inspect(wrapper));
        assertEquals(0, delegatedCalls.get(), "Even a same-network delegate must not be queried through an unverified wrapper");
        assertUninitialized(fixture, QNetsimRuntimeInspection.inspect(fixture.engine()));
    }

    @Test
    void diagnosticRecordDefensivelyCopiesClassNamesAndRejectsInconsistentAvailability() {
        // A value-object test only: this is not evidence that a real engine initialized.
        var classes = new ArrayList<>(List.of("example.Runner"));
        var value = new QNetsimRuntimeInspection.Snapshot("example.Engine", 1, 1, true, OptionalInt.of(1), classes);
        classes.clear();
        assertEquals(List.of("example.Runner"), value.actualRunnerClasses());
        assertThrows(UnsupportedOperationException.class, () -> value.actualRunnerClasses().clear());
        assertThrows(IllegalArgumentException.class, () -> new QNetsimRuntimeInspection.Snapshot(
                "example.Engine", 1, 1, false, OptionalInt.of(0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QNetsimRuntimeInspection.Snapshot(
                "example.Engine", 1, 1, true, OptionalInt.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QNetsimRuntimeInspection.Snapshot(
                "example.Engine", 1, 1, true, OptionalInt.of(2), List.of("example.Runner")));
    }

    private static void assertUninitialized(Fixture fixture, QNetsimRuntimeInspection.Snapshot snapshot) {
        assertFalse(snapshot.initialized());
        assertTrue(snapshot.actualRunnerCount().isEmpty(), "Not-yet-created is unknown, not an actual count of zero");
        assertTrue(snapshot.actualRunnerClasses().isEmpty());
        assertNull(fixture.engine().getQnetsimEngineRunner());
        assertEquals(0, fixture.events().get());
        assertTrue(fixture.qsim().getAgents().isEmpty());
        assertTrue(fixture.qsim().getVehicles().isEmpty());
    }

    private static Fixture construct(int threads) {
        var config = ConfigUtils.createConfig();
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(threads);
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-runner-inspection-output")
                .toAbsolutePath().toString());
        var scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork();
        var from = NetworkUtils.createAndAddNode(network, Id.createNodeId("inspection-n0"), new Coord(0, 0));
        var to = NetworkUtils.createAndAddNode(network, Id.createNodeId("inspection-n1"), new Coord(100, 0));
        var outward = NetworkUtils.createAndAddLink(network, Id.createLinkId("inspection-out"), from, to, 100, 10, 3600, 1);
        var inward = NetworkUtils.createAndAddLink(network, Id.createLinkId("inspection-back"), to, from, 100, 10, 3600, 1);
        outward.setAllowedModes(Set.of(TransportMode.car));
        inward.setAllowedModes(Set.of(TransportMode.car));
        var manager = EventsUtils.createEventsManager();
        var events = new AtomicInteger();
        manager.addHandler((BasicEventHandler) event -> events.incrementAndGet());
        QSim qsim = new QSimBuilder(config).useDefaults().build(scenario, manager);
        var engine = assertInstanceOf(QNetsimEngineWithThreadpool.class, qsim.getChildInjector().getInstance(QNetsimEngineI.class));
        assertEquals(threads, engine.numOfThreads);
        assertEquals(threads, engine.constructionRunnerCount());
        assertNull(engine.getQnetsimEngineRunner());
        return new Fixture(qsim, engine, events);
    }

    private record Fixture(QSim qsim, QNetsimEngineWithThreadpool engine, AtomicInteger events) { }
}
