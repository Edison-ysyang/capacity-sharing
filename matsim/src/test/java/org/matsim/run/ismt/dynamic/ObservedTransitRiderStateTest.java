package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.mobsim.qsim.pt.PersonEntersPtVehicleEvent;
import org.matsim.core.mobsim.qsim.pt.PersonLeavesPtVehicleEvent;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingDeniedDetailEvent;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import static org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;
import static org.matsim.run.ismt.dynamic.ObservedTransitRiderState.*;
import static org.matsim.run.ismt.dynamic.ObservedTransitServiceState.Service;

/** Synthetic copies of real native event attributes, not a QSim/prefix-source certification. */
class ObservedTransitRiderStateTest {
    private static final TripKey A = new TripKey("old-line", "old-route", "A");
    private static final TripKey B = new TripKey("next-line", "next-route", "B");
    private static final TripKey E = new TripKey("next-line", "next-route", "E");
    private static final TripKey C = new TripKey("other-line", "other-route", "C");
    private static final TripKey W = new TripKey("Wenden", "Wenden", "Wenden");
    private static List<Service> services() {
        return List.of(new Service(A, "v1", List.of("s0", "s1")), new Service(B, "v1", List.of("s1", "s2")),
                new Service(E, "v1", List.of("s1", "s2")), new Service(C, "v2", List.of("s1", "s2")));
    }
    private static Map<TripKey, List<TripKey>> chains() { return Map.of(A, List.of(B, E, C)); }

    private static final class Fixture {
        final ObservedTransitServiceState service = new ObservedTransitServiceState(services());
        final KnownFreightReleaseState releases = new KnownFreightReleaseState(List.of(new KnownFreightReleaseState.Contract(
                "order", "s0", "s2", 0, 100, List.of("f1", "f2"), "freight_origin")));
        final ObservedTransitRiderState mapper = new ObservedTransitRiderState(services(), Set.of("p", "q"), Set.of("f1", "f2"), chains());
        ObservedTransitServiceState.State serviceState = service.initialState();
        KnownFreightReleaseState.State releaseState = releases.initialState();
        State state = mapper.initialState();
        long sequence;
        ReceivedEvent send(Event nativeEvent) {
            var event = copy(nativeEvent, ++sequence);
            send(event); return event;
        }
        void send(ReceivedEvent event) {
            var nextService = service.apply(serviceState, event);
            var nextRelease = releases.apply(releaseState, event);
            var nextRiders = mapper.apply(state, event, serviceState, nextService, nextRelease);
            serviceState = nextService; releaseState = nextRelease; state = nextRiders;
        }
        RiderObservation person(String id) { return state.riders().get(id); }
        void boardA(String person, String destination) {
            send(waiting(1, person, "s0", destination)); send(start(2, A, "v1", "driver1"));
            send(arrive(3, "v1", "s0")); send(enter(4, person, "v1", A));
        }
        void endA() {
            send(depart(5, "v1", "s0")); send(arrive(6, "v1", "s1"));
            send(depart(7, "v1", "s1")); send(end(8, "driver1", "car"));
        }
    }

