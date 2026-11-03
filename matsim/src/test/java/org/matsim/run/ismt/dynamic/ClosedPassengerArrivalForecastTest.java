package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
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
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.ClosedPassengerArrivalForecast.Od;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;
import org.matsim.run.ismt.dynamic.ObservedTransitRiderState.PassengerWaitingArrival;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState.Service;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic event arithmetic and manually delivered source-bound views; no QSim.run or calibrated forecast. */
class ClosedPassengerArrivalForecastTest {
    private static final TripKey A = new TripKey("line", "route", "A");
    private static final Od OD = new Od("s0", "s1");
    private static List<Service> services() {
        return List.of(new Service(A, "v", List.of("s0", "s1", "s2")));
    }
    private static List<KnownFreightReleaseState.Contract> contracts() {
        return List.of(new KnownFreightReleaseState.Contract("order", "s0", "s1", 0, 100, List.of("f"), "cargo_origin"));
    }

    private static final class Fixture {
        final ObservedTransitServiceState service = new ObservedTransitServiceState(services());
        final KnownFreightReleaseState release = new KnownFreightReleaseState(contracts());
        final ObservedTransitRiderState mapper = new ObservedTransitRiderState(services(), Set.of("p", "q", "r"), Set.of("f"), Map.of());
        ObservedTransitServiceState.State services = service.initialState();
        KnownFreightReleaseState.State releases = release.initialState();
        ObservedTransitRiderState.State state = mapper.initialState();
        long sequence;
        ReceivedEvent send(Event event) {
            var copied = new ReceivedEvent(++sequence, event.getTime(), event.getTime(), event.getClass().getName(),
                    event.getEventType(), event.getAttributes(), false);
            var nextServices = service.apply(services, copied);
            var nextReleases = release.apply(releases, copied);
            var nextState = mapper.apply(state, copied, services, nextServices, nextReleases);
            services = nextServices; releases = nextReleases; state = nextState;
            return copied;
        }
        void boardAndAlightPassenger() {
            send(waiting(1, "p", "s0", "s1")); send(start(2)); send(arrive(3, "s0")); send(enter(4, "p"));
            send(new VehicleDepartsAtFacilityEvent(5, Id.createVehicleId("v"), stop("s0"), 0));
            send(arrive(6, "s1")); send(leave(7, "p"));
        }
    }

    @Test void noPrivatePassengerOrFreightDemandLeaksIntoAnEmptyForecast() {
        var f = new Fixture();
        var forecast = new ClosedPassengerArrivalForecast(0, 10).forecast(f.state, OptionalDouble.of(10));
        assertTrue(forecast.warmupKnown()); assertTrue(forecast.arrivalsByOd().isEmpty());
        assertTrue(forecast.currentlyWaitingPassengers().isEmpty());
        assertEquals(10, forecast.sampleWindow().observedDurationSeconds());
    }

    @Test void unknownPrefixIsNotAnObservedZeroRateAndCannotContainFacts() {
        var f = new Fixture(); var model = new ClosedPassengerArrivalForecast(0, 10);
        var unknown = model.forecast(f.state, OptionalDouble.empty());
        assertFalse(unknown.warmupKnown()); assertTrue(unknown.sampleWindow().closedThroughSeconds().isEmpty());
        assertTrue(unknown.sampleWindow().lowerExclusiveSeconds().isEmpty()); assertTrue(unknown.arrivalsByOd().isEmpty());
        f.send(waiting(0, "p", "s0", "s1"));
        assertThrows(IllegalArgumentException.class, () -> model.forecast(f.state, OptionalDouble.empty()));
    }

    @Test void intervalIsStrictlyOpenOnTheLeftAndClosedOnTheRight() {
        var f = new Fixture();
        f.send(waiting(10, "p", "s0", "s1"));
        f.send(waiting(Math.nextUp(10.0), "q", "s0", "s1"));
        f.send(waiting(20, "r", "s0", "s1"));
        var result = new ClosedPassengerArrivalForecast(0, 10).forecast(f.state, OptionalDouble.of(20));
        assertEquals(2, result.arrivalsByOd().get(OD).observedArrivals());
        assertEquals(0.2, result.arrivalsByOd().get(OD).ratePerSecond().orElseThrow(), 0);
        assertEquals(10, result.sampleWindow().lowerExclusiveSeconds().orElseThrow());
        assertEquals(3, result.currentlyWaitingPassengers().size(), "Old waiting passengers remain queued outside the rate window");
    }

