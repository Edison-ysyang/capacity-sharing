package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ScientificRunContext;
import org.matsim.run.ismt.UnifiedMixedComparison;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Value-only toy fixtures: no files, preparation, simulation, forecast or runtime construction. */
class UnifiedMixedComparisonTest {
    @Test void armAndControlSwitchAreTheOnlyScientificExclusionsAndOrderIsSymmetric() {
        var m = windowed(DynamicMixedDesign.Arm.MIXED);
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        assertFalse(m.design().controlEnabled());
        assertTrue(t.design().controlEnabled());
        assertEquals(UnifiedMixedComparison.compare(m, t), UnifiedMixedComparison.compare(t, m));
        var result = UnifiedMixedComparison.requireCompatible(m, t);
        assertTrue(result.declaredEqual());
        assertTrue(result.mismatchPaths().isEmpty());
        assertFalse(result.inputBytesVerified(), "Toy paths deliberately need not exist");
        assertFalse(result.runtimeVerified());
        // Comparing a disabled T declaration also cannot certify an active treatment.
        assertTrue(UnifiedMixedComparison.compare(m,
                replace(t, "design", replace(t.design(), "controlEnabled", false))).declaredEqual());
    }

    @Test void rejectsSameArmOtherArmsAndNulls() {
        var m = windowed(DynamicMixedDesign.Arm.MIXED);
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        assertThrows(IllegalArgumentException.class, () -> UnifiedMixedComparison.compare(m, m));
        assertThrows(IllegalArgumentException.class, () -> UnifiedMixedComparison.compare(t, t));
        for (var arm : List.of(DynamicMixedDesign.Arm.CONTROL, DynamicMixedDesign.Arm.DEDICATED))
            assertThrows(IllegalArgumentException.class, () -> UnifiedMixedComparison.compare(m, windowed(arm)));
        assertThrows(NullPointerException.class, () -> UnifiedMixedComparison.compare(null, t));
        assertThrows(NullPointerException.class, () -> UnifiedMixedComparison.compare(m, null));
    }

    @Test void bothDeclarationsMustPassTheExistingWindowedEntryContract() {
        var m = windowed(DynamicMixedDesign.Arm.MIXED);
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        for (var valid : List.of(m, t)) {
            var oldPolicy = replace(valid, "control", replace(valid.control(), "policyId", DynamicMixedControlDefinitions.POLICY_ID));
            var repaired = replace(valid, "control", replace(valid.control(), "baselineTreatment",
                    DynamicMixedExperimentSpec.BaselineTreatment.FREIGHT_BLIND_PRE_SERVICE_REPAIR));
            var unsupportedEvidence = replace(valid, "output", replace(valid.output(), "dispatchEvidenceSchemaVersion", "other-evidence-v1"));
            for (var invalid : List.of(oldPolicy, repaired, unsupportedEvidence)) {
                assertThrows(IllegalArgumentException.class, () -> UnifiedMixedComparison.compare(invalid, t));
                assertThrows(IllegalArgumentException.class, () -> UnifiedMixedComparison.compare(m, invalid));
            }
        }
    }

    @Test void experimentBlockAndValidationProfileAreCompared() {
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        for (String field : List.of("experimentId", "blockId", "validationProfile"))
            assertMismatch(replace(t, field, "different-valid-identity"), "/" + field);
    }

    @Test void allThreeSeedsAreCompared() {
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        for (String field : List.of("passenger", "orderPool", "matsimGlobal"))
            assertMismatch(replace(t, "seeds", replace(t.seeds(), field, 99L)), "/seeds/" + field);
    }

