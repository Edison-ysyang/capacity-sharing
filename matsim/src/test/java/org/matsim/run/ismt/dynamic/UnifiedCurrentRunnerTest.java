package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.run.ismt.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.UnifiedExperimentRunner.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Declaration and fail-fast API tests only; no controller, driver, forecast or simulation. */
class UnifiedCurrentRunnerTest {
    @TempDir Path temporary;

    @Test void explicitCdRetainTheirSupplyAdapterAndLegacyMIsNotSilentlyConverted() throws Exception {
        for (Arm arm : List.of(Arm.C, Arm.D)) {
            var legacy = legacy(arm);
            var selection = new Selection(arm, Profile.UNIFIED_CURRENT, legacy, null);
            assertSame(legacy, selection.legacy());
            assertFalse(selection.requiresPairedDeclaration());
            assertNotNull(selection.executionContract());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new Selection(Arm.M, Profile.UNIFIED_CURRENT, legacy(Arm.M), null));
    }

    @Test void unifiedControlRejectsSupplyChangeButPreservationStillReadsIt() throws Exception {
        var c = legacy(Arm.C);
        for (String field : List.of("activatedSchedule", "activatedTransitVehicles")) {
            var changed = replace(c, "inputs", replace(c.inputs(), field,
                    new ExperimentSpec.FileRef("declared/changed.xml", "e".repeat(64))));
            assertThrows(IllegalArgumentException.class, () -> new Selection(Arm.C, Profile.UNIFIED_CURRENT, changed, null));
            assertDoesNotThrow(() -> new Selection(Arm.C, Profile.PRESERVE_REGISTERED, changed, null));
        }
        var aliases = replace(c, "inputs", replace(c.inputs(), "activatedSchedule",
                new ExperimentSpec.FileRef("declared/alias.xml", c.inputs().baselineSchedule().sha256())));
        assertDoesNotThrow(() -> new Selection(Arm.C, Profile.UNIFIED_CURRENT, aliases, null));
    }

    @Test void currentMixedPairUsesExplicitV2WindowAndPreservesOffRegressionIdentity() {
        for (Arm arm : List.of(Arm.M, Arm.T)) {
            var selected = selection(arm);
            assertTrue(selected.requiresPairedDeclaration());
            assertNotNull(selected.executionContract());
        }
        var t = selection(Arm.T).dynamic();
        var off = replace(t, "design", replace(t.design(), "controlEnabled", false));
        var selected = new Selection(Arm.T, Profile.UNIFIED_CURRENT, null, off);
        assertEquals(Arm.T, selected.arm());
        assertFalse(selected.dynamic().design().controlEnabled());
        var oldPolicy = replace(t, "control", replace(t.control(), "policyId", DynamicMixedControlDefinitions.POLICY_ID));
        assertThrows(IllegalArgumentException.class, () -> new Selection(Arm.T, Profile.UNIFIED_CURRENT, null, oldPolicy));
    }

    @Test void unpairedAndSameArmCallsFailBeforeInputsBuildOrOutput() {
        var runner = new UnifiedExperimentRunner();
        var m = selection(Arm.M);
        assertThrows(IllegalArgumentException.class, () -> runner.runDevelopment(m,
                temporary, temporary.resolve("output"), temporary.resolve("absent-manifest.json")));
        assertThrows(IllegalArgumentException.class, () -> runner.runPairedMemberDevelopment(m, m,
                temporary, temporary.resolve("output"), temporary.resolve("absent-manifest.json")));
        assertFalse(Files.exists(temporary.resolve("output")));
    }

    @Test void scientificMismatchFailsBeforeAnyInputFileIsOpened() {
        var m = selection(Arm.M);
        var t = selection(Arm.T).dynamic();
        var changed = new Selection(Arm.T, Profile.UNIFIED_CURRENT, null,
                replace(t, "seeds", replace(t.seeds(), "orderPool", t.seeds().orderPool() + 1)));
        assertThrows(IllegalArgumentException.class, () -> new UnifiedExperimentRunner().runPairedMemberDevelopment(m, changed,
                temporary, temporary.resolve("output"), temporary.resolve("absent-manifest.json")));
        assertFalse(Files.exists(temporary.resolve("output")));
    }

    @Test void pairedDeclarationStillRequiresActualInputBytesBeforeBuildAndOutput() {
        var m = selection(Arm.M);
        var t = selection(Arm.T);
        assertThrows(java.io.IOException.class, () -> new UnifiedExperimentRunner().runPairedMemberDevelopment(m, t,
                temporary, temporary.resolve("output"), temporary.resolve("absent-manifest.json")));
        assertFalse(Files.exists(temporary.resolve("output")));
    }

