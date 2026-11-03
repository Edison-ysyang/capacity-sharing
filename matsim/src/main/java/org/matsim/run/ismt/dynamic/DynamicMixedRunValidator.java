package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.IsmtRunArtifactWriter;
import org.matsim.run.ismt.PassengerPlanFingerprint;
import org.matsim.run.ismt.PassengerScientificFingerprint;
import org.matsim.run.ismt.ScientificRunContext;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Read-only v2 scientific artifact consistency, not historical reuse, dispatch,
 * full score reconstruction, input population identity or physical/native safety.
 * Only DYNAMIC_MIXED is accepted; no legacy ExperimentSpec is manufactured.
 * The CSV equations retain the established writer's accounts, including incomplete
 * freight outcomes and unavailable accessible departures. Zero denials/exposure
 * are valid observations, never evidence that an unexercised mechanism was tested.
 * Phase rows prove their recorded treatment transition, not actual StrategyManager
 * weights or policy execution. Those remain separate Controller/dispatch evidence.
 * Fingerprint envelopes/coverage are checked, not rehashed from raw events/plans.
 * No receipt or simulation output is modified, regardless of this report's result.
 */
public final class DynamicMixedRunValidator {
    public static final String REPORT_SCHEMA = "ismt-dynamic-scientific-validation-v1";
    public static final String SCOPE = "V2_SCIENTIFIC_ARTIFACT_CONSISTENCY";
    static final List<String> EVENT_HEADER = List.of("experiment_id", "block_id", "arm", "iteration",
            "event_count", "event_sha256", "schema_version", "design_family");
    static final List<String> PLAN_HEADER = List.of("schema_version", "experiment_id", "block_id", "arm",
            "iteration", "passenger_count", "plan_sha256", "design_family");
    static final List<String> SCORE_HEADER = List.of("iteration", "passenger_persons", "scored_persons", "avg_selected_score");
    static final List<String> MODE_HEADER = List.of("iteration", "passenger_trips", "bike", "bus", "car", "pt", "ride", "walk");
    static final List<String> SERVICE_HEADER = List.of("iteration", "completed_passenger_trips", "completed_pt_trips",
            "door_to_door_mean_min", "door_to_door_p50_min", "door_to_door_p95_min",
            "pt_door_to_door_mean_min", "pt_door_to_door_p50_min", "pt_door_to_door_p95_min",
            "pt_wait_mean_min", "pt_wait_p50_min", "pt_wait_p95_min",
            "pt_in_vehicle_mean_min", "pt_in_vehicle_p50_min", "pt_in_vehicle_p95_min");
    private static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final List<String> UNVERIFIED = List.of("historical-M-reuse", "dispatch-and-actual-headways",
            "actual-innovation-weights", "native-capacity-mechanism-exercise", "full-input-population-and-source-build-identity",
            "score-reconstruction-from-events", "fingerprint-recomputation-from-events-and-plan-memory",
            "real-railway-safety", "formal-authorization");

    public record FileEvidence(String workspaceRelativePath, long bytes, String sha256) { }
    public record Report(String schemaVersion, String scope, boolean valid, Map<String, Boolean> checks,
            List<String> issues, int finalIteration, int activationCount, int activationIteration,
            Map<Integer, PassengerScientificFingerprint.Fingerprint> preActivationFingerprint,
            Map<Integer, PassengerPlanFingerprint.Fingerprint> preActivationPlanFingerprint,
            Map<String, Long> artifactRowCounts, Map<String, FileEvidence> checkedFiles,
            List<String> unverifiedBoundaries) {
        public Report {
            checks = immutable(checks); issues = List.copyOf(issues);
            preActivationFingerprint = immutable(preActivationFingerprint);
            preActivationPlanFingerprint = immutable(preActivationPlanFingerprint);
            artifactRowCounts = immutable(artifactRowCounts); checkedFiles = immutable(checkedFiles);
            unverifiedBoundaries = List.copyOf(unverifiedBoundaries);
            require(REPORT_SCHEMA.equals(schemaVersion) && SCOPE.equals(scope), "Wrong validation report identity");
            require(!valid || issues.isEmpty() && !checks.isEmpty() && checks.values().stream().allMatch(Boolean::booleanValue),
                    "Valid scoped report contains a failed check");
        }
    }

