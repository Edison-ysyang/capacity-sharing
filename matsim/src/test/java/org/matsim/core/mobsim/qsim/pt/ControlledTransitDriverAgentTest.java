package org.matsim.core.mobsim.qsim.pt;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.interfaces.Netsim;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.Umlauf;
import org.matsim.pt.UmlaufImpl;
import org.matsim.pt.UmlaufStueck;
import org.matsim.pt.Wenden;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Version;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.core.mobsim.qsim.pt.ControlledTransitDriverAgent.*;
import static org.mockito.Mockito.*;

/** Manual lifecycle unit tests with mocked QSim; not evidence of native queued execution. */
class ControlledTransitDriverAgentTest {
    private static final TripKey A = new TripKey("line", "a", "first");
    private static final TripKey B = new TripKey("line", "b", "second");
    private static final TripKey UNKNOWN = new TripKey("line", "a", "unknown");

    private static final class Fixture {
        final Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        final EventsManager events = EventsUtils.createEventsManager();
        final QSim qsim = mock(QSim.class);
        final InternalInterface internal = mock(InternalInterface.class);
        final MobsimTimer timer = new MobsimTimer(1);
        final UmlaufImpl work = new UmlaufImpl(Id.create("controlled-unit", Umlauf.class));
        final Map<TripKey, AcceptedCommitment> accepted = new HashMap<>();
        final TransitStopAgentTracker tracker = new TransitStopAgentTracker(events);
        int lookups;

        Fixture(boolean second, boolean wenden) {
            when(qsim.getScenario()).thenReturn(scenario);
            when(qsim.getEventsManager()).thenReturn(events);
            when(qsim.getSimTimer()).thenReturn(timer);
            when(internal.getMobsim()).thenReturn(qsim);
            Id<Link> one = Id.createLinkId("one");
            Id<Link> two = Id.createLinkId("two");
            service(A, 100, one);
            if (wenden) work.getUmlaufStuecke().add(new Wenden(RouteUtils.createLinkNetworkRouteImpl(one, two)));
            if (second) service(B, 300, wenden ? two : one);
            work.setVehicleId(Id.createVehicleId("same-vehicle"));
        }

        private void service(TripKey key, double departure, Id<Link> link) {
            var factory = scenario.getTransitSchedule().getFactory();
            TransitLine line = factory.createTransitLine(Id.create(key.line(), TransitLine.class));
            TransitStopFacility facility = factory.createTransitStopFacility(Id.create(key.route() + "-stop", TransitStopFacility.class), new Coord(0, 0), false);
            facility.setLinkId(link);
            var stop = factory.createTransitRouteStop(facility, 0, 10);
            stop.setAwaitDepartureTime(true);
            TransitRoute route = factory.createTransitRoute(Id.create(key.route(), TransitRoute.class),
                    RouteUtils.createLinkNetworkRouteImpl(link, link), List.of(stop), TransportMode.pt);
            Departure dep = factory.createDeparture(Id.create(key.departure(), Departure.class), departure);
            route.addDeparture(dep);
            line.addRoute(route);
            work.getUmlaufStuecke().add(new UmlaufStueck(line, route, dep));
        }

        ControlledTransitDriverAgent controlled() {
            ControlledTransitDriverAgent driver = new ControlledTransitDriverAgent(work, TransportMode.car, tracker, internal,
                    key -> { lookups++; return Optional.ofNullable(accepted.get(key)); }, 0, 1);
            vehicle(driver);
            return driver;
        }

        TransitDriverAgentImpl nativeDriver() {
            TransitDriverAgentImpl driver = new TransitDriverAgentImpl(work, TransportMode.car, tracker, internal);
            vehicle(driver);
            return driver;
        }

        private void vehicle(TransitDriverAgentImpl driver) {
            VehicleType type = VehicleUtils.createVehicleType(Id.create("unit-type", VehicleType.class));
            type.getCapacity().setSeats(5);
            TransitQVehicle vehicle = new TransitQVehicle(VehicleUtils.createVehicle(Id.create("same-vehicle", Vehicle.class), type));
            vehicle.setDriver(driver);
            driver.setVehicle(vehicle);
        }

        void accept(TripKey key, double seconds, long revision) {
            accepted.put(key, new AcceptedCommitment(seconds, new Version(1, revision)));
        }
    }

