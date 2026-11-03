package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExperimentSpecTest {
    @TempDir Path temp;

    private static Path profiles() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve("experiments/ismt_q720");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new AssertionError("Missing checked-in q720 profiles");
    }

    private ExperimentSpec baseline() throws IOException {
        return ExperimentSpecIO.compose(profiles(), "B2", ExperimentSpec.Arm.MIXED);
    }

    @Test void allTwelveProfilesShareTemplateAndDedicatedSupplies() throws IOException {
        String template = baseline().inputs().matsimConfig().sha256();
        for (String block : new String[]{"B1", "B2", "B3"}) {
            var t = ExperimentSpecIO.compose(profiles(), block, ExperimentSpec.Arm.TIMETABLE_ONLY);
            var d = ExperimentSpecIO.compose(profiles(), block, ExperimentSpec.Arm.DEDICATED);
            assertEquals(t.inputs().activatedSchedule(), d.inputs().activatedSchedule());
            assertEquals(t.inputs().activatedTransitVehicles(), d.inputs().activatedTransitVehicles());
            assertTrue(ExperimentArmPayload.from(t).supply().sameContent(ExperimentArmPayload.from(d).supply()));
            for (var arm : ExperimentSpec.Arm.values()) {
                var spec = ExperimentSpecIO.compose(profiles(), block, arm);
                assertEquals(template, spec.inputs().matsimConfig().sha256());
                assertEquals(t.inputs().passengerPlanMemory(), spec.inputs().passengerPlanMemory());
                assertEquals(t.seeds(), spec.seeds());
                assertEquals(20, spec.protocol().activationIteration());
                assertEquals(170, spec.protocol().reportFirstIteration());
                assertEquals(199, spec.protocol().reportLastIteration());
                assertEquals(150, spec.protocol().innovationFinalOffIteration());
            }
        }
    }

    @Test void specRoundTripAndStableHash() throws IOException {
        var spec = baseline();
        Path path = temp.resolve("spec.json");
        ExperimentSpecIO.writeNew(path, spec);
        var copy = ExperimentSpecIO.read(path);
        assertEquals(spec, copy);
        assertEquals(ExperimentSpecIO.sha256(spec), ExperimentSpecIO.sha256(copy));
        assertThrows(IOException.class, () -> ExperimentSpecIO.writeNew(path, spec));
    }

    @Test void missingPrimitiveCannotSilentlyBecomeZero() throws IOException {
        var node = tree();
        ((ObjectNode) node.get("policies")).remove("betaFreight");
        assertInvalidJson(node);
    }

    @Test void unknownAndNullFieldsFailClosed() throws IOException {
        var node = tree();
        node.put("guessedDefault", 1);
        assertInvalidJson(node);
        node.remove("guessedDefault");
        ((ObjectNode) node.get("freightDemand")).putNull("orderCount");
        assertInvalidJson(node);
        node = tree();
        ((ObjectNode) node.get("policies")).putNull("passengerAdaptation");
        assertInvalidJson(node);
        node = tree();
        ((ObjectNode) node.get("policies")).put("betaFreight", "0.8");
        assertInvalidJson(node);
    }

    @Test void invalidArmAccessAndDemandFailClosed() throws IOException {
        var node = tree();
        ((ObjectNode) node.get("policies")).put("access", "DEDICATED_ONLY");
        assertInvalidJson(node);
        node = tree();
        ((ObjectNode) node.get("freightDemand")).put("orderCount", 0);
        assertInvalidJson(node);
    }

    @Test void invalidBoundsAndProtocolFailClosed() throws IOException {
        var node = tree();
        ((ObjectNode) node.get("policies")).put("passengerLoadGate", 1.1);
        assertInvalidJson(node);
        node = tree();
        ((ObjectNode) node.get("protocol")).put("reportFirstIteration", 10);
        assertInvalidJson(node);
        assertThrows(IllegalArgumentException.class, () -> new ExperimentSpec.FileRef("../escape", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ExperimentSpec.FileRef("/absolute", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ExperimentSpec.FileRef("data.xml", "invalid"));
    }

    @Test void zeroBetaAndFixedPlansRemainConfigurable() throws IOException {
        var node = tree();
        ((ObjectNode) node.get("policies")).put("betaFreight", 0).put("passengerAdaptation", false);
        var spec = readTree(node);
        assertEquals(0, spec.policies().betaFreight());
        assertFalse(spec.policies().passengerAdaptation());
    }

    @Test void configurationUsesExplicitInputsAndAllScientificOverrides() throws IOException {
        Path xml = temp.resolve("input.xml");
        var inputConfig = ConfigUtils.createConfig();
        inputConfig.replanning().addStrategySettings(freightStrategy("KeepLastSelected", 1.0, -1));
        new ConfigWriter(inputConfig).write(xml.toString());
        var node = tree();
        for (var entry : node.get("inputs").properties()) {
            ((ObjectNode) entry.getValue()).put("path", "input.xml")
                    .put("sha256", ExperimentConfiguration.sha256(xml));
        }
        ((ObjectNode) node.get("policies")).put("passengerAdaptation", false).put("betaFreight", 0);
        var spec = readTree(node);
        var config = ExperimentConfiguration.create(spec, temp, temp.resolve("output"));
        var co = ConfigUtils.addOrGetModule(config, CoTransportConfigGroup.class);
        assertEquals(0.1, co.getFreightShareCap());
        assertEquals(0.9, co.getPassengerLoadGate());
        assertEquals(0, co.getPassengerFreightCoPresencePenaltyPerMinute());
        assertEquals(59400, co.getFreightServiceWindowEndTime());
        assertEquals(20, co.getActivationIteration());
        assertEquals(150, co.getInnovationFinalOffIteration());
        assertEquals(xml.toString(), config.transit().getTransitScheduleFile());
        assertEquals(java.util.Set.of("pt", "freight_pt"), config.transit().getTransitModes());
        assertEquals(spec.seeds().matsimGlobal(), config.global().getRandomSeed());
        assertEquals(199, config.controller().getLastIteration());
        assertEquals(2, config.replanning().getStrategySettings().size());
        assertTrue(config.replanning().getStrategySettings().stream()
                .allMatch(strategy -> "KeepLastSelected".equals(strategy.getStrategyName())));
        assertEquals(1, config.replanning().getStrategySettings().stream()
                .filter(strategy -> "freight".equals(strategy.getSubpopulation())).count());
        Files.writeString(xml, "tampered");
        assertThrows(IOException.class, () -> ExperimentConfiguration.verifyInputs(spec, temp));
    }

    @Test void additionalUnregisteredScenarioInputsAreRejectedBeforeLoading() throws IOException {
        java.util.List<java.util.function.Consumer<org.matsim.core.config.Config>> extraInputs = java.util.List.of(
                c -> c.network().setChangeEventsInputFile("unregistered.xml"),
                c -> c.network().setLaneDefinitionsFile("unregistered.xml"),
                c -> c.facilities().setInputFile("unregistered.xml"),
                c -> c.facilities().setInputFacilitiesAttributesFile("unregistered.xml"),
                c -> c.households().setInputFile("unregistered.xml"),
                c -> c.households().setInputHouseholdAttributesFile("unregistered.xml"),
                c -> c.transit().setTransitLinesAttributesFile("unregistered.xml"),
                c -> c.transit().setTransitStopsAttributesFile("unregistered.xml"),
                c -> c.counts().setInputFile("unregistered.xml"),
                c -> c.replanning().setExternalExeConfigTemplate("unregistered.xml"),
                c -> c.replanning().setExternalExeTmpFileRootDir("unregistered"),
                c -> c.replanning().addStrategySettings(new org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings()
                        .setStrategyName("ExternalModule").setWeight(1).setExePath("unregistered-command")));
        for (var extra : extraInputs) {
            var config = ConfigUtils.createConfig();
            config.replanning().addStrategySettings(freightStrategy("KeepLastSelected", 1.0, -1));
            extra.accept(config);
            Path xml = temp.resolve("extra-input-config.xml");
            new ConfigWriter(config).write(xml.toString());
            var node = tree();
            for (var entry : node.get("inputs").properties()) {
                ((ObjectNode) entry.getValue()).put("path", xml.getFileName().toString())
                        .put("sha256", ExperimentConfiguration.sha256(xml));
            }
            var spec = readTree(node);
            assertThrows(IOException.class, () -> ExperimentConfiguration.create(spec, temp, temp.resolve("output")));
        }
    }

    @Test void missingFreightStrategyIsRejectedEvenWhenPassengerAdaptationIsDisabled() {
        assertAll("a default-population selector does not supply the freight subpopulation",
                () -> assertFreightRejected(passengerStrategies(), true, "missing freight selector"),
                () -> assertFreightRejected(passengerStrategies(), false, "missing freight selector"));
    }

    @Test void zeroFreightControlsDoNotRequireAnUnusedFreightSelector() throws IOException {
        for (var arm : List.of(ExperimentSpec.Arm.CONTROL, ExperimentSpec.Arm.TIMETABLE_ONLY)) {
            for (boolean adaptation : new boolean[]{true, false}) {
                var original = ExperimentSpecIO.compose(profiles(), "B2", arm);
                assertEquals(0, original.freightDemand().orderCount());
                var spec = configFixture(passengerStrategies(), original, adaptation);
                var actual = ExperimentConfiguration.create(spec, temp, temp.resolve("output"));
                assertTrue(strategyParameters(actual, "freight").isEmpty(),
                        "a q0 control must not acquire a fictitious freight strategy");
            }
        }
    }

    @Test void activeFreightInnovationOrPlanReselectionIsRejectedAlongsideValidSelector() {
        List<Executable> cases = new ArrayList<>();
        // A positive KeepLastSelected weight must not conceal a second strategy that can change freight plans.
        for (String name : List.of("ReRoute", "TimeAllocationMutator", "SubtourModeChoice", "ChangeExpBeta")) {
            for (boolean adaptation : new boolean[]{true, false}) {
                cases.add(() -> {
                    var input = passengerStrategies();
                    input.replanning().addStrategySettings(freightStrategy("KeepLastSelected", 0.8, -1));
                    input.replanning().addStrategySettings(freightStrategy(name, 0.2, -1));
                    assertFreightRejected(input, adaptation, name);
                });
            }
        }
        assertAll("only fixed freight plans, irrespective of passenger adaptation", cases);
    }

    @Test void soleFreightSelectorRequiresFinitePositiveWeight() {
        List<Executable> cases = new ArrayList<>();
        for (double weight : new double[]{0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (boolean adaptation : new boolean[]{true, false}) {
                cases.add(() -> {
                    var input = passengerStrategies();
                    input.replanning().addStrategySettings(freightStrategy("KeepLastSelected", weight, -1));
                    assertFreightRejected(input, adaptation, "weight=" + weight);
                });
            }
        }
        assertAll("a freight selector must remain executable with a finite positive weight", cases);
    }

    @Test void dormantNonfixedFreightStrategiesAreRejectedAsAnInputContract() throws IOException {
        for (boolean adaptation : new boolean[]{true, false}) {
            for (boolean zeroWeight : new boolean[]{true, false}) {
                var input = passengerStrategies();
                input.replanning().addStrategySettings(freightStrategy("KeepLastSelected", 1.0, -1));
                input.replanning().addStrategySettings(freightStrategy("ReRoute", zeroWeight ? 0.0 : 0.2,
                        zeroWeight ? -1 : 0));
                var original = baseline();
                if (!zeroWeight) {
                    // A synthetic later-start protocol makes disableAfter=0 genuinely pre-start.
                    // Negative values other than -1 are not evidence of native early disabling.
                    var node = (ObjectNode) new ObjectMapper().readTree(ExperimentSpecIO.canonicalBytes(original));
                    ((ObjectNode) node.get("protocol")).put("firstIteration", 10).put("innovationOffIteration", 10);
                    original = readTree(node);
                }
                var spec = configFixture(input, original, adaptation);
                assertThrows(IOException.class, () -> ExperimentConfiguration.create(spec, temp, temp.resolve("output")),
                        "unused nonfixed entry is outside the fixed-freight contract");
            }
        }
    }

    @Test void earlyExpiryOfOneFixedSelectorIsAllowedWhenAnotherRetainsCoverage() throws IOException {
        var input = passengerStrategies();
        input.replanning().addStrategySettings(freightStrategy("KeepLastSelected", 0.25, -1));
        input.replanning().addStrategySettings(freightStrategy("KeepLastSelected", 0.75, 20));
        var expected = strategyParameters(input, "freight");
        var spec = configFixture(input, baseline(), true);
        var actual = ExperimentConfiguration.create(spec, temp, temp.resolve("output"));
        assertEquals(expected, strategyParameters(actual, "freight"));
    }

    @Test void soleFreightSelectorCannotBeDisabledBeforeExecutionEnds() throws IOException {
        var protocol = baseline().protocol();
        List<Executable> cases = new ArrayList<>();
        for (int disableAfter : new int[]{protocol.firstIteration(), protocol.activationIteration(), protocol.finalIteration() - 1}) {
            for (boolean adaptation : new boolean[]{true, false}) {
                cases.add(() -> {
                    var input = passengerStrategies();
                    input.replanning().addStrategySettings(freightStrategy("KeepLastSelected", 1.0, disableAfter));
                    assertFreightRejected(input, adaptation, "disableAfter=" + disableAfter);
                });
            }
        }
        assertAll("freight must not lose its selector during the configured execution interval", cases);
    }

    @Test void validFreightWeightsAndDisableBoundariesArePreservedWithoutNormalization() throws IOException {
        int finalIteration = baseline().protocol().finalIteration();
        for (double weight : new double[]{0.125, 1.0, 2.5}) {
            for (int disableAfter : new int[]{-1, finalIteration, finalIteration + 1}) {
                for (boolean adaptation : new boolean[]{true, false}) {
                    var input = passengerStrategies();
                    input.replanning().addStrategySettings(freightStrategy("KeepLastSelected", weight, disableAfter));
                    var expectedFreight = strategyParameters(input, "freight");
                    var expectedPassengers = strategyParameters(input, null);
                    var spec = configFixture(input, baseline(), adaptation);
                    var actual = ExperimentConfiguration.create(spec, temp, temp.resolve("output"));
                    assertEquals(expectedFreight, strategyParameters(actual, "freight"),
                            "valid freight parameters must not be rewritten or renormalized");
                    if (adaptation) {
                        assertEquals(expectedPassengers, strategyParameters(actual, null));
                    } else {
                        var passengers = actual.replanning().getStrategySettings().stream()
                                .filter(strategy -> strategy.getSubpopulation() == null).toList();
                        assertEquals(1, passengers.size());
                        assertEquals("KeepLastSelected", passengers.getFirst().getStrategyName());
                        assertEquals(1.0, passengers.getFirst().getWeight());
                    }
                }
            }
        }
    }

    @Test void multipleValidFixedFreightStrategiesRemainUnchanged() throws IOException {
        for (boolean adaptation : new boolean[]{true, false}) {
            var input = passengerStrategies();
            input.replanning().addStrategySettings(freightStrategy("KeepLastSelected", 0.25, -1));
            input.replanning().addStrategySettings(freightStrategy("KeepLastSelected", 0.75,
                    baseline().protocol().finalIteration()));
            var expected = strategyParameters(input, "freight");
            var spec = configFixture(input, baseline(), adaptation);
            var actual = ExperimentConfiguration.create(spec, temp, temp.resolve("output"));
            assertEquals(expected, strategyParameters(actual, "freight"),
                    "the guard must not impose a single-entry format or rebuild valid strategies");
        }
    }

    @Test void allTwelveProfileTemplatesRetainTheirFreightStrategyParameters() throws IOException {
        Path workspace = profiles().getParent().getParent().getParent();
        for (String block : List.of("B1", "B2", "B3")) {
            for (var arm : ExperimentSpec.Arm.values()) {
                var original = ExperimentSpecIO.compose(profiles(), block, arm);
                Path xml = original.inputs().matsimConfig().resolve(workspace);
                assertEquals(original.inputs().matsimConfig().sha256(), ExperimentConfiguration.sha256(xml));
                var input = ConfigUtils.loadConfig(xml.toString(), new CoTransportConfigGroup(),
                        new org.matsim.run.r17.PlanAExecutionHorizonConfigGroup());
                var expected = strategyParameters(input, "freight");
                assertEquals(1, expected.size());
                assertEquals("KeepLastSelected", expected.getFirst().get("strategyName"));
                assertEquals("1.0", expected.getFirst().get("weight"));
                // Only create the config: no real population, schedule, or simulation is loaded.
                var spec = configFixture(input, original, original.policies().passengerAdaptation());
                var actual = ExperimentConfiguration.create(spec, temp, temp.resolve("output"));
                assertEquals(expected, strategyParameters(actual, "freight"), block + "/" + arm);
            }
        }
    }

    private void assertFreightRejected(Config input, boolean adaptation, String reason) throws IOException {
        var spec = configFixture(input, baseline(), adaptation);
        assertThrows(IOException.class, () -> ExperimentConfiguration.create(spec, temp, temp.resolve("output")),
                reason + "; passengerAdaptation=" + adaptation);
    }

    private ExperimentSpec configFixture(Config input, ExperimentSpec original, boolean adaptation) throws IOException {
        Path xml = temp.resolve("freight-policy-config.xml");
        new ConfigWriter(input).write(xml.toString());
        var node = (ObjectNode) new ObjectMapper().readTree(ExperimentSpecIO.canonicalBytes(original));
        String hash = ExperimentConfiguration.sha256(xml);
        for (var entry : node.get("inputs").properties()) {
            ((ObjectNode) entry.getValue()).put("path", xml.getFileName().toString()).put("sha256", hash);
        }
        ((ObjectNode) node.get("policies")).put("passengerAdaptation", adaptation);
        return readTree(node);
    }

    private static Config passengerStrategies() {
        var input = ConfigUtils.createConfig();
        input.replanning().addStrategySettings(new StrategySettings()
                .setStrategyName("ChangeExpBeta").setWeight(0.8));
        input.replanning().addStrategySettings(new StrategySettings()
                .setStrategyName("ReRoute").setWeight(0.2));
        return input;
    }

    private static StrategySettings freightStrategy(String name, double weight, int disableAfter) {
        return new StrategySettings().setSubpopulation("freight").setStrategyName(name)
                .setWeight(weight).setDisableAfter(disableAfter);
    }

    private static List<Map<String, String>> strategyParameters(Config config, String subpopulation) {
        return config.replanning().getStrategySettings().stream()
                .filter(strategy -> java.util.Objects.equals(subpopulation, strategy.getSubpopulation()))
                .<Map<String, String>>map(strategy -> new LinkedHashMap<>(strategy.getParams())).toList();
    }

    private ObjectNode tree() throws IOException {
        return (ObjectNode) new ObjectMapper().readTree(ExperimentSpecIO.canonicalBytes(baseline()));
    }
    private ExperimentSpec readTree(ObjectNode node) throws IOException {
        Path path = temp.resolve("edited.json");
        new ObjectMapper().writeValue(path.toFile(), node);
        return ExperimentSpecIO.read(path);
    }
    private void assertInvalidJson(ObjectNode node) {
        assertThrows(IOException.class, () -> readTree(node));
    }
}
