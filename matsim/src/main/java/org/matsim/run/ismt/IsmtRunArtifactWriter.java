package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.contrib.cotransport.CoTransportRuntimeMonitor;
import org.matsim.contrib.cotransport.FreightOrder;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.contrib.cotransport.PassengerExposureSnapshot;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingDeniedDetailEvent;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingDeniedDetailEventHandler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Versioned output adapter. Install the same singleton as controller listener and
 * detail-event handler. Its low listener priority consumes the original passenger
 * analysis rows after their writers finish; no rounded legacy freight CSV is read.
 */
@Singleton
public final class IsmtRunArtifactWriter implements RunArtifactWriter, IterationEndsListener,
        TransitBoardingDeniedDetailEventHandler {
    public static final String SCHEMA = "ismt-output-v1";
    public static final String UNAVAILABLE_DEPARTURE = "unavailable_no_reliable_route_schedule_reconstruction";
    public static final List<String> CONTEXT = List.of("schema_version", "experiment_id", "block_id", "arm", "iteration");
    public static final List<String> SUMMARY_COLUMNS = columns(
            "passenger_count", "scored_passenger_count", "selected_passenger_trips", "pt_share",
            "selected_plan_passenger_score", "completed_pt_trips", "mean_pt_door_to_door_minutes", "mean_pt_wait_minutes",
            "raw_physical_exposure", "raw_freight_copresence_exposure", "physical_penalty", "co_presence_penalty",
            "applied_physical_penalty", "applied_co_presence_penalty", "passenger_denied_boardings", "freight_denied_boardings",
            "freight_order_count", "completed_orders", "service_window_completed_orders", "eventual_completed_orders",
            "on_time_orders", "mean_first_board_wait_minutes", "mean_lateness_minutes", "first_board_wait_sample_size",
            "completed_order_lateness_sample_size", "passenger_boardings", "freight_boardings", "passenger_stuck_events",
            "freight_stuck_events", "freight_events", "phase_active", "denial_detail_events", "unfinished_passenger_rides",
            "unfinished_freight_units_onboard", "exposure_event_state_valid", "max_load_ratio", "max_freight_share_of_capacity",
            "passengers_with_raw_copresence", "raw_copresence_incidence", "phase_activation_count", "phase_activation_iteration");
    public static final List<String> ORDER_COLUMNS = columns(
            "order_id", "release_time_seconds", "number_of_units", "units_boarded", "units_delivered",
            "first_unit_board_time_seconds", "last_unit_board_time_seconds", "first_accessible_departure_time_seconds",
            "schedule_wait_minutes", "additional_admission_delay_minutes", "first_board_wait_minutes", "completion_time_seconds",
            "deadline_seconds", "service_class", "completed", "completed_in_service_window", "completed_within_execution_horizon",
            "on_time", "lateness_minutes", "denial_count", "denial_reasons", "boarding_events", "delivery_events",
            "accessible_departure_status");
    public static final List<String> EXPOSURE_COLUMNS = columns(
            "person_id", "raw_physical_exposure", "raw_freight_copresence_exposure", "physical_penalty", "co_presence_penalty",
            "valued_total_penalty", "applied_physical_penalty", "applied_co_presence_penalty", "total_added_penalty", "unfinished_ride");
    public static final List<String> DENIAL_COLUMNS = columns(
            "denial_sequence", "time_seconds", "actor_id", "actor_class", "order_id", "vehicle_id", "line_id", "route_id",
            "departure_id", "stop_id", "denial_reason", "passenger_occupancy", "freight_occupancy", "native_capacity",
            "freight_share_cap", "passenger_load_gate");
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

    private final ScientificRunContext context;
    private final OutputDirectoryHierarchy io;
    private final CoTransportRuntimeMonitor monitor;
    private final FreightOrderRegistry orders;
    private final Path output;
    private final List<TransitBoardingDeniedDetailEvent> denials = new ArrayList<>();
    private int currentIteration = -1;
    private int lastWrittenIteration;
    private boolean initialized;

    @Inject
    public IsmtRunArtifactWriter(ExperimentSpec spec, OutputDirectoryHierarchy io,
            CoTransportRuntimeMonitor monitor, FreightOrderRegistry orders) {
        this(ScientificRunContext.legacy(spec), io, monitor, orders);
    }

    /** Separate v2 context avoids forging a legacy arm or experiment specification. */
    public IsmtRunArtifactWriter(ScientificRunContext context, OutputDirectoryHierarchy io,
            CoTransportRuntimeMonitor monitor, FreightOrderRegistry orders) {
        this.context = java.util.Objects.requireNonNull(context);
        this.io = io;
        this.monitor = monitor;
        this.orders = orders;
        this.output = Path.of(io.getOutputPath());
        this.lastWrittenIteration = context.firstIteration() - 1;
        monitor.configureDetailedVehicleOutput(context.vehicleIntervals());
    }

    @Override public double priority() { return -1000.0; }

    @Override public void reset(int iteration) {
        if (!denials.isEmpty()) throw new IllegalStateException("Unwritten denial evidence before iteration reset");
        currentIteration = iteration;
    }

    @Override public void handleEvent(TransitBoardingDeniedDetailEvent event) {
        if (currentIteration < context.firstIteration() || currentIteration <= lastWrittenIteration)
            throw new IllegalStateException("Denial detail outside an open iteration");
        if (!Double.isFinite(event.getTime()) || event.getTime() < 0)
            throw new IllegalArgumentException("Invalid denial time");
        denials.add(event);
    }

    @Override public void notifyIterationEnds(IterationEndsEvent event) { writeIteration(event.getIteration()); }

    @Override public void writeIteration(int iteration) {
        if (iteration != currentIteration || iteration != lastWrittenIteration + 1
                || iteration > context.finalIteration())
            throw new IllegalStateException("Missing, duplicated, or out-of-order artifact iteration: " + iteration);
        try {
            PassengerExposureSnapshot exposure = monitor.passengerExposureSnapshot();
            if (exposure.iteration() != iteration) throw new IllegalStateException("Stale exposure snapshot");
            var counts = monitor.runtimeCounts();
            var freight = monitor.freightOrderSnapshots(context.executionHorizonSeconds());
            if (freight.size() != context.orderCount()) throw new IllegalStateException("Order/spec count mismatch");
            if (denials.size() != counts.passengerDeniedBoardings() + counts.freightDeniedBoardings())
                throw new IllegalStateException("Native denial count differs from detailed denial evidence");
            Map<String, String> mode = readIterationRow(Path.of(io.getOutputFilename("passenger_modestats.csv")), iteration);
            Map<String, String> score = readIterationRow(Path.of(io.getOutputFilename("passenger_scorestats.csv")), iteration);
            Map<String, String> service = readIterationRow(Path.of(io.getOutputFilename("passenger_service_stats.csv")), iteration);
            if (integer(score, "passenger_persons") != exposure.persons().size())
                throw new IllegalStateException("Passenger analysis/exposure universe mismatch");
            Map<String, Map<String, Integer>> reasonsByOrder = denialReasonsByOrder();
            for (var order : freight) {
                int detailCount = reasonsByOrder.getOrDefault(order.order().orderId(), Map.of())
                        .values().stream().mapToInt(Integer::intValue).sum();
                if (detailCount != order.deniedBoardings()) throw new IllegalStateException("Order denial count mismatch");
            }
            if (!initialized) initialize();
            appendOrders(iteration, freight, reasonsByOrder);
            appendDenials(iteration);
            if (writesExposure(iteration)) appendExposure(iteration, exposure);
            // Summary is the last scientific row committed for this iteration.
            appendSummary(iteration, mode, score, service, exposure, counts, freight);
            lastWrittenIteration = iteration;
            denials.clear();
        } catch (IOException error) {
            throw new UncheckedIOException("Could not write standardized ISMT artifacts", error);
        }
    }

    public boolean writesExposure(int iteration) {
        return iteration == context.finalIteration()
                || (context.passengerExposure() == ScientificRunContext.ExposureOutput.REPORTING_WINDOW
                && iteration >= context.reportFirstIteration() && iteration <= context.reportLastIteration());
    }

    private void initialize() throws IOException {
        Files.createDirectories(output);
        for (String name : List.of("iteration_summary.csv", "order_ledger.csv", "passenger_exposure.csv.gz",
                "denial_ledger.csv", "artifact_schema.json")) {
            if (Files.exists(output.resolve(name))) throw new IOException("Refusing to overwrite existing standard artifact: " + name);
        }
        writeNewHeader("iteration_summary.csv", SUMMARY_COLUMNS);
        writeNewHeader("order_ledger.csv", ORDER_COLUMNS);
        writeNewHeader("denial_ledger.csv", DENIAL_COLUMNS);
        try (BufferedWriter writer = gzipWriter(false)) { writeRow(writer, EXPOSURE_COLUMNS); }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("schema_version", context.outputSchema());
        schema.putAll(context.additionalSchemaMetadata());
        Map<String, List<String>> fileColumns = new TreeMap<>(Map.of("iteration_summary.csv", SUMMARY_COLUMNS, "order_ledger.csv", ORDER_COLUMNS,
                "passenger_exposure.csv.gz", EXPOSURE_COLUMNS, "denial_ledger.csv", DENIAL_COLUMNS));
        schema.put("files", fileColumns);
        Map<String, List<Map<String, Object>>> fields = new TreeMap<>();
        fileColumns.forEach((file, names) -> fields.put(file, names.stream().map(name -> fieldDefinition(file, name)).toList()));
        schema.put("field_definitions", fields);
        schema.put("units", new TreeMap<>(Map.of("*_seconds", "seconds after simulation-day midnight", "*_minutes", "minutes",
                "raw_*_exposure", "summed person-minute-equivalents; never divided by beta",
                "*_penalty", "positive model score points", "pt_share", "selected passenger PT trips / selected passenger trips",
                "*_incidence", "fraction of all passenger persons", "*_ratio", "fraction of native vehicle capacity")));
        schema.put("passenger_exposure_mode", context.passengerExposure().name());
        schema.put("detailed_vehicle_observations", context.vehicleIntervals());
        schema.put("missing_values", "Empty fields mean unavailable, not zero. Nonfinite values are never serialized.");
        schema.put("accessible_departure_status", UNAVAILABLE_DEPARTURE);
        schema.put("penalty_semantics", "physical/co_presence_penalty are beta times raw. applied_* and total_added_penalty are actual alighting score costs; unfinished rides are flagged.");
        schema.put("iteration_contract", "One summary row per executed iteration; all registered orders each iteration; exposure terminal and optionally reporting window; one row per native denial counterpart.");
        Files.write(output.resolve("artifact_schema.json"), new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(schema), StandardOpenOption.CREATE_NEW);
        initialized = true;
    }

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

    private void appendSummary(int iteration, Map<String, String> mode, Map<String, String> score,
            Map<String, String> service, PassengerExposureSnapshot exposure, CoTransportRuntimeMonitor.RuntimeCounts counts,
            List<CoTransportRuntimeMonitor.FreightOrderSnapshot> freight) throws IOException {
        List<Double> waits = freight.stream().map(o -> o.outcome().firstBoardWaitMinutes()).filter(Double::isFinite).toList();
        List<Double> late = freight.stream().map(o -> o.outcome().latenessMinutes()).filter(Double::isFinite).toList();
        long incidence = exposure.persons().values().stream().filter(p -> p.rawExposure().freightCoPresence() > 0).count();
        List<String> row = context(iteration);
        add(row, integer(score, "passenger_persons"), integer(score, "scored_persons"), integer(mode, "passenger_trips"),
                number(mode, "pt", false), number(score, "avg_selected_score", true), integer(service, "completed_pt_trips"),
                number(service, "pt_door_to_door_mean_min", true), number(service, "pt_wait_mean_min", true),
                exposure.rawExposure().physical(), exposure.rawExposure().freightCoPresence(), exposure.valuedPenalties().physical(),
                exposure.valuedPenalties().freightCoPresence(), exposure.appliedPenalties().physical(), exposure.appliedPenalties().freightCoPresence(),
                counts.passengerDeniedBoardings(), counts.freightDeniedBoardings(), freight.size(),
                freight.stream().filter(o -> o.outcome().completed()).count(),
                freight.stream().filter(o -> o.outcome().completedInServiceWindow()).count(),
                freight.stream().filter(o -> o.outcome().completedWithinExecutionHorizon()).count(),
                freight.stream().filter(o -> o.outcome().onTime()).count(), mean(waits), mean(late), waits.size(), late.size(),
                counts.passengerBoardings(), counts.freightBoardings(), counts.passengerStuckEvents(), counts.freightStuckEvents(),
                counts.freightEvents(), counts.phaseActive(), denials.size(), exposure.unfinishedRides(), counts.freightUnitsStillOnboard(),
                exposure.validEventState(), counts.maxLoadRatio(), counts.maxFreightShareOfCapacity(), incidence,
                exposure.persons().isEmpty() ? "" : (double) incidence / exposure.persons().size(),
                counts.phaseActivationCount(), counts.phaseActivationIteration());
        appendRow("iteration_summary.csv", SUMMARY_COLUMNS, row);
    }

    private void appendOrders(int iteration, List<CoTransportRuntimeMonitor.FreightOrderSnapshot> freight,
            Map<String, Map<String, Integer>> reasons) throws IOException {
        try (BufferedWriter writer = appendWriter("order_ledger.csv")) {
            for (var snapshot : freight) {
                FreightOrder order = snapshot.order();
                var outcome = snapshot.outcome();
                List<String> row = context(iteration);
                add(row, order.orderId(), order.releaseTime(), order.capacityUnits(), outcome.unitsBoarded(), outcome.unitsDelivered(),
                        optional(outcome.firstUnitBoardTime()), optional(outcome.lastUnitBoardTime()), "", "", "",
                        optional(outcome.firstBoardWaitMinutes()), optional(outcome.completionTime()), order.deadlineTime(), order.serviceClass(),
                        outcome.completed(), outcome.completedInServiceWindow(), outcome.completedWithinExecutionHorizon(), outcome.onTime(),
                        optional(outcome.latenessMinutes()), snapshot.deniedBoardings(), reasonString(reasons.getOrDefault(order.orderId(), Map.of())),
                        snapshot.boardingEvents(), snapshot.deliveryEvents(), UNAVAILABLE_DEPARTURE);
                checkedRow(writer, ORDER_COLUMNS, row);
            }
        }
    }

    private void appendExposure(int iteration, PassengerExposureSnapshot snapshot) throws IOException {
        try (BufferedWriter writer = gzipWriter(true)) {
            for (var person : snapshot.persons().values()) {
                List<String> row = context(iteration);
                add(row, person.personId(), person.rawExposure().physical(), person.rawExposure().freightCoPresence(),
                        person.valuedPenalties().physical(), person.valuedPenalties().freightCoPresence(), person.valuedPenalties().total(),
                        person.appliedPenalties().physical(), person.appliedPenalties().freightCoPresence(), person.appliedPenalties().total(),
                        person.unfinishedRide());
                checkedRow(writer, EXPOSURE_COLUMNS, row);
            }
        }
    }

    private void appendDenials(int iteration) throws IOException {
        try (BufferedWriter writer = appendWriter("denial_ledger.csv")) {
            int sequence = 0;
            for (TransitBoardingDeniedDetailEvent event : denials) {
                var detail = event.getDetails();
                FreightOrder order = orders.orderForPerson(event.getPersonId());
                List<String> row = context(iteration);
                add(row, ++sequence, event.getTime(), event.getPersonId(), detail.actorClass(), order == null ? "" : order.orderId(),
                        event.getVehicleId(), nullable(event.getLineId()), nullable(event.getRouteId()), nullable(event.getDepartureId()),
                        event.getStopId(), event.getReason(), detail.passengers(), detail.freight(), detail.nativeCapacity(),
                        optional(detail.freightShareCap()), optional(detail.passengerLoadGate()));
                checkedRow(writer, DENIAL_COLUMNS, row);
            }
        }
    }

    private Map<String, Map<String, Integer>> denialReasonsByOrder() {
        Map<String, Map<String, Integer>> result = new TreeMap<>();
        for (var event : denials) {
            FreightOrder order = orders.orderForPerson(event.getPersonId());
            if (order != null) result.computeIfAbsent(order.orderId(), ignored -> new TreeMap<>())
                    .merge(event.getReason().name(), 1, Integer::sum);
        }
        return result;
    }

    private static String reasonString(Map<String, Integer> reasons) {
        return reasons.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("|"));
    }

    static Map<String, String> readIterationRow(Path path, int iteration) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IOException("Missing passenger analysis header: " + path);
        String[] names = lines.getFirst().split(";", -1);
        Map<String, String> found = null;
        for (int line = 1; line < lines.size(); line++) {
            if (lines.get(line).isBlank()) continue;
            String[] values = lines.get(line).split(";", -1);
            if (values.length != names.length) throw new IOException("Malformed passenger analysis row: " + path);
            if (!values[0].equals(Integer.toString(iteration))) continue;
            if (found != null) throw new IOException("Duplicated passenger analysis iteration: " + path);
            found = new LinkedHashMap<>();
            for (int column = 0; column < names.length; column++) found.put(names[column], values[column]);
        }
        if (found == null) throw new IOException("Passenger analysis iteration " + iteration + " absent: " + path);
        return found;
    }

    private static long integer(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null) throw new IllegalStateException("Missing passenger field: " + name);
        long result = Long.parseLong(value);
        if (result < 0) throw new IllegalStateException("Negative passenger count: " + name);
        return result;
    }

    private static Object number(Map<String, String> values, String name, boolean allowMissing) {
        String value = values.get(name);
        if (value == null) throw new IllegalStateException("Missing passenger field: " + name);
        if (value.isBlank() && allowMissing) return "";
        double result = Double.parseDouble(value);
        if (!Double.isFinite(result)) throw new IllegalStateException("Nonfinite passenger field: " + name);
        if (!name.equals("avg_selected_score") && result < 0) throw new IllegalStateException("Negative passenger service/share field: " + name);
        if (name.equals("pt") && result > 1) throw new IllegalStateException("PT share exceeds one");
        return result;
    }

    private static Object mean(List<Double> values) {
        return values.isEmpty() ? "" : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static Object optional(double value) {
        if (Double.isNaN(value)) return "";
        if (!Double.isFinite(value)) throw new IllegalStateException("Infinite scientific artifact value");
        return value;
    }

    private static Object nullable(Object value) { return value == null ? "" : value; }

    private List<String> context(int iteration) {
        return new ArrayList<>(List.of(context.outputSchema(), context.experimentId(), context.blockId(), context.arm(), Integer.toString(iteration)));
    }

    private static List<String> columns(String... names) {
        List<String> result = new ArrayList<>(CONTEXT);
        result.addAll(List.of(names));
        return List.copyOf(result);
    }

    private static void add(List<String> row, Object... values) {
        for (Object value : values) {
            if (value == null) throw new IllegalArgumentException("Null artifact field; use explicit unavailable");
            if (value instanceof Double number && !Double.isFinite(number))
                throw new IllegalArgumentException("Nonfinite artifact value");
            row.add(value.toString());
        }
    }

    private void writeNewHeader(String name, List<String> columns) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve(name), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writeRow(writer, columns);
        }
    }

    private BufferedWriter appendWriter(String name) throws IOException {
        return Files.newBufferedWriter(output.resolve(name), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private BufferedWriter gzipWriter(boolean append) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(
                output.resolve("passenger_exposure.csv.gz"), append ? StandardOpenOption.APPEND : StandardOpenOption.CREATE_NEW)),
                StandardCharsets.UTF_8));
    }

    private void appendRow(String name, List<String> columns, List<String> values) throws IOException {
        try (BufferedWriter writer = appendWriter(name)) { checkedRow(writer, columns, values); }
    }

    private static void checkedRow(BufferedWriter writer, List<String> columns, List<String> values) throws IOException {
        if (columns.size() != values.size()) throw new IllegalStateException("Artifact header/value width mismatch");
        writeRow(writer, values);
    }

    private static void writeRow(BufferedWriter writer, List<String> values) throws IOException {
        boolean first = true;
        for (String value : values) {
            if (!first) writer.write(',');
            first = false;
            writer.write('"');
            writer.write(value.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write('\n');
    }
}
