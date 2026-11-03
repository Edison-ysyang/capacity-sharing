package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Configuration rejection only; these tests do not run a Controller or certify input files. */
class DynamicMixedControllerModuleTest {
    @TempDir Path temporary;
    private static final DynamicMixedDesign.Protocol PROTOCOL =
            new DynamicMixedDesign.Protocol(0, 2, 4, 5, 4, 5);

    @Test void acceptsExplicitMatchedProtocolWithoutChangingThreadsOrWeights() {
        Config config = config();
        var co = settings().build();
        var weights = config.replanning().getStrategySettings().stream().map(s -> s.getWeight()).toList();
        DynamicMixedControllerModule.validateConfiguration(design(), co, config);
        assertEquals(7, config.qsim().getNumberOfThreads());
        assertEquals(7, config.global().getNumberOfThreads());
        assertNull(config.eventsManager().getNumberOfThreads());
        assertEquals(weights, config.replanning().getStrategySettings().stream().map(s -> s.getWeight()).toList());
        assertEquals(0, co.innovationOffIteration());
        assertEquals(2, co.innovationReopenIteration());
        assertEquals(4, co.innovationFinalOffIteration());
    }

    @Test void rejectsWrongControllerEndpoints() {
        Config config = config();
        config.controller().setLastIteration(6);
        assertThrows(IllegalStateException.class,
                () -> DynamicMixedControllerModule.validateConfiguration(design(), settings().build(), config));
    }

    @Test void programmaticAssemblyRejectsFcfsBeforePassengerFirstProjectionWithoutChangingSettings() {
        for (var arm : List.of(DynamicMixedDesign.Arm.MIXED, DynamicMixedDesign.Arm.DYNAMIC_MIXED)) {
            Config config = config();
            var co = settings().boardingOrder(CoTransportSettings.BoardingOrder.FIRST_COME_FIRST_SERVED).build();
            var assembly = new DynamicMixedDesign(arm, PROTOCOL,
                    arm == DynamicMixedDesign.Arm.DYNAMIC_MIXED, design().limits());
            var weights = config.replanning().getStrategySettings().stream().map(s -> s.getWeight()).toList();
            var failure = assertThrows(IllegalStateException.class,
                    () -> DynamicMixedControllerModule.validateConfiguration(assembly, co, config));
            assertTrue(failure.getMessage().contains("PASSENGER_FIRST"));
            assertTrue(failure.getMessage().contains("FIRST_COME_FIRST_SERVED"));
            assertTrue(failure.getMessage().contains("projection"));
            assertEquals(CoTransportSettings.BoardingOrder.FIRST_COME_FIRST_SERVED, co.boardingOrder());
            assertEquals(weights, config.replanning().getStrategySettings().stream().map(s -> s.getWeight()).toList());
            assertEquals(7, config.qsim().getNumberOfThreads());
            assertEquals(7, config.global().getNumberOfThreads());
        }
    }