    @Test void warmupCountsUseNoRateUntilTheWholeWindowIsObserved() {
        var f = new Fixture(); f.send(waiting(5, "p", "s0", "s1")); f.send(waiting(8, "q", "s0", "s1"));
        var model = new ClosedPassengerArrivalForecast(5, 10);
        var partial = model.forecast(f.state, OptionalDouble.of(9));
        assertFalse(partial.warmupKnown()); assertEquals(4, partial.sampleWindow().observedDurationSeconds());
        assertEquals(2, partial.arrivalsByOd().get(OD).observedArrivals());
        assertTrue(partial.arrivalsByOd().get(OD).ratePerSecond().isEmpty());
        var complete = model.forecast(f.state, OptionalDouble.of(15));
        assertTrue(complete.warmupKnown()); assertEquals(1, complete.arrivalsByOd().get(OD).observedArrivals());
        assertEquals(0.1, complete.arrivalsByOd().get(OD).ratePerSecond().orElseThrow(), 0);
    }

    @Test void sameTimeArrivalsAreSeparateFactsOrderedByClosedSequence() {
        var f = new Fixture();
        var first = f.send(waiting(2, "q", "s0", "s1")); var second = f.send(waiting(2, "p", "s0", "s1"));
        assertEquals(List.of(first.closedSequence(), second.closedSequence()),
                f.state.passengerWaitingArrivals().stream().map(PassengerWaitingArrival::closedSequence).toList());
        assertEquals(List.of("q", "p"), f.state.passengerWaitingArrivals().stream().map(PassengerWaitingArrival::personId).toList());
        assertEquals(2, new ClosedPassengerArrivalForecast(0, 3).forecast(f.state, OptionalDouble.of(3)).arrivalsByOd().get(OD).observedArrivals());
    }

    @Test void completedRideDoesNotEraseItsObservedArrivalAndTransferUsesNewEpisode() {
        var f = new Fixture(); f.boardAndAlightPassenger();
        assertEquals(1, f.state.passengerWaitingArrivals().size()); assertTrue(f.state.currentlyWaitingPassengers().isEmpty());
        var transfer = f.send(waiting(9, "p", "s1", "s2"));
        var result = new ClosedPassengerArrivalForecast(0, 10).forecast(f.state, OptionalDouble.of(10));
        assertEquals(1, result.arrivalsByOd().get(OD).observedArrivals());
        assertEquals(1, result.arrivalsByOd().get(new Od("s1", "s2")).observedArrivals());
        var current = result.currentlyWaitingPassengers().get("p");
        assertEquals(9, current.occurredAtSeconds()); assertEquals(transfer.closedSequence(), current.closedSequence());
        assertEquals("s1", current.stopId()); assertEquals("s2", current.destinationStopId());
        assertEquals(1, f.state.riders().get("p").firstObservedAtSeconds(), "First observation intentionally differs from current arrival");
    }

    @Test void repeatedWaitingAtSameOdCountsEpisodesNotUniquePeople() {
        var f = new Fixture(); f.boardAndAlightPassenger(); f.send(waiting(9, "p", "s0", "s1"));
        var result = new ClosedPassengerArrivalForecast(0, 10).forecast(f.state, OptionalDouble.of(10));
        assertEquals(2, result.arrivalsByOd().get(OD).observedArrivals());
        assertEquals(1, result.currentlyWaitingPassengers().size());
    }

    @Test void boardingAndDenialDoNotCreateNewArrivalsOrResetWaitingStart() {
        var f = new Fixture(); f.send(waiting(1, "p", "s0", "s1"));
        f.send(start(2)); f.send(arrive(3, "s0"));
        f.send(new BoardingDeniedEvent(4, Id.createPersonId("p"), Id.createVehicleId("v")));
        assertEquals(1, f.state.currentlyWaitingPassengers().get("p").occurredAtSeconds());
        assertEquals(4, f.state.riders().get("p").lastObservedAtSeconds());
        f.send(enter(5, "p"));
        assertTrue(f.state.currentlyWaitingPassengers().isEmpty()); assertEquals(1, f.state.passengerWaitingArrivals().size());
        assertEquals(1, new ClosedPassengerArrivalForecast(0, 10).forecast(f.state, OptionalDouble.of(10)).arrivalsByOd().get(OD).observedArrivals());
    }

    @Test void freightWaitingAndReleaseAreNeverPassengerArrivalDemand() {
        var f = new Fixture();
        f.send(new ActivityEndEvent(0, Id.createPersonId("f"), null, null, "cargo_origin", null));
        f.send(waiting(1, "f", "s0", "s1")); f.send(waiting(2, "p", "s0", "s1"));
        var result = new ClosedPassengerArrivalForecast(0, 10).forecast(f.state, OptionalDouble.of(10));
        assertEquals(1, result.arrivalsByOd().get(OD).observedArrivals());
        assertEquals(Set.of("p"), result.currentlyWaitingPassengers().keySet());
        assertEquals(2, f.state.riders().size());
    }

