package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.run.ismt.BuildIdentity;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ExperimentSpecIO;
import org.matsim.run.ismt.IsmtRunArtifactWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedFormalCompatibility.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Synthetic file/manifest/XML evidence only. No compiler, git, Scenario, native runtime or simulation. */
class DynamicMixedFormalCompatibilityTest {
    @TempDir Path temporary;
    private int sequence;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String CHANGED = "matsim/src/main/java/example/Changed.java";
    private static final String ADDED = "matsim/src/main/java/example/Added.java";
    private static final String REMOVED = "matsim/src/main/resources/removed.txt";
    private static final String SAME = "examples/src/main/resources/reference.txt";
    private static final String TEST_SOURCE = "matsim/src/test/java/example/CompatibilityTest.java";

    @Test void exactProductionDiffAndRuntimePayloadsBindWithoutClaimingBehavioralEquivalence() throws Exception {
        var f = fixture(); var bound = f.load(); var report = bound.report();
        assertEquals(SCOPE, report.scope()); assertEquals(DynamicMixedExperimentSpecIO.sha256(f.spec), report.specificationSha256());
        assertEquals(f.evidence.historicalFiles(), bound.historicalFiles());
        assertEquals(f.ref(f.oldRun.resolve("matsim/output_config.xml")), bound.historicalOutputConfig());
        assertEquals(5, report.historicalBuild().productionFileCount()); assertEquals(5, report.currentBuild().productionFileCount());
        assertEquals(3, report.unchangedProductionFiles()); assertEquals(3, report.evidence().sourceReviews().size());
        assertEquals(2, report.orderedExternalDependencySha256().size());
        assertNotEquals(report.historicalBuild().applicationJar().sha256(), report.currentBuild().applicationJar().sha256());
        assertNotEquals(report.historicalBuild().dependencySha256(), report.currentBuild().dependencySha256(), "Path/application-sensitive digest is not an external dependency upgrade");
        assertFalse(report.machineBehavioralEquivalenceProven()); assertFalse(report.historicalReuseApproved()); assertFalse(report.formalRunAuthorized());
        assertFalse(report.unverifiedBoundaries().isEmpty()); bound.verifyUnchanged();
        assertFalse(Files.exists(f.root.resolve("output")), "Inspection creates no run output");
    }

    @Test void reportIsDetachedImmutableAndSerializesExactSourceReviewEvidence() throws Exception {
        var f = fixture(); var report = f.load().report();
        assertThrows(UnsupportedOperationException.class, () -> report.evidence().sourceReviews().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.evidence().sourceReviews().getFirst().testEvidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.orderedExternalDependencySha256().clear());
        var tree = JSON.readTree(JSON.writeValueAsBytes(report));
        assertEquals(SCOPE, tree.path("scope").textValue()); assertEquals(3, tree.path("evidence").path("sourceReviews").size());
        assertEquals(f.evidence.currentBuildManifest().sha256(), tree.path("currentBuild").path("manifest").path("sha256").textValue());
        assertFalse(tree.path("machineBehavioralEquivalenceProven").booleanValue());
        assertEquals("checked", tree.path("evidence").path("sourceReviews").get(0).path("testEvidence").get(0).path("testCase").textValue());
    }

    @Test void finalCanonicalSpecAndExplicitCurrentManifestMustMatch() throws Exception {
        var f = fixture(); f.evidence = replace(f.evidence, "specificationSha256", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, f::load);
        var g = fixture(); var ref = g.writeEvidence();
        assertThrows(IllegalArgumentException.class, () -> load(g.root, replace(g.spec, "blockId", "B2"), g.newManifest, ref));
        assertThrows(IllegalArgumentException.class, () -> load(g.root, g.spec, g.oldManifest, ref));
        var control = DynamicMixedSpecFixtures.spec(DynamicMixedDesign.Arm.CONTROL);
        assertThrows(IllegalArgumentException.class, () -> load(g.root, control, g.newManifest, ref));
    }

