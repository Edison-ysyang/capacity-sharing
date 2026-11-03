package org.matsim.run.ismt.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.run.ismt.ScientificRunContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedExperimentSpec.*;

/** Pure IO fixtures only. File references are intentionally nonexistent and are not input approval. */
class DynamicMixedExperimentSpecIOTest {
    private static final ObjectMapper EDITOR = new ObjectMapper();
    @TempDir Path temporary;

    @Test void roundTripWritesExactCanonicalBytesAndMatchingFileHashWithoutOverwriting() throws Exception {
        var spec = fixture();
        Path file = temporary.resolve("new-spec.json");
        byte[] canonical = DynamicMixedExperimentSpecIO.canonicalBytes(spec);
        DynamicMixedExperimentSpecIO.writeNew(file, spec);
        assertArrayEquals(canonical, Files.readAllBytes(file));
        assertEquals(DynamicMixedExperimentSpecIO.sha256(spec), HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        assertEquals(spec, DynamicMixedExperimentSpecIO.read(file));
        assertThrows(IOException.class, () -> DynamicMixedExperimentSpecIO.writeNew(file, spec));
        assertArrayEquals(canonical, Files.readAllBytes(file));
        try (var files = Files.list(temporary)) {
            assertEquals(List.of(file), files.toList(), "IO must not resolve or create the referenced inputs");
        }
    }

    @Test void missingParentAndNullArgumentsCannotCreateAnOutputOrImplicitDefaults() throws Exception {
        Path absent = temporary.resolve("absent/spec.json");
        assertThrows(IOException.class, () -> DynamicMixedExperimentSpecIO.writeNew(absent, fixture()));
        assertFalse(Files.exists(absent.getParent()));
        Path nullSpec = temporary.resolve("null-spec.json");
        assertThrows(NullPointerException.class, () -> DynamicMixedExperimentSpecIO.canonicalBytes(null));
        assertThrows(NullPointerException.class, () -> DynamicMixedExperimentSpecIO.sha256(null));
        assertThrows(NullPointerException.class, () -> DynamicMixedExperimentSpecIO.writeNew(nullSpec, null));
        assertFalse(Files.exists(nullSpec));
        assertThrows(NullPointerException.class, () -> DynamicMixedExperimentSpecIO.read(null));
    }

    @Test void everyRequiredPropertyIncludingEveryNestedRecordRejectsOmission() throws Exception {
        ObjectNode original = tree();
        var fields = allProperties(original, "");
        assertTrue(fields.size() > 100, "Exercise the full declaration, not just top-level fields");
        for (var field : fields) {
            ObjectNode edited = original.deepCopy();
            assertNotNull(((ObjectNode) edited.at(field.parent())).remove(field.name()));
            assertInvalid(EDITOR.writeValueAsString(edited), "Missing " + field);
        }
    }

    @Test void everyRequiredPropertyIncludingEveryNestedRecordRejectsExplicitNull() throws Exception {
        ObjectNode original = tree();
        for (var field : allProperties(original, "")) {
            ObjectNode edited = original.deepCopy();
            ((ObjectNode) edited.at(field.parent())).putNull(field.name());
            assertInvalid(EDITOR.writeValueAsString(edited), "Null " + field);
        }
    }

    @Test void unknownPropertiesAreRejectedAtEveryObjectDepth() throws Exception {
        ObjectNode original = tree();
        var objects = new ArrayList<String>();
        objects.add("");
        for (var field : allProperties(original, "")) if (original.at(field.pointer()).isObject()) objects.add(field.pointer());
        for (String pointer : objects) {
            ObjectNode edited = original.deepCopy();
            ((ObjectNode) edited.at(pointer)).put("unregisteredScientificDefault", 0);
            assertInvalid(EDITOR.writeValueAsString(edited), "Unknown field at " + pointer);
        }
    }

    @Test void duplicateKeysFailEvenWhenValuesAreIdenticalAtRootOrNested() throws Exception {
        String canonical = canonicalText();
        for (String value : List.of(SCHEMA, "different-schema")) {
            assertInvalid("{\"schemaVersion\":\"" + value + "\"," + canonical.substring(1), "Duplicate root schema");
        }
        String needle = "\"decisionIntervalSeconds\":10.0";
        assertTrue(canonical.contains(needle));
        for (String value : List.of("10.0", "11.0")) {
            assertInvalid(canonical.replace(needle, needle + ",\"decisionIntervalSeconds\":" + value), "Duplicate timing");
        }
    }

    @Test void onlyOneNonNullRootObjectIsAccepted() throws Exception {
        for (String text : List.of("", " \n\t", "null", "[]", "[" + canonicalText() + "]", "true", "42", "\"value\""))
            assertInvalid(text, "Non-object or missing root");
        for (String trailing : List.of("{}", "null", "true", "42", "\"another\"", "[]", "garbage"))
            assertInvalid(canonicalText() + " \n" + trailing, "Trailing token/content");
        assertEquals(fixture(), read(" \n\t" + canonicalText() + " \n\t"), "Whitespace is not a second JSON value");
    }

    @Test void numberAndBooleanTokensCannotBecomeStrings() throws Exception {
        for (Object value : List.of(123, 1.25, true, false)) {
            assertRejectedValue("/experimentId", value);
            assertRejectedValue("/control/policyId", value);
            assertRejectedValue("/inputs/network/path", value);
        }
    }

    @Test void stringsCannotBecomeNumbersOrBooleansAndNumbersCannotBecomeBooleans() throws Exception {
        for (String pointer : List.of("/seeds/passenger", "/runtime/globalThreads", "/policies/betaFreight",
                "/control/timing/decisionIntervalSeconds")) {
            assertRejectedValue(pointer, tree().at(pointer).asText());
            assertRejectedValue(pointer, "");
            assertRejectedValue(pointer, true);
        }
        for (String pointer : List.of("/design/controlEnabled", "/runtime/synchronizeOnSimSteps",
                "/runtime/oneThreadPerHandler", "/output/vehicleIntervals")) {
            assertRejectedValue(pointer, tree().at(pointer).asText());
            assertRejectedValue(pointer, 0);
            assertRejectedValue(pointer, 1);
            assertRejectedValue(pointer, "");
        }
    }

    @Test void integerFieldsRejectFloatTokensEvenWhenMathematicallyIntegral() throws Exception {
        for (String pointer : List.of("/seeds/passenger", "/freightDemand/orderCount", "/design/protocol/activationIteration",
                "/runtime/expectedEventWorkers", "/output/eventsWriteInterval")) {
            assertRejectedValue(pointer, tree().at(pointer).asDouble());
            assertRejectedValue(pointer, tree().at(pointer).asDouble() + 0.25);
        }
        String canonical = canonicalText();
        String needle = "\"passenger\":3";
        assertTrue(canonical.contains(needle));
        assertInvalid(canonical.replace(needle, "\"passenger\":3e0"), "Exponent notation is still a floating token");
    }

    @Test void enumsRequireExactDeclaredStringsAndNeverOrdinalOrLegacyArmAliases() throws Exception {
        Map<String, Integer> enumOrdinals = Map.ofEntries(Map.entry("/design/arm", 2),
                Map.entry("/policies/access", 1), Map.entry("/runtime/eventMode", 0),
                Map.entry("/runtime/eventWorkerSelection", 0), Map.entry("/control/informationModel", 0),
                Map.entry("/control/resetMode", 0), Map.entry("/control/baselineTreatment", 0),
                Map.entry("/control/operatingBasis", 0), Map.entry("/output/passengerExposure", 1));
        for (var entry : enumOrdinals.entrySet()) {
            assertRejectedValue(entry.getKey(), entry.getValue());
            assertRejectedValue(entry.getKey(), entry.getValue().toString());
            assertRejectedValue(entry.getKey(), "unknown-value");
            assertRejectedValue(entry.getKey(), tree().at(entry.getKey()).asText().toLowerCase(java.util.Locale.ROOT));
            assertRejectedValue(entry.getKey(), "");
        }
        assertRejectedValue("/design/arm", "TIMETABLE_ONLY");
        assertRejectedValue("/design/arm", "T");
        assertRejectedValue("/runtime/startTimeInterpretation", "ONLYUSESTARTTIME");
        assertRejectedValue("/runtime/endTimeInterpretation", 2);
    }

    @Test void singletonArraysAndMalformedArrayMembersCannotHideTypeErrors() throws Exception {
        ObjectNode edited = tree();
        edited.set("design", EDITOR.createArrayNode().add(edited.get("design")));
        assertInvalid(EDITOR.writeValueAsString(edited), "Do not unwrap a singleton record array");
        assertRejectedValue("/policies/transitModes", "pt");
        for (JsonNode invalid : List.of(EDITOR.getNodeFactory().nullNode(), EDITOR.getNodeFactory().numberNode(1),
                EDITOR.getNodeFactory().booleanNode(true))) {
            edited = tree();
            ((com.fasterxml.jackson.databind.node.ArrayNode) edited.at("/policies/transitModes")).set(0, invalid);
            assertInvalid(EDITOR.writeValueAsString(edited), "Invalid typed array member");
        }
    }

    @Test void unsupportedJsonSyntaxAndNonFiniteOrOutOfRangeNumbersAreRejected() throws Exception {
        assertInvalid("/* comment */" + canonicalText(), "Comments are not JSON");
        assertInvalid(canonicalText().replaceFirst("\\{", "{'extra':0,"), "Single quotes are not JSON");
        assertInvalid(canonicalText().substring(0, canonicalText().length() - 1) + ",}", "Trailing comma");
        String needle = "\"decisionIntervalSeconds\":10.0";
        for (String value : List.of("NaN", "Infinity", "-Infinity", "1e999"))
            assertInvalid(canonicalText().replace(needle, "\"decisionIntervalSeconds\":" + value), "Non-finite value");
        assertInvalid(canonicalText().replace("\"passenger\":3", "\"passenger\":9223372036854775808"), "Long overflow");
    }

    @Test void canonicalObjectKeysAreSortedRecursivelyAndInputKeyOrderDoesNotAffectHash() throws Exception {
        var spec = fixture();
        JsonNode canonical = EDITOR.readTree(DynamicMixedExperimentSpecIO.canonicalBytes(spec));
        assertObjectKeysSorted(canonical);
        JsonNode reversed = reverseObjectKeys(canonical);
        assertNotEquals(EDITOR.writeValueAsString(canonical), EDITOR.writeValueAsString(reversed));
        var reread = read(EDITOR.writerWithDefaultPrettyPrinter().writeValueAsString(reversed));
        assertEquals(spec, reread);
        assertEquals(DynamicMixedExperimentSpecIO.sha256(spec), DynamicMixedExperimentSpecIO.sha256(reread));
        assertArrayEquals(DynamicMixedExperimentSpecIO.canonicalBytes(spec), DynamicMixedExperimentSpecIO.canonicalBytes(reread));
    }

    @Test void numericSpellingIsCanonicalizedOnlyAfterStrictTypedDecoding() throws Exception {
        String canonical = canonicalText();
        assertTrue(canonical.contains("\"decisionIntervalSeconds\":10.0"));
        var exponent = read(canonical.replace("\"decisionIntervalSeconds\":10.0", "\"decisionIntervalSeconds\":1e1"));
        assertEquals(fixture(), exponent);
        assertArrayEquals(DynamicMixedExperimentSpecIO.canonicalBytes(fixture()), DynamicMixedExperimentSpecIO.canonicalBytes(exponent));
    }

    @Test void semanticListOrderInputIdentityAndControlChangesRemainHashVisible() throws Exception {
        String originalHash = DynamicMixedExperimentSpecIO.sha256(fixture());
        ObjectNode changed = tree();
        ((ObjectNode) changed.at("/policies")).set("transitModes", EDITOR.createArrayNode().add("freight_pt").add("pt"));
        var reversedModes = read(EDITOR.writeValueAsString(changed));
        assertEquals(List.of("freight_pt", "pt"), reversedModes.policies().transitModes());
        assertNotEquals(originalHash, DynamicMixedExperimentSpecIO.sha256(reversedModes));
        changed = tree();
        ((ObjectNode) changed.at("/inputs/network")).put("sha256", "b".repeat(64));
        assertNotEquals(originalHash, DynamicMixedExperimentSpecIO.sha256(read(EDITOR.writeValueAsString(changed))));
        changed = tree();
        ((ObjectNode) changed.at("/control/timing")).put("decisionIntervalSeconds", 11.0);
        assertNotEquals(originalHash, DynamicMixedExperimentSpecIO.sha256(read(EDITOR.writeValueAsString(changed))));
        changed = tree();
        ((ObjectNode) changed.at("/control/constraintDefinition/file")).put("sha256", "c".repeat(64));
        assertNotEquals(originalHash, DynamicMixedExperimentSpecIO.sha256(read(EDITOR.writeValueAsString(changed))));
    }

    private void assertRejectedValue(String pointer, Object value) throws Exception {
        ObjectNode changed = tree();
        int split = pointer.lastIndexOf('/');
        ((ObjectNode) changed.at(pointer.substring(0, split))).set(pointer.substring(split + 1), EDITOR.valueToTree(value));
        assertInvalid(EDITOR.writeValueAsString(changed), pointer + " must not accept " + value);
    }

    private void assertInvalid(String text, String reason) throws IOException {
        Path file = temporary.resolve("candidate.json");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> DynamicMixedExperimentSpecIO.read(file), reason);
    }

