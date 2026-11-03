package org.matsim.core.mobsim.qsim;

import org.junit.jupiter.api.Test;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.framework.Mobsim;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimAfterSimStepListener;
import org.matsim.core.mobsim.qsim.interfaces.DepartureHandler;
import org.matsim.core.mobsim.qsim.interfaces.MobsimEngine;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngineI;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.matsim.core.mobsim.qsim.QSimRuntimeInspection.Role.*;

/** Real, never-run QSim instances; manual callbacks test inventory semantics only. */
class QSimRuntimeInspectionTest {
    private static QSim qsim() {
        var config = ConfigUtils.createConfig();
        config.qsim().setNumberOfThreads(1); config.global().setNumberOfThreads(1);
        config.controller().setOutputDirectory(Path.of(".build-cache", "unused-runtime-inspection").toAbsolutePath().toString());
        return new QSimBuilder(config).useDefaults().build(ScenarioUtils.createScenario(config), EventsUtils.createEventsManager());
    }

    @Test void actualPrimaryIdentityAndRepeatedInspectionDoNotChangeTheRuntime() {
        QSim qsim = qsim(); var engine = qsim.getChildInjector().getInstance(QNetsimEngineI.class);
        double clock = qsim.getSimTimer().getTimeOfDay();
        var first = QSimRuntimeInspection.inspect(qsim); var next = QSimRuntimeInspection.inspect(qsim);
        assertTrue(first.activeNetworkEngineIs(engine)); assertTrue(QSimRuntimeInspection.activeNetworkEngineIs(qsim, engine));
        assertEquals(1, first.registeredEngineOccurrences(engine)); assertEquals(1, first.stepEngineOccurrences(engine));
        assertTrue(first.sameComponentsAs(next)); assertEquals(first.roles(), next.roles());
        assertEquals(clock, qsim.getSimTimer().getTimeOfDay()); assertTrue(qsim.getAgents().isEmpty());
        assertFalse(first.sameComponentsAs(null));
    }

    @Test void aSameNetworkReplacementIsNotTheRegisteredActiveEngine() {
        QSim qsim = qsim(); var nativeEngine = qsim.getChildInjector().getInstance(QNetsimEngineI.class);
        var original = QSimRuntimeInspection.inspect(qsim);
        QNetsimEngineI replacement = mock(QNetsimEngineI.class);
        when(replacement.getNetsimNetwork()).thenReturn(nativeEngine.getNetsimNetwork());
        qsim.addMobsimEngine(replacement);
        assertSame(nativeEngine.getNetsimNetwork(), qsim.getNetsimNetwork());
        var current = QSimRuntimeInspection.inspect(qsim);
        assertFalse(current.activeNetworkEngineIs(nativeEngine)); assertTrue(current.activeNetworkEngineIs(replacement));
        assertFalse(QSimRuntimeInspection.activeNetworkEngineIs(qsim, nativeEngine));
        assertEquals(1, current.registeredEngineOccurrences(nativeEngine));
        assertEquals(1, current.registeredEngineOccurrences(replacement));
        assertFalse(original.sameComponentsAs(current)); assertTrue(original.activeNetworkEngineIs(nativeEngine));
    }

    @Test void duplicateNativeRegistrationIsRetainedInBothRegisteredAndActualStepOrders() {
        QSim qsim = qsim(); var engine = qsim.getChildInjector().getInstance(QNetsimEngineI.class);
        qsim.addMobsimEngine(engine);
        var snapshot = QSimRuntimeInspection.inspect(qsim);
        assertEquals(2, snapshot.registeredEngineOccurrences(engine)); assertEquals(2, snapshot.stepEngineOccurrences(engine));
    }

    @Test void retainedMutableDepartureListAliasIsDetectedButNotSealed() {
        QSim qsim = qsim(); var alias = mutableDepartureHandlers(qsim);
        var first = QSimRuntimeInspection.inspect(qsim);
        alias.add(mock(DepartureHandler.class));
        var second = QSimRuntimeInspection.inspect(qsim);
        assertFalse(first.sameComponentsAs(second));
        assertEquals(first.components(DEPARTURE_HANDLER).size() + 1, second.components(DEPARTURE_HANDLER).size());
    }

    @Test void equalClassDescriptionsDoNotAuthenticateAReplacementInstance() {
        QSim qsim = qsim(); var handlers = mutableDepartureHandlers(qsim);
        DepartureHandler firstHandler = (time, agent, link) -> false;
        handlers.clear(); handlers.add(firstHandler);
        var first = QSimRuntimeInspection.inspect(qsim);
        handlers.set(0, (time, agent, link) -> false);
        var second = QSimRuntimeInspection.inspect(qsim);
        assertEquals(first.components(DEPARTURE_HANDLER), second.components(DEPARTURE_HANDLER),
                "Hidden lambda labels are deliberately not unique instance identities");
        assertFalse(first.sameComponentsAs(second));
    }

