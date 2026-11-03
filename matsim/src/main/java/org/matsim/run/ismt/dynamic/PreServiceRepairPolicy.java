package org.matsim.run.ismt.dynamic;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.RollingDeparturePolicy.*;

/**
 * Daily, conditional execution of an EXPLICITLY registered pre-service repair, followed by the
 * existing rolling policy. This class does not select/approve a lexical first trip, alter M, repair
 * input XML, create capacity, or redefine the original-baseline displacement budget. The caller
 * must construct a fresh wrapper AND fresh delegate each simulation day, using the same original
 * baseline, limits, cadence, predictor and constraint registration for both components.
 *
 * <p>Before a needed repair the original timetable is explicitly infeasible under registered
 * planned constraints; returning no proposal then is NOT a feasible fallback. At the exact repair
 * decision time a mature common-cohort forecast must protect passengers even though the original
 * reference itself violates a headway. After proposing a repair, the next ledger snapshot must
 * contain exactly that complete repaired timetable. This confirms an observed commitment only,
 * never physical dispatch, actual headways, railway safety or terminal event completeness.</p>
 */
public final class PreServiceRepairPolicy implements DynamicMixedQSimModule.Policy {
    public enum Outcome {
        PENDING_REGISTERED_REPAIR, REPAIR_NOT_REQUIRED, REPAIR_PROPOSED,
        REPAIR_COMMITMENT_OBSERVED, ROLLING_DELEGATED, FAILED
    }

    /** The displacement total describes the registered repair, not an assertion that it has already been applied. */
    public record Audit(double decisionTimeSeconds, Version sourceVersion, String closedPrefixSha256,
                        Outcome outcome, String reason, Map<TripKey, Double> proposedDepartures,
                        DispatchConstraintChecker.Assessment originalAssessment,
                        Optional<DispatchConstraintChecker.Comparison> repairComparison,
                        Optional<Projection> originalProjection, Optional<Projection> repairProjection,
                        Optional<Double> meanPassengerWaitingIncrementSeconds,
                        double totalAbsoluteOriginalBaselineShiftSeconds) {
        public Audit {
            proposedDepartures = Map.copyOf(proposedDepartures);
            Objects.requireNonNull(originalAssessment); Objects.requireNonNull(repairComparison);
            Objects.requireNonNull(originalProjection); Objects.requireNonNull(repairProjection);
            Objects.requireNonNull(meanPassengerWaitingIncrementSeconds);
        }
        public boolean actualExecutionVerified() { return false; }
        public DispatchConstraintChecker.EvidenceScope evidenceScope() {
            return DispatchConstraintChecker.EvidenceScope.REGISTERED_PLANNED_TIMES_ONLY;
        }
    }

    private enum Stage { PENDING, AWAITING_COMMITMENT, WAITING_FOR_ROLLING, ROLLING }
    private final Map<TripKey, Double> original, repair, repaired;
    private final Limits limits;
    private final DispatchConstraintChecker constraints;
    private final DispatchConstraintChecker.Assessment originalAssessment;
    private final double repairTime, lookAhead, firstRollingTime, passengerBound, repairShift;
    private final ForecastModel forecast;
    private final RollingDeparturePolicy delegate;
    private final Consumer<Audit> audit;
    private final boolean repairRequired;
    private Stage stage = Stage.PENDING;
    private Integer iteration;
    private Long epoch;
    private long lastRevision = -1;
    private double lastCall = -1;
    private Version proposedAtVersion;
    private boolean announcedPending, poisoned, busy;

