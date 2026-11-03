package org.matsim.core.mobsim.qsim.pt;

import org.matsim.api.core.v01.Message;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.pt.Umlauf;
import org.matsim.pt.UmlaufStueckI;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Version;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Collections;
import java.util.Set;

/**
 * A local-QSim native driver adapter for commitments accepted by an external ledger.
 * It does not choose commitments, validate an operating policy, publish a timetable,
 * dispatch or requeue an agent. The caller must requeue exactly once when a refresh
 * reports CURRENT_ACTIVITY_CHANGED, with no simulation step between publication and
 * application. Unexpected native application failures propagate; no queue rollback
 * is claimed here.
 *
 * <p>The immutable native schedule and work order are unchanged. Getters read cached
 * state only, never the provider or a population-sized ledger snapshot. The physical
 * ready bound records native leg completion/chain release, not a verified minimum
 * turnaround time. An active-leg anchor is frozen for the inherited stop holding rule.
 * No additional MATSim Id or random generator is created by this adapter; the one
 * superclass construction retains normal native driver-person construction.</p>
 *
 * <p>Only active nonzero-control wiring should construct this class. Exact OFF/zero
 * bypass belongs in the factory and must return a default native driver. This class
 * deliberately rejects Dsim serialization, whose native message omits its state.
 * External commitment publication and refresh must occur on the owning QSim control
 * thread. Native lifecycle methods may run on QNetsim workers; the QSim step barriers
 * must separate those transitions from control publication. This contract does not
 * itself establish correctness for a parallel simulation configuration.</p>
 */
public final class ControlledTransitDriverAgent extends TransitDriverAgentImpl {
    @FunctionalInterface
    public interface AcceptedCommitmentProvider {
        /** Empty means no override has ever been accepted for this trip in this epoch. */
        Optional<AcceptedCommitment> find(TripKey trip);
    }

    /** Restoring baseline is an explicit accepted value, not removal of an earlier override. */
    public record AcceptedCommitment(double departureSeconds, Version version) {
        public AcceptedCommitment {
            departureSeconds = time(departureSeconds, "accepted departure");
            Objects.requireNonNull(version, "version");
            if (version.epoch() <= 0 || version.revision() < 0) {
                throw new IllegalArgumentException("Accepted commitment requires a positive epoch and non-negative revision");
            }
        }
    }

    public enum RefreshOutcome { CURRENT_ACTIVITY_CHANGED, FUTURE_ONLY, BLOCKED, NO_CHANGE }

    public record RefreshResult(RefreshOutcome outcome, double previousDeadlineSeconds,
                                double effectiveDeadlineSeconds, Optional<Version> acceptedVersion) {
        public boolean requiresReschedule() { return outcome == RefreshOutcome.CURRENT_ACTIVITY_CHANGED; }
    }

    /** Effective deadlines may be positive infinity for native blocked/terminal activity. */
    public record ExecutionState(Optional<TripKey> trip, MobsimAgent.State state, boolean chainBlocked,
                                 boolean terminal, double physicalReadySeconds, double effectiveDeadlineSeconds,
                                 Optional<Version> acceptedVersion) { }

    private final AcceptedCommitmentProvider provider;
    private final long commitmentEpoch;
    private final Map<TripKey, Departure> knownTrips = new LinkedHashMap<>();
    private final Map<Departure, TripKey> keysByDeparture = new IdentityHashMap<>();
    private final Map<TripKey, AcceptedCommitment> accepted = new HashMap<>();
    private final Map<TripKey, Optional<AcceptedCommitment>> started = new HashMap<>();
    private boolean initialized;
    private boolean inNativeTransition;
    private boolean blocked;
    private boolean terminal;
    private double physicalReady;
    private double lastObservedTime;
    private Double runningAnchor;
    private TripKey currentTrip;
    private AcceptedCommitment currentAccepted;

    public ControlledTransitDriverAgent(Umlauf umlauf, String transportMode, TransitStopAgentTracker tracker,
                                        InternalInterface internalInterface, AcceptedCommitmentProvider provider,
                                        double initialPhysicalReadySeconds, long commitmentEpoch) {
        super(validateConstruction(umlauf, internalInterface, provider, initialPhysicalReadySeconds, commitmentEpoch),
                transportMode, tracker, internalInterface);
        this.provider = provider;
        this.commitmentEpoch = commitmentEpoch;
        this.physicalReady = time(initialPhysicalReadySeconds, "initial physical ready time");
        this.lastObservedTime = this.physicalReady;
        for (UmlaufStueckI work : umlauf.getUmlaufStuecke()) {
            if (work.isFahrt()) {
                TripKey key = keyOf(work);
                knownTrips.put(key, work.getDeparture());
                keysByDeparture.put(work.getDeparture(), key);
            }
        }
        this.initialized = true;
        loadCurrentNativeWork();
    }

