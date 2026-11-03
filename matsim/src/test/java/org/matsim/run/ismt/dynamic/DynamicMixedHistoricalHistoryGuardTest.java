package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.controler.ControllerListenerManagerImpl;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.ControllerListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ExperimentSpecIO;
import org.matsim.run.ismt.IsmtRunArtifactWriter;
import org.matsim.run.ismt.ScientificRunContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Synthetic files and listener dispatch only; never loads a scenario or runs QSim. */
class DynamicMixedHistoricalHistoryGuardTest {
    @TempDir Path temp;
    private int fixtureSequence;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENT = "passenger_event_fingerprint.csv", PLAN = "passenger_plan_fingerprint.csv", SUMMARY = "iteration_summary.csv";
    private static final List<String> EVENT_HEADER = List.of("experiment_id", "block_id", "arm", "iteration", "event_count", "event_sha256", "schema_version");
    private static final List<String> PLAN_HEADER = List.of("schema_version", "experiment_id", "block_id", "arm", "iteration", "passenger_count", "plan_sha256");

    @Test void completeTwentyIterationHistoryRetainsDistinctNamespacesAndFalseMatrixStatus() throws Exception {
        var f = fixture(); var guard = f.load();
        var initial = guard.report();
        assertFalse(initial.activationHistoryCheckPassed());
        assertFalse(initial.historicalMatrixValidated());
        assertEquals("VALIDATED", initial.historicalReceiptStatus());
        assertEquals("legacy-toy", initial.declaredPairing().historicalIdentity().experimentId());
        assertEquals("toy-v2", initial.declaredPairing().targetIdentity().experimentId());
        assertEquals(5, initial.historicalFiles().all().size());
        complete(f, guard, 20);
        assertFalse(guard.report().activationHistoryCheckPassed());
        guard.beforeIteration(20);
        assertTrue(guard.report().activationHistoryCheckPassed());
        assertFalse(guard.report().poisoned());
        assertEquals(20, guard.report().checkedIterations().size());
        assertEquals(3, guard.report().checkedIterations().getLast().currentFileSha256().size());
        assertTrue(initial.checkedIterations().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> guard.report().checkedIterations().clear());
        assertFalse(guard.report().stillUnverified().isEmpty());
        guard.verifyCompletedIteration(20, f.output);
        guard.beforeIteration(21);
        assertEquals(20, guard.report().checkedIterations().size());
    }

    @Test void reportSerializesAllBoundEvidenceWithoutEmptyBeansAndRetainsNarrowPairingScope() throws Exception {
        var f = fixture(); var guard = f.load(); complete(f, guard, 20); guard.beforeIteration(20);
        var tree = JSON.readTree(JSON.writeValueAsBytes(guard.report()));
        assertEquals(DynamicMixedHistoricalHistoryGuard.SCOPE, tree.path("scope").textValue());
        assertEquals("VALIDATED", tree.path("historicalReceiptStatus").textValue());
        assertFalse(tree.path("historicalMatrixValidated").booleanValue());
        assertTrue(tree.path("activationHistoryCheckPassed").booleanValue());
        assertFalse(tree.path("poisoned").booleanValue());
        assertEquals("", tree.path("failure").textValue());
        assertEquals(5, tree.path("historicalFiles").size());
        assertEquals(f.binding().receipt().sha256(), tree.path("historicalFiles").path("receipt").path("sha256").textValue());
        assertEquals(20, tree.path("checkedIterations").size());
        assertEquals(19, tree.path("checkedIterations").get(19).path("iteration").intValue());
        assertEquals(3, tree.path("checkedIterations").get(19).path("currentFileSha256").size());
        var pairing = tree.path("declaredPairing");
        assertEquals("DECLARED_COMMON_FIELDS_ONLY", pairing.path("scope").textValue());
        assertTrue(pairing.path("declaredFieldsMatch").booleanValue());
        assertEquals("legacy-toy", pairing.path("historicalIdentity").path("experimentId").textValue());
        assertEquals("toy-v2", pairing.path("targetIdentity").path("experimentId").textValue());
        assertEquals(47, pairing.path("checkedFields").size());
        assertEquals(DynamicMixedDeclaredPairing.UnverifiedAspect.values().length, pairing.path("unverifiedAspects").size());
        assertTrue(pairing.path("differences").isArray() && pairing.path("differences").isEmpty());
        assertTrue(pairing.path("targetControlRegistration").isObject());
        assertFalse(tree.path("stillUnverified").isEmpty());
        // Round-trip through the explicit record type also preserves every field.
        assertEquals(guard.report(), JSON.treeToValue(tree, DynamicMixedHistoricalHistoryGuard.Report.class));
    }

