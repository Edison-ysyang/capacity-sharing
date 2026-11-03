package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedResolvedDefinition.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Small configuration/byte fixtures only. No population, Scenario, QSim or Controller is executed. */
class DynamicMixedResolvedDefinitionTest {
    @TempDir Path temp;
    private final JsonMapper json = JsonMapper.builder().build();
    private int serial;

    @Test void completeRawAndInitializedTreesBindWithNoLiveMutationOrOutputCreation() throws Exception {
        Fixture f = fixture(); Config live = f.loader().get();
        var before = ResolvedNonDispatchConfiguration.snapshot(live);
        var calls = new AtomicInteger();
        var report = read(temp, f.spec()).verify(live, () -> { calls.incrementAndGet(); return f.loader().get(); }, f.runRoot());
        assertEquals(2, calls.get()); assertEquals(before.fields(), ResolvedNonDispatchConfiguration.snapshot(live).fields());
        assertEquals(before.fields(), report.rawSnapshot().fields());
        assertEquals(before.sha256(), report.rawSnapshot().sha256());
        assertEquals(19, report.inputVerification().files().size()); assertEquals(2, report.inputVerification().uniqueFiles());
        assertEquals(f.runRoot().resolve("matsim").toString(), report.outputDirectory());
        assertEquals(f.spec().inputs().matsimConfig().resolve(temp).toFile().toURI().toURL().toExternalForm(), report.context());
        assertEquals(2, report.initializationAudit().current().stages().size());
        assertTrue(report.initializationAudit().current().stages().stream().anyMatch(s -> !s.changes().isEmpty()));
        assertTrue(report.initializationAudit().rawStrictlyEqual()); assertTrue(report.initializationAudit().initializedStrictlyEqual());
        assertTrue(report.registeredResolvedDefinitionSemanticallyVerified());
        assertFalse(report.actualControllerLifecycleVerified()); assertFalse(report.sourceBuildRuntimeCompatibilityVerified());
        assertFalse(report.historicalReuseApproved()); assertFalse(report.formalRunAuthorized());
        assertFalse(report.configurationOrInputsSealed()); assertFalse(report.simulationStarted());
        assertFalse(Files.exists(f.runRoot()));
    }

    @Test void reportSerializesFullTreesAndBoundariesWithoutNativeObjects() throws Exception {
        var f = fixture(); var report = read(temp, f.spec()).verify(f.loader().get(), f.loader(), f.runRoot());
        JsonNode tree = json.readTree(json.writeValueAsBytes(report));
        assertEquals(REPORT_SCHEMA, tree.path("schemaVersion").asText());
        assertTrue(tree.path("registeredResolvedDefinitionSemanticallyVerified").asBoolean());
        assertFalse(tree.path("historicalReuseApproved").asBoolean(true));
        assertEquals(report.rawSnapshot().fields().size(), tree.path("rawSnapshot").path("fields").size());
        assertEquals(report.initializationAudit().current().initialized().fields().size(),
                tree.path("initializationAudit").path("current").path("initialized").path("fields").size());
        assertEquals(19, tree.path("inputVerification").path("files").size());
    }

    @Test void sameDefinitionUsesOnlyTwoExplicitBindingsAtAnotherNewOutput() throws Exception {
        var f = fixture(); Path second = temp.toRealPath().resolve("another-spec-hash-run");
        Supplier<Config> fresh = loader(f.spec(), second);
        var a = read(temp, f.spec()).verify(f.loader().get(), f.loader(), f.runRoot());
        var b = read(temp, f.spec()).verify(fresh.get(), fresh, second);
        var changed = new ArrayList<String>();
        a.rawSnapshot().fields().forEach((key, value) -> {
            if (!java.util.Objects.equals(value, b.rawSnapshot().fields().get(key))) changed.add(key);
        });
        assertEquals(List.of(OUTPUT_PATH), changed); assertNotEquals(a.rawSnapshot().sha256(), b.rawSnapshot().sha256());
        assertEquals(a.specificationBindingSha256(), b.specificationBindingSha256());
    }