    @Test void freshControllerDoesNotPretendToResumeAtAnExplicitLaterIteration() {
        var later = new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED,
                new DynamicMixedDesign.Protocol(1, 2, 4, 5, 4, 5), true,
                new DispatchCommitmentState.Limits(20, 20, 1));
        Config config = config();
        config.controller().setFirstIteration(1);
        assertThrows(IllegalStateException.class,
                () -> DynamicMixedControllerModule.validateConfiguration(later, settings().build(), config));
    }

    @Test void rejectsFreightModesThatBreakCommonHistory() {
        for (var mode : List.of(CoTransportSettings.ArmMode.DISABLED, CoTransportSettings.ArmMode.ALWAYS_ACTIVE))
            assertThrows(IllegalStateException.class, () -> DynamicMixedControllerModule.validateConfiguration(
                    design(), settings().armMode(mode).build(), config()));
    }

    @Test void rejectsEarlyActivationAndLaterDeactivation() {
        assertThrows(IllegalStateException.class, () -> DynamicMixedControllerModule.validateConfiguration(
                design(), settings().activationIteration(1).build(), config()));
        assertThrows(IllegalStateException.class, () -> DynamicMixedControllerModule.validateConfiguration(
                design(), settings().deactivationIteration(5).build(), config()));
    }

    @Test void rejectsUnmanagedOrMismatchedInnovationCalendar() {
        for (var co : List.of(settings().manageInnovationSchedule(false).build(),
                settings().innovationOffIteration(1).build(), settings().innovationReopenIteration(3).build(),
                settings().innovationFinalOffIteration(5).build()))
            assertThrows(IllegalStateException.class,
                    () -> DynamicMixedControllerModule.validateConfiguration(design(), co, config()));
    }

    @Test void rejectsMissingBaselineOrTransitOrMaterializedHorizon() {
        assertThrows(IllegalStateException.class, () -> DynamicMixedControllerModule.validateConfiguration(
                design(), settings().baselineTransitScheduleFile(null).build(), config()));
        Config noTransit = config();
        noTransit.transit().setUseTransit(false);
        assertThrows(IllegalStateException.class, () -> DynamicMixedControllerModule.validateConfiguration(
                design(), settings().build(), noTransit));
        Config noHorizon = config();
        noHorizon.removeModule(PlanAExecutionHorizonConfigGroup.GROUP_NAME);
        assertThrows(IllegalStateException.class, () -> DynamicMixedControllerModule.validateConfiguration(
                design(), settings().build(), noHorizon));
    }

    @Test void rejectsMissingFreightSelector() {
        Config config = config();
        config.replanning().clearStrategySettings();
        assertThrows(IllegalStateException.class,
                () -> DynamicMixedControllerModule.validateConfiguration(design(), settings().build(), config));
    }

    @Test void rejectsDedicatedAdmissionUnderMixedIdentity() {
        assertThrows(IllegalStateException.class, () -> DynamicMixedControllerModule.validateConfiguration(
                design(), settings().dedicatedFreightLineId("dedicated").build(), config()));
    }

    @Test void explicitActivatedScheduleMustHaveExactlyTheBaselineBytes() throws Exception {
        Path baseline = temporary.resolve("baseline.xml");
        Path activated = temporary.resolve("activated.xml");
        Files.writeString(baseline, "same declared schedule payload");
        Files.writeString(activated, "same declared schedule payload");
        Path vehicles = temporary.resolve("unchanged-vehicles.xml");
        Files.writeString(vehicles, "unchanged declared vehicle payload");
        Config config = config();
        config.transit().setVehiclesFile(vehicles.toString());
        var co = settings().baselineTransitScheduleFile(baseline).activatedTransitScheduleFile(activated)
                .activatedTransitVehiclesFile(vehicles).build();
        assertDoesNotThrow(() -> DynamicMixedControllerModule.validateConfiguration(design(), co, config));
        Files.writeString(activated, "changed route/stop attribute payload");
        assertThrows(IllegalStateException.class,
                () -> DynamicMixedControllerModule.validateConfiguration(design(), co, config));
    }

    @Test void explicitVehicleOverrideCannotSilentlyIncreaseCapacity() throws Exception {
        Path baseline = temporary.resolve("baseline-vehicles.xml");
        Path activated = temporary.resolve("activated-vehicles.xml");
        Files.writeString(baseline, "declared capacity payload");
        Files.writeString(activated, "declared capacity payload");
        Config config = config();
        config.transit().setVehiclesFile(baseline.toString());
        Path schedule = temporary.resolve("unchanged-schedule.xml");
        Files.writeString(schedule, "unchanged declared schedule payload");
        var co = settings().baselineTransitScheduleFile(schedule).activatedTransitScheduleFile(schedule)
                .activatedTransitVehiclesFile(activated).build();
        assertDoesNotThrow(() -> DynamicMixedControllerModule.validateConfiguration(design(), co, config));
        Files.writeString(activated, "increased capacity payload");
        assertThrows(IllegalStateException.class,
                () -> DynamicMixedControllerModule.validateConfiguration(design(), co, config));
    }

    @Test void rejectsFreightInnovationIncludingDormantEntries() {
        Config config = config();
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setSubpopulation("freight").setStrategyName("ReRoute").setWeight(0));
        assertThrows(IllegalStateException.class,
                () -> DynamicMixedControllerModule.validateConfiguration(design(), settings().build(), config));
    }

    @Test void rejectsAnAdditionalStrategyExpirationInsteadOfSilentlyRepairingIt() {
        Config config = config();
        config.replanning().getStrategySettings().iterator().next().setDisableAfter(3);
        assertThrows(IllegalStateException.class,
                () -> DynamicMixedControllerModule.validateConfiguration(design(), settings().build(), config));
    }

    @Test void rejectsConflictingAutomaticClosureBeforeAnyNativeReplanningRuns() {
        Config config = config();
        config.replanning().setFractionOfIterationsToDisableInnovation(.4); // native closure at 3
        assertThrows(IllegalStateException.class,
                () -> DynamicMixedControllerModule.validateConfiguration(design(), settings().build(), config));
        assertEquals(.4, config.replanning().getFractionOfIterationsToDisableInnovation());
    }

    @Test void retainsHistoricalInfinityAndCompatibleRedundantClosure() {
        for (double fraction : List.of(Double.POSITIVE_INFINITY, 0.0, .6, 1.0)) {
            Config config = config();
            config.replanning().setFractionOfIterationsToDisableInnovation(fraction);
            assertDoesNotThrow(() -> DynamicMixedControllerModule.validateConfiguration(design(), settings().build(), config));
            assertEquals(fraction, config.replanning().getFractionOfIterationsToDisableInnovation());
        }
    }

    @Test void fixedBaselineAssemblyDoesNotPretendToRegisterControlOrDedicatedSupply() {
        for (var arm : List.of(DynamicMixedDesign.Arm.CONTROL, DynamicMixedDesign.Arm.DEDICATED)) {
            var unsupported = new DynamicMixedDesign(arm, PROTOCOL, false, design().limits());
            assertThrows(IllegalArgumentException.class, () -> new DynamicMixedControllerModule(
                    unsupported, settings().build(), iteration -> null, null, session -> {}));
        }
    }

    private static DynamicMixedDesign design() {
        return new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED, PROTOCOL, true,
                new DispatchCommitmentState.Limits(20, 20, 1));
    }

    private static CoTransportSettings.Builder settings() {
        return CoTransportSettings.builder(Path.of("controller-test-orders.csv"))
                .freightBoardingMode(CoTransportSettings.FreightBoardingMode.UNIT_SPLIT)
                .baselineTransitScheduleFile(Path.of("controller-test-baseline.xml"))
                .armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW).activationIteration(2)
                .manageInnovationSchedule(true).innovationOffIteration(0)
                .innovationReopenIteration(2).innovationFinalOffIteration(4);
    }

    private static Config config() {
        Config config = ConfigUtils.createConfig(new PlanAExecutionHorizonConfigGroup());
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(5);
        config.global().setNumberOfThreads(7);
        config.qsim().setNumberOfThreads(7);
        config.transit().setUseTransit(true);
        config.replanning().clearStrategySettings();
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setStrategyName("KeepLastSelected").setSubpopulation("freight").setWeight(1));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setStrategyName("ChangeExpBeta").setWeight(.85));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setStrategyName("ReRoute").setWeight(.15));
        return config;
    }
}
