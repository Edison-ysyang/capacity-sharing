package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ScientificRunContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedDeclaredPairing.compareHistoricalMixed;

/** Entirely synthetic declarations: no profile, input, receipt or result file is read or created. */
class DynamicMixedDeclaredPairingTest {
    private static final String ORIGINAL_SHA = "a".repeat(64);
    private static final String DIFFERENT_SHA = "b".repeat(64);

    @Test void matchingCommonDeclarationsRetainBothIdentitiesAndAllUnverifiedRequirements() {
        var historical = historical();
        var target = target(historical);
        var report = compareHistoricalMixed(historical, target);
        assertTrue(report.declaredFieldsMatch());
        assertEquals(DynamicMixedDeclaredPairing.Scope.DECLARED_COMMON_FIELDS_ONLY, report.scope());
        assertEquals(47, report.checkedFields().size());
        assertEquals(47, Set.copyOf(report.checkedFields()).size());
        assertTrue(report.differences().isEmpty());
        assertTrue(report.inputPathDifferences().isEmpty());
        assertEquals(new DynamicMixedDeclaredPairing.Identity(ExperimentSpec.SCHEMA, "historical-fixture", "B1", "MIXED"), report.historicalIdentity());
        assertEquals(new DynamicMixedDeclaredPairing.Identity(DynamicMixedExperimentSpec.SCHEMA, "new-fixture", "B1", "DYNAMIC_MIXED"), report.targetIdentity());
        assertEquals(target.design(), report.targetDesign());
        assertEquals(target.control(), report.targetControlRegistration());
        assertEquals(Set.of(DynamicMixedDeclaredPairing.UnverifiedAspect.values()), Set.copyOf(report.unverifiedAspects()));
        assertEquals(7, report.unverifiedAspects().size());
        assertFalse(Arrays.stream(report.getClass().getMethods()).anyMatch(method ->
                Set.of("valid", "isValid", "reusable", "canRun", "isExecutable").contains(method.getName())));
    }

    @Test void experimentNamespaceIsRetainedButNotMistakenForACommonScientificInput() {
        var historical = historical();
        var target = change(target(historical), "experimentId", "a-separate-v2-namespace");
        var report = compareHistoricalMixed(historical, target);
        assertTrue(report.declaredFieldsMatch());
        assertEquals("historical-fixture", report.historicalIdentity().experimentId());
        assertEquals("a-separate-v2-namespace", report.targetIdentity().experimentId());
        assertFalse(report.checkedFields().contains("experimentId"));
    }

    @Test void blockIdentityIsCompared() {
        var historical = historical();
        assertDifference(compareHistoricalMixed(historical, change(target(historical), "blockId", "B2")), "blockId");
    }

    @Test void eachOfTheTwelveDeclaredInputHashesIsComparedIndependently() {
        var historical = historical();
        var target = target(historical);
        assertEquals(12, ExperimentSpec.Inputs.class.getRecordComponents().length);
        for (RecordComponent component : ExperimentSpec.Inputs.class.getRecordComponents()) {
            var oldRef = (ExperimentSpec.FileRef) value(historical.inputs(), component.getName());
            var changedInputs = change(historical.inputs(), component.getName(), new ExperimentSpec.FileRef(oldRef.path(), DIFFERENT_SHA));
            var report = compareHistoricalMixed(change(historical, "inputs", changedInputs), target);
            assertDifference(report, "inputs." + component.getName() + ".sha256");
            assertEquals(DIFFERENT_SHA, report.differences().getFirst().historicalValue());
            assertEquals(ORIGINAL_SHA, report.differences().getFirst().targetValue());
        }
    }

