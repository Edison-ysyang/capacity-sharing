package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedControlDefinitions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedExperimentSpec.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Static scenarios and pure file decoding only: no JVM launch, QSim, Controller run or actual dispatch evidence. */
class DynamicMixedControlDefinitionsTest {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TripKey A = new TripKey("line", "a", "d");
    private static final TripKey B = new TripKey("line", "b", "d");

    @Test void windowedDefinitionBindsForecastCandidateAndCheckerWithoutTheOldMorningRepair() throws Exception {
        var original = toyBundle();
        var timing = new DecisionTiming(120, 5, 30, 5);
        var policy = new PolicyDefinition(POLICY_SCHEMA, WINDOWED_POLICY_ID, timing, original.policy().limits(),
                original.policy().objective(), original.policy().tieOrder(), 32, 1);
        var control = replace(replace(replace(original.spec().control(), "policyId", WINDOWED_POLICY_ID),
                "timing", timing), "baselineTreatment", BaselineTreatment.NO_PRE_SERVICE_REPAIR);
        var demand = replace(original.spec().freightDemand(), "serviceWindowStartSeconds", 120.0);
        var spec = replace(replace(original.spec(), "control", control), "freightDemand", demand);
        var constraints = replace(original.constraints(), "resourceModel", ResourceModel.SYNTHETIC_EXACT_LINK_XY_ADJUSTABLE_TRIP_ADJACENT_PAIRS);
        var f = original.forecast();
        var forecast = new ForecastDefinition(FORECAST_SCHEMA, WINDOWED_FORECAST_ID, CausalMixedServiceForecast.WINDOWED_APPROXIMATION,
                f.observationStartSeconds(), f.trailingWindowSeconds(), f.historyRule(), f.arrivalCohortStepSeconds(),
                f.executionEndSeconds(), f.maximumPassengerCohorts(), f.maximumStopEvents());
        var repair = new BaselineTreatmentDefinition(BASELINE_SCHEMA, BaselineTreatment.NO_PRE_SERVICE_REPAIR,
                original.baselineTreatment().originalScheduleSha256(), spec.design().protocol().activationIteration(), 120, List.of());
        var windowed = new Bundle(spec, policy, constraints, forecast, original.passengerProtection(), original.information(), repair);
        var registered = writeDefinitions(temporary, windowed);
        var bound = DynamicMixedControlDefinitions.read(temporary, registered).bind(scenario());
        assertEquals(120, bound.sharedWindow().startInclusiveSeconds());
        assertEquals(180, bound.sharedWindow().endExclusiveSeconds());
        assertEquals(bound.sharedWindow(), bound.forecastSettings().sharedWindow());
        assertTrue(bound.repairDepartures().isEmpty());
        assertTrue(bound.checker().evaluate(bound.originalBaseline()).candidate().satisfiesRegisteredConstraints(),
                "The fixed background 100/100 pair is not certified against window-only headway bounds");
        assertThrows(IllegalArgumentException.class, () -> bound.checker().evaluate(Map.of(A, 100.0, B, 125.0)));
        assertInstanceOf(RollingDeparturePolicy.class, bound.newPolicy((s, d) -> { throw new AssertionError(); }, d -> { }, d -> { }));
        assertThrows(IllegalArgumentException.class, () -> new Bundle(spec, policy, original.constraints(), forecast,
                original.passengerProtection(), original.information(), repair));
        assertThrows(IllegalArgumentException.class, () -> new Bundle(spec, policy, constraints, original.forecast(),
                original.passengerProtection(), original.information(), repair));
        assertThrows(IllegalArgumentException.class, () -> new Bundle(spec, policy, constraints, forecast,
                original.passengerProtection(), original.information(), original.baselineTreatment()));
    }

