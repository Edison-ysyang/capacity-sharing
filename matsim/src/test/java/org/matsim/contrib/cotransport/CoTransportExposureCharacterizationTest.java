package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.PersonScoreEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Event-level characterization of the pre-refactor exposure/scoring contract. */
class CoTransportExposureCharacterizationTest {

	@TempDir
	Path tempDirectory;

	@Test
	void observedBoardingOutsideExplicitWindowFailsEvenIfNativeAdmissionWereBypassed() throws Exception {
		Fixture fixture = fixture(0.8, 0.8, new CoTransportBoardingPolicy.SharedBoardingWindow(100, 200));
		fixture.enter("p1", 0);
		var error = assertThrows(IllegalStateException.class, () -> fixture.enter("cargo", 200));
		assertTrue(error.getMessage().contains("outside shared window"));
		assertEquals(0, fixture.monitor.runtimeCounts().freightBoardings());
	}

	@Test
	void observedAlightingAfterWindowClosesRetainsAllEarlierRidingExposure() throws Exception {
		Fixture fixture = fixture(0.8, 0.8, new CoTransportBoardingPolicy.SharedBoardingWindow(100, 200));
		fixture.enter("p1", 0);
		fixture.enter("cargo", 100);
		fixture.leave("cargo", 300);
		fixture.leave("p1", 360);
		assertEquals(1, fixture.monitor.runtimeCounts().freightBoardings());
		assertEquals(0, fixture.monitor.runtimeCounts().freightUnitsStillOnboard());
		assertEquals(-0.8 * (200.0 / 60) * 0.1,
				fixture.score("p1", "coTransportPassengerFreightCoPresence"), 1e-12);
	}

	@Test
	void settlesEachBoardingAndAlightingIntervalUsingPreEventOccupancy() throws Exception {
		Fixture fixture = fixture(0.8, 0.8);
		fixture.enter("p1", 0);
		fixture.enter("p2", 60);
		fixture.enter("cargo", 120);
		fixture.enter("p3", 180);
		fixture.leave("cargo", 300);
		fixture.leave("p3", 420);
		fixture.leave("p1", 600);
		fixture.leave("p2", 660);

		// p1/p2: physical = .8 * (1*.1 + 2*.2 + 2*.1); co-presence = .8 * 3*.1.
		assertEquals(-0.56, fixture.score("p1", "coTransportPassengerPhysicalCrowding"), 1e-12);
		assertEquals(-0.24, fixture.score("p1", "coTransportPassengerFreightCoPresence"), 1e-12);
		assertEquals(-0.56, fixture.score("p2", "coTransportPassengerPhysicalCrowding"), 1e-12);
		assertEquals(-0.24, fixture.score("p2", "coTransportPassengerFreightCoPresence"), 1e-12);
		// p3 boards only at 180 s, and receives no exposure for an interval before boarding.
		assertEquals(-0.48, fixture.score("p3", "coTransportPassengerPhysicalCrowding"), 1e-12);
		assertEquals(-0.16, fixture.score("p3", "coTransportPassengerFreightCoPresence"), 1e-12);
		assertEquals(6, fixture.scores.size());
	}

	@Test
	void sameTimestampEventsDoNotCreateExposureAndAlightingStopsTheAccount() throws Exception {
		Fixture fixture = fixture(0.8, 0.8);
		fixture.enter("p1", 0);
		fixture.enter("cargo", 0);
		fixture.leave("p1", 60);
		fixture.leave("cargo", 600);
		assertEquals(0.0, fixture.score("p1", "coTransportPassengerPhysicalCrowding"), 0.0);
		assertEquals(-0.08, fixture.score("p1", "coTransportPassengerFreightCoPresence"), 1e-12);
		assertEquals(1, fixture.scores.size());
		assertEquals(60.0, fixture.scores.getFirst().getTime(), 0.0);
	}

	@Test
	void freightCoefficientZeroSuppressesOnlyFreightScoreEvents() throws Exception {
		Fixture fixture = fixture(0.8, 0.0);
		fixture.enter("p1", 0);
		fixture.enter("p2", 0);
		fixture.enter("cargo", 0);
		fixture.leave("p1", 60);
		fixture.leave("p2", 60);
		fixture.leave("cargo", 60);
		assertEquals(-0.08, fixture.score("p1", "coTransportPassengerPhysicalCrowding"), 1e-12);
		assertEquals(-0.08, fixture.score("p2", "coTransportPassengerPhysicalCrowding"), 1e-12);
		assertTrue(fixture.scores.stream().noneMatch(event ->
				event.getKind().equals("coTransportPassengerFreightCoPresence")));
	}

	@Test
	void bothCoefficientsZeroProduceNoExposureScoreEvents() throws Exception {
		Fixture fixture = fixture(0.0, 0.0);
		fixture.enter("p1", 0);
		fixture.enter("p2", 0);
		fixture.enter("cargo", 0);
		fixture.leave("cargo", 600);
		fixture.leave("p1", 600);
		fixture.leave("p2", 600);
		assertTrue(fixture.scores.isEmpty());
	}