    @Test void matchingDeclaredHashesAtDifferentPathsAreReportedWithoutClaimingVerifiedBytes() {
        var historical = historical();
        var target = target(historical);
        var inputs = target.inputs();
        for (RecordComponent component : ExperimentSpec.Inputs.class.getRecordComponents()) {
            var ref = (ExperimentSpec.FileRef) value(inputs, component.getName());
            inputs = change(inputs, component.getName(), new ExperimentSpec.FileRef("elsewhere-declared-only/" + component.getName(), ref.sha256()));
        }
        var report = compareHistoricalMixed(historical, change(target, "inputs", inputs));
        assertTrue(report.declaredFieldsMatch());
        assertEquals(12, report.inputPathDifferences().size());
        for (var alias : report.inputPathDifferences()) {
            assertEquals(ORIGINAL_SHA, alias.declaredSha256());
            assertNotEquals(alias.historicalPath(), alias.targetPath());
            assertTrue(alias.targetPath().startsWith("elsewhere-declared-only/"));
        }
        assertTrue(report.unverifiedAspects().contains(DynamicMixedDeclaredPairing.UnverifiedAspect.FILE_CONTENTS_AND_WORKSPACE_PATH_CONTAINMENT));
    }

    @Test void differentHashesAtDifferentPathsAreNotListedAsMatchingHashAliases() {
        var historical = historical();
        var changedInputs = change(historical.inputs(), "network", new ExperimentSpec.FileRef("different/network", DIFFERENT_SHA));
        var report = compareHistoricalMixed(change(historical, "inputs", changedInputs), target(historical));
        assertDifference(report, "inputs.network.sha256");
        assertTrue(report.inputPathDifferences().isEmpty());
    }

    @Test void everySeedComponentIsCompared() {
        var historical = historical();
        for (RecordComponent component : ExperimentSpec.Seeds.class.getRecordComponents()) {
            long prior = (Long) value(historical.seeds(), component.getName());
            var report = compareHistoricalMixed(change(historical, "seeds", change(historical.seeds(), component.getName(), prior + 1)), target(historical));
            assertDifference(report, "seeds." + component.getName());
        }
    }

    @Test void everyFreightDemandComponentIsComparedWithoutAnOrderCountExclusion() {
        var historical = historical();
        for (RecordComponent component : ExperimentSpec.FreightDemand.class.getRecordComponents()) {
            Object prior = value(historical.freightDemand(), component.getName());
            Object replacement;
            if (prior instanceof Integer number) replacement = number + 1;
            else if (prior instanceof Double number) replacement = number + 0.125;
            else replacement = prior + "-changed";
            var report = compareHistoricalMixed(change(historical, "freightDemand", change(historical.freightDemand(), component.getName(), replacement)), target(historical));
            assertDifference(report, "freightDemand." + component.getName());
        }
    }

    @Test void everyPolicyComponentIsCoveredAndEveryPermittedMutationIsCompared() {
        var historical = historical();
        var target = target(historical);
        var unchanged = compareHistoricalMixed(historical, target);
        for (RecordComponent component : ExperimentSpec.Policies.class.getRecordComponents()) {
            String field = component.getName();
            assertTrue(unchanged.checkedFields().contains("policies." + field), field);
            Object prior = value(historical.policies(), field);
            Object replacement;
            if (field.equals("access")) replacement = ExperimentSpec.Access.NO_FREIGHT;
            else if (field.equals("dedicatedLineId")) replacement = "not-a-shared-line";
            else if (prior instanceof List<?>) replacement = List.of("freight_pt", "pt");
            else if (prior instanceof Boolean flag) replacement = !flag;
            else replacement = (Double) prior + 0.01;
            var changedPolicies = change(historical.policies(), field, replacement);
            if (field.equals("access") || field.equals("dedicatedLineId")) {
                // Legal M/T declarations cannot carry these mismatches. Keep both outer contracts strict.
                assertThrows(IllegalArgumentException.class, () -> change(historical, "policies", changedPolicies));
                assertThrows(IllegalArgumentException.class, () -> change(target, "policies", changedPolicies));
            } else {
                assertDifference(compareHistoricalMixed(change(historical, "policies", changedPolicies), target), "policies." + field);
            }
        }
    }

