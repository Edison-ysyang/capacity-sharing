package org.matsim.core.mobsim.qsim.pt;

import com.google.inject.multibindings.OptionalBinder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.PrepareForSimUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
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
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.StepView;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState.Motion;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState.WorkObservation;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;

/**
 * Closed native observation -> toy decision -> complete bridge -> native execution.
 * Four short QSim runs, no passenger/freight population or formal control parameters.
 * The third-service variation is an engineering causality counterexample, NOT a
 * matched-supply M/T experiment. There is no optimization or production wiring here.
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDriverNativeObservedDispatchTest {
    private static final String LINE = "observed-line";
    private static final String ROUTE = "observed-route";
    private static final String FIRST_STOP = "observed-first-stop";
    private static final String LAST_STOP = "observed-last-stop";
    private static final String FIRST_VEHICLE = "observed-first-vehicle";
    private static final String SECOND_VEHICLE = "observed-second-vehicle";
    private static final String THIRD_VEHICLE = "observed-third-vehicle";
    private static final TripKey FIRST = new TripKey(LINE, ROUTE, "observed-first");
    private static final TripKey SECOND = new TripKey(LINE, ROUTE, "observed-second");
    private static final TripKey THIRD = new TripKey(LINE, ROUTE, "observed-third");
    private static final double FIRST_OFFSET = 10;

    @Test
    void realFirstServiceObservationMustCloseBeforeTheSecondServiceCanActuallyBeAdvanced() {
        RunResult reference = run(false, 140);
        RunResult controlled = run(true, 140);

        assertTrue(reference.decisions().isEmpty());
        assertEquals(1, controlled.decisions().size());
        DecisionEvidence decision = controlled.decisions().getFirst();
        assertEquals(30, departureAtFirstStop(controlled, FIRST_VEHICLE), 0.0);
        assertEquals(31, decision.batch().decisionTimeSeconds(), 0.0);
        assertEquals(30, decision.intent().source().closedThrough(), 0.0);
        assertEquals(30, decision.intent().source().occurredAt(), 0.0);
        assertEquals(31, decision.intent().source().availableAt(), 0.0);
        assertEquals(60, decision.intent().proposedDeparture(), 0.0);
        assertEquals(Map.of(SECOND, 60.0), decision.batch().proposedDepartures());
        assertEquals(List.of(decision.intent().observation()), decision.batch().observations());
        StepView sourceView = controlled.views().stream().filter(view -> view.decisionTimeSeconds() == 31)
                .findFirst().orElseThrow();
        assertEquals(sourceView.closedPrefixSha256(), decision.intent().source().prefixHash());
        assertEquals(sourceView.closedEventCount(), decision.intent().source().closedEventCount());
        ReceivedEvent sourceEvent = sourceView.newlyClosedEvents().stream().filter(event ->
                event.closedSequence() == decision.intent().source().closedSequence()).findFirst().orElseThrow();
        assertEquals(VehicleDepartsAtFacilityEvent.class.getName(), sourceEvent.eventClass());
        assertEquals(FIRST_VEHICLE, sourceEvent.attributes().get("vehicle"));
        assertEquals(FIRST_STOP, sourceEvent.attributes().get("facility"));
        assertFalse(sourceEvent.receivedOnControlThread(), "The trigger is a real QNetsim worker event");
        assertTrue(controlled.steps().stream().filter(step -> step.time() <= 30)
                .allMatch(step -> step.committedDecisions() == 0 && step.secondCommitment() == 80),
                "Even the actual event's simulation step must finish before any commitment changes");
        StepEvidence firstChanged = controlled.steps().stream().filter(step -> step.committedDecisions() > 0)
                .findFirst().orElseThrow();
        assertEquals(31, firstChanged.time(), 0.0);
        assertEquals(60, firstChanged.secondCommitment(), 0.0);

        assertEquals(80, driverStart(reference, SECOND_VEHICLE), 0.0);
        assertEquals(60, driverStart(controlled, SECOND_VEHICLE), 0.0);
        assertEquals(90, departureAtFirstStop(reference, SECOND_VEHICLE), 0.0);
        assertEquals(70, departureAtFirstStop(controlled, SECOND_VEHICLE), 0.0);
        assertEquals(eventsForVehicle(reference, FIRST_VEHICLE), eventsForVehicle(controlled, FIRST_VEHICLE));
        assertEquals(eventsForVehicle(reference, THIRD_VEHICLE), eventsForVehicle(controlled, THIRD_VEHICLE));
        assertEquals(ControlledTransitDispatchBridge.Status.COMMITTED_APPLIED, decision.application().status());
        assertEquals(1, decision.application().refreshedTrips().size());
        assertEquals(1, decision.application().requeuedDriverIds().size());
        assertTrue(decision.application().constraints().orElseThrow().candidate().satisfiesRegisteredConstraints());
        assertFalse(decision.application().constraints().orElseThrow().coverage().sharedResourceCoverageVerified());
    }

    @Test
    void changingAnUnobservedFutureServiceCannotChangeTheEarlierPrefixOrDecision() {
        RunResult earlierSuffix = run(true, 140);
        RunResult laterSuffix = run(true, 160);
        DecisionEvidence earlier = earlierSuffix.decisions().getFirst();
        DecisionEvidence later = laterSuffix.decisions().getFirst();
        double decisionTime = earlier.batch().decisionTimeSeconds();

        assertEquals(earlier.intent(), later.intent(), "Policy input provenance and pure output must match");
        assertEquals(earlier.batch(), later.batch(), "Same closed input must lead to the same submitted batch");
        assertEquals(earlier.application().decision(), later.application().decision());
        assertEquals(prefixesThrough(earlierSuffix, decisionTime), prefixesThrough(laterSuffix, decisionTime));
        assertEquals(earlierSuffix.serviceStates().stream().filter(state -> state.time() <= decisionTime).toList(),
                laterSuffix.serviceStates().stream().filter(state -> state.time() <= decisionTime).toList(),
                "A changed unobserved service contract must not appear in the folded public state");
        assertEquals(earlierSuffix.events().stream().filter(event -> event.time() <= decisionTime).toList(),
                laterSuffix.events().stream().filter(event -> event.time() <= decisionTime).toList());
        assertEquals(140, driverStart(earlierSuffix, THIRD_VEHICLE), 0.0);
        assertEquals(160, driverStart(laterSuffix, THIRD_VEHICLE), 0.0);
        assertNotEquals(earlierSuffix.events(), laterSuffix.events(), "The suffix perturbation must really execute");
        assertEquals(60, driverStart(earlierSuffix, SECOND_VEHICLE), 0.0);
        assertEquals(60, driverStart(laterSuffix, SECOND_VEHICLE), 0.0);
    }

    private RunResult run(boolean enableToyPolicy, double thirdDeparture) {
        Fixture fixture = fixture(thirdDeparture);
        DispatchCommitmentState ledger = new DispatchCommitmentState(fixture.baseline(), new Limits(30, 30, 10));
        ledger.beginIteration(20, 0, true);
        EventsManager events = EventsUtils.createEventsManager();
        List<NativeEvent> traceForPostRunAssertionsOnly = new ArrayList<>();
        events.addHandler((BasicEventHandler) event -> traceForPostRunAssertionsOnly.add(new NativeEvent(
                event.getClass().getName(), event.getTime(), Map.copyOf(new TreeMap<>(event.getAttributes())))));
        AtomicReference<ControlledTransitDispatchBridge> bridgeRef = new AtomicReference<>();
        TransitDriverAgentFactory factory = (umlauf, internal, tracker) ->
                Objects.requireNonNull(bridgeRef.get()).createTransitDriver(umlauf, internal, tracker);
        PrepareForSimUtils.createDefaultPrepareForSim(fixture.scenario()).run();
        QSim qsim = new QSimBuilder(fixture.scenario().getConfig()).useDefaults()
                .addQSimModule(new AbstractQSimModule() {
                    @Override protected void configureQSim() {
                        OptionalBinder.newOptionalBinder(binder(), TransitDriverAgentFactory.class)
                                .setBinding().toInstance(factory);
                    }
                }).build(fixture.scenario(), events);

        List<DecisionEvidence> decisions = new ArrayList<>();
        List<StepEvidence> steps = new ArrayList<>();
        List<StepView> views = new ArrayList<>();
        ObservedTransitServiceState serviceMapper = new ObservedTransitServiceState(fixture.baseline().stream()
                .map(trip -> new ObservedTransitServiceState.Service(trip.key(), trip.vehicleId(), List.of(FIRST_STOP, LAST_STOP)))
                .toList());
        AtomicReference<ObservedTransitServiceState.State> serviceState = new AtomicReference<>(serviceMapper.initialState());
        List<ServiceStateEvidence> serviceStates = new ArrayList<>();
        assertTrue(serviceState.get().observedServices().isEmpty());
        assertTrue(serviceState.get().latestWorkByVehicle().isEmpty());
        AtomicReference<Invocation> currentInvocation = new AtomicReference<>();
        ControlPoint point = new ControlPoint(new ControlPointKey(FIRST_STOP, "synthetic-outbound"),
                List.of(new Call(FIRST, FIRST_OFFSET), new Call(SECOND, FIRST_OFFSET), new Call(THIRD, FIRST_OFFSET)),
                20, 200, Optional.of(new FirstLastWindow(30, 30, 150, 170)));
        ControlledTransitDispatchBridge bridge = new ControlledTransitDispatchBridge(qsim, ledger, 20,
                List.of(point), List.of(), (active, now) -> {
                    Invocation invocation = Objects.requireNonNull(currentInvocation.get(), "No native closed-view context");
                    assertEquals(invocation.time(), now, 0.0);
                    assertEquals(invocation.epoch(), ledger.snapshot().version().epoch());
                    invocation.intent().ifPresent(intent -> {
                        assertEquals(now, intent.source().availableAt(), 0.0);
                        Batch batch = new Batch(ledger.snapshot().version(), now, List.of(intent.observation()),
                                Map.of(SECOND, intent.proposedDeparture()));
                        ControlledTransitDispatchBridge.ApplicationAudit application = active.submit(batch);
                        decisions.add(new DecisionEvidence(intent, batch, application));
                    });
                    steps.add(new StepEvidence(now, ledger.history().size(), ledger.snapshot().commitments().get(SECOND)));
                });
        bridgeRef.set(bridge);
        ClosedFirstDeparturePolicy policy = new ClosedFirstDeparturePolicy(enableToyPolicy);

        // The observer is the ONLY registered Before listener for this composition.
        // Its real QSim callback closes the prefix, then this consumer synchronously
        // forwards that same live time/QSim context to the UNREGISTERED bridge listener.
        // There are no synthetic vehicle events and no inter-listener ordering assumption.
        NativeEventPrefixObserver observer = NativeEventPrefixObserver.attach(qsim, 20,
                ledger.snapshot().version().epoch(), 0, view -> {
                    assertEquals(view.decisionTimeSeconds(), qsim.getSimTimer().getTimeOfDay(), 0.0);
                    assertNull(currentInvocation.get(), "A prior native callback must have completed");
                    views.add(view);
                    ObservedTransitServiceState.State before = serviceState.get();
                    Map<TripKey, WorkObservation> priorServices = Map.copyOf(before.observedServices());
                    ObservedTransitServiceState.State folded = serviceMapper.applyAll(before, view.newlyClosedEvents());
                    assertEquals(priorServices, before.observedServices(), "Folding a delta must not revise its input state");
                    serviceState.set(folded);
                    serviceStates.add(new ServiceStateEvidence(view.decisionTimeSeconds(), folded.observedServices(),
                            folded.latestWorkByVehicle()));
                    assertClosedServiceVisibility(folded, view, enableToyPolicy ? 60 : 80, thirdDeparture);
                    recordClosedNativeStarts(ledger, view);
                    Optional<DecisionIntent> intent = policy.accept(view);
                    currentInvocation.set(new Invocation(view.epoch(), view.decisionTimeSeconds(), intent));
                    try {
                        bridge.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(qsim, view.decisionTimeSeconds()));
                    } finally {
                        currentInvocation.set(null);
                    }
                });
        try {
            qsim.run();
        } finally {
            observer.close();
        }
        assertFalse(observer.isPoisoned());
        assertFalse(bridge.isPoisoned());
        assertFalse(observer.closeReceipt().terminalPrefixCertified());
        assertEquals(0, qsim.getAgentCounter().getLiving());
        assertEquals(0, qsim.getAgentCounter().getLost());
        assertTrue(traceForPostRunAssertionsOnly.stream().noneMatch(event -> event.eventClass().equals(PersonStuckEvent.class.getName())));
        assertEquals(3, traceForPostRunAssertionsOnly.stream().filter(event ->
                event.eventClass().equals(TransitDriverStartsEvent.class.getName())).count());
        assertEquals(6, traceForPostRunAssertionsOnly.stream().filter(event ->
                event.eventClass().equals(VehicleDepartsAtFacilityEvent.class.getName())).count());
        assertEquals(3, ledger.snapshot().startedAtSeconds().size());
        assertEquals(enableToyPolicy ? 1 : 0, decisions.size());
        assertEquals(enableToyPolicy ? 1 : 0, bridge.history().size());
        assertTrue(views.getFirst().closedThroughSeconds().isEmpty());
        assertTrue(views.getFirst().newlyClosedEvents().isEmpty());
        fixture.assertSupplyUnchanged();
        for (StepView view : views) {
            assertFalse(view.evidence().allProducersCertified());
            assertTrue(view.evidence().primaryEngineIdentityCertified());
            assertTrue(view.evidence().singleRunnerConstructionChecked());
            assertTrue(view.evidence().actualRunnerCountVerified());
        }
        ObservedTransitServiceState.State completed = serviceState.get();
        assertEquals(Set.of(FIRST, SECOND, THIRD), completed.observedServices().keySet());
        assertEquals(Set.of(FIRST_VEHICLE, SECOND_VEHICLE, THIRD_VEHICLE), completed.latestWorkByVehicle().keySet());
        Map<TripKey, Double> expectedStarts = Map.of(FIRST, 20.0, SECOND, enableToyPolicy ? 60.0 : 80.0, THIRD, thirdDeparture);
        completed.observedServices().forEach((trip, work) -> {
            assertEquals(Motion.ENDED, work.motion());
            assertEquals(2, work.nextStopIndex());
            assertTrue(work.atStop().isEmpty());
            assertEquals(Optional.of(LAST_STOP), work.lastObservedStop());
            assertEquals(expectedStarts.get(trip), work.startedAtSeconds(), 0.0);
            assertEquals(ledger.snapshot().startedAtSeconds().get(trip), work.startedAtSeconds(), 0.0);
            assertTrue(work.lastObservedAtSeconds() >= work.startedAtSeconds());
            assertEquals(work, completed.latestWorkByVehicle().get(work.vehicleId()));
            assertTrue(completed.hasObservedDriver(work.driverId()));
            assertFalse(work.active());
        });
        return new RunResult(List.copyOf(traceForPostRunAssertionsOnly), List.copyOf(decisions), List.copyOf(steps),
                List.copyOf(views), List.copyOf(serviceStates));
    }

    /** Test assertions only: the toy policy never receives this state or these future fixture times. */
    private static void assertClosedServiceVisibility(ObservedTransitServiceState.State state, StepView view,
                                                       double secondStart, double thirdStart) {
        double now = view.decisionTimeSeconds();
        if (now <= 20) {
            assertTrue(state.observedServices().isEmpty(), "Scheduled services are not observed before a closed real start");
            assertTrue(state.latestWorkByVehicle().isEmpty());
        }
        if (now <= secondStart) {
            assertFalse(state.observedServices().containsKey(SECOND));
            assertFalse(state.latestWorkByVehicle().containsKey(SECOND_VEHICLE));
        }
        if (now <= thirdStart) {
            assertFalse(state.observedServices().containsKey(THIRD));
            assertFalse(state.latestWorkByVehicle().containsKey(THIRD_VEHICLE));
        }
        if (now == 31) {
            assertEquals(Set.of(FIRST), state.observedServices().keySet());
            WorkObservation first = state.observedServices().get(FIRST);
            assertTrue(first.nextStopIndex() >= 1, "The real first-stop departure has been folded");
            assertEquals(20, first.startedAtSeconds(), 0.0);
            assertEquals(30, first.lastObservedAtSeconds(), 0.0);
        }
        if (now == secondStart + 1) assertEquals(secondStart, state.observedServices().get(SECOND).startedAtSeconds(), 0.0);
        if (now == thirdStart + 1) assertEquals(thirdStart, state.observedServices().get(THIRD).startedAtSeconds(), 0.0);
        for (WorkObservation work : state.observedServices().values()) {
            assertTrue(work.startedAtSeconds() <= view.closedThroughSeconds().orElseThrow());
            assertTrue(work.lastObservedAtSeconds() <= view.closedThroughSeconds().orElseThrow());
        }
    }

    /** Only closed real starts update the ledger. The reporting trace is not read. */
    private static void recordClosedNativeStarts(DispatchCommitmentState ledger, StepView view) {
        for (ReceivedEvent event : view.newlyClosedEvents()) {
            if (event.eventClass().equals(TransitDriverStartsEvent.class.getName())) {
                Map<String, String> attributes = event.attributes();
                TripKey key = new TripKey(attributes.get(TransitDriverStartsEvent.ATTRIBUTE_TRANSIT_LINE_ID),
                        attributes.get(TransitDriverStartsEvent.ATTRIBUTE_TRANSIT_ROUTE_ID),
                        attributes.get(TransitDriverStartsEvent.ATTRIBUTE_DEPARTURE_ID));
                ledger.recordStarted(key, event.occurredAtSeconds(), view.decisionTimeSeconds());
            }
        }
    }

    /**
     * Restricted deterministic policy: one immutable closed view is its only input.
     * It holds no QSim, observer, ledger, trace, plans, registry, or suffix parameter.
     * IDs and 30 seconds are declared toy rule constants, not empirical constraints.
     */
    private static final class ClosedFirstDeparturePolicy {
        private final boolean enabled;
        private boolean triggered;
        private ClosedFirstDeparturePolicy(boolean enabled) { this.enabled = enabled; }
        private Optional<DecisionIntent> accept(StepView view) {
            if (!enabled || triggered) return Optional.empty();
            for (ReceivedEvent event : view.newlyClosedEvents()) {
                if (!event.eventClass().equals(VehicleDepartsAtFacilityEvent.class.getName())
                        || !FIRST_VEHICLE.equals(event.attributes().get("vehicle"))
                        || !FIRST_STOP.equals(event.attributes().get("facility"))) continue;
                double watermark = view.closedThroughSeconds().orElseThrow();
                if (event.occurredAtSeconds() > watermark || event.receivedAtSeconds() > view.decisionTimeSeconds()) {
                    throw new IllegalStateException("Observation lies outside the supplied closed prefix");
                }
                SourceReference source = new SourceReference(view.epoch(), event.closedSequence(), view.closedEventCount(),
                        watermark, view.closedPrefixSha256(), event.occurredAtSeconds(), view.decisionTimeSeconds());
                triggered = true;
                return Optional.of(new DecisionIntent(event.occurredAtSeconds() + 30, source,
                        new ObservedFact("native-prefix:" + source.epoch() + ":" + source.closedSequence()
                                + ":" + source.prefixHash(), source.occurredAt(), source.availableAt())));
            }
            return Optional.empty();
        }
    }

    private static List<PrefixEvidence> prefixesThrough(RunResult result, double time) {
        return result.views().stream().filter(view -> view.decisionTimeSeconds() <= time)
                .map(view -> new PrefixEvidence(view.decisionTimeSeconds(), view.closedEventCount(), view.closedPrefixSha256())).toList();
    }

    private static double driverStart(RunResult result, String vehicle) {
        return result.events().stream().filter(event -> event.eventClass().equals(TransitDriverStartsEvent.class.getName())
                && vehicle.equals(event.attributes().get(TransitDriverStartsEvent.ATTRIBUTE_VEHICLE_ID)))
                .findFirst().orElseThrow().time();
    }

    private static double departureAtFirstStop(RunResult result, String vehicle) {
        return result.events().stream().filter(event -> event.eventClass().equals(VehicleDepartsAtFacilityEvent.class.getName())
                && vehicle.equals(event.attributes().get("vehicle")) && FIRST_STOP.equals(event.attributes().get("facility")))
                .findFirst().orElseThrow().time();
    }

    private static List<NativeEvent> eventsForVehicle(RunResult result, String vehicle) {
        return result.events().stream().filter(event -> vehicle.equals(event.attributes().get("vehicle"))
                || vehicle.equals(event.attributes().get("vehicleId"))).toList();
    }

    private Fixture fixture(double thirdDeparture) {
        Config config = ConfigUtils.createConfig();
        config.transit().setUseTransit(true);
        config.transit().setUsingTransitInMobsim(true);
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(220);
        config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-observed-dispatch-output")
                .toAbsolutePath().toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        Network network = scenario.getNetwork();
        Node a = NetworkUtils.createAndAddNode(network, Id.createNodeId("observed-n0"), new Coord(0, 0));
        Node b = NetworkUtils.createAndAddNode(network, Id.createNodeId("observed-n1"), new Coord(100, 0));
        Node c = NetworkUtils.createAndAddNode(network, Id.createNodeId("observed-n2"), new Coord(200, 0));
        Link firstLink = NetworkUtils.createAndAddLink(network, Id.createLinkId("observed-l1"), a, b, 100, 10, 3600, 1);
        Link lastLink = NetworkUtils.createAndAddLink(network, Id.createLinkId("observed-l2"), b, c, 100, 10, 3600, 1);
        Link backLink = NetworkUtils.createAndAddLink(network, Id.createLinkId("observed-return"), c, a, 200, 10, 3600, 1);
        for (Link link : List.of(firstLink, lastLink, backLink)) link.setAllowedModes(Set.of(TransportMode.car));
        TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();
        TransitStopFacility firstStop = factory.createTransitStopFacility(Id.create(FIRST_STOP, TransitStopFacility.class), new Coord(50, 0), false);
        TransitStopFacility lastStop = factory.createTransitStopFacility(Id.create(LAST_STOP, TransitStopFacility.class), new Coord(150, 0), false);
        firstStop.setLinkId(firstLink.getId());
        lastStop.setLinkId(lastLink.getId());
        scenario.getTransitSchedule().addStopFacility(firstStop);
        scenario.getTransitSchedule().addStopFacility(lastStop);
        TransitRouteStop firstCall = factory.createTransitRouteStop(firstStop, 0, FIRST_OFFSET);
        TransitRouteStop lastCall = factory.createTransitRouteStop(lastStop, 20, 30);
        firstCall.setAwaitDepartureTime(true);
        lastCall.setAwaitDepartureTime(true);
        NetworkRoute path = RouteUtils.createLinkNetworkRouteImpl(firstLink.getId(), lastLink.getId());
        path.setLinkIds(firstLink.getId(), List.of(), lastLink.getId());
        TransitRoute route = factory.createTransitRoute(Id.create(ROUTE, TransitRoute.class), path, List.of(firstCall, lastCall), TransportMode.pt);
        TransitLine line = factory.createTransitLine(Id.create(LINE, TransitLine.class));
        List<ScheduledTrip> baseline = List.of(new ScheduledTrip(FIRST, FIRST_VEHICLE, 20),
                new ScheduledTrip(SECOND, SECOND_VEHICLE, 80), new ScheduledTrip(THIRD, THIRD_VEHICLE, thirdDeparture));
        VehicleType type = VehicleUtils.createVehicleType(Id.create("observed-type", VehicleType.class));
        type.getCapacity().setSeats(4);
        type.getCapacity().setStandingRoom(2);
        type.setMaximumVelocity(10);
        type.setPcuEquivalents(1);
        scenario.getTransitVehicles().addVehicleType(type);
        Map<TripKey, Departure> departures = new LinkedHashMap<>();
        for (ScheduledTrip trip : baseline) {
            Vehicle vehicle = VehicleUtils.createVehicle(Id.createVehicleId(trip.vehicleId()), type);
            scenario.getTransitVehicles().addVehicle(vehicle);
            Departure departure = factory.createDeparture(Id.create(trip.key().departure(), Departure.class), trip.baselineDepartureSeconds());
            departure.setVehicleId(vehicle.getId());
            route.addDeparture(departure);
            departures.put(trip.key(), departure);
        }
        line.addRoute(route);
        scenario.getTransitSchedule().addTransitLine(line);
        return new Fixture(scenario, List.copyOf(baseline), Map.copyOf(departures));
    }

    private record Fixture(Scenario scenario, List<ScheduledTrip> baseline, Map<TripKey, Departure> departures) {
        private void assertSupplyUnchanged() {
            assertEquals(1, scenario.getTransitSchedule().getTransitLines().size());
            assertEquals(3, scenario.getTransitVehicles().getVehicles().size());
            assertEquals(3, departures.size());
            for (ScheduledTrip trip : baseline) {
                Departure departure = departures.get(trip.key());
                assertEquals(trip.baselineDepartureSeconds(), departure.getDepartureTime(), 0.0);
                assertEquals(trip.vehicleId(), departure.getVehicleId().toString());
                Vehicle vehicle = scenario.getTransitVehicles().getVehicles().get(departure.getVehicleId());
                assertEquals(Integer.valueOf(4), vehicle.getType().getCapacity().getSeats());
                assertEquals(Integer.valueOf(2), vehicle.getType().getCapacity().getStandingRoom());
            }
        }
    }

    private record Invocation(long epoch, double time, Optional<DecisionIntent> intent) { }
    private record SourceReference(long epoch, long closedSequence, long closedEventCount, double closedThrough,
                                   String prefixHash, double occurredAt, double availableAt) { }
    private record DecisionIntent(double proposedDeparture, SourceReference source, ObservedFact observation) { }
    private record DecisionEvidence(DecisionIntent intent, Batch batch, ControlledTransitDispatchBridge.ApplicationAudit application) { }
    private record StepEvidence(double time, int committedDecisions, double secondCommitment) { }
    private record PrefixEvidence(double time, long closedCount, String hash) { }
    private record NativeEvent(String eventClass, double time, Map<String, String> attributes) { }
    private record ServiceStateEvidence(double time, Map<TripKey, WorkObservation> services, Map<String, WorkObservation> vehicles) {
        private ServiceStateEvidence { services = Map.copyOf(services); vehicles = Map.copyOf(vehicles); }
    }
    private record RunResult(List<NativeEvent> events, List<DecisionEvidence> decisions, List<StepEvidence> steps,
                             List<StepView> views, List<ServiceStateEvidence> serviceStates) { }
}
