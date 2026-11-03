package org.matsim.core.mobsim.qsim.pt;

import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.mobsim.framework.PlanAgent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimRuntimeInspection;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngineI;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimRuntimeInspection;
import org.matsim.run.ismt.dynamic.NativeThroughChainInputAudit;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-only native lifecycle probe. Installed only in observer-on fixtures, before
 * run; it reads real initialized agents, not Scenario selected-plan substitutes.
 * Inventory equality is only a comparison at sampled quiescent boundaries, not
 * registration sealing, intervening-mutation/ABA detection or producer completeness.
 */
final class NativePreparedInputInspection implements MobsimInitializedListener, MobsimBeforeSimStepListener {
    private final QSim qsim;
    private final boolean expectThroughChains;
    private final boolean inspectRuntime;
    private NativeThroughChainInputAudit.Report inputReport;
    private QSimRuntimeInspection.Snapshot initializedInventory;
    private EventsManagerImpl.RuntimeInspectionSnapshot initializedHandlers;
    private QNetsimRuntimeInspection.Snapshot initializedRunners;
    private QNetsimEngineI expectedEngine;
    private EventsManagerImpl expectedManager;
    private int initializedCallbacks, beforeCallbacks;

    NativePreparedInputInspection(QSim qsim, boolean expectThroughChains, boolean inspectRuntime) {
        this.qsim = qsim;
        this.expectThroughChains = expectThroughChains;
        this.inspectRuntime = inspectRuntime;
    }

    @Override public void notifyMobsimInitialized(MobsimInitializedEvent event) {
        assertSame(qsim, event.getQueueSimulation());
        assertEquals(0, initializedCallbacks++);
        Map<String, Plan> executing = new TreeMap<>();
        int drivers = 0;
        for (var entry : qsim.getAgents().entrySet()) {
            var agent = entry.getValue();
            assertEquals(entry.getKey(), agent.getId());
            if (agent instanceof AbstractTransitDriverAgent) {
                drivers++;
                continue;
            }
            PlanAgent planAgent = assertInstanceOf(PlanAgent.class, agent);
            Plan executingPlan = planAgent.getCurrentPlan();
            assertNotNull(executingPlan); assertNotNull(executingPlan.getPerson());
            assertEquals(agent.getId(), executingPlan.getPerson().getId());
            var populationPerson = qsim.getScenario().getPopulation().getPersons().get(agent.getId());
            assertNotNull(populationPerson, "An unregistered non-driver agent cannot be silently omitted");
            assertNotSame(populationPerson.getSelectedPlan(), executingPlan,
                    "The native BasicPlanAgent executes its copied plan, not Scenario selected-plan memory");
            assertNull(executing.put(agent.getId().toString(), executingPlan));
        }
        Set<String> populationIds = qsim.getScenario().getPopulation().getPersons().keySet().stream()
                .map(Object::toString).collect(Collectors.toSet());
        assertEquals(populationIds, executing.keySet(), "The actual prepared-plan audit must cover the exact population ID set");
        assertTrue(drivers > 0, "This native PT fixture must also contain excluded transit drivers");
        assertEquals(executing.size() + drivers, qsim.getAgents().size());
        inputReport = NativeThroughChainInputAudit.inspect(qsim.getScenario().getTransitSchedule(), executing);
        assertEquals(executing.size(), inputReport.counts().executingPlans());
        assertTrue(inputReport.stableAcrossReads());
        if (expectThroughChains) {
            assertTrue(inputReport.counts().scheduledChainEdges() > 0);
            assertTrue(inputReport.counts().planChainEdges() > 0);
            assertTrue(inputReport.violations().stream().anyMatch(issue -> issue.code() == NativeThroughChainInputAudit.Code.SCHEDULE_THROUGH_CHAIN));
            assertTrue(inputReport.violations().stream().anyMatch(issue -> issue.code() == NativeThroughChainInputAudit.Code.PLAN_THROUGH_CHAIN));
            assertThrows(IllegalStateException.class, inputReport::requireNoThroughChains,
                    "The production boundary rejects this unsupported input; the deliberate native microfixture still continues");
        } else {
            assertEquals(0, inputReport.counts().scheduledChainEdges());
            assertEquals(0, inputReport.counts().planChainEdges());
            assertDoesNotThrow(inputReport::requireNoThroughChains);
        }
        if (inspectRuntime) {
            expectedEngine = qsim.getChildInjector().getInstance(QNetsimEngineI.class);
            expectedManager = assertInstanceOf(EventsManagerImpl.class, qsim.getEventsManager());
            assertEquals(EventsManagerImpl.class, expectedManager.getClass());
            initializedInventory = QSimRuntimeInspection.inspect(qsim);
            assertActiveEngine(initializedInventory);
            initializedRunners = QNetsimRuntimeInspection.inspect(expectedEngine);
            assertTrue(initializedRunners.initialized());
            assertEquals(1, initializedRunners.constructionThreadCount());
            assertEquals(1, initializedRunners.constructionRunnerCount());
            assertEquals(1, initializedRunners.actualRunnerCount().orElseThrow());
            assertEquals(1, initializedRunners.actualRunnerClasses().size());
            initializedHandlers = expectedManager.inspectRuntimeRegistrations(List.of(Event.class));
            assertFalse(initializedHandlers.registeredGroups().isEmpty());
        }
    }

    @Override public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent event) {
        assertSame(qsim, event.getQueueSimulation());
        assertEquals(1, initializedCallbacks);
        assertNotNull(inputReport);
        beforeCallbacks++;
        if (inspectRuntime) {
            assertSame(expectedManager, qsim.getEventsManager());
            var current = QSimRuntimeInspection.inspect(qsim);
            assertActiveEngine(current);
            assertTrue(initializedInventory.sameComponentsAs(current), "Actual role identities and their order must match initialization at this Before boundary");
            assertEquals(initializedRunners, QNetsimRuntimeInspection.inspect(expectedEngine));
            assertTrue(initializedHandlers.sameRegistrationsAs(expectedManager.inspectRuntimeRegistrations(List.of(Event.class))),
                    "Complete actual handler groups, including typed groups not dispatched for Event itself, must retain identity and order");
        }
    }

    private void assertActiveEngine(QSimRuntimeInspection.Snapshot snapshot) {
        assertTrue(snapshot.activeNetworkEngineIs(expectedEngine));
        assertEquals(1, snapshot.registeredEngineOccurrences(expectedEngine));
        assertEquals(1, snapshot.stepEngineOccurrences(expectedEngine));
    }

    void assertCompleted(int expectedBeforeCallbacks) {
        assertEquals(1, initializedCallbacks);
        assertTrue(beforeCallbacks > 0);
        assertEquals(expectedBeforeCallbacks, beforeCallbacks);
        assertNotNull(inputReport);
        // No post-cleanup comparison: observer cleanup legitimately unregisters it.
        if (inspectRuntime) {
            assertNotNull(initializedInventory); assertNotNull(initializedHandlers); assertNotNull(initializedRunners);
        }
    }
}
