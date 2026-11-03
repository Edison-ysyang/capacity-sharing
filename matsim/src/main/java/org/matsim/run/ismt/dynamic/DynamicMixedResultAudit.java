package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.vehicles.MatsimVehicleReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Independent postflight entry for persisted new-T output. Reads static supply
 * and order contracts, but never loads the population, creates a Controller,
 * runs QSim, edits a receipt, or resumes a simulation. Hashing a population is
 * not native preparation or proof of its supported execution domain. Missing or
 * partial days remain failures; a successful scoped audit is not formal reuse
 * authorization, full causal certification or a railway-safety validation.
 */
public final class DynamicMixedResultAudit {
    public static final String SCHEMA = "ismt-dynamic-result-audit-v1";

    public record StaticInputs(DynamicMixedDispatchValidator.Inputs dispatch,
            DynamicMixedInputVerifier.Verification registeredInputs, int orderCount, int freightUnits,
            int loadedPopulationPersons, boolean nativePreparationVerified) { }
    public record Day(int iteration, String workspaceRelativeFile,
            DynamicMixedDispatchValidator.Report report, String failure) {
        public boolean valid() { return report != null && report.valid() && failure == null; }
    }
    public record Report(String schemaVersion, String checkedAt, String runDirectory, String specificationSha256,
            boolean scopedChecksPassed, boolean formalAuthorization, StaticInputSummary staticInputs,
            List<Day> dispatchDays, DynamicMixedRunValidator.Report scientific,
            List<String> unverifiedBoundaries) {
        public Report { dispatchDays = List.copyOf(dispatchDays); unverifiedBoundaries = List.copyOf(unverifiedBoundaries); }
    }
    public record StaticInputSummary(DynamicMixedInputVerifier.Verification registeredInputs,
            int registeredTrips, int registeredControlPoints, int orderCount, int freightUnits,
            int loadedPopulationPersons, boolean nativePreparationVerified) { }

    /** Reads static supply only; no population/ScenarioLoader, Controller or output writer is invoked. */
    public static StaticInputs loadStaticInputs(Path workspace, Path runRoot, DynamicMixedExperimentSpec spec) throws IOException {
        Path root = workspace.toRealPath(); Path run = existingDirectory(root, runRoot);
        DynamicMixedExperimentRunner.requireSupportedSpec(spec);
        var before = DynamicMixedInputVerifier.verify(root, spec);
        // The execution adapter rightly rejects existing output. Give its config-only assembly a
        // never-created path, then restore the real context; neither path is opened by this audit.
        Path configOnlyOutput = run.resolve(".unwritten-postflight-" + java.util.UUID.randomUUID());
        var config = ExperimentConfiguration.create(DynamicMixedConfiguration.commonRequest(spec), root, configOnlyOutput);
        config.controller().setOutputDirectory(run.resolve("matsim").toString());
        DynamicMixedConfiguration.requireRuntimeDeclaration(config, spec.runtime());
        var scenario = ScenarioUtils.createScenario(config);
        // Match the normal loader's coordinate transformations without reading plans or attributes.
        new MatsimNetworkReader(config.network().getInputCRS(), config.global().getCoordinateSystem(), scenario.getNetwork())
                .readFile(spec.inputs().network().resolve(root).toString());
        new TransitScheduleReader(config.transit().getInputScheduleCRS(), config.global().getCoordinateSystem(), scenario)
                .readFile(spec.inputs().baselineSchedule().resolve(root).toString());
        new MatsimVehicleReader(scenario.getTransitVehicles())
                .readFile(spec.inputs().baselineTransitVehicles().resolve(root).toString());
        var bound = DynamicMixedControlDefinitions.read(root, spec).bind(scenario);
        var settings = ConfigUtils.addOrGetModule(config, CoTransportConfigGroup.class).toSettings(config);
        var registry = new FreightOrderRegistry(settings);
        var release = new LinkedHashMap<String, Double>();
        int orderCount = 0;
        for (var order : registry.orders()) {
            orderCount++;
            for (var unit : registry.unitIdsForOrder(order.orderId()))
                if (release.putIfAbsent(unit.toString(), order.releaseTime()) != null)
                    throw new IOException("Duplicate registered freight unit");
        }
        if (orderCount != spec.freightDemand().orderCount())
            throw new IOException("Declared order count differs from the execution table");
        // Inputs.from needs only the static mapping here; release identities come directly from the real registry.
        var supply = DynamicMixedDispatchValidator.Inputs.from(bound, List.of());
        var input = new DynamicMixedDispatchValidator.Inputs(supply.design(), supply.trips(), supply.points(),
                supply.connections(), supply.facilities(), supply.originFacilities(), release, supply.repairDepartures(),
                supply.repairTimeSeconds(), supply.maximumMeanWaitingIncrementSeconds(), supply.qsimTimeStepSeconds(),
                supply.adjustmentWindow());
        if (!before.equals(DynamicMixedInputVerifier.verify(root, spec)))
            throw new IOException("Registered inputs changed during static audit assembly");
        if (!scenario.getPopulation().getPersons().isEmpty()) throw new IOException("Postflight must not load a population");
        return new StaticInputs(input, before, orderCount, release.size(), 0, false);
    }

