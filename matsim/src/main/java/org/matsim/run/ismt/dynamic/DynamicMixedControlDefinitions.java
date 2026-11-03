package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SharedBoardingWindow;
import org.matsim.run.ismt.ExperimentSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedExperimentSpec.*;

/**
 * Six narrow, hash-bound machine-readable definitions for the existing single-line
 * mixed controller. Decoding neither approves numerical assumptions nor runs QSim.
 * Binding checks the supplied Scenario, not a frozen build, complete configuration,
 * prepared population, native event source or physical railway-resource model.
 * The exact-link/XY grouping below is the explicitly synthetic registration used by
 * this domain; it is not evidence that coincident facilities share a signal block.
 * No input, schedule, plan, MATSim identity or random stream is changed.
 */
public final class DynamicMixedControlDefinitions {
    public static final String POLICY_SCHEMA = "ismt-dynamic-policy-v1";
    public static final String CONSTRAINT_SCHEMA = "ismt-dynamic-constraints-v1";
    public static final String FORECAST_SCHEMA = "ismt-dynamic-forecast-v1";
    public static final String PROTECTION_SCHEMA = "ismt-dynamic-passenger-protection-v1";
    public static final String INFORMATION_SCHEMA = "ismt-dynamic-information-v1";
    public static final String BASELINE_SCHEMA = "ismt-dynamic-baseline-treatment-v1";
    public static final String POLICY_ID = "bounded-coordinate-mixed-v1";
    public static final String FORECAST_ID = "causal-mixed-service-v1";
    public static final String WINDOWED_POLICY_ID = "bounded-coordinate-shared-window-v2";
    public static final String WINDOWED_FORECAST_ID = "causal-windowed-mixed-service-v2";

    public enum Objective { LATE_UNITS_THEN_CENSORED_LATENESS_THEN_WAIT_THEN_ABSOLUTE_SHIFT }
    public enum TieOrder { LINE_ROUTE_DEPARTURE_THEN_ASCENDING_GRID_TIME }
    public enum ResourceModel { SYNTHETIC_EXACT_LINK_XY_SHARED_DEPARTURE_POINTS,
        SYNTHETIC_EXACT_LINK_XY_ADJUSTABLE_TRIP_ADJACENT_PAIRS }
    public enum CohortRule { FIXED_WAITING_AND_FORECAST_WITHIN_ADJUSTABLE_SERVICE_ENVELOPE }
    public enum HistoryRule { COMPLETE_TRAILING_WINDOW_REQUIRED }
    public enum ObservationRule { NATIVE_CLOSED_PREFIX_ONLY }

    public record PolicyDefinition(String schemaVersion, String policyId, DecisionTiming timing,
                                   Limits limits, Objective objective, TieOrder tieOrder,
                                   int maximumCandidates, int maximumImprovingSweeps) {
        public PolicyDefinition {
            schema(schemaVersion, POLICY_SCHEMA); require(POLICY_ID.equals(policyId) || WINDOWED_POLICY_ID.equals(policyId), "Unsupported policy implementation");
            Objects.requireNonNull(timing); Objects.requireNonNull(limits);
            Objects.requireNonNull(objective); Objects.requireNonNull(tieOrder);
            require(maximumCandidates > 0 && maximumImprovingSweeps > 0, "Explicit positive search budgets required");
        }
    }

    /** A visit is explicit, including the native event facility and the published departure offset. */
    public record PointCall(TripKey trip, int stopIndex, String facilityId, double departureOffsetSeconds) {
        public PointCall {
            Objects.requireNonNull(trip); require(stopIndex >= 0, "Negative stop index");
            text(facilityId); time(departureOffsetSeconds);
        }
    }

    public record SharedPoint(ControlPointKey key, String linkId, double x, double y,
                              List<PointCall> orderedCalls, FirstLastWindow serviceWindow) {
        public SharedPoint {
            Objects.requireNonNull(key); text(linkId); finite(x); finite(y);
            orderedCalls = List.copyOf(orderedCalls); require(!orderedCalls.isEmpty(), "Empty shared point");
            Objects.requireNonNull(serviceWindow);
        }
    }

