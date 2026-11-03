package org.matsim.contrib.cotransport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable event-observation receipt for one iteration. Raw accounts cover only
 * observed intervals; an unfinished ride is never assigned a fabricated alighting.
 * Valued penalties and penalties actually emitted at alighting are distinguished
 * so a truncated run cannot present the former as applied behavioural scores.
 */
public record PassengerExposureSnapshot(int iteration, Map<String, PersonExposure> persons,
		ExposureIntegrator.RawExposure rawExposure, ExposureValuation.Penalties valuedPenalties,
		ExposureValuation.Penalties appliedPenalties, int unfinishedRides, List<String> invalidEventReasons) {
	public PassengerExposureSnapshot {
		persons = Collections.unmodifiableMap(new LinkedHashMap<>(persons));
		invalidEventReasons = List.copyOf(invalidEventReasons);
		if (unfinishedRides < 0) {
			throw new IllegalArgumentException("unfinishedRides must be nonnegative");
		}
	}

	public boolean validEventState() {
		return invalidEventReasons.isEmpty();
	}

	public boolean allObservedRidesClosed() {
		return unfinishedRides == 0;
	}

	public record PersonExposure(String personId, ExposureIntegrator.RawExposure rawExposure,
			ExposureValuation.Penalties valuedPenalties, ExposureValuation.Penalties appliedPenalties,
			boolean unfinishedRide) {
	}
}