    public Report audit(Path workspace, Path runRoot) throws IOException {
        Path root = workspace.toRealPath(); Path run = existingDirectory(root, runRoot);
        Path specFile = regularFile(root, run.resolve("experiment_spec.json"));
        String fileHash = ExperimentConfiguration.sha256(specFile);
        var spec = DynamicMixedExperimentSpecIO.read(specFile);
        var inputs = loadStaticInputs(root, run, spec);
        var days = new ArrayList<Day>(); var protocol = spec.design().protocol();
        for (int day = protocol.firstIteration(); day <= protocol.finalIteration(); day++) {
            Path file = run.resolve("dispatch").resolve("iteration-%03d.jsonl".formatted(day));
            String name = root.relativize(file).toString();
            try {
                regularFile(root, file);
                days.add(new Day(day, name, new DynamicMixedDispatchValidator().validate(root, file, day, inputs.dispatch()), null));
            } catch (IOException | RuntimeException failure) {
                days.add(new Day(day, name, null, failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            }
        }
        var scientific = new DynamicMixedRunValidator().validate(root, run, spec);
        if (!fileHash.equals(ExperimentConfiguration.sha256(regularFile(root, specFile)))
                || !inputs.registeredInputs().equals(DynamicMixedInputVerifier.verify(root, spec)))
            throw new IOException("Specification or registered inputs changed during postflight");
        var summary = new StaticInputSummary(inputs.registeredInputs(), inputs.dispatch().trips().size(),
                inputs.dispatch().points().size(), inputs.orderCount(), inputs.freightUnits(), 0, false);
        return new Report(SCHEMA, Instant.now().toString(), root.relativize(run).toString(),
                DynamicMixedExperimentSpecIO.sha256(spec), scientific.valid() && days.stream().allMatch(Day::valid), false,
                summary, days, scientific, List.of("actual-native-prepared-population-domain", "source-build-and-runtime-identity",
                        "registered-resolved-definition-semantics", "whole-run-atomic-snapshot",
                        "historical-M-pairing-and-common-history", "full-event-prefix-and-terminal-suffix",
                        "native-capacity-mechanism-exercise", "railway-safety", "formal-run-authorization"));
    }

    /** Report must be new and outside the inspected run, so audit never alters simulation evidence. */
    static Path reportPath(Path workspace, Path runRoot, Path requested) throws IOException {
        Path root = workspace.toRealPath(), run = existingDirectory(root, runRoot);
        Path target = lexicalContained(root, requested);
        if (target.startsWith(run) || Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Report must be a new file outside the inspected run");
        existingDirectory(root, target.getParent());
        return target;
    }
    private static Path existingDirectory(Path root, Path requested) throws IOException {
        Path target = lexicalContained(root, requested);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected existing workspace subdirectory: " + target);
        return target;
    }
    private static Path regularFile(Path root, Path requested) throws IOException {
        Path target = lexicalContained(root, requested);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing regular workspace file: " + target);
        return target;
    }
    private static Path lexicalContained(Path root, Path requested) throws IOException {
        Objects.requireNonNull(requested);
        for (Path part : requested) if (part.toString().equals("..")) throw new IOException("Parent traversal is forbidden");
        Path path = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize();
        if (path.equals(root) || !path.startsWith(root)) throw new IOException("Expected workspace subpath");
        Path part = root;
        for (Path entry : root.relativize(path)) {
            part = part.resolve(entry);
            if (Files.isSymbolicLink(part)) throw new IOException("Audit paths must not traverse symbolic links");
        }
        return path;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4 || !args[0].equals("audit"))
            throw new IllegalArgumentException("Usage: DynamicMixedResultAudit audit <workspace> <existing-run-root> <new-report-file>");
        Path workspace = Path.of(args[1]).toRealPath(), run = Path.of(args[2]);
        Path output = reportPath(workspace, run, Path.of(args[3]));
        Report result = new DynamicMixedResultAudit().audit(workspace, run);
        var json = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        Files.write(output, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(result), StandardOpenOption.CREATE_NEW);
        if (!result.scopedChecksPassed()) throw new IOException("Scoped postflight failed; independent report preserved at " + output);
    }
}
