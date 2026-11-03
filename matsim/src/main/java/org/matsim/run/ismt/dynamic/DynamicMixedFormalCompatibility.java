package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.run.ismt.BuildIdentity;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ExperimentSpecIO;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An external, identity-bound record of scoped source review, not a machine proof of
 * behavioral equivalence. Recomputes both complete production inventories and every runtime
 * payload; checks exact review coverage and the declared, passing test cases against their
 * unchanged tested-source inventories. It cannot establish that a test fully proves the prose
 * review judgment. No configuration lifecycle, population, Controller or QSim is invoked.
 *
 * <p>The caller still verifies the current clean frozen checkout and live runtime with
 * {@link BuildIdentity}, compares the actual fresh configurations, and installs the historical
 * history guard. This package neither grants user authority nor validates historical CSV content.
 * The accepted runtime comparison is the present study's unchanged, ordered external JAR hashes
 * and JVM version, not equality of application JARs or path-sensitive dependency manifest hashes.</p>
 */
public final class DynamicMixedFormalCompatibility {
    public static final String SCHEMA = "ismt-dynamic-formal-compatibility-v1";
    public static final String SCOPE = "IDENTITY_BOUND_SCOPED_SOURCE_REVIEW";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private static final List<String> BOUNDARIES = List.of(
            "review-judgment-is-not-machine-proven-behavioral-equivalence",
            "declared-passing-tests-do-not-exhaustively-prove-each-reviewed-change",
            "current-clean-checkout-and-actual-loaded-runtime-verified-by-caller",
            "actual-fresh-configuration-comparison-required-separately",
            "historical-CSV-semantics-and-real-common-history-required-separately",
            "no-railway-safety-or-complete-raw-event-prefix-certification",
            "point-in-time-file-checks-not-registration-or-filesystem-sealing");

    public enum Classification {
        ADDITIVE_T_ONLY, PASSIVE_NATIVE_INSPECTION, NATIVE_SUPPORTED_DOMAIN_CHANGE,
        CONFIGURATION_REFACTOR, SCIENTIFIC_ENVELOPE_ONLY, BUILD_INPUT_CHANGE,
        REMOVAL_WITHIN_DECLARED_DOMAIN
    }

    /** Exact Surefire class/case names, including any parameter suffix, not a substring search. */
    public record TestEvidence(ExperimentSpec.FileRef sourceBefore, ExperimentSpec.FileRef sourceAfter,
                               ExperimentSpec.FileRef report, String testClass, String testCase) {
        public TestEvidence {
            Objects.requireNonNull(sourceBefore); Objects.requireNonNull(sourceAfter); Objects.requireNonNull(report);
            nonblank(testClass, "testClass"); nonblank(testCase, "testCase");
        }
    }

    /** Null old/new SHA means absent in that inventory; both null or equal is not a change. */
    public record SourceReview(String path, String oldSha256, String newSha256, Classification classification,
                               String activationCondition, String scientificJudgment, List<TestEvidence> testEvidence) {
        public SourceReview {
            sourcePath(path); if (oldSha256 != null) sha(oldSha256); if (newSha256 != null) sha(newSha256);
            require(!Objects.equals(oldSha256, newSha256), "Review must describe an actual change");
            Objects.requireNonNull(classification); nonblank(activationCondition, "activationCondition");
            nonblank(scientificJudgment, "scientificJudgment"); testEvidence = List.copyOf(testEvidence);
            require(!testEvidence.isEmpty(), "Each source review needs declared test evidence");
        }
    }

    public record Evidence(String schemaVersion, String specificationSha256,
                           ExperimentSpec.FileRef currentBuildManifest,
                           DynamicMixedHistoricalHistoryGuard.HistoricalFiles historicalFiles,
                           ExperimentSpec.FileRef historicalBuildManifest, ExperimentSpec.FileRef historicalOutputConfig,
                           List<SourceReview> sourceReviews) {
        public Evidence {
            require(SCHEMA.equals(schemaVersion), "Unsupported compatibility schema"); sha(specificationSha256);
            Objects.requireNonNull(currentBuildManifest); Objects.requireNonNull(historicalFiles);
            Objects.requireNonNull(historicalBuildManifest); Objects.requireNonNull(historicalOutputConfig);
            sourceReviews = List.copyOf(sourceReviews);
        }
    }

    public record BuildEvidence(ExperimentSpec.FileRef manifest, String sourceRoot, String sourceCommit,
                                String sourceSha256, ExperimentSpec.FileRef applicationJar, String dependencySha256,
                                String matsimVersion, String javaRuntimeVersion, int productionFileCount) { }

