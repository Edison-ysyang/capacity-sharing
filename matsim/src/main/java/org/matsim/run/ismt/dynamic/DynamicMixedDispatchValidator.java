package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.run.ismt.ExperimentConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;

/**
 * Streaming read-only audit of persisted dispatch evidence against an explicitly bound supply.
 * Replays commitments and candidate plans, and matches native applications and service facts.
 * Actual intervals are reported separately, in registered order, never sorted to hide inversions.
 * The filtered evidence cannot reconstruct the complete event prefix or certify signal safety.
 * A valid report covers this evidence scope only, not historical reuse or scientific outcomes.
 */
public final class DynamicMixedDispatchValidator {
    public static final String SCHEMA = "ismt-dynamic-dispatch-validation-v1";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final String DRIVER = "org.matsim.api.core.v01.events.TransitDriverStartsEvent";
    private static final String DEPARTURE = "org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent";
    private static final String ARRIVAL = "org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent";

    public record Inputs(DynamicMixedDesign design, List<ScheduledTrip> trips, List<ControlPoint> points,
                         List<VehicleConnection> connections,
                         Map<ControlPointKey, Map<TripKey, String>> facilities,
                         Map<TripKey, String> originFacilities, Map<String, Double> earliestFreightRelease,
                         Map<TripKey, Double> repairDepartures, double repairTimeSeconds,
                         double maximumMeanWaitingIncrementSeconds, double qsimTimeStepSeconds,
                         RollingDepartureCandidates.AdjustmentWindow adjustmentWindow) {
        public Inputs(DynamicMixedDesign design, List<ScheduledTrip> trips, List<ControlPoint> points,
                      List<VehicleConnection> connections, Map<ControlPointKey, Map<TripKey, String>> facilities,
                      Map<TripKey, String> originFacilities, Map<String, Double> earliestFreightRelease,
                      Map<TripKey, Double> repairDepartures, double repairTimeSeconds,
                      double maximumMeanWaitingIncrementSeconds, double qsimTimeStepSeconds) {
            this(design, trips, points, connections, facilities, originFacilities, earliestFreightRelease,
                    repairDepartures, repairTimeSeconds, maximumMeanWaitingIncrementSeconds, qsimTimeStepSeconds, null);
        }
        public Inputs {
            Objects.requireNonNull(design); trips = List.copyOf(trips); points = List.copyOf(points);
            connections = List.copyOf(connections); originFacilities = Map.copyOf(originFacilities);
            earliestFreightRelease = Map.copyOf(earliestFreightRelease); repairDepartures = Map.copyOf(repairDepartures);
            var copy = new LinkedHashMap<ControlPointKey, Map<TripKey, String>>();
            facilities.forEach((key, value) -> copy.put(key, Map.copyOf(value))); facilities = Map.copyOf(copy);
            require(!trips.isEmpty() && !points.isEmpty(), "Explicit supply and control points required");
            require(Double.isFinite(repairTimeSeconds) && repairTimeSeconds >= 0, "Invalid repair time");
            require(Double.isFinite(maximumMeanWaitingIncrementSeconds) && maximumMeanWaitingIncrementSeconds >= 0, "Invalid protection budget");
            require(Double.isFinite(qsimTimeStepSeconds) && qsimTimeStepSeconds > 0, "Invalid native time step");
            Set<TripKey> keys = new HashSet<>();
            for (var trip : trips) require(keys.add(trip.key()), "Repeated registered trip");
            require(originFacilities.keySet().equals(keys) && keys.containsAll(repairDepartures.keySet()), "Incomplete origin or repair coverage");
            require(facilities.keySet().equals(points.stream().map(ControlPoint::key).collect(java.util.stream.Collectors.toSet())), "Incomplete point/facility coverage");
            for (var point : points) require(facilities.get(point.key()).keySet().equals(point.orderedCalls().stream()
                    .map(Call::trip).collect(java.util.stream.Collectors.toSet())), "Incomplete call/facility coverage");
            earliestFreightRelease.forEach((id, time) -> require(id != null && !id.isBlank() && Double.isFinite(time) && time >= 0, "Invalid freight contract"));
        }
        public static Inputs from(DynamicMixedControlDefinitions.Bound bound, List<KnownFreightReleaseState.Contract> contracts) {
            var definitions = bound.definitions(); var origins = new LinkedHashMap<TripKey, String>();
            var facilities = new LinkedHashMap<ControlPointKey, Map<TripKey, String>>();
            bound.supply().trips().forEach((key, trip) -> origins.put(key, trip.calls().getFirst().stopId()));
            for (var point : definitions.constraints().sharedDeparturePoints()) {
                var calls = new LinkedHashMap<TripKey, String>();
                point.orderedCalls().forEach(call -> calls.put(call.trip(), call.facilityId())); facilities.put(point.key(), calls);
            }
            for (var point : definitions.constraints().routeOrigins()) {
                var calls = new LinkedHashMap<TripKey, String>();
                point.orderedTrips().forEach(trip -> calls.put(trip, origins.get(trip))); facilities.put(point.key(), calls);
            }
            var releases = new LinkedHashMap<String, Double>();
            for (var contract : contracts) for (String id : contract.unitIds())
                require(releases.putIfAbsent(id, contract.earliestReleaseSeconds()) == null, "Repeated freight unit contract");
            return new Inputs(definitions.spec().design(), bound.supply().trips().values().stream()
                    .map(SingleLineForecastSupply.Trip::scheduled).toList(), bound.controlPoints(), bound.vehicleConnections(),
                    facilities, origins, releases, bound.repairDepartures(), definitions.baselineTreatment().repairDecisionTimeSeconds(),
                    definitions.passengerProtection().maximumMeanWaitingIncrementSeconds(), bound.supply().qsimTimeStepSeconds(), bound.adjustmentWindow());
        }
    }

