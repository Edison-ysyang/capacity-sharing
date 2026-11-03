package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonContinuesInVehicleEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import static org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;
import static org.matsim.run.ismt.dynamic.ObservedTransitServiceState.*;

/** Pure transitions on explicitly supplied event values; not native prefix certification. */
class ObservedTransitServiceStateTest {
    private static final TripKey A = new TripKey("line", "route", "A");
    private static final TripKey B = new TripKey("line", "route", "B");
    private static final TripKey C = new TripKey("line", "other-route", "C");
    private static final TripKey W = new TripKey("Wenden", "Wenden", "Wenden");
    private static Service service(TripKey key, String vehicle) { return new Service(key, vehicle, List.of("s0", "s1")); }
    private static ObservedTransitServiceState mapper() {
        return new ObservedTransitServiceState(List.of(service(A, "v1"), service(B, "v1"), service(C, "v2")));
    }
    private static ReceivedEvent copy(Event event) {
        return new ReceivedEvent(1, event.getTime(), event.getTime(), event.getClass().getName(),
                event.getEventType(), event.getAttributes(), false);
    }
    private static ReceivedEvent start(double at, TripKey trip, String vehicle, String driver) {
        return copy(new TransitDriverStartsEvent(at, Id.createPersonId(driver), Id.createVehicleId(vehicle),
                Id.create(trip.line(), TransitLine.class), Id.create(trip.route(), TransitRoute.class),
                Id.create(trip.departure(), Departure.class)));
    }
    private static ReceivedEvent arrive(double at, String vehicle, String stop) { return arrive(at, vehicle, stop, 0); }
    private static ReceivedEvent arrive(double at, String vehicle, String stop, double delay) {
        return copy(new VehicleArrivesAtFacilityEvent(at, Id.createVehicleId(vehicle), Id.create(stop, TransitStopFacility.class), delay));
    }
    private static ReceivedEvent depart(double at, String vehicle, String stop) {
        return copy(new VehicleDepartsAtFacilityEvent(at, Id.createVehicleId(vehicle), Id.create(stop, TransitStopFacility.class), 0));
    }
    private static ReceivedEvent end(double at, String driver) {
        return copy(new PersonArrivalEvent(at, Id.createPersonId(driver), Id.createLinkId("end-link"), "pt"));
    }
    private static ReceivedEvent stuck(double at, String driver) {
        return copy(new PersonStuckEvent(at, Id.createPersonId(driver), Id.createLinkId("stuck-link"), "pt"));
    }
    private static List<ReceivedEvent> completed(TripKey trip, String vehicle, String driver, double at) {
        return List.of(start(at, trip, vehicle, driver), arrive(at + 1, vehicle, "s0"), depart(at + 2, vehicle, "s0"),
                arrive(at + 3, vehicle, "s1"), depart(at + 4, vehicle, "s1"), end(at + 5, driver));
    }

    @Test void contractsDoNotExposeUnstartedVehiclesOrServices() {
        var mapper = mapper(); var empty = mapper.initialState();
        assertTrue(empty.observedServices().isEmpty()); assertTrue(empty.latestWorkByVehicle().isEmpty());
        assertFalse(empty.hasObservedDriver("d1"));
        var state = mapper.apply(empty, start(10, A, "v1", "d1"));
        assertEquals(List.of(A), List.copyOf(state.observedServices().keySet()));
        assertEquals(List.of("v1"), List.copyOf(state.latestWorkByVehicle().keySet()));
        assertTrue(state.hasObservedDriver("d1")); assertTrue(empty.observedServices().isEmpty());
    }

