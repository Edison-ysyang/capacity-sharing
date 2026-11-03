package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Pure plan/CSV checks: no Controller, QSim, Mobsim or predictor is constructed or run. */
class WholeOrderRegistryTest {
    @TempDir Path temporary;

    @Test void fiveUnitMotherOrderHasImmutableCompleteMembershipAndOneSharedChain() throws Exception {
        var scenario = scenario(); var registry = FreightOrderRegistry.fromScenario(settings(), scenario);
        var members = registry.unitIdsForOrder("parcel");
        assertEquals(5, members.size());
        assertEquals("parcel_cap_05", members.getLast().toString());
        assertThrows(UnsupportedOperationException.class, () -> members.clear());
        assertEquals(5, scenario.getPopulation().getPersons().size());
        registry.validateWholeOrderPlans(scenario.getPopulation());
    }

    @Test void rejectsPreparationRoutingAndFinalServiceDifferencesWithoutRepairingThePlan() throws Exception {
        List<Consumer<Plan>> mutations = List.of(
                plan -> activity(plan, 0).setEndTime(48601),
                plan -> activity(plan, 2).setMaximumDuration(121),
                plan -> leg(plan, 3).setTravelTime(361),
                plan -> activity(plan, 4).setMaximumDuration(121),
                plan -> ((DefaultTransitPassengerRoute) leg(plan, 5).getRoute()).transitLine = Id.create("other", TransitLine.class),
                plan -> ((DefaultTransitPassengerRoute) leg(plan, 5).getRoute()).transitRoute = Id.create("other", TransitRoute.class),
                plan -> ((DefaultTransitPassengerRoute) leg(plan, 5).getRoute()).accessFacility = Id.create("other", TransitStopFacility.class),
                plan -> ((DefaultTransitPassengerRoute) leg(plan, 5).getRoute()).egressFacility = Id.create("other", TransitStopFacility.class),
                plan -> leg(plan, 7).getRoute().setTravelTime(361),
                plan -> activity(plan, 8).setMaximumDuration(181),
                plan -> activity(plan, 10).setLinkId(Id.createLinkId("other")));
        for (var mutation : mutations) {
            var scenario = scenario(); var changed = scenario.getPopulation().getPersons()
                    .get(Id.createPersonId("parcel_cap_05")).getSelectedPlan();
            mutation.accept(changed); var elements = List.copyOf(changed.getPlanElements());
            var failure = assertThrows(IllegalStateException.class,
                    () -> FreightOrderRegistry.fromScenario(settings(), scenario));
            assertTrue(failure.getMessage().contains("WHOLE_ORDER"));
            assertSame(changed, scenario.getPopulation().getPersons().get(Id.createPersonId("parcel_cap_05")).getSelectedPlan());
            assertEquals(elements, changed.getPlanElements());
        }
    }

    @Test void explicitSplitModeDoesNotAddWholeChainRestrictionsToHistoricalFixedPlans() throws Exception {
        var scenario = scenario();
        leg(scenario.getPopulation().getPersons().get(Id.createPersonId("parcel_cap_05")).getSelectedPlan(), 5).setRoute(null);
        var settings = CoTransportSettings.builder(orders()).freightBoardingMode(CoTransportSettings.FreightBoardingMode.UNIT_SPLIT).build();
        var registry = FreightOrderRegistry.fromScenario(settings, scenario);
        registry.validateFixedPlans(scenario.getPopulation());
        assertNull(leg(scenario.getPopulation().getPersons().get(Id.createPersonId("parcel_cap_05")).getSelectedPlan(), 5).getRoute());
    }

    @Test void rejectsMatchingChainedContinuationForAllFiveUnitsUntilAtomicRelocationExists() throws Exception {
        var scenario = scenario();
        for (var person : scenario.getPopulation().getPersons().values()) {
            var route = (DefaultTransitPassengerRoute) leg(person.getSelectedPlan(), 5).getRoute();
            route.chainedRoute = new DefaultTransitPassengerRoute(
                    Id.createLinkId("stationB"), Id.createLinkId("stationC"),
                    Id.create("B", TransitStopFacility.class), Id.create("C", TransitStopFacility.class),
                    Id.create("line", TransitLine.class), Id.create("continuation", TransitRoute.class));
        }
        var failure = assertThrows(IllegalStateException.class,
                () -> FreightOrderRegistry.fromScenario(settings(), scenario));
        assertTrue(failure.getMessage().contains("WHOLE_ORDER"));
        assertTrue(failure.getMessage().contains("chained PT continuation"));
        assertTrue(failure.getMessage().contains("atomic whole-order continuation extension"));
        assertEquals(5, scenario.getPopulation().getPersons().size());
    }

