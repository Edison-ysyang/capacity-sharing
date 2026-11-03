package org.matsim.run.ismt.dynamic;

import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ScientificRunContext;

/** Syntactically complete toy declarations only: no adopted parameters, existing files or executable policy. */
final class DynamicMixedSpecFixtures {
    private DynamicMixedSpecFixtures() { }

    static DynamicMixedExperimentSpec spec() { return spec(DynamicMixedDesign.Arm.DYNAMIC_MIXED); }

    static DynamicMixedExperimentSpec spec(DynamicMixedDesign.Arm arm) {
        var ref = new ExperimentSpec.FileRef("toy/input.xml", "a".repeat(64));
        var definition = new DynamicMixedExperimentSpec.RegisteredDefinition("toy-definition-v1", ref);
        return new DynamicMixedExperimentSpec(DynamicMixedExperimentSpec.SCHEMA, "toy-v2", "B1",
                new DynamicMixedDesign(arm, new DynamicMixedDesign.Protocol(0, 2, 4, 5, 4, 5),
                        arm == DynamicMixedDesign.Arm.DYNAMIC_MIXED, new DispatchCommitmentState.Limits(25, 25, 5)),
                new ExperimentSpec.Seeds(1, 2, 3),
                new ExperimentSpec.Inputs(ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref),
                new ExperimentSpec.FreightDemand(arm == DynamicMixedDesign.Arm.CONTROL ? 0 : 1, 5, 0, 180, "freight_", "_unit_"),
                new ExperimentSpec.Policies(arm == DynamicMixedDesign.Arm.CONTROL ? ExperimentSpec.Access.NO_FREIGHT
                        : arm == DynamicMixedDesign.Arm.DEDICATED ? ExperimentSpec.Access.DEDICATED_ONLY
                        : ExperimentSpec.Access.SHARED_PASSENGER_SERVICE,
                        arm == DynamicMixedDesign.Arm.DEDICATED ? "dedicated" : "", java.util.List.of("pt", "freight_pt"),
                        0.1, 0.9, 1, 0, true, 0.6, 0.8, 0.8, 1, 1, 1, 1),
                new DynamicMixedExperimentSpec.RuntimeDeclaration(7, 7, DynamicMixedExperimentSpec.EventMode.NATIVE_SIM_STEP_PARALLEL,
                        DynamicMixedExperimentSpec.EventWorkerSelection.DEFAULT_SINGLE_WORKER, 1, true, false, 2,
                        1, 0, QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime,
                        QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime, 200, 240, definition),
                new DynamicMixedExperimentSpec.ControlRegistration("toy-policy-not-implemented",
                        DynamicMixedExperimentSpec.InformationModel.BASELINE_PRETRIP_NO_ADDED_INTRADAY_REROUTING,
                        DynamicMixedExperimentSpec.ResetMode.FRESH_ORIGINAL_BASELINE_EACH_ITERATION,
                        DynamicMixedExperimentSpec.BaselineTreatment.NO_PRE_SERVICE_REPAIR,
                        DynamicMixedExperimentSpec.OperatingBasis.SYNTHETIC_ASSUMPTIONS,
                        new DynamicMixedExperimentSpec.DecisionTiming(10, 5, 30, 5),
                        definition, definition, definition, definition, definition, definition),
                new DynamicMixedExperimentSpec.OutputOptions(ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA,
                        ScientificRunContext.ExposureOutput.REPORTING_WINDOW, false, 0, 0, "toy-dispatch-evidence-v1"), "toy-not-formal");
    }

    /** Reconstructs a record through its real constructor, so ordinary invariants still run. */
    @SuppressWarnings("unchecked")
    static <T extends Record> T replace(T value, String field, Object replacement) {
        var components = value.getClass().getRecordComponents();
        Object[] arguments = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        boolean found = false;
        try {
            for (int index = 0; index < components.length; index++) {
                var component = components[index]; types[index] = component.getType();
                if (component.getName().equals(field)) { arguments[index] = replacement; found = true; }
                else arguments[index] = component.getAccessor().invoke(value);
            }
            if (!found) throw new IllegalArgumentException("No record field " + field);
            return (T) value.getClass().getDeclaredConstructor(types).newInstance(arguments);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
