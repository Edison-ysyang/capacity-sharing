package org.matsim.core.mobsim.qsim.pt;

import com.google.inject.multibindings.OptionalBinder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.PrepareForSimUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.listeners.MobsimAfterSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.utils.TransitScheduleValidator;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in native QSim microfixtures for two different vehicles releasing a third
 * through real ChainedDeparture references. Default engines, UmlaufCache,
 * TransitStopAgentTracker and PassengerAccessEgressImpl own waiting, arrival
 * counting and release/rescheduling. No readiness callback or event is fabricated.
 *
 * <p>The empty passenger population isolates the native chain-departure gate;
 * this is not passenger-transfer validation, full policy feasibility, a production
 * OFF/zero gate or a formal ISMT simulation. Native reschedule invocation counts
 * are not instrumented or claimed; actual state and event timing are checked.</p>
 */
@Tag("ismt-integration")
@Tag("ismt-native-dispatch-micro")
class ControlledTransitDriverNativeChainTest {
    private static final TripKey A = new TripKey("chain-line", "chain-route-A", "chain-departure-A");
    private static final TripKey B = new TripKey("chain-line", "chain-route-B", "chain-departure-B");
    private static final TripKey D = new TripKey("chain-line", "chain-route-dependent", "chain-departure-dependent");
    private static final List<TripKey> TRIPS = List.of(A, B, D);
    private static final double DEPENDENT_BASELINE = 160;
    private static final double FIRST_OFFSET = 10;
    private static final double DECISION_TIME = 20;

    @Test
    void acceptedAdvanceRemainsBlockedUntilBothRealPredecessorsFinish() {
        Result result = runFixture(40);
        assertNativeEvidence(result);
        double release = result.trace.legEnds.get(B).getTime();
        assertTrue(result.commitment < release, "The advance must actually request departure before the second release");
        assertTrue(result.trace.starts.get(D).getTime() < DEPENDENT_BASELINE);
        assertEquals(release + FIRST_OFFSET, result.trace.departures.get(D).getFirst().getTime(), 0.0,
                "The actual facility departure uses physical release plus the unchanged offset, not the infeasible commitment");
    }

    @Test
    void acceptedDelayRemainsBlockedThenWaitsForItsLaterCommittedTime() {
        Result result = runFixture(200);
        assertNativeEvidence(result);
        double release = result.trace.legEnds.get(B).getTime();
        assertTrue(release < result.commitment, "The delay must leave a genuine native waiting interval after release");
        assertTrue(result.releasedWaitingSamples > 0);
        assertEquals(200, result.trace.starts.get(D).getTime(), 0.0);
        assertEquals(210, result.trace.departures.get(D).getFirst().getTime(), 0.0);
    }

