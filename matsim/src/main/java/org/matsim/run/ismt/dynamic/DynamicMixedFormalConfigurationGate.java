package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Pre-Scenario configuration gate for new T against the receipt-bound historical M output XML.
 * Uses the actual live raw Config, fresh exact v2 assemblies, and the complete historical XML;
 * it never fabricates a legacy T/spec or reconstructs M to stand in for its actual output.
 * Only the two identity-derived context/output positions can differ after bounded initialization.
 * Every raw, intermediate and initialized field remains in the reports, including native defaults.
 * No files are written, no Scenario is loaded, and no Controller/injector/simulation is started.
 * Current-parser configuration equality is not historical source/runtime equivalence, common-history
 * validation, result reuse approval, user authorization, or a filesystem/configuration seal.
 */
public final class DynamicMixedFormalConfigurationGate {
    public static final String SCHEMA = "ismt-dynamic-formal-configuration-gate-v1";
    private static final String CONFIG_OUTPUT = "matsim/output_config.xml";
    private static final String CONTEXT = DynamicMixedResolvedDefinition.CONTEXT_PATH;
    private static final String OUTPUT = DynamicMixedResolvedDefinition.OUTPUT_PATH;
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private DynamicMixedFormalConfigurationGate() { }

    public record FileEvidence(ExperimentSpec.FileRef declared, String realWorkspaceRelativePath, long bytes, String sha256) { }
    public record HistoricalIdentity(String experimentId, String blockId, String arm, String receiptStatus) { }

    /** Only this class can construct a report. requireVerified concerns this configuration scope only. */
    public static final class Report {
        private final DynamicMixedResolvedDefinition.Report definition;
        private final ControllerInitializationConfigurationAudit.Report historical;
        private final HistoricalIdentity identity;
        private final List<FileEvidence> filesBefore, filesAfter;
        private final DynamicMixedInputVerifier.Verification inputsAfter;
        private final List<ResolvedNonDispatchConfiguration.ExactDifferenceExplanation> allowed;
        private final List<ResolvedNonDispatchConfiguration.Difference> rejected;
        private final List<String> issues;
        private Report(DynamicMixedResolvedDefinition.Report definition, ControllerInitializationConfigurationAudit.Report historical,
                       HistoricalIdentity identity, List<FileEvidence> filesBefore, List<FileEvidence> filesAfter,
                       DynamicMixedInputVerifier.Verification inputsAfter,
                       List<ResolvedNonDispatchConfiguration.ExactDifferenceExplanation> allowed,
                       List<ResolvedNonDispatchConfiguration.Difference> rejected, List<String> issues) {
            this.definition = definition; this.historical = historical; this.identity = identity;
            this.filesBefore = List.copyOf(filesBefore); this.filesAfter = List.copyOf(filesAfter); this.inputsAfter = inputsAfter;
            this.allowed = List.copyOf(allowed); this.rejected = List.copyOf(rejected); this.issues = List.copyOf(issues);
        }
        @JsonProperty public String schemaVersion() { return SCHEMA; }
        @JsonProperty public String scope() { return "LIVE_V2_AND_RECEIPT_BOUND_M_COMPLETE_CONFIGURATION_ONLY"; }
        @JsonProperty public DynamicMixedResolvedDefinition.Report resolvedDefinitionReport() { return definition; }
        @JsonProperty public ControllerInitializationConfigurationAudit.Report historicalInitializationReport() { return historical; }
        @JsonProperty public HistoricalIdentity historicalIdentity() { return identity; }
        @JsonProperty public List<FileEvidence> historicalFilesBefore() { return filesBefore; }
        @JsonProperty public List<FileEvidence> historicalFilesAfter() { return filesAfter; }
        @JsonProperty public DynamicMixedInputVerifier.Verification targetInputsAfter() { return inputsAfter; }
        @JsonProperty public List<ResolvedNonDispatchConfiguration.ExactDifferenceExplanation> allowedInitializedDifferences() { return allowed; }
        @JsonProperty public List<ResolvedNonDispatchConfiguration.Difference> rejectedInitializedDifferences() { return rejected; }
        @JsonProperty public List<String> issues() { return issues; }
        @JsonProperty public boolean configurationVerified() { return issues.isEmpty() && rejected.isEmpty(); }
        @JsonProperty public boolean liveRawUnchanged() { return true; }
        @JsonProperty public boolean historicalOutputReceiptBindingVerified() { return true; }
        @JsonProperty public boolean sourceBuildRuntimeCompatibilityVerified() { return false; }
        @JsonProperty public boolean historicalReuseApproved() { return false; }
        @JsonProperty public boolean formalRunAuthorized() { return false; }
        @JsonProperty public boolean simulationStarted() { return false; }
        @JsonProperty public boolean configurationOrInputsSealed() { return false; }
        public void requireVerified() {
            if (!configurationVerified()) throw new IllegalStateException("Formal configuration gate failed: " + issues
                    + "; unexpected initialized differences=" + rejected);
        }
    }

