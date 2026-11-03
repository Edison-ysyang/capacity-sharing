package org.matsim.core.mobsim.qsim.pt;

import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimRuntimeInspection;
import org.matsim.pt.Umlauf;
import org.matsim.pt.UmlaufStueckI;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.run.ismt.dynamic.DispatchConstraintChecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.matsim.core.mobsim.qsim.pt.ControlledTransitDriverAgent.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;

/**
 * Unwired, active-control-only bridge from one ledger epoch to one local QSim.
 * The bridge owns its provider and factory; externally constructed drivers/providers
 * cannot be registered. Each original Umlauf is constructed once, never wrapped.
 * OFF/zero/pre-activation wiring must use the existing factory's native bypass instead.
 *
 * <p>Install this same object as factory and BeforeSimStep listener. Only its synchronous
 * StepController callback may submit, after QSim has initially queued agents. All factory,
 * listener and submission operations must use the owning QSim thread and clock;
 * manually fabricating a listener event does not establish native queue initialization.
 * No external caller may refresh these drivers or mutate their plans/accepted provider.
 * The ledger may receive real start observations/clock advances, but only this bridge may
 * commit changes during its lifetime. Native driver lifecycle calls may run on QNetsim
 * workers; their reads and transitions must be separated from bridge publication by
 * the native QSim step barriers. Volatile table publication is not a replacement for
 * those barriers or evidence of parallel execution correctness. A new iteration
 * requires a new bridge and QSim.</p>
 *
 * <p>Precommit rejection changes neither ledger, driver nor queue. After ledger.commit,
 * the complete private override table is published, changed keys are refreshed, then
 * each affected current driver is requeued at most once. An unexpected native failure
 * can leave partial application: it is audited, poisons the bridge and propagates.
 * No rollback or successful native execution is inferred from a returned queue call.</p>
 *
 * <p>Evidence remains REGISTERED_PLANNED_TIMES_ONLY with explicit coverage, including
 * empty registrations, which cause submission rejection. This class neither supplies scientific operating limits nor
 * proves full feasibility, actual headways, passenger protection or railway safety.</p>
 */
public final class ControlledTransitDispatchBridge implements TransitDriverAgentFactory, MobsimBeforeSimStepListener {
    @FunctionalInterface
    public interface StepController {
        void beforeStep(ControlledTransitDispatchBridge bridge, double nowSeconds);
    }

    public enum Status { REJECTED_PRECOMMIT, COMMITTED_APPLIED, FAILED_NATIVE_APPLY }

    public record RefreshedTrip(TripKey trip, String driverId, RefreshResult result) { }

    public record ApplicationAudit(Status status, Batch batch, Optional<DecisionRecord> decision,
                                   Optional<Comparison> constraints, List<RefreshedTrip> refreshedTrips,
                                   List<String> requeuedDriverIds, String stage, String detail) {
        public ApplicationAudit {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(batch, "batch");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(constraints, "constraints");
            refreshedTrips = List.copyOf(refreshedTrips);
            requeuedDriverIds = List.copyOf(requeuedDriverIds);
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(detail, "detail");
        }

        public EvidenceScope scope() { return EvidenceScope.REGISTERED_PLANNED_TIMES_ONLY; }
    }

    public static final class PrecommitRejected extends IllegalArgumentException {
        private final ApplicationAudit audit;
        private PrecommitRejected(ApplicationAudit audit, RuntimeException cause) {
            super(audit.detail(), cause);
            this.audit = audit;
        }
        public ApplicationAudit audit() { return audit; }
    }

    public static final class NativeApplicationFailure extends IllegalStateException {
        private final ApplicationAudit audit;
        private NativeApplicationFailure(ApplicationAudit audit, Throwable cause) {
            super("Accepted batch was not completely applied; bridge is poisoned: " + audit.stage(), cause);
            this.audit = audit;
        }
        public ApplicationAudit audit() { return audit; }
    }

    private record Target(Registration registration, int workIndex) { }

    private static final class Registration {
        final Umlauf umlauf;
        final String vehicleId;
        final List<UmlaufStueckI> works;
        final ControlledTransitDriverAgent driver;
        final List<PlanElement> plan;
        final Map<PlanElement, Integer> positions = new IdentityHashMap<>();

