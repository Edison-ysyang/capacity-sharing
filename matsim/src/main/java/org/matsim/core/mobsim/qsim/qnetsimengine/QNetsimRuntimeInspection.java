package org.matsim.core.mobsim.qsim.qnetsimengine;

import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimRuntimeInspection;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Diagnostics for the exact native thread-pool engine supplied by the caller.
 * inspect is read-only; trackPreparedExecution is a separately named opt-in marker
 * installation. Neither reads mutable configuration, calls a lifecycle method,
 * creates runners or starts threads. A wrapper is not inspected through its delegate.
 *
 * <p>Call before QSim.run or at an established QSim lifecycle barrier, not while
 * beforeMobsim is concurrently constructing runners. {@code initialized} means
 * the runner list has been materialized; it does not assert that a thread is
 * currently running, that all engine preparation has completed, or that the pool
 * remains open after cleanup. The runner count is unavailable before that list
 * exists, rather than being reported as zero.
 *
 * <p>This value does not prove that the inspected object is the active QSim engine,
 * certify producer completeness, or seal any mutable registration. Those checks
 * belong to the runtime assembly. No native object is returned to a consumer.</p>
 */
public final class QNetsimRuntimeInspection {
    public enum ExecutionPhase { PREPARED, RUNNING_NODES, RUNNING_LINKS, BETWEEN_STEPS, FAILED, CLOSED }

    /** Point-in-time diagnostics; IDLE-like phases alone do not authenticate a QSim callback. */
    public record BarrierSnapshot(ExecutionPhase phase, int actualRunnerCount, long completedSteps,
                                  OptionalDouble lastCompletedStepSeconds, boolean callerIsOwnerThread,
                                  boolean runnerIdentitiesUnchanged) { }

    /**
     * Assembly-only handle, deliberately not a policy input. Installation is opt-in
     * at the real post-preparation hook. Native node and link futures must ALL return
     * successfully before BETWEEN_STEPS is published; failure never publishes an
     * idle certificate. This records native execution, not external producer closure,
     * registration sealing, arbitrary callback behavior or absence of ABA mutations.
     */
    public static final class ExecutionBarrier {
        private record State(ExecutionPhase phase, long completedSteps, OptionalDouble lastCompletedStep) { }
        private final QSim owner;
        private final QNetsimEngineWithThreadpool engine;
        private final Thread ownerThread;
        private final List<QNetsimEngineRunnerForThreadpool> runners;
        private final double startTime;
        private volatile State state = new State(ExecutionPhase.PREPARED, 0, OptionalDouble.empty());

        private ExecutionBarrier(QSim owner, QNetsimEngineWithThreadpool engine) {
            this.owner = owner; this.engine = engine; ownerThread = Thread.currentThread();
            runners = List.copyOf(engine.getQnetsimEngineRunner());
            startTime = owner.getSimTimer().getTimeOfDay();
        }

        public BarrierSnapshot snapshot() {
            State read = state;
            return new BarrierSnapshot(read.phase(), runners.size(), read.completedSteps(), read.lastCompletedStep(),
                    Thread.currentThread() == ownerThread, sameRunners());
        }

        public void requirePrepared(QSim qsim) {
            requireOwner(qsim);
            require(QSimRuntimeInspection.isAtPreparationCheck(owner), "Not the native prepared callback");
            require(state.phase() == ExecutionPhase.PREPARED && state.completedSteps() == 0,
                    "Execution tracking is no longer at preparation");
            requireActiveEngine();
        }

        /** This authenticates the native Before scope as well as this engine's completed futures. */
        public void requireBeforeStep(QSim qsim, double now) {
            requireOwner(qsim);
            require(QSimRuntimeInspection.isAtBeforeSimStep(owner), "Not the native QSim Before callback");
            require(Double.isFinite(now) && owner.getSimTimer().getTimeOfDay() == now,
                    "Execution barrier differs from the live QSim clock");
            requireActiveEngine();
            State read = state;
            if (read.phase() == ExecutionPhase.PREPARED) {
                require(read.completedSteps() == 0 && now == startTime, "Missing first native engine step");
            } else {
                require(read.phase() == ExecutionPhase.BETWEEN_STEPS && read.lastCompletedStep().isPresent(),
                        "Native runners have not completed a successful step");
                require(read.lastCompletedStep().getAsDouble() + owner.getSimTimer().getSimTimestepSize() == now,
                        "Native completed step does not immediately precede this Before callback");
            }
        }

        private void requireOwner(QSim qsim) {
            require(qsim == owner && Thread.currentThread() == ownerThread, "Wrong QSim or execution-control thread");
        }
        private void requireActiveEngine() {
            require(QSimRuntimeInspection.activeNetworkEngineIs(owner, engine)
                    && QSimRuntimeInspection.hasSingleNetworkEngineRegistration(owner, engine),
                    "Tracked engine is not the unique native QSim step engine");
            require(sameRunners(), "Materialized runner identity/order changed");
        }
        private boolean sameRunners() {
            var actual = engine.getQnetsimEngineRunner();
            if (actual == null || actual.size() != runners.size()) return false;
            for (int i = 0; i < runners.size(); i++) if (actual.get(i) != runners.get(i)) return false;
            return true;
        }

