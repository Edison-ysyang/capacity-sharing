package org.matsim.run.ismt.dynamic;

import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ScientificRunContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned scientific registration for the new four-arm design, independent of
 * the legacy TIMETABLE_ONLY experiment. All values and definition-file references
 * are explicit. A valid object is a complete declaration in this schema, NOT an
 * executable policy, verified file/content/coverage manifest, adopted assumption,
 * user approval, proven feasible baseline, frozen build or historical reuse gate.
 *
 * <p>Machine-readable policy/forecast/protection/resource definitions are separately
 * versioned and hash-bound. Their semantic decoders and native conformance checks
 * must succeed before a future runner can use them; a nonempty FileRef is not such
 * validation. No boolean approval/canRun field, launcher or scientific default is
 * supplied. Confirmation for diagnostics and authorization for formal runs remain
 * separate, external evidence bound to the appropriate exact specification hash.</p>
 */
public record DynamicMixedExperimentSpec(String schemaVersion, String experimentId, String blockId,
        DynamicMixedDesign design, ExperimentSpec.Seeds seeds, ExperimentSpec.Inputs inputs,
        ExperimentSpec.FreightDemand freightDemand, ExperimentSpec.Policies policies,
        RuntimeDeclaration runtime, ControlRegistration control, OutputOptions output, String validationProfile) {
    public static final String SCHEMA = "ismt-dynamic-experiment-v2";

    /** The formal-registration runtime target is the already tested historical-M event mechanism. */
    public enum EventMode { NATIVE_SIM_STEP_PARALLEL }
    public enum EventWorkerSelection { DEFAULT_SINGLE_WORKER, EXPLICIT_SINGLE_WORKER }
    public enum InformationModel { BASELINE_PRETRIP_NO_ADDED_INTRADAY_REROUTING }
    public enum ResetMode { FRESH_ORIGINAL_BASELINE_EACH_ITERATION }
    public enum BaselineTreatment { NO_PRE_SERVICE_REPAIR, FREIGHT_BLIND_PRE_SERVICE_REPAIR }
    public enum OperatingBasis { SYNTHETIC_ASSUMPTIONS, LINE_DOCUMENTED_ASSUMPTIONS }

    /** Declared format plus exact file bytes; neither format nor content is authenticated by this record. */
    public record RegisteredDefinition(String schemaVersion, ExperimentSpec.FileRef file) {
        public RegisteredDefinition {
            identifier(schemaVersion, "definition schema"); Objects.requireNonNull(file, "definition file");
        }
    }

    /** Remaining modules and ordered parameter sets are bound by the full resolved-config definition. */
    public record RuntimeDeclaration(int qsimThreads, int globalThreads, EventMode eventMode,
            EventWorkerSelection eventWorkerSelection, int expectedEventWorkers,
            boolean synchronizeOnSimSteps, boolean oneThreadPerHandler, int observationLagSteps,
            double qsimTimeStepSeconds, double qsimStartTimeSeconds,
            QSimConfigGroup.StarttimeInterpretation startTimeInterpretation,
            QSimConfigGroup.EndtimeInterpretation endTimeInterpretation,
            double behaviouralHorizonSeconds, double executionHorizonSeconds,
            RegisteredDefinition resolvedConfiguration) {
        public RuntimeDeclaration {
            if (qsimThreads <= 0 || globalThreads <= 0) throw new IllegalArgumentException("Thread counts must be positive");
            Objects.requireNonNull(eventMode); Objects.requireNonNull(eventWorkerSelection);
            if (expectedEventWorkers != 1 || !synchronizeOnSimSteps || oneThreadPerHandler || observationLagSteps != 2)
                throw new IllegalArgumentException("This registration requires the native sim-step single event worker and two-step observation lag");
            positive(qsimTimeStepSeconds, "QSim step"); nonnegative(qsimStartTimeSeconds, "QSim start");
            positive(behaviouralHorizonSeconds, "behavioural horizon"); positive(executionHorizonSeconds, "execution horizon");
            if (qsimStartTimeSeconds >= behaviouralHorizonSeconds || executionHorizonSeconds <= behaviouralHorizonSeconds)
                throw new IllegalArgumentException("Require start < behavioural horizon < execution horizon");
            Objects.requireNonNull(startTimeInterpretation); Objects.requireNonNull(endTimeInterpretation);
            if (endTimeInterpretation != QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime)
                throw new IllegalArgumentException("Split-horizon execution requires onlyUseEndtime");
            Objects.requireNonNull(resolvedConfiguration);
        }
    }

    /** Absolute simulation-day anchor, recurrence, lookahead and candidate spacing; no defaults. */
    public record DecisionTiming(double firstDecisionTimeSeconds, double decisionIntervalSeconds,
            double lookAheadSeconds, double candidateStepSeconds) {
        public DecisionTiming {
            nonnegative(firstDecisionTimeSeconds, "first decision time"); positive(decisionIntervalSeconds, "decision interval");
            positive(lookAheadSeconds, "lookahead"); positive(candidateStepSeconds, "candidate step");
        }
    }

    /**
     * Policy implementation/definition, causal information, reset and baseline
     * treatment are distinct identities. Repair cannot be hidden as a forecast or
     * constraints-only change. Merely naming repair here does NOT authorize it.
     * Numerical protection and point/connection bounds must be in the registered
     * machine-readable definition files; their absence cannot be replaced by a
     * Markdown description, a default, or a permissive untyped parameter map.
     */
    public record ControlRegistration(String policyId, InformationModel informationModel, ResetMode resetMode,
            BaselineTreatment baselineTreatment, OperatingBasis operatingBasis, DecisionTiming timing,
            RegisteredDefinition policyDefinition, RegisteredDefinition constraintDefinition,
            RegisteredDefinition forecastDefinition, RegisteredDefinition passengerProtectionDefinition,
            RegisteredDefinition informationDefinition, RegisteredDefinition baselineTreatmentDefinition) {
        public ControlRegistration {
            identifier(policyId, "policy implementation");
            Objects.requireNonNull(informationModel); Objects.requireNonNull(resetMode);
            Objects.requireNonNull(baselineTreatment); Objects.requireNonNull(operatingBasis); Objects.requireNonNull(timing);
            Objects.requireNonNull(policyDefinition); Objects.requireNonNull(constraintDefinition);
            Objects.requireNonNull(forecastDefinition); Objects.requireNonNull(passengerProtectionDefinition);
            Objects.requireNonNull(informationDefinition); Objects.requireNonNull(baselineTreatmentDefinition);
        }
    }

    /** Dispatch evidence version is a requested contract, not a claim that its writer is installed. */
    public record OutputOptions(String schemaVersion, ScientificRunContext.ExposureOutput passengerExposure,
            boolean vehicleIntervals, int eventsWriteInterval, int plansWriteInterval, String dispatchEvidenceSchemaVersion) {
        public OutputOptions {
            if (!ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA.equals(schemaVersion))
                throw new IllegalArgumentException("Expected dynamic-v2 scientific output schema");
            Objects.requireNonNull(passengerExposure);
            if (eventsWriteInterval < 0 || plansWriteInterval < 0) throw new IllegalArgumentException("Negative output interval");
            identifier(dispatchEvidenceSchemaVersion, "dispatch evidence schema");
        }
    }

    public DynamicMixedExperimentSpec {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported dynamic experiment schema");
        identifier(experimentId, "experiment"); identifier(blockId, "block"); identifier(validationProfile, "validation profile");
        Objects.requireNonNull(design); Objects.requireNonNull(seeds); Objects.requireNonNull(inputs);
        Objects.requireNonNull(freightDemand); Objects.requireNonNull(policies); Objects.requireNonNull(runtime);
        Objects.requireNonNull(control); Objects.requireNonNull(output);
        if (design.protocol().firstIteration() != 0)
            throw new IllegalArgumentException("Current uninterrupted Controller registration requires firstIteration=0");
        boolean freight = design.arm() != DynamicMixedDesign.Arm.CONTROL;
        if (freight != (freightDemand.orderCount() > 0)) throw new IllegalArgumentException("Dynamic arm and freight demand disagree");
        var expectedAccess = design.arm() == DynamicMixedDesign.Arm.CONTROL ? ExperimentSpec.Access.NO_FREIGHT
                : design.arm() == DynamicMixedDesign.Arm.DEDICATED ? ExperimentSpec.Access.DEDICATED_ONLY
                : ExperimentSpec.Access.SHARED_PASSENGER_SERVICE;
        if (policies.access() != expectedAccess) throw new IllegalArgumentException("Dynamic arm and admission policy disagree");
        if ((design.arm() == DynamicMixedDesign.Arm.DEDICATED) == policies.dedicatedLineId().isBlank())
            throw new IllegalArgumentException("Only dedicated service requires a nonblank dedicated line identity");
        if (design.arm() != DynamicMixedDesign.Arm.DEDICATED) {
            requireSameContent(inputs.baselineSchedule(), inputs.activatedSchedule(), "static schedule");
            requireSameContent(inputs.baselineTransitVehicles(), inputs.activatedTransitVehicles(), "static transit vehicles");
        }
        if (freightDemand.serviceWindowEndSeconds() > runtime.executionHorizonSeconds())
            throw new IllegalArgumentException("Freight service window exceeds the execution horizon");
        if (control.timing().firstDecisionTimeSeconds() < runtime.qsimStartTimeSeconds()
                || control.timing().firstDecisionTimeSeconds() >= runtime.executionHorizonSeconds())
            throw new IllegalArgumentException("Decision anchor is outside the registered simulation day");
        if (control.timing().decisionIntervalSeconds() < runtime.qsimTimeStepSeconds()
                || control.timing().candidateStepSeconds() < runtime.qsimTimeStepSeconds())
            throw new IllegalArgumentException("Decision interval and candidate spacing cannot be below the native time step");
        if (control.timing().lookAheadSeconds() <= design.limits().lockWindowSeconds())
            throw new IllegalArgumentException("Lookahead must extend beyond the lock window");
        // Non-active arms declare the same policy context for comparison, but do not execute it.
        // No enum values are appended to the legacy ExperimentSpec or its arm loops.
    }

    /** Every declared file role, not just the supply files; no file is read by this method. */
    public Map<String, ExperimentSpec.FileRef> allReferencedFiles() {
        Map<String, ExperimentSpec.FileRef> result = new LinkedHashMap<>();
        inputs.all().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put("inputs." + entry.getKey(), entry.getValue()));
        result.put("runtime.resolvedConfiguration", runtime.resolvedConfiguration().file());
        result.put("control.policyDefinition", control.policyDefinition().file());
        result.put("control.constraintDefinition", control.constraintDefinition().file());
        result.put("control.forecastDefinition", control.forecastDefinition().file());
        result.put("control.passengerProtectionDefinition", control.passengerProtectionDefinition().file());
        result.put("control.informationDefinition", control.informationDefinition().file());
        result.put("control.baselineTreatmentDefinition", control.baselineTreatmentDefinition().file());
        return Collections.unmodifiableMap(result);
    }

    /** Supplies metadata only; it does not validate files, policy implementation or runtime readiness. */
    public ScientificRunContext scientificContext() {
        return ScientificRunContext.dynamic(design, experimentId, blockId, freightDemand.orderCount(),
                runtime.behaviouralHorizonSeconds(), runtime.executionHorizonSeconds(), output.passengerExposure(), output.vehicleIntervals());
    }

    private static void requireSameContent(ExperimentSpec.FileRef baseline, ExperimentSpec.FileRef activated, String name) {
        if (!baseline.sha256().equals(activated.sha256())) throw new IllegalArgumentException("Fixed-baseline arm cannot change " + name);
    }

    private static void identifier(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("Invalid " + name + " identifier: " + value);
    }

    private static void positive(double value, String name) {
        nonnegative(value, name);
        if (value == 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void nonnegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(name + " must be finite and nonnegative");
    }
}
