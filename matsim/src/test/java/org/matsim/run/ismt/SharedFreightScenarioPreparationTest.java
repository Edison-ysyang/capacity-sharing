package org.matsim.run.ismt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.cotransport.CoTransportBoardingPolicy;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.SharedFreightScenarioPreparation.ValidationOrder.*;

/** In-memory preparation checks only: no Controller, QSim, Mobsim or forecast invocation. */
class SharedFreightScenarioPreparationTest {
    private static final String HEADER = "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n";
    private static final String ORDER = "parcel,13:30:00,14:30:00,0.01,2,standard,1,2\n";
    @TempDir Path directory;
    private int fixtureNumber;

    @Test void bothSequencesReturnSameRegisteredFactsWithoutChangingPopulationOrPlans() throws Exception {
        for (var order : SharedFreightScenarioPreparation.ValidationOrder.values()) {
            Scenario scenario = scenario(ORDER, "parcel", "parcel_cap_02");
            addPerson(scenario, "passenger", false);
            Runnable unchanged = snapshot(scenario);
            var prepared = SharedFreightScenarioPreparation.prepare(scenario, 1, order);
            assertEquals(1, prepared.conservation().orders());
            assertEquals(2, prepared.conservation().expectedUnits());
            assertEquals(2, prepared.conservation().prebuiltUnits());
            assertEquals(2, prepared.registry().orderForPerson(Id.createPersonId("parcel")).capacityUnits(),
                    "Explicit units must not be replaced by the volume-derived count");
            assertEquals(0.10, prepared.settings().freightShareCap(), 0);
            assertEquals(0.90, prepared.settings().passengerLoadGate(), 0);
            assertFalse(prepared.registry().isFreightPerson(Id.createPersonId("passenger")));
            unchanged.run();
        }
    }

    @Test void emptyControlPopulationDoesNotAcquireFreightOrABoardingWindow() throws Exception {
        for (var order : SharedFreightScenarioPreparation.ValidationOrder.values()) {
            Scenario scenario = scenario("");
            addPerson(scenario, "passenger", false);
            Runnable unchanged = snapshot(scenario);
            var prepared = SharedFreightScenarioPreparation.prepare(scenario, 0, order);
            assertEquals(0, prepared.conservation().orders());
            assertTrue(prepared.registry().unitIds().isEmpty());
            assertNull(scenario.getScenarioElement(CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT));
            unchanged.run();
        }
    }

    @Test void declaredServiceWindowDoesNotInstallPhysicalWindowForEitherRunnerFamily() throws Exception {
        for (var order : SharedFreightScenarioPreparation.ValidationOrder.values()) {
            Scenario scenario = scenario(ORDER, "parcel", "parcel_cap_02");
            var prepared = SharedFreightScenarioPreparation.prepare(scenario, 1, order);
            assertEquals(48600, prepared.settings().freightServiceWindowStartTime(), 0);
            assertEquals(59400, prepared.settings().freightServiceWindowEndTime(), 0);
            assertNull(scenario.getScenarioElement(CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT),
                    "Only the explicit arm-specific assembly may install a physical boarding window");
        }
    }

    @Test void dedicatedSettingsStayDedicatedWithoutImplicitMixedWindowOrSupplyChanges() throws Exception {
        Scenario scenario = scenario(ORDER, "parcel", "parcel_cap_02");
        var co = ConfigUtils.addOrGetModule(scenario.getConfig(), CoTransportConfigGroup.class);
        co.setDedicatedFreightLineId("registered-dedicated-line");
        var schedule = scenario.getTransitSchedule();
        var vehicles = scenario.getTransitVehicles();
        var prepared = SharedFreightScenarioPreparation.prepare(scenario, 1, REGISTERED_V1_DEMAND_FIRST);
        assertEquals("registered-dedicated-line", prepared.settings().dedicatedFreightLineId());
        assertSame(schedule, scenario.getTransitSchedule());
        assertSame(vehicles, scenario.getTransitVehicles());
        assertNull(scenario.getScenarioElement(CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT));
    }

    @Test void anExplicitExistingBoardingWindowIsNeitherReplacedNorRemoved() throws Exception {
        Scenario scenario = scenario(ORDER, "parcel", "parcel_cap_02");
        var window = new CoTransportBoardingPolicy.SharedBoardingWindow(48600, 59400);
        scenario.addScenarioElement(CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT, window);
        SharedFreightScenarioPreparation.prepare(scenario, 1, REGISTERED_V2_POPULATION_FIRST);
        assertSame(window, scenario.getScenarioElement(CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT));
    }

    @Test void demandMismatchRetainsEachRegisteredErrorMessage() throws Exception {
        Scenario scenario = scenario(ORDER, "parcel", "parcel_cap_02");
        assertEquals("Declared demand differs from the execution order table",
                assertThrows(IllegalStateException.class, () -> SharedFreightScenarioPreparation.prepare(
                        scenario, 0, REGISTERED_V1_DEMAND_FIRST)).getMessage());
        assertEquals("Declared freight demand differs from the execution order table",
                assertThrows(IllegalStateException.class, () -> SharedFreightScenarioPreparation.prepare(
                        scenario, 0, REGISTERED_V2_POPULATION_FIRST)).getMessage());
    }

