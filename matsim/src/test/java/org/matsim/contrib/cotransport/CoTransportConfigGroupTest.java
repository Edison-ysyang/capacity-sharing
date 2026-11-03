package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoTransportConfigGroupTest {
	@TempDir
	Path tempDirectory;

	@Test
	void roundTripsAllEffectiveParametersThroughXml() throws Exception {
		Path orders = tempDirectory.resolve("orders.csv");
		Path baselineSchedule = tempDirectory.resolve("baseline_schedule.xml");
		CoTransportConfigGroup original = new CoTransportConfigGroup();
		original.setFreightOrdersFile(orders.toString());
		original.setBaselineTransitScheduleFile(baselineSchedule.toString());
		original.setArmMode(CoTransportSettings.ArmMode.ITERATION_WINDOW);
		original.setBoardingOrder(CoTransportSettings.BoardingOrder.FIRST_COME_FIRST_SERVED);
		original.setFreightBoardingMode(CoTransportSettings.FreightBoardingMode.UNIT_SPLIT);
		original.setWholeOrderQueuePolicy(CoTransportSettings.WholeOrderQueuePolicy.FIT_FIRST);
		original.setActivationIteration(180);
		original.setDeactivationIteration(270);
		original.setInnovationOffIteration(150);
		original.setInnovationReopenIteration(180);
		original.setInnovationFinalOffIteration(300);
		original.setManageInnovationSchedule(true);
		original.setFreightEquivalentPassengersPerM3(5.0);
		original.setComfortThreshold(0.6);
		original.setHardCapacityRatio(0.95);
		original.setPassengerLoadGate(0.9);
		original.setFreightShareCap(0.2);
		original.setFreightHandlingDwellSeconds(60.0);
		original.setFreightLatePenaltyPerMinute(0.55);
		original.setFreightDeniedBoardingPenalty(10.0);
		original.setFreightIncompletePenaltyPerUnit(25.0);
		original.setFreightServiceWindowStartTime(13.5 * 3600.0);
		original.setFreightServiceWindowEndTime(16.5 * 3600.0);
		original.setPassengerDeniedBoardingPenalty(12.0);
		original.setPassengerPhysicalCrowdingPenaltyPerMinute(0.8);
		original.setPassengerFreightCoPresencePenaltyPerMinute(0.35);
		original.setFreightActivityPrefix("cargo_");
		original.setFreightShadowToken("_unit_");
		original.setDedicatedFreightLineId("dedicated_line");

		Config config = ConfigUtils.createConfig(original);
		Path configFile = tempDirectory.resolve("config.xml");
		ConfigUtils.writeConfig(config, configFile.toString());
		String xml = Files.readString(configFile);
		assertTrue(xml.contains("module name=\"coTransport\""));
		assertTrue(xml.contains("passengerFreightCoPresencePenaltyPerMinute"));
		assertTrue(xml.contains("freightHandlingDwellSeconds"));
		assertTrue(xml.contains("name=\"boardingOrder\" value=\"FIRST_COME_FIRST_SERVED\""));
		assertTrue(xml.contains("name=\"freightBoardingMode\" value=\"UNIT_SPLIT\""));
		assertTrue(xml.contains("name=\"wholeOrderQueuePolicy\" value=\"FIT_FIRST\""));

		Config loadedConfig = ConfigUtils.loadConfig(configFile.toString(), new CoTransportConfigGroup());
		CoTransportConfigGroup loaded = ConfigUtils.addOrGetModule(loadedConfig, CoTransportConfigGroup.class);
		CoTransportSettings settings = loaded.toSettings(loadedConfig);
		assertEquals(CoTransportSettings.ArmMode.ITERATION_WINDOW, settings.armMode());
		assertEquals(CoTransportSettings.BoardingOrder.FIRST_COME_FIRST_SERVED, settings.boardingOrder());
		assertEquals(CoTransportSettings.FreightBoardingMode.UNIT_SPLIT, settings.freightBoardingMode());
		assertEquals(CoTransportSettings.WholeOrderQueuePolicy.FIT_FIRST, settings.wholeOrderQueuePolicy());
		assertEquals(180, settings.activationIteration());
		assertEquals(270, settings.deactivationIteration());
		assertEquals(150, settings.innovationOffIteration());
		assertEquals(180, settings.innovationReopenIteration());
		assertEquals(300, settings.innovationFinalOffIteration());
		assertTrue(settings.manageInnovationSchedule());
		assertEquals(orders, settings.freightOrdersCsv());
		assertEquals(baselineSchedule, settings.baselineTransitScheduleFile());
		assertEquals(0.9, settings.passengerLoadGate(), 1e-12);
		assertEquals(0.2, settings.freightShareCap(), 1e-12);
		assertEquals(60.0, settings.freightHandlingDwellSeconds(), 1e-12);
		assertEquals(0.8, settings.passengerPhysicalCrowdingPenaltyPerMinute(), 1e-12);
		assertEquals(0.35, settings.passengerFreightCoPresencePenaltyPerMinute(), 1e-12);
		assertEquals("dedicated_line", settings.dedicatedFreightLineId());
	}

	@Test
	void legacySettingsRemainAlwaysActiveAndDoNotManageInnovation() {
		CoTransportSettings settings = new CoTransportSettings(tempDirectory.resolve("orders.csv"));
		assertEquals(CoTransportSettings.ArmMode.ALWAYS_ACTIVE, settings.armMode());
		assertEquals(CoTransportSettings.BoardingOrder.PASSENGER_FIRST, settings.boardingOrder());
		assertTrue(settings.isActiveAt(0));
		assertTrue(settings.isActiveAt(500));
		assertFalse(settings.manageInnovationSchedule());
		assertEquals(settings.passengerPhysicalCrowdingPenaltyPerMinute(),
				settings.passengerFreightCoPresencePenaltyPerMinute(), 1e-12);
	}

	@Test
	void omittedBoardingOrderPreservesPassengerFirstInXmlAndBuilder() throws Exception {
		Path orders = tempDirectory.resolve("orders.csv");
		Path file = tempDirectory.resolve("legacy_config.xml");
		Files.writeString(file, """
				<?xml version="1.0" encoding="UTF-8"?>
				<!DOCTYPE config SYSTEM "http://www.matsim.org/files/dtd/config_v2.dtd">
				<config><module name="coTransport">
				<param name="freightOrdersFile" value="orders.csv" />
				</module></config>
				""");
		Config loaded = ConfigUtils.loadConfig(file.toString(), new CoTransportConfigGroup());
		CoTransportConfigGroup co = ConfigUtils.addOrGetModule(loaded, CoTransportConfigGroup.class);
		assertEquals(CoTransportSettings.BoardingOrder.PASSENGER_FIRST, co.getBoardingOrder());
		assertEquals(CoTransportSettings.FreightBoardingMode.WHOLE_ORDER, co.getFreightBoardingMode());
		assertEquals(CoTransportSettings.WholeOrderQueuePolicy.STRICT_FIFO, co.getWholeOrderQueuePolicy());
		assertEquals(CoTransportSettings.FreightBoardingMode.WHOLE_ORDER, co.toSettings(loaded).freightBoardingMode());
		assertEquals(CoTransportSettings.WholeOrderQueuePolicy.STRICT_FIFO,
				CoTransportSettings.builder(orders).build().wholeOrderQueuePolicy());
		assertEquals(CoTransportSettings.BoardingOrder.PASSENGER_FIRST, co.toSettings(loaded).boardingOrder());
		assertEquals(CoTransportSettings.BoardingOrder.PASSENGER_FIRST,
				CoTransportSettings.builder(orders).build().boardingOrder());
	}

	@Test
	void applyAndBuilderPreserveStartupBoardingOrder() {
		for (CoTransportSettings.BoardingOrder order : CoTransportSettings.BoardingOrder.values()) {
			CoTransportSettings settings = CoTransportSettings.builder(tempDirectory.resolve("orders.csv"))
					.boardingOrder(order).build();
			CoTransportConfigGroup co = new CoTransportConfigGroup();
			co.apply(settings);
			assertEquals(order, co.getBoardingOrder());
			assertEquals(order, co.toSettings(ConfigUtils.createConfig(co)).boardingOrder());
		}
	}

	@Test
	void rejectsUnknownAndNullBoardingOrderWithoutChangingDefault() {
		CoTransportConfigGroup co = new CoTransportConfigGroup();
		assertThrows(IllegalArgumentException.class, () -> co.addParam("boardingOrder", "ORDER_RELEASE_FIRST"));
		assertThrows(IllegalArgumentException.class, () -> co.setBoardingOrder(null));
		assertThrows(IllegalArgumentException.class, () ->
				CoTransportSettings.builder(tempDirectory.resolve("orders.csv")).boardingOrder(null).build());
		assertEquals(CoTransportSettings.BoardingOrder.PASSENGER_FIRST, co.getBoardingOrder());
	}

	@Test
	void passengerReferenceClampsToFirstIterationForImmediateActivation() {
		assertEquals(0, PassengerOnlyIterationStats.referenceIteration(0, 0));
		assertEquals(179, PassengerOnlyIterationStats.referenceIteration(0, 180));
		assertEquals(40, PassengerOnlyIterationStats.referenceIteration(40, 0));
	}

	@Test
	void explicitFreightModesAndQueuePoliciesSurviveApplyAndRejectInvalidValues() {
		for (var mode : CoTransportSettings.FreightBoardingMode.values()) {
			for (var queue : CoTransportSettings.WholeOrderQueuePolicy.values()) {
				var settings = CoTransportSettings.builder(tempDirectory.resolve("orders.csv"))
						.freightBoardingMode(mode).wholeOrderQueuePolicy(queue).build();
				var co = new CoTransportConfigGroup(); co.apply(settings);
				var restored = co.toSettings(ConfigUtils.createConfig(co));
				assertEquals(mode, restored.freightBoardingMode());
				assertEquals(queue, restored.wholeOrderQueuePolicy());
			}
		}
		var co = new CoTransportConfigGroup();
		assertThrows(IllegalArgumentException.class, () -> co.setFreightBoardingMode(null));
		assertThrows(IllegalArgumentException.class, () -> co.setWholeOrderQueuePolicy(null));
		assertThrows(IllegalArgumentException.class, () -> co.addParam("freightBoardingMode", "SPLIT_IF_FULL"));
		assertThrows(IllegalArgumentException.class, () -> co.addParam("wholeOrderQueuePolicy", "UNKNOWN"));
		assertThrows(IllegalArgumentException.class, () -> CoTransportSettings.builder(tempDirectory.resolve("orders.csv"))
				.freightBoardingMode(null).build());
		assertThrows(IllegalArgumentException.class, () -> CoTransportSettings.builder(tempDirectory.resolve("orders.csv"))
				.wholeOrderQueuePolicy(null).build());
	}
}
