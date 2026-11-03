package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.cotransport.CoTransportBoardingPolicy;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.run.ismt.dynamic.DynamicMixedControlDefinitions;
import org.matsim.run.ismt.dynamic.DynamicMixedDesign;
import org.matsim.run.ismt.dynamic.DynamicMixedExperimentSpec;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure declarations and in-memory Scenario checks; no file loading, Controller or simulator. */
class UnifiedArmContractTest {
    private static final String WINDOW_ELEMENT = CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT;
    private static final String WINDOWED = DynamicMixedControlDefinitions.WINDOWED_POLICY_ID;
    private static final String HISTORICAL = DynamicMixedControlDefinitions.POLICY_ID;

    @Test void allLegacyArmsKeepTheirAccessSupplyAndAbsentControlAndWindow() {
        for (var arm : ExperimentSpec.Arm.values()) {
            var spec = legacy(arm);
            var contract = UnifiedArmContract.fromLegacy(spec);
            assertEquals(UnifiedArmContract.SCHEMA, contract.schemaVersion());
            assertEquals(ExperimentSpec.SCHEMA, contract.specificationSchemaVersion());
            assertEquals(spec.policies().access(), contract.access());
            assertSame(spec.inputs().baselineSchedule(), contract.supply().baselineSchedule());
            assertSame(spec.inputs().activatedSchedule(), contract.supply().activatedSchedule());
            assertSame(spec.inputs().activatedTransitVehicles(), contract.supply().activatedTransitVehicles());
            assertEquals(UnifiedArmContract.WindowMode.NO_PHYSICAL_BOARDING_WINDOW, contract.windowMode());
            assertEquals(UnifiedArmContract.ControlMode.NOT_APPLICABLE, contract.controlMode());
            assertFalse(contract.controlEnabled());
            assertEquals("", contract.controlPolicyId());
        }
    }

    @Test void historicalTimetableOnlyIsExplicitlyNotDynamicTInJson() throws Exception {
        var contract = UnifiedArmContract.fromLegacy(legacy(ExperimentSpec.Arm.TIMETABLE_ONLY));
        assertEquals(UnifiedArmContract.Arm.LEGACY_TIMETABLE_ONLY, contract.arm());
        assertEquals(ExperimentSpec.Access.NO_FREIGHT, contract.access());
        assertEquals(UnifiedArmContract.SupplyMode.REGISTERED_DEDICATED_SERVICE_SUPPLY, contract.supply().mode());
        var tree = new ObjectMapper().valueToTree(contract);
        assertEquals("LEGACY_TIMETABLE_ONLY", tree.get("arm").asText());
        assertEquals("NO_FREIGHT", tree.get("access").asText());
        assertFalse(tree.toString().contains("DYNAMIC_MIXED"));
    }

    @Test void v1ConfiguredServiceClocksDoNotCreatePhysicalBoardingGates() {
        for (var arm : ExperimentSpec.Arm.values()) {
            var contract = UnifiedArmContract.fromLegacy(legacy(arm));
            var scenario = scenario(contract);
            Runnable unchanged = snapshot(scenario);
            contract.verifyScenario(scenario);
            assertNull(scenario.getScenarioElement(WINDOW_ELEMENT));
            unchanged.run();
        }
    }

    @Test void everyLegacyArmRejectsAnAccidentalPhysicalSharedWindowWithoutRemovingIt() {
        for (var arm : ExperimentSpec.Arm.values()) {
            var contract = UnifiedArmContract.fromLegacy(legacy(arm));
            var scenario = scenario(contract);
            var window = matchingWindow(contract);
            scenario.addScenarioElement(WINDOW_ELEMENT, window);
            Runnable unchanged = snapshot(scenario);
            assertThrows(IllegalStateException.class, () -> contract.verifyScenario(scenario));
            assertSame(window, scenario.getScenarioElement(WINDOW_ELEMENT));
            unchanged.run();
        }
    }

    @Test void dedicatedAndHistoricalEmptyServiceArmsNeedNoActivatedLineAtPreparation() {
        for (var arm : List.of(ExperimentSpec.Arm.DEDICATED, ExperimentSpec.Arm.TIMETABLE_ONLY)) {
            var contract = UnifiedArmContract.fromLegacy(legacy(arm));
            var scenario = scenario(contract);
            assertTrue(scenario.getTransitSchedule().getTransitLines().isEmpty());
            Runnable unchanged = snapshot(scenario);
            assertDoesNotThrow(() -> contract.verifyScenario(scenario));
            assertEquals("dedicated-line", contract.dedicatedLineId());
            unchanged.run();
        }
    }

