package org.matsim.contrib.cotransport;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.vehicles.Vehicle;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Objects;

/**
 * Observational reconstruction of already executed MATSim events. This class is
 * not a capacity allocator: native QSim alone accepts and removes occupants.
 */
final class VehicleCapacityState {
	private final Id<Vehicle> vehicleId;
	private final int capacity;
	private final Set<Id<Person>> passengers = new LinkedHashSet<>();
	private final Set<Id<Person>> freightUnits = new LinkedHashSet<>();
	private int maxLoad;
	private int maxFreightUnits;

	VehicleCapacityState(Id<Vehicle> vehicleId, int capacity) {
		this.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
		if (capacity <= 0) {
			throw new IllegalArgumentException("Observed vehicle capacity must be positive");
		}
		this.capacity = capacity;
	}

	void enter(Id<Person> personId, boolean freight) {
		if (!passengers.add(Objects.requireNonNull(personId, "personId"))) {
			throw new IllegalStateException("Duplicate entering event for " + personId + " in " + vehicleId);
		}
		if (freight) {
			freightUnits.add(personId);
		}
		maxLoad = Math.max(maxLoad, passengers.size());
		maxFreightUnits = Math.max(maxFreightUnits, freightUnits.size());
	}

	void leave(Id<Person> personId) {
		if (!passengers.remove(personId)) {
			throw new IllegalStateException("Unmatched leaving event for " + personId + " in " + vehicleId);
		}
		freightUnits.remove(personId);
	}

	Id<Vehicle> vehicleId() {
		return vehicleId;
	}

	int capacity() {
		return capacity;
	}

	int load() {
		return passengers.size();
	}

	boolean contains(Id<Person> personId) {
		return passengers.contains(personId);
	}

	int freightUnits() {
		return freightUnits.size();
	}

	int maxLoad() {
		return maxLoad;
	}

	int maxFreightUnits() {
		return maxFreightUnits;
	}

	double loadRatio() {
		return capacity <= 0 ? 0.0 : (double) passengers.size() / capacity;
	}

	double freightShareOfCapacity() {
		return capacity <= 0 ? 0.0 : (double) freightUnits.size() / capacity;
	}
}
