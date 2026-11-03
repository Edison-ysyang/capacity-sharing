package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.jar.JarFile;

/** Binds a plain-Java execution to an inspected source tree and every runtime JAR. */
public record BuildIdentity(String schemaVersion, String sourceRoot, String sourceCommit,
        String sourceSha256, Map<String, String> sourceFiles, ExperimentSpec.FileRef applicationJar,
        List<ExperimentSpec.FileRef> runtimeClasspath, String dependencySha256, String matsimVersion,
        String javaRuntimeVersion, String mavenVersion, String wrapperVersion, String os,
        List<String> buildCommand, String createdAt, boolean sourceClean) {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    public BuildIdentity {
        if (!"ismt-build-v1".equals(schemaVersion)) throw new IllegalArgumentException("Unsupported build schema");
        sourceFiles = Map.copyOf(sourceFiles);
        runtimeClasspath = List.copyOf(runtimeClasspath);
        buildCommand = List.copyOf(buildCommand);
        Objects.requireNonNull(applicationJar);
        if (sourceFiles.isEmpty() || runtimeClasspath.isEmpty() || !runtimeClasspath.getFirst().equals(applicationJar))
            throw new IllegalArgumentException("Incomplete source/classpath identity");
        if (!sourceCommit.matches("[a-f0-9]{40,64}")) throw new IllegalArgumentException("Invalid source commit");
        if (!"2026.0".equals(matsimVersion)) throw new IllegalArgumentException("This experiment requires MATSim 2026.0");
        // Validate relative source root using the same traversal rules as input references.
        new ExperimentSpec.FileRef(sourceRoot, sourceSha256);
        for (var entry : sourceFiles.entrySet()) new ExperimentSpec.FileRef(entry.getKey(), entry.getValue());
    }

    public static BuildIdentity read(Path path) throws IOException {
        return JSON.readValue(path.toFile(), BuildIdentity.class);
    }

    public boolean verifySource(Path workspace, boolean forceDevelopment) throws IOException {
        Path root = workspace.toRealPath();
        Path source = checkedPath(root, sourceRoot);
        String commit = git(source, "rev-parse", "HEAD").strip();
        if (!commit.equals(sourceCommit)) throw new IOException("Source commit differs from frozen build");
        boolean clean = git(source, "status", "--porcelain", "--untracked-files=all").isBlank();
        if (!forceDevelopment && (!clean || !sourceClean)) throw new IOException("Formal execution requires a clean source snapshot");
        for (var entry : sourceFiles.entrySet()) {
            Path path = source.resolve(entry.getKey()).toRealPath();
            if (!path.startsWith(source) || !ExperimentConfiguration.sha256(path).equals(entry.getValue()))
                throw new IOException("Source hash mismatch: " + entry.getKey());
        }
        String hash = ExperimentSpecIO.sha256(JSON.writeValueAsBytes(new TreeMap<>(sourceFiles)));
        if (!hash.equals(sourceSha256)) throw new IOException("Invalid source manifest digest");
        // A formal frozen checkout may contain no unmanifested tracked source or config.
        if (!forceDevelopment) {
            // Match the freezer's raw UTF-8, NUL-delimited inventory; Git's display format quotes
            // non-ASCII names and cannot distinguish a filename's newline from a record boundary.
            var tracked = new java.util.HashSet<>(Arrays.asList(git(source, "ls-files", "-z").split("\\x00")));
            if (!tracked.equals(sourceFiles.keySet())) throw new IOException("Source manifest does not cover the complete frozen checkout");
        }
        return clean;
    }

    public void verifyRuntime(Path workspace) throws IOException {
        Path root = workspace.toRealPath();
        if (!System.getProperty("java.runtime.version").equals(javaRuntimeVersion))
            throw new IOException("JVM runtime version differs from the recorded build");
        List<Path> registered = new ArrayList<>();
        for (var item : runtimeClasspath) {
            Path path = checkedPath(root, item.path());
            if (!path.toString().endsWith(".jar") || !ExperimentConfiguration.sha256(path).equals(item.sha256()))
                throw new IOException("Runtime JAR hash mismatch: " + path);
            registered.add(path);
        }
        String dependencyHash = ExperimentSpecIO.sha256(JSON.writeValueAsBytes(runtimeClasspath));
        if (!dependencyHash.equals(dependencySha256)) throw new IOException("Invalid dependency manifest digest");
        List<Path> live = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
            live.add(Path.of(entry).toRealPath());
        if (!registered.equals(live)) throw new IOException("Launch with exactly the recorded plain-Java classpath, not Maven exec or loose classes");
        try {
            Path loaded = Path.of(BuildIdentity.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!loaded.equals(registered.getFirst())) throw new IOException("Loaded application is not the recorded JAR");
        } catch (java.net.URISyntaxException e) { throw new IOException(e); }
        verifyManifestClasspath(registered);
    }

    /** Test seam for the manifest guard; callers supply the already verified canonical JAR paths. */
    static void verifyManifestClasspath(List<Path> registered) throws IOException {
        for (Path jar : registered) {
            try (JarFile archive = new JarFile(jar.toFile())) {
                var manifest = archive.getManifest();
                String extra = manifest == null ? null : manifest.getMainAttributes().getValue("Class-Path");
                if (extra == null || extra.isBlank()) continue;
                for (String entry : extra.strip().split("\\s+")) {
                    // JAR Class-Path entries are URLs relative to the containing JAR, not raw
                    // filesystem names. In particular, file: URLs and percent escapes must resolve
                    // to the same canonical local file that the JVM can actually load.
                    Path linked;
                    try {
                        URI uri = jar.toUri().resolve(new URI(entry));
                        if (!"file".equalsIgnoreCase(uri.getScheme()))
                            throw new IOException("Nonlocal manifest Class-Path entry: " + entry);
                        linked = Path.of(uri).normalize();
                    } catch (URISyntaxException | IllegalArgumentException invalid) {
                        throw new IOException("Invalid manifest Class-Path entry: " + entry, invalid);
                    }
                    // The core JAR's generated libs/* entries are deliberately absent after the
                    // freezer copies dependencies under hash-prefixed names. Missing local links
                    // cannot supply classes; every existing target must be in the verified list.
                    if (Files.exists(linked) && !registered.contains(linked.toRealPath()))
                        throw new IOException("Unrecorded manifest Class-Path entry: " + linked);
                }
            }
        }
    }

    private static Path checkedPath(Path workspace, String relative) throws IOException {
        Path path = workspace.resolve(relative).toRealPath();
        if (!path.startsWith(workspace)) throw new IOException("Path escapes workspace: " + path);
        return path;
    }

    private static String git(Path source, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-C", source.toString()));
        command.addAll(List.of(args));
        command.add(1, "--no-optional-locks");
        command.addAll(2, List.of("-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) throw new IOException("Git inspection failed: " + output);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        return output;
    }
}
