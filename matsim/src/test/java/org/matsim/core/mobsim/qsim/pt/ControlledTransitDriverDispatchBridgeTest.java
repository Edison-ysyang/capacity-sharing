package org.matsim.core.mobsim.qsim.pt;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
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
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.core.mobsim.qsim.pt.ControlledTransitDispatchBridge.*;
import static org.matsim.core.mobsim.qsim.pt.ControlledTransitDriverAgent.RefreshOutcome;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;
import static org.mockito.Mockito.*;

/**
 * Bounded unit tests: real native/controlled driver construction and manual lifecycle,
 * but mocked QSim/queue calls, empty population, no JVM simulation loop or output files.
 * Fabricated BeforeSimStep callbacks and manual starts are test mechanics, not native
 * execution, full M/T equivalence, physical safety or complete-policy feasibility evidence.
 */
class ControlledTransitDriverDispatchBridgeTest {
    private static final TripKey A = new TripKey("line", "a", "first");
    private static final TripKey B = new TripKey("line", "b", "second");
    private static final TripKey C = new TripKey("line", "c", "other");
    private static final TripKey UNKNOWN = new TripKey("line", "b", "absent");
    private static final AtomicInteger SERIAL = new AtomicInteger();
    private static final Limits LIMITS = new Limits(200, 200, 5);

    private static final class Fixture {
        final Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        final EventsManager events = EventsUtils.createEventsManager();
        final QSim qsim = mock(QSim.class);
        final InternalInterface internal = mock(InternalInterface.class);
        final MobsimTimer timer = new MobsimTimer(1);
        final Map<Id<Person>, MobsimAgent> agents = new LinkedHashMap<>();
        final TransitStopAgentTracker tracker = new TransitStopAgentTracker(events);
        final UmlaufImpl work;
        final UmlaufImpl otherWork;
        final DispatchCommitmentState ledger;
        final ControlledTransitDispatchBridge bridge;
        final ControlledTransitDriverAgent driver;
        final ControlledTransitDriverAgent otherDriver;
        Consumer<ControlledTransitDispatchBridge> action = b -> { };

        Fixture(boolean second, boolean wenden, boolean other) {
            this(second, wenden, other, true, null, List.of(), LIMITS);
        }

        Fixture(boolean second, boolean wenden, boolean other, boolean registerOther,
                List<ControlPoint> points, List<VehicleConnection> connections, Limits limits) {
            when(qsim.getScenario()).thenReturn(scenario);
            when(qsim.getEventsManager()).thenReturn(events);
            when(qsim.getSimTimer()).thenReturn(timer);
            when(qsim.getAgents()).thenReturn(agents);
            when(internal.getMobsim()).thenReturn(qsim);
            int serial = SERIAL.incrementAndGet();
            work = new UmlaufImpl(Id.create("bridge-work-" + serial, Umlauf.class));
            service(work, A, 100, "one");
            if (wenden) work.getUmlaufStuecke().add(new Wenden(RouteUtils.createLinkNetworkRouteImpl(
                    Id.createLinkId("one"), Id.createLinkId("two"))));
            if (second) service(work, B, 300, wenden ? "two" : "one");
            work.setVehicleId(Id.createVehicleId("vehicle"));
            List<ScheduledTrip> trips = new ArrayList<>();
            trips.add(new ScheduledTrip(A, "vehicle", 100));
            if (second) trips.add(new ScheduledTrip(B, "vehicle", 300));
            if (other) {
                otherWork = new UmlaufImpl(Id.create("bridge-other-" + serial, Umlauf.class));
                service(otherWork, C, 200, "one");
                otherWork.setVehicleId(Id.createVehicleId("other-vehicle"));
                trips.add(new ScheduledTrip(C, "other-vehicle", 200));
            } else otherWork = null;
            ledger = new DispatchCommitmentState(trips, limits);
            ledger.beginIteration(20, 0, true);
            // Explicit synthetic service windows, not default scientific operating/safety standards.
            List<ControlPoint> registered = points == null ? trips.stream().map(trip ->
                    new ControlPoint(new ControlPointKey("unit-window-" + trip.key().route(), "unit-direction"),
                            List.of(new Call(trip.key(), 0)), 0, 1_000,
                            Optional.of(new FirstLastWindow(0, 1_000, 0, 1_000)))).toList() : points;
            bridge = new ControlledTransitDispatchBridge(qsim, ledger, 20, registered, connections, (b, now) -> action.accept(b));
            driver = create(work);
            otherDriver = other && registerOther ? create(otherWork) : null;
        }

