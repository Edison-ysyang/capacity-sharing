package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.PlanAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.mobsim.qsim.pt.AbstractTransitDriverAgent;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.matsim.run.ismt.dynamic.PreparedQSimInputAudit.*;

/** Real but NEVER RUN QSim registries with explicitly fake agents: pure source-validation tests, not native preparation evidence. */
class PreparedQSimInputAuditTest {
    @Test void actualRegisteredPlanIsInspectedAndCopiedPlanIsOnlyDescriptive() {
        var f = new Fixture(); Person person = f.person("p"); Plan copy = PopulationUtils.createPlan(person);
        PopulationUtils.copyFromTo(person.getSelectedPlan(), copy); f.addPlanAgent("p", copy);
        Report report = inspect(f.qsim, Set.of("p"));
        assertTrue(report.verifiedSupportedInput()); assertDoesNotThrow(report::requireSupportedInput);
        assertFalse(report.preparationBarrierCertified());
        assertEquals(new Counts(1, 0, 1, 1, 1, 0, 0), report.counts());
        assertEquals(SelectedPlanRelation.DIFFERENT_OBJECT, report.identities().getFirst().selectedPlanRelation());
        assertEquals(1, report.throughChainReport().orElseThrow().counts().executingPlans());
        assertEquals("p", report.identities().getFirst().planOwnerId());
    }

    @Test void legitimateSameSelectedPlanObjectIsNotRejected() {
        var f = new Fixture(); Person person = f.person("p"); f.addPlanAgent("p", person.getSelectedPlan());
        Report report = inspect(f.qsim, Set.of("p"));
        assertTrue(report.verifiedSupportedInput());
        assertEquals(SelectedPlanRelation.SAME_OBJECT, report.identities().getFirst().selectedPlanRelation());
    }

    @Test void selectedPlanMemoryNeverSubstitutesAnInvalidActualPlan() {
        var f = new Fixture(); f.person("p"); f.addPlanAgent("p", null);
        Report report = inspect(f.qsim, Set.of("p"));
        assertTrue(codes(report).contains(Code.INVALID_PLAN)); assertRejected(report);
        assertEquals(0, report.counts().validatedPlans());
        assertEquals(0, report.throughChainReport().orElseThrow().counts().executingPlans());
    }

    @Test void malformedActualPtRouteCannotBeHiddenBySafeSelectedPlan() {
        var f = new Fixture(); Person person = f.person("p"); Plan actual = PopulationUtils.createPlan(person);
        actual.addActivity(PopulationUtils.createActivityFromLinkId("origin", Id.createLinkId("l")));
        actual.addLeg(PopulationUtils.createLeg(TransportMode.pt));
        actual.addActivity(PopulationUtils.createActivityFromLinkId("destination", Id.createLinkId("l")));
        f.addPlanAgent("p", actual);
        Report report = inspect(f.qsim, Set.of("p")); assertRejected(report);
        assertTrue(codes(report).contains(Code.UNSUPPORTED_INPUT_STRUCTURE));
        assertTrue(report.throughChainReport().orElseThrow().violations().stream()
                .anyMatch(issue -> issue.code() == NativeThroughChainInputAudit.Code.UNVERIFIED_PT_ROUTE));
    }

    @Test void explicitExpectedSetDetectsBothMissingAndExtraActualAgents() {
        var f = new Fixture(); Person extra = f.person("extra"); f.addPlanAgent("extra", extra.getSelectedPlan());
        Report report = inspect(f.qsim, Set.of("missing"));
        assertTrue(codes(report).containsAll(Set.of(Code.MISSING_POPULATION_AGENT, Code.EXTRA_POPULATION_AGENT)));
        assertEquals(1, report.counts().missingPopulationAgents()); assertEquals(1, report.counts().extraPopulationAgents());
        assertRejected(report);
    }

    @Test void nonPlanAgentCannotBeOmittedEvenWithCorrectIdCoverage() {
        var f = new Fixture(); MobsimAgent agent = mock(MobsimAgent.class);
        when(agent.getId()).thenReturn(Id.createPersonId("p")); f.qsim.insertAgentIntoMobsim(agent);
        Report report = inspect(f.qsim, Set.of("p"));
        assertEquals(0, report.counts().missingPopulationAgents());
        assertTrue(codes(report).contains(Code.NON_PLAN_AGENT)); assertRejected(report);
    }

    @Test void driverIsExcludedButExpectedPopulationOverlapIsNotSilentlyExcluded() {
        var f = new Fixture(); var driver = mock(AbstractTransitDriverAgent.class);
        when(driver.getId()).thenReturn(Id.createPersonId("driver")); f.qsim.insertAgentIntoMobsim(driver);
        Report excluded = inspect(f.qsim, Set.of());
        assertTrue(excluded.verifiedSupportedInput()); assertEquals(1, excluded.counts().transitDrivers());
        assertEquals(0, excluded.counts().validatedPlans());
        Report overlap = inspect(f.qsim, Set.of("driver")); assertRejected(overlap);
        assertTrue(codes(overlap).contains(Code.DRIVER_POPULATION_OVERLAP));
        verify(driver, never()).getCurrentPlan();
    }

    @Test void changedAgentIdAfterRegistrationCannotPassByMatchingOnlyExpectedIds() {
        var f = new Fixture(); Person person = f.person("changed");
        MobsimAgent agent = f.addPlanAgent("original", person.getSelectedPlan());
        when(agent.getId()).thenReturn(Id.createPersonId("changed"));
        Report report = inspect(f.qsim, Set.of("changed")); assertRejected(report);
        assertTrue(codes(report).contains(Code.REGISTRY_AGENT_ID_MISMATCH));
    }

