package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.ExperimentSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;
import static org.matsim.run.ismt.dynamic.DynamicMixedControlDefinitions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedExperimentSpec.*;

/** Independent persisted-artifact assembly; no Controller/QSim is launched by these tests. */
class DynamicMixedResultAuditTest {
    @TempDir Path temporary;

    private DynamicMixedExperimentSpec scenarioFiles() throws Exception {
        var fixture = new DynamicMixedExperimentRunnerTest(); fixture.temporary = temporary;
        return fixture.scenarioFiles();
    }
    private Path run(DynamicMixedExperimentSpec spec) throws IOException {
        Path run = Files.createDirectory(temporary.resolve("run"));
        DynamicMixedExperimentSpecIO.writeNew(run.resolve("experiment_spec.json"), spec);
        return run;
    }

    @Test void staticInputsMatchNativeFixtureSupplyAndRealUnitRegistryWithoutLoadingPopulation() throws Exception {
        var spec = scenarioFiles(); Path run = run(spec);
        Path matsim = Files.createDirectory(run.resolve("matsim"));
        Path existing = matsim.resolve("preserve.txt"); Files.writeString(existing, "existing output");
        var before = DynamicMixedInputVerifier.verify(temporary, spec);
        var input = DynamicMixedResultAudit.loadStaticInputs(temporary, run, spec);
        assertEquals(2, input.dispatch().trips().size()); assertEquals(4, input.dispatch().points().size());
        assertEquals(Map.of("order-assembly", 50.0), input.dispatch().earliestFreightRelease());
        assertNull(input.dispatch().adjustmentWindow(), "Legacy static postflight keeps its all-day scope");
        assertEquals(1, input.orderCount()); assertEquals(1, input.freightUnits());
        assertEquals(0, input.loadedPopulationPersons()); assertFalse(input.nativePreparationVerified());
        assertEquals(before, input.registeredInputs());
        assertEquals(before, DynamicMixedInputVerifier.verify(temporary, spec));
        assertEquals("existing output", Files.readString(existing));
        assertFalse(Files.exists(run.resolve("dispatch"))); assertFalse(Files.exists(run.resolve("run_receipt.json")));
    }

    @Test void windowedStaticPostflightPreservesScopeWhenReplacingFreightContracts() throws Exception {
        var original = DynamicMixedControlDefinitions.read(temporary, scenarioFiles());
        var timing = new DecisionTiming(120, 5, 30, 5);
        var policy = new PolicyDefinition(POLICY_SCHEMA, WINDOWED_POLICY_ID, timing, original.policy().limits(),
                original.policy().objective(), original.policy().tieOrder(), 32, 1);
        var control = replace(replace(replace(original.spec().control(), "policyId", WINDOWED_POLICY_ID),
                "timing", timing), "baselineTreatment", BaselineTreatment.NO_PRE_SERVICE_REPAIR);
        var demand = replace(original.spec().freightDemand(), "serviceWindowStartSeconds", 120.0);
        var spec = replace(replace(original.spec(), "control", control), "freightDemand", demand);
        var constraints = replace(original.constraints(), "resourceModel",
                ResourceModel.SYNTHETIC_EXACT_LINK_XY_ADJUSTABLE_TRIP_ADJACENT_PAIRS);
        var f = original.forecast();
        var forecast = new ForecastDefinition(FORECAST_SCHEMA, WINDOWED_FORECAST_ID,
                CausalMixedServiceForecast.WINDOWED_APPROXIMATION, f.observationStartSeconds(),
                f.trailingWindowSeconds(), f.historyRule(), f.arrivalCohortStepSeconds(), f.executionEndSeconds(),
                f.maximumPassengerCohorts(), f.maximumStopEvents());
        var treatment = new BaselineTreatmentDefinition(BASELINE_SCHEMA, BaselineTreatment.NO_PRE_SERVICE_REPAIR,
                original.baselineTreatment().originalScheduleSha256(), spec.design().protocol().activationIteration(),
                120, List.of());
        spec = DynamicMixedControlDefinitionsTest.writeDefinitions(temporary,
                new Bundle(spec, policy, constraints, forecast, original.passengerProtection(), original.information(), treatment));
        Path run = run(spec);
        var input = DynamicMixedResultAudit.loadStaticInputs(temporary, run, spec).dispatch();
        assertEquals(new RollingDepartureCandidates.AdjustmentWindow(120, 180), input.adjustmentWindow());
        assertEquals(Map.of("order-assembly", 50.0), input.earliestFreightRelease());
        var originals = new LinkedHashMap<DispatchCommitmentState.TripKey, Double>();
        input.trips().forEach(trip -> originals.put(trip.key(), trip.baselineDepartureSeconds()));
        var checker = new DispatchConstraintChecker(input.trips(), input.points(), input.connections(), input.adjustmentWindow());
        assertTrue(checker.evaluate(originals).candidate().satisfiesRegisteredConstraints());
        assertFalse(new DispatchConstraintChecker(input.trips(), input.points(), input.connections())
                .evaluate(originals).candidate().satisfiesRegisteredConstraints(),
                "Dropping the scope must reproduce the fixed-background baseline false alarm");
        var outsideEdit = new LinkedHashMap<>(originals);
        outsideEdit.put(input.trips().getFirst().key(), 125.0);
        assertThrows(IllegalArgumentException.class, () -> checker.evaluate(outsideEdit));
        assertFalse(Files.exists(run.resolve("matsim")));
        assertFalse(Files.exists(run.resolve("run_receipt.json")));
    }