        private void service(UmlaufImpl umlauf, TripKey key, double time, String linkName) {
            var factory = scenario.getTransitSchedule().getFactory();
            Id<Link> link = Id.createLinkId(linkName);
            TransitLine line = factory.createTransitLine(Id.create(key.line(), TransitLine.class));
            TransitStopFacility facility = factory.createTransitStopFacility(
                    Id.create(key.route() + "-stop", TransitStopFacility.class), new Coord(0, 0), false);
            facility.setLinkId(link);
            var stop = factory.createTransitRouteStop(facility, 0, 10);
            stop.setAwaitDepartureTime(true);
            TransitRoute route = factory.createTransitRoute(Id.create(key.route(), TransitRoute.class),
                    RouteUtils.createLinkNetworkRouteImpl(link, link), List.of(stop), TransportMode.pt);
            Departure departure = factory.createDeparture(Id.create(key.departure(), Departure.class), time);
            route.addDeparture(departure);
            line.addRoute(route);
            umlauf.getUmlaufStuecke().add(new UmlaufStueck(line, route, departure));
        }

        ControlledTransitDriverAgent create(Umlauf umlauf) {
            var created = (ControlledTransitDriverAgent) bridge.createTransitDriver(umlauf, internal, tracker);
            VehicleType type = VehicleUtils.createVehicleType(Id.create("bridge-unit-type", VehicleType.class));
            type.getCapacity().setSeats(5);
            TransitQVehicle vehicle = new TransitQVehicle(VehicleUtils.createVehicle(umlauf.getVehicleId(), type));
            vehicle.setDriver(created);
            created.setVehicle(vehicle);
            agents.put(created.getId(), created);
            return created;
        }

        Batch batch(double now, Map<TripKey, Double> proposed) {
            return new Batch(ledger.snapshot().version(), now, List.of(), proposed);
        }

        void step(double now, Consumer<ControlledTransitDispatchBridge> callback) {
            timer.setTime(now);
            action = callback;
            bridge.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(qsim, now));
        }

        ApplicationAudit submit(double now, Map<TripKey, Double> proposed) {
            AtomicReference<ApplicationAudit> result = new AtomicReference<>();
            step(now, b -> result.set(b.submit(batch(now, proposed))));
            return result.get();
        }

        PrecommitRejected reject(double now, Batch batch) {
            Snapshot before = ledger.snapshot();
            List<DecisionRecord> history = ledger.history();
            var state = driver.currentExecutionState();
            var otherState = otherDriver == null ? null : otherDriver.currentExecutionState();
            long queues = queues();
            AtomicReference<PrecommitRejected> failure = new AtomicReference<>();
            step(now, b -> failure.set(assertThrows(PrecommitRejected.class, () -> b.submit(batch))));
            assertEquals(before, ledger.snapshot(), "A rejected batch must not advance the clock, locks or version");
            assertEquals(history, ledger.history());
            assertEquals(state, driver.currentExecutionState());
            if (otherDriver != null) assertEquals(otherState, otherDriver.currentExecutionState());
            assertEquals(queues, queues());
            assertEquals(Status.REJECTED_PRECOMMIT, failure.get().audit().status());
            assertTrue(failure.get().audit().decision().isEmpty());
            assertTrue(failure.get().audit().refreshedTrips().isEmpty());
            assertFalse(bridge.isPoisoned());
            return failure.get();
        }

