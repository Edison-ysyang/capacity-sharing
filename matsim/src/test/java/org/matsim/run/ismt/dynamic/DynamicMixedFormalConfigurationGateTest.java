package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedResolvedDefinition.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Configuration and small byte fixtures only; never loads a Scenario or runs Controller/QSim. */
class DynamicMixedFormalConfigurationGateTest {
    @TempDir Path temp;
    private final JsonMapper json = JsonMapper.builder().build();
    private int serial;

    @Test void actualLiveV2AndReceiptBoundMCompareWithoutDiscardingNativeRawDifferences() throws Exception {
        Fixture f = fixture(c -> { });
        var before = ResolvedNonDispatchConfiguration.snapshot(f.configuration().config());
        byte[] oldXml = Files.readAllBytes(f.oldConfig().resolve(f.root())), receipt = Files.readAllBytes(f.receipt().resolve(f.root()));
        var report = verify(f); report.requireVerified();
        assertTrue(report.configurationVerified()); assertTrue(report.historicalOutputReceiptBindingVerified());
        assertEquals(before.fields(), ResolvedNonDispatchConfiguration.snapshot(f.configuration().config()).fields());
        assertTrue(report.liveRawUnchanged());
        var history = report.historicalInitializationReport();
        assertTrue(history.rawDifferences().size() > 2, "The complete old output already contains native initialization defaults");
        assertEquals(2, history.initializedDifferences().size());
        assertEquals(List.of(CONTEXT_PATH, OUTPUT_PATH), report.allowedInitializedDifferences().stream().map(
                ResolvedNonDispatchConfiguration.ExactDifferenceExplanation::path).toList());
        assertTrue(report.rejectedInitializedDifferences().isEmpty()); assertTrue(report.issues().isEmpty());
        assertEquals(report.resolvedDefinitionReport().rawSnapshot().fields(), history.current().raw().fields());
        assertEquals(report.resolvedDefinitionReport().initializationAudit().current().initialized().fields(), history.current().initialized().fields());
        assertEquals(report.historicalFilesBefore(), report.historicalFilesAfter());
        assertEquals(report.resolvedDefinitionReport().inputVerification(), report.targetInputsAfter());
        assertEquals(19, report.targetInputsAfter().files().size());
        assertArrayEquals(oldXml, Files.readAllBytes(f.oldConfig().resolve(f.root())));
        assertArrayEquals(receipt, Files.readAllBytes(f.receipt().resolve(f.root())));
        assertFalse(Files.exists(f.runRoot()));
    }

    @Test void allowedPositionsAreTheFourDerivedExactValuesNotPathWildcards() throws Exception {
        Fixture f = fixture(c -> { }); var report = verify(f);
        var byPath = new TreeMap<String, ResolvedNonDispatchConfiguration.ExactDifferenceExplanation>();
        report.allowedInitializedDifferences().forEach(value -> byPath.put(value.path(), value));
        assertEquals(f.oldConfig().resolve(f.root()).toUri().toURL().toExternalForm(), byPath.get(CONTEXT_PATH).historical().text());
        assertEquals(f.spec().inputs().matsimConfig().resolve(f.root()).toUri().toURL().toExternalForm(), byPath.get(CONTEXT_PATH).target().text());
        assertEquals(f.receipt().resolve(f.root()).getParent().resolve("matsim").toString(), byPath.get(OUTPUT_PATH).historical().text());
        assertEquals(f.runRoot().resolve("matsim").toString(), byPath.get(OUTPUT_PATH).target().text());
        assertTrue(byPath.values().stream().allMatch(value -> value.historical().present() && value.target().present()));
    }

    @Test void fullReportSerializesAndCannotPromoteConfigurationChecksToReuseOrRuntimeApproval() throws Exception {
        var report = verify(fixture(c -> { }));
        var tree = json.readTree(json.writeValueAsBytes(report));
        assertEquals(DynamicMixedFormalConfigurationGate.SCHEMA, tree.path("schemaVersion").asText());
        assertTrue(tree.path("configurationVerified").asBoolean());
        assertEquals(report.historicalInitializationReport().rawDifferences().size(),
                tree.path("historicalInitializationReport").path("rawDifferences").size());
        assertEquals(report.historicalInitializationReport().current().initialized().fields().size(),
                tree.path("historicalInitializationReport").path("current").path("initialized").path("fields").size());
        assertFalse(report.sourceBuildRuntimeCompatibilityVerified()); assertFalse(report.historicalReuseApproved());
        assertFalse(report.formalRunAuthorized()); assertFalse(report.simulationStarted()); assertFalse(report.configurationOrInputsSealed());
        assertThrows(UnsupportedOperationException.class, () -> report.allowedInitializedDifferences().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.historicalFilesBefore().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.historicalInitializationReport().current().raw().fields().clear());
    }

