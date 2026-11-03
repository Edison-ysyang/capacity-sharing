package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.PlanAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.PtConstants;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure assembly with fake agents in a NEVER RUN QSim; no native preparation or timing proof. */
class DynamicMixedScenarioInputsTest {
    @TempDir Path temporary;
    private static final DynamicMixedDesign.Protocol PROTOCOL = new DynamicMixedDesign.Protocol(0, 2, 4, 5, 4, 5);
    private static final String UNIT = "order", SHADOW = "order_cap_02";

    @Test void activeDayContainsExactSupplyAllUnitsAndActualAdmissionSettings() throws Exception {
        var f = new Fixture(); var assembly = f.capture(); var day = assembly.forIteration(2);
        assertEquals(Set.of("p", UNIT, SHADOW), day.qsimInputs().expectedPopulationIds());
        assertEquals(Set.of("p"), day.qsimInputs().passengerIds());
        assertEquals(1, day.qsimInputs().freightContracts().size());
        var contract = day.qsimInputs().freightContracts().getFirst();
        assertEquals("A", contract.originStopId()); assertEquals("C", contract.destinationStopId());
        assertEquals(List.of(UNIT, SHADOW), contract.unitIds());
        assertEquals("cargo_origin", contract.releaseActivityType()); assertEquals(10, contract.earliestReleaseSeconds());
        assertEquals("cargo_delivery", day.qsimInputs().journeyContracts().getFirst().deliveryActivityPrefix());
        assertEquals(new DynamicMixedScenarioInputs.Admission(0.25, 0.75), day.admission());
        assertEquals(1, day.supply().trips().size()); assertEquals(2, day.inputAudit().freightUnits());
        assertEquals(1, day.inputAudit().staticFreightOds());
        assertSame(day, assembly.forIteration(2));
    }

    @Test void captureAllowsUnroutedFreightButActiveDayRequiresNativeRoutingAlreadyDone() throws Exception {
        var f = new Fixture(); f.pt(f.freight).setRoute(null); f.pt(f.shadow).setRoute(null);
        var assembly = f.capture();
        f.removeFreight(); var inactive = assembly.forIteration(0);
        assertTrue(inactive.qsimInputs().freightContracts().isEmpty());
        assertTrue(inactive.qsimInputs().journeyContracts().isEmpty()); assertTrue(inactive.accessEgress().isEmpty());
        assertEquals(Set.of("p"), inactive.qsimInputs().expectedPopulationIds());
        f.restoreFreight(); assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
        f.pt(f.freight).setRoute(f.ptRoute(f.c)); f.pt(f.shadow).setRoute(f.ptRoute(f.c));
        assertEquals(2, assembly.forIteration(2).inputAudit().freightUnits());
    }

