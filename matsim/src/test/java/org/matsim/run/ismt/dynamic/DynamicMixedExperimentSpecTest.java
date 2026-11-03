package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ScientificRunContext;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.*;

class DynamicMixedExperimentSpecTest {
    @Test void exactlyFourNewArmsRetainTheirFreightAndSupplyMeanings() {
        assertEquals(4, DynamicMixedDesign.Arm.values().length);
        for (var arm : DynamicMixedDesign.Arm.values()) {
            var value = spec(arm);
            assertEquals(arm.name(), value.scientificContext().arm());
            assertEquals(arm != DynamicMixedDesign.Arm.CONTROL, value.freightDemand().orderCount() > 0);
            assertEquals(arm == DynamicMixedDesign.Arm.DYNAMIC_MIXED, value.design().controlEnabled());
        }
        assertEquals(4, ExperimentSpec.Arm.values().length);
        assertTrue(Arrays.stream(ExperimentSpec.Arm.values()).anyMatch(arm -> arm.name().equals("TIMETABLE_ONLY")));
    }

    @Test void rejectsWrongIdentitySchemaAndMissingFullSections() {
        for (String field : new String[]{"experimentId", "blockId", "validationProfile"})
            assertThrows(IllegalArgumentException.class, () -> replace(spec(), field, "bad,id"));
        assertThrows(IllegalArgumentException.class, () -> replace(spec(), "schemaVersion", ExperimentSpec.SCHEMA));
        for (String field : new String[]{"design", "seeds", "inputs", "freightDemand", "policies", "runtime", "control", "output"})
            assertThrows(NullPointerException.class, () -> replace(spec(), field, null));
    }

    @Test void noEmptyFreightOrDedicatedAdmissionCanMasqueradeAsDynamicMixed() {
        var value = spec();
        assertThrows(IllegalArgumentException.class, () -> replace(value, "freightDemand", replace(value.freightDemand(), "orderCount", 0)));
        assertThrows(IllegalArgumentException.class, () -> replace(value, "policies", replace(value.policies(), "access", ExperimentSpec.Access.NO_FREIGHT)));
        assertThrows(IllegalArgumentException.class, () -> replace(value, "policies", replace(value.policies(), "dedicatedLineId", "freight-only")));
        var control = spec(DynamicMixedDesign.Arm.CONTROL);
        assertThrows(IllegalArgumentException.class, () -> replace(control, "freightDemand", value.freightDemand()));
        var dedicated = spec(DynamicMixedDesign.Arm.DEDICATED);
        assertThrows(IllegalArgumentException.class, () -> replace(dedicated, "policies", replace(dedicated.policies(), "dedicatedLineId", "")));
    }

    @Test void fixedBaselineArmsCompareContentRatherThanNamesAndRejectStaticSupplyChanges() {
        for (var arm : new DynamicMixedDesign.Arm[]{DynamicMixedDesign.Arm.CONTROL, DynamicMixedDesign.Arm.MIXED,
                DynamicMixedDesign.Arm.DYNAMIC_MIXED}) {
            var value = spec(arm);
            var alias = new ExperimentSpec.FileRef("toy/same-bytes-different-path.xml", "a".repeat(64));
            assertDoesNotThrow(() -> replace(value, "inputs", replace(value.inputs(), "activatedSchedule", alias)));
            for (String field : new String[]{"activatedSchedule", "activatedTransitVehicles"})
                assertThrows(IllegalArgumentException.class, () -> replace(value, "inputs", replace(value.inputs(), field,
                        new ExperimentSpec.FileRef("toy/changed.xml", "b".repeat(64)))));
        }
    }

    @Test void declarationsCoverAllNineteenFileRolesWithoutReadingOrCertifyingThem() {
        var value = spec();
        var references = value.allReferencedFiles();
        assertEquals(19, references.size());
        for (String role : value.inputs().all().keySet()) assertTrue(references.containsKey("inputs." + role));
        assertTrue(references.containsKey("runtime.resolvedConfiguration"));
        assertTrue(references.containsKey("control.baselineTreatmentDefinition"));
        assertThrows(UnsupportedOperationException.class, () -> references.clear());
        assertFalse(Arrays.stream(DynamicMixedExperimentSpec.class.getRecordComponents()).anyMatch(c -> c.getName().equals("approved")));
        assertFalse(Arrays.stream(DynamicMixedExperimentSpec.class.getMethods()).anyMatch(m -> m.getName().equals("isExecutable") || m.getName().equals("canRun")));
    }