    @Override
    public double getActivityEndTime() {
        // Superclass construction/transition can call virtual methods before adapter state is ready.
        if (!initialized || inNativeTransition) return super.getActivityEndTime();
        if (getState() == MobsimAgent.State.LEG && runningAnchor != null) return runningAnchor;
        if (getState() == MobsimAgent.State.ACTIVITY) {
            if (blocked || terminal) return Double.POSITIVE_INFINITY;
            if (currentTrip != null && currentAccepted != null) {
                return Math.max(currentAccepted.departureSeconds(), physicalReady);
            }
        }
        // Important for chain-ready: super may deliberately differ from the published baseline.
        return super.getActivityEndTime();
    }

    public ExecutionState currentExecutionState() {
        return new ExecutionState(Optional.ofNullable(currentTrip), getState(), blocked, terminal, physicalReady,
                getActivityEndTime(), currentVersion());
    }

    /**
     * Refresh one already accepted ledger entry. Future work is cached only, a blocked
     * driver remains at infinity, and an already-started trip cannot acquire a new
     * override, lose its old override, or change its accepted time. A version-only
     * repetition for an already-started trip leaves its applied version/anchor frozen.
     */
    public RefreshResult refreshAcceptedCommitment(TripKey trip, double now) {
        requireKnown(trip);
        double observed = monotonicTime(now);
        if (getState() == MobsimAgent.State.ABORT) throw new IllegalStateException("Cannot refresh an aborted driver");
        double previous = getActivityEndTime();
        Optional<AcceptedCommitment> incoming = validatedLookup(trip);
        if (started.containsKey(trip)) {
            if (!sameAcceptedTime(started.get(trip), incoming)) {
                throw new IllegalStateException("Accepted commitment changed after native start: " + trip);
            }
            // Track publication monotonicity separately from the frozen applied start version.
            incoming.ifPresent(value -> accepted.put(trip, value));
            lastObservedTime = observed;
            return new RefreshResult(RefreshOutcome.NO_CHANGE, previous, getActivityEndTime(), currentVersion());
        }
        AcceptedCommitment former = accepted.get(trip);
        incoming.ifPresent(value -> accepted.put(trip, value));
        lastObservedTime = observed;
        if (!trip.equals(currentTrip)) {
            return new RefreshResult(Objects.equals(former, incoming.orElse(null)) ? RefreshOutcome.NO_CHANGE : RefreshOutcome.FUTURE_ONLY,
                    previous, getActivityEndTime(), incoming.map(AcceptedCommitment::version));
        }
        currentAccepted = incoming.orElse(null);
        double deadline = getActivityEndTime();
        if (blocked || terminal) return new RefreshResult(RefreshOutcome.BLOCKED, previous, deadline, currentVersion());
        if (getState() != MobsimAgent.State.ACTIVITY) {
            throw new IllegalStateException("Unstarted current trip is not in native ACTIVITY: " + trip);
        }
        RefreshOutcome outcome = deadline == previous ? RefreshOutcome.NO_CHANGE : RefreshOutcome.CURRENT_ACTIVITY_CHANGED;
        return new RefreshResult(outcome, previous, deadline, currentVersion());
    }

    @Override
    public void endActivityAndComputeNextState(double now) {
        double observed = monotonicTime(now);
        if (getState() != MobsimAgent.State.ACTIVITY || blocked || terminal) {
            throw new IllegalStateException("Cannot start a non-active, blocked or terminal native work segment");
        }
        double anchor = getActivityEndTime();
        if (!Double.isFinite(anchor) || observed < anchor) {
            throw new IllegalStateException("Native activity ended before its effective deadline");
        }
        if (currentTrip != null) {
            if (started.containsKey(currentTrip)) throw new IllegalStateException("Duplicate native start: " + currentTrip);
            // Latch before the native start event is dispatched to synchronous listeners.
            started.put(currentTrip, Optional.ofNullable(currentAccepted));
        }
        runningAnchor = anchor;
        lastObservedTime = observed;
        super.endActivityAndComputeNextState(observed);
    }

    @Override
    public void endLegAndComputeNextState(double now) {
        double observed = monotonicTime(now);
        if (getState() != MobsimAgent.State.LEG) throw new IllegalStateException("Cannot end a non-running native leg");
        inNativeTransition = true;
        try {
            super.endLegAndComputeNextState(observed);
        } finally {
            inNativeTransition = false;
        }
        lastObservedTime = observed;
        physicalReady = Math.max(physicalReady, observed);
        runningAnchor = null;
        blocked = false;
        loadCurrentNativeWork();
    }

    @Override
    void setWaitForDeparture() {
        if (initialized && (getState() != MobsimAgent.State.ACTIVITY || terminal)) {
            throw new IllegalStateException("Cannot block a running or terminal driver");
        }
        super.setWaitForDeparture();
        blocked = true;
    }

    @Override
    void setReadyForDeparture(double now) {
        double observed = monotonicTime(now);
        if (getState() != MobsimAgent.State.ACTIVITY || terminal) {
            throw new IllegalStateException("Cannot release a running or terminal driver");
        }
        super.setReadyForDeparture(observed);
        blocked = false;
        physicalReady = Math.max(physicalReady, observed);
        lastObservedTime = observed;
        // Native chain-transfer code owns the subsequent reschedule; do not do it here.
    }