    @Test void gettersAreStableAndDoNotQueryProviderOrFollowWallClock() {
        Fixture f = new Fixture(false, false);
        f.accept(A, 80, 1);
        ControlledTransitDriverAgent driver = f.controlled();
        int lookups = f.lookups;
        ExecutionState before = driver.currentExecutionState();
        f.timer.setTime(1_000);
        for (int i = 0; i < 100; i++) {
            assertEquals(80, driver.getActivityEndTime());
            assertEquals(before, driver.currentExecutionState());
        }
        assertEquals(lookups, f.lookups);
    }

    @Test void noOverridePreservesNativeDeadlineIncludingChainReadyReplacement() {
        Fixture f = new Fixture(false, false);
        ControlledTransitDriverAgent controlled = f.controlled();
        TransitDriverAgentImpl nativeDriver = f.nativeDriver();
        assertEquals(nativeDriver.getActivityEndTime(), controlled.getActivityEndTime());
        controlled.setWaitForDeparture();
        nativeDriver.setWaitForDeparture();
        assertEquals(nativeDriver.getActivityEndTime(), controlled.getActivityEndTime());
        controlled.setReadyForDeparture(50);
        nativeDriver.setReadyForDeparture(50);
        assertEquals(50, controlled.getActivityEndTime());
        assertEquals(nativeDriver.getActivityEndTime(), controlled.getActivityEndTime());
        assertEquals(50, controlled.currentExecutionState().physicalReadySeconds());
        verify(qsimOf(f), never()).rescheduleActivityEnd(any());
    }

    private QSim qsimOf(Fixture fixture) { return fixture.qsim; }

    @Test void acceptedAdvanceCanMoveEarlierThanNativeBaseline() {
        Fixture f = new Fixture(false, false);
        ControlledTransitDriverAgent driver = f.controlled();
        f.accept(A, 80, 1);
        RefreshResult changed = driver.refreshAcceptedCommitment(A, 20);
        assertTrue(changed.requiresReschedule());
        assertEquals(100, changed.previousDeadlineSeconds());
        assertEquals(80, changed.effectiveDeadlineSeconds());
        assertEquals(100, driver.getDeparture().getDepartureTime(), "Published native schedule remains unchanged");
        verify(f.qsim, never()).rescheduleActivityEnd(any());
    }

    @Test void acceptedDelayChangesOnlyCachedDeadlineAndRepeatedRefreshDoesNotRequeue() {
        Fixture f = new Fixture(false, false);
        ControlledTransitDriverAgent driver = f.controlled();
        f.accept(A, 120, 1);
        assertTrue(driver.refreshAcceptedCommitment(A, 20).requiresReschedule());
        assertEquals(RefreshOutcome.NO_CHANGE, driver.refreshAcceptedCommitment(A, 21).outcome());
        assertEquals(120, driver.getActivityEndTime());
        verify(f.internal, never()).arrangeNextAgentState(any());
        verify(f.qsim, never()).rescheduleActivityEnd(any());
    }

    @Test void runningLegKeepsAdvancedAnchorForInheritedAwaitDepartureRule() {
        Fixture f = new Fixture(false, false);
        f.accept(A, 80, 1);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.endActivityAndComputeNextState(80);
        assertEquals(MobsimAgent.State.LEG, driver.getState());
        assertEquals(80, driver.getActivityEndTime());
        assertEquals(5, driver.longerStopTimeIfWeAreAheadOfSchedule(85, 0));
        assertEquals(0, driver.longerStopTimeIfWeAreAheadOfSchedule(90, 0));
        f.accept(A, 80, 2);
        assertFalse(driver.refreshAcceptedCommitment(A, 90).requiresReschedule());
        assertEquals(Optional.of(new Version(1, 1)), driver.currentExecutionState().acceptedVersion());
        assertEquals(80, driver.getActivityEndTime());
        f.accept(A, 80, 1);
        assertThrows(IllegalStateException.class, () -> driver.refreshAcceptedCommitment(A, 91),
                "Publication revision cannot roll back even though the applied start version is frozen");
    }

    @Test void startedTimeChangeIsRejectedAndRunningAnchorIsUnaffected() {
        Fixture f = new Fixture(false, false);
        f.accept(A, 80, 1);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.endActivityAndComputeNextState(80);
        f.accept(A, 90, 2);
        ExecutionState before = driver.currentExecutionState();
        assertThrows(IllegalStateException.class, () -> driver.refreshAcceptedCommitment(A, 90));
        assertEquals(before, driver.currentExecutionState());
    }

    @Test void startedNativeTripCannotAcquireFirstOverride() {
        Fixture f = new Fixture(false, false);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.endActivityAndComputeNextState(100);
        f.accept(A, 100, 1);
        assertThrows(IllegalStateException.class, () -> driver.refreshAcceptedCommitment(A, 101));
        assertEquals(100, driver.getActivityEndTime());
    }

