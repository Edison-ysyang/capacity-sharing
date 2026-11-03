package org.matsim.run.ismt;

import org.matsim.run.ismt.dynamic.DynamicMixedDesign;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Identity and reporting endpoints for already-executed scientific observations.
 * This is NOT an executable experiment specification, input/build certificate,
 * operating-policy approval, or historical-comparison gate. The private factories
 * keep legacy arm semantics separate from the explicit dynamic design family.
 * Statistical calculations and fingerprint encodings remain in their original
 * writers; this value supplies only their context and output-selection settings.
 */
public final class ScientificRunContext {
    public static final String DYNAMIC_OUTPUT_SCHEMA = "ismt-output-dynamic-v2";
    public enum ExposureOutput { TERMINAL, REPORTING_WINDOW }

    private final String experimentId, blockId, arm, outputSchema;
    private final int firstIteration, finalIteration, reportFirstIteration, reportLastIteration, orderCount;
    private final double behaviouralHorizonSeconds, executionHorizonSeconds;
    private final ExposureOutput passengerExposure;
    private final boolean vehicleIntervals;
    private final DynamicMixedDesign dynamicDesign;

    private ScientificRunContext(String experimentId, String blockId, String arm, String outputSchema,
            int firstIteration, int finalIteration, int reportFirstIteration, int reportLastIteration,
            int orderCount, double behaviouralHorizonSeconds, double executionHorizonSeconds,
            ExposureOutput passengerExposure, boolean vehicleIntervals, DynamicMixedDesign dynamicDesign) {
        identifier(experimentId); identifier(blockId);
        this.experimentId = experimentId; this.blockId = blockId; this.arm = Objects.requireNonNull(arm);
        this.outputSchema = Objects.requireNonNull(outputSchema);
        if (firstIteration < 0 || firstIteration > reportFirstIteration || reportFirstIteration > reportLastIteration
                || reportLastIteration > finalIteration || orderCount < 0)
            throw new IllegalArgumentException("Invalid scientific reporting endpoints or order count");
        if (!Double.isFinite(behaviouralHorizonSeconds) || behaviouralHorizonSeconds <= 0
                || !Double.isFinite(executionHorizonSeconds) || executionHorizonSeconds <= behaviouralHorizonSeconds)
            throw new IllegalArgumentException("Require finite positive behavioural horizon and later execution horizon");
        this.firstIteration = firstIteration; this.finalIteration = finalIteration;
        this.reportFirstIteration = reportFirstIteration; this.reportLastIteration = reportLastIteration;
        this.orderCount = orderCount; this.behaviouralHorizonSeconds = behaviouralHorizonSeconds;
        this.executionHorizonSeconds = executionHorizonSeconds;
        this.passengerExposure = Objects.requireNonNull(passengerExposure);
        this.vehicleIntervals = vehicleIntervals; this.dynamicDesign = dynamicDesign;
    }

    /** Preserve v1 metadata and selection semantics without changing the legacy specification. */
    public static ScientificRunContext legacy(ExperimentSpec spec) {
        Objects.requireNonNull(spec);
        var protocol = spec.protocol();
        return new ScientificRunContext(spec.experimentId(), spec.blockId(), spec.arm().name(),
                spec.output().schemaVersion(), protocol.firstIteration(), protocol.finalIteration(),
                protocol.reportFirstIteration(), protocol.reportLastIteration(), spec.freightDemand().orderCount(),
                protocol.behaviouralHorizonSeconds(), protocol.executionHorizonSeconds(),
                ExposureOutput.valueOf(spec.output().passengerExposure().name()), spec.output().vehicleIntervals(), null);
    }

