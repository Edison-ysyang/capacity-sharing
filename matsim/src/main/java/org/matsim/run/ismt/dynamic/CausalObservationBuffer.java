package org.matsim.run.ismt.dynamic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.matsim.run.ismt.dynamic.DispatchCommitmentState.ObservedFact;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Version;

/**
 * Iteration-local, declared causal prefixes, not an event-source synchronization bridge.
 *
 * <p>A producer records facts that have already occurred and become available at its
 * declared receiving time. It separately declares an inclusive occurrence-time prefix
 * complete. Only facts in that closed prefix reach a strategy snapshot. A later fact
 * in a closed interval is an error, not a silent revision of earlier information.
 * Receiving a fact never declares its timestamp, or any other timestamp, complete.</p>
 *
 * <p>This class checks caller-supplied times and epochs. It cannot prove that the source
 * delivered every event, that a timestamp is truthful, or that an asynchronous event
 * manager has synchronized. Every snapshot therefore says DECLARED_PREFIX_ONLY and
 * synchronizationVerified() is always false. A production source bridge and its
 * completion barrier remain required. Give strategies Snapshot, not this producer API.</p>
 *
 * <p>The sealed payloads are observations, not a second vehicle-capacity or queue
 * conservation authority. No whole-day order registry, population plans, MATSim Id,
 * forecast engine, random generator, native simulator, or scoring function is accessed.
 * All mutations are atomic and synchronized; payloads and published lists are immutable.
 * There is no public pending-fact view or count. Within an epoch all operation times
 * are monotone, including snapshotAt; reset requires a strictly newer ledger epoch.</p>
 */
public final class CausalObservationBuffer {
    public enum Evidence { DECLARED_PREFIX_ONLY }
    public enum PersonKind { PASSENGER, FREIGHT }
    public enum MovementKind { STARTED, ARRIVED_AT_CONTROL_POINT, DEPARTED_CONTROL_POINT, ENDED }

    public sealed interface Observation permits QueuePresence, TrainMovement, ReleasedOrder { }

