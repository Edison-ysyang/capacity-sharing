package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.run.ismt.BuildIdentity;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.ExperimentSpec;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One explicitly registered formal new-T block, using the same tested simulation
 * assembly as development. There is no matrix, force, resume, or legacy-T mode.
 * A clean build and external identity-bound compatibility review are prerequisites;
 * configuration, native prepared-day dispatch, historical common history and final
 * scientific artifacts are checked in their respective scopes. Completion is not
 * a machine proof of source behavioral equivalence, railway safety or convergence.
 */
public final class DynamicMixedFormalRunner {
    public static final String RECEIPT_SCHEMA = "ismt-dynamic-formal-run-v1";
    public static final String COMPLETED = "COMPLETED_SCOPED_VALIDATED";
    public static final DynamicMixedDesign.Protocol FORMAL_PROTOCOL =
            new DynamicMixedDesign.Protocol(0, 20, 150, 199, 170, 199);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final List<String> BOUNDARIES = List.of(
            "source-change-review-is-an-identity-bound-judgment-not-machine-behavioral-equivalence",
            "full-event-prefix-and-terminal-suffix-reconstruction",
            "native-capacity-mechanism-exercise-beyond-recorded-runtime-and-regression-evidence",
            "whole-run-atomic-filesystem-snapshot",
            "real-railway-signalling-block-occupancy-and-safety",
            "cross-arm-matrix-validation-and-causal-interpretation-beyond-registered-comparisons",
            "convergence-and-paper-conclusions-require-separate-result-analysis");

    public record Options(Path workspace, Path outputRoot, Path buildManifest,
                          ExperimentSpec.FileRef compatibilityEvidenceFileRef) {
        public Options {
            Objects.requireNonNull(workspace); Objects.requireNonNull(outputRoot);
            Objects.requireNonNull(buildManifest); Objects.requireNonNull(compatibilityEvidenceFileRef);
        }
    }

    public record RunResult(String runId, Path runRoot, String status,
                            int finalIterationReached, int completedIterations,
                            boolean formalRun, boolean scopedChecksPassed) { }