    @Test void everyNonPositionalDifferenceRemainsAndFailsTheGateIncludingThreadsScoringAndEventSettings() throws Exception {
        var f = fixture(c -> { c.global().setNumberOfThreads(1); c.qsim().setNumberOfThreads(1);
            c.eventsManager().setEventsQueueSize(12345); c.scoring().setBrainExpBeta(2.5); });
        var report = verify(f); assertFalse(report.configurationVerified());
        var paths = report.rejectedInitializedDifferences().stream().map(ResolvedNonDispatchConfiguration.Difference::path).toList();
        assertTrue(paths.contains("/modules/=global/params/=numberOfThreads"));
        assertTrue(paths.contains("/modules/=qsim/params/=numberOfThreads"));
        assertTrue(paths.contains("/modules/=eventsManager/params/=eventsQueueSize"));
        assertTrue(paths.contains("/modules/=scoring/params/=brainExpBeta"));
        assertEquals(2, report.allowedInitializedDifferences().size());
        assertThrows(IllegalStateException.class, report::requireVerified);
        assertFalse(Files.exists(f.runRoot()));
    }

    @Test void orderedReplanningSetsAreNotNormalizedAway() throws Exception {
        var f = fixture(c -> {
            var settings = new ArrayList<>(c.replanning().getStrategySettings()); c.replanning().clearStrategySettings();
            for (var setting : settings.reversed()) c.replanning().addStrategySettings(setting);
        });
        var report = verify(f);
        assertFalse(report.configurationVerified());
        assertTrue(report.rejectedInitializedDifferences().stream().anyMatch(d -> d.path().contains("/=replanning/parameterSets/")));
        assertThrows(IllegalStateException.class, report::requireVerified);
    }