    /** Origin order/window only: its redundant upper bound is derived from the immutable shift limits. */
    public record RouteOrigin(ControlPointKey key, List<TripKey> orderedTrips, FirstLastWindow serviceWindow) {
        public RouteOrigin {
            Objects.requireNonNull(key); orderedTrips = List.copyOf(orderedTrips);
            require(!orderedTrips.isEmpty(), "Empty route-origin registration"); Objects.requireNonNull(serviceWindow);
        }
    }

    public record ConstraintDefinition(String schemaVersion, OperatingBasis operatingBasis, ResourceModel resourceModel,
            String baselineScheduleSha256, double minimumSharedHeadwaySeconds, double maximumHeadwayIncrementSeconds,
            List<SharedPoint> sharedDeparturePoints, List<RouteOrigin> routeOrigins,
            List<VehicleConnection> vehicleConnections) {
        public ConstraintDefinition {
            schema(schemaVersion, CONSTRAINT_SCHEMA); Objects.requireNonNull(operatingBasis); Objects.requireNonNull(resourceModel);
            require(operatingBasis == OperatingBasis.SYNTHETIC_ASSUMPTIONS, "Only explicitly synthetic resources are supported");
            digest(baselineScheduleSha256); time(minimumSharedHeadwaySeconds); time(maximumHeadwayIncrementSeconds);
            sharedDeparturePoints = List.copyOf(sharedDeparturePoints); routeOrigins = List.copyOf(routeOrigins);
            vehicleConnections = List.copyOf(vehicleConnections);
            require(!sharedDeparturePoints.isEmpty() && !routeOrigins.isEmpty(), "Shared resources and route origins must be explicit");
        }
    }

    public record ForecastDefinition(String schemaVersion, String forecastId, String approximation,
            double observationStartSeconds, double trailingWindowSeconds, HistoryRule historyRule,
            double arrivalCohortStepSeconds, double executionEndSeconds,
            int maximumPassengerCohorts, int maximumStopEvents) {
        public ForecastDefinition {
            schema(schemaVersion, FORECAST_SCHEMA); require(FORECAST_ID.equals(forecastId) || WINDOWED_FORECAST_ID.equals(forecastId), "Unsupported forecast implementation");
            require((WINDOWED_FORECAST_ID.equals(forecastId) ? CausalMixedServiceForecast.WINDOWED_APPROXIMATION
                    : CausalMixedServiceForecast.APPROXIMATION).equals(approximation), "Forecast approximation identity differs");
            time(observationStartSeconds); positive(trailingWindowSeconds); Objects.requireNonNull(historyRule);
            positive(arrivalCohortStepSeconds); time(executionEndSeconds);
            require(executionEndSeconds > observationStartSeconds, "Empty forecast horizon");
            require(maximumPassengerCohorts > 0 && maximumStopEvents > 0, "Explicit positive forecast budgets required");
        }
    }

    public record PassengerProtectionDefinition(String schemaVersion, CohortRule cohortRule,
                                               double maximumMeanWaitingIncrementSeconds) {
        public PassengerProtectionDefinition {
            schema(schemaVersion, PROTECTION_SCHEMA); Objects.requireNonNull(cohortRule); time(maximumMeanWaitingIncrementSeconds);
        }
    }

    public record InformationDefinition(String schemaVersion, InformationModel informationModel,
            ResetMode resetMode, ObservationRule observationRule, int observationLagSteps) {
        public InformationDefinition {
            schema(schemaVersion, INFORMATION_SCHEMA); Objects.requireNonNull(informationModel);
            Objects.requireNonNull(resetMode); Objects.requireNonNull(observationRule);
            require(observationLagSteps == 2, "This source registration requires the native two-step closed-prefix lag");
        }
    }

    public record RepairDeparture(TripKey trip, double departureSeconds) {
        public RepairDeparture { Objects.requireNonNull(trip); time(departureSeconds); }
    }

