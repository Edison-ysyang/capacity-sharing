package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Four-iteration real-QSim PT smoke for the R17 in-run activation protocol. */
@org.junit.jupiter.api.Tag("ismt-integration")
class CoTransportPtActivationSmokeTest {
	private static final String PASSENGER_ID = "passenger";
	private static final String FREIGHT_ID = "cargo_order";

	@TempDir
	Path tempDirectory;

	@Test
	void mixedArmBoardsAndAlightsFreightOnlyAfterIterationTwo() throws Exception {
		RunResult run = runTinyPtArm("mixed", true);

		for (int iteration = 0; iteration <= 1; iteration++) {
			assertEquals(0, run.events().freightBoardings(iteration));
			assertEquals(0, run.events().freightAlightings(iteration));
			Map<String, String> audit = rowForIteration(run.activationAudit(), iteration);
			assertEquals("false", audit.get("freight_active"));
			assertEquals("0", audit.get("freight_agents_installed"));
			assertEquals("0", audit.get("freight_events"));
			assertEquals("0", iterationSummary(run.output(), iteration).get("freight_boardings"));
		}
		for (int iteration = 2; iteration <= 3; iteration++) {
			assertEquals(1, run.events().freightBoardings(iteration));
			assertEquals(1, run.events().freightAlightings(iteration));
			Map<String, String> audit = rowForIteration(run.activationAudit(), iteration);
			assertEquals("true", audit.get("freight_active"));
			assertEquals("1", audit.get("freight_agents_installed"));
			assertTrue(Integer.parseInt(audit.get("freight_events")) > 0);
			Map<String, String> summary = iterationSummary(run.output(), iteration);
			assertEquals("1", summary.get("freight_boardings"));
			assertEquals("1", summary.get("completed_orders"));
		}
		assertPassengerOutputs(run);
		assertRuntimeAuditOutputs(run, true);
		assertEquals(0, run.events().passengerStuckEvents());
		assertSerializedConfig(run.output());
	}

	@Test
	void controlArmKeepsTheSamePassengerTripButNeverInstallsFreight() throws Exception {
		RunResult run = runTinyPtArm("control", false);
		for (int iteration = 0; iteration <= 3; iteration++) {
			Map<String, String> audit = rowForIteration(run.activationAudit(), iteration);
			assertEquals("false", audit.get("treatment_active"));
			assertEquals("false", audit.get("schedule_active"));
			assertEquals("false", audit.get("freight_active"));
			assertEquals("0", audit.get("freight_agents_installed"));
			assertEquals("0", audit.get("freight_events"));
			assertEquals(0, run.events().freightBoardings(iteration));
			assertEquals("0", iterationSummary(run.output(), iteration).get("freight_boardings"));
		}
		assertPassengerOutputs(run);
		assertRuntimeAuditOutputs(run, false);
		assertEquals(0, run.events().passengerStuckEvents());
		assertSerializedConfig(run.output());
	}

	@Test
	void oneAndEightThreadRunsReproduceRegisteredPassengerAggregates() throws Exception {
		RunResult oneThread = runTinyPtArm("thread-1", false, 1);
		RunResult eightThreads = runTinyPtArm("thread-8", false, 8);
		for (String file : List.of("passenger_modestats.csv", "passenger_completed_modestats.csv",
				"passenger_scorestats.csv", "passenger_service_stats.csv",
				"passenger_adaptation_stats.csv", "passenger_departure_bins.csv",
				"passenger_mode_transitions.csv", "r17_event_fingerprint.csv")) {
			assertEquals(Files.readString(oneThread.output().resolve(file)),
					Files.readString(eightThreads.output().resolve(file)), file);
		}
	}

	@Test
	void controlAndMixedHaveIdenticalPreActivationEventFingerprints() throws Exception {
		RunResult control = runTinyPtArm("fingerprint-control", false);
		RunResult mixed = runTinyPtArm("fingerprint-mixed", true);
		List<Map<String, String>> controlRows = readDelimited(
				control.output().resolve("r17_event_fingerprint.csv"), ",");
		List<Map<String, String>> mixedRows = readDelimited(
				mixed.output().resolve("r17_event_fingerprint.csv"), ",");
		for (int iteration = 0; iteration <= 1; iteration++) {
			assertEquals(rowForIteration(controlRows, iteration).get("event_count"),
					rowForIteration(mixedRows, iteration).get("event_count"));
			assertEquals(rowForIteration(controlRows, iteration).get("event_sha256"),
					rowForIteration(mixedRows, iteration).get("event_sha256"));
		}
		assertFalse(rowForIteration(controlRows, 2).get("event_sha256")
				.equals(rowForIteration(mixedRows, 2).get("event_sha256")));
	}

