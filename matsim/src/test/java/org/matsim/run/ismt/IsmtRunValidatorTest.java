package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class IsmtRunValidatorTest {
	@TempDir Path temp;
	private int sequence;

	@Test void acceptsCompleteSingleRunAndReturnsImmutableActualActivationEvidence() throws Exception {
		Fixture fixture = fixture(false, .8, ExperimentSpec.ExposureOutput.REPORTING_WINDOW);
		var result = fixture.validate();
		assertTrue(result.valid(), result.issues().toString());
		assertEquals(3, result.finalIteration()); assertEquals(1, result.activationCount()); assertEquals(1, result.activationIteration());
		assertEquals(Set.of(0), result.preActivationFingerprint().keySet());
		assertEquals(4L, result.artifactRowCounts().get("iteration_summary.csv"));
		assertEquals(4L, result.artifactRowCounts().get("passenger_exposure.csv.gz"));
		assertThrows(UnsupportedOperationException.class, () -> result.checks().clear());
		assertThrows(UnsupportedOperationException.class, () -> result.preActivationFingerprint().clear());
	}

	@Test void controlShamAndTerminalOnlyExposureRemainValid() throws Exception {
		Fixture fixture = fixture(true, .8, ExperimentSpec.ExposureOutput.TERMINAL);
		var result = fixture.validate();
		assertTrue(result.valid(), result.issues().toString());
		assertEquals(1, result.activationCount());
		assertEquals(0L, result.artifactRowCounts().get("order_ledger.csv"));
		assertEquals(2L, result.artifactRowCounts().get("passenger_exposure.csv.gz"));
	}

	@Test void zeroBetaKeepsPositiveRawExposureAndRequiresExactlyZeroPenalty() throws Exception {
		Fixture fixture = fixture(false, 0, ExperimentSpec.ExposureOutput.REPORTING_WINDOW);
		assertTrue(fixture.validate().valid());
		assertEquals("1.5", fixture.summaries.get(3).get("raw_freight_copresence_exposure"));
		fixture.exposure.getLast().put("co_presence_penalty", "1e-16");
		assertFailed(fixture.validate(), "passenger_exposure");
	}

	@Test void rejectsIdentitySchemaAndIterationCoverageMismatches() throws Exception {
		Fixture missing = fixture(); missing.summaries.remove(1); assertFailed(missing.validate(), "iteration_summary");
		Fixture duplicate = fixture(); duplicate.summaries.add(1, new LinkedHashMap<>(duplicate.summaries.getFirst())); assertFailed(duplicate.validate(), "iteration_summary");
		Fixture identity = fixture(); identity.summaries.get(2).put("block_id", "B2"); assertFailed(identity.validate(), "iteration_summary");
		Fixture schema = fixture(); schema.orders.getFirst().put("schema_version", "other"); assertFailed(schema.validate(), "order_ledger");
		Fixture fingerprint = fixture(); fingerprint.fingerprints.remove(2); assertFailed(fingerprint.validate(), "passenger_event_fingerprint");
	}

	@Test void rejectsEditedSchemaUnitsAndMissingExposureRows() throws Exception {
		Fixture schema = fixture(); schema.write();
		Path path = schema.root.resolve("matsim/artifact_schema.json");
		ObjectMapper mapper = new ObjectMapper();
		var node = mapper.readTree(path.toFile());
		((com.fasterxml.jackson.databind.node.ObjectNode) node.path("field_definitions").path("iteration_summary.csv").get(5)).put("unit", "fabricated units");
		mapper.writeValue(path.toFile(), node);
		assertFailed(new IsmtRunValidator().validate(schema.root, schema.spec), "artifact_schema");
		Fixture exposure = fixture(); exposure.exposure.removeLast(); assertFailed(exposure.validate(), "passenger_exposure");
	}

	@Test void rejectsFakeActivationCountersAndPreactivationFreightEvenWhenPhaseLooksRight() throws Exception {
		Fixture repeat = fixture(); repeat.summaries.get(2).put("phase_activation_count", "2"); assertFailed(repeat.validate(), "iteration_summary");
		Fixture wrongTime = fixture(); wrongTime.summaries.get(1).put("phase_activation_iteration", "0"); assertFailed(wrongTime.validate(), "iteration_summary");
		Fixture leakage = fixture(); leakage.summaries.getFirst().put("freight_events", "1"); assertFailed(leakage.validate(), "iteration_summary");
	}

	@Test void incompleteOrdersAreNotSoftwareFailuresButFabricatedCompletionIs() throws Exception {
		Fixture fixture = fixture();
		for (int iteration = 1; iteration <= 3; iteration++) {
			var order = fixture.orders.get(iteration);
			order.put("units_delivered", "1"); order.put("delivery_events", "1");
			for (String field : List.of("completed", "completed_in_service_window", "completed_within_execution_horizon", "on_time")) order.put(field, "false");
			order.put("completion_time_seconds", ""); order.put("lateness_minutes", "");
			var summary = fixture.summaries.get(iteration);
			for (String field : List.of("completed_orders", "service_window_completed_orders", "eventual_completed_orders", "on_time_orders", "completed_order_lateness_sample_size")) summary.put(field, "0");
			summary.put("mean_lateness_minutes", "");
		}
		assertTrue(fixture.validate().valid());
		fixture.orders.get(2).put("completed", "true"); assertFailed(fixture.validate(), "order_ledger");
	}

	@Test void distinguishesServiceWindowFromEventualCompletionAndDoesNotInventAccessibleDepartures() throws Exception {
		Fixture fixture = fixture();
		for (int iteration = 1; iteration <= 3; iteration++) {
			fixture.orders.get(iteration).put("completion_time_seconds", "600");
			fixture.orders.get(iteration).put("lateness_minutes", Double.toString(500.0 / 60));
			fixture.orders.get(iteration).put("completed_in_service_window", "false");
			fixture.summaries.get(iteration).put("service_window_completed_orders", "0");
			fixture.summaries.get(iteration).put("mean_lateness_minutes", Double.toString(500.0 / 60));
		}
		assertTrue(fixture.validate().valid());
		fixture.orders.get(2).put("first_accessible_departure_time_seconds", "20"); assertFailed(fixture.validate(), "order_ledger");
	}

	@Test void positiveFreightDenialsAreValidWhenReasonsOccupancyAndCountsReconcile() throws Exception {
		Fixture fixture = fixture();
		fixture.freightDenial(2);
		assertTrue(fixture.validate().valid());
		fixture.denials.getFirst().put("denial_reason", "NATIVE_CAPACITY");
		assertFailed(fixture.validate(), "denial_ledger");
	}

	@Test void rejectsOrderIdentityChangesDuplicateDeliveryAndLedgerCountMismatch() throws Exception {
		Fixture ids = fixture(); ids.orders.get(2).put("order_id", "changed"); assertFailed(ids.validate(), "order_ledger");
		Fixture duplicate = fixture(); duplicate.orders.add(1, new LinkedHashMap<>(duplicate.orders.getFirst())); assertFailed(duplicate.validate(), "order_ledger");
		Fixture delivery = fixture(); delivery.orders.get(1).put("delivery_events", "3"); assertFailed(delivery.validate(), "order_ledger");
		Fixture counts = fixture(); counts.summaries.get(2).put("completed_orders", "0"); assertFailed(counts.validate(), "order_ledger");
	}

	@Test void rejectsUnfinishedStateNativeOverloadNonfiniteOrDuplicateExposureAndWrongAggregation() throws Exception {
		Fixture state = fixture(); state.summaries.get(2).put("exposure_event_state_valid", "false"); assertFailed(state.validate(), "iteration_summary");
		Fixture rides = fixture(); rides.summaries.get(2).put("unfinished_passenger_rides", "1"); assertFailed(rides.validate(), "iteration_summary");
		Fixture freight = fixture(); freight.summaries.get(2).put("unfinished_freight_units_onboard", "1"); assertFailed(freight.validate(), "iteration_summary");
		Fixture capacity = fixture(); capacity.summaries.get(2).put("max_load_ratio", "1.0000000001"); assertFailed(capacity.validate(), "iteration_summary");
		Fixture nan = fixture(); nan.exposure.getFirst().put("raw_physical_exposure", "NaN"); assertFailed(nan.validate(), "passenger_exposure");
		Fixture ids = fixture(); ids.exposure.get(1).put("person_id", "p1"); assertFailed(ids.validate(), "passenger_exposure");
		Fixture aggregate = fixture(); aggregate.summaries.get(2).put("raw_physical_exposure", "4");
		aggregate.summaries.get(2).put("physical_penalty", "3.2"); aggregate.summaries.get(2).put("applied_physical_penalty", "3.2");
		assertFailed(aggregate.validate(), "passenger_exposure");
	}

	private static void assertFailed(RunValidator.ValidationResult result, String gate) {
		assertFalse(result.valid()); assertEquals(false, result.checks().get(gate), result.issues().toString());
	}
	private Fixture fixture() throws Exception { return fixture(false, .8, ExperimentSpec.ExposureOutput.REPORTING_WINDOW); }
	private Fixture fixture(boolean control, double betaFreight, ExperimentSpec.ExposureOutput mode) throws Exception {
		return new Fixture(temp.resolve("run-" + sequence++), control, betaFreight, mode);
	}

	private static final class Fixture {
		final Path root;
		final ExperimentSpec spec;
		final List<Map<String, String>> summaries = new ArrayList<>(), orders = new ArrayList<>(), exposure = new ArrayList<>(), denials = new ArrayList<>(), fingerprints = new ArrayList<>();
		Fixture(Path root, boolean control, double betaFreight, ExperimentSpec.ExposureOutput mode) {
			this.root = root; this.spec = spec(control, betaFreight, mode);
			for (int iteration = 0; iteration <= 3; iteration++) {
				boolean active = iteration >= 1, freight = active && !control;
				var summary = row(IsmtRunArtifactWriter.SUMMARY_COLUMNS, iteration);
				put(summary, "passenger_count", 2, "scored_passenger_count", 2, "selected_passenger_trips", 2, "pt_share", .5,
						"selected_plan_passenger_score", -1, "completed_pt_trips", 1, "mean_pt_door_to_door_minutes", 5, "mean_pt_wait_minutes", 1,
						"raw_physical_exposure", 2, "physical_penalty", 1.6, "applied_physical_penalty", 1.6,
						"raw_freight_copresence_exposure", freight ? 1.5 : 0, "co_presence_penalty", freight ? 1.5 * betaFreight : 0,
						"applied_co_presence_penalty", freight ? 1.5 * betaFreight : 0, "freight_order_count", control ? 0 : 1,
						"completed_orders", freight ? 1 : 0, "service_window_completed_orders", freight ? 1 : 0, "eventual_completed_orders", freight ? 1 : 0,
						"mean_first_board_wait_minutes", freight ? Double.toString(10.0 / 60) : "", "mean_lateness_minutes", freight ? Double.toString(20.0 / 60) : "",
						"first_board_wait_sample_size", freight ? 1 : 0, "completed_order_lateness_sample_size", freight ? 1 : 0,
						"passenger_boardings", 2, "freight_boardings", freight ? 2 : 0, "freight_events", freight ? 6 : 0,
						"phase_active", active, "phase_activation_count", active ? 1 : 0, "phase_activation_iteration", active ? 1 : -1,
						"exposure_event_state_valid", true, "max_load_ratio", .8, "max_freight_share_of_capacity", freight ? .1 : 0,
						"passengers_with_raw_copresence", freight ? 2 : 0, "raw_copresence_incidence", freight ? 1 : 0);
				summaries.add(summary);
				if (!control) {
					var order = row(IsmtRunArtifactWriter.ORDER_COLUMNS, iteration);
					put(order, "order_id", "cargo", "release_time_seconds", 10, "number_of_units", 2, "units_boarded", freight ? 2 : 0,
							"units_delivered", freight ? 2 : 0, "first_unit_board_time_seconds", freight ? "20" : "", "last_unit_board_time_seconds", freight ? "30" : "",
							"first_accessible_departure_time_seconds", "", "schedule_wait_minutes", "", "additional_admission_delay_minutes", "",
							"first_board_wait_minutes", freight ? Double.toString(10.0 / 60) : "", "completion_time_seconds", freight ? "120" : "", "deadline_seconds", 100,
							"service_class", "standard", "completed", freight, "completed_in_service_window", freight, "completed_within_execution_horizon", freight,
							"on_time", false, "lateness_minutes", freight ? Double.toString(20.0 / 60) : "", "denial_reasons", "",
							"boarding_events", freight ? 2 : 0, "delivery_events", freight ? 2 : 0, "accessible_departure_status", IsmtRunArtifactWriter.UNAVAILABLE_DEPARTURE);
					orders.add(order);
				}
				if (iteration == 3 || (mode == ExperimentSpec.ExposureOutput.REPORTING_WINDOW && iteration >= 2)) {
					for (int person = 1; person <= 2; person++) {
						var row = row(IsmtRunArtifactWriter.EXPOSURE_COLUMNS, iteration);
						double physical = person == 1 ? 2 : 0, copresence = freight ? person * .5 : 0;
						put(row, "person_id", "p" + person, "raw_physical_exposure", physical, "raw_freight_copresence_exposure", copresence,
								"physical_penalty", .8 * physical, "co_presence_penalty", betaFreight * copresence, "applied_physical_penalty", .8 * physical,
								"applied_co_presence_penalty", betaFreight * copresence, "valued_total_penalty", .8 * physical + betaFreight * copresence,
								"total_added_penalty", .8 * physical + betaFreight * copresence, "unfinished_ride", false);
						exposure.add(row);
					}
				}
				fingerprints.add(new LinkedHashMap<>(Map.of("experiment_id", spec.experimentId(), "block_id", "B1", "arm", spec.arm().name(),
						"iteration", Integer.toString(iteration), "event_count", "10", "event_sha256", "a".repeat(64), "schema_version", PassengerScientificFingerprint.SCHEMA_VERSION)));
			}
		}
		void freightDenial(int iteration) {
			var row = row(IsmtRunArtifactWriter.DENIAL_COLUMNS, iteration);
			put(row, "denial_sequence", 1, "time_seconds", 15, "actor_id", "cargo", "actor_class", "FREIGHT", "order_id", "cargo",
					"vehicle_id", "v", "line_id", "l", "route_id", "r", "departure_id", "", "stop_id", "s", "denial_reason", "FREIGHT_SHARE_CAP",
					"passenger_occupancy", 1, "freight_occupancy", 1, "native_capacity", 10, "freight_share_cap", .1, "passenger_load_gate", .9);
			denials.add(row); put(orders.get(iteration), "denial_count", 1, "denial_reasons", "FREIGHT_SHARE_CAP=1");
			put(summaries.get(iteration), "freight_denied_boardings", 1, "denial_detail_events", 1);
		}
		RunValidator.ValidationResult validate() throws Exception { write(); return new IsmtRunValidator().validate(root, spec); }
		void write() throws Exception {
			Path output = root.resolve("matsim"); Files.createDirectories(output);
			Map<String, List<String>> columns = Map.of("iteration_summary.csv", IsmtRunArtifactWriter.SUMMARY_COLUMNS,
					"order_ledger.csv", IsmtRunArtifactWriter.ORDER_COLUMNS, "denial_ledger.csv", IsmtRunArtifactWriter.DENIAL_COLUMNS,
					"passenger_exposure.csv.gz", IsmtRunArtifactWriter.EXPOSURE_COLUMNS);
			Map<String, Object> definitions = new TreeMap<>();
			columns.forEach((file, names) -> definitions.put(file, names.stream().map(name -> IsmtRunArtifactWriter.fieldDefinition(file, name)).toList()));
			new ObjectMapper().writeValue(output.resolve("artifact_schema.json").toFile(), Map.of("schema_version", IsmtRunArtifactWriter.SCHEMA,
					"passenger_exposure_mode", spec.output().passengerExposure().name(), "files", columns, "field_definitions", definitions));
			csv(output.resolve("iteration_summary.csv"), IsmtRunArtifactWriter.SUMMARY_COLUMNS, summaries);
			csv(output.resolve("order_ledger.csv"), IsmtRunArtifactWriter.ORDER_COLUMNS, orders);
			csv(output.resolve("denial_ledger.csv"), IsmtRunArtifactWriter.DENIAL_COLUMNS, denials);
			csv(output.resolve("passenger_exposure.csv.gz"), IsmtRunArtifactWriter.EXPOSURE_COLUMNS, exposure);
			csv(output.resolve(PassengerScientificFingerprint.FILE_NAME), List.of("experiment_id", "block_id", "arm", "iteration", "event_count", "event_sha256", "schema_version"), fingerprints);
		}
		Map<String, String> row(List<String> columns, int iteration) {
			Map<String, String> row = new LinkedHashMap<>(); columns.forEach(name -> row.put(name, "0"));
			put(row, "schema_version", IsmtRunArtifactWriter.SCHEMA, "experiment_id", spec.experimentId(), "block_id", spec.blockId(), "arm", spec.arm().name(), "iteration", iteration);
			return row;
		}
	}
	private static void put(Map<String, String> row, Object... fields) { for (int i = 0; i < fields.length; i += 2) row.put(fields[i].toString(), fields[i + 1].toString()); }
	private static void csv(Path path, List<String> header, List<Map<String, String>> rows) throws Exception {
		try (BufferedWriter writer = path.toString().endsWith(".gz") ? new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8)) : Files.newBufferedWriter(path, StandardCharsets.UTF_8);
				CSVPrinter printer = new CSVPrinter(writer, CSVFormat.RFC4180.builder().setHeader(header.toArray(String[]::new)).build())) {
			for (Map<String, String> row : rows) printer.printRecord(header.stream().map(row::get).toList());
		}
	}
	private static ExperimentSpec spec(boolean control, double betaFreight, ExperimentSpec.ExposureOutput mode) {
		var ref = new ExperimentSpec.FileRef("fixture.xml", "0".repeat(64));
		return new ExperimentSpec(ExperimentSpec.SCHEMA, "validator-test", "B1", control ? ExperimentSpec.Arm.CONTROL : ExperimentSpec.Arm.MIXED,
				new ExperimentSpec.Seeds(1, 2, 3), new ExperimentSpec.Inputs(ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref),
				new ExperimentSpec.Protocol(0, 1, 2, 2, 2, 3, 2, 3, 86400, 129600),
				new ExperimentSpec.FreightDemand(control ? 0 : 1, 5, 0, 500, "freight_destination", "_cap_"),
				new ExperimentSpec.Policies(control ? ExperimentSpec.Access.NO_FREIGHT : ExperimentSpec.Access.SHARED_PASSENGER_SERVICE, "", List.of("pt", "freight_pt"), .1, .9, 1, 0, true, .6, .8, betaFreight, 1, 1, 1, 1),
				new ExperimentSpec.OutputOptions("ismt-output-v1", mode, false, 0, 0), "test");
	}
}