    @Test void protocolAndDisplacementLimitsAreNotHiddenWithTheDesignSwitch() {
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        Map<String, Integer> endpoints = Map.of("activationIteration", 3, "innovationOffIteration", 3,
                "finalIteration", 6, "reportFirstIteration", 5, "reportLastIteration", 4);
        endpoints.forEach((field, value) -> assertMismatch(replace(t, "design",
                replace(t.design(), "protocol", replace(t.design().protocol(), field, value))), "/design/protocol/" + field));
        for (String field : List.of("maximumAdvanceSeconds", "maximumDelaySeconds", "lockWindowSeconds")) {
            double value = field.equals("lockWindowSeconds") ? 6 : 26;
            assertMismatch(replace(t, "design", replace(t.design(), "limits",
                    replace(t.design().limits(), field, value))), "/design/limits/" + field);
        }
    }

    @Test void allFreightDemandFieldsAreCompared() {
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        Map<String, Object> changes = Map.of("orderCount", 2, "equivalentPassengersPerM3", 6.0,
                "serviceWindowStartSeconds", 1.0, "serviceWindowEndSeconds", 190.0,
                "destinationActivityPrefix", "other_destination", "shadowToken", "_capacity_");
        changes.forEach((field, value) -> assertMismatch(replace(t, "freightDemand",
                replace(t.freightDemand(), field, value)), "/freightDemand/" + field));
    }

    @Test void admissionScoringAndAdaptationFieldsAreComparedEvenWhenTheyAreLegacyMetadata() {
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        for (String field : List.of("freightShareCap", "passengerLoadGate", "legacyHardCapacityRatio", "comfortThreshold"))
            assertMismatch(replace(t, "policies", replace(t.policies(), field, 0.5)), "/policies/" + field);
        for (String field : List.of("handlingDwellSeconds", "betaPhysical", "betaFreight", "freightLatePenaltyPerMinute",
                "freightDeniedBoardingPenalty", "freightIncompletePenaltyPerUnit", "passengerDeniedBoardingPenalty"))
            assertMismatch(replace(t, "policies", replace(t.policies(), field, 2.0)), "/policies/" + field);
        assertMismatch(replace(t, "policies", replace(t.policies(), "passengerAdaptation", false)), "/policies/passengerAdaptation");
    }

    @Test void arrayOrderAndAdditionalElementsRemainScientificDifferences() {
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        var reordered = replace(t, "policies", replace(t.policies(), "transitModes", List.of("freight_pt", "pt")));
        assertEquals(List.of("/policies/transitModes/0", "/policies/transitModes/1"),
                UnifiedMixedComparison.compare(windowed(DynamicMixedDesign.Arm.MIXED), reordered).mismatchPaths());
        assertMismatch(replace(t, "policies", replace(t.policies(), "transitModes", List.of("pt", "freight_pt", "extra"))),
                "/policies/transitModes/2");
    }

    @Test void runtimeDeclarationIncludesThreadsClockInterpretationAndBothHorizons() {
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        Map<String, Object> changes = Map.of("qsimThreads", 8, "globalThreads", 8,
                "eventWorkerSelection", DynamicMixedExperimentSpec.EventWorkerSelection.EXPLICIT_SINGLE_WORKER,
                "qsimTimeStepSeconds", 2.0, "qsimStartTimeSeconds", 1.0,
                "startTimeInterpretation", QSimConfigGroup.StarttimeInterpretation.maxOfStarttimeAndEarliestActivityEnd,
                "behaviouralHorizonSeconds", 210.0, "executionHorizonSeconds", 250.0);
        changes.forEach((field, value) -> assertMismatch(replace(t, "runtime",
                replace(t.runtime(), field, value)), "/runtime/" + field));
    }

    @Test void controllerTimingOperatingBasisAndEveryDefinitionSchemaAreCompared() {
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        for (String field : List.of("firstDecisionTimeSeconds", "decisionIntervalSeconds", "lookAheadSeconds", "candidateStepSeconds"))
            assertMismatch(replace(t, "control", replace(t.control(), "timing",
                    replace(t.control().timing(), field, 11.0))), "/control/timing/" + field);
        assertMismatch(replace(t, "control", replace(t.control(), "operatingBasis",
                DynamicMixedExperimentSpec.OperatingBasis.LINE_DOCUMENTED_ASSUMPTIONS)), "/control/operatingBasis");
        for (String role : t.allReferencedFiles().keySet()) {
            if (role.startsWith("inputs.")) continue;
            var changed = (DynamicMixedExperimentSpec) replaceAt(t, role + ".schemaVersion", "different-definition-v2");
            assertMismatch(changed, "/" + role.replace('.', '/') + "/schemaVersion");
        }
    }

