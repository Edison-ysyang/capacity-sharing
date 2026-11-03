package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.commons.csv.CSVFormat;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ExperimentSpecIO;
import org.matsim.run.ismt.IsmtRunArtifactWriter;
import org.matsim.run.ismt.PassengerPlanFingerprint;
import org.matsim.run.ismt.PassengerScientificFingerprint;
import org.matsim.run.ismt.ScientificRunContext;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed comparison of a newly executed T history (0--19) with one explicitly
 * bound, completed legacy M run. This neither authorizes reuse nor verifies the
 * build, full resolved configuration or the validity of post-activation results.
 * Existing scientific fingerprint algorithms are consumed unchanged; this class
 * never reorders events, plans, CSV rows or scientific fields to make them match.
 *
 * <p>Install alongside the actual v2 artifact writers, not instead of them. The
 * ordinary end listener (-1500) follows the summary writer (-1000); the start
 * listener (2000) precedes CoTransportPhaseController (1000), including activation
 * at 20. MATSim dispatches ordinary listeners in descending priority. Core
 * listeners run separately first; this guard makes no claim to precede those.</p>
 */
public final class DynamicMixedHistoricalHistoryGuard {
    public static final int ACTIVATION_ITERATION = 20;
    public static final String SCOPE = "DECLARED_PAIRING_AND_PRE_ACTIVATION_HISTORY";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String EVENT_FILE = PassengerScientificFingerprint.FILE_NAME;
    private static final String PLAN_FILE = PassengerPlanFingerprint.FILE_NAME;
    private static final String SUMMARY_FILE = "iteration_summary.csv";
    private static final List<String> EVENT_COLUMNS = List.of("experiment_id", "block_id", "arm", "iteration",
            "event_count", "event_sha256", "schema_version");
    private static final List<String> PLAN_COLUMNS = List.of("schema_version", "experiment_id", "block_id", "arm",
            "iteration", "passenger_count", "plan_sha256");
    private static final Set<String> ENVELOPE = Set.of("schema_version", "experiment_id", "block_id", "arm", "design_family");

    /** Hashes are supplied by the caller, including the receipt's own external hash. */
    public record HistoricalFiles(ExperimentSpec.FileRef spec, ExperimentSpec.FileRef receipt,
                                  ExperimentSpec.FileRef eventFingerprint, ExperimentSpec.FileRef planFingerprint,
                                  ExperimentSpec.FileRef summary) {
        public HistoricalFiles {
            Objects.requireNonNull(spec); Objects.requireNonNull(receipt); Objects.requireNonNull(eventFingerprint);
            Objects.requireNonNull(planFingerprint); Objects.requireNonNull(summary);
        }
        public List<ExperimentSpec.FileRef> all() { return List.of(spec, receipt, eventFingerprint, planFingerprint, summary); }
    }

    public record IterationCheck(int iteration, Map<String, String> currentFileSha256) {
        public IterationCheck { currentFileSha256 = Map.copyOf(currentFileSha256); }
    }

    /** Serialization-safe copy of the existing bounded declaration report, without upgrading its scope. */
    public record DeclaredPairingEvidence(DynamicMixedDeclaredPairing.Scope scope,
            DynamicMixedDeclaredPairing.Identity historicalIdentity, DynamicMixedDeclaredPairing.Identity targetIdentity,
            DynamicMixedDesign targetDesign, DynamicMixedExperimentSpec.ControlRegistration targetControlRegistration,
            List<String> checkedFields, List<DynamicMixedDeclaredPairing.Difference> differences,
            List<DynamicMixedDeclaredPairing.InputPathDifference> inputPathDifferences, boolean declaredFieldsMatch,
            List<DynamicMixedDeclaredPairing.UnverifiedAspect> unverifiedAspects) {
        public DeclaredPairingEvidence {
            checkedFields = List.copyOf(checkedFields); differences = List.copyOf(differences);
            inputPathDifferences = List.copyOf(inputPathDifferences); unverifiedAspects = List.copyOf(unverifiedAspects);
        }
        private static DeclaredPairingEvidence copyOf(DynamicMixedDeclaredPairing.Report report) {
            return new DeclaredPairingEvidence(report.scope(), report.historicalIdentity(), report.targetIdentity(),
                    report.targetDesign(), report.targetControlRegistration(), report.checkedFields(), report.differences(),
                    report.inputPathDifferences(), report.declaredFieldsMatch(), report.unverifiedAspects());
        }
    }