    @Test void futureOverrideDoesNotMoveRunningLegAndLoadsAfterNativeTransition() {
        Fixture f = new Fixture(true, false);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.endActivityAndComputeNextState(100);
        f.accept(B, 250, 1);
        RefreshResult future = driver.refreshAcceptedCommitment(B, 110);
        assertEquals(RefreshOutcome.FUTURE_ONLY, future.outcome());
        assertFalse(future.requiresReschedule());
        assertEquals(100, driver.getActivityEndTime());
        driver.endLegAndComputeNextState(200);
        assertEquals(Optional.of(B), driver.currentExecutionState().trip());
        assertEquals(200, driver.currentExecutionState().physicalReadySeconds());
        assertEquals(250, driver.getActivityEndTime());
    }

    @Test void futureProviderEntryLoadsEvenWithoutAnExplicitFutureRefresh() {
        Fixture f = new Fixture(true, false);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.endActivityAndComputeNextState(100);
        f.accept(B, 250, 1);
        driver.endLegAndComputeNextState(200);
        assertEquals(250, driver.getActivityEndTime());
    }

    @Test void actualCompletionIsAnIndependentLowerBoundNotTheOldBaseline() {
        Fixture f = new Fixture(true, false);
        f.accept(B, 250, 1);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.endActivityAndComputeNextState(100);
        driver.endLegAndComputeNextState(275);
        assertEquals(275, driver.getActivityEndTime(), "Use physical 275, not native baseline 300");
        assertEquals(250, f.accepted.get(B).departureSeconds(), "Do not rewrite the late commitment");
        assertEquals(275, driver.currentExecutionState().physicalReadySeconds());
    }

    @Test void noOverrideNextTripPreservesSuperClampWhenVehicleIsLate() {
        Fixture f = new Fixture(true, false);
        ControlledTransitDriverAgent controlled = f.controlled();
        TransitDriverAgentImpl nativeDriver = f.nativeDriver();
        controlled.endActivityAndComputeNextState(100);
        nativeDriver.endActivityAndComputeNextState(100);
        controlled.endLegAndComputeNextState(350);
        nativeDriver.endLegAndComputeNextState(350);
        assertEquals(350, controlled.getActivityEndTime());
        assertEquals(nativeDriver.getActivityEndTime(), controlled.getActivityEndTime());
    }

    @Test void blockedCommitmentCannotReleaseAndReadyRespectsBothDelayAndPhysicalTime() {
        Fixture f = new Fixture(false, false);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.setWaitForDeparture();
        f.accept(A, 120, 1);
        RefreshResult blocked = driver.refreshAcceptedCommitment(A, 20);
        assertEquals(RefreshOutcome.BLOCKED, blocked.outcome());
        assertFalse(blocked.requiresReschedule());
        assertEquals(Double.POSITIVE_INFINITY, driver.getActivityEndTime());
        assertThrows(IllegalStateException.class, () -> driver.endActivityAndComputeNextState(30));
        driver.setReadyForDeparture(50);
        assertEquals(120, driver.getActivityEndTime());
        assertFalse(driver.currentExecutionState().chainBlocked());
        assertEquals(50, driver.currentExecutionState().physicalReadySeconds());
        verify(f.qsim, never()).rescheduleActivityEnd(any());
    }

    @Test void chainReleaseLaterThanCommitmentCannotBeAdvancedAway() {
        Fixture f = new Fixture(false, false);
        f.accept(A, 80, 1);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.setWaitForDeparture();
        driver.setReadyForDeparture(150);
        assertEquals(150, driver.getActivityEndTime());
        assertEquals(80, f.accepted.get(A).departureSeconds());
        f.accept(A, 90, 2);
        assertFalse(driver.refreshAcceptedCommitment(A, 150).requiresReschedule());
        assertEquals(150, driver.getActivityEndTime());
    }

    @Test void explicitAcceptedBaselineRestorationIsDifferentFromWithdrawal() {
        Fixture f = new Fixture(false, false);
        f.accept(A, 80, 1);
        ControlledTransitDriverAgent driver = f.controlled();
        f.accept(A, 100, 2);
        assertTrue(driver.refreshAcceptedCommitment(A, 20).requiresReschedule());
        assertEquals(100, driver.getActivityEndTime());
        f.accepted.remove(A);
        assertThrows(IllegalStateException.class, () -> driver.refreshAcceptedCommitment(A, 21));
        assertEquals(100, driver.getActivityEndTime());
    }