    @Test void legacyOffAndReopenFieldsAreMappedToTheirActualNewPhaseMeanings() {
        var historical = historical();
        var target = target(historical);
        var mapping = Map.of(
                "activationIteration", "design.protocol.activationIteration",
                "innovationOffIteration", "design.protocol.firstIteration",
                "innovationReopenIteration", "design.protocol.activationIteration",
                "innovationFinalOffIteration", "design.protocol.innovationOffIteration",
                "finalIteration", "design.protocol.finalIteration",
                "reportFirstIteration", "design.protocol.reportFirstIteration",
                "reportLastIteration", "design.protocol.reportLastIteration");
        for (var entry : mapping.entrySet()) {
            int prior = (Integer) value(historical.protocol(), entry.getKey());
            int replacement = entry.getKey().equals("reportLastIteration") ? prior - 1 : prior + 1;
            var protocol = change(historical.protocol(), entry.getKey(), replacement);
            assertDifference(compareHistoricalMixed(change(historical, "protocol", protocol), target),
                    "protocol." + entry.getKey() + " -> " + entry.getValue());
        }
        var shiftedStart = change(historical.protocol(), Map.of("firstIteration", 1, "innovationOffIteration", 1));
        var report = compareHistoricalMixed(change(historical, "protocol", shiftedStart), target);
        assertEquals(Set.of("protocol.firstIteration -> design.protocol.firstIteration",
                "protocol.innovationOffIteration -> design.protocol.firstIteration"), differenceFields(report));
        assertFalse(compareHistoricalMixed(change(historical, "protocol", shiftedStart), target).declaredFieldsMatch());
    }

    @Test void bothHorizonsAreComparedRatherThanInferredFromIterationEndpoints() {
        var historical = historical();
        for (String field : List.of("behaviouralHorizonSeconds", "executionHorizonSeconds")) {
            double prior = (Double) value(historical.protocol(), field);
            var protocol = change(historical.protocol(), field, prior + 1);
            assertDifference(compareHistoricalMixed(change(historical, "protocol", protocol), target(historical)),
                    "protocol." + field + " -> runtime." + field);
        }
    }

    @Test void legacyTimetableOnlyControlAndDedicatedCannotBeHistoricalMixed() {
        var historical = historical();
        for (var arm : List.of(ExperimentSpec.Arm.TIMETABLE_ONLY, ExperimentSpec.Arm.CONTROL, ExperimentSpec.Arm.DEDICATED)) {
            boolean freight = arm == ExperimentSpec.Arm.DEDICATED;
            var policies = change(historical.policies(), Map.of("access", freight ? ExperimentSpec.Access.DEDICATED_ONLY : ExperimentSpec.Access.NO_FREIGHT,
                    "dedicatedLineId", arm == ExperimentSpec.Arm.CONTROL ? "" : "dedicated-fixture"));
            var demand = change(historical.freightDemand(), "orderCount", freight ? 1 : 0);
            var other = change(historical, Map.of("arm", arm, "policies", policies, "freightDemand", demand));
            assertThrows(IllegalArgumentException.class, () -> compareHistoricalMixed(other, target(historical)));
        }
    }

    @Test void targetMixedCannotBeRelabelledAsTheNewTreatmentByPairing() {
        var historical = historical();
        var target = target(historical);
        var design = new DynamicMixedDesign(DynamicMixedDesign.Arm.MIXED, target.design().protocol(), false, target.design().limits());
        var mixed = change(target, "design", design);
        assertThrows(IllegalArgumentException.class, () -> compareHistoricalMixed(historical, mixed));
    }

