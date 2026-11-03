package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;

class DispatchCommitmentStateTest {
    private static final TripKey A = new TripKey("line", "branch-a", "departure-1");
    private static final TripKey B = new TripKey("line", "branch-b", "departure-1");
    private static final TripKey UNKNOWN = new TripKey("line", "branch-c", "departure-1");

    private DispatchCommitmentState state() {
        return state(new Limits(100, 100, 30), true);
    }

    private DispatchCommitmentState state(Limits limits, boolean enabled) {
        DispatchCommitmentState state = new DispatchCommitmentState(List.of(
                new ScheduledTrip(A, "fixed-vehicle-a", 1_000),
                new ScheduledTrip(B, "fixed-vehicle-b", 2_000)), limits);
        state.beginIteration(20, 0, enabled);
        return state;
    }

    private Batch batch(DispatchCommitmentState state, double at, Map<TripKey, Double> changes) {
        return new Batch(state.snapshot().version(), at, List.of(), changes);
    }

    private void rejected(Failure failure, DispatchCommitmentState state, Batch candidate) {
        Snapshot before = state.snapshot();
        List<DecisionRecord> history = state.history();
        RejectedBatch error = assertThrows(RejectedBatch.class, () -> state.commit(candidate));
        assertEquals(failure, error.failure());
        assertEquals(before, state.snapshot(), "Rejection must not advance clock or partially modify the state");
        assertEquals(history, state.history());
    }

    @Test void tripKeyIncludesLineAndRouteNotJustDeparture() {
        DispatchCommitmentState state = state();
        assertEquals(2, state.baseline().size());
        DecisionRecord result = state.commit(batch(state, 100, Map.of(A, 950.0, B, 2_050.0)));
        assertEquals(List.of(A, B), result.changes().stream().map(CommitmentChange::key).toList());
        assertEquals("fixed-vehicle-a", result.changes().getFirst().vehicleId());
        assertEquals(1_000, result.changes().getFirst().baselineDepartureSeconds());
        assertEquals(1_000, result.changes().getFirst().previousDepartureSeconds());
        assertEquals(950, result.changes().getFirst().committedDepartureSeconds());
        assertEquals(100, result.decisionTimeSeconds());
        assertEquals(result.resultingVersion(), state.snapshot().version());
    }

    @Test void sameRouteAndDepartureOnDifferentLinesRemainDistinct() {
        TripKey otherLine = new TripKey("other-line", A.route(), A.departure());
        DispatchCommitmentState state = new DispatchCommitmentState(List.of(
                new ScheduledTrip(A, "vehicle-a", 1_000),
                new ScheduledTrip(otherLine, "vehicle-other", 1_000)), new Limits(100, 100, 30));
        state.beginIteration(20, 0, true);
        state.commit(batch(state, 100, Map.of(A, 950.0)));
        assertEquals(Map.of(A, 950.0, otherLine, 1_000.0), state.snapshot().commitments());
        assertEquals("vehicle-other", state.baseline().get(otherLine).vehicleId());
    }

    @Test void disablingAtResetRemovesPriorAdjustmentLocksAndStarts() {
        DispatchCommitmentState state = state();
        state.commit(batch(state, 100, Map.of(A, 950.0)));
        state.recordStarted(A, 950, 950);
        state.advanceTo(1_970);
        Batch stale = batch(state, 1_970, Map.of());
        assertEquals(2, state.snapshot().lockedTrips().size());
        Snapshot reset = state.beginIteration(21, 0, false);
        DispatchCommitmentState freshDisabled = state(new Limits(100, 100, 30), false);
        assertEquals(freshDisabled.snapshot().commitments(), reset.commitments());
        assertEquals(freshDisabled.snapshot().lockedTrips(), reset.lockedTrips());
        assertEquals(freshDisabled.snapshot().startedAtSeconds(), reset.startedAtSeconds());
        assertFalse(reset.enabled());
        assertEquals(0, reset.nowSeconds());
        rejected(Failure.STALE_VERSION, state, stale);
        rejected(Failure.CONTROLLER_DISABLED, state, batch(state, 100, Map.of(A, 950.0)));
        assertTrue(state.commit(batch(state, 100, reset.commitments())).changes().isEmpty());
    }