    @Override
    public Message toMessage() {
        throw new UnsupportedOperationException("ControlledTransitDriverAgent supports local QSim only; Dsim messages omit control state");
    }

    private void loadCurrentNativeWork() {
        terminal = getState() == MobsimAgent.State.ACTIVITY && getNextPlanElement() == null;
        currentTrip = terminal || getTransitRoute() == null || getDeparture() == null
                ? null : keysByDeparture.get(getDeparture());
        currentAccepted = null;
        if (!terminal && getDeparture() != null && getTransitRoute() != null && currentTrip == null) {
            throw new IllegalStateException("Native current departure is not one of the original work segments");
        }
        if (currentTrip != null) {
            if (started.containsKey(currentTrip)) throw new IllegalStateException("Native work revisits an already-started service trip: " + currentTrip);
            Optional<AcceptedCommitment> incoming = validatedLookup(currentTrip);
            incoming.ifPresent(value -> accepted.put(currentTrip, value));
            currentAccepted = incoming.orElse(null);
        }
    }

    private Optional<AcceptedCommitment> validatedLookup(TripKey trip) {
        Optional<AcceptedCommitment> incoming = Objects.requireNonNull(provider.find(trip), "Provider returned null instead of Optional");
        AcceptedCommitment previous = accepted.get(trip);
        if (incoming.isEmpty()) {
            if (previous != null) throw new IllegalStateException("Accepted override was withdrawn: " + trip);
            return incoming;
        }
        AcceptedCommitment next = incoming.orElseThrow();
        if (next.version().epoch() != commitmentEpoch) throw new IllegalStateException("Commitment belongs to a different QSim epoch: " + trip);
        if (previous != null) {
            if (next.version().revision() < previous.version().revision()) throw new IllegalStateException("Stale accepted commitment version: " + trip);
            if (next.version().equals(previous.version()) && next.departureSeconds() != previous.departureSeconds()) {
                throw new IllegalStateException("Accepted time was changed without a new ledger version: " + trip);
            }
        }
        return incoming;
    }

    private Optional<Version> currentVersion() {
        return Optional.ofNullable(currentAccepted).map(AcceptedCommitment::version);
    }

    private void requireKnown(TripKey trip) {
        if (!knownTrips.containsKey(trip)) throw new IllegalArgumentException("Unknown driver work trip: " + trip);
    }

    private double monotonicTime(double now) {
        double value = time(now, "native observation time");
        if (initialized && value < lastObservedTime) throw new IllegalArgumentException("Native observation time moved backwards");
        return value;
    }

    private static boolean sameAcceptedTime(Optional<AcceptedCommitment> left, Optional<AcceptedCommitment> right) {
        if (left.isEmpty() || right.isEmpty()) return left.isEmpty() && right.isEmpty();
        return left.orElseThrow().departureSeconds() == right.orElseThrow().departureSeconds();
    }

    private static Umlauf validateConstruction(Umlauf umlauf, InternalInterface internalInterface,
                                               AcceptedCommitmentProvider provider, double ready, long epoch) {
        Objects.requireNonNull(umlauf, "umlauf");
        Objects.requireNonNull(internalInterface, "internalInterface");
        Objects.requireNonNull(provider, "provider");
        time(ready, "initial physical ready time");
        if (epoch <= 0) throw new IllegalArgumentException("A positive fixed commitment epoch is required");
        if (!(internalInterface.getMobsim() instanceof QSim)) throw new IllegalArgumentException("Only local QSim is supported");
        if (umlauf.getUmlaufStuecke().isEmpty()) throw new IllegalArgumentException("A native work sequence is required");
        Map<TripKey, Departure> seen = new HashMap<>();
        Set<Departure> departureInstances = Collections.newSetFromMap(new IdentityHashMap<>());
        for (UmlaufStueckI work : umlauf.getUmlaufStuecke()) {
            Objects.requireNonNull(work, "work");
            if (work.isFahrt()) {
                TripKey key = keyOf(work);
                time(work.getDeparture().getDepartureTime(), "native baseline departure");
                if (seen.putIfAbsent(key, work.getDeparture()) != null) throw new IllegalArgumentException("Duplicate native work trip: " + key);
                if (!departureInstances.add(work.getDeparture())) {
                    throw new IllegalArgumentException("Unsupported native work: the same Departure instance is reused by multiple services; "
                            + "its composite trip identity would be ambiguous: " + key);
                }
            }
        }
        return umlauf;
    }

    private static TripKey keyOf(UmlaufStueckI work) {
        return new TripKey(Objects.requireNonNull(work.getLine(), "line").getId().toString(),
                Objects.requireNonNull(work.getRoute(), "route").getId().toString(),
                Objects.requireNonNull(work.getDeparture(), "departure").getId().toString());
    }

    private static double time(double value, String label) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid " + label + ": " + value);
        return value == 0.0 ? 0.0 : value;
    }
}
