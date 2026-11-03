package org.matsim.run.ismt.dynamic;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Configuration/byte fixtures only: no population parsing, native QSim, Controller or formal input. */
class DynamicMixedConfigurationTest {
    @TempDir Path temp;

    @Test void v2UsesNeutralPhasesAndNeverFabricatesLegacyTimetableOnlyIdentity() {
        var spec = DynamicMixedSpecFixtures.spec();
        spec = replace(spec, "design", new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED,
                new DynamicMixedDesign.Protocol(0, 20, 150, 199, 170, 199), true, spec.design().limits()));
        var common = DynamicMixedConfiguration.commonRequest(spec);
        assertEquals(0, common.protocol().firstIteration()); assertEquals(20, common.protocol().activationIteration());
        assertEquals(0, common.protocol().innovationOffIteration()); assertEquals(20, common.protocol().innovationReopenIteration());
        assertEquals(150, common.protocol().innovationFinalOffIteration()); assertEquals(199, common.protocol().finalIteration());
        assertSame(spec.inputs(), common.inputs()); assertSame(spec.policies(), common.policies());
        assertEquals(spec.freightDemand(), common.freightDemand());
    }

    @Test void allNineteenRolesAreVerifiedAndNativeNonDispatchSettingsArePreserved() throws IOException {
        var spec = fixture(config -> { config.eventsManager().setEventsQueueSize(12345); config.scoring().setBrainExpBeta(2.5); });
        var prepared = DynamicMixedConfiguration.prepare(spec, temp, temp.resolve("output"));
        Config config = prepared.config(); var co = co(config);
        assertEquals(19, prepared.inputVerification().files().size()); assertEquals(19, prepared.inputVerification().uniqueFiles());
        assertFalse(prepared.registeredResolvedDefinitionSemanticallyVerified());
        assertEquals(7, config.global().getNumberOfThreads()); assertEquals(7, config.qsim().getNumberOfThreads());
        assertNull(config.eventsManager().getNumberOfThreads()); assertTrue(config.eventsManager().getSynchronizeOnSimSteps());
        assertFalse(config.eventsManager().getOneThreadPerHandler()); assertEquals(12345, config.eventsManager().getEventsQueueSize());
        assertEquals(2.5, config.scoring().getBrainExpBeta());
        assertEquals(List.of("ChangeExpBeta", "ReRoute", "KeepLastSelected"), config.replanning().getStrategySettings().stream()
                .map(ReplanningConfigGroup.StrategySettings::getStrategyName).toList());
        assertEquals(List.of(0.7, 0.3, 1.0), config.replanning().getStrategySettings().stream()
                .map(ReplanningConfigGroup.StrategySettings::getWeight).toList());
        assertEquals(0, co.getInnovationOffIteration()); assertEquals(2, co.getInnovationReopenIteration());
        assertEquals(4, co.getInnovationFinalOffIteration()); assertEquals(2, co.getActivationIteration());
        assertEquals(200, config.qsim().getEndTime().seconds()); assertEquals(240, horizon(config).getExecutionEndTime());
        assertEquals(prepared.resolvedSnapshot().fields(), ResolvedNonDispatchConfiguration.snapshot(config).fields());
        assertFalse(Files.exists(temp.resolve("output")), "Configuration creation must not start a run or create its output directory");
    }

    @Test void oldMixedAndNewDynamicRequestsProduceExactlyTheSameFullCommonConfig() throws IOException {
        var spec = fixture(config -> { }); var p = spec.design().protocol();
        var old = new ExperimentSpec(ExperimentSpec.SCHEMA, "genuine-legacy-mixed-fixture", spec.blockId(), ExperimentSpec.Arm.MIXED,
                spec.seeds(), spec.inputs(), new ExperimentSpec.Protocol(p.firstIteration(), p.activationIteration(), p.firstIteration(),
                p.activationIteration(), p.innovationOffIteration(), p.finalIteration(), p.reportFirstIteration(), p.reportLastIteration(),
                spec.runtime().behaviouralHorizonSeconds(), spec.runtime().executionHorizonSeconds()), spec.freightDemand(), spec.policies(),
                new ExperimentSpec.OutputOptions("ismt-output-v1", ExperimentSpec.ExposureOutput.REPORTING_WINDOW, false, 0, 0), "toy-old-mixed");
        Path output = temp.resolve("same-uncreated-output");
        var oldConfig = ExperimentConfiguration.create(old, temp, output);
        var commonConfig = ExperimentConfiguration.create(ExperimentConfiguration.commonRequest(old), temp, output);
        var newConfig = DynamicMixedConfiguration.create(spec, temp, output);
        var first = ResolvedNonDispatchConfiguration.snapshot(oldConfig);
        assertTrue(ResolvedNonDispatchConfiguration.compare(first, ResolvedNonDispatchConfiguration.snapshot(commonConfig)).strictlyEqual());
        assertTrue(ResolvedNonDispatchConfiguration.compare(first, ResolvedNonDispatchConfiguration.snapshot(newConfig)).strictlyEqual());
        // This proves toy common configuration equality only, not real M reuse or implementation/source equivalence.
    }

    @Test void everyDeclaredRoleIsCheckedIncludingAllSevenDefinitionFiles() throws IOException {
        var spec = fixture(config -> { });
        assertEquals(19, spec.allReferencedFiles().size());
        for (var entry : spec.allReferencedFiles().entrySet()) {
            Path file = entry.getValue().resolve(temp); byte[] before = Files.readAllBytes(file);
            Files.writeString(file, "tampered role " + entry.getKey());
            try {
                assertThrows(IOException.class, () -> DynamicMixedConfiguration.prepare(spec, temp, temp.resolve("output")), entry.getKey());
                assertFalse(Files.exists(temp.resolve("output")));
            } finally { Files.write(file, before); }
        }
    }

    @Test void mismatchedHistoricalThreadsAreRejectedNotSilentlyRewritten() throws IOException {
        var spec = fixture(config -> config.qsim().setNumberOfThreads(1));
        var failure = assertThrows(IllegalArgumentException.class, () -> DynamicMixedConfiguration.create(spec, temp, temp.resolve("output")));
        assertTrue(failure.getMessage().contains("qsim.numberOfThreads declared=7 actual=1"));
    }

    @Test void defaultAndExplicitSingleEventWorkerRemainDifferentDeclarations() throws IOException {
        var spec = fixture(config -> config.eventsManager().setNumberOfThreads(1));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedConfiguration.create(spec, temp, temp.resolve("output")));
        var runtime = replace(spec.runtime(), "eventWorkerSelection", DynamicMixedExperimentSpec.EventWorkerSelection.EXPLICIT_SINGLE_WORKER);
        var explicit = replace(spec, "runtime", runtime);
        Config actual = DynamicMixedConfiguration.create(explicit, temp, temp.resolve("output"));
        assertEquals(Integer.valueOf(1), actual.eventsManager().getNumberOfThreads());
        actual.eventsManager().setNumberOfThreads(null);
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedConfiguration.requireRuntimeDeclaration(actual, runtime));
        assertNull(actual.eventsManager().getNumberOfThreads());
    }

    @Test void everyDeclaredRuntimeFieldIsComparedAndChecksAreReadOnly() throws IOException {
        var spec = fixture(config -> { });
        List<Consumer<Config>> mutations = List.of(
                c -> c.qsim().setNumberOfThreads(1), c -> c.global().setNumberOfThreads(1),
                c -> c.eventsManager().setNumberOfThreads(2), c -> c.eventsManager().setSynchronizeOnSimSteps(false),
                c -> c.eventsManager().setOneThreadPerHandler(true), c -> c.qsim().setTimeStepSize(2),
                c -> c.qsim().setStartTime(1),
                c -> c.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.maxOfStarttimeAndEarliestActivityEnd),
                c -> c.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.minOfEndtimeAndMobsimFinished),
                c -> c.qsim().setEndTime(201), c -> horizon(c).setExecutionEndTime(241));
        for (var mutation : mutations) {
            Config config = DynamicMixedConfiguration.create(spec, temp, temp.resolve("output")); mutation.accept(config);
            var before = ResolvedNonDispatchConfiguration.snapshot(config);
            assertThrows(IllegalArgumentException.class, () -> DynamicMixedConfiguration.requireRuntimeDeclaration(config, spec.runtime()));
            assertEquals(before.fields(), ResolvedNonDispatchConfiguration.snapshot(config).fields());
        }
    }

    @Test void missingTypedHorizonFailsWithoutAddingModule() throws IOException {
        var spec = fixture(config -> { });
        Config config = DynamicMixedConfiguration.create(spec, temp, temp.resolve("output"));
        config.getModules().remove(PlanAExecutionHorizonConfigGroup.GROUP_NAME);
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedConfiguration.requireRuntimeDeclaration(config, spec.runtime()));
        assertFalse(config.getModules().containsKey(PlanAExecutionHorizonConfigGroup.GROUP_NAME));
        config.addModule(new ConfigGroup(PlanAExecutionHorizonConfigGroup.GROUP_NAME));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedConfiguration.requireRuntimeDeclaration(config, spec.runtime()));
    }

    @Test void temporarilyRaisedExecutionHorizonIsNotMisidentifiedAsAssemblyConfig() throws IOException {
        var spec = fixture(config -> { }); Config config = DynamicMixedConfiguration.create(spec, temp, temp.resolve("output"));
        config.qsim().setEndTime(spec.runtime().executionHorizonSeconds());
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedConfiguration.requireRuntimeDeclaration(config, spec.runtime()));
    }

    @Test void freightStrategyAndUnregisteredAuxiliaryRestrictionsAreShared() throws IOException {
        var badFreight = fixture(config -> config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setSubpopulation("freight").setStrategyName("ReRoute").setWeight(0)));
        assertThrows(IOException.class, () -> DynamicMixedConfiguration.create(badFreight, temp, temp.resolve("output")));
        var extraInput = fixture(config -> config.facilities().setInputFile("unbound-facilities.xml"));
        assertThrows(IOException.class, () -> DynamicMixedConfiguration.create(extraInput, temp, temp.resolve("output")));
    }

    @Test void adaptationOffPreservesFreightSelectorAndReplacesPassengerStrategiesExactly() throws IOException {
        var spec = fixture(config -> { }); spec = replace(spec, "policies", replace(spec.policies(), "passengerAdaptation", false));
        var config = DynamicMixedConfiguration.create(spec, temp, temp.resolve("output"));
        var strategies = new ArrayList<>(config.replanning().getStrategySettings());
        assertEquals(2, strategies.size()); assertEquals("freight", strategies.getFirst().getSubpopulation());
        assertNull(strategies.getLast().getSubpopulation());
        assertTrue(strategies.stream().allMatch(value -> value.getStrategyName().equals("KeepLastSelected") && value.getWeight() == 1));
    }

    @Test void offAndZeroAreTheSameCommonConfigAndDoNotChangeAnyNativeConfigModule() throws IOException {
        var spec = fixture(config -> { }); var d = spec.design();
        var off = replace(spec, "design", new DynamicMixedDesign(d.arm(), d.protocol(), false, d.limits()));
        var zero = replace(spec, "design", new DynamicMixedDesign(d.arm(), d.protocol(), true,
                new DispatchCommitmentState.Limits(0, 0, d.limits().lockWindowSeconds())));
        var active = ResolvedNonDispatchConfiguration.snapshot(DynamicMixedConfiguration.create(spec, temp, temp.resolve("output")));
        assertTrue(ResolvedNonDispatchConfiguration.compare(active,
                ResolvedNonDispatchConfiguration.snapshot(DynamicMixedConfiguration.create(off, temp, temp.resolve("output")))).strictlyEqual());
        assertTrue(ResolvedNonDispatchConfiguration.compare(active,
                ResolvedNonDispatchConfiguration.snapshot(DynamicMixedConfiguration.create(zero, temp, temp.resolve("output")))).strictlyEqual());
    }

    @Test void allV2ArmsCanUseCommonAdapterWithoutExtendingLegacyArmEnumeration() throws IOException {
        var base = fixture(config -> { });
        for (var arm : DynamicMixedDesign.Arm.values()) {
            var template = DynamicMixedSpecFixtures.spec(arm);
            var spec = replace(template, "inputs", base.inputs());
            spec = replace(spec, "runtime", base.runtime()); spec = replace(spec, "control", base.control());
            var actual = DynamicMixedConfiguration.create(spec, temp, temp.resolve("output"));
            // Existing CoTransportConfigGroup canonicalizes a disabled/blank line to null.
            String dedicated = template.policies().dedicatedLineId();
            assertEquals(dedicated.isBlank() ? null : dedicated, co(actual).getDedicatedFreightLineId());
            assertEquals(template.freightDemand().serviceWindowEndSeconds(), co(actual).getFreightServiceWindowEndTime());
        }
        assertEquals(4, ExperimentSpec.Arm.values().length);
    }

    @Test void existingAndEscapingOutputsAreRejectedAndNothingIsCreated() throws IOException {
        var spec = fixture(config -> { });
        Path existing = Files.createDirectory(temp.resolve("existing"));
        assertThrows(IOException.class, () -> DynamicMixedConfiguration.create(spec, temp, existing));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedConfiguration.create(spec, temp, Path.of("relative-output")));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedConfiguration.create(spec, temp, temp.resolve("../outside-output").normalize()));
        Files.createSymbolicLink(temp.resolve("escape"), temp.getParent());
        assertThrows(IOException.class, () -> DynamicMixedConfiguration.create(spec, temp, temp.resolve("escape/new-output")));
        Files.createSymbolicLink(temp.resolve("dangling-output"), temp.resolve("missing-target"));
        assertThrows(IOException.class, () -> DynamicMixedConfiguration.create(spec, temp, temp.resolve("dangling-output")));
    }

    @Test void commonRequestRejectsMalformedPhaseAndOutputValues() {
        var spec = DynamicMixedSpecFixtures.spec(); var request = DynamicMixedConfiguration.commonRequest(spec);
        assertThrows(IllegalArgumentException.class, () -> new ExperimentConfiguration.PhaseParameters(0, 2, 0, 2, 4, 5, 200, 200));
        assertThrows(IllegalArgumentException.class, () -> new ExperimentConfiguration.PhaseParameters(0, 2, 0, 2, 6, 5, 200, 240));
        assertThrows(IllegalArgumentException.class, () -> new ExperimentConfiguration.CommonRequest(request.seeds(), request.inputs(),
                request.protocol(), request.freightDemand(), request.policies(), -1, 0));
    }

    private DynamicMixedExperimentSpec fixture(Consumer<Config> change) throws IOException {
        Config config = ConfigUtils.createConfig(new CoTransportConfigGroup(), new PlanAExecutionHorizonConfigGroup());
        co(config).setFreightBoardingMode(org.matsim.contrib.cotransport.CoTransportSettings.FreightBoardingMode.UNIT_SPLIT);
        config.global().setNumberOfThreads(7); config.qsim().setNumberOfThreads(7);
        config.qsim().setStartTime(0); config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.replanning().clearStrategySettings();
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ChangeExpBeta").setWeight(0.7));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("ReRoute").setWeight(0.3));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setSubpopulation("freight")
                .setStrategyName("KeepLastSelected").setWeight(1));
        change.accept(config);
        Path xml = temp.resolve("source-config.xml"); new ConfigWriter(config).write(xml.toString());
        byte[] bytes = Files.readAllBytes(xml); String hash = ExperimentConfiguration.sha256(xml);
        List<ExperimentSpec.FileRef> files = new ArrayList<>();
        for (int index = 0; index < 19; index++) {
            Path path = temp.resolve("role-" + index + ".xml"); Files.write(path, bytes);
            files.add(new ExperimentSpec.FileRef(path.getFileName().toString(), hash));
        }
        var inputs = new ExperimentSpec.Inputs(files.get(0), files.get(1), files.get(2), files.get(3), files.get(4), files.get(5),
                files.get(6), files.get(7), files.get(8), files.get(9), files.get(10), files.get(11));
        var spec = replace(DynamicMixedSpecFixtures.spec(), "inputs", inputs);
        spec = replace(spec, "runtime", replace(spec.runtime(), "resolvedConfiguration", definition(files.get(12))));
        var control = spec.control();
        List<String> definitions = List.of("policyDefinition", "constraintDefinition", "forecastDefinition", "passengerProtectionDefinition",
                "informationDefinition", "baselineTreatmentDefinition");
        for (int index = 0; index < definitions.size(); index++) control = replace(control, definitions.get(index), definition(files.get(13 + index)));
        return replace(spec, "control", control);
    }

    private static DynamicMixedExperimentSpec.RegisteredDefinition definition(ExperimentSpec.FileRef file) {
        return new DynamicMixedExperimentSpec.RegisteredDefinition("toy-opaque-definition-not-semantically-decoded", file);
    }
    private static CoTransportConfigGroup co(Config config) { return (CoTransportConfigGroup) config.getModules().get(CoTransportConfigGroup.GROUP_NAME); }
    private static PlanAExecutionHorizonConfigGroup horizon(Config config) {
        return (PlanAExecutionHorizonConfigGroup) config.getModules().get(PlanAExecutionHorizonConfigGroup.GROUP_NAME);
    }
}