    private static ReceivedEvent copy(Event event, long sequence) {
        return new ReceivedEvent(sequence, event.getTime(), event.getTime(), event.getClass().getName(),
                event.getEventType(), event.getAttributes(), false);
    }
    private static TransitDriverStartsEvent start(double t, TripKey trip, String vehicle, String driver) {
        return new TransitDriverStartsEvent(t, Id.createPersonId(driver), Id.createVehicleId(vehicle),
                Id.create(trip.line(), TransitLine.class), Id.create(trip.route(), TransitRoute.class), Id.create(trip.departure(), Departure.class));
    }
    private static AgentWaitingForPtEvent waiting(double t, String person, String from, String to) {
        return new AgentWaitingForPtEvent(t, Id.createPersonId(person), Id.create(from, TransitStopFacility.class), Id.create(to, TransitStopFacility.class));
    }
    private static VehicleArrivesAtFacilityEvent arrive(double t, String vehicle, String stop) {
        return new VehicleArrivesAtFacilityEvent(t, Id.createVehicleId(vehicle), Id.create(stop, TransitStopFacility.class), 0);
    }
    private static VehicleDepartsAtFacilityEvent depart(double t, String vehicle, String stop) {
        return new VehicleDepartsAtFacilityEvent(t, Id.createVehicleId(vehicle), Id.create(stop, TransitStopFacility.class), 0);
    }
    private static PersonEntersPtVehicleEvent enter(double t, String person, String vehicle, TripKey trip) {
        return new PersonEntersPtVehicleEvent(t, Id.createPersonId(person), Id.createVehicleId(vehicle),
                Id.create(trip.line(), TransitLine.class), Id.create(trip.route(), TransitRoute.class));
    }
    private static PersonLeavesPtVehicleEvent leave(double t, String person, String vehicle, TripKey trip) {
        return new PersonLeavesPtVehicleEvent(t, Id.createPersonId(person), Id.createVehicleId(vehicle),
                Id.create(trip.line(), TransitLine.class), Id.create(trip.route(), TransitRoute.class));
    }
    private static PersonArrivalEvent end(double t, String person, String mode) {
        return new PersonArrivalEvent(t, Id.createPersonId(person), Id.createLinkId("end-link"), mode);
    }
    private static PersonStuckEvent abort(double t, String person, String mode) {
        return new PersonStuckEvent(t, Id.createPersonId(person), Id.createLinkId("abort-link"), mode);
    }
    private static PersonContinuesInVehicleEvent continued(double t, String person, String from, String target, String stop, TripKey reported) {
        return new PersonContinuesInVehicleEvent(t, Id.createPersonId(person), Id.createVehicleId(from), Id.createVehicleId(target),
                stop == null ? null : Id.create(stop, TransitStopFacility.class), Id.create(reported.line(), TransitLine.class), Id.create(reported.route(), TransitRoute.class));
    }
    private static BoardingDeniedEvent denied(double t, String person, String vehicle) {
        return new BoardingDeniedEvent(t, Id.createPersonId(person), Id.createVehicleId(vehicle));
    }
    private static TransitBoardingDeniedDetailEvent detail(double t, String person) {
        return new TransitBoardingDeniedDetailEvent(t, Id.createPersonId(person), Id.createVehicleId("v1"),
                Id.create(A.line(), TransitLine.class), Id.create(A.route(), TransitRoute.class), Id.create(A.departure(), Departure.class),
                Id.create("s0", TransitStopFacility.class), TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY,
                new TransitBoardingPolicy.DenialDetails(TransitBoardingPolicy.ActorClass.PASSENGER, 1, 0, 1, Double.NaN, Double.NaN));
    }
    private static ActivityEndEvent release(double t, String person) {
        return new ActivityEndEvent(t, Id.createPersonId(person), null, null, "freight_origin", null);
    }

    @Test void privateClassificationsAndTimetableDoNotPopulateThePublicState() {
        var f = new Fixture();
        assertTrue(f.state.riders().isEmpty()); assertTrue(f.state.eventAccountedOccupantsByVehicle().isEmpty());
        f.send(release(0, "f1"));
        assertTrue(f.state.riders().isEmpty(), "Released freight has not yet arrived at a PT queue");
    }

    @Test void waitingCanPrecedeOrFollowTheVehiclesActualArrival() {
        var before = new Fixture(); before.boardA("p", "s1");
        var after = new Fixture();
        after.send(start(2, A, "v1", "driver1")); after.send(arrive(3, "v1", "s0"));
        var waiting = after.send(waiting(4, "p", "s0", "s1"));
        assertEquals("p", waiting.attributes().get("person")); assertEquals("p", waiting.attributes().get("agent"));
        assertEquals("s0", waiting.attributes().get("atStop"));
        after.send(enter(5, "p", "v1", A));
        assertEquals(Phase.RIDING, before.person("p").phase()); assertEquals(Phase.RIDING, after.person("p").phase());
        assertEquals(Map.of("v1", Set.of("p")), after.state.eventAccountedOccupantsByVehicle());
    }

