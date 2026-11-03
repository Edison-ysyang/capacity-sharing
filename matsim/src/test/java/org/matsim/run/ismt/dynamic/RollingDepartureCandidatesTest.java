package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Batch;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Limits;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.ScheduledTrip;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Snapshot;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Version;

import static org.junit.jupiter.api.Assertions.*;

/** Pure synthetic values only: no QSim, policy, real data, event emission or file IO. */
class RollingDepartureCandidatesTest {
    private static final TripKey A = new TripKey("line", "route", "a");
    private static final TripKey B = new TripKey("line", "route", "b");
    private static final TripKey UNKNOWN = new TripKey("unknown", "route", "departure");
    private static final Limits LIMITS = new Limits(20, 20, 5);

    @Test void windowKeepsOutsideServicesUnchangedAndCannotPullThemInside() {
        var window = new RollingDepartureCandidates.AdjustmentWindow(100, 200);
        var trips = List.of(trip(A, 90), trip(B, 200));
        var engine = new RollingDepartureCandidates(trips, LIMITS, 300, 10, 1, window);
        var values = engine.generate(snapshot(0, true, Map.of(A, 90.0, B, 200.0), Set.of(), Map.of()));
        assertEquals(1, values.size());
        assertEquals(Map.of(A, 90.0, B, 200.0), values.getFirst().departures());
    }

    @Test void windowStartIsIncludedAndWindowEndIsExcludedWithoutOffGridClamping() {
        var window = new RollingDepartureCandidates.AdjustmentWindow(100, 120);
        var engine = new RollingDepartureCandidates(List.of(trip(A, 110)), LIMITS, 300, 10, 2, window);
        var values = engine.generate(snapshot(0, true, Map.of(A, 110.0), Set.of(), Map.of()));
        assertEquals(List.of(110.0, 100.0), times(values, A));
        var nonGrid = new RollingDepartureCandidates(List.of(trip(A, 110)), LIMITS, 300, 10, 1,
                new RollingDepartureCandidates.AdjustmentWindow(101, 119));
        assertEquals(1, nonGrid.generate(snapshot(0, true, Map.of(A, 110.0), Set.of(), Map.of())).size());
    }

    @Test void windowRejectsOutsideCommitmentsEvenWhenDisabledOrAlreadyStarted() {
        var window = new RollingDepartureCandidates.AdjustmentWindow(100, 200);
        var before = new RollingDepartureCandidates(List.of(trip(A, 90)), LIMITS, 300, 10, 10, window);
        assertThrows(IllegalArgumentException.class, () -> before.generate(
                snapshot(0, false, Map.of(A, 100.0), Set.of(), Map.of())));
        var inside = new RollingDepartureCandidates(List.of(trip(A, 190)), LIMITS, 300, 10, 10, window);
        assertThrows(IllegalArgumentException.class, () -> inside.generate(
                snapshot(250, true, Map.of(A, 200.0), Set.of(), Map.of(A, 200.0))));
        assertThrows(IllegalArgumentException.class, () -> inside.generate(
                snapshot(0, true, Map.of(A, 200.0), Set.of(), Map.of())));
    }

    @Test void windowDoesNotOverrideLockOrOriginalBaselineDisplacementBudget() {
        var engine = new RollingDepartureCandidates(List.of(trip(A, 100)), LIMITS, 300, 10, 3,
                new RollingDepartureCandidates.AdjustmentWindow(100, 200));
        assertEquals(List.of(110.0, 100.0, 120.0), times(engine.generate(
                snapshot(0, true, Map.of(A, 110.0), Set.of(), Map.of())), A));
        assertEquals(1, engine.generate(snapshot(95, true, Map.of(A, 100.0), Set.of(), Map.of())).size());
    }

    @Test void windowValidationIsExplicitAndUnchangedOutsideDeparturesRemainValid() {
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new RollingDepartureCandidates.AdjustmentWindow(invalid, 200));
            assertThrows(IllegalArgumentException.class, () -> new RollingDepartureCandidates.AdjustmentWindow(100, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new RollingDepartureCandidates.AdjustmentWindow(100, 100));
        assertThrows(IllegalArgumentException.class, () -> new RollingDepartureCandidates.AdjustmentWindow(200, 100));
        var window = new RollingDepartureCandidates.AdjustmentWindow(100, 200);
        assertTrue(window.permits(90, 90));
        assertFalse(window.permits(90, 100));
        assertFalse(window.permits(190, 200));
        assertFalse(window.contains(Double.NaN));
    }