    @Test void windowedMixedAndDynamicMixedHaveTheSameRequiredPhysicalWindow() {
        for (var arm : List.of(DynamicMixedDesign.Arm.MIXED, DynamicMixedDesign.Arm.DYNAMIC_MIXED)) {
            var contract = UnifiedArmContract.fromDynamic(dynamic(arm, WINDOWED, arm == DynamicMixedDesign.Arm.DYNAMIC_MIXED, 25));
            assertEquals(UnifiedArmContract.WindowMode.REGISTERED_SHARED_BOARDING_WINDOW, contract.windowMode());
            assertEquals(ExperimentSpec.Access.SHARED_PASSENGER_SERVICE, contract.access());
            var scenario = scenario(contract);
            scenario.addScenarioElement(WINDOW_ELEMENT, matchingWindow(contract));
            Runnable unchanged = snapshot(scenario);
            contract.verifyScenario(scenario);
            unchanged.run();
        }
    }

    @Test void disabledAndZeroAmplitudeTDoNotLoseThePhysicalFreightWindow() {
        var off = UnifiedArmContract.fromDynamic(dynamic(DynamicMixedDesign.Arm.DYNAMIC_MIXED, WINDOWED, false, 25));
        var zero = UnifiedArmContract.fromDynamic(dynamic(DynamicMixedDesign.Arm.DYNAMIC_MIXED, WINDOWED, true, 0));
        assertEquals(UnifiedArmContract.ControlMode.DISABLED, off.controlMode());
        assertFalse(off.controlEnabled());
        assertEquals(UnifiedArmContract.ControlMode.ZERO_AMPLITUDE, zero.controlMode());
        assertTrue(zero.controlEnabled());
        for (var contract : List.of(off, zero)) {
            assertEquals(UnifiedArmContract.WindowMode.REGISTERED_SHARED_BOARDING_WINDOW, contract.windowMode());
            var scenario = scenario(contract);
            assertThrows(IllegalStateException.class, () -> contract.verifyScenario(scenario));
            scenario.addScenarioElement(WINDOW_ELEMENT, matchingWindow(contract));
            assertDoesNotThrow(() -> contract.verifyScenario(scenario));
        }
    }

    @Test void installedMatchingWindowRetainsItsInclusiveOpeningAndExclusiveClosing() {
        var contract = windowedT();
        var scenario = scenario(contract);
        var window = matchingWindow(contract);
        scenario.addScenarioElement(WINDOW_ELEMENT, window);
        contract.verifyScenario(scenario);
        assertSame(window, scenario.getScenarioElement(WINDOW_ELEMENT));
        assertFalse(window.contains(contract.serviceWindowStartSeconds() - 1));
        assertTrue(window.contains(contract.serviceWindowStartSeconds()));
        assertTrue(window.contains(contract.serviceWindowEndSeconds() - 1));
        assertFalse(window.contains(contract.serviceWindowEndSeconds()));
    }

    @Test void missingRequiredWindowIsRejectedAndNeverInstalledByVerification() {
        var contract = windowedT();
        var scenario = scenario(contract);
        Runnable unchanged = snapshot(scenario);
        assertThrows(IllegalStateException.class, () -> contract.verifyScenario(scenario));
        assertNull(scenario.getScenarioElement(WINDOW_ELEMENT));
        unchanged.run();
    }

    @Test void wrongWindowTypeOrEndpointsAreRejectedWithoutRepair() {
        var contract = windowedT();
        for (Object window : List.of("not-a-window", new CoTransportBoardingPolicy.SharedBoardingWindow(31, 180),
                new CoTransportBoardingPolicy.SharedBoardingWindow(30, 181))) {
            var scenario = scenario(contract);
            scenario.addScenarioElement(WINDOW_ELEMENT, window);
            Runnable unchanged = snapshot(scenario);
            assertThrows(IllegalStateException.class, () -> contract.verifyScenario(scenario));
            assertSame(window, scenario.getScenarioElement(WINDOW_ELEMENT));
            unchanged.run();
        }
    }

    @Test void historicalNonwindowedTStaysWithoutTheNewPhysicalGate() {
        var contract = UnifiedArmContract.fromDynamic(dynamic(DynamicMixedDesign.Arm.DYNAMIC_MIXED, HISTORICAL, true, 25));
        assertEquals(UnifiedArmContract.Arm.DYNAMIC_MIXED, contract.arm());
        assertEquals(UnifiedArmContract.ControlMode.ENABLED, contract.controlMode());
        assertEquals(UnifiedArmContract.WindowMode.NO_PHYSICAL_BOARDING_WINDOW, contract.windowMode());
        var scenario = scenario(contract);
        contract.verifyScenario(scenario);
        assertNull(scenario.getScenarioElement(WINDOW_ELEMENT));
        scenario.addScenarioElement(WINDOW_ELEMENT, matchingWindow(contract));
        assertThrows(IllegalStateException.class, () -> contract.verifyScenario(scenario));
    }

