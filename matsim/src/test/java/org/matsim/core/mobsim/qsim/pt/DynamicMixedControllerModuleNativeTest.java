package org.matsim.core.mobsim.qsim.pt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Key;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.replanning.PlanStrategyModule;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.events.SimStepEventsRuntimeInspection;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.mobsim.qsim.QSimRuntimeInspection;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.core.replanning.selectors.PlanSelector;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.IsmtRunArtifactWriter;
import org.matsim.run.ismt.PassengerPlanFingerprint;
import org.matsim.run.ismt.PassengerScientificFingerprint;
import org.matsim.run.ismt.ScientificRunContext;
import org.matsim.run.ismt.dynamic.ClosedCoTransportObservationState;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.run.ismt.dynamic.DynamicMixedArtifactModule;
import org.matsim.run.ismt.dynamic.DynamicMixedControllerModule;
import org.matsim.run.ismt.dynamic.DynamicMixedDesign;
import org.matsim.run.ismt.dynamic.DynamicMixedQSimModule;
import org.matsim.run.ismt.dynamic.KnownFreightReleaseState;
import org.matsim.run.ismt.dynamic.KnownFreightJourneyState;
import org.matsim.run.ismt.dynamic.CausalMixedServiceForecast;
import org.matsim.run.ismt.dynamic.ClosedPassengerArrivalForecast;
import org.matsim.run.ismt.dynamic.SingleLineForecastSupply;
import org.matsim.run.ismt.dynamic.MixedCapacityProjection;
import org.matsim.run.ismt.dynamic.RollingDepartureCandidates;
import org.matsim.run.ismt.dynamic.RollingDeparturePolicy;
import org.matsim.run.ismt.dynamic.PreServiceRepairPolicy;
import org.matsim.run.ismt.dynamic.DispatchConstraintChecker;
import org.matsim.run.ismt.dynamic.DynamicMixedScenarioInputs;
import org.matsim.run.ismt.dynamic.DynamicMixedDispatchEvidence;
import org.matsim.run.ismt.dynamic.DynamicMixedDispatchValidator;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;

