package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ExperimentSpecIO;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only reconstruction of one historical MIXED specification through today's neutral common
 * configuration adapter, compared against an explicitly supplied full historical output_config.xml.
 * No dynamic-v2 specification is fabricated. No Scenario, Controller, QSim, population XML parser,
 * scientific writer or simulation output directory is created. All twelve declared input roles are
 * freshly byte-verified, with hashing deduplicated by actual resolved path, not by declared hash.
 *
 * <p>Both XML configurations use the CURRENT parser and the same two custom config-group classes.
 * The report is an exposed-configuration-tree comparison, not replay of the old parser/runtime,
 * automatic historical-result reuse approval, or a claim that the supplied old XML is authenticated
 * by an old run receipt. Even a strictly equal tree is not a matched new-T specification. Positional
 * context/output notes never remove a difference or approve its scientific consequences.</p>
 */
public final class HistoricalMixedResolvedConfigurationAudit {
    public static final String SCHEMA = "ismt-historical-mixed-resolved-config-audit-v1";
    public static final String INITIALIZED_SCHEMA = "ismt-historical-mixed-initialized-config-audit-v1";
    private static final String SCOPE = "HISTORICAL_MIXED_SPEC_THROUGH_CURRENT_COMMON_ADAPTER_ONLY";
    private HistoricalMixedResolvedConfigurationAudit() { }