    public PreServiceRepairPolicy(Map<TripKey, Double> originalBaseline, Limits limits,
            DispatchConstraintChecker constraints, Map<TripKey, Double> registeredRepair,
            double repairDecisionTime, double repairLookAheadSeconds, double rollingDecisionAnchor,
            double rollingDecisionInterval, ForecastModel forecast, RollingDeparturePolicy delegate,
            double maximumMeanWaitingIncrementSeconds, Consumer<Audit> audit) {
        this.original = times(originalBaseline); this.repair = times(registeredRepair);
        require(!original.isEmpty(), "Original baseline must be complete and nonempty");
        this.limits = Objects.requireNonNull(limits); this.constraints = Objects.requireNonNull(constraints);
        this.forecast = Objects.requireNonNull(forecast); this.delegate = Objects.requireNonNull(delegate);
        this.audit = Objects.requireNonNull(audit);
        repairTime = nonnegative(repairDecisionTime, "repair decision time");
        lookAhead = positive(repairLookAheadSeconds, "repair lookahead");
        passengerBound = nonnegative(maximumMeanWaitingIncrementSeconds, "passenger bound");
        nonnegative(rollingDecisionAnchor, "rolling anchor"); positive(rollingDecisionInterval, "rolling interval");
        firstRollingTime = firstSlotAfter(repairTime, rollingDecisionAnchor, rollingDecisionInterval);
        require(original.keySet().containsAll(repair.keySet()), "Repair contains a trip outside the original baseline");
        var full = new LinkedHashMap<>(original);
        double shift = 0;
        for (var entry : repair.entrySet()) {
            double baseline = original.get(entry.getKey()), proposed = entry.getValue();
            require(proposed != baseline, "Registered repair contains an identity entry");
            require(baseline - proposed <= limits.maximumAdvanceSeconds()
                    && proposed - baseline <= limits.maximumDelaySeconds(), "Repair exceeds original-baseline displacement budget");
            shift += Math.abs(proposed - baseline);
            require(Double.isFinite(shift), "Repair displacement sum overflow");
            full.put(entry.getKey(), proposed);
        }
        repaired = Collections.unmodifiableMap(full); repairShift = shift;
        var originalCheck = constraints.evaluate(original);
        require(originalCheck.coverage().anyConstraintsRegistered() && originalCheck.candidate().anyConstraintsEvaluated(),
                "No planned operating constraints are registered");
        require(originalCheck.baseline().equals(originalCheck.candidate()),
                "Original baseline disagrees with the checker's registered baseline assessment");
        originalAssessment = originalCheck.candidate();
        repairRequired = !originalAssessment.satisfiesRegisteredConstraints();
        require(!repairRequired || !repair.isEmpty(), "Infeasible original baseline has no registered repair; no valid fallback");
    }

    @Override public Map<TripKey, Double> propose(ClosedCoTransportObservationState.Snapshot observations, Snapshot commitments) {
        require(!poisoned, "Repair policy is fail-closed after a prior failure");
        if (busy) { poisoned = true; throw new IllegalStateException("Reentrant repair policy call"); }
        busy = true;
        try {
            validateCall(observations, commitments);
            double now = commitments.nowSeconds();
            if (stage == Stage.PENDING) {
                require(commitments.commitments().equals(original), "Pre-repair commitments differ from the original baseline");
                if (!repairRequired) {
                    emit(observations, commitments, Outcome.REPAIR_NOT_REQUIRED, "Original registered constraints already satisfied; repair not proposed",
                            Map.of(), null, null, null, null);
                    stage = Stage.WAITING_FOR_ROLLING;
                } else if (now < repairTime) {
                    if (!announcedPending) {
                        announcedPending = true;
                        emit(observations, commitments, Outcome.PENDING_REGISTERED_REPAIR,
                                "Registered repair pending; original plan is not a feasible fallback", Map.of(), null, null, null, null);
                    }
                    return Map.of();
                } else {
                    require(now == repairTime, "Missed exact registered repair decision time");
                    require(observations.position().closedThroughSeconds().isPresent(), "Repair requires a mature closed observation prefix");
                    validateRepairWindow(commitments);
                    var checked = constraints.evaluate(repaired);
                    require(checked.candidate().anyConstraintsEvaluated() && checked.candidate().satisfiesRegisteredConstraints(),
                            "Registered repair violates planned operating constraints: " + checked.candidate().violations());
                    Projection reference = ready(forecast.project(observations, original), observations, commitments);
                    Projection candidate = ready(forecast.project(observations, repaired), observations, commitments);
                    double increment = waitingIncrement(reference, candidate);
                    require(increment <= passengerBound, "Registered repair exceeds passenger waiting increment bound");
                    proposedAtVersion = commitments.version(); stage = Stage.AWAITING_COMMITMENT;
                    emit(observations, commitments, Outcome.REPAIR_PROPOSED, "Constraint-checked and passenger-protected proposal; commitment/execution not yet observed",
                            repair, checked, reference, candidate, increment);
                    return repair;
                }
            }
            if (stage == Stage.AWAITING_COMMITMENT) {
                require(commitments.version().revision() > proposedAtVersion.revision(), "Repair commitment version did not advance");
                require(commitments.commitments().equals(repaired), "Repair proposal was not committed exactly; no silent retry or feasible fallback");
                emit(observations, commitments, Outcome.REPAIR_COMMITMENT_OBSERVED,
                        "Exact repaired commitments observed in a later ledger snapshot; actual execution remains unverified",
                        Map.of(), constraints.evaluate(repaired), null, null, null);
                stage = Stage.WAITING_FOR_ROLLING;
            }
            if (stage == Stage.WAITING_FOR_ROLLING) {
                require(commitments.commitments().equals(repairRequired ? repaired : original), "Commitments changed before the registered rolling handoff");
                if (now < firstRollingTime) return Map.of();
                require(now == firstRollingTime, "Missed first registered rolling cadence after repair");
                stage = Stage.ROLLING;
                emit(observations, commitments, Outcome.ROLLING_DELEGATED, "Rolling policy first called on its next registered cadence",
                        Map.of(), null, null, null, null);
            }
            Map<TripKey, Double> result = delegate.propose(observations, commitments);
            require(!poisoned, "Reentrant callback poisoned repair policy");
            return result;
        } catch (RuntimeException | Error failure) {
            poisoned = true;
            if (observations != null && commitments != null) {
                try { emit(observations, commitments, Outcome.FAILED, failure.toString(), Map.of(), null, null, null, null); }
                catch (RuntimeException | Error auditFailure) { if (auditFailure != failure) failure.addSuppressed(auditFailure); }
            }
            throw failure;
        } finally { busy = false; }
    }