        // Called by the exact native engine only. No changes to engine scheduling,
        // runner invocation order, exceptions or events are made by these markers.
        void movingNodes() {
            State old = state;
            ExecutionPhase next = Thread.currentThread() == ownerThread
                    && (old.phase() == ExecutionPhase.PREPARED || old.phase() == ExecutionPhase.BETWEEN_STEPS)
                    ? ExecutionPhase.RUNNING_NODES : ExecutionPhase.FAILED;
            state = new State(next, old.completedSteps(), old.lastCompletedStep());
        }
        void movingLinks() {
            State old = state;
            ExecutionPhase next = Thread.currentThread() == ownerThread && old.phase() == ExecutionPhase.RUNNING_NODES
                    ? ExecutionPhase.RUNNING_LINKS : ExecutionPhase.FAILED;
            state = new State(next, old.completedSteps(), old.lastCompletedStep());
        }
        void completed(double time) {
            State old = state;
            if (Thread.currentThread() != ownerThread || old.phase() != ExecutionPhase.RUNNING_LINKS
                    || !Double.isFinite(time) || old.completedSteps() == Long.MAX_VALUE) {
                failed();
            } else {
                state = new State(ExecutionPhase.BETWEEN_STEPS, old.completedSteps() + 1, OptionalDouble.of(time));
            }
        }
        void failed() {
            State old = state;
            state = new State(ExecutionPhase.FAILED, old.completedSteps(), old.lastCompletedStep());
        }
        void closed() {
            State old = state;
            if (old.phase() != ExecutionPhase.FAILED)
                state = new State(ExecutionPhase.CLOSED, old.completedSteps(), old.lastCompletedStep());
        }
    }

    /** Immutable diagnostic values, not an unforgeable runtime certificate. */
    public record Snapshot(String engineClass, int constructionThreadCount, int constructionRunnerCount,
                           boolean initialized, OptionalInt actualRunnerCount, List<String> actualRunnerClasses) {
        public Snapshot {
            Objects.requireNonNull(engineClass);
            Objects.requireNonNull(actualRunnerCount);
            actualRunnerClasses = List.copyOf(actualRunnerClasses);
            if (initialized != actualRunnerCount.isPresent()
                    || actualRunnerCount.isPresent() && actualRunnerCount.getAsInt() != actualRunnerClasses.size()
                    || !initialized && !actualRunnerClasses.isEmpty()) {
                throw new IllegalArgumentException("Runner availability, count and class list disagree");
            }
        }
    }

    private QNetsimRuntimeInspection() { }

    /**
     * The sole mutating opt-in API in this inspection class. It installs diagnostic
     * markers only; it neither starts workers nor changes the event manager, runner
     * count, scheduling or default behavior. Do not install it for historical arms
     * merely to make their runtime resemble a controlled experiment.
     */
    public static ExecutionBarrier trackPreparedExecution(QSim owner, QNetsimEngineI engine) {
        Objects.requireNonNull(owner); Objects.requireNonNull(engine);
        require(QSimRuntimeInspection.isAtPreparationCheck(owner), "Execution tracking requires native afterPrepared");
        require(engine.getClass() == QNetsimEngineWithThreadpool.class, "Only the exact native thread-pool engine is supported");
        var nativeEngine = (QNetsimEngineWithThreadpool) engine;
        require(QSimRuntimeInspection.activeNetworkEngineIs(owner, engine)
                && QSimRuntimeInspection.hasSingleNetworkEngineRegistration(owner, engine), "Wrong active native engine");
        var actual = nativeEngine.getQnetsimEngineRunner();
        require(actual != null && !actual.isEmpty() && actual.size() == nativeEngine.numOfThreads
                && actual.size() == nativeEngine.constructionRunnerCount(), "Materialized native runner counts disagree");
        var handle = new ExecutionBarrier(owner, nativeEngine);
        nativeEngine.installExecutionBarrier(handle);
        return handle;
    }

    public static Snapshot inspect(QNetsimEngineI engine) {
        Objects.requireNonNull(engine, "engine");
        if (engine.getClass() != QNetsimEngineWithThreadpool.class) {
            throw new IllegalArgumentException("Only exact QNetsimEngineWithThreadpool can be inspected: " + engine.getClass().getName());
        }
        QNetsimEngineWithThreadpool nativeEngine = (QNetsimEngineWithThreadpool) engine;
        var runners = nativeEngine.getQnetsimEngineRunner();
        if (runners == null) {
            return new Snapshot(nativeEngine.getClass().getName(), nativeEngine.numOfThreads,
                    nativeEngine.constructionRunnerCount(), false, OptionalInt.empty(), List.of());
        }
        List<String> classes = List.copyOf(runners).stream().map(runner -> runner.getClass().getName()).toList();
        return new Snapshot(nativeEngine.getClass().getName(), nativeEngine.numOfThreads,
                nativeEngine.constructionRunnerCount(), true, OptionalInt.of(classes.size()), classes);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
