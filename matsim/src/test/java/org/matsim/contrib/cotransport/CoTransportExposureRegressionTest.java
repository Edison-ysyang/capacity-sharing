package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.Vehicle;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression requirements intentionally not satisfied by the pre-refactor runtime. */
class CoTransportExposureRegressionTest {
	@TempDir
	Path tempDirectory;

	@Test
	void rawChannelsSurviveEitherAndBothCoefficientKnockouts() throws Exception {
		for (double betaPhysical : List.of(0.0, 0.8)) {
			for (double betaFreight : List.of(0.0, 0.8)) {
				var fixture = fixture(betaPhysical, betaFreight);
				fixture.enter("p1", 0);
				fixture.enter("p2", 0);
				fixture.enter("cargo", 0);
				fixture.leave("cargo", 600);
				fixture.leave("p1", 600);
				fixture.leave("p2", 600);
				PassengerExposureSnapshot snapshot = fixture.monitor().passengerExposureSnapshot();
				assertEquals(2.0, snapshot.rawExposure().physical(), 1e-12);
				assertEquals(2.0, snapshot.rawExposure().freightCoPresence(), 1e-12);
				assertEquals(2.0 * betaPhysical, snapshot.valuedPenalties().physical(), 1e-12);
				assertEquals(2.0 * betaFreight, snapshot.valuedPenalties().freightCoPresence(), 1e-12);
				assertEquals(snapshot.valuedPenalties().physical(), snapshot.appliedPenalties().physical(), 1e-12);
				assertEquals(snapshot.valuedPenalties().freightCoPresence(), snapshot.appliedPenalties().freightCoPresence(), 1e-12);
				assertTrue(snapshot.validEventState());
				assertTrue(snapshot.allObservedRidesClosed());
			}
		}
	}

	@Test
	void exactMultiIntervalRawAccountsMatchIndependentOracle() throws Exception {
		var fixture = fixture(0.8, 0.8);
		fixture.enter("p1", 0);
		fixture.enter("p2", 60);
		fixture.enter("cargo", 120);
		fixture.enter("p3", 180);
		fixture.leave("cargo", 300);
		fixture.leave("p3", 420);
		fixture.leave("p1", 600);
		fixture.leave("p2", 660);
		PassengerExposureSnapshot snapshot = fixture.monitor().passengerExposureSnapshot();
		assertEquals(0.7, snapshot.persons().get("p1").rawExposure().physical(), 1e-12);
		assertEquals(0.3, snapshot.persons().get("p1").rawExposure().freightCoPresence(), 1e-12);
		assertEquals(0.7, snapshot.persons().get("p2").rawExposure().physical(), 1e-12);
		assertEquals(0.3, snapshot.persons().get("p2").rawExposure().freightCoPresence(), 1e-12);
		assertEquals(0.6, snapshot.persons().get("p3").rawExposure().physical(), 1e-12);
		assertEquals(0.2, snapshot.persons().get("p3").rawExposure().freightCoPresence(), 1e-12);
		assertEquals(2.0, snapshot.rawExposure().physical(), 1e-12);
		assertEquals(0.8, snapshot.rawExposure().freightCoPresence(), 1e-12);
	}

	@Test
	void noFreightMeansExactlyZeroRawCoPresence() throws Exception {
		var fixture = fixture(0.8, 0.8);
		fixture.enter("p1", 0);
		fixture.enter("p2", 0);
		fixture.enter("p3", 0);
		fixture.leave("p1", 60);
		fixture.leave("p2", 60);
		fixture.leave("p3", 60);
		assertEquals(0.0, fixture.monitor().passengerExposureSnapshot().rawExposure().freightCoPresence(), 0.0);
	}

