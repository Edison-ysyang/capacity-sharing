package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.ScheduledTrip;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.SingleLineForecastSupply.Call;
import org.matsim.run.ismt.dynamic.SingleLineForecastSupply.DwellMode;
import org.matsim.run.ismt.dynamic.SingleLineForecastSupply.Trip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.MixedCapacityProjection.*;

/** Pure batched forecast examples, not native QSim/door-handler equivalence tests. */
class MixedCapacityProjectionTest {
    private static final double EPS = 1e-10;
    private final MixedCapacityProjection projection = new MixedCapacityProjection();

    @Test void boardingCutoffDoesNotRemoveOnboardFreightOrStopPassengerService() {
        var window = new org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SharedBoardingWindow(10, 30);
        var result = projection.project(new Inputs(0, 100, 1, 1, 100,
                List.of(service("r", 3, 0, threeCalls())), List.of(pax("p", "B", "C", 0, 1)),
                List.of(freight("onboard-before-cutoff", "A", "C"), freight("waiting-at-cutoff", "B", "C")), window));
        assertEquals(10, result.freight().get("onboard-before-cutoff").firstBoardedAtSeconds().orElseThrow());
        assertEquals(40, result.freight().get("onboard-before-cutoff").deliveredAtSeconds().orElseThrow());
        assertTrue(result.freight().get("waiting-at-cutoff").firstBoardedAtSeconds().isEmpty());
        assertEquals(Completion.UNDELIVERED_AT_HORIZON, result.freight().get("waiting-at-cutoff").completion());
        assertEquals(0, result.passengers().get("p").unboardedMass());
        assertEquals(1, result.loads().get(1).freightAfterBoarding());
        assertEquals(0, result.loads().get(1).freightBoarded());
    }

    @Test void boardingWindowRejectsEarlyStopsAndInconsistentObservedBoardingButPreservesValidRidingState() {
        var window = new org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SharedBoardingWindow(11, 30);
        Service service = service("r", 2, 0, threeCalls());
        var waiting = projection.project(new Inputs(0, 100, 1, 1, 100, List.of(service), List.of(),
                List.of(freight("f", "A", "C")), window));
        assertTrue(waiting.freight().get("f").firstBoardedAtSeconds().isEmpty());
        var riding = new Freight("f", "A", "C", 0, 0, 100, 0, OptionalDouble.of(20),
                OptionalDouble.empty(), OptionalDouble.empty(), false, Optional.of(service.supply().scheduled().key()));
        var continued = projection.project(new Inputs(31, 100, 1, 1, 100,
                List.of(new Service(service.supply(), 0, 2, 0, 31)), List.of(), List.of(riding), window));
        assertEquals(40, continued.freight().get("f").deliveredAtSeconds().orElseThrow());
        var invalid = new Freight("invalid", "A", "C", 0, 0, 100, 0, OptionalDouble.of(30),
                OptionalDouble.empty(), OptionalDouble.empty(), false, Optional.of(service.supply().scheduled().key()));
        assertThrows(IllegalArgumentException.class, () -> new Inputs(31, 100, 1, 1, 100,
                List.of(service), List.of(), List.of(invalid), window));
    }

    @Test void passengersBoardFirstEvenWhenFreightWasReadyEarlier() {
        Service service = service("r", 1, 0, twoCalls());
        Result result = project(List.of(service), List.of(pax("p", "A", "B", 9, 1)),
                List.of(freight("f", "A", "B", 0, 0, 50, 0)), 1, 1);
        StopLoad first = result.loads().getFirst();
        assertEquals(1, first.passengerMassBoarded()); assertEquals(0, first.freightBoarded());
        assertEquals(1, result.passengers().get("p").meanWaitingSeconds());
        FreightResult cargo = result.freight().get("f");
        assertEquals(Completion.UNDELIVERED_AT_HORIZON, cargo.completion());
        assertTrue(cargo.firstBoardedAtSeconds().isEmpty());
        assertEquals(100, cargo.waitingAccountSeconds()); assertEquals(50, cargo.latenessAccountSeconds());
        assertTrue(cargo.missedDeadlineOrUnserved());
    }

