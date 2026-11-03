package org.matsim.run.ismt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.HasPersonId;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ReplanningEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ReplanningListener;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.qsim.pt.PersonEntersPtVehicleEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.replanning.ReplanningUtils;
import org.matsim.core.replanning.StrategyManager;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Small real-QSim contract check through ExperimentRunner's shared preparation seam.
 * Skipping only the source/build guard makes this explicitly NONFORMAL; its synthetic
 * effect sizes are not evidence about the Hangzhou case or a transport policy.
 */
@Tag("ismt-integration")
class IsmtFourArmIntegrationTest {
    @TempDir Path temporaryDirectory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CONTEXT = Set.of("experiment_id", "block_id", "arm");
    private static final List<String> PASSENGER_SUMMARY = List.of("iteration", "passenger_count",
            "scored_passenger_count", "selected_passenger_trips", "pt_share", "selected_plan_passenger_score",
            "completed_pt_trips", "mean_pt_door_to_door_minutes", "mean_pt_wait_minutes", "raw_physical_exposure",
            "raw_freight_copresence_exposure", "physical_penalty", "co_presence_penalty", "applied_physical_penalty",
            "applied_co_presence_penalty", "passenger_denied_boardings", "passenger_boardings", "passenger_stuck_events",
            "unfinished_passenger_rides", "passengers_with_raw_copresence", "raw_copresence_incidence");

