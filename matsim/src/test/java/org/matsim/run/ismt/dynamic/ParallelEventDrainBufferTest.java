package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.events.Event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.ParallelEventDrainBuffer.*;

/** Pure declared-drain/value tests. No QSim run, native barrier or producer certification. */
class ParallelEventDrainBufferTest {
    private static class ValueEvent extends Event {
        String value;
        final String type;
        ValueEvent(double time, String value) { this(time, value, "drain-test"); }
        ValueEvent(double time, String value, String type) { super(time); this.value = value; this.type = type; }
        @Override public String getEventType() { return type; }
        @Override public Map<String, String> getAttributes() {
            var attributes = super.getAttributes(); attributes.put("value", value); return attributes;
        }
    }

    private static List<String> values(Publication publication) {
        return publication.newlyClosedEvents().stream().map(event -> event.attributes().get("value")).toList();
    }

    private static ParallelEventDrainBuffer throughFirstDrain() {
        var buffer = new ParallelEventDrainBuffer();
        buffer.publishBefore(0); buffer.recordDrain(1, 0);
        return buffer;
    }

    private static ParallelEventDrainBuffer throughSecondDrain() {
        var buffer = throughFirstDrain();
        buffer.publishBefore(1); buffer.recordDrain(2, 1);
        return buffer;
    }

    @Test void firstTwoBeforeViewsAreUnknownAndSecondDrainCoversLateSameTimeAfterEvents() {
        var buffer = new ParallelEventDrainBuffer();
        buffer.receive(new ValueEvent(0, "preparation"));
        var first = buffer.publishBefore(0);
        buffer.receive(new ValueEvent(0, "engine")); buffer.recordDrain(1, 0);
        buffer.receive(new ValueEvent(0, "late-after-listener"));
        var second = buffer.publishBefore(1);
        buffer.receive(new ValueEvent(1, "newer-step")); buffer.recordDrain(2, 1);
        var third = buffer.publishBefore(2);
        for (var unknown : List.of(first, second)) {
            assertTrue(unknown.closedThroughSeconds().isEmpty());
            assertTrue(unknown.newlyClosedEvents().isEmpty()); assertEquals(0, unknown.closedEventCount());
        }
        assertEquals(3, third.viewSequence());
        assertEquals(0, third.closedThroughSeconds().orElseThrow());
        assertEquals(List.of("preparation", "engine", "late-after-listener"), values(third));
        assertEquals(List.of(1L, 2L, 3L), third.newlyClosedEvents().stream().map(PublishedEvent::closedSequence).toList());
        assertEquals(new DrainCoverage(1, 0), third.newlyClosedEvents().getFirst().firstCoveredByDrain());
        assertEquals(new DrainCoverage(2, 1), third.newlyClosedEvents().getLast().firstCoveredByDrain());
        assertEquals(0, third.newlyClosedEvents().getLast().occurredAtSeconds());
        buffer.recordDrain(3, 2);
        var fourth = buffer.publishBefore(3);
        assertEquals(List.of("newer-step"), values(fourth)); assertEquals(4, fourth.closedEventCount());
        assertEquals(new DrainCoverage(2, 1), fourth.newlyClosedEvents().getFirst().firstCoveredByDrain(),
                "First drain coverage is retained, not replaced by publication time");
    }

    @Test void variableStepsUseRecordedSecondLastDrainTime() {
        var buffer = new ParallelEventDrainBuffer();
        buffer.publishBefore(10.25);
        buffer.receive(new ValueEvent(8, "prepare-before-start"));
        buffer.recordDrain(1, 10.25); buffer.publishBefore(10.75);
        buffer.receive(new ValueEvent(10.25, "after"));
        buffer.receive(new ValueEvent(10.75, "next")); buffer.recordDrain(2, 10.75);
        var publication = buffer.publishBefore(12.5);
        assertEquals(10.25, publication.closedThroughSeconds().orElseThrow());
        assertEquals(List.of("prepare-before-start", "after"), values(publication));
        buffer.recordDrain(3, 12.5);
        assertEquals(10.75, buffer.publishBefore(17).closedThroughSeconds().orElseThrow());
    }

