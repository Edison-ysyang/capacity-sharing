package org.matsim.run.ismt;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.run.ismt.dynamic.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * One build/entry for current C/M/T/D, without relabelling historical registrations.
 * The preservation profile keeps the registered v1 C/M/D and v2 T semantics.
 * The forward windowed profile accepts explicit v2 M/T specifications and shares
 * their entire native assembly. It is development-only until separately registered
 * formal validation exists. No input conversion, implicit matrix launch or resume.
 */
public final class UnifiedExperimentRunner {
    public enum Arm { C, M, T, D }
    public enum Profile { PRESERVE_REGISTERED, WINDOWED_MIXED, UNIFIED_CURRENT }
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public record Selection(Arm arm, Profile profile, ExperimentSpec legacy,
                            DynamicMixedExperimentSpec dynamic) {
        public Selection {
            Objects.requireNonNull(arm); Objects.requireNonNull(profile);
            if ((legacy == null) == (dynamic == null))
                throw new IllegalArgumentException("Exactly one registered specification is required");
            if (legacy != null) {
                if (profile == Profile.WINDOWED_MIXED || (profile == Profile.UNIFIED_CURRENT
                        && legacy.arm() != ExperimentSpec.Arm.CONTROL && legacy.arm() != ExperimentSpec.Arm.DEDICATED))
                    throw new IllegalArgumentException("Legacy specifications cannot silently acquire a boarding window");
                Arm expected = switch (legacy.arm()) {
                    case CONTROL -> Arm.C;
                    case MIXED -> Arm.M;
                    case DEDICATED -> Arm.D;
                    case TIMETABLE_ONLY -> throw new IllegalArgumentException("Historical TIMETABLE_ONLY is not current T");
                };
                if (arm != expected) throw new IllegalArgumentException("Requested arm differs from specification");
                if (profile == Profile.UNIFIED_CURRENT && arm == Arm.C
                        && (!legacy.inputs().baselineSchedule().sha256().equals(legacy.inputs().activatedSchedule().sha256())
                        || !legacy.inputs().baselineTransitVehicles().sha256().equals(legacy.inputs().activatedTransitVehicles().sha256())))
                    throw new IllegalArgumentException("Unified C requires unchanged baseline passenger supply");
            } else {
                Arm expected = switch (dynamic.design().arm()) {
                    case MIXED -> Arm.M;
                    case DYNAMIC_MIXED -> Arm.T;
                    default -> throw new IllegalArgumentException("v2 mixed lifecycle does not replace C/D supply assembly");
                };
                if (arm != expected) throw new IllegalArgumentException("Requested arm differs from specification");
                if (profile != Profile.PRESERVE_REGISTERED) DynamicMixedExperimentRunner.requireWindowedMixedSpec(dynamic);
                else if (arm != Arm.T) throw new IllegalArgumentException("Preservation M requires its original v1 registration");
            }
        }

        public String specificationSha256() throws IOException {
            return legacy != null ? ExperimentSpecIO.sha256(legacy) : DynamicMixedExperimentSpecIO.sha256(dynamic);
        }
        public String boardingWindowPolicy() {
            if (legacy != null) return "unchanged-v1-no-new-physical-window";
            return DynamicMixedControlDefinitions.WINDOWED_POLICY_ID.equals(dynamic.control().policyId())
                    ? "registered-shared-window-stop-new-boarding-allow-onboard-arrival"
                    : "registered-v2-definition";
        }
        public UnifiedArmContract executionContract() {
            return legacy != null ? UnifiedArmContract.fromLegacy(legacy) : UnifiedArmContract.fromDynamic(dynamic);
        }
        public boolean requiresPairedDeclaration() {
            return profile == Profile.UNIFIED_CURRENT && (arm == Arm.M || arm == Arm.T);
        }
    }

    public static Selection read(Path file, Arm arm, Profile profile) throws IOException {
        var metadata = JSON.readTree(Files.readAllBytes(file));
        if (metadata == null || !metadata.isObject()) throw new IOException("Expected one specification object");
        return switch (metadata.path("schemaVersion").asText()) {
            case ExperimentSpec.SCHEMA -> new Selection(arm, profile, ExperimentSpecIO.read(file), null);
            case DynamicMixedExperimentSpec.SCHEMA -> new Selection(arm, profile, null, DynamicMixedExperimentSpecIO.read(file));
            default -> throw new IOException("Unsupported explicit specification schema");
        };
    }

    /** Exactly one arm; all child runners verify the same explicitly supplied build. */
    public Object runDevelopment(Selection selected, Path workspace, Path output, Path manifest) throws IOException {
        if (selected.requiresPairedDeclaration())
            throw new IllegalArgumentException("Unified M/T development requires an explicit opposite-arm paired specification");
        return dispatchDevelopment(selected, workspace, output, manifest);
    }

    /** Verify both declarations and their actual input bytes, then execute only the selected member. */
    public Object runPairedMemberDevelopment(Selection selected, Selection counterpart,
            Path workspace, Path output, Path manifest) throws IOException {
        if (!selected.requiresPairedDeclaration() || !counterpart.requiresPairedDeclaration()
                || selected.arm() == counterpart.arm())
            throw new IllegalArgumentException("Expected an explicit UNIFIED_CURRENT M/T pair");
        UnifiedMixedComparison.requireCompatible(selected.dynamic(), counterpart.dynamic());
        DynamicMixedInputVerifier.verify(workspace, selected.dynamic());
        DynamicMixedInputVerifier.verify(workspace, counterpart.dynamic());
        return dispatchDevelopment(selected, workspace, output, manifest);
    }