    @Test void terminalStateDoesNotReuseStaleDepartureOrReviveDriver() {
        Fixture f = new Fixture(false, false);
        f.accept(A, 80, 1);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.endActivityAndComputeNextState(80);
        driver.endLegAndComputeNextState(120);
        assertTrue(driver.currentExecutionState().terminal());
        assertTrue(driver.currentExecutionState().trip().isEmpty());
        assertNotNull(driver.getDeparture(), "Native stale departure reference remains present");
        assertEquals(Double.POSITIVE_INFINITY, driver.getActivityEndTime());
        assertFalse(driver.refreshAcceptedCommitment(A, 130).requiresReschedule());
        assertThrows(IllegalStateException.class, () -> driver.setReadyForDeparture(140));
        assertThrows(IllegalStateException.class, driver::setWaitForDeparture);
        assertThrows(IllegalStateException.class, () -> driver.endActivityAndComputeNextState(140));
        f.accept(A, 140, 2);
        assertThrows(IllegalStateException.class, () -> driver.refreshAcceptedCommitment(A, 140));
        assertEquals(Double.POSITIVE_INFINITY, driver.getActivityEndTime());
        verify(f.qsim, never()).rescheduleActivityEnd(any());
    }

    @Test void wendenIsNativeWorkAndMustFinishBeforeNextControlledService() {
        Fixture f = new Fixture(true, true);
        f.accept(B, 250, 1);
        ControlledTransitDriverAgent controlled = f.controlled();
        TransitDriverAgentImpl nativeDriver = f.nativeDriver();
        controlled.endActivityAndComputeNextState(100);
        nativeDriver.endActivityAndComputeNextState(100);
        controlled.endLegAndComputeNextState(200);
        nativeDriver.endLegAndComputeNextState(200);
        assertTrue(controlled.currentExecutionState().trip().isEmpty());
        assertFalse(controlled.currentExecutionState().terminal());
        assertNull(controlled.getDeparture());
        assertEquals(nativeDriver.getActivityEndTime(), controlled.getActivityEndTime());
        controlled.endActivityAndComputeNextState(200);
        controlled.endLegAndComputeNextState(275);
        assertEquals(Optional.of(B), controlled.currentExecutionState().trip());
        assertEquals(275, controlled.getActivityEndTime());
        assertEquals(275, controlled.currentExecutionState().physicalReadySeconds());
    }

    @Test void staleVersionEpochAndSameVersionTimeMutationAreRejected() {
        Fixture f = new Fixture(false, false);
        f.accept(A, 80, 2);
        ControlledTransitDriverAgent driver = f.controlled();
        ExecutionState before = driver.currentExecutionState();
        f.accept(A, 90, 1);
        assertThrows(IllegalStateException.class, () -> driver.refreshAcceptedCommitment(A, 20));
        f.accept(A, 90, 2);
        assertThrows(IllegalStateException.class, () -> driver.refreshAcceptedCommitment(A, 20));
        f.accepted.put(A, new AcceptedCommitment(90, new Version(2, 3)));
        assertThrows(IllegalStateException.class, () -> driver.refreshAcceptedCommitment(A, 20));
        assertEquals(before, driver.currentExecutionState());
    }

    @Test void epochIsFixedEvenBeforeFirstOverride() {
        Fixture f = new Fixture(false, false);
        ControlledTransitDriverAgent driver = f.controlled();
        f.accepted.put(A, new AcceptedCommitment(80, new Version(2, 1)));
        assertThrows(IllegalStateException.class, () -> driver.refreshAcceptedCommitment(A, 20));
        assertEquals(100, driver.getActivityEndTime());
    }

    @Test void unknownKeyIsRejectedAndCannotCreateService() {
        Fixture f = new Fixture(false, false);
        ControlledTransitDriverAgent driver = f.controlled();
        ExecutionState before = driver.currentExecutionState();
        f.accept(UNKNOWN, 80, 1);
        assertThrows(IllegalArgumentException.class, () -> driver.refreshAcceptedCommitment(UNKNOWN, 20));
        assertEquals(before, driver.currentExecutionState());
        assertEquals(1, f.work.getUmlaufStuecke().size());
    }