    @Test void dynamicControlAndDedicatedDeclarationsDoNotBecomeMixedBySharingPolicyContext() {
        for (var arm : List.of(DynamicMixedDesign.Arm.CONTROL, DynamicMixedDesign.Arm.DEDICATED)) {
            var contract = UnifiedArmContract.fromDynamic(dynamic(arm, WINDOWED, false, 25));
            assertEquals(UnifiedArmContract.ControlMode.NOT_APPLICABLE, contract.controlMode());
            assertEquals(UnifiedArmContract.WindowMode.NO_PHYSICAL_BOARDING_WINDOW, contract.windowMode());
            var scenario = scenario(contract);
            Runnable unchanged = snapshot(scenario);
            contract.verifyScenario(scenario);
            unchanged.run();
            scenario.addScenarioElement(WINDOW_ELEMENT, matchingWindow(contract));
            assertThrows(IllegalStateException.class, () -> contract.verifyScenario(scenario));
        }
    }

    @Test void differentSerializedFreightServiceClocksFailWithoutBeingOverwritten() {
        var contract = windowedT();
        for (boolean opening : List.of(true, false)) {
            var scenario = scenario(contract);
            scenario.addScenarioElement(WINDOW_ELEMENT, matchingWindow(contract));
            var co = (CoTransportConfigGroup) scenario.getConfig().getModules().get(CoTransportConfigGroup.GROUP_NAME);
            if (opening) co.setFreightServiceWindowStartTime(31);
            else co.setFreightServiceWindowEndTime(181);
            Runnable unchanged = snapshot(scenario);
            assertThrows(IllegalStateException.class, () -> contract.verifyScenario(scenario));
            unchanged.run();
        }
    }

    @Test void dedicatedIsolationCannotSilentlyBecomeMixedOrTheReverse() {
        for (var arm : List.of(ExperimentSpec.Arm.CONTROL, ExperimentSpec.Arm.MIXED, ExperimentSpec.Arm.DEDICATED)) {
            var contract = UnifiedArmContract.fromLegacy(legacy(arm));
            var scenario = scenario(contract);
            var co = (CoTransportConfigGroup) scenario.getConfig().getModules().get(CoTransportConfigGroup.GROUP_NAME);
            co.setDedicatedFreightLineId(arm == ExperimentSpec.Arm.DEDICATED ? "" : "unexpected-dedicated-line");
            Runnable unchanged = snapshot(scenario);
            assertThrows(IllegalStateException.class, () -> contract.verifyScenario(scenario));
            unchanged.run();
        }
    }