    /** A bounded evidence report, deliberately without a reusable/canRun/valid flag. */
    public record Report(String scope, HistoricalFiles historicalFiles,
                         DeclaredPairingEvidence declaredPairing, String historicalReceiptStatus,
                         boolean historicalMatrixValidated, List<IterationCheck> checkedIterations,
                         boolean activationHistoryCheckPassed, boolean poisoned, String failure,
                         List<String> stillUnverified) {
        public Report { checkedIterations = List.copyOf(checkedIterations); stillUnverified = List.copyOf(stillUnverified); }
    }

    private final Path workspace;
    private final DynamicMixedExperimentSpec target;
    private final HistoricalFiles files;
    private final DynamicMixedDeclaredPairing.Report pairing;
    private final boolean historicalMatrixValidated;
    private final Map<String, Table> historical;
    private final List<IterationCheck> checked = new ArrayList<>();
    private Path currentOutput;
    private int started = -1;
    private boolean installed, activationPassed, poisoned;
    private String failure = "";

    private DynamicMixedHistoricalHistoryGuard(Path workspace, DynamicMixedExperimentSpec target,
            HistoricalFiles files, DynamicMixedDeclaredPairing.Report pairing, boolean matrixValidated,
            Map<String, Table> historical) {
        this.workspace = workspace; this.target = target; this.files = files; this.pairing = pairing;
        this.historicalMatrixValidated = matrixValidated; this.historical = Map.copyOf(historical);
    }