    @Test void oppositeMemberActualBytesAreVerifiedAfterValidSelectedMember() throws Exception {
        byte[] bytes = "read-only verifier fixture, not executable simulation data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.write(temporary.resolve("valid-input.bin"), bytes);
        Files.writeString(temporary.resolve("changed-alias.bin"), "different bytes");
        var m = referencedSelection(Arm.M, "valid-input.bin", sha);
        var t = referencedSelection(Arm.T, "changed-alias.bin", sha);
        assertTrue(UnifiedMixedComparison.compare(m.dynamic(), t.dynamic()).declaredEqual());
        var failure = assertThrows(java.io.IOException.class, () -> new UnifiedExperimentRunner().runPairedMemberDevelopment(m, t,
                temporary, temporary.resolve("output"), temporary.resolve("absent-manifest.json")));
        assertTrue(failure.getMessage().contains("hash mismatch"), failure.getMessage());
        assertTrue(failure.getMessage().contains("changed-alias.bin"), failure.getMessage());
        assertFalse(Files.exists(temporary.resolve("output")));
    }

    @Test void allCurrentArmsRejectFormalPromotionBeforeBuild() throws Exception {
        var runner = new UnifiedExperimentRunner();
        for (Arm arm : Arm.values()) {
            var selection = arm == Arm.C || arm == Arm.D
                    ? new Selection(arm, Profile.UNIFIED_CURRENT, legacy(arm), null) : selection(arm);
            assertThrows(IllegalArgumentException.class, () -> runner.runFormal(selection,
                    temporary, temporary.resolve("output"), temporary.resolve("absent-manifest.json"), null));
        }
        assertFalse(Files.exists(temporary.resolve("output")));
    }

    @Test void cliRequiresPairBeforeTryingToResolveMissingManifest() throws Exception {
        Path file = temporary.resolve("m.json");
        DynamicMixedExperimentSpecIO.writeNew(file, selection(Arm.M).dynamic());
        var args = new String[]{"development", "M", "UNIFIED_CURRENT", temporary.toString(),
                "output", "absent-manifest.json", "m.json"};
        assertThrows(IllegalArgumentException.class, () -> UnifiedExperimentRunner.main(args));
        assertFalse(Files.exists(temporary.resolve("output")));
    }

    private static Selection selection(Arm arm) {
        var t = DynamicMixedSpecFixtures.spec(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        t = replace(replace(t, "control", replace(t.control(), "policyId", DynamicMixedControlDefinitions.WINDOWED_POLICY_ID)),
                "output", replace(t.output(), "dispatchEvidenceSchemaVersion", DynamicMixedDispatchEvidence.SCHEMA));
        var spec = arm == Arm.T ? t : replace(t, "design", new DynamicMixedDesign(DynamicMixedDesign.Arm.MIXED,
                t.design().protocol(), false, t.design().limits()));
        return new Selection(arm, Profile.UNIFIED_CURRENT, null, spec);
    }

    private static Selection referencedSelection(Arm arm, String path, String sha) throws Exception {
        var mapper = new ObjectMapper();
        JsonNode tree = mapper.valueToTree(selection(arm).dynamic());
        rewriteReferences(tree, path, sha);
        return new Selection(arm, Profile.UNIFIED_CURRENT, null,
                mapper.treeToValue(tree, DynamicMixedExperimentSpec.class));
    }

    private static void rewriteReferences(JsonNode tree, String path, String sha) {
        if (tree.isObject() && tree.size() == 2 && tree.has("path") && tree.has("sha256")) {
            ((ObjectNode) tree).put("path", path).put("sha256", sha);
        } else if (tree.isContainerNode()) {
            tree.forEach(child -> rewriteReferences(child, path, sha));
        }
    }

    private static ExperimentSpec legacy(Arm arm) throws Exception {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            Path profiles = path.resolve("experiments/ismt_q720");
            if (Files.isDirectory(profiles)) return ExperimentSpecIO.compose(profiles, "B1", switch (arm) {
                case C -> ExperimentSpec.Arm.CONTROL; case M -> ExperimentSpec.Arm.MIXED;
                case D -> ExperimentSpec.Arm.DEDICATED; default -> throw new IllegalArgumentException();
            });
        }
        throw new AssertionError("Profiles missing");
    }
}