    @Test void specificationBindingExcludesOnlySelfFileNotItsSchemaOrAnyOtherMetadata() throws Exception {
        var f = fixture(); String original = specificationBindingSha256(f.spec());
        var moved = self(f.spec(), new ExperimentSpec.FileRef("another-definition.json", "1".repeat(64)));
        assertEquals(original, specificationBindingSha256(moved));
        assertNotEquals(original, specificationBindingSha256(replace(f.spec(), "validationProfile", "different-profile")));
        assertNotEquals(original, specificationBindingSha256(replace(f.spec(), "blockId", "B2")));
        assertNotEquals(original, specificationBindingSha256(replace(f.spec(), "seeds", new ExperimentSpec.Seeds(7, 8, 9))));
        assertNotEquals(original, specificationBindingSha256(replace(f.spec(), "runtime", replace(f.spec().runtime(),
                "resolvedConfiguration", new DynamicMixedExperimentSpec.RegisteredDefinition("other-schema", moved.runtime().resolvedConfiguration().file())))));
        var sameConfigDifferentControl = replace(f.spec(), "design", new DynamicMixedDesign(f.spec().design().arm(),
                f.spec().design().protocol(), false, f.spec().design().limits()));
        assertNotEquals(original, specificationBindingSha256(sameConfigDifferentControl));
        assertThrows(IllegalArgumentException.class, () -> read(temp, sameConfigDifferentControl));
    }

    @Test void developmentWrapperAndContentSchemasCannotMasqueradeAsFormal() throws Exception {
        var f = fixture();
        var development = replace(f.spec(), "runtime", replace(f.spec().runtime(), "resolvedConfiguration",
                new DynamicMixedExperimentSpec.RegisteredDefinition("ismt-development-resolved-context-v1",
                        f.spec().runtime().resolvedConfiguration().file())));
        assertThrows(IllegalArgumentException.class, () -> read(temp, development));
        var node = tree(f.definition()); node.put("schemaVersion", "ismt-development-resolved-context-v1");
        var forged = registerBytes(f.spec(), json.writeValueAsBytes(node));
        assertThrows(IOException.class, () -> read(temp, forged));
    }

    @Test void wrongExperimentAndBlockAreRejectedEvenWithUpdatedFileHash() throws Exception {
        var f = fixture();
        for (String field : List.of("experimentId", "blockId")) {
            var node = tree(f.definition()); node.put(field, "another-identity");
            var spec = registerBytes(f.spec(), json.writeValueAsBytes(node));
            assertThrows(IllegalArgumentException.class, () -> read(temp, spec));
        }
    }

    @Test void exactEighteenRolesCannotBeMissingAddedOrReplacePathWithIdenticalBytes() throws Exception {
        var f = fixture();
        Path alias = temp.resolve("same-content.xml"); Files.copy(f.spec().inputs().matsimConfig().resolve(temp), alias);
        var role = nonSelfInputFiles(f.spec()).keySet().iterator().next();
        for (int variant = 0; variant < 3; variant++) {
            var map = new TreeMap<>(f.definition().inputFiles());
            if (variant == 0) map.remove(role);
            else if (variant == 1) map.put("control.unregisteredExtra", map.get(role));
            else map.put(role, new ExperimentSpec.FileRef("same-content.xml", map.get(role).sha256()));
            var wrong = replace(f.definition(), "inputFiles", map);
            var spec = registerBytes(f.spec(), canonicalBytes(wrong));
            assertThrows(IllegalArgumentException.class, () -> read(temp, spec));
        }
        var own = new TreeMap<>(f.definition().inputFiles()); own.put("runtime.resolvedConfiguration", f.spec().runtime().resolvedConfiguration().file());
        assertThrows(IllegalArgumentException.class, () -> replace(f.definition(), "inputFiles", own));
    }

    @Test void everyActualScientificMutationIsRejectedWithoutFiltering() throws Exception {
        var f = fixture(); Bound bound = read(temp, f.spec());
        List<Consumer<Config>> changes = List.of(c -> c.qsim().setNumberOfThreads(1),
                c -> c.eventsManager().setNumberOfThreads(1), c -> c.scoring().setBrainExpBeta(12),
                c -> ConfigUtils.addOrGetModule(c, CoTransportConfigGroup.class).setFreightShareCap(0.23),
                c -> c.controller().setLastIteration(4), c -> c.network().setInputFile("different.xml"),
                c -> c.addModule(new ConfigGroup("unexpected-module")));
        for (var change : changes) {
            Config live = f.loader().get(); change.accept(live);
            var before = ResolvedNonDispatchConfiguration.snapshot(live);
            assertThrows(IllegalArgumentException.class, () -> bound.verify(live, f.loader(), f.runRoot()));
            assertEquals(before.fields(), ResolvedNonDispatchConfiguration.snapshot(live).fields());
        }
    }