    private static Result runFixture(double commitment) {
        Fixture fixture = fixture();
        var ledger = new DispatchCommitmentState(TRIPS.stream().map(key -> {
            Service service = fixture.services.get(key);
            return new DispatchCommitmentState.ScheduledTrip(key, service.vehicle.getId().toString(), service.baseline);
        }).toList(), new DispatchCommitmentState.Limits(130, 60, 0));
        long epoch = ledger.beginIteration(20, 0, true).version().epoch();
        Result result = new Result(fixture, ledger, commitment);
        Map<TripKey, ControlledTransitDriverAgent.AcceptedCommitment> accepted = new LinkedHashMap<>();
        var events = EventsUtils.createEventsManager();
        events.addHandler(result.trace);
        TransitDriverAgentFactory factory = (umlauf, internal, tracker) -> {
            assertEquals(1, umlauf.getUmlaufStuecke().size(), "Each of the three original vehicles has one service only");
            var work = umlauf.getUmlaufStuecke().getFirst();
            assertTrue(work.isFahrt());
            TripKey key = new TripKey(work.getLine().getId().toString(), work.getRoute().getId().toString(),
                    work.getDeparture().getId().toString());
            assertTrue(fixture.services.containsKey(key));
            assertEquals(fixture.services.get(key).vehicle.getId(), umlauf.getVehicleId());
            AbstractTransitDriverAgent driver;
            if (key.equals(D)) {
                driver = new ControlledTransitDriverAgent(umlauf, TransportMode.car, tracker, internal,
                        trip -> Optional.ofNullable(accepted.get(trip)), 0, epoch);
                result.dependent = (ControlledTransitDriverAgent) driver;
            } else {
                driver = new DefaultTransitDriverAgentFactory().createTransitDriver(umlauf, internal, tracker);
            }
            assertNull(result.drivers.put(key, driver), "Native reconstruction must not duplicate a service driver");
            return driver;
        };

        PrepareForSimUtils.createDefaultPrepareForSim(fixture.scenario).run();
        result.supplyBefore = supplySnapshot(fixture);
        QSim qsim = new QSimBuilder(fixture.scenario.getConfig()).useDefaults()
                .addQSimModule(new AbstractQSimModule() {
                    @Override protected void configureQSim() {
                        OptionalBinder.newOptionalBinder(binder(), TransitDriverAgentFactory.class).setBinding().toInstance(factory);
                    }
                }).build(fixture.scenario, events);
        result.qsim = qsim;
        qsim.addQueueSimulationListeners((MobsimBeforeSimStepListener) event -> {
            double now = event.getSimulationTime();
            if (now == DECISION_TIME) {
                result.decisionVisits++;
                assertEquals(3, result.drivers.size());
                assertNotNull(result.trace.starts.get(A), "First predecessor must have really started before publication");
                assertTrue(result.trace.legEnds.isEmpty(), "Neither predecessor may already have released the dependent");
                assertBlocked(result);
                var sourceStart = result.trace.starts.get(A);
                var decision = ledger.commit(new DispatchCommitmentState.Batch(ledger.snapshot().version(), now,
                        List.of(new DispatchCommitmentState.ObservedFact("native-A-start", sourceStart.getTime(), sourceStart.getTime())),
                        Map.of(D, commitment)));
                assertEquals(1, decision.changes().size());
                var change = decision.changes().getFirst();
                assertEquals(D, change.key());
                result.acceptedVersion = decision.resultingVersion();
                accepted.put(D, new ControlledTransitDriverAgent.AcceptedCommitment(change.committedDepartureSeconds(),
                        decision.resultingVersion()));
                var refresh = result.dependent.refreshAcceptedCommitment(D, now);
                assertEquals(ControlledTransitDriverAgent.RefreshOutcome.BLOCKED, refresh.outcome());
                assertFalse(refresh.requiresReschedule());
                assertEquals(Double.POSITIVE_INFINITY, refresh.previousDeadlineSeconds());
                assertEquals(Double.POSITIVE_INFINITY, refresh.effectiveDeadlineSeconds());
                assertEquals(Optional.of(result.acceptedVersion), refresh.acceptedVersion());
                assertBlocked(result);
                // Deliberately no controller reschedule: native PassengerAccessEgressImpl
                // alone must arrange the eventual release after the second real leg end.
            }
            if (now == 260 || now == 290) {
                result.terminalChecks++;
                assertTerminal(result);
                int eventsBefore = result.trace.eventCount;
                var refresh = result.dependent.refreshAcceptedCommitment(D, now);
                assertEquals(ControlledTransitDriverAgent.RefreshOutcome.NO_CHANGE, refresh.outcome());
                assertFalse(refresh.requiresReschedule());
                assertEquals(Double.POSITIVE_INFINITY, refresh.effectiveDeadlineSeconds());
                assertEquals(eventsBefore, result.trace.eventCount);
                assertTerminal(result);
            }
        });
        qsim.addQueueSimulationListeners((MobsimAfterSimStepListener) event -> {
            double now = event.getSimulationTime();
            if (result.trace.legEnds.containsKey(A) && !result.trace.legEnds.containsKey(B)) {
                result.onePredecessorFinishedSamples++;
                assertBlocked(result);
                assertEquals(0, result.dependent.currentExecutionState().physicalReadySeconds(), 0.0,
                        "A alone must not invoke native setReadyForDeparture");
                if (result.onePredecessorFinishedSamples == 1) {
                    var refresh = result.dependent.refreshAcceptedCommitment(D, now);
                    assertEquals(ControlledTransitDriverAgent.RefreshOutcome.BLOCKED, refresh.outcome());
                    assertFalse(refresh.requiresReschedule());
                    assertBlocked(result);
                }
            }
            PersonArrivalEvent secondArrival = result.trace.legEnds.get(B);
            if (secondArrival != null && now == secondArrival.getTime()) {
                result.releaseChecks++;
                var state = result.dependent.currentExecutionState();
                assertFalse(state.chainBlocked(), "Only both native predecessor completions may release the waiting driver");
                assertFalse(state.terminal());
                assertEquals(Optional.of(D), state.trip());
                assertEquals(secondArrival.getTime(), state.physicalReadySeconds(), 0.0);
                assertEquals(Math.max(commitment, secondArrival.getTime()), state.effectiveDeadlineSeconds(), 0.0);
                assertEquals(Optional.of(result.acceptedVersion), state.acceptedVersion());
            }
            if (secondArrival != null && !result.trace.starts.containsKey(D)) {
                result.releasedWaitingSamples++;
                var state = result.dependent.currentExecutionState();
                assertFalse(state.chainBlocked());
                assertEquals(MobsimAgent.State.ACTIVITY, state.state());
                assertEquals(Math.max(commitment, secondArrival.getTime()), state.effectiveDeadlineSeconds(), 0.0);
            }
        });
        qsim.run(); // All starts, arrivals, facility departures and chain releases are native.
        return result;
    }