        Registration(Umlauf umlauf, ControlledTransitDriverAgent driver) {
            this.umlauf = umlauf;
            this.vehicleId = umlauf.getVehicleId().toString();
            this.works = List.copyOf(umlauf.getUmlaufStuecke());
            this.driver = driver;
            // getCurrentPlan() returns newly wrapped elements on every call, not native identities.
            this.plan = List.copyOf(driver.getPerson().getSelectedPlan().getPlanElements());
            require(plan.size() == Math.addExact(Math.multiplyExact(works.size(), 2), 1), "Unexpected native plan layout");
            for (int i = 0; i < plan.size(); i++) positions.put(plan.get(i), i);
            require(positions.size() == plan.size(), "Native plan reuses a plan element identity");
        }
    }

    private final QSim owner;
    private final DispatchCommitmentState ledger;
    private final DispatchConstraintChecker checker;
    private final ControlledTransitDriverAgentFactory factory;
    private final StepController controller;
    private final long epoch;
    private final int iteration;
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private final Map<TripKey, Target> targets = new LinkedHashMap<>();
    private final Set<Departure> departureIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<ApplicationAudit> audits = Collections.synchronizedList(new ArrayList<>());
    private volatile Map<TripKey, AcceptedCommitment> accepted = Map.of();
    private Map<TripKey, Double> publishedCommitments;
    private volatile Thread executionThread;
    private boolean sealed;
    private boolean inStep;
    private boolean submitting;
    private volatile boolean poisoned;
    private double stepTime;
    private double lastStep = Double.NEGATIVE_INFINITY;
    private QNetsimRuntimeInspection.ExecutionBarrier executionBarrier;

    public ControlledTransitDispatchBridge(QSim owner, DispatchCommitmentState ledger, int activationIteration,
                                           List<ControlPoint> controlPoints, List<VehicleConnection> connections,
                                           StepController controller) {
        this(owner, ledger, activationIteration, controlPoints, connections, controller, null);
    }