    @Test void sixHashedTypedRolesBindToNativeStaticObjectsAndCreateFreshPolicies() throws Exception {
        var spec = writeDefinitions(temporary, toyBundle());
        var read = DynamicMixedControlDefinitions.read(temporary, spec);
        var bound = read.bind(scenario());
        assertEquals(4, bound.controlPoints().size());
        assertTrue(bound.vehicleConnections().isEmpty());
        assertEquals(Map.of(A, 100.0, B, 100.0), bound.originalBaseline());
        assertEquals(Map.of(B, 110.0), bound.repairDepartures());
        assertFalse(bound.checker().evaluate(bound.originalBaseline()).candidate().satisfiesRegisteredConstraints());
        var repaired = new java.util.HashMap<>(bound.originalBaseline()); repaired.putAll(bound.repairDepartures());
        assertTrue(bound.checker().evaluate(repaired).candidate().satisfiesRegisteredConstraints());
        assertEquals(5, bound.forecastSettings().arrivalCohortStepSeconds());
        assertEquals(0.1, bound.forecastSettings().freightShareCap());
        assertEquals(0.9, bound.forecastSettings().passengerLoadGate());
        RollingDeparturePolicy.ForecastModel unused = (source, departures) -> { throw new AssertionError("Construction must not forecast"); };
        var first = bound.newPolicy(unused, d -> fail("Construction must not decide"), a -> fail("Construction must not repair"));
        var second = bound.newPolicy(unused, d -> fail(), a -> fail());
        assertInstanceOf(PreServiceRepairPolicy.class, first); assertNotSame(first, second);
        assertThrows(UnsupportedOperationException.class, () -> bound.controlPoints().clear());
        assertThrows(UnsupportedOperationException.class, () -> bound.originalBaseline().clear());
        assertThrows(UnsupportedOperationException.class, () -> bound.repairDepartures().clear());
    }

    @Test void everyRequiredFieldInEveryRoleRejectsMissingAndNullIncludingNestedCalls() throws Exception {
        int tested = 0;
        for (var item : definitions(toyBundle()).entrySet()) {
            ObjectNode original = JSON.valueToTree(item.getValue());
            var properties = new ArrayList<String>(); collectProperties(original, "", properties);
            for (String pointer : properties) {
                for (boolean nullInstead : List.of(false, true)) {
                    ObjectNode changed = original.deepCopy(); int slash = pointer.lastIndexOf('/');
                    ObjectNode parent = (ObjectNode) changed.at(pointer.substring(0, slash));
                    String field = pointer.substring(slash + 1);
                    if (nullInstead) parent.putNull(field); else parent.remove(field);
                    DynamicMixedExperimentSpec spec = writeDefinitions(temporary, toyBundle());
                    spec = substitute(spec, item.getKey(), JSON.writeValueAsBytes(changed));
                    var finalSpec = spec;
                    assertThrows(Exception.class, () -> DynamicMixedControlDefinitions.read(temporary, finalSpec),
                            item.getKey() + pointer + (nullInstead ? " null" : " missing"));
                    tested++;
                }
            }
        }
        assertTrue(tested > 200, "Recursive test must cover all six roles and nested visits/windows/keys");
    }

    @Test void everyRoleRejectsUnknownFieldsDuplicateKeysTrailingAndNonobjectRoots() throws Exception {
        for (var item : definitions(toyBundle()).entrySet()) {
            String canonical = new String(canonicalBytes(item.getValue()), StandardCharsets.UTF_8);
            for (String bad : List.of(canonical.substring(0, canonical.length() - 1) + ",\"unknown\":1}",
                    "{\"schemaVersion\":\"duplicate\"," + canonical.substring(1), canonical + " {}", "null", "[]", "true", "")) {
                var spec = substitute(writeDefinitions(temporary, toyBundle()), item.getKey(), bad.getBytes(StandardCharsets.UTF_8));
                assertThrows(Exception.class, () -> DynamicMixedControlDefinitions.read(temporary, spec), item.getKey() + bad);
            }
        }
    }