    public static DynamicMixedHistoricalHistoryGuard load(Path workspace, DynamicMixedExperimentSpec target,
            HistoricalFiles files) throws IOException {
        Objects.requireNonNull(target); Objects.requireNonNull(files);
        Path root = workspace.toRealPath();
        if (target.design().protocol().firstIteration() != 0
                || target.design().protocol().activationIteration() != ACTIVATION_ITERATION)
            throw new IllegalArgumentException("Historical formal gate requires target iterations 0--19 before activation 20");
        var contents = new LinkedHashMap<ExperimentSpec.FileRef, byte[]>();
        for (var ref : files.all()) contents.put(ref, readBound(root, ref));
        // Separately reject malformed roots/trailing tokens; legacy canonical SHA stays exactly legacy.
        object(JSON.readTree(contents.get(files.spec())), "historical spec");
        ExperimentSpec old = ExperimentSpecIO.read(resolve(root, files.spec()));
        var pairing = DynamicMixedDeclaredPairing.compareHistoricalMixed(old, target);
        require(pairing.declaredFieldsMatch(), "Historical M/target T declared common fields differ: " + pairing.differences());
        JsonNode receipt = object(JSON.readTree(contents.get(files.receipt())), "historical receipt");
        textEquals(receipt, "schema_version", "ismt-run-v1");
        textEquals(receipt, "status", "VALIDATED");
        boolEquals(receipt, "formalRun", true);
        textEquals(receipt, "experiment_id", old.experimentId());
        textEquals(receipt, "block_id", old.blockId());
        textEquals(receipt, "arm", old.arm().name());
        String specSha = ExperimentSpecIO.sha256(old);
        textEquals(receipt, "spec_sha256", specSha);
        textEquals(receipt, "run_id", old.experimentId() + "-" + old.blockId() + "-" + old.arm().name() + "-" + specSha.substring(0, 16));
        integerEquals(receipt, "final_iteration_reached", old.protocol().finalIteration());
        integerEquals(receipt, "activation_count", 1);
        integerEquals(receipt, "activation_iteration", ACTIVATION_ITERATION);
        textEquals(receipt, "output_schema_version", IsmtRunArtifactWriter.SCHEMA);
        JsonNode seeds = object(receipt.get("seeds"), "receipt seeds");
        integerEquals(seeds, "passenger", old.seeds().passenger());
        integerEquals(seeds, "orderPool", old.seeds().orderPool());
        integerEquals(seeds, "matsimGlobal", old.seeds().matsimGlobal());
        JsonNode validation = object(receipt.get("validation"), "receipt validation");
        boolEquals(validation, "valid", true);
        require(validation.path("issues").isArray() && validation.path("issues").isEmpty(), "Historical validation has missing/nonempty issues");
        boolEquals(object(validation.get("checks"), "validation checks"), "iteration_summary", true);
        boolEquals(validation.get("checks"), "passenger_event_fingerprint", true);
        JsonNode matrix = receipt.get("matrix_validated");
        require(matrix != null && matrix.isBoolean(), "Missing/invalid historical matrix_validated declaration");
        // false is legitimate for a validated individual M run, never upgraded here.
        Path run = resolve(root, files.receipt()).getParent();
        require(resolve(root, files.receipt()).getFileName().toString().equals("run_receipt.json"), "Expected legacy run_receipt.json");
        var expectedPaths = Map.of("experiment_spec.json", files.spec(), "matsim/" + EVENT_FILE, files.eventFingerprint(),
                "matsim/" + PLAN_FILE, files.planFingerprint(), "matsim/" + SUMMARY_FILE, files.summary());
        JsonNode recordedOutputs = object(receipt.get("output_files"), "receipt output_files");
        for (var entry : expectedPaths.entrySet()) {
            var ref = entry.getValue();
            require(resolve(root, ref).equals(run.resolve(entry.getKey())), "Bound file is not this receipt's " + entry.getKey());
            JsonNode recorded = object(recordedOutputs.get(entry.getKey()), "receipt output " + entry.getKey());
            textEquals(recorded, "sha256", ref.sha256());
            integerEquals(recorded, "bytes", contents.get(ref).length);
        }
        var tables = new LinkedHashMap<String, Table>();
        tables.put(EVENT_FILE, readTable(contents.get(files.eventFingerprint()), EVENT_COLUMNS));
        tables.put(PLAN_FILE, readTable(contents.get(files.planFingerprint()), PLAN_COLUMNS));
        tables.put(SUMMARY_FILE, readTable(contents.get(files.summary()), IsmtRunArtifactWriter.SUMMARY_COLUMNS));
        int rows = old.protocol().finalIteration() + 1;
        for (var entry : tables.entrySet()) validateTable(entry.getKey(), entry.getValue(), rows,
                old.experimentId(), old.blockId(), old.arm().name(), false);
        JsonNode pre = object(receipt.get("pre_activation_fingerprint"), "receipt pre_activation_fingerprint");
        require(pre.size() == ACTIVATION_ITERATION, "Historical receipt must bind precisely 20 pre-activation fingerprints");
        for (int iteration = 0; iteration < ACTIVATION_ITERATION; iteration++) {
            JsonNode stamp = object(pre.get(Integer.toString(iteration)), "receipt iteration " + iteration);
            Map<String, String> event = tables.get(EVENT_FILE).rows().get(iteration);
            integerEquals(stamp, "iteration", iteration);
            integerEquals(stamp, "eventCount", Long.parseLong(event.get("event_count")));
            textEquals(stamp, "sha256", event.get("event_sha256"));
            textEquals(stamp, "schemaVersion", PassengerScientificFingerprint.SCHEMA_VERSION);
        }
        // No bytes may change between the initial hash/read and decoding/binding.
        for (var ref : files.all()) readBound(root, ref);
        return new DynamicMixedHistoricalHistoryGuard(root, target, files, pairing, matrix.booleanValue(), tables);
    }

    public synchronized AbstractModule module(Path currentMatsimOutput) {
        healthy();
        if (installed) throw reject("History guard can be installed only once");
        bindOutput(currentMatsimOutput);
        installed = true;
        return new AbstractModule() {
            @Override public void install() {
                addControllerListenerBinding().toInstance(new StartListener());
                addControllerListenerBinding().toInstance(new EndListener());
            }
        };
    }

    private final class StartListener implements IterationStartsListener {
        @Override public double priority() { return 2000; }
        @Override public void notifyIterationStarts(IterationStartsEvent event) { beforeIteration(event.getIteration()); }
    }
    private final class EndListener implements IterationEndsListener {
        @Override public double priority() { return -1500; }
        @Override public void notifyIterationEnds(IterationEndsEvent event) { verifyCompletedIteration(event.getIteration(), currentOutput); }
    }

