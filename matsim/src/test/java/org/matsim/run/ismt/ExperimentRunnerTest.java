package org.matsim.run.ismt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

class ExperimentRunnerTest {
    @TempDir Path temp;

    private static Path profiles() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve("experiments/ismt_q720");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new AssertionError("Missing profiles");
    }
    @Test void completeMatrixIsRequiredBeforeAnyLaunch() throws IOException {
        var specs = new ArrayList<ExperimentSpec>();
        for (String block : new String[]{"B1", "B2", "B3"}) for (var arm : ExperimentSpec.Arm.values())
            specs.add(ExperimentSpecIO.compose(profiles(), block, arm));
        assertDoesNotThrow(() -> ExperimentRunner.validateMatrixSpecifications(specs));
        var one = specs.removeLast();
        assertThrows(IllegalArgumentException.class, () -> ExperimentRunner.validateMatrixSpecifications(specs));
        specs.add(one); specs.add(one);
        assertThrows(IllegalArgumentException.class, () -> ExperimentRunner.validateMatrixSpecifications(specs));
    }

    @Test void outputTraversalAndSymlinkEscapeFailBeforeCreation() throws IOException {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        assertThrows(IOException.class, () -> ExperimentRunner.safeOutputRoot(workspace, workspace));
        assertThrows(IOException.class, () -> ExperimentRunner.safeOutputRoot(workspace, Path.of("../outside/new")));
        Files.createSymbolicLink(workspace.resolve("escape"), outside);
        assertThrows(IOException.class, () -> ExperimentRunner.safeOutputRoot(workspace, Path.of("escape/new")));
        assertFalse(Files.exists(outside.resolve("new")));
        assertEquals(workspace.toRealPath().resolve("output/new"), ExperimentRunner.safeOutputRoot(workspace, Path.of("output/new")));
    }

    @Test void inventoryExcludesReceiptCycleAndDetectsTamperedOutput() throws IOException {
        Path run = Files.createDirectory(temp.resolve("run"));
        Files.writeString(run.resolve("data.csv"), "a,b\n1,2\n");
        Files.writeString(run.resolve("run_receipt.json"), "{}");
        var original = ExperimentRunner.inventory(run);
        assertEquals(java.util.Set.of("data.csv"), original.keySet());
        Files.writeString(run.resolve("data.csv"), "a,b\n1,3\n");
        assertNotEquals(original, ExperimentRunner.inventory(run));
    }

    @Test void inWorkspaceSymlinkCannotRedirectAppendedRunPathIntoSource() throws IOException {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path source = Files.createDirectories(workspace.resolve("frozen/source"));
        Files.createDirectories(workspace.resolve("outputs"));
        Files.createSymbolicLink(workspace.resolve("outputs/formal"), source);
        assertThrows(IOException.class, () -> ExperimentRunner.safeOutputRoot(workspace,
                Path.of("outputs/formal/experiment/hash/B1/control/spec")));
        assertFalse(Files.exists(source.resolve("experiment")));
    }

    @Test void matchedPoolCannotHideDifferentExecutionOrders() throws IOException {
        var specs = block();
        var changed = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(specs.get(3));
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.get("inputs").get("executionOrders"))
                .put("sha256", "a".repeat(64));
        specs.set(3, modified(changed));
        assertThrows(IllegalArgumentException.class, () -> ExperimentRunner.validateMatrixSpecifications(specs));
    }

    @Test void commonHistoryCannotHideDifferentPostActivationPolicy() throws IOException {
        var specs = block();
        var changed = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(specs.get(1));
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.get("policies")).put("betaFreight", 0.0);
        specs.set(1, modified(changed));
        assertThrows(IllegalArgumentException.class, () -> ExperimentRunner.validateMatrixSpecifications(specs));
    }

    @Test void matchedSupplyCannotHideDifferentDedicatedLineIdentity() throws IOException {
        var specs = block();
        var changed = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(specs.get(3));
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.get("policies")).put("dedicatedLineId", "another_line");
        specs.set(3, modified(changed));
        assertThrows(IllegalArgumentException.class, () -> ExperimentRunner.validateMatrixSpecifications(specs));
    }

    @Test void completedResumeRevalidatesWithoutRewritingReceiptOrArtifacts() throws Exception {
        var fixture = resumeFixture("complete");
        var calls = new AtomicInteger();
        var expected = validation(true);
        var runner = new ExperimentRunner((root, spec) -> {
            calls.incrementAndGet();
            assertEquals(fixture.root(), root);
            assertEquals(fixture.spec(), spec);
            return expected;
        });
        var before = ExperimentRunner.inventory(fixture.root());
        byte[] receiptBefore = Files.readAllBytes(fixture.root().resolve("run_receipt.json"));
        var result = invokeResume(runner, fixture);
        assertTrue(result.resumed());
        assertTrue(result.formalRun());
        assertEquals("resume-test", result.runId());
        assertEquals(expected, result.validation());
        assertEquals(1, calls.get());
        assertEquals(before, ExperimentRunner.inventory(fixture.root()));
        assertArrayEquals(receiptBefore, Files.readAllBytes(fixture.root().resolve("run_receipt.json")));
    }

    @Test void incompleteReceiptsCannotResumeOrReachScientificRevalidation() throws Exception {
        for (String status : List.of("STARTED", "FAILED", "VALIDATING", "")) {
            var fixture = resumeFixture("status-" + (status.isEmpty() ? "missing" : status));
            if (status.isEmpty()) fixture.receipt().remove("status");
            else fixture.receipt().put("status", status);
            writeResumeReceipt(fixture);
            byte[] receiptBefore = Files.readAllBytes(fixture.root().resolve("run_receipt.json"));
            var runner = new ExperimentRunner((root, spec) -> {
                fail("Incomplete receipt must be rejected before validator invocation");
                return validation(true);
            });
            var failure = assertThrows(IOException.class, () -> invokeResume(runner, fixture));
            assertTrue(failure.getMessage().contains("incomplete or differently identified"));
            assertArrayEquals(receiptBefore, Files.readAllBytes(fixture.root().resolve("run_receipt.json")));
        }
    }

    @Test void completedResumeRejectsEveryBoundIdentityMismatch() throws Exception {
        for (String field : List.of("formalRun", "spec_sha256", "source_sha256", "build_sha256", "dependency_sha256")) {
            var fixture = resumeFixture("identity-" + field);
            if (field.equals("formalRun")) fixture.receipt().put(field, false);
            else fixture.receipt().put(field, "e".repeat(64));
            writeResumeReceipt(fixture);
            var runner = new ExperimentRunner((root, spec) -> {
                fail("Identity mismatch must be rejected before validator invocation");
                return validation(true);
            });
            assertThrows(IOException.class, () -> invokeResume(runner, fixture));
        }
    }

    @Test void completedResumeRejectsChangedMissingAndAdditionalOutput() throws Exception {
        for (String mutation : List.of("changed", "missing", "additional")) {
            var fixture = resumeFixture("output-" + mutation);
            Path data = fixture.root().resolve("summary.csv");
            if (mutation.equals("changed")) Files.writeString(data, "iteration,value\n199,2\n");
            else if (mutation.equals("missing")) Files.move(data, temp.resolve("retained-summary.csv"));
            else Files.writeString(fixture.root().resolve("unexpected.csv"), "unexpected\n");
            var runner = new ExperimentRunner((root, spec) -> {
                fail("Changed inventory must be rejected before validator invocation");
                return validation(true);
            });
            var failure = assertThrows(IOException.class, () -> invokeResume(runner, fixture));
            assertTrue(failure.getMessage().contains("output inventory changed"));
        }
    }

    @Test void completedReceiptDoesNotBypassFreshScientificValidation() throws Exception {
        var fixture = resumeFixture("invalid-science");
        var calls = new AtomicInteger();
        byte[] receiptBefore = Files.readAllBytes(fixture.root().resolve("run_receipt.json"));
        var runner = new ExperimentRunner((root, spec) -> {
            calls.incrementAndGet();
            return validation(false);
        });
        var failure = assertThrows(IOException.class, () -> invokeResume(runner, fixture));
        assertTrue(failure.getMessage().contains("no longer validates"));
        assertEquals(1, calls.get());
        assertArrayEquals(receiptBefore, Files.readAllBytes(fixture.root().resolve("run_receipt.json")));
    }

    private record ResumeFixture(Path root, ExperimentSpec spec, String specHash,
                                 BuildIdentity build, ObjectNode receipt) { }

    private ResumeFixture resumeFixture(String name) throws IOException {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.writeString(root.resolve("summary.csv"), "iteration,value\n199,1\n");
        var spec = ExperimentSpecIO.compose(profiles(), "B1", ExperimentSpec.Arm.CONTROL);
        String specHash = ExperimentSpecIO.sha256(spec);
        var jar = new ExperimentSpec.FileRef("synthetic/application.jar", "b".repeat(64));
        var build = new BuildIdentity("ismt-build-v1", "synthetic/source", "a".repeat(40),
                "a".repeat(64), Map.of("source.java", "d".repeat(64)), jar, List.of(jar),
                "c".repeat(64), "2026.0", "synthetic", "synthetic", "synthetic", "synthetic",
                List.of("synthetic"), "synthetic", true);
        var json = new ObjectMapper();
        var receipt = json.createObjectNode();
        receipt.put("schema_version", "ismt-run-v1");
        receipt.put("run_id", "resume-test");
        receipt.put("status", "VALIDATED");
        receipt.put("formalRun", true);
        receipt.put("spec_sha256", specHash);
        receipt.put("source_sha256", build.sourceSha256());
        receipt.put("build_sha256", build.applicationJar().sha256());
        receipt.put("dependency_sha256", build.dependencySha256());
        receipt.set("output_files", json.valueToTree(ExperimentRunner.inventory(root)));
        var fixture = new ResumeFixture(root, spec, specHash, build, receipt);
        writeResumeReceipt(fixture);
        return fixture;
    }

    private static void writeResumeReceipt(ResumeFixture fixture) throws IOException {
        new ObjectMapper().writeValue(fixture.root().resolve("run_receipt.json").toFile(), fixture.receipt());
    }

    private static RunValidator.ValidationResult validation(boolean valid) {
        return new RunValidator.ValidationResult(valid, Map.of("synthetic-check", valid),
                valid ? List.of() : List.of("synthetic scientific validation failure"),
                199, 1, 20, Map.of(), Map.of());
    }

    /** Exercises only the private resume boundary; public build/runtime guards are not disabled. */
    private static ExperimentRunner.RunResult invokeResume(ExperimentRunner runner, ResumeFixture fixture) throws Exception {
        var method = ExperimentRunner.class.getDeclaredMethod("resume", Path.class, String.class,
                ExperimentSpec.class, String.class, BuildIdentity.class, boolean.class);
        method.setAccessible(true);
        try {
            return (ExperimentRunner.RunResult) method.invoke(runner, fixture.root(), "resume-test", fixture.spec(),
                    fixture.specHash(), fixture.build(), true);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private ArrayList<ExperimentSpec> block() throws IOException {
        var specs = new ArrayList<ExperimentSpec>();
        for (var arm : ExperimentSpec.Arm.values()) specs.add(ExperimentSpecIO.compose(profiles(), "B1", arm));
        return specs;
    }

    private ExperimentSpec modified(com.fasterxml.jackson.databind.JsonNode tree) throws IOException {
        Path path = temp.resolve("changed-spec.json");
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(path.toFile(), tree);
        return ExperimentSpecIO.read(path);
    }
}
