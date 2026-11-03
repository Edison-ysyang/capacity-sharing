package org.matsim.run.ismt.dynamic;

import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Limits;

import java.util.Objects;

/**
 * Independent v2 arm identity and iteration-phase values, not a production experiment
 * profile or runner. No scientific parameter is defaulted. This type neither changes
 * legacy ExperimentSpec/TIMETABLE_ONLY nor authenticates matched demands, plans,
 * seeds, fleets, schedules, or model parameters across arms. Its schema label is a
 * design-family identifier, NOT a complete experiment/input identity.
 *
 * <p>CONTROL has no freight treatment; MIXED and DEDICATED activate freight only;
 * DYNAMIC_MIXED additionally permits the configured within-day control switch.
 * OFF/zero-amplitude DYNAMIC_MIXED retains its v2 arm identity for regression tests:
 * bypass eligibility alone does not establish complete M/T output equivalence.
 * Disabling passenger innovation never disables operating control. No state,
 * timetable or commitment is carried across iterations by this immutable value.</p>
 */
public record DynamicMixedDesign(Arm arm, Protocol protocol, boolean controlEnabled, Limits limits) {
    public static final String SCHEMA_ID = "dynamic-mixed-v2";

    public enum Arm { CONTROL, MIXED, DYNAMIC_MIXED, DEDICATED }

    /**
     * All iteration endpoints are explicit and inclusive, except innovationOffIteration.
     * The first iteration retains the initial innovation-weight setting. Subsequent
     * common-history iterations disable innovation; activation reopens it until
     * innovationOffIteration, which closes it for the remaining experiment. This
     * matches the existing r5 weight schedule (initial 0, closed 1--19, open 20--149,
     * closed 150--199 for its registered endpoints). These are iteration-entry
     * weight states, not proof that replanning occurred during the initial iteration.
     * Requires a nonempty common history and a nonempty post-activation innovation
     * phase. Reporting starts at or after innovation closes; a single-iteration report
     * window is allowed. No additional reopening or implicit terminal extension exists.
     */
    public record Protocol(int firstIteration, int activationIteration, int innovationOffIteration,
                           int finalIteration, int reportFirstIteration, int reportLastIteration) {
        public Protocol {
            if (firstIteration < 0 || firstIteration >= activationIteration
                    || activationIteration >= innovationOffIteration
                    || innovationOffIteration > reportFirstIteration
                    || reportFirstIteration > reportLastIteration
                    || reportLastIteration > finalIteration) {
                throw new IllegalArgumentException("Require 0 <= first < activation < innovationOff <= reportFirst <= reportLast <= final");
            }
        }

        public boolean contains(int iteration) {
            return iteration >= firstIteration && iteration <= finalIteration;
        }
    }

    /**
     * Phase switches only. controlEligible means the configured driver/control path
     * may be selected, not that a controller is installed, a decision exists, a native
     * departure is changed, or all operational constraints are satisfied. Reporting
     * selects iterations only and is not a convergence or equilibrium certificate.
     * passengerInnovationEnabled describes the registered iteration-entry weight
     * state; this value does not install or validate a StrategyManager schedule.
     */
    public record Phase(int iteration, boolean freightTreatmentActive, boolean controlEligible,
                        boolean passengerInnovationEnabled, boolean reporting) { }

    public DynamicMixedDesign {
        Objects.requireNonNull(arm, "arm");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(limits, "explicit limits");
        if (controlEnabled && arm != Arm.DYNAMIC_MIXED) {
            throw new IllegalArgumentException("Only DYNAMIC_MIXED may enable within-day control");
        }
    }

    public String schemaId() { return SCHEMA_ID; }

    /** Exact v2 names only; legacy T must be migrated explicitly, never reinterpreted. */
    public static Arm parseArm(String value) {
        Objects.requireNonNull(value, "arm name");
        if (value.equals("TIMETABLE_ONLY")) {
            throw new IllegalArgumentException("Legacy TIMETABLE_ONLY is not a dynamic-mixed-v2 arm; do not relabel historical results as DYNAMIC_MIXED");
        }
        try {
            return Arm.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Expected exact v2 arm CONTROL, MIXED, DYNAMIC_MIXED, or DEDICATED: " + value, invalid);
        }
    }

    public Phase phaseAt(int iteration) {
        if (!protocol.contains(iteration)) {
            throw new IllegalArgumentException("Iteration is outside the explicit v2 protocol: " + iteration);
        }
        boolean activated = iteration >= protocol.activationIteration();
        boolean nonzeroAmplitude = limits.maximumAdvanceSeconds() > 0 || limits.maximumDelaySeconds() > 0;
        return new Phase(iteration,
                arm != Arm.CONTROL && activated,
                arm == Arm.DYNAMIC_MIXED && controlEnabled && activated && nonzeroAmplitude,
                (iteration == protocol.firstIteration() || activated)
                        && iteration < protocol.innovationOffIteration(),
                iteration >= protocol.reportFirstIteration() && iteration <= protocol.reportLastIteration());
    }
}
