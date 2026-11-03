package org.matsim.run.ismt.dynamic;

import org.matsim.api.core.v01.events.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Pure, declared-boundary buffer for one event-receiving worker and one owner.
 * This class has no QSim, native clock, handler-registration or barrier authority:
 * its caller must separately authenticate the actual native drains. Construct a
 * new instance for every execution epoch; there is deliberately no reset method.
 *
 * <p>The worker copies Event values outside the private lock, without reading a
 * simulation clock. The owner records a completed drain only when no copy remains
 * in flight. Coverage means that the copied event was available by that drain;
 * it is NOT the actual worker receipt time or evidence of when the event was
 * generated. Private receipt ordinals and unclosed suffix sizes are never present
 * in a policy Publication.</p>
 *
 * <p>At Before, only events at or before the second-most-recent drain time can be
 * published. This deliberately retains same-time After-listener events until the
 * following drain covers them. With the normal Before/drain alternation the first
 * two Before publications have an unknown watermark. Variable step lengths use
 * recorded drain times, not a subtraction of an assumed time step. Each delta
 * preserves worker receipt order among its eligible events; it does not sort by
 * occurrence time or event type. These rules cannot certify unregistered producers
 * or a producer that emits an old timestamp after a declared drain.</p>
 *
 * <p>Before may overlap a copy of the still-private suffix; drain may not. A late
 * event discovered after publication poisons the source, never revises a published
 * prefix, and must make the caller stop control. Cleanup suspends publication and
 * drops later tail receipts, including an in-flight copy, without interpreting
 * those receipts as policy input. Cleanup counts are audit-only and never claim a
 * complete terminal prefix. No events are emitted or mutable native objects kept.</p>
 */
public final class ParallelEventDrainBuffer {
    public record DrainCoverage(long sequence, double timeSeconds) {
        public DrainCoverage {
            require(sequence > 0, "Positive drain sequence required");
            finiteTime(timeSeconds);
        }
    }

    public record PublishedEvent(long closedSequence, double occurredAtSeconds,
                                 String eventClass, String eventType, Map<String, String> attributes,
                                 DrainCoverage firstCoveredByDrain) {
        public PublishedEvent {
            require(closedSequence > 0, "Positive closed sequence required");
            finiteTime(occurredAtSeconds);
            requireText(eventClass); requireText(eventType);
            attributes = Map.copyOf(attributes);
            Objects.requireNonNull(firstCoveredByDrain);
            require(occurredAtSeconds <= firstCoveredByDrain.timeSeconds(), "Occurrence after covering drain");
        }
    }

    /** Closed-prefix delta only; intentionally no pending count, receipt ordinal or suffix hash. */
    public record Publication(long viewSequence, double decisionTimeSeconds,
                              OptionalDouble closedThroughSeconds, List<PublishedEvent> newlyClosedEvents,
                              long closedEventCount) {
        public Publication {
            require(viewSequence > 0 && closedEventCount >= 0, "Invalid publication counters");
            finiteTime(decisionTimeSeconds);
            Objects.requireNonNull(closedThroughSeconds);
            newlyClosedEvents = List.copyOf(newlyClosedEvents);
            if (closedThroughSeconds.isPresent()) {
                finiteTime(closedThroughSeconds.getAsDouble());
                require(closedThroughSeconds.getAsDouble() < decisionTimeSeconds,
                        "Closed watermark must precede the decision");
            } else require(newlyClosedEvents.isEmpty() && closedEventCount == 0,
                    "Unknown watermark cannot expose events");
        }
    }

    /** Audit-only: obtainable at suspension, not through a policy publication. */
    public record CloseCounts(long receivedEvents, long closedEvents, long unpublishedEvents, boolean poisoned) { }

    /** Tail was deliberately not consumed as control input; terminal certification is always false. */
    public record CloseReceipt(long receivedEvents, long closedEvents, long unpublishedEvents,
                               long ignoredTailEvents, boolean poisoned, boolean terminalPrefixCertified) { }