	@Test
	void snapshotsIncludeNonRidersExcludeFreightAndRemainImmutableAcrossReset() throws Exception {
		var fixture = fixture(0.8, 0.8);
		fixture.monitor().reset(199);
		fixture.startVehicle();
		fixture.enter("p1", 0);
		fixture.enter("cargo", 0);
		fixture.leave("cargo", 60);
		fixture.leave("p1", 60);
		PassengerExposureSnapshot snapshot = fixture.monitor().passengerExposureSnapshot();
		assertEquals(199, snapshot.iteration());
		assertEquals(List.of("p1", "p2", "p3"), List.copyOf(snapshot.persons().keySet()));
		assertEquals(0.0, snapshot.persons().get("p3").rawExposure().physical(), 0.0);
		assertEquals(0.0, snapshot.persons().get("p3").rawExposure().freightCoPresence(), 0.0);
		assertThrows(UnsupportedOperationException.class, () -> snapshot.persons().clear());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.invalidEventReasons().add("changed"));
		fixture.monitor().reset(200);
		assertEquals(0.1, snapshot.rawExposure().freightCoPresence(), 1e-12);
		assertEquals(0.0, fixture.monitor().passengerExposureSnapshot().rawExposure().freightCoPresence(), 0.0);
	}

	@Test
	void unfinishedRidesRetainObservedRawButDoNotInventAlightingOrAppliedScores() throws Exception {
		var fixture = fixture(0.8, 0.8);
		fixture.enter("p1", 0);
		fixture.enter("cargo", 0);
		fixture.leave("cargo", 60); // One observed minute ends here; p1 stays aboard.
		PassengerExposureSnapshot snapshot = fixture.monitor().passengerExposureSnapshot();
		assertEquals(1, snapshot.unfinishedRides());
		assertFalse(snapshot.allObservedRidesClosed());
		assertTrue(snapshot.persons().get("p1").unfinishedRide());
		assertEquals(0.1, snapshot.rawExposure().freightCoPresence(), 1e-12);
		assertEquals(0.08, snapshot.valuedPenalties().freightCoPresence(), 1e-12);
		assertEquals(0.0, snapshot.appliedPenalties().total(), 0.0);
		assertTrue(fixture.scores().isEmpty());
	}

	@Test
	void reversedEventTimeFailsBeforeClockOrOccupancyMutation() throws Exception {
		var fixture = fixture(0.8, 0.8);
		fixture.enter("p1", 100);
		assertThrows(IllegalStateException.class, () -> fixture.enter("cargo", 90));
		assertFalse(fixture.monitor().passengerExposureSnapshot().validEventState());
		fixture.enter("cargo", 100);
		fixture.leave("cargo", 160);
		fixture.leave("p1", 160);
		assertEquals(0.1, fixture.monitor().passengerExposureSnapshot().rawExposure().freightCoPresence(), 1e-12);
	}

	@Test
	void duplicateEnterAndUnmatchedLeaveAreRecordedAsInvalid() throws Exception {
		var fixture = fixture(0.8, 0.8);
		fixture.enter("p1", 0);
		assertThrows(IllegalStateException.class, () -> fixture.enter("p1", 60));
		assertThrows(IllegalStateException.class, () -> fixture.leave("p2", 60));
		assertEquals(2, fixture.monitor().passengerExposureSnapshot().invalidEventReasons().size());
		assertEquals(1, fixture.monitor().passengerExposureSnapshot().unfinishedRides());
	}

	@Test
	void nonfiniteAndNegativeEventTimeFailClosed() throws Exception {
		for (double time : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
			var fixture = fixture(0.8, 0.8);
			assertThrows(IllegalStateException.class, () -> fixture.enter("p1", time));
			assertFalse(fixture.monitor().passengerExposureSnapshot().validEventState());
			assertEquals(0, fixture.monitor().passengerExposureSnapshot().unfinishedRides());
		}
	}

	@Test
	void observationalStateRejectsDuplicateAndUnmatchedEventsWithoutAllocatingCapacity() {
		VehicleCapacityState state = new VehicleCapacityState(Id.create("vehicle", Vehicle.class), 1);
		state.enter(Id.createPersonId("p1"), false);
		assertThrows(IllegalStateException.class, () -> state.enter(Id.createPersonId("p1"), false));
		assertThrows(IllegalStateException.class, () -> state.leave(Id.createPersonId("absent")));
		assertEquals(1, state.load());
		state.leave(Id.createPersonId("p1"));
		assertEquals(0, state.load());
	}

	private CoTransportExposureCharacterizationTest.Fixture fixture(double physical, double freight) throws Exception {
		CoTransportExposureCharacterizationTest fixtureBuilder = new CoTransportExposureCharacterizationTest();
		fixtureBuilder.tempDirectory = tempDirectory;
		return fixtureBuilder.fixture(physical, freight);
	}
}