    private static void assertBlocked(Result result) {
        var state = result.dependent.currentExecutionState();
        assertTrue(state.chainBlocked());
        assertFalse(state.terminal());
        assertEquals(MobsimAgent.State.ACTIVITY, state.state());
        assertEquals(Optional.of(D), state.trip());
        assertEquals(Double.POSITIVE_INFINITY, result.dependent.getActivityEndTime());
        assertFalse(result.trace.starts.containsKey(D));
        assertTrue(result.trace.departures.get(D).isEmpty());
    }

    private static void assertTerminal(Result result) {
        assertEquals(3, result.trace.legEnds.size());
        assertEquals(0, result.qsim.getAgentCounter().getLiving());
        assertEquals(0, result.qsim.getAgentCounter().getLost());
        for (var entry : result.drivers.entrySet()) {
            var driver = entry.getValue();
            assertEquals(MobsimAgent.State.ACTIVITY, driver.getState());
            assertNull(driver.getNextPlanElement());
            assertEquals(Double.POSITIVE_INFINITY, driver.getActivityEndTime());
            Service service = result.fixture.services.get(entry.getKey());
            var vehicle = result.qsim.getVehicles().get(service.vehicle.getId());
            assertSame(service.vehicle, vehicle.getVehicle());
            assertSame(vehicle, driver.getVehicle());
            assertNull(vehicle.getDriver(), "Native letVehicleArrive detaches the driver before its final activity");
            assertTrue(vehicle.getPassengers().isEmpty());
        }
        assertTrue(result.dependent.currentExecutionState().terminal());
        assertTrue(result.dependent.currentExecutionState().trip().isEmpty());
    }

    private static void assertNativeEvidence(Result result) {
        assertEquals(1, result.decisionVisits);
        assertEquals(2, result.terminalChecks, "Both explicitly scheduled terminal checks must really execute");
        assertTrue(result.onePredecessorFinishedSamples > 0);
        assertEquals(1, result.releaseChecks);
        assertEquals(2, result.trace.arrivalBeforeReleaseChecks,
                "Both real PersonArrival handlers must observe the still-blocked driver before native relocation");
        assertEquals(3, result.trace.arrivalDetachedVehicleChecks,
                "All three real native leg ends must exercise the detached-vehicle lifecycle assertion");
        assertEquals(3, result.drivers.size());
        assertEquals(3, result.trace.starts.size());
        assertEquals(3, result.qsim.getVehicles().size());
        assertEquals(0, result.trace.stuckEvents);
        assertEquals(1, result.ledger.history().size());
        assertEquals(result.commitment, result.ledger.snapshot().commitments().get(D), 0.0);
        assertTerminal(result);
        for (TripKey key : TRIPS) {
            Service service = result.fixture.services.get(key);
            assertEquals(service.vehicle.getId(), result.trace.starts.get(key).getVehicleId());
            assertEquals(2, result.trace.runningVehicleIdentityChecks.get(key).intValue(),
                    "Both actual facility departures must verify the original running driver/vehicle identity for every service");
            assertEquals(List.of(service.first.getId(), service.last.getId()),
                    result.trace.departures.get(key).stream().map(VehicleDepartsAtFacilityEvent::getFacilityId).toList());
            assertEquals(List.of(service.first.getId(), service.last.getId()),
                    result.trace.arrivals.get(key).stream().map(VehicleArrivesAtFacilityEvent::getFacilityId).toList());
            assertTrue(result.trace.legEnds.get(key).getTime() >= result.trace.departures.get(key).getLast().getTime());
            assertEquals(result.trace.starts.get(key).getTime(), result.ledger.snapshot().startedAtSeconds().get(key), 0.0);
        }
        assertEquals(10, result.trace.starts.get(A).getTime(), 0.0);
        assertEquals(60, result.trace.starts.get(B).getTime(), 0.0);
        double firstCompletion = result.trace.legEnds.get(A).getTime();
        double release = result.trace.legEnds.get(B).getTime();
        assertTrue(firstCompletion < release);
        double anchor = Math.max(result.commitment, release);
        double start = result.trace.starts.get(D).getTime();
        var firstDeparture = result.trace.departures.get(D).getFirst();
        assertTrue(start >= anchor && start <= anchor + 1,
                "A one-second QSim step may defer native activity execution, but not bypass readiness or retain baseline 160");
        assertEquals(release, result.trace.physicalReadyAtDependentStart, 0.0);
        assertEquals(anchor + FIRST_OFFSET, firstDeparture.getTime(), 0.0,
                "Actual first-facility departure, not driver start alone, proves the accepted/ready anchor plus unchanged offset");
        assertTrue(firstDeparture.getTime() > start);
        assertEquals(firstDeparture.getTime() - (DEPENDENT_BASELINE + FIRST_OFFSET), firstDeparture.getDelay(), 0.0,
                "Native delay remains measured against the published timetable");
        assertEquals(result.supplyBefore, supplySnapshot(result.fixture));
        assertTrue(TransitScheduleValidator.validateChainedDepartures(result.fixture.scenario.getTransitSchedule()).isValid());
        assertEquals(0, result.fixture.scenario.getPopulation().getPersons().size());
    }