    @Test
    void fourArmsPreserveWarmupAndIsolationAndRepeatScientificArtifacts() throws Exception {
        Paths paths = paths();
        writeNewJson(paths.root().resolve("integration_receipt.json"), Map.of(
                "schemaVersion", "ismt-four-arm-integration-v1", "formalRun", false,
                "purpose", "Synthetic real-QSim integration fixture; not a policy result or formal run receipt",
                "executionPath", "ExperimentRunner.prepareSimulation", "workspace", paths.workspace().toString()));
        var fixture = IsmtFourArmFixture.create(paths.workspace(), paths.root());
        Map<ExperimentSpec.Arm, Result> runs = new EnumMap<>(ExperimentSpec.Arm.class);
        for (var arm : ExperimentSpec.Arm.values())
            runs.put(arm, run(fixture, fixture.specs().get(arm), arm.name()));

        Result control = runs.get(ExperimentSpec.Arm.CONTROL);
        Result mixed = runs.get(ExperimentSpec.Arm.MIXED);
        Result timetable = runs.get(ExperimentSpec.Arm.TIMETABLE_ONLY);
        Result dedicated = runs.get(ExperimentSpec.Arm.DEDICATED);
        assertEquals(timetable.spec().inputs().activatedSchedule(), dedicated.spec().inputs().activatedSchedule());
        assertEquals(timetable.spec().inputs().activatedTransitVehicles(), dedicated.spec().inputs().activatedTransitVehicles());

        for (Result result : runs.values()) {
            assertNativeInnovation(result);
            assertEquals(control.validation().preActivationFingerprint(), result.validation().preActivationFingerprint(),
                    "Passenger native trajectories must match in ALL four arms, iterations 0..19: " + result.spec().arm());
            assertEquals(selectRows(control.rows(PassengerPlanFingerprint.FILE_NAME), 0, 19),
                    selectRows(result.rows(PassengerPlanFingerprint.FILE_NAME), 0, 19),
                    "Warm-up passenger plan memory must match: " + result.spec().arm());
            assertEquals(control.innovation().rows.subMap(0, true, 19, true),
                    result.innovation().rows.subMap(0, true, 19, true),
                    "Actual native warm-up replanning must match in every arm");
            for (int iteration = 0; iteration <= 23; iteration++) {
                var summary = result.summary(iteration);
                assertEquals("11", summary.get("passenger_count"));
                assertEquals("11", summary.get("completed_pt_trips"), result.label() + " iteration " + iteration);
                assertEquals("0", summary.get("passenger_stuck_events"));
                assertEquals("0", summary.get("freight_stuck_events"));
                assertTrue(Double.parseDouble(summary.get("max_load_ratio")) <= 1.0);
                if (iteration < 20) {
                    assertEquals(0, result.audit().freightEvents.getOrDefault(iteration, 0), "Dormant freight cannot emit person events");
                    assertEquals("0", summary.get("freight_boardings"));
                }
                if (result.spec().arm() != ExperimentSpec.Arm.MIXED || iteration < 20)
                    assertEquals(0.0, Double.parseDouble(summary.get("raw_freight_copresence_exposure")), 0.0);
            }
        }

        assertEquals(withoutContext(timetable.rows(PassengerScientificFingerprint.FILE_NAME)),
                withoutContext(dedicated.rows(PassengerScientificFingerprint.FILE_NAME)), "T/D complete passenger event equality");
        assertEquals(withoutContext(timetable.rows(PassengerPlanFingerprint.FILE_NAME)),
                withoutContext(dedicated.rows(PassengerPlanFingerprint.FILE_NAME)), "T/D complete passenger plan-memory equality");
        assertEquals(project(timetable.rows("iteration_summary.csv"), PASSENGER_SUMMARY),
                project(dedicated.rows("iteration_summary.csv"), PASSENGER_SUMMARY), "T/D passenger summary equality");
        assertEquals(withoutContext(timetable.rows("passenger_exposure.csv.gz")),
                withoutContext(dedicated.rows("passenger_exposure.csv.gz")), "T/D individual exposure equality");
        assertEquals(timetable.innovation().passengerProjection(), dedicated.innovation().passengerProjection(),
                "T/D actual passenger strategy weights and new-plan creation must agree");

        for (int iteration = 20; iteration <= 23; iteration++) {
            assertTrue(Double.parseDouble(mixed.summary(iteration).get("raw_freight_copresence_exposure")) > 0,
                    "Mixed fixture must actually share, not merely configure shared access");
            assertEquals("1", mixed.summary(iteration).get("completed_orders"));
            assertEquals("1", dedicated.summary(iteration).get("completed_orders"));
            assertEquals("3", mixed.summary(iteration).get("freight_boardings"));
            assertEquals("3", dedicated.summary(iteration).get("freight_boardings"));
            assertEquals("0", timetable.summary(iteration).get("freight_boardings"));
            assertPriorityAndCapacity(mixed, iteration);
            for (Boarding boarding : dedicated.audit().boardings.get(iteration)) {
                if (IsmtFourArmFixture.FREIGHT_IDS.contains(boarding.person()))
                    assertEquals(IsmtFourArmFixture.DEDICATED_LINE, boarding.line(), "Freight cannot enter passenger service in D");
                else assertEquals(IsmtFourArmFixture.PASSENGER_LINE, boarding.line(), "Passengers cannot enter dedicated service");
            }
            assertTrue(timetable.audit().boardings.get(iteration).stream()
                    .allMatch(b -> IsmtFourArmFixture.PASSENGER_LINE.equals(b.line())), "T added service must remain empty");
        }
        assertEquals(11, mixed.rows("passenger_exposure.csv.gz").size(), "11 passengers in report iteration 23");

        Result repeated = run(fixture, dedicated.spec(), "DEDICATED_repeat");
        assertNativeInnovation(repeated);
        for (String file : List.of("iteration_summary.csv", "order_ledger.csv", "denial_ledger.csv",
                "passenger_exposure.csv.gz", PassengerScientificFingerprint.FILE_NAME, PassengerPlanFingerprint.FILE_NAME))
            assertEquals(dedicated.rows(file), repeated.rows(file), "Same spec rerun must reproduce scientific rows: " + file);
        assertEquals(dedicated.audit().boardings, repeated.audit().boardings);
        assertEquals(dedicated.innovation().rows, repeated.innovation().rows,
                "Same spec must reproduce actual native weights and per-passenger plan-creation counts");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", "ismt-four-arm-integration-v1");
        receipt.put("formalRun", false);
        receipt.put("status", "VALIDATED");
        receipt.put("validatedArms", runs.keySet());
        receipt.put("repeatSpecSha256", ExperimentSpecIO.sha256(dedicated.spec()));
        receipt.put("syntheticInnovationProtocol", Map.of("offIteration", 0, "reopenIteration", 20,
                "finalOffIteration", 23, "reportFirstIteration", 23, "reportLastIteration", 23));
        receipt.put("checks", List.of("shared-runner-path", "iterations-0-through-23", "single-activation-20",
                "actual-manager-registered-strategy-identity", "actual-innovation-off-1-through-19-and-23",
                "actual-innovation-weights-enabled-20-through-22", "positive-native-new-plan-creation-after-20",
                "all-arm-preactivation-identity", "native-capacity", "stable-passenger-priority",
                "mixed-positive-copresence", "dedicated-bidirectional-isolation", "T-D-passenger-identity", "same-spec-repeat"));
        writeNewJson(paths.root().resolve("integration_validation.json"), receipt);
    }

