package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.replanning.PlanStrategyModule;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.replanning.selectors.RandomPlanSelector;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoTransportPhaseControllerTest {
	@TempDir
	Path tempDirectory;

	@Test
	void inactivePhaseRemovesFreightAndUsesBaselineScheduleUntilActivation() throws Exception {
		Path orders = writeOrders(true);
		Path baseline = writeSchedule("baseline_line");
		Scenario scenario = scenarioWithActivatedLineAndFreight();
		CoTransportSettings settings = CoTransportSettings.builder(orders)
				.armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW)
				.activationIteration(2)
				.deactivationIteration(3)
				.baselineTransitScheduleFile(baseline)
				.build();
		CoTransportPhaseController controller = controller(settings, scenario, 0, 3);

		controller.applyIteration(0);
		assertFalse(controller.isActive());
		assertFalse(controller.isFreightActive());
		assertFalse(controller.isScheduleActive());
		assertEquals(0, controller.installedFreightPersonCount());
		assertFalse(scenario.getPopulation().getPersons().containsKey(Id.createPersonId("cargo")));
		assertTrue(hasLine(scenario, "baseline_line"));
		assertFalse(hasLine(scenario, "activated_line"));

		controller.applyIteration(2);
		assertTrue(controller.isActive());
		assertTrue(controller.isFreightActive());
		assertTrue(controller.isScheduleActive());
		assertTrue(controller.isTreatmentActive());
		assertEquals(1, controller.installedFreightPersonCount());
		assertTrue(scenario.getPopulation().getPersons().containsKey(Id.createPersonId("cargo")));
		assertTrue(hasLine(scenario, "activated_line"));
		assertFalse(hasLine(scenario, "baseline_line"));

		controller.applyIteration(3);
		assertFalse(controller.isActive());
		assertEquals(0, controller.installedFreightPersonCount());
		assertTrue(hasLine(scenario, "baseline_line"));
	}

	@Test
	void inactiveIterationWithoutBaselineScheduleFailsPreflight() throws Exception {
		Path orders = writeOrders(true);
		Scenario scenario = scenarioWithActivatedLineAndFreight();
		CoTransportSettings settings = CoTransportSettings.builder(orders)
				.armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW)
				.activationIteration(2)
				.build();
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> controller(settings, scenario, 0, 3));
		assertTrue(error.getMessage().contains("baselineTransitScheduleFile"));
	}

	@Test
	void iterationWindowActivatedAtZeroIsActiveFromFirstIteration() throws Exception {
		Path orders = writeOrders(true);
		Scenario scenario = scenarioWithActivatedLineAndFreight();
		CoTransportSettings settings = CoTransportSettings.builder(orders)
				.armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW)
				.activationIteration(0)
				.deactivationIteration(-1)
				.build();
		CoTransportPhaseController controller = controller(settings, scenario, 0, 49);
		controller.applyIteration(0);
		assertTrue(controller.isActive());
		assertTrue(controller.isFreightActive());
		assertEquals(1, controller.installedFreightPersonCount());
	}

	@Test
	void timetableOnlyArmHasScheduleTreatmentButNoFreightTreatment() throws Exception {
		Path orders = writeOrders(false);
		Path baseline = writeSchedule("baseline_line");
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		scenario.getTransitSchedule().addTransitLine(scenario.getTransitSchedule().getFactory()
				.createTransitLine(Id.create("activated_line", TransitLine.class)));
		CoTransportSettings settings = CoTransportSettings.builder(orders)
				.armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW)
				.activationIteration(2)
				.baselineTransitScheduleFile(baseline)
				.build();
		CoTransportPhaseController controller = controller(settings, scenario, 0, 3);
		controller.applyIteration(0);
		controller.applyIteration(2);
		assertTrue(controller.isScheduleActive());
		assertFalse(controller.isFreightActive());
		assertTrue(controller.isTreatmentActive());
		assertEquals(0, controller.registeredFreightPersonCount());
	}

	@Test
	void emptyControlShamCountsExactlyOneActivationDespiteDuplicateLifecycleCalls() throws Exception {
		Path orders = writeOrders(false);
		Path baseline = writeSchedule("baseline_line");
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		scenario.getTransitSchedule().addTransitLine(scenario.getTransitSchedule().getFactory()
				.createTransitLine(Id.create("baseline_line", TransitLine.class)));
		var settings = CoTransportSettings.builder(orders).armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW)
				.activationIteration(20).baselineTransitScheduleFile(baseline).build();
		var phase = controller(settings, scenario, 0, 199);
		phase.applyIteration(0);
		phase.applyIteration(0);
		assertEquals(0, phase.activationCount());
		assertEquals(-1, phase.firstActivationIteration());
		phase.applyIteration(20);
		phase.applyIteration(20);
		phase.applyIteration(21);
		assertEquals(1, phase.activationCount());
		assertEquals(20, phase.firstActivationIteration());
		assertTrue(phase.isActive());
		assertFalse(phase.isTreatmentActive());
	}

	@Test
	void missingOfflineFreightPersonIsRejectedBeforeInactiveRemoval() throws Exception {
		Path orders = writeOrders(true);
		Path baseline = writeSchedule("baseline_line");
		Scenario scenario = scenarioWithActivatedLineAndFreight();
		scenario.getPopulation().removePerson(Id.createPersonId("cargo"));
		var settings = CoTransportSettings.builder(orders).armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW)
				.activationIteration(20).baselineTransitScheduleFile(baseline).build();
		assertThrows(IllegalStateException.class, () -> controller(settings, scenario, 0, 199));
		assertFalse(scenario.getPopulation().getPersons().containsKey(Id.createPersonId("cargo")));
	}

	@Test
	void innovationRequestsTargetPassengerInnovatorsOnlyAndRestoreConfiguredWeight() {
		CoTransportSettings settings = CoTransportSettings.builder(tempDirectory.resolve("orders.csv"))
				.manageInnovationSchedule(true)
				.innovationOffIteration(150)
				.innovationReopenIteration(180)
				.innovationFinalOffIteration(300)
				.build();
		PlanStrategy passengerInnovator = innovativeStrategy();
		PlanStrategy selector = new PlanStrategyImpl.Builder(new RandomPlanSelector<>()).build();
		PlanStrategy freightInnovator = innovativeStrategy();
		ReplanningConfigGroup.StrategySettings passenger = strategy("ReRoute", null, 0.15);
		ReplanningConfigGroup.StrategySettings passengerSelector = strategy("ChangeExpBeta", null, 0.85);
		ReplanningConfigGroup.StrategySettings freight = strategy("ReRoute", "freight", 0.4);
		Map<ReplanningConfigGroup.StrategySettings, PlanStrategy> strategies = new LinkedHashMap<>();
		strategies.put(passenger, passengerInnovator);
		strategies.put(passengerSelector, selector);
		strategies.put(freight, freightInnovator);

		List<CoTransportPhaseController.InnovationChangeRequest> requests =
				CoTransportPhaseController.innovationChangeRequests(settings, strategies);
		assertEquals(3, requests.size());
		assertEquals(List.of(150, 180, 300), requests.stream().map(
				CoTransportPhaseController.InnovationChangeRequest::iteration).toList());
		assertEquals(List.of(0.0, 0.15, 0.0), requests.stream().map(
				CoTransportPhaseController.InnovationChangeRequest::weight).toList());
		assertTrue(requests.stream().allMatch(request -> request.subpopulation() == null));
		assertTrue(requests.stream().allMatch(request -> request.strategy() == passengerInnovator));

		CoTransportSettings legacy = new CoTransportSettings(tempDirectory.resolve("orders.csv"));
		assertTrue(CoTransportPhaseController.innovationChangeRequests(legacy, strategies).isEmpty());
	}

	private CoTransportPhaseController controller(CoTransportSettings settings, Scenario scenario,
			int firstIteration, int lastIteration) {
		ControllerConfigGroup controllerConfig = new ControllerConfigGroup();
		controllerConfig.setFirstIteration(firstIteration);
		controllerConfig.setLastIteration(lastIteration);
		return new CoTransportPhaseController(settings, new FreightOrderRegistry(settings), scenario,
				EventsUtils.createEventsManager(), controllerConfig, new StrategyManager(), Map.of());
	}

	private Scenario scenarioWithActivatedLineAndFreight() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		scenario.getTransitSchedule().addTransitLine(scenario.getTransitSchedule().getFactory()
				.createTransitLine(Id.create("activated_line", TransitLine.class)));
		Person freight = scenario.getPopulation().getFactory().createPerson(Id.createPersonId("cargo"));
		scenario.getPopulation().addPerson(freight);
		Person passenger = scenario.getPopulation().getFactory().createPerson(Id.createPersonId("passenger"));
		scenario.getPopulation().addPerson(passenger);
		return scenario;
	}

	private Path writeOrders(boolean includeOrder) throws Exception {
		Path orders = tempDirectory.resolve(includeOrder ? "orders.csv" : "empty_orders.csv");
		String contents = "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n";
		if (includeOrder) {
			contents += "cargo,00:00:00,23:00:00,1.0,1.0,standard,1,1\n";
		}
		Files.writeString(orders, contents);
		return orders;
	}

	private Path writeSchedule(String lineId) {
		Scenario holder = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		holder.getTransitSchedule().addTransitLine(holder.getTransitSchedule().getFactory()
				.createTransitLine(Id.create(lineId, TransitLine.class)));
		Path schedule = tempDirectory.resolve(lineId + ".xml");
		new TransitScheduleWriter(holder.getTransitSchedule()).writeFile(schedule.toString());
		return schedule;
	}

	private static boolean hasLine(Scenario scenario, String lineId) {
		return scenario.getTransitSchedule().getTransitLines().containsKey(Id.create(lineId, TransitLine.class));
	}

	private static ReplanningConfigGroup.StrategySettings strategy(String name, String subpopulation, double weight) {
		return new ReplanningConfigGroup.StrategySettings()
				.setStrategyName(name)
				.setSubpopulation(subpopulation)
				.setWeight(weight);
	}

	private static PlanStrategy innovativeStrategy() {
		return new PlanStrategyImpl.Builder(new RandomPlanSelector<>())
				.addStrategyModule(new PlanStrategyModule() {
					@Override
					public void prepareReplanning(ReplanningContext replanningContext) {
					}

					@Override
					public void handlePlan(Plan plan) {
					}

					@Override
					public void finishReplanning() {
					}
				})
				.build();
	}
}
