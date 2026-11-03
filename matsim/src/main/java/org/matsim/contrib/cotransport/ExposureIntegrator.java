package org.matsim.contrib.cotransport;

/**
 * Pure integration of an observed occupancy interval, independent of behavioural
 * coefficients and of MATSim's authoritative vehicle-capacity implementation.
 */
@FunctionalInterface
public interface ExposureIntegrator {

	RawExposure integrate(Interval interval);

	/** The conference model: total load and freight share use vehicle capacity as denominator. */
	static ExposureIntegrator capacityNormalized() {
		return interval -> new RawExposure(
				interval.durationMinutes() * Math.max(0.0, interval.totalLoadRatio() - interval.comfortThreshold()),
				interval.durationMinutes() * interval.freightShareOfCapacity());
	}

	/** A constant pre-event occupancy interval; duration is in minutes, not seconds. */
	record Interval(double durationMinutes, double totalLoadRatio, double freightShareOfCapacity,
			double comfortThreshold) {
		public Interval {
			requireNonnegativeFinite(durationMinutes, "durationMinutes");
			requireRatio(totalLoadRatio, "totalLoadRatio");
			requireRatio(freightShareOfCapacity, "freightShareOfCapacity");
			requireRatio(comfortThreshold, "comfortThreshold");
			if (freightShareOfCapacity > totalLoadRatio) {
				throw new IllegalArgumentException("Freight share cannot exceed total load ratio");
			}
		}
	}

	/** Per-person minute-equivalent accounts, before either beta is applied. */
	record RawExposure(double physical, double freightCoPresence) {
		public RawExposure {
			requireNonnegativeFinite(physical, "rawPhysical");
			requireNonnegativeFinite(freightCoPresence, "rawFreightCoPresence");
		}

		public RawExposure plus(RawExposure other) {
			return new RawExposure(physical + other.physical, freightCoPresence + other.freightCoPresence);
		}
	}

	private static void requireRatio(double value, String name) {
		requireNonnegativeFinite(value, name);
		if (value > 1.0) {
			throw new IllegalArgumentException(name + " must be in [0,1], got " + value);
		}
	}

	static void requireNonnegativeFinite(double value, String name) {
		if (!Double.isFinite(value) || value < 0.0) {
			throw new IllegalArgumentException(name + " must be finite and nonnegative, got " + value);
		}
	}
}
