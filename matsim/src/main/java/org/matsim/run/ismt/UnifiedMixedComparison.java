package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.matsim.run.ismt.dynamic.DynamicMixedDesign;
import org.matsim.run.ismt.dynamic.DynamicMixedExperimentRunner;
import org.matsim.run.ismt.dynamic.DynamicMixedExperimentSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Pure declared-value comparison for one explicit windowed M and one explicit T.
 * Only design.arm, design.controlEnabled and the path aliases of exact FileRef
 * objects are excluded. All other fields, including output settings, identifiers,
 * definition schemas and all nineteen file digests, participate in comparison.
 *
 * <p>This class never reads paths, authenticates bytes, prepares a runtime, invokes
 * a forecast or launches a simulation. Equal declared SHA-256 values are not proof
 * that either path contains those bytes. Callers must separately verify both
 * specifications' referenced inputs and the selected runtime before execution.
 */
public final class UnifiedMixedComparison {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private UnifiedMixedComparison() { }

    /** Mismatch paths are deterministic JSON Pointers into the declared objects. */
    public record Comparison(boolean declaredEqual, boolean inputBytesVerified,
                             boolean runtimeVerified, List<String> mismatchPaths) {
        public Comparison {
            mismatchPaths = List.copyOf(Objects.requireNonNull(mismatchPaths, "mismatch paths"));
            if (declaredEqual != mismatchPaths.isEmpty())
                throw new IllegalArgumentException("Declared equality must agree with mismatch paths");
            if (inputBytesVerified || runtimeVerified)
                throw new IllegalArgumentException("A declaration comparison cannot certify input bytes or runtime");
        }
    }

    /** Argument order is immaterial; unsupported or non-M/T pairs are rejected. */
    public static Comparison compare(DynamicMixedExperimentSpec left, DynamicMixedExperimentSpec right) {
        Objects.requireNonNull(left, "left specification");
        Objects.requireNonNull(right, "right specification");
        DynamicMixedExperimentRunner.requireWindowedMixedSpec(left);
        DynamicMixedExperimentRunner.requireWindowedMixedSpec(right);
        var leftArm = left.design().arm();
        var rightArm = right.design().arm();
        if (!((leftArm == DynamicMixedDesign.Arm.MIXED && rightArm == DynamicMixedDesign.Arm.DYNAMIC_MIXED)
                || (leftArm == DynamicMixedDesign.Arm.DYNAMIC_MIXED && rightArm == DynamicMixedDesign.Arm.MIXED)))
            throw new IllegalArgumentException("Require exactly one MIXED (M) and one DYNAMIC_MIXED (T) declaration");

        JsonNode leftTree = comparable(JSON.valueToTree(left), "");
        JsonNode rightTree = comparable(JSON.valueToTree(right), "");
        List<String> mismatches = new ArrayList<>();
        collectDifferences(leftTree, rightTree, "", mismatches);
        return new Comparison(mismatches.isEmpty(), false, false, mismatches);
    }

    /** A fail-closed declaration gate, not a replacement for actual input verification. */
    public static Comparison requireCompatible(DynamicMixedExperimentSpec left, DynamicMixedExperimentSpec right) {
        Comparison result = compare(left, right);
        if (!result.declaredEqual())
            throw new IllegalArgumentException("M/T declarations differ beyond the permitted control switch and "
                    + "FileRef path aliases: " + String.join(", ", result.mismatchPaths()));
        return result;
    }

    private static JsonNode comparable(JsonNode node, String pointer) {
        if (node.isObject()) {
            var result = NODES.objectNode();
            // Do not erase arbitrary fields named 'path', or metadata attached to
            // a larger object. Typed FileRef is exactly these two textual fields.
            boolean fileRef = node.size() == 2 && node.has("path") && node.has("sha256")
                    && node.get("path").isTextual() && node.get("sha256").isTextual();
            var fields = new TreeSet<String>();
            node.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (fileRef && field.equals("path")) continue;
                if (pointer.equals("/design") && (field.equals("arm") || field.equals("controlEnabled"))) continue;
                result.set(field, comparable(node.get(field), child(pointer, field)));
            }
            return result;
        }
        if (node.isArray()) {
            var result = NODES.arrayNode();
            for (int index = 0; index < node.size(); index++)
                result.add(comparable(node.get(index), child(pointer, Integer.toString(index))));
            return result;
        }
        return node.deepCopy();
    }

    private static void collectDifferences(JsonNode left, JsonNode right, String pointer, List<String> differences) {
        if (Objects.equals(left, right)) return;
        if (left != null && right != null && left.isObject() && right.isObject()) {
            var fields = new TreeSet<String>();
            left.fieldNames().forEachRemaining(fields::add);
            right.fieldNames().forEachRemaining(fields::add);
            for (String field : fields)
                collectDifferences(left.get(field), right.get(field), child(pointer, field), differences);
        } else if (left != null && right != null && left.isArray() && right.isArray()) {
            for (int index = 0; index < Math.max(left.size(), right.size()); index++)
                collectDifferences(left.get(index), right.get(index), child(pointer, Integer.toString(index)), differences);
        } else {
            differences.add(pointer);
        }
    }

    private static String child(String pointer, String field) {
        return pointer + "/" + field.replace("~", "~0").replace("/", "~1");
    }
}
