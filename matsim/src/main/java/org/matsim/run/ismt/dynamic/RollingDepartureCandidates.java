package org.matsim.run.ismt.dynamic;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Limits;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.ScheduledTrip;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Snapshot;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;

/**
 * Parameter-neutral single-departure coordinate neighbourhood, not an optimizer,
 * constraint checker, passenger-protection rule or native dispatcher. Every call
 * includes the unchanged complete timetable first; this does NOT certify that
 * retaining the current timetable is a feasible fallback.
 *
 * <p>Subsequent candidates move exactly one eligible departure relative to the
 * supplied snapshot, retaining all its other commitments. Grid points are always
 * originalBaseline + integer * step, not currentCommitment + integer * step.
 * Decimal arithmetic on the supplied doubles' canonical decimal representations
 * defines grid indices and points; conversion to a native double happens once.
 * Unrepresentable/collapsed points or strict native time-bound violations fail
 * explicitly, without epsilon relaxation or forced non-grid boundary points.</p>
 *
 * <p>Ordering is no-action, then line/route/departure lexicographic order, then
 * candidate time ascending. maximumCandidates includes no-action and is an explicit
 * computational bound, not a scientific default. An oversized neighbourhood is
 * rejected in full, never silently truncated. No input file, mutable native object,
 * random stream or previous generation is read or modified.</p>
 */
public final class RollingDepartureCandidates {
    private static final Comparator<TripKey> TRIP_ORDER = Comparator.comparing(TripKey::line)
            .thenComparing(TripKey::route).thenComparing(TripKey::departure);

    /** Explicit half-open origin-departure window; not a freight boarding or completion rule. */
    public record AdjustmentWindow(double startInclusiveSeconds, double endExclusiveSeconds) {
        public AdjustmentWindow {
            finiteNonnegative(startInclusiveSeconds, "adjustment window start");
            finiteNonnegative(endExclusiveSeconds, "adjustment window end");
            if (endExclusiveSeconds <= startInclusiveSeconds)
                throw new IllegalArgumentException("Adjustment window must have positive duration");
        }

        public boolean contains(double timeSeconds) {
            return Double.isFinite(timeSeconds) && timeSeconds >= startInclusiveSeconds
                    && timeSeconds < endExclusiveSeconds;
        }

        /** Unchanged outside-window passenger services remain in the complete timetable. */
        public boolean permits(double originalSeconds, double candidateSeconds) {
            finiteNonnegative(originalSeconds, "original departure");
            finiteNonnegative(candidateSeconds, "candidate departure");
            return originalSeconds == candidateSeconds || contains(originalSeconds) && contains(candidateSeconds);
        }
    }

    /** changedDepartures is relative to this generation's snapshot, not to the original baseline. */
    public record Candidate(Map<TripKey, Double> changedDepartures, Map<TripKey, Double> departures,
                            double totalAbsoluteBaselineShiftSeconds) {
        public Candidate {
            changedDepartures = immutableTimes(changedDepartures);
            departures = immutableTimes(departures);
            if (departures.isEmpty() || changedDepartures.size() > 1)
                throw new IllegalArgumentException("A candidate requires a full nonempty timetable and at most one coordinate change");
            for (var entry : changedDepartures.entrySet())
                if (!entry.getValue().equals(departures.get(entry.getKey())))
                    throw new IllegalArgumentException("Changed departure does not match the complete timetable");
            finiteNonnegative(totalAbsoluteBaselineShiftSeconds, "total baseline shift");
        }
    }

    private record Move(TripKey trip, double time) { }
    private final Map<TripKey, Double> baseline;
    private final Limits limits;
    private final double lookAheadSeconds;
    private final BigDecimal step;
    private final int maximumCandidates;
    private final AdjustmentWindow adjustmentWindow;

    public RollingDepartureCandidates(List<ScheduledTrip> trips, Limits limits, double lookAheadSeconds,
                                      double candidateStepSeconds, int maximumCandidates) {
        this(trips, limits, lookAheadSeconds, candidateStepSeconds, maximumCandidates, null);
    }