    @Test void evenHashValidMalformedPopulationIsNotParsedOrClaimedNativePrepared() throws Exception {
        var spec = scenarioFiles(); Path population = temporary.resolve("population.xml");
        Files.writeString(population, "this is deliberately not population XML");
        var ref = new ExperimentSpec.FileRef("population.xml", ExperimentConfiguration.sha256(population));
        spec = replace(spec, "inputs", replace(replace(spec.inputs(), "population", ref), "passengerPlanMemory", ref));
        var input = DynamicMixedResultAudit.loadStaticInputs(temporary, run(spec), spec);
        assertEquals(0, input.loadedPopulationPersons()); assertFalse(input.nativePreparationVerified());
        assertEquals(2, input.dispatch().trips().size());
    }

    @Test void populationHashIsStillRequiredAlthoughPlansAreNotLoaded() throws Exception {
        var spec = scenarioFiles(); Path run = run(spec);
        Files.writeString(temporary.resolve("population.xml"), "changed after registration");
        var error = assertThrows(IOException.class, () -> DynamicMixedResultAudit.loadStaticInputs(temporary, run, spec));
        assertTrue(error.getMessage().contains("hash mismatch"));
        assertFalse(Files.exists(run.resolve("matsim")));
    }

    @Test void realOrderCountCannotBeReplacedByADeclaredCount() throws Exception {
        var original = scenarioFiles();
        var spec = replace(original, "freightDemand", replace(original.freightDemand(), "orderCount", 2));
        Path run = run(spec);
        var error = assertThrows(IOException.class, () -> DynamicMixedResultAudit.loadStaticInputs(temporary, run, spec));
        assertTrue(error.getMessage().contains("order count"));
    }

    @Test void absentDaysAndScientificFilesRemainFailedAndDoNotChangeReceipt() throws Exception {
        var spec = scenarioFiles(); Path run = run(spec);
        Path receipt = run.resolve("run_receipt.json"); Files.writeString(receipt, "{\"status\":\"STARTED\"}");
        var report = new DynamicMixedResultAudit().audit(temporary, run);
        assertFalse(report.scopedChecksPassed()); assertFalse(report.formalAuthorization());
        assertFalse(report.scientific().valid());
        assertEquals(spec.design().protocol().finalIteration() + 1, report.dispatchDays().size());
        assertTrue(report.dispatchDays().stream().allMatch(day -> !day.valid() && day.failure() != null && day.report() == null));
        assertEquals("{\"status\":\"STARTED\"}", Files.readString(receipt));
        var encoded = new ObjectMapper().valueToTree(report);
        assertEquals(DynamicMixedResultAudit.SCHEMA, encoded.path("schemaVersion").asText());
        assertFalse(encoded.path("scopedChecksPassed").asBoolean());
        assertFalse(Files.exists(run.resolve("matsim"))); assertFalse(Files.exists(run.resolve("dispatch")));
    }