    private static Fixture fixture() {
        var config = ConfigUtils.createConfig();
        config.transit().setUseTransit(true);
        config.transit().setUsingTransitInMobsim(true);
        config.global().setNumberOfThreads(1);
        config.qsim().setNumberOfThreads(1);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(300);
        config.qsim().setTimeStepSize(1);
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.controller().setOutputDirectory(Path.of(".build-cache", "ismt-native-dispatch-micro", "unused-chain-output")
                .toAbsolutePath().toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++) nodes.add(NetworkUtils.createAndAddNode(scenario.getNetwork(),
                Id.createNodeId("chain-n" + i), new Coord(i * 100, 0)));
        List<Link> links = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Link link = NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("chain-l" + i),
                    nodes.get(i), nodes.get((i + 1) % 4), i == 3 ? 300 : 100, 10, 3600, 1);
            link.setAllowedModes(Set.of(TransportMode.car));
            links.add(link);
        }
        // The last link closes the directed SCC and is not part of any service route.
        var sf = scenario.getTransitSchedule().getFactory();
        TransitLine line = sf.createTransitLine(Id.create(A.line(), TransitLine.class));
        scenario.getTransitSchedule().addTransitLine(line);
        TransitStopFacility origin = stop(scenario, "chain-origin", links.get(0));
        TransitStopFacility join = stop(scenario, "chain-join", links.get(1));
        TransitStopFacility terminal = stop(scenario, "chain-terminal", links.get(2));
        VehicleType type = VehicleUtils.createVehicleType(Id.create("chain-type", VehicleType.class));
        type.getCapacity().setSeats(4);
        type.getCapacity().setStandingRoom(2);
        type.setMaximumVelocity(10);
        type.setPcuEquivalents(1);
        scenario.getTransitVehicles().addVehicleType(type);
        Map<TripKey, Service> services = new LinkedHashMap<>();
        services.put(A, service(scenario, line, A, type, 10, links.get(0), links.get(1), origin, join));
        services.put(B, service(scenario, line, B, type, 60, links.get(0), links.get(1), origin, join));
        services.put(D, service(scenario, line, D, type, DEPENDENT_BASELINE, links.get(1), links.get(2), join, terminal));
        for (TripKey predecessor : List.of(A, B)) {
            services.get(predecessor).departure.setChainedDepartures(List.of(sf.createChainedDeparture(line.getId(),
                    services.get(D).route.getId(), services.get(D).departure.getId())));
        }
        assertEquals(3, services.values().stream().map(s -> s.departure.getId()).distinct().count(),
                "Bare native departure IDs must be globally unique across all routes in this fixture");
        assertEquals(1, UmlaufCache.calculateDeparturesDependingOnChains(scenario.getTransitSchedule()).size());
        assertEquals(2, UmlaufCache.calculateDeparturesDependingOnChains(scenario.getTransitSchedule())
                .getInt(services.get(D).departure.getId()));
        assertTrue(TransitScheduleValidator.validateChainedDepartures(scenario.getTransitSchedule()).isValid());
        return new Fixture(scenario, services);
    }

    private static TransitStopFacility stop(Scenario scenario, String id, Link link) {
        var schedule = scenario.getTransitSchedule();
        TransitStopFacility stop = schedule.getFactory().createTransitStopFacility(Id.create(id, TransitStopFacility.class),
                link.getCoord(), false);
        stop.setLinkId(link.getId());
        schedule.addStopFacility(stop);
        return stop;
    }

    private static Service service(Scenario scenario, TransitLine line, TripKey key, VehicleType type, double baseline,
                                   Link firstLink, Link lastLink, TransitStopFacility first, TransitStopFacility last) {
        var sf = scenario.getTransitSchedule().getFactory();
        TransitRouteStop firstCall = sf.createTransitRouteStop(first, 0, FIRST_OFFSET);
        firstCall.setAwaitDepartureTime(true);
        TransitRouteStop lastCall = sf.createTransitRouteStop(last, 30, 30);
        lastCall.setAwaitDepartureTime(true);
        var networkRoute = RouteUtils.createLinkNetworkRouteImpl(firstLink.getId(), lastLink.getId());
        networkRoute.setLinkIds(firstLink.getId(), List.of(), lastLink.getId());
        TransitRoute route = sf.createTransitRoute(Id.create(key.route(), TransitRoute.class), networkRoute,
                List.of(firstCall, lastCall), TransportMode.pt);
        Vehicle vehicle = VehicleUtils.createVehicle(Id.createVehicleId(key.departure() + "-vehicle"), type);
        scenario.getTransitVehicles().addVehicle(vehicle);
        Departure departure = sf.createDeparture(Id.create(key.departure(), Departure.class), baseline);
        departure.setVehicleId(vehicle.getId());
        route.addDeparture(departure);
        line.addRoute(route);
        return new Service(vehicle, route, departure, first, last, baseline);
    }

    private static List<String> supplySnapshot(Fixture fixture) {
        List<String> rows = new ArrayList<>();
        rows.add("counts:" + fixture.scenario.getTransitSchedule().getTransitLines().size() + ":"
                + fixture.scenario.getTransitSchedule().getFacilities().size() + ":"
                + fixture.scenario.getTransitVehicles().getVehicles().size() + ":"
                + fixture.scenario.getTransitVehicles().getVehicleTypes().size());
        fixture.scenario.getTransitSchedule().getTransitLines().values().forEach(line -> {
            rows.add("line:" + line.getId() + ":" + line.getRoutes().size());
            line.getRoutes().values().forEach(route -> {
                rows.add("route:" + route.getId() + ":" + route.getTransportMode() + ":" + route.getRoute().getStartLinkId()
                        + ":" + route.getRoute().getLinkIds() + ":" + route.getRoute().getEndLinkId());
                route.getStops().forEach(stop -> rows.add("stop:" + stop.getStopFacility().getId() + ":"
                        + stop.getStopFacility().getLinkId() + ":" + stop.getArrivalOffset() + ":"
                        + stop.getDepartureOffset() + ":" + stop.isAwaitDepartureTime()));
                route.getDepartures().values().forEach(departure -> {
                    rows.add("departure:" + departure.getId() + ":" + departure.getDepartureTime() + ":" + departure.getVehicleId());
                    departure.getChainedDepartures().forEach(chain -> rows.add("chain:" + chain.getChainedTransitLineId()
                            + ":" + chain.getChainedRouteId() + ":" + chain.getChainedDepartureId()));
                });
            });
        });
        fixture.scenario.getTransitVehicles().getVehicles().values().forEach(vehicle -> rows.add("vehicle:" + vehicle.getId()
                + ":" + vehicle.getType().getId() + ":" + vehicle.getType().getCapacity().getSeats() + ":"
                + vehicle.getType().getCapacity().getStandingRoom() + ":" + vehicle.getType().getMaximumVelocity()
                + ":" + vehicle.getType().getPcuEquivalents()));
        return List.copyOf(rows);
    }

    private record Service(Vehicle vehicle, TransitRoute route, Departure departure, TransitStopFacility first,
                           TransitStopFacility last, double baseline) { }

    private record Fixture(Scenario scenario, Map<TripKey, Service> services) { }

    private static final class Result {
        final Fixture fixture;
        final DispatchCommitmentState ledger;
        final double commitment;
        final Map<TripKey, AbstractTransitDriverAgent> drivers = new LinkedHashMap<>();
        final Trace trace;
        ControlledTransitDriverAgent dependent;
        DispatchCommitmentState.Version acceptedVersion;
        QSim qsim;
        List<String> supplyBefore;
        int decisionVisits, onePredecessorFinishedSamples, releaseChecks, releasedWaitingSamples, terminalChecks;

        Result(Fixture fixture, DispatchCommitmentState ledger, double commitment) {
            this.fixture = fixture;
            this.ledger = ledger;
            this.commitment = commitment;
            this.trace = new Trace(this);
        }
    }

    private static final class Trace implements BasicEventHandler {
        final Result result;
        final Map<TripKey, TransitDriverStartsEvent> starts = new LinkedHashMap<>();
        final Map<TripKey, PersonArrivalEvent> legEnds = new LinkedHashMap<>();
        final Map<TripKey, List<VehicleDepartsAtFacilityEvent>> departures = new LinkedHashMap<>();
        final Map<TripKey, List<VehicleArrivesAtFacilityEvent>> arrivals = new LinkedHashMap<>();
        final Map<TripKey, Integer> runningVehicleIdentityChecks = new LinkedHashMap<>();
        int eventCount, stuckEvents, arrivalBeforeReleaseChecks, arrivalDetachedVehicleChecks;
        double physicalReadyAtDependentStart = Double.NaN;

        Trace(Result result) {
            this.result = result;
            TRIPS.forEach(key -> {
                departures.put(key, new ArrayList<>());
                arrivals.put(key, new ArrayList<>());
                runningVehicleIdentityChecks.put(key, 0);
            });
        }

        @Override public void handleEvent(Event event) {
            eventCount++;
            if (event instanceof PersonStuckEvent) stuckEvents++;
            if (event instanceof TransitDriverStartsEvent start) {
                TripKey key = new TripKey(start.getTransitLineId().toString(), start.getTransitRouteId().toString(), start.getDepartureId().toString());
                assertTrue(TRIPS.contains(key));
                assertNull(starts.put(key, start), "No duplicate or resurrected service start is allowed");
                result.ledger.recordStarted(key, start.getTime(), start.getTime());
                if (key.equals(D)) {
                    assertTrue(legEnds.containsKey(A) && legEnds.containsKey(B), "Both predecessors must really finish before dependent start");
                    assertFalse(result.dependent.currentExecutionState().chainBlocked());
                    physicalReadyAtDependentStart = result.dependent.currentExecutionState().physicalReadySeconds();
                    assertEquals(Optional.of(result.acceptedVersion), result.dependent.currentExecutionState().acceptedVersion());
                }
            }
            if (event instanceof VehicleDepartsAtFacilityEvent departure) {
                TripKey key = keyForVehicle(departure.getVehicleId());
                var vehicle = result.qsim.getVehicles().get(departure.getVehicleId());
                var driver = result.drivers.get(key);
                assertSame(result.fixture.services.get(key).vehicle, vehicle.getVehicle());
                assertSame(driver, vehicle.getDriver(), "An actual facility departure must belong to the original running driver");
                assertSame(vehicle, driver.getVehicle());
                assertEquals(MobsimAgent.State.LEG, driver.getState());
                runningVehicleIdentityChecks.merge(key, 1, Integer::sum);
                departures.get(key).add(departure);
            }
            if (event instanceof VehicleArrivesAtFacilityEvent arrival) arrivals.get(keyForVehicle(arrival.getVehicleId())).add(arrival);
            if (event instanceof PersonArrivalEvent arrival) {
                TripKey key = result.drivers.entrySet().stream().filter(entry -> entry.getValue().getId().equals(arrival.getPersonId()))
                        .map(Map.Entry::getKey).findFirst().orElseThrow();
                assertNull(legEnds.put(key, arrival), "Each original vehicle must complete exactly one native leg");
                // AbstractQNetsimEngine.letVehicleArrive clears the vehicle's driver
                // before endLegAndComputeNextState emits this native arrival event.
                assertNull(result.qsim.getVehicles().get(result.fixture.services.get(key).vehicle.getId()).getDriver());
                arrivalDetachedVehicleChecks++;
                if (key.equals(A) || key.equals(B)) {
                    // TransitDriverAgentImpl emits this real arrival immediately before
                    // handleEndRoute invokes the native relocation/counter/release path.
                    assertBlocked(result);
                    arrivalBeforeReleaseChecks++;
                }
            }
        }

        private TripKey keyForVehicle(Id<Vehicle> vehicleId) {
            return result.fixture.services.entrySet().stream().filter(entry -> entry.getValue().vehicle.getId().equals(vehicleId))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
        }
    }
}
