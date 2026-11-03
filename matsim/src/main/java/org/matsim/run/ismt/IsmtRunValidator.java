package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Streaming, read-only consistency gates for one run. Source/build and matrix equality are separate gates. */
public final class IsmtRunValidator implements RunValidator {
	@Override
	public ValidationResult validate(Path runRoot, ExperimentSpec spec) {
		Audit audit = new Audit(runRoot.resolve("matsim"), spec);
		audit.check("artifact_schema", audit::schema);
		audit.check("iteration_summary", audit::summaries);
		audit.check("order_ledger", audit::orders);
		audit.check("denial_ledger", audit::denials);
		audit.check("passenger_exposure", audit::exposure);
		audit.check("passenger_event_fingerprint", audit::fingerprints);
		return new ValidationResult(audit.issues.isEmpty(), audit.checks, audit.issues, audit.finalIteration,
				audit.activationCount, audit.activationIteration, audit.preActivation, audit.rowCounts);
	}

	private static final class Audit {
		private final Path directory;
		private final ExperimentSpec spec;
		private final Map<String, Boolean> checks = new LinkedHashMap<>();
		private final List<String> issues = new ArrayList<>();
		private final Map<String, Long> rowCounts = new LinkedHashMap<>();
		private final Map<Integer, Map<String, String>> summaries = new LinkedHashMap<>();
		private final Map<Integer, PassengerScientificFingerprint.Fingerprint> preActivation = new LinkedHashMap<>();
		private final Map<Integer, Map<String, Map<String, Long>>> expectedDenials = new HashMap<>();
		private final Set<String> orderIds = new HashSet<>();
		private final Map<String, Long> orderUnits = new HashMap<>();
		private int finalIteration = -1;
		private int activationCount;
		private int activationIteration = -1;

		Audit(Path directory, ExperimentSpec spec) { this.directory = directory; this.spec = spec; }

