package org.matsim.run.ismt.dynamic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An iteration-local ledger of departure commitments, not a transit execution engine.
 *
 * <p>The baseline and vehicle assignments never change. A batch changes only existing
 * departure times, after every proposed change has passed the checks in this class.
 * Headways, vehicle connections and passenger protection are deliberately not evaluated
 * here: a caller must validate those separately before submission. No claim that a
 * commitment was executed by QSim follows from an accepted batch.</p>
 *
 * <p>Times are non-negative simulation seconds within an iteration. A fact has both
 * an occurrence time and an availability time; this class checks the declared times,
 * but cannot establish the provenance of facts supplied by its caller. Forecasts must
 * not be passed off as observations. No MATSim Id or random generator is used.</p>
 *
 * <p>All mutating operations are synchronized. Snapshots, audit entries and constructor
 * inputs are defensively copied. Starting an iteration resets commitments, locks and
 * started trips while retaining the audit history and advancing an epoch, so a batch
 * from any previous reset cannot be applied to the new day.</p>
 */
public final class DispatchCommitmentState {
    public record TripKey(String line, String route, String departure) {
        public TripKey {
            requireText(line, "line");
            requireText(route, "route");
            requireText(departure, "departure");
        }
    }

    public record ScheduledTrip(TripKey key, String vehicleId, double baselineDepartureSeconds) {
        public ScheduledTrip {
            Objects.requireNonNull(key, "key");
            requireText(vehicleId, "vehicleId");
            baselineDepartureSeconds = requireTime(baselineDepartureSeconds, "baseline departure");
        }
    }

    /** Experimental parameters must be supplied explicitly; no scientific defaults exist. */
    public record Limits(double maximumAdvanceSeconds, double maximumDelaySeconds, double lockWindowSeconds) {
        public Limits {
            maximumAdvanceSeconds = requireTime(maximumAdvanceSeconds, "maximum advance");
            maximumDelaySeconds = requireTime(maximumDelaySeconds, "maximum delay");
            lockWindowSeconds = requireTime(lockWindowSeconds, "lock window");
        }
    }

    /** Revision covers clock advances and starts as well as accepted decision batches. */
    public record Version(long epoch, long revision) { }

    public record ObservedFact(String factId, double occurredAtSeconds, double availableAtSeconds) {
        public ObservedFact {
            requireText(factId, "factId");
            occurredAtSeconds = requireTime(occurredAtSeconds, "fact occurrence");
            availableAtSeconds = requireTime(availableAtSeconds, "fact availability");
            if (availableAtSeconds < occurredAtSeconds) {
                throw new IllegalArgumentException("An observation cannot be available before it occurred");
            }
        }
    }

    /** No field permits changing a vehicle, route, capacity, or number of trips. */
    public record Batch(Version expectedVersion, double decisionTimeSeconds,
                        List<ObservedFact> observations, Map<TripKey, Double> proposedDepartures) {
        public Batch {
            Objects.requireNonNull(expectedVersion, "expectedVersion");
            decisionTimeSeconds = requireTime(decisionTimeSeconds, "decision time");
            observations = List.copyOf(observations);
            proposedDepartures = immutableMap(proposedDepartures);
            proposedDepartures.forEach((key, time) -> {
                Objects.requireNonNull(key, "proposed trip");
                requireTime(Objects.requireNonNull(time, "proposed departure"), "proposed departure");
            });
        }
    }

    public record CommitmentChange(TripKey key, String vehicleId, double baselineDepartureSeconds,
                                   double previousDepartureSeconds, double committedDepartureSeconds) { }

    /** An accepted decision is evidence of a commitment only, never of native execution. */
    public record DecisionRecord(int iteration, Version sourceVersion, Version resultingVersion,
                                 double decisionTimeSeconds, List<ObservedFact> observations,
                                 List<CommitmentChange> changes) {
        public DecisionRecord {
            observations = List.copyOf(observations);
            changes = List.copyOf(changes);
        }
    }

    public record Snapshot(int iteration, Version version, double nowSeconds, boolean enabled,
                           Map<TripKey, Double> commitments, Set<TripKey> lockedTrips,
                           Map<TripKey, Double> startedAtSeconds) {
        public Snapshot {
            commitments = immutableMap(commitments);
            lockedTrips = Collections.unmodifiableSet(new LinkedHashSet<>(lockedTrips));
            startedAtSeconds = immutableMap(startedAtSeconds);
        }
    }

