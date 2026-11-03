package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Valid-input behaviour was run before the refactor. Three malformed-input characterizations
 * are now fail-closed regressions; their original failures are documented in the order audit.
 */
class FreightOrderCharacterizationTest {

	@TempDir
	Path directory;

	@Test
	void implicitUnitizationUsesCeilingAtFiveUnitsPerCubicMetre() throws Exception {
		FreightOrderRegistry registry = registry(false,
				row("exact", "0.4", null), row("above", "0.40001", null));
		assertEquals(2, registry.orderForPerson(person("exact")).capacityUnits());
		assertEquals(3, registry.orderForPerson(person("above")).capacityUnits());
	}

	@Test
	void zeroAndTinyPositiveVolumeReceiveTheMinimumOneUnit() throws Exception {
		FreightOrderRegistry registry = registry(false,
				row("zero", "0", null), row("tiny", "0.00001", null));
		assertEquals(1, registry.orderForPerson(person("zero")).capacityUnits());
		assertEquals(1, registry.orderForPerson(person("tiny")).capacityUnits());
		assertFalse(registry.isFreightPerson(person("zero_cap_02")));
	}

	@Test
	void explicitPositiveUnitCountIsAuthoritativeAndBlankFallsBackToVolume() throws Exception {
		FreightOrderRegistry registry = registry(true,
				row("explicit", "0.8", "2"), row("blank", "0.8", ""));
		assertEquals(2, registry.orderForPerson(person("explicit")).capacityUnits());
		assertEquals(4, registry.orderForPerson(person("blank")).capacityUnits());
	}

	@Test
	void unitIdsPreservePrimaryAndShadowMappingAndOrderInsertionOrder() throws Exception {
		FreightOrderRegistry registry = registry(true,
				row("beta", "0.4", "2"), row("alpha", "0.6", "3"));
		List<String> orderIds = new ArrayList<>();
		int units = 0;
		for (FreightOrder order : registry.orders()) {
			orderIds.add(order.orderId());
			units += order.capacityUnits();
			for (int i = 1; i <= order.capacityUnits(); i++) {
				Id<Person> id = person(i == 1 ? order.orderId() : order.orderId() + "_cap_0" + i);
				assertSame(order, registry.orderForPerson(id));
				assertEquals(order.orderId(), registry.normalizeOrderId(id));
				assertEquals(i == 1, registry.isPrimaryFreightPerson(id));
			}
		}
		assertEquals(List.of("beta", "alpha"), orderIds);
		assertEquals(5, units);
		assertNull(registry.orderForPerson(person("ordinary_passenger")));
	}

