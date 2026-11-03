package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ExperimentSpecIO;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Small temporary configuration files only: no Controller, QSim, Scenario or actual study data. */
class HistoricalMixedResolvedConfigurationAuditTest {
    @TempDir Path temp;

    private record Fixture(Path workspace, Path specFile, Path historicalXml, Path template,
                           Path prospectiveOutput, ExperimentSpec spec) {
        HistoricalMixedResolvedConfigurationAudit.Report inspect() throws IOException {
            return HistoricalMixedResolvedConfigurationAudit.inspect(workspace, specFile, historicalXml, prospectiveOutput);
        }
    }

    @Test void completeCurrentCommonTreeIsComparedWithoutConstructingAnyDynamicSpec() throws IOException {
        var fixture = fixture(temp, ignored -> {}); var report = fixture.inspect();
        assertEquals(ExperimentSpec.SCHEMA, report.historicalIdentity().schemaVersion());
        assertEquals("MIXED", report.historicalIdentity().arm()); assertEquals("B1", report.historicalIdentity().blockId());
        assertEquals(12, report.declaredInputs().files().size()); assertEquals(1, report.declaredInputs().uniqueFiles());
        assertEquals(Files.size(fixture.template()), report.declaredInputs().uniqueBytesHashed());
        assertEquals(ResolvedNonDispatchConfiguration.SCHEMA, report.historicalTree().schemaVersion());
        assertEquals("7", report.currentCommonTree().fields().get("/modules/=qsim/params/=numberOfThreads"));
        assertEquals("7", report.currentCommonTree().fields().get("/modules/=global/params/=numberOfThreads"));
        assertEquals("null", report.currentCommonTree().fields().get("/modules/=eventsManager/params/=numberOfThreads"));
        assertEquals("150", report.currentCommonTree().fields().get("/modules/=coTransport/params/=innovationFinalOffIteration"));
        assertFalse(report.strictlyEqual());
        assertTrue(report.differences().stream().allMatch(d -> d.path().equals("/context")
                || d.path().equals("/modules/=controller/params/=outputDirectory")), report.differences().toString());
        assertFalse(Files.exists(fixture.prospectiveOutput()));
        assertFalse(report.formalRunStarted()); assertFalse(report.newDynamicSpecMatched());
        assertFalse(report.sourceBuildRuntimeCompatibilityVerified()); assertFalse(report.historicalReceiptBindingVerified());
    }

    @Test void rawAndCanonicalSpecIdentitiesAreRetainedRatherThanAssumedIdentical() throws IOException {
        var fixture = fixture(temp, ignored -> {}); var report = fixture.inspect();
        assertEquals(ExperimentConfiguration.sha256(fixture.specFile()), report.historicalIdentity().rawSpecSha256());
        assertEquals(ExperimentSpecIO.sha256(fixture.spec()), report.historicalIdentity().canonicalSpecSha256());
        assertNotEquals(report.historicalIdentity().rawSpecSha256(), report.historicalIdentity().canonicalSpecSha256(),
                "The toy captured file deliberately uses pretty-print bytes, unlike the canonical hash");
        assertEquals(report.historicalIdentity().rawSpecSha256(), report.specificationFile().sha256());
    }

    @Test void positionalNotesRetainEveryDifferenceAndDoNotApproveContextOrOutputAliases() throws IOException {
        var report = fixture(temp, ignored -> {}).inspect();
        assertEquals(2, report.positionalNotes().size());
        assertEquals(report.differences().size(), report.differences().stream().filter(d -> d.explanation() == null).count());
        for (var note : report.positionalNotes()) assertTrue(report.differences().stream().anyMatch(d -> d.path().equals(note.path())
                && d.historical().equals(note.historical()) && d.target().equals(note.current())));
        assertFalse(report.strictlyEqual());
    }

    @Test void realScientificParameterDifferencesAreNotNormalisedOrExplainedAway() throws IOException {
        var report = fixture(temp, config -> {
            config.qsim().setNumberOfThreads(1); config.global().setNumberOfThreads(2);
            config.eventsManager().setNumberOfThreads(1); config.eventsManager().setSynchronizeOnSimSteps(false);
            config.scoring().setBrainExpBeta(8);
            ((CoTransportConfigGroup) config.getModules().get(CoTransportConfigGroup.GROUP_NAME)).setInnovationFinalOffIteration(149);
        }).inspect();
        for (String path : List.of("/modules/=qsim/params/=numberOfThreads", "/modules/=global/params/=numberOfThreads",
                "/modules/=eventsManager/params/=numberOfThreads", "/modules/=eventsManager/params/=synchronizeOnSimSteps",
                "/modules/=scoring/params/=brainExpBeta", "/modules/=coTransport/params/=innovationFinalOffIteration")) {
            assertTrue(report.differences().stream().anyMatch(d -> d.path().equals(path)), path);
            assertFalse(report.positionalNotes().stream().anyMatch(note -> note.path().equals(path)), path);
        }
        assertTrue(report.differences().stream().allMatch(d -> d.explanation() == null));
    }