    public record BaselineTreatmentDefinition(String schemaVersion, BaselineTreatment treatment,
            String originalScheduleSha256, int activationIteration, double repairDecisionTimeSeconds,
            List<RepairDeparture> repairDepartures) {
        public BaselineTreatmentDefinition {
            schema(schemaVersion, BASELINE_SCHEMA); Objects.requireNonNull(treatment); digest(originalScheduleSha256);
            require(activationIteration >= 0, "Negative activation iteration"); time(repairDecisionTimeSeconds);
            repairDepartures = List.copyOf(repairDepartures);
            require((treatment == BaselineTreatment.NO_PRE_SERVICE_REPAIR) == repairDepartures.isEmpty(),
                    "Baseline treatment and repair entries disagree");
            var seen = new HashSet<TripKey>();
            for (var item : repairDepartures) require(seen.add(item.trip()), "Repeated repair trip");
        }
    }

    public record Bundle(DynamicMixedExperimentSpec spec, PolicyDefinition policy, ConstraintDefinition constraints,
            ForecastDefinition forecast, PassengerProtectionDefinition passengerProtection,
            InformationDefinition information, BaselineTreatmentDefinition baselineTreatment) {
        public Bundle {
            Objects.requireNonNull(spec); Objects.requireNonNull(policy); Objects.requireNonNull(constraints);
            Objects.requireNonNull(forecast); Objects.requireNonNull(passengerProtection);
            Objects.requireNonNull(information); Objects.requireNonNull(baselineTreatment);
            var control = spec.control(); var runtime = spec.runtime();
            require(control.policyId().equals(policy.policyId()) && control.timing().equals(policy.timing())
                    && spec.design().limits().equals(policy.limits()), "Policy timing/identity/limits differ from specification");
            require(control.operatingBasis() == constraints.operatingBasis(), "Operating basis differs from specification");
            require(constraints.baselineScheduleSha256().equals(spec.inputs().baselineSchedule().sha256())
                    && baselineTreatment.originalScheduleSha256().equals(spec.inputs().baselineSchedule().sha256()), "Original schedule digest differs");
            require(forecast.observationStartSeconds() == runtime.qsimStartTimeSeconds()
                    && forecast.executionEndSeconds() == runtime.executionHorizonSeconds(), "Forecast clocks differ from execution registration");
            require(information.informationModel() == control.informationModel() && information.resetMode() == control.resetMode()
                    && information.observationLagSteps() == runtime.observationLagSteps(), "Information/reset/source lag differs");
            require(baselineTreatment.treatment() == control.baselineTreatment()
                    && baselineTreatment.activationIteration() == spec.design().protocol().activationIteration(), "Baseline treatment/activation differs");
            require(spec.policies().handlingDwellSeconds() == 0, "Forecast supports zero additional freight handling dwell only");
            boolean windowed = WINDOWED_POLICY_ID.equals(policy.policyId());
            require(windowed == WINDOWED_FORECAST_ID.equals(forecast.forecastId()), "Dispatch and forecast window semantics differ");
            require(windowed == (constraints.resourceModel() == ResourceModel.SYNTHETIC_EXACT_LINK_XY_ADJUSTABLE_TRIP_ADJACENT_PAIRS),
                    "Headway scope differs from the policy's immutable outside-window services");
            if (windowed) {
                var window = new SharedBoardingWindow(spec.freightDemand().serviceWindowStartSeconds(), spec.freightDemand().serviceWindowEndSeconds());
                require(baselineTreatment.treatment() == BaselineTreatment.NO_PRE_SERVICE_REPAIR,
                        "Window-only control cannot carry the old out-of-window pre-service repair");
                require(policy.timing().firstDecisionTimeSeconds() == window.startInclusiveSeconds(),
                        "Windowed policy's first decision must equal the shared opening time");
            }
            if (baselineTreatment.treatment() == BaselineTreatment.FREIGHT_BLIND_PRE_SERVICE_REPAIR) {
                double repair = baselineTreatment.repairDecisionTimeSeconds();
                require(repair >= runtime.qsimStartTimeSeconds() && repair < runtime.executionHorizonSeconds(), "Repair time is outside the day");
                require((repair - runtime.qsimStartTimeSeconds()) % runtime.qsimTimeStepSeconds() == 0, "Repair time is not a native step boundary");
                require(repair - information.observationLagSteps() * runtime.qsimTimeStepSeconds()
                        >= forecast.observationStartSeconds() + forecast.trailingWindowSeconds(), "Repair lacks a complete closed history window");
            }
        }

        public Bound bind(Scenario scenario) { return bindTo(this, Objects.requireNonNull(scenario)); }
    }