    @Test void physicalCapacityBindsEvenWhenShareAndLoadGatePermitMoreFreight() {
        Result result = project(List.of(service("r", 2, 0, twoCalls())),
                List.of(pax("p", "A", "B", 0, 1)),
                List.of(freight("a", "A", "B"), freight("b", "A", "B")), 1, 1);
        assertEquals(1, result.loads().getFirst().freightBoarded());
        assertEquals(1, result.loads().getFirst().passengersAfterBoarding());
        assertTrue(result.freight().get("a").firstBoardedAtSeconds().isPresent());
        assertTrue(result.freight().get("b").firstBoardedAtSeconds().isEmpty());
        for (StopLoad load : result.loads()) assertTrue(load.passengersAfterBoarding() + load.freightAfterBoarding() <= 2);
    }

    @Test void freightShareUsesFloorNotRounding() {
        List<Service> services = List.of(service("r", 3, 0, twoCalls()));
        List<Freight> cargo = List.of(freight("a", "A", "B"), freight("b", "A", "B"));
        assertEquals(1, project(services, List.of(), cargo, 0.5, 1).loads().getFirst().freightBoarded());
        assertEquals(0, project(services, List.of(), cargo, Math.nextDown(1.0 / 3.0), 1).loads().getFirst().freightBoarded());
        assertEquals(1, project(services, List.of(), cargo, 1.0 / 3.0, 1).loads().getFirst().freightBoarded());
    }

    @Test void loadGateEqualityIsAllowedButStrictlyGreaterPassengerLoadIsNot() {
        List<Service> services = List.of(service("r", 4, 0, twoCalls()));
        List<Freight> cargo = List.of(freight("f", "A", "B"));
        assertEquals(1, project(services, List.of(pax("p", "A", "B", 0, 2)), cargo, 1, 0.5)
                .loads().getFirst().freightBoarded());
        assertEquals(0, project(services, List.of(pax("p", "A", "B", 0, 2.000001)), cargo, 1, 0.5)
                .loads().getFirst().freightBoarded());
    }

    @Test void passengerAlightingAtAnIntermediateArrivalReleasesCapacityForFreight() {
        Result result = project(List.of(service("r", 2, 0, threeCalls())),
                List.of(pax("p", "A", "B", 0, 2)), List.of(freight("f", "B", "C")), 1, 1);
        assertEquals(2, result.loads().get(0).passengersAfterBoarding());
        assertEquals(0, result.loads().get(1).passengersAfterBoarding());
        assertEquals(1, result.loads().get(1).freightBoarded());
        assertEquals(30, result.freight().get("f").firstBoardedAtSeconds().orElseThrow());
        assertEquals(40, result.freight().get("f").deliveredAtSeconds().orElseThrow());
    }

    @Test void fractionalPassengerCohortSplitsAcrossLaterTrainsWithoutLosingMass() {
        Result result = project(List.of(service("a", 1, 0, twoCalls()), service("b", 1, 10, twoCalls()),
                        service("c", 1, 20, twoCalls())),
                List.of(pax("p", "A", "B", 0, 2.5)), List.of(), 1, 1);
        PassengerResult passenger = result.passengers().get("p");
        assertEquals(0, passenger.unboardedMass(), EPS);
        assertEquals(18, passenger.meanWaitingSeconds(), EPS); // (1*10 + 1*20 + .5*30) / 2.5
        assertEquals(List.of(1.0, 1.0, 0.5), result.loads().stream().filter(load -> load.call() == 0)
                .map(StopLoad::passengerMassBoarded).toList());
        assertEquals(2.5, result.loads().stream().mapToDouble(StopLoad::passengerMassBoarded).sum(), EPS);
    }

    @Test void unboardedFractionContributesHorizonCensoredWaitingInsteadOfZero() {
        Passenger passenger = new Passenger("p", "A", "B", 0, 2.5, true, Optional.empty());
        Result result = project(List.of(service("r", 1, 0, twoCalls())), List.of(passenger), List.of(), 1, 1);
        PassengerResult outcome = result.passengers().get("p");
        assertEquals(1.5, outcome.unboardedMass(), EPS);
        assertEquals(64, outcome.meanWaitingSeconds(), EPS); // (10 + 1.5*100) / 2.5
        assertTrue(outcome.protectedCohort());
    }

    @Test void repeatedNonbinaryFractionSplitsDoNotProduceNegativeResidualOrFalseFailure() {
        var services = new ArrayList<Service>();
        var passengers = new ArrayList<Passenger>();
        for (int i = 0; i < 4; i++) {
            services.add(service("r" + i, 1, 20 * i, threeCalls()));
            passengers.add(pax("upstream-" + i, "A", "C", 20 * i, 0.6));
        }
        passengers.add(pax("target", "B", "C", 0, 1.3));
        Result result = assertDoesNotThrow(() -> project(services, passengers, List.of(), 1, 1));
        PassengerResult target = result.passengers().get("target");
        assertEquals(0, target.unboardedMass(), EPS);
        assertEquals(69.0 / 1.3, target.meanWaitingSeconds(), EPS);
        assertEquals(1.3, result.loads().stream().filter(load -> load.call() == 1)
                .mapToDouble(StopLoad::passengerMassBoarded).sum(), EPS);
    }

