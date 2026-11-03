package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.ExperimentSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Strict semantic gate for the seventh v2 definition: two COMPLETE exposed configuration trees,
 * before native initialization and after the bounded configuration-only initialization replay.
 * Only context and output are substituted, at two fixed exact paths under mandatory explicit rules.
 * Nothing is filtered: counts, ordered parameter sets, nulls and every other input/setting survive.
 * The self file reference is excluded from a separate specification binding to avoid a hash cycle;
 * its bytes are instead authenticated by the supplied spec. Output is derived from the caller's
 * new run root, not from a placeholder output path that could contain the spec hash.
 *
 * <p>This is a pre-start configuration gate, not a formal-run authorization or historical-M reuse
 * approval. It does not initialize a Controller, load a Scenario, inspect executing plans, certify
 * source/binaries/producers, or seal files/configuration. Successful input hashes are point-in-time
 * evidence. A caller must retain its independent runtime, pairing and result gates.</p>
 */
public final class DynamicMixedResolvedDefinition {
    public static final String SCHEMA = "ismt-dynamic-resolved-definition-v1";
    public static final String REPORT_SCHEMA = "ismt-dynamic-resolved-definition-verification-v1";
    public static final String CONTEXT_PATH = "/context";
    public static final String OUTPUT_PATH = "/modules/=controller/params/=outputDirectory";
    public static final String CONTEXT_TOKEN = "$CONFIG_CONTEXT$";
    public static final String OUTPUT_TOKEN = "$RUN_MATSIM_OUTPUT$";
    private static final String SELF_ROLE = "runtime.resolvedConfiguration";
    private static final ObjectMapper JSON = strictMapper();

    private DynamicMixedResolvedDefinition() { }