    @Test void incompleteDispatchIsReportedNotInventedCompleted() throws Exception {
        var spec = scenarioFiles(); Path run = run(spec);
        Path dispatch = Files.createDirectory(run.resolve("dispatch"));
        Path partial = dispatch.resolve("iteration-000.jsonl"); Files.writeString(partial, "{\"schemaVersion\":");
        var report = new DynamicMixedResultAudit().audit(temporary, run);
        assertFalse(report.scopedChecksPassed()); assertNotNull(report.dispatchDays().getFirst().report());
        assertFalse(report.dispatchDays().getFirst().report().valid());
        assertEquals("{\"schemaVersion\":", Files.readString(partial));
    }

    @Test void reportCannotOverwriteOrBePlacedInsideTheAuditedRun() throws Exception {
        Path run = run(scenarioFiles()); Path reports = Files.createDirectory(temporary.resolve("reports"));
        Path existing = reports.resolve("old.json"); Files.writeString(existing, "preserve");
        assertThrows(IOException.class, () -> DynamicMixedResultAudit.reportPath(temporary, run, existing));
        assertThrows(IOException.class, () -> DynamicMixedResultAudit.reportPath(temporary, run, run.resolve("audit.json")));
        assertThrows(IOException.class, () -> DynamicMixedResultAudit.reportPath(temporary, run, Path.of("reports/../audit.json")));
        assertEquals(reports.resolve("new.json"), DynamicMixedResultAudit.reportPath(temporary, run, Path.of("reports/new.json")));
        assertEquals("preserve", Files.readString(existing));
    }

    @Test void symlinkAliasesAndMissingDirectoriesAreRejectedWithoutCreatingOutputs() throws Exception {
        var spec = scenarioFiles(); Path run = run(spec); Path alias = temporary.resolve("alias");
        Files.createSymbolicLink(alias, run);
        assertThrows(IOException.class, () -> DynamicMixedResultAudit.loadStaticInputs(temporary, alias, spec));
        assertThrows(IOException.class, () -> DynamicMixedResultAudit.loadStaticInputs(temporary, temporary, spec));
        assertThrows(IOException.class, () -> DynamicMixedResultAudit.loadStaticInputs(temporary, temporary.resolve("missing"), spec));
        assertFalse(Files.exists(temporary.resolve("missing")));
    }

    @Test void cliPreservesFailureReportOutsideRunAndNeverUpgradesRunReceipt() throws Exception {
        Path run = run(scenarioFiles()), directory = Files.createDirectory(temporary.resolve("reports"));
        Path output = directory.resolve("audit.json");
        var error = assertThrows(IOException.class, () -> DynamicMixedResultAudit.main(
                new String[]{"audit", temporary.toString(), run.toString(), output.toString()}));
        assertTrue(error.getMessage().contains("Scoped postflight failed"));
        var report = new ObjectMapper().readTree(output.toFile());
        assertFalse(report.path("scopedChecksPassed").asBoolean());
        assertFalse(report.path("formalAuthorization").asBoolean());
        assertFalse(Files.exists(run.resolve("run_receipt.json")));
        assertThrows(IOException.class, () -> DynamicMixedResultAudit.main(
                new String[]{"audit", temporary.toString(), run.toString(), output.toString()}));
    }

    @Test void cliHasNoRunResumeOrImplicitMode() {
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedResultAudit.main(new String[]{}));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedResultAudit.main(new String[]{"run", "missing", "missing", "missing"}));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedResultAudit.main(new String[]{"resume", "missing", "missing", "missing"}));
    }
}