    private Object dispatchDevelopment(Selection selected, Path workspace, Path output, Path manifest) throws IOException {
        if (selected.legacy() != null)
            return new ExperimentRunner().run(selected.legacy(), new ExperimentRunner.Options(workspace, output, manifest, true, false));
        var runner = new DynamicMixedExperimentRunner();
        var options = new DynamicMixedExperimentRunner.Options(workspace, output, manifest);
        return selected.profile() != Profile.PRESERVE_REGISTERED
                ? runner.runWindowedDevelopment(selected.dynamic(), options)
                : runner.runDevelopment(selected.dynamic(), options);
    }

    public Object runFormal(Selection selected, Path workspace, Path output, Path manifest,
            ExperimentSpec.FileRef compatibility) throws IOException {
        if (selected.profile() != Profile.PRESERVE_REGISTERED)
            throw new IllegalArgumentException("Forward profiles require separate formal registration; no development promotion");
        if (selected.legacy() != null) {
            if (compatibility != null) throw new IllegalArgumentException("C/M/D do not consume a T compatibility file");
            return new ExperimentRunner().run(selected.legacy(), new ExperimentRunner.Options(workspace, output, manifest, false, false));
        }
        if (compatibility == null) throw new IllegalArgumentException("Formal T requires exact-build compatibility evidence");
        return new DynamicMixedFormalRunner().run(selected.dynamic(),
                new DynamicMixedFormalRunner.Options(workspace, output, manifest, compatibility));
    }

    /** Read-only declaration inspection is deliberately not a simulation/preflight certificate. */
    public static void main(String[] args) throws IOException {
        if (args.length < 7 || args.length > 9)
            throw new IllegalArgumentException("Usage: UnifiedExperimentRunner <inspect|development|formal> <C|M|T|D> <PRESERVE_REGISTERED|WINDOWED_MIXED|UNIFIED_CURRENT> <workspace> <output> <build-manifest> <spec> [paired-spec OR compatibility-file sha256]");
        if (!java.util.Set.of("inspect", "development", "formal").contains(args[0]))
            throw new IllegalArgumentException("Unknown operation");
        Path workspace = Path.of(args[3]).toRealPath();
        Selection selected = read(input(workspace, Path.of(args[6])), Arm.valueOf(args[1]), Profile.valueOf(args[2]));
        if (args[0].equals("inspect")) {
            if (args.length != 7) throw new IllegalArgumentException("Inspection has no compatibility override");
            System.out.println(JSON.writeValueAsString(Map.of("arm", selected.arm(), "profile", selected.profile(),
                    "specificationSha256", selected.specificationSha256(), "boardingWindowPolicy", selected.boardingWindowPolicy(),
                    "executionContract", selected.executionContract(), "pairedDeclarationRequiredForDevelopment", selected.requiresPairedDeclaration(),
                    "simulationExecuted", false, "runtimeVerified", false)));
            return;
        }
        Selection counterpart = null;
        if (args[0].equals("development")) {
            if (selected.requiresPairedDeclaration()) {
                if (args.length != 8) throw new IllegalArgumentException("Unified M/T development requires exactly one paired specification");
                counterpart = read(input(workspace, Path.of(args[7])), selected.arm() == Arm.M ? Arm.T : Arm.M, selected.profile());
                UnifiedMixedComparison.requireCompatible(selected.dynamic(), counterpart.dynamic());
            } else if (args.length != 7) throw new IllegalArgumentException("This development selection has no paired or compatibility override");
        } else if (selected.profile() != Profile.PRESERVE_REGISTERED) {
            throw new IllegalArgumentException("Forward profiles have no formal runtime registration");
        }
        Path output = ExperimentRunner.safeOutputRoot(workspace, Path.of(args[4]));
        Path manifest = input(workspace, Path.of(args[5]));
        var runner = new UnifiedExperimentRunner();
        Object result;
        if (args[0].equals("development")) {
            result = counterpart == null ? runner.runDevelopment(selected, workspace, output, manifest)
                    : runner.runPairedMemberDevelopment(selected, counterpart, workspace, output, manifest);
        } else {
            if (args.length == 8) throw new IllegalArgumentException("Compatibility file requires its SHA-256");
            var compatibility = args.length == 9
                    ? new ExperimentSpec.FileRef(workspace.relativize(input(workspace, Path.of(args[7]))).toString(), args[8]) : null;
            result = runner.runFormal(selected, workspace, output, manifest, compatibility);
        }
        System.out.println(JSON.writeValueAsString(Map.of("arm", selected.arm(), "profile", selected.profile(), "result", result)));
    }

    private static Path input(Path workspace, Path requested) throws IOException {
        for (Path part : requested) if (part.toString().equals("..")) throw new IOException("Parent traversal forbidden");
        Path path = (requested.isAbsolute() ? requested : workspace.resolve(requested)).normalize();
        if (!path.startsWith(workspace)) throw new IOException("Input outside workspace");
        Path current = workspace;
        for (Path part : workspace.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Input symlink forbidden");
        }
        if (!Files.isRegularFile(path)) throw new IOException("Missing input: " + path);
        return path.toRealPath();
    }
}