    public enum ContextRule { MATSIM_CONFIG_FILE_URL }
    public enum OutputRule { RUN_ROOT_MATSIM }
    public record BindingRules(ContextRule context, OutputRule outputDirectory) {
        public BindingRules { Objects.requireNonNull(context); Objects.requireNonNull(outputDirectory); }
    }
    public record TreeTemplate(String schemaVersion, Map<String, String> fields) {
        public TreeTemplate {
            if (!ResolvedNonDispatchConfiguration.SCHEMA.equals(schemaVersion))
                throw new IllegalArgumentException("Unsupported full configuration tree schema");
            Objects.requireNonNull(fields);
            var copy = new TreeMap<String, String>();
            fields.forEach((path, value) -> {
                if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("Invalid full-tree path");
                if (!CONTEXT_PATH.equals(path) && !OUTPUT_PATH.equals(path)
                        && value != null && (value.contains(CONTEXT_TOKEN) || value.contains(OUTPUT_TOKEN)))
                    throw new IllegalArgumentException("Derived binding token outside its exact path: " + path);
                copy.put(path, value);
            });
            if (!CONTEXT_TOKEN.equals(copy.get(CONTEXT_PATH)) || !OUTPUT_TOKEN.equals(copy.get(OUTPUT_PATH)))
                throw new IllegalArgumentException("Both exact derived paths must contain their mandatory tokens");
            fields = Collections.unmodifiableMap(copy); // Null is a real exposed config value, not absence.
        }
    }
    public record Definition(String schemaVersion, String experimentId, String blockId,
                             String specificationBindingSha256,
                             Map<String, ExperimentSpec.FileRef> inputFiles,
                             BindingRules bindingRules, TreeTemplate rawTree, TreeTemplate initializedTree) {
        public Definition {
            if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported resolved definition schema");
            if (Objects.requireNonNull(experimentId).isBlank() || Objects.requireNonNull(blockId).isBlank())
                throw new IllegalArgumentException("Missing experiment/block identity");
            if (!Objects.requireNonNull(specificationBindingSha256).matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid specification binding SHA-256");
            var copy = new TreeMap<String, ExperimentSpec.FileRef>();
            Objects.requireNonNull(inputFiles).forEach((role, ref) -> copy.put(Objects.requireNonNull(role), Objects.requireNonNull(ref)));
            if (copy.containsKey(SELF_ROLE)) throw new IllegalArgumentException("Definition cannot contain its own file reference");
            inputFiles = Collections.unmodifiableMap(copy);
            Objects.requireNonNull(bindingRules); Objects.requireNonNull(rawTree); Objects.requireNonNull(initializedTree);
        }
    }

    /**
     * Strictly decodes/authenticates the registered definition itself and binds its declarations to
     * this exact spec. All nineteen payloads are hashed in Bound.verify, so merely reading a definition
     * cannot yield an input-integrity or semantic-verification report. No development schema is accepted.
     */
    public static Bound read(Path workspace, DynamicMixedExperimentSpec spec) throws IOException {
        Objects.requireNonNull(spec);
        if (!SCHEMA.equals(spec.runtime().resolvedConfiguration().schemaVersion()))
            throw new IllegalArgumentException("A formal full resolved definition is required; development context is not supported");
        Path root = Objects.requireNonNull(workspace).toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Workspace must be a directory");
        var ref = spec.runtime().resolvedConfiguration().file();
        byte[] bytes = authenticatedBytes(root, ref);
        JsonNode tree = JSON.readTree(bytes);
        if (tree == null || !tree.isObject()) throw new IOException("Expected exactly one definition object");
        Definition definition = JSON.treeToValue(tree, Definition.class);
        if (!spec.experimentId().equals(definition.experimentId()) || !spec.blockId().equals(definition.blockId()))
            throw new IllegalArgumentException("Resolved definition belongs to another experiment/block");
        if (!specificationBindingSha256(spec).equals(definition.specificationBindingSha256()))
            throw new IllegalArgumentException("Resolved definition is bound to a different non-self specification");
        if (!nonSelfInputFiles(spec).equals(definition.inputFiles()))
            throw new IllegalArgumentException("Resolved definition requires the exact eighteen non-self input roles and FileRefs");
        return new Bound(root, spec, definition);
    }

    public static final class Bound {
        private final Path workspace;
        private final DynamicMixedExperimentSpec spec;
        private final Definition definition;
        private Bound(Path workspace, DynamicMixedExperimentSpec spec, Definition definition) {
            this.workspace = workspace; this.spec = spec; this.definition = definition;
        }
        public Definition definition() { return definition; }

        /**
         * liveRaw is only read. The supplier is called exactly twice and must return exclusively owned
         * fresh raw configurations; both are consumed by the existing bounded replay. Neither may be
         * liveRaw, share ANY configuration group with liveRaw, or share groups with the other copy.
         * The caller supplies the unique runRoot; only its not-yet-created child "matsim" is admitted.
         * The formal runner separately authenticates the runRoot's provenance/source/spec layout.
         */
        public Report verify(Config liveRaw, Supplier<Config> freshLoader, Path runRoot) throws IOException {
            Objects.requireNonNull(liveRaw); Objects.requireNonNull(freshLoader);
            Path output = derivedOutput(workspace, runRoot);
            String context = spec.inputs().matsimConfig().resolve(workspace).toFile().toURI().toURL().toExternalForm();
            Map<String, String> expectedRaw = substitute(definition.rawTree(), context, output.toString());
            Map<String, String> expectedInitialized = substitute(definition.initializedTree(), context, output.toString());
            // Not supplied by callers as a forgeable verification flag or unvalidated public record.
            var inputs = DynamicMixedInputVerifier.verify(workspace, spec);
            authenticatedBytes(workspace, spec.runtime().resolvedConfiguration().file());
            var raw = ResolvedNonDispatchConfiguration.snapshot(liveRaw);
            requireEqual(expectedRaw, raw.fields(), "Registered raw full tree mismatch");
            // A self-consistent wrong template + arbitrary matching suppliers must not detach the
            // declared FileRefs, phases, seeds or admission settings from the native assembly. This
            // is only the existing configuration adapter (no payload population/Scenario loading).
            Config canonicalAssembly = ExperimentConfiguration.create(DynamicMixedConfiguration.commonRequest(spec), workspace, output);
            requireEqual(ResolvedNonDispatchConfiguration.snapshot(canonicalAssembly).fields(), raw.fields(),
                    "Live raw configuration differs from exact spec/input-bound common assembly");
            DynamicMixedConfiguration.requireRuntimeDeclaration(liveRaw, spec.runtime());
            Config first = Objects.requireNonNull(freshLoader.get(), "First fresh configuration");
            Config second = Objects.requireNonNull(freshLoader.get(), "Second fresh configuration");
            requireNoSharedGroups(liveRaw, first); requireNoSharedGroups(liveRaw, second);
            requireEqual(raw.fields(), ResolvedNonDispatchConfiguration.snapshot(liveRaw).fields(), "Live configuration changed by loader");
            requireEqual(raw.fields(), ResolvedNonDispatchConfiguration.snapshot(first).fields(), "First fresh raw tree mismatch");
            requireEqual(raw.fields(), ResolvedNonDispatchConfiguration.snapshot(second).fields(), "Second fresh raw tree mismatch");
            var initialized = ControllerInitializationConfigurationAudit.compareFresh(() -> first, () -> second);
            if (!initialized.rawStrictlyEqual() || !initialized.initializedStrictlyEqual())
                throw new IllegalStateException("Fresh initialization copies differ");
            requireEqual(expectedInitialized, initialized.current().initialized().fields(), "Registered initialized full tree mismatch");
            requireEqual(raw.fields(), ResolvedNonDispatchConfiguration.snapshot(liveRaw).fields(), "Live configuration changed during audit");
            // Only small inputs are rehashed after the bounded replay; the nineteen-input receipt
            // states its earlier read interval, not a filesystem seal covering later execution.
            requireUnchangedSmallSource(spec.inputs().matsimConfig(), inputs);
            requireUnchangedSmallSource(spec.runtime().resolvedConfiguration().file(), inputs);
            if (!output.equals(derivedOutput(workspace, runRoot))) throw new IOException("Output binding changed during audit");
            return new Report(spec.experimentId(), spec.blockId(), DynamicMixedExperimentSpecIO.sha256(spec),
                    spec.runtime().resolvedConfiguration().file(), definition.specificationBindingSha256(),
                    context, output.toString(), inputs, raw, initialized);
        }

        private void requireUnchangedSmallSource(ExperimentSpec.FileRef ref, DynamicMixedInputVerifier.Verification inputs) throws IOException {
            authenticatedBytes(workspace, ref);
            Path real = ref.resolve(workspace).toRealPath();
            for (var entry : inputs.files()) if (entry.declaredPath().equals(ref.path())
                    && !workspace.resolve(entry.realWorkspaceRelativePath()).equals(real))
                throw new IOException("Small configuration source alias changed during semantic verification: " + ref.path());
        }
    }

    /** Created only after complete successful verification; contains no mutable Config/native objects. */
    public static final class Report {
        private final String experimentId, blockId, specSha256, specificationBindingSha256, context, outputDirectory;
        private final ExperimentSpec.FileRef definitionFile;
        private final DynamicMixedInputVerifier.Verification inputs;
        private final ControllerInitializationConfigurationAudit.Tree raw;
        private final ControllerInitializationConfigurationAudit.Report initialization;
        private Report(String experimentId, String blockId, String specSha256, ExperimentSpec.FileRef definitionFile,
                       String specificationBindingSha256, String context, String outputDirectory,
                       DynamicMixedInputVerifier.Verification inputs, ResolvedNonDispatchConfiguration.Snapshot raw,
                       ControllerInitializationConfigurationAudit.Report initialization) {
            this.experimentId = experimentId; this.blockId = blockId; this.specSha256 = specSha256;
            this.definitionFile = definitionFile; this.specificationBindingSha256 = specificationBindingSha256;
            this.context = context; this.outputDirectory = outputDirectory; this.inputs = inputs;
            this.raw = new ControllerInitializationConfigurationAudit.Tree(raw.schemaVersion(), raw.sha256(), raw.fields());
            this.initialization = initialization;
        }
        @JsonProperty public String schemaVersion() { return REPORT_SCHEMA; }
        @JsonProperty public String experimentId() { return experimentId; }
        @JsonProperty public String blockId() { return blockId; }
        @JsonProperty public String specSha256() { return specSha256; }
        @JsonProperty public ExperimentSpec.FileRef definitionFile() { return definitionFile; }
        @JsonProperty public String specificationBindingSha256() { return specificationBindingSha256; }
        @JsonProperty public String context() { return context; }
        @JsonProperty public String outputDirectory() { return outputDirectory; }
        @JsonProperty public DynamicMixedInputVerifier.Verification inputVerification() { return inputs; }
        @JsonProperty public ControllerInitializationConfigurationAudit.Tree rawSnapshot() { return raw; }
        @JsonProperty public ControllerInitializationConfigurationAudit.Report initializationAudit() { return initialization; }
        @JsonProperty public boolean registeredResolvedDefinitionSemanticallyVerified() { return true; }
        @JsonProperty public boolean actualControllerLifecycleVerified() { return false; }
        @JsonProperty public boolean sourceBuildRuntimeCompatibilityVerified() { return false; }
        @JsonProperty public boolean historicalReuseApproved() { return false; }
        @JsonProperty public boolean formalRunAuthorized() { return false; }
        @JsonProperty public boolean simulationStarted() { return false; }
        @JsonProperty public boolean configurationOrInputsSealed() { return false; }
    }

    /** Exact declared paths and digests, not merely equality of resolved payload bytes. */
    public static Map<String, ExperimentSpec.FileRef> nonSelfInputFiles(DynamicMixedExperimentSpec spec) {
        var values = new TreeMap<>(Objects.requireNonNull(spec).allReferencedFiles());
        if (values.remove(SELF_ROLE) == null || values.size() != 18)
            throw new IllegalArgumentException("Expected exactly nineteen v2 input roles including the self definition");
        return Collections.unmodifiableMap(values);
    }

    /**
     * SHA-256 of UTF-8 (SCHEMA + "\nnon-self-spec-v1\n") followed by the existing canonical spec JSON
     * with exactly runtime.resolvedConfiguration.file removed (not the schema or any other field).
     * Changes to self path/hash alone do not change this digest; every other spec field remains bound.
     */
    public static String specificationBindingSha256(DynamicMixedExperimentSpec spec) throws IOException {
        ObjectNode tree = (ObjectNode) JSON.readTree(DynamicMixedExperimentSpecIO.canonicalBytes(Objects.requireNonNull(spec)));
        ObjectNode self = (ObjectNode) tree.path("runtime").path("resolvedConfiguration");
        if (self.remove("file") == null) throw new IllegalArgumentException("Missing self reference");
        MessageDigest digest = sha256();
        digest.update((SCHEMA + "\nnon-self-spec-v1\n").getBytes(StandardCharsets.UTF_8));
        digest.update(JSON.writeValueAsBytes(sortedObjects(tree)));
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Deterministic object-key order; writes no implied approval and does not derive/adopt a template. */
    public static byte[] canonicalBytes(Definition definition) throws IOException {
        return JSON.writeValueAsBytes(sortedObjects(JSON.valueToTree(Objects.requireNonNull(definition))));
    }
    public static void writeNew(Path path, Definition definition) throws IOException {
        Files.write(Objects.requireNonNull(path), canonicalBytes(definition), StandardOpenOption.CREATE_NEW);
    }

    private static Map<String, String> substitute(TreeTemplate tree, String context, String output) {
        var fields = new TreeMap<>(tree.fields());
        fields.put(CONTEXT_PATH, context); fields.put(OUTPUT_PATH, output);
        return fields;
    }
    private static void requireEqual(Map<String, String> expected, Map<String, String> actual, String message) {
        if (expected.equals(actual)) return;
        var paths = new TreeSet<>(expected.keySet()); paths.addAll(actual.keySet());
        for (String path : paths) if (expected.containsKey(path) != actual.containsKey(path)
                || !Objects.equals(expected.get(path), actual.get(path)))
            throw new IllegalArgumentException(message + ": " + path + " expected="
                    + new ResolvedNonDispatchConfiguration.Value(expected.containsKey(path), expected.get(path))
                    + " actual=" + new ResolvedNonDispatchConfiguration.Value(actual.containsKey(path), actual.get(path)));
        throw new IllegalStateException(message);
    }
    private static Path derivedOutput(Path workspace, Path runRoot) throws IOException {
        Objects.requireNonNull(runRoot);
        if (!runRoot.isAbsolute() || !runRoot.equals(runRoot.normalize()) || !runRoot.startsWith(workspace)
                || runRoot.equals(workspace)) throw new IllegalArgumentException("Require a normalized contained non-root run directory");
        Path ancestor = runRoot;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null || !Files.isDirectory(ancestor) || !ancestor.toRealPath().equals(ancestor))
            throw new IOException("Run output ancestry must use real, non-aliased workspace paths");
        Path output = runRoot.resolve("matsim");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Require a new derived matsim output directory");
        return output;
    }
    private static byte[] authenticatedBytes(Path workspace, ExperimentSpec.FileRef ref) throws IOException {
        Path declared = ref.resolve(workspace); Path real = declared.toRealPath();
        if (!real.startsWith(workspace) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Definition/config source must be a contained regular file: " + ref.path());
        byte[] bytes = Files.readAllBytes(real);
        if (!ref.sha256().equals(HexFormat.of().formatHex(sha256().digest(bytes)))
                || !real.equals(declared.toRealPath()) || !ref.sha256().equals(ExperimentConfiguration.sha256(real)))
            throw new IOException("Definition/config source changed or hash mismatched: " + ref.path());
        return bytes;
    }
    private static void requireNoSharedGroups(Config live, Config fresh) {
        if (live == fresh) throw new IllegalArgumentException("Fresh loader returned live configuration");
        var liveGroups = new IdentityHashMap<ConfigGroup, Boolean>();
        var freshGroups = new IdentityHashMap<ConfigGroup, Boolean>();
        live.getModules().values().forEach(group -> collect(group, liveGroups));
        fresh.getModules().values().forEach(group -> collect(group, freshGroups));
        for (var group : freshGroups.keySet()) if (liveGroups.containsKey(group))
            throw new IllegalArgumentException("Fresh loader shares a configuration group with the live configuration");
    }
    private static void collect(ConfigGroup group, IdentityHashMap<ConfigGroup, Boolean> values) {
        if (values.put(group, Boolean.TRUE) != null) return;
        group.getParameterSets().values().forEach(groups -> groups.forEach(nested -> collect(nested, values)));
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonNode sortedObjects(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>(); node.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            var object = JSON.createObjectNode(); sorted.forEach((key, value) -> object.set(key, sortedObjects(value))); return object;
        }
        if (node.isArray()) { var array = JSON.createArrayNode(); node.forEach(value -> array.add(sortedObjects(value))); return array; }
        return node;
    }
    private static ObjectMapper strictMapper() {
        ObjectMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT, DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                        DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS, DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT,
                        DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL,
                        DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS, MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS,
                        MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        for (var shape : new CoercionInputShape[]{CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean})
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Enum).setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        return mapper;
    }
}
