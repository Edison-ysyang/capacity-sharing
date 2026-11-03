package org.matsim.run.ismt.dynamic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.ScheduledTrip;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;

/**
 * Pure checks of explicitly registered planned-time constraints for complete timetables.
 *
 * <p>A call time is an origin commitment plus a caller-supplied offset, not a native
 * arrival/departure observation. Offsets and every bound are explicit experimental
 * inputs; their validity and information provenance are outside this checker. Calls
 * are checked in their registered order, including calls from different branches.
 * Sorting candidate times would hide overtaking and is deliberately not performed.</p>
 *
 * <p>Passing these checks does not establish infrastructure coverage, actual headways,
 * real vehicle circulation, railway safety, passenger protection, or QSim execution.
 * In particular, a trip appearing at one registered control point does not mean all
 * of its shared resources or intermediate control points have been covered. Missing
 * registrations are exposed in Coverage, never filled with scientific defaults.</p>
 *
 * <p>This class neither commits a batch nor handles time locks. Callers must construct
 * a complete candidate from one DispatchCommitmentState snapshot, check the relevant
 * operating constraints, and use that snapshot's version when committing. No files,
 * mutable external objects, MATSim Ids, random numbers or runtime simulations are used.</p>
 */
public final class DispatchConstraintChecker {
    public record ControlPointKey(String id, String direction) {
        public ControlPointKey {
            requireText(id, "control point id");
            requireText(direction, "control point direction");
        }
    }

    /** This version supports one visit per trip at a given control point. */
    public record Call(TripKey trip, double offsetSeconds) {
        public Call {
            Objects.requireNonNull(trip, "trip");
            offsetSeconds = nonNegativeFinite(offsetSeconds, "call offset");
        }
    }

    /** Windows apply to the first and last registered call, not to sorted candidate extrema. */
    public record FirstLastWindow(double firstEarliestSeconds, double firstLatestSeconds,
                                  double lastEarliestSeconds, double lastLatestSeconds) {
        public FirstLastWindow {
            firstEarliestSeconds = nonNegativeFinite(firstEarliestSeconds, "first earliest");
            firstLatestSeconds = nonNegativeFinite(firstLatestSeconds, "first latest");
            lastEarliestSeconds = nonNegativeFinite(lastEarliestSeconds, "last earliest");
            lastLatestSeconds = nonNegativeFinite(lastLatestSeconds, "last latest");
            if (firstLatestSeconds < firstEarliestSeconds || lastLatestSeconds < lastEarliestSeconds) {
                throw new IllegalArgumentException("A first/last service window has its bounds reversed");
            }
        }
    }

    public record ControlPoint(ControlPointKey key, List<Call> orderedCalls,
                               double minimumHeadwaySeconds, double maximumHeadwaySeconds,
                               Optional<FirstLastWindow> serviceWindow) {
        public ControlPoint {
            Objects.requireNonNull(key, "control point key");
            orderedCalls = List.copyOf(orderedCalls);
            if (orderedCalls.isEmpty()) throw new IllegalArgumentException("Control point must contain a call");
            minimumHeadwaySeconds = nonNegativeFinite(minimumHeadwaySeconds, "minimum headway");
            maximumHeadwaySeconds = nonNegativeFinite(maximumHeadwaySeconds, "maximum headway");
            if (maximumHeadwaySeconds < minimumHeadwaySeconds) {
                throw new IllegalArgumentException("Maximum headway is smaller than minimum headway");
            }
            Objects.requireNonNull(serviceWindow, "serviceWindow");
            Set<TripKey> seen = new LinkedHashSet<>();
            for (Call call : orderedCalls) {
                if (!seen.add(call.trip())) {
                    throw new IllegalArgumentException("Repeated trip at control point " + key + ": " + call.trip()
                            + "; loop visits require a separate visit identity not supported by this checker");
                }
            }
        }
    }

    /** The successor's origin must follow the predecessor's registered end plus transfer time. */
    public record VehicleConnection(String id, TripKey predecessor, TripKey successor,
                                    double predecessorEndOffsetSeconds, double minimumTransferSeconds) {
        public VehicleConnection {
            requireText(id, "connection id");
            Objects.requireNonNull(predecessor, "predecessor");
            Objects.requireNonNull(successor, "successor");
            if (predecessor.equals(successor)) throw new IllegalArgumentException("A trip cannot connect to itself");
            predecessorEndOffsetSeconds = nonNegativeFinite(predecessorEndOffsetSeconds, "predecessor end offset");
            minimumTransferSeconds = nonNegativeFinite(minimumTransferSeconds, "minimum transfer");
        }
    }