    @Test void allMutableOutputOptionsAreComparedRatherThanIgnoredAsBookkeeping() {
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        Map<String, Object> changes = Map.of("passengerExposure", ScientificRunContext.ExposureOutput.TERMINAL,
                "vehicleIntervals", true, "eventsWriteInterval", 1, "plansWriteInterval", 1);
        changes.forEach((field, value) -> assertMismatch(replace(t, "output",
                replace(t.output(), field, value)), "/output/" + field));
    }

    @Test void allNineteenFileRolesAllowOnlyTheirPathAliasToVary() {
        var m = windowed(DynamicMixedDesign.Arm.MIXED);
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        assertEquals(19, t.allReferencedFiles().size());
        var aliased = t;
        for (var entry : t.allReferencedFiles().entrySet()) {
            var alias = new ExperimentSpec.FileRef("nonexistent/alias/" + entry.getKey() + ".xml", entry.getValue().sha256());
            aliased = (DynamicMixedExperimentSpec) replaceAt(aliased, fileField(entry.getKey()), alias);
            assertTrue(UnifiedMixedComparison.compare(m, aliased).declaredEqual(), entry.getKey());
        }
        assertFalse(UnifiedMixedComparison.requireCompatible(m, aliased).inputBytesVerified());
    }

    @Test void everyOneOfNineteenDeclaredDigestsIsCompared() {
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        for (String role : t.allReferencedFiles().keySet()) {
            var changedRef = new ExperimentSpec.FileRef("nonexistent/different-bytes.xml", "b".repeat(64));
            var changed = withChangedDigest(t, role, changedRef);
            String expected = "/" + fileField(role).replace('.', '/') + "/sha256";
            var result = UnifiedMixedComparison.compare(windowed(DynamicMixedDesign.Arm.MIXED), changed);
            assertFalse(result.declaredEqual(), role);
            assertTrue(result.mismatchPaths().contains(expected), expected);
            assertFalse(result.inputBytesVerified());
            assertFalse(result.runtimeVerified());
        }
    }

