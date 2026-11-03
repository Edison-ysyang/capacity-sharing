package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.qsim.pt.PTPassengerAgent;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.ActorClass;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.DenialReason;
import org.matsim.core.mobsim.qsim.pt.TransitQVehicle;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.fakes.FakePassengerAgent;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies that the MATSim adapter translates native occupants, not the event mirror. */
class CoTransportBoardingAdapterTest {
	@TempDir Path directory;

	@Test
	void explicitWindowUsesPhysicalTimeWithInclusiveStartExclusiveEndAndLeavesPassengersUnrestricted() throws Exception {
		Fixture fixture = fixture(false, new CoTransportBoardingPolicy.SharedBoardingWindow(100, 200));
		var cargo = fixture.cargo.getFirst();
		for (double time : new double[]{0, 99, 200, 201})
			assertEquals(DenialReason.OUTSIDE_SHARED_WINDOW, fixture.policy.boardingTimeDecision(
					cargo, fixture.vehicle, fixture.passengerLine, null, null, time).denialReason());
		for (double time : new double[]{100, 150, Math.nextDown(200.0)})
			assertTrue(fixture.policy.boardingTimeDecision(cargo, fixture.vehicle, fixture.passengerLine,
					null, null, time).allowed());
		assertTrue(fixture.policy.boardingTimeDecision(fixture.passenger("ordinary"), fixture.vehicle,
				fixture.passengerLine, null, null, 250).allowed());
		assertTrue(fixture.vehicle.getPassengers().isEmpty(), "Time admission does not reserve physical space");
	}