    /** All values are explicit. This factory does not adopt parameters or authorize a run. */
    public static ScientificRunContext dynamic(DynamicMixedDesign design, String experimentId, String blockId,
            int orderCount, double behaviouralHorizonSeconds, double executionHorizonSeconds,
            ExposureOutput passengerExposure, boolean vehicleIntervals) {
        Objects.requireNonNull(design);
        if ((design.arm() != DynamicMixedDesign.Arm.CONTROL) != (orderCount > 0))
            throw new IllegalArgumentException("Dynamic arm and expected freight order count disagree");
        var protocol = design.protocol();
        return new ScientificRunContext(experimentId, blockId, design.arm().name(), DYNAMIC_OUTPUT_SCHEMA,
                protocol.firstIteration(), protocol.finalIteration(), protocol.reportFirstIteration(),
                protocol.reportLastIteration(), orderCount, behaviouralHorizonSeconds, executionHorizonSeconds,
                passengerExposure, vehicleIntervals, design);
    }

    public String experimentId() { return experimentId; }
    public String blockId() { return blockId; }
    public String arm() { return arm; }
    public String outputSchema() { return outputSchema; }
    public int firstIteration() { return firstIteration; }
    public int finalIteration() { return finalIteration; }
    public int reportFirstIteration() { return reportFirstIteration; }
    public int reportLastIteration() { return reportLastIteration; }
    public int orderCount() { return orderCount; }
    public double behaviouralHorizonSeconds() { return behaviouralHorizonSeconds; }
    public double executionHorizonSeconds() { return executionHorizonSeconds; }
    public ExposureOutput passengerExposure() { return passengerExposure; }
    public boolean vehicleIntervals() { return vehicleIntervals; }
    public Optional<DynamicMixedDesign> dynamicDesign() { return Optional.ofNullable(dynamicDesign); }

    /** The digest algorithm version is unchanged; v2 CSV envelopes explicitly name their design. */
    public String fingerprintHeader(String legacyHeader) {
        if (!legacyHeader.endsWith("\n")) throw new IllegalArgumentException("Expected newline-terminated legacy header");
        return dynamicDesign == null ? legacyHeader
                : legacyHeader.substring(0, legacyHeader.length() - 1) + ",design_family\n";
    }

    public String fingerprintRowSuffix() {
        return dynamicDesign == null ? "" : "," + DynamicMixedDesign.SCHEMA_ID;
    }

    /** Empty for v1 so its artifact_schema.json fields and ordering are preserved. */
    public Map<String, Object> additionalSchemaMetadata() {
        if (dynamicDesign == null) return Map.of();
        var protocol = dynamicDesign.protocol();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("design_family", DynamicMixedDesign.SCHEMA_ID);
        identity.put("identity_scope", "scientific-artifact-context-not-experiment-provenance");
        identity.put("experiment_id", experimentId); identity.put("block_id", blockId); identity.put("arm", arm);
        identity.put("first_iteration", firstIteration); identity.put("activation_iteration", protocol.activationIteration());
        identity.put("innovation_off_iteration", protocol.innovationOffIteration());
        identity.put("final_iteration", finalIteration); identity.put("report_first_iteration", reportFirstIteration);
        identity.put("report_last_iteration", reportLastIteration); identity.put("expected_order_count", orderCount);
        identity.put("behavioural_horizon_seconds", behaviouralHorizonSeconds);
        identity.put("execution_horizon_seconds", executionHorizonSeconds);
        identity.put("control_enabled", dynamicDesign.controlEnabled());
        identity.put("maximum_advance_seconds", dynamicDesign.limits().maximumAdvanceSeconds());
        identity.put("maximum_delay_seconds", dynamicDesign.limits().maximumDelaySeconds());
        identity.put("lock_window_seconds", dynamicDesign.limits().lockWindowSeconds());
        identity.put("phase_active_semantics", "native co-transport treatment/sham phase, not actual dispatch execution");
        identity.put("control_metadata_semantics", "configured design only; actual decisions and execution require separate evidence");
        identity.put("fingerprint_envelope", "original algorithm schema and columns, followed by design_family");
        identity.put("passenger_event_fingerprint_scope", "native passenger whitelist and within-time multiset; excludes scores and plans, not full physical event order");
        return java.util.Collections.unmodifiableMap(identity);
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("Invalid scientific artifact identifier: " + value);
    }
}
