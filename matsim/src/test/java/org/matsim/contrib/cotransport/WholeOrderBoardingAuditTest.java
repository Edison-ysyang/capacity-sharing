package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Pure in-memory event receipt arithmetic; no controller or simulation is constructed. */
class WholeOrderBoardingAuditTest {
	@TempDir Path temporary;

	@Test void producesOneReceiptOnlyAfterEveryUnitArrivesAtOnePhysicalCommitTime() throws Exception {
		var registry = registry(); var audit = new WholeOrderBoardingAudit(registry);
		var vehicle = Id.createVehicleId("dedicated");
		for (var member : registry.unitIdsForOrder("parcel").subList(0, 4)) audit.entered(member, vehicle, 49000);
		assertTrue(audit.receipts().isEmpty());
		assertThrows(IllegalStateException.class, () -> audit.assertVehicleBatchComplete(vehicle));
		audit.entered(registry.unitIdsForOrder("parcel").getLast(), vehicle, 49000);
		audit.assertVehicleBatchComplete(vehicle); audit.assertAllBatchesComplete();
		assertEquals(1, audit.receipts().size()); assertEquals(5, audit.receipts().getFirst().units().size());
		assertEquals(49000, audit.receipts().getFirst().time());
		assertThrows(UnsupportedOperationException.class, () -> audit.receipts().getFirst().units().clear());
	}

	@Test void rejectsSameOrderAcrossVehiclesOrPhysicalTimes() throws Exception {
		var registry = registry(); var members = registry.unitIdsForOrder("parcel");
		for (boolean otherVehicle : new boolean[]{true, false}) {
			var audit = new WholeOrderBoardingAudit(registry);
			audit.entered(members.getFirst(), Id.createVehicleId("first"), 100);
			var failure = assertThrows(IllegalStateException.class, () -> audit.entered(members.get(1),
					Id.createVehicleId(otherVehicle ? "second" : "first"), otherVehicle ? 100 : 101));
			assertTrue(failure.getMessage().contains("split boarding"));
		}
	}

	@Test void rejectsDuplicateUnitAndUnfinishedBatchAtIterationEnd() throws Exception {
		var registry = registry(); var audit = new WholeOrderBoardingAudit(registry);
		var first = registry.unitIdsForOrder("parcel").getFirst(); var vehicle = Id.createVehicleId("train");
		audit.entered(first, vehicle, 100);
		assertThrows(IllegalStateException.class, () -> audit.entered(first, vehicle, 100));
		assertThrows(IllegalStateException.class, audit::assertAllBatchesComplete);
		audit.assertVehicleBatchComplete(Id.createVehicleId("unrelated"));
	}

	@Test void supportsCompleteTransferBatchesAndIterationResetWithoutCombiningEpisodes() throws Exception {
		var registry = registry(); var audit = new WholeOrderBoardingAudit(registry);
		for (int episode = 1; episode <= 2; episode++) {
			for (var member : registry.unitIds()) audit.entered(member, Id.createVehicleId("train-" + episode), episode * 100);
		}
		assertEquals(2, audit.receipts().size());
		audit.reset(); audit.assertAllBatchesComplete(); assertTrue(audit.receipts().isEmpty());
	}

	@Test void rejectsUnknownActorRatherThanConstructingAGroup() throws Exception {
		var audit = new WholeOrderBoardingAudit(registry());
		assertThrows(IllegalArgumentException.class, () -> audit.entered(Id.createPersonId("unknown"), Id.createVehicleId("train"), 100));
	}

	private FreightOrderRegistry registry() throws Exception {
		Path path = temporary.resolve("orders.csv");
		Files.writeString(path, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
				+ "parcel,13:30:00,15:00:00,1.25,20,standard,1,5\n");
		return new FreightOrderRegistry(CoTransportSettings.builder(path).build());
	}
}
