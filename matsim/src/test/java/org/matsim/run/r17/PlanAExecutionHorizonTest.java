package org.matsim.run.r17;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.BeforeMobsimEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAExecutionHorizonTest {
	private static final double BEHAVIOURAL_END = 30.0 * 3600.0;
	private static final double EXECUTION_END = 36.0 * 3600.0;

	@TempDir
	Path temporaryDirectory;

	@Test
	void exposesAndRoundTripsExecutionEndTimeWithoutChangingQsimEndTime() {
		var horizon = new PlanAExecutionHorizonConfigGroup();
		Config config = ConfigUtils.createConfig(horizon);
		config.qsim().setEndTime(BEHAVIOURAL_END);
		config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);

		assertEquals(PlanAExecutionHorizonConfigGroup.GROUP_NAME, horizon.getName());
		assertEquals(EXECUTION_END, horizon.getExecutionEndTime());
		assertEquals("36:00:00", horizon.getExecutionEndTimeAsString());

		horizon.setExecutionEndTime("40:30:00");
		assertEquals(40.5 * 3600.0, horizon.getExecutionEndTime());
		assertEquals(BEHAVIOURAL_END, config.qsim().getEndTime().seconds());

		Path configFile = temporaryDirectory.resolve("config.xml");
		new ConfigWriter(config).write(configFile.toString());
		Config reloaded = ConfigUtils.loadConfig(
				configFile.toString(), new PlanAExecutionHorizonConfigGroup());
		var reloadedHorizon = (PlanAExecutionHorizonConfigGroup) reloaded.getModule(
				PlanAExecutionHorizonConfigGroup.GROUP_NAME);
		assertEquals(40.5 * 3600.0, reloadedHorizon.getExecutionEndTime());
		assertEquals(BEHAVIOURAL_END, reloaded.qsim().getEndTime().seconds());
	}

	@Test
	void rejectsUndefinedOrNonSplitHorizons() {
		var undefinedConfig = ConfigUtils.createConfig(new PlanAExecutionHorizonConfigGroup());
		assertThrows(IllegalArgumentException.class, undefinedConfig::checkConsistency);

		var equalHorizon = new PlanAExecutionHorizonConfigGroup();
		equalHorizon.setExecutionEndTime(BEHAVIOURAL_END);
		Config equalConfig = ConfigUtils.createConfig(equalHorizon);
		equalConfig.qsim().setEndTime(BEHAVIOURAL_END);
		assertThrows(IllegalArgumentException.class, equalConfig::checkConsistency);

		var agentDependentHorizon = new PlanAExecutionHorizonConfigGroup();
		Config agentDependentConfig = ConfigUtils.createConfig(agentDependentHorizon);
		agentDependentConfig.qsim().setEndTime(BEHAVIOURAL_END);
		agentDependentConfig.qsim().setSimEndtimeInterpretation(
				QSimConfigGroup.EndtimeInterpretation.minOfEndtimeAndMobsimFinished);
		assertThrows(IllegalArgumentException.class, agentDependentConfig::checkConsistency);
	}

	@Test
	void adaptersHaveOppositePrioritiesRequiredByTheControllerLifecycle() {
		Fixture fixture = fixture("priority");
		var before = new PlanAExecutionHorizonControllerListeners.Before(fixture.horizonSwitch);
		var after = new PlanAExecutionHorizonControllerListeners.After(fixture.horizonSwitch);

		assertEquals(-1000.0, before.priority());
		assertTrue(before.priority() < 0.0,
				"activation must follow the priority-1000 canonicalizer and priority-0 audit");
		assertEquals(1000.0, after.priority());
		assertTrue(after.priority() > 0.0, "restoration must precede default-priority AfterMobsim listeners");
	}

	@Test
	void switchesOnlyAcrossMobsimAndWritesClosedReceipt() throws IOException {
		Fixture fixture = fixture("normal");
		var before = new PlanAExecutionHorizonControllerListeners.Before(fixture.horizonSwitch);
		var after = new PlanAExecutionHorizonControllerListeners.After(fixture.horizonSwitch);

		before.notifyBeforeMobsim(new BeforeMobsimEvent(null, 7, false));
		assertEquals(EXECUTION_END, fixture.config.qsim().getEndTime().seconds());
		assertTrue(fixture.horizonSwitch.isExecutionHorizonActive());

		JsonNode active = readAudit(fixture.outputDirectory);
		assertEquals(PlanAExecutionHorizonSwitch.AUDIT_SCHEMA, active.path("schema").asText());
		assertEquals(PlanAExecutionHorizonSwitch.QSIM_ACTIVE, active.path("execution_phase").asText());
		assertEquals(BEHAVIOURAL_END, active.path("behavioral_horizon_before_seconds").asDouble());
		assertEquals(EXECUTION_END, active.path("qsim_execution_horizon_seconds").asDouble());
		assertTrue(active.path("behavioral_horizon_restored_seconds").isNull());
		assertFalse(active.path("restored_after_mobsim").asBoolean());

		after.notifyAfterMobsim(new AfterMobsimEvent(null, 7, false));
		assertEquals(BEHAVIOURAL_END, fixture.config.qsim().getEndTime().seconds());
		assertFalse(fixture.horizonSwitch.isExecutionHorizonActive());

		JsonNode restored = readAudit(fixture.outputDirectory);
		assertEquals(PlanAExecutionHorizonSwitch.BEHAVIOURAL_RESTORED,
				restored.path("execution_phase").asText());
		assertEquals(BEHAVIOURAL_END,
				restored.path("behavioral_horizon_restored_seconds").asDouble());
		assertTrue(restored.path("restored_after_mobsim").asBoolean());
		assertEquals(1, restored.path("records").size());
	}

	@Test
	void restoresAndFailsClosedWhenQsimOrLifecycleBreaksTheContract() throws IOException {
		Fixture fixture = fixture("fail-closed");
		var before = new PlanAExecutionHorizonControllerListeners.Before(fixture.horizonSwitch);
		var after = new PlanAExecutionHorizonControllerListeners.After(fixture.horizonSwitch);

		before.notifyBeforeMobsim(new BeforeMobsimEvent(null, 3, false));
		fixture.config.qsim().setEndTime(35.0 * 3600.0);

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> after.notifyAfterMobsim(new AfterMobsimEvent(null, 3, false)));
		assertTrue(failure.getMessage().contains("failed closed"));
		assertEquals(BEHAVIOURAL_END, fixture.config.qsim().getEndTime().seconds());
		assertFalse(fixture.horizonSwitch.isExecutionHorizonActive());
		assertEquals(PlanAExecutionHorizonSwitch.FAIL_CLOSED_RESTORED,
				readAudit(fixture.outputDirectory).path("execution_phase").asText());
	}

	@Test
	void duplicateBeforeMobsimCannotLeakTheExecutionHorizon() {
		Fixture fixture = fixture("duplicate");
		var before = new PlanAExecutionHorizonControllerListeners.Before(fixture.horizonSwitch);

		before.notifyBeforeMobsim(new BeforeMobsimEvent(null, 2, false));
		assertThrows(IllegalStateException.class,
				() -> before.notifyBeforeMobsim(new BeforeMobsimEvent(null, 3, false)));

		assertEquals(BEHAVIOURAL_END, fixture.config.qsim().getEndTime().seconds());
		assertFalse(fixture.horizonSwitch.isExecutionHorizonActive());
	}

	@Test
	void afterMobsimIsSafeWhenAnEarlierBeforeListenerFailedBeforeActivation() {
		Fixture fixture = fixture("earlier-before-failure");
		var after = new PlanAExecutionHorizonControllerListeners.After(fixture.horizonSwitch);

		after.notifyAfterMobsim(new AfterMobsimEvent(null, 5, false));

		assertEquals(BEHAVIOURAL_END, fixture.config.qsim().getEndTime().seconds());
		assertFalse(fixture.horizonSwitch.isExecutionHorizonActive());
		assertFalse(Files.exists(fixture.outputDirectory.resolve(PlanAExecutionHorizonSwitch.AUDIT_FILE)));
	}

	private Fixture fixture(String name) {
		var horizon = new PlanAExecutionHorizonConfigGroup();
		Config config = ConfigUtils.createConfig(horizon);
		config.qsim().setEndTime(BEHAVIOURAL_END);
		config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
		Path output = temporaryDirectory.resolve(name);
		var controllerIO = new OutputDirectoryHierarchy(
				output.toString(),
				OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
				ControllerConfigGroup.CompressionType.none);
		return new Fixture(config, output, new PlanAExecutionHorizonSwitch(config, controllerIO));
	}

	private static JsonNode readAudit(Path outputDirectory) throws IOException {
		Path audit = outputDirectory.resolve(PlanAExecutionHorizonSwitch.AUDIT_FILE);
		assertTrue(Files.isRegularFile(audit));
		return new ObjectMapper().readTree(audit.toFile());
	}

	private record Fixture(Config config, Path outputDirectory, PlanAExecutionHorizonSwitch horizonSwitch) {
	}
}
