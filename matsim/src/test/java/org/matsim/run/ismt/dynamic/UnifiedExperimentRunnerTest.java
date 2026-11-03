package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.cotransport.CoTransportBoardingPolicy;
import org.matsim.run.ismt.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.UnifiedExperimentRunner.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;
import static org.matsim.run.ismt.dynamic.DynamicMixedControlDefinitions.*;

class UnifiedExperimentRunnerTest {
    @TempDir Path temporary;

    @Test void preservationAcceptsExactCmdWithoutConvertingInputsOrPolicies() throws Exception {
        for (Arm arm : List.of(Arm.C, Arm.M, Arm.D)) {
            var spec = legacy(arm);
            var selected = new Selection(arm, Profile.PRESERVE_REGISTERED, spec, null);
            assertSame(spec, selected.legacy());
            assertEquals(ExperimentSpecIO.sha256(spec), selected.specificationSha256());
            assertEquals("unchanged-v1-no-new-physical-window", selected.boardingWindowPolicy());
        }
    }

    @Test void oldTimetableOnlyCannotBeCalledNewT() throws Exception {
        var old = ExperimentSpecIO.compose(profiles(), "B1", ExperimentSpec.Arm.TIMETABLE_ONLY);
        assertThrows(IllegalArgumentException.class, () -> new Selection(Arm.T, Profile.PRESERVE_REGISTERED, old, null));
    }

    @Test void mismatchedArmAndImplicitLegacyWindowAreRejected() throws Exception {
        var m = legacy(Arm.M);
        assertThrows(IllegalArgumentException.class, () -> new Selection(Arm.D, Profile.PRESERVE_REGISTERED, m, null));
        assertThrows(IllegalArgumentException.class, () -> new Selection(Arm.M, Profile.WINDOWED_MIXED, m, null));
    }

    @Test void explicitV2MixedAndDynamicHaveSeparateIdentitiesAndNoImplicitControl() throws Exception {
        var t = windowedDeclaration(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        var m = replace(t, "design", new DynamicMixedDesign(DynamicMixedDesign.Arm.MIXED,
                t.design().protocol(), false, t.design().limits()));
        var ts = new Selection(Arm.T, Profile.WINDOWED_MIXED, null, t);
        var ms = new Selection(Arm.M, Profile.WINDOWED_MIXED, null, m);
        assertNotEquals(ts.specificationSha256(), ms.specificationSha256());
        assertEquals(ts.boardingWindowPolicy(), ms.boardingWindowPolicy());
        assertTrue(t.design().phaseAt(4).controlEligible());
        assertFalse(m.design().phaseAt(4).controlEligible());
        assertThrows(IllegalArgumentException.class, () -> new Selection(Arm.M, Profile.PRESERVE_REGISTERED, null, m));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedExperimentRunner.requireSupportedSpec(m),
                "Original T-only public entry must keep its contract");
    }

