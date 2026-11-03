package org.matsim.run.ismt.dynamic;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.SimStepEventsRuntimeInspection;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeCleanupEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimAfterSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeCleanupListener;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimRuntimeInspection;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngineI;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimRuntimeInspection;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * QSim-local native callback-prefix source, not a demand/state reducer.
 * Attach once before QSim.run and close after it (also detached at BeforeCleanup).
 * The next Before callback closes only the previous completed After time, because
 * later After listeners can still produce same-time events. The first view has no
 * closed watermark. Views contain NEWLY CLOSED events, not the whole event history;
 * consumers must fold them into their own separately verified immutable state.
 * Opt-in attachForPreparation is allowed only at the actual QSim preparation-start
 * hook. It also buffers preparation events on that hook's thread, with their actual
 * occurrence/receipt times even before the registered simulation start. It never
 * publishes a view before Initialized and a real completed step. Ordinary attach
 * continues to reject pre-initialization events. The native start callback remains
 * required; materialized runners/phase markers are checks, not a producer seal.
 *
 * <p>The original mode uses an exact synchronous manager, registered native engine class, active engine and
 * network identity, and construction-time one-runner counts are checked. Actual
 * runner count is separately checked when its list exists; a manual initialization
 * callback on a never-run QSim cannot turn an unavailable count into verified one.
 * Read-only role/handler inspection is not registration sealing or complete
 * producer certification. Extra asynchronous producers are unsupported.
 * Evidence therefore never certifies all producers or a complete causal policy.
 * A future controlled assembly must register and seal those remaining conditions.
 * This class does not change CausalObservationBuffer's declared-only evidence.</p>
 *
 * <p>The native-preparation mode also supports the exact sim-step-parallel manager
 * with one actual event worker and the registered positive QSim runner count. That
 * worker only copies events; it never reads the mutable simulation clock or calls
 * the policy. Existing QSim event-drain callbacks seal copied segments on the owner
 * thread. Before publishes only through the second-most-recent native drain time,
 * explicitly lagging two steps, to retain After-listener same-time receipts until
 * a subsequent drain. This path needs the native step-boundary hook; simply allowing
 * another manager class is insufficient. Runtime inventories are still not producer
 * seals. In particular, the existing manager can reject asynchronously derived,
 * backdated events behind a later native sentinel; this adapter does not repair or
 * silently reorder that native behavior. Parallel event timing is an explicit first
 * covering drain, never a fabricated actual worker receipt clock.</p>
 *
 * <p>Closed sequence means publication order, NOT global emission order. Each delta
 * preserves this Basic handler's receipt order among its eligible events; a delayed
 * timestamp can cause publication in a different order across successive deltas.
 * Pending receipt ordinals are not exposed or hashed: even their count could leak
 * information outside the closed prefix.
 * A typed handler can emit a derived event before this observer receives the outer
 * event. Native worker reception is allowed; closure/delivery is owner-thread-only.
 * The observer emits no events and calls its consumer outside its private lock.
 * Parallel cleanup first suspends publication, then detaches after successful native
 * finish. Failed cleanup is not upgraded to a finished or terminal-complete receipt.
 * No native Event, mutable attribute map, MATSim Id, plan or future registry is
 * exposed. Terminal/cleanup events are not published as a control-time prefix.</p>
 */