    @Test void everySummaryScientificColumnIsComparedExactlyWithoutMissingToZeroOrTolerance() throws Exception {
        Set<String> metadata = Set.of("schema_version", "experiment_id", "block_id", "arm");
        int tested = 0;
        for (String column : IsmtRunArtifactWriter.SUMMARY_COLUMNS) {
            if (metadata.contains(column)) continue;
            var f = fixture(); var guard = f.load(); guard.beforeIteration(0); f.current(1);
            f.changeCurrent(SUMMARY, 0, column, "DIFFERENT");
            assertThrows(IllegalStateException.class, () -> guard.verifyCompletedIteration(0, f.output), column);
            assertTrue(guard.report().poisoned());
            assertTrue(guard.report().checkedIterations().isEmpty());
            tested++;
        }
        assertEquals(IsmtRunArtifactWriter.SUMMARY_COLUMNS.size() - 4, tested);
    }

    @Test void bothFingerprintCountsAndDigestsAreCheckedNotJustOneKindOfHistory() throws Exception {
        for (var entry : Map.of(EVENT, List.of("event_count", "event_sha256"), PLAN, List.of("passenger_count", "plan_sha256")).entrySet()) {
            for (String column : entry.getValue()) {
                var f = fixture(); var guard = f.load(); guard.beforeIteration(0); f.current(1);
                f.changeCurrent(entry.getKey(), 0, column, column.endsWith("sha256") ? "c".repeat(64) : "9");
                assertThrows(IllegalStateException.class, () -> guard.verifyCompletedIteration(0, f.output));
            }
        }
    }

    @Test void metadataSchemasAndDesignFamilyMustMatchTheirOwnExplicitEnvelopes() throws Exception {
        for (String name : List.of(EVENT, PLAN, SUMMARY)) {
            var columns = new ArrayList<>(List.of("experiment_id", "block_id", "arm", "schema_version"));
            if (!name.equals(SUMMARY)) columns.add("design_family");
            for (String column : columns) {
                var f = fixture(); var guard = f.load(); guard.beforeIteration(0); f.current(1);
                f.changeCurrent(name, 0, column, "wrong-envelope");
                assertThrows(IllegalStateException.class, () -> guard.verifyCompletedIteration(0, f.output));
            }
        }
    }

