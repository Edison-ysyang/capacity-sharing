package org.matsim.contrib.cotransport;

/** Behavioural scenario valuation. Changing beta never changes the raw exposure account. */
public record ExposureValuation(double betaPhysical, double betaFreight) {

	public ExposureValuation {
		ExposureIntegrator.requireNonnegativeFinite(betaPhysical, "betaPhysical");
		ExposureIntegrator.requireNonnegativeFinite(betaFreight, "betaFreight");
	}

	public Penalties value(ExposureIntegrator.RawExposure raw) {
		return new Penalties(betaPhysical * raw.physical(), betaFreight * raw.freightCoPresence());
	}

	/** Positive score-point costs; the event adapter emits their negatives as score events. */
	public record Penalties(double physical, double freightCoPresence) {
		public Penalties {
			ExposureIntegrator.requireNonnegativeFinite(physical, "physicalPenalty");
			ExposureIntegrator.requireNonnegativeFinite(freightCoPresence, "coPresencePenalty");
			ExposureIntegrator.requireNonnegativeFinite(physical + freightCoPresence, "totalPenalty");
		}

		public double total() {
			return physical + freightCoPresence;
		}
	}
}
