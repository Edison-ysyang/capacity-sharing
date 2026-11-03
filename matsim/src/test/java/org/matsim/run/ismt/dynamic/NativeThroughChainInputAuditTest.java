package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.routes.ExperimentalTransitRoute;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.ChainedDeparture;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.matsim.run.ismt.dynamic.NativeThroughChainInputAudit.*;

/** Pure native input-object inspection; no QSim, execution-plan provenance or runtime seal claim. */
class NativeThroughChainInputAuditTest {
    @Test void actualNativeDirectRoutePassesWithBoundedStructuralCounts() {
        var f = new Fixture();
        Plan plan = f.plan("person", f.directA());
        Report report = inspect(f.schedule, Map.of("person", plan));
        assertTrue(report.verifiedNoThroughChains());
        assertDoesNotThrow(report::requireNoThroughChains);
        assertEquals(new Counts(2, 2, 2, 0, 1, 1, 1, 1, 0, 0), report.counts());
        assertTrue(report.stableAcrossReads());
        assertEquals(report.beforeStructureSha256(), report.afterStructureSha256());
        assertTrue(report.beforeStructureSha256().matches("[0-9a-f]{64}"));
    }

    @Test void sameVehicleReuseAloneIsNotAThroughChain() {
        var f = new Fixture();
        assertEquals(f.depA.getVehicleId(), f.depB.getVehicleId());
        Report report = inspect(f.schedule, Map.of());
        assertTrue(report.verifiedNoThroughChains());
        assertEquals(2, report.counts().scheduleDepartures());
        assertEquals(0, report.counts().scheduledChainEdges());
    }

    @Test void ordinarySeparatePtLegTransferIsAllowed() {
        var f = new Fixture();
        Plan plan = f.plan("person", f.directA(), f.directB());
        Report report = inspect(f.schedule, Map.of("person", plan));
        assertTrue(report.verifiedNoThroughChains(), report.violations().toString());
        assertEquals(2, report.counts().ptLegs());
        assertEquals(2, report.counts().planRouteParts());
        assertEquals(0, report.counts().planChainEdges());
    }

    @Test void nativeUnmodifiableExecutionPlanViewIsInspectedWithoutElementIdentityAssumptions() {
        var f = new Fixture(); Plan plan = f.plan("person", f.directA());
        Plan firstView = PopulationUtils.unmodifiablePlan(plan);
        Plan secondView = PopulationUtils.unmodifiablePlan(plan);
        assertNotSame(firstView.getPlanElements().get(1), secondView.getPlanElements().get(1));
        Report report = inspect(f.schedule, Map.of("person", firstView));
        assertTrue(report.verifiedNoThroughChains());
        assertEquals(report.beforeStructureSha256(), inspect(f.schedule, Map.of("person", secondView)).beforeStructureSha256());
    }

    @Test void bareDepartureNameReuseAcrossDifferentTripKeysDoesNotInventAChain() {
        var f = new Fixture();
        f.routeB.removeDeparture(f.depB);
        f.departure("departure-A", f.routeB, 200);
        Report report = inspect(f.schedule, Map.of());
        assertTrue(report.verifiedNoThroughChains());
        assertEquals(2, report.counts().scheduleDepartures());
        assertEquals(0, report.counts().scheduledChainEdges());
    }

    @Test void departureChainRecordsExactSourceAndTargetTripIdsAndRejects() {
        var f = new Fixture();
        f.depA.setChainedDepartures(List.of(f.toB()));
        Report report = inspect(f.schedule, Map.of());
        assertEquals(Set.of(Code.SCHEDULE_THROUGH_CHAIN), codes(report));
        assertEquals(1, report.counts().scheduledChainEdges());
        var edge = identity(report, "scheduledChain").checkedFields();
        assertEquals("line-A", edge.get("source.line"));
        assertEquals("route-A", edge.get("source.route"));
        assertEquals("departure-A", edge.get("source.departure"));
        assertEquals("line-B", edge.get("target.line"));
        assertEquals("route-B", edge.get("target.route"));
        assertEquals("departure-B", edge.get("target.departure"));
        assertThrows(IllegalStateException.class, report::requireNoThroughChains);
    }