    @Test void callbacksUseTheActualInvocationArraysWithDuplicatesAndCrossRoleAliases() {
        var visits = new ArrayList<String>();
        var manager = new MobsimListenerManager(mock(Mobsim.class));
        var a = new Probe("a", visits); var b = new Probe("b", visits); var c = new Probe("c", visits);
        manager.addQueueSimulationListener(a); manager.addQueueSimulationListener(b);
        manager.addQueueSimulationListener(c); manager.addQueueSimulationListener(a);
        var expected = manager.invocationOrderForInspection(MobsimBeforeSimStepListener.class);
        assertEquals(List.of(a, c, b, a), expected, "Swing listener arrays run in reverse registration order");
        assertEquals(expected, manager.invocationOrderForInspection(MobsimAfterSimStepListener.class));
        manager.fireQueueSimulationBeforeSimStepEvent(0);
        assertEquals(expected.stream().map(value -> ((Probe) value).name).toList(), visits);
        manager.removeQueueSimulationListener(a);
        assertEquals(List.of(a, c, b, a), expected, "Previously returned invocation order stays detached");

        QSim qsim = qsim(); qsim.addQueueSimulationListeners(a); qsim.addQueueSimulationListeners(b);
        qsim.addQueueSimulationListeners(c); qsim.addQueueSimulationListeners(a);
        var snapshot = QSimRuntimeInspection.inspect(qsim);
        var before = snapshot.components(BEFORE_STEP_LISTENER);
        var after = snapshot.components(AFTER_STEP_LISTENER);
        assertEquals(4, after.size());
        assertEquals(after.getFirst().identityGroup(), after.getLast().identityGroup());
        assertEquals(after.stream().map(QSimRuntimeInspection.Component::identityGroup).toList(),
                before.subList(0, 4).stream().map(QSimRuntimeInspection.Component::identityGroup).toList());
    }

    @Test void allPublicRoleCollectionsAreDetachedAndUnmodifiable() {
        QSim qsim = qsim(); var snapshot = QSimRuntimeInspection.inspect(qsim);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.roles().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.components(REGISTERED_ENGINE).clear());
        int old = snapshot.components(REGISTERED_ENGINE).size();
        qsim.addMobsimEngine(mock(MobsimEngine.class));
        assertEquals(old, snapshot.components(REGISTERED_ENGINE).size());
        assertEquals(old + 1, QSimRuntimeInspection.inspect(qsim).components(REGISTERED_ENGINE).size());
    }

    @Test void separateQsimOwnersCannotAuthenticateEachOtherEvenWithIdenticalRoles() {
        var first = QSimRuntimeInspection.inspect(qsim()); var second = QSimRuntimeInspection.inspect(qsim());
        assertEquals(first.roles(), second.roles()); assertFalse(first.sameComponentsAs(second));
    }

    @Test void hiddenListenerTypeRegistrationsDoNotLeakVirtualMachineAddresses() {
        QSim qsim = qsim();
        qsim.addQueueSimulationListeners((MobsimBeforeSimStepListener) ignored -> { });
        var snapshot = QSimRuntimeInspection.inspect(qsim);
        assertTrue(snapshot.components(LISTENER_REGISTRATION_PAIRS).stream()
                .anyMatch(value -> value.classLabel().startsWith("registrationType:hidden:")));
        assertTrue(snapshot.roles().stream().flatMap(role -> role.components().stream())
                .noneMatch(value -> value.classLabel().contains("/0x")));
    }

    @SuppressWarnings("unchecked")
    private static List<DepartureHandler> mutableDepartureHandlers(QSim qsim) {
        // The production interface exposes Collection<? extends DepartureHandler>.
        // This test deliberately exercises QSim's existing mutable List alias;
        // check that concrete shape and its elements before the narrow test-only cast.
        List<?> handlers = assertInstanceOf(List.class, qsim.internalInterface.getDepartureHandlers());
        handlers.forEach(handler -> assertInstanceOf(DepartureHandler.class, handler));
        return (List<DepartureHandler>) handlers;
    }

    private static final class Probe implements MobsimBeforeSimStepListener, MobsimAfterSimStepListener {
        final String name; final List<String> visits;
        Probe(String name, List<String> visits) { this.name = name; this.visits = visits; }
        @Override public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent event) { visits.add(name); }
        @Override public void notifyMobsimAfterSimStep(MobsimAfterSimStepEvent event) { visits.add(name); }
    }
}