	@Test
	void controlAndDedicatedHaveIdenticalPreActivationEventFingerprints() throws Exception {
		RunResult control = runTinyPtArm("fingerprint-dedicated-control", false);
		RunResult dedicated = runTinyDedicatedArm();
		List<Map<String, String>> controlRows = readDelimited(
				control.output().resolve("r17_event_fingerprint.csv"), ",");
		List<Map<String, String>> dedicatedRows = readDelimited(
				dedicated.output().resolve("r17_event_fingerprint.csv"), ",");
		for (int iteration = 0; iteration <= 1; iteration++) {
			assertEquals(rowForIteration(controlRows, iteration).get("event_count"),
					rowForIteration(dedicatedRows, iteration).get("event_count"));
			assertEquals(rowForIteration(controlRows, iteration).get("event_sha256"),
					rowForIteration(dedicatedRows, iteration).get("event_sha256"));
		}
		assertFalse(rowForIteration(controlRows, 2).get("event_sha256")
				.equals(rowForIteration(dedicatedRows, 2).get("event_sha256")));
	}

	@Test
	void dedicatedArmEnforcesPassengerFreightVehicleIsolationInRealQSim() throws Exception {
		RunResult run = runTinyDedicatedArm();
		for (int iteration = 0; iteration <= 1; iteration++) {
			assertEquals(0, run.events().freightBoardings(iteration));
			assertEquals(Set.of("tr_0"), run.events().passengerVehicles(iteration));
		}
		for (int iteration = 2; iteration <= 3; iteration++) {
			assertEquals(1, run.events().freightBoardings(iteration));
			assertEquals(Set.of("dedicated_vehicle"), run.events().freightVehicles(iteration));
			assertEquals(Set.of("tr_0"), run.events().passengerVehicles(iteration));
			Map<String, String> audit = rowForIteration(run.activationAudit(), iteration);
			assertEquals("true", audit.get("schedule_active"));
			assertEquals("true", audit.get("freight_active"));
		}
	}