    public enum Kind {
        REGISTERED_ORDER, MINIMUM_HEADWAY, MAXIMUM_HEADWAY,
        FIRST_EARLIEST, FIRST_LATEST, LAST_EARLIEST, LAST_LATEST, MINIMUM_VEHICLE_TRANSFER
    }

    /**
     * Signed margin in seconds: non-negative passes this one registered inequality.
     * Value is a headway, first/last call time, or connection gap according to kind.
     * A control-point check has point set and connectionId empty; a connection check
     * has the converse. Trips follow registered order.
     */
    public record ConstraintCheck(Kind kind, Optional<ControlPointKey> point, Optional<String> connectionId,
                                  List<TripKey> trips, double valueSeconds, double boundSeconds,
                                  double marginSeconds) {
        public ConstraintCheck {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(connectionId, "connectionId");
            trips = List.copyOf(trips);
            if (point.isPresent() == connectionId.isPresent()) {
                throw new IllegalArgumentException("Exactly one constraint registration identity is required");
            }
            finite(valueSeconds, "check value");
            finite(boundSeconds, "check bound");
            finite(marginSeconds, "check margin");
        }

        public boolean violated() { return marginSeconds < 0; }
    }

    public record Assessment(List<ConstraintCheck> checks) {
        public Assessment { checks = List.copyOf(checks); }

        /** Vacuously true for no checks; never use without the accompanying coverage report. */
        public boolean satisfiesRegisteredConstraints() { return checks.stream().noneMatch(ConstraintCheck::violated); }

        public boolean anyConstraintsEvaluated() { return !checks.isEmpty(); }

        public List<ConstraintCheck> violations() { return checks.stream().filter(ConstraintCheck::violated).toList(); }
    }

    /** Counts registration only; it does not prove complete infrastructure or vehicle-chain coverage. */
    public record Coverage(int registeredControlPoints, int registeredHeadwayPairs, int registeredServiceWindows,
                           int registeredVehicleConnections, int evaluatedInequalities,
                           Set<TripKey> tripsWithoutRegisteredControlPointCall,
                           Set<TripKey> tripsWithoutRegisteredConnectionEndpoint) {
        public Coverage {
            tripsWithoutRegisteredControlPointCall = immutableSet(tripsWithoutRegisteredControlPointCall);
            tripsWithoutRegisteredConnectionEndpoint = immutableSet(tripsWithoutRegisteredConnectionEndpoint);
        }

        public boolean anyConstraintsRegistered() { return evaluatedInequalities > 0; }

        /** Always false: complete shared-resource coverage requires an independent network audit. */
        public boolean sharedResourceCoverageVerified() { return false; }

        /** Always false even if all trips occur as endpoints: registered pairs are not a circulation audit. */
        public boolean actualVehicleCirculationVerified() { return false; }
    }

    public enum EvidenceScope { REGISTERED_PLANNED_TIMES_ONLY }

    public record Comparison(Assessment baseline, Assessment candidate, Coverage coverage) {
        public EvidenceScope scope() { return EvidenceScope.REGISTERED_PLANNED_TIMES_ONLY; }
    }

    private record ConnectionPair(TripKey predecessor, TripKey successor) { }

    private final Map<TripKey, ScheduledTrip> baselineTrips;
    private final List<ControlPoint> points;
    private final List<VehicleConnection> connections;
    private final Assessment baselineAssessment;
    private final Coverage coverage;
    private final RollingDepartureCandidates.AdjustmentWindow adjustmentWindow;

    public DispatchConstraintChecker(Collection<ScheduledTrip> baseline, List<ControlPoint> points,
                                     List<VehicleConnection> connections) {
        this(baseline, points, connections, null);
    }