    @Test
    void roadRoutingRandomnessPreservesFourArmWarmupAndTimetableDedicatedEquality() throws Exception {
        Path workspace = temporaryDirectory.toRealPath();
        Path root = Files.createDirectory(workspace.resolve("road-routing-rng"));
        var fixture = IsmtFourArmFixture.createRoadCompetition(workspace, root);
        ExperimentSpec reference = fixture.specs().get(ExperimentSpec.Arm.CONTROL);

        var configured = ConfigUtils.loadConfig(reference.inputs().matsimConfig().resolve(workspace).toString());
        assertEquals(IsmtFourArmFixture.ROAD_ROUTING_RANDOMNESS,
                configured.routing().getRoutingRandomness(), 0.0);
        assertEquals(IsmtFourArmFixture.ROAD_THREADS, configured.global().getNumberOfThreads());
        assertEquals(IsmtFourArmFixture.ROAD_THREADS, configured.qsim().getNumberOfThreads());
        assertEquals(Set.of(TransportMode.car, TransportMode.ride),
                Set.copyOf(configured.routing().getNetworkModes()));
        assertEquals(Set.of(TransportMode.car, TransportMode.ride),
                Set.copyOf(configured.qsim().getMainModes()));
        for (String mode : List.of(TransportMode.car, TransportMode.ride))
            assertEquals(-0.001, configured.scoring().getModes().get(mode).getMonetaryDistanceRate(), 0.0,
                    "Routing randomness needs a nonzero distance-money tradeoff for " + mode);

        var network = NetworkUtils.readNetwork(reference.inputs().network().resolve(workspace).toString());
        assertTrue(network.getLinks().keySet().stream().map(Id::toString).collect(java.util.stream.Collectors.toSet())
                .containsAll(IsmtFourArmFixture.ROAD_COMPETING_LINKS),
                "Road fixture must contain both the short/slow and long/fast alternatives");
        var short1 = network.getLinks().get(Id.createLinkId("road_short_1"));
        var short2 = network.getLinks().get(Id.createLinkId("road_short_2"));
        var fast1 = network.getLinks().get(Id.createLinkId("road_fast_1"));
        var fast2 = network.getLinks().get(Id.createLinkId("road_fast_2"));
        assertEquals(short1.getFromNode(), fast1.getFromNode());
        assertEquals(short2.getToNode(), fast2.getToNode());
        assertTrue(short1.getLength() + short2.getLength() < fast1.getLength() + fast2.getLength());
        assertTrue(short1.getLength() / short1.getFreespeed() + short2.getLength() / short2.getFreespeed()
                > fast1.getLength() / fast1.getFreespeed() + fast2.getLength() / fast2.getFreespeed(),
                "The alternatives must expose a distance/time tradeoff rather than duplicate links");
        var population = PopulationUtils.readPopulation(
                reference.inputs().passengerPlanMemory().resolve(workspace).toString());
        assertEquals(IsmtFourArmFixture.ROAD_PASSENGER_IDS,
                population.getPersons().keySet().stream().map(Id::toString).collect(java.util.stream.Collectors.toSet()));
        assertRoadLeg(population.getPersons().get(Id.createPersonId(IsmtFourArmFixture.ROAD_CAR_PASSENGER)),
                TransportMode.car);
        assertRoadLeg(population.getPersons().get(Id.createPersonId(IsmtFourArmFixture.ROAD_RIDE_PASSENGER)),
                TransportMode.ride);

        Map<ExperimentSpec.Arm, Result> runs = new EnumMap<>(ExperimentSpec.Arm.class);
        for (var arm : ExperimentSpec.Arm.values())
            runs.put(arm, run(fixture, fixture.specs().get(arm), "road_" + arm.name()));
        Result control = runs.get(ExperimentSpec.Arm.CONTROL);
        List<Map<String, String>> controlWarmEvents = selectRows(
                control.rows(PassengerScientificFingerprint.FILE_NAME), 0, 19);
        List<Map<String, String>> controlWarmPlans = selectRows(
                control.rows(PassengerPlanFingerprint.FILE_NAME), 0, 19);
        for (Result result : runs.values()) {
            assertEquals(control.validation().preActivationFingerprint(), result.validation().preActivationFingerprint(),
                    "Road passenger event history must match in all four arms through iteration 19: " + result.label());
            assertEquals(controlWarmEvents,
                    selectRows(result.rows(PassengerScientificFingerprint.FILE_NAME), 0, 19),
                    "Road passenger events must share the exact 0..19 history: " + result.label());
            assertEquals(controlWarmPlans,
                    selectRows(result.rows(PassengerPlanFingerprint.FILE_NAME), 0, 19),
                    "Road passenger plans and randomized routes must share the exact 0..19 history: " + result.label());
            for (int iteration = 0; iteration <= 23; iteration++)
                assertEquals("2", result.summary(iteration).get("passenger_count"));
        }

        Result timetable = runs.get(ExperimentSpec.Arm.TIMETABLE_ONLY);
        Result dedicated = runs.get(ExperimentSpec.Arm.DEDICATED);
        assertEquals(selectRows(timetable.rows(PassengerScientificFingerprint.FILE_NAME), 20, 23),
                selectRows(dedicated.rows(PassengerScientificFingerprint.FILE_NAME), 20, 23),
                "T/D road-passenger events must remain equal after activation");
        assertEquals(selectRows(timetable.rows(PassengerPlanFingerprint.FILE_NAME), 20, 23),
                selectRows(dedicated.rows(PassengerPlanFingerprint.FILE_NAME), 20, 23),
                "T/D road-passenger plans and randomized routes must remain equal after activation");
    }

