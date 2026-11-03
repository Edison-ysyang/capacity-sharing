package org.matsim.run.ismt.dynamic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;

/**
 * Deterministic, bounded coordinate search over existing departures. A decision can
 * change multiple trips, but this is not exhaustive joint optimization. Every
 * selected candidate must satisfy the registered planned constraints and passenger
 * protection relative to the ORIGINAL no-action forecast at this decision, not the
 * previous search step. A model-specific observed-delivery invariant can prune a
 * candidate that cannot improve the strict objective; that is not a protection pass.
 * The native bridge rechecks and executes the final atomic proposal separately.
 *
 * <p>The explicit ForecastModel must supply one frozen demand projection/cohort for
 * every candidate. This class implements search/selection, NOT a demand/capacity or
 * delivery predictor. In particular, installing it with a toy oracle does not create
 * a production freight-responsive policy. No native capacities or plans are changed.
 * All numerical controls are constructor arguments, never scientific defaults.</p>
 */
public final class RollingDeparturePolicy implements DynamicMixedQSimModule.Policy {
    public enum ForecastStatus { READY, INSUFFICIENT_HISTORY, UNSUPPORTED_STATE }
    public enum Outcome { NO_RELEASED_FREIGHT, FORECAST_UNAVAILABLE, NO_IMPROVEMENT, PROPOSED }
    public enum TrialOutcome { OPERATING_REJECTION, FORECAST_UNAVAILABLE, PASSENGER_REJECTION, EVALUATED,
        OBSERVED_DELIVERED_DOMINATED }

    /** Costs cover the same released capacity-unit IDs for every candidate. */
    public record FreightCost(long lateUnits, double totalLatenessSeconds, double totalWaitingSeconds) {
        public FreightCost {
            require(lateUnits >= 0, "Negative late-unit count");
            nonnegative(totalLatenessSeconds, "lateness"); nonnegative(totalWaitingSeconds, "freight waiting");
        }
    }

    /** A cohort may have fractional forecast mass; every candidate must retain its mass. */
    public record PassengerCost(double passengers, double meanWaitingSeconds) {
        public PassengerCost {
            positive(passengers, "cohort mass"); nonnegative(meanWaitingSeconds, "passenger waiting");
        }
    }

    /**
     * affectedPassengers is a candidate-independent, explicitly constructed affected
     * population for the WHOLE search horizon, including arrival cohorts that can miss
     * an advanced train. It must not be rebuilt by selecting only harmed/served riders.
     * Projection identity and equality checks detect substitution, not scientific
     * completeness: the concrete predictor/assembly must establish that coverage.
     */
    public record Projection(String demandProjectionId, double forecastEndSeconds,
                             Set<String> releasedFreightUnitIds, FreightCost freight,
                             Map<String, PassengerCost> affectedPassengers) {
        public Projection {
            text(demandProjectionId); nonnegative(forecastEndSeconds, "forecast end");
            releasedFreightUnitIds = Set.copyOf(releasedFreightUnitIds);
            releasedFreightUnitIds.forEach(RollingDeparturePolicy::text);
            Objects.requireNonNull(freight);
            require(freight.lateUnits() <= releasedFreightUnitIds.size(), "More late units than released units");
            affectedPassengers = Map.copyOf(affectedPassengers);
            affectedPassengers.keySet().forEach(RollingDeparturePolicy::text);
            affectedPassengers.values().forEach(Objects::requireNonNull);
            if (releasedFreightUnitIds.isEmpty()) require(freight.equals(new FreightCost(0, 0, 0)), "Cost without freight");
        }
    }

    public record Forecast(ForecastStatus status, String reason, Optional<Projection> projection) {
        public Forecast {
            Objects.requireNonNull(status); text(reason); Objects.requireNonNull(projection);
            require((status == ForecastStatus.READY) == projection.isPresent(), "Forecast status/content mismatch");
        }
        public static Forecast ready(Projection projection) {
            return new Forecast(ForecastStatus.READY, "explicit-predictor-ready", Optional.of(projection));
        }
    }

    @FunctionalInterface public interface ForecastModel {
        Forecast project(ClosedCoTransportObservationState.Snapshot observations, Map<TripKey, Double> departures);

        /**
         * Optional, model-specific proof that ALL currently released units have
         * observed deliveries and that their cost equals this successful original
         * no-action projection for EVERY candidate in this decision. Ordinary
         * oracles opt out. Implementations must bind to this exact closed Snapshot
         * and reference; predicted completion, an unreleased suffix, or a previous
         * decision cannot establish this property. Query occurs once, immediately
         * after the original no-action forecast; it must not execute another forecast.
         */
        default Optional<FreightCost> fixedObservedDeliveredFreightCost(
                ClosedCoTransportObservationState.Snapshot observations, Projection originalNoActionProjection) {
            return Optional.empty();
        }
    }