    /** Testable file gate; the module is what places this before actual treatment activation. */
    public synchronized void beforeIteration(int iteration) {
        healthy();
        if (iteration > ACTIVATION_ITERATION && activationPassed) return;
        if (iteration != started + 1 || checked.size() != iteration)
            throw reject("Missing/out-of-order completed common history before iteration " + iteration);
        if (iteration == ACTIVATION_ITERATION) {
            try {
                compareCurrent(ACTIVATION_ITERATION - 1);
                for (var ref : files.all()) readBound(workspace, ref);
                activationPassed = true;
            } catch (IOException | RuntimeException bad) { throw reject("Activation history gate failed: " + bad.getMessage(), bad); }
        }
        started = iteration;
    }

    /** Checks all rows accumulated so far, also detecting rewrites of earlier current rows. */
    public synchronized void verifyCompletedIteration(int iteration, Path output) {
        healthy();
        if (iteration >= ACTIVATION_ITERATION) {
            if (!activationPassed) throw reject("Post-activation end without history gate");
            return;
        }
        if (iteration != started || iteration != checked.size()) throw reject("Out-of-order/repeated history completion " + iteration);
        bindOutput(output);
        try { checked.add(new IterationCheck(iteration, compareCurrent(iteration))); }
        catch (IOException | RuntimeException bad) { throw reject("Common history differs at completed iteration " + iteration + ": " + bad.getMessage(), bad); }
    }

    public synchronized Report report() {
        return new Report(SCOPE, files, DeclaredPairingEvidence.copyOf(pairing), "VALIDATED", historicalMatrixValidated, checked, activationPassed, poisoned, failure,
                List.of("FULL_RESOLVED_CONFIGURATION_AND_ACTUAL_INPUT_COMPATIBILITY", "SOURCE_BUILD_AND_NATIVE_RUNTIME_COMPATIBILITY",
                        "POST_ACTIVATION_RESULT_VALIDITY", "CROSS_ARM_MATRIX_VALIDATION", "REUSE_AND_RUN_AUTHORIZATION"));
    }

    private Map<String, String> compareCurrent(int last) throws IOException {
        require(currentOutput != null, "No current output is bound");
        var hashes = new LinkedHashMap<String, String>();
        for (var entry : historical.entrySet()) {
            Path file = currentOutput.resolve(entry.getKey()).toRealPath();
            require(file.startsWith(workspace), "Current artifact escapes workspace");
            byte[] bytes = Files.readAllBytes(file);
            List<String> columns = new ArrayList<>(entry.getValue().columns());
            if (!entry.getKey().equals(SUMMARY_FILE)) columns.add("design_family");
            Table actual = readTable(bytes, columns);
            validateTable(entry.getKey(), actual, last + 1, target.experimentId(), target.blockId(), target.design().arm().name(), true);
            for (int row = 0; row <= last; row++) for (String column : entry.getValue().columns()) {
                if (ENVELOPE.contains(column)) continue; // separately checked against both explicit identities/schemas
                String expected = entry.getValue().rows().get(row).get(column);
                String value = actual.rows().get(row).get(column);
                require(expected.equals(value), entry.getKey() + " iteration " + row + " field " + column
                        + " historical=" + expected + " target=" + value);
            }
            hashes.put(entry.getKey(), ExperimentSpecIO.sha256(bytes));
        }
        return hashes;
    }