		void check(String name, CheckedAction action) {
			try { action.run(); checks.put(name, true); }
			catch (Exception error) {
				checks.put(name, false);
				issues.add(name + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
			}
		}

		void schema() throws IOException {
			ObjectMapper mapper = new ObjectMapper();
			JsonNode node = mapper.readTree(directory.resolve("artifact_schema.json").toFile());
			require(IsmtRunArtifactWriter.SCHEMA.equals(node.path("schema_version").asText()), "Wrong artifact schema version");
			require(spec.output().passengerExposure().name().equals(node.path("passenger_exposure_mode").asText()), "Exposure output mode/spec mismatch");
			require(node.path("files").size() == 4 && node.path("field_definitions").size() == 4, "Unexpected artifact schema file set");
			for (var entry : Map.of("iteration_summary.csv", IsmtRunArtifactWriter.SUMMARY_COLUMNS,
					"order_ledger.csv", IsmtRunArtifactWriter.ORDER_COLUMNS, "denial_ledger.csv", IsmtRunArtifactWriter.DENIAL_COLUMNS,
					"passenger_exposure.csv.gz", IsmtRunArtifactWriter.EXPOSURE_COLUMNS).entrySet()) {
				List<String> actual = new ArrayList<>();
				node.path("files").path(entry.getKey()).forEach(value -> actual.add(value.asText()));
				require(entry.getValue().equals(actual), "Schema columns disagree with writer: " + entry.getKey());
				JsonNode definitions = node.path("field_definitions").path(entry.getKey());
				require(definitions.isArray() && definitions.size() == entry.getValue().size(), "Missing field definitions: " + entry.getKey());
				for (int index = 0; index < definitions.size(); index++) {
					JsonNode expected = mapper.valueToTree(IsmtRunArtifactWriter.fieldDefinition(entry.getKey(), entry.getValue().get(index)));
					require(expected.equals(definitions.get(index)), "Field type/unit/nullability/bounds differ from writer contract: " + entry.getKey() + ":" + entry.getValue().get(index));
				}
			}
		}

		void summaries() throws IOException {
			int expectedIteration = spec.protocol().firstIteration();
			Long passengers = null;
			boolean previousActive = false;
			int observedTransitions = 0;
			try (Rows rows = rows("iteration_summary.csv", IsmtRunArtifactWriter.SUMMARY_COLUMNS)) {
				for (CSVRecord record : rows) {
					Map<String, String> row = record.toMap();
					int iteration = context(row);
					require(iteration == expectedIteration++, "Duplicate, missing, or out-of-order summary iteration: " + iteration);
					finalIteration = iteration;
					long count = integer(row, "passenger_count");
					if (passengers == null) passengers = count;
					require(passengers == count, "Passenger universe changes between iterations");
					require(integer(row, "scored_passenger_count") <= count, "Scored passenger count exceeds population");
					for (String field : List.of("selected_passenger_trips", "completed_pt_trips", "passenger_denied_boardings",
							"freight_denied_boardings", "completed_orders", "service_window_completed_orders", "eventual_completed_orders",
							"on_time_orders", "first_board_wait_sample_size", "completed_order_lateness_sample_size", "passenger_boardings",
							"freight_boardings", "passenger_stuck_events", "freight_stuck_events", "freight_events", "denial_detail_events")) integer(row, field);
					require(integer(row, "freight_order_count") == spec.freightDemand().orderCount(), "Summary order count/spec mismatch");
					fraction(row, "pt_share"); fraction(row, "max_load_ratio"); fraction(row, "max_freight_share_of_capacity");
					require(number(row, "max_freight_share_of_capacity") <= number(row, "max_load_ratio"), "Freight share exceeds total load");
					optionalNumber(row, "selected_plan_passenger_score", false);
					for (String field : List.of("mean_pt_door_to_door_minutes", "mean_pt_wait_minutes", "mean_first_board_wait_minutes", "mean_lateness_minutes")) optionalNumber(row, field, true);
					for (String field : exposureFields()) nonnegative(row, field);
					close(number(row, "physical_penalty"), spec.policies().betaPhysical() * number(row, "raw_physical_exposure"), "Summary physical valuation");
					close(number(row, "co_presence_penalty"), spec.policies().betaFreight() * number(row, "raw_freight_copresence_exposure"), "Summary co-presence valuation");
					require(bool(row, "exposure_event_state_valid"), "Invalid observational exposure event state");
					require(integer(row, "unfinished_passenger_rides") == 0, "Unfinished passenger rides");
					require(integer(row, "unfinished_freight_units_onboard") == 0, "Freight units remain onboard at horizon");
					close(number(row, "applied_physical_penalty"), number(row, "physical_penalty"), "Closed-ride applied physical penalty");
					close(number(row, "applied_co_presence_penalty"), number(row, "co_presence_penalty"), "Closed-ride applied co-presence penalty");
					long incidence = integer(row, "passengers_with_raw_copresence");
					require(incidence <= count, "Co-presence incidence exceeds passenger count");
					if (count == 0) blank(row, "raw_copresence_incidence");
					else close(fraction(row, "raw_copresence_incidence"), (double) incidence / count, "Summary incidence");
					boolean active = bool(row, "phase_active");
					require(active == (iteration >= spec.protocol().activationIteration()), "Wrong treatment/sham phase at iteration " + iteration);
					if (active && !previousActive) observedTransitions++;
					previousActive = active;
					int counter = Math.toIntExact(integer(row, "phase_activation_count"));
					int activationAt = signedInteger(row, "phase_activation_iteration");
					require(counter == (active ? 1 : 0) && activationAt == (active ? spec.protocol().activationIteration() : -1), "Actual phase activation receipt mismatch");
					activationCount = counter; activationIteration = activationAt;
					if (!active || spec.freightDemand().orderCount() == 0) {
						require(integer(row, "freight_events") == 0 && integer(row, "freight_boardings") == 0
								&& integer(row, "freight_denied_boardings") == 0 && integer(row, "freight_stuck_events") == 0, "Pre-activation freight leakage");
						close(number(row, "raw_freight_copresence_exposure"), 0, "No-freight co-presence");
						close(number(row, "max_freight_share_of_capacity"), 0, "No-freight occupancy");
					}
					summaries.put(iteration, row);
				}
				rowCounts.put("iteration_summary.csv", rows.count);
			}
			require(expectedIteration == spec.protocol().finalIteration() + 1, "Incomplete summary coverage");
			require(observedTransitions == 1 && activationCount == 1, "Activation does not occur exactly once");
		}

		void orders() throws IOException {
			requireSummary();
			Map<String, OrderDefinition> definitions = new HashMap<>();
			try (Rows rows = rows("order_ledger.csv", IsmtRunArtifactWriter.ORDER_COLUMNS)) {
				Iterator<CSVRecord> iterator = rows.iterator();
				CSVRecord next = iterator.hasNext() ? iterator.next() : null;
				for (int iteration : summaries.keySet()) {
					Set<String> seen = new HashSet<>();
					long completed = 0, window = 0, eventual = 0, onTime = 0, waits = 0, late = 0, boardingEvents = 0;
					Sum waitSum = new Sum(), lateSum = new Sum();
					while (next != null && signedInteger(next.toMap(), "iteration") == iteration) {
						Map<String, String> row = next.toMap(); context(row);
						String id = required(row, "order_id");
						require(seen.add(id), "Duplicate order ID in iteration " + iteration + ": " + id);
						long units = integer(row, "number_of_units"), boarded = integer(row, "units_boarded"), delivered = integer(row, "units_delivered");
						require(units > 0 && units <= Integer.MAX_VALUE && delivered <= boarded && boarded <= units, "Order-unit conservation failed: " + id);
						OrderDefinition definition = new OrderDefinition(units, nonnegative(row, "release_time_seconds"),
								nonnegative(row, "deadline_seconds"), required(row, "service_class"));
					if (iteration == spec.protocol().firstIteration()) {
							definitions.put(id, definition); orderIds.add(id); orderUnits.put(id, units);
						} else require(definition.equals(definitions.get(id)), "Order metadata/identity changed across iterations: " + id);
						long boardEvents = integer(row, "boarding_events"), deliveryEvents = integer(row, "delivery_events");
						require(boardEvents >= boarded && (boardEvents == 0) == (boarded == 0) && deliveryEvents == delivered, "Event/unit count mismatch: " + id);
						boardingEvents += boardEvents;
						boolean isComplete = bool(row, "completed");
						require(isComplete == (delivered == units), "Completed flag disagrees with units: " + id);
						double first = optionalNumber(row, "first_unit_board_time_seconds", true);
						double last = optionalNumber(row, "last_unit_board_time_seconds", true);
						double wait = optionalNumber(row, "first_board_wait_minutes", true);
						if (boarded == 0) {
							require(Double.isNaN(first) && Double.isNaN(last) && Double.isNaN(wait), "Unboarded order has fabricated boarding times");
						} else {
							require(Double.isFinite(first) && Double.isFinite(last) && first >= definition.release && last >= first, "Invalid first/last unit boarding times");
							if (boarded == 1) require(first == last, "One boarded unit has different first/last first-boarding times");
							close(wait, (first - definition.release) / 60, "First-board wait"); waits++; waitSum.add(wait);
						}
						double completion = optionalNumber(row, "completion_time_seconds", true);
						double lateness = optionalNumber(row, "lateness_minutes", true);
						boolean inWindow = bool(row, "completed_in_service_window"), inHorizon = bool(row, "completed_within_execution_horizon"), timely = bool(row, "on_time");
						if (isComplete) {
							require(Double.isFinite(completion) && completion >= last, "Invalid order completion time");
							close(lateness, Math.max(0, completion - definition.deadline) / 60, "Order lateness");
							require(inWindow == (completion <= spec.freightDemand().serviceWindowEndSeconds())
									&& inHorizon == (completion <= spec.protocol().executionHorizonSeconds()) && timely == (completion <= definition.deadline), "Completion cutoff flags disagree");
							completed++; late++; lateSum.add(lateness);
						} else require(Double.isNaN(completion) && Double.isNaN(lateness) && !inWindow && !inHorizon && !timely, "Incomplete order has completed outcome");
						if (inWindow) window++; if (inHorizon) eventual++; if (timely) onTime++;
						if (iteration < spec.protocol().activationIteration()) require(boarded == 0 && delivered == 0 && boardEvents == 0, "Pre-activation order execution");
						require(IsmtRunArtifactWriter.UNAVAILABLE_DEPARTURE.equals(required(row, "accessible_departure_status")), "Unreviewed accessible-departure derivation");
						blank(row, "first_accessible_departure_time_seconds"); blank(row, "schedule_wait_minutes"); blank(row, "additional_admission_delay_minutes");
						Map<String, Long> reasons = reasons(row.get("denial_reasons"));
						require(reasons.values().stream().mapToLong(Long::longValue).sum() == integer(row, "denial_count"), "Order denial reasons/count mismatch");
						if (!reasons.isEmpty()) expectedDenials.computeIfAbsent(iteration, ignored -> new HashMap<>()).put(id, reasons);
						next = iterator.hasNext() ? iterator.next() : null;
					}
					require(seen.size() == spec.freightDemand().orderCount() && seen.equals(orderIds), "Order ledger iteration coverage/IDs mismatch: " + iteration);
					if (iteration == spec.protocol().firstIteration()) for (String orderId : orderIds) require(generatedUnitParent(orderId) == null, "Order ID collides with another order's generated unit: " + orderId);
					Map<String, String> summary = summaries.get(iteration);
					equalCount(summary, "completed_orders", completed); equalCount(summary, "service_window_completed_orders", window);
					equalCount(summary, "eventual_completed_orders", eventual); equalCount(summary, "on_time_orders", onTime);
					equalCount(summary, "first_board_wait_sample_size", waits); equalCount(summary, "completed_order_lateness_sample_size", late);
					equalCount(summary, "freight_boardings", boardingEvents);
					mean(summary, "mean_first_board_wait_minutes", waitSum.value, waits); mean(summary, "mean_lateness_minutes", lateSum.value, late);
				}
				require(next == null, "Unexpected extra/out-of-order order-ledger rows"); rowCounts.put("order_ledger.csv", rows.count);
			}
		}

		void denials() throws IOException {
			requireSummary(); require(Boolean.TRUE.equals(checks.get("order_ledger")), "Order-ledger validation required for denial reconciliation");
			try (Rows rows = rows("denial_ledger.csv", IsmtRunArtifactWriter.DENIAL_COLUMNS)) {
				Iterator<CSVRecord> iterator = rows.iterator(); CSVRecord next = iterator.hasNext() ? iterator.next() : null;
				for (int iteration : summaries.keySet()) {
					long count = 0, passenger = 0, freight = 0; double lastTime = -1;
					Map<String, Map<String, Long>> observedReasons = new HashMap<>();
					while (next != null && signedInteger(next.toMap(), "iteration") == iteration) {
						Map<String, String> row = next.toMap(); context(row);
						require(integer(row, "denial_sequence") == ++count, "Denial sequence is not unique/consecutive");
						double time = nonnegative(row, "time_seconds"); require(time >= lastTime, "Reverse-ordered denial time"); lastTime = time;
						String actor = required(row, "actor_id"), actorClass = required(row, "actor_class"), order = row.get("order_id");
						String reason = required(row, "denial_reason"); TransitBoardingPolicy.DenialReason.valueOf(reason);
						if (actorClass.equals("PASSENGER")) { passenger++; require(order.isEmpty() && unitOrder(actor) == null, "Passenger denial assigned to freight unit"); }
						else if (actorClass.equals("FREIGHT")) {
							freight++; require(order.equals(unitOrder(actor)), "Freight denial unit/order mismatch");
							observedReasons.computeIfAbsent(order, ignored -> new HashMap<>()).merge(reason, 1L, Long::sum);
						} else throw new IllegalArgumentException("Invalid denial actor class: " + actorClass);
						required(row, "vehicle_id"); required(row, "stop_id");
						long p = integer(row, "passenger_occupancy"), f = integer(row, "freight_occupancy"), capacity = integer(row, "native_capacity");
						require(capacity <= Integer.MAX_VALUE && p <= capacity && f <= capacity && p + f <= capacity, "Denial observation exceeds native capacity");
						if (reason.equals("NATIVE_CAPACITY")) require(p + f == capacity, "Native-capacity denial with a free place");
						double cap = optionalNumber(row, "freight_share_cap", true), gate = optionalNumber(row, "passenger_load_gate", true);
						require(Double.isNaN(cap) || cap <= 1, "Invalid freight-share threshold"); require(Double.isNaN(gate) || gate <= 1, "Invalid passenger gate");
						close(cap, spec.policies().freightShareCap(), "Denial freight-share parameter/spec");
						close(gate, spec.policies().passengerLoadGate(), "Denial passenger-load parameter/spec");
						if (reason.equals("FREIGHT_SHARE_CAP")) require(actorClass.equals("FREIGHT") && Double.isFinite(cap) && f + 1 > Math.floor(cap * capacity), "Freight-share denial disagrees with snapshot");
						if (reason.equals("PASSENGER_LOAD_GATE")) require(actorClass.equals("FREIGHT") && capacity > 0 && Double.isFinite(gate) && (double) p / capacity > gate, "Passenger-load denial disagrees with snapshot");
						next = iterator.hasNext() ? iterator.next() : null;
					}
					Map<String, String> summary = summaries.get(iteration);
					equalCount(summary, "denial_detail_events", count); equalCount(summary, "passenger_denied_boardings", passenger); equalCount(summary, "freight_denied_boardings", freight);
					require(observedReasons.equals(expectedDenials.getOrDefault(iteration, Map.of())), "Order/denial-ledger reasons disagree at " + iteration);
				}
				require(next == null, "Unexpected extra/out-of-order denial rows"); rowCounts.put("denial_ledger.csv", rows.count);
			}
		}

		void exposure() throws IOException {
			requireSummary();
			try (Rows rows = rows("passenger_exposure.csv.gz", IsmtRunArtifactWriter.EXPOSURE_COLUMNS)) {
				Iterator<CSVRecord> iterator = rows.iterator(); CSVRecord next = iterator.hasNext() ? iterator.next() : null;
				for (int iteration : summaries.keySet()) {
					if (!writesExposure(iteration)) continue;
					Map<String, Sum> sums = new HashMap<>(); for (String field : exposureFields()) sums.put(field, new Sum());
					long count = 0, incidence = 0; String previousId = null;
					while (next != null && signedInteger(next.toMap(), "iteration") == iteration) {
						Map<String, String> row = next.toMap(); context(row);
						String id = required(row, "person_id"); require(previousId == null || previousId.compareTo(id) < 0, "Exposure person IDs not strictly increasing (duplicate or unsorted)");
						require(unitOrder(id) == null, "Freight unit appears in passenger exposure"); previousId = id; count++;
						for (String field : exposureFields()) sums.get(field).add(nonnegative(row, field));
						if (number(row, "raw_freight_copresence_exposure") > 0) incidence++;
						close(number(row, "physical_penalty"), spec.policies().betaPhysical() * number(row, "raw_physical_exposure"), "Person physical valuation");
						close(number(row, "co_presence_penalty"), spec.policies().betaFreight() * number(row, "raw_freight_copresence_exposure"), "Person co-presence valuation");
						close(nonnegative(row, "valued_total_penalty"), number(row, "physical_penalty") + number(row, "co_presence_penalty"), "Valued penalty channels");
						close(nonnegative(row, "total_added_penalty"), number(row, "applied_physical_penalty") + number(row, "applied_co_presence_penalty"), "Applied penalty channels");
						require(!bool(row, "unfinished_ride"), "Unfinished passenger exposure ride");
						close(number(row, "applied_physical_penalty"), number(row, "physical_penalty"), "Person applied physical valuation");
						close(number(row, "applied_co_presence_penalty"), number(row, "co_presence_penalty"), "Person applied co-presence valuation");
						next = iterator.hasNext() ? iterator.next() : null;
					}
					Map<String, String> summary = summaries.get(iteration); equalCount(summary, "passenger_count", count); equalCount(summary, "passengers_with_raw_copresence", incidence);
					for (String field : exposureFields()) close(number(summary, field), sums.get(field).value, "Exposure/summary aggregate " + field);
				}
				require(next == null, "Unexpected exposure iteration or extra rows"); rowCounts.put("passenger_exposure.csv.gz", rows.count);
			}
		}

		void fingerprints() throws IOException {
			List<String> header = List.of("experiment_id", "block_id", "arm", "iteration", "event_count", "event_sha256", "schema_version");
			int expected = spec.protocol().firstIteration();
			try (Rows rows = rows(PassengerScientificFingerprint.FILE_NAME, header)) {
				for (CSVRecord record : rows) {
					Map<String, String> row = record.toMap();
					identity(row); int iteration = signedInteger(row, "iteration");
					require(iteration == expected++, "Fingerprint iteration coverage mismatch");
					require(PassengerScientificFingerprint.SCHEMA_VERSION.equals(required(row, "schema_version")), "Wrong fingerprint schema");
					String hash = required(row, "event_sha256"); require(hash.matches("[a-f0-9]{64}"), "Malformed passenger fingerprint SHA-256");
					long count = integer(row, "event_count");
					if (iteration < spec.protocol().activationIteration()) preActivation.put(iteration, new PassengerScientificFingerprint.Fingerprint(iteration, count, hash, PassengerScientificFingerprint.SCHEMA_VERSION));
				}
				rowCounts.put(PassengerScientificFingerprint.FILE_NAME, rows.count);
			}
			require(expected == spec.protocol().finalIteration() + 1, "Incomplete passenger fingerprint coverage");
		}

		private int context(Map<String, String> row) {
			identity(row); require(IsmtRunArtifactWriter.SCHEMA.equals(required(row, "schema_version")), "Wrong row schema");
			int iteration = signedInteger(row, "iteration");
			require(iteration >= spec.protocol().firstIteration() && iteration <= spec.protocol().finalIteration(), "Row iteration outside spec"); return iteration;
		}
		private void identity(Map<String, String> row) {
			require(spec.experimentId().equals(required(row, "experiment_id")) && spec.blockId().equals(required(row, "block_id"))
					&& spec.arm().name().equals(required(row, "arm")), "Artifact experiment/block/arm mismatch");
		}
		private void requireSummary() { require(Boolean.TRUE.equals(checks.get("iteration_summary")), "Validated complete iteration summary is required"); }
		private boolean writesExposure(int iteration) { return iteration == spec.protocol().finalIteration()
				|| (spec.output().passengerExposure() == ExperimentSpec.ExposureOutput.REPORTING_WINDOW && iteration >= spec.protocol().reportFirstIteration() && iteration <= spec.protocol().reportLastIteration()); }
		private Rows rows(String name, List<String> columns) throws IOException { return new Rows(directory.resolve(name), columns); }
		private String unitOrder(String unitId) { return orderIds.contains(unitId) ? unitId : generatedUnitParent(unitId); }
		private String generatedUnitParent(String unitId) {
			String token = spec.freightDemand().shadowToken(); int split = unitId.lastIndexOf(token);
			if (split < 0) return null;
			String parent = unitId.substring(0, split), suffix = unitId.substring(split + token.length());
			Long units = orderUnits.get(parent);
			if (units == null || !suffix.matches("[0-9]{2,10}")) return null;
			long ordinal = Long.parseLong(suffix);
			return ordinal >= 2 && ordinal <= units && suffix.equals(String.format(Locale.ROOT, "%02d", ordinal)) ? parent : null;
		}
	}