    @Test void verificationDoesNotAddMissingConfigurationModules() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        assertFalse(scenario.getConfig().getModules().containsKey(CoTransportConfigGroup.GROUP_NAME));
        assertThrows(IllegalStateException.class, () -> windowedT().verifyScenario(scenario));
        assertFalse(scenario.getConfig().getModules().containsKey(CoTransportConfigGroup.GROUP_NAME));
        assertNull(scenario.getScenarioElement(WINDOW_ELEMENT));
    }

    @Test void existingRegistrationsRejectUnregisteredFcfsWithoutChangingConfiguration() {
        for (var contract : List.of(windowedT(), UnifiedArmContract.fromLegacy(legacy(ExperimentSpec.Arm.MIXED)))) {
            var scenario = scenario(contract);
            if (contract.windowMode() == UnifiedArmContract.WindowMode.REGISTERED_SHARED_BOARDING_WINDOW)
                scenario.addScenarioElement(WINDOW_ELEMENT, matchingWindow(contract));
            var co = (CoTransportConfigGroup) scenario.getConfig().getModules().get(CoTransportConfigGroup.GROUP_NAME);
            co.setBoardingOrder(CoTransportSettings.BoardingOrder.FIRST_COME_FIRST_SERVED);
            Runnable unchanged = snapshot(scenario);
            var failure = assertThrows(IllegalStateException.class, () -> contract.verifyScenario(scenario));
            assertTrue(failure.getMessage().contains("PASSENGER_FIRST"));
            assertTrue(failure.getMessage().contains("development configuration"));
            unchanged.run();
        }
    }

    @Test void jsonRoundTripKeepsCompleteImmutableDeclarationWithoutLoadingFakeFileReferences() throws Exception {
        var mapper = new ObjectMapper();
        for (var contract : List.of(windowedT(), UnifiedArmContract.fromLegacy(legacy(ExperimentSpec.Arm.DEDICATED)))) {
            var encoded = mapper.writeValueAsString(contract);
            assertEquals(contract, mapper.readValue(encoded, UnifiedArmContract.class));
            var tree = mapper.readTree(encoded);
            assertEquals(contract.supply().activatedSchedule().path(), tree.get("supply").get("activatedSchedule").get("path").asText());
            assertFalse(tree.has("canRun"));
            assertFalse(tree.has("runtimeVerified"));
            assertFalse(tree.has("approved"));
        }
    }

    @Test void contractConstructorCannotRelabelLegacyTOrAttachItsPhysicalWindow() {
        var original = UnifiedArmContract.fromLegacy(legacy(ExperimentSpec.Arm.TIMETABLE_ONLY));
        assertThrows(IllegalArgumentException.class, () -> new UnifiedArmContract(original.schemaVersion(),
                original.specificationSchemaVersion(), UnifiedArmContract.Arm.DYNAMIC_MIXED, original.access(),
                original.supply(), original.dedicatedLineId(), original.windowMode(), original.serviceWindowStartSeconds(),
                original.serviceWindowEndSeconds(), original.controlMode(), original.controlEnabled(), original.controlPolicyId()));
        var mixed = UnifiedArmContract.fromLegacy(legacy(ExperimentSpec.Arm.MIXED));
        assertThrows(IllegalArgumentException.class, () -> new UnifiedArmContract(mixed.schemaVersion(),
                mixed.specificationSchemaVersion(), mixed.arm(), mixed.access(), mixed.supply(), mixed.dedicatedLineId(),
                UnifiedArmContract.WindowMode.REGISTERED_SHARED_BOARDING_WINDOW, mixed.serviceWindowStartSeconds(),
                mixed.serviceWindowEndSeconds(), mixed.controlMode(), mixed.controlEnabled(), mixed.controlPolicyId()));
    }

    private UnifiedArmContract windowedT() {
        return UnifiedArmContract.fromDynamic(dynamic(DynamicMixedDesign.Arm.DYNAMIC_MIXED, WINDOWED, true, 25));
    }

    private Scenario scenario(UnifiedArmContract contract) {
        var config = ConfigUtils.createConfig(new CoTransportConfigGroup());
        var co = (CoTransportConfigGroup) config.getModules().get(CoTransportConfigGroup.GROUP_NAME);
        co.setDedicatedFreightLineId(contract.dedicatedLineId());
        co.setFreightServiceWindowStartTime(contract.serviceWindowStartSeconds());
        co.setFreightServiceWindowEndTime(contract.serviceWindowEndSeconds());
        return ScenarioUtils.createScenario(config);
    }

    private CoTransportBoardingPolicy.SharedBoardingWindow matchingWindow(UnifiedArmContract contract) {
        return new CoTransportBoardingPolicy.SharedBoardingWindow(contract.serviceWindowStartSeconds(), contract.serviceWindowEndSeconds());
    }

    private Runnable snapshot(Scenario scenario) {
        var co = scenario.getConfig().getModules().get(CoTransportConfigGroup.GROUP_NAME);
        var params = new LinkedHashMap<>(co.getParams());
        var population = scenario.getPopulation();
        var persons = new LinkedHashMap<>(population.getPersons());
        var schedule = scenario.getTransitSchedule();
        var lines = new LinkedHashMap<>(schedule.getTransitLines());
        var vehicles = scenario.getTransitVehicles();
        var fleet = new LinkedHashMap<>(vehicles.getVehicles());
        var window = scenario.getScenarioElement(WINDOW_ELEMENT);
        return () -> {
            assertSame(co, scenario.getConfig().getModules().get(CoTransportConfigGroup.GROUP_NAME));
            assertEquals(params, co.getParams());
            assertSame(population, scenario.getPopulation());
            assertEquals(persons, population.getPersons());
            assertSame(schedule, scenario.getTransitSchedule());
            assertEquals(lines, schedule.getTransitLines());
            assertSame(vehicles, scenario.getTransitVehicles());
            assertEquals(fleet, vehicles.getVehicles());
            assertSame(window, scenario.getScenarioElement(WINDOW_ELEMENT));
        };
    }

    private ExperimentSpec legacy(ExperimentSpec.Arm arm) {
        boolean dedicated = arm == ExperimentSpec.Arm.DEDICATED || arm == ExperimentSpec.Arm.TIMETABLE_ONLY;
        var access = arm == ExperimentSpec.Arm.DEDICATED ? ExperimentSpec.Access.DEDICATED_ONLY
                : arm == ExperimentSpec.Arm.MIXED ? ExperimentSpec.Access.SHARED_PASSENGER_SERVICE : ExperimentSpec.Access.NO_FREIGHT;
        return new ExperimentSpec(ExperimentSpec.SCHEMA, "contract-toy", "B1", arm,
                new ExperimentSpec.Seeds(1, 2, 3), inputs(dedicated),
                new ExperimentSpec.Protocol(0, 2, 4, 4, 4, 5, 4, 5, 200, 240),
                demand(access == ExperimentSpec.Access.NO_FREIGHT ? 0 : 1), policies(access, dedicated),
                new ExperimentSpec.OutputOptions("ismt-output-v1", ExperimentSpec.ExposureOutput.TERMINAL, false, 0, 0), "toy-not-formal");
    }

    private DynamicMixedExperimentSpec dynamic(DynamicMixedDesign.Arm arm, String policyId, boolean enabled, double amplitude) {
        boolean dedicated = arm == DynamicMixedDesign.Arm.DEDICATED;
        var access = dedicated ? ExperimentSpec.Access.DEDICATED_ONLY
                : arm == DynamicMixedDesign.Arm.CONTROL ? ExperimentSpec.Access.NO_FREIGHT : ExperimentSpec.Access.SHARED_PASSENGER_SERVICE;
        var definition = new DynamicMixedExperimentSpec.RegisteredDefinition("toy-not-decoded-v1", ref("not-read.json", "a"));
        return new DynamicMixedExperimentSpec(DynamicMixedExperimentSpec.SCHEMA, "contract-toy-v2", "B1",
                new DynamicMixedDesign(arm, new DynamicMixedDesign.Protocol(0, 2, 4, 5, 4, 5), enabled,
                        new DispatchCommitmentState.Limits(amplitude, amplitude, 5)),
                new ExperimentSpec.Seeds(1, 2, 3), inputs(dedicated), demand(arm == DynamicMixedDesign.Arm.CONTROL ? 0 : 1),
                policies(access, dedicated), new DynamicMixedExperimentSpec.RuntimeDeclaration(7, 7,
                DynamicMixedExperimentSpec.EventMode.NATIVE_SIM_STEP_PARALLEL,
                DynamicMixedExperimentSpec.EventWorkerSelection.DEFAULT_SINGLE_WORKER, 1, true, false, 2,
                1, 0, QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime, QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime,
                200, 240, definition), new DynamicMixedExperimentSpec.ControlRegistration(policyId,
                DynamicMixedExperimentSpec.InformationModel.BASELINE_PRETRIP_NO_ADDED_INTRADAY_REROUTING,
                DynamicMixedExperimentSpec.ResetMode.FRESH_ORIGINAL_BASELINE_EACH_ITERATION,
                DynamicMixedExperimentSpec.BaselineTreatment.NO_PRE_SERVICE_REPAIR,
                DynamicMixedExperimentSpec.OperatingBasis.SYNTHETIC_ASSUMPTIONS,
                new DynamicMixedExperimentSpec.DecisionTiming(30, 5, 30, 5),
                definition, definition, definition, definition, definition, definition),
                new DynamicMixedExperimentSpec.OutputOptions(ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA,
                        ScientificRunContext.ExposureOutput.TERMINAL, false, 0, 0, "toy-not-executed-v1"), "toy-not-formal");
    }

    private ExperimentSpec.Inputs inputs(boolean dedicated) {
        var base = ref("not-loaded-baseline.xml", "a");
        var activated = dedicated ? ref("not-loaded-dedicated.xml", "b") : base;
        return new ExperimentSpec.Inputs(base, base, base, base, base, base, base, activated, activated, base, base, base);
    }

    private ExperimentSpec.FileRef ref(String path, String digit) { return new ExperimentSpec.FileRef(path, digit.repeat(64)); }

    private ExperimentSpec.FreightDemand demand(int count) {
        return new ExperimentSpec.FreightDemand(count, 5, 30, 180, "freight_destination", "_cap_");
    }

    private ExperimentSpec.Policies policies(ExperimentSpec.Access access, boolean dedicated) {
        return new ExperimentSpec.Policies(access, dedicated ? "dedicated-line" : "", List.of("pt", "freight_pt"),
                0.1, 0.9, 0.95, 0, true, 0.6, 0.8, 0.8, 0.55, 10, 25, 12);
    }
}
