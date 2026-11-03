package org.matsim.run.ismt.dynamic;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.CausalObservationBuffer.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;

class CausalObservationBufferTest {
    private static final TripKey TRIP = new TripKey("line", "branch", "departure");

    private CausalObservationBuffer buffer() {
        CausalObservationBuffer buffer = new CausalObservationBuffer();
        buffer.beginIteration(20, 1, 0);
        return buffer;
    }

    private Fact fact(String id, double occurred, double available) {
        return new Fact(1, id, occurred, available,
                new QueuePresence("person-" + id, "stop", PersonKind.PASSENGER, true));
    }

    @Test void operationsRequireAnExplicitIteration() {
        CausalObservationBuffer buffer = new CausalObservationBuffer();
        assertThrows(IllegalStateException.class, () -> buffer.record(1, 0, List.of()));
        assertThrows(IllegalStateException.class, () -> buffer.declareCompletedThrough(1, 0, 0));
        assertThrows(IllegalStateException.class, () -> buffer.snapshotAt(1, 0));
    }

    @Test void initializationRejectsInvalidEpochIterationAndTime() {
        CausalObservationBuffer buffer = new CausalObservationBuffer();
        assertThrows(IllegalArgumentException.class, () -> buffer.beginIteration(-1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> buffer.beginIteration(20, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> buffer.beginIteration(20, -1, 0));
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> buffer.beginIteration(20, 1, invalid));
        }
        buffer.beginIteration(20, 1, 0);
        assertEquals(20, buffer.snapshotAt(1, 0).iteration());
    }

    @Test void receivingFactsDoesNotImplyACompletedPrefix() {
        CausalObservationBuffer buffer = buffer();
        buffer.record(1, 10, List.of(fact("waiting", 10, 10)));
        var snapshot = buffer.snapshotAt(1, 10);
        assertTrue(snapshot.watermark().isEmpty());
        assertTrue(snapshot.facts().isEmpty());
        assertEquals(Evidence.DECLARED_PREFIX_ONLY, snapshot.evidence());
        assertFalse(snapshot.synchronizationVerified());
    }

    @Test void sameSecondBoundaryIsInclusiveOnlyAfterCompletion() {
        CausalObservationBuffer buffer = buffer();
        Fact first = fact("a", 10, 10);
        Fact second = fact("b", 10, 10);
        buffer.record(1, 10, List.of(first, second));
        assertTrue(buffer.snapshotAt(1, 10).facts().isEmpty());
        buffer.declareCompletedThrough(1, 10, 10);
        var snapshot = buffer.snapshotAt(1, 10);
        assertEquals(List.of(first, second), snapshot.facts());
        assertEquals(new DeclaredWatermark(1, 10, 10), snapshot.watermark().orElseThrow());
        assertFalse(snapshot.synchronizationVerified(), "A completion declaration is not a tested source barrier");
    }

