package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Reads each already-closed dispatch JSONL after the evidence writer (-1000) and
 * historical end gate (-1500), but before the runner's completion counter (-2000).
 * Never calls finishDay, writes native events, or grants formal/reuse authorization.
 * Reports retain the underlying validator's restricted scope and unverified bounds.
 */
public final class DynamicMixedDailyDispatchAudit implements IterationEndsListener {
    public static final String SCHEMA = "ismt-dynamic-daily-dispatch-audit-v1";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    /** Null validation means validation could not start/finish; it is never an empty successful report. */
    public record DayAudit(String schemaVersion, int iteration, String evidenceFile, String reportFile,
                           DynamicMixedDispatchValidator.Report validation, String failure) {
        public boolean passed() { return validation != null && validation.valid() && failure == null; }
    }

    private final Path workspace, runRoot, directory;
    private final DynamicMixedDesign.Protocol protocol;
    private final IntFunction<DynamicMixedDispatchValidator.Inputs> preparedInputs;
    private final List<DayAudit> reports = new ArrayList<>();
    private boolean moduleRequested, poisoned;
    private com.google.inject.Injector installationOwner;

    public DynamicMixedDailyDispatchAudit(Path workspace, Path runRoot, DynamicMixedDesign.Protocol protocol,
            IntFunction<DynamicMixedDispatchValidator.Inputs> preparedInputs) throws IOException {
        this.workspace = Objects.requireNonNull(workspace).toRealPath();
        this.protocol = Objects.requireNonNull(protocol); this.preparedInputs = Objects.requireNonNull(preparedInputs);
        this.runRoot = DynamicMixedExperimentRunner.containedOutput(this.workspace, Objects.requireNonNull(runRoot));
        if (!Files.isDirectory(this.runRoot, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Daily audit requires an existing caller-owned run directory");
        Path requested = DynamicMixedExperimentRunner.containedOutput(this.workspace, this.runRoot.resolve("audit/dispatch"));
        if (Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Refusing existing daily dispatch audit directory");
        Files.createDirectories(requested.getParent());
        this.directory = Files.createDirectory(requested);
    }

    public synchronized AbstractModule module() {
        healthy();
        if (moduleRequested) throw lifecycleFailure(-1, "Daily dispatch audit module requested more than once");
        moduleRequested = true;
        return new AbstractModule() {
            @com.google.inject.Inject private com.google.inject.Injector bootstrapIdentity;
            @Override public void install() {
                synchronized (DynamicMixedDailyDispatchAudit.this) {
                    healthy();
                    // MATSim inspects a module repeatedly via Elements.getElements before
                    // creating its child injector. Those same-owner passes are not new runs.
                    if (installationOwner != null && installationOwner != bootstrapIdentity)
                        throw lifecycleFailure(-1, "Daily dispatch audit module installed more than once in different injectors");
                    installationOwner = Objects.requireNonNull(bootstrapIdentity, "MATSim module bootstrap injection required");
                }
                addControllerListenerBinding().toInstance(DynamicMixedDailyDispatchAudit.this);
            }
        };
    }

    @Override public double priority() { return -1600; }

    @Override public synchronized void notifyIterationEnds(IterationEndsEvent event) {
        healthy();
        int day = Objects.requireNonNull(event, "iteration-end event").getIteration();
        if (!protocol.contains(day) || day != (long) protocol.firstIteration() + reports.size())
            throw lifecycleFailure(day, "Missing, repeated, out-of-order or out-of-range daily dispatch audit");
        Path evidence = evidence(day), reportFile = directory.resolve(dayName(day) + ".json");
        DynamicMixedDispatchValidator.Report validation = null;
        Throwable failure = null;
        try {
            var input = Objects.requireNonNull(preparedInputs.apply(day), "No actual prepared dispatch validation inputs for iteration " + day);
            if (!input.design().protocol().equals(protocol)) throw new IllegalStateException("Prepared dispatch protocol differs from daily audit protocol");
            // Recheck directory containment at use time. Do not follow a retargeted evidence directory/file.
            Path dispatch = DynamicMixedExperimentRunner.containedOutput(workspace, evidence.getParent());
            if (!Files.isDirectory(dispatch, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(evidence, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Missing regular closed dispatch evidence: " + evidence);
            validation = new DynamicMixedDispatchValidator().validate(workspace, evidence, day, input);
            if (!validation.valid()) failure = new IllegalStateException("Scoped daily dispatch checks failed: " + validation.errors());
        } catch (IOException | RuntimeException | Error caught) { failure = caught; }
        DayAudit value = record(day, evidence, reportFile, validation, failure);
        try { persist(reportFile, value); }
        catch (IOException | RuntimeException writeFailure) {
            if (failure != null) writeFailure.addSuppressed(failure);
            // A collision or write failure must never overwrite an earlier report. Preserve a separate failure if possible.
            throw saveFailure(day, validation, writeFailure);
        }
        reports.add(value);
        if (failure != null) {
            poisoned = true;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Daily dispatch audit failed; report retained at " + reportFile, failure);
        }
    }

    public synchronized List<DayAudit> reports() { return List.copyOf(reports); }

    /** Read-only completion check; a returned Controller alone is not a completed audited run. */
    public synchronized void requireComplete() {
        healthy();
        long expected = (long) protocol.finalIteration() - protocol.firstIteration() + 1;
        if (reports.size() != expected || reports.stream().anyMatch(value -> !value.passed()))
            throw new IllegalStateException("Not every registered iteration has a successful persisted dispatch audit");
        for (int index = 0; index < reports.size(); index++)
            if (reports.get(index).iteration() != protocol.firstIteration() + index)
                throw new IllegalStateException("Daily dispatch audit coverage is not consecutive");
    }

    private IllegalStateException lifecycleFailure(int day, String message) {
        return saveFailure(day, null, new IllegalStateException(message));
    }

    private IllegalStateException saveFailure(int day, DynamicMixedDispatchValidator.Report validation, Throwable failure) {
        poisoned = true;
        Path file = directory.resolve(String.format(Locale.ROOT, "rejected-call-%03d.json", reports.size()));
        DayAudit value = record(day, day >= 0 ? evidence(day) : null, file, validation, failure);
        reports.add(value);
        try { persist(file, value); }
        catch (IOException | RuntimeException writeFailure) { failure.addSuppressed(writeFailure); }
        return new IllegalStateException("Daily dispatch audit failed; earlier reports/output retained; failure report " + file, failure);
    }

    private DayAudit record(int day, Path evidence, Path report, DynamicMixedDispatchValidator.Report validation, Throwable failure) {
        return new DayAudit(SCHEMA, day, evidence == null ? null : workspace.relativize(evidence).toString(),
                workspace.relativize(report).toString(), validation,
                failure == null ? null : failure.getClass().getName() + ": " + failure.getMessage());
    }

    private void persist(Path path, DayAudit value) throws IOException {
        if (!DynamicMixedExperimentRunner.containedOutput(workspace, directory).equals(directory))
            throw new IOException("Daily audit directory changed");
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }

    private Path evidence(int day) { return runRoot.resolve("dispatch").resolve(dayName(day) + ".jsonl"); }
    private static String dayName(int day) { return String.format(Locale.ROOT, "iteration-%03d", day); }
    private void healthy() {
        if (poisoned) throw new IllegalStateException("Daily dispatch audit is poisoned; failed evidence cannot be upgraded or retried");
    }
}