    @Test void destinationsMustBeExactAndStrictlyLaterNotAnAliasOrEarlierStop() {
        Result result = project(List.of(service("r", 5, 0, threeCalls())),
                List.of(pax("alias", "A", "C-alias", 0, 1), pax("backward", "B", "A", 0, 1),
                        pax("same", "A", "A", 0, 1), pax("valid", "A", "C", 0, 1)), List.of(), 1, 1);
        for (String id : List.of("alias", "backward", "same")) assertEquals(1, result.passengers().get(id).unboardedMass());
        assertEquals(0, result.passengers().get("valid").unboardedMass());
        assertEquals(1, result.loads().getFirst().passengerMassBoarded());
    }

    @Test void boardingAndAlightingPermissionsAreRespected() {
        List<Call> calls = List.of(new Call("A", 0, 10, true, false, true, 0),
                new Call("B", 20, 30, true, true, true, 0),
                new Call("C", 40, 50, true, true, false, 0));
        Result result = project(List.of(service("r", 3, 0, calls)),
                List.of(pax("p", "A", "B", 0, 1)),
                List.of(freight("blocked-boarding", "A", "B"), freight("blocked-alighting", "B", "C")), 1, 1);
        assertEquals(1, result.passengers().get("p").unboardedMass());
        assertTrue(result.freight().values().stream().allMatch(cargo -> cargo.firstBoardedAtSeconds().isEmpty()));
        assertTrue(result.loads().stream().allMatch(load -> load.passengerMassBoarded() == 0 && load.freightBoarded() == 0));
    }

