package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.run.ismt.BuildIdentity;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.SharedFreightScenarioPreparation;
import org.matsim.run.ismt.UnifiedArmContract;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Single-T development entry, using the real v2 input/configuration/Controller path.
 * It neither launches a legacy matrix nor constructs a legacy ExperimentSpec. There
 * is deliberately no formal-run or resume entry. A finished Controller is recorded
 * as COMPLETED_UNVALIDATED: full scientific result validation, historical-M reuse,
 * resolved-definition semantics and formal authorization are NOT supplied here.
 * Development still requires the recorded plain-Java build/classpath and all input
 * hashes. It permits a dirty recorded source tree, unlike a formal source gate.
 */
public final class DynamicMixedExperimentRunner {
    public static final String RECEIPT_SCHEMA = "ismt-dynamic-development-run-v1";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final List<String> UNVERIFIED = List.of(
            "full-scientific-output-validation", "registered-resolved-configuration-definition-semantics",
            "historical-M-full-configuration-and-source-runtime-compatibility", "historical-M-common-history-and-result-reuse",
            "formal-source-inventory-completeness", "formal-run-authorization",
            "complete-actual-operating-constraint-and-terminal-event-certification");

    public record Options(Path workspace, Path outputRoot, Path buildManifest) {
        public Options { Objects.requireNonNull(workspace); Objects.requireNonNull(outputRoot); Objects.requireNonNull(buildManifest); }
    }

    public record RunResult(String runId, Path runRoot, String status, int finalIterationReached,
                            int completedIterations, boolean formalRun, boolean scientificOutputsValidated) { }

    /** Read-only pre-Scenario check; returning does not itself confer formal or scientific approval. */
    @FunctionalInterface
    public interface ConfigurationGate {
        void verify(DynamicMixedConfiguration.PreparedConfiguration configuration, Path runRoot) throws IOException;
    }

    /** Executes only the explicitly supplied new-T specification; no other arm is inferred. */
    public RunResult runDevelopment(DynamicMixedExperimentSpec spec, Options options) throws IOException {
        requireSupportedSpec(spec);
        return runDevelopmentInternal(spec, options, false);
    }

    /** Explicit forward M/T entry. Never changes a legacy M registration or grants formal status. */
    public RunResult runWindowedDevelopment(DynamicMixedExperimentSpec spec, Options options) throws IOException {
        requireWindowedMixedSpec(spec);
        return runDevelopmentInternal(spec, options, true);
    }

