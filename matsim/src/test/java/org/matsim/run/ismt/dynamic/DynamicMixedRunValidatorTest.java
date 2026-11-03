package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.IsmtRunArtifactWriter;
import org.matsim.run.ismt.PassengerPlanFingerprint;
import org.matsim.run.ismt.PassengerScientificFingerprint;
import org.matsim.run.ismt.ScientificRunContext;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Synthetic files exercise recorded-account consistency, never a claim of native mechanism execution. */
class DynamicMixedRunValidatorTest {
    @TempDir Path temp;
    private int sequence;

    @Test void acceptsCompleteV2AccountsWithHashBoundOrdersAndImmutableScopedEvidence() throws Exception {
        Fixture f = fixture();
        var result = f.validate();
        assertTrue(result.valid(), result.issues().toString());
        assertEquals(DynamicMixedRunValidator.REPORT_SCHEMA, result.schemaVersion());
        assertEquals(DynamicMixedRunValidator.SCOPE, result.scope());
        assertEquals(3, result.finalIteration()); assertEquals(1, result.activationCount()); assertEquals(1, result.activationIteration());
        assertEquals(Set.of(0), result.preActivationFingerprint().keySet());
        assertEquals(Set.of(0), result.preActivationPlanFingerprint().keySet());
        assertEquals(4L, result.artifactRowCounts().get("iteration_summary.csv"));
        assertEquals(4L, result.artifactRowCounts().get("passenger_exposure.csv.gz"));
        assertEquals(11, result.checkedFiles().size());
        assertEquals(f.spec.inputs().executionOrders().sha256(), result.checkedFiles().get("input.executionOrders").sha256());
        assertTrue(result.unverifiedBoundaries().contains("historical-M-reuse"));
        assertTrue(result.unverifiedBoundaries().contains("native-capacity-mechanism-exercise"));
        assertThrows(UnsupportedOperationException.class, () -> result.checks().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.issues().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.preActivationFingerprint().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.preActivationPlanFingerprint().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.artifactRowCounts().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.checkedFiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.unverifiedBoundaries().clear());
    }

    @Test void formalTwoHundredIterationCoverageUsesSpecNotCompressedFixtureDefaults() throws Exception {
        Fixture f = fixture(new DynamicMixedDesign.Protocol(0, 20, 150, 199, 170, 199), .8,
                ScientificRunContext.ExposureOutput.REPORTING_WINDOW);
        var result = f.validate();
        assertTrue(result.valid(), result.issues().toString());
        assertEquals(199, result.finalIteration()); assertEquals(20, result.activationIteration());
        assertEquals(20, result.preActivationPlanFingerprint().size());
        assertEquals(200L, result.artifactRowCounts().get("order_ledger.csv"));
        assertEquals(60L, result.artifactRowCounts().get("passenger_exposure.csv.gz"));
        // No actual StrategyManager or dispatch execution is inferred from these recorded phase rows.
        assertTrue(result.unverifiedBoundaries().contains("actual-innovation-weights"));
    }