    @Test void initializedValuesAndPositionCountsCannotBeSilentlyIgnored() throws Exception {
        var f = fixture();
        List<Consumer<Map<String, String>>> changes = List.of(
                map -> map.put("/modules/=scoring/params/=brainExpBeta", "12.0"),
                map -> map.put("/modules/@count", "999"),
                map -> map.remove("/modules/=swissRailRaptor/params/=transferWalkMargin"),
                map -> map.put("/unexpected", "not-allowed"));
        for (var change : changes) {
            var fields = new TreeMap<>(f.definition().initializedTree().fields()); change.accept(fields);
            var definition = replace(f.definition(), "initializedTree", new TreeTemplate(ResolvedNonDispatchConfiguration.SCHEMA, fields));
            var spec = registerBytes(f.spec(), canonicalBytes(definition));
            Bound bound = read(temp, spec);
            assertThrows(IllegalArgumentException.class, () -> bound.verify(f.loader().get(), f.loader(), f.runRoot()));
        }
    }

    @Test void matchingWrongTemplatesAndSuppliersCannotDetachInputPathFromSpec() throws Exception {
        var f = fixture();
        Supplier<Config> wrong = () -> {
            Config value = f.loader().get(); value.network().setInputFile(temp.resolve("unregistered-network.xml").toString()); return value;
        };
        var audit = ControllerInitializationConfigurationAudit.compareFresh(wrong, wrong);
        var definition = replace(f.definition(), "rawTree", template(audit.current().raw().fields()));
        definition = replace(definition, "initializedTree", template(audit.current().initialized().fields()));
        var spec = registerBytes(f.spec(), canonicalBytes(definition));
        var failure = assertThrows(IllegalArgumentException.class, () -> read(temp, spec).verify(wrong.get(), wrong, f.runRoot()));
        assertTrue(failure.getMessage().contains("spec/input-bound common assembly"));
    }

    @Test void orderedStrategyPositionsAreNotNormalizedOrSortedAway() throws Exception {
        var f = fixture(); Config live = f.loader().get();
        var strategies = new ArrayList<>(live.replanning().getStrategySettings());
        live.replanning().clearStrategySettings();
        strategies.reversed().forEach(live.replanning()::addStrategySettings);
        var failure = assertThrows(IllegalArgumentException.class, () -> read(temp, f.spec()).verify(live, f.loader(), f.runRoot()));
        assertTrue(failure.getMessage().contains("/modules/=replanning/parameterSets/"));
    }

    @Test void rawCannotBeReplacedByInitializedTreeAndViceVersa() throws Exception {
        var f = fixture();
        var definition = replace(f.definition(), "rawTree", f.definition().initializedTree());
        var spec = registerBytes(f.spec(), canonicalBytes(definition));
        assertThrows(IllegalArgumentException.class, () -> read(temp, spec).verify(f.loader().get(), f.loader(), f.runRoot()));
        definition = replace(f.definition(), "initializedTree", f.definition().rawTree());
        var second = registerBytes(f.spec(), canonicalBytes(definition));
        assertThrows(IllegalArgumentException.class, () -> read(temp, second).verify(f.loader().get(), f.loader(), f.runRoot()));
    }

    @Test void literalMissingAndNullAreDistinctAndTemplatesAreDetached() {
        var source = new LinkedHashMap<String, String>(); source.put(CONTEXT_PATH, CONTEXT_TOKEN); source.put(OUTPUT_PATH, OUTPUT_TOKEN);
        source.put("/nullable", null); source.put("/literal", "null");
        var value = new TreeTemplate(ResolvedNonDispatchConfiguration.SCHEMA, source);
        source.put("/nullable", "mutated"); source.remove("/literal");
        assertTrue(value.fields().containsKey("/nullable")); assertNull(value.fields().get("/nullable"));
        assertEquals("null", value.fields().get("/literal")); assertFalse(value.fields().containsKey("/absent"));
        assertThrows(UnsupportedOperationException.class, () -> value.fields().put("/nullable", "x"));
    }

