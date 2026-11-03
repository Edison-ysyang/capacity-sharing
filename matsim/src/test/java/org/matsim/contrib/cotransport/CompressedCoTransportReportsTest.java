package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Actual CSV/gzip output from a monitor supplied with deterministic manual events and mocked
 * controller services. No Controller, QSim, Mobsim, predictor or simulation is constructed.
 */
class CompressedCoTransportReportsTest {
	@TempDir Path temporary;
	private static final List<String> ITERATION_REPORTS = List.of("coTransport_stuck_events.csv",
			"coTransport_freight_orders.csv", "coTransport_whole_order_boardings.csv", "coTransport_summary.csv",
			"coTransport_denied_responses.csv", "coTransport_denied_boarding_events.csv", "coTransport_load_observations.csv");

	@Test void disabledIsTheDefaultInBuilderLegacyConstructorAndSerializedConfig() {
		Path orders = temporary.resolve("orders.csv");
		assertFalse(CoTransportSettings.builder(orders).build().compressReports());
		assertFalse(new CoTransportSettings(orders).compressReports());
		var group = new CoTransportConfigGroup(); group.setFreightOrdersFile(orders.toString());
		assertFalse(group.isCompressReports());
		assertFalse(group.toSettings(ConfigUtils.createConfig(group)).compressReports());
	}

	@Test void compressionFlagRoundTripsThroughApplyBuilderAndXmlWithoutPolicyChanges() throws Exception {
		for (boolean enabled : List.of(false, true)) {
			var original = CoTransportSettings.builder(temporary.resolve("orders.csv")).compressReports(enabled)
					.boardingOrder(CoTransportSettings.BoardingOrder.FIRST_COME_FIRST_SERVED).build();
			var group = new CoTransportConfigGroup(); group.apply(original);
			Path file = temporary.resolve("config-" + enabled + ".xml");
			ConfigUtils.writeConfig(ConfigUtils.createConfig(group), file.toString());
			assertTrue(Files.readString(file).contains("name=\"compressReports\" value=\"" + enabled + "\""));
			var loaded = ConfigUtils.loadConfig(file.toString(), new CoTransportConfigGroup());
			var restored = ConfigUtils.addOrGetModule(loaded, CoTransportConfigGroup.class).toSettings(loaded);
			assertEquals(enabled, restored.compressReports());
			assertEquals(original.boardingOrder(), restored.boardingOrder());
			assertEquals(original.freightBoardingMode(), restored.freightBoardingMode());
			assertEquals(original.wholeOrderQueuePolicy(), restored.wholeOrderQueuePolicy());
		}
	}

	@Test void allSevenActualIterationReportsAndRootAuditMatchPlainTextExactlyAfterDecompression() throws Exception {
		var plain = fixture("plain", false); var compressed = fixture("compressed", true);
		for (int iteration = 0; iteration < 2; iteration++) {
			for (Fixture fixture : List.of(plain, compressed)) {
				fixture.monitor.reset(iteration); fixture.supplyEvents(); fixture.write(iteration);
			}
			for (String report : ITERATION_REPORTS) {
				Path original = Path.of(plain.io.getIterationFilename(iteration, report));
				Path gzip = Path.of(compressed.io.getIterationFilename(iteration, report + ".gz"));
				assertTrue(Files.isRegularFile(original), report);
				assertTrue(Files.isRegularFile(gzip), report);
				assertEquals(Files.readString(original), text(gzip), report + " preserves every row and field");
				assertFalse(Files.exists(Path.of(compressed.io.getIterationFilename(iteration, report))));
				assertFalse(Files.exists(Path.of(plain.io.getIterationFilename(iteration, report + ".gz"))));
				byte[] bytes = Files.readAllBytes(gzip); assertEquals(0x1f, Byte.toUnsignedInt(bytes[0])); assertEquals(0x8b, Byte.toUnsignedInt(bytes[1]));
				assertTrue(text(gzip).lines().count() >= 2, report + " test exercises data, not only a header");
			}
		}
		Path originalRoot = Path.of(plain.io.getOutputFilename("r17_activation_audit.csv"));
		Path compressedRoot = Path.of(compressed.io.getOutputFilename("r17_activation_audit.csv.gz"));
		assertEquals(Files.readString(originalRoot), text(compressedRoot));
		assertEquals(3, text(compressedRoot).lines().count());
		assertFalse(Files.exists(Path.of(compressed.io.getOutputFilename("r17_activation_audit.csv"))));
	}