    private static void assertRoadLeg(org.matsim.api.core.v01.population.Person person, String mode) {
        assertNotNull(person);
        List<Leg> legs = person.getSelectedPlan().getPlanElements().stream()
                .filter(Leg.class::isInstance).map(Leg.class::cast).toList();
        assertEquals(1, legs.size());
        assertEquals(mode, legs.getFirst().getMode());
        assertNull(legs.getFirst().getRoute(), "Startup routing must choose between the competing road alternatives");
    }

    private static void assertNativeInnovation(Result result) {
        assertEquals(java.util.stream.IntStream.rangeClosed(0, 23).boxed().toList(),
                new ArrayList<>(result.innovation().rows.keySet()));
        int activeNewPlans = 0;
        for (var entry : result.innovation().rows.entrySet()) {
            int iteration = entry.getKey();
            InnovationRow row = entry.getValue();
            assertEquals(iteration > 0, row.nativeReplanningObserved(), "Iteration zero does not replan");
            assertEquals(List.of("ChangeExpBeta", "ReRoute"), row.passengerWeights().stream()
                    .map(StrategyWeight::configuredName).sorted().toList());
            for (StrategyWeight strategy : row.passengerWeights()) {
                if (strategy.configuredName().equals("ChangeExpBeta")) {
                    assertTrue(strategy.onlySelector());
                    assertEquals(IsmtFourArmFixture.PASSENGER_SELECTOR_WEIGHT, strategy.weight(), 0.0);
                } else {
                    assertFalse(strategy.onlySelector(), "The native ReRoute must really contain an innovative module");
                    // The initial QSim has no native replanning; pending change requests are
                    // first applied inside StrategyManager.run at iteration 1.
                    boolean enabled = iteration == 0 || iteration >= 20 && iteration < 23;
                    assertEquals(enabled ? IsmtFourArmFixture.PASSENGER_REROUTE_WEIGHT : 0., strategy.weight(), 0.0,
                            result.label() + " actual native ReRoute weight at " + iteration);
                }
            }
            assertEquals(List.of("KeepLastSelected"), row.freightWeights().stream().map(StrategyWeight::configuredName).toList());
            assertTrue(row.freightWeights().getFirst().onlySelector());
            assertEquals(1., row.freightWeights().getFirst().weight(), 0.0);
            assertEquals(Set.copyOf(IsmtFourArmFixture.PASSENGER_IDS), row.passengerPlans().keySet());
            Set<String> expectedFreight = iteration >= 20 && result.spec().freightDemand().orderCount() > 0
                    ? Set.copyOf(IsmtFourArmFixture.FREIGHT_IDS) : Set.of();
            assertEquals(expectedFreight, row.freightPlans().keySet(), "Every active fixed freight plan must be audited");
            for (var person : row.passengerPlans().entrySet()) {
                PlanChange change = person.getValue();
                assertTrue(change.newPlans() == 0 || change.newPlans() == 1,
                        "One native strategy applies at most once per person and iteration");
                assertEquals(change.newPlans() == 1, change.selectedPlanIsNew(),
                        "Native ReRoute copies the selected plan and makes the new plan selected");
                if (iteration < 20 || iteration == 23) {
                    assertEquals(0, change.newPlans(), result.label() + " unexpected innovation at " + iteration + ": " + person.getKey());
                    if (iteration < 20) assertEquals(1, change.plansAfter(), "Warm-up must retain only the original plan");
                } else activeNewPlans += change.newPlans();
            }
            for (PlanChange change : row.freightPlans().values()) {
                assertEquals(1, change.plansBefore());
                assertEquals(1, change.plansAfter());
                assertEquals(0, change.newPlans());
                assertFalse(change.selectedPlanIsNew(), "Every freight unit retains its fixed execution plan");
            }
        }
        assertTrue(activeNewPlans > 0, result.label() + " must execute real native innovation during 20..22, not merely configure it");
    }

