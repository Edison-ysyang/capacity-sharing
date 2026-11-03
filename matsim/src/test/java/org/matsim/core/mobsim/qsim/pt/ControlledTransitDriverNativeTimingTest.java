package org.matsim.core.mobsim.qsim.pt;

import com.google.inject.multibindings.OptionalBinder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.PrepareForSimUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.DispatchConstraintChecker;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit opt-in native QSim timing microfixtures, not formal ISMT simulations.
 *
 * <p>Uses real default QSim engines, a test-local driver factory and in-memory
 * events. The empty passenger population isolates the native departure seam:
 * this is not the production controller OFF/zero scientific-output gate,
 * a capacity interaction test, or validation of a full operating policy.</p>
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDriverNativeTimingTest {
    private static final double BASELINE = 100;
    private static final double FIRST_STOP_OFFSET = 10;
    private static final double DECISION_TIME = 20;
    private static final double TERMINAL_CHECK_TIME = 200;
    private static final TripKey TRIP = new TripKey("timing-line", "timing-route", "timing-departure");

    private enum Mode {
        NATIVE, CONTROLLED_UNCHANGED, ADVANCE, DELAY,
        FACTORY_OFF, FACTORY_ZERO, FACTORY_COMMON_19,
        FACTORY_ACTIVE_20, FACTORY_ACTIVE_150, FACTORY_ACTIVE_199,
        BRIDGE_ADVANCE, BRIDGE_DELAY, BRIDGE_REJECTED, BRIDGE_ROLLING
    }

    @Test
    void rollingBridgeReplacesQueuedTimesRestoresBaselineAndKeepsRejectedAndIdentityBatchesHarmless() {
        RunResult rolling = run(Mode.BRIDGE_ROLLING);
        assertEquals(90, rolling.driverStartSeconds(), 0.0);
        assertEquals(100, rolling.firstStopDepartureSeconds(), 0.0);
        assertEquals(-10, rolling.firstStopBaselineDelaySeconds(), 0.0);
        assertEquals(5, rolling.acceptedDecisions(), "Four changes plus one accepted identity batch");
        assertEquals(4, rolling.controllerReschedules(), "Neither rejection nor identity may requeue");
    }

    @Test
    void wholeBridgeAppliesAcceptedAdvanceAndDelayToRealFirstStopEvents() {
        RunResult advance = run(Mode.BRIDGE_ADVANCE);
        RunResult delay = run(Mode.BRIDGE_DELAY);
        assertEquals(80, advance.driverStartSeconds(), 0.0);
        assertEquals(90, advance.firstStopDepartureSeconds(), 0.0);
        assertEquals(-20, advance.firstStopBaselineDelaySeconds(), 0.0);
        assertEquals(120, delay.driverStartSeconds(), 0.0);
        assertEquals(130, delay.firstStopDepartureSeconds(), 0.0);
        assertEquals(20, delay.firstStopBaselineDelaySeconds(), 0.0);
        for (RunResult result : List.of(advance, delay)) {
            assertEquals(1, result.acceptedDecisions());
            assertEquals(1, result.controllerReschedules());
        }
    }

    @Test
    void bridgeConstraintRejectionLeavesTheCompleteNativeEventSequenceUnchanged() {
        RunResult baseline = run(Mode.NATIVE);
        RunResult rejected = run(Mode.BRIDGE_REJECTED);
        assertEquals(baseline.nativeEvents(), rejected.nativeEvents());
        assertEquals(0, rejected.acceptedDecisions());
        assertEquals(0, rejected.controllerReschedules());
    }

    @Test
    void factoryBypassAndLateActivePhasesRetainNativeEventsWithoutAnAcceptedAdjustment() {
        RunResult nativeRun = run(Mode.NATIVE);
        for (Mode mode : List.of(Mode.FACTORY_OFF, Mode.FACTORY_ZERO, Mode.FACTORY_COMMON_19,
                Mode.FACTORY_ACTIVE_20, Mode.FACTORY_ACTIVE_150, Mode.FACTORY_ACTIVE_199)) {
            RunResult candidate = run(mode);
            assertEquals(nativeRun.nativeEvents(), candidate.nativeEvents(), mode.toString());
            assertEquals(0, candidate.controllerReschedules(), mode.toString());
            assertEquals(0, candidate.acceptedDecisions(), mode.toString());
        }
    }

    @Test
    void noOverrideMatchesDefaultFactoryNativeEventSequence() {
        RunResult nativeRun = run(Mode.NATIVE);
        RunResult unchanged = run(Mode.CONTROLLED_UNCHANGED);

        assertEquals(nativeRun.nativeEvents(), unchanged.nativeEvents(),
                "The complete in-memory native event records, not only means, must match");
        assertEquals(BASELINE, unchanged.driverStartSeconds(), 0.0);
        assertEquals(BASELINE + FIRST_STOP_OFFSET, unchanged.firstStopDepartureSeconds(), 0.0);
        assertEquals(0, unchanged.controllerReschedules());
        assertEquals(0, unchanged.acceptedDecisions());
    }

    @Test
    void acceptedAdvanceRequeuesCurrentActivityAndReallyDepartsFirstStopEarlier() {
        RunResult result = run(Mode.ADVANCE);

        assertEquals(80, result.driverStartSeconds(), 0.0);
        assertEquals(90, result.firstStopDepartureSeconds(), 0.0);
        assertEquals(-20, result.firstStopBaselineDelaySeconds(), 0.0,
                "Native delay must retain the published baseline, not be rewritten to zero");
        assertEquals(1, result.controllerReschedules());
        assertEquals(1, result.acceptedDecisions());
    }

    @Test
    void acceptedDelayRequeuesCurrentActivityAndReallyDepartsFirstStopLater() {
        RunResult result = run(Mode.DELAY);

        assertEquals(120, result.driverStartSeconds(), 0.0);
        assertEquals(130, result.firstStopDepartureSeconds(), 0.0);
        assertEquals(20, result.firstStopBaselineDelaySeconds(), 0.0);
        assertEquals(1, result.controllerReschedules());
        assertEquals(1, result.acceptedDecisions());
    }

    private RunResult run(Mode mode) {
        Fixture fixture = fixture();
        DispatchCommitmentState ledger = new DispatchCommitmentState(
                List.of(new DispatchCommitmentState.ScheduledTrip(TRIP, fixture.vehicle().getId().toString(), BASELINE)),
                new DispatchCommitmentState.Limits(30, 30, 10));
        ledger.beginIteration(20, 0, true);
        Map<TripKey, ControlledTransitDriverAgent.AcceptedCommitment> accepted = new LinkedHashMap<>();
        List<AbstractTransitDriverAgent> constructed = new ArrayList<>();
        List<Event> nativeEvents = new ArrayList<>();
        List<NativeEvent> nativeEventRecords = new ArrayList<>();
        boolean bridgeMode = mode == Mode.BRIDGE_ADVANCE || mode == Mode.BRIDGE_DELAY
                || mode == Mode.BRIDGE_REJECTED || mode == Mode.BRIDGE_ROLLING;
        AtomicReference<ControlledTransitDispatchBridge> dispatchBridge = new AtomicReference<>();
        int[] contextRequests = {0};
        boolean bypassMode = mode == Mode.FACTORY_OFF || mode == Mode.FACTORY_ZERO || mode == Mode.FACTORY_COMMON_19;
        int factoryIteration = switch (mode) {
            case FACTORY_COMMON_19 -> 19;
            case FACTORY_ACTIVE_150 -> 150;
            case FACTORY_ACTIVE_199 -> 199;
            default -> 20;
        };
        ControlledTransitDriverAgentFactory lazyFactory = switch (mode) {
            case FACTORY_OFF, FACTORY_ZERO, FACTORY_COMMON_19,
                 FACTORY_ACTIVE_20, FACTORY_ACTIVE_150, FACTORY_ACTIVE_199 ->
                    new ControlledTransitDriverAgentFactory(mode != Mode.FACTORY_OFF, factoryIteration, 20,
                            new DispatchCommitmentState.Limits(mode == Mode.FACTORY_ZERO ? 0 : 30,
                                    mode == Mode.FACTORY_ZERO ? 0 : 30, 10), () -> {
                                contextRequests[0]++;
                                assertFalse(bypassMode, "An inactive factory must never obtain a control context");
                                return new ControlledTransitDriverAgentFactory.ActiveContext(
                                        key -> Optional.ofNullable(accepted.get(key)), 0,
                                        ledger.snapshot().version().epoch());
                            });
            default -> null;
        };

        EventsManager events = EventsUtils.createEventsManager();
        events.addHandler((BasicEventHandler) event -> {
            nativeEvents.add(event);
            nativeEventRecords.add(new NativeEvent(event.getClass().getName(), event.getTime(),
                    Map.copyOf(new TreeMap<>(event.getAttributes()))));
            if (event instanceof TransitDriverStartsEvent start) {
                assertEquals(fixture.vehicle().getId(), start.getVehicleId());
                assertEquals(TRIP.line(), start.getTransitLineId().toString());
                assertEquals(TRIP.route(), start.getTransitRouteId().toString());
                assertEquals(TRIP.departure(), start.getDepartureId().toString());
                // This records a real event; neither the fixture nor the adapter fabricates it.
                ledger.recordStarted(TRIP, start.getTime(), start.getTime());
            }
        });

        TransitDriverAgentFactory testFactory = (umlauf, internal, tracker) -> {
            assertEquals(1, umlauf.getUmlaufStuecke().size(), "This fixture has one original vehicle work segment");
            assertEquals(fixture.vehicle().getId(), umlauf.getVehicleId());
            AbstractTransitDriverAgent driver;
            if (mode == Mode.NATIVE) {
                driver = new DefaultTransitDriverAgentFactory().createTransitDriver(umlauf, internal, tracker);
            } else if (bridgeMode) {
                driver = dispatchBridge.get().createTransitDriver(umlauf, internal, tracker);
            } else if (lazyFactory != null) {
                driver = lazyFactory.createTransitDriver(umlauf, internal, tracker);
            } else {
                driver = new ControlledTransitDriverAgent(umlauf, TransportMode.car, tracker, internal,
                        key -> Optional.ofNullable(accepted.get(key)), 0, ledger.snapshot().version().epoch());
            }
            constructed.add(driver);
            return driver;
        };

        PrepareForSimUtils.createDefaultPrepareForSim(fixture.scenario()).run();
        QSim qsim = new QSimBuilder(fixture.scenario().getConfig()).useDefaults()
                .addQSimModule(new AbstractQSimModule() {
                    @Override
                    protected void configureQSim() {
                        OptionalBinder.newOptionalBinder(binder(), TransitDriverAgentFactory.class)
                                .setBinding().toInstance(testFactory);
                    }
                }).build(fixture.scenario(), events);

        int[] decisionVisits = {0};
        int[] terminalVisits = {0};
        int[] controllerReschedules = {0};
        boolean[] priorStepObserved = {false};
        qsim.addQueueSimulationListeners((MobsimBeforeSimStepListener) step -> {
            double now = step.getSimulationTime();
            if (now == DECISION_TIME - 1) priorStepObserved[0] = true;
            if (now == DECISION_TIME) {
                decisionVisits[0]++;
                assertTrue(priorStepObserved[0], "Change must occur after initial queueing and earlier simulation steps");
                assertEquals(1, constructed.size());
                AbstractTransitDriverAgent driver = constructed.getFirst();
                assertSame(driver, qsim.getAgents().get(driver.getId()));
                assertSame(driver, qsim.getVehicles().get(fixture.vehicle().getId()).getDriver());
                assertEquals(MobsimAgent.State.ACTIVITY, driver.getState());
                // Listener registration order is not an execution-order contract.
                // In bridge modes, the unchanged deadline is checked inside its callback before submit.
                if (!bridgeMode) assertEquals(BASELINE, driver.getActivityEndTime(), 0.0);
                assertEquals(0, nativeEvents.stream().filter(TransitDriverStartsEvent.class::isInstance).count());
                if (mode == Mode.ADVANCE || mode == Mode.DELAY) {
                    double candidate = mode == Mode.ADVANCE ? 80 : 120;
                    DispatchCommitmentState.DecisionRecord decision = ledger.commit(new DispatchCommitmentState.Batch(
                            ledger.snapshot().version(), now, List.of(), Map.of(TRIP, candidate)));
                    assertEquals(1, decision.changes().size());
                    // Consume an actual accepted ledger change. No full infrastructure-policy
                    // feasibility claim follows from this single-trip commitment fixture.
                    for (DispatchCommitmentState.CommitmentChange change : decision.changes()) {
                        accepted.put(change.key(), new ControlledTransitDriverAgent.AcceptedCommitment(
                                change.committedDepartureSeconds(), decision.resultingVersion()));
                    }
                    var refresh = ((ControlledTransitDriverAgent) driver).refreshAcceptedCommitment(TRIP, now);
                    assertTrue(refresh.requiresReschedule());
                    assertEquals(BASELINE, refresh.previousDeadlineSeconds(), 0.0);
                    assertEquals(candidate, refresh.effectiveDeadlineSeconds(), 0.0);
                    qsim.rescheduleActivityEnd(driver);
                    controllerReschedules[0]++;
                }
                fixture.assertPublishedSupplyUnchanged();
            }
            if (now == TERMINAL_CHECK_TIME) {
                terminalVisits[0]++;
                assertTerminal(constructed.getFirst(), qsim);
                if (!bridgeMode && constructed.getFirst() instanceof ControlledTransitDriverAgent controlled) {
                    int eventCountBefore = nativeEvents.size();
                    var refresh = controlled.refreshAcceptedCommitment(TRIP, now);
                    assertFalse(refresh.requiresReschedule(), "An unchanged old trip must not resurrect its final activity");
                    assertEquals(Double.POSITIVE_INFINITY, refresh.effectiveDeadlineSeconds());
                    assertEquals(eventCountBefore, nativeEvents.size(), "Refresh must not generate fake native events");
                    assertTerminal(controlled, qsim);
                }
            }
        });

        if (bridgeMode) {
            // Explicit synthetic first/last bounds exercise the real registered checker.
            // This single-service fixture makes no shared-resource or full-policy claim.
            var point = new DispatchConstraintChecker.ControlPoint(
                    new DispatchConstraintChecker.ControlPointKey("timing-first", "synthetic-outbound"),
                    List.of(new DispatchConstraintChecker.Call(TRIP, FIRST_STOP_OFFSET)), 0, 0,
                    Optional.of(new DispatchConstraintChecker.FirstLastWindow(90, 130, 90, 130)));
            ControlledTransitDispatchBridge bridge = new ControlledTransitDispatchBridge(qsim, ledger, 20,
                    List.of(point), List.of(), (activeBridge, now) -> {
                        boolean rollingStep = mode == Mode.BRIDGE_ROLLING
                                && List.of(20.0, 30.0, 40.0, 50.0, 60.0, 70.0).contains(now);
                        if (now != DECISION_TIME && !rollingStep) return;
                        var before = ledger.snapshot();
                        assertTrue(priorStepObserved[0], "Initial native queueing and the prior step must have run");
                        assertEquals(1, constructed.size());
                        var driver = constructed.getFirst();
                        assertSame(driver, qsim.getAgents().get(driver.getId()));
                        assertSame(driver, qsim.getVehicles().get(fixture.vehicle().getId()).getDriver());
                        assertEquals(MobsimAgent.State.ACTIVITY, driver.getState());
                        assertEquals(before.commitments().get(TRIP), driver.getActivityEndTime(), 0.0);
                        if (now == DECISION_TIME) assertEquals(BASELINE, driver.getActivityEndTime(), 0.0);
                        assertEquals(0, nativeEvents.stream().filter(TransitDriverStartsEvent.class::isInstance).count());
                        double proposed = switch (mode) {
                            case BRIDGE_ADVANCE -> 80;
                            case BRIDGE_DELAY -> 120;
                            case BRIDGE_REJECTED -> 50;
                            case BRIDGE_ROLLING -> switch ((int) now) {
                                case 20 -> 80;
                                case 30 -> 120;
                                case 40, 60 -> 100;
                                case 50 -> 75; // Inside displacement bounds, outside the registered service window.
                                case 70 -> 90;
                                default -> throw new AssertionError("Unexpected rolling decision time");
                            };
                            default -> throw new AssertionError("Unexpected bridge mode");
                        };
                        var batch = new DispatchCommitmentState.Batch(before.version(), now, List.of(), Map.of(TRIP, proposed));
                        if (mode == Mode.BRIDGE_REJECTED || mode == Mode.BRIDGE_ROLLING && now == 50) {
                            var historyBefore = ledger.history();
                            var rejected = assertThrows(ControlledTransitDispatchBridge.PrecommitRejected.class,
                                    () -> activeBridge.submit(batch));
                            assertEquals(ControlledTransitDispatchBridge.Status.REJECTED_PRECOMMIT, rejected.audit().status());
                            assertFalse(rejected.audit().constraints().orElseThrow().candidate().satisfiesRegisteredConstraints());
                            assertEquals(before, ledger.snapshot());
                            assertEquals(historyBefore, ledger.history());
                            assertEquals(before.commitments().get(TRIP), driver.getActivityEndTime(), 0.0);
                            assertTrue(rejected.audit().refreshedTrips().isEmpty());
                            assertTrue(rejected.audit().requeuedDriverIds().isEmpty());
                        } else {
                            var applied = activeBridge.submit(batch);
                            assertEquals(ControlledTransitDispatchBridge.Status.COMMITTED_APPLIED, applied.status());
                            boolean identity = before.commitments().get(TRIP).doubleValue() == proposed;
                            assertEquals(identity ? 0 : 1, applied.decision().orElseThrow().changes().size());
                            assertEquals(identity ? 0 : 1, applied.refreshedTrips().size());
                            assertEquals(identity ? List.of() : List.of(driver.getId().toString()), applied.requeuedDriverIds());
                            assertEquals(proposed, driver.getActivityEndTime(), 0.0);
                            assertTrue(applied.constraints().orElseThrow().candidate().satisfiesRegisteredConstraints());
                            assertFalse(applied.constraints().orElseThrow().coverage().sharedResourceCoverageVerified());
                            controllerReschedules[0] += applied.requeuedDriverIds().size();
                        }
                        fixture.assertPublishedSupplyUnchanged();
                    });
            dispatchBridge.set(bridge);
            // Check pre-submit state inside the control callback, independently of listener ordering.
            // This bridge owns all publication/refresh/requeue, not the test caller.
            qsim.addQueueSimulationListeners(bridge);
        }

        qsim.run(); // The only producer of movement, starts and facility events in this fixture.

        assertEquals(1, decisionVisits[0], "Do not silently pass if the decision callback never ran");
        assertEquals(1, terminalVisits[0], "Explicit end-time continuation must exercise the terminal callback");
        assertEquals(1, constructed.size());
        if (bridgeMode) {
            assertFalse(dispatchBridge.get().isPoisoned());
            assertEquals(mode == Mode.BRIDGE_ROLLING ? 6 : 1, dispatchBridge.get().history().size());
        }
        if (lazyFactory != null) {
            assertEquals(bypassMode ? 0 : 1, contextRequests[0]);
            assertEquals(bypassMode ? TransitDriverAgentImpl.class : ControlledTransitDriverAgent.class,
                    constructed.getFirst().getClass(),
                    "Exact bypass/active driver type must follow control activation, not passenger innovation closure");
        }
        assertTerminal(constructed.getFirst(), qsim);
        fixture.assertPublishedSupplyUnchanged();
        assertEquals(1, qsim.getVehicles().size());
        assertEquals(0, nativeEvents.stream().filter(PersonStuckEvent.class::isInstance).count());
        List<TransitDriverStartsEvent> starts = nativeEvents.stream().filter(TransitDriverStartsEvent.class::isInstance)
                .map(TransitDriverStartsEvent.class::cast).toList();
        List<VehicleDepartsAtFacilityEvent> departures = nativeEvents.stream().filter(VehicleDepartsAtFacilityEvent.class::isInstance)
                .map(VehicleDepartsAtFacilityEvent.class::cast).toList();
        List<VehicleArrivesAtFacilityEvent> arrivals = nativeEvents.stream().filter(VehicleArrivesAtFacilityEvent.class::isInstance)
                .map(VehicleArrivesAtFacilityEvent.class::cast).toList();
        assertEquals(1, starts.size(), "No extra driver start after completing the original service");
        assertEquals(2, departures.size(), "Exactly the two original stop departures must be real native events");
        assertEquals(2, arrivals.size());
        assertEquals(fixture.firstStop().getId(), departures.getFirst().getFacilityId());
        assertEquals(fixture.lastStop().getId(), departures.getLast().getFacilityId());
        assertEquals(fixture.vehicle().getId(), departures.getFirst().getVehicleId());
        assertEquals(fixture.vehicle().getId(), departures.getLast().getVehicleId());
        assertEquals(FIRST_STOP_OFFSET, departures.getFirst().getTime() - starts.getFirst().getTime(), 0.0,
                "The first-stop execution proof must not be replaced by the earlier driver-start event");
        assertTrue(arrivals.getFirst().getTime() <= departures.getFirst().getTime());
        assertTrue(departures.getLast().getTime() < TERMINAL_CHECK_TIME);
        assertEquals(starts.getFirst().getTime(), ledger.snapshot().startedAtSeconds().get(TRIP), 0.0);

        return new RunResult(List.copyOf(nativeEventRecords), starts.getFirst().getTime(), departures.getFirst().getTime(),
                departures.getFirst().getDelay(), controllerReschedules[0], ledger.history().size());
    }

    private void assertTerminal(AbstractTransitDriverAgent driver, QSim qsim) {
        assertEquals(MobsimAgent.State.ACTIVITY, driver.getState());
        assertNull(driver.getNextPlanElement());
        assertEquals(Double.POSITIVE_INFINITY, driver.getActivityEndTime());
        assertEquals(0, qsim.getAgentCounter().getLiving());
        assertEquals(0, qsim.getAgentCounter().getLost());
        if (driver instanceof ControlledTransitDriverAgent controlled) {
            assertTrue(controlled.currentExecutionState().terminal());
            assertTrue(controlled.currentExecutionState().trip().isEmpty());
        }
    }

    private Fixture fixture() {
        Config config = ConfigUtils.createConfig();
        config.transit().setUseTransit(true);
        config.transit().setUsingTransitInMobsim(true);
        config.qsim().setNumberOfThreads(1);
        config.global().setNumberOfThreads(1);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(240);
        config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        // QSimBuilder and in-memory handlers do not write controller output. Keep
        // the unused configured path workspace-relative, not a global temp path.
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-timing-output")
                .toAbsolutePath().toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        Network network = scenario.getNetwork();
        Node n0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("timing-n0"), new Coord(0, 0));
        Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("timing-n1"), new Coord(100, 0));
        Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("timing-n2"), new Coord(200, 0));
        Link first = NetworkUtils.createAndAddLink(network, Id.createLinkId("timing-l1"), n0, n1, 100, 10, 3_600, 1);
        Link last = NetworkUtils.createAndAddLink(network, Id.createLinkId("timing-l2"), n1, n2, 100, 10, 3_600, 1);
        // Complete a strongly connected car network without changing the service's two-link route.
        Link returnLink = NetworkUtils.createAndAddLink(network, Id.createLinkId("timing-return"), n2, n0, 200, 10, 3_600, 1);
        first.setAllowedModes(Set.of(TransportMode.car));
        last.setAllowedModes(Set.of(TransportMode.car));
        returnLink.setAllowedModes(Set.of(TransportMode.car));
        TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();
        TransitStopFacility firstStop = factory.createTransitStopFacility(Id.create("timing-stop-first", TransitStopFacility.class), new Coord(50, 0), false);
        firstStop.setLinkId(first.getId());
        TransitStopFacility lastStop = factory.createTransitStopFacility(Id.create("timing-stop-last", TransitStopFacility.class), new Coord(150, 0), false);
        lastStop.setLinkId(last.getId());
        scenario.getTransitSchedule().addStopFacility(firstStop);
        scenario.getTransitSchedule().addStopFacility(lastStop);
        TransitRouteStop firstCall = factory.createTransitRouteStop(firstStop, 0, FIRST_STOP_OFFSET);
        firstCall.setAwaitDepartureTime(true);
        TransitRouteStop lastCall = factory.createTransitRouteStop(lastStop, 20, 30);
        lastCall.setAwaitDepartureTime(true);
        NetworkRoute networkRoute = RouteUtils.createLinkNetworkRouteImpl(first.getId(), last.getId());
        networkRoute.setLinkIds(first.getId(), List.of(), last.getId());
        TransitRoute route = factory.createTransitRoute(Id.create(TRIP.route(), TransitRoute.class), networkRoute,
                List.of(firstCall, lastCall), TransportMode.pt);
        TransitLine line = factory.createTransitLine(Id.create(TRIP.line(), TransitLine.class));
        VehicleType type = VehicleUtils.createVehicleType(Id.create("timing-type", VehicleType.class));
        type.getCapacity().setSeats(4);
        type.getCapacity().setStandingRoom(2);
        type.setMaximumVelocity(10);
        type.setPcuEquivalents(1);
        Vehicle vehicle = VehicleUtils.createVehicle(Id.createVehicleId("timing-vehicle"), type);
        scenario.getTransitVehicles().addVehicleType(type);
        scenario.getTransitVehicles().addVehicle(vehicle);
        Departure departure = factory.createDeparture(Id.create(TRIP.departure(), Departure.class), BASELINE);
        departure.setVehicleId(vehicle.getId());
        route.addDeparture(departure);
        line.addRoute(route);
        scenario.getTransitSchedule().addTransitLine(line);
        return new Fixture(scenario, line, route, departure, networkRoute, firstStop, lastStop, firstCall, lastCall, vehicle, type);
    }

    private record Fixture(Scenario scenario, TransitLine line, TransitRoute route, Departure departure,
                           NetworkRoute networkRoute, TransitStopFacility firstStop, TransitStopFacility lastStop,
                           TransitRouteStop firstCall, TransitRouteStop lastCall, Vehicle vehicle, VehicleType type) {
        void assertPublishedSupplyUnchanged() {
            assertEquals(1, scenario.getTransitSchedule().getTransitLines().size());
            assertSame(line, scenario.getTransitSchedule().getTransitLines().get(line.getId()));
            assertEquals(1, line.getRoutes().size());
            assertSame(route, line.getRoutes().get(route.getId()));
            assertEquals(1, route.getDepartures().size());
            assertSame(departure, route.getDepartures().get(departure.getId()));
            assertEquals(BASELINE, departure.getDepartureTime(), 0.0);
            assertEquals(vehicle.getId(), departure.getVehicleId());
            assertSame(networkRoute, route.getRoute());
            assertEquals(firstStop.getLinkId(), networkRoute.getStartLinkId());
            assertEquals(lastStop.getLinkId(), networkRoute.getEndLinkId());
            assertTrue(networkRoute.getLinkIds().isEmpty());
            assertEquals(TransportMode.pt, route.getTransportMode());
            assertEquals(List.of(firstCall, lastCall), route.getStops());
            assertEquals(0, firstCall.getArrivalOffset().seconds(), 0.0);
            assertEquals(FIRST_STOP_OFFSET, firstCall.getDepartureOffset().seconds(), 0.0);
            assertEquals(20, lastCall.getArrivalOffset().seconds(), 0.0);
            assertEquals(30, lastCall.getDepartureOffset().seconds(), 0.0);
            assertTrue(firstCall.isAwaitDepartureTime());
            assertTrue(lastCall.isAwaitDepartureTime());
            assertEquals(2, scenario.getTransitSchedule().getFacilities().size());
            assertSame(firstStop, scenario.getTransitSchedule().getFacilities().get(firstStop.getId()));
            assertSame(lastStop, scenario.getTransitSchedule().getFacilities().get(lastStop.getId()));
            assertEquals(1, scenario.getTransitVehicles().getVehicles().size());
            assertSame(vehicle, scenario.getTransitVehicles().getVehicles().get(vehicle.getId()));
            assertSame(type, vehicle.getType());
            assertEquals(Integer.valueOf(4), type.getCapacity().getSeats());
            assertEquals(Integer.valueOf(2), type.getCapacity().getStandingRoom());
            assertEquals(10, type.getMaximumVelocity(), 0.0);
            assertEquals(1, type.getPcuEquivalents(), 0.0);
        }
    }

    private record NativeEvent(String className, double timeSeconds, Map<String, String> attributes) { }

    private record RunResult(List<NativeEvent> nativeEvents, double driverStartSeconds, double firstStopDepartureSeconds,
                             double firstStopBaselineDelaySeconds, int controllerReschedules, int acceptedDecisions) { }
}