    public Report validate(Path workspace, Path runRoot, DynamicMixedExperimentSpec spec) {
        Audit audit = new Audit(workspace, runRoot, Objects.requireNonNull(spec));
        audit.check("path_and_v2_scope", audit::paths);
        audit.check("execution_order_contract", audit::contracts);
        audit.check("artifact_schema", audit::schema);
        audit.check("iteration_summary", audit::summaries);
        audit.check("order_ledger", audit::orders);
        audit.check("denial_ledger", audit::denials);
        audit.check("passenger_exposure", audit::exposure);
        audit.check("passenger_statistics", audit::statistics);
        audit.check("passenger_event_fingerprint", audit::fingerprints);
        audit.check("passenger_plan_fingerprint", audit::planFingerprints);
        audit.check("checked_files_unchanged", audit::unchanged);
        return new Report(REPORT_SCHEMA, SCOPE, audit.issues.isEmpty(), audit.checks, audit.issues,
                audit.finalIteration, audit.activationCount, audit.activationIteration, audit.preActivation,
                audit.preActivationPlans, audit.rowCounts, audit.fileEvidence, UNVERIFIED);
    }

	private static final class Audit {
		private final Path requestedWorkspace, requestedRunRoot;
        private Path workspace, directory;
        private final Map<String, FileEvidence> fileEvidence = new LinkedHashMap<>();
        private final Map<String, BasicFileAttributes> fileAttributes = new LinkedHashMap<>();
        private final Map<String, OrderDefinition> expectedOrders = new LinkedHashMap<>();
        private final Map<String, String> unitParents = new HashMap<>();
        private final Map<Integer, PassengerPlanFingerprint.Fingerprint> preActivationPlans = new LinkedHashMap<>();
		private final DynamicMixedExperimentSpec spec;
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

		Audit(Path workspace, Path runRoot, DynamicMixedExperimentSpec spec) {
            this.requestedWorkspace = Objects.requireNonNull(workspace);
            this.requestedRunRoot = Objects.requireNonNull(runRoot); this.spec = spec;
        }

		void check(String name, CheckedAction action) {
			try { action.run(); checks.put(name, true); }
			catch (Exception error) {
				checks.put(name, false);
				issues.add(name + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
			}
		}

        void paths() throws IOException {
            require(spec.design().arm() == DynamicMixedDesign.Arm.DYNAMIC_MIXED, "Only new DYNAMIC_MIXED scientific outputs are supported");
            workspace = requestedWorkspace.toRealPath();
            Path run = (requestedRunRoot.isAbsolute() ? requestedRunRoot : workspace.resolve(requestedRunRoot)).toRealPath();
            require(!run.equals(workspace) && run.startsWith(workspace), "Run directory escapes workspace");
            Path candidate = run.resolve("matsim");
            require(!Files.isSymbolicLink(candidate), "Scientific output directory is a symlink");
            directory = candidate.toRealPath();
            require(directory.startsWith(run) && Files.isDirectory(directory), "Scientific output directory escapes run");
        }

        private Path track(String role, Path path) throws IOException {
            require(Boolean.TRUE.equals(checks.get("path_and_v2_scope")), "Validated output scope required");
            require(!Files.isSymbolicLink(path), "Checked file is a symlink: " + role);
            Path real = path.toRealPath(); require(real.startsWith(workspace), "Checked file escapes workspace");
            BasicFileAttributes before = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            require(before.isRegularFile(), "Not a regular file: " + role);
            String sha = ExperimentConfiguration.sha256(real);
            require(sameAttributes(before, Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)), "File changed while hashing: " + role);
            FileEvidence previous = fileEvidence.putIfAbsent(role, new FileEvidence(workspace.relativize(real).toString(), before.size(), sha));
            if (previous != null) require(previous.equals(new FileEvidence(workspace.relativize(real).toString(), before.size(), sha)), "File changed between checks: " + role);
            fileAttributes.putIfAbsent(role, before); return real;
        }