    @Test void resetRestoresBaselineAndChangesEpochEvenForSameIterationNumber() {
        DispatchCommitmentState state = state();
        state.commit(batch(state, 100, Map.of(A, 950.0)));
        Batch stale = batch(state, 200, Map.of(A, 960.0));
        state.recordStarted(A, 950, 950);
        Snapshot reset = state.beginIteration(20, 0, true);
        assertEquals(Map.of(A, 1_000.0, B, 2_000.0), reset.commitments());
        assertTrue(reset.startedAtSeconds().isEmpty());
        assertTrue(reset.lockedTrips().isEmpty());
        assertEquals(0, reset.nowSeconds());
        assertEquals(0, reset.version().revision());
        assertEquals(2, reset.version().epoch());
        rejected(Failure.STALE_VERSION, state, stale);
        assertEquals(1, state.history().size(), "Previous iteration audit must remain available");
        assertEquals(1, state.history().getFirst().sourceVersion().epoch());
    }

    @Test void repeatedSmallAdjustmentsCannotExceedCumulativeBaselineLimit() {
        DispatchCommitmentState state = state();
        state.commit(batch(state, 10, Map.of(A, 1_060.0)));
        state.commit(batch(state, 20, Map.of(A, 1_100.0)));
        rejected(Failure.DELAY_LIMIT, state, batch(state, 30, Map.of(A, 1_101.0)));
        state.commit(batch(state, 30, Map.of(A, 900.0)));
        rejected(Failure.ADVANCE_LIMIT, state, batch(state, 40, Map.of(A, 899.0)));
        assertEquals(1_100, state.history().get(2).changes().getFirst().previousDepartureSeconds());
        assertEquals(1_000, state.baseline().get(A).baselineDepartureSeconds());
    }

    @Test void exactDisplacementLimitsAreAllowed() {
        DispatchCommitmentState state = state();
        state.commit(batch(state, 100, Map.of(A, 900.0, B, 2_100.0)));
        assertEquals(Map.of(A, 900.0, B, 2_100.0), state.snapshot().commitments());
    }

    @Test void locksUseCurrentCommitmentRatherThanOriginalBaseline() {
        DispatchCommitmentState state = state();
        state.commit(batch(state, 100, Map.of(A, 900.0)));
        state.advanceTo(870);
        assertTrue(state.snapshot().lockedTrips().contains(A));
        rejected(Failure.LOCKED_TRIP, state, batch(state, 870, Map.of(A, 950.0)));
    }

    @Test void delayedCommitmentDoesNotLockAtObsoleteBaselineTime() {
        DispatchCommitmentState state = state();
        state.commit(batch(state, 100, Map.of(A, 1_100.0)));
        state.advanceTo(970);
        assertFalse(state.snapshot().lockedTrips().contains(A));
        state.commit(batch(state, 970, Map.of(A, 1_090.0)));
        assertEquals(1_090, state.snapshot().commitments().get(A));
    }

    @Test void lockIsAppliedAtDecisionTimeEvenWithoutAdvanceToCall() {
        DispatchCommitmentState state = state();
        rejected(Failure.LOCKED_TRIP, state, batch(state, 970, Map.of(A, 1_050.0)));
        // A rejected attempt leaves the clock alone; an explicit clock advance then latches the lock.
        state.advanceTo(970);
        state.advanceTo(1_100);
        rejected(Failure.LOCKED_TRIP, state, batch(state, 1_100, Map.of(A, 1_101.0)));
    }

