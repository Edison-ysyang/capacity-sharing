package org.matsim.run.ismt.dynamic;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.cotransport.CoTransportPhaseController;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.replanning.ReplanningUtils;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.run.ismt.SharedIsmtExecutionModule;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Common M/T Controller assembly, with a separate v2 identity. Installs the existing
 * freight activation, native boarding policy, exposure scoring, passenger routing,
 * initial feasibility canonicalization and split-horizon adapters without altering
 * their implementations. QSim creates fresh dispatch/observation state each day;
 * the Controller retains native passenger plan memory between days.
 *
 * <p>This is NOT a formal experiment runner, input/provenance manifest, dispatch
 * policy, complete event-producer certification, or legacy C/M/D reuse gate. In
 * particular no legacy ExperimentSpec is forged to enable the v1 artifact writers.
 * Scientific writers can be installed separately with DynamicMixedArtifactModule
 * and its explicit v2 context; they are not installed by this module. Only the common fixed-baseline
 * M/T lifecycle is supported here; D requires a separately registered supply package.
 * The caller supplies audited daily inputs and an explicit policy. No scientific
 * control parameter or MATSim thread setting is silently supplied or changed.</p>
 */
public final class DynamicMixedControllerModule extends AbstractModule {
    private final DynamicMixedDesign design;
    private final CoTransportSettings settings;
    private final IntFunction<DynamicMixedQSimModule.Inputs> inputs;
    private final DynamicMixedQSimModule.PolicyFactory policies;
    private final Consumer<DynamicMixedQSimModule.Session> sessions;
    private final DynamicMixedQSimModule.AssemblyCallbacks callbacks;

    public DynamicMixedControllerModule(DynamicMixedDesign design, CoTransportSettings settings,
            IntFunction<DynamicMixedQSimModule.Inputs> inputs,
            DynamicMixedQSimModule.PolicyFactory policies,
            Consumer<DynamicMixedQSimModule.Session> sessions) {
        this(design, settings, inputs, policies, sessions, DynamicMixedQSimModule.AssemblyCallbacks.none());
    }

    public DynamicMixedControllerModule(DynamicMixedDesign design, CoTransportSettings settings,
            IntFunction<DynamicMixedQSimModule.Inputs> inputs,
            DynamicMixedQSimModule.PolicyFactory policies, Consumer<DynamicMixedQSimModule.Session> sessions,
            DynamicMixedQSimModule.AssemblyCallbacks callbacks) {
        this.design = Objects.requireNonNull(design);
        this.settings = Objects.requireNonNull(settings);
        DynamicMixedScenarioInputs.requireSupportedBoardingMode(settings, design);
        this.inputs = Objects.requireNonNull(inputs);
        this.policies = policies;
        this.sessions = Objects.requireNonNull(sessions);
        this.callbacks = Objects.requireNonNull(callbacks);
        if (design.arm() != DynamicMixedDesign.Arm.MIXED
                && design.arm() != DynamicMixedDesign.Arm.DYNAMIC_MIXED)
            throw new IllegalArgumentException("This Controller assembly supports the common fixed-baseline M/T lifecycle only");
    }

    @Override public void install() {
        validateConfiguration(design, settings, getConfig());
        DaySlot slot = new DaySlot();
        bind(DynamicMixedDesign.class).toInstance(design);
        bind(DaySlot.class).toInstance(slot);
        install(new SharedIsmtExecutionModule(settings));
        bind(LifecycleCheck.class).asEagerSingleton();
        addControllerListenerBinding().to(LifecycleCheck.class);
        installOverridingQSimModule(new DynamicMixedQSimModule(design, inputs, policies, session -> {
            if (slot.current != null || slot.expectedIteration != session.iteration())
                throw new IllegalStateException("Missing Controller BeforeMobsim, duplicate QSim, or unfinished previous day");
            slot.current = session;
            sessions.accept(session);
        }, callbacks));
    }