    @Test void orderedStrategySetsAndUnknownModuleValuesRemainInCompleteTree() throws IOException {
        var report = fixture(temp, config -> {
            var strategies = new ArrayList<>(config.replanning().getStrategySettings()); Collections.reverse(strategies);
            config.replanning().clearStrategySettings(); strategies.forEach(config.replanning()::addStrategySettings);
            var extra = new ConfigGroup("unknownScientificModule"); extra.addParam("coefficient", "9");
            var nested = new ConfigGroup("nested"); nested.addParam("alpha", "3"); extra.addParameterSet(nested); config.addModule(extra);
        }).inspect();
        assertTrue(report.differences().stream().anyMatch(d -> d.path().startsWith("/modules/=replanning/parameterSets/")));
        assertEquals("3", report.historicalTree().fields().get("/modules/=unknownScientificModule/parameterSets/=nested/0/params/=alpha"));
        assertFalse(report.currentCommonTree().fields().containsKey("/modules/=unknownScientificModule/@name"));
        assertTrue(report.differences().stream().anyMatch(d -> d.path().contains("unknownScientificModule")));
    }

    @Test void pathAliasesInScientificInputsAreDifferencesEvenWhenTheirBytesAreEqual() throws IOException {
        var fixture = fixture(temp, config -> config.network().setInputFile(temp.resolve("alias-network.xml").toString()));
        Files.copy(fixture.template(), temp.resolve("alias-network.xml"));
        var report = fixture.inspect();
        String path = "/modules/=network/params/=inputNetworkFile";
        assertTrue(report.differences().stream().anyMatch(d -> d.path().equals(path)), report.differences().toString());
        assertFalse(report.positionalNotes().stream().anyMatch(note -> note.path().equals(path)));
    }

    @Test void inputHashingDeduplicatesByRealPathAndNotMerelyDeclaredDigest() throws IOException {
        var fixture = fixture(temp, ignored -> {});
        Path second = temp.resolve("same-bytes-distinct-file.xml"); Files.copy(fixture.template(), second);
        var ref = new ExperimentSpec.FileRef(second.getFileName().toString(), ExperimentConfiguration.sha256(second));
        var changed = replace(fixture.spec(), "inputs", replace(fixture.spec().inputs(), "network", ref));
        Files.delete(fixture.specFile()); ExperimentSpecIO.writeNew(fixture.specFile(), changed);
        var report = fixture.inspect();
        assertEquals(2, report.declaredInputs().uniqueFiles());
        assertEquals(2 * Files.size(second), report.declaredInputs().uniqueBytesHashed());
        assertEquals(12, report.declaredInputs().files().size());
    }

    @Test void symlinkAliasesWithinWorkspaceShareOneActualPayload() throws IOException {
        var fixture = fixture(temp, ignored -> {}); Path alias = temp.resolve("inside-alias.xml"); Files.createSymbolicLink(alias, fixture.template());
        var ref = new ExperimentSpec.FileRef(alias.getFileName().toString(), ExperimentConfiguration.sha256(fixture.template()));
        var changed = replace(fixture.spec(), "inputs", replace(fixture.spec().inputs(), "network", ref));
        Files.delete(fixture.specFile()); ExperimentSpecIO.writeNew(fixture.specFile(), changed);
        var report = fixture.inspect(); assertEquals(1, report.declaredInputs().uniqueFiles());
        var file = report.declaredInputs().files().stream().filter(f -> f.role().equals("inputs.network")).findFirst().orElseThrow();
        assertEquals("inside-alias.xml", file.requestedWorkspaceRelativePath()); assertEquals("template.xml", file.realWorkspaceRelativePath());
    }

    @Test void everyRoleIsFreshlyVerifiedBeforeReconstruction() throws IOException {
        var fixture = fixture(temp, ignored -> {});
        for (var field : ExperimentSpec.Inputs.class.getRecordComponents()) {
            var badRef = new ExperimentSpec.FileRef("template.xml", "0".repeat(64));
            var changed = replace(fixture.spec(), "inputs", replace(fixture.spec().inputs(), field.getName(), badRef));
            Files.delete(fixture.specFile()); ExperimentSpecIO.writeNew(fixture.specFile(), changed);
            var error = assertThrows(IOException.class, fixture::inspect, field.getName());
            assertTrue(error.getMessage().contains(field.getName()), error.getMessage());
            assertFalse(Files.exists(fixture.prospectiveOutput()));
        }
    }

