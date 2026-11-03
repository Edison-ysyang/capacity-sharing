package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FreightOrderRuntimeStatsTest {

	@Test
	void countsUniqueFreightAgentsAcrossRepeatBoardingsAndRejectsDuplicateDelivery() {
		FreightOrderRuntimeStats stats = new FreightOrderRuntimeStats(order("order", 2));
		Id<Person> first = Id.createPersonId("order");

		stats.boarded(first, 160.0);
		stats.boarded(first, 220.0);
		assertEquals(1, stats.unitsBoarded());
		assertEquals(2, stats.boardingEvents());
		assertEquals(1.0, stats.waitToFirstBoardMinutes(), 1e-12);

		stats.delivered(first, 300.0, 350.0);
		assertEquals(1, stats.unitsDelivered());
		assertEquals(1, stats.unitsDeliveredWithinServiceWindow());
		assertEquals("order", stats.boardedAgentIds());
		assertEquals("order", stats.deliveredAgentIds());
		assertEquals("incomplete_conserved", stats.agentBalanceState());
		assertThrows(IllegalStateException.class, () -> stats.delivered(first, 310.0, 350.0));
		assertEquals(1, stats.unitsDelivered());
	}

	@Test
	void rejectsDeliveryWithoutPriorBoarding() {
		FreightOrderRuntimeStats stats = new FreightOrderRuntimeStats(order("order", 1));
		Id<Person> person = Id.createPersonId("order");
		assertThrows(IllegalStateException.class, () -> stats.delivered(person, 300.0, 350.0));
		assertEquals(0, stats.unitsDelivered());
	}

	@Test
	void thirtyTwoUnitCeilingMatchesAnalyticSupplyAndRejectsTheThirtyThirdAgent() {
		FreightOrderRuntimeStats stats = new FreightOrderRuntimeStats(order("toy32", 32));
		for (int unit = 1; unit <= 32; unit++) {
			Id<Person> person = person("toy32", unit);
			stats.boarded(person, 100.0 + unit);
		}
		// Replay the fixture chronologically: all boardings precede these deliveries.
		for (int unit = 1; unit <= 32; unit++) {
			Id<Person> person = person("toy32", unit);
			stats.delivered(person, 200.0 + unit, 1_000.0);
		}
		assertEquals(32, stats.unitsBoarded());
		assertEquals(32, stats.unitsDelivered());
		assertEquals("completed_conserved", stats.agentBalanceState());

		Id<Person> thirtyThird = person("toy32", 33);
		assertThrows(IllegalStateException.class, () -> stats.boarded(thirtyThird, 500.0));
		assertThrows(IllegalStateException.class, () -> stats.delivered(thirtyThird, 600.0, 1_000.0));
		assertEquals(32, stats.unitsBoarded());
		assertEquals(32, stats.unitsDelivered());
	}

	private static FreightOrder order(String id, int capacityUnits) {
		return new FreightOrder(id, 100.0, 250.0, 1.0, 1.0, "standard", 1, capacityUnits);
	}

	private static Id<Person> person(String orderId, int unit) {
		return unit == 1
				? Id.createPersonId(orderId)
				: Id.createPersonId(orderId + "_cap_" + String.format("%02d", unit));
	}
}