    @Test void scalarCoercionFloatAsIntegerEnumNumberAndNestedUnknownAreRejected() throws Exception {
        var policy = JSON.<ObjectNode>valueToTree(toyBundle().policy());
        for (JsonNode bad : List.of(JSON.getNodeFactory().textNode("32"), JSON.getNodeFactory().numberNode(32.0),
                JSON.getNodeFactory().booleanNode(true), JSON.createArrayNode().add(32))) {
            var changed = policy.deepCopy(); changed.set("maximumCandidates", bad);
            rejected("policyDefinition", changed);
        }
        for (String field : List.of("objective", "tieOrder", "policyId")) {
            var changed = policy.deepCopy(); changed.put(field, 1); rejected("policyDefinition", changed);
            changed = policy.deepCopy(); changed.put(field, true); rejected("policyDefinition", changed);
        }
        var changed = policy.deepCopy(); ((ObjectNode) changed.get("limits")).put("maximumAdvanceSeconds", "25"); rejected("policyDefinition", changed);
        changed = policy.deepCopy(); ((ObjectNode) changed.get("timing")).put("extra", 0); rejected("policyDefinition", changed);
        var info = JSON.<ObjectNode>valueToTree(toyBundle().information()); info.put("observationLagSteps", 2.0); rejected("informationDefinition", info);
    }