    private void validateCall(ClosedCoTransportObservationState.Snapshot observations, Snapshot commitments) {
        Objects.requireNonNull(observations); Objects.requireNonNull(commitments); Objects.requireNonNull(commitments.version());
        var position = observations.position();
        require(position.iteration() == commitments.iteration() && position.epoch() == commitments.version().epoch()
                && position.decisionTimeSeconds() == commitments.nowSeconds(), "Observation/ledger source or clock mismatch");
        require(commitments.iteration() >= 0 && commitments.version().epoch() > 0 && commitments.version().revision() >= 0,
                "Invalid source version");
        require(commitments.enabled(), "Repair wrapper must not be invoked for a disabled controller");
        require(commitments.commitments().keySet().equals(original.keySet())
                && original.keySet().containsAll(commitments.lockedTrips())
                && original.keySet().containsAll(commitments.startedAtSeconds().keySet()), "Ledger trip identities differ from original baseline");
        if (iteration == null) { iteration = commitments.iteration(); epoch = commitments.version().epoch(); }
        require(iteration == commitments.iteration() && epoch == commitments.version().epoch(), "Repair wrapper cannot cross a daily reset");
        double now = nonnegative(commitments.nowSeconds(), "decision time");
        require(now > lastCall && commitments.version().revision() >= lastRevision, "Replayed or regressed repair policy call");
        commitments.commitments().forEach((trip, time) -> {
            double actual = nonnegative(Objects.requireNonNull(time), "commitment time"), baseline = original.get(trip);
            require(baseline - actual <= limits.maximumAdvanceSeconds() && actual - baseline <= limits.maximumDelaySeconds(),
                    "Commitment exceeds original-baseline displacement budget");
        });
        commitments.startedAtSeconds().forEach((trip, time) -> require(nonnegative(Objects.requireNonNull(time), "actual start") <= now,
                "Future native start in source snapshot"));
        position.closedThroughSeconds().ifPresent(closed -> require(closed <= now, "Future closed prefix"));
        lastCall = now; lastRevision = commitments.version().revision();
    }