	@Test
	void absentExplicitGatePreservesLegacyBoardingAndInvalidWindowOrClockFailsClosed() throws Exception {
		Fixture fixture = fixture(false);
		assertTrue(fixture.policy.boardingTimeDecision(fixture.cargo.getFirst(), fixture.vehicle,
				fixture.passengerLine, null, null, 90000).allowed());
		for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertThrows(IllegalArgumentException.class, () -> new CoTransportBoardingPolicy.SharedBoardingWindow(invalid, 200));
			assertThrows(IllegalArgumentException.class, () -> new CoTransportBoardingPolicy.SharedBoardingWindow(100, invalid));
			assertThrows(IllegalArgumentException.class, () -> new CoTransportBoardingPolicy.SharedBoardingWindow(100, 200).contains(invalid));
		}
		assertThrows(IllegalArgumentException.class, () -> new CoTransportBoardingPolicy.SharedBoardingWindow(100, 100));
	}

	@Test
	void nativeAlightingAndSameStopPassengerAcceptancesReachTheGateWithoutDoubleCounting() throws Exception {
		Fixture fixture = fixture(false);
		List<PTPassengerAgent> onboardPassengers = new ArrayList<>();
		for (int i = 0; i < 90; i++) {
			PTPassengerAgent passenger = fixture.passenger("onboard-" + i);
			onboardPassengers.add(passenger);
			assertTrue(fixture.vehicle.addPassenger(passenger));
		}
		var leaving = onboardPassengers.subList(0, 2);
		var accepted = List.of(fixture.passenger("accepted-1"), fixture.passenger("accepted-2"));
		var cargo = fixture.cargo.getFirst();
		assertTrue(fixture.policy.decideBoarding(cargo, fixture.vehicle, fixture.passengerLine,
				null, null, leaving, accepted).allowed());
		var additional = new ArrayList<>(accepted);
		additional.add(fixture.passenger("accepted-3"));
		assertEquals(DenialReason.PASSENGER_LOAD_GATE, fixture.policy.decideBoarding(cargo,
				fixture.vehicle, fixture.passengerLine, null, null, leaving, additional).denialReason());
		var details = fixture.policy.denialDetails(cargo, fixture.vehicle, fixture.passengerLine,
				null, null, leaving, additional);
		assertEquals(91, details.passengers());
		assertEquals(0, details.freight());
		assertEquals(ActorClass.FREIGHT, details.actorClass());
		assertEquals(90, fixture.vehicle.getPassengers().size(), "decision and diagnostics must not mutate native state");
	}

	@Test
	void nativeFreightAlightingAndAcceptedUnitsReachTheFloorCap() throws Exception {
		Fixture fixture = fixture(false);
		for (PTPassengerAgent cargo : fixture.cargo.subList(0, 9)) assertTrue(fixture.vehicle.addPassenger(cargo));
		assertTrue(fixture.policy.decideBoarding(fixture.cargo.get(11), fixture.vehicle,
				fixture.passengerLine, null, null, fixture.cargo.subList(0, 2), fixture.cargo.subList(9, 11)).allowed());
		assertEquals(DenialReason.FREIGHT_SHARE_CAP, fixture.policy.decideBoarding(fixture.cargo.get(11),
				fixture.vehicle, fixture.passengerLine, null, null, List.of(), List.of(fixture.cargo.get(9))).denialReason());
	}

	@Test
	void dedicatedAdapterRejectsBothCrossBoardingsAndAnUnregisteredAgent() throws Exception {
		Fixture fixture = fixture(true);
		PTPassengerAgent passenger = fixture.passenger("passenger");
		PTPassengerAgent cargo = fixture.cargo.getFirst();
		assertEquals(DenialReason.ISOLATION_RULE, fixture.policy.decideBoarding(passenger,
				fixture.vehicle, fixture.dedicatedLine, null, null, List.of(), List.of()).denialReason());
		assertEquals(DenialReason.ISOLATION_RULE, fixture.policy.decideBoarding(cargo,
				fixture.vehicle, fixture.passengerLine, null, null, List.of(), List.of()).denialReason());
		assertTrue(fixture.policy.decideBoarding(cargo, fixture.vehicle, fixture.dedicatedLine,
				null, null, List.of(), List.of()).allowed());
		assertTrue(fixture.policy.decideBoarding(passenger, fixture.vehicle, fixture.passengerLine,
				null, null, List.of(), List.of()).allowed());
		assertEquals(DenialReason.INVALID_AGENT_CLASS, fixture.policy.decideBoarding(
				new FakePassengerAgent(Id.createPersonId("absent"), null), fixture.vehicle,
				fixture.passengerLine, null, null, List.of(), List.of()).denialReason());
	}

	@Test
	void passengerPriorityAlsoAppliesToTheLegacyAlwaysActiveEntryPoint() throws Exception {
		Fixture fixture = fixture(false);
		assertTrue(fixture.policy.priority(fixture.passenger("p"), fixture.vehicle, fixture.passengerLine, null, null)
				> fixture.policy.priority(fixture.cargo.getFirst(), fixture.vehicle, fixture.passengerLine, null, null));
		assertFalse(fixture.policy.usesQueueArrivalTime());
	}

	@Test
	void fifoSettingReachesTheNativeHookWithoutChangingEligibilityOrTimeGates() throws Exception {
		Fixture mixed = fixture(false, new CoTransportBoardingPolicy.SharedBoardingWindow(100, 200),
				CoTransportSettings.BoardingOrder.FIRST_COME_FIRST_SERVED);
		var passenger = mixed.passenger("p-fifo");
		assertTrue(mixed.policy.usesQueueArrivalTime());
		assertEquals(mixed.policy.priority(passenger, mixed.vehicle, mixed.passengerLine, null, null),
				mixed.policy.priority(mixed.cargo.getFirst(), mixed.vehicle, mixed.passengerLine, null, null));
		assertEquals(DenialReason.OUTSIDE_SHARED_WINDOW, mixed.policy.boardingTimeDecision(
				mixed.cargo.getFirst(), mixed.vehicle, mixed.passengerLine, null, null, 200).denialReason());
		for (var cargo : mixed.cargo.subList(0, 10)) assertTrue(mixed.vehicle.addPassenger(cargo));
		assertEquals(DenialReason.FREIGHT_SHARE_CAP, mixed.policy.decideBoarding(mixed.cargo.get(10),
				mixed.vehicle, mixed.passengerLine, null, null, List.of(), List.of()).denialReason());
		Fixture dedicated = fixture(true, null, CoTransportSettings.BoardingOrder.FIRST_COME_FIRST_SERVED);
		assertEquals(DenialReason.ISOLATION_RULE, dedicated.policy.decideBoarding(dedicated.passenger("p-separated"),
				dedicated.vehicle, dedicated.dedicatedLine, null, null, List.of(), List.of()).denialReason());
	}

	private Fixture fixture(boolean dedicated) throws Exception {
		return fixture(dedicated, null);
	}

	private Fixture fixture(boolean dedicated, CoTransportBoardingPolicy.SharedBoardingWindow window) throws Exception {
		return fixture(dedicated, window, CoTransportSettings.BoardingOrder.PASSENGER_FIRST);
	}

	private Fixture fixture(boolean dedicated, CoTransportBoardingPolicy.SharedBoardingWindow window,
			CoTransportSettings.BoardingOrder boardingOrder) throws Exception {
		Path orders = directory.resolve("orders.csv");
		Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
				+ "cargo,00:00:00,23:00:00,1.0,1.0,standard,1,12\n");
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		var builder = CoTransportSettings.builder(orders).freightShareCap(.10).passengerLoadGate(.90)
				.freightHandlingDwellSeconds(0).boardingOrder(boardingOrder);
		if (dedicated) builder.dedicatedFreightLineId("dedicated");
		if (window != null) {
			builder.freightServiceWindow(window.startInclusiveSeconds(), window.endExclusiveSeconds());
			scenario.addScenarioElement(CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT, window);
		}
		CoTransportSettings settings = builder.build();
		FreightOrderRegistry registry = new FreightOrderRegistry(settings);
		List<PTPassengerAgent> cargo = new ArrayList<>();
		for (var id : registry.unitIds()) {
			var person = scenario.getPopulation().getFactory().createPerson(id);
			scenario.getPopulation().addPerson(person);
			cargo.add(new FakePassengerAgent(id, null));
		}
		var controller = new CoTransportPhaseController(settings, registry, scenario, EventsUtils.createEventsManager(),
				scenario.getConfig().controller(), new StrategyManager(), Map.of());
		var policy = new CoTransportBoardingPolicy(settings, registry, controller, scenario);
		var passengerLine = scenario.getTransitSchedule().getFactory().createTransitLine(Id.create("passenger", TransitLine.class));
		var dedicatedLine = scenario.getTransitSchedule().getFactory().createTransitLine(Id.create("dedicated", TransitLine.class));
		VehicleType type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class));
		type.getCapacity().setSeats(100);
		type.getCapacity().setStandingRoom(0);
		var vehicle = new TransitQVehicle(VehicleUtils.createVehicle(Id.createVehicleId("train"), type));
		return new Fixture(scenario, cargo, policy, passengerLine, dedicatedLine, vehicle);
	}

	private record Fixture(Scenario scenario, List<PTPassengerAgent> cargo, CoTransportBoardingPolicy policy,
			TransitLine passengerLine, TransitLine dedicatedLine, TransitQVehicle vehicle) {
		PTPassengerAgent passenger(String name) {
			var id = Id.createPersonId(name);
			scenario.getPopulation().addPerson(scenario.getPopulation().getFactory().createPerson(id));
			return new FakePassengerAgent(id, null);
		}
	}
}