    public ControlledTransitDispatchBridge(QSim owner, DispatchCommitmentState ledger, int activationIteration,
                                           List<ControlPoint> controlPoints, List<VehicleConnection> connections,
                                           StepController controller,
                                           org.matsim.run.ismt.dynamic.RollingDepartureCandidates.AdjustmentWindow adjustmentWindow) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.controller = Objects.requireNonNull(controller, "controller");
        Snapshot initial = ledger.snapshot();
        require(initial.version().revision() == 0 && initial.startedAtSeconds().isEmpty(), "Bridge requires a fresh ledger epoch");
        this.epoch = initial.version().epoch();
        this.iteration = initial.iteration();
        this.publishedCommitments = initial.commitments();
        this.checker = new DispatchConstraintChecker(ledger.baseline().values(), controlPoints, connections, adjustmentWindow);
        this.factory = new ControlledTransitDriverAgentFactory(initial.enabled(), iteration, activationIteration,
                ledger.limits(), () -> new ControlledTransitDriverAgentFactory.ActiveContext(
                        key -> Optional.ofNullable(accepted.get(key)), initial.nowSeconds(), epoch));
        require(factory.isControlActive(), "Inactive/zero control must use the native factory bypass, not this bridge");
    }

    /** Read-only views; the audit records commitment application, not real first-stop departures. */
    public Snapshot snapshot() { return ledger.snapshot(); }
    public List<ApplicationAudit> history() { synchronized (audits) { return List.copyOf(audits); } }
    public boolean isPoisoned() { return poisoned; }

    /**
     * Opt-in at native afterPrepared, before the first queued step. The handle
     * authenticates Before scope and successful completion of every native runner.
     * It belongs on the assembly side, never in a policy's input. Existing untracked
     * callers retain their original explicit lifecycle contract, without acquiring
     * new multi-runner execution evidence. Production assembly must bind this handle.
     */
    public void bindExecutionBarrier(QNetsimRuntimeInspection.ExecutionBarrier barrier) {
        requireThread();
        require(!poisoned && !sealed && !inStep && executionBarrier == null, "Barrier binding is closed");
        Objects.requireNonNull(barrier).requirePrepared(owner);
        executionBarrier = barrier;
    }

    @Override
    public AbstractTransitDriverAgent createTransitDriver(Umlauf umlauf, InternalInterface internal,
                                                           TransitStopAgentTracker tracker) {
        bindRegistrationThread();
        require(!poisoned && !sealed && !inStep, "Driver registration is closed");
        require(Objects.requireNonNull(internal, "internal").getMobsim() == owner, "Wrong QSim for registration");
        requireLedgerIdentity(ledger.snapshot());
        Objects.requireNonNull(umlauf, "umlauf");
        String id = Objects.requireNonNull(umlauf.getId(), "umlauf id").toString();
        require(!registrations.containsKey(id), "Umlauf already constructed: " + id);
        List<UmlaufStueckI> works = List.copyOf(umlauf.getUmlaufStuecke());
        require(!works.isEmpty(), "Empty Umlauf");
        String vehicle = Objects.requireNonNull(umlauf.getVehicleId(), "umlauf vehicle").toString();
        Set<TripKey> keys = new LinkedHashSet<>();
        Set<Departure> instances = Collections.newSetFromMap(new IdentityHashMap<>());
        for (UmlaufStueckI work : works) {
            Objects.requireNonNull(work.getCarRoute(), "work route");
            if (work.isFahrt()) {
                TripKey key = keyOf(work);
                require(keys.add(key) && !targets.containsKey(key), "Duplicate registered trip: " + key);
                require(instances.add(work.getDeparture()) && !departureIdentities.contains(work.getDeparture()), "Reused departure identity");
                validateBaseline(work, vehicle);
            }
        }
        // The sole native construction follows all registration input checks. Never wrap a default driver.
        try {
            ControlledTransitDriverAgent driver = (ControlledTransitDriverAgent) factory.createTransitDriver(umlauf, internal, tracker);
            Registration registration = new Registration(umlauf, driver);
            registrations.put(id, registration);
            for (int i = 0; i < works.size(); i++) {
                if (works.get(i).isFahrt()) targets.put(keyOf(works.get(i)), new Target(registration, i));
            }
            departureIdentities.addAll(instances);
            return driver;
        } catch (RuntimeException | Error failure) {
            poisoned = true; // Native construction may already have occurred; retrying is not safe.
            throw failure;
        }
    }

    @Override
    public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent event) {
        requireThread();
        require(!poisoned, "Bridge is poisoned");
        require(!inStep && !submitting, "Reentrant step callback");
        require(event.getQueueSimulation() == owner, "Wrong QSim step event");
        double now = event.getSimulationTime();
        require(Double.isFinite(now) && now >= 0 && now > lastStep, "Invalid or repeated QSim step time");
        require(owner.getSimTimer().getTimeOfDay() == now, "Step event differs from the actual QSim clock");
        requireExecutionBarrier(now);
        requireLedgerIdentity(ledger.snapshot());
        require(targets.keySet().equals(ledger.baseline().keySet()), "Not all baseline trips have native driver registrations");
        for (Registration registration : registrations.values()) {
            validateRegistration(registration);
            if (!sealed) require(owner.getAgents().get(registration.driver.getId()) == registration.driver,
                    "Initial native driver registration is missing from QSim");
        }
        sealed = true;
        stepTime = now;
        lastStep = now;
        inStep = true;
        try {
            controller.beforeStep(this, now);
        } catch (PrecommitRejected rejection) {
            throw rejection;
        } catch (RuntimeException | Error failure) {
            poisoned = true;
            throw failure;
        } finally {
            inStep = false;
        }
    }

    /** Must be called synchronously from this bridge's controller callback, never from an event handler. */
    public ApplicationAudit submit(Batch batch) {
        Objects.requireNonNull(batch, "batch");
        Comparison comparison = null;
        DecisionRecord decision;
        try {
            requireThread();
            require(!poisoned, "Bridge is poisoned");
            require(inStep && !submitting, "Submission is outside the step callback or is reentrant");
            require(batch.decisionTimeSeconds() == stepTime && owner.getSimTimer().getTimeOfDay() == stepTime,
                    "Batch decision must equal the live QSim step clock");
            requireExecutionBarrier(stepTime);
        } catch (RuntimeException failure) {
            throw rejection(batch, null, failure);
        }
        submitting = true;
        try {
            // Prevent another thread from resetting/committing the ledger across the validation boundary.
            synchronized (ledger) {
                try {
                    Snapshot source = ledger.snapshot();
                    requireLedgerIdentity(source);
                    require(source.version().equals(batch.expectedVersion()), "Stale batch version or wrong epoch");
                    require(source.nowSeconds() <= stepTime, "Ledger clock is ahead of QSim");
                    Map<TripKey, Double> candidate = new LinkedHashMap<>(source.commitments());
                    for (Map.Entry<TripKey, Double> proposal : batch.proposedDepartures().entrySet()) {
                        require(targets.containsKey(proposal.getKey()), "Unknown or unregistered trip: " + proposal.getKey());
                        candidate.put(proposal.getKey(), proposal.getValue());
                    }
                    comparison = checker.evaluate(candidate);
                    require(comparison.coverage().anyConstraintsRegistered(), "No planned-time operating constraints are registered");
                    require(comparison.candidate().satisfiesRegisteredConstraints(), "Candidate violates registered planned-time constraints");
                    for (Registration registration : registrations.values()) validateRegistration(registration);
                    for (Map.Entry<TripKey, Double> proposal : batch.proposedDepartures().entrySet()) {
                        if (proposal.getValue().doubleValue() != source.commitments().get(proposal.getKey())) {
                            validateNativeTarget(proposal.getKey(), stepTime);
                        }
                    }
                    // Ledger locks, declared observations, bounds and starts are atomically checked last.
                    decision = ledger.commit(batch);
                } catch (RuntimeException failure) {
                    throw rejection(batch, comparison, failure);
                }
                return applyAccepted(batch, decision, comparison);
            }
        } finally {
            submitting = false;
        }
    }

    private ApplicationAudit applyAccepted(Batch batch, DecisionRecord decision, Comparison comparison) {
        List<RefreshedTrip> refreshed = new ArrayList<>();
        List<String> requeued = new ArrayList<>();
        String stage = "PUBLISH";
        try {
            Map<TripKey, AcceptedCommitment> next = new LinkedHashMap<>(accepted);
            Map<TripKey, Double> nextTimes = new LinkedHashMap<>(publishedCommitments);
            for (CommitmentChange change : decision.changes()) {
                next.put(change.key(), new AcceptedCommitment(change.committedDepartureSeconds(), decision.resultingVersion()));
                nextTimes.put(change.key(), change.committedDepartureSeconds());
            }
            // Absence for a never-overridden trip is preserved, particularly for already-started work.
            accepted = Collections.unmodifiableMap(next);
            publishedCommitments = Collections.unmodifiableMap(nextTimes);
            Set<ControlledTransitDriverAgent> toRequeue = new LinkedHashSet<>();
            for (CommitmentChange change : decision.changes()) {
                ControlledTransitDriverAgent driver = targets.get(change.key()).registration().driver;
                stage = "REFRESH:" + change.key();
                RefreshResult result = driver.refreshAcceptedCommitment(change.key(), stepTime);
                refreshed.add(new RefreshedTrip(change.key(), driver.getId().toString(), result));
                if (result.requiresReschedule()) toRequeue.add(driver);
            }
            for (ControlledTransitDriverAgent driver : toRequeue) {
                stage = "RESCHEDULE:" + driver.getId();
                owner.rescheduleActivityEnd(driver);
                requeued.add(driver.getId().toString());
            }
            ApplicationAudit audit = new ApplicationAudit(Status.COMMITTED_APPLIED, batch, Optional.of(decision),
                    Optional.of(comparison), refreshed, requeued, "COMPLETE", "Queue calls returned; native execution is not established");
            audits.add(audit);
            return audit;
        } catch (RuntimeException | Error failure) {
            poisoned = true;
            ApplicationAudit audit = new ApplicationAudit(Status.FAILED_NATIVE_APPLY, batch, Optional.of(decision),
                    Optional.of(comparison), refreshed, requeued, stage, failure.getClass().getName() + ": " + failure.getMessage());
            audits.add(audit);
            throw new NativeApplicationFailure(audit, failure);
        }
    }

    private void validateNativeTarget(TripKey trip, double now) {
        Target target = targets.get(trip);
        Registration registration = target.registration();
        ControlledTransitDriverAgent driver = registration.driver;
        ExecutionState state = driver.currentExecutionState();
        require(state.state() != MobsimAgent.State.ABORT && !state.terminal(), "Cannot change aborted or terminal native work: " + trip);
        Integer position = registration.positions.get(driver.getCurrentPlanElement());
        require(position != null, "Unknown native plan position");
        require(state.physicalReadySeconds() <= now, "Native readiness is in the future of the QSim clock");
        int currentWork = position / 2;
        boolean leg = state.state() == MobsimAgent.State.LEG;
        require(leg == (position % 2 == 1), "Native state and plan position disagree");
        require(target.workIndex() > currentWork || (target.workIndex() == currentWork && !leg),
                "Trip has already started or completed in the original native work sequence: " + trip);
        UmlaufStueckI current = registration.works.get(currentWork);
        Optional<TripKey> expected = current.isFahrt() ? Optional.of(keyOf(current)) : Optional.empty();
        require(state.trip().equals(expected), "Current native service identity does not match registered work order");
        require(owner.getAgents().get(driver.getId()) == driver, "Driver is not registered in its owning QSim");
        // Future service while a Fahrt or Wenden is running is cache-only. Blocked current work stays at infinity.
    }

    private void validateRegistration(Registration registration) {
        require(registration.vehicleId.equals(registration.umlauf.getVehicleId().toString()), "Umlauf vehicle identity changed");
        List<UmlaufStueckI> current = registration.umlauf.getUmlaufStuecke();
        require(current.size() == registration.works.size(), "Umlauf work count changed");
        for (int i = 0; i < current.size(); i++) {
            require(current.get(i) == registration.works.get(i), "Original native work order changed");
            if (current.get(i).isFahrt()) validateBaseline(current.get(i), registration.vehicleId);
        }
        List<PlanElement> plan = registration.driver.getPerson().getSelectedPlan().getPlanElements();
        require(plan.size() == registration.plan.size(), "Native plan size changed");
        for (int i = 0; i < plan.size(); i++) require(plan.get(i) == registration.plan.get(i), "Native plan identity/order changed");
    }

    private void validateBaseline(UmlaufStueckI work, String vehicle) {
        TripKey key = keyOf(work);
        ScheduledTrip baseline = ledger.baseline().get(key);
        require(baseline != null, "Work trip is absent from the ledger baseline: " + key);
        require(baseline.vehicleId().equals(vehicle) && vehicle.equals(String.valueOf(work.getDeparture().getVehicleId())),
                "Native vehicle differs from the fixed ledger baseline: " + key);
        require(work.getDeparture().getDepartureTime() == baseline.baselineDepartureSeconds(), "Native baseline departure changed: " + key);
        require(work.getRoute().getDepartures().get(work.getDeparture().getId()) == work.getDeparture(), "Departure is not the original route member");
        require(work.getLine().getRoutes().get(work.getRoute().getId()) == work.getRoute(), "Route is not the original line member");
    }

    private void requireLedgerIdentity(Snapshot state) {
        require(state.version().epoch() == epoch && state.iteration() == iteration, "Bridge cannot cross ledger epochs/iterations");
        require(state.commitments().equals(publishedCommitments), "Ledger commitments were changed outside this bridge");
    }

    private void requireThread() {
        require(executionThread == Thread.currentThread(), "Bridge may only be used by its registered QSim execution thread");
    }

    private void requireExecutionBarrier(double now) {
        if (executionBarrier != null) executionBarrier.requireBeforeStep(owner, now);
    }

    private synchronized void bindRegistrationThread() {
        if (executionThread == null) executionThread = Thread.currentThread();
        requireThread();
    }

    private PrecommitRejected rejection(Batch batch, Comparison comparison, RuntimeException failure) {
        ApplicationAudit audit = new ApplicationAudit(Status.REJECTED_PRECOMMIT, batch, Optional.empty(),
                Optional.ofNullable(comparison), List.of(), List.of(), "PRECOMMIT", failure.getClass().getName() + ": " + failure.getMessage());
        audits.add(audit);
        return new PrecommitRejected(audit, failure);
    }

    private static TripKey keyOf(UmlaufStueckI work) {
        return new TripKey(work.getLine().getId().toString(), work.getRoute().getId().toString(), work.getDeparture().getId().toString());
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }
}
