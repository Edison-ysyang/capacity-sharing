package org.matsim.run.ismt.dynamic;

import org.matsim.run.ismt.ExperimentSpec;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure, field-level comparison of a legacy MIXED declaration with a new
 * DYNAMIC_MIXED declaration. No file, receipt, result, runtime or user approval is
 * inspected. Matching declared hashes are not verification of file contents.
 *
 * <p>The target's control registration is the explicit new treatment, not a
 * blacklist that permits changes to common fields. Every component of the shared
 * Seeds, Inputs, FreightDemand and Policies records is inspected. Runtime fields
 * absent from the legacy specification cannot be inferred from a presumed default;
 * complete resolved-configuration and source/runtime compatibility remain separate
 * UNVERIFIED requirements. This class provides no overall reuse or execution gate.</p>
 */
public final class DynamicMixedDeclaredPairing {
    private DynamicMixedDeclaredPairing() { }

    public enum Scope { DECLARED_COMMON_FIELDS_ONLY }
    public enum UnverifiedAspect {
        FILE_CONTENTS_AND_WORKSPACE_PATH_CONTAINMENT,
        FULL_RESOLVED_NON_DISPATCH_CONFIGURATION_INCLUDING_THREADS_EVENTS_SCORING_AND_STRATEGIES,
        SOURCE_BUILD_AND_NATIVE_RUNTIME_COMPATIBILITY,
        PRE_ACTIVATION_COMMON_HISTORY,
        SCIENTIFIC_OUTPUT_SCHEMA_AND_RESULT_VALIDITY,
        TARGET_CONTROL_DEFINITION_IMPLEMENTATION_COVERAGE_AND_FEASIBILITY,
        USER_PARAMETER_DIAGNOSTIC_AND_FORMAL_RUN_APPROVAL
    }

    /** Original namespaces are retained, not normalized to manufacture one experiment identity. */
    public record Identity(String schemaVersion, String experimentId, String blockId, String arm) {
        public Identity {
            Objects.requireNonNull(schemaVersion); Objects.requireNonNull(experimentId);
            Objects.requireNonNull(blockId); Objects.requireNonNull(arm);
        }
    }

    /** Values describe immutable record values; the field identifies both names when a mapping is required. */
    public record Difference(String field, String historicalValue, String targetValue) {
        public Difference {
            Objects.requireNonNull(field); Objects.requireNonNull(historicalValue); Objects.requireNonNull(targetValue);
        }
    }

    /** Same declared SHA, different declared locations; actual bytes and containment are not established. */
    public record InputPathDifference(String role, String declaredSha256, String historicalPath, String targetPath) {
        public InputPathDifference {
            Objects.requireNonNull(role); Objects.requireNonNull(declaredSha256);
            Objects.requireNonNull(historicalPath); Objects.requireNonNull(targetPath);
        }
    }

    public static final class Report {
        private final Identity historicalIdentity, targetIdentity;
        private final DynamicMixedDesign targetDesign;
        private final DynamicMixedExperimentSpec.ControlRegistration targetControlRegistration;
        private final List<String> checkedFields;
        private final List<Difference> differences;
        private final List<InputPathDifference> inputPathDifferences;

        private Report(Identity historicalIdentity, Identity targetIdentity, DynamicMixedDesign targetDesign,
                DynamicMixedExperimentSpec.ControlRegistration targetControlRegistration,
                List<String> checkedFields, List<Difference> differences, List<InputPathDifference> inputPathDifferences) {
            this.historicalIdentity = historicalIdentity; this.targetIdentity = targetIdentity;
            this.targetDesign = targetDesign; this.targetControlRegistration = targetControlRegistration;
            this.checkedFields = List.copyOf(checkedFields); this.differences = List.copyOf(differences);
            this.inputPathDifferences = List.copyOf(inputPathDifferences);
        }

        public Scope scope() { return Scope.DECLARED_COMMON_FIELDS_ONLY; }
        public Identity historicalIdentity() { return historicalIdentity; }
        public Identity targetIdentity() { return targetIdentity; }
        public DynamicMixedDesign targetDesign() { return targetDesign; }
        public DynamicMixedExperimentSpec.ControlRegistration targetControlRegistration() { return targetControlRegistration; }
        public List<String> checkedFields() { return checkedFields; }
        public List<Difference> differences() { return differences; }
        public List<InputPathDifference> inputPathDifferences() { return inputPathDifferences; }
        /** Only the enumerated common declarations match; no broader validity follows. */
        public boolean declaredFieldsMatch() { return differences.isEmpty(); }
        public List<UnverifiedAspect> unverifiedAspects() { return List.of(UnverifiedAspect.values()); }
    }