    /** The supplied spec is the only block that can be launched; never infer other arms. */
    public RunResult run(DynamicMixedExperimentSpec spec, Options options) throws IOException {
        requireFormalSpec(spec); Objects.requireNonNull(options);
        Path workspace = options.workspace().toRealPath();
        Path manifest = containedFile(workspace, options.buildManifest());
        String manifestSha = ExperimentConfiguration.sha256(manifest);
        BuildIdentity build = BuildIdentity.read(manifest);
        build.verifySource(workspace, false);
        build.verifyRuntime(workspace);
        String specSha = DynamicMixedExperimentSpecIO.sha256(spec);
        String runId = spec.experimentId() + "-" + spec.blockId() + "-DYNAMIC_MIXED-" + specSha.substring(0, 16);
        Path runRoot = formalRunRoot(workspace, options.outputRoot(), spec, build.sourceSha256(), specSha);
        createNewRunDirectory(runRoot);
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("schema_version", RECEIPT_SCHEMA); receipt.put("formalRun", true);
        receipt.put("experiment_id", spec.experimentId()); receipt.put("block_id", spec.blockId());
        receipt.put("arm", spec.design().arm()); receipt.put("design_family", DynamicMixedDesign.SCHEMA_ID);
        receipt.put("run_id", runId); receipt.put("spec_sha256", specSha); receipt.put("seeds", spec.seeds());
        receipt.put("source_sha256", build.sourceSha256()); receipt.put("sourceClean", true);
        receipt.put("build_sha256", build.applicationJar().sha256()); receipt.put("dependency_sha256", build.dependencySha256());
        receipt.put("build_manifest_path", workspace.relativize(manifest).toString());
        receipt.put("build_manifest_sha256", manifestSha);
        receipt.put("compatibility_evidence", options.compatibilityEvidenceFileRef());
        receipt.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        receipt.put("protocol", FORMAL_PROTOCOL); receipt.put("started_at", Instant.now().toString());
        receipt.put("status", "STARTED"); receipt.put("stage", "REGISTER_RUN");
        receipt.put("scopedChecksPassed", false); receipt.put("unverifiedBoundaries", BOUNDARIES);
        DynamicMixedExperimentRunner.PreparedSimulation simulation = null;
        DynamicMixedHistoricalHistoryGuard history = null;
        DynamicMixedDailyDispatchAudit daily = null;
        try {
            writeReceipt(runRoot, receipt);
            DynamicMixedExperimentSpecIO.writeNew(runRoot.resolve("experiment_spec.json"), spec);
            Files.createDirectory(runRoot.resolve("audit"));
            writeNewJson(runRoot.resolve("scenario_manifest.json"), Map.of(
                    "schema_version", "ismt-dynamic-formal-scenario-v1", "spec", spec, "spec_sha256", specSha,
                    "build", build, "simulation_directory", "matsim", "dispatch_directory", "dispatch",
                    "formalRun", true, "compatibility_evidence", options.compatibilityEvidenceFileRef()));
            stage(runRoot, receipt, "VERIFY_REGISTERED_INPUTS_AND_COMPATIBILITY");
            var inputBefore = DynamicMixedInputVerifier.verify(workspace, spec);
            receipt.put("input_verification", inputBefore);
            var compatibility = DynamicMixedFormalCompatibility.load(workspace, spec, manifest,
                    options.compatibilityEvidenceFileRef());
            writeNewJson(runRoot.resolve("audit/compatibility.json"), compatibility.report());
            history = DynamicMixedHistoricalHistoryGuard.load(workspace, spec, compatibility.historicalFiles());
            writeNewJson(runRoot.resolve("audit/historical-registration.json"), history.report());
            stage(runRoot, receipt, "CONFIGURATION_GATE_AND_SCENARIO_ASSEMBLY");
            simulation = DynamicMixedExperimentRunner.prepareSimulationWithConfigurationGate(spec, workspace, runRoot,
                    (configuration, actualRoot) -> {
                        var report = DynamicMixedFormalConfigurationGate.verify(workspace, spec, actualRoot, configuration,
                                compatibility.historicalOutputConfig(), compatibility.historicalFiles().receipt());
                        writeNewJson(actualRoot.resolve("audit/configuration.json"), report);
                        report.requireVerified();
                    });
            int finalIteration, completedIterations;
            try (var prepared = simulation) {
                receipt.put("initial_conservation", prepared.conservation());
                receipt.put("assembled_configuration_sha256", prepared.configuration().resolvedSnapshot().sha256());
                writeNewJson(runRoot.resolve("assembled_configuration.json"), Map.of(
                        "schema_version", ResolvedNonDispatchConfiguration.SCHEMA,
                        "sha256", prepared.configuration().resolvedSnapshot().sha256(),
                        "fields", prepared.configuration().resolvedSnapshot().fields()));
                // Both listeners join the actual controller; no manual replay can open activation 20.
                prepared.controler().addOverridingModule(history.module(runRoot.resolve("matsim")));
                daily = new DynamicMixedDailyDispatchAudit(workspace, runRoot, spec.design().protocol(),
                        prepared::dispatchValidationInputs);
                prepared.controler().addOverridingModule(daily.module());
                stage(runRoot, receipt, "CONTROLLER_RUNNING");
                prepared.controler().run();
                prepared.requireAllIterationsCompleted();
                daily.requireComplete();
                var historyResult = history.report();
                if (!historyResult.activationHistoryCheckPassed() || historyResult.poisoned()
                        || historyResult.checkedIterations().size() != FORMAL_PROTOCOL.activationIteration())
                    throw new IOException("Controller returned without the complete actual pre-activation history gate");
                finalIteration = prepared.finalIterationReached();
                completedIterations = prepared.completedIterations().size();
            }
            // Evidence streams have closed before independent persisted-output validation.
            receipt.put("final_iteration_reached", finalIteration); receipt.put("completed_iterations", completedIterations);
            writeNewJson(runRoot.resolve("audit/historical-final.json"), history.report());
            writeNewJson(runRoot.resolve("audit/daily-dispatch-index.json"), daily.reports());
            stage(runRoot, receipt, "INDEPENDENT_POSTFLIGHT");
            var postflight = new DynamicMixedResultAudit().audit(workspace, runRoot);
            writeNewJson(runRoot.resolve("audit/postflight.json"), postflight);
            if (!postflight.scopedChecksPassed())
                throw new IOException("Independent complete-range dispatch/scientific postflight failed; see audit/postflight.json");
            stage(runRoot, receipt, "VERIFY_FINAL_IDENTITIES");
            build.verifySource(workspace, false); build.verifyRuntime(workspace);
            compatibility.verifyUnchanged();
            var inputAfter = DynamicMixedInputVerifier.verify(workspace, spec);
            if (!inputBefore.equals(inputAfter)) throw new IOException("Registered inputs changed during formal execution");
            if (!manifestSha.equals(ExperimentConfiguration.sha256(manifest)))
                throw new IOException("Build manifest changed during formal execution");
            if (!specSha.equals(DynamicMixedExperimentSpecIO.sha256(
                    DynamicMixedExperimentSpecIO.read(runRoot.resolve("experiment_spec.json")))))
                throw new IOException("Run specification changed during formal execution");
            receipt.put("sourceCleanAfter", true); receipt.put("input_verification_after", inputAfter);
            receipt.put("verification_scopes", Map.of(
                    "clean-source-and-exact-runtime-identity", true,
                    "identity-bound-source-compatibility-review", true,
                    "registered-resolved-and-historical-initialized-configuration", true,
                    "actual-pre-activation-0-19-history", true,
                    "prepared-input-daily-dispatch-and-registered-service-coverage", true,
                    "independent-complete-range-scientific-artifacts", true));
            receipt.put("scopedChecksPassed", true); receipt.put("stage", "FINALIZE_RECEIPT");
            receipt.put("status", COMPLETED); receipt.put("completed_at", Instant.now().toString());
            receipt.put("output_files", DynamicMixedExperimentRunner.inventory(runRoot));
            receipt.put("stage", "COMPLETE");
            writeReceipt(runRoot, receipt);
            return new RunResult(runId, runRoot, COMPLETED, finalIteration, completedIterations, true, true);
        } catch (Exception | Error failure) {
            if (simulation != null) {
                receipt.put("final_iteration_reached", simulation.finalIterationReached());
                receipt.put("completed_iterations", simulation.completedIterations().size());
            }
            if (history != null) receipt.put("historical_history_at_failure", history.report());
            if (daily != null) receipt.put("daily_dispatch_at_failure", daily.reports());
            recordFailure(runRoot, receipt, failure);
            if (failure instanceof Error error) throw error;
            if (failure instanceof IOException io) throw io;
            throw new IOException("Formal new-T run failed; partial output retained at " + runRoot, failure);
        }
    }