    @Test void noActionIsFirstThenTheFullOriginalBaselineGridInAscendingTime() {
        var values = generator(List.of(trip(A, 100)), LIMITS, 200, 10, 5)
                .generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of()));
        assertEquals(5, values.size());
        assertTrue(values.getFirst().changedDepartures().isEmpty());
        assertEquals(List.of(100.0, 80.0, 90.0, 110.0, 120.0), times(values, A));
        assertEquals(List.of(0.0, 20.0, 10.0, 10.0, 20.0), values.stream()
                .map(RollingDepartureCandidates.Candidate::totalAbsoluteBaselineShiftSeconds).toList());
        for (var candidate : values.subList(1, values.size()))
            assertEquals(Map.of(A, candidate.departures().get(A)), candidate.changedDepartures());
    }

    @Test void offGridCurrentTimeIsRetainedAsNoActionAndBaselineRemainsAnExplicitCandidate() {
        var values = generator(List.of(trip(A, 100)), LIMITS, 200, 10, 6)
                .generate(snapshot(0, true, Map.of(A, 105.0), Set.of(), Map.of()));
        assertEquals(List.of(105.0, 80.0, 90.0, 100.0, 110.0, 120.0), times(values, A));
        assertEquals(5, values.getFirst().totalAbsoluteBaselineShiftSeconds());
        assertEquals(Map.of(A, 100.0), values.get(3).changedDepartures());
    }

    @Test void nonGridDisplacementBoundsAreNotForcedIntoTheNeighbourhood() {
        var values = generator(List.of(trip(A, 100)), new Limits(22, 37, 0), 200, 10, 10)
                .generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of()));
        assertEquals(List.of(100.0, 80.0, 90.0, 110.0, 120.0, 130.0), times(values, A));
        assertFalse(times(values, A).contains(78.0));
        assertFalse(times(values, A).contains(137.0));
    }

    @Test void movedLedgerStillUsesOriginalBaselineRatherThanAccumulatingItsAllowance() {
        var baseline = List.of(trip(A, 100));
        var ledger = new DispatchCommitmentState(baseline, LIMITS);
        var initial = ledger.beginIteration(20, 0, true);
        ledger.commit(new Batch(initial.version(), 0, List.of(), Map.of(A, 90.0)));
        var shifted = ledger.snapshot();
        var values = generator(baseline, LIMITS, 200, 10, 5).generate(shifted);
        assertEquals(List.of(90.0, 80.0, 100.0, 110.0, 120.0), times(values, A));
        assertEquals(20, values.getLast().totalAbsoluteBaselineShiftSeconds(), "90 -> 120 is allowed, but original 100 -> 130 is not");
        assertFalse(times(values, A).contains(70.0));
        assertFalse(times(values, A).contains(130.0));
        assertEquals(shifted, ledger.snapshot());
        assertEquals(1, ledger.history().size());
    }

    @Test void aMultiTripCurrentTimetableIsRetainedAndEachNeighbourChangesOnlyOneCoordinate() {
        var now = snapshot(0, true, Map.of(A, 110.0, B, 190.0), Set.of(), Map.of());
        var values = generator(List.of(trip(B, 200), trip(A, 100)), LIMITS, 300, 10, 9).generate(now);
        assertEquals(9, values.size());
        assertEquals(now.commitments(), values.getFirst().departures());
        assertEquals(20, values.getFirst().totalAbsoluteBaselineShiftSeconds());
        for (var candidate : values.subList(1, values.size())) {
            assertEquals(1, candidate.changedDepartures().size());
            TripKey moved = candidate.changedDepartures().keySet().iterator().next();
            TripKey untouched = moved.equals(A) ? B : A;
            assertEquals(now.commitments().get(untouched), candidate.departures().get(untouched));
            double cost = Math.abs(candidate.departures().get(A) - 100) + Math.abs(candidate.departures().get(B) - 200);
            assertEquals(cost, candidate.totalAbsoluteBaselineShiftSeconds());
        }
    }

    @Test void inputAndSnapshotInsertionOrderDoNotChangeTripThenTimeOrdering() {
        var k1 = new TripKey("a", "a", "1");
        var k2 = new TripKey("a", "a", "2");
        var k3 = new TripKey("a", "z", "1");
        var k4 = new TripKey("b", "a", "1");
        List<TripKey> sorted = List.of(k1, k2, k3, k4);
        List<TripKey> shuffled = List.of(k4, k2, k3, k1);
        Map<TripKey, Double> times = new LinkedHashMap<>();
        shuffled.forEach(key -> times.put(key, 100.0));
        var forward = generator(sorted.stream().map(key -> trip(key, 100)).toList(), new Limits(0, 10, 0), 200, 10, 5)
                .generate(snapshot(0, true, times, Set.of(), Map.of()));
        var reverse = generator(shuffled.stream().map(key -> trip(key, 100)).toList(), new Limits(0, 10, 0), 200, 10, 5)
                .generate(snapshot(0, true, times, Set.of(), Map.of()));
        assertEquals(forward, reverse);
        assertEquals(sorted, forward.subList(1, forward.size()).stream().map(candidate -> candidate.changedDepartures().keySet().iterator().next()).toList());
        for (var candidate : forward) assertEquals(sorted, new ArrayList<>(candidate.departures().keySet()));
    }

    @Test void disabledControlHasOnlyNoActionEvenWhenAnActiveGridWouldExceedItsBudget() {
        var values = generator(List.of(trip(A, 100)), LIMITS, 200, 0.001, 1)
                .generate(snapshot(0, false, Map.of(A, 103.0), Set.of(), Map.of()));
        assertEquals(1, values.size()); assertEquals(103, values.getFirst().departures().get(A));
        assertEquals(3, values.getFirst().totalAbsoluteBaselineShiftSeconds());
    }

    @Test void zeroAmplitudeHasOnlyNoActionEvenWithATinyPositiveGrid() {
        var values = generator(List.of(trip(A, 100)), new Limits(0, 0, 5), 200, Double.MIN_VALUE, 1)
                .generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of()));
        assertEquals(1, values.size());
    }

    @Test void explicitlyLockedAndActuallyStartedTripsAreNeverMoved() {
        var values = generator(List.of(trip(A, 100), trip(B, 120)), LIMITS, 200, 10, 1)
                .generate(snapshot(50, true, Map.of(A, 100.0, B, 120.0), Set.of(A), Map.of(B, 40.0)));
        assertEquals(1, values.size());
        assertEquals(Map.of(A, 100.0, B, 120.0), values.getFirst().departures());
    }

    @Test void currentAtOrBeforeTheComputedLockBoundaryIsExcludedEvenWithoutALatchedLock() {
        var engine = generator(List.of(trip(A, 100)), new Limits(20, 20, 10), 200, 10, 10);
        for (double now : new double[]{90, 91, 100, 110})
            assertEquals(1, engine.generate(snapshot(now, true, Map.of(A, 100.0), Set.of(), Map.of())).size());
    }

    @Test void candidateAtTheLockBoundaryIsExcludedButTheFirstStrictlyLaterGridPointRemains() {
        var values = generator(List.of(trip(A, 100)), new Limits(20, 20, 10), 200, 10, 10)
                .generate(snapshot(80, true, Map.of(A, 100.0), Set.of(), Map.of()));
        assertEquals(List.of(100.0, 110.0, 120.0), times(values, A));
        var justBefore = generator(List.of(trip(A, 100)), new Limits(20, 20, 10), 200, 10, 10)
                .generate(snapshot(79.5, true, Map.of(A, 100.0), Set.of(), Map.of()));
        assertEquals(List.of(100.0, 90.0, 110.0, 120.0), times(justBefore, A));
    }

    @Test void candidateAtLookaheadIsIncludedAndBeyondItIsNot() {
        var values = generator(List.of(trip(A, 100)), new Limits(20, 20, 0), 110, 10, 10)
                .generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of()));
        assertEquals(List.of(100.0, 80.0, 90.0, 110.0), times(values, A));
    }

    @Test void anExistingCommitmentOutsideLookaheadCannotBePulledInsideByAnAdvance() {
        var values = generator(List.of(trip(A, 100)), LIMITS, 99, 10, 1)
                .generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of()));
        assertEquals(1, values.size());
        var atBoundary = generator(List.of(trip(A, 100)), LIMITS, 100, 10, 3)
                .generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of()));
        assertEquals(List.of(100.0, 80.0, 90.0), times(atBoundary, A));
    }

    @Test void shortLookaheadOrEmptyAdmissibleIntervalReturnsNoActionWithoutCallingItFeasible() {
        assertEquals(1, generator(List.of(trip(A, 100)), new Limits(20, 20, 30), 20, 10, 1)
                .generate(snapshot(80, true, Map.of(A, 100.0), Set.of(), Map.of())).size());
    }

    @Test void negativeGridTimesAreExcludedAndExactlyRepresentableFractionalGridIsSupported() {
        var values = generator(List.of(trip(A, 0.5)), new Limits(1, 0.5, 0), 2, 0.25, 4)
                .generate(snapshot(0, true, Map.of(A, 0.5), Set.of(), Map.of()));
        assertEquals(List.of(0.5, 0.25, 0.75, 1.0), times(values, A));
        assertTrue(times(values, A).stream().allMatch(value -> value > 0));
    }

    @Test void originalBaselineThatHasAlreadyPassedIsNotReintroducedAsAnIllegalAdvance() {
        var values = generator(List.of(trip(A, 100)), new Limits(20, 30, 5), 100, 10, 5)
                .generate(snapshot(105, true, Map.of(A, 120.0), Set.of(), Map.of()));
        assertEquals(List.of(120.0, 130.0), times(values, A));
    }

    @Test void candidateBudgetIncludesNoActionAndNeverSilentlyTruncates() {
        var snapshot = snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of());
        assertEquals(5, generator(List.of(trip(A, 100)), LIMITS, 200, 10, 5).generate(snapshot).size());
        var failure = assertThrows(IllegalArgumentException.class, () -> generator(List.of(trip(A, 100)), LIMITS, 200, 10, 4).generate(snapshot));
        assertTrue(failure.getMessage().contains("maximumCandidates=4"));
        assertTrue(failure.getMessage().contains("no truncation"));
        assertEquals(Map.of(A, 100.0), snapshot.commitments());
    }

    @Test void aLaterTripThatExceedsTheRemainingBudgetRejectsTheWholeGeneration() {
        var engine = generator(List.of(trip(A, 100), trip(B, 120)), new Limits(0, 10, 0), 200, 10, 2);
        assertThrows(IllegalArgumentException.class, () -> engine.generate(snapshot(0, true, Map.of(A, 100.0, B, 120.0), Set.of(), Map.of())));
        assertEquals(2, engine.generate(snapshot(0, true, Map.of(A, 100.0, B, 120.0), Set.of(B), Map.of())).size());
    }

    @Test void extremeIndexCountIsRejectedWithoutEnumeratingOrOverflowingALong() {
        var engine = generator(List.of(trip(A, 100)), new Limits(1, 1, 0), 200, Double.MIN_VALUE, 5);
        var failure = assertThrows(IllegalArgumentException.class, () -> engine.generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of())));
        assertTrue(failure.getMessage().contains("maximumCandidates"));
    }

    @Test void doubleCollapsedGridPointsFailExplicitlyInsteadOfReturningDuplicates() {
        double baseline = Math.scalb(1.0, 54);
        var engine = generator(List.of(trip(A, baseline)), new Limits(2, 2, 0), baseline + 16, 1, 10);
        assertThrows(ArithmeticException.class, () -> engine.generate(snapshot(0, true, Map.of(A, baseline), Set.of(), Map.of())));
    }

    @Test void strictNativeDisplacementRoundingIsNotSilentlyRelaxed() {
        var engine = generator(List.of(trip(A, 1)), new Limits(0, 0.1, 0), 2, 0.1, 2);
        assertThrows(ArithmeticException.class, () -> engine.generate(snapshot(0, true, Map.of(A, 1.0), Set.of(), Map.of())),
                "Native double 1.1 - 1 exceeds 0.1; this generator must not claim such a point passes its exact bound");
    }

    @Test void missingExtraAndSubstitutedCommitmentKeysAreRejectedEvenWhenDisabled() {
        var engine = generator(List.of(trip(A, 100)), LIMITS, 200, 10, 10);
        for (var commitments : List.of(Map.<TripKey, Double>of(), Map.of(UNKNOWN, 100.0), Map.of(A, 100.0, UNKNOWN, 100.0)))
            assertThrows(IllegalArgumentException.class, () -> engine.generate(snapshot(0, false, commitments, Set.of(), Map.of())));
    }

    @Test void unknownLockedOrStartedTripAndFutureStartAreRejected() {
        var engine = generator(List.of(trip(A, 100)), LIMITS, 200, 10, 10);
        assertThrows(IllegalArgumentException.class, () -> engine.generate(snapshot(0, true, Map.of(A, 100.0), Set.of(UNKNOWN), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> engine.generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of(UNKNOWN, 0.0))));
        assertThrows(IllegalArgumentException.class, () -> engine.generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of(A, 1.0))));
        for (double value : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> engine.generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), Map.of(A, value))));
    }

    @Test void malformedTimesVersionsAndOutOfLimitCurrentCommitmentsAreRejected() {
        var engine = generator(List.of(trip(A, 100)), LIMITS, 200, 10, 10);
        for (double value : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> engine.generate(snapshot(value, true, Map.of(A, 100.0), Set.of(), Map.of())));
            assertThrows(IllegalArgumentException.class, () -> engine.generate(snapshot(0, true, Map.of(A, value), Set.of(), Map.of())));
        }
        for (double value : new double[]{79, 121})
            assertThrows(IllegalArgumentException.class, () -> engine.generate(snapshot(0, true, Map.of(A, value), Set.of(), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> engine.generate(new Snapshot(-1, new Version(1, 0), 0, true, Map.of(A, 100.0), Set.of(), Map.of())));
        for (var version : List.of(new Version(0, 0), new Version(1, -1)))
            assertThrows(IllegalArgumentException.class, () -> engine.generate(new Snapshot(20, version, 0, true, Map.of(A, 100.0), Set.of(), Map.of())));
        assertThrows(NullPointerException.class, () -> engine.generate(new Snapshot(20, null, 0, true, Map.of(A, 100.0), Set.of(), Map.of())));
        assertThrows(NullPointerException.class, () -> engine.generate(null));
    }

    @Test void nullCommitmentAndStartValuesCannotMasqueradeAsUnknownTimes() {
        var engine = generator(List.of(trip(A, 100)), LIMITS, 200, 10, 10);
        var missingValue = new LinkedHashMap<TripKey, Double>(); missingValue.put(A, null);
        assertThrows(NullPointerException.class, () -> engine.generate(snapshot(0, true, missingValue, Set.of(), Map.of())));
        assertThrows(NullPointerException.class, () -> engine.generate(snapshot(0, true, Map.of(A, 100.0), Set.of(), missingValue)));
    }

    @Test void invalidConstructorInputsHaveNoScientificDefaults() {
        for (double value : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> generator(List.of(trip(A, 100)), LIMITS, value, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> generator(List.of(trip(A, 100)), LIMITS, 200, value, 10));
        }
        for (int budget : new int[]{0, -1}) assertThrows(IllegalArgumentException.class, () -> generator(List.of(trip(A, 100)), LIMITS, 200, 10, budget));
        assertThrows(IllegalArgumentException.class, () -> generator(List.of(), LIMITS, 200, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> generator(List.of(trip(A, 100), trip(A, 110)), LIMITS, 200, 10, 10));
        assertThrows(NullPointerException.class, () -> generator(null, LIMITS, 200, 10, 10));
        assertThrows(NullPointerException.class, () -> generator(List.of(trip(A, 100)), null, 200, 10, 10));
        var withNull = new ArrayList<ScheduledTrip>(); withNull.add(null);
        assertThrows(NullPointerException.class, () -> generator(withNull, LIMITS, 200, 10, 10));
    }

    @Test void arithmeticOverflowInBoundsOrTotalMovementFailsExplicitly() {
        assertThrows(ArithmeticException.class, () -> generator(List.of(trip(A, Double.MAX_VALUE)), new Limits(0, Double.MAX_VALUE, 0), 1, 1, 1));
        var horizon = generator(List.of(trip(A, 100)), new Limits(0, 0, 0), Double.MAX_VALUE, 1, 1);
        assertThrows(ArithmeticException.class, () -> horizon.generate(snapshot(Double.MAX_VALUE, false, Map.of(A, 100.0), Set.of(), Map.of())));
        var lock = generator(List.of(trip(A, 100)), new Limits(0, 0, Double.MAX_VALUE), 1, 1, 1);
        assertThrows(ArithmeticException.class, () -> lock.generate(snapshot(Double.MAX_VALUE, false, Map.of(A, 100.0), Set.of(), Map.of())));
        var sum = generator(List.of(trip(A, 0), trip(B, 0)), new Limits(0, Double.MAX_VALUE, 0), 1, 1, 1);
        assertThrows(ArithmeticException.class, () -> sum.generate(snapshot(0, false, Map.of(A, Double.MAX_VALUE, B, Double.MAX_VALUE), Set.of(), Map.of())));
    }

    @Test void candidatesAreNotHeadwayPassengerProtectionOrExecutionCertificates() {
        var values = generator(List.of(trip(A, 100), trip(B, 105)), new Limits(0, 10, 0), 200, 5, 5)
                .generate(snapshot(0, true, Map.of(A, 100.0, B, 105.0), Set.of(), Map.of()));
        assertTrue(values.stream().anyMatch(value -> value.changedDepartures().equals(Map.of(A, 110.0))
                && value.departures().get(B) == 105.0), "Order/headway constraints belong to the independent checker");
    }

    @Test void generatorSnapshotAndCandidateCollectionsAreDefensivelyCopied() {
        var baseline = new ArrayList<>(List.of(trip(A, 100)));
        var commitments = new LinkedHashMap<TripKey, Double>(); commitments.put(A, 100.0);
        var locks = new LinkedHashSet<TripKey>();
        var snapshot = snapshot(0, true, commitments, locks, Map.of());
        var engine = generator(baseline, LIMITS, 200, 10, 5);
        baseline.clear(); commitments.clear(); locks.add(A);
        var values = engine.generate(snapshot);
        assertEquals(5, values.size());
        assertThrows(UnsupportedOperationException.class, () -> values.clear());
        assertThrows(UnsupportedOperationException.class, () -> values.getFirst().departures().clear());
        assertThrows(UnsupportedOperationException.class, () -> values.get(1).changedDepartures().clear());
        assertEquals(values, engine.generate(snapshot));
        Map<TripKey, Double> full = new LinkedHashMap<>(); full.put(A, 100.0);
        Map<TripKey, Double> change = new LinkedHashMap<>(); change.put(A, 100.0);
        var direct = new RollingDepartureCandidates.Candidate(change, full, 0);
        full.clear(); change.clear();
        assertEquals(Map.of(A, 100.0), direct.departures()); assertEquals(Map.of(A, 100.0), direct.changedDepartures());
    }

    @Test void candidateValueRejectsMalformedMapsAndNonfiniteCosts() {
        assertThrows(IllegalArgumentException.class, () -> new RollingDepartureCandidates.Candidate(Map.of(), Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new RollingDepartureCandidates.Candidate(Map.of(A, 100.0), Map.of(B, 100.0), 0));
        assertThrows(IllegalArgumentException.class, () -> new RollingDepartureCandidates.Candidate(Map.of(A, 90.0, B, 110.0), Map.of(A, 90.0, B, 110.0), 20));
        for (double cost : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new RollingDepartureCandidates.Candidate(Map.of(), Map.of(A, 100.0), cost));
    }

    private static RollingDepartureCandidates generator(List<ScheduledTrip> trips, Limits limits, double lookahead, double step, int maximum) {
        return new RollingDepartureCandidates(trips, limits, lookahead, step, maximum);
    }

    private static ScheduledTrip trip(TripKey key, double time) { return new ScheduledTrip(key, "vehicle-" + key.departure(), time); }

    private static Snapshot snapshot(double now, boolean enabled, Map<TripKey, Double> times,
                                     Set<TripKey> locked, Map<TripKey, Double> started) {
        return new Snapshot(20, new Version(1, 0), now, enabled, times, locked, started);
    }

    private static List<Double> times(List<RollingDepartureCandidates.Candidate> candidates, TripKey trip) {
        return candidates.stream().map(candidate -> candidate.departures().get(trip)).toList();
    }
}