    @Test void identicalTargetOutputValueInOldXmlStillFailsItsHistoricalIdentityBinding() throws Exception {
        Fixture f = fixture(c -> { }); String targetOutput = f.runRoot().resolve("matsim").toString();
        var changed = rewriteOld(f, config -> config.controller().setOutputDirectory(targetOutput));
        var report = verify(changed);
        assertFalse(report.configurationVerified());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains(OUTPUT_PATH)));
        assertTrue(report.historicalInitializationReport().initializedDifferences().stream().noneMatch(d -> d.path().equals(OUTPUT_PATH)),
                "Equal but historically wrong values must be checked even without an output difference");
        assertThrows(IllegalStateException.class, report::requireVerified);
    }

    @Test void arbitraryOldOutputLocationDoesNotBecomeAnAllowedContextDifference() throws Exception {
        Fixture f = fixture(c -> { }); String unrelated = f.root().resolve("unrelated-output").toString();
        var changed = rewriteOld(f, config -> config.controller().setOutputDirectory(unrelated));
        var report = verify(changed);
        assertFalse(report.configurationVerified());
        assertTrue(report.rejectedInitializedDifferences().stream().anyMatch(d -> d.path().equals(OUTPUT_PATH)));
        assertThrows(IllegalStateException.class, report::requireVerified);
    }

    @Test void modifiedLiveRawConfigIsRejectedRatherThanUsingPreparedSnapshotAsAStandIn() throws Exception {
        var f = fixture(c -> { }); f.configuration().config().scoring().setBrainExpBeta(17);
        var before = ResolvedNonDispatchConfiguration.snapshot(f.configuration().config());
        assertThrows(IllegalArgumentException.class, () -> verify(f));
        assertEquals(before.fields(), ResolvedNonDispatchConfiguration.snapshot(f.configuration().config()).fields());
    }

    @Test void stalePreparedSnapshotIsRejectedEvenWhenTheActualLiveConfigIsCanonical() throws Exception {
        Fixture f = fixture(c -> { }); Config wrong = ConfigUtils.createConfig();
        var stale = new DynamicMixedConfiguration.PreparedConfiguration(f.configuration().config(), f.configuration().inputVerification(),
                ResolvedNonDispatchConfiguration.snapshot(wrong));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedFormalConfigurationGate.verify(f.root(), f.spec(), f.runRoot(), stale, f.oldConfig(), f.receipt()));
    }

    @Test void resolvedDefinitionVerificationRunsBeforeHistoricalFilesAreLoaded() throws Exception {
        Fixture f = fixture(c -> { });
        var development = replace(f.spec(), "runtime", replace(f.spec().runtime(), "resolvedConfiguration",
                new DynamicMixedExperimentSpec.RegisteredDefinition("ismt-development-resolved-context-v1", f.spec().runtime().resolvedConfiguration().file())));
        var missing = new ExperimentSpec.FileRef("missing-historical-file", "0".repeat(64));
        var error = assertThrows(IllegalArgumentException.class, () -> DynamicMixedFormalConfigurationGate.verify(f.root(), development,
                f.runRoot(), f.configuration(), missing, missing));
        assertTrue(error.getMessage().contains("formal full resolved definition"));
    }

    @Test void historicalShaAndReceiptByteCountMustMatchActualBytes() throws Exception {
        Fixture f = fixture(c -> { });
        var wrongHash = new ExperimentSpec.FileRef(f.oldConfig().path(), "0".repeat(64));
        assertThrows(IOException.class, () -> DynamicMixedFormalConfigurationGate.verify(f.root(), f.spec(), f.runRoot(), f.configuration(), wrongHash, f.receipt()));
        for (String shape : List.of("wrong", "text", "fractional", "missing")) {
            var changed = rewriteReceipt(f, node -> {
                ObjectNode output = (ObjectNode) node.path("output_files").path("matsim/output_config.xml");
                switch (shape) {
                    case "wrong" -> output.put("bytes", -1);
                    case "text" -> output.put("bytes", "123");
                    case "fractional" -> output.put("bytes", 123.0);
                    case "missing" -> output.remove("bytes");
                    default -> throw new AssertionError();
                }
            });
            assertThrows(IllegalArgumentException.class, () -> verify(changed));
        }
        var wrongReceiptHash = new ExperimentSpec.FileRef(f.receipt().path(), "0".repeat(64));
        assertThrows(IOException.class, () -> DynamicMixedFormalConfigurationGate.verify(f.root(), f.spec(), f.runRoot(),
                f.configuration(), f.oldConfig(), wrongReceiptHash));
        var wrongRecordedSha = rewriteReceipt(f, node -> ((ObjectNode) node.path("output_files").path("matsim/output_config.xml"))
                .put("bytes", uncheckedSize(f.oldConfig().resolve(f.root()))).put("sha256", "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> verify(wrongRecordedSha));
    }

    @Test void historicalConfigCannotBeReplacedBySameBytesFromAnotherDirectoryOrAlias() throws Exception {
        var f = fixture(c -> { }); Path duplicate = f.root().resolve("duplicate-output-config.xml");
        Files.copy(f.oldConfig().resolve(f.root()), duplicate);
        var ref = new ExperimentSpec.FileRef(f.root().relativize(duplicate).toString(), f.oldConfig().sha256());
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedFormalConfigurationGate.verify(f.root(), f.spec(), f.runRoot(), f.configuration(), ref, f.receipt()));
        Path alias = Files.createSymbolicLink(f.root().resolve("config-alias.xml"), f.oldConfig().resolve(f.root()));
        var aliasRef = new ExperimentSpec.FileRef(f.root().relativize(alias).toString(), f.oldConfig().sha256());
        assertThrows(IOException.class, () -> DynamicMixedFormalConfigurationGate.verify(f.root(), f.spec(), f.runRoot(), f.configuration(), aliasRef, f.receipt()));
    }

    @Test void receiptIdentityAndExactOutputEntryAreRequiredWithoutReclassifyingItsStatus() throws Exception {
        for (String field : List.of("schema_version", "arm", "block_id", "experiment_id", "output_files")) {
            var f = fixture(c -> { });
            var changed = rewriteReceipt(f, node -> {
                if (field.equals("output_files")) node.set(field, json.createObjectNode());
                else node.put(field, field.equals("experiment_id") ? "" : "wrong");
            });
            assertThrows(IllegalArgumentException.class, () -> verify(changed), field);
        }
        var f = rewriteReceipt(fixture(c -> { }), node -> node.put("status", "COMPLETED_UNVALIDATED"));
        var report = verify(f); report.requireVerified();
        assertEquals("COMPLETED_UNVALIDATED", report.historicalIdentity().receiptStatus());
        assertFalse(report.historicalReuseApproved(), "Receipt scientific validity is another gate, not inferred from configuration equality");
    }

    @Test void malformedDuplicateAndTrailingReceiptJsonAreRejectedEvenWithUpdatedHashes() throws Exception {
        for (String kind : List.of("array", "duplicate", "trailing")) {
            var f = fixture(c -> { }); Path path = f.receipt().resolve(f.root());
            String original = Files.readString(path);
            String invalid = switch (kind) {
                case "array" -> "[]";
                case "duplicate" -> original.replaceFirst("\\{", "{\"schema_version\":\"ismt-run-v1\",");
                case "trailing" -> original + " {}";
                default -> throw new AssertionError();
            };
            Files.writeString(path, invalid);
            Fixture changed = withReceipt(f, fileRef(f.root(), path));
            if (kind.equals("array")) assertThrows(IllegalArgumentException.class, () -> verify(changed));
            else assertThrows(IOException.class, () -> verify(changed));
        }
    }

    @Test void changedTargetBytesCannotUseThePreparedInputReceiptAsAuthority() throws Exception {
        var f = fixture(c -> { }); Files.writeString(f.spec().inputs().matsimConfig().resolve(f.root()), "changed XML bytes");
        assertThrows(IOException.class, () -> verify(f));
        assertFalse(Files.exists(f.runRoot()));
    }

    private record Fixture(Path root, DynamicMixedExperimentSpec spec, Path runRoot,
                           DynamicMixedConfiguration.PreparedConfiguration configuration, ExperimentSpec.FileRef oldConfig, ExperimentSpec.FileRef receipt) { }
    private DynamicMixedFormalConfigurationGate.Report verify(Fixture f) throws IOException {
        return DynamicMixedFormalConfigurationGate.verify(f.root(), f.spec(), f.runRoot(), f.configuration(), f.oldConfig(), f.receipt());
    }
    private Fixture fixture(Consumer<Config> oldChange) throws Exception {
        Path root = Files.createDirectory(temp.toRealPath().resolve("case-" + serial++)); Path source = root.resolve("source-config.xml");
        Config config = ConfigUtils.createConfig(new CoTransportConfigGroup(), new PlanAExecutionHorizonConfigGroup());
        config.transit().setUseTransit(true); config.qsim().setNumberOfThreads(7); config.global().setNumberOfThreads(7);
        config.qsim().setStartTime(0); config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.replanning().clearStrategySettings();
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(.7));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(.3));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setSubpopulation("freight").setStrategyName("KeepLastSelected").setWeight(1));
        new ConfigWriter(config).write(source.toString()); var ref = fileRef(root, source);
        var inputs = new ExperimentSpec.Inputs(ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref);
        var spec = replace(DynamicMixedSpecFixtures.spec(), "inputs", inputs);
        for (String role : List.of("policyDefinition", "constraintDefinition", "forecastDefinition", "passengerProtectionDefinition", "informationDefinition", "baselineTreatmentDefinition"))
            spec = replace(spec, "control", replace(spec.control(), role, new DynamicMixedExperimentSpec.RegisteredDefinition("toy-opaque-payload", ref)));
        spec = self(spec, new ExperimentSpec.FileRef("resolved.json", "0".repeat(64)));
        Path run = root.resolve("new-T-run");
        var stages = ControllerInitializationConfigurationAudit.compareFresh(loader(root, spec, run), loader(root, spec, run));
        var definition = new Definition(SCHEMA, spec.experimentId(), spec.blockId(), specificationBindingSha256(spec), nonSelfInputFiles(spec),
                new BindingRules(ContextRule.MATSIM_CONFIG_FILE_URL, OutputRule.RUN_ROOT_MATSIM),
                template(stages.current().raw().fields()), template(stages.current().initialized().fields()));
        Path registered = root.resolve("resolved.json"); writeNew(registered, definition); spec = self(spec, fileRef(root, registered));
        var prepared = DynamicMixedConfiguration.prepare(spec, root, run.resolve("matsim"));
        Path oldRun = root.resolve("old-M-run");
        Config old = loader(root, spec, oldRun).get(); oldChange.accept(old);
        ControllerInitializationConfigurationAudit.compareFresh(() -> old, loader(root, spec, oldRun));
        Path oldOutput = Files.createDirectories(oldRun.resolve("matsim"));
        Path oldXml = oldOutput.resolve("output_config.xml"); new ConfigWriter(old).write(oldXml.toString());
        var receipt = json.createObjectNode(); receipt.put("schema_version", "ismt-run-v1"); receipt.put("experiment_id", "historical-namespace");
        receipt.put("block_id", spec.blockId()); receipt.put("arm", "MIXED"); receipt.put("status", "VALIDATED");
        receipt.putObject("output_files").putObject("matsim/output_config.xml")
                .put("sha256", ExperimentConfiguration.sha256(oldXml)).put("bytes", Files.size(oldXml));
        Path receiptPath = oldRun.resolve("run_receipt.json"); Files.write(receiptPath, json.writeValueAsBytes(receipt));
        return new Fixture(root, spec, run, prepared, fileRef(root, oldXml), fileRef(root, receiptPath));
    }
    private Fixture rewriteReceipt(Fixture f, Consumer<ObjectNode> change) throws IOException {
        Path path = f.receipt().resolve(f.root()); ObjectNode node = (ObjectNode) json.readTree(Files.readAllBytes(path));
        change.accept(node); Files.write(path, json.writeValueAsBytes(node)); return withReceipt(f, fileRef(f.root(), path));
    }
    private Fixture rewriteOld(Fixture f, Consumer<Config> change) throws IOException {
        Path path = f.oldConfig().resolve(f.root()); Config config = ConfigUtils.loadConfig(path.toString(), new CoTransportConfigGroup(), new PlanAExecutionHorizonConfigGroup());
        change.accept(config); new ConfigWriter(config).write(path.toString());
        var newRef = fileRef(f.root(), path);
        Fixture updated = new Fixture(f.root(), f.spec(), f.runRoot(), f.configuration(), newRef, f.receipt());
        return rewriteReceipt(updated, node -> ((ObjectNode) node.path("output_files").path("matsim/output_config.xml"))
                .put("sha256", newRef.sha256()).put("bytes", uncheckedSize(path)));
    }
    private static long uncheckedSize(Path path) { try { return Files.size(path); } catch (IOException error) { throw new UncheckedIOException(error); } }
    private static Fixture withReceipt(Fixture f, ExperimentSpec.FileRef ref) { return new Fixture(f.root(), f.spec(), f.runRoot(), f.configuration(), f.oldConfig(), ref); }
    private static ExperimentSpec.FileRef fileRef(Path root, Path path) throws IOException { return new ExperimentSpec.FileRef(root.relativize(path).toString(), ExperimentConfiguration.sha256(path)); }
    private static Supplier<Config> loader(Path root, DynamicMixedExperimentSpec spec, Path run) {
        return () -> { try { return ExperimentConfiguration.create(DynamicMixedConfiguration.commonRequest(spec), root, run.resolve("matsim")); }
        catch (IOException error) { throw new UncheckedIOException(error); } };
    }
    private static TreeTemplate template(Map<String, String> fields) {
        var copy = new TreeMap<>(fields); copy.put(CONTEXT_PATH, CONTEXT_TOKEN); copy.put(OUTPUT_PATH, OUTPUT_TOKEN);
        return new TreeTemplate(ResolvedNonDispatchConfiguration.SCHEMA, copy);
    }
    private static DynamicMixedExperimentSpec self(DynamicMixedExperimentSpec spec, ExperimentSpec.FileRef ref) {
        return replace(spec, "runtime", replace(spec.runtime(), "resolvedConfiguration", new DynamicMixedExperimentSpec.RegisteredDefinition(SCHEMA, ref)));
    }
}
