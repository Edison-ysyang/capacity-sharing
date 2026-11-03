package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class BuildIdentityTest {
    @TempDir Path workspace;

    @Test void cleanSourceVerifiesAndContentTamperNeverPassesDevelopmentOverride() throws Exception {
        BuildIdentity identity = fixture();
        assertTrue(identity.verifySource(workspace, false));
        Files.writeString(workspace.resolve("source/code.txt"), "changed");
        assertThrows(IOException.class, () -> identity.verifySource(workspace, false));
        assertThrows(IOException.class, () -> identity.verifySource(workspace, true));
    }

    @Test void dirtySourceOnlyPermittedWhenExplicitlyDevelopment() throws Exception {
        BuildIdentity identity = fixture();
        Files.writeString(workspace.resolve("source/new-scientific-input.txt"), "untracked");
        assertThrows(IOException.class, () -> identity.verifySource(workspace, false));
        assertFalse(identity.verifySource(workspace, true));
    }

    @Test void missingTrackedSourceCoverageRejectsFormalReceipt() throws Exception {
        BuildIdentity identity = fixture();
        Path source = workspace.resolve("source");
        Files.writeString(source.resolve("extra.txt"), "source");
        git(source, "add", "--", "extra.txt");
        git(source, "commit", "-m", "Second fixture source");
        BuildIdentity incomplete = new BuildIdentity(identity.schemaVersion(), identity.sourceRoot(),
                git(source, "rev-parse", "HEAD").strip(), identity.sourceSha256(), identity.sourceFiles(),
                identity.applicationJar(), identity.runtimeClasspath(), identity.dependencySha256(), identity.matsimVersion(),
                identity.javaRuntimeVersion(), identity.mavenVersion(), identity.wrapperVersion(), identity.os(),
                identity.buildCommand(), identity.createdAt(), true);
        assertThrows(IOException.class, () -> incomplete.verifySource(workspace, false));
    }

    @Test void classDirectoriesCannotMasqueradeAsFrozenRuntime() throws Exception {
        BuildIdentity identity = fixture();
        // Fixture's application.jar is intentionally not the live test classpath.
        Files.writeString(workspace.resolve("application.jar"), "not a matching JAR");
        assertThrows(IOException.class, () -> identity.verifyRuntime(workspace));
    }

    @Test void completeCleanSourceWithChineseFilenameMatchesRawGitInventory() throws Exception {
        BuildIdentity identity = fixture("docs/研究说明.md");
        assertTrue(identity.verifySource(workspace, false));
    }

    @Test void completeCleanSourceWithNewlineFilenameMatchesRawGitInventory() throws Exception {
        BuildIdentity identity = fixture("docs/two\nlines.md");
        assertTrue(identity.verifySource(workspace, false));
    }

    @Test void effectiveUnregisteredFileUrlManifestDependencyIsRejected() throws Exception {
        Path dependency = jar("extra.jar", null, "file-url-marker");
        Path application = jar("application.jar", dependency.toUri().toASCIIString(), null);
        assertEffectiveManifestDependency(application, "file-url-marker");

        assertThrows(IOException.class, () -> BuildIdentity.verifyManifestClasspath(List.of(application)));
    }

    @Test void effectiveUnregisteredPercentEncodedManifestDependencyIsRejected() throws Exception {
        jar("extra dep.jar", null, "encoded-path-marker");
        Path application = jar("application.jar", "extra%20dep.jar", null);
        assertEffectiveManifestDependency(application, "encoded-path-marker");

        assertThrows(IOException.class, () -> BuildIdentity.verifyManifestClasspath(List.of(application)));
    }

    @Test void registeredEncodedManifestDependencyRemainsAllowed() throws Exception {
        Path dependency = jar("extra dep.jar", null, "registered-marker");
        Path application = jar("application.jar", "extra%20dep.jar", null);
        assertEffectiveManifestDependency(application, "registered-marker");

        assertDoesNotThrow(() -> BuildIdentity.verifyManifestClasspath(List.of(application, dependency)));
    }

    @Test void registeredFileUrlManifestDependencyRemainsAllowed() throws Exception {
        Path dependency = jar("extra.jar", null, "registered-file-marker");
        Path application = jar("application.jar", dependency.toUri().toASCIIString(), null);
        assertEffectiveManifestDependency(application, "registered-file-marker");

        assertDoesNotThrow(() -> BuildIdentity.verifyManifestClasspath(List.of(application, dependency)));
    }

    @Test void absentRelativeManifestDependenciesRemainAllowedForFrozenPlainClasspath() throws Exception {
        Path application = jar("application.jar", "libs/not-copied-by-the-freezer.jar", null);
        assertDoesNotThrow(() -> BuildIdentity.verifyManifestClasspath(List.of(application)));
    }

    @Test void nonlocalManifestDependencyIsRejectedWithoutOpeningIt() throws Exception {
        Path application = jar("application.jar", "https://example.invalid/unregistered.jar", null);
        assertThrows(IOException.class, () -> BuildIdentity.verifyManifestClasspath(List.of(application)));
    }

    @Test void malformedEncodedManifestDependencyFailsClosed() throws Exception {
        Path application = jar("application.jar", "extra%ZZ.jar", null);
        assertThrows(IOException.class, () -> BuildIdentity.verifyManifestClasspath(List.of(application)));
    }

    @Test void blankManifestClasspathIsAnEmptyDependencyList() throws Exception {
        Path application = jar("application.jar", "  ", null);
        assertDoesNotThrow(() -> BuildIdentity.verifyManifestClasspath(List.of(application)));
    }

    private Path jar(String filename, String classpath, String marker) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classpath != null) manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classpath);
        Path path = workspace.resolve(filename);
        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            if (marker != null) {
                output.putNextEntry(new JarEntry("identity-marker.txt"));
                output.write(marker.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path.toRealPath();
    }

    private static void assertEffectiveManifestDependency(Path application, String expected) throws IOException {
        try (var loader = new URLClassLoader(new URL[]{application.toUri().toURL()}, null);
                var marker = loader.getResourceAsStream("identity-marker.txt")) {
            assertNotNull(marker, "The JVM must resolve this manifest link for the regression to be meaningful");
            assertEquals(expected, new String(marker.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private BuildIdentity fixture() throws Exception {
        return fixture("code.txt");
    }

    private BuildIdentity fixture(String filename) throws Exception {
        Path source = Files.createDirectory(workspace.resolve("source"));
        Path file = source.resolve(filename);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "source\n");
        git(source, "init", "--initial-branch=ismt-test-fixture");
        git(source, "add", "--", filename);
        git(source, "commit", "-m", "Temporary provenance test fixture");
        Map<String, String> files = Map.of(filename, ExperimentConfiguration.sha256(file));
        String hash = ExperimentSpecIO.sha256(new ObjectMapper().writeValueAsBytes(files));
        var jar = new ExperimentSpec.FileRef("application.jar", "a".repeat(64));
        return new BuildIdentity("ismt-build-v1", "source", git(source, "rev-parse", "HEAD").strip(), hash,
                files, jar, List.of(jar), "b".repeat(64), "2026.0", System.getProperty("java.runtime.version"),
                "3.9.16", "3.3.4", "test", List.of("test-only"), "2026-09-04T00:00:00Z", true);
    }

    private String git(Path source, String... args) throws Exception {
        Path hooks = workspace.resolve("empty-hooks");
        Files.createDirectories(hooks);
        List<String> command = new ArrayList<>(List.of("git", "-C", source.toString(), "-c", "user.name=ISMT Test Fixture",
                "-c", "user.email=ismt-test@invalid", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=" + hooks));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
