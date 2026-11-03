package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Strict JSON decoding: omitted primitive fields must never become implicit scientific defaults. */
public final class ExperimentSpecIO {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private ExperimentSpecIO() { }

    public static ExperimentSpec read(Path path) throws IOException {
        return JSON.readValue(path.toFile(), ExperimentSpec.class);
    }

    public static ExperimentSpec compose(Path profileDirectory, String blockId, ExperimentSpec.Arm arm) throws IOException {
        if (!blockId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid block ID");
        ObjectNode base = object(JSON.readTree(profileDirectory.resolve("base.json").toFile()));
        ObjectNode block = object(JSON.readTree(profileDirectory.resolve("blocks/" + blockId + ".json").toFile()));
        JsonNode armInputs = block.remove("armInputs");
        if (armInputs == null || !armInputs.has(arm.name())) throw new IllegalArgumentException("Missing block arm inputs");
        merge(base, block);
        merge(base, object(JSON.readTree(profileDirectory.resolve("arms/" + arm.name().toLowerCase(java.util.Locale.ROOT) + ".json").toFile())));
        merge(base, object(armInputs.get(arm.name())));
        ExperimentSpec result = JSON.treeToValue(base, ExperimentSpec.class);
        if (!result.blockId().equals(blockId) || result.arm() != arm) throw new IllegalArgumentException("Profile identity mismatch");
        return result;
    }

    public static byte[] canonicalBytes(ExperimentSpec spec) throws IOException {
        return JSON.writeValueAsBytes(spec);
    }

    public static String sha256(ExperimentSpec spec) throws IOException {
        return sha256(canonicalBytes(spec));
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static void writeNew(Path path, ExperimentSpec spec) throws IOException {
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(spec), StandardOpenOption.CREATE_NEW);
    }

    private static ObjectNode object(JsonNode node) {
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("Expected JSON object");
        return object;
    }
    private static void merge(ObjectNode target, ObjectNode overrides) {
        overrides.properties().forEach(entry -> {
            JsonNode prior = target.get(entry.getKey());
            if (prior instanceof ObjectNode existing && entry.getValue() instanceof ObjectNode replacement)
                merge(existing, replacement);
            else target.set(entry.getKey(), entry.getValue().deepCopy());
        });
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) throw new IllegalArgumentException("Usage: ExperimentSpecIO <workspace> <profile-dir> <new-output-directory> <comma-separated-blocks>");
        Path workspace = Path.of(args[0]).toRealPath();
        Path output = ExperimentRunner.safeOutputRoot(workspace, Path.of(args[2]));
        if (Files.exists(output)) throw new IOException("Refusing existing specification directory: " + output);
        var specs = new java.util.ArrayList<ExperimentSpec>();
        for (String block : args[3].split(",")) for (var arm : ExperimentSpec.Arm.values())
            specs.add(compose(Path.of(args[1]), block, arm));
        ExperimentRunner.validateMatrixSpecifications(specs);
        for (var spec : specs) ExperimentConfiguration.verifyInputs(spec, workspace);
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        for (var spec : specs) writeNew(output.resolve(spec.blockId() + "-" + spec.arm() + ".json"), spec);
        System.out.println("Wrote " + specs.size() + " complete specifications; no simulation executed: " + output);
    }
}
