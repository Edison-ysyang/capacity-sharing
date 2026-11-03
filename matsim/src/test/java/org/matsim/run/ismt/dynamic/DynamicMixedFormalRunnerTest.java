package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.run.ismt.ExperimentSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Formal-entry rejection and receipt plumbing, not a claim that a formal Controller was run. */
class DynamicMixedFormalRunnerTest {
    @TempDir Path temporary;

    static DynamicMixedExperimentSpec formalSpec() {
        var spec = DynamicMixedSpecFixtures.spec();
        spec = replace(spec, "output", replace(spec.output(), "dispatchEvidenceSchemaVersion", DynamicMixedDispatchEvidence.SCHEMA));
        return replace(spec, "design", replace(spec.design(), "protocol", DynamicMixedFormalRunner.FORMAL_PROTOCOL));
    }

    private DynamicMixedFormalRunner.Options options() {
        return new DynamicMixedFormalRunner.Options(temporary, Path.of("outputs"), Path.of("missing-build.json"),
                new ExperimentSpec.FileRef("missing-compatibility.json", "a".repeat(64)));
    }

    @Test void onlyTheExplicitNewTBlockIsAcceptedAndOtherArmsFailBeforeAnyFileRead() {
        for (var arm : List.of(DynamicMixedDesign.Arm.CONTROL, DynamicMixedDesign.Arm.MIXED, DynamicMixedDesign.Arm.DEDICATED)) {
            var spec = DynamicMixedSpecFixtures.spec(arm);
            spec = replace(spec, "output", replace(spec.output(), "dispatchEvidenceSchemaVersion", DynamicMixedDispatchEvidence.SCHEMA));
            var candidate = replace(spec, "design", replace(spec.design(), "protocol", DynamicMixedFormalRunner.FORMAL_PROTOCOL));
            assertThrows(IllegalArgumentException.class, () -> new DynamicMixedFormalRunner().run(candidate, options()));
        }
        assertFalse(Files.exists(temporary.resolve("outputs")));
    }

    @Test void shortenedDevelopmentOrAlteredFormalWindowsCannotEnter() {
        for (var protocol : List.of(new DynamicMixedDesign.Protocol(0, 1, 2, 2, 2, 2),
                new DynamicMixedDesign.Protocol(0, 19, 150, 199, 170, 199),
                new DynamicMixedDesign.Protocol(0, 20, 149, 199, 170, 199),
                new DynamicMixedDesign.Protocol(0, 20, 150, 200, 170, 199),
                new DynamicMixedDesign.Protocol(0, 20, 150, 199, 171, 199),
                new DynamicMixedDesign.Protocol(0, 20, 150, 199, 170, 198))) {
            var base = formalSpec(); var spec = replace(base, "design", replace(base.design(), "protocol", protocol));
            var failure = assertThrows(IllegalArgumentException.class, () -> new DynamicMixedFormalRunner().run(spec, options()));
            assertTrue(failure.getMessage().contains("0--199"));
        }
        assertFalse(Files.exists(temporary.resolve("outputs")));
    }

    @Test void offAndZeroAmplitudeAreRegressionControlsNotFormalDynamicTResults() {
        var base = formalSpec();
        var off = replace(base, "design", replace(base.design(), "controlEnabled", false));
        var zero = replace(base, "design", replace(base.design(), "limits", new DispatchCommitmentState.Limits(0, 0, 5)));
        for (var spec : List.of(off, zero)) {
            assertDoesNotThrow(() -> DynamicMixedExperimentRunner.requireSupportedSpec(spec));
            assertThrows(IllegalArgumentException.class, () -> DynamicMixedFormalRunner.requireFormalSpec(spec));
        }
    }

