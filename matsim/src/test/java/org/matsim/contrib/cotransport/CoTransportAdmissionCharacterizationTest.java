package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retains the characterized baseline and regressions for the documented epsilon correction. */
class CoTransportAdmissionCharacterizationTest {

	@Test
	void freightLimitUsesFloorAndAcceptsTheExactLimit() {
		assertTrue(allows(109, 9, 20, 0, 0, 0.10, 0.90));
		assertFalse(allows(109, 10, 20, 0, 0, 0.10, 0.90));
	}

	@Test
	void passengerGateAcceptsEqualityAndRejectsTheNextPassenger() {
		assertTrue(allows(100, 0, 90, 0, 0, 0.10, 0.90));
		assertFalse(allows(100, 0, 91, 0, 0, 0.10, 0.90));
	}

	@Test
	void alreadyAcceptedFreightConsumesTheSameStopFreightAllowance() {
		assertTrue(allows(100, 7, 60, 2, 0, 0.10, 0.90));
		assertFalse(allows(100, 7, 60, 3, 0, 0.10, 0.90));
	}

	@Test
	void alreadyAcceptedPassengersCountTowardsThePassengerGate() {
		assertTrue(allows(100, 0, 85, 0, 5, 0.10, 0.90));
		assertFalse(allows(100, 0, 85, 0, 6, 0.10, 0.90));
	}

	@Test
	void nonpositiveCapacityIsRejected() {
		assertFalse(allows(0, 0, 0, 0, 0, 0.10, 0.90));
		assertFalse(allows(-1, 0, 0, 0, 0, 0.10, 0.90));
	}

	@Test
	void literalFloorRejectsTheNearBoundaryThatTheHistoricalEpsilonAdmitted() {
		// Characterization previously admitted this; README requires literal floor (nine).
		// One nextDown is insufficient: binary64 multiplication rounds 100 * nextDown(.1) to 10.
		double justBelowShare = Math.nextDown(Math.nextDown(0.10));
		assertTrue(Math.floor(100 * justBelowShare) == 9);
		assertTrue(Math.floor(100 * justBelowShare + 1e-12) == 10);
		assertFalse(allows(100, 9, 0, 0, 0, justBelowShare, 0.90));
	}

	@Test
	void strictGateRejectsTheTinyExceedanceThatTheHistoricalEpsilonAdmitted() {
		// Characterization previously admitted nextDown(.90); no epsilon is now added.
		assertFalse(allows(100, 0, 90, 0, 0, 0.10, Math.nextDown(0.90)));
		assertFalse(allows(100, 0, 90, 0, 0, 0.10, 0.90 - 2e-12));
	}

	private static boolean allows(int capacity, int freight, int passengers,
			int acceptedFreight, int acceptedPassengers, double share, double gate) {
		return CoTransportBoardingPolicy.allowsMixedFreightBoarding(capacity, freight, passengers,
				acceptedFreight, acceptedPassengers, share, gate);
	}
}