    @Test void lastAllowedIndexExactlyMatchesTheIndependentLinearPredicate() {
        List<String> stops = List.of("A", "B", "C", "D");
        for (int mask = 0; mask < 16; mask++) {
            var calls = new ArrayList<Call>();
            for (int i = 0; i < stops.size(); i++) calls.add(new Call(stops.get(i), i * 20, i * 20 + 10,
                    true, true, (mask & (1 << i)) != 0, 0));
            Trip trip = service("r", 1, 0, calls).supply();
            var index = new AlightingIndex(trip);
            for (int current = 0; current < calls.size(); current++) {
                for (String destination : List.of("A", "B", "C", "D", "D-alias", "unknown")) {
                    assertEquals(linearAllowsAlighting(trip, current, destination), index.allowsAfter(current, destination),
                            "mask=" + mask + ", current=" + current + ", destination=" + destination);
                }
            }
            assertThrows(IllegalArgumentException.class, () -> index.allowsAfter(-1, "D"));
            assertThrows(IllegalArgumentException.class, () -> index.allowsAfter(calls.size(), "D"));
            assertThrows(IllegalArgumentException.class, () -> new Service(trip, 0, -1, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new Service(trip, 0, calls.size(), 0, 0));
        }
    }

    @Test void fullBoardingBreakRetainsTheCompleteFractionalResultAndRawFloatingPointBits() {
        Service a = service("a", 1, 0, twoCalls()), z = service("z", 1, 0, twoCalls());
        var services = new ArrayList<>(List.of(z, a));
        var passengers = new ArrayList<>(List.of(pax("z", "A", "B", 0, .9), pax("alias", "A", "B-alias", 0, .2),
                pax("later", "A", "B", 11, .4), pax("a", "A", "B", 0, .6)));
        var cargo = List.of(freight("f", "A", "B"));
        // Closed arithmetic oracle: preserve the two actual fractional boardings,
        // not a rounded total or a copy of the event-processing implementation.
        double firstPiece = 1.0 - .6, remainder = .9 - firstPiece;
        var expected = new Result(Map.of(
                "a", new PassengerResult(.6, (.6 * 10) / .6, 0, false),
                "z", new PassengerResult(.9, (firstPiece * 10 + remainder * 10) / .9, 0, false),
                "alias", new PassengerResult(.2, (.2 * 100) / .2, .2, false),
                "later", new PassengerResult(.4, (.4 * (100 - 11)) / .4, .4, false)),
                Map.of("f", new FreightResult(0, 100, OptionalDouble.empty(), OptionalDouble.empty(),
                        Completion.UNDELIVERED_AT_HORIZON, 100, 0, true)),
                List.of(new StopLoad(a.supply().scheduled().key(), 0, 0, 10, 1, 0, 1, 0),
                        new StopLoad(z.supply().scheduled().key(), 0, 0, 10, remainder, 0, remainder, 0),
                        new StopLoad(a.supply().scheduled().key(), 1, 20, 30, 0, 0, 0, 0),
                        new StopLoad(z.supply().scheduled().key(), 1, 20, 30, 0, 0, 0, 0)), 8);
        assertResultBits(expected, project(services, passengers, cargo, 1, 1));
        Collections.reverse(services); Collections.reverse(passengers);
        assertResultBits(expected, project(services, passengers, cargo, 1, 1));
        assertResultBits(expected, project(services, passengers, cargo, 1, 1));
    }

    @Test void minimumStopDurationPropagatesAndDeliveryIncludesEgressAfterArrival() {
        List<Call> calls = List.of(new Call("A", 0, 10, true, true, true, 25),
                new Call("B", 20, 30, true, true, true, 20), new Call("C", 40, 50));
        Result result = project(List.of(service("r", 2, 0, calls)), List.of(),
                List.of(freight("f", "A", "C", 0, 0, 70, 7)), 1, 1);
        assertEquals(List.of(0.0, 35.0, 65.0), result.loads().stream().map(StopLoad::arrivalSeconds).toList());
        assertEquals(List.of(25.0, 55.0, 75.0), result.loads().stream().map(StopLoad::departureSeconds).toList());
        FreightResult cargo = result.freight().get("f");
        assertEquals(25, cargo.firstBoardedAtSeconds().orElseThrow());
        assertEquals(72, cargo.deliveredAtSeconds().orElseThrow());
        assertEquals(25, cargo.waitingAccountSeconds()); assertEquals(2, cargo.latenessAccountSeconds());
    }

    @Test void initiallyRidingPassengerAndFreightConsumeCapacityUntilTheirActualNextStop() {
        Service base = service("r", 2, 0, threeCalls());
        TripKey trip = base.supply().scheduled().key();
        Service continuing = new Service(base.supply(), 0, 1, 0, 15);
        Passenger riding = new Passenger("riding", "A", "B", 0, 1, false, Optional.of(trip));
        Freight cargo = new Freight("f", "A", "C", 0, 0, 100, 5, OptionalDouble.of(10),
                OptionalDouble.empty(), OptionalDouble.empty(), false, Optional.of(trip));
        Result result = projection.project(new Inputs(15, 100, 1, 1, 100, List.of(continuing),
                List.of(riding, pax("new", "B", "C", 15, 2)), List.of(cargo)));
        assertEquals(List.of(1, 2), result.loads().stream().map(StopLoad::call).toList());
        StopLoad middle = result.loads().getFirst();
        assertEquals(1, middle.passengersAfterBoarding()); assertEquals(1, middle.freightAfterBoarding());
        assertEquals(1, middle.passengerMassBoarded()); assertEquals(0, middle.freightBoarded());
        assertEquals(1, result.passengers().get("new").unboardedMass());
        assertEquals(10, result.freight().get("f").firstBoardedAtSeconds().orElseThrow());
        assertEquals(45, result.freight().get("f").deliveredAtSeconds().orElseThrow());
    }

    @Test void initialPhysicalOccupancyAboveCapacityFailsInsteadOfBeingClipped() {
        Service service = service("r", 1, 0, twoCalls()); TripKey key = service.supply().scheduled().key();
        Passenger passenger = new Passenger("p", "A", "B", 0, 0.5, false, Optional.of(key));
        Freight cargo = new Freight("f", "A", "B", 0, 0, 100, 0, OptionalDouble.of(0),
                OptionalDouble.empty(), OptionalDouble.empty(), false, Optional.of(key));
        Inputs input = new Inputs(0, 100, 1, 1, 100, List.of(service), List.of(passenger), List.of(cargo));
        assertThrows(IllegalArgumentException.class, () -> projection.project(input));
    }

    @Test void delayAndFirstArrivalFloorDoNotReplayCompletedCallsOrMoveBeforeDecision() {
        Service base = service("r", 2, 0, threeCalls());
        Service service = new Service(base.supply(), 0, 1, 5, 40);
        Result result = projection.project(new Inputs(25, 100, 1, 1, 100, List.of(service), List.of(), List.of()));
        assertEquals(List.of(1, 2), result.loads().stream().map(StopLoad::call).toList());
        assertEquals(List.of(40.0, 50.0), result.loads().stream().map(StopLoad::arrivalSeconds).toList());
        assertEquals(List.of(40.0, 60.0), result.loads().stream().map(StopLoad::departureSeconds).toList());
    }

    @Test void releasedButNotYetReadyFreightCannotCatchEarlierOpportunity() {
        Result result = project(List.of(service("r", 2, 0, twoCalls())), List.of(),
                List.of(freight("f", "A", "B", 0, 11, 50, 0)), 1, 1);
        assertEquals(0, result.loads().getFirst().freightBoarded());
        assertTrue(result.freight().get("f").firstBoardedAtSeconds().isEmpty());
        assertEquals(100, result.freight().get("f").waitingAccountSeconds());
    }

    @Test void knownFreightQueueAgeIsNotErasedAtTheForecastStart() {
        Result result = projection.project(new Inputs(10, 100, 1, 1, 100,
                List.of(service("r", 1, 10, twoCalls())), List.of(),
                List.of(freight("a-newer", "A", "B", 0, 5, 50, 0),
                        freight("z-older", "A", "B", 0, 0, 50, 0))));
        assertEquals(20, result.freight().get("z-older").firstBoardedAtSeconds().orElseThrow());
        assertTrue(result.freight().get("a-newer").firstBoardedAtSeconds().isEmpty());
    }

    @Test void readyExactlyAtDepartureIsEligible() {
        Result result = project(List.of(service("r", 2, 0, twoCalls())),
                List.of(pax("p", "A", "B", 10, 1)), List.of(freight("f", "A", "B", 0, 10, 50, 0)), 1, 1);
        assertEquals(1, result.loads().getFirst().passengerMassBoarded());
        assertEquals(1, result.loads().getFirst().freightBoarded());
    }

    @Test void observedDeliveryIsRetainedAndAbortedDemandIsNeverRequeued() {
        Freight delivered = new Freight("done", "A", "B", 0, 0, 15, 0, OptionalDouble.of(10),
                OptionalDouble.of(20), OptionalDouble.empty(), false, Optional.empty());
        Freight aborted = new Freight("aborted", "A", "B", 0, 0, 20, 0, OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), true, Optional.empty());
        Result result = projection.project(new Inputs(30, 100, 1, 1, 100,
                List.of(service("r", 3, 30, twoCalls())), List.of(), List.of(delivered, aborted)));
        assertEquals(Completion.DELIVERED_OBSERVED, result.freight().get("done").completion());
        assertEquals(20, result.freight().get("done").deliveredAtSeconds().orElseThrow());
        assertEquals(10, result.freight().get("done").waitingAccountSeconds());
        assertEquals(5, result.freight().get("done").latenessAccountSeconds());
        assertEquals(Completion.ABORTED_OBSERVED, result.freight().get("aborted").completion());
        assertTrue(result.freight().get("aborted").firstBoardedAtSeconds().isEmpty());
        assertTrue(result.freight().get("aborted").deliveredAtSeconds().isEmpty());
        assertTrue(result.loads().stream().allMatch(load -> load.freightBoarded() == 0));
    }

