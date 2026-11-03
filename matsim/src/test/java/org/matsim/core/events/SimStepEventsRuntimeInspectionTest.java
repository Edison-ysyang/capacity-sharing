package org.matsim.core.events;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.GenericEvent;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.PersonScoreEventHandler;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.EventsManagerConfigGroup;
import org.matsim.core.events.handler.BasicEventHandler;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native manager tests only, never QSim: the four lifecycle tests make five
 * init/finish attempts and four successful native sim-step drains in total.
 * Lifecycle calls are explicit test actions, not fabricated QSim barrier evidence.
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SimStepEventsRuntimeInspectionTest {
    @Test
    void constructionValueDoesNotFollowLaterMutableConfigurationAndInspectionDoesNotInitialize() {
        var config = new EventsManagerConfigGroup();
        config.setNumberOfThreads(2);
        var manager = new SimStepParallelEventsManagerImpl(config);
        var before = SimStepEventsRuntimeInspection.inspect(manager);
        config.setNumberOfThreads(1);
        var after = SimStepEventsRuntimeInspection.inspect(manager);
        assertEquals(2, after.constructionWorkerCount());
        assertTrue(after.materializedWorkerCount().isEmpty());
        assertTrue(after.workers().isEmpty());
        assertFalse(after.parallelMode());
        assertFalse(after.failed());
        assertFalse(after.inspectionOnActualWorkerThread());
        assertFalse(SimStepEventsRuntimeInspection.isCurrentWorkerThread(manager));
        assertTrue(before.sameRuntimeAs(after));
        assertTrue(before.sameRegistrationsAs(after));
        assertEquals(2, after.workerRegistrations().size(), "Dispatcher allocation is not worker initialization");
        assertEquals(1, SimStepEventsRuntimeInspection.inspect(
                new SimStepParallelEventsManagerImpl(new EventsManagerConfigGroup())).constructionWorkerCount());
    }

    @Test
    void unknownManagersSubclassesAndWrappersAreRejectedWithoutCallingTheirMethods() {
        var actual = new SimStepParallelEventsManagerImpl(1);
        AtomicInteger calls = new AtomicInteger();
        EventsManager proxy = (EventsManager) Proxy.newProxyInstance(EventsManager.class.getClassLoader(),
                new Class<?>[]{EventsManager.class}, (ignored, method, arguments) -> {
                    calls.incrementAndGet();
                    return method.invoke(actual, arguments);
                });
        assertThrows(IllegalArgumentException.class, () -> SimStepEventsRuntimeInspection.inspect(proxy));
        assertThrows(IllegalArgumentException.class, () -> SimStepEventsRuntimeInspection.isCurrentWorkerThread(proxy));
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> SimStepEventsRuntimeInspection.inspect(new EventsManagerImpl()));
        assertThrows(IllegalArgumentException.class, () -> SimStepEventsRuntimeInspection.inspect(new SynchronizedEventsManagerImpl(actual)));
        assertThrows(IllegalArgumentException.class, () -> SimStepEventsRuntimeInspection.inspect(new SimStepParallelEventsManagerImpl(1) { }));
        assertThrows(NullPointerException.class, () -> SimStepEventsRuntimeInspection.inspect(null));
        assertThrows(NullPointerException.class, () -> SimStepEventsRuntimeInspection.inspect(actual, null));
    }

    @Test
    void opaqueRegistrationSnapshotsRetainDelegateAndActualRoundRobinWorkerOrders() {
        var manager = new SimStepParallelEventsManagerImpl(2);
        var a = new CountingBasicHandler();
        var b = new CountingBasicHandler();
        var c = new CountingBasicHandler();
        manager.addHandler(a);
        manager.addHandler(b);
        manager.addHandler(c);
        var snapshot = SimStepEventsRuntimeInspection.inspect(manager, List.of(Event.class));
        assertEquals(3, snapshot.delegateRegistrations().dispatches().getFirst().handlers().size());
        assertEquals(2, snapshot.workerRegistrations().get(0).dispatches().getFirst().handlers().size());
        assertEquals(1, snapshot.workerRegistrations().get(1).dispatches().getFirst().handlers().size());
        assertTrue(snapshot.sameRegistrationsAs(SimStepEventsRuntimeInspection.inspect(manager)));
        assertEquals(0, a.count + b.count + c.count, "Inspection neither dispatches nor resets handlers");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.workerRegistrations().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.delegateRegistrations().registeredGroups().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.workers().clear());
        manager.removeHandler(b);
        var changed = SimStepEventsRuntimeInspection.inspect(manager, List.of(Event.class));
        assertTrue(snapshot.sameRuntimeAs(changed));
        assertFalse(snapshot.sameRegistrationsAs(changed));
        assertEquals(1, snapshot.workerRegistrations().get(1).dispatches().getFirst().handlers().size(),
                "Earlier snapshots remain detached after removal");
        assertEquals(0, changed.workerRegistrations().get(1).dispatches().getFirst().handlers().size());
    }

    @Test
    void equalDescriptionsCannotAuthenticateReplacementHandlersOrDifferentManagers() {
        var manager = new SimStepParallelEventsManagerImpl(1);
        var firstHandler = new CountingBasicHandler();
        manager.addHandler(firstHandler);
        var first = SimStepEventsRuntimeInspection.inspect(manager, List.of(Event.class));
        manager.removeHandler(firstHandler);
        manager.addHandler(new CountingBasicHandler());
        var second = SimStepEventsRuntimeInspection.inspect(manager, List.of(Event.class));
        assertEquals(first.delegateRegistrations().registeredGroups(), second.delegateRegistrations().registeredGroups());
        assertFalse(first.sameRegistrationsAs(second));
        assertTrue(first.sameRuntimeAs(second));
        var anotherManager = new SimStepParallelEventsManagerImpl(1);
        anotherManager.addHandler(new CountingBasicHandler());
        var another = SimStepEventsRuntimeInspection.inspect(anotherManager, List.of(Event.class));
        assertEquals(second.delegateRegistrations().registeredGroups(), another.delegateRegistrations().registeredGroups());
        assertFalse(second.isSameManagerAs(another));
        assertFalse(second.sameRuntimeAs(another));
        assertFalse(second.sameRegistrationsAs(another));
        assertFalse(second.sameRuntimeAs(null));
    }

    @Test
    void oneRealWorkerIsNotTheCallerAndReinitializationReplacesItsIdentity() {
        var manager = new SimStepParallelEventsManagerImpl(1);
        var received = new ArrayList<String>();
        var workerSnapshot = new AtomicReference<SimStepEventsRuntimeInspection.Snapshot>();
        var workerCheck = new AtomicBoolean();
        manager.addHandler((BasicEventHandler) event -> {
            received.add(event.getEventType());
            workerSnapshot.set(SimStepEventsRuntimeInspection.inspect(manager, List.of(Event.class)));
            workerCheck.set(SimStepEventsRuntimeInspection.isCurrentWorkerThread(manager));
        });
        var before = SimStepEventsRuntimeInspection.inspect(manager);
        manager.initProcessing();
        SimStepEventsRuntimeInspection.Snapshot firstRun;
        try {
            firstRun = SimStepEventsRuntimeInspection.inspect(manager);
            assertTrue(firstRun.parallelMode());
            assertEquals(1, firstRun.materializedWorkerCount().orElseThrow());
            assertEquals(1, firstRun.workers().size());
            assertFalse(firstRun.failed());
            assertFalse(firstRun.inspectionOnActualWorkerThread());
            assertTrue(before.isSameManagerAs(firstRun));
            assertFalse(before.sameRuntimeAs(firstRun));
            assertTrue(before.sameRegistrationsAs(firstRun));
            manager.processEvent(new GenericEvent("first", 0));
            manager.afterSimStep(0);
            assertEquals(List.of("first"), received);
            assertTrue(workerCheck.get());
            assertTrue(workerSnapshot.get().inspectionOnActualWorkerThread());
            assertTrue(firstRun.sameRuntimeAs(workerSnapshot.get()));
            assertTrue(firstRun.sameRegistrationsAs(workerSnapshot.get()));
            assertFalse(SimStepEventsRuntimeInspection.isCurrentWorkerThread(manager));

            // Like an After listener, this submits a same-time event after the native drain.
            // Only the next explicit drain, not a clock read or this inspection, establishes completion.
            manager.processEvent(new GenericEvent("late-same-time", 0));
            manager.afterSimStep(1);
            assertEquals(List.of("first", "late-same-time"), received);
        } finally {
            manager.finishProcessing();
        }
        var finished = SimStepEventsRuntimeInspection.inspect(manager);
        assertFalse(finished.parallelMode());
        assertFalse(finished.failed());
        assertEquals(1, finished.materializedWorkerCount().orElseThrow(), "Retained identities do not assert a still-live worker");
        assertTrue(firstRun.sameRuntimeAs(finished));

        manager.initProcessing();
        try {
            var secondRun = SimStepEventsRuntimeInspection.inspect(manager);
            assertTrue(firstRun.isSameManagerAs(secondRun));
            assertFalse(firstRun.sameRuntimeAs(secondRun));
            assertTrue(firstRun.sameRegistrationsAs(secondRun));
            manager.processEvent(new GenericEvent("second-run", 0));
            manager.afterSimStep(0);
            assertEquals(List.of("first", "late-same-time", "second-run"), received);
        } finally {
            manager.finishProcessing();
        }
    }

    @Test
    void nativeDrainIncludesReentrantQueuedScoreChainWithoutReplacingTheManager() {
        var manager = new SimStepParallelEventsManagerImpl(1);
        var received = new ArrayList<Class<?>>();
        AtomicInteger scores = new AtomicInteger();
        manager.addHandler((LinkEnterEventHandler) event -> manager.processEvent(
                new PersonScoreEvent(event.getTime(), Id.createPersonId("score-person"), -1, "first-derived")));
        manager.addHandler((PersonScoreEventHandler) event -> {
            if (scores.incrementAndGet() == 1) manager.processEvent(
                    new PersonScoreEvent(event.getTime(), event.getPersonId(), -2, "second-derived"));
        });
        manager.addHandler((BasicEventHandler) event -> received.add(event.getClass()));
        manager.initProcessing();
        try {
            manager.processEvent(new LinkEnterEvent(0, Id.createVehicleId("score-vehicle"), Id.createLinkId("score-link")));
            manager.afterSimStep(0);
            assertEquals(2, scores.get());
            assertEquals(List.of(LinkEnterEvent.class, PersonScoreEvent.class, PersonScoreEvent.class), received,
                    "One asynchronous worker completes source callbacks before dispatching queued derived events");
            assertFalse(SimStepEventsRuntimeInspection.inspect(manager).failed());
        } finally {
            manager.finishProcessing();
        }
    }

    @Test
    void failedNativeWorkerCannotBeMistakenForACompletedDrain() throws InterruptedException {
        var manager = new SimStepParallelEventsManagerImpl(1);
        manager.addHandler((BasicEventHandler) event -> { throw new IllegalStateException("synthetic worker failure"); });
        manager.initProcessing();
        manager.processEvent(new GenericEvent("failure", 0));
        // Wait for the real uncaught-exception handler, not just entry into the failing event handler.
        // This avoids entering an already-reset native barrier with no surviving worker.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!SimStepEventsRuntimeInspection.inspect(manager).failed() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(SimStepEventsRuntimeInspection.inspect(manager).failed());
        assertThrows(RuntimeException.class, () -> manager.afterSimStep(0));
        assertThrows(RuntimeException.class, manager::finishProcessing);
        var failed = SimStepEventsRuntimeInspection.inspect(manager);
        assertTrue(failed.failed());
        assertFalse(failed.parallelMode());
        assertFalse(failed.inspectionOnActualWorkerThread());
    }

    @Test
    void oldTimestampDerivedAfterTheNextNativeMarkerIsAnUnsupportedProducerNotADrainSuccess() throws Exception {
        var manager = new SimStepParallelEventsManagerImpl(1);
        var sourceEntered = new CountDownLatch(1);
        var permitDerivative = new CountDownLatch(1);
        var drainReturned = new CountDownLatch(1);
        var drainFailure = new AtomicReference<Throwable>();
        manager.addHandler((BasicEventHandler) event -> {
            if (!event.getEventType().equals("late-source")) return;
            sourceEntered.countDown();
            try {
                if (!permitDerivative.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out awaiting test gate");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            manager.processEvent(new PersonScoreEvent(event.getTime(), Id.createPersonId("late-score-person"), -1, "late-worker-derived"));
        });
        manager.initProcessing();
        Thread drainer = new Thread(() -> {
            try { manager.afterSimStep(1); }
            catch (Throwable failure) { drainFailure.set(failure); }
            finally { drainReturned.countDown(); }
        }, "native-late-score-counterexample-drainer");
        drainer.setDaemon(true);
        boolean finished = false;
        try {
            manager.processEvent(new GenericEvent("late-source", 0));
            assertTrue(sourceEntered.await(3, TimeUnit.SECONDS));
            // Test-only read of the existing queue: no queue mutation, injected marker,
            // manager subclass, production inspection bypass, or additional flush.
            Queue<?> actualInputQueue = actualWorkerInputQueue(manager);
            drainer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (actualInputQueue.stream().noneMatch(value -> value instanceof LastEventOfSimStep marker
                    && marker.getTime() == 1) && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(actualInputQueue.stream().anyMatch(value -> value instanceof LastEventOfSimStep marker
                    && marker.getTime() == 1), "The next-time marker must really be queued before the old-time Score is produced");
            permitDerivative.countDown();
            assertTrue(drainReturned.await(3, TimeUnit.SECONDS));
            assertNotNull(drainFailure.get());
            Throwable cause = drainFailure.get();
            while (cause.getCause() != null) cause = cause.getCause();
            assertTrue(String.valueOf(cause.getMessage()).contains("not ordered chronologically"), cause.toString());
            var rejected = SimStepEventsRuntimeInspection.inspect(manager);
            assertTrue(rejected.failed());
            assertEquals(1, rejected.materializedWorkerCount().orElseThrow());
            finished = true;
            assertThrows(RuntimeException.class, manager::finishProcessing);
            assertTrue(SimStepEventsRuntimeInspection.inspect(manager).failed());
            assertFalse(SimStepEventsRuntimeInspection.inspect(manager).parallelMode());
        } finally {
            permitDerivative.countDown();
            if (drainer.isAlive()) {
                drainer.interrupt();
                drainer.join(1_000);
            }
            // On an unexpected test failure, never block cleanup in another native barrier.
            // The manager's workers and this auxiliary thread are daemon threads.
            if (!finished && SimStepEventsRuntimeInspection.inspect(manager).failed()) {
                try { manager.finishProcessing(); } catch (RuntimeException expectedWorkerFailure) { }
            }
        }
    }

    private static Queue<?> actualWorkerInputQueue(SimStepParallelEventsManagerImpl manager) throws ReflectiveOperationException {
        Field runnables = SimStepParallelEventsManagerImpl.class.getDeclaredField("runnables");
        runnables.setAccessible(true);
        Object[] workers = (Object[]) runnables.get(manager);
        assertEquals(1, workers.length);
        Field input = workers[0].getClass().getDeclaredField("eventsQueue");
        input.setAccessible(true);
        return assertInstanceOf(Queue.class, input.get(workers[0]));
    }

    private static final class CountingBasicHandler implements BasicEventHandler {
        private int count;
        @Override public void handleEvent(Event event) { count++; }
    }
}