    /**
     * The explicit window rejects out-of-window edits. Headway bounds then apply to
     * every registered adjacent pair touching an origin-window-eligible trip,
     * including its fixed outside neighbour and downstream calls after the window.
     * Entirely fixed background pairs keep their original order and service windows;
     * their pre-existing headways are not certified against the new control bounds.
     */
    public DispatchConstraintChecker(Collection<ScheduledTrip> baseline, List<ControlPoint> points,
                                     List<VehicleConnection> connections,
                                     RollingDepartureCandidates.AdjustmentWindow adjustmentWindow) {
        this.adjustmentWindow = adjustmentWindow;
        Objects.requireNonNull(baseline, "baseline");
        Map<TripKey, ScheduledTrip> trips = new LinkedHashMap<>();
        for (ScheduledTrip trip : baseline) {
            Objects.requireNonNull(trip, "baseline trip");
            if (trips.putIfAbsent(trip.key(), trip) != null) {
                throw new IllegalArgumentException("Duplicate baseline trip: " + trip.key());
            }
        }
        if (trips.isEmpty()) throw new IllegalArgumentException("Baseline must contain a trip");
        this.baselineTrips = Collections.unmodifiableMap(trips);
        this.points = List.copyOf(points);
        this.connections = List.copyOf(connections);
        Set<ControlPointKey> pointKeys = new LinkedHashSet<>();
        Set<TripKey> unregisteredCalls = new LinkedHashSet<>(trips.keySet());
        int headwayPairs = 0;
        int windows = 0;
        for (ControlPoint point : this.points) {
            if (!pointKeys.add(point.key())) throw new IllegalArgumentException("Duplicate control point: " + point.key());
            for (Call call : point.orderedCalls()) {
                requireKnown(call.trip());
                unregisteredCalls.remove(call.trip());
            }
            headwayPairs = Math.addExact(headwayPairs, point.orderedCalls().size() - 1);
            if (point.serviceWindow().isPresent()) windows = Math.incrementExact(windows);
        }
        Set<String> connectionIds = new LinkedHashSet<>();
        Set<ConnectionPair> connectionPairs = new LinkedHashSet<>();
        Set<TripKey> unregisteredEndpoints = new LinkedHashSet<>(trips.keySet());
        for (VehicleConnection connection : this.connections) {
            requireKnown(connection.predecessor());
            requireKnown(connection.successor());
            if (!connectionIds.add(connection.id())) throw new IllegalArgumentException("Duplicate connection id: " + connection.id());
            if (!connectionPairs.add(new ConnectionPair(connection.predecessor(), connection.successor()))) {
                throw new IllegalArgumentException("Duplicate connection pair: " + connection.predecessor() + " -> " + connection.successor());
            }
            if (!trips.get(connection.predecessor()).vehicleId().equals(trips.get(connection.successor()).vehicleId())) {
                throw new IllegalArgumentException("Connection changes fixed vehicle identity: " + connection.id());
            }
            unregisteredEndpoints.remove(connection.predecessor());
            unregisteredEndpoints.remove(connection.successor());
        }
        Map<TripKey, Double> baselineTimes = new LinkedHashMap<>();
        trips.forEach((key, trip) -> baselineTimes.put(key, trip.baselineDepartureSeconds()));
        this.baselineAssessment = assess(baselineTimes);
        this.coverage = new Coverage(this.points.size(), headwayPairs, windows, this.connections.size(),
                baselineAssessment.checks().size(), unregisteredCalls, unregisteredEndpoints);
    }

    /** The candidate must contain exactly every original trip, including unchanged trips. */
    public Comparison evaluate(Map<TripKey, Double> candidateOriginCommitments) {
        Objects.requireNonNull(candidateOriginCommitments, "candidateOriginCommitments");
        Map<TripKey, Double> candidate = new LinkedHashMap<>(candidateOriginCommitments);
        if (!candidate.keySet().equals(baselineTrips.keySet())) {
            Set<TripKey> missing = new LinkedHashSet<>(baselineTrips.keySet());
            missing.removeAll(candidate.keySet());
            Set<TripKey> unexpected = new LinkedHashSet<>(candidate.keySet());
            unexpected.removeAll(baselineTrips.keySet());
            throw new IllegalArgumentException("Candidate must have exactly the baseline trips; missing=" + missing + ", unexpected=" + unexpected);
        }
        candidate.forEach((key, time) -> {
            nonNegativeFinite(Objects.requireNonNull(time, "candidate time"), "candidate origin time");
            if (adjustmentWindow != null && !adjustmentWindow.permits(
                    baselineTrips.get(key).baselineDepartureSeconds(), time))
                throw new IllegalArgumentException("Candidate violates the origin-departure adjustment window: " + key);
        });
        return new Comparison(baselineAssessment, assess(candidate), coverage);
    }

