package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.mobsim.qsim.pt.PersonEntersPtVehicleEvent;
import org.matsim.core.mobsim.qsim.pt.PersonLeavesPtVehicleEvent;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.CausalMixedServiceForecast.*;
import static org.matsim.run.ismt.dynamic.ClosedPassengerArrivalForecast.Od;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.RollingDeparturePolicy.ForecastStatus.*;
import static org.matsim.run.ismt.dynamic.MixedCapacityProjection.Completion.*;

/** Concrete finite-capacity predictions from source-bound snapshots; manual callbacks, NEVER RUN QSim. */
class CausalMixedServiceForecastTest {
    private static final TripKey A = new TripKey("line", "rA", "A"), B = new TripKey("line", "rB", "B");
    private static final Od OD = new Od("s0", "s1");
    private static Settings settings() { return new Settings(0, 5, 12, 4, 40, 100, 1000, .5, .5, new Limits(5, 5, 1)); }
    private static Map<Od, AccessEgressEstimate> estimates() { return Map.of(OD, new AccessEgressEstimate(3, 2)); }
    private static SingleLineForecastSupply supply() { return supply(10, 2); }
    private static SingleLineForecastSupply supply(double firstDeparture, int capacity) {
        return supply(firstDeparture, capacity, 5);
    }
    private static SingleLineForecastSupply supply(double firstDeparture, int capacity, int callSpacing) {
        var config = ConfigUtils.createConfig(); config.transit().setUseTransit(true); config.transit().setUsingTransitInMobsim(true);
        var scenario = ScenarioUtils.createScenario(config); var sf = scenario.getTransitSchedule().getFactory();
        var line = sf.createTransitLine(Id.create("line", TransitLine.class)); scenario.getTransitSchedule().addTransitLine(line);
        var facilities = new ArrayList<TransitStopFacility>();
        for (int i = 0; i < 3; i++) {
            var stop = sf.createTransitStopFacility(Id.create("s" + i, TransitStopFacility.class), new Coord(i, 0), false);
            stop.setLinkId(Id.createLinkId("l" + i)); scenario.getTransitSchedule().addStopFacility(stop); facilities.add(stop);
        }
        var type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class));
        type.getCapacity().setSeats(capacity).setStandingRoom(0); type.setAccessTime(.1); type.setEgressTime(.1);
        scenario.getTransitVehicles().addVehicleType(type);
        for (TripKey key : List.of(A, B)) {
            var calls = new ArrayList<TransitRouteStop>();
            for (int i = 0; i < 3; i++) { var call = sf.createTransitRouteStop(facilities.get(i), i * callSpacing, i * callSpacing); call.setAwaitDepartureTime(true); calls.add(call); }
            var route = sf.createTransitRoute(Id.create(key.route(), TransitRoute.class),
                    RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("l0"), Id.createLinkId("l2")), calls, TransportMode.pt);
            var vehicle = VehicleUtils.createVehicle(Id.createVehicleId("v" + key.departure()), type);
            scenario.getTransitVehicles().addVehicle(vehicle);
            var departure = sf.createDeparture(Id.create(key.departure(), Departure.class), key.equals(A) ? firstDeparture : 20);
            departure.setVehicleId(vehicle.getId()); route.addDeparture(departure); line.addRoute(route);
        }
        assertTrue(scenario.getPopulation().getPersons().isEmpty());
        return SingleLineForecastSupply.from(scenario);
    }
    private static Map<TripKey, Double> times(SingleLineForecastSupply supply) {
        var result = new LinkedHashMap<TripKey, Double>(); supply.trips().forEach((key, trip) -> result.put(key, trip.scheduled().baselineDepartureSeconds())); return result;
    }
    private static Map<TripKey, Double> changed(Map<TripKey, Double> original, TripKey key, double at) {
        var result = new LinkedHashMap<>(original); result.put(key, at); return result;
    }
    private static ActivityEndEvent release(String unit, int at) { return new ActivityEndEvent(at, Id.createPersonId(unit), Id.createLinkId("origin"), null, "cargo_origin", null); }
    private static AgentWaitingForPtEvent waiting(String person, int at) { return waiting(person, at, "s0", "s1"); }
    private static AgentWaitingForPtEvent waiting(String person, int at, String origin, String destination) {
        return new AgentWaitingForPtEvent(at, Id.createPersonId(person), Id.create(origin, TransitStopFacility.class), Id.create(destination, TransitStopFacility.class));
    }
    private static TransitDriverStartsEvent start(int at) { return new TransitDriverStartsEvent(at, Id.createPersonId("driverA"), Id.createVehicleId("vA"),
            Id.create("line", TransitLine.class), Id.create("rA", TransitRoute.class), Id.create("A", Departure.class)); }
    private static VehicleArrivesAtFacilityEvent arrival(int at, String stop) { return new VehicleArrivesAtFacilityEvent(at, Id.createVehicleId("vA"), Id.create(stop, TransitStopFacility.class), 9876); }
    private static VehicleDepartsAtFacilityEvent depart(int at, String stop) { return new VehicleDepartsAtFacilityEvent(at, Id.createVehicleId("vA"), Id.create(stop, TransitStopFacility.class), -4321); }
    private static PersonEntersPtVehicleEvent board(String person, int at) { return new PersonEntersPtVehicleEvent(at, Id.createPersonId(person), Id.createVehicleId("vA"), Id.create("line", TransitLine.class), Id.create("rA", TransitRoute.class)); }
    private static PersonLeavesPtVehicleEvent alight(String person, int at) { return new PersonLeavesPtVehicleEvent(at, Id.createPersonId(person), Id.createVehicleId("vA"), Id.create("line", TransitLine.class), Id.create("rA", TransitRoute.class)); }
    private static ActivityStartEvent delivery(int at) { return new ActivityStartEvent(at, Id.createPersonId("f"), Id.createLinkId("delivery"), null, "cargo_delivery:terminal"); }

    private static final class Source implements AutoCloseable {
        final EventsManager events = EventsUtils.createEventsManager();
        final QSim qsim;
        final NativeEventPrefixObserver observer;
        final ClosedCoTransportObservationState state;
        final List<NativeEventPrefixObserver.StepView> views = new ArrayList<>();
        ClosedCoTransportObservationState.Snapshot snapshot;
        int now;
        Source(SingleLineForecastSupply supply) { this(supply, true, 30, "s1", "future-f"); }
        Source(SingleLineForecastSupply supply, boolean journeys, double deadline, String destination, String privateSuffix) {
            this(supply, journeys, deadline, destination, privateSuffix, false);
        }
        Source(SingleLineForecastSupply supply, boolean journeys, double deadline, String destination, String privateSuffix,
               boolean omitReleasedJourneyRegistration) {
            var contracts = List.of(new KnownFreightReleaseState.Contract("known-order", "s0", destination, 0, deadline, List.of("f", privateSuffix), "cargo_origin"),
                    new KnownFreightReleaseState.Contract("private-order", "s0", "s1", 100, 200, List.of("later-f"), "cargo_origin"));
            var services = supply.trips().values().stream().map(trip -> new ObservedTransitServiceState.Service(trip.scheduled().key(),
                    trip.scheduled().vehicleId(), trip.calls().stream().map(SingleLineForecastSupply.Call::stopId).toList())).toList();
            state = new ClosedCoTransportObservationState(20, 1, services, contracts, Set.of("p", "p2", "p3"), Map.of(), journeys
                    ? List.of(new KnownFreightJourneyState.Contract("known-order",
                            omitReleasedJourneyRegistration ? List.of(privateSuffix) : List.of("f", privateSuffix), "cargo_delivery"),
                    new KnownFreightJourneyState.Contract("private-order", List.of("later-f"), "cargo_delivery")) : List.of());
            var config = ConfigUtils.createConfig(); config.qsim().setNumberOfThreads(1); config.global().setNumberOfThreads(1);
            config.qsim().setStartTime(0); config.qsim().setEndTime(50);
            config.controller().setOutputDirectory(Path.of(".build-cache", "causal-forecast-unit-unused").toAbsolutePath().toString());
            qsim = new QSimBuilder(config).useDefaults().build(ScenarioUtils.createScenario(config), events);
            observer = NativeEventPrefixObserver.attach(qsim, 20, 1, 0, views::add); state.bindTo(observer);
            qsim.getSimTimer().setTime(0); observer.notifyMobsimInitialized(new MobsimInitializedEvent<>(qsim)); before(0);
        }
        private void before(int at) { now = at; qsim.getSimTimer().setTime(at); observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(qsim, at)); snapshot = state.accept(views.getLast()); }
        ClosedCoTransportObservationState.Snapshot advance(int target, Map<Integer, List<Event>> emitted) {
            while (now < target) {
                for (Event event : emitted.getOrDefault(now, List.of())) events.processEvent(event);
                observer.notifyMobsimAfterSimStep(new MobsimAfterSimStepEvent<>(qsim, now)); before(now + 1);
            }
            return snapshot;
        }
        @Override public void close() { observer.close(); }
    }
    private static CausalMixedServiceForecast model(SingleLineForecastSupply supply) { return new CausalMixedServiceForecast(settings(), supply, estimates(), ignored -> { }); }
    private static Map<Integer, List<Event>> releasedAndWaitingAtZero() { return Map.of(0, List.of(release("f", 0), waiting("p", 0))); }

    @Test void concreteCapacityForecastDeliversReleasedNotYetWaitingFreightAndRetainsPassengerCost() {
        var supply = supply(); var model = model(supply);
        try (Source source = new Source(supply)) {
            var result = model.project(source.advance(6, releasedAndWaitingAtZero()), times(supply));
            assertEquals(READY, result.status()); var projection = result.projection().orElseThrow();
            assertEquals(Set.of("f"), projection.releasedFreightUnitIds()); assertEquals(10, projection.freight().totalWaitingSeconds());
            assertEquals(0, projection.freight().lateUnits()); assertEquals(1, projection.affectedPassengers().size());
            assertEquals(10, projection.affectedPassengers().values().iterator().next().meanWaitingSeconds());
            var detail = model.lastForecast().orElseThrow(); var unit = detail.result().orElseThrow().freight().get("f");
            assertEquals(10, unit.firstBoardedAtSeconds().orElseThrow()); assertEquals(17, unit.deliveredAtSeconds().orElseThrow());
            assertEquals(DELIVERED_PREDICTED, unit.completion()); assertEquals(0, detail.futureArrivalCohorts());
            assertTrue(detail.approximation().contains("horizon-censored"));
        }
    }

    @Test void positiveTrailingRatesCreateFluidDemandThatCanLeaveFreightUnservedUnderFiniteCapacity() {
        var supply = supply(); var model = model(supply);
        try (Source source = new Source(supply)) {
            var result = model.project(source.advance(6, Map.of(0, List.of(release("f", 0)), 2, List.of(waiting("p", 2)))), times(supply));
            assertEquals(READY, result.status()); var detail = model.lastForecast().orElseThrow();
            assertEquals(6, detail.futureArrivalCohorts()); assertEquals(3, detail.protectedPassengerMass(), 1e-12);
            assertEquals(40, detail.arrivalProjectionEndSeconds()); assertEquals(15, detail.protectedArrivalThroughSeconds().get(OD));
            assertEquals(25, detail.capacityArrivalThroughSeconds().get(OD)); assertEquals(3, detail.omittedUnserviceableTailPassengerMass(), 1e-12);
            var output = detail.result().orElseThrow();
            assertTrue(output.loads().stream().allMatch(load -> load.passengersAfterBoarding() + load.freightAfterBoarding() <= 2));
            assertEquals(UNDELIVERED_AT_HORIZON, output.freight().get("f").completion());
            assertTrue(output.freight().get("f").deliveredAtSeconds().isEmpty());
            assertEquals(1, result.projection().orElseThrow().freight().lateUnits());
            assertEquals(10, result.projection().orElseThrow().freight().totalLatenessSeconds());
            assertEquals(40, result.projection().orElseThrow().freight().totalWaitingSeconds());
        }
    }

    @Test void candidatesShareFrozenMassIdentitiesAndPendingFutureEventsAreNotVisible() {
        var supply = supply(); var model = model(supply); var original = times(supply);
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(6, releasedAndWaitingAtZero());
            var first = model.project(snapshot, original).projection().orElseThrow();
            source.events.processEvent(release("future-f", 6)); source.events.processEvent(waiting("p2", 6));
            var delayed = model.project(snapshot, changed(original, A, 12)).projection().orElseThrow();
            assertEquals(first.demandProjectionId(), delayed.demandProjectionId());
            assertEquals(first.affectedPassengers().keySet(), delayed.affectedPassengers().keySet());
            assertEquals(first.releasedFreightUnitIds(), delayed.releasedFreightUnitIds()); assertEquals(1, model.demandPreparationCount());
            assertEquals(12, delayed.freight().totalWaitingSeconds()); assertFalse(model.lastForecast().orElseThrow().newDemandPreparation());
            var next = model.project(source.advance(7, Map.of()), original).projection().orElseThrow();
            assertEquals(Set.of("f", "future-f"), next.releasedFreightUnitIds()); assertEquals(2, model.demandPreparationCount());
            assertNotEquals(first.demandProjectionId(), next.demandProjectionId());
            assertEquals(UNSUPPORTED_STATE, model.project(snapshot, original).status());
        }
    }

    @Test void frozenProtectedLookupMatchesOriginalSortedAggregationAcrossCandidatesAndNewPrefixes() {
        var supply = supply(); var model = model(supply); var original = times(supply);
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(6, Map.of(0, List.of(release("f", 0)),
                    2, List.of(waiting("p", 2)), 3, List.of(waiting("p2", 3))));
            var first = model.project(snapshot, original).projection().orElseThrow();
            var firstDetail = model.lastForecast().orElseThrow();
            var firstResult = firstDetail.result().orElseThrow();
            assertMatchesOriginalProtectedAggregation(firstDetail);
            assertTrue(firstResult.passengers().size() > first.affectedPassengers().size(),
                    "Unprotected future cohorts still participate in the full capacity projection");
            for (double departure : List.of(8.0, 15.0, 10.0)) {
                var candidate = model.project(snapshot, changed(original, A, departure)).projection().orElseThrow();
                var detail = model.lastForecast().orElseThrow();
                assertMatchesOriginalProtectedAggregation(detail);
                assertEquals(first.demandProjectionId(), candidate.demandProjectionId());
                assertEquals(first.affectedPassengers().keySet(), candidate.affectedPassengers().keySet());
                first.affectedPassengers().forEach((id, value) -> assertRawBits(value.passengers(),
                        candidate.affectedPassengers().get(id).passengers()));
                assertRawBits(firstDetail.protectedPassengerMass(), detail.protectedPassengerMass());
                assertFalse(detail.newDemandPreparation());
                if (departure == 10) assertEquals(firstResult, detail.result().orElseThrow(),
                        "Returning to the original candidate must not inherit another projection's mutable queues");
            }
            assertEquals(1, model.demandPreparationCount());
            var next = model.project(source.advance(7, Map.of(6, List.of(waiting("p3", 6)))), original)
                    .projection().orElseThrow();
            assertMatchesOriginalProtectedAggregation(model.lastForecast().orElseThrow());
            assertNotEquals(first.demandProjectionId(), next.demandProjectionId());
            assertEquals(2, model.demandPreparationCount());
            assertTrue(model.lastForecast().orElseThrow().newDemandPreparation());
            assertTrue(next.affectedPassengers().containsKey("observed:p3"));
        }
    }

    /** The former per-candidate sort/filter, independently applied to the complete public kernel result. */
    private static void assertMatchesOriginalProtectedAggregation(DetailedForecast detail) {
        assertEquals(READY, detail.forecast().status());
        var expected = new LinkedHashMap<String, RollingDeparturePolicy.PassengerCost>();
        detail.result().orElseThrow().passengers().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var value = entry.getValue();
            if (value.protectedCohort()) expected.put(entry.getKey(),
                    new RollingDeparturePolicy.PassengerCost(value.mass(), value.meanWaitingSeconds()));
        });
        var actual = detail.forecast().projection().orElseThrow().affectedPassengers();
        assertEquals(expected, actual); assertEquals(expected.size(), detail.protectedCohorts());
        expected.forEach((id, value) -> {
            assertRawBits(value.passengers(), actual.get(id).passengers());
            assertRawBits(value.meanWaitingSeconds(), actual.get(id).meanWaitingSeconds());
        });
    }

    private static void assertRawBits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    @Test void unreleasedPrivateSuffixNeverChangesPredictionOrDemandProjectionId() {
        var supply = supply(); var left = model(supply); var right = model(supply);
        try (Source a = new Source(supply, true, 30, "s1", "hidden-A"); Source b = new Source(supply, true, 30, "s1", "hidden-B")) {
            var x = left.project(a.advance(6, releasedAndWaitingAtZero()), times(supply));
            var y = right.project(b.advance(6, releasedAndWaitingAtZero()), times(supply));
            assertEquals(x, y); assertEquals(left.lastForecast().orElseThrow().result(), right.lastForecast().orElseThrow().result());
            assertEquals(Set.of("f"), x.projection().orElseThrow().releasedFreightUnitIds());
        }
    }

    @Test void unknownAndIncompleteWarmupDoNotUseAFullWindowDenominator() {
        var supply = supply(); var model = model(supply);
        try (Source source = new Source(supply)) {
            assertEquals(INSUFFICIENT_HISTORY, model.project(source.snapshot, times(supply)).status());
            assertEquals(INSUFFICIENT_HISTORY, model.project(source.advance(5, releasedAndWaitingAtZero()), times(supply)).status());
            assertEquals(READY, model.project(source.advance(6, Map.of()), times(supply)).status());
        }
    }

    @Test void missingJourneyRegistrationAndMissingKnownOdEstimateFailExplicitly() {
        var supply = supply();
        try (Source source = new Source(supply, false, 30, "s1", "future-f")) {
            var model = model(supply); var result = model.project(source.advance(6, releasedAndWaitingAtZero()), times(supply));
            assertEquals(UNSUPPORTED_STATE, result.status()); assertTrue(result.reason().contains("journey"));
            assertEquals(1, model.lastForecast().orElseThrow().knownFreightUnits()); assertTrue(model.lastForecast().orElseThrow().result().isEmpty());
        }
        try (Source source = new Source(supply)) {
            var model = new CausalMixedServiceForecast(settings(), supply, Map.of(), ignored -> { });
            var result = model.project(source.advance(6, releasedAndWaitingAtZero()), times(supply));
            assertEquals(UNSUPPORTED_STATE, result.status()); assertTrue(result.reason().contains("access/egress"));
        }
    }

    @Test void onlyKnownDeadlinesMustLieInsideTheExecutionHorizon() {
        var supply = supply();
        try (Source source = new Source(supply, true, 41, "s1", "future-f")) {
            var result = model(supply).project(source.advance(6, releasedAndWaitingAtZero()), times(supply));
            assertEquals(UNSUPPORTED_STATE, result.status()); assertTrue(result.reason().contains("deadline"));
        }
        // The ordinary fixture also has a still-private order whose deadline is 200.
        try (Source source = new Source(supply)) { assertEquals(READY, model(supply).project(source.advance(6, releasedAndWaitingAtZero()), times(supply)).status()); }
    }

    @Test void accessEstimateCanMissTheFirstTrainWithoutBeingMislabelledAsObservedWaiting() {
        var supply = supply(); var model = new CausalMixedServiceForecast(settings(), supply, Map.of(OD, new AccessEgressEstimate(15, 2)), ignored -> { });
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(6, releasedAndWaitingAtZero()); assertFalse(snapshot.riders().riders().containsKey("f"));
            assertEquals(READY, model.project(snapshot, times(supply)).status());
            var unit = model.lastForecast().orElseThrow().result().orElseThrow().freight().get("f");
            assertEquals(20, unit.firstBoardedAtSeconds().orElseThrow()); assertEquals(27, unit.deliveredAtSeconds().orElseThrow());
        }
    }

    @Test void alightedFreightUsesPendingEgressAndObservedDeliveryStaysFixedAcrossCandidates() {
        var supply = supply(); var model = model(supply);
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(6, Map.of(0, List.of(release("f", 0), start(0), arrival(0, "s0"), waiting("f", 0), board("f", 0), depart(0, "s0")),
                    5, List.of(arrival(5, "s1"), alight("f", 5))));
            assertEquals(READY, model.project(snapshot, times(supply)).status());
            var predicted = model.lastForecast().orElseThrow().result().orElseThrow().freight().get("f");
            assertEquals(7, predicted.deliveredAtSeconds().orElseThrow()); assertEquals(DELIVERED_PREDICTED, predicted.completion());
            snapshot = source.advance(7, Map.of(6, List.of(delivery(6))));
            model.project(snapshot, times(supply)); model.project(snapshot, changed(times(supply), B, 25));
            var observed = model.lastForecast().orElseThrow().result().orElseThrow().freight().get("f");
            assertEquals(6, observed.deliveredAtSeconds().orElseThrow()); assertEquals(0, observed.firstBoardedAtSeconds().orElseThrow());
            assertEquals(DELIVERED_OBSERVED, observed.completion());
        }
    }

    private static Map<Integer, List<Event>> observedLateDelivery() {
        return Map.of(0, List.of(release("f", 0)),
                2, List.of(start(2), arrival(2, "s0"), waiting("f", 2), board("f", 2), depart(2, "s0")),
                7, List.of(arrival(7, "s1"), alight("f", 7)), 8, List.of(delivery(8)));
    }

    @Test void invariantCapabilityRequiresOriginalSuccessfulReferenceAndPreservesNonzeroObservedCosts() {
        var supply = supply(2, 2); var model = model(supply);
        try (Source source = new Source(supply, true, 4, "s1", "future-f")) {
            var snapshot = source.advance(9, observedLateDelivery());
            var reference = model.project(snapshot, times(supply)).projection().orElseThrow();
            assertEquals(new RollingDeparturePolicy.FreightCost(1, 4, 2), reference.freight());
            assertEquals(Optional.of(reference.freight()), model.fixedObservedDeliveredFreightCost(snapshot, reference));
            assertEquals(Set.of("f"), reference.releasedFreightUnitIds());
            assertEquals(2, snapshot.releases().knownOrders().get("known-order").contractedUnits());
            var copied = new RollingDeparturePolicy.Projection(reference.demandProjectionId(), reference.forecastEndSeconds(),
                    reference.releasedFreightUnitIds(), reference.freight(), reference.affectedPassengers());
            assertEquals(reference, copied);
            assertTrue(model.fixedObservedDeliveredFreightCost(snapshot, copied).isEmpty(), "An equal externally copied projection is not the original successful result");
            var candidate = model.project(snapshot, changed(times(supply), B, 21)).projection().orElseThrow();
            assertEquals(reference.freight(), candidate.freight());
            assertTrue(model.fixedObservedDeliveredFreightCost(snapshot, reference).isEmpty());
            assertTrue(model.fixedObservedDeliveredFreightCost(snapshot, candidate).isEmpty(), "A later candidate is not the original no-action reference");
        }
    }

    @Test void observedDeliveryCapabilityIgnoresPrivateSuffixAndIsRevokedByItsActualLaterRelease() {
        var supply = supply(2, 2); var left = model(supply); var right = model(supply);
        try (Source a = new Source(supply, true, 30, "s1", "hidden-A");
             Source b = new Source(supply, true, 30, "s1", "hidden-B")) {
            var x = a.advance(9, observedLateDelivery()); var y = b.advance(9, observedLateDelivery());
            var px = left.project(x, times(supply)).projection().orElseThrow();
            var py = right.project(y, times(supply)).projection().orElseThrow();
            assertEquals(px, py);
            assertEquals(left.fixedObservedDeliveredFreightCost(x, px), right.fixedObservedDeliveredFreightCost(y, py));
            assertTrue(left.fixedObservedDeliveredFreightCost(y, px).isEmpty(), "Even same-prefix content from a foreign observer is not this prepared Snapshot");
            // Receipt at current time 9 is still outside closedThrough=8. No private suffix is consulted.
            a.events.processEvent(release("hidden-A", 9));
            assertEquals(Optional.of(px.freight()), left.fixedObservedDeliveredFreightCost(x, px));
            var next = a.advance(10, Map.of());
            assertTrue(left.fixedObservedDeliveredFreightCost(next, px).isEmpty());
            var later = left.project(next, times(supply)).projection().orElseThrow();
            assertEquals(Set.of("f", "hidden-A"), later.releasedFreightUnitIds());
            assertTrue(left.fixedObservedDeliveredFreightCost(next, later).isEmpty(), "No day-completed latch survives a new actual release");
            assertTrue(left.fixedObservedDeliveredFreightCost(x, px).isEmpty(), "Old source/reference cannot qualify after a new preparation");
        }
    }

    @Test void predictedAlightingAndUnclosedDeliveryAreNotObservedDeliveredEvidence() {
        var supply = supply(2, 2); var model = model(supply);
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(8, observedLateDelivery());
            var reference = model.project(snapshot, times(supply)).projection().orElseThrow();
            assertEquals(KnownFreightJourneyState.Phase.ALIGHTED, snapshot.journeys().journeys().get("f").phase());
            assertEquals(DELIVERED_PREDICTED, model.lastForecast().orElseThrow().result().orElseThrow().freight().get("f").completion());
            source.events.processEvent(delivery(8));
            assertTrue(model.fixedObservedDeliveredFreightCost(snapshot, reference).isEmpty());
            var closed = source.advance(9, Map.of());
            var actual = model.project(closed, times(supply)).projection().orElseThrow();
            assertEquals(Optional.of(actual.freight()), model.fixedObservedDeliveredFreightCost(closed, actual));
        }
    }

    @Test void noRegistrationPartialCoverageUnknownWarmupAndUnsuccessfulForecastCannotCertify() {
        var supply = supply(2, 2);
        RollingDeparturePolicy.Projection successful;
        try (Source valid = new Source(supply)) {
            successful = model(supply).project(valid.advance(9, observedLateDelivery()), times(supply)).projection().orElseThrow();
        }
        for (boolean partial : List.of(false, true)) {
            try (Source source = new Source(supply, partial, 30, "s1", "future-f", partial)) {
                var model = model(supply); var snapshot = source.advance(9, observedLateDelivery());
                assertEquals(UNSUPPORTED_STATE, model.project(snapshot, times(supply)).status());
                assertFalse(snapshot.journeys().allKnownReleasedUnitsCovered());
                assertTrue(model.fixedObservedDeliveredFreightCost(snapshot, successful).isEmpty());
            }
        }
        try (Source source = new Source(supply)) {
            var model = model(supply);
            assertEquals(INSUFFICIENT_HISTORY, model.project(source.snapshot, times(supply)).status());
            assertTrue(model.fixedObservedDeliveredFreightCost(source.snapshot, successful).isEmpty());
            var snapshot = source.advance(9, observedLateDelivery());
            var smallBudget = new Settings(0, 5, 12, 4, 40, 100, 1, .5, .5, new Limits(5, 5, 1));
            var limited = new CausalMixedServiceForecast(smallBudget, supply, estimates(), ignored -> { });
            assertEquals(UNSUPPORTED_STATE, limited.project(snapshot, times(supply)).status());
            assertTrue(limited.fixedObservedDeliveredFreightCost(snapshot, successful).isEmpty());
        }
    }

    @Test void releasedWaitingRidingAndAbortedUnitsNeverQualifyAsObservedDelivered() {
        var supply = supply(2, 2);
        List<Map<Integer, List<Event>>> traces = List.of(
                Map.of(0, List.of(release("f", 0))),
                Map.of(0, List.of(release("f", 0), waiting("f", 0))),
                Map.of(0, List.of(release("f", 0)), 2, List.of(start(2), arrival(2, "s0"), waiting("f", 2), board("f", 2), depart(2, "s0"))),
                Map.of(0, List.of(release("f", 0)), 1, List.of(new PersonStuckEvent(1, Id.createPersonId("f"), Id.createLinkId("walk"), "walk"))));
        for (var trace : traces) try (Source source = new Source(supply)) {
            var model = model(supply); var snapshot = source.advance(6, trace);
            var reference = model.project(snapshot, times(supply)).projection().orElseThrow();
            assertTrue(model.fixedObservedDeliveredFreightCost(snapshot, reference).isEmpty());
        }
    }

    @Test void publicPolicyConcreteCapabilityAndOrdinaryWrapperSelectIdenticallyWithFewerForecasts() {
        var supply = supply(2, 2); var scheduled = supply.trips().values().stream().map(SingleLineForecastSupply.Trip::scheduled).toList();
        var limits = settings().limits();
        var checker = new DispatchConstraintChecker(scheduled, List.of(new DispatchConstraintChecker.ControlPoint(
                new DispatchConstraintChecker.ControlPointKey("toy-origin", "one-direction"),
                List.of(new DispatchConstraintChecker.Call(A, 0), new DispatchConstraintChecker.Call(B, 0)), 1, 40, Optional.empty())), List.of());
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(9, observedLateDelivery());
            for (double secondDeparture : List.of(20.0, 21.0)) {
                List<DetailedForecast> ordinaryCalls = new ArrayList<>(), prunedCalls = new ArrayList<>();
                var ordinaryModel = new CausalMixedServiceForecast(settings(), supply, estimates(), ordinaryCalls::add);
                var prunedModel = new CausalMixedServiceForecast(settings(), supply, estimates(), prunedCalls::add);
                List<RollingDeparturePolicy.Decision> ordinaryDecisions = new ArrayList<>(), prunedDecisions = new ArrayList<>();
                // A lambda deliberately keeps the default-disabled optional capability.
                var ordinary = new RollingDeparturePolicy(new RollingDepartureCandidates(scheduled, limits, 12, 1, 50), checker,
                        ordinaryModel::project, 9, 1, 10, 3, ordinaryDecisions::add);
                var pruned = new RollingDeparturePolicy(new RollingDepartureCandidates(scheduled, limits, 12, 1, 50), checker,
                        prunedModel, 9, 1, 10, 3, prunedDecisions::add);
                var commitments = new Snapshot(20, new Version(1, 0), 9, true,
                        changed(times(supply), B, secondDeparture), Set.of(A), Map.of(A, 2.0));
                assertEquals(ordinary.propose(snapshot, commitments), pruned.propose(snapshot, commitments));
                var x = ordinaryDecisions.getFirst(); var y = prunedDecisions.getFirst();
                assertEquals(x.outcome(), y.outcome()); assertEquals(x.selectedProjection(), y.selectedProjection());
                assertEquals(x.noActionProjection(), y.noActionProjection()); assertEquals(x.improvingSweeps(), y.improvingSweeps());
                assertEquals(x.searchBudgetReached(), y.searchBudgetReached());
                assertTrue(prunedCalls.size() < ordinaryCalls.size());
                assertTrue(y.trials().stream().anyMatch(trial -> trial.outcome() == RollingDeparturePolicy.TrialOutcome.OBSERVED_DELIVERED_DOMINATED));
                assertEquals(secondDeparture == 20 ? Map.of() : Map.of(B, 20.0), y.proposedDepartures());
            }
        }
    }

    @Test void initiallyRidingLoadsUseActualResidualDelayNotCommitmentOrFacilityDelayTwice() {
        var supply = supply(2, 2); var model = model(supply); var noAction = changed(times(supply), A, 4);
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(6, Map.of(0, List.of(release("f", 0)), 4, List.of(start(4), arrival(4, "s0"),
                    waiting("p", 4), waiting("f", 4), board("p", 4), board("f", 4), depart(4, "s0"))));
            assertEquals(READY, model.project(snapshot, noAction).status());
            var output = model.lastForecast().orElseThrow().result().orElseThrow();
            assertEquals(11, output.freight().get("f").deliveredAtSeconds().orElseThrow());
            assertEquals(4, output.freight().get("f").firstBoardedAtSeconds().orElseThrow());
            var loads = output.loads().stream().filter(load -> load.trip().equals(A)).toList();
            assertEquals(1, loads.getFirst().call()); assertEquals(9, loads.getFirst().arrivalSeconds());
            assertEquals(1, model.lastForecast().orElseThrow().initiallyRidingPassengers());
            assertFalse(model.lastForecast().orElseThrow().forecast().projection().orElseThrow().affectedPassengers().containsKey("riding:p"));
            assertEquals(UNSUPPORTED_STATE, model.project(snapshot, changed(noAction, A, 5)).status());
        }
    }

    @Test void endedServiceIsNotReplayedAndAnotherRouteOnSameLineCanServeTheOd() {
        var supply = supply(); var model = model(supply);
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(6, Map.of(0, List.of(release("f", 0), start(0), arrival(0, "s0"), depart(0, "s0")),
                    1, List.of(arrival(1, "s1"), depart(1, "s1")), 2, List.of(arrival(2, "s2"), depart(2, "s2"),
                            new PersonArrivalEvent(2, Id.createPersonId("driverA"), Id.createLinkId("end"), "pt"))));
            assertEquals(READY, model.project(snapshot, times(supply)).status());
            var result = model.lastForecast().orElseThrow().result().orElseThrow();
            assertTrue(result.loads().stream().noneMatch(load -> load.trip().equals(A)));
            assertEquals(20, result.freight().get("f").firstBoardedAtSeconds().orElseThrow());
        }
    }

    @Test void abortedWalkingFreightRemainsExplicitUncompletedRatherThanRequeued() {
        var supply = supply(); var model = model(supply);
        try (Source source = new Source(supply)) {
            var result = model.project(source.advance(6, Map.of(0, List.of(release("f", 0)), 1, List.of(
                    new PersonStuckEvent(1, Id.createPersonId("f"), Id.createLinkId("walk"), "walk")))), times(supply));
            assertEquals(READY, result.status()); var unit = model.lastForecast().orElseThrow().result().orElseThrow().freight().get("f");
            assertEquals(ABORTED_OBSERVED, unit.completion()); assertTrue(unit.firstBoardedAtSeconds().isEmpty());
            assertEquals(1, result.projection().orElseThrow().freight().lateUnits()); assertEquals(10, unit.latenessAccountSeconds());
        }
    }

    @Test void unresolvedAbortDirectOdMismatchAndDeliveryWithoutPtDoNotBecomeReady() {
        var supply = supply();
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(6, Map.of(0, List.of(release("f", 0)), 1, List.of(new PersonStuckEvent(1, Id.createPersonId("p"), null, "pt"))));
            assertEquals(UNSUPPORTED_STATE, model(supply).project(snapshot, times(supply)).status());
        }
        try (Source source = new Source(supply, true, 30, "s2", "future-f")) {
            var model = new CausalMixedServiceForecast(settings(), supply, Map.of(new Od("s0", "s2"), new AccessEgressEstimate(0, 0)), ignored -> { });
            var snapshot = source.advance(6, Map.of(0, List.of(release("f", 0), waiting("f", 0))));
            assertEquals(UNSUPPORTED_STATE, model.project(snapshot, times(supply)).status());
        }
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(6, Map.of(0, List.of(release("f", 0), delivery(0))));
            assertEquals(UNSUPPORTED_STATE, model(supply).project(snapshot, times(supply)).status());
        }
    }

    @Test void computationalBudgetsFailClosedInsteadOfTruncatingDemandOrStopEvents() {
        var supply = supply();
        for (boolean cohort : List.of(true, false)) {
            var settings = new Settings(0, 5, 12, 4, 40, cohort ? 1 : 100, cohort ? 1000 : 1, .5, .5, new Limits(5, 5, 1));
            var model = new CausalMixedServiceForecast(settings, supply, estimates(), ignored -> { });
            try (Source source = new Source(supply)) {
                var result = model.project(source.advance(6, Map.of(0, List.of(release("f", 0)), 2, List.of(waiting("p", 2)))), times(supply));
                assertEquals(UNSUPPORTED_STATE, result.status()); assertTrue(result.reason().contains("budget"));
                assertTrue(model.lastForecast().orElseThrow().result().isEmpty());
            }
        }
    }

    @Test void exactTimetableKeysetBaselineBoundsAndCurrentLocksAreCheckedWithoutChangingFrozenDemand() {
        var supply = supply(); var model = model(supply);
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(10, releasedAndWaitingAtZero()); var original = times(supply);
            assertEquals(READY, model.project(snapshot, original).status());
            assertEquals(UNSUPPORTED_STATE, model.project(snapshot, Map.of(A, 10.0)).status());
            assertEquals(UNSUPPORTED_STATE, model.project(snapshot, changed(original, B, 26)).status());
            assertEquals(UNSUPPORTED_STATE, model.project(snapshot, changed(original, A, 11)).status());
            assertEquals(READY, model.project(snapshot, original).status()); assertEquals(1, model.demandPreparationCount());
        }
    }

    @Test void passengerProtectionDoesNotIncludeUnrelatedAllPopulationOrInitiallyRidingMass() {
        var supply = supply();
        var shortLookahead = new Settings(0, 5, 2, 1, 40, 100, 1000, .5, .5, new Limits(5, 5, 1));
        var model = new CausalMixedServiceForecast(shortLookahead, supply, estimates(), ignored -> { });
        try (Source source = new Source(supply)) {
            var result = model.project(source.advance(6, releasedAndWaitingAtZero()), times(supply));
            assertEquals(READY, result.status()); assertTrue(result.projection().orElseThrow().affectedPassengers().isEmpty());
            assertFalse(model.lastForecast().orElseThrow().hasProtectedPassengerCohort());
            assertEquals(1, model.lastForecast().orElseThrow().currentWaitingPassengers());
            assertEquals(1, model.lastForecast().orElseThrow().result().orElseThrow().passengers().size());
            assertMatchesOriginalProtectedAggregation(model.lastForecast().orElseThrow());
        }
    }

    @Test void downstreamArrivalsBeyondOriginLookaheadStayProtectedButUnserviceableTailDoesNotDiluteMean() {
        var supply = supply(10, 2, 30);
        var settings = new Settings(0, 5, 12, 4, 80, 100, 1000, .5, 1, new Limits(5, 5, 1));
        var model = new CausalMixedServiceForecast(settings, supply, estimates(), ignored -> { });
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(6, Map.of(0, List.of(release("f", 0)), 2, List.of(waiting("p", 2, "s1", "s2"))));
            var original = model.project(snapshot, times(supply)).projection().orElseThrow();
            var detail = model.lastForecast().orElseThrow(); Od downstream = new Od("s1", "s2");
            assertEquals(45, detail.protectedArrivalThroughSeconds().get(downstream));
            assertEquals(55, detail.capacityArrivalThroughSeconds().get(downstream));
            assertEquals(5, detail.omittedUnserviceableTailPassengerMass(), 1e-12);
            assertEquals(9, detail.protectedPassengerMass(), 1e-12);
            // This fixed cohort arrives at 23, beyond decision(6)+origin lookahead(12)=18.
            assertTrue(detail.result().orElseThrow().passengers().get("forecast:2:s1:2:s2:4:0").protectedCohort());
            // Arrivals at 47 remain in capacity propagation, but outside every adjustable A boarding opportunity.
            assertFalse(detail.result().orElseThrow().passengers().get("forecast:2:s1:2:s2:10:0").protectedCohort());
            assertMatchesOriginalProtectedAggregation(detail);
            var delayed = model.project(snapshot, changed(times(supply), A, 15)).projection().orElseThrow();
            assertMatchesOriginalProtectedAggregation(model.lastForecast().orElseThrow());
            assertEquals(original.affectedPassengers().keySet(), delayed.affectedPassengers().keySet());
            assertEquals(original.demandProjectionId(), delayed.demandProjectionId());
            double increment = original.affectedPassengers().entrySet().stream().mapToDouble(entry -> entry.getValue().passengers()
                    * (delayed.affectedPassengers().get(entry.getKey()).meanWaitingSeconds() - entry.getValue().meanWaitingSeconds())).sum();
            assertTrue(increment > 0, "A downstream passenger cost changes when the originating train is delayed");
        }
    }

    @Test void tailPruningKeepsAnOpportunityWhenLatestShiftIsPastHorizonButAnEarlierShiftCanBoard() {
        var supply = supply(); var settings = new Settings(0, 5, 12, 4, 22, 100, 1000, .5, 1, new Limits(5, 5, 1));
        var model = new CausalMixedServiceForecast(settings, supply, estimates(), ignored -> { });
        try (Source source = new Source(supply, true, 21, "s1", "future-f")) {
            assertEquals(READY, model.project(source.advance(6, Map.of(0, List.of(release("f", 0)), 2, List.of(waiting("p", 2)))), times(supply)).status());
            var detail = model.lastForecast().orElseThrow();
            assertEquals(22, detail.capacityArrivalThroughSeconds().get(OD));
            assertEquals(0, detail.omittedUnserviceableTailPassengerMass());
            assertTrue(detail.futureArrivalCohorts() > 0);
        }
    }

    @Test void windowedForecastBindsAdmissionAndAdjustmentEdgesAndRetainsPostCutoffDelivery() {
        var supply = supply();
        var window = new org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SharedBoardingWindow(10, 15);
        var settings = new Settings(0, 5, 12, 4, 40, 100, 1000, .5, .5, new Limits(5, 5, 1), window);
        var model = new CausalMixedServiceForecast(settings, supply, estimates(), ignored -> { });
        try (Source source = new Source(supply)) {
            var snapshot = source.advance(6, releasedAndWaitingAtZero());
            assertEquals(READY, model.project(snapshot, times(supply)).status());
            var detail = model.lastForecast().orElseThrow();
            assertEquals(WINDOWED_APPROXIMATION, detail.approximation());
            var unit = detail.result().orElseThrow().freight().get("f");
            assertEquals(10, unit.firstBoardedAtSeconds().orElseThrow());
            assertEquals(17, unit.deliveredAtSeconds().orElseThrow());
            assertEquals(DELIVERED_PREDICTED, unit.completion());
            assertEquals(Math.nextDown(15.0), detail.protectedArrivalThroughSeconds().get(OD));
            assertEquals(20, detail.capacityArrivalThroughSeconds().get(OD));
            assertEquals(UNSUPPORTED_STATE, model.project(snapshot, changed(times(supply), A, 9)).status());
            assertEquals(UNSUPPORTED_STATE, model.project(snapshot, changed(times(supply), A, 15)).status());
            assertEquals(UNSUPPORTED_STATE, model.project(snapshot, changed(times(supply), B, 19)).status());
            assertEquals(READY, model.project(snapshot, changed(times(supply), A, 14)).status());
        }
    }

    @Test void settingsStaticOdAndAuditValuesAreExplicitImmutableAndHaveNoPrivateRegistryInput() {
        var supply = supply();
        assertThrows(IllegalArgumentException.class, () -> new Settings(0, 0, 12, 4, 40, 1, 1, .5, .5, new Limits(1, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new Settings(0, 5, 12, Double.NaN, 40, 1, 1, .5, .5, new Limits(1, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new AccessEgressEstimate(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CausalMixedServiceForecast(settings(), supply,
                Map.of(new Od("s1", "s0"), new AccessEgressEstimate(1, 1)), ignored -> { }));
        var audit = new ArrayList<DetailedForecast>(); var mutable = new LinkedHashMap<>(estimates());
        var model = new CausalMixedServiceForecast(settings(), supply, mutable, audit::add); mutable.clear();
        try (Source source = new Source(supply)) {
            assertEquals(READY, model.project(source.advance(6, releasedAndWaitingAtZero()), times(supply)).status());
            assertEquals(1, audit.size()); assertSame(audit.getFirst(), model.lastForecast().orElseThrow());
            assertThrows(UnsupportedOperationException.class, () -> audit.getFirst().completionCounts().clear());
            assertThrows(UnsupportedOperationException.class, () -> audit.getFirst().result().orElseThrow().freight().clear());
        }
        var constructor = CausalMixedServiceForecast.class.getConstructors()[0];
        assertEquals(List.of(Settings.class, SingleLineForecastSupply.class, Map.class, java.util.function.Consumer.class), List.of(constructor.getParameterTypes()));
    }
}
