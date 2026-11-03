package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.contrib.cotransport.CoTransportPhaseController;
import org.matsim.contrib.cotransport.CoTransportRuntimeMonitor;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingDeniedDetailEvent;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class IsmtRunArtifactWriterTest {
    private static final String RIDER = "passenger,\"one";
    @TempDir Path temp;

    @Test void standardArtifactsKeepPrecisionUnitsMissingValuesAndZeroBetaExposure() throws Exception {
        Fixture fixture = fixture(ExperimentSpec.ExposureOutput.TERMINAL);
        fixture.emptyIteration(0);
        fixture.emptyIteration(1);
        fixture.start(2);
        fixture.enter(RIDER, 0);
        fixture.enter("cargo", 60);
        fixture.leave("cargo", 660);
        fixture.leave(RIDER, 660);
        fixture.deliver(660);
        fixture.finish(2);
        var summary = csv(fixture.output.resolve("iteration_summary.csv"));
        assertEquals(3, summary.size());
        assertEquals(IsmtRunArtifactWriter.SUMMARY_COLUMNS.size(), summary.getLast().size());
        assertEquals("1.2345678912345", summary.getLast().get("selected_plan_passenger_score"));
        assertEquals("0.5", summary.getLast().get("pt_share"));
        assertEquals("1.0", summary.getLast().get("raw_freight_copresence_exposure"));
        assertEquals("0.0", summary.getLast().get("co_presence_penalty"));
        assertEquals("1", summary.getLast().get("completed_orders"));
        assertEquals("0", summary.getLast().get("service_window_completed_orders"));
        assertEquals("1", summary.getLast().get("eventual_completed_orders"));
        assertEquals("6.0", summary.getLast().get("mean_lateness_minutes"));
        assertEquals("", summary.getFirst().get("mean_lateness_minutes"));
        assertEquals("0", summary.getFirst().get("phase_activation_count"));
        assertEquals("-1", summary.getFirst().get("phase_activation_iteration"));
        assertEquals("1", summary.getLast().get("phase_activation_count"));
        assertEquals("1", summary.getLast().get("phase_activation_iteration"));
        var ledger = csv(fixture.output.resolve("order_ledger.csv"));
        assertEquals(3, ledger.size());
        assertEquals("60.0", ledger.getLast().get("first_unit_board_time_seconds"));
        assertEquals("60.0", ledger.getLast().get("last_unit_board_time_seconds"));
        assertEquals("660.0", ledger.getLast().get("completion_time_seconds"));
        assertEquals("", ledger.getLast().get("first_accessible_departure_time_seconds"));
        assertEquals("", ledger.getLast().get("schedule_wait_minutes"));
        assertEquals("", ledger.getLast().get("additional_admission_delay_minutes"));
        assertEquals(IsmtRunArtifactWriter.UNAVAILABLE_DEPARTURE, ledger.getLast().get("accessible_departure_status"));
        var exposure = csv(fixture.output.resolve("passenger_exposure.csv.gz"));
        assertEquals(2, exposure.size());
        assertTrue(exposure.stream().allMatch(row -> row.get("iteration").equals("2")));
        var rider = exposure.stream().filter(row -> row.get("person_id").equals(RIDER)).findFirst().orElseThrow();
        assertEquals("1.0", rider.get("raw_freight_copresence_exposure"));
        assertEquals("0.0", rider.get("total_added_penalty"));
        assertTrue(csv(fixture.output.resolve("denial_ledger.csv")).isEmpty());
        var schema = new ObjectMapper().readTree(fixture.output.resolve("artifact_schema.json").toFile());
        assertEquals("ismt-output-v1", schema.get("schema_version").asText());
        assertEquals("TERMINAL", schema.get("passenger_exposure_mode").asText());
    }

    @Test void reportingWindowGzipContainsAllRequestedIterationsWithoutDuplicateHeader() throws Exception {
        Fixture fixture = fixture(ExperimentSpec.ExposureOutput.REPORTING_WINDOW);
        fixture.emptyIteration(0);
        fixture.emptyIteration(1);
        fixture.emptyIteration(2);
        var rows = csv(fixture.output.resolve("passenger_exposure.csv.gz"));
        assertEquals(4, rows.size());
        assertEquals(List.of("1", "1", "2", "2"), rows.stream().map(row -> row.get("iteration")).toList());
        assertTrue(rows.stream().allMatch(row -> row.get("raw_freight_copresence_exposure").equals("0.0")));
    }

    @Test void nativeAndDetailedDenialsAreNotDoubleCountedAndDepartureIsUnavailable() throws Exception {
        Fixture fixture = fixture(ExperimentSpec.ExposureOutput.TERMINAL);
        fixture.emptyIteration(0);
        fixture.start(1);
        fixture.monitor.handleEvent(new BoardingDeniedEvent(0, Id.createPersonId("cargo"), fixture.vehicle));
        fixture.writer.handleEvent(detail(fixture.vehicle));
        fixture.finish(1);
        var summary = csv(fixture.output.resolve("iteration_summary.csv"));
        assertEquals("1", summary.getLast().get("freight_denied_boardings"));
        assertEquals("1", summary.getLast().get("denial_detail_events"));
        var orders = csv(fixture.output.resolve("order_ledger.csv"));
        assertEquals("1", orders.getLast().get("denial_count"));
        assertEquals("INELIGIBLE_SERVICE=1", orders.getLast().get("denial_reasons"));
        var denials = csv(fixture.output.resolve("denial_ledger.csv"));
        assertEquals(1, denials.size());
        assertEquals("1", denials.getFirst().get("iteration"));
        assertEquals("FREIGHT", denials.getFirst().get("actor_class"));
        assertEquals("", denials.getFirst().get("departure_id"));
        assertEquals("0.1", denials.getFirst().get("freight_share_cap"));
        assertEquals("0.9", denials.getFirst().get("passenger_load_gate"));
    }

    @Test void missingDetailCounterpartAndMissingPassengerIterationFailClosed() throws Exception {
        Fixture fixture = fixture(ExperimentSpec.ExposureOutput.TERMINAL);
        fixture.emptyIteration(0);
        fixture.start(1);
        fixture.monitor.handleEvent(new BoardingDeniedEvent(0, Id.createPersonId("cargo"), fixture.vehicle));
        fixture.passengerRows(1);
        assertThrows(IllegalStateException.class, () -> fixture.writer.writeIteration(1));
        assertEquals(1, csv(fixture.output.resolve("iteration_summary.csv")).size());
        fixture.writer.handleEvent(detail(fixture.vehicle));
        fixture.writer.writeIteration(1);
        fixture.start(2);
        assertThrows(java.io.UncheckedIOException.class, () -> fixture.writer.writeIteration(2));
    }

    @Test void duplicateMissingAndStaleIterationsNeverAppendAgain() throws Exception {
        Fixture fixture = fixture(ExperimentSpec.ExposureOutput.TERMINAL);
        fixture.emptyIteration(0);
        assertThrows(IllegalStateException.class, () -> fixture.writer.writeIteration(0));
        fixture.start(2);
        fixture.passengerRows(2);
        assertThrows(IllegalStateException.class, () -> fixture.writer.writeIteration(2));
        assertEquals(1, csv(fixture.output.resolve("iteration_summary.csv")).size());
    }

    @Test void existingArtifactIsPreservedRatherThanOverwritten() throws Exception {
        Fixture fixture = fixture(ExperimentSpec.ExposureOutput.TERMINAL);
        Path existing = fixture.output.resolve("iteration_summary.csv");
        Files.writeString(existing, "precious previous evidence\n");
        fixture.start(0);
        fixture.passengerRows(0);
        assertThrows(java.io.UncheckedIOException.class, () -> fixture.writer.writeIteration(0));
        assertEquals("precious previous evidence\n", Files.readString(existing));
    }

    @Test void unfinishedRideIsExportedWithoutFabricatedAppliedScore() throws Exception {
        Fixture fixture = fixture(ExperimentSpec.ExposureOutput.TERMINAL);
        fixture.emptyIteration(0);
        fixture.emptyIteration(1);
        fixture.start(2);
        fixture.enter(RIDER, 0);
        fixture.enter("cargo", 0);
        fixture.leave("cargo", 60);
        fixture.finish(2);
        var summary = csv(fixture.output.resolve("iteration_summary.csv"));
        assertEquals("1", summary.getLast().get("unfinished_passenger_rides"));
        var rows = csv(fixture.output.resolve("passenger_exposure.csv.gz"));
        var rider = rows.stream().filter(row -> row.get("person_id").equals(RIDER)).findFirst().orElseThrow();
        assertEquals("true", rider.get("unfinished_ride"));
        assertEquals("0.1", rider.get("raw_freight_copresence_exposure"));
        assertEquals("0.0", rider.get("total_added_penalty"));
    }

    @Test void vehicleDetailOutputOptionChangesOnlyDetailedFileNotExposureOrSummary() throws Exception {
        Fixture off = fixture(ExperimentSpec.ExposureOutput.TERMINAL, false, "detail-off");
        Fixture on = fixture(ExperimentSpec.ExposureOutput.TERMINAL, true, "detail-on");
        for (Fixture fixture : List.of(off, on)) {
            fixture.start(1);
            fixture.enter(RIDER, 0); fixture.enter("cargo", 0);
            fixture.monitor.handleEvent(new VehicleDepartsAtFacilityEvent(0, fixture.vehicle,
                    Id.create("stop", TransitStopFacility.class), 0));
            fixture.leave("cargo", 60); fixture.leave(RIDER, 60);
            MatsimServices services = (MatsimServices) Proxy.newProxyInstance(MatsimServices.class.getClassLoader(),
                    new Class<?>[]{MatsimServices.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getControllerIO")) return fixture.io;
                        throw new UnsupportedOperationException(method.getName());
                    });
            fixture.monitor.notifyIterationEnds(new IterationEndsEvent(services, 1, false));
        }
        assertFalse(off.monitor.detailedVehicleOutputEnabled());
        assertTrue(on.monitor.detailedVehicleOutputEnabled());
        assertFalse(Files.exists(Path.of(off.io.getIterationFilename(1, "coTransport_load_observations.csv"))));
        assertTrue(Files.exists(Path.of(on.io.getIterationFilename(1, "coTransport_load_observations.csv"))));
        assertEquals(off.monitor.passengerExposureSnapshot(), on.monitor.passengerExposureSnapshot());
        assertEquals(Files.readString(Path.of(off.io.getIterationFilename(1, "coTransport_summary.csv"))),
                Files.readString(Path.of(on.io.getIterationFilename(1, "coTransport_summary.csv"))));
        assertThrows(IllegalStateException.class, () -> off.monitor.configureDetailedVehicleOutput(true));
    }

    private Fixture fixture(ExperimentSpec.ExposureOutput mode) throws Exception {
        return fixture(mode, false, "output");
    }

    private Fixture fixture(ExperimentSpec.ExposureOutput mode, boolean vehicleDetails, String outputName) throws Exception {
        Path ordersPath = temp.resolve("orders.csv");
        Files.writeString(ordersPath, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
                + "cargo,00:00:00,00:05:00,0.2,1.0,standard,1,1\n");
        ExperimentSpec.FileRef ref = new ExperimentSpec.FileRef("test-input.xml", "a".repeat(64));
        ExperimentSpec spec = new ExperimentSpec(ExperimentSpec.SCHEMA, "writer-fixture", "B1", ExperimentSpec.Arm.MIXED,
                new ExperimentSpec.Seeds(1, 2, 3), new ExperimentSpec.Inputs(ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref),
                new ExperimentSpec.Protocol(0, 1, 1, 1, 1, 2, 1, 2, 86400, 129600),
                new ExperimentSpec.FreightDemand(1, 5, 0, 600, "freight_", "_cap_"),
                new ExperimentSpec.Policies(ExperimentSpec.Access.SHARED_PASSENGER_SERVICE, "", List.of("pt", "freight_pt"), 0.1, 0.9, 1.0,
                        0, true, 0.6, 0.8, 0, 0, 0, 0, 0),
                new ExperimentSpec.OutputOptions(IsmtRunArtifactWriter.SCHEMA, mode, vehicleDetails, 0, 0), "fixture");
        Path baseline = temp.resolve("baseline-schedule.xml");
        new TransitScheduleWriter(ScenarioUtils.createScenario(ConfigUtils.createConfig()).getTransitSchedule()).writeFile(baseline.toString());
        CoTransportSettings settings = CoTransportSettings.builder(ordersPath).comfortThreshold(0.6)
                .armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW).activationIteration(1).baselineTransitScheduleFile(baseline)
                .freightServiceWindow(0, 600)
                .passengerPhysicalCrowdingPenaltyPerMinute(0.8).passengerFreightCoPresencePenaltyPerMinute(0).build();
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        for (String person : List.of(RIDER, "other", "cargo"))
            scenario.getPopulation().addPerson(scenario.getPopulation().getFactory().createPerson(Id.createPersonId(person)));
        VehicleType type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class));
        type.getCapacity().setSeats(10); type.getCapacity().setStandingRoom(0);
        Id<Vehicle> vehicle = Id.createVehicleId("train");
        scenario.getTransitVehicles().addVehicleType(type);
        scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(vehicle, type));
        FreightOrderRegistry registry = new FreightOrderRegistry(settings);
        var events = EventsUtils.createEventsManager();
        ControllerConfigGroup controller = new ControllerConfigGroup();
        controller.setLastIteration(2);
        var phase = new CoTransportPhaseController(settings, registry, scenario, events, controller, new StrategyManager(), Map.of());
        var monitor = new CoTransportRuntimeMonitor(settings, phase, registry, scenario, events);
        Path output = temp.resolve(outputName);
        var io = new OutputDirectoryHierarchy(output.toString(), "test-run", OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                ControllerConfigGroup.CompressionType.gzip);
        return new Fixture(new IsmtRunArtifactWriter(spec, io, monitor, registry), monitor, phase, io, output, vehicle);
    }

    private static TransitBoardingDeniedDetailEvent detail(Id<Vehicle> vehicle) {
        return new TransitBoardingDeniedDetailEvent(0, Id.createPersonId("cargo"), vehicle,
                Id.create("line", TransitLine.class), Id.create("route", TransitRoute.class), null,
                Id.create("stop", TransitStopFacility.class), TransitBoardingPolicy.DenialReason.INELIGIBLE_SERVICE,
                new TransitBoardingPolicy.DenialDetails(TransitBoardingPolicy.ActorClass.FREIGHT, 1, 0, 10, 0.1, 0.9));
    }

    private static List<CSVRecord> csv(Path path) throws IOException {
        try (Reader reader = path.toString().endsWith(".gz")
                ? new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8)
                : Files.newBufferedReader(path, StandardCharsets.UTF_8);
             var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            return parser.getRecords();
        }
    }

    private record Fixture(IsmtRunArtifactWriter writer, CoTransportRuntimeMonitor monitor, CoTransportPhaseController phase, OutputDirectoryHierarchy io,
                           Path output, Id<Vehicle> vehicle) {
        void start(int iteration) {
            phase.notifyIterationStarts(new IterationStartsEvent(null, iteration, iteration == 2));
            monitor.reset(iteration); writer.reset(iteration);
            monitor.handleEvent(new TransitDriverStartsEvent(0, Id.createPersonId("driver"), vehicle,
                    Id.create("line", TransitLine.class), Id.create("route", TransitRoute.class), Id.create("dep", Departure.class)));
        }
        void emptyIteration(int iteration) throws IOException { start(iteration); finish(iteration); }
        void finish(int iteration) throws IOException { passengerRows(iteration); writer.writeIteration(iteration); }
        void enter(String id, double time) { monitor.handleEvent(new PersonEntersVehicleEvent(time, Id.createPersonId(id), vehicle)); }
        void leave(String id, double time) { monitor.handleEvent(new PersonLeavesVehicleEvent(time, Id.createPersonId(id), vehicle)); }
        void deliver(double time) { monitor.handleEvent(new ActivityStartEvent(time, Id.createPersonId("cargo"), null, null, "freight_destination", null)); }
        void passengerRows(int iteration) throws IOException {
            row("passenger_modestats.csv", "iteration;passenger_trips;pt\n", iteration + ";2;0.5\n");
            row("passenger_scorestats.csv", "iteration;passenger_persons;scored_persons;avg_selected_score\n", iteration + ";2;2;1.2345678912345\n");
            row("passenger_service_stats.csv", "iteration;completed_pt_trips;pt_door_to_door_mean_min;pt_wait_mean_min\n", iteration + ";1;12.5;2.5\n");
        }
        void row(String name, String header, String row) throws IOException {
            Path path = Path.of(io.getOutputFilename(name));
            if (!Files.exists(path)) Files.writeString(path, header, StandardOpenOption.CREATE_NEW);
            Files.writeString(path, row, StandardOpenOption.APPEND);
        }
    }
}