/**
 * Four uninterrupted real Controller runs, six days each. Real native replanning,
 * passenger/freight boarding, worker-derived exposure scores, and native plan-score
 * assignment are exercised; no events, driver transitions, phase notifications,
 * StrategyManager runs, score updates, or observer callbacks are fabricated.
 * Passive equivalence preserves every event field and count, the global physical
 * event order, and the complete score-event order. Only a derived score's position
 * among physical events in the same time bucket may differ; this is not proof of
 * reproducible global native event order. The captured raw traces remain unchanged.
 * The same runs also write v2 scientific artifacts without an ExperimentSpec binding.
 * Their unchanged v1 fingerprint algorithms retain their original limited scopes;
 * neither fingerprint alone certifies scores or complete physical event causality.
 *
 * The innovative test module only observes the native newly copied plan. It does
 * not change its route: this proves actual innovation gating/copying and scored
 * memory selection, NOT a validated behavioural response, convergence, or the
 * economic/operational usefulness of this deliberately simple dispatch policy.
 * The explicit small capacities, weights, times and constraints are test inputs,
 * not recommended scientific parameters. D and complete terminal-prefix coverage
 * are outside this fixture. Runtime output uses the wrapper-provided temp root.
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class DynamicMixedControllerModuleNativeTest {
    private static final String LINE = "controller-line", ROUTE = "controller-route";
    private static final String ORIGIN = "controller-origin", DESTINATION = "controller-destination";
    private static final String PASSENGER = "controller-passenger", FREIGHT = "cargo_controller_order";
    private static final String VEHICLE_A = "controller-vehicle-a", VEHICLE_B = "controller-vehicle-b";
    private static final String SELECT = "ToyObservedBest", INNOVATE = "ToyObservedCopy";
    private static final String CO_PRESENCE = "coTransportPassengerFreightCoPresence";
    private static final String EXPERIMENT_ID = "controller-artifact-micro", BLOCK_ID = "toy-block";
    private static final List<String> ARTIFACT_FILES = List.of("iteration_summary.csv", "order_ledger.csv",
            "passenger_exposure.csv.gz", "denial_ledger.csv", PassengerScientificFingerprint.FILE_NAME,
            PassengerPlanFingerprint.FILE_NAME);
    private static final double INNOVATION_WEIGHT = 1_000_000;
    private static final TripKey FIRST = new TripKey(LINE, ROUTE, "first");
    private static final TripKey SECOND = new TripKey(LINE, ROUTE, "second");
    private static final Map<TripKey, Double> BASELINE = Map.of(FIRST, 20.0, SECOND, 100.0);
    private static final DynamicMixedDesign.Protocol PROTOCOL = new DynamicMixedDesign.Protocol(0, 2, 4, 5, 4, 5);

    @TempDir Path temporary;

    @Test void concreteCausalForecastAndConditionalRepairReachActualNativeExecutionAcrossDailyResets() throws Exception {
        var design = new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED, PROTOCOL, true, new Limits(25, 25, 5));
        for (boolean repairNeeded : List.of(false, true)) {
            var forecasts = new LinkedHashMap<Integer, List<CausalMixedServiceForecast.DetailedForecast>>();
            var repairs = new LinkedHashMap<Integer, List<PreServiceRepairPolicy.Audit>>();
            var decisions = new LinkedHashMap<Integer, List<RollingDeparturePolicy.Decision>>();
            double minimum = repairNeeded ? 90 : 40;
            Run result = run(repairNeeded ? "concrete-repair" : "concrete-rolling", design, minimum, (fixture, assembly, evidence) -> day -> {
                var preparedInput = assembly.forIteration(day);
                var input = preparedInput.qsimInputs();
                var checker = new DispatchConstraintChecker(fixture.trips(), input.controlPoints(), input.connections());
                var dailyForecasts = new ArrayList<CausalMixedServiceForecast.DetailedForecast>();
                var dailyRepairs = new ArrayList<PreServiceRepairPolicy.Audit>();
                var dailyDecisions = new ArrayList<RollingDeparturePolicy.Decision>();
                assertNull(forecasts.put(day, dailyForecasts)); assertNull(repairs.put(day, dailyRepairs));
                assertNull(decisions.put(day, dailyDecisions));
                var predictor = new CausalMixedServiceForecast(new CausalMixedServiceForecast.Settings(
                        0, 5, 150, 5, 240, 1000, 10000, 0.25, 1, design.limits()),
                        preparedInput.supply(), preparedInput.accessEgress(), dailyForecasts::add);
                var recordedForecast = evidence.auditedForecast(day, predictor);
                var rolling = new RollingDeparturePolicy(new RollingDepartureCandidates(fixture.trips(), design.limits(), 150, 5, 100),
                        checker, recordedForecast, 10, 5, 10, 10, value -> { dailyDecisions.add(value); evidence.rollingDecision(day, value); });
                return new PreServiceRepairPolicy(BASELINE, design.limits(), checker,
                        repairNeeded ? Map.of(SECOND, 110.0) : Map.of(), 10, 150, 10, 5,
                        recordedForecast, rolling, 10, value -> { dailyRepairs.add(value); evidence.repairDecision(day, value); });
            });
            assertEquals(Set.of(2, 3, 4, 5), forecasts.keySet());
            assertEquals(Set.of(0, 1, 2, 3, 4, 5), result.audit().preparedDays);
            for (int day = 0; day <= 5; day++) {
                var session = result.sessions().get(day);
                assertClosedEvidence(result, day);
                assertPersistedEvidence(result, day, minimum, repairNeeded);
                assertEquals(day < 2 ? 0 : 1, result.audit().preparedInputs.get(day).verifiedFreightUnits());
                assertEquals(BASELINE, session.initialCommitments().commitments());
                assertFalse(session.receipt().poisoned());
                assertTrue(session.receipt().nativeEventsProcessingFinished());
                if (day < 2) {
                    assertEquals(100, driverStart(result.audit(), day, VEHICLE_B), 0);
                    assertEquals(0, session.receipt().policyCalls());
                    continue;
                }
                assertTrue(decisions.get(day).stream().anyMatch(value -> value.decisionTimeSeconds() >= 170));
                assertTrue(forecasts.get(day).stream().anyMatch(value -> value.knownFreightUnits() == 1
                        && value.forecast().status() == RollingDeparturePolicy.ForecastStatus.READY));
                assertTrue(forecasts.get(day).stream().anyMatch(value -> value.completionCounts()
                        .getOrDefault(MixedCapacityProjection.Completion.DELIVERED_OBSERVED, 0L) == 1));
                for (var forecast : forecasts.get(day)) if (forecast.forecast().status() == RollingDeparturePolicy.ForecastStatus.READY) {
                    assertEquals(day, forecast.iteration());
                    assertTrue(forecast.closedThroughSeconds().orElseThrow() <= forecast.decisionTimeSeconds());
                    assertEquals(240, forecast.forecastEndSeconds(), 0);
                }
                assertEquals(1, personEvents(result.audit(), day, ActivityStartEvent.class, FREIGHT).stream()
                        .filter(value -> "freight_delivery".equals(value.attributes().get("actType"))).count());
                if (repairNeeded) {
                    assertEquals(List.of(PreServiceRepairPolicy.Outcome.PENDING_REGISTERED_REPAIR,
                                    PreServiceRepairPolicy.Outcome.REPAIR_PROPOSED,
                                    PreServiceRepairPolicy.Outcome.REPAIR_COMMITMENT_OBSERVED,
                                    PreServiceRepairPolicy.Outcome.ROLLING_DELEGATED),
                            repairs.get(day).stream().map(PreServiceRepairPolicy.Audit::outcome).toList());
                    assertEquals(110, driverStart(result.audit(), day, VEHICLE_B), 0);
                    assertEquals(120, firstStopDeparture(result.audit(), day, VEHICLE_B), 0);
                    assertEquals(1, session.receipt().committedChanges());
                } else {
                    assertEquals(PreServiceRepairPolicy.Outcome.REPAIR_NOT_REQUIRED, repairs.get(day).getFirst().outcome());
                    assertTrue(decisions.get(day).stream().anyMatch(value -> value.outcome() == RollingDeparturePolicy.Outcome.PROPOSED));
                    assertTrue(driverStart(result.audit(), day, VEHICLE_B) < 100);
                }
                var published = result.audit().scenario.getTransitSchedule().getTransitLines().get(Id.create(LINE, TransitLine.class))
                        .getRoutes().get(Id.create(ROUTE, TransitRoute.class)).getDepartures().get(Id.create("second", Departure.class));
                assertEquals(100, published.getDepartureTime(), 0, "Actual control never rewrites the passenger timetable");
            }
        }
    }

    private static void assertPersistedEvidence(Run result, int day, double minimum, boolean repairNeeded) throws Exception {
        Path file = result.output().getParent().resolve("dispatch").resolve(String.format(java.util.Locale.ROOT, "iteration-%03d.jsonl", day));
        var pointKey = new ControlPointKey(ORIGIN, "toy-outbound");
        var point = new ControlPoint(pointKey, List.of(new Call(FIRST, 10), new Call(SECOND, 10)), minimum, 100, Optional.empty());
        var registered = new org.matsim.run.ismt.dynamic.DynamicMixedDispatchValidator.Inputs(result.design(),
                List.of(new ScheduledTrip(FIRST, VEHICLE_A, 20), new ScheduledTrip(SECOND, VEHICLE_B, 100)),
                List.of(point), List.of(), Map.of(pointKey, Map.of(FIRST, ORIGIN, SECOND, ORIGIN)),
                Map.of(FIRST, ORIGIN, SECOND, ORIGIN), Map.of(FREIGHT, 50.0), repairNeeded ? Map.of(SECOND, 110.0) : Map.of(), 10, 10, 1);
        var persistedReport = new org.matsim.run.ismt.dynamic.DynamicMixedDispatchValidator()
                .validate(result.output().getParent(), file, day, registered);
        assertTrue(persistedReport.valid(), persistedReport.errors().toString());
        assertEquals(1, persistedReport.actualDepartureIntervals().size());
        assertEquals(2, persistedReport.actualDepartureIntervals().getFirst().observedUniqueCalls());
        var parser = new ObjectMapper(); var rows = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (String line : Files.readAllLines(file)) rows.add(parser.readTree(line));
        assertEquals("day_start", rows.getFirst().path("type").asText());
        assertEquals("day_end", rows.getLast().path("type").asText());
        var expectedFacts = result.audit().closedEvidence.get(day).stream().flatMap(frame -> frame.view().newlyClosedEvents().stream())
                .filter(event -> Set.of(TransitDriverStartsEvent.class.getName(), VehicleDepartsAtFacilityEvent.class.getName(),
                        "org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent").contains(event.eventClass())).toList();
        var demandReferences = new LinkedHashSet<com.fasterxml.jackson.databind.JsonNode>();
        long facts = 0, attempts = 0;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index); assertEquals(index + 1, row.path("record").asLong());
            assertEquals(day, row.path("iteration").asInt());
            assertEquals(DynamicMixedDispatchEvidence.SCHEMA, row.path("schemaVersion").asText());
            var payload = row.path("payload");
            if (row.path("type").asText().equals("closed_service_facts")) {
                for (var event : payload.path("events")) {
                    var expected = expectedFacts.get(Math.toIntExact(facts++));
                    assertEquals(expected.closedSequence(), event.path("closedSequence").asLong());
                    assertEquals(expected.eventClass(), event.path("eventClass").asText());
                    assertEquals(expected.occurredAtSeconds(), event.path("occurredAtSeconds").asDouble());
                    assertEquals(parser.valueToTree(expected.attributes()), event.path("attributes"));
                    // Compare the JSON wire representation: valueToTree retains LongNode,
                    // whereas readTree uses IntNode for small whole-number sequences.
                    assertEquals(parser.readTree(parser.writeValueAsBytes(expected.firstCoveredByDrain().orElseThrow())),
                            event.path("firstCoveredByDrain"));
                    assertTrue(event.path("actualReceiptSeconds").isNull(), "Parallel drain time is not actual receipt time");
                    assertFalse(event.path("firstCoveredByDrain").isNull());
                    assertTrue(event.path("occurredAtSeconds").asDouble() <= payload.path("source").path("closedThroughSeconds").asDouble());
                    Map<String, String> attributes = new LinkedHashMap<>();
                    event.path("attributes").fields().forEachRemaining(entry -> attributes.put(entry.getKey(), entry.getValue().asText()));
                    assertTrue(result.audit().events(day).contains(new NativeEvent(event.path("eventClass").asText(),
                            event.path("occurredAtSeconds").asDouble(), attributes)));
                }
            }
            if (row.path("type").asText().equals("forecast_demand")) assertTrue(demandReferences.add(payload.path("reference")));
            if (row.path("type").asText().equals("forecast_attempt")) {
                assertEquals(++attempts, payload.path("attempt").asLong());
                assertTrue(payload.path("candidateSha256").asText().matches("[0-9a-f]{64}"));
                assertEquals(day, payload.path("source").path("iteration").asInt());
                assertTrue(payload.path("elapsedNanos").asLong() >= 0);
                assertTrue(demandReferences.contains(payload.path("demandReference")), "Candidate references an earlier recorded fixed demand");
                var source = result.audit().closedEvidence.get(day).get(payload.path("source").path("viewSequence").asInt() - 1);
                var candidate = new LinkedHashMap<>(source.commitmentsBeforePolicy().commitments());
                for (var change : payload.path("changesFromDecisionStart")) {
                    var key = change.path("trip");
                    candidate.put(new TripKey(key.path("line").asText(), key.path("route").asText(), key.path("departure").asText()),
                            change.path("departureSeconds").asDouble());
                }
                var keyOrder = java.util.Comparator.comparing(TripKey::line).thenComparing(TripKey::route).thenComparing(TripKey::departure);
                var ordered = candidate.entrySet().stream().sorted(Map.Entry.comparingByKey(keyOrder))
                        .map(entry -> Map.of("trip", entry.getKey(), "departureSeconds", entry.getValue())).toList();
                var canonical = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .writeValueAsBytes(ordered);
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical));
                assertEquals(hash, payload.path("candidateSha256").asText());
            }
        }
        var end = rows.getLast().path("payload");
        assertEquals(facts, end.path("copiedServiceEvents").asLong()); assertTrue(facts > 0);
        assertEquals(expectedFacts.size(), facts);
        assertEquals(attempts, end.path("forecastAttempts").asLong());
        assertEquals(day >= 2, attempts > 0); assertFalse(end.path("terminalPrefixCertified").asBoolean());
        assertEquals(result.sessions().get(day).receipt().committedChanges(), end.path("receipt").path("committedChanges").asLong());
        if (day == 2) assertRealEvidenceCorruptionsRejected(file, day, registered, rows, repairNeeded);
    }

    /**
     * Corrupt copies of the two real day-2 streams already produced above. No extra
     * Controller/QSim day is run, no synthetic decision/application is inserted, and
     * neither the original JSONL nor the native observer/event inputs are modified.
     * Every mutation must fail for its intended link, not merely for a broken count.
     */
    private static void assertRealEvidenceCorruptionsRejected(Path original, int day,
            DynamicMixedDispatchValidator.Inputs registered, List<JsonNode> rows, boolean repair) throws Exception {
        byte[] originalBytes = Files.readAllBytes(original);
        Path root = original.getParent().getParent();
        Path copies = Files.createDirectory(root.resolve("dispatch-corruption-checks-day-" + day));
        var validator = new DynamicMixedDispatchValidator();
        Path exactCopy = copies.resolve("unchanged.jsonl"); Files.copy(original, exactCopy);
        var exactReport = validator.validate(root, exactCopy, day, registered);
        assertTrue(exactReport.valid(), exactReport.errors().toString());
        // Also establish that the JSON tree/write round trip itself is not a failure source.
        Path rewrittenCopy = copies.resolve("unchanged-json-roundtrip.jsonl");
        Files.write(rewrittenCopy, rows.stream().map(JsonNode::toString).toList());
        var rewrittenReport = validator.validate(root, rewrittenCopy, day, registered);
        assertTrue(rewrittenReport.valid(), rewrittenReport.errors().toString());

        int attempt = evidenceIndex(rows, row -> row.path("type").asText().equals("forecast_attempt"));
        JsonNode source = rows.get(attempt).path("payload").path("source");
        assertTrue(rows.subList(0, attempt).stream().anyMatch(row -> row.path("payload").path("source").equals(source)),
                "Same-view mutations require a preceding real record of exactly this source");
        var cases = new ArrayList<EvidenceCorruption>();
        String sameView = "Same closed view changed its source metadata";
        cases.add(new EvidenceCorruption("same-view-prefix", copy -> evidenceSource(copy, attempt).put("closedPrefixSha256", "0".repeat(64)), sameView));
        cases.add(new EvidenceCorruption("same-view-cutoff", copy -> evidenceSource(copy, attempt).put("closedThroughSeconds", source.path("closedThroughSeconds").asDouble() + 0.25), sameView));
        cases.add(new EvidenceCorruption("same-view-count", copy -> evidenceSource(copy, attempt).put("closedEventCount", source.path("closedEventCount").asLong() + 1), sameView));
        cases.add(new EvidenceCorruption("same-view-clock", copy -> evidenceSource(copy, attempt).put("decisionTimeSeconds", source.path("decisionTimeSeconds").asDouble() + 0.25), sameView));
        cases.add(new EvidenceCorruption("same-view-version", copy -> ((ObjectNode) evidenceSource(copy, attempt).get("commitmentVersion"))
                .put("revision", source.path("commitmentVersion").path("revision").asLong() + 1), sameView));

        int later = -1; JsonNode previous = null, prior = null;
        for (int i = 0; i < rows.size(); i++) {
            JsonNode current = rows.get(i).path("payload").path("source");
            if (!current.isObject()) continue;
            if (previous != null && current.path("viewSequence").asLong() > previous.path("viewSequence").asLong()
                    && previous.path("closedThroughSeconds").isNumber() && previous.path("closedThroughSeconds").asDouble() > 0
                    && previous.path("closedEventCount").asLong() > 0 && previous.path("commitmentVersion").path("revision").asLong() > 0) {
                later = i; prior = previous; break;
            }
            previous = current;
        }
        assertTrue(later >= 0, "Require a real later source with known positive count/watermark/version; never silently skip the checks");
        final int laterIndex = later; final JsonNode priorSource = prior;
        cases.add(new EvidenceCorruption("later-view-revision-regression", copy -> ((ObjectNode) evidenceSource(copy, laterIndex).get("commitmentVersion"))
                .put("revision", priorSource.path("commitmentVersion").path("revision").asLong() - 1), "Source ledger epoch/revision differs or regressed"));
        cases.add(new EvidenceCorruption("later-view-watermark-regression", copy -> evidenceSource(copy, laterIndex)
                .put("closedThroughSeconds", priorSource.path("closedThroughSeconds").asDouble() - 0.25), "Future/regressed closed prefix"));
        cases.add(new EvidenceCorruption("later-view-count-regression", copy -> evidenceSource(copy, laterIndex)
                .put("closedEventCount", priorSource.path("closedEventCount").asLong() - 1), "Closed prefix count regressed"));
        cases.add(new EvidenceCorruption("later-view-clock-regression", copy -> evidenceSource(copy, laterIndex)
                .put("decisionTimeSeconds", priorSource.path("decisionTimeSeconds").asDouble() - 0.25), "Regressed evidence source"));
        int accepted = evidenceIndex(rows, row -> row.path("type").asText().equals("accepted_commitment"));
        int afterAccepted = -1;
        for (int i = accepted + 1; i < rows.size(); i++) if (rows.get(i).path("payload").path("source").isObject()) { afterAccepted = i; break; }
        assertTrue(afterAccepted >= 0, "A real post-commit source must be present");
        final int afterAcceptedIndex = afterAccepted;
        long preCommitRevision = rows.get(accepted).path("payload").path("sourceVersion").path("revision").asLong();
        assertTrue(rows.get(afterAccepted).path("payload").path("source").path("commitmentVersion").path("revision").asLong() > preCommitRevision);
        cases.add(new EvidenceCorruption("source-forgets-accepted-revision", copy -> ((ObjectNode) evidenceSource(copy, afterAcceptedIndex).get("commitmentVersion"))
                .put("revision", preCommitRevision), "Source ledger epoch/revision differs or regressed"));

        int application = evidenceIndex(rows, row -> row.path("type").asText().equals("native_application")
                && row.path("payload").path("status").asText().equals("COMMITTED_APPLIED"));
        JsonNode nativeApplication = rows.get(application).path("payload");
        assertTrue(nativeApplication.path("refreshedTrips").size() > 0);
        assertEquals("CURRENT_ACTIVITY_CHANGED", nativeApplication.path("refreshedTrips").get(0).path("outcome").asText());
        assertTrue(nativeApplication.path("requeuedDriverIds").size() > 0, "These single-work micro drivers really required rescheduling");
        cases.add(new EvidenceCorruption("missing-native-refresh", copy -> ((ArrayNode) evidencePayload(copy, application).get("refreshedTrips")).removeAll(), "Incomplete refreshed native trip coverage"));
        cases.add(new EvidenceCorruption("duplicate-native-refresh", copy -> {
            var values = (ArrayNode) evidencePayload(copy, application).get("refreshedTrips"); values.add(values.get(0).deepCopy());
        }, "Incomplete refreshed native trip coverage"));
        cases.add(new EvidenceCorruption("refresh-wrong-trip", copy -> ((ObjectNode) firstRefresh(copy, application).get("trip")).put("departure", "not-the-committed-trip"), "Native refresh trip/version mismatch"));
        cases.add(new EvidenceCorruption("refresh-wrong-revision", copy -> ((ObjectNode) firstRefresh(copy, application).get("acceptedVersion"))
                .put("revision", nativeApplication.path("refreshedTrips").get(0).path("acceptedVersion").path("revision").asLong() + 1), "Native refresh trip/version mismatch"));
        cases.add(new EvidenceCorruption("refresh-wrong-epoch", copy -> ((ObjectNode) firstRefresh(copy, application).get("acceptedVersion"))
                .put("epoch", nativeApplication.path("refreshedTrips").get(0).path("acceptedVersion").path("epoch").asLong() + 1), "Native refresh trip/version mismatch"));
        cases.add(new EvidenceCorruption("refresh-missing-version", copy -> firstRefresh(copy, application).remove("acceptedVersion"), "Missing field acceptedVersion"));
        cases.add(new EvidenceCorruption("refresh-wrong-driver", copy -> firstRefresh(copy, application).put("driverId", "invented-driver"), "Native requeue list differs"));
        cases.add(new EvidenceCorruption("missing-native-requeue", copy -> ((ArrayNode) evidencePayload(copy, application).get("requeuedDriverIds")).removeAll(), "Native requeue list differs"));
        cases.add(new EvidenceCorruption("duplicate-native-requeue", copy -> {
            var values = (ArrayNode) evidencePayload(copy, application).get("requeuedDriverIds"); values.add(values.get(0).deepCopy());
        }, "Native requeue list differs"));
        cases.add(new EvidenceCorruption("foreign-native-requeue", copy -> ((ArrayNode) evidencePayload(copy, application).get("requeuedDriverIds"))
                .set(0, new ObjectMapper().getNodeFactory().textNode("unrelated-driver")), "Native requeue list differs"));
        cases.add(new EvidenceCorruption("false-future-only", copy -> firstRefresh(copy, application).put("outcome", "FUTURE_ONLY"), "Non-rescheduled refresh changed a deadline"));
        cases.add(new EvidenceCorruption("unchanged-deadline-claimed-requeued", copy -> firstRefresh(copy, application).set("effectiveDeadlineSeconds",
                firstRefresh(copy, application).get("previousDeadlineSeconds").deepCopy()), "Invalid changed native deadline"));
        int finalApplication = application;
        for (int i = application + 1; i < rows.size(); i++) if (rows.get(i).path("type").asText().equals("native_application")
                && rows.get(i).path("payload").path("status").asText().equals("COMMITTED_APPLIED")) finalApplication = i;
        final int deadlineApplication = finalApplication;
        JsonNode finalRefresh = rows.get(deadlineApplication).path("payload").path("refreshedTrips").get(0);
        assertEquals("CURRENT_ACTIVITY_CHANGED", finalRefresh.path("outcome").asText());
        cases.add(new EvidenceCorruption("refreshed-deadline-disagrees-with-actual-start", copy -> firstRefresh(copy, deadlineApplication)
                .put("effectiveDeadlineSeconds", finalRefresh.path("effectiveDeadlineSeconds").asDouble() + 1),
                "Native current activity did not execute on its refreshed deadline step"));

        JsonNode refreshedTrip = nativeApplication.path("refreshedTrips").get(0).path("trip");
        int startRow = -1, startEvent = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).path("type").asText().equals("closed_service_facts")) continue;
            JsonNode facts = rows.get(i).path("payload").path("events");
            for (int j = 0; j < facts.size(); j++) {
                JsonNode fact = facts.get(j), attributes = fact.path("attributes");
                if (fact.path("eventClass").asText().equals(TransitDriverStartsEvent.class.getName())
                        && attributes.path("transitLineId").equals(refreshedTrip.path("line"))
                        && attributes.path("transitRouteId").equals(refreshedTrip.path("route"))
                        && attributes.path("departureId").equals(refreshedTrip.path("departure"))) { startRow = i; startEvent = j; }
            }
        }
        assertTrue(startRow >= 0, "The applied native trip must actually have a copied start event");
        final int actualStartRow = startRow, actualStartEvent = startEvent;
        cases.add(new EvidenceCorruption("actual-start-driver-disagrees", copy -> ((ObjectNode) evidencePayload(copy, actualStartRow)
                .path("events").get(actualStartEvent).get("attributes")).put("driverId", "different-actual-driver"), "Refreshed driver differs from native start event"));

        int decision = evidenceIndex(rows, row -> row.path("type").asText().equals(repair ? "repair_decision" : "rolling_decision")
                && row.path("payload").path("outcome").asText().equals(repair ? "REPAIR_PROPOSED" : "PROPOSED"));
        String originalProjection = repair ? "originalProjection" : "noActionProjection";
        String selectedProjection = repair ? "repairProjection" : "selectedProjection";
        String substituted = repair ? "Repair substituted its recorded forecasts" : "Rolling decision substituted its recorded forecasts";
        for (String field : List.of(originalProjection, selectedProjection)) {
            cases.add(new EvidenceCorruption("decision-substitutes-" + field, copy -> {
                var projection = (ObjectNode) evidencePayload(copy, decision).get(field);
                projection.put("forecastEndSeconds", projection.path("forecastEndSeconds").asDouble() + 1);
            }, substituted));
        }
        if (repair) {
            cases.add(new EvidenceCorruption("repair-increment-scalar-substitution", copy -> evidencePayload(copy, decision)
                    .put("meanPassengerWaitingIncrementSeconds", rows.get(decision).path("payload").path("meanPassengerWaitingIncrementSeconds").asDouble() - 1),
                    "Repair waiting-increment scalar differs from recorded projections"));
        } else {
            JsonNode selected = rows.get(decision).path("payload").path(selectedProjection);
            JsonNode noAction = rows.get(decision).path("payload").path(originalProjection);
            assertNotEquals(noAction, selected, "The real rolling proposal must have a genuinely different predicted outcome");
            // Both summaries are real and from the same source/cohort, but the wrong candidate is not interchangeable.
            cases.add(new EvidenceCorruption("selected-forecast-swapped-with-real-no-action", copy -> evidencePayload(copy, decision)
                    .set(selectedProjection, noAction.deepCopy()), substituted));
            cases.add(new EvidenceCorruption("no-action-swapped-with-real-selected", copy -> evidencePayload(copy, decision)
                    .set(originalProjection, selected.deepCopy()), substituted));
        }
        cases.add(new EvidenceCorruption("forecast-candidate-hash-substitution", copy -> evidencePayload(copy, attempt)
                .put("candidateSha256", "0".repeat(64)), "Candidate full-plan digest mismatch"));

        assertEquals(repair ? 28 : 29, cases.size(), "Do not silently lose a targeted corruption check");
        for (var corruption : cases) {
            var copy = new ArrayList<JsonNode>(); rows.forEach(row -> copy.add(row.deepCopy()));
            corruption.mutation().accept(copy);
            assertNotEquals(rows, copy, corruption.name() + " must actually change the real evidence");
            Path file = copies.resolve(corruption.name() + ".jsonl");
            Files.write(file, copy.stream().map(JsonNode::toString).toList());
            var rejected = validator.validate(root, file, day, registered);
            assertFalse(rejected.valid(), corruption.name() + " was incorrectly accepted");
            assertTrue(rejected.errors().stream().anyMatch(error -> error.contains(corruption.expectedReason())),
                    corruption.name() + " failed for an unrelated reason: " + rejected.errors());
        }
        var trailing = new ArrayList<>(Files.readAllLines(original)); trailing.set(0, trailing.getFirst() + " {}");
        Path trailingFile = copies.resolve("trailing-json-on-complete-real-day.jsonl"); Files.write(trailingFile, trailing);
        var trailingReport = validator.validate(root, trailingFile, day, registered);
        assertFalse(trailingReport.valid());
        assertTrue(trailingReport.errors().stream().anyMatch(error -> error.contains("Trailing token")), trailingReport.errors().toString());
        assertArrayEquals(originalBytes, Files.readAllBytes(original), "Never rewrite the original native evidence stream");
        assertTrue(validator.validate(root, original, day, registered).valid(), "Original evidence must still pass after all copy mutations");
    }

    private record EvidenceCorruption(String name, Consumer<List<JsonNode>> mutation, String expectedReason) { }
    private static ObjectNode evidencePayload(List<JsonNode> rows, int index) { return (ObjectNode) rows.get(index).get("payload"); }
    private static ObjectNode evidenceSource(List<JsonNode> rows, int index) { return (ObjectNode) evidencePayload(rows, index).get("source"); }
    private static ObjectNode firstRefresh(List<JsonNode> rows, int application) {
        return (ObjectNode) evidencePayload(rows, application).path("refreshedTrips").get(0);
    }
    private static int evidenceIndex(List<JsonNode> rows, Predicate<JsonNode> matches) {
        for (int i = 0; i < rows.size(); i++) if (matches.test(rows.get(i))) return i;
        throw new AssertionError("Required actual evidence record is absent; corruption coverage cannot be skipped");
    }

    private static void assertClosedEvidence(Run result, int day) {
        var session = result.sessions().get(day);
        var frames = result.audit().closedEvidence.get(day);
        assertEquals(session.receipt().closedViewCount(), frames.size());
        long sequence = 0; String priorHash = null;
        for (int index = 0; index < frames.size(); index++) {
            var frame = frames.get(index); var view = frame.view(); var commitments = frame.commitmentsBeforePolicy();
            assertEquals(index + 1, view.viewSequence()); assertEquals(day, view.iteration());
            assertEquals(view.iteration(), commitments.iteration()); assertEquals(view.epoch(), commitments.version().epoch());
            assertEquals(view.decisionTimeSeconds(), commitments.nowSeconds(), 0);
            if (priorHash != null) assertEquals(priorHash, view.precedingClosedPrefixSha256());
            for (var received : view.newlyClosedEvents()) {
                assertEquals(++sequence, received.closedSequence());
                assertTrue(received.occurredAtSeconds() <= view.closedThroughSeconds().orElseThrow());
                assertTrue(result.audit().events(day).contains(new NativeEvent(received.eventClass(),
                        received.occurredAtSeconds(), received.attributes())), "Closed audit cannot invent a native event");
            }
            assertEquals(sequence, view.closedEventCount()); priorHash = view.closedPrefixSha256();
        }
        var close = session.observationCloseReceipt();
        assertEquals(sequence, close.closedEvents());
        assertEquals(close.receivedEvents(), close.closedEvents() + close.unpublishedEvents());
        assertFalse(close.terminalPrefixCertified());
        assertEquals(session.receipt().committedChanges(), session.commitmentHistory().stream()
                .mapToLong(record -> record.changes().size()).sum());
        assertThrows(UnsupportedOperationException.class, () -> session.commitmentHistory().clear());
        assertThrows(UnsupportedOperationException.class, () -> frames.getFirst().view().newlyClosedEvents().clear());
    }

    @Test void realControllerPreservesPassiveEquivalenceAndRunsDynamicMixedControlAfterInnovationCloses() throws Exception {
        Run mixed = run("mixed", new DynamicMixedDesign(DynamicMixedDesign.Arm.MIXED, PROTOCOL, false, new Limits(25, 25, 5)));
        Run off = run("off", new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED, PROTOCOL, false, new Limits(25, 25, 5)));
        Run zero = run("zero", new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED, PROTOCOL, true, new Limits(0, 0, 5)));
        Run active = run("active", new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED, PROTOCOL, true, new Limits(25, 25, 5)));

        for (Run passive : List.of(mixed, off, zero)) {
            assertTrue(passive.policies().isEmpty(), "Passive paths must not construct any policy, including common history");
            for (int day = 0; day <= 5; day++) {
                assertPassiveEventsEquivalent(mixed.audit().events(day), passive.audit().events(day),
                        "Passive native event content and physical/score order in day " + day);
                assertEquals(mixed.audit().ended.get(day).memory(), passive.audit().ended.get(day).memory(),
                        "Selected plan, retained plans and scores in day " + day);
                assertEquals(mixed.audit().before.get(day), passive.audit().before.get(day));
                var session = passive.sessions().get(day);
                assertEquals(0, session.receipt().controlledDrivers());
                assertEquals(2, session.receipt().nativeDrivers());
                assertEquals(0, session.receipt().policyCalls());
                assertEquals(0, session.receipt().committedChanges());
                assertTrue(session.applications().isEmpty());
                assertEquals(100, driverStart(passive.audit(), day, VEHICLE_B), 0);
                assertEquals(110, firstStopDeparture(passive.audit(), day, VEHICLE_B), 0);
            }
            for (String file : ARTIFACT_FILES) assertEquals(withoutArm(mixed.artifacts().get(file)),
                    withoutArm(passive.artifacts().get(file)), "Every scientific artifact field except arm: " + file);
        }
        for (Run result : List.of(mixed, off, zero, active)) {
            assertSixDayLifecycle(result);
            assertScientificArtifacts(result);
        }
        assertEquals(Set.of(2, 3, 4, 5), active.policies().keySet());
        for (int day = 0; day <= 1; day++) {
            assertEquals(mixed.audit().events(day), active.audit().events(day), "Exact shared native history before activation");
            assertEquals(mixed.audit().ended.get(day).memory(), active.audit().ended.get(day).memory());
            assertEquals(0, active.sessions().get(day).receipt().controlledDrivers());
            for (String file : ARTIFACT_FILES) assertEquals(withoutArm(iterationRows(mixed.artifacts().get(file), day)),
                    withoutArm(iterationRows(active.artifacts().get(file), day)), "Common-history artifact " + file + "/" + day);
        }
        for (int day = 2; day <= 5; day++) {
            var session = active.sessions().get(day);
            var policy = active.policies().get(day);
            assertTrue(session.phase().controlEligible());
            assertEquals(day < 4, session.phase().passengerInnovationEnabled());
            assertEquals(2, session.receipt().controlledDrivers());
            assertEquals(0, session.receipt().nativeDrivers());
            assertEquals(session.receipt().closedViewCount(), session.receipt().policyCalls());
            assertEquals(session.receipt().policyCalls(), policy.calls);
            assertTrue(policy.initialUnknown, "Every day starts without yesterday's observed riders/orders/services");
            assertTrue(policy.secondUnknown, "Default event-worker path retains the explicit two-step closure lag");
            assertEquals(new Proposal(52, 50, 50, 30), policy.proposal);
            assertEquals(1, session.receipt().committedChanges());
            assertEquals(1, session.applications().size());
            var application = session.applications().getFirst();
            assertEquals(ControlledTransitDispatchBridge.Status.COMMITTED_APPLIED, application.status());
            assertEquals(Map.of(SECOND, 80.0), application.batch().proposedDepartures());
            assertEquals(52, application.batch().decisionTimeSeconds(), 0);
            assertEquals(1, application.refreshedTrips().size());
            assertEquals(1, application.requeuedDriverIds().size());
            assertTrue(application.constraints().orElseThrow().candidate().satisfiesRegisteredConstraints());
            assertEquals(80, driverStart(active.audit(), day, VEHICLE_B), 0);
            assertEquals(90, firstStopDeparture(active.audit(), day, VEHICLE_B), 0);
            assertEquals(30, firstStopDeparture(active.audit(), day, VEHICLE_A), 0);
            assertEquals(80, session.snapshot().commitments().get(SECOND), 0);
            assertTrue(passengerWait(active.audit(), day) < passengerWait(mixed.audit(), day));
            assertTrue(active.audit().ended.get(day).score() > mixed.audit().ended.get(day).score(),
                    "Toy earlier service should improve its explicitly measured waiting cost, not an asserted general T benefit");
        }
    }

    @Test void passiveComparisonOnlyAllowsSameTimeDerivedScorePlacement() {
        var departure = new NativeEvent(PersonDepartureEvent.class.getName(), 122,
                Map.of("type", "departure", "time", "122.0", "person", PASSENGER, "vehicle", VEHICLE_B, "legMode", "pt"));
        var arrival = new NativeEvent(PersonArrivalEvent.class.getName(), 122,
                Map.of("type", "arrival", "time", "122.0", "person", PASSENGER, "vehicle", VEHICLE_B, "legMode", "pt"));
        var score = new NativeEvent(PersonScoreEvent.class.getName(), 122,
                Map.of("type", "personScore", "time", "122.0", "person", PASSENGER,
                        "kind", CO_PRESENCE, "amount", "-0.21"));
        List<NativeEvent> raw = List.of(departure, arrival, score);
        assertDoesNotThrow(() -> assertPassiveEventsEquivalent(raw, List.of(score, departure, arrival), "Same second"));
        assertEquals(List.of(departure, arrival, score), raw, "Comparison must not reorder its input");

        for (var change : List.of(Map.entry("amount", "-0.22"), Map.entry("kind", "another-kind"),
                Map.entry("time", "123.0"))) {
            var attributes = new LinkedHashMap<>(score.attributes());
            attributes.put(change.getKey(), change.getValue());
            var changed = new NativeEvent(score.eventClass(), change.getKey().equals("time") ? 123 : 122,
                    Map.copyOf(attributes));
            assertThrows(AssertionError.class, () -> assertPassiveEventsEquivalent(raw,
                    List.of(departure, arrival, changed), change.getKey()));
        }
        assertThrows(AssertionError.class, () -> assertPassiveEventsEquivalent(raw, List.of(departure, arrival), "Missing score"));
        assertThrows(AssertionError.class, () -> assertPassiveEventsEquivalent(raw,
                List.of(departure, arrival, score, score), "Duplicated score"));
        assertThrows(AssertionError.class, () -> assertPassiveEventsEquivalent(raw,
                List.of(arrival, departure, score), "Changed same-person and same-vehicle physical order"));
        var next = new NativeEvent(PersonArrivalEvent.class.getName(), 123,
                Map.of("type", "arrival", "time", "123.0", "person", FREIGHT));
        assertThrows(AssertionError.class, () -> assertPassiveEventsEquivalent(
                List.of(departure, score, next), List.of(departure, next, score), "Score moved across seconds"));
    }

    private static void assertSixDayLifecycle(Run result) {
        assertEquals(Set.of(0, 1, 2, 3, 4, 5), result.sessions().keySet());
        var distinctSessions = Collections.newSetFromMap(new IdentityHashMap<DynamicMixedQSimModule.Session, Boolean>());
        distinctSessions.addAll(result.sessions().values());
        assertEquals(6, distinctSessions.size());
        assertEquals(List.of(2, 3), result.audit().innovated,
                "Fixed test seed/explicit dominant innovative weight must exercise native copying only in the reopened window");
        assertEquals(5, result.audit().selections.size(), "Real native plan selection should run once in each subsequent day");
        for (int day = 0; day <= 5; day++) {
            var session = result.sessions().get(day);
            var receipt = session.receipt();
            assertEquals(day, session.iteration());
            assertEquals(day, session.initialCommitments().iteration());
            assertEquals(1, session.initialCommitments().version().epoch(),
                    "Each new Session has its own ledger, so numeric epoch alone is not a global day identity");
            assertEquals(BASELINE, session.initialCommitments().commitments());
            assertTrue(session.initialCommitments().startedAtSeconds().isEmpty());
            assertTrue(session.initialCommitments().lockedTrips().isEmpty());
            assertEquals(7, receipt.qsimRunnerCount());
            assertEquals("org.matsim.core.events.SimStepParallelEventsManagerImpl", receipt.eventManagerClass());
            assertEquals(2, receipt.closureLagSteps());
            assertTrue(receipt.preparedInputVerified());
            assertTrue(receipt.nativeEventsProcessingFinished());
            assertFalse(receipt.poisoned());
            assertFalse(receipt.terminalPrefixCertified());
            assertFalse(receipt.registrationSealed());
            assertFalse(receipt.allProducersCertified());
            assertEquals(day >= 2, receipt.phase().freightTreatmentActive());
            assertEquals(day >= 4, receipt.phase().reporting());
            assertEquals(List.of(1.0, day == 0 || day == 2 || day == 3 ? INNOVATION_WEIGHT : 0.0),
                    result.audit().weights.get(day));
            assertEquals(List.of(1.0), result.audit().freightWeights.get(day));
            assertEquals(BASELINE, result.audit().supply.get(day), "Passenger published schedule never follows accepted overrides");
            assertEquals(200, result.audit().ended.get(day).restoredHorizon(), 0);
            List<NativeEvent> passengerBoards = personEvents(result.audit(), day, PersonEntersPtVehicleEvent.class, PASSENGER);
            assertEquals(1, passengerBoards.size());
            assertEquals(VEHICLE_B, passengerBoards.getFirst().attributes().get("vehicle"));
            assertEquals(1, personEvents(result.audit(), day, PersonLeavesPtVehicleEvent.class, PASSENGER).size());
            assertTrue(result.audit().events(day).stream().noneMatch(event -> event.eventClass().equals(PersonStuckEvent.class.getName())));
            List<NativeEvent> freightBoards = personEvents(result.audit(), day, PersonEntersPtVehicleEvent.class, FREIGHT);
            List<NativeEvent> exposure = personEvents(result.audit(), day, PersonScoreEvent.class, PASSENGER);
            if (day < 2) {
                assertTrue(freightBoards.isEmpty());
                assertTrue(exposure.isEmpty());
                assertTrue(session.closedSnapshot().releases().knownOrders().isEmpty());
                assertEquals(Set.of(PASSENGER), session.inputReport().expectedPopulationIds());
            } else {
                assertEquals(1, freightBoards.size());
                assertEquals(VEHICLE_B, freightBoards.getFirst().attributes().get("vehicle"));
                assertEquals(1, personEvents(result.audit(), day, PersonLeavesPtVehicleEvent.class, FREIGHT).size());
                assertEquals(1, exposure.size());
                assertEquals(CO_PRESENCE, exposure.getFirst().attributes().get(PersonScoreEvent.ATTRIBUTE_KIND));
                assertTrue(Double.parseDouble(exposure.getFirst().attributes().get(PersonScoreEvent.ATTRIBUTE_AMOUNT)) < 0);
                assertEquals(1, result.audit().workerExposureCounts.getOrDefault(day, 0));
                assertEquals(Map.of(FREIGHT, 50.0), session.closedSnapshot().releases().knownOrders().get(FREIGHT).releasedUnits());
                assertEquals(Set.of(PASSENGER, FREIGHT), session.inputReport().expectedPopulationIds());
            }
            double eventScore = exposure.stream().mapToDouble(event -> Double.parseDouble(
                    event.attributes().get(PersonScoreEvent.ATTRIBUTE_AMOUNT))).sum();
            assertEquals(-passengerLegTime(result.audit(), day) + eventScore, result.audit().ended.get(day).score(), 1e-9,
                    "Native Controller score equals experienced leg time (PT already includes waiting) plus exposure, exactly once");
            if (day < 5) {
                EndState previous = result.audit().ended.get(day);
                int nextDay = day + 1;
                Selection selectedNextDay = result.audit().selections.stream().filter(value -> value.iteration() == nextDay)
                        .findFirst().orElseThrow();
                assertTrue(selectedNextDay.scores().stream().anyMatch(value -> value.plan() == previous.selected()
                                && value.score() == previous.score()),
                        "The next native selector reads the preceding day's actual scored plan; Session must not erase memory");
            }
        }
    }

    private static void assertScientificArtifacts(Run result) throws IOException {
        var schema = new ObjectMapper().readTree(result.output().resolve("artifact_schema.json").toFile());
        assertEquals(ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA, schema.path("schema_version").asText());
        assertEquals(DynamicMixedDesign.SCHEMA_ID, schema.path("design_family").asText());
        assertEquals("scientific-artifact-context-not-experiment-provenance", schema.path("identity_scope").asText());
        assertEquals(EXPERIMENT_ID, schema.path("experiment_id").asText());
        assertEquals(BLOCK_ID, schema.path("block_id").asText());
        assertEquals(result.design().arm().name(), schema.path("arm").asText());
        assertEquals(0, schema.path("first_iteration").asInt(-1));
        assertEquals(2, schema.path("activation_iteration").asInt(-1));
        assertEquals(4, schema.path("innovation_off_iteration").asInt(-1));
        assertEquals(5, schema.path("final_iteration").asInt(-1));
        assertEquals(4, schema.path("report_first_iteration").asInt(-1));
        assertEquals(5, schema.path("report_last_iteration").asInt(-1));
        assertEquals(1, schema.path("expected_order_count").asInt(-1));
        assertEquals(200, schema.path("behavioural_horizon_seconds").asDouble(), 0);
        assertEquals(240, schema.path("execution_horizon_seconds").asDouble(), 0);
        assertEquals(result.design().controlEnabled(), schema.path("control_enabled").asBoolean());
        assertEquals(result.design().limits().maximumAdvanceSeconds(), schema.path("maximum_advance_seconds").asDouble(), 0);
        assertEquals(result.design().limits().maximumDelaySeconds(), schema.path("maximum_delay_seconds").asDouble(), 0);
        assertEquals(result.design().limits().lockWindowSeconds(), schema.path("lock_window_seconds").asDouble(), 0);
        assertEquals("native co-transport treatment/sham phase, not actual dispatch execution",
                schema.path("phase_active_semantics").asText());
        assertEquals("configured design only; actual decisions and execution require separate evidence",
                schema.path("control_metadata_semantics").asText());
        assertEquals("REPORTING_WINDOW", schema.path("passenger_exposure_mode").asText());
        assertFalse(schema.path("detailed_vehicle_observations").asBoolean());

        for (String file : ARTIFACT_FILES) {
            assertTrue(Files.isRegularFile(result.output().resolve(file)));
            for (var row : result.artifacts().get(file)) {
                assertEquals(EXPERIMENT_ID, row.get("experiment_id"));
                assertEquals(BLOCK_ID, row.get("block_id"));
                assertEquals(result.design().arm().name(), row.get("arm"));
                String algorithm = file.equals(PassengerPlanFingerprint.FILE_NAME) ? PassengerPlanFingerprint.SCHEMA_VERSION
                        : file.equals(PassengerScientificFingerprint.FILE_NAME) ? PassengerScientificFingerprint.SCHEMA_VERSION
                        : ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA;
                assertEquals(algorithm, row.get("schema_version"));
                if (file.equals(PassengerPlanFingerprint.FILE_NAME) || file.equals(PassengerScientificFingerprint.FILE_NAME))
                    assertEquals(DynamicMixedDesign.SCHEMA_ID, row.get("design_family"));
            }
        }
        for (String file : List.of("iteration_summary.csv", "order_ledger.csv", PassengerPlanFingerprint.FILE_NAME,
                PassengerScientificFingerprint.FILE_NAME)) assertEquals(List.of("0", "1", "2", "3", "4", "5"),
                result.artifacts().get(file).stream().map(row -> row.get("iteration")).toList(), "Complete ordered rows: " + file);
        assertEquals(List.of("4", "5"), result.artifacts().get("passenger_exposure.csv.gz").stream()
                .map(row -> row.get("iteration")).toList(), "Person-level exposure follows the explicit reporting window");
        assertTrue(result.artifacts().get("denial_ledger.csv").isEmpty(), "No native denial occurred in this uncongested fixture");

        for (int day = 0; day <= 5; day++) {
            var summary = onlyIterationRow(result, "iteration_summary.csv", day);
            var order = onlyIterationRow(result, "order_ledger.csv", day);
            assertEquals(result.audit().ended.get(day).score(), artifactNumber(summary, "selected_plan_passenger_score"), 1e-9);
            assertEquals("1", summary.get("passenger_count"));
            assertEquals("1", summary.get("scored_passenger_count"));
            assertEquals("1", summary.get("freight_order_count"), "All registered orders are reported even before treatment");
            assertEquals("1", summary.get("passenger_boardings"));
            assertEquals("0", summary.get("passenger_denied_boardings"));
            assertEquals("0", summary.get("freight_denied_boardings"));
            assertEquals("0", summary.get("denial_detail_events"));
            assertEquals(Boolean.toString(day >= 2), summary.get("phase_active"), "Existing treatment-phase field is not a control flag");
            assertEquals(day >= 2 ? "1" : "0", summary.get("phase_activation_count"));
            assertEquals(day >= 2 ? "2" : "-1", summary.get("phase_activation_iteration"));
            assertEquals("true", summary.get("exposure_event_state_valid"));
            assertEquals("0", summary.get("unfinished_passenger_rides"));
            assertEquals("0", summary.get("unfinished_freight_units_onboard"));
            assertEquals(0, artifactNumber(summary, "raw_physical_exposure"), 0);
            assertEquals(0, artifactNumber(summary, "applied_physical_penalty"), 0);
            assertEquals(FREIGHT, order.get("order_id"));
            assertEquals(50, artifactNumber(order, "release_time_seconds"), 0);
            assertEquals("1", order.get("number_of_units"));
            assertEquals(IsmtRunArtifactWriter.UNAVAILABLE_DEPARTURE, order.get("accessible_departure_status"));
            for (String unavailable : List.of("first_accessible_departure_time_seconds", "schedule_wait_minutes",
                    "additional_admission_delay_minutes")) assertEquals("", order.get(unavailable));

            var scores = personEvents(result.audit(), day, PersonScoreEvent.class, PASSENGER);
            double applied = -scores.stream().mapToDouble(event -> Double.parseDouble(event.attributes().get("amount"))).sum();
            assertEquals(applied, artifactNumber(summary, "applied_co_presence_penalty"), 1e-12);
            assertEquals(applied, artifactNumber(summary, "co_presence_penalty"), 1e-12);
            assertEquals(artifactNumber(summary, "raw_freight_copresence_exposure") * 6, applied, 1e-12);
            if (day < 2) {
                assertEquals("0", summary.get("freight_boardings"));
                assertEquals("0", summary.get("completed_orders"));
                assertEquals("0", order.get("units_boarded"));
                assertEquals("0", order.get("units_delivered"));
                assertEquals("false", order.get("completed"));
                for (String absent : List.of("first_unit_board_time_seconds", "last_unit_board_time_seconds",
                        "first_board_wait_minutes", "completion_time_seconds")) assertEquals("", order.get(absent));
                assertEquals(0, applied, 0);
            } else {
                var boarding = personEvents(result.audit(), day, PersonEntersPtVehicleEvent.class, FREIGHT).getFirst();
                var alighting = personEvents(result.audit(), day, PersonLeavesPtVehicleEvent.class, FREIGHT).getFirst();
                var delivery = personEvents(result.audit(), day, ActivityStartEvent.class, FREIGHT).stream()
                        .filter(event -> "freight_delivery".equals(event.attributes().get("actType"))).toList();
                assertEquals(1, delivery.size());
                assertEquals("1", summary.get("freight_boardings"));
                for (String completed : List.of("completed_orders", "service_window_completed_orders", "eventual_completed_orders",
                        "on_time_orders")) assertEquals("1", summary.get(completed));
                assertEquals("1", order.get("units_boarded"));
                assertEquals("1", order.get("units_delivered"));
                for (String completed : List.of("completed", "completed_in_service_window", "completed_within_execution_horizon",
                        "on_time")) assertEquals("true", order.get(completed));
                assertEquals(boarding.time(), artifactNumber(order, "first_unit_board_time_seconds"), 0);
                assertEquals(boarding.time(), artifactNumber(order, "last_unit_board_time_seconds"), 0);
                assertEquals((boarding.time() - 50) / 60, artifactNumber(order, "first_board_wait_minutes"), 1e-12);
                assertEquals(delivery.getFirst().time(), artifactNumber(order, "completion_time_seconds"), 0);
                assertTrue(delivery.getFirst().time() >= alighting.time());
                assertEquals(0, artifactNumber(order, "lateness_minutes"), 0);
                assertTrue(applied > 0);
            }
            var plan = onlyIterationRow(result, PassengerPlanFingerprint.FILE_NAME, day);
            var event = onlyIterationRow(result, PassengerScientificFingerprint.FILE_NAME, day);
            assertEquals("1", plan.get("passenger_count"));
            assertTrue(plan.get("plan_sha256").matches("[a-f0-9]{64}"));
            assertTrue(event.get("event_sha256").matches("[a-f0-9]{64}"));
            assertTrue(Long.parseLong(event.get("event_count")) > 0);
            if (day >= 4) {
                var exposure = onlyIterationRow(result, "passenger_exposure.csv.gz", day);
                assertEquals(PASSENGER, exposure.get("person_id"));
                assertEquals(applied, artifactNumber(exposure, "applied_co_presence_penalty"), 1e-12);
                assertEquals(applied, artifactNumber(exposure, "total_added_penalty"), 1e-12);
                assertEquals(artifactNumber(summary, "raw_freight_copresence_exposure"),
                        artifactNumber(exposure, "raw_freight_copresence_exposure"), 0);
                assertEquals("false", exposure.get("unfinished_ride"));
            }
        }
    }

    private static double artifactNumber(Map<String, String> row, String field) {
        double value = Double.parseDouble(row.get(field));
        assertTrue(Double.isFinite(value), "Scientific artifact value must be finite: " + field);
        return value;
    }

    private static Map<String, String> onlyIterationRow(Run result, String file, int day) {
        var rows = iterationRows(result.artifacts().get(file), day);
        assertEquals(1, rows.size(), file + "/" + day);
        return rows.getFirst();
    }

    private static List<Map<String, String>> iterationRows(List<Map<String, String>> rows, int day) {
        return rows.stream().filter(row -> Integer.toString(day).equals(row.get("iteration"))).toList();
    }

    private static List<Map<String, String>> withoutArm(List<Map<String, String>> rows) {
        return rows.stream().map(row -> {
            var copy = new LinkedHashMap<>(row);
            assertNotNull(copy.remove("arm"));
            return Map.copyOf(copy);
        }).toList();
    }

    private static Map<String, List<Map<String, String>>> readArtifacts(Path output) throws IOException {
        var result = new LinkedHashMap<String, List<Map<String, String>>>();
        for (String name : ARTIFACT_FILES) {
            try (var stream = Files.newInputStream(output.resolve(name));
                 var decoded = name.endsWith(".gz") ? new GZIPInputStream(stream) : stream;
                 var reader = new InputStreamReader(decoded, StandardCharsets.UTF_8);
                 var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
                assertTrue(parser.getHeaderNames().containsAll(List.of("schema_version", "experiment_id", "block_id", "arm", "iteration")));
                var rows = new ArrayList<Map<String, String>>();
                for (var row : parser) {
                    assertEquals(parser.getHeaderNames().size(), row.size(), "Header/value width for " + name);
                    rows.add(Map.copyOf(row.toMap()));
                }
                result.put(name, List.copyOf(rows));
            }
        }
        return Map.copyOf(result);
    }

    private Run run(String name, DynamicMixedDesign design) throws Exception {
        return run(name, design, 40, null);
    }

    @FunctionalInterface private interface ConcreteFactory {
        DynamicMixedQSimModule.PolicyFactory create(Fixture fixture, DynamicMixedScenarioInputs inputs, DynamicMixedDispatchEvidence evidence);
    }

    private Run run(String name, DynamicMixedDesign design, double minimumHeadway, ConcreteFactory concreteFactory) throws Exception {
        Path root = temporary.resolve(name);
        Files.createDirectories(root);
        Path orders = root.resolve("orders.csv");
        Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
                + FREIGHT + ",00:00:50,00:03:00,0.1,1.0,standard,1,1\n");
        Fixture fixture = fixture(root.resolve("output"));
        Path baseline = root.resolve("baseline.xml");
        new TransitScheduleWriter(fixture.scenario().getTransitSchedule()).writeFile(baseline.toString());
        CoTransportSettings settings = CoTransportSettings.builder(orders).baselineTransitScheduleFile(baseline)
                .armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW).activationIteration(2).deactivationIteration(-1)
                .manageInnovationSchedule(true).innovationOffIteration(0).innovationReopenIteration(2).innovationFinalOffIteration(4)
                .freightServiceWindow(0, 180).freightShareCap(0.25).passengerLoadGate(1)
                .freightHandlingDwellSeconds(0).comfortThreshold(0.6)
                .passengerPhysicalCrowdingPenaltyPerMinute(0).passengerFreightCoPresencePenaltyPerMinute(6)
                .passengerDeniedBoardingPenalty(0).freightActivityPrefix("freight_delivery").build();
        Audit audit = new Audit(fixture.scenario(), fixture.passenger());
        var sessions = new LinkedHashMap<Integer, DynamicMixedQSimModule.Session>();
        var policies = new LinkedHashMap<Integer, ToyPolicy>();
        var assembly = concreteFactory == null ? null : DynamicMixedScenarioInputs.capture(fixture.scenario(),
                new FreightOrderRegistry(settings), settings, design, fixture.inputs(2, minimumHeadway).controlPoints(), List.of());
        var evidence = concreteFactory == null ? null : new DynamicMixedDispatchEvidence(temporary, root.resolve("dispatch"));
        var concrete = concreteFactory == null ? null : concreteFactory.create(fixture, assembly, evidence);
        Controler controller = new Controler(fixture.scenario());
        if (evidence != null) controller.addOverridingModule(evidence.module());
        controller.addOverridingModule(new DynamicMixedArtifactModule(ScientificRunContext.dynamic(design,
                EXPERIMENT_ID, BLOCK_ID, 1, 200, 240, ScientificRunContext.ExposureOutput.REPORTING_WINDOW, false)));
        controller.addOverridingModule(new DynamicMixedControllerModule(design, settings,
                day -> assembly == null ? fixture.inputs(day, minimumHeadway) : assembly.forIteration(day).qsimInputs(), day -> {
            assertTrue(design.phaseAt(day).controlEligible(), "Only active T may ask for a policy");
            if (concrete != null) return concrete.create(day);
            ToyPolicy policy = new ToyPolicy();
            assertNull(policies.put(day, policy));
            return policy;
        }, session -> {
            if (evidence != null) evidence.sessionStarted(session);
            assertNull(sessions.put(session.iteration(), session));
            assertEquals(BASELINE, session.initialCommitments().commitments());
            assertTrue(session.applications().isEmpty());
            assertEquals(240, fixture.scenario().getConfig().qsim().getEndTime().seconds(), 0,
                    "Actual QSim construction happens inside the registered execution horizon");
        }, new DynamicMixedQSimModule.AssemblyCallbacks((day, qsim) -> {
            assertTrue(QSimRuntimeInspection.isAtPreparationCheck(qsim));
            assertSame(fixture.scenario(), qsim.getScenario());
            assertTrue(audit.preparedDays.add(day), "One actual prepared callback per day");
            if (assembly != null) {
                var prepared = assembly.forIteration(day).verifyPrepared(qsim);
                assertNull(audit.preparedInputs.put(day, prepared)); evidence.prepared(day, prepared);
            }
        }, frame -> {
            int day = frame.view().iteration();
            assertTrue(audit.preparedDays.contains(day));
            audit.closedEvidence.computeIfAbsent(day, ignored -> new ArrayList<>()).add(frame);
            if (evidence != null) evidence.acceptClosed(frame);
        })));
        controller.addOverridingModule(new AbstractModule() {
            @Override public void install() {
                addEventHandlerBinding().toInstance(audit);
                addControllerListenerBinding().toInstance(audit);
                addPlanStrategyBinding(SELECT).toProvider(() -> new PlanStrategyImpl.Builder(new ObservedBest(audit)).build());
                addPlanStrategyBinding(INNOVATE).toProvider(() -> new PlanStrategyImpl.Builder(new ObservedBest(audit))
                        .addStrategyModule(new CopyObservation(audit)).build());
            }
        });
        try { controller.run(); } finally { if (evidence != null) evidence.close(); }
        assertNull(controller.getInjector().getExistingBinding(Key.get(ExperimentSpec.class)),
                "The dynamic artifact path must not forge or bind a legacy experiment specification");
        assertEquals(7, fixture.scenario().getConfig().global().getNumberOfThreads());
        assertEquals(7, fixture.scenario().getConfig().qsim().getNumberOfThreads());
        assertEquals(200, fixture.scenario().getConfig().qsim().getEndTime().seconds(), 0);
        assertEquals(1, fixture.freight().getPlans().size(), "Freight retains its single fixed prebuilt plan");
        Path output = root.resolve("output");
        return new Run(audit, Map.copyOf(sessions), Map.copyOf(policies), design, output, readArtifacts(output));
    }

    private static Fixture fixture(Path output) {
        var config = ConfigUtils.createConfig();
        config.global().setRandomSeed(1931);
        config.global().setNumberOfThreads(7);
        config.qsim().setNumberOfThreads(7);
        config.qsim().setPersonInitializedEventsSetting(QSimConfigGroup.PersonInitializedEventsSetting.all);
        config.qsim().setStartTime(0); config.qsim().setEndTime(200); config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        ConfigUtils.addOrGetModule(config, PlanAExecutionHorizonConfigGroup.class).setExecutionEndTime(240);
        config.transit().setUseTransit(true); config.transit().setUsingTransitInMobsim(true);
        config.controller().setFirstIteration(0); config.controller().setLastIteration(5);
        config.controller().setOutputDirectory(output.toAbsolutePath().toString());
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists);
        config.controller().setCompressionType(ControllerConfigGroup.CompressionType.none);
        config.controller().setWriteEventsInterval(0); config.controller().setWritePlansInterval(0);
        config.controller().setWriteTripsInterval(0); config.controller().setCreateGraphsInterval(0);
        config.controller().setDumpDataAtEnd(false);
        config.replanning().setFractionOfIterationsToDisableInnovation(Double.POSITIVE_INFINITY);
        config.replanning().setMaxAgentPlanMemorySize(10);
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName(SELECT).setWeight(1));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName(INNOVATE).setWeight(INNOVATION_WEIGHT));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setStrategyName("KeepLastSelected").setSubpopulation("freight").setWeight(1));
        config.timeAllocationMutator().setMutationRange(0).setMutationRangeStep(0);
        config.changeMode().setModes(new String[]{TransportMode.pt});
        config.scoring().setLearningRate(1); config.scoring().setPerforming_utils_hr(0);
        config.scoring().setMarginalUtlOfWaitingPt_utils_hr(-3600); config.scoring().setUtilityOfLineSwitch(0);
        config.scoring().setWriteExperiencedPlans(false);
        for (String activity : List.of("home", "work", "cargo_origin", "freight_delivery")) {
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(activity)
                    .setTypicalDuration(100).setScoringThisActivityAtAll(false));
        }
        // Real Plan-A routing must not see free walking against costly PT waiting.
        // Both native travelling and PT waiting cost one test utility unit/second.
        for (var mode : config.scoring().getModes().values()) mode.setMarginalUtilityOfTraveling(-3600)
                .setMarginalUtilityOfDistance(0).setMonetaryDistanceRate(0).setConstant(0)
                .setDailyUtilityConstant(0).setDailyMonetaryConstant(0);
        config.vspExperimental().setGeneratingBoardingDeniedEvent(true);
        Scenario scenario = ScenarioUtils.createScenario(config);
        var network = scenario.getNetwork();
        var a = NetworkUtils.createAndAddNode(network, Id.createNodeId("controller-n0"), new Coord(0, 0));
        var b = NetworkUtils.createAndAddNode(network, Id.createNodeId("controller-n1"), new Coord(100, 0));
        var c = NetworkUtils.createAndAddNode(network, Id.createNodeId("controller-n2"), new Coord(200, 0));
        Link first = NetworkUtils.createAndAddLink(network, Id.createLinkId("controller-l0"), a, b, 100, 10, 3600, 1);
        Link last = NetworkUtils.createAndAddLink(network, Id.createLinkId("controller-l1"), b, c, 100, 10, 3600, 1);
        Link back = NetworkUtils.createAndAddLink(network, Id.createLinkId("controller-return"), c, a, 200, 10, 3600, 1);
        for (Link link : List.of(first, last, back)) link.setAllowedModes(Set.of(TransportMode.car));
        var sf = scenario.getTransitSchedule().getFactory();
        var origin = sf.createTransitStopFacility(Id.create(ORIGIN, TransitStopFacility.class), first.getCoord(), false);
        var destination = sf.createTransitStopFacility(Id.create(DESTINATION, TransitStopFacility.class), last.getCoord(), false);
        origin.setLinkId(first.getId()); destination.setLinkId(last.getId());
        scenario.getTransitSchedule().addStopFacility(origin); scenario.getTransitSchedule().addStopFacility(destination);
        var originCall = sf.createTransitRouteStop(origin, 0, 10); originCall.setAwaitDepartureTime(true);
        var destinationCall = sf.createTransitRouteStop(destination, 20, 30); destinationCall.setAwaitDepartureTime(true);
        var path = RouteUtils.createLinkNetworkRouteImpl(first.getId(), last.getId());
        path.setLinkIds(first.getId(), List.of(), last.getId());
        var route = sf.createTransitRoute(Id.create(ROUTE, TransitRoute.class), path,
                List.of(originCall, destinationCall), TransportMode.pt);
        var line = sf.createTransitLine(Id.create(LINE, TransitLine.class));
        line.addRoute(route); scenario.getTransitSchedule().addTransitLine(line);
        var type = VehicleUtils.createVehicleType(Id.create("controller-type", VehicleType.class));
        type.getCapacity().setSeats(10); type.getCapacity().setStandingRoom(0);
        type.setAccessTime(0); type.setEgressTime(0); type.setMaximumVelocity(10); type.setPcuEquivalents(1);
        scenario.getTransitVehicles().addVehicleType(type);
        List<ScheduledTrip> trips = List.of(new ScheduledTrip(FIRST, VEHICLE_A, 20), new ScheduledTrip(SECOND, VEHICLE_B, 100));
        for (var trip : trips) {
            var vehicle = VehicleUtils.createVehicle(Id.createVehicleId(trip.vehicleId()), type);
            scenario.getTransitVehicles().addVehicle(vehicle);
            var departure = sf.createDeparture(Id.create(trip.key().departure(), Departure.class), trip.baselineDepartureSeconds());
            departure.setVehicleId(vehicle.getId()); route.addDeparture(departure);
        }
        Person passenger = addPerson(scenario, PASSENGER, 45, false, first, last, origin, destination, line, route);
        Person freight = addPerson(scenario, FREIGHT, 50, true, first, last, origin, destination, line, route);
        return new Fixture(scenario, passenger, freight, trips);
    }

    private static Person addPerson(Scenario scenario, String id, double release, boolean freight, Link first, Link last,
                                    TransitStopFacility origin, TransitStopFacility destination, TransitLine line, TransitRoute route) {
        var pf = scenario.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId(id));
        Plan plan = pf.createPlan();
        Activity initial = pf.createActivityFromLinkId(freight ? "cargo_origin" : "home", first.getId());
        initial.setCoord(origin.getCoord()); initial.setEndTime(release); plan.addActivity(initial);
        Leg leg = pf.createLeg(TransportMode.pt); leg.setDepartureTime(release); leg.setTravelTime(120 - release);
        var passengerRoute = new DefaultTransitPassengerRoute(origin, line, route, destination);
        passengerRoute.setBoardingTime(110); passengerRoute.setTravelTime(120 - release); passengerRoute.setDistance(200);
        leg.setRoute(passengerRoute); plan.addLeg(leg);
        Activity end = pf.createActivityFromLinkId(freight ? "freight_delivery" : "work", last.getId());
        end.setCoord(destination.getCoord()); plan.addActivity(end);
        person.addPlan(plan); person.setSelectedPlan(plan); plan.setScore(123.5);
        if (freight) PopulationUtils.putSubpopulation(person, "freight");
        scenario.getPopulation().addPerson(person);
        return person;
    }

    private record Fixture(Scenario scenario, Person passenger, Person freight, List<ScheduledTrip> trips) {
        DynamicMixedQSimModule.Inputs inputs(int day) {
            return inputs(day, 40);
        }
        DynamicMixedQSimModule.Inputs inputs(int day, double minimumHeadway) {
            var contracts = day < 2 ? List.<KnownFreightReleaseState.Contract>of() : List.of(
                    new KnownFreightReleaseState.Contract(FREIGHT, ORIGIN, DESTINATION, 50, 180,
                            List.of(FREIGHT), "cargo_origin"));
            var point = new ControlPoint(new ControlPointKey(ORIGIN, "toy-outbound"),
                    List.of(new Call(FIRST, 10), new Call(SECOND, 10)), minimumHeadway, 100, Optional.empty());
            return new DynamicMixedQSimModule.Inputs(trips, trips.stream().map(trip ->
                    new ObservedTransitServiceState.Service(trip.key(), trip.vehicleId(), List.of(ORIGIN, DESTINATION))).toList(),
                    contracts, Set.of(PASSENGER), day < 2 ? Set.of(PASSENGER) : Set.of(PASSENGER, FREIGHT),
                    List.of(point), List.of(), 0, day < 2 ? List.of() : List.of(
                            new KnownFreightJourneyState.Contract(FREIGHT, List.of(FREIGHT), "freight_delivery")));
        }
    }

    private static final class ToyPolicy implements DynamicMixedQSimModule.Policy {
        long calls;
        boolean initialUnknown, secondUnknown;
        Double observedFirstDeparture;
        Proposal proposal;
        @Override public Map<TripKey, Double> propose(ClosedCoTransportObservationState.Snapshot observed,
                                                      DispatchCommitmentState.Snapshot commitments) {
            double now = observed.position().decisionTimeSeconds();
            calls++;
            if (calls == 1) {
                initialUnknown = observed.position().closedThroughSeconds().isEmpty()
                        && observed.position().closedEventCount() == 0 && observed.services().observedServices().isEmpty()
                        && observed.releases().knownOrders().isEmpty() && observed.riders().riders().isEmpty();
                assertEquals(BASELINE, commitments.commitments());
            }
            if (now == 1) secondUnknown = observed.position().closedThroughSeconds().isEmpty();
            var first = observed.services().observedServices().get(FIRST);
            if (first != null && first.motion() == ObservedTransitServiceState.Motion.BETWEEN_STOPS
                    && first.lastObservedStop().equals(Optional.of(ORIGIN))) observedFirstDeparture = first.lastObservedAtSeconds();
            var order = observed.releases().knownOrders().get(FREIGHT);
            if (proposal != null || observedFirstDeparture == null || order == null) return Map.of();
            double cutoff = observed.position().closedThroughSeconds().orElseThrow();
            assertEquals(100, commitments.commitments().get(SECOND), 0);
            assertFalse(observed.services().observedServices().containsKey(SECOND));
            assertEquals(Map.of(FREIGHT, 50.0), order.releasedUnits());
            assertTrue(observedFirstDeparture <= cutoff && order.releasedUnits().get(FREIGHT) <= cutoff);
            assertEquals(now - 2, cutoff, 0);
            proposal = new Proposal(now, cutoff, order.releasedUnits().get(FREIGHT), observedFirstDeparture);
            return Map.of(SECOND, 80.0);
        }
    }

    private static final class ObservedBest implements PlanSelector<Plan, Person> {
        private final Audit audit;
        private final BestPlanSelector<Plan, Person> nativeSelector = new BestPlanSelector<>();
        ObservedBest(Audit audit) { this.audit = audit; }
        @Override public Plan selectPlan(HasPlansAndId<Plan, Person> person) {
            assertEquals(PASSENGER, person.getId().toString());
            List<ScoredPlan> scores = person.getPlans().stream().map(plan -> {
                assertNotNull(plan.getScore());
                return new ScoredPlan(plan, plan.getScore());
            }).toList();
            Plan selected = nativeSelector.selectPlan(person);
            audit.selections.add(new Selection(audit.iteration, scores));
            return selected;
        }
    }

    private static final class CopyObservation implements PlanStrategyModule {
        private final Audit audit;
        private volatile int iteration;
        CopyObservation(Audit audit) { this.audit = audit; }
        @Override public void prepareReplanning(ReplanningContext context) { iteration = context.getIteration(); }
        @Override public void handlePlan(Plan plan) {
            assertEquals(PASSENGER, plan.getPerson().getId().toString());
            assertTrue(plan.getPerson().getPlans().size() > 1, "Native innovative strategy must make a plan copy");
            assertSame(plan, plan.getPerson().getSelectedPlan());
            audit.innovated.add(iteration);
        }
        @Override public void finishReplanning() { }
    }

    private static final class Audit implements BasicEventHandler, IterationStartsListener, BeforeMobsimListener, IterationEndsListener {
        final Set<Integer> preparedDays = new LinkedHashSet<>();
        final Map<Integer, List<DynamicMixedQSimModule.ClosedEvidence>> closedEvidence = new LinkedHashMap<>();
        final Map<Integer, DynamicMixedScenarioInputs.PreparedAudit> preparedInputs = new LinkedHashMap<>();
        @Inject EventsManager manager;
        @Inject StrategyManager strategies;
        final Scenario scenario;
        final Person passenger;
        final Map<Integer, List<NativeEvent>> trace = new LinkedHashMap<>();
        final Map<Integer, Integer> workerExposureCounts = new LinkedHashMap<>();
        final Map<Integer, PlanMemory> before = new LinkedHashMap<>();
        final Map<Integer, EndState> ended = new LinkedHashMap<>();
        final Map<Integer, List<Double>> weights = new LinkedHashMap<>(), freightWeights = new LinkedHashMap<>();
        final Map<Integer, Map<TripKey, Double>> supply = new LinkedHashMap<>();
        final List<Selection> selections = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> innovated = Collections.synchronizedList(new ArrayList<>());
        volatile int iteration;
        Audit(Scenario scenario, Person passenger) { this.scenario = scenario; this.passenger = passenger; }
        @Override public double priority() { return -500; }
        @Override public synchronized void reset(int iteration) { this.iteration = iteration; trace.put(iteration, new ArrayList<>()); }
        @Override public void notifyIterationStarts(IterationStartsEvent event) { iteration = event.getIteration(); }
        @Override public synchronized void handleEvent(Event event) {
            trace.computeIfAbsent(iteration, ignored -> new ArrayList<>()).add(
                    new NativeEvent(event.getClass().getName(), event.getTime(), Map.copyOf(event.getAttributes())));
            if (event instanceof PersonScoreEvent score && score.getPersonId().toString().equals(PASSENGER)
                    && CO_PRESENCE.equals(score.getKind())) {
                assertTrue(SimStepEventsRuntimeInspection.isCurrentWorkerThread(manager),
                        "The actual co-transport monitor's derived exposure score must arrive on the native event worker");
                workerExposureCounts.merge(iteration, 1, Integer::sum);
            }
        }
        @Override public void notifyBeforeMobsim(BeforeMobsimEvent event) {
            int day = event.getIteration();
            before.put(day, memory(passenger));
            weights.put(day, List.copyOf(strategies.getWeights(null)));
            freightWeights.put(day, List.copyOf(strategies.getWeights("freight")));
            var departures = new LinkedHashMap<TripKey, Double>();
            for (var line : scenario.getTransitSchedule().getTransitLines().values())
                for (var route : line.getRoutes().values()) for (var departure : route.getDepartures().values())
                    departures.put(new TripKey(line.getId().toString(), route.getId().toString(), departure.getId().toString()),
                            departure.getDepartureTime());
            supply.put(day, Map.copyOf(departures));
        }
        @Override public void notifyIterationEnds(IterationEndsEvent event) {
            Plan selected = passenger.getSelectedPlan();
            assertNotNull(selected.getScore());
            ended.put(event.getIteration(), new EndState(selected, selected.getScore(), memory(passenger),
                    scenario.getConfig().qsim().getEndTime().seconds()));
        }
        synchronized List<NativeEvent> events(int day) { return List.copyOf(trace.get(day)); }
    }

    private static PlanMemory memory(Person person) {
        List<PlanValue> plans = new ArrayList<>();
        int selected = -1;
        for (int i = 0; i < person.getPlans().size(); i++) {
            Plan plan = person.getPlans().get(i);
            if (plan == person.getSelectedPlan()) selected = i;
            List<String> elements = new ArrayList<>();
            for (var element : plan.getPlanElements()) {
                if (element instanceof Activity activity) elements.add("activity|" + activity.getType() + "|"
                        + activity.getLinkId() + "|" + activity.getFacilityId() + "|" + activity.getCoord() + "|"
                        + activity.getStartTime() + "|" + activity.getEndTime() + "|" + activity.getMaximumDuration());
                else if (element instanceof Leg leg) elements.add("leg|" + leg.getMode() + "|" + leg.getDepartureTime()
                        + "|" + leg.getTravelTime() + "|" + (leg.getRoute() == null ? "no-route" : leg.getRoute().getRouteType()
                        + "|" + leg.getRoute().getStartLinkId() + "|" + leg.getRoute().getEndLinkId() + "|"
                        + leg.getRoute().getDistance() + "|" + leg.getRoute().getTravelTime() + "|" + leg.getRoute().getRouteDescription()));
            }
            plans.add(new PlanValue(plan.getType(), plan.getScore(), List.copyOf(elements),
                    new TreeMap<>(plan.getAttributes().getAsMap()).toString()));
        }
        return new PlanMemory(selected, List.copyOf(plans));
    }

    private static List<NativeEvent> personEvents(Audit audit, int day, Class<? extends Event> eventClass, String person) {
        return audit.events(day).stream().filter(event -> event.eventClass().equals(eventClass.getName())
                && person.equals(event.attributes().get("person"))).toList();
    }
    private static void assertPassiveEventsEquivalent(List<NativeEvent> expected, List<NativeEvent> actual, String context) {
        assertEquals(eventTimeBuckets(expected), eventTimeBuckets(actual), context + ": complete time-bucket event multisets");
        assertEquals(expected.stream().filter(event -> !isScore(event)).toList(),
                actual.stream().filter(event -> !isScore(event)).toList(), context + ": global physical event order");
        assertEquals(expected.stream().filter(DynamicMixedControllerModuleNativeTest::isScore).toList(),
                actual.stream().filter(DynamicMixedControllerModuleNativeTest::isScore).toList(),
                context + ": complete score event order and fields");
    }
    private static boolean isScore(NativeEvent event) {
        return event.eventClass().equals(PersonScoreEvent.class.getName());
    }
    private static List<Map<NativeEvent, Integer>> eventTimeBuckets(List<NativeEvent> events) {
        // Keep consecutive time buckets in their original order; never sort or rewrite the raw trace.
        List<Map<NativeEvent, Integer>> buckets = new ArrayList<>();
        double previousTime = Double.NaN;
        for (NativeEvent event : events) {
            if (buckets.isEmpty() || Double.compare(previousTime, event.time()) != 0) buckets.add(new LinkedHashMap<>());
            buckets.getLast().merge(event, 1, Integer::sum);
            previousTime = event.time();
        }
        return buckets;
    }
    private static double passengerWait(Audit audit, int day) {
        var departure = personEvents(audit, day, PersonDepartureEvent.class, PASSENGER).stream()
                .filter(event -> TransportMode.pt.equals(event.attributes().get(PersonDepartureEvent.ATTRIBUTE_LEGMODE))).toList();
        assertEquals(1, departure.size());
        return personEvents(audit, day, PersonEntersPtVehicleEvent.class, PASSENGER).getFirst().time() - departure.getFirst().time();
    }
    private static double passengerLegTime(Audit audit, int day) {
        var departures = personEvents(audit, day, PersonDepartureEvent.class, PASSENGER);
        var arrivals = personEvents(audit, day, PersonArrivalEvent.class, PASSENGER);
        assertEquals(departures.size(), arrivals.size());
        double time = 0;
        for (int i = 0; i < departures.size(); i++) {
            assertEquals(departures.get(i).attributes().get("legMode"), arrivals.get(i).attributes().get("legMode"));
            assertTrue(arrivals.get(i).time() >= departures.get(i).time());
            time += arrivals.get(i).time() - departures.get(i).time();
        }
        return time;
    }
    private static double driverStart(Audit audit, int day, String vehicle) {
        return audit.events(day).stream().filter(event -> event.eventClass().equals(TransitDriverStartsEvent.class.getName())
                && vehicle.equals(event.attributes().get("vehicleId"))).findFirst().orElseThrow().time();
    }
    private static double firstStopDeparture(Audit audit, int day, String vehicle) {
        return audit.events(day).stream().filter(event -> event.eventClass().equals(VehicleDepartsAtFacilityEvent.class.getName())
                && vehicle.equals(event.attributes().get("vehicle")) && ORIGIN.equals(event.attributes().get("facility")))
                .findFirst().orElseThrow().time();
    }

    private record NativeEvent(String eventClass, double time, Map<String, String> attributes) { }
    private record PlanValue(String type, Double score, List<String> elements, String attributes) { }
    private record PlanMemory(int selectedIndex, List<PlanValue> plans) { }
    private record ScoredPlan(Plan plan, double score) { }
    private record Selection(int iteration, List<ScoredPlan> scores) { }
    private record EndState(Plan selected, double score, PlanMemory memory, double restoredHorizon) { }
    private record Proposal(double decisionTime, double closedThrough, double releaseTime, double firstDeparture) { }
    private record Run(Audit audit, Map<Integer, DynamicMixedQSimModule.Session> sessions, Map<Integer, ToyPolicy> policies,
                       DynamicMixedDesign design, Path output, Map<String, List<Map<String, String>>> artifacts) { }
}
