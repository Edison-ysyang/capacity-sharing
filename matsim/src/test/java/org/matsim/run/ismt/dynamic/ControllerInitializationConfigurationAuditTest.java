package org.matsim.run.ismt.dynamic;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Small configuration-only examples. No Controller, Scenario, QSim or scientific population. */
class ControllerInitializationConfigurationAuditTest {
    @TempDir Path temp;
    private static final String ACT = "/modules/=scoring/parameterSets/=scoringParameters/0/parameterSets/=activityParams";

    @Test void exactSevenStageActivitiesAndRaptorExplain109InitializationFieldsWithoutFiltering() {
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> initialized(config()), this::config);
        assertEquals(109, report.rawDifferences().size());
        assertFalse(report.rawStrictlyEqual()); assertTrue(report.initializedStrictlyEqual());
        assertEquals(92, report.current().stages().get(0).changes().size());
        assertEquals(17, report.current().stages().get(1).changes().size());
        assertTrue(report.historical().stages().stream().allMatch(stage -> stage.changes().isEmpty()));
        assertEquals("10", report.current().raw().fields().get(ACT + "/@count"));
        assertEquals("17", report.current().initialized().fields().get(ACT + "/@count"));
        List<String> modes = List.of("car", "ride", "bike", "bus", "pt", "walk", "freight_pt");
        for (int i = 0; i < modes.size(); i++) {
            assertEquals(modes.get(i) + " interaction", report.current().initialized().fields().get(ACT + "/" + (10 + i) + "/params/=activityType"));
            assertEquals("false", report.current().initialized().fields().get(ACT + "/" + (10 + i) + "/params/=scoringThisActivityAtAll"));
        }
        assertFalse(report.actualControllerLifecycleVerified()); assertFalse(report.sourceBuildRuntimeCompatibilityVerified());
        assertFalse(report.historicalReuseApproved()); assertFalse(report.simulationStarted());
    }

    @Test void historical111PatternRetainsContextAndOutputAsTwoInitializedDifferences() throws Exception {
        Config historical = initialized(config()), current = config();
        historical.setContext(temp.resolve("old/config.xml").toUri().toURL());
        current.setContext(temp.resolve("new/config.xml").toUri().toURL());
        historical.controller().setOutputDirectory(temp.resolve("old-output").toString());
        current.controller().setOutputDirectory(temp.resolve("new-output").toString());
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> historical, () -> current);
        assertEquals(111, report.rawDifferences().size()); assertEquals(2, report.initializedDifferences().size());
        assertEquals(List.of("/context", "/modules/=controller/params/=outputDirectory"),
                report.initializedDifferences().stream().map(ResolvedNonDispatchConfiguration.Difference::path).toList());
        assertFalse(report.initializedStrictlyEqual());
        assertFalse(Files.exists(temp.resolve("old-output"))); assertFalse(Files.exists(temp.resolve("new-output")));
    }

    @Test void scientificThreadsScoringAndAdmissionRemainDifferentAfterInitialization() {
        Config old = initialized(config()), target = config();
        old.qsim().setNumberOfThreads(1); target.qsim().setNumberOfThreads(7);
        old.eventsManager().setSynchronizeOnSimSteps(false); target.eventsManager().setSynchronizeOnSimSteps(true);
        old.scoring().setBrainExpBeta(8);
        ConfigUtils.addOrGetModule(old, CoTransportConfigGroup.class).setFreightShareCap(0.2);
        ConfigUtils.addOrGetModule(target, CoTransportConfigGroup.class).setFreightShareCap(0.1);
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> old, () -> target);
        for (String path : List.of("/modules/=qsim/params/=numberOfThreads", "/modules/=eventsManager/params/=synchronizeOnSimSteps",
                "/modules/=scoring/params/=brainExpBeta", "/modules/=coTransport/params/=freightShareCap"))
            assertTrue(report.initializedDifferences().stream().anyMatch(d -> d.path().equals(path)), path);
        assertFalse(report.initializedStrictlyEqual());
    }

    @Test void alreadyConfiguredInteractionParametersAreNeverOverwrittenWithDefaults() {
        Config old = config(), target = config();
        old.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("car interaction")
                .setTypicalDuration(300).setScoringThisActivityAtAll(true));
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> old, () -> target);
        assertEquals("true", report.historical().initialized().fields().get(ACT + "/10/params/=scoringThisActivityAtAll"));
        assertEquals("false", report.current().initialized().fields().get(ACT + "/10/params/=scoringThisActivityAtAll"));
        assertTrue(report.initializedDifferences().stream().anyMatch(d -> d.path().endsWith("/=scoringThisActivityAtAll")));
    }

    @Test void nonDefaultExplicitRaptorParameterSurvivesGenericMaterialization() {
        Config old = config(), target = config();
        var generic = new ConfigGroup("swissRailRaptor"); generic.addParam("transferWalkMargin", "9.0"); old.addModule(generic);
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> old, () -> target);
        assertEquals("9.0", report.historical().initialized().fields().get("/modules/=swissRailRaptor/params/=transferWalkMargin"));
        assertEquals("5.0", report.current().initialized().fields().get("/modules/=swissRailRaptor/params/=transferWalkMargin"));
        assertEquals(1, report.initializedDifferences().size());
        assertInstanceOf(SwissRailRaptorConfigGroup.class, old.getModules().get("swissRailRaptor"));
    }

    @Test void typedRaptorSettingsAndOrderedNestedSetsAreRetained() {
        Config old = config(), target = config();
        var a = ConfigUtils.addOrGetModule(old, SwissRailRaptorConfigGroup.class);
        var b = ConfigUtils.addOrGetModule(target, SwissRailRaptorConfigGroup.class);
        a.addParameterSet(new SwissRailRaptorConfigGroup.ModeToModeTransferPenalty("pt", "freight_pt", 2));
        a.addParameterSet(new SwissRailRaptorConfigGroup.ModeToModeTransferPenalty("freight_pt", "pt", 3));
        b.addParameterSet(new SwissRailRaptorConfigGroup.ModeToModeTransferPenalty("freight_pt", "pt", 3));
        b.addParameterSet(new SwissRailRaptorConfigGroup.ModeToModeTransferPenalty("pt", "freight_pt", 2));
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> old, () -> target);
        assertFalse(report.initializedStrictlyEqual());
        assertTrue(report.initializedDifferences().stream().allMatch(d -> d.path().startsWith("/modules/=swissRailRaptor/parameterSets/")));
        assertTrue(report.historical().stages().get(1).changes().isEmpty());
        assertSame(a, old.getModules().get("swissRailRaptor"));
    }

    @Test void unknownRaptorParametersFailInsteadOfBeingDiscarded() {
        Config old = config(); var raptor = new ConfigGroup("swissRailRaptor");
        raptor.addParam("notARecognizedScientificParameter", "7"); old.addModule(raptor);
        assertThrows(RuntimeException.class, () -> ControllerInitializationConfigurationAudit.compareFresh(() -> old, this::config));
    }

    @Test void noTransitDoesNotMaterializeRaptorOrEraseExistingRaptor() {
        Config old = config(), target = config(); old.transit().setUseTransit(false); target.transit().setUseTransit(false);
        old.addModule(new SwissRailRaptorConfigGroup());
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> old, () -> target);
        assertFalse(report.current().initialized().fields().containsKey("/modules/=swissRailRaptor/@name"));
        assertTrue(report.historical().initialized().fields().containsKey("/modules/=swissRailRaptor/@name"));
        assertEquals(17, report.initializedDifferences().size());
        assertTrue(report.current().stages().get(1).changes().isEmpty());
    }

    @Test void modeSetsAreDerivedNotHardcodedToStudySeven() {
        Config a = config(), b = config();
        for (Config c : List.of(a, b)) {
            c.routing().setNetworkModes(List.of("custom_network"));
            var scoring = c.scoring().getScoringParameters(null);
            new ArrayList<>(scoring.getParameterSets("modeParams")).forEach(scoring::removeParameterSet);
            scoring.addModeParams(new ScoringConfigGroup.ModeParams("custom_teleport"));
            // No pt ModeParams in this deliberately custom-only set: native serialization
            // therefore requires explicit waitingPt instead of its pt-travel-utility fallback.
            scoring.setMarginalUtlOfWaitingPt_utils_hr(-6.0);
        }
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> a, () -> b);
        assertEquals("12", report.current().initialized().fields().get(ACT + "/@count"));
        assertEquals("custom_network interaction", report.current().initialized().fields().get(ACT + "/10/params/=activityType"));
        assertEquals("custom_teleport interaction", report.current().initialized().fields().get(ACT + "/11/params/=activityType"));
    }

    @Test void emptyActivityCollectionStillGetsExactNativeStageTypeAndCount() {
        Config a = config(), b = config();
        for (Config c : List.of(a, b)) {
            var scoring = c.scoring().getScoringParameters(null);
            new ArrayList<>(scoring.getParameterSets("activityParams")).forEach(scoring::removeParameterSet);
        }
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> a, () -> b);
        assertEquals("7", report.current().initialized().fields().get(ACT + "/@count"));
        assertEquals("car interaction", report.current().initialized().fields().get(ACT + "/0/params/=activityType"));
    }

    @Test void eachSubpopulationReceivesOnlyItsOwnMissingModesInNativeOrder() {
        Config a = config(), b = config();
        for (Config c : List.of(a, b)) {
            var defaults = c.scoring().getOrCreateScoringParameters("default");
            defaults.addModeParams(new ScoringConfigGroup.ModeParams("default_mode"));
            defaults.setMarginalUtlOfWaitingPt_utils_hr(-6.0);
            var freight = c.scoring().getOrCreateScoringParameters("freight");
            freight.addModeParams(new ScoringConfigGroup.ModeParams("cargo_mode"));
            freight.setMarginalUtlOfWaitingPt_utils_hr(-6.0);
        }
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> a, () -> b);
        String prefix = "/modules/=scoring/parameterSets/=scoringParameters";
        // Native addScoringParameters replaces the null fallback when the explicit default is
        // added. Locate serialized positions by their actual subpopulation, not a presumed slot.
        var nativeSets = new ArrayList<>(b.scoring().getParameterSets("scoringParameters"));
        assertEquals(2, nativeSets.size());
        var found = new LinkedHashSet<String>();
        for (int index = 0; index < nativeSets.size(); index++) {
            String subpopulation = ((ScoringConfigGroup.ScoringParameterSet) nativeSets.get(index)).getSubpopulation();
            assertTrue(found.add(subpopulation));
            String ownMode = switch (subpopulation) {
                case "default" -> "default_mode";
                case "freight" -> "cargo_mode";
                default -> throw new AssertionError("Unexpected scoring subpopulation: " + subpopulation);
            };
            String group = prefix + "/" + index;
            assertEquals(subpopulation, report.current().initialized().fields().get(group + "/params/=subpopulation"));
            assertEquals("5", report.current().initialized().fields().get(group + "/parameterSets/=activityParams/@count"));
            List<String> expected = List.of("car", "ride", "bike", "bus", ownMode);
            for (int activity = 0; activity < expected.size(); activity++)
                assertEquals(expected.get(activity) + " interaction", report.current().initialized().fields().get(
                        group + "/parameterSets/=activityParams/" + activity + "/params/=activityType"));
        }
        assertEquals(java.util.Set.of("default", "freight"), found);
        assertTrue(report.initializedStrictlyEqual());
    }

    @Test void unknownModuleAndStrategyOrderStayInFullComparison() {
        Config a = config(), b = config();
        var unknown = new ConfigGroup("unknownScientificModule"); unknown.addParam("coefficient", "1.7"); a.addModule(unknown);
        var list = new ArrayList<>(b.replanning().getStrategySettings()); Collections.reverse(list);
        b.replanning().clearStrategySettings(); list.forEach(b.replanning()::addStrategySettings);
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> a, () -> b);
        assertTrue(report.initializedDifferences().stream().anyMatch(d -> d.path().contains("unknownScientificModule")));
        assertTrue(report.initializedDifferences().stream().anyMatch(d -> d.path().startsWith("/modules/=replanning/parameterSets/")));
        assertTrue(report.initializedDifferences().stream().allMatch(d -> d.explanation() == null));
    }

    @Test void relativeInputPathsAndTheirContextsAreNotRebasedOrFiltered() throws Exception {
        Config a = config(), b = config();
        a.network().setInputFile("network.xml"); b.network().setInputFile("other/network.xml");
        a.setContext(temp.resolve("a/config.xml").toUri().toURL()); b.setContext(temp.resolve("b/config.xml").toUri().toURL());
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> a, () -> b);
        assertEquals("network.xml", report.historical().initialized().fields().get("/modules/=network/params/=inputNetworkFile"));
        assertEquals(2, report.initializedDifferences().size());
    }

    @Test void unrelatedConsistencyMutationCannotBeHiddenByNormalizingBothSides() {
        Config a = config(), b = config();
        for (Config c : List.of(a, b)) c.addModule(new ConfigGroup("mutatingScientificModule") {
            @Override protected void checkConsistency(Config config) { config.qsim().setNumberOfThreads(1); }
        });
        var failure = assertThrows(IllegalStateException.class,
                () -> ControllerInitializationConfigurationAudit.compareFresh(() -> a, () -> b));
        assertTrue(failure.getMessage().contains("Unsupported visible consistency mutation"));
        assertTrue(failure.getMessage().contains("numberOfThreads"));
    }

    @Test void customCheckerThatDeletesAnExplicitFieldFailsClosed() {
        Config a = config(); a.addConfigConsistencyChecker(config -> config.network().setInputFile(null));
        a.network().setInputFile("must-not-be-dropped.xml");
        assertThrows(IllegalStateException.class, () -> ControllerInitializationConfigurationAudit.compareFresh(() -> a, this::config));
    }

    @Test void invalidNativeScoringConfigurationFailsRatherThanReceivingSuccessReport() {
        Config a = config(); a.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("unscorable_missing_duration"));
        assertThrows(RuntimeException.class, () -> ControllerInitializationConfigurationAudit.compareFresh(() -> a, this::config));
    }

    @Test void sameConfigAndSharedNestedGroupsAreRejectedBeforeReplay() {
        Config a = config(); var before = ResolvedNonDispatchConfiguration.snapshot(a);
        assertThrows(IllegalArgumentException.class, () -> ControllerInitializationConfigurationAudit.compareFresh(() -> a, () -> a));
        assertEquals(before.sha256(), ResolvedNonDispatchConfiguration.snapshot(a).sha256());
        Config b = config(); var shared = new ConfigGroup("nested"); shared.addParam("key", "value");
        var left = new ConfigGroup("left"); left.addParameterSet(shared); a.addModule(left);
        var right = new ConfigGroup("right"); right.addParameterSet(shared); b.addModule(right);
        assertThrows(IllegalArgumentException.class, () -> ControllerInitializationConfigurationAudit.compareFresh(() -> a, () -> b));
    }

    @Test void supplierInvocationsAreOnceEachAndSnapshotsRemainDetachedFromConsumedConfigs() {
        var oldCount = new AtomicInteger(); var newCount = new AtomicInteger(); Config a = config(), b = config();
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> { oldCount.incrementAndGet(); return a; },
                () -> { newCount.incrementAndGet(); return b; });
        String retained = report.current().initialized().sha256(); b.qsim().setNumberOfThreads(19);
        assertEquals(1, oldCount.get()); assertEquals(1, newCount.get());
        assertEquals(retained, report.current().initialized().sha256());
        assertEquals("7", report.current().initialized().fields().get("/modules/=qsim/params/=numberOfThreads"));
        assertThrows(UnsupportedOperationException.class, () -> report.rawDifferences().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.current().stages().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.current().initialized().fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.current().stages().get(0).changes().clear());
    }

    @Test void finiteConfigurationOnlyXmlRoundTripKeepsSourceBytesAndOriginalDiff() throws Exception {
        Path old = temp.resolve("historical.xml"), target = temp.resolve("template.xml");
        new ConfigWriter(initialized(config())).write(old.toString()); new ConfigWriter(config()).write(target.toString());
        byte[] oldBytes = Files.readAllBytes(old), newBytes = Files.readAllBytes(target);
        var report = ControllerInitializationConfigurationAudit.compareFresh(
                () -> ConfigUtils.loadConfig(old.toString()), () -> ConfigUtils.loadConfig(target.toString()));
        assertArrayEquals(oldBytes, Files.readAllBytes(old)); assertArrayEquals(newBytes, Files.readAllBytes(target));
        assertEquals(110, report.rawDifferences().size());
        assertEquals(1, report.initializedDifferences().size());
        assertEquals("/context", report.initializedDifferences().getFirst().path());
        assertTrue(report.historical().stages().get(1).changes().isEmpty(), "Full generic Raptor text already equals typed defaults");
        var json = JsonMapper.builder().build().valueToTree(report);
        assertEquals(report.rawDifferences().size(), json.get("rawDifferences").size());
        assertEquals(report.current().raw().fields().size(), json.get("current").get("raw").get("fields").size());
    }

    @Test void repeatedInitializationIsIdempotentAndDoesNotDuplicateStageEntries() {
        Config a = initialized(config()), b = initialized(config());
        var report = ControllerInitializationConfigurationAudit.compareFresh(() -> a, () -> b);
        assertTrue(report.rawStrictlyEqual()); assertTrue(report.initializedStrictlyEqual());
        assertTrue(report.current().stages().stream().allMatch(stage -> stage.changes().isEmpty()));
        assertEquals("17", report.current().initialized().fields().get(ACT + "/@count"));
    }

    private Config config() {
        Config config = ConfigUtils.createConfig();
        config.transit().setUseTransit(true); config.transit().setTransitModes(new LinkedHashSet<>(List.of("pt", "freight_pt")));
        config.routing().setNetworkModes(List.of("car", "ride", "bike", "bus"));
        config.routing().removeModeRoutingParams("bike"); config.routing().removeModeRoutingParams("ride");
        config.qsim().setNumberOfThreads(7); config.global().setNumberOfThreads(7);
        config.controller().setOutputDirectory(temp.resolve("never-created-simulation").toString());
        var scoring = config.scoring().getScoringParameters(null);
        new ArrayList<>(scoring.getParameterSets("activityParams")).forEach(scoring::removeParameterSet);
        new ArrayList<>(scoring.getParameterSets("modeParams")).forEach(scoring::removeParameterSet);
        for (int i = 0; i < 10; i++) scoring.addActivityParams(new ScoringConfigGroup.ActivityParams("a" + i).setTypicalDuration(3600));
        for (String mode : List.of("pt", "walk", "freight_pt")) scoring.addModeParams(new ScoringConfigGroup.ModeParams(mode));
        config.replanning().clearStrategySettings();
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(0.7));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(0.3));
        return config;
    }

    private static Config initialized(Config config) {
        config.checkConsistency(); ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class); return config;
    }
}