    @Test void currentInputTamperingAndMissingFilesCannotProduceAReport() throws IOException {
        var fixture = fixture(temp, ignored -> {}); Files.writeString(fixture.template(), "tampered");
        assertThrows(IOException.class, fixture::inspect);
        Files.delete(fixture.template()); assertThrows(IOException.class, fixture::inspect);
        assertFalse(Files.exists(fixture.prospectiveOutput()));
    }

    @Test void nonMixedLegacyArmIsRejectedWithoutTreatingOldTAsNewT() throws IOException {
        var fixture = fixture(temp, ignored -> {}); var spec = fixture.spec();
        for (var arm : List.of(ExperimentSpec.Arm.CONTROL, ExperimentSpec.Arm.TIMETABLE_ONLY, ExperimentSpec.Arm.DEDICATED)) {
            boolean freight = arm == ExperimentSpec.Arm.DEDICATED;
            var demand = replace(spec.freightDemand(), "orderCount", freight ? 1 : 0);
            var policies = replace(spec.policies(), "access", freight ? ExperimentSpec.Access.DEDICATED_ONLY : ExperimentSpec.Access.NO_FREIGHT);
            policies = replace(policies, "dedicatedLineId", arm == ExperimentSpec.Arm.CONTROL ? "" : "dedicated");
            var alternate = new ExperimentSpec(ExperimentSpec.SCHEMA, spec.experimentId(), spec.blockId(), arm, spec.seeds(), spec.inputs(),
                    spec.protocol(), demand, policies, spec.output(), spec.validationProfile());
            Files.delete(fixture.specFile()); ExperimentSpecIO.writeNew(fixture.specFile(), alternate);
            assertThrows(IllegalArgumentException.class, fixture::inspect);
        }
    }

    @Test void sourceFilesRemainByteIdenticalAndProspectiveDirectoryIsNeverCreated() throws IOException {
        var fixture = fixture(temp, ignored -> {});
        var paths = List.of(fixture.specFile(), fixture.historicalXml(), fixture.template());
        var before = new ArrayList<String>(); for (Path path : paths) before.add(ExperimentConfiguration.sha256(path));
        var report = fixture.inspect();
        for (int index = 0; index < paths.size(); index++) assertEquals(before.get(index), ExperimentConfiguration.sha256(paths.get(index)));
        assertEquals(before.get(1), report.historicalConfigFile().sha256());
        assertEquals(before.get(2), report.currentTemplateFile().sha256()); assertFalse(Files.exists(fixture.prospectiveOutput()));
    }

    @Test void reportAndTreesAreImmutableIncludingNullValuedFields() throws IOException {
        var report = fixture(temp, ignored -> {}).inspect();
        assertThrows(UnsupportedOperationException.class, () -> report.currentCommonTree().fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.historicalTree().fields().put("x", null));
        assertThrows(UnsupportedOperationException.class, () -> report.differences().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.positionalNotes().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.declaredInputs().files().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.unverifiedBoundaries().clear());
    }