    private static void assertPriorityAndCapacity(Result result, int iteration) throws IOException {
        List<Boarding> boardings = result.audit().boardings.get(iteration);
        List<String> first = boardings.stream().filter(b -> b.vehicle().equals("passenger_vehicle_0")).map(Boarding::person).toList();
        List<String> waitingPassengers = result.audit().waiting.get(iteration).stream()
                .filter(IsmtFourArmFixture.PASSENGER_IDS::contains).toList();
        assertEquals(11, waitingPassengers.size());
        assertEquals(waitingPassengers.subList(0, 10), first, "First bus must preserve passenger FIFO and fill native capacity 10");
        assertEquals(IsmtFourArmFixture.FREIGHT_IDS, result.audit().waiting.get(iteration).subList(0, 3),
                "Fixture must expose the priority decision: freight arrived before every passenger");
        assertEquals(List.of(waitingPassengers.get(10), IsmtFourArmFixture.FREIGHT_IDS.getFirst()),
                boardings.stream().filter(b -> b.vehicle().equals("passenger_vehicle_1")).map(Boarding::person).toList(),
                "Remaining passenger boards before the first freight unit at the next stop call");
        assertEquals(IsmtFourArmFixture.FREIGHT_IDS, boardings.stream()
                .filter(b -> IsmtFourArmFixture.FREIGHT_IDS.contains(b.person())).map(Boarding::person).toList(), "Freight queue remains stable");
        assertTrue(boardings.stream().allMatch(b -> IsmtFourArmFixture.PASSENGER_LINE.equals(b.line())));
        assertTrue(result.rows("denial_ledger.csv").stream().anyMatch(row -> Integer.toString(iteration).equals(row.get("iteration"))
                && "NATIVE_CAPACITY".equals(row.get("denial_reason"))
                && "passenger_vehicle_0".equals(row.get("vehicle_id")) && "10".equals(row.get("passenger_occupancy"))
                && "0".equals(row.get("freight_occupancy")) && "10".equals(row.get("native_capacity"))),
                "At least one actual candidate must be denied by physical capacity, with its native snapshot");
    }