    private record Read(FileEvidence evidence, Path path, BasicFileAttributes attributes, byte[] contents) { }
    private record Position(ResolvedNonDispatchConfiguration.Value historical, ResolvedNonDispatchConfiguration.Value target, String reason) { }

    public static Report verify(Path workspace, DynamicMixedExperimentSpec spec, Path runRoot,
                                DynamicMixedConfiguration.PreparedConfiguration configuration,
                                ExperimentSpec.FileRef historicalOutputConfig, ExperimentSpec.FileRef historicalReceipt) throws IOException {
        Path root = Objects.requireNonNull(workspace).toRealPath(); Objects.requireNonNull(spec); Objects.requireNonNull(configuration);
        require(spec.design().arm() == DynamicMixedDesign.Arm.DYNAMIC_MIXED, "This formal configuration gate accepts only new DYNAMIC_MIXED");
        Config live = configuration.config();
        var before = ResolvedNonDispatchConfiguration.snapshot(live);
        Supplier<Config> fresh = () -> {
            try { return DynamicMixedConfiguration.create(spec, root, runRoot.resolve("matsim")); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        };
        DynamicMixedResolvedDefinition.Report definition;
        try { definition = DynamicMixedResolvedDefinition.read(root, spec).verify(live, fresh, runRoot); }
        catch (UncheckedIOException failure) { throw failure.getCause(); }
        require(before.fields().equals(configuration.resolvedSnapshot().fields()), "Prepared raw snapshot does not describe the supplied live configuration");
        require(before.fields().equals(definition.rawSnapshot().fields()), "Live raw configuration changed during registered-definition verification");

        Read receipt = read(root, historicalReceipt); Read oldConfig = read(root, historicalOutputConfig);
        require(receipt.path().getFileName().toString().equals("run_receipt.json"), "Expected historical run_receipt.json");
        Path oldRun = receipt.path().getParent();
        require(oldConfig.path().equals(oldRun.resolve(CONFIG_OUTPUT)), "Historical config is not this receipt's exact matsim/output_config.xml");
        JsonNode document = object(JSON.readTree(receipt.contents()), "historical receipt");
        equalText(document, "schema_version", "ismt-run-v1"); equalText(document, "arm", "MIXED");
        equalText(document, "block_id", spec.blockId());
        var identity = new HistoricalIdentity(text(document, "experiment_id"), text(document, "block_id"),
                text(document, "arm"), text(document, "status"));
        JsonNode output = object(object(document.get("output_files"), "receipt output_files").get(CONFIG_OUTPUT), "receipt " + CONFIG_OUTPUT);
        equalText(output, "sha256", oldConfig.evidence().sha256());
        JsonNode size = output.get("bytes");
        require(size != null && size.isIntegralNumber() && size.canConvertToLong() && size.longValue() == oldConfig.evidence().bytes(),
                "Historical output byte count differs from receipt");

        ControllerInitializationConfigurationAudit.Report historical;
        try {
            historical = ControllerInitializationConfigurationAudit.compareFresh(
                    () -> ConfigUtils.loadConfig(oldConfig.path().toString(), new CoTransportConfigGroup(), new PlanAExecutionHorizonConfigGroup()), fresh);
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
        require(historical.current().raw().fields().equals(before.fields()), "Fresh target raw tree differs from actual live configuration");
        require(historical.current().initialized().fields().equals(definition.initializationAudit().current().initialized().fields()),
                "Fresh target initialized tree differs from registered-definition verification");
        var positions = Map.of(CONTEXT, new Position(present(oldConfig.path().toFile().toURI().toURL().toExternalForm()),
                        present(spec.inputs().matsimConfig().resolve(root).toFile().toURI().toURL().toExternalForm()),
                        "Exact XML source URLs: receipt-bound historical output XML versus the target's bound MATSim configuration input."),
                OUTPUT, new Position(present(oldRun.resolve("matsim").toString()), present(runRoot.resolve("matsim").toString()),
                        "Exact output identities: historical receipt parent/matsim versus caller's new runRoot/matsim."));
        var allowed = new ArrayList<ResolvedNonDispatchConfiguration.ExactDifferenceExplanation>();
        var rejected = new ArrayList<ResolvedNonDispatchConfiguration.Difference>();
        var issues = new ArrayList<String>();
        // Check these values even if both sides happen to have the same wrong value: absence of a
        // difference is not authority to substitute a location unrelated to the bound run identity.
        positions.forEach((path, position) -> {
            checkPosition(issues, "historical raw", historical.historical().raw(), path, position.historical());
            checkPosition(issues, "historical initialized", historical.historical().initialized(), path, position.historical());
            checkPosition(issues, "target raw", historical.current().raw(), path, position.target());
            checkPosition(issues, "target initialized", historical.current().initialized(), path, position.target());
        });
        for (var difference : historical.initializedDifferences()) {
            Position expected = positions.get(difference.path());
            if (expected != null && expected.historical().equals(difference.historical()) && expected.target().equals(difference.target()))
                allowed.add(new ResolvedNonDispatchConfiguration.ExactDifferenceExplanation(difference.path(), difference.historical(), difference.target(), expected.reason()));
            else rejected.add(difference);
        }

        // Fresh complete byte checks bracket this gate, including all nineteen target roles. They
        // certify only this read interval, not files that could be changed after the method returns.
        var inputsAfter = DynamicMixedInputVerifier.verify(root, spec);
        require(definition.inputVerification().equals(inputsAfter), "Target input identities changed across configuration comparison");
        Read receiptAfter = reread(root, receipt); Read configAfter = reread(root, oldConfig);
        require(before.fields().equals(ResolvedNonDispatchConfiguration.snapshot(live).fields()), "Live raw configuration changed during historical comparison");
        return new Report(definition, historical, identity, List.of(receipt.evidence(), oldConfig.evidence()),
                List.of(receiptAfter.evidence(), configAfter.evidence()), inputsAfter, allowed, rejected, issues);
    }

    private static void checkPosition(List<String> issues, String side, ControllerInitializationConfigurationAudit.Tree tree,
                                      String path, ResolvedNonDispatchConfiguration.Value expected) {
        var actual = new ResolvedNonDispatchConfiguration.Value(tree.fields().containsKey(path), tree.fields().get(path));
        if (!actual.equals(expected)) issues.add(side + " identity-derived position mismatch: " + path + " expected=" + expected + " actual=" + actual);
    }
    private static ResolvedNonDispatchConfiguration.Value present(String value) { return new ResolvedNonDispatchConfiguration.Value(true, value); }
    private static Read read(Path root, ExperimentSpec.FileRef ref) throws IOException {
        Objects.requireNonNull(ref); Path requested = ref.resolve(root); Path real = requested.toRealPath();
        if (!real.startsWith(root) || !real.equals(requested)) throw new IOException("Historical binding must be an exact non-aliased contained file: " + ref.path());
        var before = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("Expected regular historical configuration/receipt: " + real);
        byte[] contents = Files.readAllBytes(real);
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (contents.length != before.size() || !digest.equals(ref.sha256())) throw new IOException("Historical file hash/length mismatch: " + ref.path());
        Read result = new Read(new FileEvidence(ref, root.relativize(real).toString(), contents.length, digest), real, before, contents);
        requireMetadataUnchanged(result); return result;
    }
    private static Read reread(Path root, Read before) throws IOException {
        Read after = read(root, before.evidence().declared());
        if (!before.evidence().equals(after.evidence())) throw new IOException("Historical file changed during gate: " + before.path());
        requireMetadataUnchanged(before); return after;
    }
    private static void requireMetadataUnchanged(Read before) throws IOException {
        var after = Files.readAttributes(before.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var old = before.attributes();
        if (!after.isRegularFile() || after.size() != old.size() || !after.lastModifiedTime().equals(old.lastModifiedTime())
                || !after.creationTime().equals(old.creationTime()) || !Objects.equals(after.fileKey(), old.fileKey()))
            throw new IOException("Historical file metadata changed during gate: " + before.path());
    }
    private static JsonNode object(JsonNode node, String name) {
        require(node != null && node.isObject(), "Expected object: " + name); return node;
    }
    private static String text(JsonNode object, String name) {
        JsonNode value = object.get(name);
        require(value != null && value.isTextual() && !value.textValue().isBlank(), "Expected nonblank text: " + name); return value.textValue();
    }
    private static void equalText(JsonNode object, String name, String expected) { require(expected.equals(text(object, name)), "Receipt field mismatch: " + name); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
