package org.matsim.run.ismt;

import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** One config adapter for every arm; the full remaining MATSim parameters stay in the hashed XML. */
public final class ExperimentConfiguration {
    private ExperimentConfiguration() { }

    /** Configuration fields shared by designs; deliberately contains no legacy arm or schema identity. */
    public record PhaseParameters(int firstIteration, int activationIteration, int innovationOffIteration,
            int innovationReopenIteration, int innovationFinalOffIteration, int finalIteration,
            double behaviouralHorizonSeconds, double executionHorizonSeconds) {
        public PhaseParameters {
            if (firstIteration < 0 || activationIteration <= firstIteration || activationIteration > finalIteration
                    || innovationOffIteration < firstIteration || innovationReopenIteration < innovationOffIteration
                    || innovationFinalOffIteration < innovationReopenIteration || innovationFinalOffIteration > finalIteration
                    || !Double.isFinite(behaviouralHorizonSeconds) || behaviouralHorizonSeconds <= 0
                    || !Double.isFinite(executionHorizonSeconds) || executionHorizonSeconds <= behaviouralHorizonSeconds)
                throw new IllegalArgumentException("Invalid common configuration phases/horizons");
        }
    }

    /** A configuration request, not a scientific specification, verified input receipt or run authorization. */
    public record CommonRequest(ExperimentSpec.Seeds seeds, ExperimentSpec.Inputs inputs, PhaseParameters protocol,
            ExperimentSpec.FreightDemand freightDemand, ExperimentSpec.Policies policies,
            int eventsWriteInterval, int plansWriteInterval) {
        public CommonRequest {
            Objects.requireNonNull(seeds); Objects.requireNonNull(inputs); Objects.requireNonNull(protocol);
            Objects.requireNonNull(freightDemand); Objects.requireNonNull(policies);
            if (eventsWriteInterval < 0 || plansWriteInterval < 0)
                throw new IllegalArgumentException("Negative common output interval");
        }
    }

    public static CommonRequest commonRequest(ExperimentSpec spec) {
        var p = spec.protocol();
        return new CommonRequest(spec.seeds(), spec.inputs(), new PhaseParameters(p.firstIteration(), p.activationIteration(),
                p.innovationOffIteration(), p.innovationReopenIteration(), p.innovationFinalOffIteration(), p.finalIteration(),
                p.behaviouralHorizonSeconds(), p.executionHorizonSeconds()), spec.freightDemand(), spec.policies(),
                spec.output().eventsWriteInterval(), spec.output().plansWriteInterval());
    }

    public static void verifyInputs(ExperimentSpec spec, Path workspace) throws IOException {
        Path root = workspace.toRealPath();
        for (var entry : spec.inputs().all().entrySet()) {
            Path path = entry.getValue().resolve(root).toRealPath();
            if (!path.startsWith(root)) throw new IOException("Input symlink escapes workspace: " + path);
            if (!sha256(path).equals(entry.getValue().sha256()))
                throw new IOException("Input hash mismatch: " + entry.getKey() + " " + path);
        }
    }

    public static Config create(ExperimentSpec spec, Path workspace, Path output) throws IOException {
        verifyInputs(spec, workspace);
        return create(commonRequest(spec), workspace, output);
    }

