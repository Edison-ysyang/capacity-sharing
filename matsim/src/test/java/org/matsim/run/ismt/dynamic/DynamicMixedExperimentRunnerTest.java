package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.run.ismt.ExperimentConfiguration;
import org.matsim.run.ismt.ExperimentSpec;
import org.matsim.utils.objectattributes.ObjectAttributes;
import org.matsim.utils.objectattributes.ObjectAttributesXmlWriter;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedSpecFixtures.replace;

/** Entry/identity/file boundary checks only; no Controller.run, runtime impersonation or old results. */
class DynamicMixedExperimentRunnerTest {
    @TempDir Path temporary;

    @Test void onlyNewTCanEnterAndOtherArmsAreRejectedBeforeAnyInputOrBuildRead() throws IOException {
        var runner = new DynamicMixedExperimentRunner();
        var options = new DynamicMixedExperimentRunner.Options(temporary, Path.of("outputs"), Path.of("missing-build.json"));
        for (var arm : List.of(DynamicMixedDesign.Arm.CONTROL, DynamicMixedDesign.Arm.MIXED, DynamicMixedDesign.Arm.DEDICATED)) {
            var spec = withEvidenceSchema(DynamicMixedSpecFixtures.spec(arm));
            var error = assertThrows(IllegalArgumentException.class, () -> runner.runDevelopment(spec, options));
            assertTrue(error.getMessage().contains("DYNAMIC_MIXED"));
        }
        assertFalse(Files.exists(temporary.resolve("outputs")));
    }