    @Test void acceptedBlocksPreserveDeclaredControlThroughoutReportingButDoNotImplyValidatedInputs() {
        for (String block : List.of("B1", "B2", "B3")) {
            var spec = replace(formalSpec(), "blockId", block);
            assertDoesNotThrow(() -> DynamicMixedFormalRunner.requireFormalSpec(spec));
            assertFalse(spec.design().phaseAt(19).controlEligible());
            assertTrue(spec.design().phaseAt(20).controlEligible());
            for (int day = 170; day <= 199; day++) {
                assertTrue(spec.design().phaseAt(day).controlEligible());
                assertFalse(spec.design().phaseAt(day).passengerInnovationEnabled());
            }
            assertThrows(IOException.class, () -> new DynamicMixedFormalRunner().run(spec, options()));
        }
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedFormalRunner.requireFormalSpec(replace(formalSpec(), "blockId", "B4")));
        assertFalse(Files.exists(temporary.resolve("outputs")));
    }

    @Test void formalNamespaceIsSeparateFromDevelopmentAndBindsFullSourceAndSpecDigests() throws IOException {
        var spec = formalSpec(); String source = "a".repeat(64), digest = DynamicMixedExperimentSpecIO.sha256(spec);
        Path path = DynamicMixedFormalRunner.formalRunRoot(temporary, Path.of("outputs"), spec, source, digest);
        assertEquals(temporary.toRealPath().resolve("outputs/formal/toy-v2/" + source + "/B1/dynamic_mixed/" + digest), path);
        assertNotEquals(path, DynamicMixedExperimentRunner.developmentRunRoot(temporary, Path.of("outputs"), spec, source, digest));
        assertFalse(Files.exists(temporary.resolve("outputs")));
        for (String bad : List.of("invalid", "A".repeat(64), "../outside", "a".repeat(63)))
            assertThrows(IllegalArgumentException.class, () -> DynamicMixedFormalRunner.formalRunRoot(temporary, Path.of("outputs"), spec, bad, digest));
    }

    @Test void existingPartialOutputIsNeverResumedOrOverwritten() throws IOException {
        Path run = temporary.resolve("outputs/formal-run"); DynamicMixedFormalRunner.createNewRunDirectory(run);
        Path partial = run.resolve("partial.jsonl"); Files.writeString(partial, "unfinished evidence");
        assertThrows(IOException.class, () -> DynamicMixedFormalRunner.createNewRunDirectory(run));
        assertEquals("unfinished evidence", Files.readString(partial));
        assertFalse(Files.exists(run.resolve("run_receipt.json")));
    }

    @Test void formalOutputCannotEscapeWorkspaceOrUseAnAlias() throws IOException {
        var spec = formalSpec(); String hash = "a".repeat(64);
        for (Path bad : List.of(temporary, Path.of("a/../b"), temporary.getParent()))
            assertThrows(IOException.class, () -> DynamicMixedFormalRunner.formalRunRoot(temporary, bad, spec, hash, hash));
        Files.createDirectory(temporary.resolve("actual")); Files.createSymbolicLink(temporary.resolve("alias"), temporary.resolve("actual"));
        assertThrows(IOException.class, () -> DynamicMixedFormalRunner.formalRunRoot(temporary, Path.of("alias/out"), spec, hash, hash));
    }

    @Test void manifestInputCannotEscapeViaParentOrSymlinkAndCannotBeDirectory() throws IOException {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = temporary.resolve("outside.json"); Files.writeString(outside, "{}");
        Files.createSymbolicLink(workspace.resolve("alias.json"), outside);
        for (Path path : List.of(Path.of("../outside.json"), Path.of("alias.json"), outside, workspace))
            assertThrows(IOException.class, () -> DynamicMixedFormalRunner.containedFile(workspace.toRealPath(), path));
        Path inside = workspace.resolve("inside.json"); Files.writeString(inside, "{}");
        assertEquals(inside.toRealPath(), DynamicMixedFormalRunner.containedFile(workspace.toRealPath(), Path.of("inside.json")));
    }

    @Test void failureReceiptPreservesExactStagePartialEvidenceAndDoesNotDeclareCompletion() throws IOException {
        Path run = Files.createDirectory(temporary.resolve("run"));
        Files.writeString(run.resolve("partial.jsonl"), "partial remains");
        var receipt = new LinkedHashMap<String, Object>(Map.of("schema_version", DynamicMixedFormalRunner.RECEIPT_SCHEMA,
                "status", "STARTED", "stage", "CONTROLLER_RUNNING", "formalRun", true, "scopedChecksPassed", false));
        DynamicMixedFormalRunner.writeReceipt(run, receipt);
        var failure = new IOException("history differs at 7");
        DynamicMixedFormalRunner.recordFailure(run, receipt, failure);
        var json = new JsonMapper().readTree(run.resolve("run_receipt.json").toFile());
        assertEquals("FAILED", json.path("status").textValue());
        assertEquals("CONTROLLER_RUNNING", json.path("stage").textValue());
        assertFalse(json.path("scopedChecksPassed").booleanValue());
        assertTrue(json.path("failure").textValue().contains("history differs at 7"));
        assertTrue(json.path("output_files").has("partial.jsonl"));
        assertFalse(json.path("output_files").has("run_receipt.json"));
        assertEquals("partial remains", Files.readString(run.resolve("partial.jsonl")));
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test void failedReceiptWriteCannotReplaceOriginalFailureOrDiscardExistingTemporaryEvidence() throws IOException {
        Path run = Files.createDirectory(temporary.resolve("run"));
        Files.writeString(run.resolve(".run_receipt.tmp"), "incomplete receipt must remain");
        var failure = new IOException("original error");
        DynamicMixedFormalRunner.recordFailure(run, new LinkedHashMap<>(Map.of("stage", "CONFIGURATION_GATE")), failure);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("original error", failure.getMessage());
        assertEquals("incomplete receipt must remain", Files.readString(run.resolve(".run_receipt.tmp")));
    }

    @Test void finalReceiptFailureCannotLeaveAFailedRunLabelledComplete() throws IOException {
        Path run = Files.createDirectory(temporary.resolve("finalization"));
        var receipt = new LinkedHashMap<String, Object>(Map.of("stage", "COMPLETE", "status", DynamicMixedFormalRunner.COMPLETED,
                "scopedChecksPassed", true));
        DynamicMixedFormalRunner.recordFailure(run, receipt, new IOException("final atomic receipt move failed"));
        var saved = new JsonMapper().readTree(run.resolve("run_receipt.json").toFile());
        assertEquals("FAILED", saved.path("status").textValue());
        assertEquals("FINALIZE_RECEIPT", saved.path("stage").textValue());
        assertFalse(saved.path("scopedChecksPassed").booleanValue());
    }

    @Test void cliHasNoForceResumeMatrixOrDevelopmentMode() {
        for (String command : List.of("development", "resume", "matrix", "force"))
            assertThrows(IllegalArgumentException.class, () -> DynamicMixedFormalRunner.main(new String[]{command, "a", "b", "c", "d", "e", "f"}));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedFormalRunner.main(new String[]{"formal"}));
    }
}