    @Test void missingAndNullTemplateFieldsAreNotTreatedAsEqual() throws Exception {
        var f = fixture(); var fields = new TreeMap<>(f.definition().rawTree().fields());
        String key = "/modules/=scoring/params/=brainExpBeta";
        for (int variant = 0; variant < 2; variant++) {
            if (variant == 0) fields.put(key, null); else fields.remove(key);
            var def = replace(f.definition(), "rawTree", new TreeTemplate(ResolvedNonDispatchConfiguration.SCHEMA, fields));
            var spec = registerBytes(f.spec(), canonicalBytes(def));
            assertThrows(IllegalArgumentException.class, () -> read(temp, spec).verify(f.loader().get(), f.loader(), f.runRoot()));
        }
    }

    @Test void onlyExactContextAndOutputTokensAreAdmittedNoWildcardOrGlobalReplacement() throws Exception {
        var f = fixture();
        for (String path : List.of(CONTEXT_PATH, OUTPUT_PATH)) {
            var fields = new TreeMap<>(f.definition().rawTree().fields()); fields.put(path, "*");
            assertThrows(IllegalArgumentException.class, () -> new TreeTemplate(ResolvedNonDispatchConfiguration.SCHEMA, fields));
        }
        var fields = new TreeMap<>(f.definition().rawTree().fields()); fields.put("/modules/=network/params/=inputNetworkFile", "prefix-" + OUTPUT_TOKEN);
        assertThrows(IllegalArgumentException.class, () -> new TreeTemplate(ResolvedNonDispatchConfiguration.SCHEMA, fields));
        var node = tree(f.definition()); ((ObjectNode) node.path("bindingRules")).put("context", "IGNORE_CONTEXT");
        var spec = registerBytes(f.spec(), json.writeValueAsBytes(node)); assertThrows(IOException.class, () -> read(temp, spec));
    }

    @Test void wrongContextAndWrongOutputCannotPassMerelyBecauseTreesWouldOtherwiseMatch() throws Exception {
        var f = fixture(); Bound bound = read(temp, f.spec());
        Config wrongContext = f.loader().get(); wrongContext.setContext(temp.resolve("elsewhere.xml").toFile().toURI().toURL());
        assertThrows(IllegalArgumentException.class, () -> bound.verify(wrongContext, f.loader(), f.runRoot()));
        Config wrongOutput = f.loader().get(); wrongOutput.controller().setOutputDirectory(temp.resolve("other-output").toString());
        assertThrows(IllegalArgumentException.class, () -> bound.verify(wrongOutput, f.loader(), f.runRoot()));
    }