    static void requireFormalSpec(DynamicMixedExperimentSpec spec) {
        DynamicMixedExperimentRunner.requireSupportedSpec(spec);
        if (!FORMAL_PROTOCOL.equals(spec.design().protocol()))
            throw new IllegalArgumentException("Formal T requires iterations 0--199, activation 20, innovation off 150, reporting 170--199");
        if (!List.of("B1", "B2", "B3").contains(spec.blockId()))
            throw new IllegalArgumentException("Only explicitly registered T blocks B1, B2 or B3 are in scope");
        if (!spec.design().phaseAt(170).controlEligible() || !spec.design().phaseAt(199).controlEligible())
            throw new IllegalArgumentException("Formal T must retain active nonzero-amplitude dynamic control throughout reporting");
    }

    static Path formalRunRoot(Path workspace, Path outputRoot, DynamicMixedExperimentSpec spec,
                              String sourceSha, String specSha) throws IOException {
        requireFormalSpec(spec);
        for (String digest : List.of(sourceSha, specSha))
            if (!digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid formal run identity digest");
        return DynamicMixedExperimentRunner.containedOutput(workspace,
                DynamicMixedExperimentRunner.containedOutput(workspace, outputRoot).resolve("formal")
                        .resolve(spec.experimentId()).resolve(sourceSha).resolve(spec.blockId())
                        .resolve(spec.design().arm().name().toLowerCase(Locale.ROOT)).resolve(specSha));
    }

    static void createNewRunDirectory(Path runRoot) throws IOException {
        if (Files.exists(runRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Refusing existing formal output; no overwrite or resume: " + runRoot);
        Files.createDirectories(runRoot.getParent()); Files.createDirectory(runRoot);
    }

    static Path containedFile(Path workspace, Path requested) throws IOException {
        for (Path part : requested) if (part.toString().equals("..")) throw new IOException("Parent traversal is forbidden");
        Path resolved = (requested.isAbsolute() ? requested : workspace.resolve(requested)).toRealPath();
        if (!resolved.startsWith(workspace) || !Files.isRegularFile(resolved))
            throw new IOException("Expected a regular workspace file: " + requested);
        return resolved;
    }

    private static void stage(Path root, Map<String, Object> receipt, String stage) throws IOException {
        receipt.put("stage", stage); writeReceipt(root, receipt);
    }

    static void writeNewJson(Path path, Object value) throws IOException {
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }

    static void writeReceipt(Path root, Map<String, Object> receipt) throws IOException {
        Path temporary = root.resolve(".run_receipt.tmp"); writeNewJson(temporary, receipt);
        Files.move(temporary, root.resolve("run_receipt.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Failure retains its stage and outputs; inability to record is suppressed on the original cause. */
    static void recordFailure(Path root, Map<String, Object> receipt, Throwable failure) {
        if ("COMPLETE".equals(receipt.get("stage"))) receipt.put("stage", "FINALIZE_RECEIPT");
        receipt.put("status", "FAILED"); receipt.put("scopedChecksPassed", false);
        receipt.put("completed_at", Instant.now().toString());
        receipt.put("failure", failure.getClass().getName() + ": " + failure.getMessage());
        try {
            receipt.put("output_files", DynamicMixedExperimentRunner.inventory(root));
            writeReceipt(root, receipt);
        } catch (Exception recordingFailure) { failure.addSuppressed(recordingFailure); }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 7 || !args[0].equals("formal"))
            throw new IllegalArgumentException("Usage: DynamicMixedFormalRunner formal <workspace> <output-root> <build-manifest> <v2-spec.json> <compatibility-evidence.json> <compatibility-sha256>; no force/resume/matrix");
        Path workspace = Path.of(args[1]).toRealPath();
        Path spec = containedFile(workspace, Path.of(args[4]));
        Path compatibility = containedFile(workspace, Path.of(args[5]));
        var ref = new ExperimentSpec.FileRef(workspace.relativize(compatibility).toString(), args[6]);
        var result = new DynamicMixedFormalRunner().run(DynamicMixedExperimentSpecIO.read(spec),
                new Options(workspace, Path.of(args[2]), Path.of(args[3]), ref));
        System.out.println(JSON.writeValueAsString(result));
    }
}