    /** An observed presence change, not a predicted arrival or a capacity allocation. */
    public record QueuePresence(String personId, String stopId, PersonKind kind, boolean waiting)
            implements Observation {
        public QueuePresence {
            requireText(personId, "personId");
            requireText(stopId, "stopId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Actual-event identity is declared by the source; it is not certified here. */
    public record TrainMovement(TripKey trip, String vehicleId, MovementKind kind,
                                Optional<String> controlPointId) implements Observation {
        public TrainMovement {
            Objects.requireNonNull(trip, "trip");
            requireText(vehicleId, "vehicleId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(controlPointId, "controlPointId");
            controlPointId.ifPresent(id -> requireText(id, "controlPointId"));
            if ((kind == MovementKind.ARRIVED_AT_CONTROL_POINT
                    || kind == MovementKind.DEPARTED_CONTROL_POINT) && controlPointId.isEmpty()) {
                throw new IllegalArgumentException("A control-point movement requires its explicit point identity");
            }
        }
    }

    /**
     * A released, known order. The envelope's occurrence time is the release event.
     * A future contractual deadline is known order data, not a future realized arrival.
     * Advance announcements of unreleased orders require a separate future contract.
     */
    public record ReleasedOrder(String orderId, String originStopId, String destinationStopId,
                                int units, double deadlineSeconds) implements Observation {
        public ReleasedOrder {
            requireText(orderId, "orderId");
            requireText(originStopId, "originStopId");
            requireText(destinationStopId, "destinationStopId");
            if (units <= 0) throw new IllegalArgumentException("Order units must be positive");
            deadlineSeconds = requireTime(deadlineSeconds, "deadline");
        }
    }

    public record Fact(long epoch, String factId, double occurredAtSeconds, double availableAtSeconds,
                       Observation observation) {
        public Fact {
            requireEpoch(epoch);
            requireText(factId, "factId");
            occurredAtSeconds = requireTime(occurredAtSeconds, "fact occurrence");
            availableAtSeconds = requireTime(availableAtSeconds, "fact availability");
            if (availableAtSeconds < occurredAtSeconds) {
                throw new IllegalArgumentException("A fact cannot be available before it occurs");
            }
            Objects.requireNonNull(observation, "observation");
        }
    }

    /** Inclusive occurrence-time prefix, made available at the explicitly declared time. */
    public record DeclaredWatermark(long epoch, double processedThroughSeconds, double availableAtSeconds) {
        public DeclaredWatermark {
            requireEpoch(epoch);
            processedThroughSeconds = requireTime(processedThroughSeconds, "processed-through time");
            availableAtSeconds = requireTime(availableAtSeconds, "watermark availability");
            if (processedThroughSeconds > availableAtSeconds) {
                throw new IllegalArgumentException("A completed prefix cannot extend into the future");
            }
        }
    }

    /**
     * A forecast is deliberately not Observation and cannot be recorded as Fact.
     * Its times and availability can be checked, but neither accuracy nor provenance
     * is established. This buffer does not store or publish forecasts.
     */
    public record ArrivalPrediction(long epoch, String predictionId, TripKey trip, String controlPointId,
                                    double issuedAtSeconds, double availableAtSeconds,
                                    double predictedArrivalSeconds, String method) {
        public ArrivalPrediction {
            requireEpoch(epoch);
            requireText(predictionId, "predictionId");
            Objects.requireNonNull(trip, "trip");
            requireText(controlPointId, "controlPointId");
            requireText(method, "method");
            issuedAtSeconds = requireTime(issuedAtSeconds, "prediction issue time");
            availableAtSeconds = requireTime(availableAtSeconds, "prediction availability");
            predictedArrivalSeconds = requireTime(predictedArrivalSeconds, "predicted arrival");
            if (availableAtSeconds < issuedAtSeconds || predictedArrivalSeconds < issuedAtSeconds) {
                throw new IllegalArgumentException("Prediction availability and target must not precede issue time");
            }
        }

        public void requireAvailableAt(long expectedEpoch, double decisionTimeSeconds) {
            requireEpoch(expectedEpoch);
            double decision = requireTime(decisionTimeSeconds, "decision time");
            if (epoch != expectedEpoch) throw new IllegalArgumentException("Prediction belongs to another epoch");
            if (availableAtSeconds > decision) throw new IllegalArgumentException("Prediction is not yet available");
        }
    }

    /** Only the producer can construct a snapshot; previously issued snapshots never change. */
    public static final class Snapshot {
        private final int iteration;
        private final long epoch;
        private final double decisionTimeSeconds;
        private final Optional<DeclaredWatermark> watermark;
        private final List<Fact> facts;

        private Snapshot(int iteration, long epoch, double decisionTimeSeconds,
                         DeclaredWatermark watermark, List<Fact> facts) {
            this.iteration = iteration;
            this.epoch = epoch;
            this.decisionTimeSeconds = decisionTimeSeconds;
            this.watermark = Optional.ofNullable(watermark);
            this.facts = List.copyOf(facts);
        }

        public int iteration() { return iteration; }
        public long epoch() { return epoch; }
        public double decisionTimeSeconds() { return decisionTimeSeconds; }
        public Optional<DeclaredWatermark> watermark() { return watermark; }
        public List<Fact> facts() { return facts; }
        public Evidence evidence() { return Evidence.DECLARED_PREFIX_ONLY; }
        public boolean synchronizationVerified() { return false; }

        /**
         * Times-only audit references for a ledger Batch, not a native execution claim.
         * Epoch is checked before conversion because the ledger's ObservedFact has no
         * epoch field. The caller must still use the ledger's current complete Version;
         * this method cannot check that ledger's revision or independently verify facts.
         */
        public List<ObservedFact> dispatchObservations(Version targetVersion, double decisionTimeSeconds) {
            Objects.requireNonNull(targetVersion, "targetVersion");
            if (targetVersion.epoch() != epoch || targetVersion.revision() < 0) {
                throw new IllegalArgumentException("Snapshot cannot be transferred to another epoch or invalid version");
            }
            double decision = requireTime(decisionTimeSeconds, "decision time");
            if (decision < this.decisionTimeSeconds) {
                throw new IllegalArgumentException("Snapshot was not available at the requested decision time");
            }
            return facts.stream().map(fact -> new ObservedFact(fact.factId(), fact.occurredAtSeconds(),
                    fact.availableAtSeconds())).toList();
        }
    }

    private final Map<String, Fact> received = new LinkedHashMap<>();
    private int iteration = -1;
    private long epoch;
    private double startTime;
    private double now;
    private DeclaredWatermark watermark;

    /** Bind to the ledger's new epoch, also when intentionally repeating an iteration number. */
    public synchronized void beginIteration(int iteration, long ledgerEpoch, double startTimeSeconds) {
        if (iteration < 0) throw new IllegalArgumentException("Iteration must be non-negative");
        requireEpoch(ledgerEpoch);
        double start = requireTime(startTimeSeconds, "iteration start");
        if (ledgerEpoch <= epoch) throw new IllegalArgumentException("Reset requires a strictly newer ledger epoch");
        this.iteration = iteration;
        this.epoch = ledgerEpoch;
        this.startTime = start;
        this.now = start;
        this.watermark = null;
        received.clear();
    }

    /** Atomically receive already-available facts without declaring their event interval complete. */
    public synchronized void record(long expectedEpoch, double receivedAtSeconds, List<Fact> facts) {
        requireCurrentEpoch(expectedEpoch);
        double time = requireOperationTime(receivedAtSeconds);
        List<Fact> copy = List.copyOf(facts);
        Map<String, Fact> additions = new LinkedHashMap<>();
        for (Fact fact : copy) {
            if (fact.epoch() != epoch) throw new IllegalArgumentException("Fact belongs to another epoch");
            if (fact.occurredAtSeconds() < startTime) throw new IllegalArgumentException("Fact predates this iteration start");
            if (fact.availableAtSeconds() > time) throw new IllegalArgumentException("Cannot record a future or not-yet-available fact");
            if (watermark != null && fact.occurredAtSeconds() <= watermark.processedThroughSeconds()) {
                throw new IllegalArgumentException("Late fact would change an already closed inclusive prefix");
            }
            if (received.containsKey(fact.factId()) || additions.putIfAbsent(fact.factId(), fact) != null) {
                throw new IllegalArgumentException("Duplicate fact identity in this epoch: " + fact.factId());
            }
        }
        received.putAll(additions);
        now = time;
    }

    /**
     * Record a caller-declared completion watermark, not proof of asynchronous completion.
     * Facts exactly at processedThroughSeconds must have been received before this call.
     * An unchanged through-time may be re-declared later; neither time may move backwards.
     */
    public synchronized void declareCompletedThrough(long expectedEpoch, double processedThroughSeconds,
                                                      double availableAtSeconds) {
        requireCurrentEpoch(expectedEpoch);
        DeclaredWatermark next = new DeclaredWatermark(epoch, processedThroughSeconds, availableAtSeconds);
        double time = requireOperationTime(next.availableAtSeconds());
        if (next.processedThroughSeconds() < startTime
                || (watermark != null && next.processedThroughSeconds() < watermark.processedThroughSeconds())) {
            throw new IllegalArgumentException("Completed prefix cannot move backwards or predate the iteration");
        }
        watermark = next;
        now = time;
    }

    /**
     * Issue only the closed, available prefix and advance the declared operation clock.
     * A producer cannot ingest later information and then request an earlier snapshot.
     * Before any completion declaration the prefix is unknown and the fact list is empty.
     */
    public synchronized Snapshot snapshotAt(long expectedEpoch, double decisionTimeSeconds) {
        requireCurrentEpoch(expectedEpoch);
        double time = requireOperationTime(decisionTimeSeconds);
        List<Fact> visible = new ArrayList<>();
        if (watermark != null) {
            for (Fact fact : received.values()) {
                if (fact.occurredAtSeconds() <= watermark.processedThroughSeconds()
                        && fact.availableAtSeconds() <= time) {
                    visible.add(fact);
                }
            }
        }
        Snapshot result = new Snapshot(iteration, epoch, time, watermark, visible);
        now = time;
        return result;
    }

    private void requireCurrentEpoch(long expectedEpoch) {
        if (iteration < 0) throw new IllegalStateException("Begin an iteration before using observations");
        if (expectedEpoch != epoch) throw new IllegalArgumentException("Observation operation belongs to another epoch");
    }

    private double requireOperationTime(double timeSeconds) {
        double time = requireTime(timeSeconds, "operation time");
        if (time < now) throw new IllegalArgumentException("Within-iteration observation clock cannot move backwards");
        return time;
    }

    private static void requireEpoch(long epoch) {
        if (epoch <= 0) throw new IllegalArgumentException("A positive ledger epoch is required");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be non-blank");
    }

    private static double requireTime(double value, String name) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(name + " must be finite and non-negative");
        return value == 0.0 ? 0.0 : value;
    }
}