    private record Raw(long privateOrdinal, double occurredAtSeconds, String eventClass,
                       String eventType, Map<String, String> attributes) { }
    private record Covered(Raw raw, DrainCoverage firstCoveredByDrain) { }

    private final Object lock = new Object();
    private final Thread ownerThread = Thread.currentThread();
    private final List<Raw> unsealed = new ArrayList<>();
    private final List<Covered> sealed = new ArrayList<>();
    private DrainCoverage newestDrain, precedingDrain;
    private Thread copyingThread;
    private boolean copying, poisoned, suspended, closed;
    private double lastBefore = Double.NEGATIVE_INFINITY;
    private double closedThrough = Double.NEGATIVE_INFINITY;
    private long receivedCount, closedCount, viewCount, ignoredTailCount;
    private CloseReceipt closeReceipt;

    /** Copies values on the receiving worker. Does not inspect a QSim clock. */
    public void receive(Event event) {
        boolean ownsCopy = false;
        try {
            synchronized (lock) {
                if (suspended || closed) { ignoreTail(); return; }
                requireLive();
                require(!copying, "Reentrant/concurrent event field copy");
                copying = true; copyingThread = Thread.currentThread(); ownsCopy = true;
            }
            // Custom getters can call arbitrary code. Never invoke them under lock.
            Objects.requireNonNull(event);
            double occurredAt = event.getTime();
            String eventClass = event.getClass().getName();
            String eventType = event.getEventType();
            Map<String, String> attributes = Map.copyOf(event.getAttributes());
            synchronized (lock) {
                // Cleanup may have begun while the getters ran. Do not interpret tail.
                if (suspended || closed) { ignoreTail(); return; }
                requireLive();
                finiteTime(occurredAt); requireText(eventType);
                require(occurredAt == Double.parseDouble(attributes.get(Event.ATTRIBUTE_TIME))
                        && eventType.equals(attributes.get(Event.ATTRIBUTE_TYPE)),
                        "Inconsistent copied event time/type");
                require(occurredAt > closedThrough, "Late event would revise an already published prefix");
                long next = Math.incrementExact(receivedCount);
                unsealed.add(new Raw(next, occurredAt, eventClass, eventType, attributes));
                receivedCount = next;
            }
        } catch (RuntimeException failure) {
            synchronized (lock) {
                // A copy already in progress at suspension is no longer policy input.
                if (ownsCopy && suspended) { ignoreTail(); return; }
                poisoned = true;
            }
            throw failure;
        } catch (Error failure) {
            synchronized (lock) { poisoned = true; }
            throw failure;
        } finally {
            if (ownsCopy) synchronized (lock) { copying = false; copyingThread = null; }
        }
    }