    @Test void expiredObservedOdHasExplicitZeroButUnknownOdIsNotInvented() {
        var f = new Fixture(); f.send(waiting(1, "p", "s0", "s1"));
        var result = new ClosedPassengerArrivalForecast(0, 10).forecast(f.state, OptionalDouble.of(20));
        assertEquals(0, result.arrivalsByOd().get(OD).observedArrivals());
        assertEquals(0, result.arrivalsByOd().get(OD).ratePerSecond().orElseThrow());
        assertFalse(result.arrivalsByOd().containsKey(new Od("s1", "s2")));
    }

    @Test void prefixResultsAndCollectionsRemainImmutableAfterLaterArrivals() {
        var f = new Fixture(); f.send(waiting(1, "p", "s0", "s1"));
        var oldState = f.state; var oldHistory = oldState.passengerWaitingArrivals();
        var model = new ClosedPassengerArrivalForecast(0, 10); var old = model.forecast(oldState, OptionalDouble.of(10));
        f.send(waiting(12, "q", "s0", "s1"));
        assertEquals(1, oldHistory.size()); assertEquals(old, model.forecast(oldState, OptionalDouble.of(10)));
        assertThrows(UnsupportedOperationException.class, oldHistory::clear);
        assertThrows(UnsupportedOperationException.class, () -> oldState.currentlyWaitingPassengers().clear());
        assertThrows(UnsupportedOperationException.class, () -> old.arrivalsByOd().clear());
        assertThrows(UnsupportedOperationException.class, () -> old.currentlyWaitingPassengers().clear());
        assertEquals(2, f.state.passengerWaitingArrivals().size());
    }

    @Test void futureFactsAndIncorrectObservationStartAreRejectedWithoutMutatingState() {
        var f = new Fixture(); f.send(waiting(5, "p", "s0", "s1")); var state = f.state;
        assertThrows(IllegalArgumentException.class, () -> new ClosedPassengerArrivalForecast(0, 10).forecast(state, OptionalDouble.of(4)));
        assertThrows(IllegalArgumentException.class, () -> new ClosedPassengerArrivalForecast(6, 10).forecast(state, OptionalDouble.of(10)));
        assertThrows(IllegalArgumentException.class, () -> new ClosedPassengerArrivalForecast(6, 10).forecast(new Fixture().state, OptionalDouble.of(5)));
        assertSame(state, f.state);
        f.send(start(6)); f.send(arrive(7, "s0")); f.send(enter(8, "p"));
        assertThrows(IllegalArgumentException.class, () -> new ClosedPassengerArrivalForecast(0, 10).forecast(f.state, OptionalDouble.of(7)),
                "Checking only historical waiting times would miss a future boarding fact");
    }