        long queues() {
            return mockingDetails(qsim).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("rescheduleActivityEnd")).count();
        }
    }

    @Test void oneNativeConstructionRegistersAllFutureWorkAndRejectsDuplicateUmlauf() {
        Fixture f = new Fixture(true, true, false);
        assertEquals(ControlledTransitDriverAgent.class, f.driver.getClass());
        assertEquals("pt_" + f.work.getId(), f.driver.getId().toString());
        int persons = Id.getNumberOfIds(Person.class);
        assertThrows(IllegalStateException.class, () -> f.bridge.createTransitDriver(f.work, f.internal, f.tracker));
        assertEquals(persons, Id.getNumberOfIds(Person.class));
        f.step(0, b -> { }); // Real plan-element identities, not mocked plan getters, pass registration.
        ApplicationAudit audit = f.submit(20, Map.of(B, 250.0));
        assertEquals(RefreshOutcome.FUTURE_ONLY, audit.refreshedTrips().getFirst().result().outcome());
        assertEquals(0, f.queues());
    }

    @Test void unknownLastKeyRejectsEntireBatchWithoutPublishingFutureOverride() {
        Fixture f = new Fixture(true, false, false);
        Map<TripKey, Double> proposed = new LinkedHashMap<>();
        proposed.put(A, 80.0);
        proposed.put(B, 250.0);
        proposed.put(UNKNOWN, 260.0);
        f.reject(20, f.batch(20, proposed));
        f.driver.endActivityAndComputeNextState(100);
        f.driver.endLegAndComputeNextState(200);
        assertEquals(300, f.driver.getActivityEndTime(), "Rejected future value was never published or cached");
    }

    @Test void staleVersionAndWrongEpochRejectBeforeMutation() {
        Fixture f = new Fixture(false, false, false);
        f.reject(20, new Batch(new Version(1, 9), 20, List.of(), Map.of(A, 80.0)));
        f.reject(21, new Batch(new Version(2, 0), 21, List.of(), Map.of(A, 80.0)));
    }

    @Test void futureDeclaredFactAndExactDecisionClockAreCheckedAtomically() {
        Fixture f = new Fixture(false, false, false);
        f.reject(20, new Batch(f.ledger.snapshot().version(), 20,
                List.of(new ObservedFact("not-yet-available", 10, 21)), Map.of(A, 80.0)));
        f.reject(21, f.batch(22, Map.of(A, 80.0)));
    }

    @Test void lockWindowPastCandidatesAndAmplitudeFailuresLeaveAllStateUntouched() {
        Fixture f = new Fixture(false, false, false);
        f.reject(20, f.batch(20, Map.of(A, 19.0)));
        f.reject(21, f.batch(21, Map.of(A, 26.0)));
        f.reject(22, f.batch(22, Map.of(A, 301.0)));
        f.reject(96, f.batch(96, Map.of(A, 120.0)));
    }

    @Test void advanceBoundIsAlsoCheckedByTheLedgerBeforePublication() {
        Fixture f = new Fixture(false, false, false, true, null, List.of(), new Limits(10, 20, 5));
        f.reject(20, f.batch(20, Map.of(A, 89.0)));
        f.reject(21, f.batch(21, Map.of(A, 121.0)));
    }

    @Test void combinedControlPointChecksIncludeTheUnchangedOtherBranch() {
        ControlPoint point = new ControlPoint(new ControlPointKey("shared", "outbound"),
                List.of(new Call(A, 0), new Call(C, 0)), 50, 150, Optional.empty());
        Fixture f = new Fixture(false, false, true, true, List.of(point), List.of(), LIMITS);
        PrecommitRejected failure = f.reject(20, f.batch(20, Map.of(A, 180.0)));
        Comparison comparison = failure.audit().constraints().orElseThrow();
        assertEquals(1, comparison.coverage().registeredHeadwayPairs());
        assertFalse(comparison.candidate().satisfiesRegisteredConstraints());
        assertTrue(comparison.candidate().violations().stream().anyMatch(c -> c.kind() == Kind.MINIMUM_HEADWAY));
        assertEquals(200, f.otherDriver.getActivityEndTime());
    }

    @Test void explicitSameVehicleConnectionRejectsBeforeAnyDriverOrLedgerChange() {
        VehicleConnection connection = new VehicleConnection("A-to-B", A, B, 100, 50);
        Fixture f = new Fixture(true, false, false, true, List.of(), List.of(connection), LIMITS);
        PrecommitRejected failure = f.reject(20, f.batch(20, Map.of(B, 240.0)));
        assertEquals(Kind.MINIMUM_VEHICLE_TRANSFER,
                failure.audit().constraints().orElseThrow().candidate().violations().getFirst().kind());
    }

    @Test void currentAdvanceDelayBaselineRestorationAndIdentityHaveExactQueueCounts() {
        Fixture f = new Fixture(false, false, false);
        ApplicationAudit first = f.submit(20, Map.of(A, 80.0));
        assertEquals(Status.COMMITTED_APPLIED, first.status());
        assertEquals(80, f.driver.getActivityEndTime());
        assertEquals(1, f.queues());
        f.submit(21, Map.of(A, 120.0));
        assertEquals(120, f.driver.getActivityEndTime());
        f.submit(22, Map.of(A, 100.0));
        assertEquals(100, f.driver.getActivityEndTime());
        assertEquals(3, f.queues());
        ApplicationAudit identity = f.submit(23, Map.of(A, 100.0));
        assertTrue(identity.decision().orElseThrow().changes().isEmpty());
        assertTrue(identity.refreshedTrips().isEmpty());
        assertEquals(3, f.queues());
        assertEquals(100, f.work.getUmlaufStuecke().getFirst().getDeparture().getDepartureTime());
        assertEquals(EvidenceScope.REGISTERED_PLANNED_TIMES_ONLY, first.scope());
        Coverage coverage = first.constraints().orElseThrow().coverage();
        assertTrue(coverage.anyConstraintsRegistered());
        assertFalse(coverage.sharedResourceCoverageVerified());
        assertFalse(coverage.actualVehicleCirculationVerified());
    }

    @Test void sameDriverCurrentAndFutureChangesPublishTogetherButRequeueOnce() {
        Fixture f = new Fixture(true, false, false);
        ApplicationAudit audit = f.submit(20, Map.of(A, 80.0, B, 250.0));
        assertEquals(2, audit.refreshedTrips().size());
        assertEquals(1, audit.requeuedDriverIds().size());
        assertEquals(1, f.queues());
        f.driver.endActivityAndComputeNextState(80);
        f.driver.endLegAndComputeNextState(150);
        assertEquals(250, f.driver.getActivityEndTime());
        assertEquals(300, f.work.getUmlaufStuecke().getLast().getDeparture().getDepartureTime());
    }

    @Test void futureDuringNativeLegCachesWithoutMovingCurrentAndStartedTripCannotChange() {
        Fixture f = new Fixture(true, false, false);
        f.driver.endActivityAndComputeNextState(100); // Deliberately no ledger start: native position must protect A too.
        ApplicationAudit audit = f.submit(110, Map.of(B, 250.0));
        assertEquals(RefreshOutcome.FUTURE_ONLY, audit.refreshedTrips().getFirst().result().outcome());
        assertEquals(100, f.driver.getActivityEndTime());
        assertEquals(0, f.queues());
        f.reject(111, f.batch(111, Map.of(A, 140.0)));
        f.driver.endLegAndComputeNextState(275);
        assertEquals(275, f.driver.getActivityEndTime(), "Physical ready time remains the lower bound");
        assertEquals(250, f.ledger.snapshot().commitments().get(B).doubleValue());
    }

    @Test void wendenPositionProtectsEarlierAAndAllowsFutureBCacheWithoutRequeue() {
        Fixture f = new Fixture(true, true, false);
        f.driver.endActivityAndComputeNextState(100);
        f.driver.endLegAndComputeNextState(150);
        assertTrue(f.driver.currentExecutionState().trip().isEmpty());
        assertFalse(f.driver.currentExecutionState().terminal());
        f.reject(155, f.batch(155, Map.of(A, 180.0, B, 250.0)));
        ApplicationAudit pending = f.submit(156, Map.of(B, 250.0));
        assertEquals(RefreshOutcome.FUTURE_ONLY, pending.refreshedTrips().getFirst().result().outcome());
        f.driver.endActivityAndComputeNextState(160);
        ApplicationAudit running = f.submit(170, Map.of(B, 260.0));
        assertEquals(RefreshOutcome.FUTURE_ONLY, running.refreshedTrips().getFirst().result().outcome());
        assertEquals(0, f.queues());
        f.driver.endLegAndComputeNextState(280);
        assertEquals(Optional.of(B), f.driver.currentExecutionState().trip());
        assertEquals(280, f.driver.getActivityEndTime());
    }

    @Test void blockedCommitmentStaysInfiniteAndCannotReleaseTheNativeChain() {
        Fixture f = new Fixture(false, false, false);
        f.driver.setWaitForDeparture();
        ApplicationAudit audit = f.submit(20, Map.of(A, 80.0));
        assertEquals(RefreshOutcome.BLOCKED, audit.refreshedTrips().getFirst().result().outcome());
        assertEquals(Double.POSITIVE_INFINITY, f.driver.getActivityEndTime());
        assertEquals(0, f.queues());
        f.driver.setReadyForDeparture(90); // Native caller, not the bridge, owns release and its queue action.
        assertEquals(90, f.driver.getActivityEndTime());
        assertEquals(0, f.queues());
    }

    @Test void terminalIdentityNeverRefreshesOrRevivesAndChangedTerminalTripIsRejected() {
        Fixture f = new Fixture(false, false, false);
        f.driver.endActivityAndComputeNextState(100);
        f.driver.endLegAndComputeNextState(130);
        assertTrue(f.driver.currentExecutionState().terminal());
        ApplicationAudit identity = f.submit(140, Map.of(A, 100.0));
        assertTrue(identity.decision().orElseThrow().changes().isEmpty());
        assertTrue(identity.refreshedTrips().isEmpty());
        f.reject(141, f.batch(141, Map.of(A, 180.0)));
        assertEquals(Double.POSITIVE_INFINITY, f.driver.getActivityEndTime());
        assertEquals(0, f.queues());
    }

    @Test void abortedTargetIsRejectedBeforeCommitEvenIfItsLedgerTripWasNotMarkedStarted() {
        Fixture f = new Fixture(false, false, false);
        f.driver.setStateToAbort(10);
        f.reject(20, f.batch(20, Map.of(A, 80.0)));
    }

    @Test void incompleteBaselineRegistrationAndMissingNativeAgentBlockTheStep() {
        Fixture f = new Fixture(false, false, true, false, null, List.of(), LIMITS);
        Snapshot initial = f.ledger.snapshot();
        assertThrows(IllegalStateException.class, () -> f.step(10, b -> fail("Controller must not open")));
        f.create(f.otherWork);
        f.agents.remove(f.driver.getId());
        assertThrows(IllegalStateException.class, () -> f.step(11, b -> fail("Controller must not open")));
        assertEquals(initial, f.ledger.snapshot());
        assertEquals(0, f.queues());
        f.agents.put(f.driver.getId(), f.driver);
        f.submit(20, Map.of(A, 80.0));
    }

    @Test void replacedNativeAgentCannotReceiveAnAcceptedBatch() {
        Fixture f = new Fixture(false, false, false);
        f.step(10, b -> { });
        f.agents.put(f.driver.getId(), mock(MobsimAgent.class));
        f.reject(20, f.batch(20, Map.of(A, 80.0)));
    }

    @Test void outsideCallbackWrongQSimAndMismatchedStepClockNeverOpenSubmission() {
        Fixture f = new Fixture(false, false, false);
        Snapshot before = f.ledger.snapshot();
        assertThrows(PrecommitRejected.class, () -> f.bridge.submit(f.batch(20, Map.of(A, 80.0))));
        f.timer.setTime(20);
        assertThrows(IllegalStateException.class, () -> f.bridge.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(mock(QSim.class), 20)));
        assertThrows(IllegalStateException.class, () -> f.bridge.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(f.qsim, 21)));
        f.submit(20, Map.of(A, 80.0));
        assertThrows(PrecommitRejected.class, () -> f.bridge.submit(f.batch(20, Map.of(A, 90.0))));
        assertEquals(before.version().revision() + 1, f.ledger.snapshot().version().revision());
        assertEquals(1, f.queues());
    }

    @Test void wrongThreadCannotSubmitEvenWhileTheCorrectThreadCallbackIsOpen() {
        Fixture f = new Fixture(false, false, false);
        Snapshot before = f.ledger.snapshot();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        f.step(20, b -> {
            Batch batch = f.batch(20, Map.of(A, 80.0));
            Thread foreign = new Thread(() -> {
                try { b.submit(batch); }
                catch (Throwable failure) { thrown.set(failure); }
            }, "bridge-foreign-unit-thread");
            foreign.start();
            try { foreign.join(2_000); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            assertFalse(foreign.isAlive(), "Foreign-thread rejection must not block behind the QSim thread");
        });
        assertInstanceOf(PrecommitRejected.class, thrown.get());
        assertEquals(before, f.ledger.snapshot());
        assertEquals(100, f.driver.getActivityEndTime());
        assertEquals(0, f.queues());
    }

    @Test void reentrantStepAndNativeQueueSubmissionAreRejectedWithoutASecondCommit() {
        Fixture f = new Fixture(false, false, false);
        doAnswer(call -> {
            Snapshot committed = f.ledger.snapshot();
            assertThrows(PrecommitRejected.class, () -> f.bridge.submit(f.batch(20, Map.of(A, 90.0))));
            assertEquals(committed, f.ledger.snapshot());
            return null;
        }).when(f.qsim).rescheduleActivityEnd(f.driver);
        f.step(20, b -> {
            assertThrows(IllegalStateException.class, () -> b.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(f.qsim, 20)));
            b.submit(f.batch(20, Map.of(A, 80.0)));
        });
        assertEquals(1, f.ledger.history().size());
        assertEquals(1, f.queues());
        assertFalse(f.bridge.isPoisoned());
        assertThrows(PrecommitRejected.class, () -> f.bridge.submit(f.batch(20, Map.of(A, 90.0))));
    }

    @Test void unexpectedCallbackFailureClosesTheWindowAndPoisonsTheBridge() {
        Fixture f = new Fixture(false, false, false);
        Snapshot before = f.ledger.snapshot();
        assertThrows(IllegalStateException.class, () -> f.step(20, b -> { throw new IllegalStateException("controller failure"); }));
        assertTrue(f.bridge.isPoisoned());
        assertThrows(PrecommitRejected.class, () -> f.bridge.submit(f.batch(20, Map.of(A, 80.0))));
        assertEquals(before, f.ledger.snapshot());
        assertEquals(0, f.queues());
    }

    @Test void lateNativeQueueFailureRecordsPartialApplicationAndNeverClaimsRollback() {
        Fixture f = new Fixture(false, false, true);
        doThrow(new IllegalStateException("injected second queue failure")).when(f.qsim).rescheduleActivityEnd(f.otherDriver);
        NativeApplicationFailure failure = assertThrows(NativeApplicationFailure.class,
                () -> f.submit(20, Map.of(A, 110.0, C, 210.0)));
        ApplicationAudit audit = failure.audit();
        assertEquals(Status.FAILED_NATIVE_APPLY, audit.status());
        assertEquals(2, audit.decision().orElseThrow().changes().size());
        assertEquals(2, audit.refreshedTrips().size());
        assertEquals(List.of(f.driver.getId().toString()), audit.requeuedDriverIds());
        assertEquals("RESCHEDULE:" + f.otherDriver.getId(), audit.stage());
        assertEquals(110, f.driver.getActivityEndTime());
        assertEquals(210, f.otherDriver.getActivityEndTime());
        assertEquals(1, f.ledger.history().size(), "Accepted ledger decision was not rolled back");
        assertEquals(110, f.ledger.snapshot().commitments().get(A).doubleValue());
        assertEquals(210, f.ledger.snapshot().commitments().get(C).doubleValue());
        assertTrue(f.bridge.isPoisoned());
        assertThrows(IllegalStateException.class, () -> f.step(21, b -> fail("Poisoned bridge must never re-open")));
        assertThrows(UnsupportedOperationException.class, () -> audit.requeuedDriverIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> f.bridge.history().clear());
        assertEquals(2, f.queues(), "Only the first call returned successfully; the second threw");
    }

    @Test void ledgerEpochResetCannotLeakIntoExistingNativeDrivers() {
        Fixture f = new Fixture(false, false, false);
        f.submit(20, Map.of(A, 80.0));
        f.ledger.beginIteration(20, 0, true);
        Snapshot reset = f.ledger.snapshot();
        assertThrows(IllegalStateException.class, () -> f.step(21, b -> fail("Old bridge must reject the new epoch")));
        assertEquals(reset, f.ledger.snapshot());
        assertEquals(80, f.driver.getActivityEndTime(), "Old driver is not silently rebound to the new day");
        assertEquals(1, f.queues());
    }

    @Test void externalLedgerCommitIsDetectedInsteadOfSilentlyReplacingThePrivateProvider() {
        Fixture f = new Fixture(false, false, false);
        f.ledger.commit(f.batch(10, Map.of(A, 80.0)));
        Snapshot external = f.ledger.snapshot();
        assertThrows(IllegalStateException.class, () -> f.step(20, b -> fail("External commitment must not be adopted")));
        assertEquals(external, f.ledger.snapshot());
        assertEquals(100, f.driver.getActivityEndTime());
        assertEquals(0, f.queues());
    }

    @Test void changedNativeVehicleIdentityIsRejectedWithoutConstructingAnotherDriver() {
        Fixture f = new Fixture(true, false, false);
        f.work.setVehicleId(Id.createVehicleId("unexpected-replacement-vehicle"));
        Snapshot before = f.ledger.snapshot();
        int persons = Id.getNumberOfIds(Person.class);
        assertThrows(IllegalStateException.class, () -> f.step(20, b -> fail("Changed fixed vehicle identity must block")));
        assertEquals(before, f.ledger.snapshot());
        assertEquals(persons, Id.getNumberOfIds(Person.class));
        assertEquals(0, f.queues());
    }

    @Test void bridgeRequiresActiveNonzeroFreshEpochAndBuildsCheckerFromItsOwnLedger() {
        Fixture f = new Fixture(false, false, false);
        for (Limits limits : List.of(new Limits(0, 0, 0), LIMITS)) {
            DispatchCommitmentState other = new DispatchCommitmentState(List.of(new ScheduledTrip(A, "vehicle", 100)), limits);
            other.beginIteration(19, 0, true);
            assertThrows(IllegalStateException.class, () -> new ControlledTransitDispatchBridge(f.qsim, other, 20, List.of(), List.of(), (b, n) -> { }));
        }
        DispatchCommitmentState disabled = new DispatchCommitmentState(List.of(new ScheduledTrip(A, "vehicle", 100)), LIMITS);
        disabled.beginIteration(20, 0, false);
        assertThrows(IllegalStateException.class, () -> new ControlledTransitDispatchBridge(f.qsim, disabled, 20, List.of(), List.of(), (b, n) -> { }));
        DispatchCommitmentState zero = new DispatchCommitmentState(List.of(new ScheduledTrip(A, "vehicle", 100)), new Limits(0, 0, 0));
        zero.beginIteration(20, 0, true);
        assertThrows(IllegalStateException.class, () -> new ControlledTransitDispatchBridge(f.qsim, zero, 20, List.of(), List.of(), (b, n) -> { }));
        f.ledger.advanceTo(1);
        assertThrows(IllegalStateException.class, () -> new ControlledTransitDispatchBridge(f.qsim, f.ledger, 20, List.of(), List.of(), (b, n) -> { }));
        assertEquals(0, f.queues());
    }

    @Test void emptyConstraintRegistrationsAreReportedButCannotCommitEvenAnIdentityBatch() {
        Fixture f = new Fixture(false, false, false, true, List.of(), List.of(), LIMITS);
        PrecommitRejected changed = f.reject(20, f.batch(20, Map.of(A, 80.0)));
        assertFalse(changed.audit().constraints().orElseThrow().coverage().anyConstraintsRegistered());
        assertTrue(changed.audit().constraints().orElseThrow().candidate().satisfiesRegisteredConstraints(),
                "Vacuous pure-checker truth is not accepted by the bridge");
        f.reject(21, f.batch(21, Map.of(A, 100.0)));
        assertTrue(f.ledger.history().isEmpty());
    }
}
