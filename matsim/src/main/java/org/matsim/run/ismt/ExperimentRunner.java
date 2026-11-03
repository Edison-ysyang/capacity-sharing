package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One execution path for all four arms. Formal identity checks precede scenario loading. */
public final class ExperimentRunner {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final RunValidator validator;
    public ExperimentRunner() { this(new IsmtRunValidator()); }
    public ExperimentRunner(RunValidator validator) { this.validator = java.util.Objects.requireNonNull(validator); }

    public record Options(Path workspace, Path outputRoot, Path buildManifest,
            boolean forceDevelopment, boolean resume) { }
    public record RunResult(String runId, Path runRoot, boolean formalRun, boolean resumed,
            RunValidator.ValidationResult validation) { }

    public RunResult run(ExperimentSpec spec, Options options) throws IOException {
        Path workspace = options.workspace().toRealPath();
        BuildIdentity build = BuildIdentity.read(options.buildManifest());
        boolean clean = build.verifySource(workspace, options.forceDevelopment());
        build.verifyRuntime(workspace);
        ExperimentConfiguration.verifyInputs(spec, workspace);
        String specHash = ExperimentSpecIO.sha256(spec);
        String runId = spec.experimentId() + "-" + spec.blockId() + "-" + spec.arm() + "-" + specHash.substring(0, 16);
        Path outputRoot = safeOutputRoot(workspace, options.outputRoot());
        Path runRoot = outputRoot.resolve(options.forceDevelopment() ? "development" : "formal")
                .resolve(spec.experimentId()).resolve(build.sourceSha256()).resolve(spec.blockId())
                .resolve(spec.arm().name().toLowerCase(java.util.Locale.ROOT)).resolve(specHash);
        runRoot = safeOutputRoot(workspace, runRoot); // appended identity components may themselves be symlinks
        Path sourceRoot = workspace.resolve(build.sourceRoot()).toRealPath();
        if (!options.forceDevelopment() && runRoot.startsWith(sourceRoot))
            throw new IOException("Formal output must be separate from the frozen source checkout");
        boolean formal = !options.forceDevelopment();
        if (Files.exists(runRoot)) {
            if (!options.resume()) throw new IOException("Refusing existing run; --resume only revalidates a completed receipt: " + runRoot);
            return resume(runRoot, runId, spec, specHash, build, formal);
        }
        Files.createDirectories(runRoot.getParent());
        Files.createDirectory(runRoot); // exclusive collision boundary; no overwrite/partial-run restart
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schema_version", "ismt-run-v1");
        receipt.put("formalRun", formal);
        receipt.put("sourceClean", clean);
        receipt.put("experiment_id", spec.experimentId()); receipt.put("block_id", spec.blockId());
        receipt.put("arm", spec.arm()); receipt.put("run_id", runId); receipt.put("seeds", spec.seeds());
        receipt.put("started_at", Instant.now().toString()); receipt.put("status", "STARTED");
        receipt.put("source_sha256", build.sourceSha256()); receipt.put("build_sha256", build.applicationJar().sha256());
        receipt.put("dependency_sha256", build.dependencySha256()); receipt.put("spec_sha256", specHash);
        receipt.put("output_schema_version", spec.output().schemaVersion());
        receipt.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        receipt.put("runtime_os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        receipt.put("build_manifest_path", options.buildManifest().toAbsolutePath().toString());
        receipt.put("build_manifest_sha256", ExperimentConfiguration.sha256(options.buildManifest()));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema_version", "ismt-scenario-v1"); manifest.put("spec", spec);
        manifest.put("spec_sha256", specHash); manifest.put("build", build);
        manifest.put("arm_payload", ExperimentArmPayload.from(spec)); manifest.put("simulation_directory", "matsim");
        // Preserve the exact bytes hashed by Java; downstream validation need not mimic floating-point JSON formatting.
        Files.write(runRoot.resolve("experiment_spec.json"), ExperimentSpecIO.canonicalBytes(spec), StandardOpenOption.CREATE_NEW);
        Files.write(runRoot.resolve("scenario_manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest),
                StandardOpenOption.CREATE_NEW);
        writeReceipt(runRoot, receipt);
        try {
            var prepared = prepareSimulation(spec, workspace, runRoot.resolve("matsim"));
            receipt.put("initial_conservation", prepared.conservation());
            writeReceipt(runRoot, receipt);
            prepared.controler().run();
            RunValidator.ValidationResult validation = validator.validate(runRoot, spec);
            receipt.put("validation", validation);
            receipt.put("final_iteration_reached", validation.finalIteration());
            receipt.put("activation_count", validation.activationCount());
            receipt.put("activation_iteration", validation.activationIteration());
            receipt.put("pre_activation_fingerprint", validation.preActivationFingerprint());
            if (!validation.valid()) throw new IOException("Run failed structural validation: " + validation.issues());
            boolean cleanAfter = build.verifySource(workspace, options.forceDevelopment());
            build.verifyRuntime(workspace);
            ExperimentConfiguration.verifyInputs(spec, workspace);
            receipt.put("sourceCleanAfter", cleanAfter);
            receipt.put("status", "VALIDATED");
            receipt.put("matrix_validated", false); // a single run never proves cross-arm equivalence
            receipt.put("completed_at", Instant.now().toString());
            receipt.put("output_files", inventory(runRoot));
            writeReceipt(runRoot, receipt);
            return new RunResult(runId, runRoot, formal, false, validation);
        } catch (Exception failure) {
            receipt.put("status", "FAILED"); receipt.put("completed_at", Instant.now().toString());
            receipt.put("failure", failure.getClass().getName() + ": " + failure.getMessage());
            receipt.put("output_files", inventory(runRoot));
            writeReceipt(runRoot, receipt);
            if (failure instanceof IOException io) throw io;
            throw new IOException("ISMT run failed; partial outputs preserved at " + runRoot, failure);
        }
    }

    /** Package seam for CI fixtures: same execution path, explicitly not a formal provenance claim. */
    static PreparedSimulation prepareSimulation(ExperimentSpec spec, Path workspace, Path output) throws IOException {
        var config = ExperimentConfiguration.create(spec, workspace, output);
        var scenario = ScenarioUtils.loadScenario(config);
        var freight = SharedFreightScenarioPreparation.prepare(scenario, spec.freightDemand().orderCount(),
                SharedFreightScenarioPreparation.ValidationOrder.REGISTERED_V1_DEMAND_FIRST);
        UnifiedArmContract.fromLegacy(spec).verifyScenario(scenario);
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new IsmtControllerModule(spec));
        return new PreparedSimulation(controler, freight.conservation());
    }

    record PreparedSimulation(Controler controler, FreightOrderRegistry.PopulationReconciliation conservation) { }

    /** Executes a complete arm set per block. Cross-arm validation is a separate mandatory matrix gate. */
    public List<RunResult> runMatrix(List<ExperimentSpec> specs, Options options) throws IOException {
        preflight(specs, options);
        List<RunResult> results = new ArrayList<>();
        for (var spec : specs) results.add(run(spec, options));
        return List.copyOf(results);
    }

    /** Read-only full-matrix preflight: invalid later-arm inputs cannot trigger a partially launched matrix. */
    public static void preflight(List<ExperimentSpec> specs, Options options) throws IOException {
        validateMatrixSpecifications(specs);
        Path workspace = options.workspace().toRealPath();
        BuildIdentity build = BuildIdentity.read(options.buildManifest());
        build.verifySource(workspace, options.forceDevelopment());
        build.verifyRuntime(workspace);
        Path output = safeOutputRoot(workspace, options.outputRoot());
        Path source = workspace.resolve(build.sourceRoot()).toRealPath();
        if (!options.forceDevelopment() && output.startsWith(source))
            throw new IOException("Formal output must be outside the frozen source checkout");
        for (var spec : specs) {
            ExperimentConfiguration.verifyInputs(spec, workspace);
            var config = org.matsim.core.config.ConfigUtils.loadConfig(spec.inputs().matsimConfig().resolve(workspace).toString(),
                    new CoTransportConfigGroup(), new org.matsim.run.r17.PlanAExecutionHorizonConfigGroup());
            ExperimentConfiguration.rejectUnregisteredInputs(config);
            ExperimentConfiguration.validateFixedFreightStrategies(config, spec);
        }
    }

    public static void validateMatrixSpecifications(List<ExperimentSpec> specs) {
        if (specs.isEmpty()) throw new IllegalArgumentException("Empty matrix");
        Map<String, Map<ExperimentSpec.Arm, ExperimentSpec>> blocks = new TreeMap<>();
        for (var spec : specs) {
            if (blocks.computeIfAbsent(spec.blockId(), ignored -> new java.util.EnumMap<>(ExperimentSpec.Arm.class))
                    .putIfAbsent(spec.arm(), spec) != null) throw new IllegalArgumentException("Duplicate block/arm");
            if (!spec.experimentId().equals(specs.getFirst().experimentId())
                    || !spec.protocol().equals(specs.getFirst().protocol())
                    || !spec.output().equals(specs.getFirst().output())
                    || !spec.validationProfile().equals(specs.getFirst().validationProfile())
                    || !commonSettings(spec.policies(), "access", "dedicatedLineId")
                            .equals(commonSettings(specs.getFirst().policies(), "access", "dedicatedLineId"))
                    || !commonSettings(spec.freightDemand(), "orderCount")
                            .equals(commonSettings(specs.getFirst().freightDemand(), "orderCount"))
                    || !spec.inputs().matsimConfig().equals(specs.getFirst().inputs().matsimConfig()))
                throw new IllegalArgumentException("Matrix uses inconsistent common configuration");
        }
        for (var block : blocks.values()) {
            if (block.size() != ExperimentSpec.Arm.values().length) throw new IllegalArgumentException("Missing arm");
            var control = block.get(ExperimentSpec.Arm.CONTROL);
            for (var spec : block.values()) {
                if (!spec.seeds().equals(control.seeds())
                        || !spec.inputs().passengerPlanMemory().equals(control.inputs().passengerPlanMemory())
                        || !spec.inputs().network().equals(control.inputs().network())
                        || !spec.inputs().baselineSchedule().equals(control.inputs().baselineSchedule())
                        || !spec.inputs().baselineTransitVehicles().equals(control.inputs().baselineTransitVehicles())
                        || !spec.inputs().roadVehicles().sha256().equals(control.inputs().roadVehicles().sha256())
                        || !spec.inputs().orderPool().equals(control.inputs().orderPool()))
                    throw new IllegalArgumentException("Block matching inputs differ");
            }
            var t = block.get(ExperimentSpec.Arm.TIMETABLE_ONLY);
            var d = block.get(ExperimentSpec.Arm.DEDICATED);
            var m = block.get(ExperimentSpec.Arm.MIXED);
            if (!t.policies().dedicatedLineId().equals(d.policies().dedicatedLineId()))
                throw new IllegalArgumentException("Timetable-only and Dedicated line identities differ");
            if (!m.inputs().executionOrders().sha256().equals(d.inputs().executionOrders().sha256())
                    || m.freightDemand().orderCount() != d.freightDemand().orderCount()
                    || !control.inputs().executionOrders().sha256().equals(t.inputs().executionOrders().sha256()))
                throw new IllegalArgumentException("Execution demand differs within a matched freight/no-freight pair");
            if (!m.inputs().personAttributes().sha256().equals(d.inputs().personAttributes().sha256())
                    || !control.inputs().personAttributes().sha256().equals(t.inputs().personAttributes().sha256()))
                throw new IllegalArgumentException("Person attributes differ within a matched freight/no-freight pair");
            if (!ExperimentArmPayload.from(t).supply().sameContent(ExperimentArmPayload.from(d).supply()))
                throw new IllegalArgumentException("Timetable-only and Dedicated supplies differ");
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode commonSettings(Object record, String... armSpecific) {
        com.fasterxml.jackson.databind.node.ObjectNode common = JSON.valueToTree(record);
        common.remove(List.of(armSpecific));
        return common;
    }

    private RunResult resume(Path root, String runId, ExperimentSpec spec, String hash, BuildIdentity build,
            boolean formal) throws IOException {
        var receipt = JSON.readTree(root.resolve("run_receipt.json").toFile());
        if (!receipt.path("status").asText().equals("VALIDATED") || receipt.path("formalRun").asBoolean() != formal
                || !receipt.path("spec_sha256").asText().equals(hash)
                || !receipt.path("source_sha256").asText().equals(build.sourceSha256())
                || !receipt.path("build_sha256").asText().equals(build.applicationJar().sha256())
                || !receipt.path("dependency_sha256").asText().equals(build.dependencySha256()))
            throw new IOException("--resume cannot reuse an incomplete or differently identified run");
        // Round-trip the expected inventory just like the receipt: small file sizes
        // otherwise compare as LongNode (Files.size) versus parsed IntNode.
        if (!JSON.readTree(JSON.writeValueAsBytes(inventory(root))).equals(receipt.get("output_files")))
            throw new IOException("Completed output inventory changed");
        var validation = validator.validate(root, spec);
        if (!validation.valid()) throw new IOException("Completed run no longer validates: " + validation.issues());
        return new RunResult(runId, root, formal, true, validation);
    }

    static Map<String, Map<String, Object>> inventory(Path root) throws IOException {
        Map<String, Map<String, Object>> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = root.relativize(path).toString();
                if (name.equals("run_receipt.json") || name.equals(".run_receipt.tmp")) continue;
                if (!path.toRealPath().startsWith(root.toRealPath())) throw new IOException("Output symlink escapes run");
                files.put(name, Map.of("bytes", Files.size(path), "sha256", ExperimentConfiguration.sha256(path)));
            }
        }
        return files;
    }

    static Path safeOutputRoot(Path workspace, Path requested) throws IOException {
        Path root = workspace.toRealPath();
        for (Path component : requested) if (component.toString().equals(".."))
            throw new IOException("Output path must not contain parent traversal");
        Path output = requested.isAbsolute() ? requested.normalize() : root.resolve(requested).normalize();
        if (output.equals(root) || !output.startsWith(root)) throw new IOException("Output root must be a workspace subdirectory");
        Path componentPath = root;
        for (Path component : root.relativize(output)) {
            componentPath = componentPath.resolve(component);
            if (Files.isSymbolicLink(componentPath)) throw new IOException("Output path must not traverse a symlink: " + componentPath);
        }
        Path ancestor = output;
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(root)) throw new IOException("Output ancestor escapes workspace");
        return output;
    }