    public record ActualPoint(ControlPointKey point, int registeredCalls, int observedUniqueCalls,
                              int missingCalls, int duplicateCalls, int observedAdjacentPairs,
                              int belowMinimum, int aboveMaximum, int orderInversions,
                              Double minimumObservedSeconds, Double maximumObservedSeconds) { }
    public record Report(String schemaVersion, String scope, int iteration, boolean valid,
                         String fileSha256, long records, long serviceFacts, long forecastAttempts,
                         long readyForecasts, long committedChanges, long appliedBatches,
                         long dayElapsedNanos, List<ActualPoint> actualDepartureIntervals,
                         List<String> errors, List<String> unverifiedBoundaries) {
        public Report { actualDepartureIntervals = List.copyOf(actualDepartureIntervals); errors = List.copyOf(errors); unverifiedBoundaries = List.copyOf(unverifiedBoundaries); }
    }

    public Report validate(Path workspace, Path file, int iteration, Inputs inputs) throws IOException {
        Path root = workspace.toRealPath(), real = file.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real)) throw new IOException("Dispatch evidence must be a workspace file");
        String before = ExperimentConfiguration.sha256(real);
        var state = new State(iteration, inputs); var errors = new ArrayList<String>();
        try (var reader = Files.newBufferedReader(real)) {
            String line;
            while ((line = reader.readLine()) != null) {
                require(!line.isBlank(), "Blank evidence record");
                state.accept(JSON.readTree(line));
            }
            state.complete();
        } catch (RuntimeException | IOException failure) {
            errors.add("record " + (state.records + 1) + ": " + failure.getMessage());
        }
        if (!before.equals(ExperimentConfiguration.sha256(real))) errors.add("Evidence file changed during validation");
        var actual = state.actual();
        if (actual.stream().anyMatch(point -> point.missingCalls() > 0 || point.duplicateCalls() > 0))
            errors.add("Incomplete or duplicate registered actual departure coverage");
        return new Report(SCHEMA, "PERSISTED_DISPATCH_CONSISTENCY_AND_REGISTERED_SERVICE_COVERAGE", iteration,
                errors.isEmpty(), before, state.records, state.serviceFacts, state.attempts, state.ready,
                state.changes, state.applications.size(), state.dayElapsed, actual, errors,
                List.of("complete-native-event-prefix-and-terminal-suffix", "actual-release-event-provenance-from-filtered-stream",
                        "signal-blocks-and-railway-safety", "realized-passenger-protection-and-scientific-outcomes",
                        "historical-M-reuse-and-source-runtime-compatibility"));
    }

    private record CallId(TripKey trip, String facility) { }
    private record Proposal(double time, Map<TripKey, Double> departures) { }
    private static final class State {
        final int iteration; final Inputs input; final DynamicMixedDesign.Phase phase;
        final Map<TripKey, ScheduledTrip> trips = new LinkedHashMap<>();
        final Map<TripKey, Double> original = new LinkedHashMap<>(), commitments = new LinkedHashMap<>(), starts = new LinkedHashMap<>();
        final Map<String, TripKey> currentVehicleTrip = new HashMap<>();
        final Map<CallId, List<Double>> departures = new HashMap<>();
        final Map<Version, Proposal> proposals = new HashMap<>();
        final Map<Version, JsonNode> accepted = new LinkedHashMap<>();
        final Set<Version> applications = new HashSet<>();
        final Map<String, JsonNode> demands = new HashMap<>();
        final Map<String, JsonNode> candidatePredictions = new HashMap<>();
        final Map<TripKey, String> actualDrivers = new HashMap<>(), refreshedDrivers = new HashMap<>();
        final Map<TripKey, Double> currentActivityDeadlines = new HashMap<>();
        final DispatchConstraintChecker checker;
        long records, serviceFacts, attempts, ready, changes, dayElapsed, lastClosedSequence, epoch, lastView;
        double lastSourceTime = -1;
        long minimumRevision, lastSourceRevision, lastClosedCount;
        Double lastClosedThrough;
        JsonNode lastSource;
        boolean begun, prepared, ended, repaired;
        State(int iteration, Inputs input) {
            this.iteration = iteration; this.input = input; phase = input.design().phaseAt(iteration);
            for (var trip : input.trips()) { trips.put(trip.key(), trip); original.put(trip.key(), trip.baselineDepartureSeconds()); }
            commitments.putAll(original);
            checker = new DispatchConstraintChecker(input.trips(), input.points(), input.connections(), input.adjustmentWindow());
        }
        void accept(JsonNode row) throws IOException {
            require(row != null && row.isObject() && row.size() == 5, "Invalid evidence envelope");
            require(text(row, "schemaVersion").equals(DynamicMixedDispatchEvidence.SCHEMA), "Unsupported evidence schema");
            require(integer(row, "record") == records + 1 && integer(row, "iteration") == iteration, "Record/iteration sequence mismatch");
            require(!ended, "Record after day_end");
            String type = text(row, "type"); JsonNode p = field(row, "payload");
            require(p.isObject(), "Payload must be an object");
            if (records == 0) require(type.equals("day_start"), "Missing day_start");
            else require(!type.equals("day_start") && begun, "Repeated day_start");
            if (p.has("source")) source(field(p, "source"));
            switch (type) {
                case "day_start" -> {
                    require(integer(p, "iteration") == iteration && sameWire(phase, field(p, "phase")), "Wrong daily phase");
                    var version = version(field(p, "initialVersion")); epoch = version.epoch();
                    require(epoch > 0 && version.revision() == 0, "Daily ledger was not reset");
                    require(times(field(p, "originalDepartures")).equals(original), "Original supply differs or accumulated previous control");
                    require(bool(p, "enabled") == phase.controlEligible() && bool(p, "filteredServiceEventsOnly") && !bool(p, "terminalPrefixCertified"), "Wrong phase/evidence boundaries");
                    begun = true;
                }
                case "prepared_inputs" -> {
                    require(!prepared && bool(p, "supportedInput") && !bool(p, "actualTimingCertified"), "Invalid prepared inputs");
                    long expected = phase.freightTreatmentActive() ? input.earliestFreightRelease().size() : 0;
                    require(integer(p, "verifiedFreightUnits") == expected, "Prepared freight unit count differs from contracts/phase");
                    prepared = true;
                }
                case "closed_service_facts" -> service(p);
                case "forecast_demand" -> demand(p);
                case "forecast_attempt" -> forecast(p);
                case "rolling_decision", "repair_decision" -> proposal(type, p);
                case "accepted_commitment" -> commitment(p);
                case "native_application" -> application(p);
                case "day_end" -> end(p);
                case "day_incomplete", "forecast_failure" -> throw new IllegalArgumentException("Incomplete/failed dispatch: " + type);
                default -> throw new IllegalArgumentException("Unknown dispatch record type: " + type);
            }
            records++;
        }
        void source(JsonNode s) {
            require(prepared && integer(s, "iteration") == iteration && integer(s, "epoch") == epoch, "Source before preparation or wrong iteration/epoch");
            long view = integer(s, "viewSequence"); double now = number(s, "decisionTimeSeconds");
            require(view >= lastView && view > 0 && now >= lastSourceTime, "Regressed evidence source");
            var ledgerVersion = version(field(s, "commitmentVersion"));
            require(ledgerVersion.epoch() == epoch && ledgerVersion.revision() >= minimumRevision
                    && ledgerVersion.revision() >= lastSourceRevision, "Source ledger epoch/revision differs or regressed");
            if (view == lastView) require(lastSource != null && lastSource.equals(s), "Same closed view changed its source metadata");
            require(text(s, "closedPrefixSha256").matches("[a-f0-9]{64}"), "Invalid source prefix digest");
            if (!field(s, "closedThroughSeconds").isNull()) {
                double through = number(s, "closedThroughSeconds");
                require(through <= now && (lastClosedThrough == null || through >= lastClosedThrough), "Future/regressed closed prefix");
                lastClosedThrough = through;
            } else require(lastClosedThrough == null, "Known closed watermark became unknown");
            require(integer(s, "closedEventCount") >= Math.max(lastClosedSequence, lastClosedCount), "Closed prefix count regressed");
            if (view > lastView) { demands.clear(); candidatePredictions.clear(); }
            lastView = view; lastSourceTime = now; lastSource = s;
            lastSourceRevision = ledgerVersion.revision(); lastClosedCount = integer(s, "closedEventCount");
        }
        void service(JsonNode p) {
            var source = field(p, "source"); double through = number(source, "closedThroughSeconds"), now = number(source, "decisionTimeSeconds");
            for (var e : array(p, "events")) {
                long sequence = integer(e, "closedSequence"); double time = number(e, "occurredAtSeconds");
                require(sequence > lastClosedSequence && sequence <= integer(source, "closedEventCount"), "Service event sequence mismatch");
                require(time <= through && number(e, "availableBySeconds") <= now && number(e, "availableBySeconds") >= time, "Future or premature available service fact");
                JsonNode drain = field(e, "firstCoveredByDrain");
                if (!drain.isNull()) require(field(e, "actualReceiptSeconds").isNull() && number(drain, "timeSeconds") <= now
                        && number(drain, "timeSeconds") >= time && number(e, "availableBySeconds") == number(drain, "timeSeconds"), "Parallel drain/availability time mismatch");
                else require(number(e, "actualReceiptSeconds") >= time && number(e, "actualReceiptSeconds") <= now
                        && number(e, "availableBySeconds") == number(e, "actualReceiptSeconds"), "Invalid synchronous receipt/availability time");
                var a = field(e, "attributes"); String kind = text(e, "eventClass");
                if (kind.equals(DRIVER)) {
                    var trip = new TripKey(text(a, "transitLineId"), text(a, "transitRouteId"), text(a, "departureId"));
                    require(trips.containsKey(trip) && trips.get(trip).vehicleId().equals(text(a, "vehicleId")), "Unknown/reassigned native driver trip");
                    require(starts.putIfAbsent(trip, time) == null, "Repeated driver start");
                    actualDrivers.put(trip, text(a, "driverId"));
                    require(time >= commitments.get(trip), "Driver started earlier than its accepted commitment");
                    currentVehicleTrip.put(text(a, "vehicleId"), trip);
                } else if (kind.equals(DEPARTURE) || kind.equals(ARRIVAL)) {
                    var trip = currentVehicleTrip.get(text(a, "vehicle")); require(trip != null, "Facility event without an observed registered driver");
                    require(time >= starts.get(trip), "Facility event preceded native trip start");
                    if (kind.equals(DEPARTURE)) departures.computeIfAbsent(new CallId(trip, text(a, "facility")), ignored -> new ArrayList<>()).add(time);
                } else throw new IllegalArgumentException("Unexpected copied service class");
                lastClosedSequence = sequence; serviceFacts++;
            }
        }
        void demand(JsonNode p) throws IOException {
            require(phase.controlEligible(), "Forecast leaked into inactive phase");
            var reference = field(p, "reference"); var source = field(p, "source");
            require(integer(reference, "iteration") == iteration && integer(reference, "epoch") == epoch
                    && integer(reference, "viewSequence") == integer(source, "viewSequence"), "Demand reference source mismatch");
            require(text(reference, "demandProjectionId").equals(text(field(p, "detail"), "demandProjectionId")), "Demand projection identity differs");
            require(demands.putIfAbsent(reference.toString(), p) == null, "Repeated fixed demand reference");
            JsonNode ids = field(p, "releasedFreightUnitIds");
            if (!ids.isNull()) {
                var seen = new HashSet<String>();
                for (var id : iterable(ids)) {
                    require(id.isTextual() && seen.add(id.textValue()), "Invalid/duplicate released ID");
                    var release = input.earliestFreightRelease().get(id.textValue());
                    require(release != null && release <= number(source, "closedThroughSeconds"), "Unknown or not-yet-releasable freight ID");
                }
                require(seen.size() == integer(field(p, "detail"), "knownFreightUnits"), "Known freight count differs");
            }
        }
        void forecast(JsonNode p) throws IOException {
            require(phase.controlEligible() && integer(p, "attempt") == ++attempts && integer(p, "elapsedNanos") >= 0, "Invalid forecast attempt");
            var demand = demands.get(field(p, "demandReference").toString()); require(demand != null, "Unrecorded fixed demand reference");
            require(field(demand, "source").equals(field(p, "source")), "Forecast demand uses a different closed source");
            var candidate = new LinkedHashMap<>(commitments); var delta = times(field(p, "changesFromDecisionStart"));
            require(candidate.keySet().containsAll(delta.keySet()), "Candidate invented a trip"); candidate.putAll(delta);
            require(hash(DynamicMixedDispatchEvidence.times(candidate)).equals(text(p, "candidateSha256")), "Candidate full-plan digest mismatch");
            boolean infeasibleOriginalRepairReference = number(field(p, "source"), "decisionTimeSeconds") == input.repairTimeSeconds() && candidate.equals(original);
            require(infeasibleOriginalRepairReference || checker.evaluate(candidate).candidate().satisfiesRegisteredConstraints(), "Forecast candidate violates registered plan constraints");
            String status = text(p, "status"); var projection = field(p, "projection");
            require(Set.of("READY", "INSUFFICIENT_HISTORY", "UNSUPPORTED_STATE").contains(status) && status.equals("READY") == !projection.isNull(), "Forecast status/projection mismatch");
            if (!projection.isNull()) {
                ready++;
                var ids = field(demand, "releasedFreightUnitIds"); require(ids.isArray(), "Ready forecast lacks released IDs");
                require(integer(projection, "releasedFreightUnitCount") == ids.size()
                        && text(projection, "releasedFreightUnitSetSha256").equals(hash(JSON.convertValue(ids, List.class))), "Forecast released-unit digest/count mismatch");
                require(text(projection, "demandProjectionId").equals(text(field(demand, "detail"), "demandProjectionId")), "Candidate changed fixed demand identity");
                require(number(projection, "forecastEndSeconds") > number(field(p, "source"), "decisionTimeSeconds"), "Empty forecast horizon");
                var previous = candidatePredictions.putIfAbsent(text(p, "candidateSha256"), projection);
                require(previous == null || previous.equals(projection), "Same candidate/source changed its forecast projection");
            }
        }
        void proposal(String type, JsonNode p) throws IOException {
            require(phase.controlEligible(), "Control decision in inactive phase");
            var changes = times(field(p, "proposedDepartures")); var source = field(p, "source");
            boolean selected = type.equals("repair_decision") ? text(p, "outcome").equals("REPAIR_PROPOSED") : text(p, "outcome").equals("PROPOSED");
            if (selected) {
                require(!changes.isEmpty() && !bool(p, "actualExecutionVerified"), "Invalid proposal/execution claim");
                var key = version(field(source, "commitmentVersion"));
                require(proposals.putIfAbsent(key, new Proposal(number(source, "decisionTimeSeconds"), changes)) == null, "Repeated proposal for source version");
                var candidate = new LinkedHashMap<>(commitments); candidate.putAll(changes);
                var reference = candidatePredictions.get(hash(DynamicMixedDispatchEvidence.times(commitments)));
                var selectedProjection = candidatePredictions.get(hash(DynamicMixedDispatchEvidence.times(candidate)));
                require(reference != null && selectedProjection != null, "Proposal lacks actual recorded candidate/no-action forecasts");
                if (type.equals("repair_decision")) {
                    require(number(source, "decisionTimeSeconds") == input.repairTimeSeconds() && changes.equals(input.repairDepartures()), "Unregistered pre-service repair");
                    require(reference.equals(field(p, "originalProjection")) && selectedProjection.equals(field(p, "repairProjection")), "Repair substituted its recorded forecasts");
                    require(number(p, "meanPassengerWaitingIncrementSeconds") <= input.maximumMeanWaitingIncrementSeconds(), "Repair exceeds predicted passenger budget");
                } else {
                    require(reference.equals(field(p, "noActionProjection")) && selectedProjection.equals(field(p, "selectedProjection")), "Rolling decision substituted its recorded forecasts");
                }
                require(text(reference, "demandProjectionId").equals(text(selectedProjection, "demandProjectionId"))
                        && number(reference, "protectedPassengerMass") == number(selectedProjection, "protectedPassengerMass"), "Proposed control changed protected demand");
                double mass = number(reference, "protectedPassengerMass");
                double increment = mass == 0 ? 0 : (number(selectedProjection, "protectedTotalWaitingSeconds") - number(reference, "protectedTotalWaitingSeconds")) / mass;
                // Aggregated evidence sums before/after waiting separately; tolerate only numerical re-association.
                require(increment <= input.maximumMeanWaitingIncrementSeconds() + 1e-9, "Proposed control exceeds predicted passenger budget");
                if (type.equals("repair_decision")) require(Math.abs(increment - number(p, "meanPassengerWaitingIncrementSeconds")) <= 1e-9,
                        "Repair waiting-increment scalar differs from recorded projections");
            } else require(changes.isEmpty(), "Non-proposed decision includes changes");
        }
        void commitment(JsonNode p) {
            require(phase.controlEligible() && integer(p, "iteration") == iteration, "Commitment in inactive/wrong phase");
            var source = version(field(p, "sourceVersion")); var result = version(field(p, "resultingVersion"));
            require(source.epoch() == epoch && result.epoch() == epoch && result.revision() == source.revision() + 1, "Invalid commitment revision");
            require(source.revision() >= Math.max(minimumRevision, lastSourceRevision), "Commitment source revision regressed");
            var proposed = proposals.get(source); double now = number(p, "decisionTimeSeconds");
            require(proposed != null && proposed.time() == now && accepted.putIfAbsent(source, p) == null, "Commitment without unique matching proposal");
            var delta = new LinkedHashMap<TripKey, Double>();
            for (var c : array(p, "changes")) {
                var key = trip(field(c, "key")); var registered = trips.get(key); double time = number(c, "committedDepartureSeconds");
                require(registered != null && registered.vehicleId().equals(text(c, "vehicleId")), "Commitment changed supply/vehicle");
                require(number(c, "baselineDepartureSeconds") == original.get(key) && number(c, "previousDepartureSeconds") == commitments.get(key), "Baseline/previous commitment mismatch");
                require(time >= original.get(key) - input.design().limits().maximumAdvanceSeconds()
                        && time <= original.get(key) + input.design().limits().maximumDelaySeconds(), "Cumulative baseline displacement exceeded");
                require(!starts.containsKey(key) && commitments.get(key) > now + input.design().limits().lockWindowSeconds()
                        && time > now + input.design().limits().lockWindowSeconds(), "Locked/started/past trip changed");
                require(delta.putIfAbsent(key, time) == null, "Repeated commitment change"); changes++;
            }
            require(delta.equals(proposed.departures()), "Committed changes differ from proposal");
            commitments.putAll(delta);
            minimumRevision = result.revision();
            require(checker.evaluate(commitments).candidate().satisfiesRegisteredConstraints(), "Accepted plan violates registered constraints");
            if (now == input.repairTimeSeconds() && delta.equals(input.repairDepartures())) repaired = true;
        }
        void application(JsonNode p) {
            require(phase.controlEligible() && !bool(p, "actualExecutionVerified"), "Unexpected native application/execution claim");
            String status = text(p, "status"); require(!status.equals("FAILED_NATIVE_APPLY"), "Native application failed");
            var source = version(field(p, "expectedVersion"));
            if (status.equals("COMMITTED_APPLIED")) {
                var commit = accepted.get(source); var proposed = proposals.get(source);
                require(commit != null && proposed != null && commit.equals(field(p, "decision")) && applications.add(source), "Unmatched/repeated native application");
                require(number(p, "decisionTimeSeconds") == proposed.time() && times(field(p, "proposedDepartures")).equals(proposed.departures()), "Application changed proposed batch");
                require(bool(field(p, "candidateAssessment"), "satisfiesRegisteredConstraints"), "Applied batch reports invalid planned constraints");
                require(text(p, "stage").equals("COMPLETE"), "Applied batch has not completed native application stage");
                var expectedChanges = field(commit, "changes"); var refreshed = field(p, "refreshedTrips");
                require(refreshed.isArray() && refreshed.size() == expectedChanges.size(), "Incomplete refreshed native trip coverage");
                var toRequeue = new java.util.LinkedHashSet<String>();
                for (int index = 0; index < refreshed.size(); index++) {
                    var item = refreshed.get(index); var key = trip(field(item, "trip"));
                    require(key.equals(trip(field(expectedChanges.get(index), "key")))
                            && version(field(item, "acceptedVersion")).equals(version(field(commit, "resultingVersion"))), "Native refresh trip/version mismatch");
                    String driver = text(item, "driverId"); var old = refreshedDrivers.putIfAbsent(key, driver);
                    require(old == null || old.equals(driver), "Trip refresh changed its driver identity");
                    String outcome = text(item, "outcome");
                    require(Set.of("CURRENT_ACTIVITY_CHANGED", "FUTURE_ONLY", "BLOCKED", "NO_CHANGE").contains(outcome), "Unknown refresh outcome");
                    double previous = deadline(field(item, "previousDeadlineSeconds")), effective = deadline(field(item, "effectiveDeadlineSeconds"));
                    if (outcome.equals("CURRENT_ACTIVITY_CHANGED")) {
                        require(Double.isFinite(effective) && previous != effective && effective > number(p, "decisionTimeSeconds"), "Invalid changed native deadline");
                        toRequeue.add(driver);
                    } else require(previous == effective, "Non-rescheduled refresh changed a deadline");
                    if (Double.isFinite(effective) && (outcome.equals("CURRENT_ACTIVITY_CHANGED") || outcome.equals("NO_CHANGE")))
                        currentActivityDeadlines.put(key, effective);
                    else currentActivityDeadlines.remove(key); // Future work/blocked transitions require their own native ready-state evidence.
                }
                var requeued = new ArrayList<String>();
                for (var id : array(p, "requeuedDriverIds")) { require(id.isTextual(), "Invalid requeued driver ID"); requeued.add(id.textValue()); }
                require(requeued.equals(List.copyOf(toRequeue)), "Native requeue list differs from exactly changed current activities");
            } else require(status.equals("REJECTED_PRECOMMIT") && field(p, "decision").isNull(), "Unknown application status or rejected commitment");
        }
        void end(JsonNode p) throws IOException {
            require(prepared, "Day ended before prepared verification"); var r = field(p, "receipt"); var close = field(p, "observationClose");
            require(integer(r, "iteration") == iteration && sameWire(phase, field(r, "phase")), "Receipt daily phase differs");
            require(!bool(r, "poisoned") && !bool(close, "poisoned") && bool(r, "preparedInputVerified") && bool(r, "nativeEventsProcessingFinished"), "Incomplete native day");
            require(!bool(p, "terminalPrefixCertified") && !bool(r, "terminalPrefixCertified"), "Unsupported terminal prefix certification");
            require(integer(close, "receivedEvents") == integer(close, "closedEvents") + integer(close, "unpublishedEvents"), "Observer closure accounting mismatch");
            require(integer(p, "closedEvents") == integer(close, "closedEvents") && integer(p, "closedViews") == integer(r, "closedViewCount"), "Closed view/event totals mismatch");
            require(integer(p, "copiedServiceEvents") == serviceFacts && integer(p, "forecastAttempts") == attempts && integer(r, "committedChanges") == changes, "Persisted record counts mismatch");
            require(times(field(p, "finalCommitments")).equals(commitments) && times(field(p, "observedDriverStarts")).equals(starts), "Final commitment/start ledger mismatch");
            require(applications.equals(accepted.keySet()) && starts.keySet().equals(original.keySet()), "Unapplied commitments or missing native trips");
            refreshedDrivers.forEach((trip, driver) -> require(driver.equals(actualDrivers.get(trip)), "Refreshed driver differs from native start event"));
            currentActivityDeadlines.forEach((trip, deadline) -> require(starts.get(trip) + 1e-9 >= deadline
                    && starts.get(trip) < deadline + input.qsimTimeStepSeconds() + 1e-9,
                    "Native current activity did not execute on its refreshed deadline step"));
            require(phase.controlEligible() ? integer(r, "nativeDrivers") == 0 && integer(r, "controlledDrivers") > 0
                    : integer(r, "controlledDrivers") == 0 && integer(r, "policyCalls") == 0 && changes == 0 && attempts == 0, "Wrong execution path/phase");
            if (phase.controlEligible() && !checker.evaluate(original).candidate().satisfiesRegisteredConstraints())
                require(repaired, "Required conditional repair was not accepted; do not upgrade run");
            for (var e : accepted.entrySet()) for (var c : array(e.getValue(), "changes"))
                require(starts.get(trip(field(c, "key"))) > number(e.getValue(), "decisionTimeSeconds"), "Retrospective actual start preceded its adjustment");
            for (var entry : input.originFacilities().entrySet()) {
                var values = departures.getOrDefault(new CallId(entry.getKey(), entry.getValue()), List.of());
                require(values.size() == 1 && values.getFirst() >= starts.get(entry.getKey()), "Missing/duplicate/early actual origin departure");
            }
            dayElapsed = integer(p, "elapsedNanos"); require(dayElapsed >= 0, "Invalid day timing"); ended = true;
        }
        void complete() { require(begun && prepared && ended, "No complete day_start/prepared/day_end chain"); }
        List<ActualPoint> actual() {
            var result = new ArrayList<ActualPoint>();
            for (var point : input.points()) {
                int unique = 0, missing = 0, duplicate = 0, pairs = 0, low = 0, high = 0, inversions = 0;
                Double previous = null, min = null, max = null;
                for (var call : point.orderedCalls()) {
                    var values = departures.getOrDefault(new CallId(call.trip(), input.facilities().get(point.key()).get(call.trip())), List.of());
                    if (values.size() != 1) { if (values.isEmpty()) missing++; else duplicate++; previous = null; continue; }
                    double value = values.getFirst(); unique++;
                    if (previous != null) {
                        double gap = value - previous; pairs++;
                        if (gap < point.minimumHeadwaySeconds()) low++; if (gap > point.maximumHeadwaySeconds()) high++; if (gap < 0) inversions++;
                        min = min == null ? gap : Math.min(min, gap); max = max == null ? gap : Math.max(max, gap);
                    }
                    previous = value;
                }
                result.add(new ActualPoint(point.key(), point.orderedCalls().size(), unique, missing, duplicate, pairs, low, high, inversions, min, max));
            }
            return result;
        }
    }
    private static JsonNode field(JsonNode p, String key) { var value = p.get(key); require(value != null, "Missing field " + key); return value; }
    private static String text(JsonNode p, String key) { var value = field(p, key); require(value.isTextual() && !value.textValue().isBlank(), "Invalid text " + key); return value.textValue(); }
    private static boolean bool(JsonNode p, String key) { var value = field(p, key); require(value.isBoolean(), "Invalid boolean " + key); return value.booleanValue(); }
    private static double number(JsonNode p, String key) { var value = field(p, key); require(value.isNumber() && Double.isFinite(value.doubleValue()), "Invalid finite number " + key); return value.doubleValue(); }
    private static long integer(JsonNode p, String key) { var value = field(p, key); require(value.isIntegralNumber() && value.canConvertToLong(), "Invalid integer " + key); return value.longValue(); }
    private static Iterable<JsonNode> iterable(JsonNode value) { require(value.isArray(), "Expected array"); return value; }
    private static Iterable<JsonNode> array(JsonNode p, String key) { return iterable(field(p, key)); }
    private static TripKey trip(JsonNode p) { require(p.isObject() && p.size() == 3, "Invalid trip key"); return new TripKey(text(p, "line"), text(p, "route"), text(p, "departure")); }
    private static Version version(JsonNode p) { return new Version(integer(p, "epoch"), integer(p, "revision")); }
    private static double deadline(JsonNode value) {
        if (value.isTextual() && value.textValue().equals("POSITIVE_INFINITY_NATIVE_BLOCKED_OR_TERMINAL")) return Double.POSITIVE_INFINITY;
        require(value.isNumber() && Double.isFinite(value.doubleValue()) && value.doubleValue() >= 0, "Invalid native deadline encoding"); return value.doubleValue();
    }
    private static Map<TripKey, Double> times(JsonNode values) {
        var result = new LinkedHashMap<TripKey, Double>();
        for (var value : iterable(values)) { double time = number(value, "departureSeconds"); require(time >= 0 && result.putIfAbsent(trip(field(value, "trip")), time) == null, "Invalid/repeated trip time"); }
        return result;
    }
    private static boolean sameWire(Object value, JsonNode expected) throws IOException { return JSON.readTree(JSON.writeValueAsBytes(value)).equals(expected); }
    private static String hash(Object value) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
