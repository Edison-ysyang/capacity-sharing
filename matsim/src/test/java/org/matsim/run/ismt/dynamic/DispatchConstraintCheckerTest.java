package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.ScheduledTrip;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;

class DispatchConstraintCheckerTest {
    private static final TripKey A1 = new TripKey("line", "branch-a", "1");
    private static final TripKey A2 = new TripKey("line", "branch-a", "2");
    private static final TripKey B1 = new TripKey("line", "branch-b", "1");
    private static final TripKey B2 = new TripKey("line", "branch-b", "2");
    private static final TripKey UNKNOWN = new TripKey("other", "other", "missing");

    @Test void explicitWindowRejectsBypassEditsButRetainsCompleteUnchangedOutsideSupply() {
        var engine = new DispatchConstraintChecker(baseline(), List.of(), List.of(),
                new RollingDepartureCandidates.AdjustmentWindow(200, 400));
        assertEquals(engine.evaluate(baselineTimes()).baseline(), engine.evaluate(baselineTimes()).candidate());
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(
                Map.of(A1, 200.0, A2, 300.0, B1, 200.0, B2, 400.0)), "Outside baseline cannot be pulled inside");
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(
                Map.of(A1, 100.0, A2, 400.0, B1, 200.0, B2, 400.0)), "Candidate cannot reach exclusive end");
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(
                Map.of(A1, 100.0, A2, 300.0, B1, 199.0, B2, 400.0)), "Candidate cannot cross the start");
        assertThrows(IllegalArgumentException.class, () -> engine.evaluate(
                Map.of(A1, 100.0, A2, 300.0, B1, 200.0, B2, 390.0)), "Baseline at exclusive end remains fixed");
    }

    @Test void explicitWindowPreservesHeadwayChecksWithTheOutsideNeighbour() {
        var shared = point("window-edge", 100, 250, new Call(A1, 0), new Call(B1, 0), new Call(A2, 0));
        var engine = new DispatchConstraintChecker(baseline(), List.of(shared), List.of(),
                new RollingDepartureCandidates.AdjustmentWindow(200, 400));
        assertTrue(engine.evaluate(baselineTimes()).candidate().satisfiesRegisteredConstraints());
        var result = engine.evaluate(Map.of(A1, 100.0, A2, 300.0, B1, 210.0, B2, 400.0));
        assertFalse(result.candidate().satisfiesRegisteredConstraints());
        assertEquals(1, result.candidate().violations().size());
        assertEquals(Kind.MINIMUM_HEADWAY, result.candidate().violations().getFirst().kind());
    }

    private List<ScheduledTrip> baseline() {
        return List.of(new ScheduledTrip(A1, "a", 100), new ScheduledTrip(A2, "a", 300),
                new ScheduledTrip(B1, "b", 200), new ScheduledTrip(B2, "b", 400));
    }

    private Map<TripKey, Double> baselineTimes() { return Map.of(A1, 100.0, A2, 300.0, B1, 200.0, B2, 400.0); }

    private ControlPoint point(String id, double min, double max, Call... calls) {
        return new ControlPoint(new ControlPointKey(id, "outbound"), List.of(calls), min, max, Optional.empty());
    }

    private DispatchConstraintChecker checker(ControlPoint... points) {
        return new DispatchConstraintChecker(baseline(), List.of(points), List.of());
    }

    private List<ConstraintCheck> kind(Assessment assessment, Kind kind) {
        return assessment.checks().stream().filter(check -> check.kind() == kind).toList();
    }

    @Test void individuallyValidBranchesCanViolateSharedMergeHeadway() {
        ControlPoint branchA = point("branch-a", 150, 250, new Call(A1, 0), new Call(A2, 0));
        ControlPoint branchB = point("branch-b", 150, 250, new Call(B1, 0), new Call(B2, 0));
        ControlPoint merge = point("shared-merge", 50, 150, new Call(A1, 50), new Call(B1, 50), new Call(A2, 50), new Call(B2, 50));
        Map<TripKey, Double> candidate = Map.of(A1, 100.0, A2, 300.0, B1, 130.0, B2, 330.0);
        assertTrue(checker(branchA, branchB).evaluate(candidate).candidate().satisfiesRegisteredConstraints());
        Comparison result = checker(branchA, branchB, merge).evaluate(candidate);
        assertTrue(result.baseline().satisfiesRegisteredConstraints());
        assertFalse(result.candidate().satisfiesRegisteredConstraints());
        assertTrue(result.candidate().violations().stream().allMatch(check -> check.point().orElseThrow().equals(merge.key())));
        assertEquals(2, kind(result.candidate(), Kind.MINIMUM_HEADWAY).stream().filter(ConstraintCheck::violated).count());
        assertEquals(1, kind(result.candidate(), Kind.MAXIMUM_HEADWAY).stream().filter(ConstraintCheck::violated).count());
        assertEquals(-20, kind(result.candidate(), Kind.MINIMUM_HEADWAY).get(2).marginSeconds());
    }

    @Test void candidateIsNotSortedToHideRegisteredOrderReversal() {
        Comparison result = checker(point("merge", 20, 300, new Call(A1, 0), new Call(B1, 0)))
                .evaluate(Map.of(A1, 250.0, A2, 300.0, B1, 200.0, B2, 400.0));
        ConstraintCheck order = kind(result.candidate(), Kind.REGISTERED_ORDER).getFirst();
        assertEquals(List.of(A1, B1), order.trips());
        assertEquals(-50, order.valueSeconds());
        assertEquals(-50, order.marginSeconds());
        assertTrue(order.violated());
        assertTrue(kind(result.candidate(), Kind.MINIMUM_HEADWAY).getFirst().violated());
    }

    @Test void exactMinimumAndMaximumHeadwayBoundariesPass() {
        DispatchConstraintChecker checker = checker(point("merge", 100, 200, new Call(A1, 0), new Call(B1, 0), new Call(B2, 0)));
        Comparison result = checker.evaluate(baselineTimes());
        assertTrue(result.candidate().satisfiesRegisteredConstraints());
        assertEquals(0, kind(result.candidate(), Kind.MINIMUM_HEADWAY).getFirst().marginSeconds());
        assertEquals(0, kind(result.candidate(), Kind.MAXIMUM_HEADWAY).get(1).marginSeconds());
    }

    @Test void belowMinimumAndAboveMaximumMarginsAreSigned() {
        DispatchConstraintChecker checker = checker(point("merge", 100, 200, new Call(A1, 0), new Call(B1, 0), new Call(B2, 0)));
        Comparison result = checker.evaluate(Map.of(A1, 100.0, A2, 300.0, B1, 199.0, B2, 400.0));
        assertEquals(-1, kind(result.candidate(), Kind.MINIMUM_HEADWAY).getFirst().marginSeconds());
        assertEquals(-1, kind(result.candidate(), Kind.MAXIMUM_HEADWAY).get(1).marginSeconds());
    }

    @Test void explicitCallOffsetsCanExposeConflictEvenIfOriginTimesLookValid() {
        Comparison result = checker(point("merge", 100, 200, new Call(A1, 150), new Call(B1, 60)))
                .evaluate(baselineTimes());
        assertEquals(10, kind(result.candidate(), Kind.MINIMUM_HEADWAY).getFirst().valueSeconds());
        assertEquals(-90, kind(result.candidate(), Kind.MINIMUM_HEADWAY).getFirst().marginSeconds());
    }

    @Test void baselineAndCandidateCanBothViolateAndNeitherIsAcceptedAsFallback() {
        DispatchConstraintChecker checker = checker(point("merge", 120, 140, new Call(A1, 0), new Call(B1, 0)));
        Comparison result = checker.evaluate(Map.of(A1, 100.0, A2, 300.0, B1, 250.0, B2, 400.0));
        assertFalse(result.baseline().satisfiesRegisteredConstraints());
        assertFalse(result.candidate().satisfiesRegisteredConstraints());
        assertEquals(Kind.MINIMUM_HEADWAY, result.baseline().violations().getFirst().kind());
        assertEquals(Kind.MAXIMUM_HEADWAY, result.candidate().violations().getFirst().kind());
        assertEquals(-20, result.baseline().violations().getFirst().marginSeconds());
        assertEquals(-10, result.candidate().violations().getFirst().marginSeconds());
    }

    @Test void candidateCanFixRegisteredViolationWithoutRewritingBaselineAssessment() {
        DispatchConstraintChecker checker = checker(point("merge", 120, 140, new Call(A1, 0), new Call(B1, 0)));
        Comparison fixed = checker.evaluate(Map.of(A1, 100.0, A2, 300.0, B1, 230.0, B2, 400.0));
        assertFalse(fixed.baseline().satisfiesRegisteredConstraints());
        assertTrue(fixed.candidate().satisfiesRegisteredConstraints());
        assertEquals(fixed.baseline(), checker.evaluate(baselineTimes()).baseline());
    }

    @Test void explicitFirstAndLastWindowsCheckRegisteredEndpointsWithOffsets() {
        ControlPoint point = new ControlPoint(new ControlPointKey("terminus", "outbound"),
                List.of(new Call(A1, 10), new Call(A2, 20)), 100, 300,
                Optional.of(new FirstLastWindow(110, 120, 310, 320)));
        DispatchConstraintChecker checker = checker(point);
        Comparison boundaries = checker.evaluate(baselineTimes());
        assertTrue(boundaries.candidate().satisfiesRegisteredConstraints());
        assertEquals(0, kind(boundaries.candidate(), Kind.FIRST_EARLIEST).getFirst().marginSeconds());
        assertEquals(0, kind(boundaries.candidate(), Kind.LAST_LATEST).getFirst().marginSeconds());
        Comparison outside = checker.evaluate(Map.of(A1, 99.0, A2, 301.0, B1, 200.0, B2, 400.0));
        assertEquals(-1, kind(outside.candidate(), Kind.FIRST_EARLIEST).getFirst().marginSeconds());
        assertEquals(-1, kind(outside.candidate(), Kind.LAST_LATEST).getFirst().marginSeconds());
    }

    @Test void firstLatestAndLastEarliestViolationsAreAlsoChecked() {
        ControlPoint point = new ControlPoint(new ControlPointKey("terminus", "outbound"),
                List.of(new Call(A1, 0), new Call(A2, 0)), 0, 500,
                Optional.of(new FirstLastWindow(90, 110, 290, 310)));
        Comparison result = checker(point).evaluate(Map.of(A1, 111.0, A2, 289.0, B1, 200.0, B2, 400.0));
        assertEquals(-1, kind(result.candidate(), Kind.FIRST_LATEST).getFirst().marginSeconds());
        assertEquals(-1, kind(result.candidate(), Kind.LAST_EARLIEST).getFirst().marginSeconds());
    }

    @Test void singleCallCanHaveServiceWindowWithoutInventingHeadwayPair() {
        ControlPoint point = new ControlPoint(new ControlPointKey("single", "outbound"), List.of(new Call(A1, 0)),
                20, 200, Optional.of(new FirstLastWindow(100, 100, 100, 100)));
        Comparison result = checker(point).evaluate(baselineTimes());
        assertTrue(result.candidate().satisfiesRegisteredConstraints());
        assertEquals(0, result.coverage().registeredHeadwayPairs());
        assertEquals(1, result.coverage().registeredServiceWindows());
        assertEquals(4, result.candidate().checks().size());
    }

    @Test void connectionRequiresSameFixedVehicleAndExplicitTurnaroundTime() {
        VehicleConnection connection = new VehicleConnection("rotation-a", A1, A2, 150, 50);
        DispatchConstraintChecker checker = new DispatchConstraintChecker(baseline(), List.of(), List.of(connection));
        Comparison exact = checker.evaluate(baselineTimes());
        ConstraintCheck check = exact.candidate().checks().getFirst();
        assertEquals(50, check.valueSeconds());
        assertEquals(50, check.boundSeconds());
        assertEquals(0, check.marginSeconds());
        assertTrue(exact.candidate().satisfiesRegisteredConstraints());
        assertEquals(Optional.of("rotation-a"), check.connectionId());
        assertTrue(check.point().isEmpty());
        Comparison conflict = checker.evaluate(Map.of(A1, 100.0, A2, 299.0, B1, 200.0, B2, 400.0));
        assertEquals(-1, conflict.candidate().checks().getFirst().marginSeconds());
        assertFalse(conflict.candidate().satisfiesRegisteredConstraints());
    }

    @Test void successorBeforePredecessorEndReturnsViolation() {
        DispatchConstraintChecker checker = new DispatchConstraintChecker(baseline(), List.of(),
                List.of(new VehicleConnection("rotation-a", A1, A2, 150, 10)));
        Comparison result = checker.evaluate(Map.of(A1, 100.0, A2, 240.0, B1, 200.0, B2, 400.0));
        assertEquals(-10, result.candidate().checks().getFirst().valueSeconds());
        assertEquals(-20, result.candidate().checks().getFirst().marginSeconds());
    }

    @Test void connectionCannotUseDifferentFixedVehicle() {
        assertThrows(IllegalArgumentException.class, () -> new DispatchConstraintChecker(baseline(), List.of(),
                List.of(new VehicleConnection("wrong-vehicle", A1, B1, 50, 10))));
    }

    @Test void missingOrExtraCandidateTripsAreRejectedEvenWithNoRegisteredConstraints() {
        DispatchConstraintChecker checker = checker();
        Map<TripKey, Double> missing = new LinkedHashMap<>(baselineTimes());
        missing.remove(A2);
        assertThrows(IllegalArgumentException.class, () -> checker.evaluate(missing));
        Map<TripKey, Double> extra = new LinkedHashMap<>(baselineTimes());
        extra.put(UNKNOWN, 100.0);
        assertThrows(IllegalArgumentException.class, () -> checker.evaluate(extra));
        extra.remove(A2);
        assertThrows(IllegalArgumentException.class, () -> checker.evaluate(extra), "Equal size is not equal keyset");
    }

    @Test void unknownRegisteredCallAndConnectionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> checker(point("unknown", 0, 100, new Call(A1, 0), new Call(UNKNOWN, 0))));
        assertThrows(IllegalArgumentException.class, () -> new DispatchConstraintChecker(baseline(), List.of(),
                List.of(new VehicleConnection("unknown", A1, UNKNOWN, 0, 0))));
    }

    @Test void duplicateControlPointUsesIdAndDirectionButOppositeDirectionsAreLegal() {
        ControlPoint first = point("shared", 0, 1_000, new Call(A1, 0), new Call(A2, 0));
        assertThrows(IllegalArgumentException.class, () -> checker(first, first));
        ControlPoint reverse = new ControlPoint(new ControlPointKey("shared", "inbound"),
                List.of(new Call(B1, 0), new Call(B2, 0)), 0, 1_000, Optional.empty());
        assertEquals(2, checker(first, reverse).evaluate(baselineTimes()).coverage().registeredControlPoints());
    }

    @Test void repeatedTripAtOnePointIsRejectedEvenWithDifferentOffset() {
        assertThrows(IllegalArgumentException.class, () -> point("loop-not-supported", 0, 100,
                new Call(A1, 0), new Call(A1, 50)));
    }

    @Test void duplicateConnectionIdsAndPairsAreRejected() {
        VehicleConnection first = new VehicleConnection("same-id", A1, A2, 100, 20);
        assertThrows(IllegalArgumentException.class, () -> new DispatchConstraintChecker(baseline(), List.of(), List.of(first,
                new VehicleConnection("same-id", B1, B2, 100, 20))));
        assertThrows(IllegalArgumentException.class, () -> new DispatchConstraintChecker(baseline(), List.of(), List.of(first,
                new VehicleConnection("different-id", A1, A2, 110, 30))));
        assertThrows(IllegalArgumentException.class, () -> new VehicleConnection("self", A1, A1, 0, 0));
    }

    @Test void absentRegistrationsAreExplicitAndCannotConstituteFormalRelease() {
        Comparison result = checker().evaluate(baselineTimes());
        assertTrue(result.candidate().satisfiesRegisteredConstraints(), "This is only vacuous satisfaction");
        assertFalse(result.candidate().anyConstraintsEvaluated());
        assertFalse(result.coverage().anyConstraintsRegistered());
        assertEquals(Set.of(A1, A2, B1, B2), result.coverage().tripsWithoutRegisteredControlPointCall());
        assertEquals(Set.of(A1, A2, B1, B2), result.coverage().tripsWithoutRegisteredConnectionEndpoint());
        assertFalse(result.coverage().actualVehicleCirculationVerified());
        assertFalse(result.coverage().sharedResourceCoverageVerified());
        assertEquals(EvidenceScope.REGISTERED_PLANNED_TIMES_ONLY, result.scope());
    }

    @Test void singletonPointWithoutWindowStillRegistersNoInequality() {
        Comparison result = checker(point("single", 20, 200, new Call(A1, 0))).evaluate(baselineTimes());
        assertEquals(1, result.coverage().registeredControlPoints());
        assertEquals(0, result.coverage().evaluatedInequalities());
        assertFalse(result.coverage().anyConstraintsRegistered());
        assertEquals(Set.of(A2, B1, B2), result.coverage().tripsWithoutRegisteredControlPointCall());
    }

    @Test void allTripsRegisteredStillDoesNotProveInfrastructureOrCirculationCoverage() {
        ControlPoint point = point("one-point-only", 100, 100, new Call(A1, 0), new Call(B1, 0), new Call(A2, 0), new Call(B2, 0));
        DispatchConstraintChecker checker = new DispatchConstraintChecker(baseline(), List.of(point),
                List.of(new VehicleConnection("a", A1, A2, 150, 50), new VehicleConnection("b", B1, B2, 150, 50)));
        Coverage coverage = checker.evaluate(baselineTimes()).coverage();
        assertTrue(coverage.anyConstraintsRegistered());
        assertTrue(coverage.tripsWithoutRegisteredControlPointCall().isEmpty());
        assertTrue(coverage.tripsWithoutRegisteredConnectionEndpoint().isEmpty());
        assertFalse(coverage.sharedResourceCoverageVerified());
        assertFalse(coverage.actualVehicleCirculationVerified());
    }

    @Test void partialConnectionCoverageRemainsExplicit() {
        Comparison result = new DispatchConstraintChecker(baseline(), List.of(),
                List.of(new VehicleConnection("a", A1, A2, 150, 50))).evaluate(baselineTimes());
        assertEquals(1, result.coverage().registeredVehicleConnections());
        assertEquals(Set.of(B1, B2), result.coverage().tripsWithoutRegisteredConnectionEndpoint());
        assertFalse(result.coverage().actualVehicleCirculationVerified());
    }

    @Test void originPlusCallOffsetOverflowIsRejectedForBaselineAndCandidate() {
        ControlPoint huge = point("huge", 0, 1_000, new Call(A1, Double.MAX_VALUE));
        List<ScheduledTrip> hugeBaseline = List.of(new ScheduledTrip(A1, "a", Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new DispatchConstraintChecker(hugeBaseline, List.of(huge), List.of()));
        DispatchConstraintChecker checker = checker(huge);
        Map<TripKey, Double> candidate = new LinkedHashMap<>(baselineTimes());
        candidate.put(A1, Double.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> checker.evaluate(candidate));
    }

    @Test void connectionEndOrEarliestAvailabilityOverflowIsRejected() {
        List<ScheduledTrip> hugeBaseline = List.of(new ScheduledTrip(A1, "a", Double.MAX_VALUE), new ScheduledTrip(A2, "a", Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new DispatchConstraintChecker(hugeBaseline, List.of(),
                List.of(new VehicleConnection("end-overflow", A1, A2, Double.MAX_VALUE, 0))));
        assertThrows(IllegalArgumentException.class, () -> new DispatchConstraintChecker(hugeBaseline, List.of(),
                List.of(new VehicleConnection("availability-overflow", A1, A2, 0, Double.MAX_VALUE))));
    }

    @Test void invalidRegistrationNumbersAndCandidateTimesAreRejected() {
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Call(A1, invalid));
            assertThrows(IllegalArgumentException.class, () -> point("bad", invalid, 100, new Call(A1, 0)));
            assertThrows(IllegalArgumentException.class, () -> point("bad", 0, invalid, new Call(A1, 0)));
            assertThrows(IllegalArgumentException.class, () -> new FirstLastWindow(invalid, 100, 100, 200));
            assertThrows(IllegalArgumentException.class, () -> new VehicleConnection("bad", A1, A2, invalid, 10));
            assertThrows(IllegalArgumentException.class, () -> new VehicleConnection("bad", A1, A2, 10, invalid));
            Map<TripKey, Double> candidate = new LinkedHashMap<>(baselineTimes());
            candidate.put(A1, invalid);
            assertThrows(IllegalArgumentException.class, () -> checker().evaluate(candidate));
        }
        assertThrows(IllegalArgumentException.class, () -> point("bad", 101, 100, new Call(A1, 0)));
        assertThrows(IllegalArgumentException.class, () -> new FirstLastWindow(101, 100, 100, 200));
        assertThrows(IllegalArgumentException.class, () -> new FirstLastWindow(0, 100, 201, 200));
        assertThrows(IllegalArgumentException.class, () -> point("empty", 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new ControlPointKey(" ", "outbound"));
        assertThrows(IllegalArgumentException.class, () -> new ControlPointKey("point", ""));
        assertThrows(IllegalArgumentException.class, () -> new DispatchConstraintChecker(List.of(baseline().getFirst(), baseline().getFirst()), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DispatchConstraintChecker(List.of(), List.of(), List.of()));
    }

    @Test void inputsAndResultsAreDefensiveAndRepeatedEvaluationHasNoSideEffects() {
        List<Call> calls = new ArrayList<>(List.of(new Call(A1, 0), new Call(A2, 0)));
        ControlPoint point = new ControlPoint(new ControlPointKey("point", "outbound"), calls, 100, 300, Optional.empty());
        List<ScheduledTrip> baseline = new ArrayList<>(baseline());
        List<ControlPoint> points = new ArrayList<>(List.of(point));
        DispatchConstraintChecker checker = new DispatchConstraintChecker(baseline, points, List.of());
        calls.clear();
        baseline.clear();
        points.clear();
        Map<TripKey, Double> candidate = new LinkedHashMap<>(baselineTimes());
        Comparison first = checker.evaluate(candidate);
        candidate.put(A1, 500.0);
        assertTrue(first.candidate().satisfiesRegisteredConstraints());
        assertFalse(checker.evaluate(candidate).candidate().satisfiesRegisteredConstraints());
        assertEquals(first, checker.evaluate(baselineTimes()));
        assertThrows(UnsupportedOperationException.class, () -> first.candidate().checks().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.candidate().checks().getFirst().trips().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.coverage().tripsWithoutRegisteredControlPointCall().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.coverage().tripsWithoutRegisteredConnectionEndpoint().clear());
    }

    @Test void checkingCompleteStateSnapshotDoesNotCommitOrChangeVersion() {
        DispatchCommitmentState state = new DispatchCommitmentState(baseline(), new DispatchCommitmentState.Limits(100, 100, 30));
        state.beginIteration(20, 0, true);
        DispatchCommitmentState.Snapshot before = state.snapshot();
        Map<TripKey, Double> candidate = new LinkedHashMap<>(before.commitments());
        candidate.put(A1, 150.0);
        Comparison result = checker(point("merge", 100, 200, new Call(A1, 0), new Call(B1, 0))).evaluate(candidate);
        assertFalse(result.candidate().satisfiesRegisteredConstraints());
        assertEquals(before, state.snapshot());
        assertTrue(state.history().isEmpty());
    }
}