    private Assessment assess(Map<TripKey, Double> origins) {
        List<ConstraintCheck> checks = new ArrayList<>();
        for (ControlPoint point : points) {
            List<Call> calls = point.orderedCalls();
            List<Double> times = new ArrayList<>(calls.size());
            for (Call call : calls) times.add(sum(origins.get(call.trip()), call.offsetSeconds(), "control-point call time"));
            for (int i = 1; i < calls.size(); i++) {
                List<TripKey> trips = List.of(calls.get(i - 1).trip(), calls.get(i).trip());
                double gap = difference(times.get(i), times.get(i - 1), "headway");
                checks.add(pointCheck(Kind.REGISTERED_ORDER, point, trips, gap, 0, gap));
                if (adjustmentWindow != null && trips.stream().noneMatch(trip -> adjustmentWindow.contains(
                        baselineTrips.get(trip).baselineDepartureSeconds()))) continue;
                checks.add(pointCheck(Kind.MINIMUM_HEADWAY, point, trips, gap, point.minimumHeadwaySeconds(),
                        difference(gap, point.minimumHeadwaySeconds(), "minimum headway margin")));
                checks.add(pointCheck(Kind.MAXIMUM_HEADWAY, point, trips, gap, point.maximumHeadwaySeconds(),
                        difference(point.maximumHeadwaySeconds(), gap, "maximum headway margin")));
            }
            if (point.serviceWindow().isPresent()) {
                FirstLastWindow window = point.serviceWindow().orElseThrow();
                double first = times.getFirst();
                double last = times.getLast();
                List<TripKey> firstTrip = List.of(calls.getFirst().trip());
                List<TripKey> lastTrip = List.of(calls.getLast().trip());
                checks.add(pointCheck(Kind.FIRST_EARLIEST, point, firstTrip, first, window.firstEarliestSeconds(),
                        difference(first, window.firstEarliestSeconds(), "first earliest margin")));
                checks.add(pointCheck(Kind.FIRST_LATEST, point, firstTrip, first, window.firstLatestSeconds(),
                        difference(window.firstLatestSeconds(), first, "first latest margin")));
                checks.add(pointCheck(Kind.LAST_EARLIEST, point, lastTrip, last, window.lastEarliestSeconds(),
                        difference(last, window.lastEarliestSeconds(), "last earliest margin")));
                checks.add(pointCheck(Kind.LAST_LATEST, point, lastTrip, last, window.lastLatestSeconds(),
                        difference(window.lastLatestSeconds(), last, "last latest margin")));
            }
        }
        for (VehicleConnection connection : connections) {
            double predecessorEnd = sum(origins.get(connection.predecessor()), connection.predecessorEndOffsetSeconds(), "predecessor end");
            // Also reject overflow in the earliest permissible next start, even if subtracting first would mask it.
            sum(predecessorEnd, connection.minimumTransferSeconds(), "earliest vehicle availability");
            double gap = difference(origins.get(connection.successor()), predecessorEnd, "vehicle connection gap");
            double margin = difference(gap, connection.minimumTransferSeconds(), "vehicle connection margin");
            checks.add(new ConstraintCheck(Kind.MINIMUM_VEHICLE_TRANSFER, Optional.empty(), Optional.of(connection.id()),
                    List.of(connection.predecessor(), connection.successor()), gap, connection.minimumTransferSeconds(), margin));
        }
        return new Assessment(checks);
    }

    private static ConstraintCheck pointCheck(Kind kind, ControlPoint point, List<TripKey> trips,
                                               double value, double bound, double margin) {
        return new ConstraintCheck(kind, Optional.of(point.key()), Optional.empty(), trips, value, bound, margin);
    }

    private void requireKnown(TripKey key) {
        if (!baselineTrips.containsKey(key)) throw new IllegalArgumentException("Unknown registered trip: " + key);
    }

    private static double sum(double left, double right, String name) { return finite(left + right, name); }

    private static double difference(double left, double right, String name) { return finite(left - right, name); }

    private static double finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite or overflowing " + name + ": " + value);
        return value == 0.0 ? 0.0 : value;
    }

    private static double nonNegativeFinite(double value, String name) {
        finite(value, name);
        if (value < 0) throw new IllegalArgumentException("Negative " + name + ": " + value);
        return value == 0.0 ? 0.0 : value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
    }

    private static <T> Set<T> immutableSet(Set<T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