public final class NativeEventPrefixObserver implements BasicEventHandler,
        MobsimInitializedListener, MobsimBeforeSimStepListener, MobsimAfterSimStepListener,
        MobsimBeforeCleanupListener, AutoCloseable {
    private static final String NATIVE_ENGINE =
            "org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngineWithThreadpool";

    public record RuntimeEvidence(String managerClass, String primaryEngineClass,
                                  boolean actualManagerChecked, boolean registeredEngineClassChecked,
                                  boolean activeNetworkIdentityChecked, boolean singleRunnerConfigurationDeclared,
                                  boolean primaryEngineIdentityCertified, boolean singleRunnerConstructionChecked,
                                  boolean actualRunnerCountVerified, boolean allProducersCertified,
                                  int configuredRunnerCount, OptionalInt actualRunnerCount,
                                  boolean nativeEventDrainChecked, int closureLagSteps) { }

    public record ReceivedEvent(long closedSequence, double occurredAtSeconds, double receivedAtSeconds,
                                String eventClass, String eventType, Map<String, String> attributes,
                                boolean receivedOnControlThread,
                                Optional<ParallelEventDrainBuffer.DrainCoverage> firstCoveredByDrain) {
        /** Synchronous receipt clock; retained for existing callers and hash compatibility. */
        public ReceivedEvent(long sequence, double occurred, double received, String eventClass,
                             String eventType, Map<String, String> attributes, boolean onControlThread) {
            this(sequence, occurred, received, eventClass, eventType, attributes, onControlThread, Optional.empty());
        }
        public ReceivedEvent {
            if (closedSequence <= 0) throw new IllegalArgumentException("Positive closed sequence required");
            finiteTime(occurredAtSeconds);
            firstCoveredByDrain = Objects.requireNonNull(firstCoveredByDrain);
            if (firstCoveredByDrain.isEmpty()) {
                finiteTime(receivedAtSeconds);
                if (occurredAtSeconds > receivedAtSeconds) throw new IllegalArgumentException("Future received event");
            } else {
                require(Double.isNaN(receivedAtSeconds), "Parallel callback has no observed native receipt clock");
                require(occurredAtSeconds <= firstCoveredByDrain.orElseThrow().timeSeconds(), "Event is beyond its drain coverage");
            }
            Objects.requireNonNull(eventClass); Objects.requireNonNull(eventType);
            attributes = Map.copyOf(attributes);
        }
        /** Never substitute a barrier time for an actual worker receipt clock. */
        @Override public double receivedAtSeconds() {
            if (firstCoveredByDrain.isPresent()) throw new IllegalStateException("Parallel receipt clock unavailable; use drain coverage/availableBySeconds");
            return receivedAtSeconds;
        }
        public double availableBySeconds() {
            return firstCoveredByDrain.map(ParallelEventDrainBuffer.DrainCoverage::timeSeconds).orElse(receivedAtSeconds);
        }
    }

    /** Immutable delta plus cumulative closed-prefix count/hash; no pending-data accessor. */
    public static final class StepView {
        private final Object sourceIdentity;
        private final long viewSequence;
        private final int iteration;
        private final long epoch;
        private final double decisionTime;
        private final OptionalDouble closedThrough;
        private final List<ReceivedEvent> newlyClosed;
        private final long closedEventCount;
        private final String prefixHash;
        private final String precedingPrefixHash;
        private final RuntimeEvidence evidence;

        private StepView(Object sourceIdentity, long viewSequence, int iteration, long epoch, double decisionTime, OptionalDouble closedThrough,
                         List<ReceivedEvent> newlyClosed, long closedEventCount, String prefixHash,
                         String precedingPrefixHash, RuntimeEvidence evidence) {
            this.sourceIdentity = sourceIdentity; this.viewSequence = viewSequence;
            this.iteration = iteration; this.epoch = epoch; this.decisionTime = decisionTime;
            this.closedThrough = closedThrough; this.newlyClosed = List.copyOf(newlyClosed);
            this.closedEventCount = closedEventCount; this.prefixHash = prefixHash;
            this.precedingPrefixHash = precedingPrefixHash; this.evidence = evidence;
        }
        Object sourceIdentity() { return sourceIdentity; }
        /** Counts issued views, not pending events; starts at one even for an empty first view. */
        public long viewSequence() { return viewSequence; }
        public int iteration() { return iteration; }
        public long epoch() { return epoch; }
        public double decisionTimeSeconds() { return decisionTime; }
        public OptionalDouble closedThroughSeconds() { return closedThrough; }
        public List<ReceivedEvent> newlyClosedEvents() { return newlyClosed; }
        public long closedEventCount() { return closedEventCount; }
        public String closedPrefixSha256() { return prefixHash; }
        public String precedingClosedPrefixSha256() { return precedingPrefixHash; }
        public RuntimeEvidence evidence() { return evidence; }
    }

    public record CloseReceipt(long receivedEvents, long closedEvents, long unpublishedEvents,
                               boolean poisoned, boolean terminalPrefixCertified) { }

    private record PendingEvent(double occurredAtSeconds, double receivedAtSeconds, String eventClass,
                                String eventType, Map<String, String> attributes, boolean receivedOnControlThread) { }

    private final Object lock = new Object();
    private final Object sourceIdentity = new Object();
    private final QSim owner;
    private final EventsManager manager;
    private final QNetsimEngineI engine;
    private final Consumer<StepView> consumer;
    private final int iteration;
    private final long epoch;
    private final double expectedStart;
    private final Thread attachingThread;
    private final boolean preparationReception;
    private final boolean parallelReception;
    private final int expectedRunnerCount;
    private final ParallelEventDrainBuffer parallelBuffer;
    private long nativeDrains;
    private RuntimeEvidence evidence;
    private final List<PendingEvent> pending = new ArrayList<>();
    private Thread controlThread;
    private boolean initialized, copying, delivering, closed, poisoned;
    private double lastBefore = Double.NEGATIVE_INFINITY;
    private double lastAfter = Double.NEGATIVE_INFINITY;
    private double closedThrough = Double.NEGATIVE_INFINITY;
    private double lastReceiptClock = Double.NEGATIVE_INFINITY;
    private long receivedCount, closedCount, viewCount;
    private String prefixHash;
    private CloseReceipt closeReceipt;

    private NativeEventPrefixObserver(QSim owner, int iteration, long epoch, double expectedStart,
                                      Consumer<StepView> consumer, boolean preparationReception) {
        this.owner = Objects.requireNonNull(owner);
        this.consumer = Objects.requireNonNull(consumer);
        if (iteration < 0 || epoch <= 0) throw new IllegalArgumentException("Explicit iteration and positive epoch required");
        finiteTime(expectedStart);
        this.iteration = iteration; this.epoch = epoch; this.expectedStart = expectedStart;
        this.attachingThread = Thread.currentThread();
        this.preparationReception = preparationReception;
        if (preparationReception) require(QSimRuntimeInspection.isAtPreparationStart(owner),
                "Preparation reception requires the actual QSim preparation-start hook");
        this.manager = owner.getEventsManager();
        require(owner.getClass() == QSim.class, "Only the exact local QSim is supported");
        parallelReception = manager != null && manager.getClass().getName().equals(
                "org.matsim.core.events.SimStepParallelEventsManagerImpl");
        require(manager != null && (manager.getClass() == EventsManagerImpl.class || parallelReception && preparationReception),
                "Exact synchronous manager or native-preparation sim-step-parallel manager required; other wrappers/async managers are unsupported");
        expectedRunnerCount = parallelReception ? owner.getScenario().getConfig().qsim().getNumberOfThreads() : 1;
        require(expectedRunnerCount > 0, "Positive registered runner count required");
        parallelBuffer = parallelReception ? new ParallelEventDrainBuffer() : null;
        this.engine = owner.getChildInjector().getInstance(QNetsimEngineI.class);
        require(engine.getClass().getName().equals(NATIVE_ENGINE), "Unregistered primary network engine");
        requireRuntime();
        this.prefixHash = initialPrefixHash(iteration, epoch);
    }

    public static NativeEventPrefixObserver attach(QSim owner, int iteration, long epoch, double expectedStart,
                                                   Consumer<StepView> consumer) {
        NativeEventPrefixObserver observer = new NativeEventPrefixObserver(owner, iteration, epoch, expectedStart, consumer, false);
        observer.manager.addHandler(observer);
        owner.addQueueSimulationListeners(observer);
        return observer;
    }

    /**
     * Native preparation-start only. Buffer preparation receipts without synthesizing
     * Initialized, rewriting clocks, or publishing observations during preparation.
     * An ordinary/manual pre-initialization caller cannot enable this mode.
     */
    public static NativeEventPrefixObserver attachForPreparation(QSim owner, int iteration, long epoch, double expectedStart,
                                                                 Consumer<StepView> consumer) {
        NativeEventPrefixObserver observer = new NativeEventPrefixObserver(owner, iteration, epoch, expectedStart, consumer, true);
        observer.manager.addHandler(observer);
        owner.addQueueSimulationListeners(observer);
        return observer;
    }

    @Override public void notifyMobsimInitialized(MobsimInitializedEvent event) {
        try {
            require(event.getQueueSimulation() == owner, "Wrong QSim initialization");
            requireRuntime();
            synchronized (lock) {
                requireOpen();
                require(!initialized && !copying, "Observer cannot be initialized twice");
                require(owner.getSimTimer().getTimeOfDay() == expectedStart, "Unexpected native start time");
                if (preparationReception) {
                    require(Thread.currentThread() == attachingThread, "Initialization differs from the native preparation thread");
                    require(!QSimRuntimeInspection.isAtPreparationStart(owner) && !QSimRuntimeInspection.isAtPreparationCheck(owner)
                            && evidence.actualRunnerCountVerified(), "Initialization precedes completed native preparation");
                    require(lastReceiptClock <= expectedStart, "Native initialization clock precedes a preparation receipt");
                }
                controlThread = Thread.currentThread(); initialized = true;
            }
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
    }

    @Override public void handleEvent(Event event) {
        if (parallelReception) {
            synchronized (lock) { if (closed) return; }
            try {
                require(SimStepEventsRuntimeInspection.isCurrentWorkerThread(manager), "Parallel observation is not on the actual registered event worker");
                parallelBuffer.receive(event);
            } catch (RuntimeException | Error failure) { poison(); throw failure; }
            return;
        }
        try {
            double receivedAt = owner.getSimTimer().getTimeOfDay();
            finiteTime(receivedAt);
            final boolean preparing;
            synchronized (lock) {
                requireOpen();
                preparing = !initialized;
                require(!preparing || preparationReception, "Event before native initialization");
                if (preparing) require(Thread.currentThread() == attachingThread,
                        "Preparation event received outside the native preparation thread");
                require(!copying, "Reentrant/concurrent event field copy");
                require(receivedAt >= lastReceiptClock, "Native receipt clock moved backwards");
                copying = true;
            }
            // Field access can invoke custom event code: never hold the private lock here.
            double occurredAt = Objects.requireNonNull(event).getTime();
            String eventType = event.getEventType();
            Map<String, String> attributes = Map.copyOf(event.getAttributes());
            finiteTime(occurredAt);
            require((preparing || occurredAt >= expectedStart) && occurredAt <= receivedAt, "Event is before start or in the future");
            require(occurredAt == Double.parseDouble(attributes.get(Event.ATTRIBUTE_TIME))
                    && Objects.equals(eventType, attributes.get(Event.ATTRIBUTE_TYPE)), "Inconsistent copied event time/type");
            synchronized (lock) {
                requireOpen();
                require(occurredAt > closedThrough, "Late event would revise an already closed prefix");
                long nextReceivedCount = Math.incrementExact(receivedCount);
                pending.add(new PendingEvent(occurredAt, receivedAt, event.getClass().getName(),
                        eventType, attributes, Thread.currentThread() == controlThread));
                receivedCount = nextReceivedCount; lastReceiptClock = receivedAt; copying = false;
            }
        } catch (RuntimeException | Error failure) {
            synchronized (lock) { copying = false; poisoned = true; }
            throw failure;
        }
    }

    @Override public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent event) {
        if (parallelReception) { publishParallelBefore(event); return; }
        boolean openedDelivery = false;
        try {
            requireRuntime();
            double now = event.getSimulationTime();
            StepView view;
            synchronized (lock) {
                requireControl(event.getQueueSimulation(), now);
                require(!delivering && !copying && now > lastBefore, "Repeated/reentrant Before callback");
                String precedingHash = prefixHash;
                List<ReceivedEvent> newlyClosed = new ArrayList<>();
                if (Double.isFinite(lastBefore)) {
                    require(lastAfter == lastBefore, "Previous simulation step has no After marker");
                    long nextClosedCount = closedCount;
                    for (PendingEvent received : pending) {
                        if (received.occurredAtSeconds() <= lastAfter) {
                            nextClosedCount = Math.incrementExact(nextClosedCount);
                            newlyClosed.add(new ReceivedEvent(nextClosedCount, received.occurredAtSeconds(),
                                    received.receivedAtSeconds(), received.eventClass(), received.eventType(),
                                    received.attributes(), received.receivedOnControlThread()));
                        }
                    }
                    for (ReceivedEvent received : newlyClosed) prefixHash = extendHash(prefixHash, received);
                    closedCount = Math.addExact(closedCount, newlyClosed.size());
                    pending.removeIf(received -> received.occurredAtSeconds() <= lastAfter);
                    closedThrough = lastAfter;
                } else require(now == expectedStart, "First Before must be the initialized start");
                lastBefore = now; delivering = true; openedDelivery = true;
                viewCount = Math.incrementExact(viewCount);
                view = new StepView(sourceIdentity, viewCount, iteration, epoch, now, Double.isFinite(closedThrough)
                        ? OptionalDouble.of(closedThrough) : OptionalDouble.empty(), newlyClosed,
                        closedCount, prefixHash, precedingHash, evidence);
            }
            // The consumer receives only closed deltas, not this producer API or live pending data.
            consumer.accept(view);
            synchronized (lock) { requireOpen(); }
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
        finally { if (openedDelivery) synchronized (lock) { delivering = false; } }
    }

    @Override public void notifyMobsimAfterSimStep(MobsimAfterSimStepEvent event) {
        try {
            requireRuntime();
            synchronized (lock) {
                double now = event.getSimulationTime();
                requireControl(event.getQueueSimulation(), now);
                require(!delivering && !copying && lastBefore == now && now > lastAfter, "Invalid/repeated After callback");
                lastAfter = now; // No closure: other After listeners may still emit events for now.
            }
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
    }

    /** Called only by the optional native QSim boundary, never by the event worker. */
    public void afterNativeEventsProcessed(QSim qsim, double now) {
        if (!parallelReception) return;
        try {
            require(QSimRuntimeInspection.isAtEventsProcessed(qsim), "No actual native event-drain scope");
            requireRuntime();
            synchronized (lock) { requireControl(qsim, now); require(!delivering && lastBefore == now, "Drain is outside the current native step"); }
            parallelBuffer.recordDrain(Math.incrementExact(nativeDrains), now);
            nativeDrains++;
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
    }

    private void publishParallelBefore(MobsimBeforeSimStepEvent event) {
        boolean opened = false;
        try {
            requireRuntime();
            double now = event.getSimulationTime();
            synchronized (lock) {
                requireControl(event.getQueueSimulation(), now);
                require(QSimRuntimeInspection.isAtBeforeSimStep(owner), "No actual native Before scope");
                require(!delivering && now > lastBefore, "Repeated/reentrant Before callback");
                if (Double.isFinite(lastBefore)) require(lastAfter == lastBefore, "Previous native After marker missing");
                else require(now == expectedStart, "First Before differs from registered start");
                delivering = true; opened = true;
            }
            var publication = parallelBuffer.publishBefore(now);
            String preceding = prefixHash;
            var values = new ArrayList<ReceivedEvent>();
            for (var raw : publication.newlyClosedEvents()) {
                var value = new ReceivedEvent(raw.closedSequence(), raw.occurredAtSeconds(), Double.NaN,
                        raw.eventClass(), raw.eventType(), raw.attributes(), false, Optional.of(raw.firstCoveredByDrain()));
                values.add(value); prefixHash = extendHash(prefixHash, value);
            }
            closedCount = publication.closedEventCount();
            closedThrough = publication.closedThroughSeconds().orElse(Double.NEGATIVE_INFINITY);
            lastBefore = now; viewCount = publication.viewSequence();
            StepView view = new StepView(sourceIdentity, viewCount, iteration, epoch, now,
                    publication.closedThroughSeconds(), values, closedCount, prefixHash, preceding, evidence);
            consumer.accept(view);
            synchronized (lock) { requireOpen(); }
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
        finally { if (opened) synchronized (lock) { delivering = false; } }
    }

    /** Safe deregistration after successful native processing shutdown; no terminal completeness claim. */
    public void afterNativeEventsFinished(QSim qsim) {
        if (!parallelReception) return;
        require(qsim == owner && QSimRuntimeInspection.isAtEventsFinished(qsim), "No actual native event-finish scope");
        require(!SimStepEventsRuntimeInspection.inspect(manager).parallelMode(), "Event worker processing is not finished");
        close();
        parallelBuffer.closeAfterProcessing();
        manager.removeHandler(this);
    }

    @Override public void notifyMobsimBeforeCleanup(MobsimBeforeCleanupEvent event) {
        try {
            require(event.getQueueSimulation() == owner, "Wrong cleanup QSim");
            close(); // Native cleanup continues emitting events; do not label that tail complete.
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
    }

    @Override public void reset(int newIteration) {
        synchronized (lock) {
            if (newIteration != iteration || initialized || receivedCount != 0 || closed) {
                poisoned = true;
                throw new IllegalStateException("Create a new observer for every QSim/epoch; reset cannot reuse this one");
            }
        }
    }

    @Override public void close() {
        try {
            synchronized (lock) {
                if (closed) return;
                Thread expected = controlThread == null ? attachingThread : controlThread;
                require(Thread.currentThread() == expected && !copying && !delivering, "Close must be outside control delivery on its owner thread");
                if (parallelReception) {
                    var counts = parallelBuffer.suspendPublication();
                    closeReceipt = new CloseReceipt(counts.receivedEvents(), counts.closedEvents(), counts.unpublishedEvents(), poisoned || counts.poisoned(), false);
                } else closeReceipt = new CloseReceipt(receivedCount, closedCount, pending.size(), poisoned, false);
                closed = true;
                pending.clear();
            }
            // Removing a handler while a worker executes its handler array is unsafe.
            if (!parallelReception || !SimStepEventsRuntimeInspection.inspect(manager).parallelMode()) manager.removeHandler(this);
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
    }

    public CloseReceipt closeReceipt() {
        synchronized (lock) {
            require(closed, "Close receipt is unavailable during simulation");
            return closeReceipt;
        }
    }

    public boolean isPoisoned() { synchronized (lock) { return poisoned; } }

    private void requireRuntime() {
        require(owner.getEventsManager() == manager, "Event manager changed");
        if (parallelReception) {
            var events = SimStepEventsRuntimeInspection.inspect(manager);
            require(events.constructionWorkerCount() == 1 && !events.failed(), "Exactly one healthy native event worker required");
            require(events.materializedWorkerCount().isEmpty() || events.materializedWorkerCount().orElseThrow() == 1,
                    "Actual native event-worker count differs from one");
        } else require(manager.getClass() == EventsManagerImpl.class, "Event manager is not synchronous");
        require(owner.getScenario().getConfig().qsim().getNumberOfThreads() == expectedRunnerCount,
                "Configured native runner count changed");
        require(QSimRuntimeInspection.activeNetworkEngineIs(owner, engine),
                "Active QSim engine instance differs from registered primary engine");
        require(QSimRuntimeInspection.hasSingleNetworkEngineRegistration(owner, engine),
                "Primary engine must occur exactly once in registration and native step order");
        require(owner.getNetsimNetwork() == engine.getNetsimNetwork(), "Active QSim network differs from registered primary engine");
        var runners = QNetsimRuntimeInspection.inspect(engine);
        require(runners.constructionThreadCount() == expectedRunnerCount && runners.constructionRunnerCount() == expectedRunnerCount,
                "Native engine construction differs from registered runner count");
        require(runners.actualRunnerCount().isEmpty() || runners.actualRunnerCount().getAsInt() == expectedRunnerCount,
                "Actual initialized native runner count differs from registration");
        evidence = new RuntimeEvidence(manager.getClass().getName(), engine.getClass().getName(),
                true, true, true, expectedRunnerCount == 1, true, expectedRunnerCount == 1,
                runners.actualRunnerCount().isPresent(), false, expectedRunnerCount, runners.actualRunnerCount(),
                parallelReception && nativeDrains > 0, parallelReception ? 2 : 1);
    }

    public boolean usesParallelDrainBoundary() { return parallelReception; }

    private void requireControl(Object qsim, double now) {
        requireOpen(); finiteTime(now);
        require(initialized && Thread.currentThread() == controlThread && qsim == owner,
                "Wrong control thread, QSim or initialization");
        require(owner.getSimTimer().getTimeOfDay() == now, "Callback time differs from live native clock");
    }

    private void requireOpen() { require(!closed && !poisoned, "Observer is closed or poisoned"); }
    private void poison() { synchronized (lock) { poisoned = true; } }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
    private static void finiteTime(double time) { require(Double.isFinite(time) && time >= 0, "Finite nonnegative time required"); }

    // Assembly-only identity: never expose the observer or this token to a policy.
    Object sourceIdentity() { return sourceIdentity; }
    int sourceIteration() { return iteration; }
    long sourceEpoch() { return epoch; }

    static String initialPrefixHash(int iteration, long epoch) {
        return hash(List.of("native-callback-prefix-v1", Integer.toString(iteration), Long.toString(epoch)));
    }

    static String extendHash(String previous, ReceivedEvent event) {
        List<String> fields;
        if (event.firstCoveredByDrain().isPresent()) {
            var coverage = event.firstCoveredByDrain().orElseThrow();
            fields = new ArrayList<>(List.of(previous, "native-drain-coverage-v1", Long.toString(event.closedSequence()),
                    Double.toHexString(event.occurredAtSeconds()), Long.toString(coverage.sequence()),
                    Double.toHexString(coverage.timeSeconds()), event.eventClass(), event.eventType()));
        } else fields = new ArrayList<>(List.of(previous, Long.toString(event.closedSequence()),
                    Double.toHexString(event.occurredAtSeconds()), Double.toHexString(event.receivedAtSeconds()),
                    event.eventClass(), event.eventType()));
        new TreeMap<>(event.attributes()).forEach((key, value) -> { fields.add(key); fields.add(value); });
        // Thread identity is diagnostic, not part of the scientific callback-content hash.
        return hash(fields);
    }

    private static String hash(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
