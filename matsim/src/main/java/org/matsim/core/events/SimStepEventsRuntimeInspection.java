package org.matsim.core.events;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Read-only inspection of the exact native sim-step-parallel manager, including
 * its separate worker dispatcher(s). One worker is still asynchronous: it is not
 * the QSim caller thread. No method here initializes, drains, finishes, dispatches,
 * changes registrations, or certifies a completed event prefix.
 *
 * <p>Capture full snapshots only at registration-quiescent assembly/native lifecycle
 * boundaries, or from a handler when no concurrent registration/lifecycle changes
 * occur. This is not an atomic concurrent inventory, a registration lock, or a
 * seal of arbitrary producers. A native successful drain must be established
 * separately. In particular, a successful finish is not proof that derived events
 * behind the final iteration marker were processed.
 */
public final class SimStepEventsRuntimeInspection {
    private SimStepEventsRuntimeInspection() { }

    public static Snapshot inspect(EventsManager manager) {
        return inspect(manager, List.of());
    }

    public static Snapshot inspect(EventsManager manager, List<Class<? extends Event>> eventClasses) {
        SimStepParallelEventsManagerImpl actual = exact(manager);
        return actual.runtimeForInspection(List.copyOf(Objects.requireNonNull(eventClasses, "eventClasses")));
    }

    /** Actual Thread identity only; thread names and mutable configuration are not evidence. */
    public static boolean isCurrentWorkerThread(EventsManager manager) {
        return exact(manager).currentThreadIsWorkerForInspection();
    }

    private static SimStepParallelEventsManagerImpl exact(EventsManager manager) {
        Objects.requireNonNull(manager, "manager");
        if (manager.getClass() != SimStepParallelEventsManagerImpl.class) {
            throw new IllegalArgumentException("Expected the exact native SimStepParallelEventsManagerImpl");
        }
        return (SimStepParallelEventsManagerImpl) manager;
    }

    /** Descriptions contain no native runnable or Thread object. */
    public record WorkerDescription(int position, String runnableClass, String threadClass, String threadName) { }

    public static final class Snapshot {
        private final SimStepParallelEventsManagerImpl manager;
        private final int constructionWorkerCount;
        private final boolean parallelMode;
        private final boolean failed;
        private final Object[] runnableIdentities;
        private final Thread[] threadIdentities;
        private final OptionalInt materializedWorkerCount;
        private final boolean inspectionOnActualWorkerThread;
        private final List<WorkerDescription> workers;
        private final EventsManagerImpl.RuntimeInspectionSnapshot delegateRegistrations;
        private final List<EventsManagerImpl.RuntimeInspectionSnapshot> workerRegistrations;

        Snapshot(SimStepParallelEventsManagerImpl manager, int constructionWorkerCount,
                 boolean parallelMode, boolean failed, Object[] runnables, Thread[] threads,
                 EventsManagerImpl.RuntimeInspectionSnapshot delegateRegistrations,
                 List<EventsManagerImpl.RuntimeInspectionSnapshot> workerRegistrations) {
            this.manager = manager;
            this.constructionWorkerCount = constructionWorkerCount;
            this.parallelMode = parallelMode;
            this.failed = failed;
            runnableIdentities = runnables == null ? null : runnables.clone();
            threadIdentities = threads == null ? null : threads.clone();
            var descriptions = new ArrayList<WorkerDescription>();
            boolean onWorker = false;
            if (runnableIdentities != null && threadIdentities != null) {
                for (int i = 0; i < Math.min(runnableIdentities.length, threadIdentities.length); i++) {
                    if (runnableIdentities[i] == null || threadIdentities[i] == null) continue;
                    descriptions.add(new WorkerDescription(i, runnableIdentities[i].getClass().getName(),
                            threadIdentities[i].getClass().getName(), threadIdentities[i].getName()));
                    onWorker |= threadIdentities[i] == Thread.currentThread();
                }
            }
            materializedWorkerCount = runnableIdentities == null || threadIdentities == null
                    ? OptionalInt.empty() : OptionalInt.of(descriptions.size());
            workers = List.copyOf(descriptions);
            inspectionOnActualWorkerThread = onWorker;
            this.delegateRegistrations = Objects.requireNonNull(delegateRegistrations);
            this.workerRegistrations = List.copyOf(workerRegistrations);
        }

        /** Captured native constructor value, never re-read from mutable config. */
        public int constructionWorkerCount() { return constructionWorkerCount; }

        /**
         * Unknown before allocation; otherwise the count of actual runnable/Thread
         * pairs. Materialized does not mean currently alive, joined, or drained.
         */
        public OptionalInt materializedWorkerCount() { return materializedWorkerCount; }
        public boolean parallelMode() { return parallelMode; }
        public boolean failed() { return failed; }
        public boolean inspectionOnActualWorkerThread() { return inspectionOnActualWorkerThread; }
        public List<WorkerDescription> workers() { return workers; }
        public EventsManagerImpl.RuntimeInspectionSnapshot delegateRegistrations() { return delegateRegistrations; }
        public List<EventsManagerImpl.RuntimeInspectionSnapshot> workerRegistrations() { return workerRegistrations; }

        /** Same actual outer manager; permits the normal before-init to after-init transition. */
        public boolean isSameManagerAs(Snapshot other) {
            return other != null && manager == other.manager;
        }

        /**
         * Same actual manager and worker/runnable generation. Flags may have
         * changed (e.g. finish); inspect them separately. Re-initialization creates
         * new native worker identities and cannot pass this comparison.
         */
        public boolean sameRuntimeAs(Snapshot other) {
            return isSameManagerAs(other) && sameReferences(runnableIdentities, other.runnableIdentities)
                    && sameReferences(threadIdentities, other.threadIdentities);
        }

        /** Complete delegate and each actual worker registration order/identity, not descriptions alone. */
        public boolean sameRegistrationsAs(Snapshot other) {
            if (!isSameManagerAs(other) || workerRegistrations.size() != other.workerRegistrations.size()
                    || !delegateRegistrations.sameRegistrationsAs(other.delegateRegistrations)) return false;
            for (int i = 0; i < workerRegistrations.size(); i++) {
                if (!workerRegistrations.get(i).sameRegistrationsAs(other.workerRegistrations.get(i))) return false;
            }
            return true;
        }

        private static boolean sameReferences(Object[] left, Object[] right) {
            if (left == null || right == null) return left == right;
            if (left.length != right.length) return false;
            for (int i = 0; i < left.length; i++) if (left[i] != right[i]) return false;
            return true;
        }
    }
}