    @Test void terminalExposureUsesSpecAndCurrentSpecRejectsNonzeroFirstIteration() throws Exception {
        Fixture f = fixture(new DynamicMixedDesign.Protocol(0, 2, 3, 4, 3, 4), .8,
                ScientificRunContext.ExposureOutput.TERMINAL);
        var result = f.validate();
        assertTrue(result.valid(), result.issues().toString());
        assertEquals(Set.of(0, 1), result.preActivationFingerprint().keySet());
        assertEquals(2L, result.artifactRowCounts().get("passenger_exposure.csv.gz"));
        assertThrows(IllegalArgumentException.class, () -> replace(f.spec, "design",
                new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED,
                        new DynamicMixedDesign.Protocol(7, 8, 9, 10, 9, 10), true, f.spec.design().limits())));
    }

    @Test void offAndZeroControlRetainDynamicIdentityWithoutInventingDispatchValidation() throws Exception {
        for (boolean off : List.of(true, false)) {
            Fixture f = fixture();
            var design = new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED, f.spec.design().protocol(), !off,
                    off ? f.spec.design().limits() : new DispatchCommitmentState.Limits(0, 0, 5));
            f.spec = replace(f.spec, "design", design);
            var result = f.validate();
            assertTrue(result.valid(), result.issues().toString());
            assertTrue(result.unverifiedBoundaries().contains("dispatch-and-actual-headways"));
        }
    }

    @Test void sourceOrderHashMustMatchEvenWhenAllLedgersAgreeWithEachOther() throws Exception {
        Fixture f = fixture();
        Files.writeString(f.orderInput, Fixture.ORDER_CSV.replace(",standard,", ",changed,"));
        assertFailed(f.validate(), "execution_order_contract");
    }

    @Test void duplicatedOrMissingSourceOrderIsRejectedAfterCorrectRehashing() throws Exception {
        Fixture duplicate = fixture();
        duplicate.rewriteOrders(Fixture.ORDER_CSV + Fixture.ORDER_CSV.lines().skip(1).findFirst().orElseThrow() + "\n");
        assertFailed(duplicate.validate(), "execution_order_contract");
        Fixture missing = fixture(); missing.rewriteOrders(Fixture.ORDER_CSV.lines().findFirst().orElseThrow() + "\n");
        assertFailed(missing.validate(), "execution_order_contract");
    }

    @Test void consistentlyFabricatedIdentityOrUnitCountCannotReplaceTheRealOrderContract() throws Exception {
        Fixture renamed = fixture(); renamed.orders.forEach(row -> row.put("order_id", "fabricated"));
        assertFailed(renamed.validate(), "order_ledger");
        Fixture units = fixture(); units.orders.forEach(row -> row.put("number_of_units", "3"));
        assertFailed(units.validate(), "order_ledger");
        Fixture metadata = fixture(); metadata.orders.forEach(row -> row.put("deadline_seconds", "101"));
        assertFailed(metadata.validate(), "order_ledger");
    }

    @Test void sourceTimeFormatAndGeneratedUnitCollisionsAreNotSilentlyRepaired() throws Exception {
        Fixture time = fixture(); time.rewriteOrders(Fixture.ORDER_CSV.replace("00:00:10", "10"));
        assertFailed(time.validate(), "execution_order_contract");
        Fixture collision = fixture(); collision.rewriteOrders(Fixture.ORDER_CSV
                + "cargo_cap_02,00:00:10,00:01:40,0.2,1,standard,1,1\n");
        collision.spec = replace(collision.spec, "freightDemand", replace(collision.spec.freightDemand(), "orderCount", 2));
        assertFailed(collision.validate(), "execution_order_contract");
    }

    @Test void schemaRequiresExactV2MetadataAndActualFieldDefinitions() throws Exception {
        Fixture f = fixture(); f.write();
        Path schema = f.output.resolve("artifact_schema.json");
        var mapper = new ObjectMapper(); var node = (ObjectNode) mapper.readTree(schema.toFile());
        node.put("design_family", "legacy"); mapper.writeValue(schema.toFile(), node);
        assertFailed(f.read(), "artifact_schema");
        f.write(); node = (ObjectNode) mapper.readTree(schema.toFile());
        ((ObjectNode) node.path("field_definitions").path("iteration_summary.csv").get(5)).put("unit", "invented");
        mapper.writeValue(schema.toFile(), node); assertFailed(f.read(), "artifact_schema");
        f.write(); node = (ObjectNode) mapper.readTree(schema.toFile());
        node.put("activation_iteration", 2); mapper.writeValue(schema.toFile(), node); assertFailed(f.read(), "artifact_schema");
    }

    @Test void duplicateJsonFieldsAndTrailingJsonTokensAreRejected() throws Exception {
        Fixture f = fixture(); f.write(); Path schema = f.output.resolve("artifact_schema.json");
        String original = Files.readString(schema);
        Files.writeString(schema, original.substring(0, original.length() - 1) + ",\"schema_version\":\"duplicate\"}");
        assertFailed(f.read(), "artifact_schema");
        Files.writeString(schema, original + " {}"); assertFailed(f.read(), "artifact_schema");
    }

    @Test void legacyArmSchemaAndMissingOrDuplicateSummaryPeriodsAreRejected() throws Exception {
        Fixture arm = fixture(); arm.summaries.get(2).put("arm", "TIMETABLE_ONLY"); assertFailed(arm.validate(), "iteration_summary");
        Fixture schema = fixture(); schema.orders.getFirst().put("schema_version", IsmtRunArtifactWriter.SCHEMA); assertFailed(schema.validate(), "order_ledger");
        Fixture missing = fixture(); missing.summaries.remove(1); assertFailed(missing.validate(), "iteration_summary");
        Fixture duplicate = fixture(); duplicate.summaries.add(1, new LinkedHashMap<>(duplicate.summaries.getFirst())); assertFailed(duplicate.validate(), "iteration_summary");
    }

    @Test void otherV2ArmIsOutsideThisValidatorScope() throws Exception {
        Fixture f = fixture();
        f.spec = replace(f.spec, "design", new DynamicMixedDesign(DynamicMixedDesign.Arm.MIXED,
                f.spec.design().protocol(), false, f.spec.design().limits()));
        assertFailed(f.validate(), "path_and_v2_scope");
    }

    @Test void wrongPhaseReceiptAndPreactivationFreightLeakageAreRejected() throws Exception {
        for (var mutation : List.of(Map.entry("phase_activation_count", "2"), Map.entry("phase_activation_iteration", "0"),
                Map.entry("phase_active", "false"))) {
            Fixture f = fixture(); f.summaries.get(2).put(mutation.getKey(), mutation.getValue()); assertFailed(f.validate(), "iteration_summary");
        }
        Fixture leakage = fixture(); leakage.summaries.getFirst().put("freight_events", "1"); assertFailed(leakage.validate(), "iteration_summary");
    }

    @Test void incompleteFreightIsARecordedOutcomeNotAutomaticallyASoftwareFailure() throws Exception {
        Fixture f = fixture();
        for (int iteration = 1; iteration <= 3; iteration++) {
            var order = f.orders.get(iteration);
            put(order, "units_delivered", 1, "delivery_events", 1, "completion_time_seconds", "", "lateness_minutes", "");
            for (String field : List.of("completed", "completed_in_service_window", "completed_within_execution_horizon", "on_time")) order.put(field, "false");
            var summary = f.summaries.get(iteration);
            for (String field : List.of("completed_orders", "service_window_completed_orders", "eventual_completed_orders", "on_time_orders", "completed_order_lateness_sample_size")) summary.put(field, "0");
            summary.put("mean_lateness_minutes", "");
        }
        assertValid(f.validate()); f.orders.get(2).put("completed", "true"); assertFailed(f.validate(), "order_ledger");
    }

    @Test void serviceWindowAndExecutionHorizonRemainDifferentAccounts() throws Exception {
        Fixture f = fixture();
        for (int iteration = 1; iteration <= 3; iteration++) {
            put(f.orders.get(iteration), "completion_time_seconds", 600, "lateness_minutes", 500.0 / 60, "completed_in_service_window", false);
            put(f.summaries.get(iteration), "service_window_completed_orders", 0, "mean_lateness_minutes", 500.0 / 60);
        }
        assertValid(f.validate());
        f.orders.get(2).put("first_accessible_departure_time_seconds", "20"); assertFailed(f.validate(), "order_ledger");
    }

    @Test void rejectsDuplicateMissingOrdersDeliveryOverflowAndFakeFirstBoardingTimes() throws Exception {
        Fixture duplicate = fixture(); duplicate.orders.add(1, new LinkedHashMap<>(duplicate.orders.getFirst())); assertFailed(duplicate.validate(), "order_ledger");
        Fixture missing = fixture(); missing.orders.remove(2); assertFailed(missing.validate(), "order_ledger");
        Fixture delivery = fixture(); delivery.orders.get(2).put("delivery_events", "3"); assertFailed(delivery.validate(), "order_ledger");
        Fixture units = fixture(); units.orders.get(2).put("units_delivered", "3"); assertFailed(units.validate(), "order_ledger");
        Fixture before = fixture(); before.orders.get(2).put("first_unit_board_time_seconds", "9"); assertFailed(before.validate(), "order_ledger");
        Fixture unboarded = fixture(); unboarded.orders.getFirst().put("first_unit_board_time_seconds", "20"); assertFailed(unboarded.validate(), "order_ledger");
    }

    @Test void positiveDenialUsesRegisteredUnitAndReconcilesNativeSnapshotAndOrderReasons() throws Exception {
        Fixture f = fixture(); f.freightDenial(2); assertValid(f.validate());
        f.denials.getFirst().put("actor_id", "cargo_cap_02"); assertValid(f.validate());
        f.denials.getFirst().put("actor_id", "cargo_cap_03"); assertFailed(f.validate(), "denial_ledger");
    }

    @Test void denialMismatchAndFreePlaceNativeCapacityClaimAreRejected() throws Exception {
        Fixture reason = fixture(); reason.freightDenial(2); reason.denials.getFirst().put("denial_reason", "NATIVE_CAPACITY");
        assertFailed(reason.validate(), "denial_ledger");
        Fixture count = fixture(); count.freightDenial(2); count.summaries.get(2).put("denial_detail_events", "0"); assertFailed(count.validate(), "denial_ledger");
        Fixture policy = fixture(); policy.freightDenial(2); policy.denials.getFirst().put("freight_share_cap", ".2"); assertFailed(policy.validate(), "denial_ledger");
        Fixture overflow = fixture(); overflow.orders.get(2).put("denial_reasons", "NATIVE_CAPACITY=9223372036854775807|FREIGHT_SHARE_CAP=1"); assertFailed(overflow.validate(), "order_ledger");
    }

    @Test void zeroBetaRetainsRawExposureButRequiresExactlyZeroPenalty() throws Exception {
        Fixture f = fixture(new DynamicMixedDesign.Protocol(0, 1, 2, 3, 2, 3), 0, ScientificRunContext.ExposureOutput.REPORTING_WINDOW);
        assertValid(f.validate()); assertEquals("1.5", f.summaries.get(3).get("raw_freight_copresence_exposure"));
        f.exposure.getLast().put("co_presence_penalty", "1e-16"); assertFailed(f.validate(), "passenger_exposure");
    }

    @Test void unfinishedStateOverloadAndMalformedScientificTypesAreRejected() throws Exception {
        for (var mutation : List.of(Map.entry("exposure_event_state_valid", "false"), Map.entry("unfinished_passenger_rides", "1"),
                Map.entry("unfinished_freight_units_onboard", "1"), Map.entry("max_load_ratio", "1.0000000001"),
                Map.entry("freight_events", "1.5"), Map.entry("phase_active", "1"), Map.entry("raw_physical_exposure", "NaN"))) {
            Fixture f = fixture(); f.summaries.get(2).put(mutation.getKey(), mutation.getValue()); assertFailed(f.validate(), "iteration_summary");
        }
    }

    @Test void passengerExposureIdentityCoverageAndAggregateMustReconcile() throws Exception {
        Fixture missing = fixture(); missing.exposure.removeLast(); assertFailed(missing.validate(), "passenger_exposure");
        Fixture duplicate = fixture(); duplicate.exposure.get(1).put("person_id", "p1"); assertFailed(duplicate.validate(), "passenger_exposure");
        Fixture freight = fixture(); freight.exposure.getFirst().put("person_id", "cargo_cap_02"); assertFailed(freight.validate(), "passenger_exposure");
        Fixture aggregate = fixture(); put(aggregate.summaries.get(2), "raw_physical_exposure", 4, "physical_penalty", 3.2, "applied_physical_penalty", 3.2);
        assertFailed(aggregate.validate(), "passenger_exposure");
    }

    @Test void scoreModeAndServiceTablesMustMatchSummaryAndCoverAllIterations() throws Exception {
        Fixture score = fixture(); score.scores.get(2).put("avg_selected_score", "-2"); assertFailed(score.validate(), "passenger_statistics");
        Fixture count = fixture(); count.scores.get(2).put("scored_persons", "1"); assertFailed(count.validate(), "passenger_statistics");
        Fixture mode = fixture(); mode.modes.get(2).put("walk", ".4"); assertFailed(mode.validate(), "passenger_statistics");
        Fixture service = fixture(); service.services.get(2).put("pt_wait_mean_min", "2"); assertFailed(service.validate(), "passenger_statistics");
        Fixture quantile = fixture(); quantile.services.get(2).put("pt_wait_p50_min", "9"); assertFailed(quantile.validate(), "passenger_statistics");
        Fixture missing = fixture(); missing.services.remove(2); assertFailed(missing.validate(), "passenger_statistics");
    }

    @Test void unavailableScoresAndServiceMeansMustHaveMatchingZeroSamples() throws Exception {
        Fixture f = fixture();
        for (int iteration = 0; iteration <= 3; iteration++) {
            put(f.summaries.get(iteration), "scored_passenger_count", 0, "selected_plan_passenger_score", "",
                    "completed_pt_trips", 0, "mean_pt_door_to_door_minutes", "", "mean_pt_wait_minutes", "");
            put(f.scores.get(iteration), "scored_persons", 0, "avg_selected_score", "");
            put(f.services.get(iteration), "completed_pt_trips", 0);
            for (String field : DynamicMixedRunValidator.SERVICE_HEADER.subList(6, DynamicMixedRunValidator.SERVICE_HEADER.size())) f.services.get(iteration).put(field, "");
        }
        assertValid(f.validate()); f.services.get(2).put("pt_wait_mean_min", "0"); assertFailed(f.validate(), "passenger_statistics");
    }

    @Test void eventAndPlanEnvelopesRequireV2IdentityValidDigestAndCoverage() throws Exception {
        Fixture missing = fixture(); missing.fingerprints.remove(2); assertFailed(missing.validate(), "passenger_event_fingerprint");
        Fixture malformed = fixture(); malformed.fingerprints.get(2).put("event_sha256", "A".repeat(64)); assertFailed(malformed.validate(), "passenger_event_fingerprint");
        Fixture family = fixture(); family.plans.get(2).put("design_family", "v1"); assertFailed(family.validate(), "passenger_plan_fingerprint");
        Fixture count = fixture(); count.plans.get(2).put("passenger_count", "3"); assertFailed(count.validate(), "passenger_plan_fingerprint");
        Fixture extra = fixture(); extra.plans.add(new LinkedHashMap<>(extra.plans.getLast())); assertFailed(extra.validate(), "passenger_plan_fingerprint");
    }

    @Test void oldFingerprintHeadersMalformedCsvAndBrokenGzipAreRejected() throws Exception {
        Fixture f = fixture(); f.write();
        csv(f.output.resolve(PassengerScientificFingerprint.FILE_NAME), DynamicMixedRunValidator.EVENT_HEADER.subList(0, 7), f.fingerprints);
        assertFailed(f.read(), "passenger_event_fingerprint");
        f.write(); Path summary = f.output.resolve("iteration_summary.csv");
        Files.writeString(summary, Files.readString(summary) + "incomplete,row\n"); assertFailed(f.read(), "iteration_summary");
        f.write(); Files.write(f.output.resolve("passenger_exposure.csv.gz"), new byte[]{31, (byte) 139, 8});
        assertFailed(f.read(), "passenger_exposure");
    }

    @Test void independentErrorsArePreservedAndReceiptAndAllReadFilesRemainUntouched() throws Exception {
        Fixture f = fixture(); f.write();
        Path receipt = f.root.resolve("run_receipt.json"); String receiptText = "{\"status\":\"COMPLETED_UNVALIDATED\"}\n";
        Files.writeString(receipt, receiptText);
        var good = f.read(); assertValid(good);
        var hashes = new LinkedHashMap<String, String>();
        good.checkedFiles().forEach((name, evidence) -> hashes.put(name, evidence.sha256()));
        var repeated = f.read(); assertValid(repeated);
        repeated.checkedFiles().forEach((name, evidence) -> assertEquals(hashes.get(name), evidence.sha256()));
        assertEquals(receiptText, Files.readString(receipt));
        Files.delete(f.output.resolve("artifact_schema.json")); Files.delete(f.output.resolve(PassengerPlanFingerprint.FILE_NAME));
        var failed = f.read(); assertFailed(failed, "artifact_schema"); assertFailed(failed, "passenger_plan_fingerprint");
        assertTrue(failed.issues().size() >= 2); assertEquals(receiptText, Files.readString(receipt));
    }

    @Test void outputAndInputSymlinksAndRunOutsideDeclaredWorkspaceFailClosed() throws Exception {
        Fixture outside = fixture(); outside.write(); Path nestedWorkspace = Files.createDirectory(temp.resolve("other-workspace"));
        assertFailed(new DynamicMixedRunValidator().validate(nestedWorkspace, outside.root, outside.spec), "path_and_v2_scope");
        Fixture link = fixture(); link.write(); Path original = link.output.resolve("order_ledger.csv"), moved = link.output.resolve("original.csv");
        Files.move(original, moved); Files.createSymbolicLink(original, moved); assertFailed(link.read(), "order_ledger");
        Fixture input = fixture(); input.write(); Path actual = input.root.resolve("original-orders.csv");
        Files.move(input.orderInput, actual); Files.createSymbolicLink(input.orderInput, actual); assertFailed(input.read(), "execution_order_contract");
    }

    private static void assertValid(DynamicMixedRunValidator.Report report) { assertTrue(report.valid(), report.issues().toString()); }
    private static void assertFailed(DynamicMixedRunValidator.Report report, String gate) {
        assertFalse(report.valid()); assertEquals(false, report.checks().get(gate), report.issues().toString());
    }
    private Fixture fixture() throws Exception {
        return fixture(new DynamicMixedDesign.Protocol(0, 1, 2, 3, 2, 3), .8, ScientificRunContext.ExposureOutput.REPORTING_WINDOW);
    }
    private Fixture fixture(DynamicMixedDesign.Protocol protocol, double betaFreight, ScientificRunContext.ExposureOutput mode) throws Exception {
        return new Fixture(temp, temp.resolve("run-" + sequence++), protocol, betaFreight, mode);
    }

    private static final class Fixture {
        static final String ORDER_CSV = "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
                + "cargo,00:00:10,00:01:40,0.4,1,standard,1,2\n";
        final Path workspace, root, output, orderInput;
        DynamicMixedExperimentSpec spec;
        final List<Map<String, String>> summaries = new ArrayList<>(), orders = new ArrayList<>(), exposure = new ArrayList<>(),
                denials = new ArrayList<>(), fingerprints = new ArrayList<>(), plans = new ArrayList<>(),
                scores = new ArrayList<>(), modes = new ArrayList<>(), services = new ArrayList<>();

        Fixture(Path workspace, Path root, DynamicMixedDesign.Protocol protocol, double betaFreight,
                ScientificRunContext.ExposureOutput mode) throws Exception {
            this.workspace = workspace; this.root = root; this.output = root.resolve("matsim");
            Files.createDirectories(output); orderInput = root.resolve("execution-orders.csv"); Files.writeString(orderInput, ORDER_CSV);
            var base = DynamicMixedSpecFixtures.spec(); var runtime = base.runtime();
            runtime = replace(runtime, "executionHorizonSeconds", 129600.0);
            runtime = replace(runtime, "behaviouralHorizonSeconds", 86400.0);
            var input = new ExperimentSpec.FileRef(workspace.relativize(orderInput).toString(), ExperimentConfiguration.sha256(orderInput));
            spec = new DynamicMixedExperimentSpec(DynamicMixedExperimentSpec.SCHEMA, "scientific-validator-v2", "B1",
                    new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED, protocol, true, base.design().limits()),
                    base.seeds(), replace(base.inputs(), "executionOrders", input),
                    new ExperimentSpec.FreightDemand(1, 5, 0, 500, "freight_destination", "_cap_"),
                    replace(base.policies(), "betaFreight", betaFreight), runtime, base.control(),
                    replace(base.output(), "passengerExposure", mode), "synthetic-artifact-consistency-only");
            for (int iteration = protocol.firstIteration(); iteration <= protocol.finalIteration(); iteration++) {
                boolean active = iteration >= protocol.activationIteration();
                var summary = row(IsmtRunArtifactWriter.SUMMARY_COLUMNS, iteration);
                put(summary, "passenger_count", 2, "scored_passenger_count", 2, "selected_passenger_trips", 2, "pt_share", .5,
                        "selected_plan_passenger_score", -1, "completed_pt_trips", 1, "mean_pt_door_to_door_minutes", 5, "mean_pt_wait_minutes", 1,
                        "raw_physical_exposure", 2, "physical_penalty", 1.6, "applied_physical_penalty", 1.6,
                        "raw_freight_copresence_exposure", active ? 1.5 : 0, "co_presence_penalty", active ? 1.5 * betaFreight : 0,
                        "applied_co_presence_penalty", active ? 1.5 * betaFreight : 0, "freight_order_count", 1,
                        "completed_orders", active ? 1 : 0, "service_window_completed_orders", active ? 1 : 0, "eventual_completed_orders", active ? 1 : 0,
                        "mean_first_board_wait_minutes", active ? Double.toString(10.0 / 60) : "", "mean_lateness_minutes", active ? Double.toString(20.0 / 60) : "",
                        "first_board_wait_sample_size", active ? 1 : 0, "completed_order_lateness_sample_size", active ? 1 : 0,
                        "passenger_boardings", 2, "freight_boardings", active ? 2 : 0, "freight_events", active ? 6 : 0,
                        "phase_active", active, "phase_activation_count", active ? 1 : 0, "phase_activation_iteration", active ? protocol.activationIteration() : -1,
                        "exposure_event_state_valid", true, "max_load_ratio", .8, "max_freight_share_of_capacity", active ? .1 : 0,
                        "passengers_with_raw_copresence", active ? 2 : 0, "raw_copresence_incidence", active ? 1 : 0);
                summaries.add(summary);
                var order = row(IsmtRunArtifactWriter.ORDER_COLUMNS, iteration);
                put(order, "order_id", "cargo", "release_time_seconds", 10, "number_of_units", 2, "units_boarded", active ? 2 : 0,
                        "units_delivered", active ? 2 : 0, "first_unit_board_time_seconds", active ? "20" : "", "last_unit_board_time_seconds", active ? "30" : "",
                        "first_accessible_departure_time_seconds", "", "schedule_wait_minutes", "", "additional_admission_delay_minutes", "",
                        "first_board_wait_minutes", active ? Double.toString(10.0 / 60) : "", "completion_time_seconds", active ? "120" : "", "deadline_seconds", 100,
                        "service_class", "standard", "completed", active, "completed_in_service_window", active, "completed_within_execution_horizon", active,
                        "on_time", false, "lateness_minutes", active ? Double.toString(20.0 / 60) : "", "denial_reasons", "",
                        "boarding_events", active ? 2 : 0, "delivery_events", active ? 2 : 0, "accessible_departure_status", IsmtRunArtifactWriter.UNAVAILABLE_DEPARTURE);
                orders.add(order);
                if (iteration == protocol.finalIteration() || mode == ScientificRunContext.ExposureOutput.REPORTING_WINDOW
                        && iteration >= protocol.reportFirstIteration() && iteration <= protocol.reportLastIteration()) {
                    for (int person = 1; person <= 2; person++) {
                        var row = row(IsmtRunArtifactWriter.EXPOSURE_COLUMNS, iteration);
                        double physical = person == 1 ? 2 : 0, copresence = active ? person * .5 : 0;
                        put(row, "person_id", "p" + person, "raw_physical_exposure", physical, "raw_freight_copresence_exposure", copresence,
                                "physical_penalty", .8 * physical, "co_presence_penalty", betaFreight * copresence, "applied_physical_penalty", .8 * physical,
                                "applied_co_presence_penalty", betaFreight * copresence, "valued_total_penalty", .8 * physical + betaFreight * copresence,
                                "total_added_penalty", .8 * physical + betaFreight * copresence, "unfinished_ride", false);
                        exposure.add(row);
                    }
                }
                var event = row(DynamicMixedRunValidator.EVENT_HEADER, iteration);
                put(event, "event_count", 10, "event_sha256", "a".repeat(64), "schema_version", PassengerScientificFingerprint.SCHEMA_VERSION,
                        "design_family", DynamicMixedDesign.SCHEMA_ID); fingerprints.add(event);
                var plan = row(DynamicMixedRunValidator.PLAN_HEADER, iteration);
                put(plan, "passenger_count", 2, "plan_sha256", "b".repeat(64), "schema_version", PassengerPlanFingerprint.SCHEMA_VERSION,
                        "design_family", DynamicMixedDesign.SCHEMA_ID); plans.add(plan);
                var score = row(DynamicMixedRunValidator.SCORE_HEADER, iteration);
                put(score, "passenger_persons", 2, "scored_persons", 2, "avg_selected_score", -1); scores.add(score);
                var modesRow = row(DynamicMixedRunValidator.MODE_HEADER, iteration);
                put(modesRow, "passenger_trips", 2, "pt", .5, "walk", .5); modes.add(modesRow);
                var service = row(DynamicMixedRunValidator.SERVICE_HEADER, iteration);
                put(service, "completed_passenger_trips", 2, "completed_pt_trips", 1);
                for (int index = 3; index < DynamicMixedRunValidator.SERVICE_HEADER.size(); index++)
                    service.put(DynamicMixedRunValidator.SERVICE_HEADER.get(index), index < 9 ? "5" : index < 12 ? "1" : "4");
                services.add(service);
            }
        }

        void rewriteOrders(String csv) throws Exception {
            Files.writeString(orderInput, csv);
            spec = replace(spec, "inputs", replace(spec.inputs(), "executionOrders",
                    new ExperimentSpec.FileRef(workspace.relativize(orderInput).toString(), ExperimentConfiguration.sha256(orderInput))));
        }
        void freightDenial(int iteration) {
            var row = row(IsmtRunArtifactWriter.DENIAL_COLUMNS, iteration);
            put(row, "denial_sequence", 1, "time_seconds", 15, "actor_id", "cargo", "actor_class", "FREIGHT", "order_id", "cargo",
                    "vehicle_id", "v", "line_id", "l", "route_id", "r", "departure_id", "", "stop_id", "s", "denial_reason", "FREIGHT_SHARE_CAP",
                    "passenger_occupancy", 1, "freight_occupancy", 1, "native_capacity", 10, "freight_share_cap", .1, "passenger_load_gate", .9);
            denials.add(row); put(orders.get(iteration), "denial_count", 1, "denial_reasons", "FREIGHT_SHARE_CAP=1");
            put(summaries.get(iteration), "freight_denied_boardings", 1, "denial_detail_events", 1);
        }
        DynamicMixedRunValidator.Report validate() throws Exception { write(); return read(); }
        DynamicMixedRunValidator.Report read() { return new DynamicMixedRunValidator().validate(workspace, root, spec); }
        void write() throws Exception {
            Map<String, List<String>> columns = Map.of("iteration_summary.csv", IsmtRunArtifactWriter.SUMMARY_COLUMNS,
                    "order_ledger.csv", IsmtRunArtifactWriter.ORDER_COLUMNS, "denial_ledger.csv", IsmtRunArtifactWriter.DENIAL_COLUMNS,
                    "passenger_exposure.csv.gz", IsmtRunArtifactWriter.EXPOSURE_COLUMNS);
            Map<String, Object> definitions = new TreeMap<>();
            columns.forEach((file, names) -> definitions.put(file, names.stream().map(name -> DynamicMixedRunValidator.fieldDefinition(file, name)).toList()));
            var schema = new LinkedHashMap<String, Object>(spec.scientificContext().additionalSchemaMetadata());
            schema.put("schema_version", ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA);
            schema.put("passenger_exposure_mode", spec.output().passengerExposure().name());
            schema.put("detailed_vehicle_observations", spec.output().vehicleIntervals());
            schema.put("files", columns); schema.put("field_definitions", definitions);
            new ObjectMapper().writeValue(output.resolve("artifact_schema.json").toFile(), schema);
            csv(output.resolve("iteration_summary.csv"), IsmtRunArtifactWriter.SUMMARY_COLUMNS, summaries);
            csv(output.resolve("order_ledger.csv"), IsmtRunArtifactWriter.ORDER_COLUMNS, orders);
            csv(output.resolve("denial_ledger.csv"), IsmtRunArtifactWriter.DENIAL_COLUMNS, denials);
            csv(output.resolve("passenger_exposure.csv.gz"), IsmtRunArtifactWriter.EXPOSURE_COLUMNS, exposure);
            csv(output.resolve(PassengerScientificFingerprint.FILE_NAME), DynamicMixedRunValidator.EVENT_HEADER, fingerprints);
            csv(output.resolve(PassengerPlanFingerprint.FILE_NAME), DynamicMixedRunValidator.PLAN_HEADER, plans);
            csv(output.resolve("passenger_scorestats.csv"), DynamicMixedRunValidator.SCORE_HEADER, scores);
            csv(output.resolve("passenger_modestats.csv"), DynamicMixedRunValidator.MODE_HEADER, modes);
            csv(output.resolve("passenger_service_stats.csv"), DynamicMixedRunValidator.SERVICE_HEADER, services);
        }
        Map<String, String> row(List<String> columns, int iteration) {
            Map<String, String> row = new LinkedHashMap<>(); columns.forEach(name -> row.put(name, "0"));
            put(row, "schema_version", ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA, "experiment_id", spec.experimentId(), "block_id", spec.blockId(),
                    "arm", spec.design().arm().name(), "iteration", iteration);
            return row;
        }
    }
    private static void put(Map<String, String> row, Object... fields) {
        for (int index = 0; index < fields.length; index += 2) row.put(fields[index].toString(), fields[index + 1].toString());
    }
    private static void csv(Path path, List<String> header, List<Map<String, String>> rows) throws Exception {
        char delimiter = path.getFileName().toString().startsWith("passenger_") && path.getFileName().toString().endsWith("stats.csv") ? ';' : ',';
        try (BufferedWriter writer = path.toString().endsWith(".gz")
                ? new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8))
                : Files.newBufferedWriter(path, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.RFC4180.builder().setDelimiter(delimiter).setHeader(header.toArray(String[]::new)).build())) {
            for (var row : rows) printer.printRecord(header.stream().map(row::get).toList());
        }
    }
}