    private static Result run(IsmtFourArmFixture.Fixture fixture, ExperimentSpec spec, String label) throws Exception {
        Path root = Files.createDirectory(fixture.root().resolve(label));
        writeNewJson(root.resolve("nonformal_fixture_receipt.json"), Map.of("formalRun", false,
                "specSha256", ExperimentSpecIO.sha256(spec), "inputs", spec.inputs().all(), "label", label));
        EventAudit audit = new EventAudit();
        InnovationAudit innovation = new InnovationAudit();
        var prepared = ExperimentRunner.prepareSimulation(spec, fixture.workspace(), root.resolve("matsim"));
        prepared.controler().addOverridingModule(new AbstractModule() {
            @Override public void install() {
                addEventHandlerBinding().toInstance(audit);
                addControllerListenerBinding().toInstance(innovation);
            }
        });
        prepared.controler().run();
        writeNewJson(root.resolve("native_replanning_audit.json"), Map.of(
                "schemaVersion", "ismt-four-arm-native-replanning-v1", "formalRun", false,
                "basis", "Actual StrategyManager weights after core native replanning; plan object identity compared before/after native replanning",
                "syntheticProtocol", spec.protocol(), "iterations", innovation.rows));
        var validation = new IsmtRunValidator().validate(root, spec);
        writeNewJson(root.resolve("nonformal_validation.json"), validation);
        assertTrue(validation.valid(), label + ": " + validation.issues());
        assertEquals(23, validation.finalIteration());
        assertEquals(1, validation.activationCount());
        assertEquals(20, validation.activationIteration());
        assertEquals(20, validation.preActivationFingerprint().size());
        return new Result(label, root, spec, audit, innovation, validation);
    }

    private record Paths(Path workspace, Path root) { }

    private Paths paths() throws IOException {
        String retained = System.getProperty("ismt.smoke.root");
        if (retained == null) {
            Path workspace = temporaryDirectory.toRealPath();
            return new Paths(workspace, Files.createDirectory(workspace.resolve("four-arm")));
        }
        String workspaceProperty = System.getProperty("ismt.workspace");
        if (workspaceProperty == null) throw new IOException("Retained smoke requires -Dismt.workspace=<real workspace>");
        Path workspace = Path.of(workspaceProperty).toRealPath();
        if (!Path.of("").toRealPath().startsWith(workspace)
                || !Files.isRegularFile(workspace.resolve("README_ISMT_REPRODUCIBILITY.md")))
            throw new IOException("Smoke workspace must contain this checkout and the reproducibility README");
        Path root = Path.of(retained);
        if (!root.isAbsolute() || !root.normalize().startsWith(workspace) || root.normalize().equals(workspace)
                || Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Smoke output must be an absolute NEW directory inside the workspace: " + root);
        root = root.normalize();
        Path existing = root.getParent();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || !existing.toRealPath().startsWith(workspace)) throw new IOException("Smoke output symlink escapes workspace");
        Files.createDirectories(root.getParent());
        if (!root.getParent().toRealPath().startsWith(workspace)) throw new IOException("Smoke output parent escapes workspace");
        return new Paths(workspace, Files.createDirectory(root).toRealPath());
    }