    /** For OBSERVED_DELIVERED_DOMINATED, freight is the certified fixed account; passenger cost was NOT evaluated. */
    public record Trial(int sweep, Map<TripKey, Double> changesFromDecisionStart,
                        TrialOutcome outcome, Optional<FreightCost> freight,
                        Optional<Double> meanPassengerWaitingIncrementSeconds, double absoluteBaselineShiftSeconds) {
        public Trial { changesFromDecisionStart = Map.copyOf(changesFromDecisionStart); }
    }

    /** A proposal receipt is not a commitment, execution or complete resource-coverage certificate. */
    public record Decision(double decisionTimeSeconds, Version sourceVersion, String closedPrefixSha256,
                           Outcome outcome, Map<TripKey, Double> proposedDepartures,
                           Optional<Projection> noActionProjection, Optional<Projection> selectedProjection,
                           List<Trial> trials, int improvingSweeps, boolean searchBudgetReached) {
        public Decision { proposedDepartures = Map.copyOf(proposedDepartures); trials = List.copyOf(trials); }
    }

    private final RollingDepartureCandidates candidates;
    private final DispatchConstraintChecker constraints;
    private final ForecastModel model;
    private final double firstDecisionSeconds, decisionIntervalSeconds, maximumMeanWaitingIncrementSeconds;
    private final int maximumImprovingSweeps;
    private final Consumer<Decision> audit;
    private final RollingDepartureCandidates.AdjustmentWindow decisionWindow;
    private long lastSlot = -1;
    private Integer iteration;
    private Long epoch;
    private double lastCall = -1;

    public RollingDeparturePolicy(RollingDepartureCandidates candidates, DispatchConstraintChecker constraints,
                                  ForecastModel model, double firstDecisionSeconds, double decisionIntervalSeconds,
                                  double maximumMeanWaitingIncrementSeconds, int maximumImprovingSweeps,
                                  Consumer<Decision> audit) {
        this(candidates, constraints, model, firstDecisionSeconds, decisionIntervalSeconds,
                maximumMeanWaitingIncrementSeconds, maximumImprovingSweeps, audit, null);
    }

    /** Optional window bounds decision work only; native boarding and ongoing rides have separate gates. */
    public RollingDeparturePolicy(RollingDepartureCandidates candidates, DispatchConstraintChecker constraints,
                                  ForecastModel model, double firstDecisionSeconds, double decisionIntervalSeconds,
                                  double maximumMeanWaitingIncrementSeconds, int maximumImprovingSweeps,
                                  Consumer<Decision> audit, RollingDepartureCandidates.AdjustmentWindow decisionWindow) {
        this.candidates = Objects.requireNonNull(candidates); this.constraints = Objects.requireNonNull(constraints);
        this.model = Objects.requireNonNull(model); this.audit = Objects.requireNonNull(audit);
        this.firstDecisionSeconds = nonnegative(firstDecisionSeconds, "decision anchor");
        this.decisionIntervalSeconds = positive(decisionIntervalSeconds, "decision interval");
        this.maximumMeanWaitingIncrementSeconds = nonnegative(maximumMeanWaitingIncrementSeconds, "passenger bound");
        require(maximumImprovingSweeps > 0, "Positive search budget required");
        this.maximumImprovingSweeps = maximumImprovingSweeps;
        this.decisionWindow = decisionWindow;
        require(decisionWindow == null || decisionWindow.startInclusiveSeconds() == firstDecisionSeconds,
                "Decision anchor must match the explicit shared opening time");
    }

    @Override public Map<TripKey, Double> propose(ClosedCoTransportObservationState.Snapshot observations, Snapshot commitments) {
        Objects.requireNonNull(observations); Objects.requireNonNull(commitments);
        var position = observations.position();
        require(position.iteration() == commitments.iteration() && position.epoch() == commitments.version().epoch()
                && position.decisionTimeSeconds() == commitments.nowSeconds(), "Observation and commitment source mismatch");
        if (iteration == null) { iteration = commitments.iteration(); epoch = commitments.version().epoch(); }
        require(iteration == commitments.iteration() && epoch == commitments.version().epoch(), "A policy cannot cross a daily reset");
        require(commitments.nowSeconds() > lastCall, "Replayed or regressed policy call");
        lastCall = commitments.nowSeconds();
        if (decisionWindow != null && !decisionWindow.contains(commitments.nowSeconds())) return Map.of();
        if (!commitments.enabled() || commitments.nowSeconds() < firstDecisionSeconds) return Map.of();
        double rawSlot = Math.floor((commitments.nowSeconds() - firstDecisionSeconds) / decisionIntervalSeconds);
        require(Double.isFinite(rawSlot) && rawSlot < Long.MAX_VALUE, "Decision clock overflow");
        long slot = (long) rawSlot;
        if (slot <= lastSlot) return Map.of();
        lastSlot = slot;
        // A missed callback is not replayed against a later realized state. At most one current decision is made.
        if (position.closedThroughSeconds().isEmpty()) return Map.of();
        require(position.closedThroughSeconds().getAsDouble() <= commitments.nowSeconds(), "Future closed prefix");
        Set<String> released = new HashSet<>();
        observations.releases().knownOrders().values().forEach(order -> released.addAll(order.releasedUnits().keySet()));
        Decision decision = select(commitments, position.closedPrefixSha256(), Set.copyOf(released),
                departures -> model.project(observations, departures),
                reference -> model.fixedObservedDeliveredFreightCost(observations, reference));
        audit.accept(decision);
        return decision.proposedDepartures();
    }

