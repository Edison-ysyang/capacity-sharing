package org.matsim.core.mobsim.qsim.pt;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
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
import org.matsim.core.mobsim.framework.listeners.MobsimAfterSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
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
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.StepView;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit opt-in, synthetic 82-step native QSim observer fixtures.
 * No controlled driver, scheduling policy, passenger population, freight registry,
 * controller output or formal ISMT simulation is installed. Synthetic marker events
 * test callback boundaries only; all vehicle events come from the default QSim.
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDriverNativePrefixTest {
    private static final double END_TIME = 80;
    private static final double PROBE_TIME = 10;
    private static final String MARKER_TYPE = "ismtNativePrefixFixtureMarker";

    private enum Probe { NONE, LISTENER_BOUNDARIES, TYPED_REENTRY, TERMINAL_TAIL }

    @Test
    void nativeWorkerReceptionIsObservableWithoutChangingTheCompleteNativeEventSequence() {
        RunResult off = run(false, Probe.NONE);
        RunResult on = run(true, Probe.NONE);
        RunResult replay = run(true, Probe.NONE);

        assertEquals(off.events(), on.events(), "Observer attachment must not change any native event");
        assertEquals(off.events(), replay.events());
        assertEquals(1, on.events().stream().filter(event -> event.eventClass().equals(
                TransitDriverStartsEvent.class.getName())).count());
        assertEquals(2, on.events().stream().filter(event -> event.eventClass().equals(
                VehicleDepartsAtFacilityEvent.class.getName())).count());
        assertTrue(closedEvents(on).stream().anyMatch(event ->
                        event.eventClass().equals(VehicleDepartsAtFacilityEvent.class.getName())
                                && !event.receivedOnControlThread()),
                "Default one-runner QNetsim must exercise actual worker-thread reception");
        assertTrue(closedEvents(on).stream().anyMatch(event ->
                        event.eventClass().equals(TransitDriverStartsEvent.class.getName())
                                && event.receivedOnControlThread()),
                "The fixture must also exercise native control-thread activity departure");
        assertEquals(on.views().stream().map(StepView::closedPrefixSha256).toList(),
                replay.views().stream().map(StepView::closedPrefixSha256).toList(),
                "Worker instances differ between runs, but callback-content hashes must not");
        assertEquals(on.events().size(), on.receipt().receivedEvents());
        assertEquals(on.events().size(), on.receipt().closedEvents());
        assertEquals(0, on.receipt().unpublishedEvents());
    }

    @Test
    void bothAfterListenersCloseAtTheNextBeforeAndCurrentBeforeEventsRemainPending() {
        RunResult result = run(true, Probe.LISTENER_BOUNDARIES);
        StepView first = result.views().getFirst();
        StepView atProbe = at(result, PROBE_TIME);
        StepView next = at(result, PROBE_TIME + 1);
        StepView following = at(result, PROBE_TIME + 2);

        assertTrue(first.closedThroughSeconds().isEmpty());
        assertTrue(first.newlyClosedEvents().isEmpty());
        assertEquals(0, first.closedEventCount());
        assertEquals(List.of("before-listener-entered", "observer-consumer-entered"), result.boundaryVisits(),
                "The current-step event must really have been received before the observer's Before callback");
        assertTrue(markers(atProbe).isEmpty(), "A Before callback cannot see that step's future After markers");
        assertEquals(Set.of("after-registered-earlier", "after-registered-later"), Set.copyOf(markers(next)),
                "Listeners on both sides of observer registration must be represented in the closed previous step");
        assertFalse(markers(next).contains("current-before"));
        assertEquals(List.of("current-before"), markers(following));
        assertEquals(PROBE_TIME, next.closedThroughSeconds().orElseThrow(), 0.0);
        assertEquals(PROBE_TIME + 1, following.closedThroughSeconds().orElseThrow(), 0.0);
        ReceivedEvent copied = following.newlyClosedEvents().stream().filter(event ->
                "current-before".equals(event.attributes().get("label"))).findFirst().orElseThrow();
        assertEquals(PROBE_TIME + 1, copied.occurredAtSeconds(), 0.0);
        assertEquals(PROBE_TIME + 1, copied.receivedAtSeconds(), 0.0);
        assertEquals("original", copied.attributes().get("mutableFixtureValue"),
                "Changing the source event after processEvent returns must not alter its received copy");
        assertThrows(UnsupportedOperationException.class, () -> copied.attributes().put("label", "changed"));
        assertThrows(UnsupportedOperationException.class, () -> following.newlyClosedEvents().add(copied));
    }

    @Test
    void typedHandlerCanDeliverADerivedEventBeforeTheBasicObserverReceivesItsNativeSource() {
        RunResult result = run(true, Probe.TYPED_REENTRY);
        List<ReceivedEvent> closed = closedEvents(result);
        ReceivedEvent derived = closed.stream().filter(event ->
                "derived-from-native-start".equals(event.attributes().get("label"))).findFirst().orElseThrow();
        ReceivedEvent source = closed.stream().filter(event ->
                event.eventClass().equals(TransitDriverStartsEvent.class.getName())).findFirst().orElseThrow();

        assertEquals(source.occurredAtSeconds(), derived.occurredAtSeconds(), 0.0);
        assertTrue(derived.closedSequence() < source.closedSequence(),
                "Basic callbacks follow typed handlers: their ordinal is not global emission order");
        assertEquals(1, closed.stream().filter(event ->
                "derived-from-native-start".equals(event.attributes().get("label"))).count());
        assertFalse(result.receipt().poisoned(), "Normal typed-handler reentry is supported, not corruption");
    }

    @Test
    void finalAfterTailIsCountedButNeverCertifiedAsAControlPrefix() {
        RunResult result = run(true, Probe.TERMINAL_TAIL);
        assertEquals(1, result.events().stream().filter(event ->
                "terminal-after".equals(event.attributes().get("label"))).count());
        assertTrue(closedEvents(result).stream().noneMatch(event ->
                "terminal-after".equals(event.attributes().get("label"))));
        assertEquals(1, result.receipt().unpublishedEvents());
        assertEquals(result.receipt().closedEvents() + 1, result.receipt().receivedEvents());
        assertFalse(result.receipt().terminalPrefixCertified());
    }

    private RunResult run(boolean attachObserver, Probe probe) {
        Scenario scenario = fixture();
        EventsManager events = EventsUtils.createEventsManager();
        List<NativeEvent> records = new ArrayList<>();
        events.addHandler((BasicEventHandler) event -> records.add(new NativeEvent(
                event.getClass().getName(), event.getTime(), Map.copyOf(new TreeMap<>(event.getAttributes())))));
        if (probe == Probe.TYPED_REENTRY) {
            events.addHandler((TransitDriverStartsEventHandler) event ->
                    events.processEvent(new MarkerEvent(event.getTime(), "derived-from-native-start")));
        }
        PrepareForSimUtils.createDefaultPrepareForSim(scenario).run();
        QSim qsim = new QSimBuilder(scenario.getConfig()).useDefaults().build(scenario, events);
        List<StepView> views = new ArrayList<>();
        List<String> boundaryVisits = new ArrayList<>();

        if (probe == Probe.LISTENER_BOUNDARIES) {
            qsim.addQueueSimulationListeners((MobsimAfterSimStepListener) step -> {
                if (step.getSimulationTime() == PROBE_TIME) {
                    events.processEvent(new MarkerEvent(step.getSimulationTime(), "after-registered-earlier"));
                }
            });
        }
        NativeEventPrefixObserver observer = attachObserver
                ? NativeEventPrefixObserver.attach(qsim, 20, 1, 0, view -> {
                    views.add(view);
                    if (probe == Probe.LISTENER_BOUNDARIES && view.decisionTimeSeconds() == PROBE_TIME + 1) {
                        boundaryVisits.add("observer-consumer-entered");
                    }
                }) : null;
        if (probe == Probe.LISTENER_BOUNDARIES) {
            qsim.addQueueSimulationListeners((MobsimAfterSimStepListener) step -> {
                if (step.getSimulationTime() == PROBE_TIME) {
                    events.processEvent(new MarkerEvent(step.getSimulationTime(), "after-registered-later"));
                }
            });
            // EventListenerList runs the later registration first in this native runtime.
            // boundaryVisits explicitly verifies that fact rather than silently assuming it.
            qsim.addQueueSimulationListeners((MobsimBeforeSimStepListener) step -> {
                if (step.getSimulationTime() == PROBE_TIME + 1) {
                    boundaryVisits.add("before-listener-entered");
                    MarkerEvent marker = new MarkerEvent(step.getSimulationTime(), "current-before");
                    events.processEvent(marker);
                    marker.mutableFixtureValue = "mutated-after-delivery";
                    marker.setTime(step.getSimulationTime() + 500);
                }
            });
        }
        if (probe == Probe.TERMINAL_TAIL) {
            qsim.addQueueSimulationListeners((MobsimAfterSimStepListener) step -> {
                // Native onlyUseEndtime executes one last step beyond END_TIME, then cleanup.
                if (step.getSimulationTime() == END_TIME + 1) {
                    events.processEvent(new MarkerEvent(step.getSimulationTime(), "terminal-after"));
                }
            });
        }

        try {
            qsim.run();
        } finally {
            if (observer != null) observer.close();
        }
        assertEquals(0, qsim.getAgentCounter().getLiving());
        assertEquals(0, qsim.getAgentCounter().getLost());
        assertTrue(records.stream().noneMatch(event -> event.eventClass().equals(PersonStuckEvent.class.getName())));
        assertEquals(1, scenario.getTransitSchedule().getTransitLines().size());
        assertEquals(1, scenario.getTransitVehicles().getVehicles().size());
        if (observer != null) {
            assertFalse(observer.isPoisoned());
            assertFalse(observer.closeReceipt().terminalPrefixCertified());
            assertFalse(views.isEmpty());
            long count = 0;
            for (StepView view : views) {
                assertEquals(20, view.iteration());
                assertEquals(1, view.epoch());
                assertTrue(view.evidence().actualManagerChecked());
                assertTrue(view.evidence().registeredEngineClassChecked());
                assertTrue(view.evidence().activeNetworkIdentityChecked());
                assertTrue(view.evidence().primaryEngineIdentityCertified());
                assertTrue(view.evidence().singleRunnerConstructionChecked());
                assertTrue(view.evidence().actualRunnerCountVerified());
                assertTrue(view.evidence().singleRunnerConfigurationDeclared());
                assertFalse(view.evidence().allProducersCertified());
                assertEquals(64, view.closedPrefixSha256().length());
                count += view.newlyClosedEvents().size();
                assertEquals(count, view.closedEventCount(), "Views contain deltas, never a repeated full history");
                for (ReceivedEvent event : view.newlyClosedEvents()) {
                    assertTrue(event.occurredAtSeconds() <= view.closedThroughSeconds().orElseThrow());
                    assertTrue(event.receivedAtSeconds() <= view.decisionTimeSeconds());
                }
            }
        }
        return new RunResult(List.copyOf(records), List.copyOf(views), List.copyOf(boundaryVisits),
                observer == null ? null : observer.closeReceipt());
    }

    private static StepView at(RunResult result, double time) {
        return result.views().stream().filter(view -> view.decisionTimeSeconds() == time).findFirst().orElseThrow();
    }

    private static List<ReceivedEvent> closedEvents(RunResult result) {
        return result.views().stream().flatMap(view -> view.newlyClosedEvents().stream()).toList();
    }

    private static List<String> markers(StepView view) {
        return view.newlyClosedEvents().stream().filter(event -> event.eventType().equals(MARKER_TYPE))
                .map(event -> event.attributes().get("label")).toList();
    }

    private Scenario fixture() {
        Config config = ConfigUtils.createConfig();
        config.transit().setUseTransit(true);
        config.transit().setUsingTransitInMobsim(true);
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(END_TIME);
        config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-prefix-output")
                .toAbsolutePath().toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        Network network = scenario.getNetwork();
        Node firstNode = NetworkUtils.createAndAddNode(network, Id.createNodeId("prefix-n0"), new Coord(0, 0));
        Node middleNode = NetworkUtils.createAndAddNode(network, Id.createNodeId("prefix-n1"), new Coord(100, 0));
        Node lastNode = NetworkUtils.createAndAddNode(network, Id.createNodeId("prefix-n2"), new Coord(200, 0));
        Link firstLink = NetworkUtils.createAndAddLink(network, Id.createLinkId("prefix-l1"), firstNode, middleNode, 100, 10, 3600, 1);
        Link lastLink = NetworkUtils.createAndAddLink(network, Id.createLinkId("prefix-l2"), middleNode, lastNode, 100, 10, 3600, 1);
        Link returnLink = NetworkUtils.createAndAddLink(network, Id.createLinkId("prefix-return"), lastNode, firstNode, 200, 10, 3600, 1);
        for (Link link : List.of(firstLink, lastLink, returnLink)) link.setAllowedModes(Set.of(TransportMode.car));
        TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();
        TransitStopFacility firstStop = factory.createTransitStopFacility(Id.create("prefix-first", TransitStopFacility.class), new Coord(50, 0), false);
        TransitStopFacility lastStop = factory.createTransitStopFacility(Id.create("prefix-last", TransitStopFacility.class), new Coord(150, 0), false);
        firstStop.setLinkId(firstLink.getId());
        lastStop.setLinkId(lastLink.getId());
        scenario.getTransitSchedule().addStopFacility(firstStop);
        scenario.getTransitSchedule().addStopFacility(lastStop);
        TransitRouteStop firstCall = factory.createTransitRouteStop(firstStop, 0, 5);
        TransitRouteStop lastCall = factory.createTransitRouteStop(lastStop, 20, 30);
        firstCall.setAwaitDepartureTime(true);
        lastCall.setAwaitDepartureTime(true);
        NetworkRoute links = RouteUtils.createLinkNetworkRouteImpl(firstLink.getId(), lastLink.getId());
        links.setLinkIds(firstLink.getId(), List.of(), lastLink.getId());
        TransitRoute route = factory.createTransitRoute(Id.create("prefix-route", TransitRoute.class), links,
                List.of(firstCall, lastCall), TransportMode.pt);
        TransitLine line = factory.createTransitLine(Id.create("prefix-line", TransitLine.class));
        VehicleType type = VehicleUtils.createVehicleType(Id.create("prefix-type", VehicleType.class));
        type.getCapacity().setSeats(4);
        type.getCapacity().setStandingRoom(2);
        type.setMaximumVelocity(10);
        type.setPcuEquivalents(1);
        Vehicle vehicle = VehicleUtils.createVehicle(Id.createVehicleId("prefix-vehicle"), type);
        scenario.getTransitVehicles().addVehicleType(type);
        scenario.getTransitVehicles().addVehicle(vehicle);
        Departure departure = factory.createDeparture(Id.create("prefix-departure", Departure.class), 20);
        departure.setVehicleId(vehicle.getId());
        route.addDeparture(departure);
        line.addRoute(route);
        scenario.getTransitSchedule().addTransitLine(line);
        return scenario;
    }

    private static final class MarkerEvent extends Event {
        private final String label;
        private String mutableFixtureValue = "original";

        private MarkerEvent(double time, String label) { super(time); this.label = label; }
        @Override public String getEventType() { return MARKER_TYPE; }
        @Override public Map<String, String> getAttributes() {
            Map<String, String> attributes = super.getAttributes();
            attributes.put("label", label);
            attributes.put("mutableFixtureValue", mutableFixtureValue);
            return attributes;
        }
    }

    private record NativeEvent(String eventClass, double time, Map<String, String> attributes) { }
    private record RunResult(List<NativeEvent> events, List<StepView> views, List<String> boundaryVisits,
                             NativeEventPrefixObserver.CloseReceipt receipt) { }
}
