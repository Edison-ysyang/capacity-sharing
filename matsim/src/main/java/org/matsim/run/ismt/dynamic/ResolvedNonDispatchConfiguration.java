package org.matsim.run.ismt.dynamic;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Complete comparison of the exposed resolved configuration tree, with NO module or parameter
 * blacklist. Names/parameters/types are sorted; the order of sets within each type is preserved,
 * including nested and empty sets. Context is included because it can change relative input meaning.
 * Comments, object identities and lock flags are not configuration values. This is neither an actual
 * injected-runtime inspection nor proof of source compatibility, historical reuse or run approval.
 * The digest identifies only this exposed tree, not all scientific inputs or executable code.
 */
public final class ResolvedNonDispatchConfiguration {
    public static final String SCHEMA = "ismt-resolved-config-tree-v1";
    private ResolvedNonDispatchConfiguration() { }

    /** Distinguishes absent fields, present Java null, and the literal string "null". */
    public record Value(boolean present, String text) {
        public Value {
            if (!present && text != null) throw new IllegalArgumentException("Absent value cannot have text");
        }
    }

    /** Explanations are annotations, NOT permissions or automatic equivalence assertions. */
    public record ExactDifferenceExplanation(String path, Value historical, Value target, String reason) {
        public ExactDifferenceExplanation {
            if (path == null || !path.startsWith("/") || reason == null || reason.isBlank())
                throw new IllegalArgumentException("Require one exact path and a nonblank explanation");
            Objects.requireNonNull(historical); Objects.requireNonNull(target);
            if (historical.equals(target)) throw new IllegalArgumentException("An explanation must describe a difference");
        }
    }

    public record Difference(String path, Value historical, Value target, String explanation) { }

    public static final class Snapshot {
        private final Map<String, String> fields;
        private final String sha256;
        private Snapshot(Map<String, String> values) {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            sha256 = digest(fields);
        }
        public String schemaVersion() { return SCHEMA; }
        public Map<String, String> fields() { return fields; }
        public String sha256() { return sha256; }
    }

    public static final class Comparison {
        private final String historicalSha256;
        private final String targetSha256;
        private final List<Difference> differences;
        private Comparison(Snapshot historical, Snapshot target, List<Difference> differences) {
            historicalSha256 = historical.sha256(); targetSha256 = target.sha256();
            this.differences = List.copyOf(differences);
        }
        public String historicalSha256() { return historicalSha256; }
        public String targetSha256() { return targetSha256; }
        public List<Difference> differences() { return differences; }
        public boolean strictlyEqual() { return differences.isEmpty(); }
        public List<Difference> unexplainedDifferences() {
            return differences.stream().filter(value -> value.explanation() == null).toList();
        }
    }

    /** Two reads detect visible tree drift; they do not seal a mutable Config or its source files. */
    public static Snapshot snapshot(Config config) {
        Objects.requireNonNull(config);
        Map<String, String> first = read(config);
        if (!first.equals(read(config))) throw new IllegalStateException("Resolved configuration changed while being read");
        return new Snapshot(first);
    }

    public static Comparison compare(Snapshot historical, Snapshot target) {
        return compare(historical, target, List.of());
    }

    /** Every supplied explanation must exactly match a real difference; wildcards/stale entries fail. */
    public static Comparison compare(Snapshot historical, Snapshot target, List<ExactDifferenceExplanation> explanations) {
        Objects.requireNonNull(historical); Objects.requireNonNull(target); Objects.requireNonNull(explanations);
        Map<String, ExactDifferenceExplanation> byPath = new LinkedHashMap<>();
        for (var explanation : explanations) {
            Objects.requireNonNull(explanation);
            if (byPath.putIfAbsent(explanation.path(), explanation) != null)
                throw new IllegalArgumentException("Duplicate explanation path: " + explanation.path());
        }
        var keys = new TreeSet<>(historical.fields().keySet()); keys.addAll(target.fields().keySet());
        List<Difference> differences = new ArrayList<>();
        for (String path : keys) {
            Value oldValue = value(historical, path); Value newValue = value(target, path);
            if (oldValue.equals(newValue)) continue;
            var explanation = byPath.remove(path);
            if (explanation != null && (!oldValue.equals(explanation.historical()) || !newValue.equals(explanation.target())))
                throw new IllegalArgumentException("Explanation values do not match: " + path);
            differences.add(new Difference(path, oldValue, newValue, explanation == null ? null : explanation.reason()));
        }
        if (!byPath.isEmpty()) throw new IllegalArgumentException("Explanations do not identify exact changed fields: " + byPath.keySet());
        return new Comparison(historical, target, differences);
    }

    private static Value value(Snapshot snapshot, String path) {
        return new Value(snapshot.fields().containsKey(path), snapshot.fields().get(path));
    }

    private static Map<String, String> read(Config config) {
        Map<String, String> values = new TreeMap<>();
        values.put("/context", config.getContext() == null ? null : config.getContext().toExternalForm());
        var modules = new TreeMap<>(config.getModules());
        values.put("/modules/@count", Integer.toString(modules.size()));
        var ancestors = new IdentityHashMap<ConfigGroup, Boolean>();
        modules.forEach((name, group) -> readGroup("/modules/" + escape(name), group, values, ancestors));
        return values;
    }

    private static void readGroup(String path, ConfigGroup group, Map<String, String> values,
                                  IdentityHashMap<ConfigGroup, Boolean> ancestors) {
        Objects.requireNonNull(group, "Null configuration group");
        if (ancestors.put(group, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic configuration group: " + path);
        try {
            values.put(path + "/@name", Objects.requireNonNull(group.getName()));
            var params = new TreeMap<>(group.getParams());
            values.put(path + "/params/@count", Integer.toString(params.size()));
            params.forEach((name, value) -> values.put(path + "/params/" + escape(name), value));
            var sets = group.getParameterSets();
            values.put(path + "/parameterSets/@count", Integer.toString(sets.size()));
            for (String type : new TreeSet<>(sets.keySet())) {
                var children = new ArrayList<>(sets.get(type));
                String setPath = path + "/parameterSets/" + escape(type);
                values.put(setPath + "/@count", Integer.toString(children.size()));
                for (int index = 0; index < children.size(); index++)
                    readGroup(setPath + "/" + index, children.get(index), values, ancestors);
            }
        } finally { ancestors.remove(group); }
    }

    // Prefix the encoded names to distinguish literal metadata names such as @count from structural fields.
    private static String escape(String value) {
        return "=" + Objects.requireNonNull(value).replace("~", "~0").replace("/", "~1");
    }

    private static String digest(Map<String, String> fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            token(digest, SCHEMA);
            fields.forEach((key, value) -> { token(digest, key); token(digest, value); });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void token(MessageDigest digest, String value) {
        if (value == null) { digest.update((byte) 0); return; }
        digest.update((byte) 1);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':'); digest.update(bytes);
    }
}