    private DynamicMixedExperimentSpec read(String text) throws IOException {
        Path file = temporary.resolve("candidate.json");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return DynamicMixedExperimentSpecIO.read(file);
    }

    private static ObjectNode tree() throws IOException {
        return (ObjectNode) EDITOR.readTree(DynamicMixedExperimentSpecIO.canonicalBytes(fixture()));
    }

    private static String canonicalText() throws IOException {
        return new String(DynamicMixedExperimentSpecIO.canonicalBytes(fixture()), StandardCharsets.UTF_8);
    }

    private record Property(String parent, String name) {
        String pointer() { return parent + "/" + name.replace("~", "~0").replace("/", "~1"); }
    }

    private static List<Property> allProperties(JsonNode node, String pointer) {
        var result = new ArrayList<Property>();
        if (node.isObject()) node.properties().forEach(entry -> {
            var property = new Property(pointer, entry.getKey());
            result.add(property);
            result.addAll(allProperties(entry.getValue(), property.pointer()));
        });
        else if (node.isArray()) for (int i = 0; i < node.size(); i++) result.addAll(allProperties(node.get(i), pointer + "/" + i));
        return result;
    }

    private static void assertObjectKeysSorted(JsonNode node) {
        if (node.isObject()) {
            List<String> names = node.properties().stream().map(Map.Entry::getKey).toList();
            assertEquals(names.stream().sorted().toList(), names);
        }
        node.forEach(DynamicMixedExperimentSpecIOTest::assertObjectKeysSorted);
    }