    @Test void predictedPendingDeliveryIsDistinctFromObservedDeliveryAndNeedsNoNewBoarding() {
        Freight pending = new Freight("f", "A", "B", 0, 0, 35, 5, OptionalDouble.of(10),
                OptionalDouble.empty(), OptionalDouble.of(40), false, Optional.empty());
        Result result = projection.project(new Inputs(30, 100, 1, 1, 100, List.of(), List.of(), List.of(pending)));
        assertEquals(Completion.DELIVERED_PREDICTED, result.freight().get("f").completion());
        assertEquals(40, result.freight().get("f").deliveredAtSeconds().orElseThrow());
        assertEquals(10, result.freight().get("f").firstBoardedAtSeconds().orElseThrow());
        assertEquals(5, result.freight().get("f").latenessAccountSeconds());
        assertTrue(result.loads().isEmpty());
    }

    @Test void egressBeyondHorizonIsUndeliveredAndKeepsFirstBoardingAndPositiveLatenessAccount() {
        Result result = project(List.of(service("r", 2, 0, twoCalls())), List.of(),
                List.of(freight("f", "A", "B", 0, 0, 50, 100)), 1, 1);
        FreightResult cargo = result.freight().get("f");
        assertEquals(Completion.UNDELIVERED_AT_HORIZON, cargo.completion());
        assertEquals(10, cargo.firstBoardedAtSeconds().orElseThrow());
        assertTrue(cargo.deliveredAtSeconds().isEmpty());
        assertEquals(10, cargo.waitingAccountSeconds()); assertEquals(50, cargo.latenessAccountSeconds());
        assertTrue(cargo.missedDeadlineOrUnserved());
        assertEquals(0, result.loads().getLast().freightAfterBoarding()); // left vehicle, not yet delivered
    }