    /**
     * Builds the common configuration only. The caller MUST first verify its own complete input
     * schema (legacy twelve roles, or dynamic nineteen roles). This overload does not authenticate
     * references, instantiate a Controller, install a policy, or authorize execution. In particular,
     * unmodified XML settings still determine threads, events, scoring and strategy order/weights.
     */
    public static Config create(CommonRequest spec, Path workspace, Path output) throws IOException {
        Objects.requireNonNull(spec);
        if (!output.isAbsolute() || !output.normalize().startsWith(workspace.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Output must be absolute and inside the workspace");
        if (Files.exists(output)) throw new IOException("Refusing existing output directory: " + output);
        var inputs = spec.inputs();
        var protocol = spec.protocol();
        var policies = spec.policies();
        var demand = spec.freightDemand();
        Config config = ConfigUtils.loadConfig(inputs.matsimConfig().resolve(workspace).toString(),
                new CoTransportConfigGroup(), new PlanAExecutionHorizonConfigGroup());
        rejectUnregisteredInputs(config);
        validateFixedFreightStrategies(config, demand.orderCount(), protocol.finalIteration());
        config.global().setRandomSeed(spec.seeds().matsimGlobal());
        config.network().setInputFile(inputs.network().resolve(workspace).toString());
        config.plans().setInputFile(inputs.population().resolve(workspace).toString());
        config.plans().setInputPersonAttributeFile(inputs.personAttributes().resolve(workspace).toString());
        config.transit().setTransitScheduleFile(inputs.baselineSchedule().resolve(workspace).toString());
        config.transit().setVehiclesFile(inputs.baselineTransitVehicles().resolve(workspace).toString());
        config.transit().setTransitModes(new java.util.LinkedHashSet<>(policies.transitModes()));
        config.vehicles().setVehiclesFile(inputs.roadVehicles().resolve(workspace).toString());
        config.controller().setOutputDirectory(output.normalize().toString());
        // Identity lives in the enclosing run directory and receipts; keep schema filenames stable.
        config.controller().setRunId(null);
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists);
        config.controller().setFirstIteration(protocol.firstIteration());
        config.controller().setLastIteration(protocol.finalIteration());
        config.controller().setWriteEventsInterval(spec.eventsWriteInterval());
        config.controller().setWritePlansInterval(spec.plansWriteInterval());
        config.qsim().setEndTime(protocol.behaviouralHorizonSeconds());
        ConfigUtils.addOrGetModule(config, PlanAExecutionHorizonConfigGroup.class)
                .setExecutionEndTime(protocol.executionHorizonSeconds());
        config.vspExperimental().setGeneratingBoardingDeniedEvent(true);
        CoTransportConfigGroup co = ConfigUtils.addOrGetModule(config, CoTransportConfigGroup.class);
        co.setFreightOrdersFile(inputs.executionOrders().resolve(workspace).toString());
        co.setBaselineTransitScheduleFile(inputs.baselineSchedule().resolve(workspace).toString());
        co.setActivatedTransitScheduleFile(inputs.activatedSchedule().resolve(workspace).toString());
        co.setActivatedTransitVehiclesFile(inputs.activatedTransitVehicles().resolve(workspace).toString());
        // Empty controls undergo the same one-time lifecycle transition (a sham).
        co.setArmMode(CoTransportSettings.ArmMode.ITERATION_WINDOW);
        co.setActivationIteration(protocol.activationIteration());
        co.setDeactivationIteration(-1);
        co.setManageInnovationSchedule(true);
        co.setInnovationOffIteration(protocol.innovationOffIteration());
        co.setInnovationReopenIteration(protocol.innovationReopenIteration());
        co.setInnovationFinalOffIteration(protocol.innovationFinalOffIteration());
        co.setFreightEquivalentPassengersPerM3(demand.equivalentPassengersPerM3());
        co.setFreightServiceWindowStartTime(demand.serviceWindowStartSeconds());
        co.setFreightServiceWindowEndTime(demand.serviceWindowEndSeconds());
        co.setFreightActivityPrefix(demand.destinationActivityPrefix());
        co.setFreightShadowToken(demand.shadowToken());
        co.setDedicatedFreightLineId(policies.dedicatedLineId());
        co.setFreightShareCap(policies.freightShareCap());
        co.setPassengerLoadGate(policies.passengerLoadGate());
        co.setHardCapacityRatio(policies.legacyHardCapacityRatio());
        co.setFreightHandlingDwellSeconds(policies.handlingDwellSeconds());
        co.setComfortThreshold(policies.comfortThreshold());
        co.setPassengerPhysicalCrowdingPenaltyPerMinute(policies.betaPhysical());
        co.setPassengerFreightCoPresencePenaltyPerMinute(policies.betaFreight());
        co.setFreightLatePenaltyPerMinute(policies.freightLatePenaltyPerMinute());
        co.setFreightDeniedBoardingPenalty(policies.freightDeniedBoardingPenalty());
        co.setFreightIncompletePenaltyPerUnit(policies.freightIncompletePenaltyPerUnit());
        co.setPassengerDeniedBoardingPenalty(policies.passengerDeniedBoardingPenalty());
        if (!policies.passengerAdaptation()) {
            // Fixed means no memory reselection as well as no plan innovation.
            var freightStrategies = config.replanning().getStrategySettings().stream()
                    .filter(strategy -> "freight".equals(strategy.getSubpopulation())).toList();
            config.replanning().clearStrategySettings();
            freightStrategies.forEach(config.replanning()::addStrategySettings);
            config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                    .setStrategyName("KeepLastSelected").setWeight(1.0));
        }
        return config;
    }