    public static Report compareHistoricalMixed(ExperimentSpec historicalMixed, DynamicMixedExperimentSpec target) {
        Objects.requireNonNull(historicalMixed, "historical MIXED specification");
        Objects.requireNonNull(target, "target DYNAMIC_MIXED specification");
        if (historicalMixed.arm() != ExperimentSpec.Arm.MIXED)
            throw new IllegalArgumentException("Historical declaration must be legacy MIXED, never TIMETABLE_ONLY or another arm");
        if (target.design().arm() != DynamicMixedDesign.Arm.DYNAMIC_MIXED)
            throw new IllegalArgumentException("Target declaration must be DYNAMIC_MIXED");

        Comparison comparison = new Comparison();
        comparison.field("blockId", historicalMixed.blockId(), target.blockId());
        comparison.record("seeds", historicalMixed.seeds(), target.seeds());
        for (RecordComponent component : ExperimentSpec.Inputs.class.getRecordComponents()) {
            var historical = (ExperimentSpec.FileRef) value(component, historicalMixed.inputs());
            var proposed = (ExperimentSpec.FileRef) value(component, target.inputs());
            String role = component.getName();
            comparison.field("inputs." + role + ".sha256", historical.sha256(), proposed.sha256());
            if (historical.sha256().equals(proposed.sha256()) && !historical.path().equals(proposed.path()))
                comparison.paths.add(new InputPathDifference(role, historical.sha256(), historical.path(), proposed.path()));
        }
        comparison.record("freightDemand", historicalMixed.freightDemand(), target.freightDemand());
        comparison.record("policies", historicalMixed.policies(), target.policies());

        var historical = historicalMixed.protocol();
        var proposed = target.design().protocol();
        comparison.field("protocol.firstIteration -> design.protocol.firstIteration", historical.firstIteration(), proposed.firstIteration());
        comparison.field("protocol.activationIteration -> design.protocol.activationIteration", historical.activationIteration(), proposed.activationIteration());
        comparison.field("protocol.innovationOffIteration -> design.protocol.firstIteration", historical.innovationOffIteration(), proposed.firstIteration());
        comparison.field("protocol.innovationReopenIteration -> design.protocol.activationIteration", historical.innovationReopenIteration(), proposed.activationIteration());
        comparison.field("protocol.innovationFinalOffIteration -> design.protocol.innovationOffIteration", historical.innovationFinalOffIteration(), proposed.innovationOffIteration());
        comparison.field("protocol.finalIteration -> design.protocol.finalIteration", historical.finalIteration(), proposed.finalIteration());
        comparison.field("protocol.reportFirstIteration -> design.protocol.reportFirstIteration", historical.reportFirstIteration(), proposed.reportFirstIteration());
        comparison.field("protocol.reportLastIteration -> design.protocol.reportLastIteration", historical.reportLastIteration(), proposed.reportLastIteration());
        comparison.field("protocol.behaviouralHorizonSeconds -> runtime.behaviouralHorizonSeconds", historical.behaviouralHorizonSeconds(), target.runtime().behaviouralHorizonSeconds());
        comparison.field("protocol.executionHorizonSeconds -> runtime.executionHorizonSeconds", historical.executionHorizonSeconds(), target.runtime().executionHorizonSeconds());
        return new Report(new Identity(historicalMixed.schemaVersion(), historicalMixed.experimentId(), historicalMixed.blockId(), historicalMixed.arm().name()),
                new Identity(target.schemaVersion(), target.experimentId(), target.blockId(), target.design().arm().name()),
                target.design(), target.control(), comparison.checked, comparison.differences, comparison.paths);
    }

    private static Object value(RecordComponent component, Record record) {
        try { return component.getAccessor().invoke(record); }
        catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException("Cannot inspect public declaration component " + component.getName(), impossible);
        }
    }

    private static final class Comparison {
        private final List<String> checked = new ArrayList<>();
        private final List<Difference> differences = new ArrayList<>();
        private final List<InputPathDifference> paths = new ArrayList<>();

        private void field(String field, Object historical, Object target) {
            checked.add(field);
            if (!Objects.equals(historical, target))
                differences.add(new Difference(field, String.valueOf(historical), String.valueOf(target)));
        }

        private void record(String prefix, Record historical, Record target) {
            if (historical.getClass() != target.getClass()) throw new IllegalArgumentException("Shared record types differ");
            for (RecordComponent component : historical.getClass().getRecordComponents())
                field(prefix + "." + component.getName(), value(component, historical), value(component, target));
        }
    }
}