    /** Package-local deterministic selection seam, not an alternative public observation-import API. */
    Decision select(Snapshot initial, String prefix, Set<String> released,
                    Function<Map<TripKey, Double>, Forecast> forecast) {
        return select(initial, prefix, released, forecast, ignored -> Optional.empty());
    }

    /** Internal explicit proof seam; not a public way to fabricate an observation snapshot. */
    Decision select(Snapshot initial, String prefix, Set<String> released,
                    Function<Map<TripKey, Double>, Forecast> forecast,
                    Function<Projection, Optional<FreightCost>> fixedObservedCost) {
        Objects.requireNonNull(initial); text(prefix); released = Set.copyOf(released); Objects.requireNonNull(forecast);
        Objects.requireNonNull(fixedObservedCost);
        var neighbourhood = candidates.generate(initial);
        var noAction = neighbourhood.getFirst();
        var startingCheck = constraints.evaluate(noAction.departures());
        require(startingCheck.candidate().anyConstraintsEvaluated(), "No operating constraints registered");
        if (!startingCheck.candidate().satisfiesRegisteredConstraints())
            throw new IllegalStateException("Current commitments are infeasible; no valid fallback. Violations="
                    + startingCheck.candidate().violations());
        if (!initial.enabled() || released.isEmpty()) return decision(initial, prefix, Outcome.NO_RELEASED_FREIGHT,
                Map.of(), null, null, List.of(), 0, false);
        Forecast initialForecast = Objects.requireNonNull(forecast.apply(noAction.departures()));
        if (initialForecast.status() != ForecastStatus.READY) return decision(initial, prefix, Outcome.FORECAST_UNAVAILABLE,
                Map.of(), null, null, List.of(), 0, false);
        Projection reference = initialForecast.projection().orElseThrow();
        validateProjection(reference, released, initial.nowSeconds());
        Optional<FreightCost> fixedCost = Objects.requireNonNull(fixedObservedCost.apply(reference));
        fixedCost.ifPresent(cost -> require(cost.equals(reference.freight()), "Fixed observed freight cost differs from original reference"));
        Projection selectedProjection = reference;
        Map<TripKey, Double> selectedTimes = initial.commitments();
        double selectedShift = noAction.totalAbsoluteBaselineShiftSeconds();
        List<Trial> trials = new ArrayList<>();
        int improvingSweeps = 0;
        for (int sweep = 0; sweep < maximumImprovingSweeps; sweep++) {
            if (sweep > 0) neighbourhood = candidates.generate(new Snapshot(initial.iteration(), initial.version(),
                    initial.nowSeconds(), initial.enabled(), selectedTimes, initial.lockedTrips(), initial.startedAtSeconds()));
            Projection bestProjection = selectedProjection;
            Map<TripKey, Double> bestTimes = selectedTimes;
            double bestShift = selectedShift;
            for (int index = 1; index < neighbourhood.size(); index++) {
                var candidate = neighbourhood.get(index);
                var changes = difference(initial.commitments(), candidate.departures());
                if (!constraints.evaluate(candidate.departures()).candidate().satisfiesRegisteredConstraints()) {
                    trials.add(new Trial(sweep, changes, TrialOutcome.OPERATING_REJECTION, Optional.empty(), Optional.empty(),
                            candidate.totalAbsoluteBaselineShiftSeconds())); continue;
                }
                // With fixed freight cost, only a STRICTLY smaller baseline shift can
                // beat best. Ties retain the earlier selected candidate, exactly as
                // compare() below. Do not invent a passenger forecast/protection pass.
                if (fixedCost.isPresent() && Double.compare(candidate.totalAbsoluteBaselineShiftSeconds(), bestShift) >= 0) {
                    trials.add(new Trial(sweep, changes, TrialOutcome.OBSERVED_DELIVERED_DOMINATED, fixedCost, Optional.empty(),
                            candidate.totalAbsoluteBaselineShiftSeconds())); continue;
                }
                Forecast projected = Objects.requireNonNull(forecast.apply(candidate.departures()));
                if (projected.status() != ForecastStatus.READY) {
                    trials.add(new Trial(sweep, changes, TrialOutcome.FORECAST_UNAVAILABLE, Optional.empty(), Optional.empty(),
                            candidate.totalAbsoluteBaselineShiftSeconds())); continue;
                }
                Projection prediction = projected.projection().orElseThrow();
                validateProjection(prediction, released, initial.nowSeconds());
                fixedCost.ifPresent(cost -> require(cost.equals(prediction.freight()), "Predictor violated fixed observed freight cost"));
                double increment = waitingIncrement(reference, prediction);
                boolean protectedPassengers = increment <= maximumMeanWaitingIncrementSeconds;
                trials.add(new Trial(sweep, changes, protectedPassengers ? TrialOutcome.EVALUATED : TrialOutcome.PASSENGER_REJECTION,
                        Optional.of(prediction.freight()), Optional.of(increment), candidate.totalAbsoluteBaselineShiftSeconds()));
                if (protectedPassengers && compare(prediction.freight(), candidate.totalAbsoluteBaselineShiftSeconds(),
                        bestProjection.freight(), bestShift) < 0) {
                    bestProjection = prediction; bestTimes = candidate.departures(); bestShift = candidate.totalAbsoluteBaselineShiftSeconds();
                }
            }
            if (bestTimes == selectedTimes) break;
            selectedTimes = bestTimes; selectedProjection = bestProjection; selectedShift = bestShift; improvingSweeps++;
        }
        var changes = difference(initial.commitments(), selectedTimes);
        return decision(initial, prefix, changes.isEmpty() ? Outcome.NO_IMPROVEMENT : Outcome.PROPOSED,
                changes, reference, selectedProjection, trials, improvingSweeps, improvingSweeps == maximumImprovingSweeps);
    }