    private void validateRepairWindow(Snapshot source) {
        double lock = source.nowSeconds() + limits.lockWindowSeconds(), horizon = source.nowSeconds() + lookAhead;
        require(Double.isFinite(lock) && Double.isFinite(horizon), "Repair time arithmetic overflow");
        for (var entry : repair.entrySet()) {
            var trip = entry.getKey(); double current = source.commitments().get(trip), candidate = entry.getValue();
            require(!source.startedAtSeconds().containsKey(trip) && !source.lockedTrips().contains(trip), "Repair trip is already started or locked");
            require(current > lock && current - source.nowSeconds() > limits.lockWindowSeconds()
                    && candidate > lock && candidate - source.nowSeconds() > limits.lockWindowSeconds(), "Repair enters the native lock window");
            require(current <= horizon && candidate <= horizon, "Repair trip lies outside the registered lookahead");
        }
    }

    private static Projection ready(Forecast result, ClosedCoTransportObservationState.Snapshot observations, Snapshot commitments) {
        Objects.requireNonNull(result);
        require(result.status() == ForecastStatus.READY, "Mature repair forecast unavailable: " + result.status() + " " + result.reason());
        Projection projection = result.projection().orElseThrow();
        Set<String> released = new HashSet<>();
        observations.releases().knownOrders().values().forEach(order -> released.addAll(order.releasedUnits().keySet()));
        require(projection.releasedFreightUnitIds().equals(released), "Repair forecast omits or invents released freight units");
        require(projection.forecastEndSeconds() > commitments.nowSeconds(), "Repair forecast horizon is expired");
        return projection;
    }

    private static double waitingIncrement(Projection reference, Projection candidate) {
        require(reference.demandProjectionId().equals(candidate.demandProjectionId())
                && reference.forecastEndSeconds() == candidate.forecastEndSeconds(), "Repair forecast projection identity or horizon changed");
        require(reference.affectedPassengers().keySet().equals(candidate.affectedPassengers().keySet()), "Repair affected cohort changed");
        double mass = 0, increase = 0;
        for (String key : reference.affectedPassengers().keySet().stream().sorted().toList()) {
            var before = reference.affectedPassengers().get(key); var after = candidate.affectedPassengers().get(key);
            require(before.passengers() == after.passengers(), "Repair affected cohort mass changed");
            mass += before.passengers(); increase += before.passengers() * (after.meanWaitingSeconds() - before.meanWaitingSeconds());
        }
        require(Double.isFinite(mass) && Double.isFinite(increase), "Repair passenger aggregation overflow");
        return mass == 0 ? 0 : increase / mass;
    }

    private void emit(ClosedCoTransportObservationState.Snapshot observations, Snapshot source, Outcome outcome, String reason,
                      Map<TripKey, Double> proposal, DispatchConstraintChecker.Comparison comparison,
                      Projection reference, Projection candidate, Double increment) {
        audit.accept(new Audit(source.nowSeconds(), source.version(), observations.position().closedPrefixSha256(), outcome, reason,
                proposal, originalAssessment, Optional.ofNullable(comparison), Optional.ofNullable(reference), Optional.ofNullable(candidate),
                Optional.ofNullable(increment), repairRequired ? repairShift : 0));
        if (outcome != Outcome.FAILED) require(!poisoned, "Reentrant audit callback poisoned repair policy");
    }

    private static double firstSlotAfter(double repair, double anchor, double interval) {
        if (anchor > repair) return anchor;
        double slot = Math.floor((repair - anchor) / interval) + 1;
        require(Double.isFinite(slot) && slot >= 0 && slot < Long.MAX_VALUE, "Rolling cadence index overflow");
        double next = Math.fma(slot, interval, anchor);
        require(Double.isFinite(next) && next > repair, "Rolling cadence cannot be represented after repair time");
        return next;
    }

    private static Map<TripKey, Double> times(Map<TripKey, Double> values) {
        Objects.requireNonNull(values);
        var sorted = new java.util.TreeMap<TripKey, Double>(Comparator.comparing(TripKey::line).thenComparing(TripKey::route).thenComparing(TripKey::departure));
        values.forEach((key, value) -> sorted.put(Objects.requireNonNull(key), nonnegative(Objects.requireNonNull(value), "registered time")));
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
    private static double nonnegative(double value, String name) {
        require(Double.isFinite(value) && value >= 0, "Finite nonnegative " + name + " required"); return value == 0 ? 0 : value;
    }
    private static double positive(double value, String name) { nonnegative(value, name); require(value > 0, "Positive " + name + " required"); return value; }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