    @Test void rejectsUnroutedOrUnregisteredUnitsRatherThanMaterializingOrRoutingThem() throws Exception {
        var unrouted = scenario();
        leg(unrouted.getPopulation().getPersons().get(Id.createPersonId("parcel_cap_02")).getSelectedPlan(), 5).setRoute(null);
        assertThrows(IllegalStateException.class, () -> FreightOrderRegistry.fromScenario(settings(), unrouted));
        var missing = scenario(); missing.getPopulation().removePerson(Id.createPersonId("parcel_cap_05"));
        assertThrows(IllegalStateException.class, () -> FreightOrderRegistry.fromScenario(settings(), missing));
        assertEquals(4, missing.getPopulation().getPersons().size());
    }

    private CoTransportSettings settings() throws Exception { return CoTransportSettings.builder(orders()).build(); }
    private Path orders() throws Exception {
        Path path = temporary.resolve("orders.csv");
        if (!Files.exists(path)) Files.writeString(path,
                "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
                        + "parcel,13:30:00,15:00:00,1.25,20,standard,1,5\n");
        return path;
    }
    private Scenario scenario() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig()); var population = scenario.getPopulation();
        var factory = population.getFactory();
        for (int index = 1; index <= 5; index++) {
            var person = factory.createPerson(Id.createPersonId(index == 1 ? "parcel" : "parcel_cap_0" + index));
            person.getAttributes().putAttribute("subpopulation", "freight");
            person.getAttributes().putAttribute("order_id", "parcel");
            var plan = factory.createPlan();
            var release = factory.createActivityFromLinkId("shipment_release", Id.createLinkId("origin"));
            release.setEndTime(48600); plan.addActivity(release);
            addWalk(plan, "origin", "origin", 0);
            addActivity(plan, "pickup_service", "origin", 120);
            addWalk(plan, "origin", "stationA", 360);
            addActivity(plan, "station_handling", "stationA", 120);
            var pt = factory.createLeg("pt"); var route = new DefaultTransitPassengerRoute(
                    Id.createLinkId("stationA"), Id.createLinkId("stationB"),
                    Id.create("A", TransitStopFacility.class), Id.create("B", TransitStopFacility.class),
                    Id.create("line", TransitLine.class), Id.create("route", TransitRoute.class));
            route.setTravelTime(600); route.setDistance(3000); pt.setRoute(route); pt.setTravelTime(600); plan.addLeg(pt);
            addActivity(plan, "pt interaction", "stationB", 0);
            addWalk(plan, "stationB", "customer", 360);
            addActivity(plan, "delivery_service", "customer", 180);
            addWalk(plan, "customer", "customer", 0);
            plan.addActivity(factory.createActivityFromLinkId("freight_delivered", Id.createLinkId("customer")));
            person.addPlan(plan); person.setSelectedPlan(plan); population.addPerson(person);
        }
        return scenario;
    }
    private static void addActivity(Plan plan, String type, String link, double duration) {
        var activity = org.matsim.core.population.PopulationUtils.createActivityFromLinkId(type, Id.createLinkId(link));
        activity.setMaximumDuration(duration); plan.addActivity(activity);
    }
    private static void addWalk(Plan plan, String start, String end, double travel) {
        var leg = org.matsim.core.population.PopulationUtils.createLeg("walk");
        var route = RouteUtils.createGenericRouteImpl(Id.createLinkId(start), Id.createLinkId(end));
        route.setTravelTime(travel); route.setDistance(travel * 1.3); leg.setRoute(route); leg.setTravelTime(travel); plan.addLeg(leg);
    }
    private static Activity activity(Plan plan, int index) { return (Activity) plan.getPlanElements().get(index); }
    private static Leg leg(Plan plan, int index) { return (Leg) plan.getPlanElements().get(index); }
}
