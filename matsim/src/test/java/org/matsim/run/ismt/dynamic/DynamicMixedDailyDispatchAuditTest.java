package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.ControllerListenerManagerImpl;
import org.matsim.core.controler.Injector;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.ControllerListener;
import org.matsim.core.controler.listener.IterationEndsListener;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Reuses the validator's synthetic wire fixture; real Guice/listener dispatch, but no Controller/QSim run. */
class DynamicMixedDailyDispatchAuditTest {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void completeRangeWritesImmutableSerializableReportsWithoutChangingEvidence() throws Exception {
        Path run = run(); var input = inputs(); var calls = new ArrayList<Integer>();
        var audit = new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), day -> { calls.add(day); return input; });
        assertTrue(calls.isEmpty(), "No prepared input exists at constructor time");
        assertTrue(audit.reports().isEmpty()); assertThrows(IllegalStateException.class, audit::requireComplete);
        List<DynamicMixedDailyDispatchAudit.DayAudit> initial = audit.reports();
        for (int day = 0; day <= 5; day++) {
            Path file = evidence(run, day); writeFixture(file, day); byte[] before = Files.readAllBytes(file);
            audit.notifyIterationEnds(end(day));
            assertArrayEquals(before, Files.readAllBytes(file));
            var value = audit.reports().get(day);
            assertTrue(value.passed()); assertEquals(day, value.iteration()); assertNull(value.failure());
            assertEquals(temporary.toRealPath().relativize(file).toString(), value.evidenceFile());
            Path report = temporary.resolve(value.reportFile());
            assertEquals(run.resolve("audit/dispatch/iteration-%03d.json".formatted(day)), report);
            assertEquals(value, JSON.readValue(Files.readAllBytes(report), DynamicMixedDailyDispatchAudit.DayAudit.class));
        }
        audit.requireComplete(); assertEquals(List.of(0, 1, 2, 3, 4, 5), calls);
        assertTrue(initial.isEmpty()); assertThrows(UnsupportedOperationException.class, () -> audit.reports().clear());
        assertEquals(6, audit.reports().size());
        assertTrue(audit.reports().stream().allMatch(value -> !value.validation().unverifiedBoundaries().isEmpty()));
    }

    @Test void moduleBindsExactlyThisListenerAndRealPriorityRunsAfterClosedFileBeforeCompletion() throws Exception {
        Path run = run(); var input = inputs(); var order = new ArrayList<String>();
        var audit = new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), day -> { order.add("audit"); return input; });
        var injector = Injector.createInjector(ConfigUtils.createConfig(), audit.module());
        Set<ControllerListener> listeners = injector.getInstance(Key.get(new TypeLiteral<Set<ControllerListener>>() { }));
        assertEquals(Set.of(audit), listeners); assertEquals(-1600, audit.priority(), 0);
        var manager = new ControllerListenerManagerImpl(); listeners.forEach(manager::addControllerListener);
        manager.addControllerListener(listener(-1000, day -> {
            order.add("closed-file-writer");
            try { writeFixture(evidence(run, day), day); } catch (Exception failure) { throw new IllegalStateException(failure); }
        }));
        manager.addControllerListener(listener(-1500, day -> { order.add("history-end"); assertTrue(Files.isRegularFile(evidence(run, day))); }));
        manager.addControllerListener(listener(-2000, day -> { order.add("completion"); assertTrue(audit.reports().getLast().passed()); }));
        manager.fireControllerIterationEndsEvent(0, false);
        assertEquals(List.of("closed-file-writer", "history-end", "audit", "completion"), order);
        assertEquals(1, audit.reports().size()); assertThrows(IllegalStateException.class, audit::requireComplete);
    }

    @Test void invalidEvidencePersistsFullValidatorReportAndStopsTheRealCompletionListener() throws Exception {
        Path run = run(); var input = inputs(); var rows = fixture(0); rows.removeLast();
        Path source = evidence(run, 0); Files.write(source, rows.stream().map(ObjectNode::toString).toList());
        byte[] original = Files.readAllBytes(source);
        var audit = new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), ignored -> input);
        var manager = new ControllerListenerManagerImpl(); manager.addControllerListener(audit);
        AtomicInteger completed = new AtomicInteger(); manager.addControllerListener(listener(-2000, day -> completed.incrementAndGet()));
        assertThrows(IllegalStateException.class, () -> manager.fireControllerIterationEndsEvent(0, false));
        assertEquals(0, completed.get()); assertArrayEquals(original, Files.readAllBytes(source));
        var report = audit.reports().getFirst(); assertFalse(report.passed()); assertNotNull(report.validation());
        assertFalse(report.validation().valid()); assertFalse(report.validation().errors().isEmpty()); assertNotNull(report.failure());
        var saved = JSON.readTree(Files.readAllBytes(temporary.resolve(report.reportFile())));
        assertFalse(saved.path("validation").path("valid").booleanValue()); assertFalse(saved.path("validation").path("errors").isEmpty());
        assertThrows(IllegalStateException.class, audit::requireComplete);
        assertThrows(IllegalStateException.class, () -> audit.notifyIterationEnds(end(0)));
        assertEquals(1, audit.reports().size(), "A poisoned audit does not silently retry or invent another successful day");
    }

    @Test void missingDayIsRecordedAsUnavailableNotAnEmptySuccessfulValidation() throws Exception {
        Path run = run(); var input = inputs();
        var audit = new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), ignored -> input);
        assertThrows(IllegalStateException.class, () -> audit.notifyIterationEnds(end(0)));
        var value = audit.reports().getFirst(); assertNull(value.validation()); assertFalse(value.passed());
        assertTrue(value.failure().contains("Missing regular closed dispatch evidence"));
        assertTrue(Files.isRegularFile(temporary.resolve(value.reportFile())));
        assertFalse(Files.exists(evidence(run, 0)), "Audit never creates a missing native evidence day");
    }

    @Test void missingPreparedInputCannotBeReplacedWithInventedContracts() throws Exception {
        Path run = run(); writeFixture(evidence(run, 0), 0); var input = inputs();
        AtomicInteger calls = new AtomicInteger();
        var audit = new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), day -> { calls.incrementAndGet(); return null; });
        assertEquals(0, calls.get()); assertThrows(IllegalStateException.class, () -> audit.notifyIterationEnds(end(0)));
        assertEquals(1, calls.get()); var value = audit.reports().getFirst();
        assertNull(value.validation()); assertTrue(value.failure().contains("No actual prepared dispatch validation inputs"));
        assertTrue(Files.isRegularFile(temporary.resolve(value.reportFile())));
    }

    @Test void preparationSupplierExceptionIsPersistedAndPoisonsFurtherDays() throws Exception {
        Path run = run(); var input = inputs();
        var audit = new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), day -> { throw new IllegalStateException("actual preparation not reached"); });
        assertThrows(IllegalStateException.class, () -> audit.notifyIterationEnds(end(0)));
        assertTrue(audit.reports().getFirst().failure().contains("actual preparation not reached"));
        assertThrows(IllegalStateException.class, () -> audit.notifyIterationEnds(end(1)));
        assertEquals(1, audit.reports().size());
    }

    @Test void preparedProtocolMismatchIsNotMistakenForTheRequestedRun() throws Exception {
        Path run = run(); var input = inputs();
        var other = new DynamicMixedDesign.Protocol(0, 3, 4, 5, 4, 5);
        var audit = new DynamicMixedDailyDispatchAudit(temporary, run, other, ignored -> input);
        assertThrows(IllegalStateException.class, () -> audit.notifyIterationEnds(end(0)));
        assertTrue(audit.reports().getFirst().failure().contains("Prepared dispatch protocol differs"));
    }

    @Test void repeatedDayDoesNotOverwriteItsPassedReportOrReadInputsAgain() throws Exception {
        Path run = run(); var input = inputs(); writeFixture(evidence(run, 0), 0); AtomicInteger calls = new AtomicInteger();
        var audit = new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), day -> { calls.incrementAndGet(); return input; });
        audit.notifyIterationEnds(end(0)); Path first = temporary.resolve(audit.reports().getFirst().reportFile()); byte[] before = Files.readAllBytes(first);
        assertThrows(IllegalStateException.class, () -> audit.notifyIterationEnds(end(0)));
        assertArrayEquals(before, Files.readAllBytes(first)); assertEquals(1, calls.get());
        assertEquals(2, audit.reports().size()); assertTrue(audit.reports().getFirst().passed()); assertFalse(audit.reports().getLast().passed());
        assertTrue(audit.reports().getLast().reportFile().endsWith("rejected-call-001.json"));
        assertTrue(Files.isRegularFile(temporary.resolve(audit.reports().getLast().reportFile())));
        assertThrows(IllegalStateException.class, audit::requireComplete);
    }

    @Test void outOfOrderOrOutOfRangeDayFailsBeforeAnyPreparedInputRead() throws Exception {
        var input = inputs();
        for (int invalid : List.of(-1, 1, 6)) {
            Path run = Files.createDirectory(temporary.resolve("bad-day-" + invalid)); AtomicInteger calls = new AtomicInteger();
            var audit = new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), day -> { calls.incrementAndGet(); return input; });
            assertThrows(IllegalStateException.class, () -> audit.notifyIterationEnds(end(invalid)));
            assertEquals(0, calls.get()); assertFalse(audit.reports().getFirst().passed());
            assertTrue(audit.reports().getFirst().failure().contains("out-of-range"));
            assertTrue(Files.isRegularFile(temporary.resolve(audit.reports().getFirst().reportFile())));
        }
    }

    @Test void repeatedModuleRequestAndRepeatedRealInstallationAreRejected() throws Exception {
        var input = inputs(); Path run = run();
        var audit = new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), ignored -> input);
        audit.module(); assertThrows(IllegalStateException.class, audit::module);
        assertTrue(audit.reports().getFirst().failure().contains("requested more than once"));
        Path another = Files.createDirectory(temporary.resolve("another-run"));
        var second = new DynamicMixedDailyDispatchAudit(temporary, another, input.design().protocol(), ignored -> input);
        var module = second.module(); Injector.createInjector(ConfigUtils.createConfig(), module);
        assertThrows(RuntimeException.class, () -> Injector.createInjector(ConfigUtils.createConfig(), module));
        assertTrue(second.reports().getFirst().failure().contains("installed more than once"));
    }

    @Test void collisionPreservesExistingReportAndWritesSeparateFailure() throws Exception {
        Path run = run(); var input = inputs(); writeFixture(evidence(run, 0), 0);
        var audit = new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), ignored -> input);
        Path collision = run.resolve("audit/dispatch/iteration-000.json"); Files.writeString(collision, "preserve this report");
        assertThrows(IllegalStateException.class, () -> audit.notifyIterationEnds(end(0)));
        assertEquals("preserve this report", Files.readString(collision));
        var value = audit.reports().getFirst(); assertFalse(value.passed()); assertNotNull(value.failure());
        assertTrue(value.reportFile().endsWith("rejected-call-000.json")); assertTrue(Files.isRegularFile(temporary.resolve(value.reportFile())));
        assertThrows(IllegalStateException.class, audit::requireComplete);
    }

    @Test void existingAuditDirectoryIsNeverResumedOrOverwritten() throws Exception {
        Path run = run(); var input = inputs(); Path dir = Files.createDirectories(run.resolve("audit/dispatch"));
        Path sentinel = dir.resolve("keep.json"); Files.writeString(sentinel, "old audit");
        assertThrows(IOException.class, () -> new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), ignored -> input));
        assertEquals("old audit", Files.readString(sentinel));
    }

    @Test void outputAndEvidenceSymlinkEscapesAreRejectedWithoutTouchingTheirTargets() throws Exception {
        Path run = run(); var input = inputs(); Path other = Files.createDirectory(temporary.resolve("other"));
        Files.createSymbolicLink(run.resolve("audit"), other);
        assertThrows(IOException.class, () -> new DynamicMixedDailyDispatchAudit(temporary, run, input.design().protocol(), ignored -> input));
        assertFalse(Files.exists(other.resolve("dispatch")));
        Path secondRun = Files.createDirectory(temporary.resolve("second-run"));
        var audit = new DynamicMixedDailyDispatchAudit(temporary, secondRun, input.design().protocol(), ignored -> input);
        Path nativeSource = other.resolve("original.jsonl"); writeFixture(nativeSource, 0); byte[] bytes = Files.readAllBytes(nativeSource);
        Files.createDirectory(secondRun.resolve("dispatch")); Files.createSymbolicLink(evidence(secondRun, 0), nativeSource);
        assertThrows(IllegalStateException.class, () -> audit.notifyIterationEnds(end(0)));
        assertArrayEquals(bytes, Files.readAllBytes(nativeSource)); assertNull(audit.reports().getFirst().validation());
    }

    private Path run() throws IOException {
        Path result = Files.createDirectory(temporary.resolve("run")); Files.createDirectory(result.resolve("dispatch")); return result;
    }
    private static Path evidence(Path run, int day) { return run.resolve("dispatch/iteration-%03d.jsonl".formatted(day)); }
    private static IterationEndsEvent end(int day) { return new IterationEndsEvent(null, day, day == 5); }
    private static IterationEndsListener listener(double priority, java.util.function.IntConsumer action) {
        return new IterationEndsListener() {
            @Override public double priority() { return priority; }
            @Override public void notifyIterationEnds(IterationEndsEvent event) { action.accept(event.getIteration()); }
        };
    }
    private static void writeFixture(Path file, int day) throws Exception { Files.write(file, fixture(day).stream().map(ObjectNode::toString).toList()); }

    // Existing fixture remains private and unchanged; reuse it rather than a second hand-written wire schema.
    private static DynamicMixedDispatchValidator.Inputs inputs() throws Exception {
        return (DynamicMixedDispatchValidator.Inputs) fixtureMethod("inputs", new Class<?>[0], new Object[0]);
    }
    @SuppressWarnings("unchecked")
    private static List<ObjectNode> fixture(int day) throws Exception {
        return (List<ObjectNode>) fixtureMethod("fixture", new Class<?>[]{int.class, double.class, double.class}, new Object[]{day, 20.0, 100.0});
    }
    private static Object fixtureMethod(String name, Class<?>[] types, Object[] arguments) throws Exception {
        var method = DynamicMixedDispatchValidatorTest.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
}