    /** Created only after all checks; serializes without native objects or a generic approval flag. */
    public static final class Report {
        private final String specificationSha256;
        private final ExperimentSpec.FileRef compatibilityEvidence;
        private final Evidence evidence;
        private final BuildEvidence historicalBuild, currentBuild;
        private final int unchangedProductionFiles;
        private final List<String> orderedExternalDependencySha256;
        private Report(String specificationSha256, ExperimentSpec.FileRef compatibilityEvidence, Evidence evidence,
                       BuildEvidence historicalBuild, BuildEvidence currentBuild, int unchangedProductionFiles,
                       List<String> external) {
            this.specificationSha256 = specificationSha256; this.compatibilityEvidence = compatibilityEvidence;
            this.evidence = evidence; this.historicalBuild = historicalBuild; this.currentBuild = currentBuild;
            this.unchangedProductionFiles = unchangedProductionFiles; this.orderedExternalDependencySha256 = List.copyOf(external);
        }
        @JsonProperty public String schemaVersion() { return SCHEMA; }
        @JsonProperty public String scope() { return SCOPE; }
        @JsonProperty public String specificationSha256() { return specificationSha256; }
        @JsonProperty public ExperimentSpec.FileRef compatibilityEvidence() { return compatibilityEvidence; }
        @JsonProperty public Evidence evidence() { return evidence; }
        @JsonProperty public BuildEvidence historicalBuild() { return historicalBuild; }
        @JsonProperty public BuildEvidence currentBuild() { return currentBuild; }
        @JsonProperty public int unchangedProductionFiles() { return unchangedProductionFiles; }
        @JsonProperty public List<String> orderedExternalDependencySha256() { return orderedExternalDependencySha256; }
        @JsonProperty public List<String> unverifiedBoundaries() { return BOUNDARIES; }
        @JsonProperty public boolean machineBehavioralEquivalenceProven() { return false; }
        @JsonProperty public boolean historicalReuseApproved() { return false; }
        @JsonProperty public boolean formalRunAuthorized() { return false; }
    }

    public static final class Bound {
        private final Tracker tracker;
        private final BuildIdentity oldBuild, newBuild;
        private final Report report;
        private Bound(Tracker tracker, BuildIdentity oldBuild, BuildIdentity newBuild, Report report) {
            this.tracker = tracker; this.oldBuild = oldBuild; this.newBuild = newBuild; this.report = report;
        }
        public DynamicMixedHistoricalHistoryGuard.HistoricalFiles historicalFiles() { return report.evidence.historicalFiles(); }
        public ExperimentSpec.FileRef historicalOutputConfig() { return report.evidence.historicalOutputConfig(); }
        public Report report() { return report; }
        /** Rechecks bound paths/payloads and the actual complete production sets, including extra files. */
        public void verifyUnchanged() throws IOException {
            tracker.verifyUnchanged(); verifyBuild(tracker, oldBuild); verifyBuild(tracker, newBuild); tracker.verifyUnchanged();
        }
    }

