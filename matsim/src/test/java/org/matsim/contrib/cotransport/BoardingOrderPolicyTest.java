package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.ActorClass;

import static org.junit.jupiter.api.Assertions.*;

/** Pure strategy checks; no simulation or event replay. */
class BoardingOrderPolicyTest {
	@Test
	void passengerFirstPreservesLegacyPrioritiesAndStableQueueTies() {
		var order = BoardingOrderPolicy.forMode(CoTransportSettings.BoardingOrder.PASSENGER_FIRST);
		assertEquals(100, order.priority(ActorClass.PASSENGER));
		assertEquals(0, order.priority(ActorClass.FREIGHT));
		assertFalse(order.usesQueueArrivalTime());
	}

	@Test
	void fifoHasNoClassPreferenceAndRequiresTheNativeArrivalClock() {
		var order = BoardingOrderPolicy.forMode(CoTransportSettings.BoardingOrder.FIRST_COME_FIRST_SERVED);
		assertEquals(order.priority(ActorClass.PASSENGER), order.priority(ActorClass.FREIGHT));
		assertTrue(order.usesQueueArrivalTime());
		assertThrows(NullPointerException.class, () -> BoardingOrderPolicy.forMode(null));
	}
}