    @Test void planChainRecordsPersonLegAndAvailableRouteIdsWithoutInventingDeparture() {
        var f = new Fixture();
        Report report = inspect(f.schedule, Map.of("person", f.plan("person", f.chain())));
        assertEquals(Set.of(Code.PLAN_THROUGH_CHAIN), codes(report));
        assertEquals(0, report.counts().scheduledChainEdges());
        assertEquals(1, report.counts().planChainEdges());
        assertEquals(2, report.counts().planRouteParts());
        var edge = identity(report, "planChain").checkedFields();
        assertEquals("person", edge.get("personId"));
        assertEquals("1", edge.get("elementIndex"));
        assertEquals("0", edge.get("legIndex"));
        assertEquals("0", edge.get("sourcePartIndex"));
        assertEquals("1", edge.get("targetPartIndex"));
        assertEquals("line-A", edge.get("source.line"));
        assertEquals("route-A", edge.get("source.route"));
        assertEquals("line-B", edge.get("target.line"));
        assertEquals("route-B", edge.get("target.route"));
        assertFalse(edge.containsKey("source.departure"));
        assertFalse(edge.containsKey("target.departure"));
        assertThrows(IllegalStateException.class, report::requireNoThroughChains);
    }

    @Test void bothScheduleAndExecutingPlanChainsAreIndependentlyCounted() {
        var f = new Fixture(); f.depA.setChainedDepartures(List.of(f.toB()));
        Report report = inspect(f.schedule, Map.of("person", f.plan("person", f.chain())));
        assertEquals(Set.of(Code.SCHEDULE_THROUGH_CHAIN, Code.PLAN_THROUGH_CHAIN), codes(report));
        assertEquals(1, report.counts().scheduledChainEdges());
        assertEquals(1, report.counts().planChainEdges());
    }

    @Test void selectedButUnpassedPlanIsNotSubstitutedForExplicitExecutionPlan() {
        var f = new Fixture();
        Plan selected = f.plan("person", f.chain());
        Person person = selected.getPerson();
        Plan explicitlyExecuted = f.unownedPlan(f.directA());
        person.addPlan(explicitlyExecuted); person.setSelectedPlan(selected);
        assertSame(selected, person.getSelectedPlan());
        Report report = inspect(f.schedule, Map.of("person", explicitlyExecuted));
        assertTrue(report.verifiedNoThroughChains());
        assertEquals(1, report.counts().executingPlans());
        assertEquals(1, report.counts().planRouteParts());
    }

    @Test void explicitChainedPlanIsInspectedEvenWhenAnotherPlanIsSelected() {
        var f = new Fixture();
        Plan selected = f.plan("person", f.directA());
        Plan explicitlyExecuted = f.unownedPlan(f.chain());
        selected.getPerson().addPlan(explicitlyExecuted); selected.getPerson().setSelectedPlan(selected);
        Report report = inspect(f.schedule, Map.of("person", explicitlyExecuted));
        assertEquals(Set.of(Code.PLAN_THROUGH_CHAIN), codes(report));
    }

    @Test void immutableReportRetainsNoLiveScheduleOrPlanCollections() {
        var f = new Fixture(); Plan plan = f.plan("person", f.directA());
        Report report = inspect(f.schedule, Map.of("person", plan));
        String original = report.beforeStructureSha256();
        assertThrows(UnsupportedOperationException.class, () -> report.identities().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.violations().add(new Violation(Code.INVALID_STRUCTURE, "x", "x")));
        assertThrows(UnsupportedOperationException.class, () -> report.identities().getFirst().checkedFields().put("x", "y"));
        f.depA.setChainedDepartures(List.of(f.toB()));
        ((Leg) plan.getPlanElements().get(1)).setRoute(f.chain());
        assertEquals(original, report.beforeStructureSha256());
        assertTrue(report.verifiedNoThroughChains());
        Report later = inspect(f.schedule, Map.of("person", plan));
        assertNotEquals(original, later.beforeStructureSha256());
        assertFalse(later.verifiedNoThroughChains());
    }