    public enum Failure {
        NOT_INITIALIZED, TIME_BACKWARDS, STALE_VERSION, UNKNOWN_TRIP, ALREADY_STARTED,
        CONTROLLER_DISABLED, LOCKED_TRIP, PAST_DEPARTURE, CANDIDATE_INSIDE_LOCK_WINDOW,
        ADVANCE_LIMIT, DELAY_LIMIT, FUTURE_OBSERVATION
    }

    public static final class RejectedBatch extends IllegalArgumentException {
        private final Failure failure;

        private RejectedBatch(Failure failure, String detail) {
            super(failure + ": " + detail);
            this.failure = failure;
        }

        public Failure failure() { return failure; }
    }

    private final Map<TripKey, ScheduledTrip> baseline;
    private final Limits limits;
    private final List<DecisionRecord> history = new ArrayList<>();
    private Map<TripKey, Double> commitments = Map.of();
    private Set<TripKey> locked = Set.of();
    private Map<TripKey, Double> started = Map.of();
    private int iteration = -1;
    private long epoch;
    private long revision;
    private double now;
    private boolean enabled;

    public DispatchCommitmentState(Collection<ScheduledTrip> trips, Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(trips, "trips");
        Map<TripKey, ScheduledTrip> copy = new LinkedHashMap<>();
        for (ScheduledTrip trip : trips) {
            Objects.requireNonNull(trip, "trip");
            if (copy.putIfAbsent(trip.key(), trip) != null) {
                throw new IllegalArgumentException("Duplicate baseline trip: " + trip.key());
            }
        }
        if (copy.isEmpty()) throw new IllegalArgumentException("Baseline must contain at least one trip");
        this.baseline = immutableMap(copy);
    }

    public Map<TripKey, ScheduledTrip> baseline() { return baseline; }

    public Limits limits() { return limits; }

    /** Explicit reset, including when intentionally repeating an iteration number. */
    public synchronized Snapshot beginIteration(int iteration, double startTimeSeconds, boolean enabled) {
        if (iteration < 0) throw new IllegalArgumentException("Negative iteration");
        double initialTime = requireTime(startTimeSeconds, "iteration start");
        long nextEpoch = Math.incrementExact(epoch);
        Map<TripKey, Double> initialCommitments = new LinkedHashMap<>();
        baseline.forEach((key, trip) -> initialCommitments.put(key, trip.baselineDepartureSeconds()));
        Set<TripKey> initialLocks = locksAt(initialTime, initialCommitments, Set.of());
        this.iteration = iteration;
        this.epoch = nextEpoch;
        this.revision = 0;
        this.now = initialTime;
        this.enabled = enabled;
        this.commitments = initialCommitments;
        this.locked = initialLocks;
        this.started = new LinkedHashMap<>();
        return snapshot();
    }

    /** Advance the within-day clock and latch every commitment entering the lock window. */
    public synchronized Snapshot advanceTo(double timeSeconds) {
        requireInitialized();
        double time = requireMonotonicTime(timeSeconds);
        if (time == now) return snapshot();
        long nextRevision = Math.incrementExact(revision);
        Set<TripKey> nextLocks = locksAt(time, commitments, locked);
        this.now = time;
        this.revision = nextRevision;
        this.locked = nextLocks;
        return snapshot();
    }

    /**
     * Record a declared native start observed at the current or a later simulation time.
     * A late observation is allowed, but a future start is not. This does not dispatch
     * a vehicle or independently verify the event. The caller must supply real events.
     */
    public synchronized Snapshot recordStarted(TripKey key, double actualStartSeconds, double observedAtSeconds) {
        requireInitialized();
        requireKnown(key);
        double time = requireMonotonicTime(observedAtSeconds);
        double actual = requireTime(actualStartSeconds, "actual start");
        if (actual > time) reject(Failure.FUTURE_OBSERVATION, "Start occurs after its observation time");
        if (started.containsKey(key)) reject(Failure.ALREADY_STARTED, key.toString());
        long nextRevision = Math.incrementExact(revision);
        Set<TripKey> nextLocks = locksAt(time, commitments, locked);
        nextLocks.add(key);
        Map<TripKey, Double> nextStarted = new LinkedHashMap<>(started);
        nextStarted.put(key, actual);
        this.now = time;
        this.revision = nextRevision;
        this.locked = nextLocks;
        this.started = nextStarted;
        return snapshot();
    }