        private Path file(String name) throws IOException {
            require(directory != null && !name.contains("/") && !name.contains("\\"), "Invalid scientific artifact path");
            return track(name, directory.resolve(name));
        }

        void contracts() throws IOException {
            require(Boolean.TRUE.equals(checks.get("path_and_v2_scope")), "Validated scope required");
            Path declared = spec.inputs().executionOrders().resolve(workspace);
            Path path = track("input.executionOrders", declared);
            require(fileEvidence.get("input.executionOrders").sha256().equals(spec.inputs().executionOrders().sha256()), "Execution orders do not match declared hash");
            var demand = spec.freightDemand();
            // Only these three fields affect the existing order reader/unitizer. No operational settings are installed.
            var settings = CoTransportSettings.builder(path).freightEquivalentPassengersPerM3(demand.equivalentPassengersPerM3())
                    .freightShadowToken(demand.shadowToken()).build();
            var registry = new FreightOrderRegistry(settings);
            for (var order : registry.orders()) {
                require(expectedOrders.putIfAbsent(order.orderId(), new OrderDefinition(order.capacityUnits(), order.releaseTime(),
                        order.deadlineTime(), order.serviceClass())) == null, "Repeated expected order");
                orderIds.add(order.orderId()); orderUnits.put(order.orderId(), (long) order.capacityUnits());
                for (var unit : registry.unitIdsForOrder(order.orderId()))
                    require(unitParents.putIfAbsent(unit.toString(), order.orderId()) == null, "Duplicate expected unit");
            }
            require(expectedOrders.size() == demand.orderCount(), "Execution order table/spec count mismatch");
        }

        void unchanged() throws IOException {
            require(Boolean.TRUE.equals(checks.get("path_and_v2_scope")) && !fileEvidence.isEmpty(), "No valid checked file scope");
            for (var entry : fileEvidence.entrySet()) {
                Path path = workspace.resolve(entry.getValue().workspaceRelativePath());
                require(!Files.isSymbolicLink(path) && path.toRealPath().equals(path), "Checked file was retargeted");
                require(sameAttributes(fileAttributes.get(entry.getKey()), Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))
                        && entry.getValue().sha256().equals(ExperimentConfiguration.sha256(path)), "Checked file changed: " + entry.getKey());
            }
        }