	@Test
	void noFreightProducesNoCoPresenceScoreEvenWithPhysicalCrowding() throws Exception {
		Fixture fixture = fixture(0.8, 0.8);
		fixture.enter("p1", 0);
		fixture.enter("p2", 0);
		fixture.enter("p3", 0);
		fixture.leave("p1", 60);
		fixture.leave("p2", 60);
		fixture.leave("p3", 60);
		assertEquals(-0.08, fixture.score("p1", "coTransportPassengerPhysicalCrowding"), 1e-12);
		assertTrue(fixture.scores.stream().noneMatch(event ->
				event.getKind().equals("coTransportPassengerFreightCoPresence")));
	}

	@Test
	void separateRidesProduceSeparateScoresAndResetDropsPreviousIterationState() throws Exception {
		Fixture fixture = fixture(0.8, 0.8);
		fixture.enter("p1", 0);
		fixture.enter("cargo", 0);
		fixture.leave("p1", 60);
		fixture.leave("cargo", 60);
		fixture.enter("p1", 120);
		fixture.enter("cargo", 120);
		fixture.leave("p1", 180);
		fixture.leave("cargo", 180);
		assertEquals(-0.16, fixture.score("p1", "coTransportPassengerFreightCoPresence"), 1e-12);
		fixture.monitor.reset(1);
		fixture.scores.clear();
		fixture.startVehicle();
		fixture.enter("p1", 0);
		fixture.leave("p1", 60);
		assertTrue(fixture.scores.isEmpty());
	}

	Fixture fixture(double betaPhysical, double betaFreight) throws Exception {
		return fixture(betaPhysical, betaFreight, null);
	}

	Fixture fixture(double betaPhysical, double betaFreight, CoTransportBoardingPolicy.SharedBoardingWindow window) throws Exception {
		Path orders = tempDirectory.resolve("orders.csv");
		Files.writeString(orders,
				"order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
						+ "cargo,00:00:00,23:00:00,0.2,1.0,standard,1,1\n");
		var settingsBuilder = CoTransportSettings.builder(orders)
				.comfortThreshold(0.2)
				.passengerPhysicalCrowdingPenaltyPerMinute(betaPhysical)
				.passengerFreightCoPresencePenaltyPerMinute(betaFreight);
		if (window != null) settingsBuilder.freightServiceWindow(window.startInclusiveSeconds(), window.endExclusiveSeconds());
		CoTransportSettings settings = settingsBuilder.build();
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		if (window != null) scenario.addScenarioElement(CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT, window);
		VehicleType type = VehicleUtils.createVehicleType(Id.create("ten-place-type", VehicleType.class));
		type.getCapacity().setSeats(10);
		type.getCapacity().setStandingRoom(0);
		scenario.getTransitVehicles().addVehicleType(type);
		Id<Vehicle> vehicleId = Id.createVehicleId("train");
		scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(vehicleId, type));
		for (String id : List.of("p1", "p2", "p3", "cargo")) {
			Person person = scenario.getPopulation().getFactory().createPerson(Id.createPersonId(id));
			scenario.getPopulation().addPerson(person);
		}
		EventsManager events = EventsUtils.createEventsManager();
		List<PersonScoreEvent> scores = new ArrayList<>();
		events.addHandler((PersonScoreEventHandler) scores::add);
		FreightOrderRegistry registry = new FreightOrderRegistry(settings);
		CoTransportPhaseController phase = new CoTransportPhaseController(settings, registry, scenario, events,
				new ControllerConfigGroup(), new StrategyManager(), Map.of());
		Fixture fixture = new Fixture(new CoTransportRuntimeMonitor(settings, phase, registry, scenario, events),
				vehicleId, scores);
		fixture.startVehicle();
		return fixture;
	}

	record Fixture(CoTransportRuntimeMonitor monitor, Id<Vehicle> vehicle, List<PersonScoreEvent> scores) {
		void startVehicle() {
			monitor.handleEvent(new TransitDriverStartsEvent(0, Id.createPersonId("driver"), vehicle,
					Id.create("passenger-line", TransitLine.class), Id.create("route", TransitRoute.class),
					Id.create("departure", Departure.class)));
		}

		void enter(String person, double time) {
			monitor.handleEvent(new PersonEntersVehicleEvent(time, Id.createPersonId(person), vehicle));
		}

		void leave(String person, double time) {
			monitor.handleEvent(new PersonLeavesVehicleEvent(time, Id.createPersonId(person), vehicle));
		}

		double score(String person, String kind) {
			return scores.stream().filter(event -> event.getPersonId().toString().equals(person)
					&& event.getKind().equals(kind)).mapToDouble(PersonScoreEvent::getAmount).sum();
		}
	}
}