    @Test void candidateCannotEnterLockWindowIncludingExactBoundary() {
        DispatchCommitmentState state = state();
        rejected(Failure.CANDIDATE_INSIDE_LOCK_WINDOW, state, batch(state, 900, Map.of(A, 930.0)));
        state.commit(batch(state, 900, Map.of(A, Math.nextUp(930.0))));
        assertFalse(state.snapshot().lockedTrips().contains(A));
        state.advanceTo(901);
        assertTrue(state.snapshot().lockedTrips().contains(A));
    }

    @Test void zeroLockWindowStillCannotMoveDepartureToNow() {
        DispatchCommitmentState state = state(new Limits(200, 100, 0), true);
        rejected(Failure.CANDIDATE_INSIDE_LOCK_WINDOW, state, batch(state, 900, Map.of(A, 900.0)));
        state.commit(batch(state, 900, Map.of(A, 901.0)));
    }

    @Test void startedTripCannotBeMovedEvenIfOriginalCommitmentIsStillFarAway() {
        DispatchCommitmentState state = state();
        state.recordStarted(A, 100, 100);
        rejected(Failure.ALREADY_STARTED, state, batch(state, 100, Map.of(A, 1_010.0)));
        assertEquals(100, state.snapshot().startedAtSeconds().get(A));
        DecisionRecord identity = state.commit(batch(state, 110, Map.of(A, 1_000.0)));
        assertTrue(identity.changes().isEmpty());
    }

    @Test void duplicateStartDoesNotChangeClockOrRecordedStart() {
        DispatchCommitmentState state = state();
        state.recordStarted(A, 100, 100);
        Snapshot before = state.snapshot();
        RejectedBatch error = assertThrows(RejectedBatch.class, () -> state.recordStarted(A, 101, 101));
        assertEquals(Failure.ALREADY_STARTED, error.failure());
        assertEquals(before, state.snapshot());
    }

    @Test void startObservationCannotBeFutureAndLateObservationIsAllowed() {
        DispatchCommitmentState state = state();
        Snapshot before = state.snapshot();
        RejectedBatch error = assertThrows(RejectedBatch.class, () -> state.recordStarted(A, 101, 100));
        assertEquals(Failure.FUTURE_OBSERVATION, error.failure());
        assertEquals(before, state.snapshot());
        state.advanceTo(110);
        state.recordStarted(A, 100, 120);
        assertEquals(100, state.snapshot().startedAtSeconds().get(A));
        assertEquals(120, state.snapshot().nowSeconds());
    }

    @Test void unknownTripRejectsWholeBatchAndCannotCreateService() {
        DispatchCommitmentState state = state();
        rejected(Failure.UNKNOWN_TRIP, state, batch(state, 100, Map.of(A, 950.0, UNKNOWN, 1_500.0)));
        assertEquals(2, state.snapshot().commitments().size());
        Snapshot before = state.snapshot();
        assertThrows(RejectedBatch.class, () -> state.recordStarted(UNKNOWN, 10, 10));
        assertEquals(before, state.snapshot());
    }

    @Test void invalidSecondProposalDoesNotPartiallyApplyFirstProposal() {
        DispatchCommitmentState state = state();
        Map<TripKey, Double> candidates = new LinkedHashMap<>();
        candidates.put(A, 950.0);
        candidates.put(B, 2_101.0);
        rejected(Failure.DELAY_LIMIT, state, batch(state, 100, candidates));
        assertEquals(1_000, state.snapshot().commitments().get(A));
    }

    @Test void disabledControllerAcceptsIdentityButRejectsAnyChange() {
        DispatchCommitmentState state = state(new Limits(100, 100, 30), false);
        Map<TripKey, Double> before = state.snapshot().commitments();
        rejected(Failure.CONTROLLER_DISABLED, state, batch(state, 100, Map.of(A, 950.0)));
        DecisionRecord record = state.commit(batch(state, 100, before));
        assertTrue(record.changes().isEmpty());
        assertEquals(before, state.snapshot().commitments());
    }