    @Test void digestChangesForCheckedVehicleIdentityButNotScoreOrTravelTime() {
        var f = new Fixture(); Plan plan = f.plan("person", f.directA());
        String first = inspect(f.schedule, Map.of("person", plan)).beforeStructureSha256();
        plan.setScore(123.4);
        ((Leg) plan.getPlanElements().get(1)).getRoute().setTravelTime(987);
        ((Leg) plan.getPlanElements().get(1)).getRoute().setDistance(654);
        assertEquals(first, inspect(f.schedule, Map.of("person", plan)).beforeStructureSha256(),
                "This is examined structure only, not full input identity");
        f.depA.setVehicleId(Id.create("different-vehicle", Vehicle.class));
        assertNotEquals(first, inspect(f.schedule, Map.of("person", plan)).beforeStructureSha256());
    }

    @Test void planMapInsertionOrderDoesNotChangeDeterministicDigest() {
        var f = new Fixture(); Plan a = f.plan("a", f.directA()), b = f.plan("b", f.directB());
        var forwards = new LinkedHashMap<String, Plan>(); forwards.put("a", a); forwards.put("b", b);
        var backwards = new LinkedHashMap<String, Plan>(); backwards.put("b", b); backwards.put("a", a);
        assertEquals(inspect(f.schedule, forwards).beforeStructureSha256(), inspect(f.schedule, backwards).beforeStructureSha256());
    }

    @Test void nativeDefaultSelfCycleIsDetectedWithoutRecursiveEgressOrDescriptionCalls() {
        var f = new Fixture(); var route = f.directA(); route.chainedRoute = route;
        Report report = inspect(f.schedule, Map.of("person", f.plan("person", route)));
        assertTrue(codes(report).containsAll(Set.of(Code.PLAN_THROUGH_CHAIN, Code.CYCLIC_PLAN_ROUTE)));
        assertFalse(codes(report).contains(Code.UNVERIFIED_READ_FAILURE));
        assertEquals(1, report.counts().planRouteParts()); assertEquals(1, report.counts().planChainEdges());
        assertEquals("0", identity(report, "planChain").checkedFields().get("targetPartIndex"));
    }

    @Test void nativeTwoPartObjectCycleTerminatesAndReportsBothEdges() {
        var f = new Fixture(); var a = f.directA(); var b = f.directB(); a.chainedRoute = b; b.chainedRoute = a;
        Report report = inspect(f.schedule, Map.of("person", f.plan("person", a)));
        assertTrue(codes(report).contains(Code.CYCLIC_PLAN_ROUTE));
        assertEquals(2, report.counts().planRouteParts()); assertEquals(2, report.counts().planChainEdges());
        assertFalse(report.verifiedNoThroughChains());
    }

    @Test void scheduleCycleUsesTripKeysAndTerminates() {
        var f = new Fixture(); f.depA.setChainedDepartures(List.of(f.toB())); f.depB.setChainedDepartures(List.of(f.toA()));
        Report report = inspect(f.schedule, Map.of());
        assertEquals(Set.of(Code.SCHEDULE_THROUGH_CHAIN, Code.CYCLIC_SCHEDULE_CHAIN), codes(report));
        assertEquals(2, report.counts().scheduledChainEdges());
    }

    @Test void missingScheduleTargetAndDuplicateTargetAreNotSilentlyAccepted() {
        var f = new Fixture();
        ChainedDeparture missing = f.schedule.getFactory().createChainedDeparture(f.lineB.getId(), f.routeB.getId(), Id.create("missing", Departure.class));
        f.depA.setChainedDepartures(List.of(f.toB(), f.toB(), missing));
        Report report = inspect(f.schedule, Map.of());
        assertTrue(codes(report).containsAll(Set.of(Code.INVALID_STRUCTURE, Code.DUPLICATE_IDENTITY, Code.SCHEDULE_THROUGH_CHAIN)));
        assertEquals(3, report.counts().scheduledChainEdges());
    }