    @Test void allTimeParametersMustBeExplicitFiniteAndValid() {
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ClosedPassengerArrivalForecast(invalid, 10));
            assertThrows(IllegalArgumentException.class, () -> new ClosedPassengerArrivalForecast(0, invalid));
            assertThrows(IllegalArgumentException.class, () -> new ClosedPassengerArrivalForecast(0, 10).forecast(new Fixture().state, OptionalDouble.of(invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> new ClosedPassengerArrivalForecast(0, 0));
        assertThrows(NullPointerException.class, () -> new ClosedPassengerArrivalForecast(0, 10).forecast((ClosedCoTransportObservationState.Snapshot) null));
        assertThrows(IllegalArgumentException.class, () -> new PassengerWaitingArrival("p", "s0", "s1", 1, 0));
        assertTrue(java.util.Arrays.stream(ObservedTransitRiderState.State.class.getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
        assertTrue(java.util.Arrays.stream(ClosedPassengerArrivalForecast.class.getMethods()).filter(method -> method.getName().equals("forecast"))
                .allMatch(method -> List.of(method.getParameterTypes()).equals(List.of(ClosedCoTransportObservationState.Snapshot.class))));
    }

    @Test void anotherOwnersStateCannotBeImportedAndFreshOwnerHasNoEarlierHistory() {
        var first = new Fixture(); var second = new Fixture(); first.send(waiting(1, "p", "s0", "s1"));
        var event = new ReceivedEvent(2, 2, 2, AgentWaitingForPtEvent.class.getName(), AgentWaitingForPtEvent.EVENT_TYPE,
                waiting(2, "q", "s0", "s1").getAttributes(), false);
        assertThrows(IllegalArgumentException.class, () -> second.mapper.apply(first.state, event,
                second.services, second.services, second.releases));
        assertTrue(second.state.passengerWaitingArrivals().isEmpty());
        assertTrue(new ClosedPassengerArrivalForecast(0, 10).forecast(second.state, OptionalDouble.of(10)).arrivalsByOd().isEmpty());
    }

    @Test void publicForecastUsesClosedCutoffNotLaterDecisionTimeAndOldEpochSnapshotIsStable() {
        var model = new ClosedPassengerArrivalForecast(0, 1);
        try (var first = new SourceFixture(1); var second = new SourceFixture(2)) {
            assertFalse(model.forecast(first.before(0)).warmupKnown());
            first.event(waiting(0, "p", "s0", "s1")); first.after(0);
            var atOne = first.before(1); var partial = model.forecast(atOne);
            assertEquals(1, atOne.position().decisionTimeSeconds()); assertEquals(0, partial.sampleWindow().closedThroughSeconds().orElseThrow());
            assertFalse(partial.warmupKnown()); assertEquals(1, partial.arrivalsByOd().get(OD).observedArrivals());
            first.event(waiting(1, "q", "s0", "s1")); first.after(1);
            var atTwo = first.before(2); var matured = model.forecast(atTwo);
            assertTrue(matured.warmupKnown()); assertEquals(1, matured.arrivalsByOd().get(OD).observedArrivals());
            assertEquals(1, matured.arrivalsByOd().get(OD).ratePerSecond().orElseThrow());
            assertEquals(partial, model.forecast(atOne), "Later arrivals cannot alter an old immutable prefix");
            assertTrue(model.forecast(second.before(0)).arrivalsByOd().isEmpty());
            assertNotEquals(atOne.position().epoch(), second.component.snapshot().orElseThrow().position().epoch());
        }
    }

    /** Real observer-bound snapshots with manual callbacks on QSim objects that are NEVER run. */
    private static final class SourceFixture implements AutoCloseable {
        final EventsManager events = EventsUtils.createEventsManager();
        final QSim qsim;
        final NativeEventPrefixObserver observer;
        final ClosedCoTransportObservationState component;
        final List<ClosedCoTransportObservationState.Snapshot> snapshots = new ArrayList<>();
        SourceFixture(long epoch) {
            var config = ConfigUtils.createConfig(); config.qsim().setNumberOfThreads(1); config.global().setNumberOfThreads(1);
            config.qsim().setStartTime(0); config.qsim().setEndTime(5);
            config.controller().setOutputDirectory(Path.of(".build-cache", "arrival-forecast-unit-unused").toAbsolutePath().toString());
            qsim = new QSimBuilder(config).useDefaults().build(ScenarioUtils.createScenario(config), events);
            component = new ClosedCoTransportObservationState(20, epoch, services(), contracts(), Set.of("p", "q", "r"), Map.of());
            observer = NativeEventPrefixObserver.attach(qsim, 20, epoch, 0, view -> snapshots.add(component.accept(view)));
            component.bindTo(observer); qsim.getSimTimer().setTime(0);
            observer.notifyMobsimInitialized(new MobsimInitializedEvent<>(qsim));
        }
        void event(Event event) { events.processEvent(event); }
        ClosedCoTransportObservationState.Snapshot before(double time) {
            qsim.getSimTimer().setTime(time); observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(qsim, time));
            return snapshots.getLast();
        }
        void after(double time) {
            qsim.getSimTimer().setTime(time); observer.notifyMobsimAfterSimStep(new MobsimAfterSimStepEvent<>(qsim, time));
        }
        @Override public void close() { observer.close(); }
    }

    private static Id<TransitStopFacility> stop(String id) { return Id.create(id, TransitStopFacility.class); }
    private static AgentWaitingForPtEvent waiting(double time, String person, String origin, String destination) {
        return new AgentWaitingForPtEvent(time, Id.createPersonId(person), stop(origin), stop(destination));
    }
    private static TransitDriverStartsEvent start(double time) {
        return new TransitDriverStartsEvent(time, Id.createPersonId("driver"), Id.createVehicleId("v"),
                Id.create(A.line(), TransitLine.class), Id.create(A.route(), TransitRoute.class), Id.create(A.departure(), Departure.class));
    }
    private static VehicleArrivesAtFacilityEvent arrive(double time, String stop) {
        return new VehicleArrivesAtFacilityEvent(time, Id.createVehicleId("v"), stop(stop), 0);
    }
    private static PersonEntersPtVehicleEvent enter(double time, String person) {
        return new PersonEntersPtVehicleEvent(time, Id.createPersonId(person), Id.createVehicleId("v"),
                Id.create(A.line(), TransitLine.class), Id.create(A.route(), TransitRoute.class));
    }
    private static PersonLeavesPtVehicleEvent leave(double time, String person) {
        return new PersonLeavesPtVehicleEvent(time, Id.createPersonId(person), Id.createVehicleId("v"),
                Id.create(A.line(), TransitLine.class), Id.create(A.route(), TransitRoute.class));
    }
}