    private void bindOutput(Path output) {
        if (output == null) throw reject("Current output is required");
        Path normalized = output.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspace)) throw reject("Current output must be in the workspace");
        if (currentOutput != null && !currentOutput.equals(normalized)) throw reject("Current output cannot change mid-history");
        currentOutput = normalized;
    }

    private static void validateTable(String filename, Table table, int expectedRows, String experiment, String block, String arm, boolean dynamic) {
        require(table.rows().size() == expectedRows, filename + " must contain every iteration exactly once in native output order");
        String schema = filename.equals(SUMMARY_FILE) ? (dynamic ? ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA : IsmtRunArtifactWriter.SCHEMA)
                : filename.equals(EVENT_FILE) ? PassengerScientificFingerprint.SCHEMA_VERSION : PassengerPlanFingerprint.SCHEMA_VERSION;
        for (int i = 0; i < expectedRows; i++) {
            Map<String, String> row = table.rows().get(i);
            require(row.get("iteration").equals(Integer.toString(i)), filename + " iteration ordering/encoding mismatch at row " + i);
            require(row.get("experiment_id").equals(experiment) && row.get("block_id").equals(block) && row.get("arm").equals(arm), filename + " identity mismatch at " + i);
            require(row.get("schema_version").equals(schema), filename + " schema mismatch");
            if (dynamic && !filename.equals(SUMMARY_FILE)) require(DynamicMixedDesign.SCHEMA_ID.equals(row.get("design_family")), filename + " design family mismatch");
            if (!filename.equals(SUMMARY_FILE)) {
                String count = row.get(filename.equals(EVENT_FILE) ? "event_count" : "passenger_count");
                require(count.matches("0|[1-9][0-9]*"), filename + " invalid count");
                require(Long.parseLong(count) >= 0, filename + " invalid count");
                require(row.get(filename.equals(EVENT_FILE) ? "event_sha256" : "plan_sha256").matches("[a-f0-9]{64}"), filename + " invalid digest");
            } else if (i < ACTIVATION_ITERATION) {
                require(row.get("phase_active").equals("false") && row.get("phase_activation_count").equals("0")
                        && row.get("phase_activation_iteration").equals("-1"), "Common history contains activated treatment at " + i);
            }
        }
    }

    private record Table(List<String> columns, List<Map<String, String>> rows) { }
    private static Table readTable(byte[] bytes, List<String> expectedColumns) throws IOException {
        try (var parser = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(false).build()
                .parse(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            var records = parser.getRecords();
            require(!records.isEmpty(), "Empty scientific CSV");
            var columns = new ArrayList<String>();
            records.getFirst().forEach(columns::add);
            require(columns.equals(expectedColumns), "Exact scientific CSV header required: " + expectedColumns);
            var rows = new ArrayList<Map<String, String>>();
            for (int i = 1; i < records.size(); i++) {
                var record = records.get(i);
                require(record.size() == columns.size(), "Scientific CSV row width differs at " + i);
                var row = new LinkedHashMap<String, String>();
                for (int j = 0; j < columns.size(); j++) row.put(columns.get(j), record.get(j));
                rows.add(Map.copyOf(row));
            }
            return new Table(List.copyOf(columns), List.copyOf(rows));
        }
    }

    private static Path resolve(Path workspace, ExperimentSpec.FileRef ref) throws IOException {
        for (Path component : Path.of(ref.path())) require(!component.toString().equals(".."), "Parent traversal in historical path");
        Path real = ref.resolve(workspace).toRealPath();
        require(real.startsWith(workspace) && Files.isRegularFile(real), "Historical file escapes workspace/is not regular");
        return real;
    }
    private static byte[] readBound(Path workspace, ExperimentSpec.FileRef ref) throws IOException {
        byte[] bytes = Files.readAllBytes(resolve(workspace, ref));
        require(ExperimentSpecIO.sha256(bytes).equals(ref.sha256()), "Historical file hash mismatch: " + ref.path());
        return bytes;
    }
    private static JsonNode object(JsonNode node, String label) {
        require(node != null && node.isObject(), "Expected object: " + label); return node;
    }
    private static void textEquals(JsonNode node, String field, String expected) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && value.textValue().equals(expected), "Receipt " + field + " mismatch");
    }
    private static void integerEquals(JsonNode node, String field, long expected) {
        JsonNode value = node.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() == expected, "Receipt " + field + " mismatch");
    }
    private static void boolEquals(JsonNode node, String field, boolean expected) {
        JsonNode value = node.get(field);
        require(value != null && value.isBoolean() && value.booleanValue() == expected, "Receipt " + field + " mismatch");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private void healthy() { if (poisoned) throw new IllegalStateException("History gate is poisoned: " + failure); }
    private IllegalStateException reject(String message) { return reject(message, null); }
    private IllegalStateException reject(String message, Throwable cause) {
        poisoned = true; failure = message; return new IllegalStateException(message, cause);
    }
}