    private record Result(String label, Path root, ExperimentSpec spec, EventAudit audit,
            InnovationAudit innovation, RunValidator.ValidationResult validation) {
        List<Map<String, String>> rows(String file) throws IOException { return readCsv(root.resolve("matsim").resolve(file)); }
        Map<String, String> summary(int iteration) throws IOException {
            return rows("iteration_summary.csv").stream().filter(row -> Integer.toString(iteration).equals(row.get("iteration"))).findFirst().orElseThrow();
        }
    }
    private record Boarding(String person, String vehicle, String line, double time) { }

    private record StrategyWeight(String configuredName, String implementation, boolean onlySelector, double weight) { }
    private record PlanChange(int plansBefore, int plansAfter, int newPlans, boolean selectedPlanIsNew) { }
    private record InnovationRow(boolean nativeReplanningObserved, List<StrategyWeight> passengerWeights,
            Map<String, PlanChange> passengerPlans, List<StrategyWeight> freightWeights, Map<String, PlanChange> freightPlans) { }

    /**
     * Passive native-controller observation: no strategy replacement, RNG call, plan
     * mutation, or requested-change-list inference. Normal ReplanningListeners run
     * after core PlansReplanningImpl, including its actual native strategy selection.
     */
    private static final class InnovationAudit implements IterationStartsListener, ReplanningListener, BeforeMobsimListener {
        @Inject private StrategyManager manager;
        @Inject private Scenario scenario;
        private final TreeMap<Integer, InnovationRow> rows = new TreeMap<>();
        private final Map<String, Set<Plan>> before = new TreeMap<>();

        @Override public void notifyIterationStarts(IterationStartsEvent event) {
            before.clear();
            for (var person : scenario.getPopulation().getPersons().values()) {
                String id = person.getId().toString();
                if (!IsmtFourArmFixture.PASSENGER_IDS.contains(id) && !IsmtFourArmFixture.FREIGHT_IDS.contains(id)) continue;
                Set<Plan> plans = Collections.newSetFromMap(new IdentityHashMap<>());
                plans.addAll(person.getPlans());
                before.put(id, plans);
            }
        }

        @Override public void notifyReplanning(ReplanningEvent event) { capture(event.getIteration(), true); }

        @Override public void notifyBeforeMobsim(BeforeMobsimEvent event) {
            if (event.getIteration() == 0) capture(0, false);
            else assertTrue(rows.containsKey(event.getIteration()), "The real native replanning callback must precede QSim");
        }

        private void capture(int iteration, boolean replanned) {
            Map<String, PlanChange> passengers = new TreeMap<>(), freight = new TreeMap<>();
            for (var entry : before.entrySet()) {
                var person = scenario.getPopulation().getPersons().get(Id.createPersonId(entry.getKey()));
                assertNotNull(person, "Observed persons cannot disappear during native replanning");
                int newPlans = (int) person.getPlans().stream().filter(plan -> !entry.getValue().contains(plan)).count();
                var change = new PlanChange(entry.getValue().size(), person.getPlans().size(), newPlans,
                        !entry.getValue().contains(person.getSelectedPlan()));
                (IsmtFourArmFixture.PASSENGER_IDS.contains(entry.getKey()) ? passengers : freight).put(entry.getKey(), change);
            }
            assertNull(rows.put(iteration, new InnovationRow(replanned, weights(null), Map.copyOf(passengers),
                    weights("freight"), Map.copyOf(freight))), "Only one native observation per iteration");
        }