    @Test void actualStartArrivalDepartureAndEndHaveSeparateStates() {
        var mapper = mapper(); var state = mapper.apply(mapper.initialState(), start(10, A, "v1", "d1"));
        var started = state.observedServices().get(A);
        assertEquals(Motion.STARTED, started.motion()); assertTrue(started.atStop().isEmpty());
        assertTrue(started.lastObservedStop().isEmpty()); assertTrue(started.lastFacilityDelaySeconds().isEmpty());
        state = mapper.apply(state, arrive(11, "v1", "s0", -3.5));
        var arrived = state.observedServices().get(A);
        assertEquals(Motion.AT_STOP, arrived.motion()); assertEquals(Optional.of("s0"), arrived.atStop());
        assertEquals(0, arrived.nextStopIndex()); assertEquals(-3.5, arrived.lastFacilityDelaySeconds().orElseThrow());
        state = mapper.apply(state, depart(12, "v1", "s0"));
        var left = state.observedServices().get(A);
        assertEquals(Motion.BETWEEN_STOPS, left.motion()); assertTrue(left.atStop().isEmpty());
        assertEquals(Optional.of("s0"), left.lastObservedStop()); assertEquals(1, left.nextStopIndex());
        state = mapper.applyAll(state, List.of(arrive(13, "v1", "s1"), depart(14, "v1", "s1"), end(15, "d1")));
        var ended = state.observedServices().get(A);
        assertEquals(Motion.ENDED, ended.motion()); assertFalse(ended.active());
        assertEquals(2, ended.nextStopIndex()); assertEquals(10, ended.startedAtSeconds());
        assertEquals(15, ended.lastObservedAtSeconds()); assertTrue(ended.atStop().isEmpty());
        assertEquals(Motion.STARTED, started.motion(), "Previously returned values are immutable");
    }

    @Test void repeatedStopIdsUseCallIndexNotUniqueStopSets() {
        var mapper = new ObservedTransitServiceState(List.of(new Service(A, "v1", List.of("s0", "s1", "s0"))));
        var state = mapper.applyAll(mapper.initialState(), List.of(start(10, A, "v1", "d1"),
                arrive(11, "v1", "s0"), depart(12, "v1", "s0"), arrive(13, "v1", "s1"), depart(14, "v1", "s1"),
                arrive(15, "v1", "s0"), depart(16, "v1", "s0"), end(17, "d1")));
        assertEquals(3, state.observedServices().get(A).nextStopIndex());
        assertEquals(Optional.of("s0"), state.observedServices().get(A).lastObservedStop());
    }