		void schema() throws IOException {
			ObjectMapper mapper = JSON;
            JsonNode node = mapper.readTree(file("artifact_schema.json").toFile());
            require(node != null && node.isObject(), "Missing schema object");
            for (var entry : spec.scientificContext().additionalSchemaMetadata().entrySet())
                require(mapper.valueToTree(entry.getValue()).equals(node.get(entry.getKey())), "Wrong v2 metadata: " + entry.getKey());
            require(node.has("detailed_vehicle_observations") && node.get("detailed_vehicle_observations").isBoolean()
                    && node.get("detailed_vehicle_observations").booleanValue() == spec.output().vehicleIntervals(), "Vehicle observation mode mismatch");
			require(ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA.equals(node.path("schema_version").asText()), "Wrong artifact schema version");
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
					JsonNode expected = mapper.valueToTree(fieldDefinition(entry.getKey(), entry.getValue().get(index)));
					require(expected.equals(definitions.get(index)), "Field type/unit/nullability/bounds differ from writer contract: " + entry.getKey() + ":" + entry.getValue().get(index));
				}
			}
		}

		void summaries() throws IOException {
			long expectedIteration = spec.design().protocol().firstIteration();
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
					if (integer(row, "scored_passenger_count") == 0) blank(row, "selected_plan_passenger_score");
					else number(row, "selected_plan_passenger_score");
					for (String field : List.of("mean_pt_door_to_door_minutes", "mean_pt_wait_minutes", "mean_first_board_wait_minutes", "mean_lateness_minutes")) optionalNumber(row, field, true);
					for (String field : List.of("mean_pt_door_to_door_minutes", "mean_pt_wait_minutes")) {
						if (integer(row, "completed_pt_trips") == 0) blank(row, field); else nonnegative(row, field);
					}
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
					require(active == (iteration >= spec.design().protocol().activationIteration()), "Wrong treatment/sham phase at iteration " + iteration);
					if (active && !previousActive) observedTransitions++;
					previousActive = active;
					int counter = Math.toIntExact(integer(row, "phase_activation_count"));
					int activationAt = signedInteger(row, "phase_activation_iteration");
					require(counter == (active ? 1 : 0) && activationAt == (active ? spec.design().protocol().activationIteration() : -1), "Actual phase activation receipt mismatch");
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
			require(expectedIteration == (long) spec.design().protocol().finalIteration() + 1, "Incomplete summary coverage");
			require(observedTransitions == 1 && activationCount == 1, "Activation does not occur exactly once");
		}

		void orders() throws IOException {
			requireSummary();
            require(Boolean.TRUE.equals(checks.get("execution_order_contract")), "Verified execution order contract required");
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
					require(definition.equals(expectedOrders.get(id)), "Order metadata/identity differs from hash-bound execution contract: " + id);
						long boardEvents = integer(row, "boarding_events"), deliveryEvents = integer(row, "delivery_events");
						require(boardEvents >= boarded && (boardEvents == 0) == (boarded == 0) && deliveryEvents == delivered, "Event/unit count mismatch: " + id);
						boardingEvents = Math.addExact(boardingEvents, boardEvents);
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
									&& inHorizon == (completion <= spec.runtime().executionHorizonSeconds()) && timely == (completion <= definition.deadline), "Completion cutoff flags disagree");
							completed++; late++; lateSum.add(lateness);
						} else require(Double.isNaN(completion) && Double.isNaN(lateness) && !inWindow && !inHorizon && !timely, "Incomplete order has completed outcome");
						if (inWindow) window++; if (inHorizon) eventual++; if (timely) onTime++;
						if (iteration < spec.design().protocol().activationIteration()) require(boarded == 0 && delivered == 0 && boardEvents == 0, "Pre-activation order execution");
						require(IsmtRunArtifactWriter.UNAVAILABLE_DEPARTURE.equals(required(row, "accessible_departure_status")), "Unreviewed accessible-departure derivation");
						blank(row, "first_accessible_departure_time_seconds"); blank(row, "schedule_wait_minutes"); blank(row, "additional_admission_delay_minutes");
						Map<String, Long> reasons = reasons(row.get("denial_reasons"));
						require(reasons.values().stream().reduce(0L, Math::addExact) == integer(row, "denial_count"), "Order denial reasons/count mismatch");
						if (!reasons.isEmpty()) expectedDenials.computeIfAbsent(iteration, ignored -> new HashMap<>()).put(id, reasons);
						next = iterator.hasNext() ? iterator.next() : null;
					}
					require(seen.size() == spec.freightDemand().orderCount() && seen.equals(orderIds), "Order ledger iteration coverage/IDs mismatch: " + iteration);
					if (iteration == spec.design().protocol().firstIteration()) for (String orderId : orderIds) require(generatedUnitParent(orderId) == null, "Order ID collides with another order's generated unit: " + orderId);
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
            require(Boolean.TRUE.equals(checks.get("execution_order_contract")), "Expected unit classification is required");
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
			requireSummary();
			List<String> header = EVENT_HEADER;
			long expected = spec.design().protocol().firstIteration();
			try (Rows rows = rows(PassengerScientificFingerprint.FILE_NAME, header)) {
				for (CSVRecord record : rows) {
					Map<String, String> row = record.toMap();
					identity(row); require(DynamicMixedDesign.SCHEMA_ID.equals(required(row, "design_family")), "Wrong event fingerprint design family"); int iteration = signedInteger(row, "iteration");
					require(iteration == expected++, "Fingerprint iteration coverage mismatch");
					require(PassengerScientificFingerprint.SCHEMA_VERSION.equals(required(row, "schema_version")), "Wrong fingerprint schema");
					String hash = required(row, "event_sha256"); require(hash.matches("[a-f0-9]{64}"), "Malformed passenger fingerprint SHA-256");
					long count = integer(row, "event_count");
					if (iteration < spec.design().protocol().activationIteration()) preActivation.put(iteration, new PassengerScientificFingerprint.Fingerprint(iteration, count, hash, PassengerScientificFingerprint.SCHEMA_VERSION));
				}
				rowCounts.put(PassengerScientificFingerprint.FILE_NAME, rows.count);
			}
			require(expected == (long) spec.design().protocol().finalIteration() + 1, "Incomplete passenger fingerprint coverage");
		}

        void planFingerprints() throws IOException {
            requireSummary(); long expected = spec.design().protocol().firstIteration();
            try (Rows rows = rows(PassengerPlanFingerprint.FILE_NAME, PLAN_HEADER)) {
                for (CSVRecord record : rows) {
                    var row = record.toMap(); identity(row);
                    require(DynamicMixedDesign.SCHEMA_ID.equals(required(row, "design_family")), "Wrong plan fingerprint design family");
                    require(PassengerPlanFingerprint.SCHEMA_VERSION.equals(required(row, "schema_version")), "Wrong plan fingerprint algorithm schema");
                    int iteration = signedInteger(row, "iteration"); require(iteration == expected++ && summaries.containsKey(iteration), "Plan fingerprint coverage mismatch");
                    long people = integer(row, "passenger_count"); equalCount(summaries.get(iteration), "passenger_count", people);
                    String sha = required(row, "plan_sha256"); require(sha.matches("[a-f0-9]{64}"), "Invalid plan fingerprint digest");
                    if (iteration < spec.design().protocol().activationIteration()) preActivationPlans.put(iteration,
                            new PassengerPlanFingerprint.Fingerprint(iteration, Math.toIntExact(people), sha, PassengerPlanFingerprint.SCHEMA_VERSION));
                }
                rowCounts.put(PassengerPlanFingerprint.FILE_NAME, rows.count);
            }
            require(expected == (long) spec.design().protocol().finalIteration() + 1, "Incomplete plan fingerprint coverage");
        }

        void statistics() throws IOException {
            requireSummary();
            statisticsFile("passenger_scorestats.csv", SCORE_HEADER, row -> {
                var summary = summaries.get(signedInteger(row, "iteration"));
                equalCount(summary, "passenger_count", integer(row, "passenger_persons"));
                equalCount(summary, "scored_passenger_count", integer(row, "scored_persons"));
                sameOptional(summary, "selected_plan_passenger_score", row, "avg_selected_score");
            });
            statisticsFile("passenger_modestats.csv", MODE_HEADER, row -> {
                var summary = summaries.get(signedInteger(row, "iteration"));
                long trips = integer(row, "passenger_trips"); equalCount(summary, "selected_passenger_trips", trips);
                Sum shares = new Sum(); for (String mode : MODE_HEADER.subList(2, MODE_HEADER.size())) shares.add(fraction(row, mode));
                close(shares.value, trips == 0 ? 0 : 1, "Selected mode-share sum");
                close(number(summary, "pt_share"), number(row, "pt"), "Selected PT share");
            });
            statisticsFile("passenger_service_stats.csv", SERVICE_HEADER, row -> {
                var summary = summaries.get(signedInteger(row, "iteration"));
                long all = integer(row, "completed_passenger_trips"), pt = integer(row, "completed_pt_trips");
                require(pt <= all, "Completed PT trips exceed completed passenger trips"); equalCount(summary, "completed_pt_trips", pt);
                for (int index = 3; index < SERVICE_HEADER.size(); index++) {
                    String field = SERVICE_HEADER.get(index); long sample = index < 6 ? all : pt;
                    if (sample == 0) blank(row, field); else nonnegative(row, field);
                }
                for (int index = 3; index < SERVICE_HEADER.size(); index += 3) if (!row.get(SERVICE_HEADER.get(index)).isEmpty())
                    require(number(row, SERVICE_HEADER.get(index + 1)) <= number(row, SERVICE_HEADER.get(index + 2)), "Reversed service percentiles");
                sameOptional(summary, "mean_pt_door_to_door_minutes", row, "pt_door_to_door_mean_min");
                sameOptional(summary, "mean_pt_wait_minutes", row, "pt_wait_mean_min");
            });
        }

        private void statisticsFile(String name, List<String> columns, java.util.function.Consumer<Map<String, String>> check) throws IOException {
            long expected = spec.design().protocol().firstIteration();
            try (Rows rows = new Rows(file(name), columns, ';')) {
                for (CSVRecord record : rows) {
                    var row = record.toMap(); int iteration = signedInteger(row, "iteration");
                    require(iteration == expected++ && summaries.containsKey(iteration), "Passenger statistics iteration coverage mismatch: " + name);
                    check.accept(row);
                }
                rowCounts.put(name, rows.count);
            }
            require(expected == (long) spec.design().protocol().finalIteration() + 1, "Incomplete passenger statistics: " + name);
        }

		private int context(Map<String, String> row) {
			identity(row); require(ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA.equals(required(row, "schema_version")), "Wrong row schema");
			int iteration = signedInteger(row, "iteration");
			require(iteration >= spec.design().protocol().firstIteration() && iteration <= spec.design().protocol().finalIteration(), "Row iteration outside spec"); return iteration;
		}
		private void identity(Map<String, String> row) {
			require(spec.experimentId().equals(required(row, "experiment_id")) && spec.blockId().equals(required(row, "block_id"))
					&& spec.design().arm().name().equals(required(row, "arm")), "Artifact experiment/block/arm mismatch");
		}
		private void requireSummary() { require(Boolean.TRUE.equals(checks.get("iteration_summary")), "Validated complete iteration summary is required"); }
		private boolean writesExposure(int iteration) { return iteration == spec.design().protocol().finalIteration()
				|| (spec.output().passengerExposure() == ScientificRunContext.ExposureOutput.REPORTING_WINDOW && iteration >= spec.design().protocol().reportFirstIteration() && iteration <= spec.design().protocol().reportLastIteration()); }
		private Rows rows(String name, List<String> columns) throws IOException { return new Rows(file(name), columns); }
		private String unitOrder(String unitId) { return unitParents.get(unitId); }
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
            this(path, columns, ',');
        }
        Rows(Path path, List<String> columns, char delimiter) throws IOException {
			definitions = columns.contains("phase_active") || columns.contains("number_of_units") || columns.contains("person_id") || columns.contains("denial_sequence")
					? columns.stream().map(name -> fieldDefinition(path.getFileName().toString(), name)).toList() : List.of();
			Reader reader = path.toString().endsWith(".gz") ? new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8) : Files.newBufferedReader(path, StandardCharsets.UTF_8);
			try {
				parser = new CSVParser(reader, CSVFormat.RFC4180.builder().setDelimiter(delimiter).setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(false).build());
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
    private static boolean sameAttributes(BasicFileAttributes a, BasicFileAttributes b) {
        return a.isRegularFile() && b.isRegularFile() && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
                && a.creationTime().equals(b.creationTime()) && Objects.equals(a.fileKey(), b.fileKey());
    }
    private static void sameOptional(Map<String, String> left, String leftName, Map<String, String> right, String rightName) {
        if (requiredColumn(left, leftName).isEmpty()) blank(right, rightName);
        else close(number(left, leftName), number(right, rightName), "Scientific/native statistics " + leftName);
    }
    private static String requiredColumn(Map<String, String> row, String field) {
        require(row.containsKey(field), "Missing column " + field); return row.get(field);
    }
    private static final Set<String> INTEGER_COLUMNS = Set.of("iteration", "passenger_count", "scored_passenger_count",
            "selected_passenger_trips", "completed_pt_trips", "passenger_denied_boardings", "freight_denied_boardings",
            "freight_order_count", "completed_orders", "service_window_completed_orders", "eventual_completed_orders", "on_time_orders",
            "first_board_wait_sample_size", "completed_order_lateness_sample_size", "passenger_boardings", "freight_boardings",
            "passenger_stuck_events", "freight_stuck_events", "freight_events", "denial_detail_events", "unfinished_passenger_rides",
            "unfinished_freight_units_onboard", "passengers_with_raw_copresence", "number_of_units", "units_boarded", "units_delivered",
            "denial_count", "boarding_events", "delivery_events", "denial_sequence", "passenger_occupancy", "freight_occupancy", "native_capacity",
            "phase_activation_count", "phase_activation_iteration");
    private static final Set<String> BOOLEAN_COLUMNS = Set.of("phase_active", "exposure_event_state_valid", "completed",
            "completed_in_service_window", "completed_within_execution_horizon", "on_time", "unfinished_ride");
    private static final Set<String> FRACTION_COLUMNS = Set.of("pt_share", "raw_copresence_incidence", "max_load_ratio",
            "max_freight_share_of_capacity", "freight_share_cap", "passenger_load_gate");
    private static final Set<String> NULLABLE_COLUMNS = Set.of("selected_plan_passenger_score", "mean_pt_door_to_door_minutes",
            "mean_pt_wait_minutes", "mean_first_board_wait_minutes", "mean_lateness_minutes", "first_unit_board_time_seconds",
            "last_unit_board_time_seconds", "first_accessible_departure_time_seconds", "schedule_wait_minutes",
            "additional_admission_delay_minutes", "first_board_wait_minutes", "completion_time_seconds", "lateness_minutes",
            "line_id", "route_id", "departure_id", "freight_share_cap", "passenger_load_gate", "raw_copresence_incidence");


    // Exact current writer field definitions, mirrored because its helper is package-private.
    static Map<String, Object> fieldDefinition(String file, String name) {
        boolean numeric = INTEGER_COLUMNS.contains(name) || FRACTION_COLUMNS.contains(name)
                || name.endsWith("_seconds") || name.endsWith("_minutes") || name.endsWith("_penalty")
                || name.endsWith("_exposure") || name.equals("selected_plan_passenger_score");
        String type = INTEGER_COLUMNS.contains(name) ? "integer" : BOOLEAN_COLUMNS.contains(name) ? "boolean"
                : numeric ? "number" : "string";
        String unit = INTEGER_COLUMNS.contains(name) ? (name.equals("iteration") || name.equals("phase_activation_iteration") ? "iteration index" : "count")
                : FRACTION_COLUMNS.contains(name) ? "fraction [0,1]" : name.endsWith("_seconds") ? "seconds after midnight"
                : name.endsWith("_minutes") ? "minutes" : name.endsWith("_exposure") ? "minute-equivalent per person, summed in summary"
                : name.endsWith("_penalty") || name.equals("selected_plan_passenger_score") ? "model score points" : "dimensionless";
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name); definition.put("type", type); definition.put("unit", unit);
        definition.put("nullable", NULLABLE_COLUMNS.contains(name) || (file.equals("denial_ledger.csv") && name.equals("order_id")));
        if (numeric && !name.equals("selected_plan_passenger_score")) definition.put("minimum", name.equals("phase_activation_iteration") ? -1 : 0);
        if (FRACTION_COLUMNS.contains(name)) definition.put("maximum", 1);
        return definition;
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
    private static <K,V> Map<K,V> immutable(Map<K,V> value) { return Collections.unmodifiableMap(new LinkedHashMap<>(value)); }
	private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
	private record OrderDefinition(long units, double release, double deadline, String serviceClass) { }
	private static final class Sum { double value, correction; void add(double number) { double adjusted = number - correction, total = value + adjusted; correction = (total - value) - adjusted; value = total; require(Double.isFinite(value), "Aggregate overflow"); } }
	@FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