    @Test void offAndZeroRemainNewTIdentityAndDoNotBypassTheInputBoundary() {
        var spec = supported();
        var off = replace(spec, "design", new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED,
                spec.design().protocol(), false, spec.design().limits()));
        var zero = replace(spec, "design", new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED,
                spec.design().protocol(), true, new DispatchCommitmentState.Limits(0, 0, 5)));
        assertDoesNotThrow(() -> DynamicMixedExperimentRunner.requireSupportedSpec(off));
        assertDoesNotThrow(() -> DynamicMixedExperimentRunner.requireSupportedSpec(zero));
        assertFalse(off.design().phaseAt(2).controlEligible());
        assertFalse(zero.design().phaseAt(2).controlEligible());
    }

    @Test void unknownDispatchContractIsNotSilentlyWrittenAsTheInstalledVersion() {
        var spec = DynamicMixedSpecFixtures.spec();
        var error = assertThrows(IllegalArgumentException.class, () -> DynamicMixedExperimentRunner.requireSupportedSpec(spec));
        assertTrue(error.getMessage().contains("dispatch evidence schema"));
    }

    @Test void developmentIdentityIncludesBothVersionedNamespaceAndFullSourceAndSpecHashes() throws IOException {
        var spec = supported();
        String source = "a".repeat(64), hash = DynamicMixedExperimentSpecIO.sha256(spec);
        Path actual = DynamicMixedExperimentRunner.developmentRunRoot(temporary, Path.of("outputs"), spec, source, hash);
        assertEquals(temporary.toRealPath().resolve("outputs/development/toy-v2/" + source + "/B1/dynamic_mixed/" + hash), actual);
        assertFalse(Files.exists(temporary.resolve("outputs")), "Identity construction is read-only");
        assertNotEquals(actual, DynamicMixedExperimentRunner.developmentRunRoot(temporary, Path.of("outputs"), spec,
                "b".repeat(64), hash));
        var renamed = replace(spec, "experimentId", "another-v2-namespace");
        assertNotEquals(actual, DynamicMixedExperimentRunner.developmentRunRoot(temporary, Path.of("outputs"), renamed,
                source, DynamicMixedExperimentSpecIO.sha256(renamed)));
    }

    @Test void malformedDigestCannotBecomeAnOutputPath() {
        for (String bad : List.of("x", "A".repeat(64), "../escape", "a".repeat(63))) {
            assertThrows(IllegalArgumentException.class, () -> DynamicMixedExperimentRunner.developmentRunRoot(
                    temporary, Path.of("outputs"), supported(), bad, "b".repeat(64)));
            assertThrows(IllegalArgumentException.class, () -> DynamicMixedExperimentRunner.developmentRunRoot(
                    temporary, Path.of("outputs"), supported(), "a".repeat(64), bad));
        }
    }

    @Test void workspaceRootOutsideAndParentTraversalAreRejectedWithoutCreatingPaths() {
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.containedOutput(temporary, temporary));
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.containedOutput(temporary, temporary.getParent().resolve("outside")));
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.containedOutput(temporary, Path.of("a/../b")));
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.containedOutput(temporary, Path.of(".")));
    }

    @Test void existingOutputAncestorsMustBeRealDirectoriesAndNotSymlinkAliases() throws IOException {
        Files.writeString(temporary.resolve("file"), "not a directory");
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.containedOutput(temporary, Path.of("file/output")));
        Path real = Files.createDirectory(temporary.resolve("real"));
        Files.createSymbolicLink(temporary.resolve("alias"), real);
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.containedOutput(temporary, Path.of("alias/output")));
        Files.createSymbolicLink(temporary.resolve("dangling"), temporary.resolve("does-not-exist"));
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.containedOutput(temporary, Path.of("dangling/output")));
        assertEquals(real.resolve("output"), DynamicMixedExperimentRunner.containedOutput(temporary, Path.of("real/output")));
    }

    @Test void missingBuildCannotBeDowngradedToUnverifiedDevelopmentExecution() {
        var runner = new DynamicMixedExperimentRunner();
        assertThrows(IOException.class, () -> runner.runDevelopment(supported(),
                new DynamicMixedExperimentRunner.Options(temporary, Path.of("outputs"), Path.of("missing.json"))));
        assertFalse(Files.exists(temporary.resolve("outputs")));
    }

    @Test void malformedBuildFailsBeforeCreatingRunOrLoadingInputs() throws IOException {
        Files.writeString(temporary.resolve("build.json"), "{}");
        assertThrows(IOException.class, () -> new DynamicMixedExperimentRunner().runDevelopment(supported(),
                new DynamicMixedExperimentRunner.Options(temporary, Path.of("outputs"), Path.of("build.json"))));
        assertFalse(Files.exists(temporary.resolve("outputs")));
    }

    @Test void assemblyRequiresCallerOwnedNewRunDirectoryAndNeverCreatesItImplicitly() {
        Path output = temporary.resolve("not-created");
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.prepareSimulation(supported(), temporary, output));
        assertFalse(Files.exists(output));
    }

    @Test void assemblyDoesNotOverwriteAnExistingDispatchDirectory() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("run"));
        Path evidence = Files.createDirectory(root.resolve("dispatch"));
        Path sentinel = evidence.resolve("preserve.txt"); Files.writeString(sentinel, "existing evidence");
        var failure = assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.prepareSimulation(supported(), temporary, root));
        assertTrue(failure.getMessage().contains("Dispatch evidence already exists"));
        assertEquals("existing evidence", Files.readString(sentinel));
        assertFalse(Files.exists(root.resolve("matsim")));
    }

    @Test void assemblyRejectsIncompleteInputBeforeCreatingEvidenceOrScenarioOutputs() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("run"));
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.prepareSimulation(supported(), temporary, root));
        try (var contents = Files.list(root)) { assertEquals(0, contents.count()); }
    }

    @Test void realFilesLoadIntoTheV2ControllerAssemblyWithoutRunningOrInventingACompletedDay() throws Exception {
        var spec = scenarioFiles();
        var before = DynamicMixedInputVerifier.verify(temporary, spec);
        Path root = Files.createDirectory(temporary.resolve("prepared-run"));
        try (var prepared = DynamicMixedExperimentRunner.prepareSimulation(spec, temporary, root)) {
            assertEquals(1, prepared.conservation().orders());
            assertEquals(1, prepared.conservation().prebuiltUnits());
            assertEquals(19, prepared.configuration().inputVerification().files().size());
            assertEquals(7, prepared.controler().getScenario().getConfig().qsim().getNumberOfThreads());
            assertEquals(7, prepared.controler().getScenario().getConfig().global().getNumberOfThreads());
            assertNull(prepared.controler().getScenario().getConfig().eventsManager().getNumberOfThreads());
            assertEquals(2, prepared.controler().getScenario().getTransitSchedule().getTransitLines().values().iterator().next().getRoutes().size());
            assertEquals(1, prepared.controler().getScenario().getPopulation().getPersons().size());
            assertEquals(-1, prepared.finalIterationReached()); assertTrue(prepared.completedIterations().isEmpty());
            assertThrows(IllegalStateException.class, prepared::requireAllIterationsCompleted);
            assertThrows(UnsupportedOperationException.class, () -> prepared.completedIterations().add(0));
            assertFalse(prepared.configuration().registeredResolvedDefinitionSemanticallyVerified());
            assertTrue(Files.isDirectory(root.resolve("dispatch")));
            assertFalse(Files.exists(root.resolve("matsim")), "Assembling must not start the Controller");
        }
        assertEquals(before.files(), DynamicMixedInputVerifier.verify(temporary, spec).files());
        try (var evidence = Files.list(root.resolve("dispatch"))) { assertEquals(0, evidence.count(), "No fake day-start event"); }
    }

    @Test void rejectingConfigurationGatePrecedesActualInvalidPopulationParsingAndCreatesNoEvidence() throws Exception {
        var spec = withInvalidButHashVerifiedPopulation(scenarioFiles());
        Path root = Files.createDirectory(temporary.resolve("gate-rejected"));
        var marker = new IOException("deliberate complete-configuration rejection");
        var calls = new AtomicInteger();
        var failure = assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.prepareSimulationWithConfigurationGate(
                spec, temporary, root, (configuration, actualRoot) -> {
                    calls.incrementAndGet();
                    assertEquals(root.toRealPath(), actualRoot);
                    assertEquals(19, configuration.inputVerification().files().size());
                    assertEquals(spec.inputs().population().resolve(temporary).toString(), configuration.config().plans().getInputFile());
                    assertEquals(configuration.resolvedSnapshot().fields(),
                            ResolvedNonDispatchConfiguration.snapshot(configuration.config()).fields());
                    assertFalse(Files.exists(actualRoot.resolve("dispatch")));
                    assertFalse(Files.exists(actualRoot.resolve("matsim")));
                    throw marker;
                }));
        assertSame(marker, failure, "The checked gate exception must survive without wrapping or population parse replacing it");
        assertEquals(1, calls.get()); assertEmptyDirectory(root);
        assertEquals(19, DynamicMixedInputVerifier.verify(temporary, spec).files().size(), "Invalid XML still has genuinely matching hashes");
    }

    @Test void acceptingGateIsFollowedByRealPopulationParserFailureNotFakeAssemblySuccess() throws Exception {
        var spec = withInvalidButHashVerifiedPopulation(scenarioFiles());
        Path root = Files.createDirectory(temporary.resolve("gate-then-real-parser"));
        var calls = new AtomicInteger();
        var failure = assertThrows(UncheckedIOException.class, () -> DynamicMixedExperimentRunner.prepareSimulationWithConfigurationGate(
                spec, temporary, root, (configuration, actualRoot) -> {
                    calls.incrementAndGet(); assertEquals(19, configuration.inputVerification().files().size());
                    assertFalse(Files.exists(actualRoot.resolve("dispatch")));
                }));
        assertEquals(1, calls.get());
        assertTrue(hasCause(failure, org.xml.sax.SAXParseException.class), "The actual invalid XML must reach the native parser after the gate");
        assertEmptyDirectory(root);
        Path oldPath = Files.createDirectory(temporary.resolve("original-ungated-parser"));
        var original = assertThrows(UncheckedIOException.class,
                () -> DynamicMixedExperimentRunner.prepareSimulation(spec, temporary, oldPath));
        assertTrue(hasCause(original, org.xml.sax.SAXParseException.class)); assertEmptyDirectory(oldPath);
    }

    @Test void realResolvedDefinitionGateRunsBeforeScenarioAndReturnsTheSameRawConfigurationToAssembly() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("gate-accepted"));
        var spec = withRegisteredResolvedDefinition(scenarioFiles(), root);
        var calls = new AtomicInteger();
        var captured = new AtomicReference<DynamicMixedConfiguration.PreparedConfiguration>();
        var report = new AtomicReference<DynamicMixedResolvedDefinition.Report>();
        var before = DynamicMixedInputVerifier.verify(temporary, spec);
        var bound = DynamicMixedResolvedDefinition.read(temporary, spec);
        try (var prepared = DynamicMixedExperimentRunner.prepareSimulationWithConfigurationGate(spec, temporary, root,
                (configuration, actualRoot) -> {
                    calls.incrementAndGet(); captured.set(configuration);
                    assertFalse(Files.exists(actualRoot.resolve("dispatch")));
                    assertFalse(Files.exists(actualRoot.resolve("matsim")));
                    report.set(bound.verify(configuration.config(), freshConfiguration(spec, actualRoot), actualRoot));
                    assertEquals(configuration.resolvedSnapshot().fields(), report.get().rawSnapshot().fields());
                })) {
            assertEquals(1, calls.get()); assertSame(captured.get(), prepared.configuration());
            assertSame(captured.get().config(), prepared.controler().getScenario().getConfig());
            assertEquals(1, prepared.controler().getScenario().getPopulation().getPersons().size());
            assertEquals(1, prepared.conservation().orders());
            assertTrue(report.get().registeredResolvedDefinitionSemanticallyVerified());
            assertFalse(report.get().formalRunAuthorized()); assertFalse(report.get().actualControllerLifecycleVerified());
            assertFalse(prepared.configuration().registeredResolvedDefinitionSemanticallyVerified(),
                    "The optional hook does not silently relabel the original adapter or development receipt");
            assertTrue(Files.isDirectory(root.resolve("dispatch")));
            assertFalse(Files.exists(root.resolve("matsim"))); assertTrue(prepared.completedIterations().isEmpty());
            assertEquals(-1, prepared.finalIterationReached());
        }
        assertEmptyDirectory(root.resolve("dispatch"));
        assertEquals(before.files(), DynamicMixedInputVerifier.verify(temporary, spec).files());
    }

    @Test void everyExposedGateMutationIncludingContextAndOutputFailsBeforeInvalidPopulationIsRead() throws Exception {
        var spec = withInvalidButHashVerifiedPopulation(scenarioFiles());
        // Native Config.setContext(null) throws inside its setter. Use a valid but different URL
        // so this test reaches the runner's full-tree guard rather than a native argument failure.
        var changedContext = temporary.resolve("changed-context.xml").toFile().toURI().toURL();
        List<Consumer<Config>> mutations = List.of(config -> config.qsim().setNumberOfThreads(1),
                config -> config.setContext(changedContext), config -> config.controller().setOutputDirectory("changed-output"),
                config -> config.scoring().setBrainExpBeta(config.scoring().getBrainExpBeta() + 1),
                config -> config.plans().setInputFile("another-unregistered-population.xml"));
        int index = 0;
        for (var mutation : mutations) {
            Path root = Files.createDirectory(temporary.resolve("mutation-" + index++));
            var completedMutations = new AtomicInteger();
            var failure = assertThrows(IllegalStateException.class, () -> DynamicMixedExperimentRunner.prepareSimulationWithConfigurationGate(
                    spec, temporary, root, (configuration, actualRoot) -> {
                        mutation.accept(configuration.config());
                        completedMutations.incrementAndGet();
                    }));
            assertEquals(1, completedMutations.get(), "Mutation must return normally before the full-tree guard rejects it");
            assertTrue(failure.getMessage().contains("changed the complete raw configuration"));
            assertEmptyDirectory(root);
        }
    }

    @Test void configurationAndInputFailurePrecedeGateAndNullGateIsNotAnImplicitBypass() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("unprepared-gate"));
        var calls = new AtomicInteger();
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.prepareSimulationWithConfigurationGate(
                supported(), temporary, root, (configuration, actualRoot) -> calls.incrementAndGet()));
        assertEquals(0, calls.get()); assertEmptyDirectory(root);
        assertThrows(NullPointerException.class, () -> DynamicMixedExperimentRunner.prepareSimulationWithConfigurationGate(
                supported(), temporary, root, null));
        assertEmptyDirectory(root);
    }

    @Test void uncheckedGateFailurePropagatesUnchangedWithoutCreatingScenarioOutputs() throws Exception {
        var spec = withInvalidButHashVerifiedPopulation(scenarioFiles());
        Path root = Files.createDirectory(temporary.resolve("runtime-gate-rejected"));
        var marker = new IllegalStateException("deliberate semantic gate failure");
        var failure = assertThrows(IllegalStateException.class, () -> DynamicMixedExperimentRunner.prepareSimulationWithConfigurationGate(
                spec, temporary, root, (configuration, actualRoot) -> { throw marker; }));
        assertSame(marker, failure); assertEmptyDirectory(root);
    }

    @Test void inventoryKeepsExactBytesAndExcludesOnlyItsOwnReceiptPaths() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("run"));
        Path nested = Files.createDirectories(root.resolve("matsim"));
        Files.writeString(root.resolve("experiment_spec.json"), "spec");
        Path data = nested.resolve("result.txt"); Files.writeString(data, "result\n");
        Files.writeString(root.resolve("run_receipt.json"), "mutable receipt");
        Files.writeString(root.resolve(".run_receipt.tmp"), "temporary receipt");
        var inventory = DynamicMixedExperimentRunner.inventory(root);
        assertEquals(List.of("experiment_spec.json", "matsim/result.txt"), List.copyOf(inventory.keySet()));
        assertEquals(Map.of("bytes", 7L, "sha256", ExperimentConfiguration.sha256(data)), inventory.get("matsim/result.txt"));
        assertThrows(UnsupportedOperationException.class, () -> inventory.clear());
        assertThrows(UnsupportedOperationException.class, () -> inventory.get("matsim/result.txt").clear());
        Files.writeString(data, "changed");
        assertNotEquals(inventory, DynamicMixedExperimentRunner.inventory(root));
    }

    @Test void inventoryRefusesEvenContainedSymlinksInsteadOfClaimingAClosedPayloadInventory() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("run"));
        Path actual = root.resolve("actual.txt"); Files.writeString(actual, "kept");
        Files.createSymbolicLink(root.resolve("alias.txt"), actual);
        assertThrows(IOException.class, () -> DynamicMixedExperimentRunner.inventory(root));
        assertEquals("kept", Files.readString(actual));
    }

    @Test void cliHasNoFormalMatrixResumeOrImplicitDevelopmentMode() {
        for (String command : List.of("formal", "run", "matrix", "resume", ""))
            assertThrows(IllegalArgumentException.class, () -> DynamicMixedExperimentRunner.main(
                    new String[]{command, "missing", "missing", "missing", "missing"}));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedExperimentRunner.main(new String[]{}));
        assertThrows(IllegalArgumentException.class, () -> DynamicMixedExperimentRunner.main(
                new String[]{"development", "missing", "missing", "missing", "missing", "--resume"}));
    }

    @Test void completedReceiptTypeDoesNotImplyFormalOrScientificValidation() {
        var result = new DynamicMixedExperimentRunner.RunResult("toy", temporary, "COMPLETED_UNVALIDATED", 5, 6, false, false);
        assertFalse(result.formalRun()); assertFalse(result.scientificOutputsValidated());
        assertEquals("COMPLETED_UNVALIDATED", result.status());
    }

    private static DynamicMixedExperimentSpec supported() { return withEvidenceSchema(DynamicMixedSpecFixtures.spec()); }
    private static DynamicMixedExperimentSpec withEvidenceSchema(DynamicMixedExperimentSpec spec) {
        return replace(spec, "output", replace(spec.output(), "dispatchEvidenceSchemaVersion", DynamicMixedDispatchEvidence.SCHEMA));
    }

    /** Complete tiny serialized inputs; no actual native preparation, routing or QSim execution. */
    DynamicMixedExperimentSpec scenarioFiles() throws Exception {
        var scenario = DynamicMixedControlDefinitionsTest.scenario();
        var bundle = DynamicMixedControlDefinitionsTest.toyBundle();
        var config = scenario.getConfig(); var runtime = bundle.spec().runtime();
        config.global().setNumberOfThreads(runtime.globalThreads()); config.qsim().setNumberOfThreads(runtime.qsimThreads());
        config.qsim().setStartTime(runtime.qsimStartTimeSeconds()); config.qsim().setTimeStepSize(runtime.qsimTimeStepSeconds());
        config.qsim().setSimStarttimeInterpretation(runtime.startTimeInterpretation());
        config.qsim().setSimEndtimeInterpretation(runtime.endTimeInterpretation());
        config.eventsManager().setNumberOfThreads(null); config.eventsManager().setSynchronizeOnSimSteps(true);
        config.eventsManager().setOneThreadPerHandler(false);
        config.plans().setInsistingOnUsingDeprecatedPersonAttributeFile(true);
        config.replanning().clearStrategySettings();
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("KeepLastSelected").setWeight(1));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings().setStrategyName("KeepLastSelected").setWeight(1).setSubpopulation("freight"));
        var stops = scenario.getTransitSchedule().getFacilities().values().stream()
                .sorted(java.util.Comparator.comparing(value -> value.getId().toString())).toList();
        var network = scenario.getNetwork(); var nf = network.getFactory();
        int index = 0;
        for (var stop : stops) if (!network.getLinks().containsKey(stop.getLinkId())) {
            var a = nf.createNode(Id.createNodeId("assembly-" + index + "-a"), new Coord(index * 100, 0));
            var b = nf.createNode(Id.createNodeId("assembly-" + index + "-b"), new Coord(index * 100 + 10, 0));
            network.addNode(a); network.addNode(b);
            var link = nf.createLink(stop.getLinkId(), a, b); link.setLength(10); link.setFreespeed(10);
            link.setCapacity(100); link.setNumberOfLanes(1); network.addLink(link); index++;
        }
        for (var line : scenario.getTransitSchedule().getTransitLines().values()) for (var route : line.getRoutes().values())
            if (route.getRoute() == null) route.setRoute(RouteUtils.createLinkNetworkRouteImpl(
                    route.getStops().getFirst().getStopFacility().getLinkId(), List.of(), route.getStops().getLast().getStopFacility().getLinkId()));
        var population = scenario.getPopulation(); var pf = population.getFactory();
        var person = pf.createPerson(Id.createPersonId("order-assembly"));
        person.getAttributes().putAttribute("subpopulation", "freight");
        var plan = pf.createPlan();
        var origin = pf.createActivityFromCoord("origin", stops.getFirst().getCoord()); origin.setLinkId(stops.getFirst().getLinkId()); origin.setEndTime(50);
        plan.addActivity(origin); plan.addLeg(pf.createLeg("freight_pt"));
        var destination = pf.createActivityFromCoord(bundle.spec().freightDemand().destinationActivityPrefix() + "test", stops.getLast().getCoord());
        destination.setLinkId(stops.getLast().getLinkId()); plan.addActivity(destination); person.addPlan(plan); population.addPerson(person);
        new NetworkWriter(network).write(temporary.resolve("network.xml").toString());
        new PopulationWriter(population).write(temporary.resolve("population.xml").toString());
        new ObjectAttributesXmlWriter(new ObjectAttributes()).writeFile(temporary.resolve("attributes.xml").toString());
        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(temporary.resolve("schedule.xml").toString());
        scenario.getTransitVehicles().getVehicleTypes().values().forEach(type -> type.setNetworkMode("pt"));
        new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(temporary.resolve("transit-vehicles.xml").toString());
        var roadVehicles = VehicleUtils.createVehiclesContainer();
        var roadType = VehicleUtils.getFactory().createVehicleType(Id.create("assembly-car", VehicleType.class));
        roadType.setNetworkMode("car"); roadType.getCapacity().setSeats(4).setStandingRoom(0);
        roadType.setLength(5); roadType.setWidth(2); roadType.setMaximumVelocity(30); roadType.setPcuEquivalents(1);
        roadVehicles.addVehicleType(roadType); // vehicleDefinitions_v2 requires at least one type; vehicles themselves are optional.
        new MatsimVehicleWriter(roadVehicles).writeFile(temporary.resolve("road-vehicles.xml").toString());
        Files.writeString(temporary.resolve("orders.csv"), "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
                + "order-assembly,00:00:50,00:03:00,0.1,1,standard,1,1\n");
        Files.writeString(temporary.resolve("resolved-definition.json"), "{\"unverified\":true}");
        new ConfigWriter(config).write(temporary.resolve("config.xml").toString());
        var inputs = new ExperimentSpec.Inputs(ref("config.xml"), ref("network.xml"), ref("population.xml"), ref("population.xml"),
                ref("attributes.xml"), ref("schedule.xml"), ref("transit-vehicles.xml"), ref("schedule.xml"), ref("transit-vehicles.xml"),
                ref("road-vehicles.xml"), ref("orders.csv"), ref("orders.csv"));
        var spec = withEvidenceSchema(replace(bundle.spec(), "inputs", inputs));
        spec = replace(spec, "runtime", replace(spec.runtime(), "resolvedConfiguration",
                new DynamicMixedExperimentSpec.RegisteredDefinition("toy-not-semantically-verified", ref("resolved-definition.json"))));
        bundle = new DynamicMixedControlDefinitions.Bundle(spec, bundle.policy(),
                replace(bundle.constraints(), "baselineScheduleSha256", inputs.baselineSchedule().sha256()), bundle.forecast(),
                bundle.passengerProtection(), bundle.information(),
                replace(bundle.baselineTreatment(), "originalScheduleSha256", inputs.baselineSchedule().sha256()));
        return DynamicMixedControlDefinitionsTest.writeDefinitions(temporary, bundle);
    }

    private ExperimentSpec.FileRef ref(String name) throws IOException {
        return new ExperimentSpec.FileRef(name, ExperimentConfiguration.sha256(temporary.resolve(name)));
    }

    private DynamicMixedExperimentSpec withInvalidButHashVerifiedPopulation(DynamicMixedExperimentSpec spec) throws IOException {
        Files.writeString(temporary.resolve("invalid-population.xml"), "not XML and deliberately not a population");
        return replace(spec, "inputs", replace(spec.inputs(), "population", ref("invalid-population.xml")));
    }

    private DynamicMixedExperimentSpec withRegisteredResolvedDefinition(DynamicMixedExperimentSpec original, Path runRoot) throws IOException {
        var draft = replace(original, "runtime", replace(original.runtime(), "resolvedConfiguration",
                new DynamicMixedExperimentSpec.RegisteredDefinition(DynamicMixedResolvedDefinition.SCHEMA,
                        original.runtime().resolvedConfiguration().file())));
        var audit = ControllerInitializationConfigurationAudit.compareFresh(freshConfiguration(draft, runRoot), freshConfiguration(draft, runRoot));
        var definition = new DynamicMixedResolvedDefinition.Definition(DynamicMixedResolvedDefinition.SCHEMA,
                draft.experimentId(), draft.blockId(), DynamicMixedResolvedDefinition.specificationBindingSha256(draft),
                DynamicMixedResolvedDefinition.nonSelfInputFiles(draft),
                new DynamicMixedResolvedDefinition.BindingRules(DynamicMixedResolvedDefinition.ContextRule.MATSIM_CONFIG_FILE_URL,
                        DynamicMixedResolvedDefinition.OutputRule.RUN_ROOT_MATSIM),
                resolvedTemplate(audit.current().raw().fields()), resolvedTemplate(audit.current().initialized().fields()));
        DynamicMixedResolvedDefinition.writeNew(temporary.resolve("gate-resolved-definition.json"), definition);
        return replace(draft, "runtime", replace(draft.runtime(), "resolvedConfiguration",
                new DynamicMixedExperimentSpec.RegisteredDefinition(DynamicMixedResolvedDefinition.SCHEMA, ref("gate-resolved-definition.json"))));
    }

    private Supplier<Config> freshConfiguration(DynamicMixedExperimentSpec spec, Path root) {
        return () -> {
            try { return ExperimentConfiguration.create(DynamicMixedConfiguration.commonRequest(spec), temporary.toRealPath(), root.resolve("matsim")); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        };
    }

    private static DynamicMixedResolvedDefinition.TreeTemplate resolvedTemplate(Map<String, String> values) {
        var fields = new TreeMap<>(values);
        fields.put(DynamicMixedResolvedDefinition.CONTEXT_PATH, DynamicMixedResolvedDefinition.CONTEXT_TOKEN);
        fields.put(DynamicMixedResolvedDefinition.OUTPUT_PATH, DynamicMixedResolvedDefinition.OUTPUT_TOKEN);
        return new DynamicMixedResolvedDefinition.TreeTemplate(ResolvedNonDispatchConfiguration.SCHEMA, fields);
    }

    private static void assertEmptyDirectory(Path path) throws IOException {
        try (var contents = Files.list(path)) { assertEquals(0, contents.count(), "No Scenario, dispatch or Controller artifact may be created"); }
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (type.isInstance(cause)) return true;
        return false;
    }
}