    /** Validate supplied values; do not repair a mismatching protocol or adjust weights. */
    static void validateConfiguration(DynamicMixedDesign design, CoTransportSettings settings, Config config) {
        DynamicMixedScenarioInputs.requireSupportedBoardingMode(settings, design);
        // This shared assembly can also be installed programmatically, without a
        // UnifiedArmContract. Its current forecast allocates passengers first, so
        // admitting native FCFS here would silently change the execution semantics.
        require(settings.boardingOrder() == CoTransportSettings.BoardingOrder.PASSENGER_FIRST,
                "Current M/T Controller assembly requires PASSENGER_FIRST boarding because its projection is passenger-first; "
                        + "FIRST_COME_FIRST_SERVED requires a compatible forecast and independently registered assembly");
        var protocol = design.protocol();
        require(protocol.firstIteration() == 0, "This uninterrupted Controller assembly requires firstIteration=0");
        require(config.controller().getFirstIteration() == protocol.firstIteration()
                        && config.controller().getLastIteration() == protocol.finalIteration(),
                "Controller endpoints differ from the v2 protocol");
        require(settings.armMode() == CoTransportSettings.ArmMode.ITERATION_WINDOW
                        && settings.activationIteration() == protocol.activationIteration()
                        && settings.deactivationIteration() == -1,
                "Freight must activate once at the registered iteration and remain active");
        require(settings.manageInnovationSchedule()
                        && settings.innovationOffIteration() == protocol.firstIteration()
                        && settings.innovationReopenIteration() == protocol.activationIteration()
                        && settings.innovationFinalOffIteration() == protocol.innovationOffIteration(),
                "Passenger innovation schedule differs from the v2 common-history/reopen/final-off protocol");
        require(settings.baselineTransitScheduleFile() != null, "Explicit baseline schedule is required");
        require(settings.dedicatedFreightLineId() == null || settings.dedicatedFreightLineId().isBlank(),
                "M/T cannot enable dedicated-service admission");
        requireSamePayload(settings.baselineTransitScheduleFile(), settings.activatedTransitScheduleFile(), "schedule");
        if (settings.activatedTransitVehiclesFile() != null) {
            var baselineVehicles = config.transit().getVehiclesFileURL(config.getContext());
            require(baselineVehicles != null && "file".equals(baselineVehicles.getProtocol()),
                    "An activated vehicle payload requires an explicit local baseline vehicle file");
            try {
                requireSamePayload(Path.of(baselineVehicles.toURI()), settings.activatedTransitVehiclesFile(), "vehicles");
            } catch (URISyntaxException invalid) {
                throw new IllegalStateException("Invalid local baseline vehicle URI", invalid);
            }
        }
        require(config.transit().isUseTransit(), "Native transit must be enabled");
        require(config.getModule(PlanAExecutionHorizonConfigGroup.GROUP_NAME)
                        instanceof PlanAExecutionHorizonConfigGroup,
                "Materialized Plan-A split-horizon configuration is required");
        double automaticFraction = config.replanning().getFractionOfIterationsToDisableInnovation();
        require(!Double.isNaN(automaticFraction) && automaticFraction >= 0,
                "Invalid automatic innovation-disable fraction");
        if (Double.isFinite(automaticFraction)) {
            // Use native truncation; retain the legacy Infinity value unchanged.
            int after = (int) ((protocol.finalIteration() - protocol.firstIteration()) * automaticFraction
                    + protocol.firstIteration());
            long closesAt = (long) after + 1;
            require(closesAt < protocol.activationIteration() || closesAt >= protocol.innovationOffIteration(),
                    "Automatic innovation closure conflicts with the registered active innovation phase");
        }
        boolean fixedFreight = false;
        for (var strategy : config.replanning().getStrategySettings()) {
            require(strategy.getDisableAfter() == -1 || strategy.getDisableAfter() >= protocol.finalIteration(),
                    "Additional strategy disableAfter schedules are unsupported by this M/T assembly");
            if (!"freight".equals(strategy.getSubpopulation())) continue;
            require("KeepLastSelected".equals(strategy.getStrategyName())
                            && Double.isFinite(strategy.getWeight()) && strategy.getWeight() > 0,
                    "Freight requires a positive fixed KeepLastSelected strategy");
            fixedFreight = true;
        }
        require(fixedFreight, "M/T require an explicit fixed-freight strategy");
    }

