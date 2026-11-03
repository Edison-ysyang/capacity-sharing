package org.matsim.run.ismt;

/** Arm composition has no independent simulation or capacity state. */
public record ExperimentArmPayload(boolean carriesFreight, ActivatedSupply supply) {
    public record ActivatedSupply(ExperimentSpec.FileRef schedule, ExperimentSpec.FileRef vehicles) {
        public boolean sameContent(ActivatedSupply other) {
            return schedule.sha256().equals(other.schedule.sha256())
                    && vehicles.sha256().equals(other.vehicles.sha256());
        }
    }
    public static ExperimentArmPayload from(ExperimentSpec spec) {
        return new ExperimentArmPayload(spec.freightDemand().orderCount() > 0,
                new ActivatedSupply(spec.inputs().activatedSchedule(), spec.inputs().activatedTransitVehicles()));
    }
}