    @Test void policyPublicationsDoNotDependOnDifferentUnclosedSuffixValuesOrCounts() {
        var left = new ParallelEventDrainBuffer(); var right = new ParallelEventDrainBuffer();
        for (var buffer : List.of(left, right)) {
            buffer.receive(new ValueEvent(0, "same-past")); buffer.publishBefore(0);
            buffer.recordDrain(1, 0); buffer.publishBefore(1); buffer.recordDrain(2, 1);
        }
        left.receive(new ValueEvent(1, "secret-a")); left.receive(new ValueEvent(1, "secret-b"));
        right.receive(new ValueEvent(2, "different-secret"));
        assertEquals(left.publishBefore(2), right.publishBefore(2));
    }

    @Test void publicationPreservesReceiptOrderRatherThanTimestampOrEventTypeOrder() {
        var buffer = new ParallelEventDrainBuffer();
        buffer.publishBefore(0);
        buffer.receive(new ValueEvent(0, "first", "z-type"));
        buffer.receive(new ValueEvent(0, "second", "a-type")); buffer.recordDrain(1, 0);
        buffer.publishBefore(1);
        buffer.receive(new ValueEvent(1, "later-timestamp"));
        buffer.receive(new ValueEvent(0, "delayed-older-timestamp")); buffer.recordDrain(2, 1);
        assertEquals(List.of("first", "second", "delayed-older-timestamp"), values(buffer.publishBefore(2)));
        buffer.recordDrain(3, 2);
        assertEquals(List.of("later-timestamp"), values(buffer.publishBefore(3)));
    }

    @Test void copiedFieldsAndRetainedPublicationsAreImmutable() {
        var buffer = new ParallelEventDrainBuffer();
        var event = new ValueEvent(0, "original"); buffer.receive(event);
        event.value = "mutated"; event.setTime(500);
        buffer.publishBefore(0); buffer.recordDrain(1, 0); buffer.publishBefore(1); buffer.recordDrain(2, 1);
        var publication = buffer.publishBefore(2);
        assertEquals(List.of("original"), values(publication));
        assertEquals(0, publication.newlyClosedEvents().getFirst().occurredAtSeconds());
        assertThrows(UnsupportedOperationException.class, () -> publication.newlyClosedEvents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> publication.newlyClosedEvents().getFirst().attributes().put("value", "edit"));
        buffer.receive(new ValueEvent(2, "next")); buffer.recordDrain(3, 2); buffer.publishBefore(3);
        assertEquals(List.of("original"), values(publication)); assertEquals(1, publication.closedEventCount());
    }

    @Test void emptyDrainsStillAdvancePastWatermarkWithoutPublishingSuffix() {
        var buffer = throughSecondDrain();
        var publication = buffer.publishBefore(2);
        assertEquals(0, publication.closedThroughSeconds().orElseThrow());
        assertEquals(0, publication.closedEventCount()); assertTrue(publication.newlyClosedEvents().isEmpty());
        buffer.recordDrain(3, 2);
        assertEquals(1, buffer.publishBefore(3).closedThroughSeconds().orElseThrow());
    }

    @Test void eventsInTheFutureAtDrainRejectTheWholeNewSeal() {
        var buffer = new ParallelEventDrainBuffer();
        buffer.receive(new ValueEvent(0, "valid-first")); buffer.receive(new ValueEvent(1, "future-tail"));
        assertThrows(IllegalStateException.class, () -> buffer.recordDrain(1, 0));
        assertTrue(buffer.isPoisoned());
        assertThrows(IllegalStateException.class, () -> buffer.publishBefore(2));
        assertEquals(new CloseCounts(2, 0, 2, true), buffer.suspendPublication());
    }