    /** Detached reusable static binding. Each newPolicy call creates a fresh, non-running daily controller. */
    public static final class Bound {
        private final Bundle definitions;
        private final SingleLineForecastSupply supply;
        private final List<ControlPoint> points;
        private final List<VehicleConnection> connections;
        private final DispatchConstraintChecker checker;
        private final Map<TripKey, Double> original, repair;
        private final CausalMixedServiceForecast.Settings forecastSettings;
        private final SharedBoardingWindow sharedWindow;

        private Bound(Bundle definitions, SingleLineForecastSupply supply, List<ControlPoint> points,
                      DispatchConstraintChecker checker, Map<TripKey, Double> original, Map<TripKey, Double> repair) {
            this.definitions = definitions; this.supply = supply; this.points = List.copyOf(points);
            connections = definitions.constraints().vehicleConnections(); this.checker = checker;
            this.original = Map.copyOf(original); this.repair = Map.copyOf(repair);
            sharedWindow = WINDOWED_POLICY_ID.equals(definitions.policy().policyId()) ? new SharedBoardingWindow(
                    definitions.spec().freightDemand().serviceWindowStartSeconds(), definitions.spec().freightDemand().serviceWindowEndSeconds()) : null;
            var f = definitions.forecast(); var p = definitions.spec().policies();
            forecastSettings = new CausalMixedServiceForecast.Settings(f.observationStartSeconds(), f.trailingWindowSeconds(),
                    definitions.policy().timing().lookAheadSeconds(), f.arrivalCohortStepSeconds(), f.executionEndSeconds(),
                    f.maximumPassengerCohorts(), f.maximumStopEvents(), p.freightShareCap(), p.passengerLoadGate(), definitions.policy().limits(), sharedWindow);
        }
        public Bundle definitions() { return definitions; }
        public SingleLineForecastSupply supply() { return supply; }
        public List<ControlPoint> controlPoints() { return points; }
        public List<VehicleConnection> vehicleConnections() { return connections; }
        public DispatchConstraintChecker checker() { return checker; }
        public Map<TripKey, Double> originalBaseline() { return original; }
        public Map<TripKey, Double> repairDepartures() { return repair; }
        public CausalMixedServiceForecast.Settings forecastSettings() { return forecastSettings; }
        public SharedBoardingWindow sharedWindow() { return sharedWindow; }
        public RollingDepartureCandidates.AdjustmentWindow adjustmentWindow() {
            return sharedWindow == null ? null : new RollingDepartureCandidates.AdjustmentWindow(
                    sharedWindow.startInclusiveSeconds(), sharedWindow.endExclusiveSeconds());
        }

        public DynamicMixedQSimModule.Policy newPolicy(RollingDeparturePolicy.ForecastModel forecast,
                Consumer<RollingDeparturePolicy.Decision> decisions, Consumer<PreServiceRepairPolicy.Audit> repairs) {
            Objects.requireNonNull(forecast); Objects.requireNonNull(decisions); Objects.requireNonNull(repairs);
            var p = definitions.policy(); var timing = p.timing();
            var candidates = new RollingDepartureCandidates(supply.trips().values().stream().map(SingleLineForecastSupply.Trip::scheduled).toList(),
                    p.limits(), timing.lookAheadSeconds(), timing.candidateStepSeconds(), p.maximumCandidates(), adjustmentWindow());
            double protection = definitions.passengerProtection().maximumMeanWaitingIncrementSeconds();
            var rolling = new RollingDeparturePolicy(candidates, checker, forecast, timing.firstDecisionTimeSeconds(),
                    timing.decisionIntervalSeconds(), protection, p.maximumImprovingSweeps(), decisions, adjustmentWindow());
            if (definitions.baselineTreatment().treatment() == BaselineTreatment.NO_PRE_SERVICE_REPAIR) return rolling;
            return new PreServiceRepairPolicy(original, p.limits(), checker, repair,
                    definitions.baselineTreatment().repairDecisionTimeSeconds(), timing.lookAheadSeconds(),
                    timing.firstDecisionTimeSeconds(), timing.decisionIntervalSeconds(), forecast, rolling, protection, repairs);
        }
    }

