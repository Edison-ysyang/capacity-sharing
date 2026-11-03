package org.matsim.contrib.cotransport;

import java.util.List;

/** Pure unitization contract. Neither unitization nor the runtime registry creates MATSim persons. */
public interface FreightUnitizer {
	int capacityUnits(double volumeM3, Integer explicitCapacityUnits);

	List<String> unitIds(String orderId, int capacityUnits);

	static FreightUnitizer proportional(double equivalentPassengersPerM3, String shadowToken) {
		return new ProportionalFreightUnitizer(equivalentPassengersPerM3, shadowToken);
	}
}
