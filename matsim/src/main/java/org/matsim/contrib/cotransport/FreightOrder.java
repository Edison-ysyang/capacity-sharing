package org.matsim.contrib.cotransport;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

public final class FreightOrder {
	private final String orderId;
	private final Id<Person> primaryPersonId;
	private final double releaseTime;
	private final double deadlineTime;
	private final double volumeM3;
	private final double massKg;
	private final String serviceClass;
	private final int priority;
	private final int capacityUnits;

	FreightOrder(String orderId, double releaseTime, double deadlineTime, double volumeM3, double massKg,
			String serviceClass, int priority, int capacityUnits) {
		if (orderId == null || orderId.isBlank()) {
			throw new IllegalArgumentException("Freight order ID must not be blank");
		}
		ProportionalFreightUnitizer.validateCount(capacityUnits);
		if (!Double.isFinite(volumeM3) || volumeM3 < 0 || !Double.isFinite(massKg) || massKg < 0) {
			throw new IllegalArgumentException("Freight volume and mass must be finite and nonnegative: " + orderId);
		}
		if (!Double.isFinite(releaseTime) || releaseTime < 0 || !Double.isFinite(deadlineTime) || deadlineTime < 0) {
			throw new IllegalArgumentException("Freight release and deadline must be finite nonnegative times: " + orderId);
		}
		this.orderId = orderId;
		this.primaryPersonId = Id.createPersonId(orderId);
		this.releaseTime = releaseTime;
		this.deadlineTime = deadlineTime;
		this.volumeM3 = volumeM3;
		this.massKg = massKg;
		this.serviceClass = serviceClass;
		this.priority = priority;
		this.capacityUnits = capacityUnits;
	}

	public String orderId() {
		return orderId;
	}

	public Id<Person> primaryPersonId() {
		return primaryPersonId;
	}

	public double releaseTime() {
		return releaseTime;
	}

	public double deadlineTime() {
		return deadlineTime;
	}

	public double volumeM3() {
		return volumeM3;
	}

	public double massKg() {
		return massKg;
	}

	public String serviceClass() {
		return serviceClass;
	}

	public int priority() {
		return priority;
	}

	public int capacityUnits() {
		return capacityUnits;
	}
}