    @Test void digestMismatchRoleSchemaMismatchAndInsidePayloadSchemaMismatchAreRejected() throws Exception {
        var spec = writeDefinitions(temporary, toyBundle());
        var definition = spec.control().policyDefinition();
        Files.writeString(temporary.resolve(definition.file().path()), "{}");
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedControlDefinitions.read(temporary, spec));
        var restored = writeDefinitions(temporary, toyBundle());
        var wrongRole = replace(restored.control().policyDefinition(), "schemaVersion", FORECAST_SCHEMA);
        var wrong = replace(restored, "control", replace(restored.control(), "policyDefinition", wrongRole));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedControlDefinitions.read(temporary, wrong));
        var policy = JSON.<ObjectNode>valueToTree(toyBundle().policy()); policy.put("schemaVersion", FORECAST_SCHEMA);
        rejected("policyDefinition", policy);
    }

    @Test void traversalAndSymlinkEscapeAreNotAcceptedEvenWithCorrectPayloadHash() throws Exception {
        var spec = writeDefinitions(temporary, toyBundle());
        var role = spec.control().policyDefinition();
        var traversal = replace(role, "file", new ExperimentSpec.FileRef("folder/../policyDefinition.json", role.file().sha256()));
        var bad = replace(spec, "control", replace(spec.control(), "policyDefinition", traversal));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedControlDefinitions.read(temporary, bad));
        Path outside = temporary.resolve("sibling"); Files.createDirectories(outside);
        Path confined = temporary.resolve("confined"); Files.createDirectories(confined);
        byte[] bytes = canonicalBytes(toyBundle().policy()); Path real = outside.resolve("policy.json"); Files.write(real, bytes);
        Files.createSymbolicLink(confined.resolve("policy.json"), real);
        var linked = replace(role, "file", new ExperimentSpec.FileRef("policy.json", sha(bytes)));
        var badLink = replace(spec, "control", replace(spec.control(), "policyDefinition", linked));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedControlDefinitions.read(confined, badLink));
    }

    @Test void sortedCanonicalObjectsKeepDeclaredArrayOrder() throws Exception {
        var policy = toyBundle().policy(); var spec = writeDefinitions(temporary, toyBundle());
        var node = JSON.<ObjectNode>valueToTree(policy); var reversed = JSON.createObjectNode();
        var fields = new ArrayList<String>(); node.fieldNames().forEachRemaining(fields::add); Collections.reverse(fields);
        fields.forEach(key -> reversed.set(key, node.get(key)));
        var changedSpec = substitute(spec, "policyDefinition", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(reversed));
        assertArrayEquals(canonicalBytes(policy), canonicalBytes(DynamicMixedControlDefinitions.read(temporary, changedSpec).policy()));
        var constraints = toyBundle().constraints(); var reversedPoints = new ArrayList<>(constraints.sharedDeparturePoints());
        Collections.reverse(reversedPoints);
        assertFalse(java.util.Arrays.equals(canonicalBytes(constraints), canonicalBytes(replace(constraints, "sharedDeparturePoints", reversedPoints))));
    }

    @Test void specificationIdentityAndClockDisagreementsFailBeforeNativeBinding() {
        var b = toyBundle();
        assertThrows(IllegalArgumentException.class, () -> replace(b, "policy", replace(b.policy(), "limits", new Limits(20, 25, 5))));
        assertThrows(IllegalArgumentException.class, () -> replace(b, "policy", replace(b.policy(), "timing", new DecisionTiming(11, 5, 30, 5))));
        assertThrows(IllegalArgumentException.class, () -> replace(b, "constraints", replace(b.constraints(), "baselineScheduleSha256", "b".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> replace(b, "forecast", replace(b.forecast(), "observationStartSeconds", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> replace(b, "forecast", replace(b.forecast(), "executionEndSeconds", 241.0)));
        assertThrows(IllegalArgumentException.class, () -> replace(b, "baselineTreatment", replace(b.baselineTreatment(), "activationIteration", 3)));
        assertThrows(IllegalArgumentException.class, () -> replace(b, "baselineTreatment", replace(b.baselineTreatment(), "repairDecisionTimeSeconds", 80.5)));
        assertThrows(IllegalArgumentException.class, () -> replace(b, "baselineTreatment", replace(b.baselineTreatment(), "repairDecisionTimeSeconds", 5.0)));
    }

    @Test void numericNonfiniteNegativeUnknownImplementationsAndEmptyRepairAreRejected() {
        var b = toyBundle();
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> replace(b.forecast(), "arrivalCohortStepSeconds", bad));
            assertThrows(IllegalArgumentException.class, () -> replace(b.constraints(), "minimumSharedHeadwaySeconds", bad));
            assertThrows(IllegalArgumentException.class, () -> replace(b.passengerProtection(), "maximumMeanWaitingIncrementSeconds", bad));
        }
        assertThrows(IllegalArgumentException.class, () -> replace(b.policy(), "maximumCandidates", 0));
        assertThrows(IllegalArgumentException.class, () -> replace(b.forecast(), "maximumPassengerCohorts", 0));
        assertThrows(IllegalArgumentException.class, () -> replace(b.policy(), "policyId", "a-new-policy"));
        assertThrows(IllegalArgumentException.class, () -> replace(b.forecast(), "approximation", "exact-native"));
        assertThrows(IllegalArgumentException.class, () -> replace(b.baselineTreatment(), "repairDepartures", List.of()));
    }

    @Test void missingDuplicatedOrWrongSyntheticSharedResourceIsRejected() {
        var b = toyBundle(); var points = b.constraints().sharedDeparturePoints();
        assertThrows(IllegalArgumentException.class, () -> withPoints(b, List.of(points.getFirst())).bind(scenario()));
        assertThrows(IllegalArgumentException.class, () -> withPoints(b, List.of(points.getFirst(), points.getFirst())).bind(scenario()));
        var wrong = replace(points.getFirst(), "x", 1.0);
        assertThrows(IllegalArgumentException.class, () -> withPoints(b, List.of(wrong, points.getLast())).bind(scenario()));
        var duplicateKey = replace(points.getLast(), "key", points.getFirst().key());
        assertThrows(IllegalArgumentException.class, () -> withPoints(b, List.of(points.getFirst(), duplicateKey)).bind(scenario()));
    }

    @Test void sharedCallsRequireExactFacilityVisitOffsetAllTripsAndOriginalTieOrder() {
        var b = toyBundle(); var first = b.constraints().sharedDeparturePoints().getFirst(); var calls = first.orderedCalls();
        var badCalls = List.of(List.of(calls.getLast(), calls.getFirst()), List.of(calls.getFirst()),
                List.of(replace(calls.getFirst(), "facilityId", "wrong"), calls.getLast()),
                List.of(replace(calls.getFirst(), "stopIndex", 1), calls.getLast()),
                List.of(replace(calls.getFirst(), "departureOffsetSeconds", 1.0), calls.getLast()),
                List.of(calls.getFirst(), calls.getFirst()),
                List.of(replace(calls.getFirst(), "trip", new TripKey("unknown", "a", "d")), calls.getLast()));
        for (var bad : badCalls) {
            var point = replace(first, "orderedCalls", bad);
            assertThrows(IllegalArgumentException.class, () -> withPoints(b, List.of(point, b.constraints().sharedDeparturePoints().getLast())).bind(scenario()));
        }
    }

    @Test void routeOriginsCannotBeMissingDuplicateMixedOrUseInventedWindows() {
        var b = toyBundle(); var origins = b.constraints().routeOrigins();
        for (var changed : List.of(List.of(origins.getFirst()), List.of(origins.getFirst(), origins.getFirst()),
                List.of(replace(origins.getFirst(), "orderedTrips", List.of(A, B)), origins.getLast()))) {
            var c = replace(b.constraints(), "routeOrigins", changed);
            assertThrows(IllegalArgumentException.class, () -> replace(b, "constraints", c).bind(scenario()));
        }
        var wrong = replace(origins.getFirst(), "serviceWindow", new FirstLastWindow(99, 125, 75, 125));
        var changed = replace(b.constraints(), "routeOrigins", List.of(wrong, origins.getLast()));
        assertThrows(IllegalArgumentException.class, () -> replace(b, "constraints", changed).bind(scenario()));
    }

    @Test void repairMustBeExistingNonidentityWithinOriginalGridLockHorizonAndGloballyFeasible() {
        var b = toyBundle();
        for (var entry : List.of(new RepairDeparture(new TripKey("line", "unknown", "d"), 110),
                new RepairDeparture(B, 100), new RepairDeparture(B, 130), new RepairDeparture(B, 109),
                new RepairDeparture(B, 105), new RepairDeparture(B, 80))) {
            var repair = replace(b.baselineTreatment(), "repairDepartures", List.of(entry));
            assertThrows(IllegalArgumentException.class, () -> replace(b, "baselineTreatment", repair).bind(scenario()), entry.toString());
        }
        var late = replace(b.baselineTreatment(), "repairDecisionTimeSeconds", 95.0);
        assertThrows(IllegalArgumentException.class, () -> replace(b, "baselineTreatment", late).bind(scenario()));
        var early = replace(b.baselineTreatment(), "repairDecisionTimeSeconds", 70.0);
        assertThrows(IllegalArgumentException.class, () -> replace(b, "baselineTreatment", early).bind(scenario()));
        assertThrows(IllegalArgumentException.class, () -> replace(b.baselineTreatment(), "repairDepartures",
                List.of(new RepairDeparture(B, 110), new RepairDeparture(B, 110))));
    }

    @Test void vehicleConnectionAndChangedNativeTimeStepCannotSneakIntoSingleTaskSupply() {
        var b = toyBundle();
        var connected = replace(b.constraints(), "vehicleConnections", List.of(new VehicleConnection("invented-turnback", A, B, 20, 0)));
        assertThrows(IllegalArgumentException.class, () -> replace(b, "constraints", connected).bind(scenario()));
        var scenario = scenario(); scenario.getConfig().qsim().setTimeStepSize(2);
        assertThrows(IllegalArgumentException.class, () -> b.bind(scenario));
    }

    @Test void noRepairIsSupportedOnlyWhenTheActiveRegisteredBaselineIsFeasible() {
        var b = toyBundle();
        var spec = replace(b.spec(), "control", replace(b.spec().control(), "baselineTreatment", BaselineTreatment.NO_PRE_SERVICE_REPAIR));
        var noRepair = new BaselineTreatmentDefinition(BASELINE_SCHEMA, BaselineTreatment.NO_PRE_SERVICE_REPAIR,
                "a".repeat(64), 2, 0, List.of());
        var infeasible = new Bundle(spec, b.policy(), b.constraints(), b.forecast(), b.passengerProtection(), b.information(), noRepair);
        assertThrows(IllegalArgumentException.class, () -> infeasible.bind(scenario()));
        var feasible = replace(infeasible, "constraints", replace(b.constraints(), "minimumSharedHeadwaySeconds", 0.0));
        var bound = feasible.bind(scenario());
        assertInstanceOf(RollingDeparturePolicy.class, bound.newPolicy((s, t) -> { throw new AssertionError(); }, d -> fail(), a -> fail()));
    }

    @Test void bindingDoesNotChangeNativeSupplyAndOutputIsDetached() {
        var b = toyBundle(); var scenario = scenario(); var bound = b.bind(scenario);
        var nativeTrip = scenario.getTransitSchedule().getTransitLines().values().iterator().next().getRoutes().get(Id.create("b", TransitRoute.class));
        assertEquals(100, nativeTrip.getDepartures().values().iterator().next().getDepartureTime());
        var old = nativeTrip.getDepartures().values().iterator().next();
        assertTrue(nativeTrip.removeDeparture(old));
        var replacement = scenario.getTransitSchedule().getFactory().createDeparture(old.getId(), 101);
        replacement.setVehicleId(old.getVehicleId()); nativeTrip.addDeparture(replacement);
        assertEquals(100, bound.originalBaseline().get(B).doubleValue());
        assertEquals(110, bound.repairDepartures().get(B).doubleValue());
        assertThrows(IllegalArgumentException.class, () -> b.bind(scenario));
    }

    private static Bundle withPoints(Bundle b, List<SharedPoint> points) {
        return replace(b, "constraints", replace(b.constraints(), "sharedDeparturePoints", points));
    }
    private void rejected(String role, ObjectNode node) throws Exception {
        var spec = substitute(writeDefinitions(temporary, toyBundle()), role, JSON.writeValueAsBytes(node));
        assertThrows(Exception.class, () -> DynamicMixedControlDefinitions.read(temporary, spec));
    }
    private static void collectProperties(JsonNode node, String path, List<String> result) {
        if (node.isObject()) node.properties().forEach(entry -> {
            String next = path + "/" + entry.getKey(); result.add(next); collectProperties(entry.getValue(), next, result);
        });
        else if (node.isArray()) for (int index = 0; index < node.size(); index++) collectProperties(node.get(index), path + "/" + index, result);
    }
    private DynamicMixedExperimentSpec substitute(DynamicMixedExperimentSpec spec, String field, byte[] bytes) throws Exception {
        Path file = temporary.resolve(field + ".json"); Files.write(file, bytes);
        var registered = definitions(toyBundle()).get(field);
        String schema = JSON.valueToTree(registered).get("schemaVersion").asText();
        var definition = new RegisteredDefinition(schema, new ExperimentSpec.FileRef(file.getFileName().toString(), sha(bytes)));
        return replace(spec, "control", replace(spec.control(), field, definition));
    }

    static Bundle toyBundle() {
        var base = DynamicMixedSpecFixtures.spec();
        var policy = new PolicyDefinition(POLICY_SCHEMA, POLICY_ID, base.control().timing(), base.design().limits(),
                Objective.LATE_UNITS_THEN_CENSORED_LATENESS_THEN_WAIT_THEN_ABSOLUTE_SHIFT,
                TieOrder.LINE_ROUTE_DEPARTURE_THEN_ASCENDING_GRID_TIME, 32, 1);
        var control = replace(replace(base.control(), "policyId", POLICY_ID), "baselineTreatment", BaselineTreatment.FREIGHT_BLIND_PRE_SERVICE_REPAIR);
        var spec = replace(base, "control", control);
        var points = new ArrayList<SharedPoint>();
        for (int index = 0; index < 2; index++) {
            double offset = index * 20;
            points.add(new SharedPoint(new ControlPointKey("shared-" + index, "outbound"), "link-" + index, index * 100, 0,
                    List.of(new PointCall(A, index, "a-" + index, offset), new PointCall(B, index, "b-" + index, offset)), window(100 + offset)));
        }
        var constraints = new ConstraintDefinition(CONSTRAINT_SCHEMA, OperatingBasis.SYNTHETIC_ASSUMPTIONS,
                ResourceModel.SYNTHETIC_EXACT_LINK_XY_SHARED_DEPARTURE_POINTS, "a".repeat(64), 10, 25, points,
                List.of(new RouteOrigin(new ControlPointKey("origin-a", "outbound"), List.of(A), window(100)),
                        new RouteOrigin(new ControlPointKey("origin-b", "outbound"), List.of(B), window(100))), List.of());
        var forecast = new ForecastDefinition(FORECAST_SCHEMA, FORECAST_ID, CausalMixedServiceForecast.APPROXIMATION,
                0, 5, HistoryRule.COMPLETE_TRAILING_WINDOW_REQUIRED, 5, 240, 1000, 20);
        var protection = new PassengerProtectionDefinition(PROTECTION_SCHEMA,
                CohortRule.FIXED_WAITING_AND_FORECAST_WITHIN_ADJUSTABLE_SERVICE_ENVELOPE, 10);
        var information = new InformationDefinition(INFORMATION_SCHEMA, spec.control().informationModel(), spec.control().resetMode(),
                ObservationRule.NATIVE_CLOSED_PREFIX_ONLY, 2);
        var repair = new BaselineTreatmentDefinition(BASELINE_SCHEMA, BaselineTreatment.FREIGHT_BLIND_PRE_SERVICE_REPAIR,
                "a".repeat(64), 2, 80, List.of(new RepairDeparture(B, 110)));
        return new Bundle(spec, policy, constraints, forecast, protection, information, repair);
    }

    static DynamicMixedExperimentSpec writeDefinitions(Path directory, Bundle bundle) throws Exception {
        Files.createDirectories(directory); var spec = bundle.spec();
        for (var entry : definitions(bundle).entrySet()) {
            byte[] bytes = canonicalBytes(entry.getValue()); Path path = directory.resolve(entry.getKey() + ".json"); Files.write(path, bytes);
            String schema = JSON.valueToTree(entry.getValue()).get("schemaVersion").asText();
            var role = new RegisteredDefinition(schema, new ExperimentSpec.FileRef(path.getFileName().toString(), sha(bytes)));
            spec = replace(spec, "control", replace(spec.control(), entry.getKey(), role));
        }
        return spec;
    }
    private static Map<String, Record> definitions(Bundle b) {
        return Map.of("policyDefinition", b.policy(), "constraintDefinition", b.constraints(), "forecastDefinition", b.forecast(),
                "passengerProtectionDefinition", b.passengerProtection(), "informationDefinition", b.information(), "baselineTreatmentDefinition", b.baselineTreatment());
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static FirstLastWindow window(double base) { return new FirstLastWindow(base, base + 25, base - 25, base + 25); }

    static Scenario scenario() {
        var config = ConfigUtils.createConfig(); config.transit().setUseTransit(true); config.qsim().setTimeStepSize(1);
        var scenario = ScenarioUtils.createScenario(config); var schedule = scenario.getTransitSchedule(); var factory = schedule.getFactory();
        var line = factory.createTransitLine(Id.create("line", TransitLine.class)); schedule.addTransitLine(line);
        var vf = VehicleUtils.getFactory(); var type = vf.createVehicleType(Id.create("type", VehicleType.class));
        type.getCapacity().setSeats(10).setStandingRoom(0); type.setAccessTime(1); type.setEgressTime(1);
        type.setDoorOperationMode(VehicleType.DoorOperationMode.serial); scenario.getTransitVehicles().addVehicleType(type);
        for (String routeName : List.of("a", "b")) {
            var stops = new ArrayList<org.matsim.pt.transitSchedule.api.TransitRouteStop>();
            for (int index = 0; index < 2; index++) {
                var stop = factory.createTransitStopFacility(Id.create(routeName + "-" + index, TransitStopFacility.class), new Coord(index * 100, 0), false);
                stop.setLinkId(Id.createLinkId("link-" + index)); schedule.addStopFacility(stop);
                var call = factory.createTransitRouteStop(stop, index * 20, index * 20); call.setAwaitDepartureTime(true); stops.add(call);
            }
            var route = factory.createTransitRoute(Id.create(routeName, TransitRoute.class),
                    RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("link-0"), Id.createLinkId("link-1")), stops, "pt");
            var vehicle = vf.createVehicle(Id.createVehicleId("vehicle-" + routeName), type); scenario.getTransitVehicles().addVehicle(vehicle);
            var departure = factory.createDeparture(Id.create("d", Departure.class), 100); departure.setVehicleId(vehicle.getId());
            route.addDeparture(departure); line.addRoute(route);
        }
        return scenario;
    }
}