    /** Null preserves the legacy unrestricted component; windowed registration must pass its explicit window. */
    public RollingDepartureCandidates(List<ScheduledTrip> trips, Limits limits, double lookAheadSeconds,
                                      double candidateStepSeconds, int maximumCandidates,
                                      AdjustmentWindow adjustmentWindow) {
        this.limits = Objects.requireNonNull(limits, "explicit limits");
        this.adjustmentWindow = adjustmentWindow;
        positive(lookAheadSeconds, "lookahead"); positive(candidateStepSeconds, "candidate step");
        if (maximumCandidates < 1) throw new IllegalArgumentException("maximumCandidates must include at least no-action");
        var ordered = new TreeMap<TripKey, Double>(TRIP_ORDER);
        for (ScheduledTrip trip : Objects.requireNonNull(trips, "baseline trips")) {
            Objects.requireNonNull(trip, "baseline trip");
            if (ordered.putIfAbsent(trip.key(), trip.baselineDepartureSeconds()) != null)
                throw new IllegalArgumentException("Duplicate baseline trip: " + trip.key());
            finiteSum(trip.baselineDepartureSeconds(), limits.maximumDelaySeconds(), "baseline plus maximum delay");
        }
        if (ordered.isEmpty()) throw new IllegalArgumentException("Baseline must contain at least one trip");
        this.baseline = immutableTimes(ordered);
        this.lookAheadSeconds = lookAheadSeconds;
        this.step = BigDecimal.valueOf(candidateStepSeconds);
        this.maximumCandidates = maximumCandidates;
    }

    public List<Candidate> generate(Snapshot current) {
        Map<TripKey, Double> present = validateSnapshot(current);
        double currentShift = totalShift(present);
        double lockBoundary = finiteSum(current.nowSeconds(), limits.lockWindowSeconds(), "decision time plus lock window");
        double horizon = finiteSum(current.nowSeconds(), lookAheadSeconds, "decision time plus lookahead");
        if (!current.enabled()) return List.of(new Candidate(Map.of(), present, currentShift));

        // Stage only small move descriptors until the entire generation passes its computational bound.
        List<Move> moves = new ArrayList<>();
        for (var entry : baseline.entrySet()) {
            TripKey trip = entry.getKey();
            double existing = present.get(trip);
            if (adjustmentWindow != null && !adjustmentWindow.contains(entry.getValue())) continue;
            if (current.startedAtSeconds().containsKey(trip) || current.lockedTrips().contains(trip)
                    || existing <= lockBoundary || existing - current.nowSeconds() <= limits.lockWindowSeconds()
                    || existing > horizon) continue;
            BigDecimal origin = BigDecimal.valueOf(entry.getValue());
            BigDecimal lower = origin.subtract(BigDecimal.valueOf(limits.maximumAdvanceSeconds())).max(BigDecimal.ZERO);
            if (adjustmentWindow != null)
                lower = lower.max(BigDecimal.valueOf(adjustmentWindow.startInclusiveSeconds()));
            BigDecimal upper = origin.add(BigDecimal.valueOf(limits.maximumDelaySeconds())).min(BigDecimal.valueOf(horizon));
            BigInteger first = quotient(lower.subtract(origin), RoundingMode.CEILING)
                    .max(quotient(BigDecimal.valueOf(lockBoundary).subtract(origin), RoundingMode.FLOOR).add(BigInteger.ONE));
            BigInteger last = quotient(upper.subtract(origin), RoundingMode.FLOOR);
            if (adjustmentWindow != null)
                last = last.min(quotient(BigDecimal.valueOf(adjustmentWindow.endExclusiveSeconds()).subtract(origin),
                        RoundingMode.CEILING).subtract(BigInteger.ONE));
            if (first.compareTo(last) > 0) continue;
            BigInteger count = last.subtract(first).add(BigInteger.ONE);
            long remaining = (long) maximumCandidates - 1 - moves.size();
            // At most one distinct grid time can be the identity/no-action value.
            if (count.compareTo(BigInteger.valueOf(remaining + 1)) > 0) throw budgetExceeded();
            double previous = Double.NEGATIVE_INFINITY;
            for (BigInteger index = first; index.compareTo(last) <= 0; index = index.add(BigInteger.ONE)) {
                double time = origin.add(step.multiply(new BigDecimal(index))).doubleValue();
                if (!Double.isFinite(time) || time < 0 || time <= previous)
                    throw new ArithmeticException("Original-baseline grid cannot be represented by distinct finite native times: " + trip);
                if (time <= lockBoundary || time - current.nowSeconds() <= limits.lockWindowSeconds() || time > horizon
                        || entry.getValue() - time > limits.maximumAdvanceSeconds()
                        || time - entry.getValue() > limits.maximumDelaySeconds()
                        || adjustmentWindow != null && !adjustmentWindow.permits(entry.getValue(), time))
                    throw new ArithmeticException("Grid conversion violates a strict native time/shift boundary: " + trip);
                previous = time;
                if (time == existing) continue;
                if (moves.size() >= (long) maximumCandidates - 1) throw budgetExceeded();
                moves.add(new Move(trip, time));
            }
        }
        List<Candidate> result = new ArrayList<>();
        result.add(new Candidate(Map.of(), present, currentShift));
        for (Move move : moves) {
            Map<TripKey, Double> changed = new LinkedHashMap<>(present);
            changed.put(move.trip(), move.time());
            result.add(new Candidate(Map.of(move.trip(), move.time()), changed, totalShift(changed)));
        }
        return List.copyOf(result);
    }