    @Test void completedZeroPrefixMayContainZeroTimeEvents() {
        CausalObservationBuffer buffer = buffer();
        Fact zero = fact("zero", -0.0, -0.0);
        buffer.record(1, 0, List.of(zero));
        buffer.declareCompletedThrough(1, -0.0, -0.0);
        assertEquals(List.of(zero), buffer.snapshotAt(1, -0.0).facts());
        assertEquals(0.0, zero.occurredAtSeconds());
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 0, List.of(fact("late-zero", 0, 0))));
    }

    @Test void factsAfterTheClosedPrefixAreNotPublished() {
        CausalObservationBuffer buffer = buffer();
        Fact early = fact("early", 1, 2);
        Fact pending = fact("pending", 9, 9);
        buffer.record(1, 10, List.of(early, pending));
        buffer.declareCompletedThrough(1, 5, 10);
        var first = buffer.snapshotAt(1, 10);
        assertEquals(List.of(early), first.facts());
        buffer.declareCompletedThrough(1, 9, 11);
        assertEquals(List.of(early, pending), buffer.snapshotAt(1, 11).facts());
        assertEquals(List.of(early), first.facts(), "An issued strategy snapshot cannot acquire a pending suffix");
    }

    @Test void differentUnclosedSuffixesHaveTheSameStrategyPrefix() {
        CausalObservationBuffer left = buffer();
        CausalObservationBuffer right = buffer();
        Fact common = fact("common", 1, 2);
        left.record(1, 10, List.of(common, fact("unclosed-left", 9, 10)));
        right.record(1, 10, List.of(common, new Fact(1, "unclosed-right", 8, 9,
                new ReleasedOrder("different-demand", "x", "y", 7, 1_000))));
        left.declareCompletedThrough(1, 5, 10);
        right.declareCompletedThrough(1, 5, 10);
        var leftPrefix = left.snapshotAt(1, 10);
        var rightPrefix = right.snapshotAt(1, 10);
        assertEquals(leftPrefix.facts(), rightPrefix.facts());
        assertEquals(leftPrefix.watermark(), rightPrefix.watermark());
        assertEquals(leftPrefix.dispatchObservations(new Version(1, 0), 10),
                rightPrefix.dispatchObservations(new Version(1, 0), 10));
    }

    @Test void laterActualSuffixCannotChangeAnEarlierSnapshot() {
        CausalObservationBuffer left = buffer();
        CausalObservationBuffer right = buffer();
        Fact common = fact("common", 1, 1);
        for (CausalObservationBuffer buffer : List.of(left, right)) {
            buffer.record(1, 5, List.of(common));
            buffer.declareCompletedThrough(1, 5, 5);
        }
        var beforeLeft = left.snapshotAt(1, 5);
        var beforeRight = right.snapshotAt(1, 5);
        left.record(1, 20, List.of(fact("different-future-left", 20, 20)));
        right.record(1, 20, List.of(fact("different-future-right", 19, 20)));
        left.declareCompletedThrough(1, 20, 20);
        right.declareCompletedThrough(1, 20, 20);
        assertEquals(List.of(common), beforeLeft.facts());
        assertEquals(beforeLeft.facts(), beforeRight.facts());
        assertNotEquals(left.snapshotAt(1, 20).facts(), right.snapshotAt(1, 20).facts());
        assertThrows(IllegalArgumentException.class, () -> left.snapshotAt(1, 5));
    }

    @Test void futureOccurrenceOrAvailabilityIsRejectedWithoutAdvancingTheClock() {
        CausalObservationBuffer buffer = buffer();
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 5, List.of(fact("future", 6, 6))));
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 5, List.of(fact("not-available", 1, 6))));
        assertTrue(buffer.snapshotAt(1, 0).facts().isEmpty());
        buffer.record(1, 6, List.of(fact("not-available", 1, 6)));
        buffer.declareCompletedThrough(1, 1, 6);
        assertThrows(IllegalArgumentException.class, () -> buffer.snapshotAt(1, 5));
        var snapshot = buffer.snapshotAt(1, 6);
        assertEquals(6, snapshot.facts().getFirst().availableAtSeconds());
        assertThrows(IllegalArgumentException.class, () -> snapshot.dispatchObservations(new Version(1, 0), 5));
    }

    @Test void lateFactsAtOrBeforeTheClosedBoundaryAreRejected() {
        CausalObservationBuffer buffer = buffer();
        buffer.declareCompletedThrough(1, 10, 10);
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 20, List.of(fact("old", 9, 20))));
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 20, List.of(fact("boundary", 10, 20))));
        assertTrue(buffer.snapshotAt(1, 10).facts().isEmpty());
        Fact next = fact("next", Math.nextUp(10.0), 20);
        buffer.record(1, 20, List.of(next));
        buffer.declareCompletedThrough(1, 20, 20);
        assertEquals(List.of(next), buffer.snapshotAt(1, 20).facts());
    }

    @Test void watermarkAndOperationTimesCannotMoveBackwards() {
        CausalObservationBuffer buffer = buffer();
        buffer.declareCompletedThrough(1, 5, 10);
        assertThrows(IllegalArgumentException.class, () -> buffer.declareCompletedThrough(1, 4, 11));
        assertThrows(IllegalArgumentException.class, () -> buffer.declareCompletedThrough(1, 5, 9));
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 9, List.of()));
        assertThrows(IllegalArgumentException.class, () -> buffer.snapshotAt(1, 9));
        assertEquals(new DeclaredWatermark(1, 5, 10), buffer.snapshotAt(1, 10).watermark().orElseThrow());
        buffer.declareCompletedThrough(1, 5, 12);
        assertEquals(new DeclaredWatermark(1, 5, 12), buffer.snapshotAt(1, 12).watermark().orElseThrow());
    }

    @Test void snapshotTimeAlsoParticipatesInTheMonotoneClock() {
        CausalObservationBuffer buffer = buffer();
        buffer.snapshotAt(1, 30);
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 29, List.of()));
        assertThrows(IllegalArgumentException.class, () -> buffer.declareCompletedThrough(1, 10, 29));
        assertThrows(IllegalArgumentException.class, () -> buffer.snapshotAt(1, 29));
        buffer.record(1, 30, List.of(fact("available-now", 20, 30)));
        buffer.declareCompletedThrough(1, 20, 30);
        assertEquals(1, buffer.snapshotAt(1, 30).facts().size());
    }

    @Test void invalidWatermarkCannotPublishFactsOrAdvanceClock() {
        CausalObservationBuffer buffer = buffer();
        buffer.record(1, 5, List.of(fact("five", 5, 5)));
        assertThrows(IllegalArgumentException.class, () -> buffer.declareCompletedThrough(1, 6, 5));
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> buffer.declareCompletedThrough(1, invalid, 5));
            assertThrows(IllegalArgumentException.class, () -> buffer.declareCompletedThrough(1, 5, invalid));
        }
        assertTrue(buffer.snapshotAt(1, 5).watermark().isEmpty());
        assertTrue(buffer.snapshotAt(1, 5).facts().isEmpty());
    }

    @Test void duplicateIdsAcrossCallsAndWithinABatchAreRejectedAtomically() {
        CausalObservationBuffer buffer = buffer();
        Fact first = fact("same", 1, 1);
        buffer.record(1, 1, List.of(first));
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 2, List.of(fact("same", 2, 2))));
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 2,
                List.of(fact("new", 2, 2), fact("new", 2, 2))));
        buffer.declareCompletedThrough(1, 1, 1);
        assertEquals(List.of(first), buffer.snapshotAt(1, 1).facts());
        buffer.record(1, 2, List.of(fact("new", 2, 2)));
        buffer.declareCompletedThrough(1, 2, 2);
        assertEquals(2, buffer.snapshotAt(1, 2).facts().size());
    }

    @Test void invalidLastFactDoesNotPartiallyRecordFirstFact() {
        CausalObservationBuffer buffer = buffer();
        Fact first = fact("valid", 1, 1);
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 2,
                List.of(first, fact("future", 3, 3))));
        assertTrue(buffer.snapshotAt(1, 0).facts().isEmpty());
        buffer.record(1, 1, List.of(first));
        buffer.declareCompletedThrough(1, 1, 1);
        assertEquals(List.of(first), buffer.snapshotAt(1, 1).facts());
    }

    @Test void resetClearsBothPublishedAndUnclosedFactsAndAllowsClockRestart() {
        CausalObservationBuffer buffer = buffer();
        Fact old = fact("reused-event-id", 1, 1);
        buffer.record(1, 10, List.of(old, fact("hidden-old", 9, 9)));
        buffer.declareCompletedThrough(1, 5, 10);
        var oldSnapshot = buffer.snapshotAt(1, 10);
        buffer.beginIteration(20, 2, 0);
        var reset = buffer.snapshotAt(2, 0);
        assertEquals(20, reset.iteration());
        assertEquals(2, reset.epoch());
        assertTrue(reset.watermark().isEmpty());
        assertTrue(reset.facts().isEmpty());
        assertEquals(List.of(old), oldSnapshot.facts());
        Fact next = new Fact(2, old.factId(), 0, 0, old.observation());
        buffer.record(2, 0, List.of(next));
        buffer.declareCompletedThrough(2, 0, 0);
        assertEquals(List.of(next), buffer.snapshotAt(2, 0).facts());
    }

    @Test void oldEpochOperationsAndOldFactsCannotContaminateNewIteration() {
        CausalObservationBuffer buffer = buffer();
        buffer.beginIteration(21, 2, 0);
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 100, List.of()));
        assertThrows(IllegalArgumentException.class, () -> buffer.declareCompletedThrough(1, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> buffer.snapshotAt(1, 100));
        assertThrows(IllegalArgumentException.class, () -> buffer.record(2, 100, List.of(fact("old", 1, 1))));
        assertTrue(buffer.snapshotAt(2, 0).facts().isEmpty());
        buffer.declareCompletedThrough(2, 100, 100);
        assertTrue(buffer.snapshotAt(2, 100).facts().isEmpty());
    }

    @Test void resetRejectsReusedOrOlderEpochWithoutClearingState() {
        CausalObservationBuffer buffer = buffer();
        Fact first = fact("first", 1, 1);
        buffer.record(1, 1, List.of(first));
        buffer.declareCompletedThrough(1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> buffer.beginIteration(21, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> buffer.beginIteration(21, 0, 0));
        assertEquals(List.of(first), buffer.snapshotAt(1, 1).facts());
    }

    @Test void nonzeroIterationStartRejectsEarlierFactsAndWatermarks() {
        CausalObservationBuffer buffer = new CausalObservationBuffer();
        buffer.beginIteration(20, 1, 100);
        assertThrows(IllegalArgumentException.class, () -> buffer.record(1, 100, List.of(fact("old", 99, 100))));
        assertThrows(IllegalArgumentException.class, () -> buffer.declareCompletedThrough(1, 99, 100));
        Fact boundary = fact("boundary", 100, 100);
        buffer.record(1, 100, List.of(boundary));
        buffer.declareCompletedThrough(1, 100, 100);
        assertEquals(List.of(boundary), buffer.snapshotAt(1, 100).facts());
    }

    @Test void inputAndSnapshotCollectionsCannotBeMutatedIntoTheBuffer() {
        CausalObservationBuffer buffer = buffer();
        Fact first = fact("first", 1, 1);
        var supplied = new ArrayList<>(List.of(first));
        buffer.record(1, 1, supplied);
        supplied.clear();
        supplied.add(fact("injected", 1, 1));
        buffer.declareCompletedThrough(1, 1, 1);
        var snapshot = buffer.snapshotAt(1, 1);
        assertEquals(List.of(first), snapshot.facts());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.facts().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.dispatchObservations(new Version(1, 0), 1).clear());
        assertTrue(Observation.class.isSealed());
        for (Class<?> payload : Observation.class.getPermittedSubclasses()) assertTrue(payload.isRecord());
        for (var constructor : CausalObservationBuffer.Snapshot.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
    }

    @Test void knownOrderTrainAndQueueFactsRetainExplicitImmutableIdentities() {
        CausalObservationBuffer buffer = buffer();
        var order = new ReleasedOrder("order", "origin", "destination", 3, 1_000);
        var train = new TrainMovement(TRIP, "fixed-vehicle", MovementKind.ARRIVED_AT_CONTROL_POINT, Optional.of("merge"));
        var queue = new QueuePresence("freight-unit", "origin", PersonKind.FREIGHT, true);
        List<Fact> facts = List.of(new Fact(1, "release", 1, 2, order),
                new Fact(1, "arrival", 2, 2, train), new Fact(1, "waiting", 2, 2, queue));
        buffer.record(1, 2, facts);
        buffer.declareCompletedThrough(1, 2, 2);
        assertEquals(facts, buffer.snapshotAt(1, 2).facts());
        assertEquals(1_000, order.deadlineSeconds(), "Known contractual deadline is not a future realized fact");
        assertEquals(TRIP, train.trip());
    }

    @Test void payloadRegistrationRejectsMalformedIdentityAndNonfiniteData() {
        assertThrows(IllegalArgumentException.class, () -> new QueuePresence(" ", "s", PersonKind.PASSENGER, true));
        assertThrows(IllegalArgumentException.class, () -> new QueuePresence("p", "", PersonKind.PASSENGER, true));
        assertThrows(NullPointerException.class, () -> new QueuePresence("p", "s", null, true));
        assertThrows(NullPointerException.class, () -> new TrainMovement(null, "v", MovementKind.STARTED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new TrainMovement(TRIP, "", MovementKind.STARTED, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainMovement(TRIP, "v", MovementKind.ARRIVED_AT_CONTROL_POINT, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainMovement(TRIP, "v", MovementKind.DEPARTED_CONTROL_POINT, Optional.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> new ReleasedOrder("order", "o", "d", 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new ReleasedOrder("", "o", "d", 1, 100));
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ReleasedOrder("order", "o", "d", 1, invalid));
        }
    }

    @Test void factConstructionRejectsMalformedTimeIdentityAndAvailabilityOrder() {
        Observation observation = new QueuePresence("p", "s", PersonKind.PASSENGER, true);
        assertThrows(IllegalArgumentException.class, () -> new Fact(0, "id", 0, 0, observation));
        assertThrows(IllegalArgumentException.class, () -> new Fact(1, " ", 0, 0, observation));
        assertThrows(IllegalArgumentException.class, () -> new Fact(1, "id", 2, 1, observation));
        assertThrows(NullPointerException.class, () -> new Fact(1, "id", 0, 0, null));
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Fact(1, "id", invalid, 0, observation));
            assertThrows(IllegalArgumentException.class, () -> new Fact(1, "id", 0, invalid, observation));
        }
    }

    @Test void invalidRecordAndSnapshotTimesDoNotAdvanceOrChangeState() {
        CausalObservationBuffer buffer = buffer();
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> buffer.record(1, invalid, List.of()));
            assertThrows(IllegalArgumentException.class, () -> buffer.snapshotAt(1, invalid));
        }
        assertTrue(buffer.snapshotAt(1, 0).facts().isEmpty());
        assertThrows(NullPointerException.class, () -> buffer.record(1, 10, null));
        var containsNull = new ArrayList<Fact>();
        containsNull.add(fact("valid", 1, 1));
        containsNull.add(null);
        assertThrows(NullPointerException.class, () -> buffer.record(1, 10, containsNull));
        assertTrue(buffer.snapshotAt(1, 0).facts().isEmpty());
    }

    @Test void snapshotAuditReferencesAdaptToLedgerWithoutCrossEpochTransfer() {
        DispatchCommitmentState ledger = new DispatchCommitmentState(
                List.of(new ScheduledTrip(TRIP, "vehicle", 100)), new Limits(10, 10, 5));
        var initial = ledger.beginIteration(20, 0, true);
        CausalObservationBuffer buffer = new CausalObservationBuffer();
        buffer.beginIteration(20, initial.version().epoch(), 0);
        Fact waiting = fact("waiting", 10, 11);
        buffer.record(1, 11, List.of(waiting));
        buffer.declareCompletedThrough(1, 10, 11);
        var snapshot = buffer.snapshotAt(1, 12);
        var references = snapshot.dispatchObservations(initial.version(), 12);
        var decision = ledger.commit(new Batch(initial.version(), 12, references, Map.of(TRIP, 105.0)));
        assertEquals(List.of(new ObservedFact("waiting", 10, 11)), decision.observations());
        assertEquals(105.0, ledger.snapshot().commitments().get(TRIP));
        assertThrows(IllegalArgumentException.class, () -> snapshot.dispatchObservations(new Version(1, -1), 12));
        var reset = ledger.beginIteration(21, 0, true);
        assertThrows(IllegalArgumentException.class, () -> snapshot.dispatchObservations(reset.version(), 12));
        assertEquals(100.0, reset.commitments().get(TRIP));
    }

    @Test void forecastIsNotAnObservationAndRequiresItsOwnAvailabilityCheck() {
        var prediction = new ArrivalPrediction(1, "prediction", TRIP, "merge", 10, 12, 100, "explicit-method");
        assertFalse(Observation.class.isAssignableFrom(ArrivalPrediction.class));
        assertThrows(IllegalArgumentException.class, () -> prediction.requireAvailableAt(1, 11));
        assertThrows(IllegalArgumentException.class, () -> prediction.requireAvailableAt(2, 12));
        assertDoesNotThrow(() -> prediction.requireAvailableAt(1, 12));
        CausalObservationBuffer buffer = buffer();
        buffer.declareCompletedThrough(1, 12, 12);
        assertTrue(buffer.snapshotAt(1, 12).facts().isEmpty(), "A separate forecast is never implicitly published as a fact");
    }

    @Test void predictionRejectsMalformedTimesAndDoesNotClaimFutureAvailability() {
        assertThrows(IllegalArgumentException.class,
                () -> new ArrivalPrediction(1, "p", TRIP, "s", 10, 9, 100, "method"));
        assertThrows(IllegalArgumentException.class,
                () -> new ArrivalPrediction(1, "p", TRIP, "s", 10, 10, 9, "method"));
        assertThrows(IllegalArgumentException.class,
                () -> new ArrivalPrediction(0, "p", TRIP, "s", 10, 10, 100, "method"));
        assertThrows(IllegalArgumentException.class,
                () -> new ArrivalPrediction(1, "p", TRIP, "s", 10, 10, 100, " "));
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ArrivalPrediction(1, "p", TRIP, "s", invalid, 10, 100, "method"));
            assertThrows(IllegalArgumentException.class,
                    () -> new ArrivalPrediction(1, "p", TRIP, "s", 10, invalid, 100, "method"));
            assertThrows(IllegalArgumentException.class,
                    () -> new ArrivalPrediction(1, "p", TRIP, "s", 10, 10, invalid, "method"));
        }
    }
}