    /**
     * Records a caller-authenticated completed native drain. Sequences start at one
     * and are consecutive; times strictly increase. No partial seal on rejection.
     */
    public void recordDrain(long sequence, double timeSeconds) {
        try {
            synchronized (lock) {
                requireOwner(); requireLive();
                require(!copying, "A completed drain cannot overlap an event field copy");
                finiteTime(timeSeconds);
                long expected = newestDrain == null ? 1 : Math.incrementExact(newestDrain.sequence());
                require(sequence == expected, "Missing, repeated or out-of-order native drain sequence");
                require(newestDrain == null || timeSeconds > newestDrain.timeSeconds(),
                        "Repeated or backwards native drain time");
                require(timeSeconds >= lastBefore, "Native drain predates the latest Before callback");
                for (Raw raw : unsealed) require(raw.occurredAtSeconds() <= timeSeconds,
                        "Event is in the future at its first covering drain");
                DrainCoverage coverage = new DrainCoverage(sequence, timeSeconds);
                for (Raw raw : unsealed) sealed.add(new Covered(raw, coverage));
                unsealed.clear();
                precedingDrain = newestDrain; newestDrain = coverage;
            }
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
    }

    /** Owner-only publication of a conservative, already-covered past prefix. */
    public Publication publishBefore(double decisionTimeSeconds) {
        try {
            synchronized (lock) {
                requireOwner(); requireLive(); finiteTime(decisionTimeSeconds);
                require(Thread.currentThread() != copyingThread, "Reentrant Before callback from an event getter");
                require(decisionTimeSeconds > lastBefore, "Repeated or backwards Before callback");
                require(newestDrain == null || newestDrain.timeSeconds() < decisionTimeSeconds,
                        "Before must follow the most recent drain time");
                double cutoff = precedingDrain == null ? Double.NEGATIVE_INFINITY : precedingDrain.timeSeconds();
                require(cutoff >= closedThrough, "Closed watermark moved backwards");
                // An old receipt after the latest declared drain invalidates closure.
                // Inspecting for violation exposes no suffix value/count in the view.
                for (Raw raw : unsealed) require(raw.occurredAtSeconds() > cutoff,
                        "Uncovered late event lies inside the proposed closed prefix");
                List<PublishedEvent> delta = new ArrayList<>();
                List<Covered> retained = new ArrayList<>();
                long nextClosedCount = closedCount;
                for (Covered covered : sealed) {
                    Raw raw = covered.raw();
                    if (raw.occurredAtSeconds() <= cutoff) {
                        nextClosedCount = Math.incrementExact(nextClosedCount);
                        delta.add(new PublishedEvent(nextClosedCount, raw.occurredAtSeconds(), raw.eventClass(),
                                raw.eventType(), raw.attributes(), covered.firstCoveredByDrain()));
                    } else retained.add(covered);
                }
                long nextViewCount = Math.incrementExact(viewCount);
                Publication publication = new Publication(nextViewCount, decisionTimeSeconds,
                        cutoff == Double.NEGATIVE_INFINITY ? OptionalDouble.empty() : OptionalDouble.of(cutoff),
                        delta, nextClosedCount);
                sealed.clear(); sealed.addAll(retained);
                closedCount = nextClosedCount; viewCount = nextViewCount;
                lastBefore = decisionTimeSeconds; closedThrough = cutoff;
                return publication;
            }
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
    }

    /**
     * Owner-only cleanup boundary. May overlap a suffix copy and is idempotent.
     * Later receive calls are ignored, not checked against a closed watermark.
     * Also usable to quiesce a poisoned source. This does not detach any handler.
     */
    public CloseCounts suspendPublication() {
        try {
            synchronized (lock) {
                requireOwner(); suspended = true;
                return new CloseCounts(receivedCount, closedCount, receivedCount - closedCount, poisoned);
            }
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
    }

    /** Caller must separately finish native processing; this class only checks no copy is active. */
    public CloseReceipt closeAfterProcessing() {
        try {
            synchronized (lock) {
                requireOwner();
                if (closeReceipt != null) return closeReceipt;
                require(suspended, "Suspend publication before closing");
                require(!copying, "Cannot close while an event field copy remains in flight");
                closed = true;
                closeReceipt = new CloseReceipt(receivedCount, closedCount, receivedCount - closedCount,
                        ignoredTailCount, poisoned, false);
                return closeReceipt;
            }
        } catch (RuntimeException | Error failure) { poison(); throw failure; }
    }

    public boolean isPoisoned() { synchronized (lock) { return poisoned; } }

    private void ignoreTail() { if (!closed) ignoredTailCount = Math.incrementExact(ignoredTailCount); }
    private void requireOwner() { require(Thread.currentThread() == ownerThread, "Wrong buffer owner thread"); }
    private void requireLive() { require(!poisoned && !suspended && !closed, "Buffer is poisoned, suspended or closed"); }
    private void poison() { synchronized (lock) { poisoned = true; } }
    private static void finiteTime(double time) { require(Double.isFinite(time) && time >= 0, "Finite nonnegative time required"); }
    private static void requireText(String text) { require(text != null && !text.isBlank(), "Nonempty event identity required"); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
