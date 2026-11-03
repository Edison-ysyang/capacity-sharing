package org.matsim.run.ismt.dynamic;

import org.matsim.run.ismt.dynamic.ObservedTransitRiderState.PassengerWaitingArrival;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Empirical trailing-window passenger PT-leg arrival rates from a closed prefix.
 * This is a declared stationary extrapolation input, not future realized demand,
 * a calibrated prediction, a capacity allocator or passenger-protection approval.
 * Transfers count as new OD waiting arrivals; continuing inside a vehicle does not.
 * No population, plans, network, schedule, order registry or RNG is read.
 *
 * <p>The sampling interval is (closedThrough - window, closedThrough], never a
 * window ending at the later decision time. The start of uninterrupted observation
 * is an explicit caller declaration, not authenticated by this estimator. Until a
 * full window is observed, rates are absent rather than dividing partial counts by
 * a full window. Unknown ODs are not synthesized from private demand registries.</p>
 */
public final class ClosedPassengerArrivalForecast {
    public record Od(String stopId, String destinationStopId) {
        public Od { text(stopId); text(destinationStopId); }
    }

    /** The rate is absent during warmup; once known it is observedArrivals / window. */
    public record ArrivalRate(long observedArrivals, OptionalDouble ratePerSecond) {
        public ArrivalRate {
            require(observedArrivals >= 0, "Negative arrival count"); Objects.requireNonNull(ratePerSecond);
            ratePerSecond.ifPresent(ClosedPassengerArrivalForecast::nonnegative);
        }
    }

    /** Lower bound is exclusive; observationStart itself is observable when above that bound. */
    public record SampleWindow(double observationStartSeconds, double trailingWindowSeconds,
                               OptionalDouble closedThroughSeconds, OptionalDouble lowerExclusiveSeconds,
                               double observedDurationSeconds) { }

    public record Forecast(SampleWindow sampleWindow, boolean warmupKnown,
                           Map<Od, ArrivalRate> arrivalsByOd,
                           Map<String, PassengerWaitingArrival> currentlyWaitingPassengers) {
        public Forecast {
            Objects.requireNonNull(sampleWindow);
            var sortedRates = new TreeMap<Od, ArrivalRate>(Comparator.comparing(Od::stopId).thenComparing(Od::destinationStopId));
            sortedRates.putAll(Map.copyOf(arrivalsByOd));
            arrivalsByOd = Collections.unmodifiableMap(new LinkedHashMap<>(sortedRates));
            currentlyWaitingPassengers = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(Map.copyOf(currentlyWaitingPassengers))));
        }
    }

    private final double observationStartSeconds;
    private final double trailingWindowSeconds;

    public ClosedPassengerArrivalForecast(double observationStartSeconds, double trailingWindowSeconds) {
        nonnegative(observationStartSeconds); nonnegative(trailingWindowSeconds);
        require(trailingWindowSeconds > 0, "Positive explicit trailing window required");
        this.observationStartSeconds = observationStartSeconds;
        this.trailingWindowSeconds = trailingWindowSeconds;
    }

    /** Public consumption accepts only the source-bound composing layer's immutable snapshot. */
    public Forecast forecast(ClosedCoTransportObservationState.Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        var position = snapshot.position();
        nonnegative(position.decisionTimeSeconds());
        position.closedThroughSeconds().ifPresent(cutoff -> require(cutoff <= position.decisionTimeSeconds(),
                "Closed facts are later than the decision time"));
        return forecast(snapshot.riders(), position.closedThroughSeconds());
    }

    /** Package-only arithmetic/test seam; this does not establish a native closed-prefix source. */
    Forecast forecast(ObservedTransitRiderState.State state, OptionalDouble closedThroughSeconds) {
        Objects.requireNonNull(state); Objects.requireNonNull(closedThroughSeconds);
        var history = state.passengerWaitingArrivals();
        var waiting = state.currentlyWaitingPassengers();
        if (closedThroughSeconds.isEmpty()) {
            require(state.riders().isEmpty() && history.isEmpty(), "Unknown prefix cannot contain observed riders");
            return new Forecast(new SampleWindow(observationStartSeconds, trailingWindowSeconds,
                    OptionalDouble.empty(), OptionalDouble.empty(), 0), false, Map.of(), Map.of());
        }
        double cutoff = closedThroughSeconds.getAsDouble(); nonnegative(cutoff);
        require(cutoff >= observationStartSeconds, "Closed cutoff precedes declared observation start");
        for (var rider : state.riders().values()) require(rider.lastObservedAtSeconds() <= cutoff,
                "Rider state contains a fact later than the closed cutoff");
        double lower = cutoff - trailingWindowSeconds;
        double duration = Math.min(trailingWindowSeconds, cutoff - observationStartSeconds);
        boolean warmed = cutoff - observationStartSeconds >= trailingWindowSeconds;
        Map<Od, Long> counts = new LinkedHashMap<>();
        for (var arrival : history) {
            require(arrival.occurredAtSeconds() >= observationStartSeconds && arrival.occurredAtSeconds() <= cutoff,
                    "Waiting history is outside the declared observed prefix");
            var od = new Od(arrival.stopId(), arrival.destinationStopId());
            // Retain historically observed ODs with an explicit zero after they leave this window.
            counts.putIfAbsent(od, 0L);
            if (arrival.occurredAtSeconds() > lower) counts.put(od, Math.addExact(counts.get(od), 1));
        }
        Map<Od, ArrivalRate> rates = new LinkedHashMap<>();
        counts.forEach((od, count) -> rates.put(od, new ArrivalRate(count,
                warmed ? OptionalDouble.of(count / trailingWindowSeconds) : OptionalDouble.empty())));
        return new Forecast(new SampleWindow(observationStartSeconds, trailingWindowSeconds,
                OptionalDouble.of(cutoff), OptionalDouble.of(lower), duration), warmed, rates, waiting);
    }

    private static void nonnegative(double value) { require(Double.isFinite(value) && value >= 0, "Finite nonnegative value required"); }
    private static void text(String value) { require(value != null && !value.isBlank(), "Nonblank stop identity required"); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