    @Test void forwardProfileRejectsNonWindowedRepairAndOtherArms() {
        var m = windowedDeclaration(DynamicMixedDesign.Arm.MIXED);
        var oldPolicy = replace(m, "control", replace(m.control(), "policyId", POLICY_ID));
        assertThrows(IllegalArgumentException.class, () -> new Selection(Arm.M, Profile.WINDOWED_MIXED, null, oldPolicy));
        var repair = replace(m, "control", replace(m.control(), "baselineTreatment",
                DynamicMixedExperimentSpec.BaselineTreatment.FREIGHT_BLIND_PRE_SERVICE_REPAIR));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedExperimentRunner.requireWindowedMixedSpec(repair));
        for (var arm : List.of(DynamicMixedDesign.Arm.CONTROL, DynamicMixedDesign.Arm.DEDICATED))
            assertThrows(IllegalArgumentException.class, () -> DynamicMixedExperimentRunner.requireWindowedMixedSpec(DynamicMixedSpecFixtures.spec(arm)));
    }

    @Test void newWindowedProfileCannotPromoteItselfToFormalOrReadMissingBuild() {
        var selected = new Selection(Arm.M, Profile.WINDOWED_MIXED, null, windowedDeclaration(DynamicMixedDesign.Arm.MIXED));
        assertThrows(IllegalArgumentException.class, () -> new UnifiedExperimentRunner().runFormal(selected,
                temporary, temporary.resolve("outputs"), temporary.resolve("absent.json"), null));
        assertFalse(Files.exists(temporary.resolve("outputs")));
    }

    @Test void realForwardMAndTInstallTheSamePhysicalBoardingWindowAndKeepOldEntryGuard() throws Exception {
        var fixture = new DynamicMixedExperimentRunnerTest(); fixture.temporary = temporary;
        var spec = fixture.scenarioFiles();
        var original = DynamicMixedControlDefinitions.read(temporary, spec);
        var timing = new DynamicMixedExperimentSpec.DecisionTiming(120, 5, 30, 5);
        var policy = new PolicyDefinition(POLICY_SCHEMA, WINDOWED_POLICY_ID, timing, original.policy().limits(),
                original.policy().objective(), original.policy().tieOrder(), 32, 1);
        var control = replace(replace(replace(spec.control(), "policyId", WINDOWED_POLICY_ID), "timing", timing),
                "baselineTreatment", DynamicMixedExperimentSpec.BaselineTreatment.NO_PRE_SERVICE_REPAIR);
        spec = replace(replace(spec, "control", control), "freightDemand",
                replace(spec.freightDemand(), "serviceWindowStartSeconds", 120.0));
        var constraints = replace(original.constraints(), "resourceModel", ResourceModel.SYNTHETIC_EXACT_LINK_XY_ADJUSTABLE_TRIP_ADJACENT_PAIRS);
        var f = original.forecast();
        var forecast = new ForecastDefinition(FORECAST_SCHEMA, WINDOWED_FORECAST_ID, CausalMixedServiceForecast.WINDOWED_APPROXIMATION,
                f.observationStartSeconds(), f.trailingWindowSeconds(), f.historyRule(), f.arrivalCohortStepSeconds(),
                f.executionEndSeconds(), f.maximumPassengerCohorts(), f.maximumStopEvents());
        var repair = new BaselineTreatmentDefinition(BASELINE_SCHEMA, DynamicMixedExperimentSpec.BaselineTreatment.NO_PRE_SERVICE_REPAIR,
                original.baselineTreatment().originalScheduleSha256(), spec.design().protocol().activationIteration(), 120, List.of());
        var t = DynamicMixedControlDefinitionsTest.writeDefinitions(temporary, new Bundle(spec, policy, constraints, forecast,
                original.passengerProtection(), original.information(), repair));
        var m = replace(t, "design", new DynamicMixedDesign(DynamicMixedDesign.Arm.MIXED,
                t.design().protocol(), false, t.design().limits()));
        var expected = new CoTransportBoardingPolicy.SharedBoardingWindow(120, 180);
        for (var input : List.of(m, t)) {
            var root = Files.createDirectory(temporary.resolve(input.design().arm().name()));
            try (var prepared = DynamicMixedExperimentRunner.prepareWindowedSimulation(input, temporary, root)) {
                assertEquals(expected, prepared.controler().getScenario().getScenarioElement(CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT));
                assertEquals(1, prepared.conservation().orders());
                assertEquals(1, prepared.conservation().prebuiltUnits());
                assertTrue(prepared.completedIterations().isEmpty(), "Assembly is not simulation evidence");
                assertFalse(Files.exists(root.resolve("matsim")));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedExperimentRunner.prepareSimulation(m, temporary, temporary));
    }

    @Test void declarationInspectionDoesNotLaunchAndRejectsDuplicateKeys() throws Exception {
        var spec = windowedDeclaration(DynamicMixedDesign.Arm.DYNAMIC_MIXED);
        Path file = temporary.resolve("spec.json"); DynamicMixedExperimentSpecIO.writeNew(file, spec);
        assertEquals(spec, UnifiedExperimentRunner.read(file, Arm.T, Profile.WINDOWED_MIXED).dynamic());
        Path bad = temporary.resolve("bad.json");
        Files.writeString(bad, "{\"schemaVersion\":\"ismt-experiment-v1\",\"schemaVersion\":\"ismt-dynamic-experiment-v2\"}");
        assertThrows(java.io.IOException.class, () -> UnifiedExperimentRunner.read(bad, Arm.T, Profile.PRESERVE_REGISTERED));
    }

    private static DynamicMixedExperimentSpec windowedDeclaration(DynamicMixedDesign.Arm arm) {
        var spec = DynamicMixedSpecFixtures.spec(arm);
        return replace(replace(spec, "control", replace(spec.control(), "policyId", WINDOWED_POLICY_ID)), "output",
                replace(spec.output(), "dispatchEvidenceSchemaVersion", DynamicMixedDispatchEvidence.SCHEMA));
    }
    private static ExperimentSpec legacy(Arm arm) throws Exception {
        return ExperimentSpecIO.compose(profiles(), "B1", switch (arm) {
            case C -> ExperimentSpec.Arm.CONTROL; case M -> ExperimentSpec.Arm.MIXED;
            case D -> ExperimentSpec.Arm.DEDICATED; default -> throw new IllegalArgumentException();
        });
    }
    private static Path profiles() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            Path profiles = path.resolve("experiments/ismt_q720");
            if (Files.isDirectory(profiles)) return profiles;
        }
        throw new AssertionError("Profiles missing");
    }
}
