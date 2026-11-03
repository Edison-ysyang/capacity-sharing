package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoTransportScoringTest {
	@Test
	void integratesFreightAndCrowdingExposure() {
		double penalty = CoTransportRuntimeMonitor.calculateExposurePenalty(
				30.0, 0.03, 0.80, 0.70, 0.28);
		assertEquals(1.092, penalty, 1e-9);
	}

	@Test
	void noExposureMeansNoPenalty() {
		assertEquals(0.0, CoTransportRuntimeMonitor.calculateExposurePenalty(
				20.0, 0.0, 0.60, 0.70, 0.28), 1e-9);
	}

	@Test
	void separatesPhysicalCrowdingAndFreightCoPresenceCoefficients() {
		CoTransportRuntimeMonitor.ExposurePenaltyComponents components =
				CoTransportRuntimeMonitor.calculateExposurePenaltyComponents(
				10.0, 0.10, 0.90, 0.80, 2.0, 3.0);
		assertEquals(2.0, components.physicalCrowding(), 1e-9);
		assertEquals(3.0, components.freightCoPresence(), 1e-9);
		assertEquals(5.0, components.total(), 1e-9);
	}

	@Test
	void capacityOnlyAndCoPresenceOnlyAblationsSwitchTheIntendedChannel() {
		CoTransportRuntimeMonitor.ExposurePenaltyComponents capacityOnly =
				CoTransportRuntimeMonitor.calculateExposurePenaltyComponents(
						10.0, 0.10, 0.90, 0.80, 2.0, 0.0);
		assertEquals(2.0, capacityOnly.physicalCrowding(), 1e-9);
		assertEquals(0.0, capacityOnly.freightCoPresence(), 1e-9);

		CoTransportRuntimeMonitor.ExposurePenaltyComponents coPresenceOnly =
				CoTransportRuntimeMonitor.calculateExposurePenaltyComponents(
						10.0, 0.10, 0.90, 1.0, 0.0, 3.0);
		assertEquals(0.0, coPresenceOnly.physicalCrowding(), 1e-9);
		assertEquals(3.0, coPresenceOnly.freightCoPresence(), 1e-9);
	}

	@Test
	void vehicleCapacitySeparatesPassengerAndFreightUnits() {
		VehicleCapacityState state = new VehicleCapacityState(Id.create("train", Vehicle.class), 100);
		state.enter(Id.createPersonId("passenger"), false);
		state.enter(Id.createPersonId("hd_cargo_1"), true);
		assertEquals(0.02, state.loadRatio(), 1e-9);
		assertEquals(0.01, state.freightShareOfCapacity(), 1e-9);
		state.leave(Id.create("hd_cargo_1", Person.class));
		assertEquals(0.01, state.loadRatio(), 1e-9);
		assertEquals(0.0, state.freightShareOfCapacity(), 1e-9);
	}

	@Test
	void passengerRideAccumulatesMultipleExposureIntervals() {
		PassengerRideState ride = new PassengerRideState(Id.create("train", Vehicle.class), 100.0);
		ride.addPhysicalCrowdingPenalty(0.2);
		ride.addFreightCoPresencePenalty(0.3);
		assertEquals(0.2, ride.physicalCrowdingPenalty(), 1e-9);
		assertEquals(0.3, ride.freightCoPresencePenalty(), 1e-9);
		assertEquals(0.5, ride.accumulatedPenalty(), 1e-9);
	}

	@Test
	void occupancyGuardFailsBeforeAFullVehicleCanAcceptAnotherBoarding() {
		VehicleCapacityState state = new VehicleCapacityState(Id.create("train", Vehicle.class), 1);
		Id<Person> first = Id.createPersonId("first");
		Id<Person> second = Id.createPersonId("second");
		CoTransportRuntimeMonitor.assertBoardingCapacityAvailable(state, first);
		state.enter(first, false);
		CoTransportRuntimeMonitor.assertOccupancyWithinCapacity(state);
		assertThrows(IllegalStateException.class,
				() -> CoTransportRuntimeMonitor.assertBoardingCapacityAvailable(state, second));
		state.leave(first);
		CoTransportRuntimeMonitor.assertOccupancyWithinCapacity(state);
	}

	@Test
	void summaryDistributionHelpersUseInterpolatedQuantiles() {
		List<Double> values = List.of(1.0, 2.0, 10.0);
		assertEquals(13.0 / 3.0, CoTransportRuntimeMonitor.mean(values), 1e-12);
		assertEquals(2.0, CoTransportRuntimeMonitor.percentile(values, 0.50), 1e-12);
		assertEquals(9.2, CoTransportRuntimeMonitor.percentile(values, 0.95), 1e-12);
	}

	@Test
	void hardCapacityRatioIsExplicitlyReportedInactive() {
		assertFalse(CoTransportRuntimeMonitor.hardCapacityRatioActive());
	}

	@Test
	void deniedBoardingTraceRetainsEveryVehicleStopAndTime() {
		CoTransportRuntimeMonitor.DeniedBoardingResponseState state =
				new CoTransportRuntimeMonitor.DeniedBoardingResponseState(
						Id.createPersonId("passenger"), false, "");
		Id<Vehicle> firstVehicle = Id.create("vehicle-1", Vehicle.class);
		Id<Vehicle> secondVehicle = Id.create("vehicle-2", Vehicle.class);
		Id<TransitStopFacility> firstStop = Id.create("stop-1", TransitStopFacility.class);
		Id<TransitStopFacility> secondStop = Id.create("stop-2", TransitStopFacility.class);
		state.denied(100.0, firstVehicle, firstStop);
		state.denied(200.0, secondVehicle, secondStop);

		assertEquals(2, state.denialOccurrences().size());
		assertEquals(1, state.denialOccurrences().getFirst().sequence());
		assertEquals(100.0, state.denialOccurrences().getFirst().time(), 1e-12);
		assertEquals(firstVehicle, state.denialOccurrences().getFirst().vehicleId());
		assertEquals(firstStop, state.denialOccurrences().getFirst().stopId());
		assertEquals(2, state.denialOccurrences().getLast().sequence());
		assertEquals(secondVehicle, state.denialOccurrences().getLast().vehicleId());
		assertEquals(secondStop, state.denialOccurrences().getLast().stopId());
	}
}
