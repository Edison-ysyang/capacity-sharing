package org.matsim.contrib.cotransport;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.MapBinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationPartition;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.replanning.choosers.StrategyChooser;
import org.matsim.core.replanning.choosers.WeightedStrategyChooser;
import org.matsim.core.replanning.selectors.PlanSelector;
import org.matsim.core.replanning.selectors.RandomPlanSelector;
import org.matsim.core.replanning.selectors.WorstPlanForRemovalSelector;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises native selection, not merely the phase controller's requested change list. */
class CoTransportPhaseControllerNativeInnovationTest {
    @TempDir Path temporary;

    @Test
    void unscopedProviderMapMustControlActualManagerStrategiesAcrossAllPhaseBoundaries() throws Exception {
        MatsimRandom.reset(4711);
        var config = ConfigUtils.createConfig();
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(199);
        // Native automatic switch-off at 200 does not interfere with the explicit 0/20/150 protocol.
        config.replanning().setFractionOfIterationsToDisableInnovation(1.0);
        var select = setting("Select", null, .85);
        var innovateA = setting("SameInnovator", null, .05);
        var innovateB = setting("SameInnovator", null, .10);
        var namedSelect = setting("Select", "student", .75);
        var namedInnovate = setting("SameInnovator", "student", .25);
        var freight = setting("SameInnovator", "freight", .40);
        var settings = List.of(select, innovateA, innovateB, namedSelect, namedInnovate, freight);
        var injector = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                bind(ReplanningConfigGroup.class).toInstance(config.replanning());
                bind(ControllerConfigGroup.class).toInstance(config.controller());
                bind(PopulationPartition.class).toInstance(PopulationPartition.SINGLE_INSTANCE);
                bind(new TypeLiteral<StrategyChooser<Plan, Person>>() {}).toInstance(new WeightedStrategyChooser<>());
                bind(new TypeLiteral<PlanSelector<Plan, Person>>() {}).toInstance(new WorstPlanForRemovalSelector());
                var strategies = MapBinder.newMapBinder(binder(), ReplanningConfigGroup.StrategySettings.class, PlanStrategy.class);
                for (var setting : settings) {
                    // Same unscoped provider semantics as the native StrategyManagerModule.
                    if (setting.getStrategyName().equals("Select")) {
                        strategies.addBinding(setting).toProvider(() -> new PlanStrategyImpl.Builder(new RandomPlanSelector<>()).build());
                    } else {
                        strategies.addBinding(setting).toProvider(CountingInnovator::new);
                    }
                }
            }
        });
        StrategyManager manager = injector.getInstance(StrategyManager.class);
        Map<ReplanningConfigGroup.StrategySettings, PlanStrategy> separatelyInjected = injector.getInstance(
                Key.get(new TypeLiteral<Map<ReplanningConfigGroup.StrategySettings, PlanStrategy>>() {}));
        assertEquals(3, manager.getStrategies(null).size());
        assertSame(manager.getStrategies(null).get(1), manager.getConfiguredStrategies().get(innovateA));
        assertSame(manager.getStrategies(null).get(2), manager.getConfiguredStrategies().get(innovateB));
        assertThrows(UnsupportedOperationException.class,
                () -> manager.getConfiguredStrategies().put(innovateA, new CountingInnovator()));
        for (var setting : settings) {
            assertTrue(manager.getStrategies(setting.getSubpopulation()).stream()
                    .noneMatch(actual -> actual == separatelyInjected.get(setting)),
                    "Fixture must reproduce separately injected prototype strategy instances");
        }
        var passengerInnovators = manager.getStrategies(null).stream().filter(CountingInnovator.class::isInstance)
                .map(CountingInnovator.class::cast).toList();
        var namedInnovator = (CountingInnovator) manager.getStrategies("student").get(1);
        var freightInnovator = (CountingInnovator) manager.getStrategies("freight").getFirst();
        assertEquals(2, passengerInnovators.size());
        assertEquals(passengerInnovators.get(0).toString(), passengerInnovators.get(1).toString(),
                "Instance binding cannot be inferred from class or toString");

        Path orders = temporary.resolve("empty-orders.csv");
        Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n");
        var coTransport = CoTransportSettings.builder(orders).manageInnovationSchedule(true)
                .innovationOffIteration(0).innovationReopenIteration(20).innovationFinalOffIteration(150).build();
        Scenario phaseScenario = ScenarioUtils.createScenario(config);
        var phase = new CoTransportPhaseController(coTransport, new FreightOrderRegistry(coTransport), phaseScenario,
                EventsUtils.createEventsManager(), config.controller(), manager, separatelyInjected);
        phase.notifyStartup(null);
        // A repeated startup callback must not register conflicting or duplicate requests.
        phase.notifyStartup(null);

        Population population = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        addPersons(population, "passenger", null, 1000);
        addPersons(population, "student", "student", 100);
        addPersons(population, "freight", "freight", 10);
        Set<Integer> boundaries = Set.of(1, 19, 20, 149, 150, 199);
        int activePassengerCalls = 0, activeNamedCalls = 0;
        for (int iteration = 1; iteration <= 199; iteration++) {
            passengerInnovators.forEach(strategy -> strategy.calls = 0);
            namedInnovator.calls = 0;
            freightInnovator.calls = 0;
            manager.run(population, iteration, null);
            boolean enabled = iteration >= 20 && iteration < 150;
            if (boundaries.contains(iteration)) {
                assertEquals(List.of(.85, enabled ? .05 : 0., enabled ? .10 : 0.), manager.getWeights(null),
                        "Actual native passenger strategy weights at iteration " + iteration);
                assertEquals(List.of(.75, enabled ? .25 : 0.), manager.getWeights("student"),
                        "Actual named passenger weights at iteration " + iteration);
            }
            int calls = passengerInnovators.stream().mapToInt(strategy -> strategy.calls).sum();
            if (!enabled) {
                assertEquals(0, calls, "No passenger innovation may execute outside [20,150): " + iteration);
                assertEquals(0, namedInnovator.calls, "Named passenger innovation must also be off: " + iteration);
            } else {
                activePassengerCalls += calls;
                activeNamedCalls += namedInnovator.calls;
            }
            assertEquals(List.of(.40), manager.getWeights("freight"), "Freight weights must remain untouched");
            assertEquals(10, freightInnovator.calls, "Freight strategy selection must remain untouched");
        }
        assertTrue(activePassengerCalls > 0);
        assertTrue(activeNamedCalls > 0);
        for (var strategy : separatelyInjected.values()) {
            if (strategy instanceof CountingInnovator counter) assertEquals(0, counter.calls,
                    "The separately injected prototype must never be mistaken for a registered strategy");
        }
    }

    @Test
    void foreignLegacyPrototypeIsRejectedBeforeAnyChangesAreRegistered() throws Exception {
        StrategyManager manager = new StrategyManager();
        PlanStrategy actual = new CountingInnovator();
        manager.addStrategy(actual, null, .2);
        var phase = manualPhase(manager, Map.of(setting("Innovate", null, .2), new CountingInnovator()), true);
        var failure = assertThrows(IllegalStateException.class, () -> phase.notifyStartup(null));
        assertTrue(failure.getMessage().contains("unregistered strategy instance"));
        assertEquals(List.of(.2), manager.getWeights(null));
    }

    @Test
    void matchingInstanceInWrongSubpopulationIsRejected() throws Exception {
        StrategyManager manager = new StrategyManager();
        PlanStrategy actual = new CountingInnovator();
        manager.addStrategy(actual, "student", .2);
        var phase = manualPhase(manager, Map.of(setting("Innovate", null, .2), actual), true);
        assertThrows(IllegalStateException.class, () -> phase.notifyStartup(null));
        assertEquals(List.of(.2), manager.getWeights("student"));
    }

    @Test
    void manualManagerCompatibilityRequiresAndUsesActualInstanceIdentity() throws Exception {
        StrategyManager manager = new StrategyManager();
        PlanStrategy selector = new PlanStrategyImpl.Builder(new RandomPlanSelector<>()).build();
        PlanStrategy actual = new CountingInnovator();
        manager.addStrategy(selector, null, .8);
        manager.addStrategy(actual, null, .2);
        var phase = manualPhase(manager, Map.of(setting("Innovate", null, .2), actual), true);
        phase.notifyStartup(null);
        Population empty = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        manager.run(empty, 1, null);
        assertEquals(List.of(.8, 0.), manager.getWeights(null));
        manager.run(empty, 20, null);
        assertEquals(List.of(.8, .2), manager.getWeights(null));
        manager.run(empty, 150, null);
        assertEquals(List.of(.8, 0.), manager.getWeights(null));
        assertTrue(manager.getConfiguredStrategies().isEmpty(), "Manual managers do not invent configuration associations");
    }

    @Test
    void removingActualInstanceAfterStartupFailsBeforeNextNativeReplanning() throws Exception {
        StrategyManager manager = new StrategyManager();
        PlanStrategy actual = new CountingInnovator();
        manager.addStrategy(actual, null, .2);
        var phase = manualPhase(manager, Map.of(setting("Innovate", null, .2), actual), true);
        phase.notifyStartup(null);
        assertTrue(manager.removeStrategy(actual, null));
        manager.addStrategy(new CountingInnovator(), null, .2);
        var failure = assertThrows(IllegalStateException.class,
                () -> phase.notifyIterationStarts(new IterationStartsEvent(null, 1, false)));
        assertTrue(failure.getMessage().contains("unregistered strategy instance"));
    }

    @Test
    void unmanagedLegacyScheduleDoesNotAttemptToControlForeignStrategies() throws Exception {
        StrategyManager manager = new StrategyManager();
        PlanStrategy actual = new CountingInnovator();
        manager.addStrategy(actual, null, .2);
        var phase = manualPhase(manager, Map.of(setting("Innovate", null, .2), new CountingInnovator()), false);
        phase.notifyStartup(null);
        phase.notifyIterationStarts(new IterationStartsEvent(null, 1, false));
        assertEquals(List.of(.2), manager.getWeights(null));
    }

    private CoTransportPhaseController manualPhase(StrategyManager manager,
            Map<ReplanningConfigGroup.StrategySettings, PlanStrategy> configured, boolean managed) throws Exception {
        var config = ConfigUtils.createConfig();
        config.controller().setLastIteration(199);
        Path orders = temporary.resolve("empty-orders.csv");
        Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n");
        var settings = CoTransportSettings.builder(orders).manageInnovationSchedule(managed)
                .innovationOffIteration(0).innovationReopenIteration(20).innovationFinalOffIteration(150).build();
        return new CoTransportPhaseController(settings, new FreightOrderRegistry(settings),
                ScenarioUtils.createScenario(config), EventsUtils.createEventsManager(), config.controller(), manager, configured);
    }

    private static ReplanningConfigGroup.StrategySettings setting(String name, String subpopulation, double weight) {
        return new ReplanningConfigGroup.StrategySettings().setStrategyName(name).setSubpopulation(subpopulation).setWeight(weight);
    }

    private static void addPersons(Population population, String prefix, String subpopulation, int count) {
        for (int i = 0; i < count; i++) {
            Person person = population.getFactory().createPerson(Id.createPersonId(prefix + i));
            Plan plan = population.getFactory().createPlan();
            plan.setScore(0.0);
            person.addPlan(plan);
            if (subpopulation != null) PopulationUtils.putSubpopulation(person, subpopulation);
            population.addPerson(person);
        }
    }

    private static final class CountingInnovator implements PlanStrategy {
        private int calls;
        @Override public void run(HasPlansAndId<Plan, Person> person) { calls++; }
        @Override public void init(ReplanningContext context) { }
        @Override public void finish() { }
        @Override public String toString() { return "same-innovator-label"; }
    }
}
