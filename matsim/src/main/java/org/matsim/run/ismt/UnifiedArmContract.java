package org.matsim.run.ismt;

import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.cotransport.CoTransportBoardingPolicy;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.run.ismt.dynamic.DynamicMixedControlDefinitions;
import org.matsim.run.ismt.dynamic.DynamicMixedExperimentSpec;

import java.util.Objects;

/**
 * Immutable, JSON-readable arm declaration plus a read-only preparation boundary.
 * This contract does not convert specifications, load/hash inputs, alter supply,
 * install a boarding window, enable control or certify a formal run. Definition
 * decoding, complete configuration and native execution checks remain separate.
 */
public record UnifiedArmContract(String schemaVersion, String specificationSchemaVersion,
        Arm arm, ExperimentSpec.Access access, RegisteredSupply supply, String dedicatedLineId,
        WindowMode windowMode, double serviceWindowStartSeconds, double serviceWindowEndSeconds,
        ControlMode controlMode, boolean controlEnabled, String controlPolicyId) {
    public static final String SCHEMA = "ismt-unified-arm-contract-v1";

    /** Historical TIMETABLE_ONLY is deliberately not represented as current T. */
    public enum Arm { CONTROL, MIXED, DYNAMIC_MIXED, DEDICATED, LEGACY_TIMETABLE_ONLY }
    public enum SupplyMode { REGISTERED_PASSENGER_SUPPLY, REGISTERED_DEDICATED_SERVICE_SUPPLY }
    public enum WindowMode { NO_PHYSICAL_BOARDING_WINDOW, REGISTERED_SHARED_BOARDING_WINDOW }
    public enum ControlMode { NOT_APPLICABLE, DISABLED, ZERO_AMPLITUDE, ENABLED }

    /** Exact registered references, not a claim that the activated supply is already loaded. */
    public record RegisteredSupply(SupplyMode mode, ExperimentSpec.FileRef baselineSchedule,
            ExperimentSpec.FileRef baselineTransitVehicles, ExperimentSpec.FileRef activatedSchedule,
            ExperimentSpec.FileRef activatedTransitVehicles) {
        public RegisteredSupply {
            Objects.requireNonNull(mode); Objects.requireNonNull(baselineSchedule);
            Objects.requireNonNull(baselineTransitVehicles); Objects.requireNonNull(activatedSchedule);
            Objects.requireNonNull(activatedTransitVehicles);
        }
    }

    public UnifiedArmContract {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported unified arm contract schema");
        boolean legacy = ExperimentSpec.SCHEMA.equals(specificationSchemaVersion);
        if (!legacy && !DynamicMixedExperimentSpec.SCHEMA.equals(specificationSchemaVersion))
            throw new IllegalArgumentException("Unsupported specification schema in arm contract");
        Objects.requireNonNull(arm); Objects.requireNonNull(access); Objects.requireNonNull(supply);
        Objects.requireNonNull(dedicatedLineId); Objects.requireNonNull(windowMode);
        Objects.requireNonNull(controlMode); Objects.requireNonNull(controlPolicyId);
        dedicatedLineId = dedicatedLineId.isBlank() ? "" : dedicatedLineId;
        if (!Double.isFinite(serviceWindowStartSeconds) || serviceWindowStartSeconds < 0
                || !Double.isFinite(serviceWindowEndSeconds) || serviceWindowEndSeconds <= serviceWindowStartSeconds)
            throw new IllegalArgumentException("Arm contract requires finite 0 <= service start < end");
        if ((legacy && arm == Arm.DYNAMIC_MIXED) || (!legacy && arm == Arm.LEGACY_TIMETABLE_ONLY))
            throw new IllegalArgumentException("Legacy TIMETABLE_ONLY and dynamic T identities cannot be exchanged");
        ExperimentSpec.Access expectedAccess = switch (arm) {
            case CONTROL, LEGACY_TIMETABLE_ONLY -> ExperimentSpec.Access.NO_FREIGHT;
            case MIXED, DYNAMIC_MIXED -> ExperimentSpec.Access.SHARED_PASSENGER_SERVICE;
            case DEDICATED -> ExperimentSpec.Access.DEDICATED_ONLY;
        };
        if (access != expectedAccess) throw new IllegalArgumentException("Arm contract access disagrees with arm");
        boolean dedicated = arm == Arm.DEDICATED || arm == Arm.LEGACY_TIMETABLE_ONLY;
        if (dedicated == dedicatedLineId.isBlank()
                || dedicated != (supply.mode() == SupplyMode.REGISTERED_DEDICATED_SERVICE_SUPPLY))
            throw new IllegalArgumentException("Arm contract dedicated supply disagrees with arm");
        boolean mixed = arm == Arm.MIXED || arm == Arm.DYNAMIC_MIXED;
        boolean physicalWindow = !legacy && mixed
                && DynamicMixedControlDefinitions.WINDOWED_POLICY_ID.equals(controlPolicyId);
        if (physicalWindow != (windowMode == WindowMode.REGISTERED_SHARED_BOARDING_WINDOW))
            throw new IllegalArgumentException("Arm contract physical window disagrees with its registration");
        if (controlEnabled && arm != Arm.DYNAMIC_MIXED)
            throw new IllegalArgumentException("Only dynamic T may declare enabled control");
        if (legacy && (!controlPolicyId.isEmpty() || controlEnabled || controlMode != ControlMode.NOT_APPLICABLE))
            throw new IllegalArgumentException("Legacy registrations do not declare dynamic control");
        if (!legacy && controlPolicyId.isBlank())
            throw new IllegalArgumentException("Dynamic registration requires its declared policy identity");
        if (!legacy && ((!mixed && controlMode != ControlMode.NOT_APPLICABLE)
                || (mixed && !controlEnabled && controlMode != ControlMode.DISABLED)
                || (controlEnabled && controlMode != ControlMode.ENABLED && controlMode != ControlMode.ZERO_AMPLITUDE)))
            throw new IllegalArgumentException("Arm contract control mode disagrees with its enablement");
    }

    public static UnifiedArmContract fromLegacy(ExperimentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Arm arm = switch (spec.arm()) {
            case CONTROL -> Arm.CONTROL;
            case MIXED -> Arm.MIXED;
            case DEDICATED -> Arm.DEDICATED;
            case TIMETABLE_ONLY -> Arm.LEGACY_TIMETABLE_ONLY;
        };
        return new UnifiedArmContract(SCHEMA, spec.schemaVersion(), arm, spec.policies().access(),
                supply(arm, spec.inputs()), spec.policies().dedicatedLineId(), WindowMode.NO_PHYSICAL_BOARDING_WINDOW,
                spec.freightDemand().serviceWindowStartSeconds(), spec.freightDemand().serviceWindowEndSeconds(),
                ControlMode.NOT_APPLICABLE, false, "");
    }

    public static UnifiedArmContract fromDynamic(DynamicMixedExperimentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Arm arm = switch (spec.design().arm()) {
            case CONTROL -> Arm.CONTROL;
            case MIXED -> Arm.MIXED;
            case DYNAMIC_MIXED -> Arm.DYNAMIC_MIXED;
            case DEDICATED -> Arm.DEDICATED;
        };
        boolean mixed = arm == Arm.MIXED || arm == Arm.DYNAMIC_MIXED;
        // Matches DynamicMixedControlDefinitions.Bound for the supported M/T assembly.
        // C/D may carry common policy context, but it does not turn them into mixed arms.
        boolean windowed = mixed && DynamicMixedControlDefinitions.WINDOWED_POLICY_ID.equals(spec.control().policyId());
        ControlMode mode = !mixed ? ControlMode.NOT_APPLICABLE : !spec.design().controlEnabled() ? ControlMode.DISABLED
                : spec.design().limits().maximumAdvanceSeconds() > 0 || spec.design().limits().maximumDelaySeconds() > 0
                ? ControlMode.ENABLED : ControlMode.ZERO_AMPLITUDE;
        return new UnifiedArmContract(SCHEMA, spec.schemaVersion(), arm, spec.policies().access(),
                supply(arm, spec.inputs()), spec.policies().dedicatedLineId(), windowed
                ? WindowMode.REGISTERED_SHARED_BOARDING_WINDOW : WindowMode.NO_PHYSICAL_BOARDING_WINDOW,
                spec.freightDemand().serviceWindowStartSeconds(), spec.freightDemand().serviceWindowEndSeconds(),
                mode, spec.design().controlEnabled(), spec.control().policyId());
    }

    /**
     * Checks only the existing isolation setting, service clocks and physical-window
     * element. No module/element is installed and no registered supply is activated.
     * D (and historical empty-service T) may still have the common baseline loaded,
     * so a dedicated line is not required to exist in the current transit schedule.
     * The existing registration schemas do not declare a boarding-order intervention;
     * their registered policy and dynamic forecast remain passenger-first. The generic
     * coTransport development entry point may select FCFS, but must not be mistaken
     * for a matched run under these historical registrations.
     */
    public void verifyScenario(Scenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        var module = scenario.getConfig().getModules().get(CoTransportConfigGroup.GROUP_NAME);
        if (!(module instanceof CoTransportConfigGroup co))
            throw new IllegalStateException("Arm contract requires an already prepared coTransport configuration");
        if (co.getBoardingOrder() != CoTransportSettings.BoardingOrder.PASSENGER_FIRST)
            throw new IllegalStateException("Existing registered experiment schemas require PASSENGER_FIRST boarding; "
                    + "FIRST_COME_FIRST_SERVED needs an independently registered schema and compatible dynamic forecast. "
                    + "Use a separate generic coTransport development configuration, not a historical matched-run registration.");
        String actualDedicated = co.getDedicatedFreightLineId();
        actualDedicated = actualDedicated == null || actualDedicated.isBlank() ? "" : actualDedicated;
        if (!dedicatedLineId.equals(actualDedicated))
            throw new IllegalStateException("Prepared dedicated-line isolation differs from the registered arm");
        if (co.getFreightServiceWindowStartTime() != serviceWindowStartSeconds
                || co.getFreightServiceWindowEndTime() != serviceWindowEndSeconds)
            throw new IllegalStateException("Prepared freight service window differs from the registered arm");
        Object observed = scenario.getScenarioElement(CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT);
        if (windowMode == WindowMode.NO_PHYSICAL_BOARDING_WINDOW) {
            if (observed != null)
                throw new IllegalStateException("Registered arm must not acquire a physical shared boarding window");
        } else {
            if (!(observed instanceof CoTransportBoardingPolicy.SharedBoardingWindow window))
                throw new IllegalStateException("Registered windowed M/T requires an explicit physical shared boarding window");
            if (window.startInclusiveSeconds() != serviceWindowStartSeconds
                    || window.endExclusiveSeconds() != serviceWindowEndSeconds)
                throw new IllegalStateException("Physical shared boarding window differs from registered freight service window");
        }
    }

    private static RegisteredSupply supply(Arm arm, ExperimentSpec.Inputs inputs) {
        var mode = arm == Arm.DEDICATED || arm == Arm.LEGACY_TIMETABLE_ONLY
                ? SupplyMode.REGISTERED_DEDICATED_SERVICE_SUPPLY : SupplyMode.REGISTERED_PASSENGER_SUPPLY;
        return new RegisteredSupply(mode, inputs.baselineSchedule(), inputs.baselineTransitVehicles(),
                inputs.activatedSchedule(), inputs.activatedTransitVehicles());
    }
}