    @Test void zeroAdjustmentBoundsNeverChangeCommitments() {
        DispatchCommitmentState state = state(new Limits(0, 0, 30), true);
        Map<TripKey, Double> before = state.snapshot().commitments();
        rejected(Failure.ADVANCE_LIMIT, state, batch(state, 100, Map.of(A, 999.0)));
        rejected(Failure.DELAY_LIMIT, state, batch(state, 100, Map.of(A, 1_001.0)));
        assertTrue(state.commit(batch(state, 100, before)).changes().isEmpty());
        assertEquals(before, state.snapshot().commitments());
    }

    @Test void sameRevisionCannotBeUsedTwiceEvenForEmptyBatch() {
        DispatchCommitmentState state = state();
        Batch empty = batch(state, 100, Map.of());
        state.commit(empty);
        rejected(Failure.STALE_VERSION, state, empty);
    }

    @Test void clockAdvanceAndStartInvalidatePreviouslyPreparedBatch() {
        DispatchCommitmentState state = state();
        Batch beforeAdvance = batch(state, 100, Map.of(A, 950.0));
        state.advanceTo(10);
        rejected(Failure.STALE_VERSION, state, beforeAdvance);
        Batch beforeStart = batch(state, 100, Map.of(B, 1_950.0));
        state.recordStarted(A, 20, 20);
        rejected(Failure.STALE_VERSION, state, beforeStart);
    }

    @Test void clockCannotGoBackWithinIterationForAnyMutatingOperation() {
        DispatchCommitmentState state = state();
        state.advanceTo(100);
        rejected(Failure.TIME_BACKWARDS, state, batch(state, 99, Map.of()));
        Snapshot before = state.snapshot();
        assertEquals(Failure.TIME_BACKWARDS,
                assertThrows(RejectedBatch.class, () -> state.advanceTo(99)).failure());
        assertEquals(Failure.TIME_BACKWARDS,
                assertThrows(RejectedBatch.class, () -> state.recordStarted(A, 90, 99)).failure());
        assertEquals(before, state.snapshot());
        assertEquals(before, state.advanceTo(100), "Same-time clock check is idempotent");
        assertEquals(0, state.beginIteration(21, 0, true).nowSeconds());
    }

    @Test void futureFactsIncludeFactsWhichOccurredButAreNotYetAvailable() {
        DispatchCommitmentState state = state();
        rejected(Failure.FUTURE_OBSERVATION, state, new Batch(state.snapshot().version(), 100,
                List.of(new ObservedFact("future-result", 110, 110)), Map.of(A, 950.0)));
        rejected(Failure.FUTURE_OBSERVATION, state, new Batch(state.snapshot().version(), 100,
                List.of(new ObservedFact("delayed-report", 90, 101)), Map.of(A, 950.0)));
        ObservedFact available = new ObservedFact("known-now", 90, 100);
        DecisionRecord record = state.commit(new Batch(state.snapshot().version(), 100, List.of(available), Map.of(A, 950.0)));
        assertEquals(List.of(available), record.observations());
    }

    @Test void pastCandidateIsRejectedWithoutMovingClock() {
        DispatchCommitmentState state = state(new Limits(1_000, 100, 30), true);
        rejected(Failure.PAST_DEPARTURE, state, batch(state, 500, Map.of(A, 499.0)));
    }

    @Test void constructorInputsAndReturnedCollectionsCannotMutateState() {
        List<ScheduledTrip> baseline = new ArrayList<>(List.of(new ScheduledTrip(A, "vehicle", 1_000)));
        DispatchCommitmentState state = new DispatchCommitmentState(baseline, new Limits(100, 100, 30));
        baseline.clear();
        state.beginIteration(20, 0, true);
        Map<TripKey, Double> proposals = new LinkedHashMap<>(Map.of(A, 950.0));
        List<ObservedFact> observations = new ArrayList<>(List.of(new ObservedFact("fact", 1, 1)));
        Batch batch = new Batch(state.snapshot().version(), 100, observations, proposals);
        proposals.put(A, 1_500.0);
        observations.clear();
        Snapshot before = state.snapshot();
        DecisionRecord record = state.commit(batch);
        assertEquals(1_000, before.commitments().get(A));
        assertEquals(950, state.snapshot().commitments().get(A));
        assertEquals(1, record.observations().size());
        assertThrows(UnsupportedOperationException.class, () -> state.baseline().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.snapshot().commitments().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.snapshot().lockedTrips().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.snapshot().startedAtSeconds().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.history().clear());
        assertThrows(UnsupportedOperationException.class, () -> record.changes().clear());
        assertThrows(UnsupportedOperationException.class, () -> record.observations().clear());
        assertThrows(UnsupportedOperationException.class, () -> batch.proposedDepartures().clear());
    }