    @Test void eachFreightUnitMustActuallyReleaseBeforeItCanWait() {
        var f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> f.send(waiting(1, "f1", "s0", "s2")));
        f.send(release(1, "f1")); f.send(waiting(2, "f1", "s0", "s2"));
        assertEquals(Kind.FREIGHT_UNIT, f.person("f1").kind());
        assertEquals(2, f.releaseState.knownOrders().get("order").contractedUnits());
        assertThrows(IllegalArgumentException.class, () -> f.send(waiting(2, "f2", "s0", "s2")));
        f.send(release(4, "f2"));
        assertThrows(IllegalArgumentException.class, () -> f.send(waiting(3, "f2", "s0", "s2")));
        f.send(waiting(5, "f2", "s0", "s2"));
        assertTrue(f.state.eventAccountedOccupantsByVehicle().isEmpty());
    }

    @Test void unknownPtActorsStopsAndVehiclesFailWithoutChangingState() {
        var f = new Fixture(); var initial = f.state;
        assertThrows(IllegalArgumentException.class, () -> f.send(waiting(1, "unknown", "s0", "s1")));
        assertThrows(IllegalArgumentException.class, () -> f.send(waiting(1, "p", "unknown", "s1")));
        assertThrows(IllegalArgumentException.class, () -> f.send(waiting(1, "p", "s0", "unknown")));
        assertSame(initial, f.state);
        f.send(waiting(1, "p", "s0", "s1"));
        assertThrows(IllegalArgumentException.class, () -> f.send(enter(2, "p", "unknown", A)));
        assertEquals(Phase.WAITING, f.person("p").phase());
    }

    @Test void boardingRequiresObservedWaitingAndAnActualMatchingStopCall() {
        var f = new Fixture();
        f.send(waiting(1, "p", "s0", "s1")); f.send(start(2, A, "v1", "driver1"));
        assertThrows(IllegalArgumentException.class, () -> f.send(enter(3, "p", "v1", A)));
        f.send(arrive(3, "v1", "s0"));
        assertThrows(IllegalArgumentException.class, () -> f.send(enter(4, "q", "v1", A)));
        assertThrows(IllegalArgumentException.class, () -> f.send(enter(4, "p", "v1", B)));
        f.send(enter(4, "p", "v1", A));
        assertThrows(IllegalArgumentException.class, () -> f.send(enter(4, "p", "v1", A)));
        assertThrows(IllegalArgumentException.class, () -> f.send(waiting(5, "p", "s0", "s1")));
    }

    @Test void normalAlightingAndASecondPtLegDoNotMeanOrderCompletion() {
        var f = new Fixture(); f.boardA("p", "s1");
        f.send(depart(5, "v1", "s0")); f.send(arrive(6, "v1", "s1")); f.send(leave(6, "p", "v1", A));
        assertEquals(Phase.ALIGHTED, f.person("p").phase()); assertTrue(f.state.eventAccountedOccupantsByVehicle().isEmpty());
        f.send(end(6, "p", "pt")); f.send(depart(7, "v1", "s1")); f.send(end(8, "driver1", "car"));
        f.send(waiting(9, "p", "s1", "s2")); f.send(start(10, B, "v1", "driver1"));
        f.send(arrive(11, "v1", "s1")); f.send(enter(12, "p", "v1", B));
        assertEquals(Optional.of(B), f.person("p").trip()); assertEquals(Optional.of("s2"), f.person("p").destinationStopId());
    }

    @Test void alightingCannotBeFabricatedFromArrivalOrAWrongVehicleRouteOrDestination() {
        var f = new Fixture(); f.boardA("p", "s2");
        assertThrows(IllegalArgumentException.class, () -> f.send(end(4, "p", "pt")));
        assertThrows(IllegalArgumentException.class, () -> f.send(leave(4, "p", "v1", B)));
        f.send(depart(5, "v1", "s0")); f.send(arrive(6, "v1", "s1"));
        assertThrows(IllegalArgumentException.class, () -> f.send(leave(6, "p", "v1", A)));
        assertEquals(Phase.RIDING, f.person("p").phase());
    }

    @Test void nativeDenialCountsOnceAndDiagnosticDetailsDoNotDependOnAdjacencyOrOrder() {
        var f = new Fixture(); f.send(waiting(1, "p", "s0", "s1"));
        f.send(start(2, A, "v1", "driver1")); f.send(arrive(3, "v1", "s0"));
        f.send(detail(4, "p")); assertEquals(0, f.person("p").nativeDeniedCount());
        var denial = f.send(denied(4, "p", "v1"));
        assertEquals(1, f.person("p").nativeDeniedCount()); assertEquals(Phase.WAITING, f.person("p").phase());
        assertEquals(new DenialObservation(denial.closedSequence(), A, "v1", "s0", 4), f.person("p").nativeDenials().getFirst());
        f.send(enter(5, "p", "v1", A)); f.send(detail(4, "p"));
        assertEquals(1, f.person("p").nativeDeniedCount()); assertEquals(5, f.person("p").lastObservedAtSeconds());
    }

    @Test void delayedNativeDenialDoesNotUndoASuccessfulBoarding() {
        var f = new Fixture(); f.boardA("p", "s1");
        f.send(denied(5, "p", "v1"));
        assertEquals(1, f.person("p").nativeDeniedCount()); assertEquals(Phase.RIDING, f.person("p").phase());
        assertEquals(Optional.of(A), f.person("p").trip());
        assertEquals(Map.of("v1", Set.of("p")), f.state.eventAccountedOccupantsByVehicle());
    }

    @Test void duplicateNativeDenialAndDenialWithoutOverlappingWaitAreRejected() {
        var f = new Fixture(); f.send(waiting(1, "p", "s0", "s1"));
        f.send(start(2, A, "v1", "driver1")); f.send(arrive(3, "v1", "s0"));
        var denial = f.send(denied(4, "p", "v1")); var once = f.state;
        assertThrows(IllegalArgumentException.class, () -> f.send(denial)); assertSame(once, f.state);
        f.send(waiting(4, "q", "s1", "s2"));
        assertThrows(IllegalArgumentException.class, () -> f.send(denied(5, "q", "v1")));
        assertEquals(0, f.person("q").nativeDeniedCount());
    }

    @Test void nativeSameTimeEnterBeforeLeaveOrderIsNotRewrittenIntoACapacityAllocation() {
        var f = new Fixture(); f.boardA("p", "s1"); f.send(waiting(4, "q", "s1", "s2"));
        f.send(depart(5, "v1", "s0")); f.send(arrive(6, "v1", "s1"));
        f.send(enter(6, "q", "v1", A));
        assertEquals(Set.of("p", "q"), f.state.eventAccountedOccupantsByVehicle().get("v1"));
        f.send(leave(6, "p", "v1", A));
        assertEquals(Set.of("q"), f.state.eventAccountedOccupantsByVehicle().get("v1"));
    }

    @Test void terminalFacilityDepartureAndDriverArrivalDoNotClearRiders() {
        var f = new Fixture(); f.boardA("p", "s2"); f.endA();
        assertEquals(Phase.AWAITING_CONTINUATION_EVENT, f.person("p").phase());
        assertEquals(Optional.of(A), f.person("p").trip());
        assertEquals(Map.of("v1", Set.of("p")), f.state.eventAccountedOccupantsByVehicle());
    }

    @Test void sameVehicleContinuationWithOldRouteRemainsAmbiguousUntilAnActualSuccessorStart() {
        var f = new Fixture(); f.boardA("p", "s2"); f.endA();
        var event = f.send(continued(8, "p", "v1", "v1", "s1", A));
        assertFalse(event.attributes().containsKey("departure"));
        assertEquals(Phase.PENDING_CONTINUATION, f.person("p").phase()); assertTrue(f.person("p").trip().isEmpty());
        assertEquals(Set.of(B, E), f.person("p").pendingContinuation().orElseThrow().candidates());
        assertEquals(Map.of("v1", Set.of("p")), f.state.eventAccountedOccupantsByVehicle());
        f.send(start(10, E, "v1", "driver1"));
        assertEquals(Phase.RIDING, f.person("p").phase()); assertEquals(Optional.of(E), f.person("p").trip());
        assertTrue(f.person("p").pendingContinuation().isEmpty());
    }

    @Test void crossVehicleContinuationTransfersMembershipButDoesNotInventABoardingOrStart() {
        var f = new Fixture(); f.boardA("p", "s2"); f.endA();
        f.send(continued(8, "p", "v1", "v2", "s1", C));
        assertEquals(Phase.PENDING_CONTINUATION, f.person("p").phase());
        assertEquals(Map.of("v2", Set.of("p")), f.state.eventAccountedOccupantsByVehicle());
        assertFalse(f.serviceState.observedServices().containsKey(C));
        f.send(start(10, C, "v2", "driver2")); f.send(arrive(11, "v2", "s1"));
        f.send(depart(12, "v2", "s1")); f.send(arrive(13, "v2", "s2")); f.send(leave(13, "p", "v2", C));
        assertEquals(Phase.ALIGHTED, f.person("p").phase()); assertTrue(f.state.eventAccountedOccupantsByVehicle().isEmpty());
    }

    @Test void anAlreadyObservedSuccessorAtItsOriginCanResolveAContinuation() {
        var f = new Fixture(); f.boardA("p", "s2"); f.endA();
        f.send(start(9, C, "v2", "driver2")); f.send(arrive(10, "v2", "s1"));
        f.send(continued(10, "p", "v1", "v2", "s1", C));
        assertEquals(Phase.RIDING, f.person("p").phase()); assertEquals(Optional.of(C), f.person("p").trip());
    }

    @Test void newServiceStartWithoutAContinuationEventDoesNotReassignRetainedRiders() {
        var f = new Fixture(); f.boardA("p", "s2"); f.endA();
        f.send(start(9, B, "v1", "driver1"));
        assertEquals(Phase.AWAITING_CONTINUATION_EVENT, f.person("p").phase()); assertEquals(Optional.of(A), f.person("p").trip());
        f.send(continued(10, "p", "v1", "v1", "s1", A));
        assertEquals(Optional.of(B), f.person("p").trip());
    }

    @Test void wrongOrMissingContinuationContractsFieldsAndDuplicateContinuationsFailClosed() {
        var f = new Fixture(); f.boardA("p", "s2");
        assertThrows(IllegalArgumentException.class, () -> f.send(continued(4, "p", "v1", "v2", "s1", C)));
        f.endA(); var retained = f.state;
        for (var invalid : List.of(continued(8, "p", "v2", "v1", "s1", A),
                continued(8, "p", "v1", "v2", "s0", C), continued(8, "p", "v1", "v2", null, C),
                continued(8, "p", "v1", "v2", "s1", A))) {
            assertThrows(IllegalArgumentException.class, () -> f.send(invalid)); assertSame(retained, f.state);
        }
        f.send(continued(8, "p", "v1", "v2", "s1", C));
        assertThrows(IllegalArgumentException.class, () -> f.send(continued(8, "p", "v1", "v2", "s1", C)));
    }

    @Test void aTargetWhichAlreadyLeftItsOriginCannotBeGuessedAsAContinuation() {
        var f = new Fixture(); f.boardA("p", "s2"); f.endA();
        f.send(start(9, C, "v2", "driver2")); f.send(arrive(10, "v2", "s1")); f.send(depart(11, "v2", "s1"));
        assertThrows(IllegalArgumentException.class, () -> f.send(continued(12, "p", "v1", "v2", "s1", C)));
        assertEquals(Phase.AWAITING_CONTINUATION_EVENT, f.person("p").phase());
    }

    @Test void wendenDoesNotResolvePendingRidersButActualLaterServiceDoes() {
        var f = new Fixture(); f.boardA("p", "s2"); f.endA(); f.send(continued(8, "p", "v1", "v1", "s1", A));
        f.send(start(9, W, "v1", "driver1"));
        assertEquals(Phase.PENDING_CONTINUATION, f.person("p").phase()); assertTrue(f.person("p").trip().isEmpty());
        f.send(end(10, "driver1", "car")); f.send(start(11, B, "v1", "driver1"));
        assertEquals(Optional.of(B), f.person("p").trip());
    }

    @Test void sourceDriverAbortRetainsUnresolvedMembershipAndCannotBecomeCompletedOrContinue() {
        var f = new Fixture(); f.boardA("p", "s2"); f.send(abort(5, "driver1", "car"));
        assertEquals(Phase.UNRESOLVED_ABORT, f.person("p").phase());
        assertEquals(Map.of("v1", Set.of("p")), f.state.eventAccountedOccupantsByVehicle());
        f.send(start(6, B, "v1", "driver1"));
        assertEquals(Phase.UNRESOLVED_ABORT, f.person("p").phase()); assertEquals(Optional.of(A), f.person("p").trip());
        assertThrows(IllegalArgumentException.class, () -> f.send(continued(7, "p", "v1", "v1", "s1", A)));
    }

    @Test void pendingTargetWendenAbortIsExplicitlyUnresolvedAndNotResurrected() {
        var f = new Fixture(); f.boardA("p", "s2"); f.endA(); f.send(continued(8, "p", "v1", "v1", "s1", A));
        f.send(start(9, W, "v1", "driver1")); f.send(abort(10, "driver1", "car"));
        assertEquals(Phase.UNRESOLVED_ABORT, f.person("p").phase()); assertTrue(f.person("p").pendingContinuation().isPresent());
        f.send(start(11, B, "v1", "driver1")); assertEquals(Phase.UNRESOLVED_ABORT, f.person("p").phase());
    }

    @Test void riderAbortStopsWaitingOrRetainsUnknownOnboardDispositionWithoutFakeAlighting() {
        var waiting = new Fixture(); waiting.send(waiting(1, "p", "s0", "s1")); waiting.send(abort(2, "p", "pt"));
        assertEquals(Phase.UNRESOLVED_ABORT, waiting.person("p").phase()); assertTrue(waiting.state.eventAccountedOccupantsByVehicle().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> waiting.send(waiting(3, "p", "s0", "s1")));
        var riding = new Fixture(); riding.boardA("p", "s1"); riding.send(abort(5, "p", "pt"));
        assertEquals(Map.of("v1", Set.of("p")), riding.state.eventAccountedOccupantsByVehicle());
        assertThrows(IllegalArgumentException.class, () -> riding.send(leave(6, "p", "v1", A)));
    }

    @Test void genericCarEventsAndTransitDriverGenericVehicleEntryDoNotCreateRiders() {
        var f = new Fixture(); var before = f.state;
        f.send(new PersonEntersVehicleEvent(0, Id.createPersonId("driver1"), Id.createVehicleId("v1")));
        f.send(new PersonLeavesVehicleEvent(1, Id.createPersonId("unregistered-car-driver"), Id.createVehicleId("car")));
        f.send(abort(2, "unregistered-car-driver", "car")); f.send(end(3, "p", "walk"));
        assertSame(before, f.state);
        assertThrows(IllegalArgumentException.class, () -> f.send(abort(4, "unregistered-person", "pt")));
        var other = new Fixture();
        var irrelevant = copy(new PersonEntersVehicleEvent(4, Id.createPersonId("car-driver"), Id.createVehicleId("car")), 1);
        assertThrows(IllegalArgumentException.class, () -> f.mapper.apply(other.state, irrelevant,
                f.serviceState, f.serviceState, f.releaseState));
    }

    @Test void oneDriversOldVehicleRecordCannotMaskItsLaterActiveVehicleEnding() {
        var f = new Fixture(); f.boardA("p", "s1"); f.send(depart(5, "v1", "s0"));
        f.send(arrive(6, "v1", "s1")); f.send(leave(6, "p", "v1", A));
        f.send(depart(7, "v1", "s1")); f.send(end(8, "driver1", "car"));
        f.send(waiting(8, "q", "s1", "s2")); f.send(start(9, C, "v2", "driver1"));
        f.send(arrive(10, "v2", "s1")); f.send(enter(11, "q", "v2", C)); f.send(depart(12, "v2", "s1"));
        f.send(arrive(13, "v2", "s2")); f.send(leave(13, "q", "v2", C)); f.send(depart(14, "v2", "s2"));
        assertDoesNotThrow(() -> f.send(end(15, "driver1", "car")));
        assertEquals(ObservedTransitServiceState.Motion.ENDED, f.serviceState.latestWorkByVehicle().get("v2").motion());
        assertEquals(Phase.ALIGHTED, f.person("p").phase()); assertEquals(Phase.ALIGHTED, f.person("q").phase());
    }

    @Test void anUnrelatedCarAbortAfterAnAlightedPtLegDoesNotReopenPtState() {
        var f = new Fixture(); f.boardA("p", "s1"); f.send(depart(5, "v1", "s0"));
        f.send(arrive(6, "v1", "s1")); f.send(leave(6, "p", "v1", A)); var alighted = f.state;
        f.send(abort(20, "p", "car")); assertSame(alighted, f.state);
    }

    @Test void peopleMayHaveDifferentEventClocksButOnePersonsClockCannotRegress() {
        var f = new Fixture(); f.send(start(2, A, "v1", "driver1")); f.send(arrive(3, "v1", "s0"));
        f.send(waiting(10, "p", "s0", "s1")); f.send(waiting(5, "q", "s0", "s1"));
        assertThrows(IllegalArgumentException.class, () -> f.send(enter(9, "p", "v1", A)));
        f.send(enter(6, "q", "v1", A)); f.send(enter(11, "p", "v1", A));
        assertEquals(Set.of("p", "q"), f.state.eventAccountedOccupantsByVehicle().get("v1"));
    }

    @Test void snapshotsNestedCollectionsAndPreviousStatesAreImmutableAndOwnerBound() {
        var f = new Fixture(); var initial = f.state; f.boardA("p", "s2"); f.endA();
        var retained = f.state; f.send(continued(8, "p", "v1", "v1", "s1", A));
        assertTrue(initial.riders().isEmpty()); assertEquals(Phase.AWAITING_CONTINUATION_EVENT, retained.riders().get("p").phase());
        assertThrows(UnsupportedOperationException.class, () -> f.state.riders().clear());
        assertThrows(UnsupportedOperationException.class, () -> f.state.eventAccountedOccupantsByVehicle().clear());
        assertThrows(UnsupportedOperationException.class, () -> f.state.eventAccountedOccupantsByVehicle().get("v1").clear());
        assertThrows(UnsupportedOperationException.class, () -> f.person("p").pendingContinuation().orElseThrow().candidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> f.person("p").nativeDenials().clear());
        var other = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> f.mapper.apply(other.state, copy(waiting(9, "q", "s0", "s1"), 99),
                f.serviceState, f.serviceState, f.releaseState));
        for (var constructor : State.class.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }

    @Test void differentPrivateUnobservedActorSuffixesHaveEqualPublicPrefixes() {
        var left = new ObservedTransitRiderState(services(), Set.of("p", "left-hidden"), Set.of(), chains());
        var right = new ObservedTransitRiderState(services(), Set.of("p", "right-hidden"), Set.of("hidden-freight"), chains());
        var f = new Fixture(); var event = copy(waiting(1, "p", "s0", "s1"), 1);
        var l = left.apply(left.initialState(), event, f.serviceState, f.serviceState, f.releaseState);
        var r = right.apply(right.initialState(), event, f.serviceState, f.serviceState, f.releaseState);
        assertEquals(l.riders(), r.riders()); assertEquals(l.eventAccountedOccupantsByVehicle(), r.eventAccountedOccupantsByVehicle());
    }

    @Test void malformedCopiedEventFieldsAndWrongTypedProducersAreRejected() {
        var f = new Fixture(); var event = copy(waiting(1, "p", "s0", "s1"), 1);
        for (String key : List.of("person", "agent", "atStop", "destinationStop", "time", "type")) {
            var attributes = new HashMap<>(event.attributes()); attributes.put(key, "wrong");
            var invalid = new ReceivedEvent(1, 1, 1, event.eventClass(), event.eventType(), attributes, false);
            assertThrows(IllegalArgumentException.class, () -> f.send(invalid), key);
        }
        var custom = new ReceivedEvent(1, 1, 1, "custom.WaitingProducer", event.eventType(), event.attributes(), false);
        assertThrows(IllegalArgumentException.class, () -> f.send(custom)); assertTrue(f.state.riders().isEmpty());
    }

    @Test void privateRegistriesRejectOverlappingActorsAndInvalidContinuationMemberships() {
        assertThrows(IllegalArgumentException.class, () -> new ObservedTransitRiderState(services(), Set.of("same"), Set.of("same"), chains()));
        assertThrows(IllegalArgumentException.class, () -> new ObservedTransitRiderState(List.of(), Set.of(), Set.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ObservedTransitRiderState(services(), Set.of(" "), Set.of(), chains()));
        assertThrows(IllegalArgumentException.class, () -> new ObservedTransitRiderState(services(), Set.of(), Set.of(), Map.of(A, List.of(A))));
        assertThrows(IllegalArgumentException.class, () -> new ObservedTransitRiderState(services(), Set.of(), Set.of(), Map.of(A, List.of(B, B))));
        var unknown = new TripKey("unknown", "unknown", "unknown");
        assertThrows(IllegalArgumentException.class, () -> new ObservedTransitRiderState(services(), Set.of(), Set.of(), Map.of(A, List.of(unknown))));
        var mutableServices = new ArrayList<>(services()); var mutableTargets = new ArrayList<>(List.of(B));
        var mapper = new ObservedTransitRiderState(mutableServices, Set.of("p"), Set.of(), Map.of(A, mutableTargets));
        mutableServices.clear(); mutableTargets.clear();
        var f = new Fixture();
        assertEquals(Phase.WAITING, mapper.apply(mapper.initialState(), copy(waiting(1, "p", "s0", "s1"), 1),
                f.serviceState, f.serviceState, f.releaseState).riders().get("p").phase());
    }
}