	private static final class Rows implements Iterable<CSVRecord>, AutoCloseable {
		private final CSVParser parser; private final List<Map<String, Object>> definitions; private long count;
		Rows(Path path, List<String> columns) throws IOException {
			definitions = columns.contains("phase_active") || columns.contains("number_of_units") || columns.contains("person_id") || columns.contains("denial_sequence")
					? columns.stream().map(name -> IsmtRunArtifactWriter.fieldDefinition(path.getFileName().toString(), name)).toList() : List.of();
			Reader reader = path.toString().endsWith(".gz") ? new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8) : Files.newBufferedReader(path, StandardCharsets.UTF_8);
			try {
				parser = new CSVParser(reader, CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(false).build());
				require(parser.getHeaderNames().equals(columns), "Wrong or duplicate CSV header: " + path.getFileName());
			} catch (RuntimeException | IOException error) { reader.close(); throw error; }
		}
		public Iterator<CSVRecord> iterator() {
			Iterator<CSVRecord> delegate = parser.iterator();
			return new Iterator<>() {
				public boolean hasNext() { return delegate.hasNext(); }
				public CSVRecord next() {
					CSVRecord row = delegate.next(); require(row.isConsistent(), "Malformed CSV row " + row.getRecordNumber());
					for (var field : definitions) {
						String name = field.get("name").toString(), type = field.get("type").toString(), value = row.get(name);
						if (type.equals("string")) continue;
						if (value.isEmpty() && Boolean.TRUE.equals(field.get("nullable"))) continue;
						if (type.equals("boolean")) require(value.equals("true") || value.equals("false"), "Invalid boolean " + name);
						else {
							if (type.equals("integer")) { require(value.matches("-?[0-9]+"), "Invalid integer " + name); Long.parseLong(value); }
							double number = Double.parseDouble(value); require(Double.isFinite(number), "Nonfinite field " + name);
							if (field.containsKey("minimum")) require(number >= ((Number) field.get("minimum")).doubleValue(), "Below minimum: " + name);
							if (field.containsKey("maximum")) require(number <= ((Number) field.get("maximum")).doubleValue(), "Above maximum: " + name);
						}
					}
					count++; return row;
				}
			};
		}
		public void close() throws IOException { parser.close(); }
	}