    @Test void noSupplyDoesNotGiveUnservedDemandZeroWaitingOrLateTime() {
        Result result = project(List.of(), List.of(pax("p", "A", "B", 10, 1)),
                List.of(freight("f", "A", "B", 0, 0, 60, 0)), 1, 1);
        assertEquals(90, result.passengers().get("p").meanWaitingSeconds());
        assertEquals(100, result.freight().get("f").waitingAccountSeconds());
        assertEquals(40, result.freight().get("f").latenessAccountSeconds());
        assertEquals(0, result.processedStopEvents());
    }

    @Test void deliveryExactlyAtHorizonAndDeadlineIsNotLostOrLate() {
        Result result = project(List.of(service("r", 1, 0, twoCalls())), List.of(),
                List.of(freight("f", "A", "B", 0, 0, 100, 80)), 1, 1);
        FreightResult cargo = result.freight().get("f");
        assertEquals(100, cargo.deliveredAtSeconds().orElseThrow());
        assertEquals(Completion.DELIVERED_PREDICTED, cargo.completion());
        assertEquals(0, cargo.latenessAccountSeconds()); assertFalse(cargo.missedDeadlineOrUnserved());
    }

    @Test void sameTimeTripAndDemandTieBreaksAreIndependentOfInputCollectionOrder() {
        var services = new ArrayList<>(List.of(service("z", 1, 0, twoCalls()), service("a", 1, 0, twoCalls())));
        var passengers = new ArrayList<>(List.of(pax("z-pax", "A", "B", 0, 0.5), pax("a-pax", "A", "B", 0, 0.5)));
        var freight = new ArrayList<>(List.of(freight("z-freight", "A", "B"), freight("a-freight", "A", "B")));
        Result first = project(services, passengers, freight, 1, 1);
        Collections.reverse(services); Collections.reverse(passengers); Collections.reverse(freight);
        assertEquals(first, project(services, passengers, freight, 1, 1));
        StopLoad firstStop = first.loads().getFirst();
        assertEquals("a", firstStop.trip().route()); assertEquals(1, firstStop.passengerMassBoarded());
        assertTrue(first.freight().get("a-freight").firstBoardedAtSeconds().isPresent());
        assertTrue(first.freight().get("z-freight").firstBoardedAtSeconds().isEmpty());
    }

    @Test void eventBudgetExhaustionFailsInsteadOfReturningTruncatedResults() {
        List<Service> services = List.of(service("r", 2, 0, threeCalls()));
        Inputs tooSmall = new Inputs(0, 100, 1, 1, 5, services, List.of(), List.of());
        var error = assertThrows(IllegalArgumentException.class, () -> projection.project(tooSmall));
        assertTrue(error.getMessage().contains("budget"));
        Result result = projection.project(new Inputs(0, 100, 1, 1, 6, services, List.of(), List.of()));
        assertEquals(6, result.processedStopEvents()); assertEquals(3, result.loads().size());
    }

    @Test void fullAtEntryStillProcessesEveryStopAndEnforcesTheSameEventBudget() {
        var services = List.of(service("r", 0, 0, threeCalls()));
        var passengers = List.of(pax("p", "A", "C", 0, .6), pax("later", "B", "C", 0, .9));
        var cargo = List.of(freight("f", "A", "C"));
        var error = assertThrows(IllegalArgumentException.class, () -> projection.project(
                new Inputs(0, 100, 1, 1, 5, services, passengers, cargo)));
        assertTrue(error.getMessage().contains("budget"));
        Result result = projection.project(new Inputs(0, 100, 1, 1, 6, services, passengers, cargo));
        assertEquals(6, result.processedStopEvents()); assertEquals(3, result.loads().size());
        assertTrue(result.loads().stream().allMatch(load -> load.passengersAfterBoarding() == 0
                && load.freightAfterBoarding() == 0 && load.passengerMassBoarded() == 0 && load.freightBoarded() == 0));
        for (Passenger passenger : passengers) {
            assertBits(passenger.mass(), result.passengers().get(passenger.id()).unboardedMass());
            assertBits((passenger.mass() * 100) / passenger.mass(), result.passengers().get(passenger.id()).meanWaitingSeconds());
        }
        assertEquals(Completion.UNDELIVERED_AT_HORIZON, result.freight().get("f").completion());
    }

