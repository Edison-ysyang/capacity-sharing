package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoTransportBoardingPolicyTest {
	@Test
	void enforcesProjectedFreightShareCap() {
		assertTrue(CoTransportBoardingPolicy.allowsMixedFreightBoarding(
				100, 2, 60, 0, 0, 0.03, 0.90));
		assertFalse(CoTransportBoardingPolicy.allowsMixedFreightBoarding(
				100, 3, 60, 0, 0, 0.03, 0.90));
	}

	@Test
	void givesAcceptedPassengersPriorityOverFreight() {
		assertTrue(CoTransportBoardingPolicy.allowsMixedFreightBoarding(
				100, 0, 74, 0, 1, 0.10, 0.75));
		assertFalse(CoTransportBoardingPolicy.allowsMixedFreightBoarding(
				100, 0, 74, 0, 2, 0.10, 0.75));
	}

	@Test
	void physicalCapacityStillRejectsDegenerateVehicles() {
		assertFalse(CoTransportBoardingPolicy.allowsMixedFreightBoarding(
				0, 0, 0, 0, 0, 1.0, 1.0));
	}
}