	private RunResult runTinyDedicatedArm() throws Exception {
		Path armDirectory = tempDirectory.resolve("dedicated");
		Path output = armDirectory.resolve("output");
		Files.createDirectories(armDirectory);
		Path orders = armDirectory.resolve("orders.csv");
		Files.writeString(orders,
				"order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
						+ "cargo_order,07:30:00,09:00:00,0.1,1.0,standard,1,1\n");
		Config config = createConfig(output, 1);
		config.transit().setTransitModes(Set.of(TransportMode.pt, "freight_pt"));
		config.scoring().addModeParams(new ScoringConfigGroup.ModeParams("freight_pt")
				.setConstant(0.0).setMarginalUtilityOfTraveling(-6.0));
		Scenario scenario = ScenarioUtils.createScenario(config);
		TransitFixture passengerTransit = addTransitService(scenario);
		Path baselineSchedule = armDirectory.resolve("baseline_transit_schedule.xml");
		new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(baselineSchedule.toString());
		TransitFixture freightTransit = addDedicatedTransitService(scenario, passengerTransit);
		addPtPerson(scenario, Id.createPersonId(PASSENGER_ID), passengerTransit, false, TransportMode.pt);
		addPtPerson(scenario, Id.createPersonId(FREIGHT_ID), freightTransit, true, "freight_pt");

		CoTransportSettings settings = CoTransportSettings.builder(orders)
				.baselineTransitScheduleFile(baselineSchedule)
				.armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW).activationIteration(2)
				.dedicatedFreightLineId("freight_line")
				.freightServiceWindow(7.5 * 3600.0, 9.0 * 3600.0).build();
		PtEventAudit eventAudit = new PtEventAudit();
		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new PassengerFreightCoTransportModule(settings));
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(eventAudit);
			}
		});
		controler.run();
		return new RunResult(output, eventAudit,
				readDelimited(output.resolve("r17_activation_audit.csv"), ","));
	}

	private RunResult runTinyPtArm(String armName, boolean includeFreight) throws Exception {
		return runTinyPtArm(armName, includeFreight, 1);
	}

	private RunResult runTinyPtArm(String armName, boolean includeFreight, int threads) throws Exception {
		Path armDirectory = tempDirectory.resolve(armName);
		Path output = armDirectory.resolve("output");
		Files.createDirectories(armDirectory);
		Path orders = armDirectory.resolve("orders.csv");
		String orderCsv = "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n";
		if (includeFreight) {
			orderCsv += "cargo_order,07:30:00,09:00:00,0.1,1.0,standard,1,1\n";
		}
		Files.writeString(orders, orderCsv);

		Config config = createConfig(output, threads);
		Scenario scenario = ScenarioUtils.createScenario(config);
		TransitFixture transit = addTransitService(scenario);
		addPtPerson(scenario, Id.createPersonId(PASSENGER_ID), transit, false);
		if (includeFreight) {
			addPtPerson(scenario, Id.createPersonId(FREIGHT_ID), transit, true);
		}
		Path baselineSchedule = armDirectory.resolve("baseline_transit_schedule.xml");
		new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(baselineSchedule.toString());

		CoTransportSettings.Builder settings = CoTransportSettings.builder(orders)
				.baselineTransitScheduleFile(baselineSchedule)
				.freightServiceWindow(7.5 * 3600.0, 9.0 * 3600.0)
				.freightShareCap(0.25)
				.passengerLoadGate(1.0);
		if (includeFreight) {
			settings.armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW).activationIteration(2);
		} else {
			settings.armMode(CoTransportSettings.ArmMode.DISABLED).activationIteration(2);
		}

		PtEventAudit eventAudit = new PtEventAudit();
		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new PassengerFreightCoTransportModule(settings.build()));
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(eventAudit);
			}
		});
		controler.run();

		return new RunResult(output, eventAudit,
				readDelimited(output.resolve("r17_activation_audit.csv"), ","));
	}

	private static Config createConfig(Path output, int threads) {
		Config config = ConfigUtils.createConfig();
		config.global().setNumberOfThreads(threads);
		config.qsim().setNumberOfThreads(threads);
		config.qsim().setStartTime(7.0 * 3600.0);
		config.qsim().setEndTime(10.0 * 3600.0);
		config.transit().setUseTransit(true);
		config.routing().setNetworkRouteConsistencyCheck(RoutingConfigGroup.NetworkRouteConsistencyCheck.disable);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(3);
		config.controller().setOutputDirectory(output.toString());
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
		config.controller().setCompressionType(ControllerConfigGroup.CompressionType.none);
		config.controller().setWriteEventsInterval(0);
		config.controller().setWritePlansInterval(0);
		config.controller().setWriteTripsInterval(1);
		config.controller().setCreateGraphsInterval(0);
		config.controller().setDumpDataAtEnd(true);
		config.scoring().setWriteExperiencedPlans(false);
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("home")
				.setTypicalDuration(16.0 * 3600.0));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("work")
				.setTypicalDuration(8.0 * 3600.0));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("cargo_origin")
				.setTypicalDuration(16.0 * 3600.0));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_delivery")
				.setTypicalDuration(8.0 * 3600.0));
		config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
				.setStrategyName("KeepLastSelected").setWeight(1.0));
		config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
				.setStrategyName("KeepLastSelected").setSubpopulation("freight").setWeight(1.0));
		config.vspExperimental().setGeneratingBoardingDeniedEvent(true);
		return config;
	}

	private static TransitFixture addTransitService(Scenario scenario) {
		Node n0 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n0"), new Coord(0, 0));
		Node n1 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n1"), new Coord(1000, 0));
		Node n2 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n2"), new Coord(2000, 0));
		Link originLink = NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("origin_link"),
				n0, n1, 1000.0, 20.0, 3600.0, 1.0);
		Link destinationLink = NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("destination_link"),
				n1, n2, 1000.0, 20.0, 3600.0, 1.0);
		NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("return_1"),
				n2, n1, 1000.0, 20.0, 3600.0, 1.0);
		NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("return_0"),
				n1, n0, 1000.0, 20.0, 3600.0, 1.0);

		TransitSchedule schedule = scenario.getTransitSchedule();
		TransitScheduleFactory factory = schedule.getFactory();
		TransitStopFacility originStop = factory.createTransitStopFacility(
				Id.create("origin_stop", TransitStopFacility.class), new Coord(1000, 0), false);
		originStop.setLinkId(originLink.getId());
		TransitStopFacility destinationStop = factory.createTransitStopFacility(
				Id.create("destination_stop", TransitStopFacility.class), new Coord(2000, 0), false);
		destinationStop.setLinkId(destinationLink.getId());
		schedule.addStopFacility(originStop);
		schedule.addStopFacility(destinationStop);

		TransitRouteStop routeOrigin = factory.createTransitRouteStop(originStop, 0.0, 0.0);
		TransitRouteStop routeDestination = factory.createTransitRouteStop(destinationStop, 600.0, 600.0);
		NetworkRoute networkRoute = RouteUtils.createLinkNetworkRouteImpl(originLink.getId(), destinationLink.getId());
		networkRoute.setLinkIds(originLink.getId(), Collections.emptyList(), destinationLink.getId());
		TransitRoute route = factory.createTransitRoute(Id.create("mixed_route", TransitRoute.class),
				networkRoute, new ArrayList<>(List.of(routeOrigin, routeDestination)), "bus");
		route.addDeparture(factory.createDeparture(Id.create("departure_0800", Departure.class), 8.0 * 3600.0));
		TransitLine line = factory.createTransitLine(Id.create("mixed_line", TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);
		new CreateVehiclesForSchedule(schedule, scenario.getTransitVehicles()).run();
		return new TransitFixture(originLink, destinationLink, originStop, destinationStop, line, route);
	}

	private static TransitFixture addDedicatedTransitService(Scenario scenario, TransitFixture passenger) {
		TransitSchedule schedule = scenario.getTransitSchedule();
		TransitScheduleFactory factory = schedule.getFactory();
		TransitRouteStop routeOrigin = factory.createTransitRouteStop(passenger.originStop(), 0.0, 0.0);
		TransitRouteStop routeDestination = factory.createTransitRouteStop(
				passenger.destinationStop(), 600.0, 600.0);
		NetworkRoute networkRoute = RouteUtils.createLinkNetworkRouteImpl(
				passenger.originLink().getId(), passenger.destinationLink().getId());
		networkRoute.setLinkIds(passenger.originLink().getId(), Collections.emptyList(),
				passenger.destinationLink().getId());
		TransitRoute route = factory.createTransitRoute(Id.create("freight_route", TransitRoute.class),
				networkRoute, new ArrayList<>(List.of(routeOrigin, routeDestination)), "bus");
		Departure departure = factory.createDeparture(
				Id.create("freight_departure_0800", Departure.class), 8.0 * 3600.0);
		VehicleType vehicleType = scenario.getTransitVehicles().getVehicleTypes().values().iterator().next();
		Vehicle vehicle = scenario.getTransitVehicles().getFactory().createVehicle(
				Id.create("dedicated_vehicle", Vehicle.class), vehicleType);
		scenario.getTransitVehicles().addVehicle(vehicle);
		departure.setVehicleId(vehicle.getId());
		route.addDeparture(departure);
		TransitLine line = factory.createTransitLine(Id.create("freight_line", TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);
		return new TransitFixture(passenger.originLink(), passenger.destinationLink(),
				passenger.originStop(), passenger.destinationStop(), line, route);
	}

	private static void addPtPerson(Scenario scenario, Id<Person> personId, TransitFixture transit, boolean freight) {
		addPtPerson(scenario, personId, transit, freight, TransportMode.pt);
	}

	private static void addPtPerson(Scenario scenario, Id<Person> personId, TransitFixture transit,
			boolean freight, String legMode) {
		Person person = scenario.getPopulation().getFactory().createPerson(personId);
		Plan plan = scenario.getPopulation().getFactory().createPlan();
		Activity origin = scenario.getPopulation().getFactory().createActivityFromLinkId(
				freight ? "cargo_origin" : "home", transit.originLink().getId());
		origin.setEndTime(7.0 * 3600.0 + 55.0 * 60.0);
		plan.addActivity(origin);
		Leg leg = scenario.getPopulation().getFactory().createLeg(legMode);
		leg.setRoute(new DefaultTransitPassengerRoute(transit.originStop(), transit.line(),
				transit.route(), transit.destinationStop()));
		plan.addLeg(leg);
		plan.addActivity(scenario.getPopulation().getFactory().createActivityFromLinkId(
				freight ? "freight_delivery" : "work", transit.destinationLink().getId()));
		person.addPlan(plan);
		if (freight) {
			PopulationUtils.putSubpopulation(person, "freight");
		}
		scenario.getPopulation().addPerson(person);
	}

	private static void assertPassengerOutputs(RunResult run) throws Exception {
		List<Map<String, String>> selected = readDelimited(run.output().resolve("passenger_modestats.csv"), ";");
		List<Map<String, String>> completed = readDelimited(
				run.output().resolve("passenger_completed_modestats.csv"), ";");
		List<Map<String, String>> service = readDelimited(
				run.output().resolve("passenger_service_stats.csv"), ";");
		List<Map<String, String>> adaptation = readDelimited(
				run.output().resolve("passenger_adaptation_stats.csv"), ";");
		List<Map<String, String>> departures = readDelimited(
				run.output().resolve("passenger_departure_bins.csv"), ";");
		List<Map<String, String>> transitions = readDelimited(
				run.output().resolve("passenger_mode_transitions.csv"), ";");
		assertEquals(4, selected.size());
		assertEquals(4, completed.size());
		assertEquals(4, service.size());
		assertEquals(4, adaptation.size());
		assertEquals(4 * 6 * 30, departures.size());
		for (int iteration = 0; iteration <= 3; iteration++) {
			int currentIteration = iteration;
			Map<String, String> selectedRow = rowForIteration(selected, iteration);
			assertEquals("1", selectedRow.get("passenger_trips"));
			assertEquals(1.0, Double.parseDouble(selectedRow.get("pt")), 1e-12);
			Map<String, String> completedRow = rowForIteration(completed, iteration);
			assertEquals("1", completedRow.get("passenger_persons"));
			assertEquals("1", completedRow.get("completed_passenger_persons"), completed.toString());
			assertEquals("1", completedRow.get("completed_passenger_trips"), completed.toString());
			assertEquals(1.0, Double.parseDouble(completedRow.get("pt")), 1e-12);
			assertEquals(1, run.events().passengerBoardings(iteration));
			assertEquals(1, run.events().passengerAlightings(iteration));
			Map<String, String> serviceRow = rowForIteration(service, iteration);
			assertEquals("1", serviceRow.get("completed_passenger_trips"));
			assertEquals("1", serviceRow.get("completed_pt_trips"));
			double doorToDoor = Double.parseDouble(serviceRow.get("pt_door_to_door_mean_min"));
			double wait = Double.parseDouble(serviceRow.get("pt_wait_mean_min"));
			double inVehicle = Double.parseDouble(serviceRow.get("pt_in_vehicle_mean_min"));
			assertEquals(5.0, wait, 0.05);
			assertTrue(inVehicle > 0.0);
			assertEquals(doorToDoor, wait + inVehicle, 1e-9);
			assertEquals(1, departures.stream().filter(row -> Integer.toString(currentIteration).equals(row.get("iteration"))
					&& "pt".equals(row.get("mode")) && "7".equals(row.get("bin_start_hour"))
					&& "1".equals(row.get("selected_trip_count"))).count());
		}
		assertEquals("false", rowForIteration(adaptation, 0).get("reference_available"));
		for (int iteration = 1; iteration <= 3; iteration++) {
			int currentIteration = iteration;
			Map<String, String> row = rowForIteration(adaptation, iteration);
			assertEquals("true", row.get("reference_available"));
			assertEquals("1", row.get("compared_trips"));
			assertEquals("0", row.get("mode_changed_trips"));
			assertEquals(1, transitions.stream().filter(transition -> Integer.toString(currentIteration)
					.equals(transition.get("iteration")) && "pt".equals(transition.get("from_mode"))
					&& "pt".equals(transition.get("to_mode"))
					&& "1".equals(transition.get("selected_trip_count"))).count());
		}
	}

	private static void assertRuntimeAuditOutputs(RunResult run, boolean freightArm) throws Exception {
		for (int iteration = 0; iteration <= 3; iteration++) {
			Map<String, String> summary = iterationSummary(run.output(), iteration);
			assertEquals("false", summary.get("hard_capacity_ratio_active"));
			assertEquals(0.95, Double.parseDouble(summary.get("configured_hard_capacity_ratio")), 1e-12);
			assertTrue(Double.parseDouble(summary.get("max_load_ratio")) <= 1.0);

			List<Map<String, String>> loads = readDelimited(run.output().resolve(
					"ITERS/it." + iteration + "/" + iteration + ".coTransport_load_observations.csv"), ",");
			assertFalse(loads.isEmpty());
			for (Map<String, String> load : loads) {
				assertFalse(load.get("vehicle").isBlank());
				assertEquals("mixed_line", load.get("line"));
				assertEquals("mixed_route", load.get("route"));
				int total = Integer.parseInt(load.get("total_occupancy"));
				int passengers = Integer.parseInt(load.get("passenger_occupancy"));
				int freight = Integer.parseInt(load.get("freight_occupancy"));
				int capacity = Integer.parseInt(load.get("capacity"));
				assertEquals(total, passengers + freight);
				assertTrue(total >= 0 && total <= capacity);
			}
			assertEquals(loads.size(), Integer.parseInt(summary.get("load_observations")));

			List<Map<String, String>> deniedEvents = readDelimited(run.output().resolve(
					"ITERS/it." + iteration + "/" + iteration
							+ ".coTransport_denied_boarding_events.csv"), ",");
			assertTrue(deniedEvents.isEmpty());

			/*
			 * The per-person stuck receipt is deliberately emitted even when
			 * empty.  A full-scale Plan-A integrity failure can then identify
			 * the exact person/mode/link before its aggregate summary is
			 * consumed by the fail-closed executor.
			 */
			List<Map<String, String>> stuckEvents = readDelimited(run.output().resolve(
					"ITERS/it." + iteration + "/" + iteration + ".coTransport_stuck_events.csv"), ",");
			assertTrue(stuckEvents.isEmpty());
			assertEquals("0", summary.get("passenger_stuck_events"));
			assertEquals("0", summary.get("passenger_stuck_persons"));
			assertEquals("0", summary.get("freight_stuck_events"));

			if (freightArm && iteration >= 2) {
				assertEquals("1", summary.get("freight_capacity_units"));
				assertEquals("1", summary.get("freight_delivered_units"));
				assertEquals(1.0, Double.parseDouble(summary.get("unit_completion_rate")), 1e-12);
				assertEquals(0.0, Double.parseDouble(summary.get("lateness_p95_min")), 1e-12);
				assertEquals(30.0, Double.parseDouble(summary.get("wait_to_first_board_mean_min")), 0.1);
				assertTrue(loads.stream().anyMatch(row -> "1".equals(row.get("freight_occupancy"))));

				List<Map<String, String>> orders = readDelimited(run.output().resolve(
						"ITERS/it." + iteration + "/" + iteration + ".coTransport_freight_orders.csv"), ",");
				assertEquals(1, orders.size());
				assertEquals("cargo_order", orders.getFirst().get("boarded_agent_ids"));
				assertEquals("cargo_order", orders.getFirst().get("delivered_agent_ids"));
				assertEquals("completed_conserved", orders.getFirst().get("agent_balance_state"));
			} else if (!freightArm) {
				assertEquals("0", summary.get("freight_capacity_units"));
				assertEquals("0", summary.get("freight_delivered_units"));
				assertEquals(0.0, Double.parseDouble(summary.get("unit_completion_rate")), 1e-12);
			}
		}
	}

	private static void assertSerializedConfig(Path output) throws Exception {
		String outputConfig = Files.readString(output.resolve("output_config.xml"));
		assertTrue(outputConfig.contains("module name=\"coTransport\""));
		assertTrue(outputConfig.contains("activationIteration"));
		assertTrue(outputConfig.contains("passengerPhysicalCrowdingPenaltyPerMinute"));
		assertFalse(outputConfig.contains("freightOrdersFile\" value=\"null"));
	}

	private static Map<String, String> iterationSummary(Path output, int iteration) throws Exception {
		List<Map<String, String>> rows = readDelimited(output.resolve(
				"ITERS/it." + iteration + "/" + iteration + ".coTransport_summary.csv"), ",");
		assertEquals(1, rows.size());
		return rows.getFirst();
	}

	private static Map<String, String> rowForIteration(List<Map<String, String>> rows, int iteration) {
		return rows.stream().filter(row -> Integer.toString(iteration).equals(row.get("iteration")))
				.findFirst().orElseThrow();
	}

	private static List<Map<String, String>> readDelimited(Path file, String delimiter) throws Exception {
		List<String> lines = Files.readAllLines(file);
		String[] headers = lines.getFirst().split(Pattern.quote(delimiter), -1);
		List<Map<String, String>> rows = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			if (line.isBlank()) {
				continue;
			}
			String[] values = line.split(Pattern.quote(delimiter), -1);
			Map<String, String> row = new LinkedHashMap<>();
			for (int i = 0; i < headers.length; i++) {
				row.put(headers[i], i < values.length ? values[i] : "");
			}
			rows.add(row);
		}
		return rows;
	}

	private record TransitFixture(Link originLink, Link destinationLink,
			TransitStopFacility originStop, TransitStopFacility destinationStop,
			TransitLine line, TransitRoute route) {
	}

	private record RunResult(Path output, PtEventAudit events,
			List<Map<String, String>> activationAudit) {
	}

	private static final class PtEventAudit implements PersonEntersVehicleEventHandler,
			PersonLeavesVehicleEventHandler, PersonStuckEventHandler {
		private final Map<Integer, Integer> passengerBoardings = new LinkedHashMap<>();
		private final Map<Integer, Integer> passengerAlightings = new LinkedHashMap<>();
		private final Map<Integer, Integer> freightBoardings = new LinkedHashMap<>();
		private final Map<Integer, Integer> freightAlightings = new LinkedHashMap<>();
		private final Map<Integer, Set<String>> passengerVehicles = new LinkedHashMap<>();
		private final Map<Integer, Set<String>> freightVehicles = new LinkedHashMap<>();
		private int passengerStuckEvents;
		private int iteration = -1;

		@Override
		public void reset(int iteration) {
			this.iteration = iteration;
		}

		@Override
		public void handleEvent(PersonEntersVehicleEvent event) {
			if (PASSENGER_ID.equals(event.getPersonId().toString())) {
				passengerBoardings.merge(iteration, 1, Integer::sum);
				passengerVehicles.computeIfAbsent(iteration, ignored -> new java.util.LinkedHashSet<>())
						.add(event.getVehicleId().toString());
			} else if (FREIGHT_ID.equals(event.getPersonId().toString())) {
				freightBoardings.merge(iteration, 1, Integer::sum);
				freightVehicles.computeIfAbsent(iteration, ignored -> new java.util.LinkedHashSet<>())
						.add(event.getVehicleId().toString());
			}
		}

		@Override
		public void handleEvent(PersonLeavesVehicleEvent event) {
			if (PASSENGER_ID.equals(event.getPersonId().toString())) {
				passengerAlightings.merge(iteration, 1, Integer::sum);
			} else if (FREIGHT_ID.equals(event.getPersonId().toString())) {
				freightAlightings.merge(iteration, 1, Integer::sum);
			}
		}

		@Override
		public void handleEvent(PersonStuckEvent event) {
			if (PASSENGER_ID.equals(event.getPersonId().toString())) {
				passengerStuckEvents++;
			}
		}

		private int passengerBoardings(int iteration) {
			return passengerBoardings.getOrDefault(iteration, 0);
		}

		private int passengerAlightings(int iteration) {
			return passengerAlightings.getOrDefault(iteration, 0);
		}

		private int freightBoardings(int iteration) {
			return freightBoardings.getOrDefault(iteration, 0);
		}

		private int freightAlightings(int iteration) {
			return freightAlightings.getOrDefault(iteration, 0);
		}

		private Set<String> passengerVehicles(int iteration) {
			return Set.copyOf(passengerVehicles.getOrDefault(iteration, Set.of()));
		}

		private Set<String> freightVehicles(int iteration) {
			return Set.copyOf(freightVehicles.getOrDefault(iteration, Set.of()));
		}

		private int passengerStuckEvents() {
			return passengerStuckEvents;
		}
	}
}