    @Test void simultaneousDemandAndMissingUnitErrorsKeepHistoricalFailurePrecedence() throws Exception {
        Scenario scenario = scenario(ORDER, "parcel");
        Runnable unchanged = snapshot(scenario);
        assertEquals("Declared demand differs from the execution order table",
                assertThrows(IllegalStateException.class, () -> SharedFreightScenarioPreparation.prepare(
                        scenario, 0, REGISTERED_V1_DEMAND_FIRST)).getMessage());
        assertTrue(assertThrows(IllegalStateException.class, () -> SharedFreightScenarioPreparation.prepare(
                scenario, 0, REGISTERED_V2_POPULATION_FIRST)).getMessage().startsWith("Freight population/order mismatch:"));
        unchanged.run();
    }

    @Test void missingUnitsAreRejectedRatherThanMaterialized() throws Exception {
        for (var order : SharedFreightScenarioPreparation.ValidationOrder.values()) {
            Scenario scenario = scenario(ORDER, "parcel");
            Runnable unchanged = snapshot(scenario);
            assertThrows(IllegalStateException.class, () -> SharedFreightScenarioPreparation.prepare(scenario, 1, order));
            assertFalse(scenario.getPopulation().getPersons().containsKey(Id.createPersonId("parcel_cap_02")));
            unchanged.run();
        }
    }

    @Test void unregisteredFreightIsRejectedWithoutRemovingPersons() throws Exception {
        for (var order : SharedFreightScenarioPreparation.ValidationOrder.values()) {
            Scenario scenario = scenario(ORDER, "parcel", "parcel_cap_02", "unregistered");
            Runnable unchanged = snapshot(scenario);
            assertTrue(assertThrows(IllegalStateException.class, () -> SharedFreightScenarioPreparation.prepare(
                    scenario, 1, order)).getMessage().contains("unregistered freight persons"));
            unchanged.run();
        }
    }

    @Test void multiplePlansFailBothSequencesWithoutSelectingOrDeletingAPlan() throws Exception {
        for (var order : SharedFreightScenarioPreparation.ValidationOrder.values()) {
            Scenario scenario = scenario(ORDER, "parcel", "parcel_cap_02");
            scenario.getPopulation().getPersons().get(Id.createPersonId("parcel"))
                    .addPlan(scenario.getPopulation().getFactory().createPlan());
            Runnable unchanged = snapshot(scenario);
            assertTrue(assertThrows(IllegalStateException.class, () -> SharedFreightScenarioPreparation.prepare(
                    scenario, 1, order)).getMessage().contains("exactly one prebuilt selected fixed plan"));
            unchanged.run();
        }
    }

    @Test void duplicateOrdersAreRejectedByTheSharedRegistryPath() throws Exception {
        Scenario scenario = scenario(ORDER + ORDER, "parcel", "parcel_cap_02");
        Runnable unchanged = snapshot(scenario);
        for (var order : SharedFreightScenarioPreparation.ValidationOrder.values()) {
            assertTrue(assertThrows(IllegalStateException.class, () -> SharedFreightScenarioPreparation.prepare(
                    scenario, 2, order)).getMessage().startsWith("Duplicate freight order id"));
        }
        unchanged.run();
    }

    private Scenario scenario(String orderRows, String... units) throws Exception {
        Path orders = directory.resolve("orders-" + fixtureNumber++ + ".csv");
        Files.writeString(orders, HEADER + orderRows);
        var config = ConfigUtils.createConfig(new CoTransportConfigGroup());
        var co = ConfigUtils.addOrGetModule(config, CoTransportConfigGroup.class);
        co.setFreightOrdersFile(orders.toString());
        // This fixture characterizes the historical ID/fixed-plan preparation contract.
        co.setFreightBoardingMode(org.matsim.contrib.cotransport.CoTransportSettings.FreightBoardingMode.UNIT_SPLIT);
        co.setFreightEquivalentPassengersPerM3(5);
        co.setFreightShareCap(0.10);
        co.setPassengerLoadGate(0.90);
        co.setFreightServiceWindowStartTime(48600);
        co.setFreightServiceWindowEndTime(59400);
        Scenario scenario = ScenarioUtils.createScenario(config);
        for (String unit : units) addPerson(scenario, unit, true);
        return scenario;
    }

    private void addPerson(Scenario scenario, String id, boolean freight) {
        var factory = scenario.getPopulation().getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        person.getAttributes().putAttribute("subpopulation", freight ? "freight" : "passenger");
        var plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromLinkId(freight ? "freight_destination" : "home", Id.createLinkId("link")));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        scenario.getPopulation().addPerson(person);
    }

    private Runnable snapshot(Scenario scenario) {
        var persons = new LinkedHashMap<>(scenario.getPopulation().getPersons());
        var checks = new ArrayList<Runnable>();
        for (var person : persons.values()) {
            var plans = new ArrayList<>(person.getPlans());
            var selected = person.getSelectedPlan();
            var attributes = new LinkedHashMap<>(person.getAttributes().getAsMap());
            checks.add(() -> {
                assertSame(person, scenario.getPopulation().getPersons().get(person.getId()));
                assertEquals(plans, person.getPlans());
                assertSame(selected, person.getSelectedPlan());
                assertEquals(attributes, person.getAttributes().getAsMap());
            });
        }
        return () -> {
            assertEquals(persons.keySet(), scenario.getPopulation().getPersons().keySet());
            checks.forEach(Runnable::run);
        };
    }
}