    public static Bound load(Path workspace, DynamicMixedExperimentSpec spec, Path currentBuildManifest,
                             ExperimentSpec.FileRef compatibilityEvidence) throws IOException {
        Objects.requireNonNull(spec);
        require(spec.design().arm() == DynamicMixedDesign.Arm.DYNAMIC_MIXED, "Compatibility is only for new T");
        Tracker tracker = new Tracker(workspace.toRealPath());
        Evidence evidence = JSON.readValue(tracker.bytes(compatibilityEvidence), Evidence.class);
        require(evidence.specificationSha256().equals(DynamicMixedExperimentSpecIO.sha256(spec)), "Final canonical v2 spec differs");
        Path currentPath = currentBuildManifest.isAbsolute() ? currentBuildManifest : tracker.root.resolve(currentBuildManifest);
        Path boundCurrent = tracker.check(evidence.currentBuildManifest());
        require(boundCurrent.equals(currentPath.toRealPath()), "Current manifest argument differs from evidence");
        tracker.check(new ExperimentSpec.FileRef(tracker.root.relativize(currentPath.normalize()).toString(), evidence.currentBuildManifest().sha256()));
        BuildIdentity oldBuild = JSON.readValue(tracker.bytes(evidence.historicalBuildManifest()), BuildIdentity.class);
        BuildIdentity newBuild = JSON.readValue(tracker.bytes(evidence.currentBuildManifest()), BuildIdentity.class);
        Map<String, String> oldProduction = verifyBuild(tracker, oldBuild), newProduction = verifyBuild(tracker, newBuild);
        require(oldBuild.javaRuntimeVersion().equals(newBuild.javaRuntimeVersion()), "JVM version differs from historical M");
        List<String> external = externalHashes(newBuild);
        require(externalHashes(oldBuild).equals(external), "Ordered external runtime dependencies differ from historical M");
        verifyHistoricalBindings(tracker, evidence, oldBuild, spec);

        var actualChanges = new TreeSet<String>(); actualChanges.addAll(oldProduction.keySet()); actualChanges.addAll(newProduction.keySet());
        int unchanged = 0;
        for (String path : new ArrayList<>(actualChanges)) if (Objects.equals(oldProduction.get(path), newProduction.get(path))) {
            actualChanges.remove(path); unchanged++;
        }
        var reviews = new TreeMap<String, SourceReview>();
        for (SourceReview review : evidence.sourceReviews()) require(reviews.put(review.path(), review) == null, "Duplicate source review: " + review.path());
        require(actualChanges.equals(reviews.keySet()), "Source review coverage differs; missing=" + difference(actualChanges, reviews.keySet())
                + "; extra=" + difference(reviews.keySet(), actualChanges));
        var checkedTests = new HashMap<TestEvidence, Map<String, String>>();
        for (SourceReview review : reviews.values()) {
            require(Objects.equals(oldProduction.get(review.path()), review.oldSha256())
                    && Objects.equals(newProduction.get(review.path()), review.newSha256()), "Reviewed source hashes differ: " + review.path());
            for (TestEvidence test : review.testEvidence()) {
                Map<String, String> tested = checkedTests.get(test);
                if (tested == null) { tested = verifyTest(tracker, test, newBuild); checkedTests.put(test, tested); }
                require(Objects.equals(tested.get(review.path()), review.newSha256()), "Reviewed current file absent/different in tested inventory: " + review.path());
            }
        }
        Report report = new Report(evidence.specificationSha256(), compatibilityEvidence, evidence,
                buildEvidence(evidence.historicalBuildManifest(), oldBuild, oldProduction.size()),
                buildEvidence(evidence.currentBuildManifest(), newBuild, newProduction.size()), unchanged, external);
        Bound result = new Bound(tracker, oldBuild, newBuild, report); result.verifyUnchanged(); return result;
    }

