package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.fakes.FakePassengerAgent;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Static supply/plan and manually supplied event checks only; no QSim, Mobsim or Controller. */
class DedicatedWholeOrderServiceTest {
	@TempDir Path temporary;

	@Test void supplyAuditCountsExplicitDeparturesVehiclesAndPhysicalCapacityWithoutModifyingDemand() throws Exception {
		var fixture = fixture(5);
		var audit = fixture.validate();
		assertEquals("FREIGHT_L1", audit.lineId()); assertEquals(1, audit.routes());
		assertEquals(1, audit.departures()); assertEquals(1, audit.distinctVehicles());
		assertEquals(5, audit.minimumNativeCapacity()); assertEquals(5, audit.maximumNativeCapacity());
		assertTrue(audit.structurallyOversizedOrders().isEmpty());
		assertEquals(6, fixture.scenario.getPopulation().getPersons().size());
	}

	@Test void structurallyOversizedWholeOrderIsReportedButNeverSplitOrDiscarded() throws Exception {
		var fixture = fixture(4);
		assertEquals(List.of("parcel"), fixture.validate().structurallyOversizedOrders());
		assertEquals(5, fixture.registry.unitIdsForOrder("parcel").size());
	}

	@Test void rejectsMissingSupplyVehicleAndZeroPhysicalCapacity() throws Exception {
		var missing = fixture(5); missing.scenario.getTransitVehicles().removeVehicle(Id.createVehicleId("train"));
		assertTrue(assertThrows(IllegalStateException.class, missing::validate).getMessage().contains("missing vehicle"));
		var zero = fixture(0);
		assertTrue(assertThrows(IllegalStateException.class, zero::validate).getMessage().contains("no native capacity"));
	}

	@Test void rejectsBothDirectionsOfInputServiceContamination() throws Exception {
		var freightOnPassengerLine = fixture(5);
		route(freightOnPassengerLine, "parcel").transitLine = Id.create("PASSENGER_L1", TransitLine.class);
		assertTrue(assertThrows(IllegalStateException.class, freightOnPassengerLine::validate).getMessage().contains("isolation"));
		var passengerOnFreightLine = fixture(5);
		route(passengerOnFreightLine, "passenger").transitLine = passengerOnFreightLine.line.getId();
		assertTrue(assertThrows(IllegalStateException.class, passengerOnFreightLine::validate).getMessage().contains("isolation"));
	}

	@Test void rejectsMissingRouteReversedStopsAndDisallowedBoarding() throws Exception {
		var missingRoute = fixture(5); route(missingRoute, "parcel").transitRoute = Id.create("absent", TransitRoute.class);
		assertThrows(IllegalStateException.class, missingRoute::validate);
		var reversed = fixture(5); route(reversed, "parcel").accessFacility = Id.create("B", TransitStopFacility.class);
		route(reversed, "parcel").egressFacility = Id.create("A", TransitStopFacility.class);
		assertThrows(IllegalStateException.class, reversed::validate);
		var prohibited = fixture(5); prohibited.route.getStops().getFirst().setAllowBoarding(false);
		assertThrows(IllegalStateException.class, prohibited::validate);
	}

	@Test void dedicatedWholeOrderRequiresAnExplicitPhysicalBoardingWindow() throws Exception {
		var fixture = fixture(5);
		var settings = CoTransportSettings.builder(fixture.settings.freightOrdersCsv()).dedicatedFreightLineId("FREIGHT_L1").build();
		assertTrue(assertThrows(IllegalStateException.class, () -> DedicatedFreightServiceValidator.validate(settings,
				fixture.registry, fixture.scenario.getPopulation(), fixture.scenario.getTransitSchedule(),
				fixture.scenario.getTransitVehicles())).getMessage().contains("freightServiceWindow"));
	}