    private record Location(String link, double x, double y) { }
    private record RouteKey(String line, String route) { }
    private record Visit(String facility, int index, double offset) { }
    private static final Comparator<TripKey> KEY_ORDER = Comparator.comparing(TripKey::line).thenComparing(TripKey::route).thenComparing(TripKey::departure);
    private static final ObjectMapper JSON = strictMapper();
    private DynamicMixedControlDefinitions() { }

    /** Six exact file payloads only. Whole-run input verification and external approval remain separate. */
    public static Bundle read(Path workspace, DynamicMixedExperimentSpec spec) throws IOException {
        Objects.requireNonNull(spec); Path root = Objects.requireNonNull(workspace).toRealPath();
        var c = spec.control();
        return new Bundle(spec,
                readRole(root, c.policyDefinition(), POLICY_SCHEMA, PolicyDefinition.class),
                readRole(root, c.constraintDefinition(), CONSTRAINT_SCHEMA, ConstraintDefinition.class),
                readRole(root, c.forecastDefinition(), FORECAST_SCHEMA, ForecastDefinition.class),
                readRole(root, c.passengerProtectionDefinition(), PROTECTION_SCHEMA, PassengerProtectionDefinition.class),
                readRole(root, c.informationDefinition(), INFORMATION_SCHEMA, InformationDefinition.class),
                readRole(root, c.baselineTreatmentDefinition(), BASELINE_SCHEMA, BaselineTreatmentDefinition.class));
    }

    /** Compact sorted-object encoding for these six typed definitions; lists retain their semantic order. */
    public static byte[] canonicalBytes(Record definition) throws IOException {
        require(definition instanceof PolicyDefinition || definition instanceof ConstraintDefinition || definition instanceof ForecastDefinition
                || definition instanceof PassengerProtectionDefinition || definition instanceof InformationDefinition
                || definition instanceof BaselineTreatmentDefinition, "Expected one supported typed definition");
        return JSON.writeValueAsBytes(sorted(JSON.valueToTree(definition)));
    }

    private static <T> T readRole(Path root, RegisteredDefinition role, String expectedSchema, Class<T> type) throws IOException {
        require(expectedSchema.equals(role.schemaVersion()), "Definition role schema mismatch: " + expectedSchema);
        Path registered = Path.of(role.file().path());
        for (Path part : registered) require(!part.toString().equals(".."), "Parent traversal in definition path");
        Path path = (registered.isAbsolute() ? registered : root.resolve(registered)).toRealPath();
        require(path.startsWith(root) && !path.equals(root) && Files.isRegularFile(path), "Definition is outside workspace or not a regular file");
        byte[] bytes = Files.readAllBytes(path);
        require(sha256(bytes).equals(role.file().sha256()), "Definition file digest differs: " + expectedSchema);
        JsonNode node = JSON.readTree(bytes);
        if (node == null || !node.isObject()) throw new IOException("Expected one non-null definition object: " + expectedSchema);
        return JSON.treeToValue(node, type);
    }