    @Test void finalStopDepartureIsNotDriverWorkEnd() {
        var mapper = mapper(); var events = completed(A, "v1", "d1", 10);
        var state = mapper.applyAll(mapper.initialState(), events.subList(0, 5));
        assertTrue(state.observedServices().get(A).active());
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(state, start(15, B, "v1", "d1")));
    }

    @Test void sameVehicleSuccessorNeedsAnObservedNewStart() {
        var mapper = mapper(); var ended = mapper.applyAll(mapper.initialState(), completed(A, "v1", "d1", 10));
        assertFalse(ended.observedServices().containsKey(B));
        var next = mapper.apply(ended, start(15, B, "v1", "d1"));
        assertEquals(Motion.ENDED, next.observedServices().get(A).motion());
        assertEquals(Motion.STARTED, next.observedServices().get(B).motion());
        assertEquals(Optional.of(B), next.latestWorkByVehicle().get("v1").trip());
    }

    @Test void sameSecondOrderedCallbacksAreRetained() {
        var mapper = mapper(); var state = mapper.applyAll(mapper.initialState(), List.of(start(10, A, "v1", "d1"),
                arrive(10, "v1", "s0"), depart(10, "v1", "s0"), arrive(10, "v1", "s1"), depart(10, "v1", "s1"), end(10, "d1")));
        assertEquals(Motion.ENDED, state.observedServices().get(A).motion());
    }

    @Test void passengerArrivalStuckAndContinuationDoNotCreateOrEndTrainWork() {
        var mapper = mapper(); var started = mapper.apply(mapper.initialState(), start(10, A, "v1", "d1"));
        ReceivedEvent continuation = copy(new PersonContinuesInVehicleEvent(11, Id.createPersonId("passenger"),
                Id.createVehicleId("v1"), Id.createVehicleId("v1"), Id.create("line", TransitLine.class), Id.create("route", TransitRoute.class)));
        var unchanged = mapper.applyAll(started, List.of(end(11, "passenger"), stuck(11, "passenger"), continuation));
        assertSame(started, unchanged, "Irrelevant events must not repeatedly copy every observed service");
        assertEquals(started.observedServices(), unchanged.observedServices());
        assertFalse(unchanged.observedServices().containsKey(B));
    }

    @Test void wendenIsObservedNonServiceWorkWithoutInventedTripOrStops() {
        var mapper = mapper(); var state = mapper.apply(mapper.initialState(), start(1, W, "v1", "d1"));
        assertTrue(state.observedServices().isEmpty());
        assertTrue(state.latestWorkByVehicle().get("v1").trip().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(state, arrive(2, "v1", "s0")));
        var ended = mapper.apply(state, end(2, "d1"));
        assertEquals(Motion.ENDED, ended.latestWorkByVehicle().get("v1").motion());
        var service = mapper.apply(ended, start(3, A, "v1", "d1"));
        assertEquals(Optional.of(A), service.latestWorkByVehicle().get("v1").trip());
    }

    @Test void abortIsExplicitAndDoesNotPretendToCompleteStops() {
        var mapper = mapper(); var started = mapper.applyAll(mapper.initialState(), List.of(start(10, A, "v1", "d1"), arrive(11, "v1", "s0")));
        var state = mapper.apply(started, stuck(12, "d1")); var work = state.observedServices().get(A);
        assertEquals(Motion.ABORTED, work.motion()); assertFalse(work.active());
        assertEquals(0, work.nextStopIndex()); assertTrue(work.atStop().isEmpty());
        assertEquals(Optional.of("s0"), work.lastObservedStop());
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(state, depart(13, "v1", "s0")));
    }

    @Test void aServiceCannotStartTwiceEvenAfterItEnded() {
        var mapper = mapper(); var state = mapper.applyAll(mapper.initialState(), completed(A, "v1", "d1", 10));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(state, start(20, A, "v1", "d1")));
    }

    @Test void unknownOrChangedTripVehicleAndPartialWendenAreRejected() {
        var mapper = mapper(); var empty = mapper.initialState();
        for (ReceivedEvent event : List.of(start(1, A, "unknown", "d1"), start(1, A, "v2", "d1"),
                start(1, new TripKey("other", "route", "A"), "v1", "d1"),
                start(1, new TripKey("Wenden", "route", "A"), "v1", "d1"))) {
            assertThrows(IllegalArgumentException.class, () -> mapper.apply(empty, event));
        }
        assertTrue(empty.observedServices().isEmpty());
    }

    @Test void arrivalWithoutStartAndDepartureWithoutArrivalFail() {
        var mapper = mapper(); var empty = mapper.initialState();
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(empty, arrive(10, "v1", "s0")));
        var started = mapper.apply(empty, start(10, A, "v1", "d1"));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(started, depart(11, "v1", "s0")));
    }

    @Test void wrongRepeatedAndSkippedFacilityCallsFail() {
        var mapper = mapper(); var started = mapper.apply(mapper.initialState(), start(10, A, "v1", "d1"));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(started, arrive(11, "v1", "s1")));
        var atStop = mapper.apply(started, arrive(11, "v1", "s0"));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(atStop, arrive(12, "v1", "s0")));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(atStop, depart(12, "v1", "s1")));
        var between = mapper.apply(atStop, depart(12, "v1", "s0"));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(between, depart(13, "v1", "s0")));
    }

    @Test void prematureAndRepeatedDriverEndFail() {
        var mapper = mapper(); var started = mapper.apply(mapper.initialState(), start(10, A, "v1", "d1"));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(started, end(11, "d1")));
        var ended = mapper.applyAll(mapper.initialState(), completed(A, "v1", "d1", 10));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(ended, end(16, "d1")));
    }

    @Test void invalidTailRejectsWholePureBatchAndLeavesEarlierSnapshotUntouched() {
        var mapper = mapper(); var initial = mapper.initialState();
        assertThrows(IllegalArgumentException.class, () -> mapper.applyAll(initial,
                List.of(start(10, A, "v1", "d1"), arrive(11, "v1", "s0"), depart(12, "v1", "s1"))));
        assertTrue(initial.observedServices().isEmpty()); assertTrue(initial.latestWorkByVehicle().isEmpty());
        var valid = mapper.applyAll(initial, completed(A, "v1", "d1", 10));
        assertEquals(Motion.ENDED, valid.observedServices().get(A).motion());
    }

    @Test void oneVehicleAndOneDriverCannotHaveOverlappingActiveWork() {
        var mapper = mapper(); var state = mapper.apply(mapper.initialState(), start(10, A, "v1", "d1"));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(state, start(11, B, "v1", "d2")));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(state, start(11, C, "v2", "d1")));
    }

    @Test void independentVehiclesCanHaveUnsortedOccurrenceTimes() {
        var mapper = mapper(); var state = mapper.applyAll(mapper.initialState(), List.of(start(100, A, "v1", "d1"), start(90, C, "v2", "d2")));
        assertEquals(100, state.observedServices().get(A).startedAtSeconds());
        assertEquals(90, state.observedServices().get(C).startedAtSeconds());
    }

    @Test void sameVehicleAndSameDriverTimeRegressionsAreRejectedIndependently() {
        var mapper = mapper(); var started = mapper.apply(mapper.initialState(), start(10, A, "v1", "d1"));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(started, arrive(9, "v1", "s0")));
        var ended = mapper.applyAll(mapper.initialState(), completed(A, "v1", "d1", 10));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(ended, start(14, B, "v1", "d2")));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(ended, start(14, C, "v2", "d1")));
    }

    @Test void driverHistorySurvivesAnotherDriverTakingOverItsFormerVehicle() {
        var mapper = mapper(); var first = mapper.applyAll(mapper.initialState(), completed(A, "v1", "d1", 95));
        var replaced = mapper.applyAll(first, completed(B, "v1", "d2", 115));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(replaced, start(90, C, "v2", "d1")));
        var moved = mapper.apply(replaced, start(100, C, "v2", "d1"));
        assertEquals("d1", moved.observedServices().get(C).driverId());
        assertEquals("d2", moved.latestWorkByVehicle().get("v1").driverId());
    }

    @Test void privateStateCannotBeTransferredBetweenRegistriesAndPublicMapsAreImmutable() {
        var mapper = mapper(); var other = mapper();
        assertThrows(IllegalArgumentException.class, () -> other.apply(mapper.initialState(), start(10, A, "v1", "d1")));
        assertThrows(IllegalArgumentException.class, () -> other.apply(mapper.initialState(), end(10, "passenger")),
                "No-op optimization must not bypass foreign-state rejection");
        var state = mapper.apply(mapper.initialState(), start(10, A, "v1", "d1"));
        assertThrows(UnsupportedOperationException.class, () -> state.observedServices().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.latestWorkByVehicle().clear());
    }

    @Test void inputsAreDefensivelyCopiedAndContractsValidated() {
        List<String> stops = new ArrayList<>(List.of("s0", "s1"));
        Service service = new Service(A, "v1", stops); stops.clear();
        List<Service> contracts = new ArrayList<>(List.of(service));
        var mapper = new ObservedTransitServiceState(contracts); contracts.clear();
        assertEquals(1, mapper.apply(mapper.initialState(), start(10, A, "v1", "d1")).observedServices().size());
        assertThrows(UnsupportedOperationException.class, () -> service.orderedStopIds().clear());
        assertThrows(IllegalArgumentException.class, () -> new ObservedTransitServiceState(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ObservedTransitServiceState(List.of(service, service)));
        assertThrows(IllegalArgumentException.class, () -> new Service(A, " ", List.of("s0")));
        assertThrows(IllegalArgumentException.class, () -> new Service(A, "v1", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Service(A, "v1", List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> new Service(W, "v1", List.of("s0")));
    }

    @Test void missingAttributesNonFiniteDelayAndUnregisteredProducerFailClosed() {
        var mapper = mapper(); var first = start(10, A, "v1", "d1");
        Map<String, String> missing = new HashMap<>(first.attributes()); missing.remove("driverId");
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mapper.initialState(), new ReceivedEvent(1, 10, 10,
                first.eventClass(), first.eventType(), missing, false)));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mapper.initialState(), new ReceivedEvent(1, 10, 10,
                "unregistered.StartEvent", first.eventType(), first.attributes(), false)));
        var state = mapper.apply(mapper.initialState(), first);
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(state, arrive(11, "v1", "s0", Double.NaN)));
    }
}