    private RunResult runDevelopmentInternal(DynamicMixedExperimentSpec spec, Options options,
            boolean windowedMixedEntry) throws IOException {
        Objects.requireNonNull(options);
        Path workspace = options.workspace().toRealPath();
        Path manifestPath = containedInput(workspace, options.buildManifest());
        BuildIdentity build = BuildIdentity.read(manifestPath);
        boolean sourceClean = build.verifySource(workspace, true);
        build.verifyRuntime(workspace);
        String specHash = DynamicMixedExperimentSpecIO.sha256(spec);
        String runId = spec.experimentId() + "-" + spec.blockId() + "-" + spec.design().arm() + "-" + specHash.substring(0, 16);
        Path runRoot = mixedDevelopmentRunRoot(workspace, options.outputRoot(), spec, build.sourceSha256(), specHash);
        if (Files.exists(runRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Refusing an existing development run; partial/completed runs are never resumed or overwritten: " + runRoot);
        Files.createDirectories(runRoot.getParent());
        Files.createDirectory(runRoot);
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("schema_version", RECEIPT_SCHEMA); receipt.put("formalRun", false);
        receipt.put("scientificOutputsValidated", false); receipt.put("historicalMixedReuseVerified", false);
        receipt.put("registeredResolvedDefinitionSemanticallyVerified", false);
        receipt.put("unverifiedBoundaries", UNVERIFIED);
        receipt.put("experiment_id", spec.experimentId()); receipt.put("block_id", spec.blockId());
        receipt.put("arm", spec.design().arm()); receipt.put("design_family", DynamicMixedDesign.SCHEMA_ID);
        receipt.put("run_id", runId); receipt.put("spec_sha256", specHash); receipt.put("seeds", spec.seeds());
        receipt.put("source_sha256", build.sourceSha256()); receipt.put("sourceClean", sourceClean);
        receipt.put("build_sha256", build.applicationJar().sha256()); receipt.put("dependency_sha256", build.dependencySha256());
        receipt.put("build_manifest_path", workspace.relativize(manifestPath).toString());
        receipt.put("build_manifest_sha256", ExperimentConfiguration.sha256(manifestPath));
        receipt.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        receipt.put("started_at", Instant.now().toString()); receipt.put("status", "STARTED");
        try {
            DynamicMixedExperimentSpecIO.writeNew(runRoot.resolve("experiment_spec.json"), spec);
            writeNewJson(runRoot.resolve("scenario_manifest.json"), Map.of(
                    "schema_version", "ismt-dynamic-development-scenario-v1", "spec", spec,
                    "spec_sha256", specHash, "build", build, "simulation_directory", "matsim",
                    "dispatch_directory", "dispatch", "formalRun", false));
            writeReceipt(runRoot, receipt);
            int finalIteration; int completedIterations;
            try (PreparedSimulation prepared = prepareSimulationInternal(spec, workspace, runRoot, null, windowedMixedEntry)) {
                receipt.put("initial_conservation", prepared.conservation());
                receipt.put("input_verification", prepared.configuration().inputVerification());
                receipt.put("assembled_configuration_sha256", prepared.configuration().resolvedSnapshot().sha256());
                writeNewJson(runRoot.resolve("assembled_configuration.json"), Map.of(
                        "schema_version", ResolvedNonDispatchConfiguration.SCHEMA,
                        "sha256", prepared.configuration().resolvedSnapshot().sha256(),
                        "fields", prepared.configuration().resolvedSnapshot().fields()));
                writeReceipt(runRoot, receipt);
                prepared.controler().run();
                prepared.requireAllIterationsCompleted();
                finalIteration = prepared.finalIterationReached();
                completedIterations = prepared.completedIterations().size();
            }
            receipt.put("sourceCleanAfter", build.verifySource(workspace, true));
            build.verifyRuntime(workspace);
            receipt.put("input_verification_after", DynamicMixedInputVerifier.verify(workspace, spec));
            if (!ExperimentConfiguration.sha256(manifestPath).equals(receipt.get("build_manifest_sha256")))
                throw new IOException("Build manifest changed during development execution");
            receipt.put("final_iteration_reached", finalIteration); receipt.put("completed_iterations", completedIterations);
            receipt.put("status", "COMPLETED_UNVALIDATED"); receipt.put("completed_at", Instant.now().toString());
            receipt.put("output_files", inventory(runRoot)); writeReceipt(runRoot, receipt);
            return new RunResult(runId, runRoot, "COMPLETED_UNVALIDATED", finalIteration, completedIterations, false, false);
        } catch (Exception | Error failure) {
            receipt.put("status", "FAILED"); receipt.put("completed_at", Instant.now().toString());
            receipt.put("failure", failure.getClass().getName() + ": " + failure.getMessage());
            try { receipt.put("output_files", inventory(runRoot)); writeReceipt(runRoot, receipt); }
            catch (Exception recordFailure) { failure.addSuppressed(recordFailure); }
            if (failure instanceof Error error) throw error;
            if (failure instanceof IOException io) throw io;
            throw new IOException("Dynamic development run failed; partial outputs preserved at " + runRoot, failure);
        }
    }

    /**
     * Same assembly for development and bounded native tests. The caller owns a new,
     * contained run directory. This does NOT check a source build or launch a QSim;
     * it verifies all nineteen input roles, decodes six control definitions, loads
     * the actual scenario, and installs the real modules. Close on every exit.
     */
    public static PreparedSimulation prepareSimulation(DynamicMixedExperimentSpec spec, Path workspace, Path runRoot) throws IOException {
        return prepareSimulationInternal(spec, workspace, runRoot, null, false);
    }

    /** Same native assembly and physical boarding window for explicitly registered forward M/T. */
    public static PreparedSimulation prepareWindowedSimulation(DynamicMixedExperimentSpec spec,
            Path workspace, Path runRoot) throws IOException {
        return prepareSimulationInternal(spec, workspace, runRoot, null, true);
    }

    /**
     * Same assembly, with an explicit caller-provided gate immediately after complete input/config
     * preparation and before definition decoding, Scenario/population loading, dispatch-directory
     * creation or Controler construction. Checked IOExceptions propagate unchanged. The gate receives
     * the actual raw Config and the resolved run root; it must not mutate the exposed configuration.
     * Successful return preserves the full raw snapshot, but does not seal hidden state or files.
     * No validation/authorization flag or development receipt is automatically upgraded by this hook.
     */
    public static PreparedSimulation prepareSimulationWithConfigurationGate(DynamicMixedExperimentSpec spec,
            Path workspace, Path runRoot, ConfigurationGate gate) throws IOException {
        return prepareSimulationInternal(spec, workspace, runRoot, Objects.requireNonNull(gate, "configurationGate"), false);
    }

    private static PreparedSimulation prepareSimulationInternal(DynamicMixedExperimentSpec spec, Path workspace,
            Path runRoot, ConfigurationGate gate, boolean windowedMixedEntry) throws IOException {
        if (windowedMixedEntry) requireWindowedMixedSpec(spec);
        else requireSupportedSpec(spec);
        Path root = workspace.toRealPath(); Path output = containedOutput(root, runRoot);
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Assembly run directory must already exist");
        if (Files.exists(output.resolve("dispatch"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Dispatch evidence already exists");
        var configuration = DynamicMixedConfiguration.prepare(spec, root, output.resolve("matsim"));
        if (gate != null) {
            gate.verify(configuration, output);
            if (!ResolvedNonDispatchConfiguration.compare(configuration.resolvedSnapshot(),
                    ResolvedNonDispatchConfiguration.snapshot(configuration.config())).strictlyEqual())
                throw new IllegalStateException("Configuration gate changed the complete raw configuration");
        }
        var definitions = DynamicMixedControlDefinitions.read(root, spec);
        var scenario = ScenarioUtils.loadScenario(configuration.config());
        var freight = SharedFreightScenarioPreparation.prepare(scenario, spec.freightDemand().orderCount(),
                SharedFreightScenarioPreparation.ValidationOrder.REGISTERED_V2_POPULATION_FIRST);
        var settings = freight.settings();
        var registry = freight.registry();
        var conservation = freight.conservation();
        var bound = definitions.bind(scenario);
        if (windowedMixedEntry && bound.sharedWindow() == null)
            throw new IllegalStateException("Forward windowed M/T assembly requires the registered physical boarding window");
        if (bound.sharedWindow() != null) {
            if (scenario.getScenarioElement(org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT) != null)
                throw new IllegalStateException("Shared boarding window already installed before registered assembly");
            scenario.addScenarioElement(org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT, bound.sharedWindow());
        }
        UnifiedArmContract.fromDynamic(spec).verifyScenario(scenario);
        var assembly = DynamicMixedScenarioInputs.capture(scenario, registry, settings, spec.design(),
                bound.controlPoints(), bound.vehicleConnections());
        DynamicMixedDispatchEvidence evidence = new DynamicMixedDispatchEvidence(root, output.resolve("dispatch"));
        try {
            Controler controler = new Controler(scenario);
            Completion completion = new Completion(spec.design().protocol());
            var dispatchInputs = new LinkedHashMap<Boolean, DynamicMixedDispatchValidator.Inputs>();
            controler.addOverridingModule(new DynamicMixedArtifactModule(spec.scientificContext()));
            controler.addOverridingModule(evidence.module());
            controler.addOverridingModule(new DynamicMixedControllerModule(spec.design(), settings,
                    day -> assembly.forIteration(day).qsimInputs(), day -> {
                        var inputs = assembly.forIteration(day);
                        var predictor = new CausalMixedServiceForecast(bound.forecastSettings(), inputs.supply(),
                                inputs.accessEgress(), ignored -> { });
                        return bound.newPolicy(evidence.auditedForecast(day, predictor),
                                decision -> evidence.rollingDecision(day, decision), audit -> evidence.repairDecision(day, audit));
                    }, evidence::sessionStarted, new DynamicMixedQSimModule.AssemblyCallbacks(
                            (day, qsim) -> {
                                var actual = assembly.forIteration(day);
                                evidence.prepared(day, actual.verifyPrepared(qsim));
                                // Capture assembly-only contracts for later read-only audits, not policy access.
                                boolean active = spec.design().phaseAt(day).freightTreatmentActive();
                                if (!dispatchInputs.containsKey(active)) dispatchInputs.put(active,
                                        DynamicMixedDispatchValidator.Inputs.from(bound, actual.qsimInputs().freightContracts()));
                            },
                            evidence::acceptClosed)));
            controler.addOverridingModule(new AbstractModule() {
                @Override public void install() { addControllerListenerBinding().toInstance(completion); }
            });
            return new PreparedSimulation(controler, configuration, conservation, evidence, completion, spec.design(), dispatchInputs);
        } catch (RuntimeException | Error failure) {
            try { evidence.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public static final class PreparedSimulation implements AutoCloseable {
        private final Controler controler;
        private final DynamicMixedConfiguration.PreparedConfiguration configuration;
        private final FreightOrderRegistry.PopulationReconciliation conservation;
        private final DynamicMixedDispatchEvidence evidence;
        private final Completion completion;
        private final DynamicMixedDesign design;
        private final Map<Boolean, DynamicMixedDispatchValidator.Inputs> dispatchInputs;
        private PreparedSimulation(Controler controler, DynamicMixedConfiguration.PreparedConfiguration configuration,
                FreightOrderRegistry.PopulationReconciliation conservation, DynamicMixedDispatchEvidence evidence, Completion completion,
                DynamicMixedDesign design, Map<Boolean, DynamicMixedDispatchValidator.Inputs> dispatchInputs) {
            this.controler = controler; this.configuration = configuration; this.conservation = conservation;
            this.evidence = evidence; this.completion = completion;
            this.design = design; this.dispatchInputs = dispatchInputs;
        }
        public Controler controler() { return controler; }
        public DynamicMixedConfiguration.PreparedConfiguration configuration() { return configuration; }
        public FreightOrderRegistry.PopulationReconciliation conservation() { return conservation; }
        /** Available only after a real prepared barrier for this phase; never fabricates missing contracts. */
        public DynamicMixedDispatchValidator.Inputs dispatchValidationInputs(int iteration) {
            return Objects.requireNonNull(dispatchInputs.get(design.phaseAt(iteration).freightTreatmentActive()),
                    "No verified native preparation for the requested phase");
        }
        public List<Integer> completedIterations() { return List.copyOf(completion.iterations); }
        public int finalIterationReached() { return completion.iterations.isEmpty() ? -1 : completion.iterations.getLast(); }
        public void requireAllIterationsCompleted() { completion.requireComplete(); }
        @Override public void close() { evidence.close(); }
    }

    private static final class Completion implements IterationEndsListener {
        private final DynamicMixedDesign.Protocol protocol;
        private final List<Integer> iterations = new ArrayList<>();
        private Completion(DynamicMixedDesign.Protocol protocol) { this.protocol = protocol; }
        @Override public double priority() { return -2000; } // after the evidence writer, never a second finishDay call
        @Override public void notifyIterationEnds(IterationEndsEvent event) {
            if (event.getIteration() != protocol.firstIteration() + iterations.size() || event.getIteration() > protocol.finalIteration())
                throw new IllegalStateException("Missing, repeated or regressed development iteration");
            iterations.add(event.getIteration());
        }
        private void requireComplete() {
            if (iterations.size() != (long) protocol.finalIteration() - protocol.firstIteration() + 1)
                throw new IllegalStateException("Controller returned without completing the registered iteration range");
        }
    }

    static void requireSupportedSpec(DynamicMixedExperimentSpec spec) {
        Objects.requireNonNull(spec);
        if (spec.design().arm() != DynamicMixedDesign.Arm.DYNAMIC_MIXED)
            throw new IllegalArgumentException("This independent entry accepts only DYNAMIC_MIXED; C/M/D and legacy T are never launched");
        if (!DynamicMixedDispatchEvidence.SCHEMA.equals(spec.output().dispatchEvidenceSchemaVersion()))
            throw new IllegalArgumentException("Unsupported dispatch evidence schema");
    }

    /** C/D and legacy TIMETABLE_ONLY are not mixed-service lifecycle aliases. */
    public static void requireWindowedMixedSpec(DynamicMixedExperimentSpec spec) {
        Objects.requireNonNull(spec);
        if (spec.design().arm() != DynamicMixedDesign.Arm.MIXED
                && spec.design().arm() != DynamicMixedDesign.Arm.DYNAMIC_MIXED)
            throw new IllegalArgumentException("Windowed mixed entry accepts only MIXED or DYNAMIC_MIXED");
        if (!DynamicMixedControlDefinitions.WINDOWED_POLICY_ID.equals(spec.control().policyId())
                || spec.control().baselineTreatment() != DynamicMixedExperimentSpec.BaselineTreatment.NO_PRE_SERVICE_REPAIR)
            throw new IllegalArgumentException("Require explicit shared-window policy and no pre-service repair");
        if (!DynamicMixedDispatchEvidence.SCHEMA.equals(spec.output().dispatchEvidenceSchemaVersion()))
            throw new IllegalArgumentException("Unsupported dispatch evidence schema");
    }

    static Path developmentRunRoot(Path workspace, Path outputRoot, DynamicMixedExperimentSpec spec,
                                   String sourceSha256, String specSha256) throws IOException {
        requireSupportedSpec(spec);
        return mixedDevelopmentRunRoot(workspace, outputRoot, spec, sourceSha256, specSha256);
    }

    private static Path mixedDevelopmentRunRoot(Path workspace, Path outputRoot, DynamicMixedExperimentSpec spec,
                                   String sourceSha256, String specSha256) throws IOException {
        if (sourceSha256 == null || !sourceSha256.matches("[a-f0-9]{64}")
                || specSha256 == null || !specSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid run identity digest");
        return containedOutput(workspace, containedOutput(workspace, outputRoot).resolve("development")
                .resolve(spec.experimentId()).resolve(sourceSha256).resolve(spec.blockId())
                .resolve(spec.design().arm().name().toLowerCase(Locale.ROOT)).resolve(specSha256));
    }

    static Path containedOutput(Path workspace, Path requested) throws IOException {
        Path root = workspace.toRealPath(); Objects.requireNonNull(requested);
        for (Path component : requested) if (component.toString().equals("..")) throw new IOException("Parent traversal is forbidden");
        Path target = requested.isAbsolute() ? requested.normalize() : root.resolve(requested).normalize();
        if (target.equals(root) || !target.startsWith(root)) throw new IOException("Output must be a workspace subdirectory");
        Path current = root;
        for (Path component : root.relativize(target)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Output path must not traverse symbolic links: " + current);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Output ancestor is not a directory: " + current);
        }
        return target;
    }

    private static Path containedInput(Path workspace, Path requested) throws IOException {
        Path path = (requested.isAbsolute() ? requested : workspace.resolve(requested)).toRealPath();
        if (!path.startsWith(workspace) || !Files.isRegularFile(path)) throw new IOException("Build manifest must be a workspace file");
        return path;
    }

    static Map<String, Map<String, Object>> inventory(Path directory) throws IOException {
        Path root = directory.toRealPath(); var files = new TreeMap<String, Map<String, Object>>();
        try (var entries = Files.walk(root)) {
            for (Path file : entries.sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Output inventory cannot certify symlinks: " + file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = root.relativize(file).toString();
                if (name.equals("run_receipt.json") || name.equals(".run_receipt.tmp")) continue;
                files.put(name, Map.of("bytes", Files.size(file), "sha256", ExperimentConfiguration.sha256(file)));
            }
        }
        return java.util.Collections.unmodifiableMap(files);
    }

    private static void writeNewJson(Path path, Object value) throws IOException {
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }

    private static void writeReceipt(Path root, Map<String, Object> receipt) throws IOException {
        Path temporary = root.resolve(".run_receipt.tmp"); writeNewJson(temporary, receipt);
        Files.move(temporary, root.resolve("run_receipt.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 5 || !args[0].equals("development"))
            throw new IllegalArgumentException("Usage: DynamicMixedExperimentRunner development <workspace> <output-root> <build-manifest> <v2-spec.json>; no formal/resume/matrix entry");
        Path workspace = Path.of(args[1]).toRealPath();
        Path specPath = containedInput(workspace, Path.of(args[4]));
        var result = new DynamicMixedExperimentRunner().runDevelopment(DynamicMixedExperimentSpecIO.read(specPath),
                new Options(workspace, Path.of(args[2]), Path.of(args[3])));
        System.out.println(JSON.writeValueAsString(result));
    }
}
