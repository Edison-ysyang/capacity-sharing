package org.matsim.run.ismt.dynamic;

import org.matsim.run.ismt.ExperimentSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only verification of all declared scientific file bytes, deduplicated only
 * by resolved real path, never merely by a claimed hash. Detects visible metadata
 * changes during the read/check window. This is a point-in-time integrity check,
 * not filesystem sealing, semantic decoding, feasibility, approval or permission
 * to use an unreviewed policy/definition. No files or directories are created.
 */
public final class DynamicMixedInputVerifier {
    private DynamicMixedInputVerifier() { }

    public record VerifiedFile(String role, String declaredPath, String realWorkspaceRelativePath,
                               long bytes, String sha256) { }
    public record Verification(List<VerifiedFile> files, int uniqueFiles, long uniqueBytesRead) {
        public Verification { files = List.copyOf(files); }
    }
    private record Payload(BasicFileAttributes attributes, long bytes, String sha256) { }

    public static Verification verify(Path workspace, DynamicMixedExperimentSpec spec) throws IOException {
        Path root = workspace.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Workspace must be a directory");
        Map<String, ExperimentSpec.FileRef> references = Objects.requireNonNull(spec).allReferencedFiles();
        Map<Path, Payload> loaded = new LinkedHashMap<>();
        List<VerifiedFile> result = new ArrayList<>();
        long total = 0;
        for (var entry : references.entrySet()) {
            Path real = resolve(root, entry.getValue());
            Payload payload = loaded.get(real);
            if (payload == null) {
                payload = read(real);
                loaded.put(real, payload);
                total = Math.addExact(total, payload.bytes());
            } else requireUnchanged(real, payload.attributes());
            if (!payload.sha256().equals(entry.getValue().sha256()))
                throw new IOException("Declared file hash mismatch: " + entry.getKey() + " " + entry.getValue().path());
            result.add(new VerifiedFile(entry.getKey(), entry.getValue().path(), root.relativize(real).toString(),
                    payload.bytes(), payload.sha256()));
        }
        // Recheck every alias and every payload after the complete batch, not only after its own read.
        for (var entry : references.entrySet()) {
            Path real = resolve(root, entry.getValue());
            Payload payload = loaded.get(real);
            if (payload == null) throw new IOException("Declared input was retargeted during verification: " + entry.getKey());
            VerifiedFile prior = result.stream().filter(value -> value.role().equals(entry.getKey())).findFirst().orElseThrow();
            if (!root.resolve(prior.realWorkspaceRelativePath()).equals(real))
                throw new IOException("Declared input alias changed during verification: " + entry.getKey());
            requireUnchanged(real, payload.attributes());
        }
        return new Verification(result, loaded.size(), total);
    }

    private static Path resolve(Path root, ExperimentSpec.FileRef ref) throws IOException {
        Path real = ref.resolve(root).toRealPath();
        if (!real.startsWith(root)) throw new IOException("Declared input escapes workspace: " + ref.path());
        return real;
    }

    private static Payload read(Path path) throws IOException {
        BasicFileAttributes before = attributes(path);
        if (!before.isRegularFile()) throw new IOException("Declared input is not a regular file: " + path);
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        long bytes = 0;
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int count; (count = stream.read(buffer)) != -1;) {
                if (count == 0) continue;
                digest.update(buffer, 0, count); bytes = Math.addExact(bytes, count);
            }
        }
        requireUnchanged(path, before);
        if (bytes != before.size()) throw new IOException("Input length changed during verification: " + path);
        return new Payload(before, bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireUnchanged(Path path, BasicFileAttributes before) throws IOException {
        var after = attributes(path);
        if (!after.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !before.creationTime().equals(after.creationTime())
                || !Objects.equals(before.fileKey(), after.fileKey()))
            throw new IOException("Input metadata changed during verification: " + path);
    }
}
