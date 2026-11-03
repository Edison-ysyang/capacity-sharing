package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Strict, single-object v2 specification IO; never composes profiles, fills defaults,
 * verifies input payloads, or invokes a simulation. This is independent of legacy IO.
 *
 * <p>Canonical bytes are compact UTF-8 JSON with every object key sorted and every
 * list kept in its declared order. They describe the decoded typed values, not the
 * original input's whitespace or numeric spelling. {@link #writeNew} writes these
 * exact bytes, so its file digest equals {@link #sha256}. This is a versioned local
 * encoding, not a claim of RFC 8785 or arbitrary-JSON canonicalization.</p>
 */
public final class DynamicMixedExperimentSpecIO {
    private static final ObjectMapper JSON = strictMapper();

    private DynamicMixedExperimentSpecIO() { }

    private static ObjectMapper strictMapper() {
        ObjectMapper mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .disable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
                .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .disable(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT)
                .disable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .disable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        // ALLOW_COERCION_OF_SCALARS alone does not define number/boolean -> String.
        for (CoercionInputShape input : new CoercionInputShape[]{CoercionInputShape.Integer,
                CoercionInputShape.Float, CoercionInputShape.Boolean}) {
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(input, CoercionAction.Fail);
        }
        mapper.coercionConfigFor(LogicalType.Enum).setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        return mapper;
    }

    /** Reads one complete object. File/path and external payload approval are caller concerns. */
    public static DynamicMixedExperimentSpec read(Path path) throws IOException {
        JsonNode root = JSON.readTree(Files.readAllBytes(Objects.requireNonNull(path, "path")));
        if (root == null || !root.isObject()) throw new IOException("Expected one non-null v2 specification JSON object");
        return JSON.treeToValue(root, DynamicMixedExperimentSpec.class);
    }

    public static byte[] canonicalBytes(DynamicMixedExperimentSpec spec) throws IOException {
        Objects.requireNonNull(spec, "spec");
        return JSON.writeValueAsBytes(sortedObjects(JSON.valueToTree(spec)));
    }

    public static String sha256(DynamicMixedExperimentSpec spec) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes(spec)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** No overwrite, implicit directory creation, pretty-printing or trailing newline. */
    public static void writeNew(Path path, DynamicMixedExperimentSpec spec) throws IOException {
        Files.write(Objects.requireNonNull(path, "path"), canonicalBytes(spec), StandardOpenOption.CREATE_NEW);
    }

    private static JsonNode sortedObjects(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            var object = JSON.createObjectNode();
            sorted.forEach((key, value) -> object.set(key, sortedObjects(value)));
            return object;
        }
        if (node.isArray()) {
            var array = JSON.createArrayNode();
            node.forEach(value -> array.add(sortedObjects(value)));
            return array;
        }
        return node;
    }
}