    @Test void defaultAndExplicitEventWorkerSelectionRemainDistinctDeclarations() {
        var value = spec().runtime();
        var explicit = replace(value, "eventWorkerSelection", DynamicMixedExperimentSpec.EventWorkerSelection.EXPLICIT_SINGLE_WORKER);
        assertNotEquals(value, explicit);
        assertEquals(value.expectedEventWorkers(), explicit.expectedEventWorkers());
        for (String field : new String[]{"qsimThreads", "globalThreads"})
            assertThrows(IllegalArgumentException.class, () -> replace(value, field, 0));
        for (int workers : new int[]{0, 2}) assertThrows(IllegalArgumentException.class, () -> replace(value, "expectedEventWorkers", workers));
        assertThrows(IllegalArgumentException.class, () -> replace(value, "oneThreadPerHandler", true));
        assertThrows(IllegalArgumentException.class, () -> replace(value, "synchronizeOnSimSteps", false));
        assertThrows(IllegalArgumentException.class, () -> replace(value, "observationLagSteps", 1));
    }

    @Test void requiresExplicitFiniteNativeStepAndSplitHorizons() {
        var runtime = spec().runtime();
        for (String field : new String[]{"qsimTimeStepSeconds", "behaviouralHorizonSeconds", "executionHorizonSeconds"})
            for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
                assertThrows(IllegalArgumentException.class, () -> replace(runtime, field, invalid));
        assertThrows(IllegalArgumentException.class, () -> replace(runtime, "qsimStartTimeSeconds", -1.0));
        assertThrows(IllegalArgumentException.class, () -> replace(runtime, "qsimStartTimeSeconds", 200.0));
        assertThrows(IllegalArgumentException.class, () -> replace(runtime, "executionHorizonSeconds", 200.0));
        assertThrows(IllegalArgumentException.class, () -> replace(runtime, "endTimeInterpretation", QSimConfigGroup.EndtimeInterpretation.minOfEndtimeAndMobsimFinished));
    }

    @Test void decisionTimingIsExplicitAndCompatibleWithDeclaredDayAndLockWindow() {
        var value = spec();
        var timing = value.control().timing();
        for (String field : new String[]{"decisionIntervalSeconds", "lookAheadSeconds", "candidateStepSeconds"})
            for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
                assertThrows(IllegalArgumentException.class, () -> replace(timing, field, invalid));
        assertThrows(IllegalArgumentException.class, () -> replace(timing, "firstDecisionTimeSeconds", -1.0));
        for (String field : new String[]{"decisionIntervalSeconds", "candidateStepSeconds"})
            assertThrows(IllegalArgumentException.class, () -> replace(value, "control",
                    replace(value.control(), "timing", replace(timing, field, 0.5))));
        assertThrows(IllegalArgumentException.class, () -> replace(value, "control",
                replace(value.control(), "timing", replace(timing, "lookAheadSeconds", 5.0))));
        assertThrows(IllegalArgumentException.class, () -> replace(value, "control",
                replace(value.control(), "timing", replace(timing, "firstDecisionTimeSeconds", 240.0))));
    }

    @Test void everyControlDefinitionAndItsFormatIsRequiredWithoutDefaultRepair() {
        var control = spec().control();
        for (String field : new String[]{"policyDefinition", "constraintDefinition", "forecastDefinition",
                "passengerProtectionDefinition", "informationDefinition", "baselineTreatmentDefinition"})
            assertThrows(NullPointerException.class, () -> replace(control, field, null));
        assertThrows(IllegalArgumentException.class, () -> replace(control.policyDefinition(), "schemaVersion", ""));
        assertEquals(DynamicMixedExperimentSpec.BaselineTreatment.NO_PRE_SERVICE_REPAIR, control.baselineTreatment());
        var repaired = replace(control, "baselineTreatment", DynamicMixedExperimentSpec.BaselineTreatment.FREIGHT_BLIND_PRE_SERVICE_REPAIR);
        assertNotEquals(control, repaired, "Repair is an explicit scientific declaration, never a hidden forecast change");
    }

    @Test void noResumeCalendarOrBeyondExecutionFreightWindowIsAccepted() {
        var value = spec();
        var later = new DynamicMixedDesign(value.design().arm(), new DynamicMixedDesign.Protocol(1, 2, 4, 5, 4, 5),
                true, value.design().limits());
        assertThrows(IllegalArgumentException.class, () -> replace(value, "design", later));
        assertThrows(IllegalArgumentException.class, () -> replace(value, "freightDemand",
                replace(value.freightDemand(), "serviceWindowEndSeconds", 241.0)));
    }

    @Test void outputContextHasExactlyRegisteredValuesWithoutAdoptingAnyPolicy() {
        var value = spec();
        var context = value.scientificContext();
        assertEquals(value.experimentId(), context.experimentId()); assertEquals(value.blockId(), context.blockId());
        assertEquals(value.design(), context.dynamicDesign().orElseThrow());
        assertEquals(value.runtime().executionHorizonSeconds(), context.executionHorizonSeconds());
        assertEquals(value.output().passengerExposure(), context.passengerExposure());
        assertEquals(ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA, context.outputSchema());
        assertThrows(IllegalArgumentException.class, () -> replace(value.output(), "schemaVersion", "ismt-output-v1"));
        assertThrows(IllegalArgumentException.class, () -> replace(value.output(), "eventsWriteInterval", -1));
        assertThrows(IllegalArgumentException.class, () -> replace(value.output(), "dispatchEvidenceSchemaVersion", ""));
    }
}