    /**
     * Validate the fixed-freight input contract without altering settings or consuming random numbers.
     * Empty controls need no freight selector. For freight-bearing runs every freight entry must be
     * KeepLastSelected with a finite positive weight, and at least one must remain enabled through
     * the final iteration. Additional fixed selectors may expire earlier without losing that cover.
     *
     * <p>Non-fixed entries are rejected even when configured with zero weight or disabled before the
     * first iteration. The contract does not rely on such entries remaining dormant under later
     * strategy changes. This is an input restriction, not a claim that the passenger phase schedule
     * currently re-enables freight strategies. Valid entries, including their order and weights,
     * are left unchanged.</p>
     */
    static void validateFixedFreightStrategies(Config config, ExperimentSpec spec) throws IOException {
        validateFixedFreightStrategies(config, spec.freightDemand().orderCount(), spec.protocol().finalIteration());
    }

    private static void validateFixedFreightStrategies(Config config, int orderCount, int finalIteration) throws IOException {
        if (orderCount == 0) return;
        boolean foundFreight = false;
        boolean enabledThroughFinalIteration = false;
        for (var strategy : config.replanning().getStrategySettings()) {
            if (!"freight".equals(strategy.getSubpopulation())) continue;
            foundFreight = true;
            if (!"KeepLastSelected".equals(strategy.getStrategyName())) {
                throw new IOException("Fixed freight plans require KeepLastSelected; unsupported freight strategy: "
                        + strategy.getStrategyName());
            }
            if (!Double.isFinite(strategy.getWeight()) || strategy.getWeight() <= 0.0) {
                throw new IOException("Freight KeepLastSelected weight must be finite and positive: "
                        + strategy.getWeight());
            }
            if (strategy.getDisableAfter() == -1 || strategy.getDisableAfter() >= finalIteration) {
                enabledThroughFinalIteration = true;
            }
        }
        if (!foundFreight) {
            throw new IOException("Freight-bearing runs require an explicit freight KeepLastSelected strategy");
        }
        if (!enabledThroughFinalIteration) {
            throw new IOException("At least one freight KeepLastSelected strategy must remain enabled through final iteration "
                    + finalIteration + " (disableAfterIteration=-1 or >= finalIteration)");
        }
    }

    /** This schema supports the twelve explicit FileRefs, not arbitrary unbound XML side inputs. */
    static void rejectUnregisteredInputs(Config config) throws IOException {
        rejectInput("network.inputChangeEventsFile", config.network().getChangeEventsInputFile());
        rejectInput("network.laneDefinitionsFile", config.network().getLaneDefinitionsFile());
        rejectInput("facilities.inputFacilitiesFile", config.facilities().getInputFile());
        rejectInput("facilities.inputFacilityAttributesFile", config.facilities().getInputFacilitiesAttributesFile());
        rejectInput("households.inputFile", config.households().getInputFile());
        rejectInput("households.inputHouseholdAttributesFile", config.households().getInputHouseholdAttributesFile());
        rejectInput("transit.transitLinesAttributesFile", config.transit().getTransitLinesAttributesFile());
        rejectInput("transit.transitStopsAttributesFile", config.transit().getTransitStopsAttributesFile());
        rejectInput("counts.inputCountsFile", config.counts().getCountsFileName());
        var ptCounts = config.getModules().get("ptCounts");
        if (ptCounts != null) for (String key : java.util.List.of("inputOccupancyCountsFile", "inputBoardCountsFile", "inputAlightCountsFile"))
            rejectInput("ptCounts." + key, ptCounts.getParams().get(key));
        rejectInput("replanning.ExternalExeConfigTemplate", config.replanning().getExternalExeConfigTemplate());
        rejectInput("replanning.ExternalExeTmpFileRootDir", config.replanning().getExternalExeTmpFileRootDir());
        for (var strategy : config.replanning().getStrategySettings())
            rejectInput("replanning.strategy.exePath", strategy.getExePath());
    }

    private static void rejectInput(String field, String path) throws IOException {
        if (path != null && !path.isBlank() && !path.equals("null"))
            throw new IOException("Unregistered auxiliary input/executable is not supported by ismt-experiment-v1: "
                    + field + ". Add an explicit hashed input to the schema before enabling it.");
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                for (int count; (count = stream.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