	@Test void cumulativeGzipRetainsFiftyRowsOneHeaderAndPreviouslyPersistedRowsAcrossMonitorInstances() throws Exception {
		var fixture = fixture("fifty", true);
		for (int iteration = 0; iteration < 50; iteration++) {
			fixture.monitor.reset(iteration); fixture.write(iteration);
		}
		Path root = Path.of(fixture.io.getOutputFilename("r17_activation_audit.csv.gz"));
		List<String> before = text(root).lines().toList(); assertEquals(51, before.size());
		for (int iteration = 0; iteration < 50; iteration++) assertTrue(before.get(iteration + 1).startsWith(iteration + ","));
		// Reusing the output writer is not a simulation/checkpoint resume: this checks only append semantics.
		var next = fixture("fifty", true); next.monitor.reset(50); next.write(50);
		List<String> after = text(root).lines().toList(); assertEquals(52, after.size());
		assertEquals(before, after.subList(0, 51)); assertTrue(after.getLast().startsWith("50,"));
		try (var paths = Files.list(root.getParent())) {
			assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith("r17_activation_audit.csv.gz-")));
		}
	}

	@Test void emptyValidGzipReceivesOneHeaderAndInvalidExistingGzipIsNotOverwritten() throws Exception {
		var empty = fixture("empty", true); Path emptyRoot = Path.of(empty.io.getOutputFilename("r17_activation_audit.csv.gz"));
		Files.createDirectories(emptyRoot.getParent());
		try (var writer = IOUtils.getBufferedWriter(emptyRoot.toString())) { writer.write(""); }
		empty.write(0); assertEquals(2, text(emptyRoot).lines().count());
		var corrupt = fixture("corrupt", true); Path corruptRoot = Path.of(corrupt.io.getOutputFilename("r17_activation_audit.csv.gz"));
		Files.createDirectories(corruptRoot.getParent()); byte[] sentinel = "not a gzip".getBytes(StandardCharsets.UTF_8); Files.write(corruptRoot, sentinel);
		assertThrows(UncheckedIOException.class, () -> corrupt.write(0));
		assertArrayEquals(sentinel, Files.readAllBytes(corruptRoot));
		try (var paths = Files.list(corruptRoot.getParent())) {
			assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith("r17_activation_audit.csv.gz-")));
		}
	}

	private static String text(Path path) throws Exception {
		try (var reader = IOUtils.getBufferedReader(path.toString())) {
			StringBuilder result = new StringBuilder(); char[] buffer = new char[8192]; int count;
			while ((count = reader.read(buffer)) != -1) result.append(buffer, 0, count);
			return result.toString();
		}
	}

	private Fixture fixture(String name, boolean compress) throws Exception {
		Path orders = temporary.resolve("orders.csv");
		Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
				+ "parcel,00:00:00,00:02:00,1.25,20,standard,1,5\n");
		var settings = CoTransportSettings.builder(orders).compressReports(compress).freightServiceWindow(100, 200).comfortThreshold(.2).build();
		var registry = new FreightOrderRegistry(settings); Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		for (var id : registry.unitIds()) scenario.getPopulation().addPerson(scenario.getPopulation().getFactory().createPerson(id));
		scenario.getPopulation().addPerson(scenario.getPopulation().getFactory().createPerson(Id.createPersonId("passenger")));
		var type = VehicleUtils.createVehicleType(Id.create("ten-place", VehicleType.class)); type.getCapacity().setSeats(10).setStandingRoom(0);
		scenario.getTransitVehicles().addVehicleType(type); scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(Id.createVehicleId("train"), type));
		var events = EventsUtils.createEventsManager();
		var phase = new CoTransportPhaseController(settings, registry, scenario, events, scenario.getConfig().controller(), new StrategyManager(), Map.of());
		var monitor = new CoTransportRuntimeMonitor(settings, phase, registry, scenario, events);
		var io = new OutputDirectoryHierarchy(temporary.resolve(name).toString(), null,
				OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists, false, ControllerConfigGroup.CompressionType.gzip);
		var services = mock(MatsimServices.class); when(services.getControllerIO()).thenReturn(io);
		return new Fixture(monitor, registry, io, services);
	}

	private record Fixture(CoTransportRuntimeMonitor monitor, FreightOrderRegistry registry,
			OutputDirectoryHierarchy io, MatsimServices services) {
		void write(int iteration) { monitor.notifyIterationEnds(new IterationEndsEvent(services, iteration, false)); }
		void supplyEvents() {
			var vehicle = Id.createVehicleId("train"); var stop = Id.create("A", TransitStopFacility.class);
			monitor.handleEvent(new TransitDriverStartsEvent(50, Id.createPersonId("driver"), vehicle,
					Id.create("METRO_L1", TransitLine.class), Id.create("AB", TransitRoute.class), Id.create("D1", Departure.class)));
			monitor.handleEvent(new VehicleArrivesAtFacilityEvent(60, vehicle, stop, 0));
			monitor.handleEvent(new BoardingDeniedEvent(70, Id.createPersonId("passenger"), vehicle));
			monitor.handleEvent(new BoardingDeniedEvent(80, Id.createPersonId("parcel"), vehicle));
			monitor.handleEvent(new PersonEntersVehicleEvent(90, Id.createPersonId("passenger"), vehicle));
			for (var id : registry.unitIds()) monitor.handleEvent(new PersonEntersVehicleEvent(100, id, vehicle));
			monitor.handleEvent(new VehicleDepartsAtFacilityEvent(101, vehicle, stop, 0));
			for (var id : registry.unitIds()) monitor.handleEvent(new PersonLeavesVehicleEvent(220, id, vehicle));
			monitor.handleEvent(new PersonLeavesVehicleEvent(230, Id.createPersonId("passenger"), vehicle));
			for (var id : registry.unitIds()) monitor.handleEvent(new ActivityStartEvent(300, id, Id.createLinkId("customer"), null, "freight_delivered"));
			monitor.handleEvent(new PersonStuckEvent(350, Id.createPersonId("passenger"), Id.createLinkId("walk"), "walk", "manual-output-fixture"));
		}
	}
}
