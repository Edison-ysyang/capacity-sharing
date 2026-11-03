package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;

/** Synthetic wire-format corruption tests; real recorded Controller evidence is tested separately. */
class DynamicMixedDispatchValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TripKey A = new TripKey("line", "route", "a"), B = new TripKey("line", "route", "b");
    private static final ControlPointKey POINT = new ControlPointKey("origin", "forward");
    private static final DynamicMixedDesign DESIGN = new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED,
            new DynamicMixedDesign.Protocol(0, 2, 4, 5, 4, 5), true, new Limits(60, 60, 5));
    @TempDir Path temporary;

    private static DynamicMixedDispatchValidator.Inputs inputs() {
        return new DynamicMixedDispatchValidator.Inputs(DESIGN,
                List.of(new ScheduledTrip(A, "va", 20), new ScheduledTrip(B, "vb", 100)),
                List.of(new ControlPoint(POINT, List.of(new Call(A, 10), new Call(B, 10)), 40, 100, Optional.empty())),
                List.of(), Map.of(POINT, Map.of(A, "origin", B, "origin")), Map.of(A, "origin", B, "origin"),
                Map.of("unit", 50.0), Map.of(), 10, 10, 1);
    }

    @Test void completeInactiveEvidenceIsValidOnlyWithinItsExplicitScope() throws Exception {
        var report = validate(fixture(0, 20, 100), 0);
        assertTrue(report.valid(), report.errors().toString());
        assertEquals(4, report.serviceFacts()); assertEquals(0, report.committedChanges());
        assertEquals(1, report.actualDepartureIntervals().getFirst().observedAdjacentPairs());
        assertEquals(80.0, report.actualDepartureIntervals().getFirst().minimumObservedSeconds());
        assertFalse(report.unverifiedBoundaries().isEmpty());
    }

    @Test void actualIntervalInversionIsReportedWithoutSortingOrClaimingSignalSafety() throws Exception {
        var report = validate(fixture(0, 200, 100), 0);
        assertTrue(report.valid(), report.errors().toString());
        var point = report.actualDepartureIntervals().getFirst();
        assertEquals(1, point.orderInversions()); assertEquals(1, point.belowMinimum());
        assertEquals(-100.0, point.minimumObservedSeconds());
    }

    @Test void rejectsMissingEnd() throws Exception { var rows = fixture(0, 20, 100); rows.removeLast(); assertFalse(validate(rows, 0).valid()); }
    @Test void rejectsRepeatedStart() throws Exception { corrupt(rows -> rows.set(2, rows.getFirst().deepCopy())); }
    @Test void rejectsUnknownEnvelopeField() throws Exception { corrupt(rows -> rows.getFirst().put("invented", true)); }
    @Test void rejectsOldSchema() throws Exception { corrupt(rows -> rows.getFirst().put("schemaVersion", "legacy")); }
    @Test void rejectsWrongPhase() throws Exception { corrupt(rows -> p(rows, 0).withObject("phase").put("controlEligible", true)); }
    @Test void rejectsDailyAccumulatedCommitment() throws Exception { corrupt(rows -> ((ObjectNode) p(rows, 0).withArray("originalDepartures").get(1)).put("departureSeconds", 115)); }
    @Test void rejectsWrongPreparedPopulation() throws Exception { corrupt(rows -> p(rows, 1).put("verifiedFreightUnits", 1)); }
    @Test void rejectsFutureServiceFact() throws Exception { corrupt(rows -> p(rows, 2).withObject("source").put("closedThroughSeconds", 10)); }
    @Test void rejectsSequenceReplay() throws Exception { corrupt(rows -> ((ObjectNode) p(rows, 2).withArray("events").get(2)).put("closedSequence", 1)); }
    @Test void rejectsNegativeSourceRevision() throws Exception { corrupt(rows -> p(rows, 2).withObject("source").withObject("commitmentVersion").put("revision", -1)); }
    @Test void rejectsWrongVehicleBinding() throws Exception { corrupt(rows -> ((ObjectNode) p(rows, 2).withArray("events").get(0)).withObject("attributes").put("vehicleId", "other")); }
    @Test void rejectsReceiptCountMismatch() throws Exception { corrupt(rows -> p(rows, 3).put("copiedServiceEvents", 3)); }
    @Test void rejectsInventedTerminalCertification() throws Exception { corrupt(rows -> p(rows, 3).put("terminalPrefixCertified", true)); }
    @Test void rejectsPoisonedClosure() throws Exception { corrupt(rows -> p(rows, 3).withObject("observationClose").put("poisoned", true)); }
    @Test void rejectsUnexpectedControlInCommonHistory() throws Exception { corrupt(rows -> p(rows, 3).withObject("receipt").put("policyCalls", 1)); }
    @Test void rejectsNativeApplicationFailure() throws Exception {
        var rows = fixture(2, 20, 100);
        rows.add(3, row(2, 4, "native_application", Map.of("actualExecutionVerified", false, "status", "FAILED_NATIVE_APPLY")));
        rows.getLast().put("record", 5);
        assertFalse(validate(rows, 2).valid());
    }
    @Test void rejectsMalformedJsonRatherThanInventingAnEmptyDay() throws Exception {
        Path file = temporary.resolve("malformed.jsonl"); Files.writeString(file, "{\n");
        assertFalse(new DynamicMixedDispatchValidator().validate(temporary, file, 0, inputs()).valid());
    }
    @Test void rejectsDuplicateJsonKeys() throws Exception {
        Path file = temporary.resolve("duplicate.jsonl"); Files.writeString(file, "{\"record\":1,\"record\":2}\n");
        assertFalse(new DynamicMixedDispatchValidator().validate(temporary, file, 0, inputs()).valid());
    }
    @Test void rejectsTrailingJson() throws Exception {
        var rows = fixture(0, 20, 100);
        assertTrue(validate(rows, 0).valid(), "The complete control file must pass before adding trailing JSON");
        var lines = new ArrayList<>(rows.stream().map(ObjectNode::toString).toList());
        lines.set(0, lines.getFirst() + " {}");
        Path file = temporary.resolve("trailing.jsonl"); Files.write(file, lines);
        var report = new DynamicMixedDispatchValidator().validate(temporary, file, 0, inputs());
        assertFalse(report.valid());
        assertTrue(report.errors().stream().anyMatch(error -> error.contains("Trailing token")), report.errors().toString());
    }
    @Test void rejectsWorkspaceEscape() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("inside"));
        Path file = temporary.resolve("outside.jsonl"); Files.writeString(file, "{}");
        assertThrows(java.io.IOException.class, () -> new DynamicMixedDispatchValidator().validate(root, file, 0, inputs()));
    }
    @Test void explicitFacilityRegistrationCannotOmitACall() {
        var value = inputs();
        assertThrows(IllegalArgumentException.class, () -> new DynamicMixedDispatchValidator.Inputs(DESIGN, value.trips(), value.points(),
                List.of(), Map.of(POINT, Map.of(A, "origin")), value.originFacilities(), value.earliestFreightRelease(), Map.of(), 10, 10, 1));
    }

    private void corrupt(Consumer<List<ObjectNode>> mutation) throws Exception {
        var rows = fixture(0, 20, 100); mutation.accept(rows); assertFalse(validate(rows, 0).valid());
    }
    private DynamicMixedDispatchValidator.Report validate(List<ObjectNode> rows, int day) throws Exception {
        Path file = temporary.resolve(UUID.randomUUID() + ".jsonl");
        Files.write(file, rows.stream().map(ObjectNode::toString).toList());
        return new DynamicMixedDispatchValidator().validate(temporary, file, day, inputs());
    }
    private static ObjectNode p(List<ObjectNode> rows, int index) { return (ObjectNode) rows.get(index).get("payload"); }
    private static ObjectNode row(int day, int record, String type, Object payload) {
        ObjectNode row = JSON.createObjectNode(); row.put("schemaVersion", DynamicMixedDispatchEvidence.SCHEMA);
        row.put("record", record); row.put("iteration", day); row.put("type", type); row.set("payload", JSON.valueToTree(payload)); return row;
    }
    private static List<ObjectNode> fixture(int day, double first, double second) {
        var rows = new ArrayList<ObjectNode>(); var phase = DESIGN.phaseAt(day);
        var baseline = DynamicMixedDispatchEvidence.times(Map.of(A, 20.0, B, 100.0));
        rows.add(row(day, 1, "day_start", Map.of("iteration", day, "phase", phase, "initialVersion", new Version(1, 0),
                "originalDepartures", baseline, "enabled", phase.controlEligible(), "filteredServiceEventsOnly", true, "terminalPrefixCertified", false)));
        rows.add(row(day, 2, "prepared_inputs", Map.of("supportedInput", true, "actualTimingCertified", false,
                "verifiedFreightUnits", phase.freightTreatmentActive() ? 1 : 0)));
        var source = Map.of("iteration", day, "epoch", 1, "viewSequence", 301, "decisionTimeSeconds", 302.0,
                "closedThroughSeconds", 300.0, "closedEventCount", 4, "closedPrefixSha256", "a".repeat(64), "commitmentVersion", new Version(1, 300));
        var facts = new ArrayList<ObjectNode>();
        // Distinct vehicle chains permit cross-trip chronological order to differ from registered order.
        for (var entry : Map.of(A, first, B, second).entrySet().stream().sorted(Map.Entry.comparingByValue()).toList()) {
            var key = entry.getKey(); String vehicle = key.equals(A) ? "va" : "vb"; double time = entry.getValue();
            facts.add(fact(facts.size() + 1, time, "org.matsim.api.core.v01.events.TransitDriverStartsEvent",
                    Map.of("transitLineId", key.line(), "transitRouteId", key.route(), "departureId", key.departure(), "vehicleId", vehicle, "driverId", "d-" + vehicle)));
            facts.add(fact(facts.size() + 1, time + 10, "org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent", Map.of("vehicle", vehicle, "facility", "origin")));
        }
        rows.add(row(day, 3, "closed_service_facts", Map.of("source", source, "events", facts)));
        ObjectNode receipt = JSON.createObjectNode(); receipt.put("iteration", day); receipt.set("phase", JSON.valueToTree(phase));
        receipt.put("poisoned", false); receipt.put("preparedInputVerified", true); receipt.put("nativeEventsProcessingFinished", true);
        receipt.put("terminalPrefixCertified", false); receipt.put("closedViewCount", 301); receipt.put("committedChanges", 0);
        receipt.put("nativeDrivers", phase.controlEligible() ? 0 : 2); receipt.put("controlledDrivers", phase.controlEligible() ? 2 : 0); receipt.put("policyCalls", phase.controlEligible() ? 1 : 0);
        rows.add(row(day, 4, "day_end", Map.of("receipt", receipt,
                "observationClose", Map.of("poisoned", false, "receivedEvents", 4, "closedEvents", 4, "unpublishedEvents", 0),
                "terminalPrefixCertified", false, "closedEvents", 4, "closedViews", 301, "copiedServiceEvents", 4,
                "forecastAttempts", 0, "finalCommitments", baseline,
                "observedDriverStarts", DynamicMixedDispatchEvidence.times(Map.of(A, first, B, second)), "elapsedNanos", 1000)));
        return rows;
    }
    private static ObjectNode fact(int sequence, double time, String kind, Map<String, String> attributes) {
        ObjectNode result = JSON.createObjectNode(); result.put("closedSequence", sequence); result.put("occurredAtSeconds", time);
        result.put("eventClass", kind); result.put("availableBySeconds", time); result.putNull("actualReceiptSeconds");
        result.set("firstCoveredByDrain", JSON.valueToTree(Map.of("sequence", (int) time + 1, "timeSeconds", time)));
        result.set("attributes", JSON.valueToTree(attributes)); return result;
    }
}
