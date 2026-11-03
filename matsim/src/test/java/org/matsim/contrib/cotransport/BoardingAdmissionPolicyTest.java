package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.ActorClass;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.BoardingDecision;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.DenialReason;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoardingAdmissionPolicyTest {
	@Test
	void physicalCapacityPrecedesEvenInvalidClassOrServiceAndDoesNotInvokeAccessPolicy() {
		AtomicInteger accessCalls = new AtomicInteger();
		BoardingAdmissionPolicy policy = BoardingAdmissionPolicy.compose(context -> {
			accessCalls.incrementAndGet();
			return BoardingDecision.allow();
		}, true);
		assertEquals(DenialReason.NATIVE_CAPACITY, policy.evaluate(context(ActorClass.INVALID,
				false, false, false, 10, 9, 0, 1, 0, .10, .90)).denialReason());
		assertEquals(0, accessCalls.get());
	}

	@Test
	void zeroNativeCapacityCannotBeOverriddenByAFullShareAllowance() {
		assertEquals(DenialReason.NATIVE_CAPACITY, BoardingAdmissionPolicy.mixed().evaluate(
				context(ActorClass.FREIGHT, true, true, false, 0, 0, 0, 0, 0, 1, 1)).denialReason());
	}

	@Test
	void passengersAreNotSubjectToFreightShareOrLoadGate() {
		assertTrue(BoardingAdmissionPolicy.mixed().evaluate(context(ActorClass.PASSENGER,
				true, true, false, 100, 98, 0, 0, 0, 0, 0)).allowed());
	}

	@Test
	void floorLimitIncludesTheCandidateAndPreviousSameStopAcceptances() {
		BoardingAdmissionPolicy policy = BoardingAdmissionPolicy.mixed();
		assertTrue(policy.evaluate(context(ActorClass.FREIGHT, true, true, false,
				109, 20, 7, 0, 2, .10, .90)).allowed());
		assertEquals(DenialReason.FREIGHT_SHARE_CAP, policy.evaluate(context(ActorClass.FREIGHT,
				true, true, false, 109, 20, 7, 0, 3, .10, .90)).denialReason());
	}

	@Test
	void passengerGateIncludesSameStopPassengersAndUsesStrictGreaterThan() {
		BoardingAdmissionPolicy policy = BoardingAdmissionPolicy.mixed();
		assertTrue(policy.evaluate(context(ActorClass.FREIGHT, true, true, false,
				100, 85, 0, 5, 0, .10, .90)).allowed());
		assertEquals(DenialReason.PASSENGER_LOAD_GATE, policy.evaluate(context(ActorClass.FREIGHT,
				true, true, false, 100, 85, 0, 6, 0, .10, .90)).denialReason());
	}

	@Test
	void ineligibleRequestedServiceAndInactiveFreightHaveExplicitReason() {
		BoardingAdmissionPolicy policy = BoardingAdmissionPolicy.mixed();
		assertEquals(DenialReason.INELIGIBLE_SERVICE, policy.evaluate(context(ActorClass.FREIGHT,
				true, false, false, 100, 0, 0, 0, 0, .10, .90)).denialReason());
		assertEquals(DenialReason.INELIGIBLE_SERVICE, policy.evaluate(context(ActorClass.FREIGHT,
				false, true, false, 100, 0, 0, 0, 0, .10, .90)).denialReason());
	}

	@Test
	void unknownActorClassIsRejectedRatherThanTreatedAsPassenger() {
		for (ActorClass actorClass : new ActorClass[]{ActorClass.INVALID, null}) {
			assertEquals(DenialReason.INVALID_AGENT_CLASS, BoardingAdmissionPolicy.mixed().evaluate(
					context(actorClass, true, true, false, 100, 0, 0, 0, 0, .10, .90)).denialReason());
		}
	}

	@Test
	void dedicatedIsolationIsTwoSidedAndFreightIsNotSubjectToMixedShareLimit() {
		BoardingAdmissionPolicy policy = BoardingAdmissionPolicy.dedicated();
		assertTrue(policy.evaluate(context(ActorClass.FREIGHT, true, true, true,
				100, 0, 98, 0, 0, .10, .90)).allowed());
		assertTrue(policy.evaluate(context(ActorClass.PASSENGER, true, true, false,
				100, 98, 0, 0, 0, .10, .90)).allowed());
		assertEquals(DenialReason.ISOLATION_RULE, policy.evaluate(context(ActorClass.PASSENGER,
				true, true, true, 100, 0, 0, 0, 0, .10, .90)).denialReason());
		assertEquals(DenialReason.ISOLATION_RULE, policy.evaluate(context(ActorClass.FREIGHT,
				true, true, false, 100, 0, 0, 0, 0, .10, .90)).denialReason());
	}

	@Test
	void admissionHasNoStateAndRepeatedCallsDoNotReserveSeats() {
		BoardingAdmissionPolicy policy = BoardingAdmissionPolicy.mixed();
		var context = context(ActorClass.FREIGHT, true, true, false, 100, 0, 9, 0, 0, .10, .90);
		assertTrue(policy.evaluate(context).allowed());
		assertEquals(policy.evaluate(context), policy.evaluate(context));
		assertEquals(9, context.freightOnBoard());
	}

	@Test
	void invalidCountsAndNonfiniteThresholdsFailAtTheImmutableBoundary() {
		assertThrows(IllegalArgumentException.class, () -> context(ActorClass.PASSENGER,
				true, true, false, 100, -1, 0, 0, 0, .10, .90));
		assertThrows(IllegalArgumentException.class, () -> context(ActorClass.FREIGHT,
				true, true, false, 100, 0, 0, 0, 0, Double.NaN, .90));
		assertThrows(IllegalArgumentException.class, () -> context(ActorClass.FREIGHT,
				true, true, false, 100, 0, 0, 0, 0, .10, Double.POSITIVE_INFINITY));
	}

	@Test
	void occupancyArithmeticCannotWrapToCreateCapacity() {
		assertEquals(DenialReason.NATIVE_CAPACITY, BoardingAdmissionPolicy.mixed().evaluate(context(
				ActorClass.FREIGHT, true, true, false, Integer.MAX_VALUE, Integer.MAX_VALUE,
				Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1)).denialReason());
	}

	@Test
	void decisionCannotBeBothAllowedAndDeniedOrLackAReason() {
		assertThrows(IllegalArgumentException.class, () -> new BoardingDecision(true, DenialReason.ISOLATION_RULE));
		assertThrows(IllegalArgumentException.class, () -> new BoardingDecision(false, null));
	}

	private static BoardingAdmissionPolicy.Context context(ActorClass actor, boolean active, boolean eligible,
			boolean dedicated, int capacity, int passengers, int freight, int acceptedPassengers,
			int acceptedFreight, double share, double gate) {
		return new BoardingAdmissionPolicy.Context(actor, active, eligible, dedicated, capacity,
				passengers, freight, acceptedPassengers, acceptedFreight, share, gate);
	}
}