    /** Byte comparison covers explicit static overrides, not a complete live-supply/input identity audit. */
    private static void requireSamePayload(Path baseline, Path activated, String role) {
        if (activated == null) return;
        try {
            require(Files.mismatch(baseline, activated) == -1,
                    "M/T cannot change the static activated " + role + " payload");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot verify baseline/activated " + role + " payloads", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class DaySlot {
        private int expectedIteration = -1;
        private DynamicMixedQSimModule.Session current;
    }

    /** Checks actual post-replanning state, not just requested change weights or phase labels. */
    @Singleton
    private static final class LifecycleCheck implements BeforeMobsimListener, IterationEndsListener {
        private final DynamicMixedDesign design;
        private final CoTransportPhaseController freightPhase;
        private final StrategyManager strategies;
        private final DaySlot slot;
        private final Map<String, List<StrategyRegistration>> initialStrategies = new LinkedHashMap<>();
        private int completed;

        private record StrategyRegistration(PlanStrategy strategy, double weight, boolean innovative) { }

        @Inject LifecycleCheck(DynamicMixedDesign design, CoTransportPhaseController freightPhase,
                StrategyManager strategies, DaySlot slot, FreightOrderRegistry registry, Scenario scenario) {
            this.design = design; this.freightPhase = freightPhase;
            this.strategies = strategies; this.slot = slot;
            // Eager construction precedes Startup's temporary removal of inactive freight.
            registry.validateFixedPlans(scenario.getPopulation());
            require(freightPhase.registeredFreightPersonCount() > 0, "M/T require registered fixed freight persons");
            for (var entry : strategies.getConfiguredStrategies().entrySet()) {
                var configured = entry.getKey();
                initialStrategies.computeIfAbsent(configured.getSubpopulation(), ignored -> new ArrayList<>())
                        .add(new StrategyRegistration(entry.getValue(), configured.getWeight(),
                                ReplanningUtils.isInnovativeStrategy(entry.getValue())));
            }
        }

        @Override public double priority() { return 500; }

        @Override public void notifyBeforeMobsim(BeforeMobsimEvent event) {
            int iteration = event.getIteration();
            require(slot.current == null && iteration == design.protocol().firstIteration() + completed,
                    "Missing, repeated or out-of-order Controller day");
            var phase = design.phaseAt(iteration);
            require(freightPhase.currentIteration() == iteration
                            && freightPhase.isFreightActive() == phase.freightTreatmentActive(),
                    "Actual freight installation disagrees with v2 phase");
            require(!freightPhase.isScheduleActive(), "M/T must not install a static intervention timetable");
            for (var entry : initialStrategies.entrySet()) {
                String subpopulation = entry.getKey();
                var actual = strategies.getStrategies(subpopulation);
                require(actual.size() == entry.getValue().size(), "Actual strategy registration count changed");
                for (int index = 0; index < actual.size(); index++) {
                    var registered = entry.getValue().get(index);
                    require(actual.get(index) == registered.strategy(), "Actual strategy identity/order changed");
                    double expected = registered.weight();
                    if (!"freight".equals(subpopulation) && registered.innovative()
                            && !phase.passengerInnovationEnabled()) expected = 0;
                    require(Double.compare(expected, strategies.getWeights(subpopulation).get(index)) == 0,
                            "Actual native strategy weight differs from v2 phase at iteration " + iteration);
                }
            }
            slot.expectedIteration = iteration;
        }

        @Override public void notifyIterationEnds(IterationEndsEvent event) {
            require(slot.current != null && slot.current.iteration() == event.getIteration(),
                    "Controller day ended without its registered native QSim session");
            var receipt = slot.current.receipt();
            require(receipt.preparedInputVerified() && receipt.nativeEventsProcessingFinished() && !receipt.poisoned(),
                    "Controller day ended with incomplete or failed native dynamic-module execution");
            require(receipt.phase().equals(design.phaseAt(event.getIteration())), "QSim/Controller phase mismatch");
            // Native processing completion is not terminal-prefix or full scientific-output certification.
            slot.current = null;
            slot.expectedIteration = -1;
            completed++;
        }
    }
}