    @Test void nullDepartureChainCollectionAndElementAreInvalidNotVerifiedAbsence() {
        var f = new Fixture(); f.depA.setChainedDepartures(null);
        var list = new ArrayList<ChainedDeparture>(); list.add(null); f.depB.setChainedDepartures(list);
        Report report = inspect(f.schedule, Map.of());
        assertTrue(codes(report).contains(Code.INVALID_STRUCTURE));
        assertTrue(codes(report).contains(Code.SCHEDULE_THROUGH_CHAIN));
        assertEquals(1, report.counts().scheduledChainEdges());
        assertFalse(report.verifiedNoThroughChains());
    }

    @Test void genericPtRouteIsUnverifiedAndItsTextIsNeverUsedToGuessChains() {
        var f = new Fixture(); Route route = mock(Route.class);
        when(route.getRouteDescription()).thenThrow(new AssertionError("Do not parse route description"));
        Report report = inspect(f.schedule, Map.of("person", f.plan("person", route)));
        assertEquals(Set.of(Code.UNVERIFIED_PT_ROUTE), codes(report));
        assertEquals(1, report.counts().unverifiedItems());
        verify(route, never()).getRouteDescription();
        assertThrows(IllegalStateException.class, report::requireNoThroughChains);
    }

    @Test void unknownTypedRouteStillRevealsChainButItsSemanticsAreNeverCertified() {
        var f = new Fixture(); TransitPassengerRoute route = mock(TransitPassengerRoute.class);
        when(route.getChainedRoute()).thenReturn(route);
        Report report = inspect(f.schedule, Map.of("person", f.plan("person", route)));
        assertEquals(Set.of(Code.UNVERIFIED_PT_ROUTE, Code.PLAN_THROUGH_CHAIN, Code.CYCLIC_PLAN_ROUTE), codes(report));
        verify(route, never()).getEgressStopId(); verify(route, never()).getRouteDescription();
        assertEquals(1, report.counts().planChainEdges());
        assertFalse(report.verifiedNoThroughChains());
    }

    @Test void defaultRouteSubclassIsNotSilentlyGivenExactNativeSemantics() {
        var f = new Fixture();
        var subclass = new DefaultTransitPassengerRoute(f.s0, f.lineA, f.routeA, f.s1) { };
        Report report = inspect(f.schedule, Map.of("person", f.plan("person", subclass)));
        assertEquals(Set.of(Code.UNVERIFIED_PT_ROUTE), codes(report));
    }

    @Test void malformedKnownRouteFieldsAndWrongServiceStopsAreRejected() {
        var f = new Fixture(); var missing = f.directA(); missing.transitLine = null;
        var wrongStop = f.directA(); wrongStop.accessFacility = f.s2.getId();
        var wrongExit = f.directA(); wrongExit.egressFacility = f.s2.getId();
        for (var route : List.of(missing, wrongStop, wrongExit)) {
            Report report = inspect(f.schedule, Map.of("person", f.plan("person", route)));
            assertTrue(codes(report).contains(Code.INVALID_STRUCTURE));
            assertFalse(report.verifiedNoThroughChains());
        }
    }

    @Test void explicitPlanOwnerMustMatchAndDuplicateOwnerAliasesFailClosed() {
        var f = new Fixture(); Plan plan = f.plan("person", f.directA());
        Report report = inspect(f.schedule, Map.of("person", plan, "alias", plan));
        assertTrue(codes(report).containsAll(Set.of(Code.INVALID_STRUCTURE, Code.DUPLICATE_IDENTITY)));
        assertEquals(2, report.counts().executingPlans());
    }

    @Test void nullEmptyAndUnownedSuppliedPlansAreNotTreatedAsNoPtLegsProof() {
        var f = new Fixture(); var map = new LinkedHashMap<String, Plan>(); map.put("null", null);
        map.put("empty", f.plan("empty"));
        // An activity-only plan is legitimate; a genuinely empty native plan is not verified.
        map.get("empty").getPlanElements().clear();
        map.put("unowned", f.unownedPlan(f.directA()));
        Report report = inspect(f.schedule, map);
        assertEquals(3, report.counts().executingPlans());
        assertEquals(Set.of(Code.INVALID_STRUCTURE), codes(report));
        assertFalse(report.verifiedNoThroughChains());
    }

