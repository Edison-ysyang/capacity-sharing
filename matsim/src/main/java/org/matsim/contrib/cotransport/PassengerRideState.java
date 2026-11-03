package org.matsim.contrib.cotransport;

import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.Vehicle;

final class PassengerRideState {
	private final Id<Vehicle> vehicleId;
	private final double enterTime;
	private double physicalCrowdingPenalty;
	private double freightCoPresencePenalty;
	private double rawPhysicalExposure;
	private double rawFreightCoPresenceExposure;

	PassengerRideState(Id<Vehicle> vehicleId, double enterTime) {
		ExposureIntegrator.requireNonnegativeFinite(enterTime, "enterTime");
		this.vehicleId = vehicleId;
		this.enterTime = enterTime;
	}

	Id<Vehicle> vehicleId() {
		return vehicleId;
	}

	double enterTime() {
		return enterTime;
	}

	void addPenalty(double penalty) {
		addPhysicalCrowdingPenalty(penalty);
	}

	void addPhysicalCrowdingPenalty(double penalty) {
		ExposureIntegrator.requireNonnegativeFinite(penalty, "physicalCrowdingPenalty increment");
		double updated = physicalCrowdingPenalty + penalty;
		ExposureIntegrator.requireNonnegativeFinite(updated, "physicalCrowdingPenalty account");
		physicalCrowdingPenalty = updated;
	}

	void addFreightCoPresencePenalty(double penalty) {
		ExposureIntegrator.requireNonnegativeFinite(penalty, "freightCoPresencePenalty increment");
		double updated = freightCoPresencePenalty + penalty;
		ExposureIntegrator.requireNonnegativeFinite(updated, "freightCoPresencePenalty account");
		freightCoPresencePenalty = updated;
	}

	void addExposure(ExposureIntegrator.RawExposure exposure, ExposureValuation valuation) {
		double updatedPhysical = rawPhysicalExposure + exposure.physical();
		double updatedCoPresence = rawFreightCoPresenceExposure + exposure.freightCoPresence();
		double physicalPenalty = valuation.betaPhysical() * updatedPhysical;
		double coPresencePenalty = valuation.betaFreight() * updatedCoPresence;
		ExposureIntegrator.requireNonnegativeFinite(updatedPhysical, "ride rawPhysical");
		ExposureIntegrator.requireNonnegativeFinite(updatedCoPresence, "ride rawCoPresence");
		ExposureIntegrator.requireNonnegativeFinite(physicalPenalty + coPresencePenalty, "ride totalPenalty");
		rawPhysicalExposure = updatedPhysical;
		rawFreightCoPresenceExposure = updatedCoPresence;
		physicalCrowdingPenalty = physicalPenalty;
		freightCoPresencePenalty = coPresencePenalty;
	}

	ExposureIntegrator.RawExposure rawExposure() {
		return new ExposureIntegrator.RawExposure(rawPhysicalExposure, rawFreightCoPresenceExposure);
	}

	double physicalCrowdingPenalty() {
		return physicalCrowdingPenalty;
	}

	double freightCoPresencePenalty() {
		return freightCoPresencePenalty;
	}

	double accumulatedPenalty() {
		return physicalCrowdingPenalty + freightCoPresencePenalty;
	}
}
