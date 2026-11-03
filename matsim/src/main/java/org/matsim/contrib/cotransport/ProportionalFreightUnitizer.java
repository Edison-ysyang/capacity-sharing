package org.matsim.contrib.cotransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit positive counts are authoritative; otherwise use max(1, ceil(eta * volume)). */
public record ProportionalFreightUnitizer(double equivalentPassengersPerM3, String shadowToken)
		implements FreightUnitizer {
	public ProportionalFreightUnitizer {
		if (!Double.isFinite(equivalentPassengersPerM3) || equivalentPassengersPerM3 <= 0) {
			throw new IllegalArgumentException("Freight unitization factor must be finite and positive");
		}
		if (shadowToken == null || shadowToken.isBlank()) {
			throw new IllegalArgumentException("Freight shadow token must not be blank");
		}
	}

	@Override
	public int capacityUnits(double volumeM3, Integer explicitCapacityUnits) {
		if (!Double.isFinite(volumeM3) || volumeM3 < 0) {
			throw new IllegalArgumentException("Freight volume must be finite and nonnegative: " + volumeM3);
		}
		if (explicitCapacityUnits != null) {
			validateCount(explicitCapacityUnits);
			return explicitCapacityUnits;
		}
		double derived = Math.max(1, Math.ceil(equivalentPassengersPerM3 * volumeM3));
		if (!Double.isFinite(derived) || derived > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Derived capacity_units exceeds supported integer range: " + derived);
		}
		return (int) derived;
	}

	@Override
	public List<String> unitIds(String orderId, int capacityUnits) {
		if (orderId == null || orderId.isBlank()) {
			throw new IllegalArgumentException("Freight order ID must not be blank");
		}
		validateCount(capacityUnits);
		List<String> ids = new ArrayList<>(capacityUnits);
		for (int index = 0; index < capacityUnits; index++) {
			ids.add(index == 0 ? orderId : orderId + shadowToken + String.format(Locale.ROOT, "%02d", index + 1));
		}
		return List.copyOf(ids);
	}

	static void validateCount(int count) {
		if (count <= 0) {
			throw new IllegalArgumentException("capacity_units must be a positive integer: " + count);
		}
	}
}