	private static Map<String, Long> reasons(String encoded) {
		Map<String, Long> result = new TreeMap<>();
		if (encoded == null || encoded.isEmpty()) return result;
		for (String token : encoded.split("\\|", -1)) {
			String[] pair = token.split("=", -1); require(pair.length == 2, "Malformed denial reason counts");
			TransitBoardingPolicy.DenialReason.valueOf(pair[0]); long count = Long.parseLong(pair[1]);
			require(count > 0 && result.putIfAbsent(pair[0], count) == null, "Duplicate/nonpositive denial reason count");
		}
		return result;
	}
	private static List<String> exposureFields() { return List.of("raw_physical_exposure", "raw_freight_copresence_exposure", "physical_penalty", "co_presence_penalty", "applied_physical_penalty", "applied_co_presence_penalty"); }
	private static void mean(Map<String, String> row, String field, double sum, long count) { if (count == 0) blank(row, field); else close(number(row, field), sum / count, "Order/summary mean " + field); }
	private static void equalCount(Map<String, String> row, String field, long expected) { require(integer(row, field) == expected, "Count mismatch: " + field); }
	private static String required(Map<String, String> row, String field) { String value = row.get(field); require(value != null && !value.isBlank(), "Missing field " + field); return value; }
	private static void blank(Map<String, String> row, String field) { require(row.containsKey(field) && row.get(field).isEmpty(), "Expected unavailable/empty field: " + field); }
	private static long integer(Map<String, String> row, String field) { String value = required(row, field); require(value.matches("[0-9]+"), "Invalid nonnegative integer: " + field); return Long.parseLong(value); }
	private static int signedInteger(Map<String, String> row, String field) { String value = required(row, field); require(value.matches("-?[0-9]+"), "Invalid integer: " + field); return Integer.parseInt(value); }
	private static double number(Map<String, String> row, String field) { double value = Double.parseDouble(required(row, field)); require(Double.isFinite(value), "Nonfinite numeric field: " + field); return value; }
	private static double nonnegative(Map<String, String> row, String field) { double value = number(row, field); require(value >= 0, "Negative field: " + field); return value; }
	private static double fraction(Map<String, String> row, String field) { double value = nonnegative(row, field); require(value <= 1, "Fraction/native load exceeds one: " + field); return value; }
	private static double optionalNumber(Map<String, String> row, String field, boolean nonnegative) { require(row.containsKey(field), "Missing column " + field); return row.get(field).isEmpty() ? Double.NaN : nonnegative ? nonnegative(row, field) : number(row, field); }
	private static boolean bool(Map<String, String> row, String field) { String value = required(row, field); require(value.equals("true") || value.equals("false"), "Invalid boolean: " + field); return value.equals("true"); }
	private static void close(double actual, double expected, String what) {
		require(Double.isFinite(actual) && Double.isFinite(expected), what + " has unavailable/nonfinite value");
		if (expected == 0) require(actual == 0, what + " must be exactly zero");
		else require(Math.abs(actual - expected) <= 1e-8 + 1e-10 * Math.abs(expected), what + " differs: " + actual + " vs " + expected);
	}
	private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
	private record OrderDefinition(long units, double release, double deadline, String serviceClass) { }
	private static final class Sum { double value, correction; void add(double number) { double adjusted = number - correction, total = value + adjusted; correction = (total - value) - adjusted; value = total; require(Double.isFinite(value), "Aggregate overflow"); } }
	@FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