	@Test void serializedWindowGatesActualBoardingWithoutScenarioMarkerButAllowsLaterAlighting() throws Exception {
		var fixture = fixture(5); var monitor = fixture.monitor();
		fixture.start(monitor, 90, "FREIGHT_L1");
		for (var id : fixture.registry.unitIds()) monitor.handleEvent(new PersonEntersVehicleEvent(100, id, Id.createVehicleId("train")));
		monitor.handleEvent(new VehicleDepartsAtFacilityEvent(101, Id.createVehicleId("train"), Id.create("A", TransitStopFacility.class), 0));
		for (var id : fixture.registry.unitIds()) monitor.handleEvent(new PersonLeavesVehicleEvent(220, id, Id.createVehicleId("train")));
		assertEquals(5, monitor.runtimeCounts().freightBoardings());
		assertEquals(0, monitor.runtimeCounts().freightUnitsStillOnboard());
		var denied = fixture.monitor(); fixture.start(denied, 90, "FREIGHT_L1");
		assertTrue(assertThrows(IllegalStateException.class, () -> denied.handleEvent(new PersonEntersVehicleEvent(200,
				fixture.registry.unitIds().iterator().next(), Id.createVehicleId("train")))).getMessage().contains("outside shared window"));
		var policy = fixture.policy(); var cargo = new FakePassengerAgent(fixture.registry.unitIds().iterator().next(), null);
		assertFalse(policy.boardingTimeDecision(cargo, null, fixture.line, fixture.route, null, 200).allowed());
		assertTrue(policy.boardingTimeDecision(cargo, null, fixture.line, fixture.route, null, 100).allowed());
	}

	@Test void eventMirrorRejectsCrossBoardingsAndIncompleteWholeOrderDeparture() throws Exception {
		var fixture = fixture(5); var monitor = fixture.monitor(); fixture.start(monitor, 90, "FREIGHT_L1");
		final var dedicatedService = monitor;
		assertThrows(IllegalStateException.class, () -> dedicatedService.handleEvent(new PersonEntersVehicleEvent(100,
				Id.createPersonId("passenger"), Id.createVehicleId("train"))));
		monitor = fixture.monitor(); fixture.start(monitor, 90, "PASSENGER_L1"); final var passengerService = monitor;
		assertThrows(IllegalStateException.class, () -> passengerService.handleEvent(new PersonEntersVehicleEvent(100,
				Id.createPersonId("parcel"), Id.createVehicleId("train"))));
		monitor = fixture.monitor(); fixture.start(monitor, 90, "FREIGHT_L1");
		monitor.handleEvent(new PersonEntersVehicleEvent(100, Id.createPersonId("parcel"), Id.createVehicleId("train")));
		final var partialBatch = monitor;
		assertThrows(IllegalStateException.class, () -> partialBatch.handleEvent(new VehicleDepartsAtFacilityEvent(101,
				Id.createVehicleId("train"), Id.create("A", TransitStopFacility.class), 0)));
	}

	@Test void reusedPhysicalVehicleTakesCurrentServiceIdentityAndProducesNoPassengerFreightExposure() throws Exception {
		var fixture = fixture(5); var monitor = fixture.monitor(); fixture.start(monitor, 90, "FREIGHT_L1");
		for (var id : fixture.registry.unitIds()) monitor.handleEvent(new PersonEntersVehicleEvent(100, id, Id.createVehicleId("train")));
		for (var id : fixture.registry.unitIds()) monitor.handleEvent(new PersonLeavesVehicleEvent(120, id, Id.createVehicleId("train")));
		fixture.start(monitor, 150, "PASSENGER_L1");
		monitor.handleEvent(new PersonEntersVehicleEvent(151, Id.createPersonId("passenger"), Id.createVehicleId("train")));
		monitor.handleEvent(new PersonLeavesVehicleEvent(180, Id.createPersonId("passenger"), Id.createVehicleId("train")));
		assertEquals(1, monitor.runtimeCounts().passengerBoardings());
		assertEquals(0, monitor.passengerExposureSnapshot(false).rawExposure().freightCoPresence(), 0);
	}

	private static DefaultTransitPassengerRoute route(Fixture fixture, String person) {
		return (DefaultTransitPassengerRoute) ((Leg) fixture.scenario.getPopulation().getPersons().get(Id.createPersonId(person))
				.getSelectedPlan().getPlanElements().getFirst()).getRoute();
	}