    @Test void reorderedMissingDuplicateExtraOrUnknownCsvStructureFails() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            var f = fixture(); var guard = f.load(); guard.beforeIteration(0); f.current(1);
            Path file = f.output.resolve(EVENT); var lines = new ArrayList<>(Files.readAllLines(file));
            switch (mode) {
                case 0 -> lines.set(0, lines.getFirst().replace("event_count,event_sha256", "event_sha256,event_count"));
                case 1 -> lines.removeLast();
                case 2 -> lines.add(lines.getLast());
                case 3 -> lines.set(0, lines.getFirst() + ",unknown");
                case 4 -> lines.set(0, lines.getFirst().replace("event_count", "event_sha256"));
                case 5 -> lines.add("");
                default -> throw new AssertionError();
            }
            Files.write(file, lines);
            assertThrows(IllegalStateException.class, () -> guard.verifyCompletedIteration(0, f.output), "mode " + mode);
        }
    }

    @Test void currentEarlierRowsCannotBeRewrittenAfterTheirInitialCheck() throws Exception {
        var f = fixture(); var guard = f.load(); complete(f, guard, 1);
        guard.beforeIteration(1); f.current(2); f.changeCurrent(PLAN, 0, "plan_sha256", "d".repeat(64));
        assertThrows(IllegalStateException.class, () -> guard.verifyCompletedIteration(1, f.output));
        assertEquals(1, guard.report().checkedIterations().size());
    }

    @Test void currentRowOrderIsNeverSortedIntoAgreement() throws Exception {
        var f = fixture(); var guard = f.load(); complete(f, guard, 1); guard.beforeIteration(1); f.current(2);
        Path file = f.output.resolve(PLAN); var lines = new ArrayList<>(Files.readAllLines(file));
        String first = lines.get(1); lines.set(1, lines.get(2)); lines.set(2, first); Files.write(file, lines);
        assertThrows(IllegalStateException.class, () -> guard.verifyCompletedIteration(1, f.output));
    }

    @Test void skippedStartsUnverifiedEndsAndRepeatedEndsPoisonWithoutPartialChecks() throws Exception {
        var f = fixture(); var g = f.load();
        assertThrows(IllegalStateException.class, () -> g.beforeIteration(20));
        assertThrows(IllegalStateException.class, () -> g.beforeIteration(0));
        var g2 = f.load(); g2.beforeIteration(0);
        assertThrows(IllegalStateException.class, () -> g2.beforeIteration(1));
        var g3 = f.load(); f.current(1);
        assertThrows(IllegalStateException.class, () -> g3.verifyCompletedIteration(0, f.output));
        var g4 = f.load(); complete(f, g4, 1);
        assertThrows(IllegalStateException.class, () -> g4.verifyCompletedIteration(0, f.output));
        assertEquals(1, g4.report().checkedIterations().size());
    }

    @Test void activationRehashesEveryBoundHistoricalFileAndRetainsTargetOutputOnFailure() throws Exception {
        for (int index = 0; index < 5; index++) {
            var f = fixture(); var guard = f.load(); complete(f, guard, 20);
            Path historical = f.binding().all().get(index).resolve(f.root);
            Files.writeString(historical, Files.readString(historical) + " ");
            assertThrows(IllegalStateException.class, () -> guard.beforeIteration(20));
            assertFalse(guard.report().activationHistoryCheckPassed());
            assertTrue(Files.exists(f.output.resolve(SUMMARY)));
            assertEquals(20, guard.report().checkedIterations().size());
        }
    }

    @Test void activationRechecksCurrentPrefixAfterEndNineteen() throws Exception {
        var f = fixture(); var guard = f.load(); complete(f, guard, 20);
        f.changeCurrent(SUMMARY, 19, "selected_plan_passenger_score", "1.0000000001");
        assertThrows(IllegalStateException.class, () -> guard.beforeIteration(20));
    }

    @Test void eachHistoricalHashIsExternallyBoundEvenReceiptItself() throws Exception {
        for (int index = 0; index < 5; index++) {
            var f = fixture(); var refs = new ArrayList<>(f.binding().all());
            var old = refs.get(index); refs.set(index, new ExperimentSpec.FileRef(old.path(), "0".repeat(64)));
            var wrong = new DynamicMixedHistoricalHistoryGuard.HistoricalFiles(refs.get(0), refs.get(1), refs.get(2), refs.get(3), refs.get(4));
            assertThrows(IllegalArgumentException.class, () -> DynamicMixedHistoricalHistoryGuard.load(f.root, f.target, wrong));
        }
    }

    @Test void receiptStatusIdentityActivationValidationAndTypeCoercionsAreRejected() throws Exception {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("status", "COMPLETED"); changes.put("formalRun", false); changes.put("experiment_id", "other");
        changes.put("block_id", "B2"); changes.put("arm", "TIMETABLE_ONLY"); changes.put("spec_sha256", "b".repeat(64));
        changes.put("run_id", "wrong"); changes.put("final_iteration_reached", 19); changes.put("activation_count", 0);
        changes.put("activation_iteration", 21); changes.put("output_schema_version", "unknown"); changes.put("matrix_validated", "false");
        for (var change : changes.entrySet()) {
            var f = fixture(); f.receipt.set(change.getKey(), JSON.valueToTree(change.getValue())); f.writeReceipt();
            assertThrows(IllegalArgumentException.class, f::load, change.getKey());
        }
        var f = fixture(); ((ObjectNode) f.receipt.get("validation")).put("valid", false); f.writeReceipt();
        assertThrows(IllegalArgumentException.class, f::load);
    }

    @Test void receiptOutputHashesSizesAndPreActivationStampsMustBindActualArtifacts() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            var f = fixture();
            var output = (ObjectNode) f.receipt.path("output_files").path("matsim/" + PLAN);
            switch (mode) {
                case 0 -> output.put("sha256", "b".repeat(64));
                case 1 -> output.put("bytes", 1);
                case 2 -> ((ObjectNode) f.receipt.path("pre_activation_fingerprint").path("0")).put("sha256", "b".repeat(64));
                case 3 -> ((ObjectNode) f.receipt.get("pre_activation_fingerprint")).remove("19");
                default -> throw new AssertionError();
            }
            f.writeReceipt(); assertThrows(IllegalArgumentException.class, f::load);
        }
    }

    @Test void receiptMalformedDuplicateOrTrailingJsonIsRejectedDespiteFreshExternalHash() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            var f = fixture(); String json = Files.readString(f.run.resolve("run_receipt.json"));
            Files.writeString(f.run.resolve("run_receipt.json"), mode == 0 ? json + " {}" : mode == 1 ? json.replaceFirst("\\{", "{\"status\":\"VALIDATED\",") : "null");
            assertThrows(Exception.class, f::load);
        }
    }

    @Test void specRawHashIsNotConfusedWithItsLegacyCanonicalIdentity() throws Exception {
        var f = fixture(); byte[] pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(f.legacy);
        assertNotEquals(ExperimentSpecIO.sha256(f.legacy), ExperimentSpecIO.sha256(pretty));
        Files.write(f.run.resolve("experiment_spec.json"), pretty); f.refreshOutputBindings(); f.writeReceipt();
        assertDoesNotThrow(f::load);
    }

    @Test void declaredPairingChangesAndCompressedProbeProtocolAreNotFormalHistory() throws Exception {
        var f = fixture();
        var changed = replace(f.target, "seeds", new ExperimentSpec.Seeds(2, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedHistoricalHistoryGuard.load(f.root, changed, f.binding()));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedHistoricalHistoryGuard.load(f.root, DynamicMixedSpecFixtures.spec(), f.binding()));
    }

    @Test void historicalArtifactCannotBeSubstitutedFromAnotherRunEvenWithSameBytes() throws Exception {
        var f = fixture(); Path other = f.root.resolve("other.csv"); Files.copy(f.oldOutput.resolve(PLAN), other);
        var refs = f.binding(); var substituted = new DynamicMixedHistoricalHistoryGuard.HistoricalFiles(refs.spec(), refs.receipt(), refs.eventFingerprint(), f.ref(other), refs.summary());
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedHistoricalHistoryGuard.load(f.root, f.target, substituted));
    }

    @Test void parentTraversalAndCurrentOutputRebindingAreRejected() throws Exception {
        var f = fixture(); var refs = f.binding();
        var traversal = new ExperimentSpec.FileRef("run/../run/experiment_spec.json", refs.spec().sha256());
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedHistoricalHistoryGuard.load(f.root, f.target,
                new DynamicMixedHistoricalHistoryGuard.HistoricalFiles(traversal, refs.receipt(), refs.eventFingerprint(), refs.planFingerprint(), refs.summary())));
        var guard = f.load(); complete(f, guard, 1); guard.beforeIteration(1); f.current(2);
        assertThrows(IllegalStateException.class, () -> guard.verifyCompletedIteration(1, f.root.resolve("another")));
    }

    @Test void actualListenerManagerOrderWritesThenChecksAndStopsBeforePhaseTwenty() throws Exception {
        var f = fixture(); var guard = f.load(); guard.module(f.output);
        var manager = new ControllerListenerManagerImpl();
        for (Class<?> type : DynamicMixedHistoricalHistoryGuard.class.getDeclaredClasses()) {
            if (!type.getSimpleName().equals("StartListener") && !type.getSimpleName().equals("EndListener")) continue;
            var constructor = type.getDeclaredConstructor(DynamicMixedHistoricalHistoryGuard.class);
            constructor.setAccessible(true);
            manager.addControllerListener((ControllerListener) constructor.newInstance(guard));
        }
        AtomicInteger phaseTwenty = new AtomicInteger(), completions = new AtomicInteger();
        manager.addControllerListener(new IterationStartsListener() {
            @Override public double priority() { return 1000; }
            @Override public void notifyIterationStarts(IterationStartsEvent event) { if (event.getIteration() == 20) phaseTwenty.incrementAndGet(); }
        });
        manager.addControllerListener(new IterationEndsListener() {
            @Override public double priority() { return -1000; }
            @Override public void notifyIterationEnds(IterationEndsEvent event) {
                try { f.current(event.getIteration() + 1); } catch (Exception ex) { throw new IllegalStateException(ex); }
            }
        });
        manager.addControllerListener(new IterationEndsListener() {
            @Override public double priority() { return -2000; }
            @Override public void notifyIterationEnds(IterationEndsEvent event) {
                assertEquals(event.getIteration() + 1, guard.report().checkedIterations().size()); completions.incrementAndGet();
            }
        });
        for (int i = 0; i < 20; i++) {
            manager.fireControllerIterationStartsEvent(i, false); manager.fireControllerIterationEndsEvent(i, false);
        }
        assertEquals(20, completions.get());
        Files.writeString(f.run.resolve("run_receipt.json"), "changed after 19");
        assertThrows(IllegalStateException.class, () -> manager.fireControllerIterationStartsEvent(20, false));
        assertEquals(0, phaseTwenty.get());
        assertFalse(guard.report().activationHistoryCheckPassed());
    }

    private static void complete(Fixture f, DynamicMixedHistoricalHistoryGuard guard, int count) throws Exception {
        for (int i = 0; i < count; i++) { guard.beforeIteration(i); f.current(i + 1); guard.verifyCompletedIteration(i, f.output); }
    }

    private Fixture fixture() throws Exception { return new Fixture(temp.resolve("fixture-" + fixtureSequence++)); }

    private static final class Fixture {
        final Path root, run, oldOutput, output;
        final DynamicMixedExperimentSpec target;
        final ExperimentSpec legacy;
        final ObjectNode receipt;

        Fixture(Path root) throws Exception {
            this.root = Files.createDirectories(root).toRealPath();
            run = Files.createDirectories(this.root.resolve("run")); oldOutput = Files.createDirectories(run.resolve("matsim"));
            output = Files.createDirectories(this.root.resolve("target/matsim"));
            var toy = DynamicMixedSpecFixtures.spec();
            target = replace(toy, "design", replace(toy.design(), "protocol", new DynamicMixedDesign.Protocol(0, 20, 150, 199, 170, 199)));
            legacy = new ExperimentSpec(ExperimentSpec.SCHEMA, "legacy-toy", target.blockId(), ExperimentSpec.Arm.MIXED,
                    target.seeds(), target.inputs(), new ExperimentSpec.Protocol(0, 20, 0, 20, 150, 199, 170, 199,
                    target.runtime().behaviouralHorizonSeconds(), target.runtime().executionHorizonSeconds()), target.freightDemand(), target.policies(),
                    new ExperimentSpec.OutputOptions(IsmtRunArtifactWriter.SCHEMA, ExperimentSpec.ExposureOutput.REPORTING_WINDOW, false, 0, 0), "synthetic-only");
            Files.write(run.resolve("experiment_spec.json"), ExperimentSpecIO.canonicalBytes(legacy));
            for (String name : List.of(EVENT, PLAN, SUMMARY)) table(oldOutput.resolve(name), name, 200, false);
            receipt = JSON.createObjectNode();
            receipt.put("schema_version", "ismt-run-v1").put("status", "VALIDATED").put("formalRun", true).put("matrix_validated", false);
            receipt.put("experiment_id", legacy.experimentId()).put("block_id", legacy.blockId()).put("arm", "MIXED");
            String sha = ExperimentSpecIO.sha256(legacy); receipt.put("spec_sha256", sha);
            receipt.put("run_id", legacy.experimentId() + "-B1-MIXED-" + sha.substring(0, 16));
            receipt.put("final_iteration_reached", 199).put("activation_count", 1).put("activation_iteration", 20).put("output_schema_version", IsmtRunArtifactWriter.SCHEMA);
            receipt.set("seeds", JSON.valueToTree(legacy.seeds()));
            var validation = receipt.putObject("validation"); validation.put("valid", true); validation.putArray("issues");
            validation.putObject("checks").put("iteration_summary", true).put("passenger_event_fingerprint", true);
            var pre = receipt.putObject("pre_activation_fingerprint");
            for (int i = 0; i < 20; i++) pre.putObject(Integer.toString(i)).put("iteration", i).put("eventCount", 7)
                    .put("sha256", "a".repeat(64)).put("schemaVersion", "ismt-passenger-event-fingerprint-v1");
            refreshOutputBindings(); writeReceipt();
        }

        void refreshOutputBindings() throws Exception {
            var outputs = receipt.putObject("output_files");
            for (String relative : List.of("experiment_spec.json", "matsim/" + EVENT, "matsim/" + PLAN, "matsim/" + SUMMARY)) {
                byte[] bytes = Files.readAllBytes(run.resolve(relative));
                outputs.putObject(relative).put("bytes", bytes.length).put("sha256", ExperimentSpecIO.sha256(bytes));
            }
        }
        void writeReceipt() throws Exception { Files.write(run.resolve("run_receipt.json"), JSON.writeValueAsBytes(receipt)); }
        ExperimentSpec.FileRef ref(Path file) throws Exception { return new ExperimentSpec.FileRef(root.relativize(file).toString(), ExperimentSpecIO.sha256(Files.readAllBytes(file))); }
        DynamicMixedHistoricalHistoryGuard.HistoricalFiles binding() throws Exception {
            return new DynamicMixedHistoricalHistoryGuard.HistoricalFiles(ref(run.resolve("experiment_spec.json")), ref(run.resolve("run_receipt.json")),
                    ref(oldOutput.resolve(EVENT)), ref(oldOutput.resolve(PLAN)), ref(oldOutput.resolve(SUMMARY)));
        }
        DynamicMixedHistoricalHistoryGuard load() throws Exception { return DynamicMixedHistoricalHistoryGuard.load(root, target, binding()); }
        void current(int rows) throws Exception { for (String name : List.of(EVENT, PLAN, SUMMARY)) table(output.resolve(name), name, rows, true); }
        void changeCurrent(String filename, int iteration, String column, String replacement) throws Exception {
            Path file = output.resolve(filename); var lines = new ArrayList<>(Files.readAllLines(file));
            var columns = List.of(lines.getFirst().split(",", -1));
            var values = new ArrayList<>(List.of(lines.get(iteration + 1).split(",", -1)));
            values.set(columns.indexOf(column), replacement); lines.set(iteration + 1, String.join(",", values)); Files.write(file, lines);
        }
        void table(Path file, String name, int count, boolean dynamic) throws Exception {
            var columns = new ArrayList<>(name.equals(EVENT) ? EVENT_HEADER : name.equals(PLAN) ? PLAN_HEADER : IsmtRunArtifactWriter.SUMMARY_COLUMNS);
            if (dynamic && !name.equals(SUMMARY)) columns.add("design_family");
            StringBuilder csv = new StringBuilder(String.join(",", columns)).append('\n');
            for (int i = 0; i < count; i++) {
                var row = new LinkedHashMap<String, String>(); for (String column : columns) row.put(column, "0");
                row.put("experiment_id", dynamic ? target.experimentId() : legacy.experimentId()); row.put("block_id", "B1");
                row.put("arm", dynamic ? "DYNAMIC_MIXED" : "MIXED"); row.put("iteration", Integer.toString(i)); row.put("design_family", DynamicMixedDesign.SCHEMA_ID);
                row.put("schema_version", name.equals(SUMMARY) ? (dynamic ? ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA : IsmtRunArtifactWriter.SCHEMA)
                        : name.equals(EVENT) ? "ismt-passenger-event-fingerprint-v1" : "ismt-passenger-plan-fingerprint-v1");
                row.put("event_count", "7"); row.put("event_sha256", "a".repeat(64)); row.put("plan_sha256", "b".repeat(64)); row.put("passenger_count", "7");
                row.put("phase_active", i < 20 ? "false" : "true"); row.put("phase_activation_count", i < 20 ? "0" : "1"); row.put("phase_activation_iteration", i < 20 ? "-1" : "20");
                row.put("mean_lateness_minutes", ""); row.put("mean_first_board_wait_minutes", ""); row.put("freight_order_count", "1");
                csv.append(String.join(",", columns.stream().map(row::get).toList())).append('\n');
            }
            Files.writeString(file, csv);
        }
    }
}