    @Test void auditUsesBaselineOrderRegardlessOfProposalInsertionOrder() {
        DispatchCommitmentState first = state();
        DispatchCommitmentState second = state();
        Map<TripKey, Double> reverse = new LinkedHashMap<>();
        reverse.put(B, 1_950.0);
        reverse.put(A, 950.0);
        assertEquals(first.commit(batch(first, 100, Map.of(A, 950.0, B, 1_950.0))),
                second.commit(batch(second, 100, reverse)));
    }

    @Test void invalidInputsNeverCreateScientificDefaults() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new Limits(bad, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new Limits(1, bad, 1));
            assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, bad));
            assertThrows(IllegalArgumentException.class, () -> new ScheduledTrip(A, "vehicle", bad));
            assertThrows(IllegalArgumentException.class, () -> new ObservedFact("fact", bad, 10));
            DispatchCommitmentState state = state();
            Snapshot before = state.snapshot();
            assertThrows(IllegalArgumentException.class, () -> state.advanceTo(bad));
            assertThrows(IllegalArgumentException.class, () -> state.beginIteration(21, bad, true));
            assertThrows(IllegalArgumentException.class, () -> batch(state, 10, Map.of(A, bad)));
            assertThrows(IllegalArgumentException.class, () -> batch(state, bad, Map.of()));
            assertEquals(before, state.snapshot());
        }
        assertThrows(IllegalArgumentException.class, () -> new TripKey("", "route", "departure"));
        assertThrows(IllegalArgumentException.class, () -> new ScheduledTrip(A, " ", 1));
        assertThrows(IllegalArgumentException.class, () -> new ObservedFact("bad-clock", 11, 10));
        ScheduledTrip trip = new ScheduledTrip(A, "vehicle", 1_000);
        assertThrows(IllegalArgumentException.class, () -> new DispatchCommitmentState(List.of(trip, trip), new Limits(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new DispatchCommitmentState(List.of(), new Limits(0, 0, 0)));
    }

    @Test void operationsRequireExplicitIterationInitialization() {
        DispatchCommitmentState state = new DispatchCommitmentState(List.of(new ScheduledTrip(A, "vehicle", 1_000)), new Limits(0, 0, 0));
        assertEquals(Failure.NOT_INITIALIZED, assertThrows(RejectedBatch.class, state::snapshot).failure());
        assertEquals(Failure.NOT_INITIALIZED, assertThrows(RejectedBatch.class, () -> state.advanceTo(0)).failure());
        assertEquals(Failure.NOT_INITIALIZED, assertThrows(RejectedBatch.class, () -> state.recordStarted(A, 0, 0)).failure());
        assertEquals(Failure.NOT_INITIALIZED, assertThrows(RejectedBatch.class,
                () -> state.commit(new Batch(new Version(0, 0), 0, List.of(), Map.of()))).failure());
        assertThrows(IllegalArgumentException.class, () -> state.beginIteration(-1, 0, true));
    }

    @Test void veryLargeLockWindowUsesNoOverflowingTimeSum() {
        DispatchCommitmentState state = new DispatchCommitmentState(
                List.of(new ScheduledTrip(A, "vehicle", Double.MAX_VALUE)),
                new Limits(0, 0, Double.MAX_VALUE));
        assertTrue(state.beginIteration(0, Double.MAX_VALUE / 2, true).lockedTrips().contains(A));
    }
}