	private Fixture fixture(int capacity) throws Exception {
		Path path = temporary.resolve("orders.csv");
		Files.writeString(path, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
				+ "parcel,00:00:00,23:00:00,1.25,20,standard,1,5\n");
		var settings = CoTransportSettings.builder(path).dedicatedFreightLineId("FREIGHT_L1").freightServiceWindow(100, 200).build();
		var registry = new FreightOrderRegistry(settings); var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		var schedule = scenario.getTransitSchedule(); var factory = schedule.getFactory();
		var a = factory.createTransitStopFacility(Id.create("A", TransitStopFacility.class), new Coord(0, 0), false);
		a.setLinkId(Id.createLinkId("A-link")); var b = factory.createTransitStopFacility(Id.create("B", TransitStopFacility.class), new Coord(100, 0), false);
		b.setLinkId(Id.createLinkId("B-link")); schedule.addStopFacility(a); schedule.addStopFacility(b);
		var line = factory.createTransitLine(Id.create("FREIGHT_L1", TransitLine.class));
		var route = factory.createTransitRoute(Id.create("AB", TransitRoute.class), RouteUtils.createLinkNetworkRouteImpl(a.getLinkId(), b.getLinkId()),
				List.of(factory.createTransitRouteStop(a, 0, 0), factory.createTransitRouteStop(b, 60, 60)), "rail");
		var departure = factory.createDeparture(Id.create("D1", Departure.class), 100); departure.setVehicleId(Id.createVehicleId("train"));
		route.addDeparture(departure); line.addRoute(route); schedule.addTransitLine(line);
		var type = VehicleUtils.createVehicleType(Id.create("freight-type", VehicleType.class));
		type.getCapacity().setSeats(capacity).setStandingRoom(0); scenario.getTransitVehicles().addVehicleType(type);
		scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(Id.createVehicleId("train"), type));
		var population = scenario.getPopulation();
		for (var id : registry.unitIds()) {
			var person = population.getFactory().createPerson(id); person.getAttributes().putAttribute("subpopulation", "freight");
			var plan = population.getFactory().createPlan(); var leg = population.getFactory().createLeg("freight_pt");
			leg.setRoute(new DefaultTransitPassengerRoute(a, line, route, b)); plan.addLeg(leg); person.addPlan(plan); population.addPerson(person);
		}
		var passenger = population.getFactory().createPerson(Id.createPersonId("passenger")); var plan = population.getFactory().createPlan();
		var leg = population.getFactory().createLeg("pt"); leg.setRoute(new DefaultTransitPassengerRoute(a.getLinkId(), b.getLinkId(), a.getId(), b.getId(),
				Id.create("PASSENGER_L1", TransitLine.class), Id.create("AB", TransitRoute.class))); plan.addLeg(leg); passenger.addPlan(plan); population.addPerson(passenger);
		return new Fixture(settings, registry, scenario, line, route);
	}

	private record Fixture(CoTransportSettings settings, FreightOrderRegistry registry, Scenario scenario, TransitLine line, TransitRoute route) {
		DedicatedFreightServiceValidator.SupplyAudit validate() {
			return DedicatedFreightServiceValidator.validate(settings, registry, scenario.getPopulation(), scenario.getTransitSchedule(), scenario.getTransitVehicles());
		}
		CoTransportPhaseController phase() {
			return new CoTransportPhaseController(settings, registry, scenario, EventsUtils.createEventsManager(), scenario.getConfig().controller(), new StrategyManager(), Map.of());
		}
		CoTransportRuntimeMonitor monitor() { return new CoTransportRuntimeMonitor(settings, phase(), registry, scenario, EventsUtils.createEventsManager()); }
		CoTransportBoardingPolicy policy() { return new CoTransportBoardingPolicy(settings, registry, phase(), scenario); }
		void start(CoTransportRuntimeMonitor monitor, double time, String lineId) {
			monitor.handleEvent(new TransitDriverStartsEvent(time, Id.createPersonId("driver-" + time), Id.createVehicleId("train"),
					Id.create(lineId, TransitLine.class), route.getId(), Id.create("D1", Departure.class)));
		}
	}
}