    public record FileEvidence(String role, String requestedWorkspaceRelativePath, String realWorkspaceRelativePath,
                               long bytes, String sha256) { }
    public record HistoricalIdentity(String schemaVersion, String experimentId, String blockId, String arm,
                                     String rawSpecSha256, String canonicalSpecSha256) { }
    public record InputVerification(List<FileEvidence> files, int uniqueFiles, long uniqueBytesHashed,
                                    String method, String stabilityScope) {
        public InputVerification { files = List.copyOf(files); }
    }
    public record ConfigurationTree(String schemaVersion, String sha256, Map<String, String> fields) {
        public ConfigurationTree { fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields)); }
    }
    public record PositionalNote(String path, ResolvedNonDispatchConfiguration.Value historical,
                                 ResolvedNonDispatchConfiguration.Value current, String observation) { }
    public record Report(String schemaVersion, String scope, String startedAtUtc, String completedAtUtc, String workspace,
                         HistoricalIdentity historicalIdentity, FileEvidence specificationFile, FileEvidence historicalConfigFile,
                         FileEvidence currentTemplateFile, InputVerification declaredInputs, String prospectiveOutputDirectory,
                         ConfigurationTree historicalTree, ConfigurationTree currentCommonTree, boolean strictlyEqual,
                         List<ResolvedNonDispatchConfiguration.Difference> differences, List<PositionalNote> positionalNotes,
                         List<String> unverifiedBoundaries, boolean formalRunStarted, boolean newDynamicSpecMatched,
                         boolean sourceBuildRuntimeCompatibilityVerified, boolean historicalReceiptBindingVerified) {
        public Report {
            differences = List.copyOf(differences); positionalNotes = List.copyOf(positionalNotes);
            unverifiedBoundaries = List.copyOf(unverifiedBoundaries);
            if (!SCHEMA.equals(schemaVersion) || !SCOPE.equals(scope) || formalRunStarted || newDynamicSpecMatched
                    || sourceBuildRuntimeCompatibilityVerified || historicalReceiptBindingVerified)
                throw new IllegalArgumentException("This report cannot make execution/reuse/provenance claims");
            if (strictlyEqual != differences.isEmpty()) throw new IllegalArgumentException("Strict equality must preserve every difference");
        }
    }

    /** Small binding/configuration sources, not executable-source/build certification. */
    public record SmallSourceCheck(FileEvidence file, String lastModifiedTime, String creationTime, String fileKey) { }

    public record InitializedReport(String schemaVersion, String startedAtUtc, String completedAtUtc,
                                    Report rawReport, ControllerInitializationConfigurationAudit.Report initReport,
                                    List<SmallSourceCheck> checkedSmallSources, List<String> unverifiedBoundaries,
                                    boolean formalRunStarted, boolean historicalReuseApproved) {
        public InitializedReport {
            if (!INITIALIZED_SCHEMA.equals(schemaVersion) || formalRunStarted || historicalReuseApproved)
                throw new IllegalArgumentException("Initialized comparison cannot approve execution or reuse");
            Objects.requireNonNull(rawReport); Objects.requireNonNull(initReport);
            checkedSmallSources = List.copyOf(checkedSmallSources); unverifiedBoundaries = List.copyOf(unverifiedBoundaries);
            if (!rawReport.historicalTree().sha256().equals(initReport.historical().raw().sha256())
                    || !rawReport.historicalTree().fields().equals(initReport.historical().raw().fields())
                    || !rawReport.currentCommonTree().sha256().equals(initReport.current().raw().sha256())
                    || !rawReport.currentCommonTree().fields().equals(initReport.current().raw().fields())
                    || !rawReport.differences().equals(initReport.rawDifferences()))
                throw new IllegalArgumentException("Fresh initialization inputs differ from retained raw comparison");
        }
    }

    private record Payload(Path real, BasicFileAttributes attributes, long bytes, String sha256) { }
    private record Read(FileEvidence evidence, Payload payload) { }

    /** Paths may be absolute contained paths or explicitly workspace-relative paths, never process-relative. */
    public static Report inspect(Path workspace, Path historicalSpec, Path historicalFullOutputConfig,
                                 Path prospectiveUncreatedOutput) throws IOException {
        String started = Instant.now().toString();
        Path root = Objects.requireNonNull(workspace).toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Workspace is not a directory");
        Path specPath = contained(root, historicalSpec); Path historicalPath = contained(root, historicalFullOutputConfig);
        Path prospectiveOutput = newOutput(root, prospectiveUncreatedOutput, false);
        Map<Path, Payload> inputsByRealPath = new LinkedHashMap<>();
        Read specBefore = read(root, "historical.specification", specPath, new LinkedHashMap<>());
        Read historicalBefore = read(root, "historical.fullOutputConfig", historicalPath, new LinkedHashMap<>());
        ExperimentSpec spec = ExperimentSpecIO.read(specBefore.payload().real());
        if (spec.arm() != ExperimentSpec.Arm.MIXED) throw new IllegalArgumentException("Only an actual legacy MIXED spec is accepted");
        List<FileEvidence> declaredInputs = new ArrayList<>();
        for (var entry : spec.inputs().all().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            Read observed = read(root, "inputs." + entry.getKey(), entry.getValue().resolve(root), inputsByRealPath);
            if (!observed.evidence().sha256().equals(entry.getValue().sha256()))
                throw new IOException("Historical declared input hash mismatch: " + entry.getKey());
            declaredInputs.add(observed.evidence());
        }
        // The common adapter's caller-verification contract has now been satisfied for the actual old schema.
        var historicalConfig = ConfigUtils.loadConfig(historicalBefore.payload().real().toString(),
                new CoTransportConfigGroup(), new PlanAExecutionHorizonConfigGroup());
        var common = ExperimentConfiguration.create(ExperimentConfiguration.commonRequest(spec), root, prospectiveOutput);
        var historicalTree = ResolvedNonDispatchConfiguration.snapshot(historicalConfig);
        var currentTree = ResolvedNonDispatchConfiguration.snapshot(common);
        var comparison = ResolvedNonDispatchConfiguration.compare(historicalTree, currentTree);

        // Recheck every declared alias and visible metadata after assembly; do not hash large inputs twice.
        for (FileEvidence file : declaredInputs) {
            Path resolved = existingReal(root, root.resolve(file.requestedWorkspaceRelativePath()));
            if (!root.resolve(file.realWorkspaceRelativePath()).equals(resolved))
                throw new IOException("Declared input alias changed during audit: " + file.role());
            requireUnchanged(inputsByRealPath.get(resolved));
        }
        requireSameSmallFile(root, specBefore, specPath);
        requireSameSmallFile(root, historicalBefore, historicalPath);
        FileEvidence template = declaredInputs.stream().filter(file -> file.role().equals("inputs.matsimConfig")).findFirst().orElseThrow();
        Read templateAfter = read(root, template.role(), root.resolve(template.requestedWorkspaceRelativePath()), new LinkedHashMap<>());
        if (!template.equals(templateAfter.evidence())) throw new IOException("Configuration template changed during audit");
        requireUnchanged(inputsByRealPath.get(templateAfter.payload().real()));
        newOutput(root, prospectiveOutput, false); // recheck the uncreated output and its existing ancestors
        List<PositionalNote> notes = new ArrayList<>();
        for (var difference : comparison.differences()) {
            if (difference.path().equals("/context")) notes.add(new PositionalNote(difference.path(), difference.historical(), difference.target(),
                    "Different XML load locations produce different Config contexts. This note does not establish equivalence of any relative path."));
            if (difference.path().equals("/modules/=controller/params/=outputDirectory"))
                notes.add(new PositionalNote(difference.path(), difference.historical(), difference.target(),
                        "The current adapter was explicitly given a new uncreated diagnostic output location. Neither location was modified; the difference is retained."));
        }
        long bytes = 0;
        for (Payload payload : inputsByRealPath.values()) bytes = Math.addExact(bytes, payload.bytes());
        return new Report(SCHEMA, SCOPE, started, Instant.now().toString(), root.toString(),
                new HistoricalIdentity(spec.schemaVersion(), spec.experimentId(), spec.blockId(), spec.arm().name(),
                        specBefore.evidence().sha256(), ExperimentSpecIO.sha256(spec)),
                specBefore.evidence(), historicalBefore.evidence(), template,
                new InputVerification(declaredInputs, inputsByRealPath.size(), bytes,
                        "Fresh SHA256 of exact bytes for all 12 old FileRefs; deduplicated by resolved real path only; no XML population/network parsing",
                        "Before/after file metadata and end-of-batch alias/metadata checks; small spec/full-config/template SHA256 reread. Point-in-time evidence, not a filesystem seal."),
                prospectiveOutput.toString(), tree(historicalTree), tree(currentTree), comparison.strictlyEqual(),
                comparison.differences(), notes, List.of(
                        "Historical full output XML association with the supplied spec/run receipt is caller-provided, not independently authenticated here.",
                        "Both XMLs were interpreted using the current parser/custom-group classes; old binary/parser behavior was not replayed.",
                        "No complete dynamic-v2 spec, 19-role target input set, registered resolved-definition decoder or target policy configuration was matched.",
                        "Actual injected runtime, source/build/classpath compatibility and event-worker/native-engine construction were not verified.",
                        "Pre-activation common history, native scientific outputs, result validity and historical M reuse eligibility were not assessed.",
                        "Positional notes do not explain away differences; every raw field difference remains present and unapproved.",
                        "No Controller, QSim or Scenario was created; no C/M/T/D simulation was launched and no old data was changed."),
                false, false, false, false);
    }

    /**
     * Optional separate diagnostic. The unchanged inspect() first verifies every declared input and
     * retains the complete raw report. Fresh configurations are then consumed by the bounded native
     * configuration-initialization replay. Their raw trees must exactly match that report; spec, full
     * historical XML and current template bytes/aliases/metadata are checked again on both sides of
     * replay. No large scientific input is parsed or rehashed a second time, and no old report is edited.
     */
    public static InitializedReport inspectInitialized(Path workspace, Path historicalSpec, Path historicalFullOutputConfig,
                                                      Path prospectiveUncreatedOutput) throws IOException {
        String started = Instant.now().toString();
        Report raw = inspect(workspace, historicalSpec, historicalFullOutputConfig, prospectiveUncreatedOutput);
        Path root = Path.of(raw.workspace());
        List<Read> sources = new ArrayList<>();
        for (FileEvidence evidence : List.of(raw.specificationFile(), raw.historicalConfigFile(), raw.currentTemplateFile())) {
            Read fresh = read(root, evidence.role(), root.resolve(evidence.requestedWorkspaceRelativePath()), new LinkedHashMap<>());
            if (!fresh.evidence().equals(evidence)) throw new IOException("Small source changed after raw audit: " + evidence.role());
            sources.add(fresh);
        }
        ExperimentSpec spec = ExperimentSpecIO.read(sources.get(0).payload().real());
        if (!ExperimentSpecIO.sha256(spec).equals(raw.historicalIdentity().canonicalSpecSha256()))
            throw new IOException("Historical specification changed before initialization replay");
        Path output = newOutput(root, Path.of(raw.prospectiveOutputDirectory()), false);
        ControllerInitializationConfigurationAudit.Report initialized;
        try {
            initialized = ControllerInitializationConfigurationAudit.compareFresh(
                    () -> ConfigUtils.loadConfig(sources.get(1).payload().real().toString(),
                            new CoTransportConfigGroup(), new PlanAExecutionHorizonConfigGroup()),
                    () -> {
                        try { return ExperimentConfiguration.create(ExperimentConfiguration.commonRequest(spec), root, output); }
                        catch (IOException failure) { throw new UncheckedIOException(failure); }
                    });
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
        List<SmallSourceCheck> checked = new ArrayList<>();
        for (Read source : sources) {
            requireSameSmallFile(root, source, root.resolve(source.evidence().requestedWorkspaceRelativePath()));
            var attributes = source.payload().attributes();
            checked.add(new SmallSourceCheck(source.evidence(), attributes.lastModifiedTime().toString(),
                    attributes.creationTime().toString(), Objects.toString(attributes.fileKey(), null)));
        }
        newOutput(root, output, false);
        return new InitializedReport(INITIALIZED_SCHEMA, started, Instant.now().toString(), raw, initialized, checked,
                List.of("Only native config consistency and conditional SwissRailRaptor config materialization were replayed; no injector, Controller, Scenario or simulation was created.",
                        "The retained raw report freshly hashes all declared inputs. Only the three small spec/config/template sources are rehashed across the additional replay; large-input stability is scoped to the raw audit interval.",
                        "Current-parser full trees and every raw/intermediate/final difference are retained. Context/output paths are not filtered or approved.",
                        "No historical receipt binding, historical binary equivalence, complete lifecycle, dynamic-v2 pairing, scientific result reuse or formal-run eligibility is certified."),
                false, false);
    }

    /** Writes only this new JSON report. The existing parent must be contained; no directories are created. */
    public static void writeNewReport(Path workspace, Path path, Report report) throws IOException {
        Path root = workspace.toRealPath(); Objects.requireNonNull(report);
        if (!root.toString().equals(report.workspace())) throw new IOException("Report workspace does not match the writing workspace");
        Path output = newOutput(root, path, true);
        var json = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
    }

    /** Writes only a new wrapper; the old raw report schema and writer are unchanged. */
    public static void writeNewInitializedReport(Path workspace, Path path, InitializedReport report) throws IOException {
        Path root = workspace.toRealPath(); Objects.requireNonNull(report);
        if (!root.toString().equals(report.rawReport().workspace())) throw new IOException("Report workspace does not match the writing workspace");
        Path output = newOutput(root, path, true);
        var json = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        Files.write(output, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
    }

    /** Explicitly named one-block diagnostic; successful comparison does not authorize a formal run. */
    public static void main(String[] args) throws IOException {
        if (args.length != 5 && (args.length != 6 || !"initialized".equals(args[5])))
            throw new IllegalArgumentException("Usage: HistoricalMixedResolvedConfigurationAudit <workspace> <old-M-spec> <old-full-output-config> <uncreated-prospective-output> <new-report-json> [initialized]");
        Path workspace = Path.of(args[0]).toRealPath();
        newOutput(workspace, Path.of(args[4]), true); // fail before large hashes when the report target is invalid/existing
        if (args.length == 6) {
            InitializedReport report = inspectInitialized(workspace, Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            writeNewInitializedReport(workspace, Path.of(args[4]), report);
            System.out.println("Read-only historical MIXED initialization comparison: " + report.rawReport().differences().size()
                    + " raw and " + report.initReport().initializedDifferences().size()
                    + " initialized differences retained; no simulation or historical reuse approval. Report: " + contained(workspace, Path.of(args[4])));
            return;
        }
        Report report = inspect(workspace, Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        writeNewReport(workspace, Path.of(args[4]), report);
        System.out.println("Read-only historical MIXED configuration comparison: " + report.differences().size()
                + " retained differences; no simulation or historical reuse approval. Report: " + contained(workspace, Path.of(args[4])));
    }

    private static ConfigurationTree tree(ResolvedNonDispatchConfiguration.Snapshot snapshot) {
        return new ConfigurationTree(snapshot.schemaVersion(), snapshot.sha256(), snapshot.fields());
    }

    private static Read read(Path root, String role, Path path, Map<Path, Payload> cache) throws IOException {
        Path requested = contained(root, path); Path real = existingReal(root, requested);
        Payload payload = cache.get(real);
        if (payload == null) {
            BasicFileAttributes before = attributes(real);
            if (!before.isRegularFile()) throw new IOException("Expected regular input file: " + real);
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            long bytes = 0;
            try (var stream = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[1024 * 1024];
                for (int count; (count = stream.read(buffer)) != -1;) {
                    if (count == 0) continue;
                    digest.update(buffer, 0, count); bytes = Math.addExact(bytes, count);
                }
            }
            payload = new Payload(real, before, bytes, HexFormat.of().formatHex(digest.digest()));
            if (bytes != before.size()) throw new IOException("Input length changed while hashing: " + real);
            requireUnchanged(payload); cache.put(real, payload);
        } else requireUnchanged(payload);
        return new Read(new FileEvidence(role, root.relativize(requested).toString(), root.relativize(real).toString(),
                payload.bytes(), payload.sha256()), payload);
    }

    private static void requireSameSmallFile(Path root, Read before, Path path) throws IOException {
        Read after = read(root, before.evidence().role(), path, new LinkedHashMap<>());
        if (!before.evidence().equals(after.evidence())) throw new IOException("Small binding/configuration file changed during audit: " + path);
        requireUnchanged(before.payload());
    }

    private static BasicFileAttributes attributes(Path real) throws IOException {
        return Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireUnchanged(Payload before) throws IOException {
        if (before == null) throw new IOException("Input was retargeted to an unhashed file");
        var after = attributes(before.real()); var old = before.attributes();
        if (!after.isRegularFile() || after.size() != old.size() || !after.lastModifiedTime().equals(old.lastModifiedTime())
                || !after.creationTime().equals(old.creationTime()) || !Objects.equals(after.fileKey(), old.fileKey()))
            throw new IOException("Input metadata changed during audit: " + before.real());
    }

    private static Path contained(Path root, Path path) throws IOException {
        Objects.requireNonNull(path);
        Path requested = (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
        if (!requested.startsWith(root)) throw new IOException("Path escapes workspace: " + path);
        return requested;
    }
    private static Path existingReal(Path root, Path path) throws IOException {
        Path real = contained(root, path).toRealPath();
        if (!real.startsWith(root)) throw new IOException("Symlink escapes workspace: " + path);
        return real;
    }
    private static Path newOutput(Path root, Path path, boolean parentMustExist) throws IOException {
        Path output = contained(root, path);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Refusing existing output: " + output);
        Path parent = output.getParent();
        if (parentMustExist && !Files.isDirectory(parent)) throw new IOException("Report parent must already exist: " + parent);
        while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) parent = parent.getParent();
        if (parent == null || !Files.isDirectory(parent) || !parent.toRealPath().startsWith(root))
            throw new IOException("Output ancestor is not a contained directory: " + output);
        return output;
    }
}