    private static JsonNode reverseObjectKeys(JsonNode node) {
        if (node.isObject()) {
            var entries = new ArrayList<>(node.properties());
            Collections.reverse(entries);
            var result = EDITOR.createObjectNode();
            entries.forEach(entry -> result.set(entry.getKey(), reverseObjectKeys(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = EDITOR.createArrayNode();
            node.forEach(value -> result.add(reverseObjectKeys(value)));
            return result;
        }
        return node;
    }

    private static RegisteredDefinition definition(String name) {
        return new RegisteredDefinition("toy-definition-v1", file(name + ".json"));
    }

    private static ExperimentSpec.FileRef file(String name) {
        return new ExperimentSpec.FileRef("synthetic-not-materialized/" + name, "a".repeat(64));
    }

    private static DynamicMixedExperimentSpec fixture() {
        var design = new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED,
                new DynamicMixedDesign.Protocol(0, 2, 4, 5, 4, 5), true, new DispatchCommitmentState.Limits(25, 25, 5));
        var inputs = new ExperimentSpec.Inputs(file("config.xml"), file("network.xml"), file("population.xml"),
                file("memory.xml"), file("attributes.xml"), file("schedule.xml"), file("transit-vehicles.xml"),
                file("schedule.xml"), file("transit-vehicles.xml"), file("road-vehicles.xml"), file("pool.csv"), file("orders.csv"));
        var freight = new ExperimentSpec.FreightDemand(1, 1, 0, 180, "freight_delivery", "cargo_");
        var policies = new ExperimentSpec.Policies(ExperimentSpec.Access.SHARED_PASSENGER_SERVICE, "", List.of("pt", "freight_pt"),
                0.25, 1, 1, 0, true, 0.6, 0, 6, 0, 0, 0, 0);
        var runtime = new RuntimeDeclaration(7, 7, EventMode.NATIVE_SIM_STEP_PARALLEL,
                EventWorkerSelection.DEFAULT_SINGLE_WORKER, 1, true, false, 2, 1, 0,
                QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime, QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime,
                200, 240, definition("resolved-config"));
        var control = new ControlRegistration("toy-only-policy", InformationModel.BASELINE_PRETRIP_NO_ADDED_INTRADAY_REROUTING,
                ResetMode.FRESH_ORIGINAL_BASELINE_EACH_ITERATION, BaselineTreatment.NO_PRE_SERVICE_REPAIR,
                OperatingBasis.SYNTHETIC_ASSUMPTIONS, new DecisionTiming(52, 10, 60, 1),
                definition("policy"), definition("constraints"), definition("forecast"), definition("protection"),
                definition("information"), definition("baseline-treatment"));
        var output = new OutputOptions(ScientificRunContext.DYNAMIC_OUTPUT_SCHEMA, ScientificRunContext.ExposureOutput.REPORTING_WINDOW,
                false, 0, 0, "toy-dispatch-evidence-v1");
        return new DynamicMixedExperimentSpec(SCHEMA, "toy-io-only", "toy-block", design,
                new ExperimentSpec.Seeds(3, 5, 7), inputs, freight, policies, runtime, control, output, "synthetic-io-only");
    }
}