    @Test void mismatchPathsAreCompleteStableAndIncludedInTheFailClosedError() {
        var m = windowed(DynamicMixedDesign.Arm.MIXED);
        var t = windowed(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        var changed = replace(replace(replace(t, "blockId", "B2"), "experimentId", "other-experiment"),
                "policies", replace(t.policies(), "betaFreight", 0.9));
        var result = UnifiedMixedComparison.compare(m, changed);
        var expected = List.of("/blockId", "/experimentId", "/policies/betaFreight");
        assertEquals(expected, result.mismatchPaths());
        assertEquals(result, UnifiedMixedComparison.compare(changed, m));
        var failure = assertThrows(IllegalArgumentException.class, () -> UnifiedMixedComparison.requireCompatible(m, changed));
        expected.forEach(path -> assertTrue(failure.getMessage().contains(path)));
        assertThrows(UnsupportedOperationException.class, () -> result.mismatchPaths().clear());
    }

    @Test void comparisonResultCannotBeRelabelledAsFileOrRuntimeVerification() {
        assertThrows(IllegalArgumentException.class, () -> new UnifiedMixedComparison.Comparison(true, true, false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new UnifiedMixedComparison.Comparison(true, false, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new UnifiedMixedComparison.Comparison(true, false, false, List.of("/seeds")));
        assertThrows(IllegalArgumentException.class, () -> new UnifiedMixedComparison.Comparison(false, false, false, List.of()));
        var paths = new ArrayList<>(List.of("/seeds/passenger"));
        var result = new UnifiedMixedComparison.Comparison(false, false, false, paths);
        paths.clear();
        assertEquals(List.of("/seeds/passenger"), result.mismatchPaths());
    }

    @Test void exactFileRefShapeAndExactDesignLocationAreRequiredForNormalizationExclusions() throws Exception {
        // The typed schema cannot carry these extra JSON shapes. Inspect only the
        // pure normalizer so a future extension cannot silently broaden exclusions.
        var method = UnifiedMixedComparison.class.getDeclaredMethod("comparable", JsonNode.class, String.class);
        method.setAccessible(true);
        var json = JsonMapper.builder().build();
        var exact = json.readTree("{\"path\":\"alias\",\"sha256\":\"digest\"}");
        JsonNode exactResult = (JsonNode) method.invoke(null, exact, "/inputs/example");
        assertFalse(exactResult.has("path"));
        assertEquals("digest", exactResult.path("sha256").asText());
        for (String source : List.of("{\"path\":\"alias\",\"sha256\":\"digest\",\"other\":1}",
                "{\"path\":\"alias\"}", "{\"path\":\"alias\",\"sha256\":1}")) {
            var object = json.readTree(source);
            assertEquals(object, method.invoke(null, object, "/inputs/example"));
        }
        var elsewhere = json.readTree("{\"arm\":\"x\",\"controlEnabled\":true,\"limits\":1}");
        assertEquals(elsewhere, method.invoke(null, elsewhere, "/other/design"));
        var design = (JsonNode) method.invoke(null, elsewhere, "/design");
        assertEquals(json.readTree("{\"limits\":1}"), design);
        assertEquals(3, elsewhere.size(), "Normalization never mutates the caller's tree");
    }

    private static void assertMismatch(DynamicMixedExperimentSpec changedT, String expectedPath) {
        var m = windowed(DynamicMixedDesign.Arm.MIXED);
        var result = UnifiedMixedComparison.compare(m, changedT);
        assertFalse(result.declaredEqual());
        assertEquals(List.of(expectedPath), result.mismatchPaths());
        assertFalse(result.inputBytesVerified());
        assertFalse(result.runtimeVerified());
    }

    private static DynamicMixedExperimentSpec windowed(DynamicMixedDesign.Arm arm) {
        var spec = DynamicMixedSpecFixtures.spec(arm);
        return replace(replace(spec, "control", replace(spec.control(), "policyId", DynamicMixedControlDefinitions.WINDOWED_POLICY_ID)),
                "output", replace(spec.output(), "dispatchEvidenceSchemaVersion", DynamicMixedDispatchEvidence.SCHEMA));
    }

    private static String fileField(String role) {
        return role.startsWith("inputs.") ? role : role + ".file";
    }

    private static DynamicMixedExperimentSpec withChangedDigest(DynamicMixedExperimentSpec spec, String role, ExperimentSpec.FileRef value) {
        // The spec constructor itself requires baseline/activated supply hashes
        // to agree, so change both supply roles before reconstructing the spec.
        String mate = switch (role) {
            case "inputs.baselineSchedule" -> "activatedSchedule";
            case "inputs.activatedSchedule" -> "baselineSchedule";
            case "inputs.baselineTransitVehicles" -> "activatedTransitVehicles";
            case "inputs.activatedTransitVehicles" -> "baselineTransitVehicles";
            default -> null;
        };
        if (mate != null) {
            var inputs = replace(replace(spec.inputs(), role.substring("inputs.".length()), value), mate, value);
            return replace(spec, "inputs", inputs);
        }
        return (DynamicMixedExperimentSpec) replaceAt(spec, fileField(role), value);
    }

    private static Record replaceAt(Record value, String dottedPath, Object replacement) {
        int separator = dottedPath.indexOf('.');
        if (separator < 0) return replace(value, dottedPath, replacement);
        String field = dottedPath.substring(0, separator);
        try {
            var child = (Record) value.getClass().getMethod(field).invoke(value);
            return replace(value, field, replaceAt(child, dottedPath.substring(separator + 1), replacement));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
