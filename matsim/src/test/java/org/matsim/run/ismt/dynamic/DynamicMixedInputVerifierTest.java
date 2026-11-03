package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ExperimentSpecIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.*;

class DynamicMixedInputVerifierTest {
    @TempDir Path temporary;
    private static final byte[] BYTES = "test bytes only, not a decoded policy or adopted configuration\n".getBytes(StandardCharsets.UTF_8);

    @Test void checksEveryRoleAndReadsOneRealFileOnceWithoutWritingArtifacts() throws Exception {
        Path root = root();
        Files.write(root.resolve("shared.bin"), BYTES);
        var value = bound(ref("shared.bin"));
        var verification = DynamicMixedInputVerifier.verify(root, value);
        assertEquals(19, verification.files().size());
        assertEquals(value.allReferencedFiles().keySet(), verification.files().stream().map(DynamicMixedInputVerifier.VerifiedFile::role)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, verification.uniqueFiles());
        assertEquals(BYTES.length, verification.uniqueBytesRead());
        assertTrue(verification.files().stream().allMatch(file -> file.sha256().equals(ref("shared.bin").sha256())
                && file.bytes() == BYTES.length && file.realWorkspaceRelativePath().equals("shared.bin")));
        assertThrows(UnsupportedOperationException.class, () -> verification.files().clear());
        try (var files = Files.list(root)) { assertEquals(java.util.List.of("shared.bin"), files.map(p -> p.getFileName().toString()).toList()); }
        assertArrayEquals(BYTES, Files.readAllBytes(root.resolve("shared.bin")));
    }

    @Test void distinctFilesWithSameClaimedHashAreBothRead() throws Exception {
        Path root = root();
        Files.write(root.resolve("shared.bin"), BYTES); Files.write(root.resolve("copy.bin"), BYTES);
        var value = bound(ref("shared.bin"));
        value = replace(value, "inputs", replace(value.inputs(), "network", ref("copy.bin")));
        var verification = DynamicMixedInputVerifier.verify(root, value);
        assertEquals(2, verification.uniqueFiles());
        assertEquals(2L * BYTES.length, verification.uniqueBytesRead());
    }

    @Test void sameClaimedHashOnDifferentCorruptedFileIsNotSkipped() throws Exception {
        Path root = root();
        Files.write(root.resolve("shared.bin"), BYTES); Files.writeString(root.resolve("copy.bin"), "wrong bytes");
        var original = bound(ref("shared.bin"));
        var corrupted = replace(original, "inputs", replace(original.inputs(), "network", ref("copy.bin")));
        var error = assertThrows(IOException.class, () -> DynamicMixedInputVerifier.verify(root, corrupted));
        assertTrue(error.getMessage().contains("inputs.network"));
    }

    @Test void eachDeclaredHashIsCheckedEvenWhenItNamesAlreadyReadFile() throws Exception {
        Path root = root(); Files.write(root.resolve("shared.bin"), BYTES);
        var original = bound(ref("shared.bin"));
        var changed = replace(original, "inputs", replace(original.inputs(), "network",
                new ExperimentSpec.FileRef("shared.bin", "b".repeat(64))));
        assertThrows(IOException.class, () -> DynamicMixedInputVerifier.verify(root, changed));
    }

    @Test void realPathAliasIsRecordedAndDoesNotDoubleCountReading() throws Exception {
        Path root = root(); Files.write(root.resolve("shared.bin"), BYTES);
        Files.createSymbolicLink(root.resolve("alias.bin"), Path.of("shared.bin"));
        var original = bound(ref("shared.bin"));
        var value = replace(original, "inputs", replace(original.inputs(), "network", ref("alias.bin")));
        var result = DynamicMixedInputVerifier.verify(root, value);
        assertEquals(1, result.uniqueFiles());
        var network = result.files().stream().filter(file -> file.role().equals("inputs.network")).findFirst().orElseThrow();
        assertEquals("alias.bin", network.declaredPath()); assertEquals("shared.bin", network.realWorkspaceRelativePath());
    }

    @Test void rejectsWorkspaceEscapesMissingFilesAndDirectories() throws Exception {
        Path root = root();
        Files.write(temporary.resolve("outside.bin"), BYTES);
        Files.createSymbolicLink(root.resolve("escape.bin"), temporary.resolve("outside.bin"));
        assertThrows(IOException.class, () -> DynamicMixedInputVerifier.verify(root, bound(ref("escape.bin"))));
        assertThrows(IOException.class, () -> DynamicMixedInputVerifier.verify(root, bound(ref("missing.bin"))));
        Files.createDirectory(root.resolve("directory"));
        assertThrows(IOException.class, () -> DynamicMixedInputVerifier.verify(root, bound(ref("directory"))));
    }

    @Test void controlAndResolvedRuntimeFilesAreNotExemptFromIntegrityChecks() throws Exception {
        Path root = root(); Files.write(root.resolve("shared.bin"), BYTES);
        var value = bound(ref("shared.bin"));
        var missing = new DynamicMixedExperimentSpec.RegisteredDefinition("toy-v1", ref("not-found.bin"));
        var runtime = replace(value, "runtime", replace(value.runtime(), "resolvedConfiguration", missing));
        assertThrows(IOException.class, () -> DynamicMixedInputVerifier.verify(root, runtime));
        for (String field : new String[]{"policyDefinition", "constraintDefinition", "forecastDefinition",
                "passengerProtectionDefinition", "informationDefinition", "baselineTreatmentDefinition"}) {
            var changed = replace(value, "control", replace(value.control(), field, missing));
            assertThrows(IOException.class, () -> DynamicMixedInputVerifier.verify(root, changed));
        }
    }

    private Path root() throws IOException { return Files.createDirectory(temporary.resolve("workspace")); }
    private static ExperimentSpec.FileRef ref(String path) { return new ExperimentSpec.FileRef(path, ExperimentSpecIO.sha256(BYTES)); }

    private static DynamicMixedExperimentSpec bound(ExperimentSpec.FileRef ref) {
        var original = spec();
        var inputs = new ExperimentSpec.Inputs(ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref);
        var definition = new DynamicMixedExperimentSpec.RegisteredDefinition("toy-definition-v1", ref);
        var runtime = replace(original.runtime(), "resolvedConfiguration", definition);
        var control = original.control();
        for (String field : new String[]{"policyDefinition", "constraintDefinition", "forecastDefinition",
                "passengerProtectionDefinition", "informationDefinition", "baselineTreatmentDefinition"})
            control = replace(control, field, definition);
        return replace(replace(replace(original, "inputs", inputs), "runtime", runtime), "control", control);
    }
}
