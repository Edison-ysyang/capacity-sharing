package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.HashSet;

import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;

/**
 * Compact, append-only control evidence, separate from the native scientific event files.
 * Only service facts in an already closed prefix are copied here. These are not all events;
 * the prefix anchors cannot be reconstructed from this filtered stream alone. Native terminal
 * events remain in the ordinary event outputs and are NEVER sent back to the controller.
 * Proposals, accepted commitments, native queue applications and observed service events are
 * explicitly different records. This writer does not certify actual headways or railway safety.
 * One writer is owned by the simulation/control thread; no per-second full-state serialization.
 */
public final class DynamicMixedDispatchEvidence implements AutoCloseable {
    public static final String SCHEMA = "ismt-dynamic-dispatch-evidence-v1";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final Comparator<TripKey> TRIPS = Comparator.comparing(TripKey::line)
            .thenComparing(TripKey::route).thenComparing(TripKey::departure);
    private static final Set<String> SERVICE_EVENTS = Set.of(
            "org.matsim.api.core.v01.events.TransitDriverStartsEvent",
            "org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent",
            "org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent");
    private final Path directory;
    private final Thread owner = Thread.currentThread();
    private DynamicMixedQSimModule.Session session;
    private BufferedWriter stream;
    private Path dayFile;
    private DynamicMixedQSimModule.ClosedEvidence latest;
    private long views, events, serviceEvents, attempts, records, startedNanos;
    private int recordedCommitments, recordedApplications;
    private int lastIteration = -1;
    private boolean closed, failed, prepared;
    private record DemandReference(int iteration, long epoch, long viewSequence, String demandProjectionId) { }
    private final Set<DemandReference> recordedDemands = new HashSet<>();