    @Test void newJsonReportSerializesTheCompleteFieldsAndCannotOverwriteExistingFiles() throws IOException {
        var fixture = fixture(temp, ignored -> {}); var report = fixture.inspect(); Path output = temp.resolve("report.json");
        HistoricalMixedResolvedConfigurationAudit.writeNewReport(temp, output, report);
        var json = JsonMapper.builder().build().readTree(output.toFile());
        assertEquals(HistoricalMixedResolvedConfigurationAudit.SCHEMA, json.get("schemaVersion").asText());
        assertEquals(report.currentCommonTree().fields().size(), json.get("currentCommonTree").get("fields").size());
        assertEquals(report.differences().size(), json.get("differences").size());
        assertEquals(12, json.get("declaredInputs").get("files").size()); assertFalse(json.get("formalRunStarted").asBoolean());
        String hash = ExperimentConfiguration.sha256(output);
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.writeNewReport(temp, output, report));
        assertEquals(hash, ExperimentConfiguration.sha256(output));
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.writeNewReport(temp, temp.resolve("missing/report.json"), report));
        assertFalse(Files.exists(temp.resolve("missing")));
    }

    @Test void allInputAndOutputPathsAreContainedIncludingSymlinkAndDanglingCases() throws IOException {
        Path root = Files.createDirectory(temp.resolve("scope")); var fixture = fixture(root, ignored -> {});
        Path outside = temp.resolve("outside.xml"); Files.copy(fixture.historicalXml(), outside);
        Files.createSymbolicLink(root.resolve("escape"), temp);
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.inspect(root, fixture.specFile(), outside, fixture.prospectiveOutput()));
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.inspect(root, fixture.specFile(), root.resolve("escape/outside.xml"), fixture.prospectiveOutput()));
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.inspect(root, fixture.specFile(), fixture.historicalXml(), root.resolve("escape/not-created")));
        Files.createSymbolicLink(root.resolve("dangling"), root.resolve("absent"));
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.inspect(root, fixture.specFile(), fixture.historicalXml(), root.resolve("dangling")));
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.inspect(root, fixture.specFile(), fixture.historicalXml(), fixture.template()));
        var report = fixture.inspect();
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.writeNewReport(root, root.resolve("escape/report.json"), report));
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.writeNewReport(temp, temp.resolve("report.json"), report));
    }

    @Test void explicitCliWritesOnlyItsNewReportAndRejectsWrongArgumentCount() throws IOException {
        var fixture = fixture(temp, ignored -> {});
        assertThrows(IllegalArgumentException.class, () -> HistoricalMixedResolvedConfigurationAudit.main(new String[]{temp.toString()}));
        HistoricalMixedResolvedConfigurationAudit.main(new String[]{temp.toString(), temp.relativize(fixture.specFile()).toString(),
                temp.relativize(fixture.historicalXml()).toString(), "never-created-simulation", "cli-report.json"});
        assertTrue(Files.isRegularFile(temp.resolve("cli-report.json"))); assertFalse(Files.exists(temp.resolve("never-created-simulation")));
        assertFalse(Files.exists(fixture.prospectiveOutput()));
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.main(new String[]{temp.toString(), fixture.specFile().toString(),
                fixture.historicalXml().toString(), fixture.prospectiveOutput().toString(), temp.resolve("cli-report.json").toString()}));
    }

    @Test void initializedWrapperRetainsRawDiffAndRechecksExactSmallSourcesWithoutChangingFiles() throws IOException {
        var fixture = fixture(temp, Config::checkConsistency);
        byte[] oldXml = Files.readAllBytes(fixture.historicalXml()), template = Files.readAllBytes(fixture.template());
        var wrapper = HistoricalMixedResolvedConfigurationAudit.inspectInitialized(temp, fixture.specFile(), fixture.historicalXml(), fixture.prospectiveOutput());
        assertEquals(HistoricalMixedResolvedConfigurationAudit.INITIALIZED_SCHEMA, wrapper.schemaVersion());
        assertEquals(12, wrapper.rawReport().declaredInputs().files().size());
        assertTrue(wrapper.rawReport().differences().size() > 2);
        assertEquals(wrapper.rawReport().differences(), wrapper.initReport().rawDifferences());
        assertEquals(wrapper.rawReport().historicalTree().fields(), wrapper.initReport().historical().raw().fields());
        assertEquals(wrapper.rawReport().currentCommonTree().fields(), wrapper.initReport().current().raw().fields());
        assertEquals(List.of("/context", "/modules/=controller/params/=outputDirectory"),
                wrapper.initReport().initializedDifferences().stream().map(ResolvedNonDispatchConfiguration.Difference::path).toList());
        assertEquals(3, wrapper.checkedSmallSources().size());
        assertEquals(wrapper.rawReport().specificationFile(), wrapper.checkedSmallSources().get(0).file());
        assertEquals(wrapper.rawReport().historicalConfigFile(), wrapper.checkedSmallSources().get(1).file());
        assertEquals(wrapper.rawReport().currentTemplateFile(), wrapper.checkedSmallSources().get(2).file());
        assertFalse(wrapper.formalRunStarted()); assertFalse(wrapper.historicalReuseApproved());
        assertThrows(UnsupportedOperationException.class, () -> wrapper.checkedSmallSources().clear());
        assertArrayEquals(oldXml, Files.readAllBytes(fixture.historicalXml())); assertArrayEquals(template, Files.readAllBytes(fixture.template()));
        assertFalse(Files.exists(fixture.prospectiveOutput()));
    }

    @Test void initializedWrapperKeepsScientificDifferencesAndAllInputVerificationGates() throws IOException {
        var fixture = fixture(temp, config -> { config.checkConsistency(); config.qsim().setNumberOfThreads(1); });
        var wrapper = HistoricalMixedResolvedConfigurationAudit.inspectInitialized(temp, fixture.specFile(), fixture.historicalXml(), fixture.prospectiveOutput());
        assertTrue(wrapper.initReport().initializedDifferences().stream().anyMatch(d -> d.path().equals("/modules/=qsim/params/=numberOfThreads")));
        Path report = temp.resolve("initialized.json"); HistoricalMixedResolvedConfigurationAudit.writeNewInitializedReport(temp, report, wrapper);
        String digest = ExperimentConfiguration.sha256(report);
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.writeNewInitializedReport(temp, report, wrapper));
        assertEquals(digest, ExperimentConfiguration.sha256(report));
        Files.writeString(fixture.template(), "changed declared input");
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.inspectInitialized(temp, fixture.specFile(), fixture.historicalXml(), fixture.prospectiveOutput()));
        assertFalse(Files.exists(fixture.prospectiveOutput()));
    }

    @Test void sixthCliArgumentWritesDistinctWrapperWhileFiveArgumentsStillWriteOriginalSchema() throws IOException {
        var fixture = fixture(temp, Config::checkConsistency);
        String[] arguments = {temp.toString(), fixture.specFile().toString(), fixture.historicalXml().toString(),
                fixture.prospectiveOutput().toString(), temp.resolve("initialized-cli.json").toString(), "initialized"};
        HistoricalMixedResolvedConfigurationAudit.main(arguments);
        var json = JsonMapper.builder().build().readTree(temp.resolve("initialized-cli.json").toFile());
        assertEquals(HistoricalMixedResolvedConfigurationAudit.INITIALIZED_SCHEMA, json.get("schemaVersion").asText());
        assertEquals(HistoricalMixedResolvedConfigurationAudit.SCHEMA, json.get("rawReport").get("schemaVersion").asText());
        assertEquals(2, json.get("initReport").get("initializedDifferences").size());
        assertEquals(3, json.get("checkedSmallSources").size()); assertFalse(json.get("formalRunStarted").asBoolean());
        assertThrows(IOException.class, () -> HistoricalMixedResolvedConfigurationAudit.main(arguments));
        arguments[4] = temp.resolve("invalid-option.json").toString(); arguments[5] = "skip-differences";
        assertThrows(IllegalArgumentException.class, () -> HistoricalMixedResolvedConfigurationAudit.main(arguments));
        assertFalse(Files.exists(temp.resolve("invalid-option.json")));
        HistoricalMixedResolvedConfigurationAudit.main(new String[]{arguments[0], arguments[1], arguments[2], arguments[3], temp.resolve("raw-cli.json").toString()});
        var raw = JsonMapper.builder().build().readTree(temp.resolve("raw-cli.json").toFile());
        assertEquals(HistoricalMixedResolvedConfigurationAudit.SCHEMA, raw.get("schemaVersion").asText()); assertFalse(raw.has("initReport"));
        assertFalse(Files.exists(fixture.prospectiveOutput()));
    }

    private static Fixture fixture(Path root, Consumer<Config> historicalChange) throws IOException {
        Config template = ConfigUtils.createConfig(new CoTransportConfigGroup(), new PlanAExecutionHorizonConfigGroup());
        template.global().setNumberOfThreads(7); template.qsim().setNumberOfThreads(7); template.qsim().setStartTime(0);
        template.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        template.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        template.replanning().clearStrategySettings();
        template.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(0.7));
        template.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(0.3));
        template.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setSubpopulation("freight").setStrategyName("KeepLastSelected").setWeight(1));
        Path xml = root.resolve("template.xml"); new ConfigWriter(template).write(xml.toString());
        var ref = new ExperimentSpec.FileRef("template.xml", ExperimentConfiguration.sha256(xml));
        var toy = DynamicMixedSpecFixtures.spec();
        var spec = new ExperimentSpec(ExperimentSpec.SCHEMA, "toy-historical-M", "B1", ExperimentSpec.Arm.MIXED, toy.seeds(),
                new ExperimentSpec.Inputs(ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref),
                new ExperimentSpec.Protocol(0, 20, 0, 20, 150, 199, 170, 199, 108000, 129600), toy.freightDemand(), toy.policies(),
                new ExperimentSpec.OutputOptions("ismt-output-v1", ExperimentSpec.ExposureOutput.TERMINAL, false, 199, 199), "toy-not-formal");
        Path specFile = root.resolve("experiment_spec.json"); ExperimentSpecIO.writeNew(specFile, spec);
        Config historical = ExperimentConfiguration.create(spec, root, root.resolve("old-simulation-location"));
        historicalChange.accept(historical); Path historicalDirectory = Files.createDirectory(root.resolve("historical"));
        Path full = historicalDirectory.resolve("output_config.xml"); new ConfigWriter(historical).write(full.toString());
        return new Fixture(root, specFile, full, xml, root.resolve("prospective-output"), spec);
    }
}