    @Test void eventsAfterHorizonDoNotSpendTheFiniteStopBudget() {
        Result result = projection.project(new Inputs(0, 5, 1, 1, 1,
                List.of(service("r", 1, 0, twoCalls())), List.of(), List.of()));
        assertEquals(1, result.processedStopEvents()); assertTrue(result.loads().isEmpty());
    }

    @Test void futureReleasesAndEventsLabelledObservedAreRejected() {
        Freight futureRelease = freight("f", "A", "B", 11, 11, 50, 0);
        Freight futureBoard = new Freight("f", "A", "B", 0, 0, 50, 0, OptionalDouble.of(11),
                OptionalDouble.empty(), OptionalDouble.empty(), false, Optional.empty());
        Freight futureDelivery = new Freight("f", "A", "B", 0, 0, 50, 0, OptionalDouble.of(5),
                OptionalDouble.of(11), OptionalDouble.empty(), false, Optional.empty());
        for (Freight invalid : List.of(futureRelease, futureBoard, futureDelivery)) {
            assertThrows(IllegalArgumentException.class, () -> new Inputs(10, 100, 1, 1, 10,
                    List.of(), List.of(), List.of(invalid)));
        }
        // Future ready times are explicit forecasts, not future observations.
        assertDoesNotThrow(() -> new Inputs(10, 100, 1, 1, 10, List.of(),
                List.of(pax("p", "A", "B", 20, 1)), List.of(freight("f", "A", "B", 5, 20, 50, 0))));
    }

    @Test void abortedAndPendingDeliveryCannotBeCombined() {
        assertThrows(IllegalArgumentException.class, () -> new Freight("f", "A", "B", 0, 0, 50, 0,
                OptionalDouble.of(5), OptionalDouble.empty(), OptionalDouble.of(30), true, Optional.empty()));
    }

    @Test void pendingDeliveryCannotPrecedeFirstBoarding() {
        assertThrows(IllegalArgumentException.class, () -> new Freight("f", "A", "B", 0, 0, 50, 0,
                OptionalDouble.of(20), OptionalDouble.empty(), OptionalDouble.of(10), false, Optional.empty()));
    }

