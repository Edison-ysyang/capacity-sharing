package org.matsim.contrib.cotransport;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only event mirror of whole-order commits. It neither accepts agents nor allocates
 * capacity. A receipt is produced only after all registered units entered one vehicle at
 * exactly the same physical event time. Transfer legs may produce another complete receipt.
 */
final class WholeOrderBoardingAudit {
	private final FreightOrderRegistry registry;
	private final Map<String, PendingBatch> pending = new LinkedHashMap<>();
	private final List<Receipt> receipts = new ArrayList<>();

	WholeOrderBoardingAudit(FreightOrderRegistry registry) { this.registry = registry; }

	void entered(Id<Person> person, Id<Vehicle> vehicle, double time) {
		FreightOrder order = registry.orderForPerson(person);
		if (order == null) throw new IllegalArgumentException("Unregistered freight unit: " + person);
		PendingBatch batch = pending.computeIfAbsent(order.orderId(), ignored -> new PendingBatch(vehicle, time));
		if (!batch.vehicle.equals(vehicle) || Double.compare(batch.time, time) != 0)
			throw new IllegalStateException("WHOLE_ORDER split boarding: order=" + order.orderId()
					+ "; first=" + batch.vehicle + "@" + batch.time + "; next=" + vehicle + "@" + time);
		if (!batch.entered.add(person))
			throw new IllegalStateException("Duplicate WHOLE_ORDER unit entry before batch completes: " + person);
		List<Id<Person>> expected = registry.unitIdsForOrder(order.orderId());
		if (batch.entered.size() == expected.size()) {
			if (!batch.entered.equals(new HashSet<>(expected)))
				throw new IllegalStateException("WHOLE_ORDER membership mismatch: " + order.orderId());
			receipts.add(new Receipt(order.orderId(), time, vehicle, expected));
			pending.remove(order.orderId());
		}
	}

	void assertVehicleBatchComplete(Id<Vehicle> vehicle) {
		for (var entry : pending.entrySet()) {
			if (entry.getValue().vehicle.equals(vehicle))
				throw new IllegalStateException("WHOLE_ORDER incomplete before vehicle departure/alighting: order="
						+ entry.getKey() + "; vehicle=" + vehicle + "; received=" + entry.getValue().entered.size()
						+ "; required=" + registry.unitIdsForOrder(entry.getKey()).size());
		}
	}

	void assertAllBatchesComplete() {
		if (!pending.isEmpty()) throw new IllegalStateException("WHOLE_ORDER unfinished event batches: " + pending.keySet());
	}

	List<Receipt> receipts() { return List.copyOf(receipts); }
	void reset() { pending.clear(); receipts.clear(); }

	record Receipt(String orderId, double time, Id<Vehicle> vehicle, List<Id<Person>> units) {
		Receipt { units = List.copyOf(units); }
	}

	private static final class PendingBatch {
		private final Id<Vehicle> vehicle;
		private final double time;
		private final Set<Id<Person>> entered = new HashSet<>();
		private PendingBatch(Id<Vehicle> vehicle, double time) { this.vehicle = vehicle; this.time = time; }
	}
}