    @Test void offAndZeroTargetsKeepDynamicIdentityWithoutCertifyingRegressionResults() {
        var historical = historical();
        var target = target(historical);
        for (var design : List.of(new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED, target.design().protocol(), false, target.design().limits()),
                new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED, target.design().protocol(), true, new DispatchCommitmentState.Limits(0, 0, 3)))) {
            var report = compareHistoricalMixed(historical, change(target, "design", design));
            assertTrue(report.declaredFieldsMatch());
            assertEquals("DYNAMIC_MIXED", report.targetIdentity().arm());
            assertEquals(design, report.targetDesign());
            assertTrue(report.unverifiedAspects().contains(DynamicMixedDeclaredPairing.UnverifiedAspect.SCIENTIFIC_OUTPUT_SCHEMA_AND_RESULT_VALIDITY));
        }
    }

    @Test void controlIsExplicitNewTreatmentAndCannotMaskAChangedCommonPolicy() {
        var historical = historical();
        var target = target(historical);
        var control = change(target.control(), "policyId", "another-declared-treatment");
        var changed = change(target, "control", control);
        var report = compareHistoricalMixed(historical, changed);
        assertTrue(report.declaredFieldsMatch());
        assertEquals(control, report.targetControlRegistration());
        var wrongPolicy = change(changed, "policies", change(changed.policies(), "betaFreight", 0.43));
        assertDifference(compareHistoricalMixed(historical, wrongPolicy), "policies.betaFreight");
    }

    @Test void uninspectedResolvedRuntimeOutputAndProfileCannotBeCalledVerified() {
        var historical = historical();
        var target = target(historical);
        var runtime = change(target.runtime(), Map.of("qsimThreads", 1, "globalThreads", 1,
                "eventWorkerSelection", DynamicMixedExperimentSpec.EventWorkerSelection.EXPLICIT_SINGLE_WORKER,
                "resolvedConfiguration", definition("another-unread-config")));
        var output = change(target.output(), "eventsWriteInterval", 17);
        var report = compareHistoricalMixed(historical, change(target, Map.of("runtime", runtime, "output", output, "validationProfile", "another-declared-profile")));
        assertTrue(report.declaredFieldsMatch(), "Only the listed shared declarations were compared, not actual thread/config equivalence");
        assertTrue(report.unverifiedAspects().contains(DynamicMixedDeclaredPairing.UnverifiedAspect.FULL_RESOLVED_NON_DISPATCH_CONFIGURATION_INCLUDING_THREADS_EVENTS_SCORING_AND_STRATEGIES));
        assertTrue(report.unverifiedAspects().contains(DynamicMixedDeclaredPairing.UnverifiedAspect.SCIENTIFIC_OUTPUT_SCHEMA_AND_RESULT_VALIDITY));
        assertTrue(report.checkedFields().stream().noneMatch(field -> field.equals("runtime.qsimThreads")));
    }

    @Test void differencesAreAccumulatedWithoutStoppingAtTheFirstMismatch() {
        var historical = historical();
        var changed = change(historical, Map.of("blockId", "B2", "seeds", change(historical.seeds(), "passenger", 99L),
                "policies", change(historical.policies(), "betaPhysical", 0.31)));
        var report = compareHistoricalMixed(changed, target(historical));
        assertEquals(Set.of("blockId", "seeds.passenger", "policies.betaPhysical"), differenceFields(report));
        assertEquals(47, report.checkedFields().size());
    }

    @Test void resultsAreImmutableAndDoNotChangeEitherDeclaration() {
        var historical = historical();
        var target = target(historical);
        var report = compareHistoricalMixed(historical, target);
        assertThrows(UnsupportedOperationException.class, () -> report.checkedFields().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.differences().add(new DynamicMixedDeclaredPairing.Difference("x", "a", "b")));
        assertThrows(UnsupportedOperationException.class, () -> report.inputPathDifferences().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.unverifiedAspects().clear());
        assertEquals(historical(), historical);
        assertEquals(target(historical), target);
        assertEquals(report.checkedFields(), compareHistoricalMixed(historical, target).checkedFields());
    }

    @Test void nullDeclarationsAreRejectedBeforeComparison() {
        assertThrows(NullPointerException.class, () -> compareHistoricalMixed(null, target(historical())));
        assertThrows(NullPointerException.class, () -> compareHistoricalMixed(historical(), null));
    }

    private static void assertDifference(DynamicMixedDeclaredPairing.Report report, String field) {
        assertFalse(report.declaredFieldsMatch());
        assertEquals(List.of(field), report.differences().stream().map(DynamicMixedDeclaredPairing.Difference::field).toList());
    }

    private static Set<String> differenceFields(DynamicMixedDeclaredPairing.Report report) {
        return report.differences().stream().map(DynamicMixedDeclaredPairing.Difference::field).collect(Collectors.toSet());
    }

    private static ExperimentSpec historical() {
        var inputs = new ExperimentSpec.Inputs(ref("config"), ref("network"), ref("population"), ref("memory"), ref("attributes"),
                ref("baseline-schedule"), ref("baseline-vehicles"), ref("activated-schedule"), ref("activated-vehicles"), ref("road-vehicles"), ref("pool"), ref("orders"));
        return new ExperimentSpec(ExperimentSpec.SCHEMA, "historical-fixture", "B1", ExperimentSpec.Arm.MIXED,
                new ExperimentSpec.Seeds(10, 20, 30), inputs,
                new ExperimentSpec.Protocol(0, 20, 0, 20, 150, 199, 170, 199, 108000, 129600),
                new ExperimentSpec.FreightDemand(1, 0.3, 48600, 59400, "fixture-delivery", "fixture-shadow"),
                new ExperimentSpec.Policies(ExperimentSpec.Access.SHARED_PASSENGER_SERVICE, "", List.of("pt", "freight_pt"),
                        0.2, 0.8, 0.9, 0, true, 0.6, 0.2, 0.4, 0.5, 0.6, 0.7, 0.8),
                new ExperimentSpec.OutputOptions("ismt-output-v1", ExperimentSpec.ExposureOutput.TERMINAL, false, 1, 1), "legacy-fixture-validation");
    }

    private static DynamicMixedExperimentSpec target(ExperimentSpec historical) {
        var design = new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED,
                new DynamicMixedDesign.Protocol(0, 20, 150, 199, 170, 199), true, new DispatchCommitmentState.Limits(11, 13, 3));
        var runtime = new DynamicMixedExperimentSpec.RuntimeDeclaration(7, 7, DynamicMixedExperimentSpec.EventMode.NATIVE_SIM_STEP_PARALLEL,
                DynamicMixedExperimentSpec.EventWorkerSelection.DEFAULT_SINGLE_WORKER, 1, true, false, 2, 1, 0,
                QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime, QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime,
                108000, 129600, definition("resolved-configuration"));
        var control = new DynamicMixedExperimentSpec.ControlRegistration("fixture-policy",
                DynamicMixedExperimentSpec.InformationModel.BASELINE_PRETRIP_NO_ADDED_INTRADAY_REROUTING,
                DynamicMixedExperimentSpec.ResetMode.FRESH_ORIGINAL_BASELINE_EACH_ITERATION,
                DynamicMixedExperimentSpec.BaselineTreatment.NO_PRE_SERVICE_REPAIR, DynamicMixedExperimentSpec.OperatingBasis.SYNTHETIC_ASSUMPTIONS,
                new DynamicMixedExperimentSpec.DecisionTiming(0, 23, 100, 7), definition("policy"), definition("constraints"),
                definition("forecast"), definition("protection"), definition("information"), definition("baseline-treatment"));
        return new DynamicMixedExperimentSpec(DynamicMixedExperimentSpec.SCHEMA, "new-fixture", historical.blockId(), design,
                historical.seeds(), historical.inputs(), historical.freightDemand(), historical.policies(), runtime, control,
                new DynamicMixedExperimentSpec.OutputOptions(ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA,
                        ScientificRunContext.ExposureOutput.TERMINAL, false, 1, 1, "fixture-dispatch-evidence-v1"), "new-fixture-validation");
    }

    private static ExperimentSpec.FileRef ref(String role) {
        return new ExperimentSpec.FileRef("nonexistent-declared-only/" + role + ".dat", ORIGINAL_SHA);
    }

    private static DynamicMixedExperimentSpec.RegisteredDefinition definition(String role) {
        return new DynamicMixedExperimentSpec.RegisteredDefinition("synthetic-definition-v1", ref(role));
    }

    private static Object value(Record source, String field) {
        try { return source.getClass().getMethod(field).invoke(source); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static <T extends Record> T change(T source, String field, Object value) {
        return change(source, Map.of(field, value));
    }

    /** Calls the real public canonical constructor, including all unchanged validation. */
    @SuppressWarnings("unchecked")
    private static <T extends Record> T change(T source, Map<String, ?> replacements) {
        var components = source.getClass().getRecordComponents();
        var remaining = new LinkedHashMap<String, Object>(replacements);
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            var component = components[index];
            types[index] = component.getType();
            values[index] = remaining.containsKey(component.getName()) ? remaining.remove(component.getName()) : value(source, component.getName());
        }
        assertTrue(remaining.isEmpty(), "Unknown fixture record fields: " + remaining.keySet());
        try { return (T) source.getClass().getConstructor(types).newInstance(values); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