    private static void writeReceipt(Path root, Map<String, Object> receipt) throws IOException {
        Path temporary = root.resolve(".run_receipt.tmp");
        Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt), StandardOpenOption.CREATE_NEW);
        Files.move(temporary, root.resolve("run_receipt.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 6) throw new IllegalArgumentException("Usage: ExperimentRunner <run|matrix|preflight> <workspace> <output-root> <build-manifest> <spec.json|profile-dir> <block-list|-> [--resume] [--force-development]");
        var flags = List.of(args).subList(6, args.length);
        if (flags.stream().anyMatch(flag -> !flag.equals("--resume") && !flag.equals("--force-development")))
            throw new IllegalArgumentException("Unknown execution flag");
        Options options = new Options(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                flags.contains("--force-development"), flags.contains("--resume"));
        ExperimentRunner runner = new ExperimentRunner();
        if (args[0].equals("run")) {
            System.out.println(JSON.writeValueAsString(runner.run(ExperimentSpecIO.read(Path.of(args[4])), options)));
        } else if (args[0].equals("matrix") || args[0].equals("preflight")) {
            List<ExperimentSpec> specs = new ArrayList<>();
            for (String block : args[5].split(",")) for (var arm : ExperimentSpec.Arm.values())
                specs.add(ExperimentSpecIO.compose(Path.of(args[4]), block, arm));
            if (args[0].equals("preflight")) {
                preflight(specs, options);
                System.out.println("Full matrix preflight passed for " + specs.size() + " specifications; no simulation executed.");
            } else System.out.println(JSON.writeValueAsString(runner.runMatrix(specs, options)));
        } else throw new IllegalArgumentException("Unknown execution command");
    }
}