    public DynamicMixedDispatchEvidence(Path workspace, Path newDirectory) throws IOException {
        Path root = workspace.toRealPath(), target = newDirectory.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || Files.exists(target))
            throw new IOException("Evidence requires a new directory inside the workspace");
        Path parent = target.getParent().toRealPath();
        if (!parent.startsWith(root)) throw new IOException("Evidence parent escapes workspace");
        directory = Files.createDirectory(target).toRealPath();
    }

    /** Install alongside, not instead of, the Controller/scientific artifact modules. */
    public AbstractModule module() {
        return new AbstractModule() {
            @Override public void install() {
                addControllerListenerBinding().toInstance(new IterationEndsListener() {
                    @Override public double priority() { return -1000; }
                    @Override public void notifyIterationEnds(IterationEndsEvent event) { finishDay(event.getIteration()); }
                });
            }
        };
    }

    public void sessionStarted(DynamicMixedQSimModule.Session value) {
        check(); Objects.requireNonNull(value);
        require(session == null && value.iteration() > lastIteration, "Overlapping or repeated evidence day");
        session = value; latest = null; views = events = serviceEvents = attempts = records = 0;
        recordedCommitments = recordedApplications = 0;
        recordedDemands.clear();
        prepared = false; startedNanos = System.nanoTime();
        dayFile = directory.resolve(String.format(java.util.Locale.ROOT, "iteration-%03d.jsonl", value.iteration()));
        try { stream = Files.newBufferedWriter(dayFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
        catch (IOException failure) { failed = true; throw new UncheckedIOException(failure); }
        var initial = value.initialCommitments();
        write("day_start", object("iteration", value.iteration(), "phase", value.phase(),
                "initialVersion", initial.version(), "initialTimeSeconds", initial.nowSeconds(),
                "originalDepartures", times(initial.commitments()), "enabled", initial.enabled(),
                "filteredServiceEventsOnly", true, "terminalPrefixCertified", false));
        flush();
    }

    public void prepared(int iteration, DynamicMixedScenarioInputs.PreparedAudit audit) {
        day(iteration); require(!prepared && audit.iteration() == iteration, "Invalid prepared evidence day");
        audit.populationAndStructure().requireSupportedInput(); prepared = true;
        write("prepared_inputs", object("verifiedFreightUnits", audit.verifiedFreightUnits(),
                "populationCounts", audit.populationAndStructure().counts(),
                "supportedInput", audit.populationAndStructure().verifiedSupportedInput(),
                "actualTimingCertified", false));
        flush();
    }

    public void acceptClosed(DynamicMixedQSimModule.ClosedEvidence evidence) {
        var view = evidence.view(); day(view.iteration());
        require(prepared, "Closed evidence before prepared verification");
        var commitment = evidence.commitmentsBeforePolicy();
        require(view.viewSequence() == views + 1 && commitment.iteration() == view.iteration()
                && view.epoch() == commitment.version().epoch()
                && view.decisionTimeSeconds() == commitment.nowSeconds(), "Broken evidence sequence/source");
        if (latest != null) require(view.precedingClosedPrefixSha256().equals(latest.view().closedPrefixSha256()),
                "Broken closed-prefix anchor chain");
        for (var event : view.newlyClosedEvents()) {
            require(event.closedSequence() == ++events, "Broken closed event sequence");
            require(view.closedThroughSeconds().isPresent()
                    && event.occurredAtSeconds() <= view.closedThroughSeconds().getAsDouble(), "Future service fact");
        }
        require(view.closedEventCount() == events, "Closed count differs from copied sequence");
        views++; latest = evidence;
        drainApplications();
        var facts = view.newlyClosedEvents().stream().filter(event -> SERVICE_EVENTS.contains(event.eventClass()))
                .map(event -> object("closedSequence", event.closedSequence(), "occurredAtSeconds", event.occurredAtSeconds(),
                        "eventClass", event.eventClass(), "eventType", event.eventType(), "attributes", event.attributes(),
                        "availableBySeconds", event.availableBySeconds(),
                        "actualReceiptSeconds", event.firstCoveredByDrain().isEmpty() ? event.receivedAtSeconds() : null,
                        "firstCoveredByDrain", event.firstCoveredByDrain().orElse(null))).toList();
        serviceEvents += facts.size();
        if (!facts.isEmpty()) write("closed_service_facts", object("source", source(), "events", facts));
    }

    /** Each candidate has its own attempt number/hash even when the demand projection is shared. */
    public RollingDeparturePolicy.ForecastModel auditedForecast(int iteration, CausalMixedServiceForecast model) {
        Objects.requireNonNull(model);
        return new RollingDeparturePolicy.ForecastModel() {
            @Override public Optional<RollingDeparturePolicy.FreightCost> fixedObservedDeliveredFreightCost(
                    ClosedCoTransportObservationState.Snapshot observations, RollingDeparturePolicy.Projection originalNoActionProjection) {
                day(iteration);
                require(latest != null && observations.position().iteration() == iteration
                        && observations.position().viewSequence() == latest.view().viewSequence()
                        && observations.position().closedPrefixSha256().equals(latest.view().closedPrefixSha256()),
                        "Pruning capability source mismatch");
                // Preserve the concrete model's opt-in proof; do not run an extra forecast or certify protection.
                return model.fixedObservedDeliveredFreightCost(observations, originalNoActionProjection);
            }

            @Override public RollingDeparturePolicy.Forecast project(ClosedCoTransportObservationState.Snapshot observations,
                    Map<TripKey, Double> departures) {
                day(iteration); require(latest != null, "Forecast without closed evidence");
                var position = observations.position();
                require(position.iteration() == iteration && position.viewSequence() == latest.view().viewSequence()
                        && position.closedPrefixSha256().equals(latest.view().closedPrefixSha256()), "Forecast source mismatch");
                long number = ++attempts, start = System.nanoTime();
                var candidate = times(departures);
                var header = object("attempt", number, "source", source(), "candidateSha256", sha(candidate),
                        "changesFromDecisionStart", changes(latest.commitmentsBeforePolicy().commitments(), departures));
                try {
                    var result = model.project(observations, departures);
                    var detail = model.lastForecast().orElseThrow();
                    require(detail.viewSequence() == position.viewSequence()
                            && detail.closedPrefixSha256().equals(position.closedPrefixSha256()), "Stale forecast detail");
                    header.put("elapsedNanos", System.nanoTime() - start);
                    header.put("timingScope", "candidate preparation and hashing plus model; excludes evidence summaries and IO; day_end elapsedNanos includes complete day");
                    var demand = new DemandReference(iteration, detail.epoch(), detail.viewSequence(), detail.demandProjectionId());
                    if (recordedDemands.add(demand)) write("forecast_demand", object("reference", demand, "source", source(),
                            "releasedFreightUnitIds", result.projection().map(value -> value.releasedFreightUnitIds().stream().sorted().toList()).orElse(null),
                            "detail", forecastDetail(detail)));
                    header.put("status", result.status()); header.put("reason", result.reason());
                    header.put("projection", result.projection().map(DynamicMixedDispatchEvidence::projection).orElse(null));
                    header.put("demandReference", demand); header.put("completionCounts", detail.completionCounts());
                    write("forecast_attempt", header);
                    return result;
                } catch (RuntimeException | Error failure) {
                    header.put("failure", failure.toString()); header.put("elapsedNanos", System.nanoTime() - start);
                    try { write("forecast_failure", header); flush(); } catch (RuntimeException recordFailure) { failure.addSuppressed(recordFailure); }
                    throw failure;
                }
            }
        };
    }

    public void rollingDecision(int iteration, RollingDeparturePolicy.Decision decision) {
        day(iteration); verifyDecision(decision.decisionTimeSeconds(), decision.sourceVersion(), decision.closedPrefixSha256());
        var trials = decision.trials().stream().map(trial -> object("sweep", trial.sweep(),
                "changesFromDecisionStart", times(trial.changesFromDecisionStart()), "outcome", trial.outcome(),
                "freight", trial.freight().orElse(null), "meanPassengerWaitingIncrementSeconds", trial.meanPassengerWaitingIncrementSeconds().orElse(null),
                "absoluteBaselineShiftSeconds", trial.absoluteBaselineShiftSeconds())).toList();
        write("rolling_decision", object("source", source(), "outcome", decision.outcome(),
                "proposedDepartures", times(decision.proposedDepartures()),
                "noActionProjection", decision.noActionProjection().map(DynamicMixedDispatchEvidence::projection).orElse(null),
                "selectedProjection", decision.selectedProjection().map(DynamicMixedDispatchEvidence::projection).orElse(null),
                "trials", trials, "improvingSweeps", decision.improvingSweeps(), "searchBudgetReached", decision.searchBudgetReached(),
                "actualExecutionVerified", false));
        flush();
    }

    public void repairDecision(int iteration, PreServiceRepairPolicy.Audit audit) {
        day(iteration); verifyDecision(audit.decisionTimeSeconds(), audit.sourceVersion(), audit.closedPrefixSha256());
        write("repair_decision", object("source", source(), "outcome", audit.outcome(), "reason", audit.reason(),
                "proposedDepartures", times(audit.proposedDepartures()), "originalAssessment", assessment(audit.originalAssessment()),
                "repairAssessment", audit.repairComparison().map(value -> assessment(value.candidate())).orElse(null),
                "originalProjection", audit.originalProjection().map(DynamicMixedDispatchEvidence::projection).orElse(null),
                "repairProjection", audit.repairProjection().map(DynamicMixedDispatchEvidence::projection).orElse(null),
                "meanPassengerWaitingIncrementSeconds", audit.meanPassengerWaitingIncrementSeconds().orElse(null),
                "totalAbsoluteOriginalBaselineShiftSeconds", audit.totalAbsoluteOriginalBaselineShiftSeconds(), "actualExecutionVerified", false));
        flush();
    }

    public void finishDay(int iteration) {
        day(iteration); var receipt = session.receipt(); var closure = session.observationCloseReceipt();
        require(prepared && !receipt.poisoned() && receipt.nativeEventsProcessingFinished()
                && !closure.poisoned() && receipt.closedViewCount() == views && closure.closedEvents() == events
                && closure.receivedEvents() == closure.closedEvents() + closure.unpublishedEvents(), "Incomplete evidence day");
        drainApplications();
        require(session.commitmentHistory().stream().mapToLong(value -> value.changes().size()).sum() == receipt.committedChanges(),
                "Commitment count mismatch");
        write("day_end", object("receipt", receipt, "observationClose", closure,
                "closedViews", views, "closedEvents", events, "copiedServiceEvents", serviceEvents, "forecastAttempts", attempts,
                "elapsedNanos", System.nanoTime() - startedNanos, "terminalPrefixCertified", false,
                "finalCommitments", times(session.snapshot().commitments()), "observedDriverStarts", times(session.snapshot().startedAtSeconds())));
        flush();
        try { stream.close(); } catch (IOException failure) { failed = true; throw new UncheckedIOException(failure); }
        stream = null; lastIteration = iteration; session = null;
    }

    /** Also called during failure cleanup; never requires a successfully finished native day. */
    private void drainApplications() {
        var commitments = session.commitmentHistory(); var applications = session.applications();
        require(recordedCommitments <= commitments.size() && recordedApplications <= applications.size(), "Audit history shrank");
        while (recordedCommitments < commitments.size()) write("accepted_commitment", commitments.get(recordedCommitments++));
        while (recordedApplications < applications.size()) {
            var value = applications.get(recordedApplications++);
            var refreshed = value.refreshedTrips().stream().map(item -> object("trip", item.trip(), "driverId", item.driverId(),
                    "outcome", item.result().outcome(), "previousDeadlineSeconds", deadline(item.result().previousDeadlineSeconds()),
                    "effectiveDeadlineSeconds", deadline(item.result().effectiveDeadlineSeconds()),
                    "acceptedVersion", item.result().acceptedVersion().orElse(null))).toList();
            write("native_application", object("status", value.status(),
                "decisionTimeSeconds", value.batch().decisionTimeSeconds(), "expectedVersion", value.batch().expectedVersion(),
                "proposedDepartures", times(value.batch().proposedDepartures()), "decision", value.decision().orElse(null),
                "candidateAssessment", value.constraints().map(c -> assessment(c.candidate())).orElse(null),
                "refreshedTrips", refreshed, "requeuedDriverIds", value.requeuedDriverIds(),
                "stage", value.stage(), "detail", value.detail(), "actualExecutionVerified", false));
        }
    }

    /** Failure closes the partial append-only file without creating a successful day_end. */
    @Override public void close() {
        if (closed) return;
        require(Thread.currentThread() == owner, "Evidence writer used from another thread");
        try {
            if (stream != null && !failed && session != null) {
                drainApplications();
                write("day_incomplete", object("reason", "Writer closed without a successful iteration-end receipt",
                        "closedViews", views, "closedEvents", events, "terminalPrefixCertified", false));
            }
        } finally {
            try { if (stream != null) stream.close(); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
            finally { closed = true; stream = null; }
        }
    }

    private void verifyDecision(double time, Version version, String prefix) {
        require(latest != null && latest.view().decisionTimeSeconds() == time
                && latest.commitmentsBeforePolicy().version().equals(version)
                && latest.view().closedPrefixSha256().equals(prefix), "Decision source differs from closed evidence");
    }

    private Map<String, Object> source() {
        var view = latest.view();
        return object("iteration", view.iteration(), "epoch", view.epoch(), "viewSequence", view.viewSequence(),
                "decisionTimeSeconds", view.decisionTimeSeconds(), "closedThroughSeconds", nullable(view.closedThroughSeconds()),
                "closedEventCount", view.closedEventCount(), "closedPrefixSha256", view.closedPrefixSha256(),
                "commitmentVersion", latest.commitmentsBeforePolicy().version());
    }

    private static Map<String, Object> forecastDetail(CausalMixedServiceForecast.DetailedForecast value) {
        return object("demandProjectionId", value.demandProjectionId(), "forecastEndSeconds", value.forecastEndSeconds(),
                "arrivalProjectionEndSeconds", value.arrivalProjectionEndSeconds(), "newDemandPreparation", value.newDemandPreparation(),
                "currentWaitingPassengers", value.currentWaitingPassengers(), "initiallyRidingPassengers", value.initiallyRidingPassengers(),
                "futureArrivalCohorts", value.futureArrivalCohorts(), "protectedCohorts", value.protectedCohorts(),
                "protectedPassengerMass", value.protectedPassengerMass(), "knownFreightUnits", value.knownFreightUnits(),
                "hasProtectedPassengerCohort", value.hasProtectedPassengerCohort(),
                "protectedArrivalThroughSeconds", ods(value.protectedArrivalThroughSeconds()),
                "capacityArrivalThroughSeconds", ods(value.capacityArrivalThroughSeconds()),
                "omittedUnserviceableTailPassengerMass", value.omittedUnserviceableTailPassengerMass(),
                "approximation", value.approximation());
    }

    private static Map<String, Object> projection(RollingDeparturePolicy.Projection value) {
        double mass = 0, waiting = 0;
        for (String key : value.affectedPassengers().keySet().stream().sorted().toList()) {
            var cohort = value.affectedPassengers().get(key); mass += cohort.passengers();
            waiting += cohort.passengers() * cohort.meanWaitingSeconds();
        }
        return object("demandProjectionId", value.demandProjectionId(), "forecastEndSeconds", value.forecastEndSeconds(),
                "releasedFreightUnitCount", value.releasedFreightUnitIds().size(),
                "releasedFreightUnitSetSha256", sha(value.releasedFreightUnitIds().stream().sorted().toList()), "freight", value.freight(),
                "protectedCohorts", value.affectedPassengers().size(), "protectedPassengerMass", mass,
                "protectedTotalWaitingSeconds", waiting, "protectedMeanWaitingSeconds", mass == 0 ? null : waiting / mass,
                "hasProtectedPassengerCohort", mass > 0);
    }

    private static Map<String, Object> assessment(DispatchConstraintChecker.Assessment value) {
        return object("checks", value.checks().size(), "satisfiesRegisteredConstraints", value.satisfiesRegisteredConstraints(),
                "minimumMarginSeconds", value.checks().stream().mapToDouble(DispatchConstraintChecker.ConstraintCheck::marginSeconds).min().stream().boxed().findFirst().orElse(null),
                "violations", value.violations().stream().map(check -> object("kind", check.kind(), "point", check.point().orElse(null),
                        "connectionId", check.connectionId().orElse(null), "trips", check.trips(), "valueSeconds", check.valueSeconds(),
                        "boundSeconds", check.boundSeconds(), "marginSeconds", check.marginSeconds())).toList());
    }

    private static List<Map<String, Object>> ods(Map<ClosedPassengerArrivalForecast.Od, Double> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ClosedPassengerArrivalForecast.Od::stopId)
                .thenComparing(ClosedPassengerArrivalForecast.Od::destinationStopId)))
                .map(entry -> object("od", entry.getKey(), "throughSeconds", entry.getValue())).toList();
    }

    static List<Map<String, Object>> times(Map<TripKey, Double> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey(TRIPS))
                .map(entry -> object("trip", entry.getKey(), "departureSeconds", entry.getValue())).toList();
    }

    private static List<Map<String, Object>> changes(Map<TripKey, Double> reference, Map<TripKey, Double> candidate) {
        require(reference.keySet().equals(candidate.keySet()), "Candidate supply keys changed");
        var differences = new LinkedHashMap<TripKey, Double>();
        candidate.forEach((key, value) -> { if (!value.equals(reference.get(key))) differences.put(key, value); });
        return times(differences);
    }

    private static Double nullable(OptionalDouble value) { return value.isPresent() ? value.getAsDouble() : null; }
    private static Object deadline(double value) {
        if (value == Double.POSITIVE_INFINITY) return "POSITIVE_INFINITY_NATIVE_BLOCKED_OR_TERMINAL";
        require(Double.isFinite(value), "Invalid native deadline"); return value;
    }
    private void check() { require(Thread.currentThread() == owner && !closed && !failed, "Evidence writer unavailable/wrong thread"); }
    private void day(int iteration) { check(); require(session != null && session.iteration() == iteration, "Wrong evidence day"); }
    private void write(String type, Object payload) {
        check(); require(stream != null, "No evidence stream");
        try {
            stream.write(JSON.writeValueAsString(object("schemaVersion", SCHEMA, "record", ++records,
                    "iteration", session.iteration(), "type", type, "payload", payload))); stream.newLine();
        } catch (IOException failure) { failed = true; throw new UncheckedIOException(failure); }
    }
    private void flush() {
        try { stream.flush(); } catch (IOException failure) { failed = true; throw new UncheckedIOException(failure); }
    }
    private static String sha(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value))); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static Map<String, Object> object(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