    private static Bound bindTo(Bundle b, Scenario scenario) {
        var supply = SingleLineForecastSupply.from(scenario); var limits = b.policy().limits();
        require(supply.qsimTimeStepSeconds() == b.spec().runtime().qsimTimeStepSeconds(), "Native supply step differs from definition");
        var originals = new LinkedHashMap<TripKey, Double>();
        var routeTrips = new HashMap<RouteKey, List<TripKey>>();
        supply.trips().forEach((key, trip) -> {
            originals.put(key, trip.scheduled().baselineDepartureSeconds());
            routeTrips.computeIfAbsent(route(key), ignored -> new ArrayList<>()).add(key);
        });
        Comparator<TripKey> originOrder = Comparator.comparingDouble((TripKey k) -> originals.get(k)).thenComparing(KEY_ORDER);
        routeTrips.values().forEach(keys -> keys.sort(originOrder));
        var locations = new HashMap<Location, Map<RouteKey, Visit>>();
        var schedule = scenario.getTransitSchedule();
        for (var line : schedule.getTransitLines().values()) for (var nativeRoute : line.getRoutes().values()) {
            RouteKey route = new RouteKey(line.getId().toString(), nativeRoute.getId().toString());
            for (int index = 0; index < nativeRoute.getStops().size(); index++) {
                var stop = nativeRoute.getStops().get(index); var facility = stop.getStopFacility();
                require(facility.getLinkId() != null && facility.getCoord() != null, "Explicit synthetic resource link and coordinates required");
                var location = new Location(facility.getLinkId().toString(), facility.getCoord().getX(), facility.getCoord().getY());
                finite(location.x()); finite(location.y());
                require(locations.computeIfAbsent(location, ignored -> new HashMap<>()).putIfAbsent(route,
                        new Visit(facility.getId().toString(), index, stop.getDepartureOffset().seconds())) == null,
                        "Repeated same-route link/XY resource requires unsupported visit identities");
            }
        }
        locations.entrySet().removeIf(entry -> entry.getValue().size() < 2);
        var registeredLocations = new HashSet<Location>(); var points = new ArrayList<ControlPoint>();
        for (var point : b.constraints().sharedDeparturePoints()) {
            var location = new Location(point.linkId(), point.x(), point.y());
            require(registeredLocations.add(location), "Shared location registered more than once");
            var members = locations.get(location); require(members != null, "Unknown/nonshared synthetic resource location");
            var expected = new ArrayList<PointCall>();
            members.forEach((route, visit) -> routeTrips.get(route).forEach(trip -> expected.add(
                    new PointCall(trip, visit.index(), visit.facility(), visit.offset()))));
            expected.sort(Comparator.comparingDouble((PointCall call) -> originals.get(call.trip()) + call.departureOffsetSeconds())
                    .thenComparing(PointCall::trip, KEY_ORDER));
            require(expected.equals(point.orderedCalls()), "Shared membership, native facility/offset or immutable baseline order differs: " + point.key());
            var calls = expected.stream().map(call -> new Call(call.trip(), call.departureOffsetSeconds())).toList();
            verifyWindow(point.serviceWindow(), calls, originals, limits);
            double max = sum(maximumGap(calls, originals), b.constraints().maximumHeadwayIncrementSeconds());
            points.add(new ControlPoint(point.key(), calls, b.constraints().minimumSharedHeadwaySeconds(), max, Optional.of(point.serviceWindow())));
        }
        require(registeredLocations.equals(locations.keySet()), "Registered shared points do not cover the exact synthetic link/XY groups");
        var registeredRoutes = new HashSet<RouteKey>();
        for (var point : b.constraints().routeOrigins()) {
            RouteKey key = route(point.orderedTrips().getFirst());
            require(registeredRoutes.add(key) && point.orderedTrips().equals(routeTrips.get(key)), "Route-origin membership/order differs or is duplicated");
            var calls = point.orderedTrips().stream().map(trip -> new Call(trip, 0)).toList();
            verifyWindow(point.serviceWindow(), calls, originals, limits);
            // This upper bound follows algebraically from original +/- limits; it adds no scientific threshold.
            double redundantMax = sum(maximumGap(calls, originals), limits.maximumAdvanceSeconds(), limits.maximumDelaySeconds());
            points.add(new ControlPoint(point.key(), calls, 0, redundantMax, Optional.of(point.serviceWindow())));
        }
        require(registeredRoutes.equals(routeTrips.keySet()), "Missing route-origin order/window registration");
        var checker = new DispatchConstraintChecker(supply.trips().values().stream().map(SingleLineForecastSupply.Trip::scheduled).toList(),
                points, b.constraints().vehicleConnections(), WINDOWED_POLICY_ID.equals(b.policy().policyId())
                        ? new RollingDepartureCandidates.AdjustmentWindow(b.spec().freightDemand().serviceWindowStartSeconds(),
                        b.spec().freightDemand().serviceWindowEndSeconds()) : null);
        var repair = new LinkedHashMap<TripKey, Double>(); var repaired = new LinkedHashMap<>(originals);
        for (var entry : b.baselineTreatment().repairDepartures()) {
            Double origin = originals.get(entry.trip()); require(origin != null, "Repair contains unknown trip");
            double proposed = entry.departureSeconds();
            require(proposed != origin && origin - proposed <= limits.maximumAdvanceSeconds()
                    && proposed - origin <= limits.maximumDelaySeconds(), "Repair identity or original displacement bound violated");
            double time = b.baselineTreatment().repairDecisionTimeSeconds();
            double lock = sum(time, limits.lockWindowSeconds()), horizon = sum(time, b.policy().timing().lookAheadSeconds());
            require(origin > lock && proposed > lock && origin <= horizon && proposed <= horizon, "Repair is outside its unlocked decision horizon");
            double grid = b.policy().timing().candidateStepSeconds();
            require(java.math.BigDecimal.valueOf(proposed).subtract(java.math.BigDecimal.valueOf(origin))
                    .remainder(java.math.BigDecimal.valueOf(grid)).signum() == 0, "Repair is off the original-baseline candidate grid");
            repair.put(entry.trip(), proposed); repaired.put(entry.trip(), proposed);
        }
        boolean active = b.spec().design().controlEnabled() && (limits.maximumAdvanceSeconds() > 0 || limits.maximumDelaySeconds() > 0);
        if (active) {
            boolean baseFeasible = checker.evaluate(originals).baseline().satisfiesRegisteredConstraints();
            require(baseFeasible || !repair.isEmpty(), "Infeasible original baseline has no registered repair");
            require(repair.isEmpty() || !baseFeasible, "A pre-service repair is registered although original constraints are feasible");
            require(checker.evaluate(repaired).candidate().satisfiesRegisteredConstraints(), "Registered repair is not globally feasible under these planned constraints");
        }
        return new Bound(b, supply, points, checker, originals, repair);
    }