    @Test void invalidTimesAndBackwardsObservationsAreRejected() {
        Fixture f = new Fixture(false, false);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.refreshAcceptedCommitment(A, 20);
        ExecutionState before = driver.currentExecutionState();
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new AcceptedCommitment(bad, new Version(1, 1)));
            assertThrows(IllegalArgumentException.class, () -> driver.refreshAcceptedCommitment(A, bad));
            assertThrows(IllegalArgumentException.class, () -> driver.setReadyForDeparture(bad));
            assertThrows(IllegalArgumentException.class, () -> driver.endActivityAndComputeNextState(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> driver.refreshAcceptedCommitment(A, 19));
        assertThrows(IllegalArgumentException.class, () -> driver.setReadyForDeparture(19));
        assertEquals(before, driver.currentExecutionState());
        assertThrows(IllegalArgumentException.class, () -> new AcceptedCommitment(80, new Version(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> new AcceptedCommitment(80, new Version(1, -1)));
    }

    @Test void explicitInitialPhysicalReadinessBoundsAnOverride() {
        Fixture f = new Fixture(false, false);
        f.accept(A, 80, 1);
        ControlledTransitDriverAgent driver = new ControlledTransitDriverAgent(f.work, TransportMode.car, f.tracker,
                f.internal, key -> Optional.ofNullable(f.accepted.get(key)), 90, 1);
        assertEquals(90, driver.getActivityEndTime());
        assertEquals(90, driver.currentExecutionState().physicalReadySeconds());
    }

    @Test void noOverrideConstructorDoesNotQueryFutureEntries() {
        Fixture f = new Fixture(true, false);
        ControlledTransitDriverAgent driver = f.controlled();
        assertEquals(1, f.lookups);
        assertEquals(100, driver.getActivityEndTime());
        driver.currentExecutionState();
        assertEquals(1, f.lookups);
    }

    @Test void unsupportedSerializationAndNonLocalMobsimAreRejected() {
        Fixture f = new Fixture(false, false);
        ControlledTransitDriverAgent driver = f.controlled();
        assertThrows(UnsupportedOperationException.class, driver::toMessage);
        InternalInterface other = mock(InternalInterface.class);
        when(other.getMobsim()).thenReturn(mock(Netsim.class));
        assertThrows(IllegalArgumentException.class, () -> new ControlledTransitDriverAgent(f.work, TransportMode.car,
                f.tracker, other, key -> Optional.empty(), 0, 1));
    }

    @Test void abortedDriverCannotBeRefreshedOrReactivated() {
        Fixture f = new Fixture(false, false);
        ControlledTransitDriverAgent driver = f.controlled();
        driver.setStateToAbort(20);
        f.accept(A, 80, 1);
        assertThrows(IllegalStateException.class, () -> driver.refreshAcceptedCommitment(A, 20));
        assertThrows(IllegalStateException.class, () -> driver.setReadyForDeparture(20));
        assertThrows(IllegalStateException.class, driver::setWaitForDeparture);
        assertEquals(MobsimAgent.State.ABORT, driver.getState());
        verify(f.qsim, never()).rescheduleActivityEnd(any());
    }

    @Test void constructorRejectsWrongEpochInvalidReadyAndDuplicateWorkKey() {
        Fixture f = new Fixture(false, false);
        assertThrows(IllegalArgumentException.class, () -> new ControlledTransitDriverAgent(f.work, TransportMode.car,
                f.tracker, f.internal, key -> Optional.empty(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ControlledTransitDriverAgent(f.work, TransportMode.car,
                f.tracker, f.internal, key -> Optional.empty(), Double.NaN, 1));
        f.work.getUmlaufStuecke().add(f.work.getUmlaufStuecke().getFirst());
        assertThrows(IllegalArgumentException.class, () -> new ControlledTransitDriverAgent(f.work, TransportMode.car,
                f.tracker, f.internal, key -> Optional.empty(), 0, 1));
    }

    @Test void constructorRejectsOneDepartureInstanceSharedAcrossDifferentRouteKeys() {
        Fixture f = new Fixture(true, false);
        var first = f.work.getUmlaufStuecke().getFirst();
        var second = f.work.getUmlaufStuecke().get(1);
        Departure sharedDeparture = first.getDeparture();
        second.getRoute().removeDeparture(second.getDeparture());
        second.getRoute().addDeparture(sharedDeparture);
        f.work.getUmlaufStuecke().set(1, new UmlaufStueck(second.getLine(), second.getRoute(), sharedDeparture));
        assertNotEquals(first.getRoute().getId(), second.getRoute().getId());
        assertSame(f.work.getUmlaufStuecke().getFirst().getDeparture(), f.work.getUmlaufStuecke().get(1).getDeparture());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> f.controlled());
        assertTrue(error.getMessage().contains("same Departure instance"));
        assertEquals(0, f.lookups, "Reject ambiguous native identity before consulting accepted commitments");
    }
}
