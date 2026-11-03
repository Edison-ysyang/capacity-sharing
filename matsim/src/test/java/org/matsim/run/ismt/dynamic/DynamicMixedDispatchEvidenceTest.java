package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.mockito.Mockito.*;

/** File boundaries here; full records are parsed against actual events in Controller native tests. */
class DynamicMixedDispatchEvidenceTest {
    @TempDir Path temporary;

    @Test void exclusivelyCreatesANewContainedDirectoryAndDoesNotOverwrite() throws Exception {
        Path target = temporary.resolve("evidence");
        try (var unused = new DynamicMixedDispatchEvidence(temporary, target)) {
            assertTrue(Files.isDirectory(target));
            assertThrows(IOException.class, () -> new DynamicMixedDispatchEvidence(temporary, target));
        }
        try (var files = Files.list(target)) { assertEquals(0, files.count(), "No day, no fabricated receipt"); }
    }

    @Test void rejectsWorkspaceRootAndNonexistentParent() {
        assertThrows(IOException.class, () -> new DynamicMixedDispatchEvidence(temporary, temporary));
        assertThrows(IOException.class, () -> new DynamicMixedDispatchEvidence(temporary, temporary.resolve("missing/day")));
    }

    @Test void rejectsSymlinkEscapingDeclaredWorkspace() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        Path sibling = Files.createDirectory(temporary.resolve("sibling"));
        Files.createSymbolicLink(root.resolve("escape"), sibling);
        assertThrows(IOException.class, () -> new DynamicMixedDispatchEvidence(root, root.resolve("escape/evidence")));
        assertFalse(Files.exists(sibling.resolve("evidence")));
    }

    @Test void noDayCannotBeFinishedOrCertifiedAndCloseIsIdempotent() throws Exception {
        var evidence = new DynamicMixedDispatchEvidence(temporary, temporary.resolve("evidence"));
        assertThrows(IllegalStateException.class, () -> evidence.finishDay(0));
        evidence.close(); evidence.close();
        assertThrows(IllegalStateException.class, () -> evidence.finishDay(0));
    }

    @Test void wrongThreadCannotTakeWriterOwnership() throws Exception {
        try (var evidence = new DynamicMixedDispatchEvidence(temporary, temporary.resolve("evidence"))) {
            var caught = new AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { evidence.finishDay(0); } catch (Throwable failure) { caught.set(failure); } });
            thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, caught.get());
        }
    }

    @Test void tripIdentityIsStructuredAndStableAcrossMapInsertionOrder() {
        var first = new TripKey("a", "same", "d"); var second = new TripKey("b", "same", "d");
        var left = new LinkedHashMap<TripKey, Double>(); left.put(second, 20.0); left.put(first, 10.0);
        var right = new LinkedHashMap<TripKey, Double>(); right.put(first, 10.0); right.put(second, 20.0);
        assertEquals(DynamicMixedDispatchEvidence.times(left), DynamicMixedDispatchEvidence.times(right));
        assertEquals(Map.of("trip", first, "departureSeconds", 10.0), DynamicMixedDispatchEvidence.times(left).getFirst());
        assertEquals(2, DynamicMixedDispatchEvidence.times(left).size());
    }

    @Test void failureClosePreservesPartialApplicationAndOptionalVersionWithoutCreatingSuccessfulDay() throws Exception {
        // Synthetic file-format fixture, not a certificate that this native failure occurred.
        var key = new TripKey("line", "route", "trip"); var version = new Version(1, 0); var next = new Version(1, 1);
        var decision = new DecisionRecord(0, version, next, 10, List.of(),
                List.of(new CommitmentChange(key, "vehicle", 100, 100, 110)));
        var refresh = new org.matsim.core.mobsim.qsim.pt.ControlledTransitDriverAgent.RefreshResult(
                org.matsim.core.mobsim.qsim.pt.ControlledTransitDriverAgent.RefreshOutcome.BLOCKED,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Optional.of(next));
        var application = new org.matsim.core.mobsim.qsim.pt.ControlledTransitDispatchBridge.ApplicationAudit(
                org.matsim.core.mobsim.qsim.pt.ControlledTransitDispatchBridge.Status.FAILED_NATIVE_APPLY,
                new Batch(version, 10, List.of(), Map.of(key, 110.0)), Optional.of(decision), Optional.empty(),
                List.of(new org.matsim.core.mobsim.qsim.pt.ControlledTransitDispatchBridge.RefreshedTrip(key, "driver", refresh)),
                List.of(), "synthetic-failure-stage", "format-only fixture");
        var session = mock(DynamicMixedQSimModule.Session.class);
        when(session.iteration()).thenReturn(0); when(session.phase()).thenReturn(new DynamicMixedDesign.Phase(0, false, false, true, false));
        when(session.initialCommitments()).thenReturn(new Snapshot(0, version, 0, false, Map.of(key, 100.0), Set.of(), Map.of()));
        when(session.commitmentHistory()).thenReturn(List.of(decision)); when(session.applications()).thenReturn(List.of(application));
        Path directory = temporary.resolve("evidence");
        try (var evidence = new DynamicMixedDispatchEvidence(temporary, directory)) { evidence.sessionStarted(session); }
        var parser = new com.fasterxml.jackson.databind.ObjectMapper();
        var lines = Files.readAllLines(directory.resolve("iteration-000.jsonl"));
        assertEquals(4, lines.size());
        assertEquals("accepted_commitment", parser.readTree(lines.get(1)).path("type").asText());
        var recorded = parser.readTree(lines.get(2));
        assertEquals("native_application", recorded.path("type").asText());
        assertEquals("FAILED_NATIVE_APPLY", recorded.path("payload").path("status").asText());
        assertEquals(1, recorded.path("payload").path("refreshedTrips").get(0).path("acceptedVersion").path("revision").asInt());
        assertEquals("POSITIVE_INFINITY_NATIVE_BLOCKED_OR_TERMINAL",
                recorded.path("payload").path("refreshedTrips").get(0).path("effectiveDeadlineSeconds").asText());
        assertEquals("day_incomplete", parser.readTree(lines.getLast()).path("type").asText());
        verify(session, never()).receipt();
    }
}