    @Test void futureReadyPassengerOrFreightCannotBeLabelledInitiallyRiding() {
        Service service = service("r", 2, 0, twoCalls()); TripKey key = service.supply().scheduled().key();
        Passenger passenger = new Passenger("p", "A", "B", 20, 1, false, Optional.of(key));
        Freight cargo = new Freight("f", "A", "B", 0, 20, 100, 0, OptionalDouble.of(10),
                OptionalDouble.empty(), OptionalDouble.empty(), false, Optional.of(key));
        assertThrows(IllegalArgumentException.class, () -> new Inputs(10, 100, 1, 1, 100,
                List.of(service), List.of(passenger), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Inputs(10, 100, 1, 1, 100,
                List.of(service), List.of(), List.of(cargo)));
    }

    @Test void unsupportedDeliveredWithoutFirstBoardingIsRejectedExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> new Freight("f", "A", "B", 0, 0, 50, 0,
                OptionalDouble.empty(), OptionalDouble.of(20), OptionalDouble.empty(), false, Optional.empty()));
    }

    @Test void repeatedIdentityTripOrVehicleAndUnknownInitialTripFailClosed() {
        Service service = service("r", 2, 0, twoCalls());
        assertThrows(IllegalArgumentException.class, () -> new Inputs(0, 100, 1, 1, 100,
                List.of(service, service), List.of(), List.of()));
        var alternateKey = new TripKey("line", "other", "d");
        Trip sharedVehicle = new Trip(new ScheduledTrip(alternateKey, service.supply().scheduled().vehicleId(), 0),
                2, 1, 1, DwellMode.COMPLEX_SERIAL, twoCalls());
        assertThrows(IllegalArgumentException.class, () -> new Inputs(0, 100, 1, 1, 100,
                List.of(service, new Service(sharedVehicle, 0, 0, 0, 0)), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Inputs(0, 100, 1, 1, 100, List.of(service),
                List.of(pax("same", "A", "B", 0, 1)), List.of(freight("same", "A", "B"))));
        Passenger orphan = new Passenger("p", "A", "B", 0, 1, false, Optional.of(alternateKey));
        assertThrows(IllegalArgumentException.class, () -> new Inputs(0, 100, 1, 1, 100, List.of(service), List.of(orphan), List.of()));
    }

    @Test void inputAndOutputCollectionsAreDetachedAndImmutable() {
        var services = new ArrayList<>(List.of(service("r", 2, 0, twoCalls())));
        var passengers = new ArrayList<>(List.of(pax("p", "A", "B", 0, 1)));
        var cargo = new ArrayList<>(List.of(freight("f", "A", "B")));
        Inputs input = new Inputs(0, 100, 1, 1, 100, services, passengers, cargo);
        services.clear(); passengers.clear(); cargo.clear();
        Result result = projection.project(input);
        assertEquals(2, result.loads().size()); assertEquals(1, result.passengers().size()); assertEquals(1, result.freight().size());
        assertThrows(UnsupportedOperationException.class, () -> input.services().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.passengers().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.freight().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.loads().clear());
        assertEquals(result, projection.project(input));
    }

    private Result project(List<Service> services, List<Passenger> passengers, List<Freight> freight, double share, double gate) {
        return projection.project(new Inputs(0, 100, share, gate, 1000, services, passengers, freight));
    }

    private static boolean linearAllowsAlighting(Trip trip, int current, String destination) {
        for (int i = current + 1; i < trip.calls().size(); i++) {
            Call call = trip.calls().get(i);
            if (call.stopId().equals(destination) && call.allowAlighting()) return true;
        }
        return false;
    }

    private static void assertResultBits(Result expected, Result actual) {
        assertEquals(expected, actual);
        expected.passengers().forEach((id, value) -> {
            var found = actual.passengers().get(id);
            assertBits(value.mass(), found.mass()); assertBits(value.meanWaitingSeconds(), found.meanWaitingSeconds());
            assertBits(value.unboardedMass(), found.unboardedMass());
        });
        expected.freight().forEach((id, value) -> {
            var found = actual.freight().get(id);
            assertBits(value.releasedAtSeconds(), found.releasedAtSeconds()); assertBits(value.deadlineSeconds(), found.deadlineSeconds());
            assertBits(value.waitingAccountSeconds(), found.waitingAccountSeconds()); assertBits(value.latenessAccountSeconds(), found.latenessAccountSeconds());
            value.firstBoardedAtSeconds().ifPresent(at -> assertBits(at, found.firstBoardedAtSeconds().orElseThrow()));
            value.deliveredAtSeconds().ifPresent(at -> assertBits(at, found.deliveredAtSeconds().orElseThrow()));
        });
        for (int i = 0; i < expected.loads().size(); i++) {
            var value = expected.loads().get(i); var found = actual.loads().get(i);
            assertBits(value.arrivalSeconds(), found.arrivalSeconds()); assertBits(value.departureSeconds(), found.departureSeconds());
            assertBits(value.passengersAfterBoarding(), found.passengersAfterBoarding()); assertBits(value.passengerMassBoarded(), found.passengerMassBoarded());
        }
    }

    private static void assertBits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private static Passenger pax(String id, String origin, String destination, double ready, double mass) {
        return new Passenger(id, origin, destination, ready, mass, false, Optional.empty());
    }

    private static Freight freight(String id, String origin, String destination) {
        return freight(id, origin, destination, 0, 0, 100, 0);
    }

    private static Freight freight(String id, String origin, String destination, double release, double ready, double deadline, double egress) {
        return new Freight(id, origin, destination, release, ready, deadline, egress,
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(), false, Optional.empty());
    }

    private static Service service(String route, int capacity, double origin, List<Call> calls) {
        TripKey key = new TripKey("line", route, "d");
        Trip trip = new Trip(new ScheduledTrip(key, "v-" + route, origin), capacity, 1, 1, DwellMode.COMPLEX_SERIAL, calls);
        return new Service(trip, origin, 0, 0, 0);
    }

    private static List<Call> twoCalls() { return List.of(new Call("A", 0, 10), new Call("B", 20, 30)); }
    private static List<Call> threeCalls() { return List.of(new Call("A", 0, 10), new Call("B", 20, 30), new Call("C", 40, 50)); }
}