	@Test
	void duplicateOrderIdsAlreadyFail() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> registry(true, row("same", "0.4", "2"), row("same", "0.4", "2")));
		assertTrue(error.getMessage().contains("Duplicate freight order id"));
	}

	@Test
	void nonIntegralExplicitUnitCountAlreadyFails() {
		assertThrows(NumberFormatException.class, () -> registry(true, row("fractional", "1", "1.5")));
	}

	@Test
	void rejectsZeroAndNegativeExplicitUnitCounts() {
		assertThrows(IllegalArgumentException.class, () -> registry(true, row("zero", "1", "0")));
		assertThrows(IllegalArgumentException.class, () -> registry(true, row("negative", "1", "-2")));
	}

	@Test
	void rejectsNegativeAndNanVolumes() {
		assertThrows(IllegalArgumentException.class, () -> registry(false, row("negative", "-1", null)));
		assertThrows(IllegalArgumentException.class, () -> registry(false, row("nan", "NaN", null)));
	}

	@Test
	void rejectsGeneratedUnitCollisionsInEitherOrder() {
		assertThrows(IllegalStateException.class, () -> registry(true,
				row("parcel", "0.4", "2"), row("parcel_cap_02", "0.2", "1")));
		assertThrows(IllegalStateException.class, () -> registry(true,
				row("parcel_cap_02", "0.2", "1"), row("parcel", "0.4", "2")));
	}

	@Test
	void partialBoardingAndPartialDeliveryRemainIncompleteWithNoOrderLateness() {
		FreightOrderRuntimeStats stats = stats(2);
		stats.boarded(person("order"), 160);
		stats.delivered(person("order"), 240, 350);
		assertEquals(1, stats.unitsBoarded());
		assertEquals(1, stats.unitsDelivered());
		assertEquals("incomplete_conserved", stats.agentBalanceState());
		assertTrue(Double.isNaN(stats.completedOrderLatenessMinutes()));
		stats.boarded(person("order_cap_02"), 260);
		assertEquals("incomplete_conserved", stats.agentBalanceState());
		assertTrue(Double.isNaN(stats.completedOrderLatenessMinutes()));
	}

	@Test
	void firstUnitBoardingDeterminesWaitAndFinalUnitDeliveryDeterminesLateness() {
		FreightOrderRuntimeStats stats = stats(2);
		stats.boarded(person("order"), 160);
		stats.boarded(person("order_cap_02"), 220);
		stats.delivered(person("order_cap_02"), 280, 350);
		stats.delivered(person("order"), 400, 350);
		assertEquals(160, stats.firstBoardingTime());
		assertEquals(1, stats.waitToFirstBoardMinutes());
		assertEquals(400, stats.lastDeliveryTime());
		assertEquals(2.5, stats.completedOrderLatenessMinutes());
		assertEquals(1, stats.unitsDeliveredWithinServiceWindow());
		assertEquals("completed_conserved", stats.agentBalanceState());
	}

	@Test
	void deliveryExactlyAtDeadlineAndServiceWindowEndCountsOnTimeAndWithinWindow() {
		FreightOrderRuntimeStats stats = stats(1);
		stats.boarded(person("order"), 100);
		stats.delivered(person("order"), 250, 250);
		assertEquals(0, stats.completedOrderLatenessMinutes());
		assertEquals(1, stats.unitsDeliveredWithinServiceWindow());
	}

	@Test
	void earlyCompletedDeliveryHasZeroRatherThanNegativeLateness() {
		FreightOrderRuntimeStats stats = stats(1);
		stats.boarded(person("order"), 100);
		stats.delivered(person("order"), 200, 350);
		assertEquals(0, stats.completedOrderLatenessMinutes());
	}

	@Test
	void duplicateDeliveryThrowsWithoutDoubleCountingCompletedUnits() {
		FreightOrderRuntimeStats stats = stats(1);
		stats.boarded(person("order"), 160);
		stats.delivered(person("order"), 200, 350);
		assertThrows(IllegalStateException.class, () -> stats.delivered(person("order"), 400, 350));
		assertEquals(1, stats.unitsDelivered());
		assertEquals(1, stats.unitsDeliveredWithinServiceWindow());
		assertEquals(200, stats.lastDeliveryTime());
		assertEquals(0, stats.completedOrderLatenessMinutes());
	}

	@Test
	void repeatBoardingIncrementsEventCounterWithoutDoubleCountingUnitsOrReplacingFirstBoard() {
		FreightOrderRuntimeStats stats = stats(1);
		stats.boarded(person("order"), 160);
		stats.boarded(person("order"), 220);
		assertEquals(1, stats.unitsBoarded());
		assertEquals(2, stats.boardingEvents());
		assertEquals(160, stats.firstBoardingTime());
	}

	private FreightOrderRegistry registry(boolean explicitUnits, String... rows) throws Exception {
		Path csv = directory.resolve("orders.csv");
		String header = "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority"
				+ (explicitUnits ? ",capacity_units" : "");
		Files.writeString(csv, header + "\n" + String.join("\n", rows) + "\n");
		return new FreightOrderRegistry(CoTransportSettings.builder(csv)
				.freightEquivalentPassengersPerM3(5).build());
	}

	private static String row(String id, String volume, String units) {
		return id + ",08:00:00,09:00:00," + volume + ",2,standard,1" + (units == null ? "" : "," + units);
	}

	private static Id<Person> person(String id) {
		return Id.createPersonId(id);
	}

	private static FreightOrderRuntimeStats stats(int units) {
		return new FreightOrderRuntimeStats(new FreightOrder("order", 100, 250, 1, 2, "standard", 1, units));
	}
}