    @Test void ownerMismatchNullOwnerAndEmptyPlanAllFailClosed() {
        for (int variant = 0; variant < 3; variant++) {
            var f = new Fixture(); Plan plan;
            if (variant == 0) plan = f.person("someone-else").getSelectedPlan();
            else if (variant == 1) plan = PopulationUtils.createPlan();
            else plan = PopulationUtils.createPlan(f.person("p"));
            f.addPlanAgent("p", plan);
            Report report = inspect(f.qsim, Set.of("p")); assertRejected(report);
            assertTrue(codes(report).contains(variant == 0 ? Code.PLAN_OWNER_MISMATCH : Code.INVALID_PLAN));
        }
    }

    @Test void getterFailureIsReportedNotSilentlyTreatedAsAnEmptySafePopulation() {
        var f = new Fixture(); MobsimAgent agent = f.addPlanAgent("p", f.person("p").getSelectedPlan());
        when(((PlanAgent) agent).getCurrentPlan()).thenThrow(new IllegalStateException("test failure"));
        Report report = inspect(f.qsim, Set.of("p")); assertRejected(report);
        assertTrue(codes(report).contains(Code.READ_FAILURE)); assertEquals(1, report.identities().size());
    }

    @Test void actualDuplicateReportedIdentityIsRejected() {
        var f = new Fixture(); Plan plan = f.person("p").getSelectedPlan();
        f.addPlanAgent("p", plan); MobsimAgent second = f.addPlanAgent("other", plan);
        when(second.getId()).thenReturn(Id.createPersonId("p"));
        Report report = inspect(f.qsim, Set.of("p")); assertRejected(report);
        assertTrue(codes(report).contains(Code.DUPLICATE_AGENT_ID));
    }

    @Test void reportIsDetachedAndInspectionDoesNotCallLifecycleOrChangeAgents() {
        var f = new Fixture(); Person person = f.person("p"); MobsimAgent agent = f.addPlanAgent("p", person.getSelectedPlan());
        var expected = new HashSet<>(Set.of("p")); int living = f.qsim.getAgentCounter().getLiving();
        Report report = inspect(f.qsim, expected); expected.add("later");
        assertTrue(report.verifiedSupportedInput()); assertEquals(Set.of("p"), report.expectedPopulationIds());
        assertEquals(living, f.qsim.getAgentCounter().getLiving()); assertSame(agent, f.qsim.getAgents().get(Id.createPersonId("p")));
        assertSame(person.getSelectedPlan(), ((PlanAgent) agent).getCurrentPlan());
        verify(agent, never()).endActivityAndComputeNextState(anyDouble());
        verify(agent, never()).endLegAndComputeNextState(anyDouble()); verify(agent, never()).setStateToAbort(anyDouble());
        assertThrows(UnsupportedOperationException.class, () -> report.identities().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.violations().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.expectedPopulationIds().clear());
        for (var constructor : Report.class.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        for (var field : Report.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertFalse(QSim.class.isAssignableFrom(field.getType())); assertFalse(Plan.class.isAssignableFrom(field.getType()));
            assertFalse(MobsimAgent.class.isAssignableFrom(field.getType()));
        }
    }

    @Test void absentSelectedPlanIsDescriptiveWhileExplicitExpectedSourceStillGoverns() {
        var f = new Fixture(); Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));
        Plan plan = PopulationUtils.createPlan(person); plan.addActivity(PopulationUtils.createActivityFromLinkId("home", Id.createLinkId("l")));
        f.addPlanAgent("p", plan);
        Report report = inspect(f.qsim, Set.of("p")); assertTrue(report.verifiedSupportedInput());
        assertEquals(SelectedPlanRelation.UNAVAILABLE, report.identities().getFirst().selectedPlanRelation());
    }

    @Test void invalidCallerArgumentsCannotPass() {
        var f = new Fixture();
        assertThrows(NullPointerException.class, () -> inspect(null, Set.of()));
        assertThrows(NullPointerException.class, () -> inspect(f.qsim, null));
        assertThrows(IllegalArgumentException.class, () -> inspect(f.qsim, Set.of(" ")));
    }

    private static Set<Code> codes(Report report) { return report.violations().stream().map(Violation::code).collect(Collectors.toSet()); }
    private static void assertRejected(Report report) {
        assertFalse(report.verifiedSupportedInput()); assertThrows(IllegalStateException.class, report::requireSupportedInput);
        assertFalse(report.preparationBarrierCertified());
    }
    private static final class Fixture {
        final QSim qsim;
        Fixture() {
            var config = ConfigUtils.createConfig(); config.qsim().setNumberOfThreads(1); config.global().setNumberOfThreads(1);
            config.controller().setOutputDirectory(Path.of(".build-cache", "prepared-qsim-input-unit-unused").toAbsolutePath().toString());
            qsim = new QSimBuilder(config).useDefaults().build(ScenarioUtils.createScenario(config), EventsUtils.createEventsManager());
        }
        Person person(String id) {
            var person = PopulationUtils.getFactory().createPerson(Id.createPersonId(id));
            Plan plan = PopulationUtils.createPlan(person); plan.addActivity(PopulationUtils.createActivityFromLinkId("home", Id.createLinkId("l")));
            person.addPlan(plan); person.setSelectedPlan(plan); qsim.getScenario().getPopulation().addPerson(person); return person;
        }
        MobsimAgent addPlanAgent(String id, Plan plan) {
            MobsimAgent agent = mock(MobsimAgent.class, withSettings().extraInterfaces(PlanAgent.class));
            when(agent.getId()).thenReturn(Id.createPersonId(id)); when(((PlanAgent) agent).getCurrentPlan()).thenReturn(plan);
            qsim.insertAgentIntoMobsim(agent); return agent;
        }
    }
}