    @Test void structuralChangeBetweenReadsIsExplicitAndCannotPass() {
        var f = new Fixture(); Plan nativePlan = f.plan("person", f.directA());
        Plan changing = mock(Plan.class);
        when(changing.getPerson()).thenReturn(nativePlan.getPerson());
        List<PlanElement> first = nativePlan.getPlanElements();
        List<PlanElement> second = f.unownedPlan(f.chain()).getPlanElements();
        when(changing.getPlanElements()).thenReturn(first, second);
        Report report = inspect(f.schedule, Map.of("person", changing));
        assertEquals(Set.of(Code.STRUCTURE_CHANGED), codes(report), "Public details intentionally describe first read");
        assertFalse(report.stableAcrossReads());
        assertEquals(0, report.counts().planChainEdges());
        assertNotEquals(report.beforeStructureSha256(), report.afterStructureSha256());
        assertThrows(IllegalStateException.class, report::requireNoThroughChains);
    }

    @Test void runtimeReadFailureIsUnverifiedEvenWhenBothReadsFailIdentically() {
        var f = new Fixture(); Plan broken = mock(Plan.class);
        when(broken.getPlanElements()).thenThrow(new IllegalStateException("fixture read failure"));
        when(broken.getPerson()).thenReturn(f.plan("person", f.directA()).getPerson());
        Report report = inspect(f.schedule, Map.of("person", broken));
        assertEquals(Set.of(Code.UNVERIFIED_READ_FAILURE), codes(report));
        assertEquals(1, report.counts().unverifiedItems());
        assertTrue(report.stableAcrossReads());
        assertFalse(report.verifiedNoThroughChains());
    }

    @SuppressWarnings("deprecation")
    @Test void exactExperimentalUnchainedNativeRouteCanBeVerifiedWithoutTextParsing() {
        var f = new Fixture();
        var route = new ExperimentalTransitRoute(f.s0, f.lineA, f.routeA, f.s1);
        Report report = inspect(f.schedule, Map.of("person", f.plan("person", route)));
        assertTrue(report.verifiedNoThroughChains(), report.violations().toString());
    }

    @Test void typedTransitRouteOnNonPtLegIsInconsistentButChainIsStillInspected() {
        var f = new Fixture(); Plan plan = f.plan("person", f.chain());
        ((Leg) plan.getPlanElements().get(1)).setMode(TransportMode.car);
        Report report = inspect(f.schedule, Map.of("person", plan));
        assertEquals(Set.of(Code.INVALID_STRUCTURE, Code.PLAN_THROUGH_CHAIN), codes(report));
        assertEquals(0, report.counts().ptLegs()); assertEquals(1, report.counts().planChainEdges());
    }

    @Test void duplicateActualRegistryObjectUnderAnotherKeyIsReportedPrecisely() {
        var f = new Fixture(); TransitSchedule malformed = mock(TransitSchedule.class);
        when(malformed.getTransitLines()).thenReturn(Map.of(f.lineA.getId(), f.lineA, Id.create("alias", TransitLine.class), f.lineA));
        Report report = inspect(malformed, Map.of());
        assertTrue(codes(report).containsAll(Set.of(Code.INVALID_STRUCTURE, Code.DUPLICATE_IDENTITY)));
        assertEquals(2, report.counts().scheduleDepartures());
    }

    @Test void routeTraversalLimitFailsClosedInsteadOfRecursingIndefinitely() {
        var f = new Fixture(); DefaultTransitPassengerRoute root = f.directA(), tail = root;
        for (int i = 0; i < 10_000; i++) { tail.chainedRoute = f.directA(); tail = tail.chainedRoute; }
        Report report = inspect(f.schedule, Map.of("person", f.plan("person", root)));
        assertTrue(codes(report).contains(Code.UNVERIFIED_TRAVERSAL_LIMIT));
        assertEquals(10_000, report.counts().planRouteParts());
        assertEquals(10_000, report.counts().planChainEdges());
        assertFalse(report.verifiedNoThroughChains());
    }