    @Test void invalidOccurrenceTimesPoisonWithoutRetainingTheEvent() {
        for (double time : new double[]{-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            var buffer = new ParallelEventDrainBuffer();
            assertThrows(IllegalStateException.class, () -> buffer.receive(new ValueEvent(time, "bad")));
            assertTrue(buffer.isPoisoned()); assertEquals(0, buffer.suspendPublication().receivedEvents());
        }
    }

    @Test void inconsistentAttributeTimeTypeOrMissingTimePoisons() {
        for (String field : List.of("time", "type", "missing-time")) {
            var buffer = new ParallelEventDrainBuffer();
            var event = new ValueEvent(0, "bad") {
                @Override public Map<String, String> getAttributes() {
                    var attributes = super.getAttributes();
                    if (field.equals("missing-time")) attributes.remove("time");
                    else attributes.put(field, field.equals("time") ? "1.0" : "different-type");
                    return attributes;
                }
            };
            assertThrows(RuntimeException.class, () -> buffer.receive(event)); assertTrue(buffer.isPoisoned());
        }
    }

    @Test void nullEventAndBlankTypePoison() {
        var first = new ParallelEventDrainBuffer();
        assertThrows(NullPointerException.class, () -> first.receive(null)); assertTrue(first.isPoisoned());
        var second = new ParallelEventDrainBuffer();
        assertThrows(IllegalStateException.class, () -> second.receive(new ValueEvent(0, "bad", " ")));
        assertTrue(second.isPoisoned());
    }

    @Test void firstDrainMustStartAtOneAndSubsequentSequencesCannotRepeatSkipOrRollback() {
        for (long sequence : new long[]{-1, 0, 2}) {
            var buffer = new ParallelEventDrainBuffer();
            assertThrows(IllegalStateException.class, () -> buffer.recordDrain(sequence, 0));
            assertTrue(buffer.isPoisoned());
        }
        for (long sequence : new long[]{0, 1, 3}) {
            var buffer = throughFirstDrain();
            assertThrows(IllegalStateException.class, () -> buffer.recordDrain(sequence, 1));
            assertTrue(buffer.isPoisoned());
        }
    }

    @Test void drainTimesCannotRepeatRollbackBeNonfiniteOrPredateLastBefore() {
        for (double time : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var buffer = throughFirstDrain();
            assertThrows(IllegalStateException.class, () -> buffer.recordDrain(2, time));
            assertTrue(buffer.isPoisoned());
        }
        var buffer = throughFirstDrain(); buffer.publishBefore(2);
        assertThrows(IllegalStateException.class, () -> buffer.recordDrain(2, 1));
    }

    @Test void beforeCannotRepeatRollbackUseNonfiniteTimeOrEqualLatestDrain() {
        for (double time : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var buffer = throughFirstDrain();
            assertThrows(IllegalStateException.class, () -> buffer.publishBefore(time)); assertTrue(buffer.isPoisoned());
        }
        var buffer = new ParallelEventDrainBuffer(); buffer.recordDrain(1, 5);
        assertThrows(IllegalStateException.class, () -> buffer.publishBefore(5));
    }

    @Test void lateReceiptCannotReviseAnAlreadyPublishedPrefix() {
        var buffer = throughSecondDrain(); var publication = buffer.publishBefore(2);
        assertThrows(IllegalStateException.class, () -> buffer.receive(new ValueEvent(0, "late")));
        assertTrue(buffer.isPoisoned()); assertEquals(0, publication.closedEventCount());
    }

    @Test void oldUnsealedReceiptAfterLatestDrainBlocksProposedClosure() {
        var buffer = throughSecondDrain(); buffer.receive(new ValueEvent(0, "not-covered-by-second-drain"));
        assertThrows(IllegalStateException.class, () -> buffer.publishBefore(2));
        assertTrue(buffer.isPoisoned()); assertEquals(0, buffer.suspendPublication().closedEvents());
    }

    @Test void workerMayCopyButCannotRecordDrainsOrPublish() throws Exception {
        var buffer = new ParallelEventDrainBuffer();
        assertNull(onWorker(() -> buffer.receive(new ValueEvent(0, "worker"))));
        buffer.recordDrain(1, 0); buffer.recordDrain(2, 1);
        assertEquals(List.of("worker"), values(buffer.publishBefore(2)));
        var wrongDrain = new ParallelEventDrainBuffer();
        assertInstanceOf(IllegalStateException.class, onWorker(() -> wrongDrain.recordDrain(1, 0)));
        assertTrue(wrongDrain.isPoisoned());
        var wrongBefore = new ParallelEventDrainBuffer();
        assertInstanceOf(IllegalStateException.class, onWorker(() -> wrongBefore.publishBefore(0)));
        assertTrue(wrongBefore.isPoisoned());
    }

    @Test void recursiveGetterReceptionPoisonsEvenIfGetterSwallowsInnerFailure() {
        var buffer = new ParallelEventDrainBuffer();
        var event = new ValueEvent(0, "outer") {
            @Override public Map<String, String> getAttributes() {
                assertThrows(IllegalStateException.class, () -> buffer.receive(new ValueEvent(0, "inner")));
                return super.getAttributes();
            }
        };
        assertThrows(IllegalStateException.class, () -> buffer.receive(event));
        assertTrue(buffer.isPoisoned()); assertEquals(0, buffer.suspendPublication().receivedEvents());
    }

    @Test void getterFailurePoisonsAndDoesNotLeaveCopyingLatched() {
        var buffer = new ParallelEventDrainBuffer();
        var event = new ValueEvent(0, "bad") {
            @Override public Map<String, String> getAttributes() { throw new IllegalArgumentException("getter failure"); }
        };
        assertThrows(IllegalArgumentException.class, () -> buffer.receive(event));
        buffer.suspendPublication(); assertTrue(buffer.closeAfterProcessing().poisoned());
    }

    @Test void ownerGetterCannotReenterPublicationAsIfItWereAConcurrentWorkerCopy() {
        var buffer = throughSecondDrain();
        var event = new ValueEvent(1, "owner-getter") {
            @Override public Map<String, String> getAttributes() {
                assertThrows(IllegalStateException.class, () -> buffer.publishBefore(2));
                return super.getAttributes();
            }
        };
        assertThrows(IllegalStateException.class, () -> buffer.receive(event));
        assertTrue(buffer.isPoisoned()); assertEquals(0, buffer.suspendPublication().closedEvents());
    }

    @Test void simultaneousCopiesAreRejectedRatherThanAssignedAnInventedOrder() throws Exception {
        var buffer = new ParallelEventDrainBuffer();
        try (var copy = new BlockedCopy(buffer, 0)) {
            assertThrows(IllegalStateException.class, () -> buffer.receive(new ValueEvent(0, "other-worker")));
            copy.finish(); assertInstanceOf(IllegalStateException.class, copy.failure.get());
            assertTrue(buffer.isPoisoned());
        }
    }

    @Test void drainCannotClaimCompletionWhileAGetterStillCopies() throws Exception {
        var buffer = new ParallelEventDrainBuffer();
        try (var copy = new BlockedCopy(buffer, 0)) {
            assertThrows(IllegalStateException.class, () -> buffer.recordDrain(1, 0));
            copy.finish(); assertInstanceOf(IllegalStateException.class, copy.failure.get());
            assertTrue(buffer.isPoisoned());
        }
    }

    @Test void beforeCanPublishCoveredPastWhileUnclosedSuffixIsStillCopying() throws Exception {
        var buffer = throughSecondDrain();
        try (var copy = new BlockedCopy(buffer, 1)) {
            var publication = buffer.publishBefore(2);
            assertEquals(0, publication.closedThroughSeconds().orElseThrow());
            assertTrue(publication.newlyClosedEvents().isEmpty());
            copy.finish(); assertNull(copy.failure.get()); assertFalse(buffer.isPoisoned());
        }
        buffer.recordDrain(3, 2);
        assertEquals(List.of("blocked-copy"), values(buffer.publishBefore(3)));
    }

    @Test void lateInFlightCopyPoisonsAfterPublicationWithoutRevisingThatView() throws Exception {
        var buffer = throughSecondDrain();
        try (var copy = new BlockedCopy(buffer, 0)) {
            var publication = buffer.publishBefore(2);
            copy.finish(); assertInstanceOf(IllegalStateException.class, copy.failure.get());
            assertTrue(buffer.isPoisoned()); assertTrue(publication.newlyClosedEvents().isEmpty());
        }
    }

    @Test void cleanupMaySuspendDuringCopyAndDropsLateOrInvalidTailWithoutPolicyPublication() throws Exception {
        var buffer = throughSecondDrain(); var publication = buffer.publishBefore(2);
        try (var copy = new BlockedCopy(buffer, 0)) {
            assertEquals(new CloseCounts(0, 0, 0, false), buffer.suspendPublication());
            copy.finish(); assertNull(copy.failure.get());
        }
        buffer.receive(new ValueEvent(Double.NaN, "ignored-tail")); buffer.receive(null);
        var receipt = buffer.closeAfterProcessing();
        assertEquals(3, receipt.ignoredTailEvents()); assertEquals(0, receipt.receivedEvents());
        assertFalse(receipt.poisoned()); assertFalse(receipt.terminalPrefixCertified());
        assertTrue(publication.newlyClosedEvents().isEmpty());
        assertSame(receipt, buffer.closeAfterProcessing());
        buffer.receive(null); assertSame(receipt, buffer.closeAfterProcessing());
    }

    @Test void suspendedInFlightRuntimeGetterFailureIsAnIgnoredTailNotWorkerFailure() throws Exception {
        var buffer = new ParallelEventDrainBuffer();
        try (var copy = new BlockedCopy(buffer, 0, true)) {
            buffer.suspendPublication(); copy.finish(); assertNull(copy.failure.get());
        }
        var receipt = buffer.closeAfterProcessing();
        assertEquals(1, receipt.ignoredTailEvents()); assertFalse(receipt.poisoned());
    }

    @Test void suspensionDoesNotClaimUnpublishedCoveredOrUnsealedEventsWereClosed() {
        var buffer = new ParallelEventDrainBuffer();
        buffer.receive(new ValueEvent(0, "covered")); buffer.recordDrain(1, 0);
        buffer.receive(new ValueEvent(0, "uncovered"));
        assertEquals(new CloseCounts(2, 0, 2, false), buffer.suspendPublication());
        var receipt = buffer.closeAfterProcessing();
        assertEquals(2, receipt.unpublishedEvents()); assertFalse(receipt.terminalPrefixCertified());
    }

    @Test void ownerOnlyCleanupAndPrematureCloseFailClosed() throws Exception {
        var wrongSuspend = new ParallelEventDrainBuffer();
        assertInstanceOf(IllegalStateException.class, onWorker(wrongSuspend::suspendPublication));
        assertTrue(wrongSuspend.isPoisoned());
        var premature = new ParallelEventDrainBuffer();
        assertThrows(IllegalStateException.class, premature::closeAfterProcessing); assertTrue(premature.isPoisoned());
        var inFlight = new ParallelEventDrainBuffer();
        try (var copy = new BlockedCopy(inFlight, 0)) {
            inFlight.suspendPublication();
            assertThrows(IllegalStateException.class, inFlight::closeAfterProcessing);
            copy.finish(); assertNull(copy.failure.get());
        }
        assertTrue(inFlight.closeAfterProcessing().poisoned());
    }

    @Test void suspendedBufferCannotPublishOrSealEvenThoughTailReceptionIsHarmless() {
        var publicationBuffer = new ParallelEventDrainBuffer(); publicationBuffer.suspendPublication();
        assertThrows(IllegalStateException.class, () -> publicationBuffer.publishBefore(1));
        publicationBuffer.receive(null);
        assertTrue(publicationBuffer.closeAfterProcessing().poisoned());
        var drainBuffer = new ParallelEventDrainBuffer(); drainBuffer.suspendPublication();
        assertThrows(IllegalStateException.class, () -> drainBuffer.recordDrain(1, 0));
        assertTrue(drainBuffer.isPoisoned());
    }

    @Test void independentBufferInstancesDoNotCarryCountsOrWatermarksAcrossEpochs() {
        var old = throughSecondDrain(); old.publishBefore(2); old.suspendPublication(); old.closeAfterProcessing();
        var next = new ParallelEventDrainBuffer(); next.receive(new ValueEvent(0, "new-epoch"));
        assertEquals(1, next.publishBefore(0).viewSequence());
        next.recordDrain(1, 0); next.publishBefore(1); next.recordDrain(2, 1);
        var publication = next.publishBefore(2);
        assertEquals(1, publication.closedEventCount()); assertEquals(List.of("new-epoch"), values(publication));
    }

    private static Throwable onWorker(Runnable action) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { action.run(); } catch (Throwable problem) { failure.set(problem); } },
                "declared-drain-test-worker");
        thread.setDaemon(true); thread.start(); thread.join(5000);
        assertFalse(thread.isAlive(), "Bounded worker did not terminate"); return failure.get();
    }

    private static final class BlockedCopy implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread worker;
        BlockedCopy(ParallelEventDrainBuffer buffer, double time) throws Exception { this(buffer, time, false); }
        BlockedCopy(ParallelEventDrainBuffer buffer, double time, boolean failAfterRelease) throws Exception {
            var event = new ValueEvent(time, "blocked-copy") {
                @Override public Map<String, String> getAttributes() {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("copy release timeout"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    if (failAfterRelease) throw new IllegalArgumentException("tail getter failure");
                    return super.getAttributes();
                }
            };
            worker = new Thread(() -> { try { buffer.receive(event); } catch (Throwable problem) { failure.set(problem); } },
                    "declared-drain-blocked-copy");
            worker.setDaemon(true); worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Worker did not enter the getter");
        }
        void finish() throws Exception { release.countDown(); worker.join(5000); assertFalse(worker.isAlive()); }
        @Override public void close() throws Exception { finish(); }
    }
}