    @Test void strictDecoderRejectsUnknownMissingNullCoercedAndTrailingValues() throws Exception {
        var f = fixture(); List<Consumer<ObjectNode>> invalid = List.of(
                node -> node.put("unknown", true), node -> node.remove("rawTree"), node -> node.putNull("initializedTree"),
                node -> node.put("blockId", 1), node -> ((ObjectNode) node.path("rawTree").path("fields")).put("/modules/@count", 12),
                node -> ((ObjectNode) node.path("bindingRules")).put("context", 0),
                node -> ((ObjectNode) node.path("inputFiles").elements().next()).put("sha256", true));
        for (var change : invalid) {
            var node = tree(f.definition()); change.accept(node);
            var spec = registerBytes(f.spec(), json.writeValueAsBytes(node)); assertThrows(IOException.class, () -> read(temp, spec));
        }
        byte[] correct = canonicalBytes(f.definition());
        var trailing = registerBytes(f.spec(), (new String(correct, java.nio.charset.StandardCharsets.UTF_8) + " {}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> read(temp, trailing));
        var empty = registerBytes(f.spec(), "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> read(temp, empty));
    }

    @Test void duplicateKeysAtRootAndNestedFullTreeFail() throws Exception {
        var f = fixture(); String text = new String(canonicalBytes(f.definition()), java.nio.charset.StandardCharsets.UTF_8);
        for (String malformed : List.of("{\"blockId\":\"B1\"," + text.substring(1),
                text.replace("\"/context\":", "\"/context\":\"duplicate\",\"/context\":"))) {
            var spec = registerBytes(f.spec(), malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> read(temp, spec));
        }
    }

    @Test void fileHashTamperingIsDetectedAtReadAndAllRolesAtVerify() throws Exception {
        var f = fixture(); Bound bound = read(temp, f.spec());
        Path definition = f.spec().runtime().resolvedConfiguration().file().resolve(temp);
        byte[] before = Files.readAllBytes(definition); Files.writeString(definition, "{}");
        assertThrows(IOException.class, () -> read(temp, f.spec()));
        assertThrows(IOException.class, () -> bound.verify(f.loader().get(), f.loader(), f.runRoot()));
        Files.write(definition, before);
        Path payload = f.spec().inputs().network().resolve(temp); Files.writeString(payload, "different bytes");
        // Capture live before the invalid source is parsed; verification still precedes fresh loading.
        assertThrows(IOException.class, () -> bound.verify(ConfigUtils.createConfig(), () -> { throw new AssertionError("must not load"); }, f.runRoot()));
    }

    @Test void changedSmallInputDuringFreshLoadingIsDetectedAfterReplay() throws Exception {
        var f = fixture(); Config live = f.loader().get(); Config a = f.loader().get(), b = f.loader().get();
        Bound bound = read(temp, f.spec()); var calls = new AtomicInteger();
        assertThrows(IOException.class, () -> bound.verify(live, () -> {
            if (calls.incrementAndGet() == 1) return a;
            try { Files.writeString(f.spec().inputs().matsimConfig().resolve(temp), "changed after hash read"); }
            catch (IOException error) { throw new UncheckedIOException(error); }
            return b;
        }, f.runRoot()));
    }

    @Test void loaderCannotReturnLiveSameCopiesSharedGroupsOrDifferentRawTrees() throws Exception {
        var f = fixture(); Config live = f.loader().get(); Bound bound = read(temp, f.spec());
        var before = ResolvedNonDispatchConfiguration.snapshot(live);
        assertThrows(IllegalArgumentException.class, () -> bound.verify(live, () -> live, f.runRoot()));
        Config same = f.loader().get(); assertThrows(IllegalArgumentException.class, () -> bound.verify(live, () -> same, f.runRoot()));
        assertThrows(IllegalArgumentException.class, () -> bound.verify(live, () -> {
            Config fresh = f.loader().get(); fresh.getModules().put("scoring", live.scoring()); return fresh;
        }, f.runRoot()));
        assertThrows(IllegalArgumentException.class, () -> bound.verify(live, () -> {
            Config fresh = f.loader().get(); fresh.scoring().setBrainExpBeta(5); return fresh;
        }, f.runRoot()));
        assertEquals(before.fields(), ResolvedNonDispatchConfiguration.snapshot(live).fields());
    }

    @Test void outputMustBeNewContainedAndNonAliasedWithNoDirectoriesCreated() throws Exception {
        var f = fixture(); Bound bound = read(temp, f.spec()); Config live = f.loader().get();
        assertThrows(IllegalArgumentException.class, () -> bound.verify(live, f.loader(), Path.of("relative")));
        assertThrows(IllegalArgumentException.class, () -> bound.verify(live, f.loader(), temp.toRealPath()));
        assertThrows(IllegalArgumentException.class, () -> bound.verify(live, f.loader(), temp.toRealPath().resolve("../outside").normalize()));
        Files.createDirectories(f.runRoot().resolve("matsim"));
        assertThrows(IOException.class, () -> bound.verify(live, f.loader(), f.runRoot()));
        Path real = Files.createDirectory(temp.resolve("real-output-parent"));
        Path alias = Files.createSymbolicLink(temp.resolve("aliased-output-parent"), real);
        assertThrows(IOException.class, () -> bound.verify(live, f.loader(), alias.resolve("new-run")));
        assertFalse(Files.exists(real.resolve("new-run")));
    }

    @Test void canonicalDefinitionBytesAreStableAndWriteNeverOverwrites() throws Exception {
        var f = fixture(); byte[] bytes = canonicalBytes(f.definition());
        var reversed = new LinkedHashMap<String, ExperimentSpec.FileRef>();
        new ArrayList<>(f.definition().inputFiles().keySet()).reversed().forEach(key -> reversed.put(key, f.definition().inputFiles().get(key)));
        assertArrayEquals(bytes, canonicalBytes(replace(f.definition(), "inputFiles", reversed)));
        var parsed = json.treeToValue(json.readTree(bytes), Definition.class);
        assertArrayEquals(bytes, canonicalBytes(parsed));
        Path target = temp.resolve("write-new.json"); writeNew(target, f.definition());
        assertThrows(IOException.class, () -> writeNew(target, f.definition())); assertArrayEquals(bytes, Files.readAllBytes(target));
        assertThrows(UnsupportedOperationException.class, () -> f.definition().inputFiles().clear());
    }

    private record Fixture(DynamicMixedExperimentSpec spec, Definition definition, Path runRoot, Supplier<Config> loader) { }

    private Fixture fixture() throws Exception {
        Path root = temp.toRealPath(); Path configFile = root.resolve("source-config.xml");
        Config config = ConfigUtils.createConfig(new CoTransportConfigGroup(), new PlanAExecutionHorizonConfigGroup());
        config.transit().setUseTransit(true); config.qsim().setNumberOfThreads(7); config.global().setNumberOfThreads(7);
        config.qsim().setStartTime(0); config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.replanning().clearStrategySettings();
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(0.7));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(0.3));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setSubpopulation("freight").setStrategyName("KeepLastSelected").setWeight(1));
        new ConfigWriter(config).write(configFile.toString());
        var ref = new ExperimentSpec.FileRef(configFile.getFileName().toString(), ExperimentConfiguration.sha256(configFile));
        var inputs = new ExperimentSpec.Inputs(ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref);
        var spec = replace(DynamicMixedSpecFixtures.spec(), "inputs", inputs);
        for (String name : List.of("policyDefinition", "constraintDefinition", "forecastDefinition", "passengerProtectionDefinition",
                "informationDefinition", "baselineTreatmentDefinition"))
            spec = replace(spec, "control", replace(spec.control(), name, new DynamicMixedExperimentSpec.RegisteredDefinition("toy-opaque-payload", ref)));
        spec = self(spec, new ExperimentSpec.FileRef("not-yet-written-definition.json", "0".repeat(64)));
        Path runRoot = root.resolve("new-run"); Supplier<Config> fresh = loader(spec, runRoot);
        var stages = ControllerInitializationConfigurationAudit.compareFresh(fresh, fresh);
        Definition definition = new Definition(SCHEMA, spec.experimentId(), spec.blockId(), specificationBindingSha256(spec),
                nonSelfInputFiles(spec), new BindingRules(ContextRule.MATSIM_CONFIG_FILE_URL, OutputRule.RUN_ROOT_MATSIM),
                template(stages.current().raw().fields()), template(stages.current().initialized().fields()));
        spec = registerBytes(spec, canonicalBytes(definition));
        return new Fixture(spec, definition, runRoot, loader(spec, runRoot));
    }
    private Supplier<Config> loader(DynamicMixedExperimentSpec spec, Path runRoot) {
        return () -> {
            try { return ExperimentConfiguration.create(DynamicMixedConfiguration.commonRequest(spec), temp.toRealPath(), runRoot.resolve("matsim")); }
            catch (IOException error) { throw new UncheckedIOException(error); }
        };
    }
    private static TreeTemplate template(Map<String, String> actual) {
        var fields = new TreeMap<>(actual); fields.put(CONTEXT_PATH, CONTEXT_TOKEN); fields.put(OUTPUT_PATH, OUTPUT_TOKEN);
        return new TreeTemplate(ResolvedNonDispatchConfiguration.SCHEMA, fields);
    }
    private ObjectNode tree(Definition value) { return json.valueToTree(value); }
    private DynamicMixedExperimentSpec registerBytes(DynamicMixedExperimentSpec spec, byte[] bytes) throws IOException {
        Path file = temp.resolve("definition-" + serial++ + ".json"); Files.write(file, bytes);
        return self(spec, new ExperimentSpec.FileRef(file.getFileName().toString(), ExperimentConfiguration.sha256(file)));
    }
    private static DynamicMixedExperimentSpec self(DynamicMixedExperimentSpec spec, ExperimentSpec.FileRef file) {
        return replace(spec, "runtime", replace(spec.runtime(), "resolvedConfiguration", new DynamicMixedExperimentSpec.RegisteredDefinition(SCHEMA, file)));
    }
}