    @Test void emptyExplicitDomainCanPassAbsenceOnlyAndNullArgumentsAreRejected() {
        var schedule = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getTransitSchedule();
        Report report = inspect(schedule, Map.of());
        assertTrue(report.verifiedNoThroughChains());
        assertEquals(new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), report.counts());
        assertThrows(NullPointerException.class, () -> inspect(null, Map.of()));
        assertThrows(NullPointerException.class, () -> inspect(schedule, null));
    }

    private static Set<Code> codes(Report report) {
        return report.violations().stream().map(Violation::code).collect(Collectors.toSet());
    }

    private static Identity identity(Report report, String kind) {
        return report.identities().stream().filter(identity -> kind.equals(identity.checkedFields().get("kind"))).findFirst().orElseThrow();
    }

    private static final class Fixture {
        final Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        final TransitSchedule schedule = scenario.getTransitSchedule();
        final TransitStopFacility s0 = stop("stop-0", 0), s1 = stop("stop-1", 1), s2 = stop("stop-2", 2);
        final TransitLine lineA = line("line-A"), lineB = line("line-B");
        final TransitRoute routeA = route("route-A", lineA, s0, s1), routeB = route("route-B", lineB, s1, s2);
        final Departure depA = departure("departure-A", routeA, 100), depB = departure("departure-B", routeB, 200);

        TransitStopFacility stop(String id, int x) {
            var stop = schedule.getFactory().createTransitStopFacility(Id.create(id, TransitStopFacility.class), new Coord(x, 0), false);
            stop.setLinkId(Id.createLinkId(id + "-link")); schedule.addStopFacility(stop); return stop;
        }
        TransitLine line(String id) {
            var line = schedule.getFactory().createTransitLine(Id.create(id, TransitLine.class)); schedule.addTransitLine(line); return line;
        }
        TransitRoute route(String id, TransitLine line, TransitStopFacility origin, TransitStopFacility destination) {
            var factory = schedule.getFactory();
            var route = factory.createTransitRoute(Id.create(id, TransitRoute.class),
                    RouteUtils.createLinkNetworkRouteImpl(origin.getLinkId(), destination.getLinkId()),
                    List.of(factory.createTransitRouteStop(origin, 0, 0), factory.createTransitRouteStop(destination, 20, 20)), TransportMode.pt);
            line.addRoute(route); return route;
        }
        Departure departure(String id, TransitRoute route, double time) {
            var departure = schedule.getFactory().createDeparture(Id.create(id, Departure.class), time);
            departure.setVehicleId(Id.create("shared-vehicle", Vehicle.class)); route.addDeparture(departure); return departure;
        }
        ChainedDeparture toA() { return schedule.getFactory().createChainedDeparture(lineA.getId(), routeA.getId(), depA.getId()); }
        ChainedDeparture toB() { return schedule.getFactory().createChainedDeparture(lineB.getId(), routeB.getId(), depB.getId()); }
        DefaultTransitPassengerRoute directA() { return new DefaultTransitPassengerRoute(s0, lineA, routeA, s1); }
        DefaultTransitPassengerRoute directB() { return new DefaultTransitPassengerRoute(s1, lineB, routeB, s2); }
        DefaultTransitPassengerRoute chain() {
            var root = new DefaultTransitPassengerRoute(s0, lineA, routeA, s2, directB());
            root.setEndLinkId(s2.getLinkId());
            return root; // Outer egress may legitimately be the whole leg's final stop.
        }
        Plan plan(String personId, Route... routes) {
            Plan plan = unownedPlan(routes);
            Person person = scenario.getPopulation().getFactory().createPerson(Id.createPersonId(personId));
            person.addPlan(plan); return plan;
        }
        Plan unownedPlan(Route... routes) {
            var factory = scenario.getPopulation().getFactory(); Plan plan = factory.createPlan();
            plan.addActivity(factory.createActivityFromLinkId("origin", s0.getLinkId()));
            for (Route route : routes) {
                Leg leg = factory.createLeg(TransportMode.pt); leg.setRoute(route); plan.addLeg(leg);
                plan.addActivity(factory.createActivityFromLinkId("transfer-or-destination", s1.getLinkId()));
            }
            return plan;
        }
    }
}
