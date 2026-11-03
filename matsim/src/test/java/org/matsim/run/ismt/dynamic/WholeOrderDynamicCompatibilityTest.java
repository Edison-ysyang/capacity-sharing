package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.cotransport.CoTransportSettings;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Configuration-only rejection: never constructs or evaluates a predictor or runs QSim. */
class WholeOrderDynamicCompatibilityTest {
    private static DynamicMixedDesign design(DynamicMixedDesign.Arm arm) {
        return new DynamicMixedDesign(arm, new DynamicMixedDesign.Protocol(0, 2, 4, 5, 4, 5),
                arm == DynamicMixedDesign.Arm.DYNAMIC_MIXED, new DispatchCommitmentState.Limits(60, 60, 120));
    }

    @Test void dynamicWholeOrderIsRejectedBeforeAnyDailyInputOrForecastCanBeRequested() {
        var settings = CoTransportSettings.builder(Path.of("never-opened.csv")).build();
        var failure = assertThrows(IllegalStateException.class,
                () -> DynamicMixedScenarioInputs.requireSupportedBoardingMode(settings, design(DynamicMixedDesign.Arm.DYNAMIC_MIXED)));
        assertTrue(failure.getMessage().contains("WHOLE_ORDER"));
        assertTrue(failure.getMessage().contains("predictor"));
        assertTrue(failure.getMessage().contains("UNIT_SPLIT"));
        assertThrows(IllegalStateException.class, () -> new DynamicMixedControllerModule(
                design(DynamicMixedDesign.Arm.DYNAMIC_MIXED), settings,
                day -> { fail("Daily inputs must not be requested"); return null; }, null,
                session -> fail("No session must start")));
    }

    @Test void explicitHistoricalUnitSplitRemainsAvailableAndFixedMixedIsNotPredictorBlocked() {
        var split = CoTransportSettings.builder(Path.of("never-opened.csv"))
                .freightBoardingMode(CoTransportSettings.FreightBoardingMode.UNIT_SPLIT).build();
        assertDoesNotThrow(() -> DynamicMixedScenarioInputs.requireSupportedBoardingMode(split, design(DynamicMixedDesign.Arm.DYNAMIC_MIXED)));
        assertDoesNotThrow(() -> DynamicMixedScenarioInputs.requireSupportedBoardingMode(
                CoTransportSettings.builder(Path.of("never-opened.csv")).build(), design(DynamicMixedDesign.Arm.MIXED)));
    }
}
