package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.cotransport.CoTransportSettings.ArmMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.ExperiencedPlansService;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("ismt-integration")
class CoTransportActivationSmokeTest {
	@TempDir
	Path tempDirectory;

	@Test
	void activatedFreightGetsScorerAndExperiencedPlanInSameRun() throws Exception {
		Path orders = tempDirectory.resolve("orders.csv");
		Files.writeString(orders,
				"order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
						+ "cargo,00:00:00,23:00:00,1.0,1.0,standard,1,1\n");
		Path baseline = tempDirectory.resolve("baseline_schedule.xml");
		Scenario emptyScheduleHolder = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleWriter(emptyScheduleHolder.getTransitSchedule()).writeFile(baseline.toString());

		// R17 loads a serialized coTransport group before the Controler is
		// created. Keep that path in this smoke test so a duplicate Guice binding
		// is caught before any full-size scenario is launched.
		Config config = ConfigUtils.createConfig(new CoTransportConfigGroup());
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(1);
		config.controller().setOutputDirectory(tempDirectory.resolve("output").toString());
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
		config.controller().setWriteEventsInterval(0);
		config.controller().setWritePlansInterval(0);
		config.controller().setWriteTripsInterval(0);
		config.controller().setCreateGraphsInterval(0);
		config.controller().setDumpDataAtEnd(false);
		config.transit().setUseTransit(false);
		config.scoring().setWriteExperiencedPlans(false);
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("home")
				.setTypicalDuration(24.0 * 3600.0));
		config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
				.setStrategyName("KeepLastSelected").setWeight(1.0));

		Scenario scenario = ScenarioUtils.createScenario(config);
		Node from = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("from"), new Coord(0, 0));
		Node to = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("to"), new Coord(100, 0));
		Link link = NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("link"),
				from, to, 100.0, 10.0, 3600.0, 1.0);
		NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("return_link"),
				to, from, 100.0, 10.0, 3600.0, 1.0);
		Person freight = scenario.getPopulation().getFactory().createPerson(Id.createPersonId("cargo"));
		Plan plan = scenario.getPopulation().getFactory().createPlan();
		Activity home = scenario.getPopulation().getFactory().createActivityFromLinkId("home", link.getId());
		plan.addActivity(home);
		freight.addPlan(plan);
		scenario.getPopulation().addPerson(freight);

		CoTransportSettings settings = CoTransportSettings.builder(orders)
				.armMode(ArmMode.ITERATION_WINDOW)
				.activationIteration(1)
				.baselineTransitScheduleFile(baseline)
				.build();
		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new PassengerFreightCoTransportModule(settings));
		controler.run();

		assertNotNull(freight.getSelectedPlan().getScore());
		ExperiencedPlansService experiencedPlans = controler.getInjector().getInstance(ExperiencedPlansService.class);
		assertTrue(experiencedPlans.getExperiencedPlans().containsKey(freight.getId()));
		String audit = Files.readString(tempDirectory.resolve("output/r17_activation_audit.csv"));
		assertTrue(audit.contains("1,true,true,false,true,1,1"));
	}
}