    private static RouteKey route(TripKey trip) { return new RouteKey(trip.line(), trip.route()); }
    private static void verifyWindow(FirstLastWindow window, List<Call> calls, Map<TripKey, Double> origins, Limits limits) {
        double first = sum(origins.get(calls.getFirst().trip()), calls.getFirst().offsetSeconds());
        double last = sum(origins.get(calls.getLast().trip()), calls.getLast().offsetSeconds());
        require(window.equals(new FirstLastWindow(first, sum(first, limits.maximumDelaySeconds()),
                Math.max(0, last - limits.maximumAdvanceSeconds()), sum(last, limits.maximumDelaySeconds()))),
                "Service window differs from original first-not-earlier/last-plus-delay registration");
    }
    private static double maximumGap(List<Call> calls, Map<TripKey, Double> origins) {
        double max = 0;
        for (int index = 1; index < calls.size(); index++) {
            var left = calls.get(index - 1); var right = calls.get(index);
            double gap = sum(origins.get(right.trip()), right.offsetSeconds()) - sum(origins.get(left.trip()), left.offsetSeconds());
            require(gap >= 0, "Registered baseline order is reversed"); max = Math.max(max, gap);
        }
        return max;
    }

    private static ObjectMapper strictMapper() {
        var mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT, DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                        DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS, DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT,
                        DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL,
                        DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS, MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS,
                        MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        for (var shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean))
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Enum).setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        return mapper;
    }
    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var entries = new TreeMap<String, JsonNode>(); node.properties().forEach(e -> entries.put(e.getKey(), e.getValue()));
            var result = JSON.createObjectNode(); entries.forEach((key, value) -> result.set(key, sorted(value))); return result;
        }
        if (node.isArray()) { var result = JSON.createArrayNode(); node.forEach(item -> result.add(sorted(item))); return result; }
        return node;
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException absent) { throw new IllegalStateException(absent); }
    }
    private static double sum(double... values) { double total = 0; for (double value : values) total += value; finite(total); return total; }
    private static void schema(String actual, String expected) { require(expected.equals(actual), "Unsupported definition schema: " + actual); }
    private static void digest(String value) { require(value != null && value.matches("[0-9a-f]{64}"), "Invalid SHA-256"); }
    private static void text(String value) { require(value != null && !value.isBlank(), "Nonblank text required"); }
    private static void finite(double value) { require(Double.isFinite(value), "Nonfinite value"); }
    private static void time(double value) { finite(value); require(value >= 0, "Negative time"); }
    private static void positive(double value) { time(value); require(value > 0, "Positive value required"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
