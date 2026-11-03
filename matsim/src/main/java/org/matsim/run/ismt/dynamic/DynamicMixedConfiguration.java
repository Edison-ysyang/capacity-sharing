package org.matsim.run.ismt.dynamic;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * V2-to-common MATSim configuration adapter. No legacy ExperimentSpec/arm is fabricated, and
 * no XML thread/event/scoring/replanning setting is silently replaced to match the declaration.
 * This does NOT install dynamic control or decode the registered resolved-config/policy files.
 * Complete historical non-dispatch comparison and actual injected-runtime checks remain separate.
 */
public final class DynamicMixedConfiguration {
    private DynamicMixedConfiguration() { }

    /** Config is intentionally mutable for assembly; the snapshot is an immutable point-in-time record. */
    public record PreparedConfiguration(Config config, DynamicMixedInputVerifier.Verification inputVerification,
            ResolvedNonDispatchConfiguration.Snapshot resolvedSnapshot) {
        public PreparedConfiguration {
            Objects.requireNonNull(config); Objects.requireNonNull(inputVerification); Objects.requireNonNull(resolvedSnapshot);
        }
        public boolean registeredResolvedDefinitionSemanticallyVerified() { return false; }
    }

    public static ExperimentConfiguration.CommonRequest commonRequest(DynamicMixedExperimentSpec spec) {
        Objects.requireNonNull(spec);
        var p = spec.design().protocol(); var r = spec.runtime();
        // This is the existing native phase listener's three-boundary protocol, not an old T identity.
        var phases = new ExperimentConfiguration.PhaseParameters(p.firstIteration(), p.activationIteration(),
                p.firstIteration(), p.activationIteration(), p.innovationOffIteration(), p.finalIteration(),
                r.behaviouralHorizonSeconds(), r.executionHorizonSeconds());
        return new ExperimentConfiguration.CommonRequest(spec.seeds(), spec.inputs(), phases, spec.freightDemand(),
                spec.policies(), spec.output().eventsWriteInterval(), spec.output().plansWriteInterval());
    }

    public static Config create(DynamicMixedExperimentSpec spec, Path workspace, Path output) throws IOException {
        return prepare(spec, workspace, output).config();
    }

    public static PreparedConfiguration prepare(DynamicMixedExperimentSpec spec, Path workspace, Path output) throws IOException {
        var verification = DynamicMixedInputVerifier.verify(workspace, spec);
        requireContainedNewOutput(workspace, output);
        Config config = ExperimentConfiguration.create(commonRequest(spec), workspace, output);
        DynamicMixedScenarioInputs.requireSupportedBoardingMode(
                ConfigUtils.addOrGetModule(config, CoTransportConfigGroup.class).toSettings(config), spec.design());
        requireRuntimeDeclaration(config, spec.runtime());
        return new PreparedConfiguration(config, verification, ResolvedNonDispatchConfiguration.snapshot(config));
    }

    /**
     * Checks configuration declarations at the behavioural-horizon assembly stage, not while the
     * native split-horizon listener has temporarily changed qsim.endTime. It does not prove which
     * event manager/engine will be injected or how many workers will actually be constructed.
     */
    public static void requireRuntimeDeclaration(Config config, DynamicMixedExperimentSpec.RuntimeDeclaration runtime) {
        Objects.requireNonNull(config); Objects.requireNonNull(runtime);
        List<String> differences = new ArrayList<>();
        same(differences, "qsim.numberOfThreads", runtime.qsimThreads(), config.qsim().getNumberOfThreads());
        same(differences, "global.numberOfThreads", runtime.globalThreads(), config.global().getNumberOfThreads());
        Integer eventThreads = runtime.eventWorkerSelection() == DynamicMixedExperimentSpec.EventWorkerSelection.DEFAULT_SINGLE_WORKER
                ? null : Integer.valueOf(runtime.expectedEventWorkers());
        same(differences, "eventsManager.numberOfThreads", eventThreads, config.eventsManager().getNumberOfThreads());
        same(differences, "eventsManager.synchronizeOnSimSteps", runtime.synchronizeOnSimSteps(), config.eventsManager().getSynchronizeOnSimSteps());
        same(differences, "eventsManager.oneThreadPerHandler", runtime.oneThreadPerHandler(), config.eventsManager().getOneThreadPerHandler());
        same(differences, "qsim.timeStepSize", runtime.qsimTimeStepSeconds(), config.qsim().getTimeStepSize());
        same(differences, "qsim.startTime", runtime.qsimStartTimeSeconds(), config.qsim().getStartTime().isDefined()
                ? config.qsim().getStartTime().seconds() : null);
        same(differences, "qsim.simStarttimeInterpretation", runtime.startTimeInterpretation(), config.qsim().getSimStarttimeInterpretation());
        same(differences, "qsim.simEndtimeInterpretation", runtime.endTimeInterpretation(), config.qsim().getSimEndtimeInterpretation());
        same(differences, "qsim.endTime (behavioural)", runtime.behaviouralHorizonSeconds(), config.qsim().getEndTime().isDefined()
                ? config.qsim().getEndTime().seconds() : null);
        var horizon = config.getModules().get(PlanAExecutionHorizonConfigGroup.GROUP_NAME);
        if (!(horizon instanceof PlanAExecutionHorizonConfigGroup typed))
            differences.add("planAExecutionHorizon: missing typed module");
        else same(differences, "planAExecutionHorizon.executionEndTime", runtime.executionHorizonSeconds(), typed.getExecutionEndTime());
        if (!differences.isEmpty()) throw new IllegalArgumentException("V2 runtime declaration differs from assembled configuration: " + differences);
    }

    private static void same(List<String> differences, String field, Object declared, Object actual) {
        if (!Objects.equals(declared, actual)) differences.add(field + " declared=" + declared + " actual=" + actual);
    }

    /** New v2 entry only: also reject a not-yet-created output below a symlink escaping the workspace. */
    private static void requireContainedNewOutput(Path workspace, Path output) throws IOException {
        Path root = workspace.toRealPath();
        if (!output.isAbsolute() || !output.normalize().startsWith(workspace.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Output must be absolute and inside the workspace");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Refusing existing output directory: " + output);
        Path parent = output.normalize().getParent();
        while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) parent = parent.getParent();
        if (parent == null || !Files.isDirectory(parent) || !parent.toRealPath().startsWith(root))
            throw new IOException("Output ancestor is not a contained directory: " + output);
    }
}
