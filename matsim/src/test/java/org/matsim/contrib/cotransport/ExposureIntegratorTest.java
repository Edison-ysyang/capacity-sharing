package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExposureIntegratorTest {
	@Test
	void pureIntervalPreservesCapacityBasedPhysicalAndFreightDenominators() {
		ExposureIntegrator.RawExposure raw = ExposureIntegrator.capacityNormalized().integrate(
				new ExposureIntegrator.Interval(10, 0.9, 0.1, 0.6));
		assertEquals(3.0, raw.physical(), 1e-12);
		assertEquals(1.0, raw.freightCoPresence(), 0.0);
		assertEquals(0.0, new ExposureValuation(0.8, 0.0).value(raw).freightCoPresence(), 0.0);
		assertEquals(2.4, new ExposureValuation(0.8, 0.0).value(raw).physical(), 1e-12);
		assertEquals(0.8, new ExposureValuation(0.0, 0.8).value(raw).freightCoPresence(), 1e-12);
		assertEquals(1.0, raw.freightCoPresence(), 0.0);
	}

	@Test
	void zeroDurationAndComfortBoundaryAreExactlyZero() {
		ExposureIntegrator integrator = ExposureIntegrator.capacityNormalized();
		assertEquals(new ExposureIntegrator.RawExposure(0.0, 0.0), integrator.integrate(
				new ExposureIntegrator.Interval(0.0, 1.0, 1.0, 0.6)));
		assertEquals(new ExposureIntegrator.RawExposure(0.0, 0.0), integrator.integrate(
				new ExposureIntegrator.Interval(60.0, 0.6, 0.0, 0.6)));
	}

	@Test
	void invalidInputsNeverProduceNegativeOrNonfiniteAccounts() {
		for (double bad : List.of(-1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
			assertThrows(IllegalArgumentException.class, () -> new ExposureIntegrator.Interval(bad, 0.5, 0.1, 0.6));
			assertThrows(IllegalArgumentException.class, () -> new ExposureIntegrator.Interval(1, bad, 0.1, 0.6));
			assertThrows(IllegalArgumentException.class, () -> new ExposureIntegrator.Interval(1, 0.5, bad, 0.6));
			assertThrows(IllegalArgumentException.class, () -> new ExposureIntegrator.Interval(1, 0.5, 0.1, bad));
			assertThrows(IllegalArgumentException.class, () -> new ExposureIntegrator.RawExposure(bad, 0));
			assertThrows(IllegalArgumentException.class, () -> new ExposureIntegrator.RawExposure(0, bad));
			assertThrows(IllegalArgumentException.class, () -> new ExposureValuation(bad, 0));
			assertThrows(IllegalArgumentException.class, () -> new ExposureValuation(0, bad));
		}
		assertThrows(IllegalArgumentException.class, () -> new ExposureIntegrator.Interval(1, 1.01, 0.1, 0.6));
		assertThrows(IllegalArgumentException.class, () -> new ExposureIntegrator.Interval(1, 0.1, 0.2, 0.6));
		assertThrows(IllegalArgumentException.class, () -> new ExposureIntegrator.Interval(1, 0.5, 0.1, 1.01));
		assertThrows(IllegalArgumentException.class, () -> new ExposureValuation(Double.MAX_VALUE, 0)
				.value(new ExposureIntegrator.RawExposure(2.0, 0)));
		assertThrows(IllegalArgumentException.class, () -> new ExposureIntegrator.RawExposure(Double.MAX_VALUE, 0)
				.plus(new ExposureIntegrator.RawExposure(Double.MAX_VALUE, 0)));
	}

	@Test
	void legacyScoringHelperUsesValidatedRawFirstValuation() {
		assertThrows(IllegalArgumentException.class, () -> CoTransportRuntimeMonitor.calculateExposurePenalty(
				Double.NaN, 0.1, 0.8, 0.6, 0.8));
		assertThrows(IllegalArgumentException.class, () -> CoTransportRuntimeMonitor.calculateExposurePenalty(
				1.0, 0.1, 0.8, 0.6, -0.8));
	}
}