    private static Decision decision(Snapshot source, String prefix, Outcome outcome, Map<TripKey, Double> changes,
                                     Projection reference, Projection selected, List<Trial> trials, int sweeps, boolean budget) {
        return new Decision(source.nowSeconds(), source.version(), prefix, outcome, changes,
                Optional.ofNullable(reference), Optional.ofNullable(selected), trials, sweeps, budget);
    }

    private static void validateProjection(Projection prediction, Set<String> released, double now) {
        require(prediction.releasedFreightUnitIds().equals(released), "Prediction omitted or invented released freight units");
        require(prediction.forecastEndSeconds() > now, "Forecast horizon does not extend past the decision");
    }

    private static double waitingIncrement(Projection reference, Projection prediction) {
        require(reference.demandProjectionId().equals(prediction.demandProjectionId())
                && reference.forecastEndSeconds() == prediction.forecastEndSeconds(), "Demand projection or horizon changed across candidates");
        require(reference.affectedPassengers().keySet().equals(prediction.affectedPassengers().keySet()), "Affected cohort changed across candidates");
        double mass = 0, increment = 0;
        // Sorted keys make floating-point aggregation independent of map iteration order.
        for (String key : reference.affectedPassengers().keySet().stream().sorted().toList()) {
            var left = reference.affectedPassengers().get(key); var right = prediction.affectedPassengers().get(key);
            require(left.passengers() == right.passengers(), "Affected cohort mass changed across candidates");
            mass += left.passengers(); increment += left.passengers() * (right.meanWaitingSeconds() - left.meanWaitingSeconds());
        }
        require(Double.isFinite(mass) && Double.isFinite(increment), "Passenger projection aggregation overflow");
        return mass == 0 ? 0 : increment / mass;
    }

    private static int compare(FreightCost left, double leftShift, FreightCost right, double rightShift) {
        int order = Long.compare(left.lateUnits(), right.lateUnits());
        if (order == 0) order = Double.compare(left.totalLatenessSeconds(), right.totalLatenessSeconds());
        if (order == 0) order = Double.compare(left.totalWaitingSeconds(), right.totalWaitingSeconds());
        if (order == 0) order = Double.compare(leftShift, rightShift);
        return order;
    }

    private static Map<TripKey, Double> difference(Map<TripKey, Double> before, Map<TripKey, Double> after) {
        var result = new LinkedHashMap<TripKey, Double>();
        after.forEach((trip, time) -> { if (time.doubleValue() != before.get(trip)) result.put(trip, time); });
        return Map.copyOf(result);
    }

    private static void text(String value) { require(value != null && !value.isBlank(), "Nonblank identity required"); }
    private static double nonnegative(double value, String label) { require(Double.isFinite(value) && value >= 0, "Invalid " + label); return value; }
    private static double positive(double value, String label) { require(Double.isFinite(value) && value > 0, "Invalid " + label); return value; }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