    @Test void strictExternalSchemaRejectsUnknownMissingDuplicateAndTrailingFields() throws Exception {
        var f = fixture(); ObjectNode original = JSON.valueToTree(f.evidence);
        var unknown = original.deepCopy().put("reviewed", true);
        var missing = original.deepCopy(); missing.remove("sourceReviews");
        var schema = original.deepCopy().put("schemaVersion", "ismt-run-v1");
        var coercion = original.deepCopy(); coercion.put("specificationSha256", 42);
        List<String> inputs = List.of(unknown.toString(), missing.toString(), schema.toString(), coercion.toString(),
                original.toString() + " {}", original.toString().replaceFirst("\\{", "{\"schemaVersion\":\"duplicate\","));
        for (String input : inputs) {
            var file = f.write("bad-evidence.json", input);
            assertThrows(IOException.class, () -> load(f.root, f.spec, f.newManifest, f.ref(file)), input);
        }
    }

    @Test void everyAddedChangedRemovedFileNeedsExactlyOneAccurateReview() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            var f = fixture(); var reviews = new ArrayList<>(f.evidence.sourceReviews());
            switch (mode) {
                case 0 -> reviews.removeFirst();
                case 1 -> reviews.add(reviews.getFirst());
                case 2 -> reviews.add(new SourceReview(SAME, "1".repeat(64), f.newBuild.sourceFiles().get(SAME),
                        Classification.PASSIVE_NATIVE_INSPECTION, "synthetic fixture", "Not an actual change", List.of(f.test)));
                case 3 -> reviews.set(0, replace(reviews.getFirst(), "oldSha256", "2".repeat(64)));
                case 4 -> reviews.set(1, replace(reviews.get(1), "newSha256", "3".repeat(64)));
                default -> throw new AssertionError();
            }
            f.evidence = replace(f.evidence, "sourceReviews", reviews);
            assertThrows(IllegalArgumentException.class, f::load, "mode=" + mode);
        }
    }

    @Test void sourceReviewRequiresNonemptySubstantiveFieldsAndTestsNotApprovalBoolean() {
        var ref = new ExperimentSpec.FileRef("evidence.json", "a".repeat(64));
        var test = new TestEvidence(ref, ref, ref, "example.Test", "case");
        assertThrows(IllegalArgumentException.class, () -> new SourceReview(CHANGED, null, null, Classification.ADDITIVE_T_ONLY, "condition", "judgment", List.of(test)));
        assertThrows(IllegalArgumentException.class, () -> new SourceReview(CHANGED, null, "a".repeat(64), Classification.ADDITIVE_T_ONLY, " ", "judgment", List.of(test)));
        assertThrows(IllegalArgumentException.class, () -> new SourceReview(CHANGED, null, "a".repeat(64), Classification.ADDITIVE_T_ONLY, "condition", "", List.of(test)));
        assertThrows(IllegalArgumentException.class, () -> new SourceReview(CHANGED, null, "a".repeat(64), Classification.ADDITIVE_T_ONLY, "condition", "judgment", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SourceReview("../outside", null, "a".repeat(64), Classification.ADDITIVE_T_ONLY, "condition", "judgment", List.of(test)));
    }

    @Test void actualProductionScanRejectsUnmanifestedJavaResourceBuildAndOtherModuleFiles() throws Exception {
        for (String path : List.of("matsim/src/main/java/example/Unlisted.java", "matsim/src/main/resources/unlisted.json",
                "other/src/main/resources/new.txt", "another/pom.xml", ".mvn/new.properties")) {
            var f = fixture(); f.write("new/source/" + path, "unregistered");
            assertThrows(IllegalArgumentException.class, f::load, path);
        }
    }

    @Test void sourceAndJarPayloadHashesAreCheckedNotOnlyManifestDescriptions() throws Exception {
        for (String path : List.of("old/source/" + CHANGED, "new/source/" + CHANGED, "old/app.jar", "new/dep-two.jar")) {
            var f = fixture(); f.write(path, "unexpected changed bytes"); assertThrows(IOException.class, f::load, path);
        }
    }

    @Test void missingSourceDigestAndDependencyDigestIntegrityFailEvenAfterManifestIsRebound() throws Exception {
        for (String field : List.of("sourceSha256", "dependencySha256")) {
            var f = fixture(); f.newBuild = replace(f.newBuild, field, "f".repeat(64)); f.writeNewManifest();
            assertThrows(IllegalArgumentException.class, f::load, field);
        }
    }

    @Test void externalRuntimeOrderAndJvmChangesAreNotMisrepresentedAsMatched() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            var f = fixture();
            if (mode == 0) f.newBuild = replace(f.newBuild, "javaRuntimeVersion", "different-version");
            else {
                var cp = new ArrayList<>(f.newBuild.runtimeClasspath());
                if (mode == 1) java.util.Collections.swap(cp, 1, 2);
                else cp.set(1, f.ref(f.write("new/different.jar", "different dependency")));
                f.newBuild = replace(replace(f.newBuild, "runtimeClasspath", cp), "dependencySha256", ExperimentSpecIO.sha256(JSON.writeValueAsBytes(cp)));
            }
            f.writeNewManifest(); assertThrows(IllegalArgumentException.class, f::load);
        }
    }

    @Test void everyHistoricalBuildReceiptIdentityAndConfigInventoryMustMatch() throws Exception {
        for (String field : List.of("source_sha256", "build_sha256", "dependency_sha256", "build_manifest_sha256", "spec_sha256")) {
            var f = fixture(); f.receipt.put(field, "0".repeat(64)); f.writeReceipt(); assertThrows(IllegalArgumentException.class, f::load, field);
        }
        var f = fixture(); f.receipt.put("build_manifest_path", f.newManifest.toString()); f.writeReceipt(); assertThrows(IllegalArgumentException.class, f::load);
        var g = fixture(); ((ObjectNode) g.receipt.path("output_files").path("matsim/output_config.xml")).put("bytes", 1000000);
        g.writeReceipt(); assertThrows(IllegalArgumentException.class, g::load);
        var h = fixture(); ((ObjectNode) h.receipt.path("output_files")).remove("matsim/output_config.xml"); h.writeReceipt(); assertThrows(IllegalArgumentException.class, h::load);
    }

    @Test void oldMFullConfigCannotBeBorrowedFromAnotherDirectoryEvenWithSameBytes() throws Exception {
        var f = fixture(); Path other = f.write("elsewhere/output_config.xml", Files.readString(f.oldRun.resolve("matsim/output_config.xml")));
        f.evidence = replace(f.evidence, "historicalOutputConfig", f.ref(other));
        assertThrows(IllegalArgumentException.class, f::load);
    }

    @Test void historicalDevelopmentOrWrongBlockAndWrongArmReceiptAreRejected() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            var f = fixture();
            if (mode == 0) f.receipt.put("formalRun", false);
            if (mode == 1) f.receipt.put("status", "COMPLETED_UNVALIDATED");
            if (mode == 2) f.receipt.put("block_id", "B2");
            if (mode == 3) f.receipt.put("arm", "DEDICATED");
            f.writeReceipt(); assertThrows(IllegalArgumentException.class, f::load);
        }
    }

    @Test void allHistoricalPayloadsAndEvidenceFileAreBound() throws Exception {
        for (String file : List.of("old/run/matsim/passenger_event_fingerprint.csv", "old/run/matsim/passenger_plan_fingerprint.csv",
                "old/run/matsim/iteration_summary.csv", "old/run/matsim/output_config.xml")) {
            var f = fixture(); f.write(file, "changed"); assertThrows(IOException.class, f::load);
        }
        var f = fixture(); var evidenceRef = f.writeEvidence(); f.write("compatibility.json", "{}");
        assertThrows(IOException.class, () -> load(f.root, f.spec, f.newManifest, evidenceRef));
    }

    @Test void testedSourcesMustBeFlatUnchangedAndMatchTheCurrentFrozenBuild() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            var f = fixture(); var map = new TreeMap<>(f.newBuild.sourceFiles());
            switch (mode) {
                case 0 -> map.put(CHANGED, "0".repeat(64));
                case 1 -> map.remove(CHANGED);
                case 2 -> map.remove(TEST_SOURCE);
                case 3 -> map.put("matsim/src/main/java/example/Unknown.java", "1".repeat(64));
                case 4 -> map.put(REMOVED, f.oldBuild.sourceFiles().get(REMOVED));
                default -> throw new AssertionError();
            }
            f.write("evidence/before.json", JSON.writeValueAsString(map)); f.write("evidence/after.json", JSON.writeValueAsString(map)); f.refreshTests();
            assertThrows(IllegalArgumentException.class, f::load, "mode=" + mode);
        }
        var f = fixture(); var map = new TreeMap<>(f.newBuild.sourceFiles()); map.remove("docs/notes.md");
        f.write("evidence/after.json", JSON.writeValueAsString(map)); f.refreshTests(); assertThrows(IllegalArgumentException.class, f::load);
        var g = fixture(); g.write("evidence/before.json", "{\"sourceFiles\":{}}"); g.refreshTests(); assertThrows(IllegalArgumentException.class, g::load);
    }

    @Test void passingCaseMustBeExactUniqueAndReallyPassNotMerelyClaimZeroFailures() throws Exception {
        for (String xml : List.of(
                xml("other", ""),
                xml("checked", "<failure message=\"failed\"/>"),
                xml("checked", "<skipped/>"),
                xml("checked", "<flakyFailure/>"),
                xml("checked", "").replace("tests=\"1\"", "tests=\"2\""),
                xml("checked", "").replace("failures=\"0\"", "failures=\"1\""),
                xml("checked", "").replace("errors=\"0\"", ""),
                xml("checked", "").replace("example.CompatibilityTest", "example.OtherTest"),
                xml("checked", "").replace("tests=\"1\"", "tests=\"2\"").replace("</testsuite>",
                        "<testcase classname=\"example.CompatibilityTest\" name=\"checked\"/></testsuite>"))) {
            var f = fixture(); f.write("evidence/report.xml", xml); f.refreshTests(); assertThrows(IllegalArgumentException.class, f::load, xml);
        }
    }

    @Test void testXmlExternalEntitiesAreRejectedWithoutReadingTheirPayload() throws Exception {
        var f = fixture(); f.write("secret.txt", "MUST_NOT_BE_LOADED");
        String xml = "<!DOCTYPE testsuite [<!ENTITY x SYSTEM \"" + f.root.resolve("secret.txt").toUri() + "\">]>" + xml("checked", "&x;");
        f.write("evidence/report.xml", xml); f.refreshTests(); assertThrows(IOException.class, f::load);
    }

    @Test void boundEndCheckDetectsPayloadChangesAndNewUnmanifestedProductionFiles() throws Exception {
        for (String file : List.of("new/source/" + CHANGED, "old/app.jar", "evidence/report.xml", "old/run/run_receipt.json", "compatibility.json")) {
            var f = fixture(); var bound = f.load(); f.write(file, "changed after successful load"); assertThrows(IOException.class, bound::verifyUnchanged, file);
        }
        var f = fixture(); var bound = f.load(); f.write("new/source/another/src/main/extra.txt", "extra");
        assertThrows(IllegalArgumentException.class, bound::verifyUnchanged);
    }

    @Test void sameBytesSymlinkRetargetIsNotAnUnchangedBoundIdentity() throws Exception {
        var f = fixture(); Path alias = f.root.resolve("current-manifest-link.json"); Files.createSymbolicLink(alias, f.newManifest);
        f.evidence = replace(f.evidence, "currentBuildManifest", f.ref(alias)); var bound = f.load();
        Path copy = f.write("current-copy.json", Files.readString(f.newManifest)); Files.delete(alias); Files.createSymbolicLink(alias, copy);
        assertThrows(IOException.class, bound::verifyUnchanged);
    }

    @Test void completeProductionWalkRefusesSymlinkDirectoriesThatCouldHideSources() throws Exception {
        var f = fixture(); Files.createSymbolicLink(f.root.resolve("new/source/other"), f.root.resolve("old/source"));
        assertThrows(IOException.class, f::load);
    }

    private Fixture fixture() throws Exception { return new Fixture(temporary.resolve("fixture-" + sequence++)); }
    private static String xml(String name, String children) { return "<testsuite name=\"example.CompatibilityTest\" tests=\"1\" errors=\"0\" failures=\"0\" skipped=\"0\"><testcase classname=\"example.CompatibilityTest\" name=\"" + name + "\">" + children + "</testcase></testsuite>"; }

    private static final class Fixture {
        final Path root, oldManifest, newManifest, oldRun;
        final DynamicMixedExperimentSpec spec;
        BuildIdentity oldBuild, newBuild;
        final ObjectNode receipt;
        Evidence evidence;
        TestEvidence test;
        Fixture(Path directory) throws Exception {
            root = Files.createDirectories(directory).toRealPath(); oldManifest = root.resolve("old/build_manifest.json");
            newManifest = root.resolve("new/build_manifest.json"); oldRun = root.resolve("old/run");
            var toy = DynamicMixedSpecFixtures.spec(); spec = replace(toy, "design", replace(toy.design(), "protocol", new DynamicMixedDesign.Protocol(0, 20, 150, 199, 170, 199)));
            for (String side : List.of("old", "new")) {
                write(side + "/source/" + CHANGED, side + " source"); write(side + "/source/" + SAME, "shared resource");
                write(side + "/source/pom.xml", "same build"); write(side + "/source/.mvn/wrapper/maven-wrapper.properties", "same wrapper");
                write(side + "/source/docs/notes.md", side + " documentation, deliberately not a production change");
                write(side + "/app.jar", side + " application payload fixture, not a runnable jar");
                write(side + "/dep-one.jar", "external one"); write(side + "/dep-two.jar", "external two");
            }
            write("old/source/" + REMOVED, "removed resource"); write("new/source/" + ADDED, "added production source");
            write("new/source/" + TEST_SOURCE, "test source identity fixture");
            oldBuild = build("old"); newBuild = build("new");
            write("old/build_manifest.json", JSON.writeValueAsString(oldBuild)); write("new/build_manifest.json", JSON.writeValueAsString(newBuild));
            var legacy = new ExperimentSpec(ExperimentSpec.SCHEMA, "historical-toy", spec.blockId(), ExperimentSpec.Arm.MIXED,
                    spec.seeds(), spec.inputs(), new ExperimentSpec.Protocol(0, 20, 0, 20, 150, 199, 170, 199,
                    spec.runtime().behaviouralHorizonSeconds(), spec.runtime().executionHorizonSeconds()), spec.freightDemand(), spec.policies(),
                    new ExperimentSpec.OutputOptions(IsmtRunArtifactWriter.SCHEMA, ExperimentSpec.ExposureOutput.REPORTING_WINDOW, false, 0, 0), "synthetic-file-only");
            write("old/run/experiment_spec.json", new String(ExperimentSpecIO.canonicalBytes(legacy), java.nio.charset.StandardCharsets.UTF_8));
            for (String name : List.of("passenger_event_fingerprint.csv", "passenger_plan_fingerprint.csv", "iteration_summary.csv")) write("old/run/matsim/" + name, "synthetic bytes, not scientifically validated rows\n");
            write("old/run/matsim/output_config.xml", "<config/>");
            receipt = JSON.createObjectNode().put("schema_version", "ismt-run-v1").put("formalRun", true).put("status", "VALIDATED")
                    .put("experiment_id", legacy.experimentId()).put("block_id", "B1").put("arm", "MIXED")
                    .put("spec_sha256", ExperimentSpecIO.sha256(legacy)).put("source_sha256", oldBuild.sourceSha256())
                    .put("build_sha256", oldBuild.applicationJar().sha256()).put("dependency_sha256", oldBuild.dependencySha256())
                    .put("build_manifest_path", oldManifest.toString()).put("build_manifest_sha256", ref(oldManifest).sha256());
            var output = receipt.putObject("output_files");
            for (String path : List.of("experiment_spec.json", "matsim/passenger_event_fingerprint.csv", "matsim/passenger_plan_fingerprint.csv", "matsim/iteration_summary.csv", "matsim/output_config.xml")) {
                Path file = oldRun.resolve(path); output.putObject(path).put("bytes", Files.size(file)).put("sha256", ref(file).sha256());
            }
            write("old/run/run_receipt.json", JSON.writeValueAsString(receipt));
            write("evidence/before.json", JSON.writeValueAsString(newBuild.sourceFiles())); write("evidence/after.json", JSON.writeValueAsString(newBuild.sourceFiles()));
            write("evidence/report.xml", xml("checked", "")); test = test();
            var reviews = List.of(
                    new SourceReview(CHANGED, oldBuild.sourceFiles().get(CHANGED), newBuild.sourceFiles().get(CHANGED), Classification.NATIVE_SUPPORTED_DOMAIN_CHANGE,
                            "Synthetic test domain only", "Illustrates a scoped review judgment, not runtime equivalence", List.of(test)),
                    new SourceReview(ADDED, null, newBuild.sourceFiles().get(ADDED), Classification.ADDITIVE_T_ONLY,
                            "Explicit T hook only", "Synthetic addition, not an experimental finding", List.of(test)),
                    new SourceReview(REMOVED, oldBuild.sourceFiles().get(REMOVED), null, Classification.REMOVAL_WITHIN_DECLARED_DOMAIN,
                            "Synthetic removed resource", "Fixture deletion reviewed, not hidden by inventory filtering", List.of(test)));
            evidence = new Evidence(SCHEMA, DynamicMixedExperimentSpecIO.sha256(spec), ref(newManifest), history(), ref(oldManifest), ref(oldRun.resolve("matsim/output_config.xml")), reviews);
        }
        Path write(String path, String contents) throws IOException { Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, contents); return file; }
        ExperimentSpec.FileRef ref(Path file) throws IOException { return new ExperimentSpec.FileRef(root.relativize(file).toString(), ExperimentSpecIO.sha256(Files.readAllBytes(file))); }
        TestEvidence test() throws IOException { return new TestEvidence(ref(root.resolve("evidence/before.json")), ref(root.resolve("evidence/after.json")), ref(root.resolve("evidence/report.xml")), "example.CompatibilityTest", "checked"); }
        DynamicMixedHistoricalHistoryGuard.HistoricalFiles history() throws IOException { return new DynamicMixedHistoricalHistoryGuard.HistoricalFiles(ref(oldRun.resolve("experiment_spec.json")), ref(oldRun.resolve("run_receipt.json")), ref(oldRun.resolve("matsim/passenger_event_fingerprint.csv")), ref(oldRun.resolve("matsim/passenger_plan_fingerprint.csv")), ref(oldRun.resolve("matsim/iteration_summary.csv"))); }
        void refreshTests() throws IOException { test = test(); evidence = replace(evidence, "sourceReviews", evidence.sourceReviews().stream().map(r -> replace(r, "testEvidence", List.of(test))).toList()); }
        void writeReceipt() throws IOException { write("old/run/run_receipt.json", JSON.writeValueAsString(receipt)); evidence = replace(evidence, "historicalFiles", history()); }
        void writeNewManifest() throws IOException { write("new/build_manifest.json", JSON.writeValueAsString(newBuild)); evidence = replace(evidence, "currentBuildManifest", ref(newManifest)); }
        ExperimentSpec.FileRef writeEvidence() throws IOException { return ref(write("compatibility.json", JSON.writeValueAsString(evidence))); }
        Bound load() throws IOException { return DynamicMixedFormalCompatibility.load(root, spec, newManifest, writeEvidence()); }
        BuildIdentity build(String side) throws IOException {
            var files = new TreeMap<String, String>(); Path source = root.resolve(side + "/source");
            try (var paths = Files.walk(source)) { for (Path file : paths.filter(Files::isRegularFile).toList()) files.put(source.relativize(file).toString(), ref(file).sha256()); }
            var jars = List.of(ref(root.resolve(side + "/app.jar")), ref(root.resolve(side + "/dep-one.jar")), ref(root.resolve(side + "/dep-two.jar")));
            return new BuildIdentity("ismt-build-v1", side + "/source", (side.equals("old") ? "a" : "b").repeat(40),
                    ExperimentSpecIO.sha256(JSON.writeValueAsBytes(files)), files, jars.getFirst(), jars,
                    ExperimentSpecIO.sha256(JSON.writeValueAsBytes(jars)), "2026.0", "25.0.3", "test", "test", "test",
                    List.of("not executed"), "synthetic", true);
        }
    }
}