    private static Map<String, String> verifyBuild(Tracker tracker, BuildIdentity build) throws IOException {
        Path source = tracker.root.resolve(build.sourceRoot()).toRealPath();
        require(source.startsWith(tracker.root) && Files.isDirectory(source), "Source root escapes workspace");
        var production = new TreeMap<String, String>();
        for (var entry : build.sourceFiles().entrySet()) {
            sourcePath(entry.getKey());
            Path actual = tracker.check(new ExperimentSpec.FileRef(build.sourceRoot() + "/" + entry.getKey(), entry.getValue()));
            require(actual.startsWith(source), "Source file escapes its frozen source root");
            if (productionPath(entry.getKey())) production.put(entry.getKey(), entry.getValue());
        }
        require(ExperimentSpecIO.sha256(JSON.writeValueAsBytes(new TreeMap<>(build.sourceFiles()))).equals(build.sourceSha256()), "Source manifest digest differs");
        var actualProduction = new TreeSet<String>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String relative = source.relativize(dir).toString();
                if (!relative.isEmpty() && (dir.getFileName().toString().equals(".git")
                        || (dir.getFileName().toString().equals("target") && !productionPath(relative + "/")))) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = source.relativize(file).toString();
                if (productionPath(relative)) {
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("Non-regular production entry: " + relative);
                    actualProduction.add(relative);
                } else if (attrs.isSymbolicLink() && Files.isDirectory(file)) {
                    throw new IOException("Uninspected symlink directory in frozen source: " + relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        require(!production.isEmpty() && actualProduction.equals(production.keySet()), "Complete production inventory differs from manifest");
        for (var jar : build.runtimeClasspath()) {
            require(jar.path().endsWith(".jar"), "Runtime payload is not a JAR"); tracker.check(jar);
        }
        require(ExperimentSpecIO.sha256(JSON.writeValueAsBytes(build.runtimeClasspath())).equals(build.dependencySha256()), "Dependency manifest digest differs");
        return Collections.unmodifiableMap(production);
    }

    /** Includes every module's main source/resources/assembly and explicit Maven build inputs, never tests/docs. */
    private static boolean productionPath(String path) {
        return path.startsWith("src/main/") || path.contains("/src/main/") || path.equals("pom.xml")
                || path.endsWith("/pom.xml") || path.startsWith(".mvn/") || path.equals("mvnw") || path.equals("mvnw.cmd");
    }

    private static void verifyHistoricalBindings(Tracker tracker, Evidence evidence, BuildIdentity build,
                                                 DynamicMixedExperimentSpec target) throws IOException {
        var files = evidence.historicalFiles(); for (var ref : files.all()) tracker.check(ref);
        ExperimentSpec old = JSON.readValue(tracker.bytes(files.spec()), ExperimentSpec.class);
        require(old.arm() == ExperimentSpec.Arm.MIXED && old.blockId().equals(target.blockId()), "Historical source is not this block's M");
        JsonNode receipt = JSON.readTree(tracker.bytes(files.receipt())); require(receipt.isObject(), "Historical receipt must be an object");
        text(receipt, "schema_version", "ismt-run-v1"); text(receipt, "status", "VALIDATED"); bool(receipt, "formalRun", true);
        text(receipt, "experiment_id", old.experimentId()); text(receipt, "block_id", old.blockId()); text(receipt, "arm", "MIXED");
        text(receipt, "spec_sha256", ExperimentSpecIO.sha256(old));
        text(receipt, "source_sha256", build.sourceSha256()); text(receipt, "build_sha256", build.applicationJar().sha256());
        text(receipt, "dependency_sha256", build.dependencySha256()); text(receipt, "build_manifest_sha256", evidence.historicalBuildManifest().sha256());
        String manifestPath = requiredText(receipt, "build_manifest_path"); Path recorded = Path.of(manifestPath);
        if (!recorded.isAbsolute()) recorded = tracker.root.resolve(recorded);
        require(recorded.toRealPath().equals(tracker.check(evidence.historicalBuildManifest())), "Historical receipt names another build manifest");
        tracker.check(new ExperimentSpec.FileRef(tracker.root.relativize(recorded.normalize()).toString(), evidence.historicalBuildManifest().sha256()));
        Path receiptPath = tracker.check(files.receipt()); require(receiptPath.getFileName().toString().equals("run_receipt.json"), "Expected historical run_receipt.json");
        Path run = receiptPath.getParent();
        bindOutput(tracker, receipt, run, "experiment_spec.json", files.spec());
        bindOutput(tracker, receipt, run, "matsim/passenger_event_fingerprint.csv", files.eventFingerprint());
        bindOutput(tracker, receipt, run, "matsim/passenger_plan_fingerprint.csv", files.planFingerprint());
        bindOutput(tracker, receipt, run, "matsim/iteration_summary.csv", files.summary());
        bindOutput(tracker, receipt, run, "matsim/output_config.xml", evidence.historicalOutputConfig());
    }

    private static void bindOutput(Tracker tracker, JsonNode receipt, Path run, String relative, ExperimentSpec.FileRef ref) throws IOException {
        Path file = tracker.check(ref); require(file.equals(run.resolve(relative)), "Historical output belongs to another receipt: " + relative);
        JsonNode item = receipt.path("output_files").path(relative); require(item.isObject(), "Missing historical output identity: " + relative);
        text(item, "sha256", ref.sha256()); JsonNode bytes = item.get("bytes");
        require(bytes != null && bytes.isIntegralNumber() && bytes.canConvertToLong() && bytes.longValue() == Files.size(file), "Historical output byte count differs: " + relative);
    }

    private static Map<String, String> verifyTest(Tracker tracker, TestEvidence evidence, BuildIdentity build) throws IOException {
        Map<String, String> before = sourceMap(tracker.bytes(evidence.sourceBefore())), after = sourceMap(tracker.bytes(evidence.sourceAfter()));
        require(!before.isEmpty() && before.equals(after), "Test source inventories changed");
        for (var entry : after.entrySet()) require(Objects.equals(build.sourceFiles().get(entry.getKey()), entry.getValue()), "Test source inventory differs from current build: " + entry.getKey());
        String outerClass = evidence.testClass().split("\\$", 2)[0];
        String suffix = "/src/test/java/" + outerClass.replace('.', '/') + ".java";
        long testSources = after.keySet().stream().filter(p -> p.endsWith(suffix) || p.equals(suffix.substring(1))).count();
        require(testSources == 1, "Exact test source missing/ambiguous in tested inventory");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            Element suite = factory.newDocumentBuilder().parse(new ByteArrayInputStream(tracker.bytes(evidence.report()))).getDocumentElement();
            require(suite.getTagName().equals("testsuite") && suite.getAttribute("name").equals(evidence.testClass()), "Test suite class differs");
            for (String name : List.of("failures", "errors", "skipped")) require(suite.hasAttribute(name) && nonnegative(suite.getAttribute(name)) == 0, "Declared test suite did not fully pass: " + name);
            if (suite.hasAttribute("flakes")) require(nonnegative(suite.getAttribute("flakes")) == 0, "Flaky test evidence is not a clean pass");
            int cases = 0, exact = 0;
            for (var node = suite.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element item && item.getTagName().equals("testcase")) {
                cases++;
                for (String failure : List.of("failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError"))
                    require(item.getElementsByTagName(failure).getLength() == 0, "Test case has nonpassing evidence: " + failure);
                if (item.getAttribute("classname").equals(evidence.testClass()) && item.getAttribute("name").equals(evidence.testCase())) exact++;
            }
            require(suite.hasAttribute("tests") && nonnegative(suite.getAttribute("tests")) == cases && exact == 1, "Exact passing test case absent/duplicate or suite count differs");
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IOException("Invalid bound test XML", ex); }
        return after;
    }

    private static Map<String, String> sourceMap(byte[] bytes) throws IOException {
        JsonNode root = JSON.readTree(bytes); require(root.isObject(), "Test source map must be a flat object");
        var result = new TreeMap<String, String>(); var fields = root.properties();
        for (var entry : fields) { sourcePath(entry.getKey()); require(entry.getValue().isTextual(), "Test source SHA must be text"); sha(entry.getValue().textValue()); result.put(entry.getKey(), entry.getValue().textValue()); }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> externalHashes(BuildIdentity build) { return build.runtimeClasspath().subList(1, build.runtimeClasspath().size()).stream().map(ExperimentSpec.FileRef::sha256).toList(); }
    private static BuildEvidence buildEvidence(ExperimentSpec.FileRef ref, BuildIdentity build, int count) { return new BuildEvidence(ref, build.sourceRoot(), build.sourceCommit(), build.sourceSha256(), build.applicationJar(), build.dependencySha256(), build.matsimVersion(), build.javaRuntimeVersion(), count); }
    private static TreeSet<String> difference(java.util.Set<String> a, java.util.Set<String> b) { var result = new TreeSet<>(a); result.removeAll(b); return result; }
    private static int nonnegative(String value) { require(value.matches("[0-9]+"), "Invalid nonnegative XML count"); return Integer.parseInt(value); }
    private static void text(JsonNode object, String key, String value) { require(requiredText(object, key).equals(value), "Historical receipt " + key + " differs"); }
    private static String requiredText(JsonNode object, String key) { JsonNode value = object.get(key); require(value != null && value.isTextual(), "Missing/text field required: " + key); return value.textValue(); }
    private static void bool(JsonNode object, String key, boolean expected) { JsonNode value = object.get(key); require(value != null && value.isBoolean() && value.booleanValue() == expected, "Boolean field differs: " + key); }
    private static void nonblank(String value, String label) { require(value != null && !value.isBlank(), "Missing " + label); }
    private static void sha(String value) { require(value != null && value.matches("[a-f0-9]{64}"), "Invalid SHA-256"); }
    private static void sourcePath(String value) { nonblank(value, "source path"); Path p = Path.of(value); require(!p.isAbsolute() && p.normalize().toString().equals(value) && !p.startsWith("..") && !value.equals("."), "Noncanonical relative source path"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }

    /** Remembers requested-to-real bindings as well as bytes, so a later symlink retarget is not accepted. */
    private static final class Tracker {
        final Path root;
        final Map<String, ExperimentSpec.FileRef> refs = new LinkedHashMap<>();
        final Map<String, Path> realPaths = new LinkedHashMap<>();
        Tracker(Path root) { this.root = root; }
        Path check(ExperimentSpec.FileRef ref) throws IOException {
            Path requested = ref.resolve(root), real = requested.toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Non-regular/outside workspace bound file: " + ref.path());
            String key = root.relativize(requested).toString();
            ExperimentSpec.FileRef prior = refs.get(key); Path priorReal = realPaths.get(key);
            if (prior != null && (!prior.sha256().equals(ref.sha256()) || !priorReal.equals(real))) throw new IOException("Bound file identity changed: " + key);
            if (!ExperimentConfiguration.sha256(real).equals(ref.sha256())) throw new IOException("Bound file hash differs: " + ref.path());
            refs.put(key, ref); realPaths.put(key, real); return real;
        }
        byte[] bytes(ExperimentSpec.FileRef ref) throws IOException { byte[] data = Files.readAllBytes(check(ref)); if (!ExperimentSpecIO.sha256(data).equals(ref.sha256())) throw new IOException("Bound file changed while reading: " + ref.path()); return data; }
        void verifyUnchanged() throws IOException { for (var ref : List.copyOf(refs.values())) check(ref); }
    }

    private DynamicMixedFormalCompatibility() { }
}