        private List<StrategyWeight> weights(String subpopulation) {
            var strategies = manager.getStrategies(subpopulation);
            var weights = manager.getWeights(subpopulation);
            assertEquals(strategies.size(), weights.size());
            List<StrategyWeight> result = new ArrayList<>();
            for (int i = 0; i < strategies.size(); i++) {
                var strategy = strategies.get(i);
                var bindings = manager.getConfiguredStrategies().entrySet().stream()
                        .filter(binding -> binding.getValue() == strategy
                                && java.util.Objects.equals(binding.getKey().getSubpopulation(), subpopulation)).toList();
                assertEquals(1, bindings.size(), "Audit must identify the registered native instance, not a separately injected prototype");
                result.add(new StrategyWeight(bindings.getFirst().getKey().getStrategyName(), strategy.toString(),
                        ReplanningUtils.isOnlySelector(strategy), weights.get(i)));
            }
            return List.copyOf(result);
        }

        Map<Integer, Object> passengerProjection() {
            Map<Integer, Object> result = new TreeMap<>();
            rows.forEach((iteration, row) -> result.put(iteration, List.of(row.nativeReplanningObserved(),
                    row.passengerWeights(), row.passengerPlans())));
            return result;
        }
    }

    /** Passive observer only: does not create IDs, consume RNG, reorder events, or affect admission. */
    private static final class EventAudit implements BasicEventHandler {
        private int iteration;
        private final Map<Integer, List<String>> waiting = new TreeMap<>();
        private final Map<Integer, List<Boarding>> boardings = new TreeMap<>();
        private final Map<Integer, Integer> freightEvents = new TreeMap<>();
        @Override public void reset(int iteration) {
            this.iteration = iteration;
            waiting.put(iteration, new ArrayList<>());
            boardings.put(iteration, new ArrayList<>());
            freightEvents.put(iteration, 0);
        }
        @Override public void handleEvent(Event event) {
            if (!(event instanceof HasPersonId identified)) return;
            String person = identified.getPersonId().toString();
            boolean freight = IsmtFourArmFixture.FREIGHT_IDS.contains(person);
            if (!freight && !IsmtFourArmFixture.PASSENGER_IDS.contains(person)) return;
            if (freight) freightEvents.merge(iteration, 1, Integer::sum);
            if (event instanceof AgentWaitingForPtEvent wait && "origin_stop".equals(wait.getWaitingAtStopId().toString()))
                waiting.get(iteration).add(person);
            if (event instanceof PersonEntersVehicleEvent enter) {
                String line = enter instanceof PersonEntersPtVehicleEvent pt ? pt.getTransitLine().toString() : "";
                boardings.get(iteration).add(new Boarding(person, enter.getVehicleId().toString(), line, event.getTime()));
            }
        }
    }

    private static List<Map<String, String>> readCsv(Path file) throws IOException {
        try (var stream = Files.newInputStream(file);
             var decoded = file.toString().endsWith(".gz") ? new GZIPInputStream(stream) : stream;
             var reader = new InputStreamReader(decoded, StandardCharsets.UTF_8);
             var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            List<Map<String, String>> result = new ArrayList<>();
            for (var record : parser) result.add(record.toMap());
            return result;
        }
    }
    private static List<Map<String, String>> withoutContext(List<Map<String, String>> rows) {
        return rows.stream().map(row -> {
            Map<String, String> copy = new TreeMap<>(row);
            CONTEXT.forEach(copy::remove);
            return copy;
        }).toList();
    }
    private static List<Map<String, String>> selectRows(List<Map<String, String>> rows, int start, int end) {
        return withoutContext(rows.stream().filter(row -> {
            int iteration = Integer.parseInt(row.get("iteration"));
            return iteration >= start && iteration <= end;
        }).toList());
    }
    private static List<Map<String, String>> project(List<Map<String, String>> rows, List<String> columns) {
        return rows.stream().map(row -> {
            Map<String, String> copy = new LinkedHashMap<>();
            for (String column : columns) {
                assertTrue(row.containsKey(column), "Missing comparison field: " + column);
                copy.put(column, row.get(column));
            }
            return copy;
        }).toList();
    }
    private static void writeNewJson(Path path, Object value) throws IOException {
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }
}