    /**
     * Validate the whole batch against one state and commit atomically. On rejection,
     * clock, version, locks, commitments and audit history are all unchanged. Identity
     * proposals are permitted even for a past/locked/started trip because they do not
     * reschedule it. They produce no CommitmentChange and must not be sent to QSim.
     */
    public synchronized DecisionRecord commit(Batch batch) {
        requireInitialized();
        Objects.requireNonNull(batch, "batch");
        Version source = version();
        if (!source.equals(batch.expectedVersion())) reject(Failure.STALE_VERSION, "Expected " + source);
        double time = requireMonotonicTime(batch.decisionTimeSeconds());
        for (ObservedFact fact : batch.observations()) {
            if (fact.occurredAtSeconds() > time || fact.availableAtSeconds() > time) {
                reject(Failure.FUTURE_OBSERVATION, fact.factId());
            }
        }
        Set<TripKey> nextLocks = locksAt(time, commitments, locked);
        Map<TripKey, Double> nextCommitments = new LinkedHashMap<>(commitments);
        List<CommitmentChange> changes = new ArrayList<>();
        // Traverse fixed baseline order so audit order does not depend on candidate Map implementation.
        for (TripKey key : batch.proposedDepartures().keySet()) requireKnown(key);
        for (ScheduledTrip trip : baseline.values()) {
            Double proposal = batch.proposedDepartures().get(trip.key());
            if (proposal == null) continue;
            double candidate = requireTime(proposal, "proposed departure");
            double current = commitments.get(trip.key());
            if (candidate == current) continue;
            if (!enabled) reject(Failure.CONTROLLER_DISABLED, trip.key().toString());
            if (started.containsKey(trip.key())) reject(Failure.ALREADY_STARTED, trip.key().toString());
            if (nextLocks.contains(trip.key())) reject(Failure.LOCKED_TRIP, trip.key().toString());
            if (candidate < time) reject(Failure.PAST_DEPARTURE, trip.key().toString());
            if (candidate - time <= limits.lockWindowSeconds()) {
                reject(Failure.CANDIDATE_INSIDE_LOCK_WINDOW, trip.key().toString());
            }
            if (trip.baselineDepartureSeconds() - candidate > limits.maximumAdvanceSeconds()) {
                reject(Failure.ADVANCE_LIMIT, trip.key().toString());
            }
            if (candidate - trip.baselineDepartureSeconds() > limits.maximumDelaySeconds()) {
                reject(Failure.DELAY_LIMIT, trip.key().toString());
            }
            nextCommitments.put(trip.key(), candidate);
            changes.add(new CommitmentChange(trip.key(), trip.vehicleId(), trip.baselineDepartureSeconds(), current, candidate));
        }
        long nextRevision = Math.incrementExact(revision);
        DecisionRecord record = new DecisionRecord(iteration, source, new Version(epoch, nextRevision), time,
                batch.observations(), changes);
        // All validation, copying and record construction precedes publication of state.
        history.add(record);
        this.commitments = nextCommitments;
        this.locked = nextLocks;
        this.now = time;
        this.revision = nextRevision;
        return record;
    }

    public synchronized Snapshot snapshot() {
        requireInitialized();
        return new Snapshot(iteration, version(), now, enabled, commitments, locked, started);
    }

    /** History survives beginIteration and identifies the iteration and unique epoch. */
    public synchronized List<DecisionRecord> history() { return List.copyOf(history); }

    private Version version() { return new Version(epoch, revision); }

    private Set<TripKey> locksAt(double time, Map<TripKey, Double> departures, Set<TripKey> priorLocks) {
        Set<TripKey> result = new LinkedHashSet<>(priorLocks);
        departures.forEach((key, departure) -> {
            // Subtraction avoids overflow from time + a very large explicit lock window.
            if (departure - time <= limits.lockWindowSeconds()) result.add(key);
        });
        return result;
    }

    private void requireInitialized() {
        if (iteration < 0) reject(Failure.NOT_INITIALIZED, "Call beginIteration first");
    }

    private void requireKnown(TripKey key) {
        if (!baseline.containsKey(key)) reject(Failure.UNKNOWN_TRIP, String.valueOf(key));
    }

    private double requireMonotonicTime(double seconds) {
        double result = requireTime(seconds, "simulation time");
        if (result < now) reject(Failure.TIME_BACKWARDS, result + " < " + now);
        return result;
    }

    private static void reject(Failure failure, String detail) { throw new RejectedBatch(failure, detail); }

    private static double requireTime(double seconds, String label) {
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Invalid " + label + ": " + seconds);
        return seconds == 0.0 ? 0.0 : seconds;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + label);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, "map")));
    }
}