    private Map<TripKey, Double> validateSnapshot(Snapshot current) {
        Objects.requireNonNull(current, "current snapshot");
        Objects.requireNonNull(current.version(), "snapshot version");
        if (current.iteration() < 0 || current.version().epoch() <= 0 || current.version().revision() < 0)
            throw new IllegalArgumentException("Snapshot does not describe an initialized ledger version");
        finiteNonnegative(current.nowSeconds(), "snapshot decision time");
        if (!current.commitments().keySet().equals(baseline.keySet()))
            throw new IllegalArgumentException("Snapshot commitment keys must exactly match the original baseline");
        if (!baseline.keySet().containsAll(current.lockedTrips()) || !baseline.keySet().containsAll(current.startedAtSeconds().keySet()))
            throw new IllegalArgumentException("Snapshot contains unknown locked or started trips");
        for (var entry : current.startedAtSeconds().entrySet()) {
            finiteNonnegative(Objects.requireNonNull(entry.getValue()), "actual start time");
            if (entry.getValue() > current.nowSeconds()) throw new IllegalArgumentException("Snapshot contains a future actual start");
        }
        Map<TripKey, Double> ordered = new LinkedHashMap<>();
        baseline.forEach((key, origin) -> {
            double time = Objects.requireNonNull(current.commitments().get(key), "commitment time");
            finiteNonnegative(time, "current commitment");
            if (origin - time > limits.maximumAdvanceSeconds() || time - origin > limits.maximumDelaySeconds())
                throw new IllegalArgumentException("Current commitment exceeds the original-baseline displacement limits: " + key);
            if (adjustmentWindow != null && !adjustmentWindow.permits(origin, time))
                throw new IllegalArgumentException("Current commitment violates the origin-departure adjustment window: " + key);
            ordered.put(key, time);
        });
        return Collections.unmodifiableMap(ordered);
    }

    private BigInteger quotient(BigDecimal value, RoundingMode rounding) {
        return value.divide(step, 0, rounding).toBigIntegerExact();
    }

    private double totalShift(Map<TripKey, Double> departures) {
        double total = 0;
        for (var entry : baseline.entrySet()) {
            total += Math.abs(departures.get(entry.getKey()) - entry.getValue());
            if (!Double.isFinite(total)) throw new ArithmeticException("Total absolute original-baseline shift overflow");
        }
        return total == 0 ? 0 : total;
    }

    private IllegalArgumentException budgetExceeded() {
        return new IllegalArgumentException("Candidate neighbourhood exceeds explicit maximumCandidates=" + maximumCandidates + "; no truncation is permitted");
    }

    private static Map<TripKey, Double> immutableTimes(Map<TripKey, Double> values) {
        Map<TripKey, Double> copy = new LinkedHashMap<>();
        Objects.requireNonNull(values, "departure times").forEach((key, value) -> {
            Objects.requireNonNull(key, "trip key");
            finiteNonnegative(Objects.requireNonNull(value, "departure time"), "departure time");
            copy.put(key, value == 0 ? 0.0 : value);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static double finiteSum(double first, double second, String label) {
        double value = first + second;
        if (!Double.isFinite(value)) throw new ArithmeticException(label + " overflow");
        return value;
    }

    private static void positive(double value, String label) {
        finiteNonnegative(value, label);
        if (value == 0) throw new IllegalArgumentException(label + " must be positive");
    }

    private static void finiteNonnegative(double value, String label) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid " + label + ": " + value);
    }
}
