package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.run.ismt.ScientificRunContext;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import static org.junit.jupiter.api.Assertions.*;

class DynamicMixedArtifactModuleTest {
    private static final DynamicMixedDesign DESIGN = new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED,
            new DynamicMixedDesign.Protocol(0, 2, 4, 5, 4, 5), true, new DispatchCommitmentState.Limits(25, 25, 5));

    @Test void matchingContextDoesNotChangeRuntimeConfiguration() {
        Config config = config();
        assertDoesNotThrow(() -> DynamicMixedArtifactModule.validateContext(context(DESIGN), DESIGN, config, 1));
        assertEquals(200, config.qsim().getEndTime().seconds());
        assertEquals(240, ((PlanAExecutionHorizonConfigGroup) config.getModule(PlanAExecutionHorizonConfigGroup.GROUP_NAME)).getExecutionEndTime());
        assertEquals(5, config.controller().getLastIteration());
    }

    @Test void refusesAnotherArmControlSwitchAmplitudeOrCalendar() {
        for (var other : new DynamicMixedDesign[]{
                new DynamicMixedDesign(DynamicMixedDesign.Arm.MIXED, DESIGN.protocol(), false, DESIGN.limits()),
                new DynamicMixedDesign(DESIGN.arm(), DESIGN.protocol(), false, DESIGN.limits()),
                new DynamicMixedDesign(DESIGN.arm(), DESIGN.protocol(), true, new DispatchCommitmentState.Limits(0, 0, 5)),
                new DynamicMixedDesign(DESIGN.arm(), new DynamicMixedDesign.Protocol(0, 2, 3, 5, 4, 5), true, DESIGN.limits())})
            assertThrows(IllegalStateException.class, () -> DynamicMixedArtifactModule.validateContext(context(DESIGN), other, config(), 1));
    }

    @Test void refusesDifferentActualEndpoints() {
        Config first = config(); first.controller().setFirstIteration(1);
        Config last = config(); last.controller().setLastIteration(6);
        for (var config : new Config[]{first, last})
            assertThrows(IllegalStateException.class, () -> DynamicMixedArtifactModule.validateContext(context(DESIGN), DESIGN, config, 1));
    }

    @Test void refusesMissingOrWrongLoadedOrders() {
        for (int count : new int[]{0, 2})
            assertThrows(IllegalStateException.class, () -> DynamicMixedArtifactModule.validateContext(context(DESIGN), DESIGN, config(), count));
    }

    @Test void refusesDifferentBehaviouralOrMissingMaterializedExecutionHorizon() {
        Config behaviour = config(); behaviour.qsim().setEndTime(201);
        assertThrows(IllegalStateException.class, () -> DynamicMixedArtifactModule.validateContext(context(DESIGN), DESIGN, behaviour, 1));
        Config missing = ConfigUtils.createConfig(); missing.controller().setLastIteration(5); missing.qsim().setEndTime(200);
        assertThrows(IllegalStateException.class, () -> DynamicMixedArtifactModule.validateContext(context(DESIGN), DESIGN, missing, 1));
        Config execution = config(); ConfigUtils.addOrGetModule(execution, PlanAExecutionHorizonConfigGroup.class).setExecutionEndTime(241);
        assertThrows(IllegalStateException.class, () -> DynamicMixedArtifactModule.validateContext(context(DESIGN), DESIGN, execution, 1));
    }

    @Test void outputModuleCannotPretendToSupportDedicatedOrNoFreightRuntime() {
        for (var arm : new DynamicMixedDesign.Arm[]{DynamicMixedDesign.Arm.CONTROL, DynamicMixedDesign.Arm.DEDICATED}) {
            var design = new DynamicMixedDesign(arm, DESIGN.protocol(), false, DESIGN.limits());
            var context = ScientificRunContext.dynamic(design, "fixture", "B1", arm == DynamicMixedDesign.Arm.CONTROL ? 0 : 1,
                    200, 240, ScientificRunContext.ExposureOutput.TERMINAL, false);
            assertThrows(IllegalArgumentException.class, () -> new DynamicMixedArtifactModule(context));
        }
    }

    private static ScientificRunContext context(DynamicMixedDesign design) {
        return ScientificRunContext.dynamic(design, "fixture", "B1", 1, 200, 240,
                ScientificRunContext.ExposureOutput.REPORTING_WINDOW, false);
    }

    private static Config config() {
        Config config = ConfigUtils.createConfig();
        config.controller().setFirstIteration(0); config.controller().setLastIteration(5);
        config.qsim().setEndTime(200);
        ConfigUtils.addOrGetModule(config, PlanAExecutionHorizonConfigGroup.class).setExecutionEndTime(240);
        return config;
    }
}