    @Test void phaseExpectedPopulationIsIndependentOfWhatHappensToBeInstalled() throws Exception {
        var f = new Fixture(); var assembly = f.capture();
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(0));
        f.removeFreight(); assembly.forIteration(0);
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
        f.restoreFreight(); f.scenario.getPopulation().removePerson(f.passenger.getId());
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
        f.scenario.getPopulation().addPerson(f.passenger); f.person("extra", false);
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
    }

    @Test void captureDoesNotAcceptAnAlreadyIncompletePopulation() throws Exception {
        var f = new Fixture(); f.scenario.getPopulation().removePerson(f.shadow.getId());
        assertThrows(IllegalStateException.class, f::capture);
    }

    @Test void routeTimeOverridesLegTimeAndStaticEstimatesAreNotFutureActualEvents() throws Exception {
        var f = new Fixture(); f.withWalks(f.freight, 3, 7); f.withWalks(f.shadow, 3, 7);
        var day = f.capture().forIteration(2);
        var estimate = day.accessEgress().get(new ClosedPassengerArrivalForecast.Od("A", "C"));
        assertEquals(3, estimate.accessSeconds()); assertEquals(7, estimate.egressSeconds());
        assertTrue(day.inputAudit().accessEgressMeaning().contains("not-actual"));
    }

    @Test void nativeLegTimeFallbackIsUsedWhenRouteTravelTimeIsUndefined() throws Exception {
        var f = new Fixture(); f.withWalks(f.freight, 3, 7); f.withWalks(f.shadow, 3, 7);
        for (Person person : List.of(f.freight, f.shadow)) {
            Leg access = (Leg) person.getSelectedPlan().getPlanElements().get(1);
            access.getRoute().setTravelTimeUndefined(); access.setTravelTime(9);
        }
        assertEquals(9, f.capture().forIteration(2).accessEgress().values().iterator().next().accessSeconds());
    }

    @Test void missingAccessTimeIsNotReplacedWithZero() throws Exception {
        var f = new Fixture(); f.withWalks(f.freight, 3, 7); f.withWalks(f.shadow, 3, 7);
        Leg access = (Leg) f.freight.getSelectedPlan().getPlanElements().get(1);
        access.getRoute().setTravelTimeUndefined(); access.setTravelTimeUndefined();
        var assembly = f.capture(); assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
    }

    @Test void sameOdConflictingEstimatesAreRejectedRatherThanLastWriteWins() throws Exception {
        var f = new Fixture(); f.withWalks(f.freight, 3, 7); f.withWalks(f.shadow, 4, 7);
        var assembly = f.capture(); assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
    }

    @Test void secondPtLegAndContinuousChainAreBothRejectedForFreight() throws Exception {
        var f = new Fixture(); var assembly = f.capture();
        DefaultTransitPassengerRoute route = (DefaultTransitPassengerRoute) f.pt(f.freight).getRoute();
        route.chainedRoute = f.ptRoute(f.c);
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
        route.chainedRoute = null;
        Plan plan = f.freight.getSelectedPlan();
        plan.getPlanElements().add(2, f.stage(f.c));
        Leg extra = PopulationUtils.createLeg(TransportMode.pt); extra.setRoute(f.ptRoute(f.c));
        plan.getPlanElements().add(3, extra);
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
    }

    @Test void reversedExactStopsAndUnavailableAlightingAreRejected() throws Exception {
        var f = new Fixture(); var assembly = f.capture();
        f.pt(f.freight).setRoute(new DefaultTransitPassengerRoute(f.c, f.line, f.route, f.a));
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
        f.pt(f.freight).setRoute(f.ptRoute(f.c));
        f.route.getStops().getLast().setAllowAlighting(false);
        var blockedAtCapture = f.capture();
        assertThrows(IllegalArgumentException.class, () -> blockedAtCapture.forIteration(2));
    }

    @Test void oneOrdersUnitsCannotUseDifferentExactDestinations() throws Exception {
        var f = new Fixture(); f.pt(f.shadow).setRoute(f.ptRoute(f.b));
        var assembly = f.capture(); assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
    }

    @Test void invalidInitialReleaseOrDeliveryPrefixFailsAtCapture() throws Exception {
        var f = new Fixture(); ((Activity) f.freight.getSelectedPlan().getPlanElements().getFirst()).setEndTime(11);
        assertThrows(IllegalArgumentException.class, f::capture);
        ((Activity) f.freight.getSelectedPlan().getPlanElements().getFirst()).setEndTime(10);
        ((Activity) f.freight.getSelectedPlan().getPlanElements().getLast()).setType("not-delivery");
        assertThrows(IllegalArgumentException.class, f::capture);
    }

    @Test void nonzeroStageAndNonwalkAccessAreExplicitlyUnsupported() throws Exception {
        var f = new Fixture(); f.withWalks(f.freight, 3, 7); f.withWalks(f.shadow, 3, 7);
        Activity stage = (Activity) f.freight.getSelectedPlan().getPlanElements().get(2);
        stage.setMaximumDuration(1);
        var assembly = f.capture(); assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
        stage.setMaximumDuration(0); ((Leg) f.freight.getSelectedPlan().getPlanElements().get(1)).setMode(TransportMode.car);
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
    }

    @Test void changedSupplyOrTimingConfigurationCannotBeSilentlyRecaptured() throws Exception {
        var f = new Fixture(); var assembly = f.capture();
        f.type.getCapacity().setSeats(99);
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
        f.type.getCapacity().setSeats(4); f.scenario.getConfig().qsim().setStartTime(1);
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
    }

    @Test void nativeScoresAndPlanObjectsArePreservedAndDoNotInvalidateFixedRouting() throws Exception {
        var f = new Fixture(); Plan plan = f.freight.getSelectedPlan(); plan.setScore(12.5);
        var assembly = f.capture(); var first = assembly.forIteration(2); plan.setScore(-31.5);
        var later = assembly.forIteration(3);
        assertSame(plan, f.freight.getSelectedPlan()); assertEquals(-31.5, plan.getScore());
        assertEquals(first.qsimInputs().freightContracts(), later.qsimInputs().freightContracts());
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
    }

    @Test void fixedFreightRoutingCannotChangeBetweenActiveIterations() throws Exception {
        var f = new Fixture(); var assembly = f.capture(); assembly.forIteration(2);
        f.pt(f.freight).setRoute(f.ptRoute(f.b)); f.pt(f.shadow).setRoute(f.ptRoute(f.b));
        assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(3));
    }

    @Test void preparedCheckReadsActualCopiedPlansNotSelectedPlanSubstitutes() throws Exception {
        var f = new Fixture(); var day = f.capture().forIteration(2); QSim qsim = f.qsim();
        f.addAgent(qsim, f.passenger, f.passenger.getSelectedPlan());
        Plan actual = f.copy(f.freight); f.addAgent(qsim, f.freight, actual);
        f.addAgent(qsim, f.shadow, f.copy(f.shadow));
        var report = day.verifyPrepared(qsim);
        assertEquals(2, report.verifiedFreightUnits()); assertFalse(report.preparationBarrierCertified());
        assertFalse(report.actualTimingCertified()); assertNotSame(f.freight.getSelectedPlan(), actual);
        ((DefaultTransitPassengerRoute) ((Leg) actual.getPlanElements().get(1)).getRoute()).egressFacility = f.b.getId();
        assertThrows(IllegalArgumentException.class, () -> day.verifyPrepared(qsim));
        assertEquals(f.c.getId(), ((DefaultTransitPassengerRoute) f.pt(f.freight).getRoute()).getEgressStopId());
    }

    @Test void actualAccessDurationMismatchIsNotHiddenByValidSelectedPlan() throws Exception {
        var f = new Fixture(); f.withWalks(f.freight, 3, 7); f.withWalks(f.shadow, 3, 7);
        var day = f.capture().forIteration(2); QSim qsim = f.qsim();
        f.addAgent(qsim, f.passenger, f.passenger.getSelectedPlan());
        Plan actual = f.copy(f.freight); ((Leg) actual.getPlanElements().get(1)).getRoute().setTravelTime(4);
        f.addAgent(qsim, f.freight, actual); f.addAgent(qsim, f.shadow, f.copy(f.shadow));
        assertThrows(IllegalArgumentException.class, () -> day.verifyPrepared(qsim));
    }

    @Test void preparedPopulationMissingOrExtraAgentCannotPass() throws Exception {
        var f = new Fixture(); var day = f.capture().forIteration(2); QSim qsim = f.qsim();
        f.addAgent(qsim, f.passenger, f.passenger.getSelectedPlan());
        f.addAgent(qsim, f.freight, f.copy(f.freight));
        assertThrows(IllegalStateException.class, () -> day.verifyPrepared(qsim));
        f.addAgent(qsim, f.shadow, f.copy(f.shadow));
        Person extra = f.person("extra", false); f.addAgent(qsim, extra, extra.getSelectedPlan());
        assertThrows(IllegalStateException.class, () -> day.verifyPrepared(qsim));
    }

    @Test void inactivePreparedDayContainsNoFreightAndDoesNotRequireTheirPlans() throws Exception {
        var f = new Fixture(); var assembly = f.capture(); f.removeFreight(); var day = assembly.forIteration(0);
        QSim qsim = f.qsim(); f.addAgent(qsim, f.passenger, f.passenger.getSelectedPlan());
        assertEquals(0, day.verifyPrepared(qsim).verifiedFreightUnits());
        f.restoreFreight(); assembly.forIteration(2);
        assertThrows(IllegalArgumentException.class, () -> day.verifyPrepared(qsim));
    }

    @Test void preparedQsimFromAnotherScenarioCannotAuthenticateThisDay() throws Exception {
        var f = new Fixture(); var day = f.capture().forIteration(2);
        var other = new Fixture(); QSim qsim = other.qsim();
        assertThrows(IllegalArgumentException.class, () -> day.verifyPrepared(qsim));
    }

    @Test void accessWalkEndpointMustAgreeWithTheExactPtStopLink() throws Exception {
        var f = new Fixture(); f.withWalks(f.freight, 3, 7); f.withWalks(f.shadow, 3, 7);
        Leg access = (Leg) f.freight.getSelectedPlan().getPlanElements().get(1);
        var wrong = RouteUtils.createGenericRouteImpl(f.a.getLinkId(), f.b.getLinkId()); wrong.setTravelTime(3); access.setRoute(wrong);
        var assembly = f.capture(); assertThrows(IllegalArgumentException.class, () -> assembly.forIteration(2));
    }

    @Test void dataCollectionsAreImmutableAndNoPopulationMutationIsPerformed() throws Exception {
        var f = new Fixture(); var before = Map.copyOf(f.scenario.getPopulation().getPersons());
        var day = f.capture().forIteration(2);
        assertEquals(before, f.scenario.getPopulation().getPersons());
        assertThrows(UnsupportedOperationException.class, () -> day.accessEgress().clear());
        assertThrows(UnsupportedOperationException.class, () -> day.qsimInputs().expectedPopulationIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> day.qsimInputs().freightContracts().clear());
    }

    @Test void incompatibleHandlingDedicatedAdmissionOrPhaseSettingsAreRejected() throws Exception {
        var f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> f.capture(f.settingsBuilder().freightHandlingDwellSeconds(1).build()));
        assertThrows(IllegalArgumentException.class, () -> f.capture(f.settingsBuilder().dedicatedFreightLineId("dedicated").build()));
        assertThrows(IllegalArgumentException.class, () -> f.capture(f.settingsBuilder().activationIteration(3).build()));
    }

    private final class Fixture {
        final Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        final Path orders;
        final CoTransportSettings settings;
        final FreightOrderRegistry registry;
        final TransitStopFacility a, b, c;
        final TransitLine line;
        final TransitRoute route;
        final VehicleType type;
        final Person passenger, freight, shadow;

        Fixture() throws Exception {
            scenario.getConfig().transit().setUseTransit(true); scenario.getConfig().transit().setUsingTransitInMobsim(true);
            scenario.getConfig().qsim().setStartTime(0); scenario.getConfig().qsim().setNumberOfThreads(1);
            scenario.getConfig().global().setNumberOfThreads(1);
            scenario.getConfig().controller().setOutputDirectory(temporary.resolve("unused-output").toString());
            var sf = scenario.getTransitSchedule().getFactory();
            a = sf.createTransitStopFacility(Id.create("A", TransitStopFacility.class), new Coord(0, 0), false);
            b = sf.createTransitStopFacility(Id.create("B", TransitStopFacility.class), new Coord(1, 0), false);
            c = sf.createTransitStopFacility(Id.create("C", TransitStopFacility.class), new Coord(2, 0), false);
            for (var facility : List.of(a, b, c)) {
                facility.setLinkId(Id.createLinkId("link-" + facility.getId())); scenario.getTransitSchedule().addStopFacility(facility);
            }
            var path = RouteUtils.createLinkNetworkRouteImpl(a.getLinkId(), c.getLinkId()); path.setLinkIds(a.getLinkId(), List.of(b.getLinkId()), c.getLinkId());
            route = sf.createTransitRoute(Id.create("route", TransitRoute.class), path,
                    List.of(sf.createTransitRouteStop(a, 0, 0), sf.createTransitRouteStop(b, 20, 25), sf.createTransitRouteStop(c, 40, 40)), TransportMode.pt);
            line = sf.createTransitLine(Id.create("line", TransitLine.class)); line.addRoute(route); scenario.getTransitSchedule().addTransitLine(line);
            type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class)); type.getCapacity().setSeats(4);
            scenario.getTransitVehicles().addVehicleType(type);
            var vehicle = VehicleUtils.createVehicle(Id.createVehicleId("vehicle"), type); scenario.getTransitVehicles().addVehicle(vehicle);
            var departure = sf.createDeparture(Id.create("d", Departure.class), 100); departure.setVehicleId(vehicle.getId()); route.addDeparture(departure);
            orders = temporary.resolve("orders.csv");
            Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\norder,00:00:10,00:03:00,0.2,1,standard,1,2\n");
            settings = settingsBuilder().build(); registry = new FreightOrderRegistry(settings);
            passenger = person("p", false); freight = person(UNIT, true); shadow = person(SHADOW, true);
        }
        CoTransportSettings.Builder settingsBuilder() { return CoTransportSettings.builder(orders)
                .freightBoardingMode(CoTransportSettings.FreightBoardingMode.UNIT_SPLIT)
                .armMode(CoTransportSettings.ArmMode.ITERATION_WINDOW).activationIteration(2).deactivationIteration(-1)
                .freightShareCap(0.25).passengerLoadGate(0.75).freightHandlingDwellSeconds(0).freightActivityPrefix("cargo_delivery"); }
        DynamicMixedScenarioInputs capture() { return capture(settings); }
        DynamicMixedScenarioInputs capture(CoTransportSettings co) {
            return DynamicMixedScenarioInputs.capture(scenario, registry, co,
                    new DynamicMixedDesign(DynamicMixedDesign.Arm.DYNAMIC_MIXED, PROTOCOL, true, new DispatchCommitmentState.Limits(60, 60, 120)), List.of(), List.of());
        }
        Person person(String id, boolean cargo) {
            var pf = scenario.getPopulation().getFactory(); Person person = pf.createPerson(Id.createPersonId(id)); Plan plan = pf.createPlan();
            Activity initial = pf.createActivityFromLinkId(cargo ? "cargo_origin" : "home", a.getLinkId()); initial.setEndTime(10); plan.addActivity(initial);
            if (cargo) {
                Leg pt = pf.createLeg(TransportMode.pt); pt.setRoute(ptRoute(c)); plan.addLeg(pt);
                plan.addActivity(pf.createActivityFromLinkId("cargo_delivery_final", c.getLinkId()));
                PopulationUtils.putSubpopulation(person, "freight");
            }
            person.addPlan(plan); person.setSelectedPlan(plan); scenario.getPopulation().addPerson(person); return person;
        }
        DefaultTransitPassengerRoute ptRoute(TransitStopFacility destination) { return new DefaultTransitPassengerRoute(a, line, route, destination); }
        Leg pt(Person person) { return person.getSelectedPlan().getPlanElements().stream().filter(e -> e instanceof Leg leg && leg.getMode().equals(TransportMode.pt)).map(e -> (Leg) e).findFirst().orElseThrow(); }
        Activity stage(TransitStopFacility facility) { Activity stage = PopulationUtils.createActivityFromLinkId(PtConstants.TRANSIT_ACTIVITY_TYPE, facility.getLinkId()); stage.setMaximumDuration(0); return stage; }
        Leg walk(TransitStopFacility facility, double duration) {
            Leg leg = PopulationUtils.createLeg(TransportMode.walk); leg.setTravelTime(99);
            var path = RouteUtils.createGenericRouteImpl(facility.getLinkId(), facility.getLinkId()); path.setTravelTime(duration); path.setDistance(1); leg.setRoute(path); return leg;
        }
        void withWalks(Person person, double access, double egress) {
            var elements = person.getSelectedPlan().getPlanElements();
            elements.add(1, walk(a, access)); elements.add(2, stage(a));
            elements.add(elements.size() - 1, stage(c)); elements.add(elements.size() - 1, walk(c, egress));
        }
        void removeFreight() { scenario.getPopulation().removePerson(freight.getId()); scenario.getPopulation().removePerson(shadow.getId()); }
        void restoreFreight() { scenario.getPopulation().addPerson(freight); scenario.getPopulation().addPerson(shadow); }
        Plan copy(Person person) { Plan result = PopulationUtils.createPlan(person); PopulationUtils.copyFromTo(person.getSelectedPlan(), result); return result; }
        QSim qsim() { return new QSimBuilder(scenario.getConfig()).useDefaults().build(scenario, EventsUtils.createEventsManager()); }
        void addAgent(QSim qsim, Person person, Plan plan) {
            MobsimAgent agent = mock(MobsimAgent.class, withSettings().extraInterfaces(PlanAgent.class));
            when(agent.getId()).thenReturn(person.getId()); when(((PlanAgent) agent).getCurrentPlan()).thenReturn(plan); qsim.insertAgentIntoMobsim(agent);
        }
    }
}
