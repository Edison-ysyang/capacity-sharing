package org.matsim.run.ismt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Limits;
import org.matsim.run.ismt.dynamic.DynamicMixedDesign;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ScientificRunContextTest {
    @TempDir Path temporary;
    private static final DynamicMixedDesign.Protocol PROTOCOL = new DynamicMixedDesign.Protocol(0, 2, 4, 5, 4, 5);

    @Test void legacyAdaptersKeepEveryArmAndReportingValueWithoutNewMetadata() throws Exception {
        for (var arm : ExperimentSpec.Arm.values()) {
            var spec = legacy(arm);
            var context = ScientificRunContext.legacy(spec);
            assertEquals(spec.experimentId(), context.experimentId());
            assertEquals(spec.blockId(), context.blockId());
            assertEquals(arm.name(), context.arm());
            assertEquals(spec.output().schemaVersion(), context.outputSchema());
            assertEquals(spec.protocol().firstIteration(), context.firstIteration());
            assertEquals(spec.protocol().finalIteration(), context.finalIteration());
            assertEquals(spec.protocol().reportFirstIteration(), context.reportFirstIteration());
            assertEquals(spec.protocol().reportLastIteration(), context.reportLastIteration());
            assertEquals(spec.protocol().behaviouralHorizonSeconds(), context.behaviouralHorizonSeconds());
            assertEquals(spec.protocol().executionHorizonSeconds(), context.executionHorizonSeconds());
            assertEquals(spec.freightDemand().orderCount(), context.orderCount());
            assertEquals(spec.output().passengerExposure().name(), context.passengerExposure().name());
            assertEquals(spec.output().vehicleIntervals(), context.vehicleIntervals());
            assertTrue(context.dynamicDesign().isEmpty());
            assertTrue(context.additionalSchemaMetadata().isEmpty());
            assertEquals("original,header\n", context.fingerprintHeader("original,header\n"));
            assertEquals("", context.fingerprintRowSuffix());
        }
    }

    @Test void dynamicIdentityNeverAliasesLegacyTimetableOnlyAndExplicitlyNamesItsScope() {
        for (var arm : DynamicMixedDesign.Arm.values()) {
            var design = design(arm);
            var context = context(design, arm == DynamicMixedDesign.Arm.CONTROL ? 0 : 1, 200, 240);
            assertEquals(arm.name(), context.arm());
            assertEquals(ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA, context.outputSchema());
            assertEquals(design, context.dynamicDesign().orElseThrow());
            assertEquals("scientific-artifact-context-not-experiment-provenance",
                    context.additionalSchemaMetadata().get("identity_scope"));
            assertEquals("dynamic-mixed-v2", context.additionalSchemaMetadata().get("design_family"));
            assertEquals("original,header,design_family\n", context.fingerprintHeader("original,header\n"));
            assertEquals(",dynamic-mixed-v2", context.fingerprintRowSuffix());
        }
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedDesign.parseArm("TIMETABLE_ONLY"));
    }

    @Test void rejectsArmOrderMismatchAndNegativeOrderCount() {
        assertThrows(IllegalArgumentException.class, () -> context(design(DynamicMixedDesign.Arm.CONTROL), 1, 200, 240));
        assertThrows(IllegalArgumentException.class, () -> context(design(DynamicMixedDesign.Arm.CONTROL), -1, 200, 240));
        for (var arm : new DynamicMixedDesign.Arm[]{DynamicMixedDesign.Arm.MIXED,
                DynamicMixedDesign.Arm.DYNAMIC_MIXED, DynamicMixedDesign.Arm.DEDICATED})
            assertThrows(IllegalArgumentException.class, () -> context(design(arm), 0, 200, 240));
    }

    @Test void rejectsUnavailableNonfiniteAndInvertedHorizons() {
        for (double value : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> context(design(DynamicMixedDesign.Arm.MIXED), 1, value, 240));
            assertThrows(IllegalArgumentException.class, () -> context(design(DynamicMixedDesign.Arm.MIXED), 1, 200, value));
        }
        assertThrows(IllegalArgumentException.class, () -> context(design(DynamicMixedDesign.Arm.MIXED), 1, 240, 240));
        assertThrows(IllegalArgumentException.class, () -> context(design(DynamicMixedDesign.Arm.MIXED), 1, 250, 240));
    }

    @Test void rejectsCsvIdentityInjectionAndMissingOptions() {
        for (String id : new String[]{"", "a,b", "a\nb", "a\"b", "../elsewhere", "with space"}) {
            assertThrows(IllegalArgumentException.class, () -> ScientificRunContext.dynamic(
                    design(DynamicMixedDesign.Arm.MIXED), id, "B1", 1, 200, 240,
                    ScientificRunContext.ExposureOutput.TERMINAL, false));
            assertThrows(IllegalArgumentException.class, () -> ScientificRunContext.dynamic(
                    design(DynamicMixedDesign.Arm.MIXED), "fixture", id, 1, 200, 240,
                    ScientificRunContext.ExposureOutput.TERMINAL, false));
        }
        assertThrows(NullPointerException.class, () -> ScientificRunContext.dynamic(
                design(DynamicMixedDesign.Arm.MIXED), "fixture", "B1", 1, 200, 240, null, false));
    }

    @Test void metadataIsImmutableAndRecordsConfiguredNotActualControl() {
        var context = context(design(DynamicMixedDesign.Arm.DYNAMIC_MIXED), 1, 200, 240);
        var metadata = context.additionalSchemaMetadata();
        assertThrows(UnsupportedOperationException.class, () -> metadata.put("arm", "TIMETABLE_ONLY"));
        assertEquals(2, metadata.get("activation_iteration"));
        assertEquals(4, metadata.get("innovation_off_iteration"));
        assertEquals(25.0, metadata.get("maximum_advance_seconds"));
        assertEquals(5.0, metadata.get("lock_window_seconds"));
        assertTrue(metadata.get("control_metadata_semantics").toString().contains("configured"));
        assertTrue(metadata.get("phase_active_semantics").toString().contains("not actual dispatch"));
        assertThrows(IllegalArgumentException.class, () -> context.fingerprintHeader("unterminated"));
    }

    @Test void v2AndLegacyContextUseExactlyTheSamePlanAndEventDigestAlgorithms() throws Exception {
        var spec = legacy(ExperimentSpec.Arm.CONTROL);
        var context = ScientificRunContext.dynamic(design(DynamicMixedDesign.Arm.CONTROL), spec.experimentId(),
                spec.blockId(), 0, 200, 240, ScientificRunContext.ExposureOutput.TERMINAL, false);
        Path ordersFile = temporary.resolve("empty-orders.csv");
        Files.writeString(ordersFile, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n");
        var registry = new FreightOrderRegistry(CoTransportSettings.builder(ordersFile).build());
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var person = scenario.getPopulation().getFactory().createPerson(Id.createPersonId("p"));
        var plan = scenario.getPopulation().getFactory().createPlan();
        plan.addActivity(scenario.getPopulation().getFactory().createActivityFromLinkId("home", Id.createLinkId("l")));
        plan.setScore(-12.3456789012345); plan.getAttributes().putAttribute("typed", 42);
        person.addPlan(plan); scenario.getPopulation().addPerson(person);
        var oldPlans = new PassengerPlanFingerprint(spec, scenario, registry, temporary.resolve("old-plans.csv"));
        var newPlans = new PassengerPlanFingerprint(context, scenario, registry, temporary.resolve("new-plans.csv"));
        assertEquals(oldPlans.fingerprintNow(), newPlans.fingerprintNow());
        var end = new IterationEndsEvent(null, 0, false);
        oldPlans.notifyIterationEnds(end); newPlans.notifyIterationEnds(end);
        assertEquals(oldPlans.completedFingerprints(), newPlans.completedFingerprints());
        assertEnvelopeOnlyDifference(temporary.resolve("old-plans.csv"), temporary.resolve("new-plans.csv"));

        var oldEvents = new PassengerScientificFingerprint(spec, registry, scenario, temporary.resolve("old-events.csv"));
        var newEvents = new PassengerScientificFingerprint(context, registry, scenario, temporary.resolve("new-events.csv"));
        oldEvents.reset(0); newEvents.reset(0);
        var event = new PersonDepartureEvent(12.3456789, person.getId(), Id.createLinkId("l"), "pt", "pt");
        oldEvents.handleEvent(event); newEvents.handleEvent(event);
        oldEvents.notifyIterationEnds(end); newEvents.notifyIterationEnds(end);
        assertEquals(oldEvents.completedFingerprints(), newEvents.completedFingerprints());
        assertEnvelopeOnlyDifference(temporary.resolve("old-events.csv"), temporary.resolve("new-events.csv"));
        assertSame(plan, person.getSelectedPlan());
        assertEquals(-12.3456789012345, plan.getScore());
    }

    private static void assertEnvelopeOnlyDifference(Path legacy, Path dynamic) throws Exception {
        var oldRows = Files.readAllLines(legacy);
        var newRows = Files.readAllLines(dynamic);
        assertEquals(oldRows.size(), newRows.size());
        assertEquals(oldRows.getFirst() + ",design_family", newRows.getFirst());
        for (int index = 1; index < oldRows.size(); index++)
            assertEquals(oldRows.get(index) + ",dynamic-mixed-v2", newRows.get(index));
    }

    private static ScientificRunContext context(DynamicMixedDesign design, int count, double behaviour, double execution) {
        return ScientificRunContext.dynamic(design, "context-fixture", "B1", count, behaviour, execution,
                ScientificRunContext.ExposureOutput.REPORTING_WINDOW, false);
    }

    private static DynamicMixedDesign design(DynamicMixedDesign.Arm arm) {
        return new DynamicMixedDesign(arm, PROTOCOL, arm == DynamicMixedDesign.Arm.DYNAMIC_MIXED, new Limits(25, 25, 5));
    }

    private static ExperimentSpec legacy(ExperimentSpec.Arm arm) throws Exception {
        for (Path parent = Path.of("").toAbsolutePath(); parent != null; parent = parent.getParent()) {
            Path profile = parent.resolve("experiments/ismt_q720");
            if (Files.isDirectory(profile)) return ExperimentSpecIO.compose(profile, "B1", arm);
        }
        throw new AssertionError("Missing checked-in legacy profiles");
    }
}
