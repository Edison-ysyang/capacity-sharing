package org.matsim.core.mobsim.qsim.pt;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Message;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.fakes.FakePassengerAgent;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Characterizes the actual QSim candidate-selection adapter, without a full controller run. */
class PassengerAccessEgressAdmissionCharacterizationTest {

	@Test
	void temporalCandidateGateReceivesNativeStopClockInBothDenialEventBranches() {
		for (boolean emitDenied : List.of(false, true)) {
			Fixture fixture = new Fixture(2, emitDenied, true);
			fixture.policy.windowEnd = 60;
			fixture.enqueue("f-at-cutoff", "p-unrestricted");
			fixture.callStop();
			assertEquals(List.of("p-unrestricted"), fixture.handler.enteringIds);
			assertEquals(List.of(60.0, 60.0), fixture.policy.timeClocks);
			assertEquals(List.of("p-unrestricted"), fixture.policy.evaluatedIds);
			if (emitDenied) {
				var detail = fixture.events.stream().filter(TransitBoardingDeniedDetailEvent.class::isInstance)
						.map(TransitBoardingDeniedDetailEvent.class::cast).toList();
				assertEquals(1, detail.size());
				assertEquals(TransitBoardingPolicy.DenialReason.OUTSIDE_SHARED_WINDOW, detail.getFirst().getReason());
			}
		}
	}

	@Test
	void physicalEntryRechecksTimeAndDoesNotRemoveARejectedUnitFromItsStop() {
		Fixture fixture = new Fixture(2, true, true);
		fixture.policy.windowEnd = 60;
		fixture.enqueue("f-delayed-entry");
		var freight = fixture.tracker.getAgentsAtFacility(fixture.stop.getId()).iterator().next();
		assertTrue(fixture.policy.boardingTimeDecision(freight, fixture.vehicle, fixture.line, null, fixture.stop, 59).allowed());
		assertFalse(fixture.adapter.handlePassengerEntering(freight, fixture.vehicle, fixture.stop.getId(), 60));
		assertTrue(fixture.vehicle.getPassengers().isEmpty());
		assertTrue(fixture.tracker.getAgentsAtFacility(fixture.stop.getId()).contains(freight));
		assertEquals(List.of(59.0, 60.0), fixture.policy.timeClocks);
	}

	@Test
	void passengerWinsLastPlaceEvenWhenFreightReachedTheStopFirst() {
		Fixture fixture = new Fixture(1, true, true);
		fixture.enqueue("f-first", "p-later");
		fixture.callStop();
		assertEquals(List.of("p-later"), fixture.handler.enteringIds);
		assertEquals(1, fixture.vehicle.getPassengers().size());
	}

	@Test
	void fifoUsesActualArrivalRatherThanActorClassOrInsertionOrderInBothEventBranches() {
		for (boolean emitDenied : List.of(false, true)) {
			Fixture fixture = new Fixture(1, emitDenied, true);
			fixture.policy.arrivalOrder = true;
			// Deliberately insert out of timestamp order: native clock wins.
			fixture.enqueueAt(20, "p-later");
			fixture.enqueueAt(10, "f-earlier");
			fixture.callStop();
			assertEquals(List.of("f-earlier"), fixture.handler.enteringIds);
			assertEquals(1, fixture.vehicle.getPassengers().size());
		}
	}

	@Test
	void fifoExactTimeTiesPreserveRegistrationSequenceWithoutAnActorOrIdTieBreaker() {
		Fixture fixture = new Fixture(4, true, true);
		fixture.policy.arrivalOrder = true;
		fixture.enqueueAt(10, "f-z", "p-z", "f-a", "p-a");
		fixture.callStop();
		assertEquals(List.of("f-z", "p-z", "f-a", "p-a"), fixture.handler.enteringIds);
	}

	@Test
	void fifoRetainsARejectedCandidatesArrivalForTheNextVehicle() {
		Fixture fixture = new Fixture(1, true, true);
		fixture.policy.arrivalOrder = true;
		assertTrue(fixture.vehicle.addPassenger(new FakePassengerAgent(Id.createPersonId("onboard"), null)));
		fixture.enqueueAt(5, "f-waiting");
		fixture.callStop();
		var freight = fixture.tracker.getAgentsAtFacility(fixture.stop.getId()).getFirst();
		assertEquals(5, fixture.tracker.getQueueArrival(freight, fixture.stop.getId()).readyTimeSeconds());
		fixture.enqueueAt(70, "p-new");
		var next = new TransitQVehicle(VehicleUtils.createVehicle(Id.createVehicleId("next"), fixture.vehicle.getVehicle().getType()));
		next.setStopHandler(fixture.handler);
		fixture.adapter.calculateStopTimeAndTriggerBoarding(null, fixture.line, next, fixture.stop, List.of(), 90);
		assertEquals(List.of("f-waiting"), fixture.handler.enteringIds);
	}

	@Test
	void fifoSkipsAnIneligibleHeadWithoutBlockingEligiblePassengers() {
		for (boolean emitDenied : List.of(false, true)) {
			Fixture fixture = new Fixture(1, emitDenied, true);
			fixture.policy.arrivalOrder = true;
			fixture.policy.windowEnd = 60;
			fixture.enqueueAt(10, "f-outside-window");
			fixture.enqueueAt(20, "p-allowed");
			fixture.callStop();
			assertEquals(List.of("p-allowed"), fixture.handler.enteringIds);
		}
	}

	@Test
	void legacyPriorityAndNoPolicyKeepTheirOriginalStableQueueOrdering() {
		Fixture prioritized = new Fixture(4, true, true);
		prioritized.enqueueAt(20, "f-z", "p-z");
		prioritized.enqueueAt(10, "f-a", "p-a");
		prioritized.callStop();
		assertEquals(List.of("p-z", "p-a", "f-z", "f-a"), prioritized.handler.enteringIds);
		Fixture nativeOnly = new Fixture(2, true, false);
		nativeOnly.enqueueAt(20, "f-z");
		nativeOnly.enqueueAt(10, "p-a");
		nativeOnly.callStop();
		assertEquals(List.of("f-z", "p-a"), nativeOnly.handler.enteringIds);
	}

	@Test
	void stablePrioritySortPreservesBothPassengerAndFreightQueueOrder() {
		Fixture fixture = new Fixture(6, true, true);
		fixture.enqueue("f-z", "p-z", "f-a", "p-a", "f-m", "p-m");
		fixture.callStop();
		assertEquals(List.of("p-z", "p-a", "p-m", "f-z", "f-a", "f-m"),
				fixture.handler.enteringIds);
	}

	@Test
	void withoutAPolicyNativeQueueOrderRemainsUnchanged() {
		Fixture fixture = new Fixture(2, true, false);
		fixture.enqueue("f-first", "p-later", "p-last");
		fixture.callStop();
		assertEquals(List.of("f-first", "p-later"), fixture.handler.enteringIds);
	}

	@Test
	void noCandidateCanTakeMoreThanTheNativeRemainingCapacity() {
		for (boolean emitDenied : List.of(false, true)) {
			Fixture fixture = new Fixture(2, emitDenied, true);
			assertTrue(fixture.vehicle.addPassenger(new FakePassengerAgent(Id.createPersonId("onboard"), null)));
			fixture.enqueue("p-1", "p-2", "f-1");
			fixture.callStop();
			assertEquals(List.of("p-1"), fixture.handler.enteringIds);
			assertEquals(2, fixture.vehicle.getPassengers().size());
			assertFalse(fixture.vehicle.addPassenger(new FakePassengerAgent(Id.createPersonId("extra"), null)));
		}
	}

	@Test
	void alightingIsCreditedBeforeSelectingNewEntrantsAndReleasedBeforeNativeAdd() {
		Fixture fixture = new Fixture(1, true, true);
		assertTrue(fixture.vehicle.addPassenger(new FakePassengerAgent(Id.createPersonId("alighting"), fixture.stop)));
		fixture.enqueue("p-entering");
		fixture.callStop();
		assertEquals(List.of("alighting"), fixture.handler.leavingIds);
		assertEquals(List.of("p-entering"), fixture.handler.enteringIds);
		assertEquals(List.of("alight:alighting", "board:p-entering"), fixture.handler.nativeTransitions);
		assertEquals(1, fixture.vehicle.getPassengers().size());
	}

	@Test
	void policySeesPreviouslyAcceptedCandidatesInOrder() {
		Fixture fixture = new Fixture(3, true, true);
		fixture.enqueue("f-1", "p-1", "p-2");
		fixture.callStop();
		assertEquals(List.of(List.of(), List.of("p-1"), List.of("p-1", "p-2")),
				fixture.policy.acceptedSnapshots);
	}

	@Test
	void deniedEventBranchNowStopsPolicyEvaluationAtNativeCapacity() {
		// Before the documented correction this list contained both candidates.
		Fixture fixture = new Fixture(1, true, true);
		fixture.enqueue("p-1", "p-2");
		fixture.callStop();
		assertEquals(List.of("p-1"), fixture.policy.evaluatedIds);
		assertEquals(List.of("p-1"), fixture.handler.enteringIds);
	}

	@Test
	void noDeniedEventBranchAlreadyStopsPolicyEvaluationAtNativeCapacity() {
		Fixture fixture = new Fixture(1, false, true);
		fixture.enqueue("p-1", "p-2");
		fixture.callStop();
		assertEquals(List.of("p-1"), fixture.policy.evaluatedIds);
	}

	@Test
	void nativeDenialHasOneDiagnosticCounterpartWithThePreMutationSnapshot() {
		Fixture fixture = new Fixture(1, true, true);
		fixture.enqueue("p-1", "p-2");
		fixture.callStop();
		assertEquals(1, fixture.events.stream().filter(BoardingDeniedEvent.class::isInstance).count());
		var details = fixture.events.stream().filter(TransitBoardingDeniedDetailEvent.class::isInstance)
				.map(TransitBoardingDeniedDetailEvent.class::cast).toList();
		assertEquals(1, details.size());
		var detail = details.getFirst();
		assertEquals(TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY, detail.getReason());
		assertEquals("p-2", detail.getPersonId().toString());
		assertEquals("line", detail.getLineId().toString());
		assertEquals(fixture.stop.getId(), detail.getStopId());
		assertEquals(1, detail.getDetails().passengers());
		assertEquals(1, detail.getDetails().nativeCapacity());
		assertEquals("", detail.getAttributes().get("departure"));
		assertEquals("1", detail.getAttributes().get("schemaVersion"));
	}

	@Test
	void unrelatedRoutesDoNotGenerateDenialsEvenWhenVehicleIsFull() {
		Fixture fixture = new Fixture(1, true, true);
		assertTrue(fixture.vehicle.addPassenger(new FakePassengerAgent(Id.createPersonId("onboard"), null)));
		fixture.tracker.addAgentToStop(0, new FakePassengerAgent(Id.createPersonId("not-a-candidate"), null) {
			@Override
			public boolean getEnterTransitRoute(TransitLine line, TransitRoute route,
					List<org.matsim.pt.transitSchedule.api.TransitRouteStop> stops, TransitVehicle vehicle) {
				return false;
			}
		}, fixture.stop.getId());
		fixture.callStop();
		assertTrue(fixture.policy.evaluatedIds.isEmpty());
		assertEquals(0, fixture.events.stream().filter(BoardingDeniedEvent.class::isInstance).count());
		assertEquals(0, fixture.events.stream().filter(TransitBoardingDeniedDetailEvent.class::isInstance).count());
	}

	@Test
	void interleavedVehicleStopCallsKeepPendingDenialsWithTheirOwnVehicle() {
		verifyInterleavedVehicleCalls(true);
		verifyInterleavedVehicleCalls(false);
	}

	private static void verifyInterleavedVehicleCalls(boolean installPolicy) {
		Fixture fixture = new Fixture(1, true, installPolicy);
		assertTrue(fixture.vehicle.addPassenger(new FakePassengerAgent(Id.createPersonId("onboard-a"), null)));
		fixture.enqueue("p-at-a");
		fixture.handler.stopTime = 30;
		fixture.callStop();
		assertEquals(0, fixture.events.stream().filter(BoardingDeniedEvent.class::isInstance).count());

		TransitStopFacility secondStop = fixture.scenario.getTransitSchedule().getFactory()
				.createTransitStopFacility(Id.create("stop-b", TransitStopFacility.class), new Coord(1, 0), false);
		TransitQVehicle secondVehicle = new TransitQVehicle(VehicleUtils.createVehicle(Id.createVehicleId("vehicle-b"),
				fixture.vehicle.getVehicle().getType()));
		secondVehicle.setStopHandler(new RecordingNativeStopHandler());
		assertTrue(secondVehicle.addPassenger(new FakePassengerAgent(Id.createPersonId("onboard-b"), null)));
		fixture.tracker.addAgentToStop(0, new FakePassengerAgent(Id.createPersonId("p-at-b"), null), secondStop.getId());
		fixture.adapter.calculateStopTimeAndTriggerBoarding(null, fixture.line, secondVehicle, secondStop, List.of(), 61);
		var afterSecond = fixture.events.stream().filter(BoardingDeniedEvent.class::isInstance)
				.map(BoardingDeniedEvent.class::cast).toList();
		assertEquals(1, afterSecond.size(), "completion of vehicle B must not flush vehicle A's pending denial");
		assertEquals("p-at-b", afterSecond.getFirst().getPersonId().toString());
		assertEquals(secondVehicle.getId(), afterSecond.getFirst().getVehicleId());

		fixture.handler.stopTime = 0;
		fixture.adapter.calculateStopTimeAndTriggerBoarding(null, fixture.line, fixture.vehicle, fixture.stop, List.of(), 90);
		var nativeDenials = fixture.events.stream().filter(BoardingDeniedEvent.class::isInstance)
				.map(BoardingDeniedEvent.class::cast).toList();
		assertEquals(2, nativeDenials.size());
		assertEquals("p-at-a", nativeDenials.getLast().getPersonId().toString());
		assertEquals(fixture.vehicle.getId(), nativeDenials.getLast().getVehicleId());
		assertEquals(90, nativeDenials.getLast().getTime());
		var details = fixture.events.stream().filter(TransitBoardingDeniedDetailEvent.class::isInstance)
				.map(TransitBoardingDeniedDetailEvent.class::cast).toList();
		assertEquals(installPolicy ? 2 : 0, details.size());
		if (installPolicy) {
			assertEquals(secondStop.getId(), details.getFirst().getStopId());
			assertEquals(secondVehicle.getId(), details.getFirst().getVehicleId());
			assertEquals(fixture.stop.getId(), details.getLast().getStopId());
			assertEquals(fixture.vehicle.getId(), details.getLast().getVehicleId());
		}
	}

	private static final class Fixture {
		private final Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		private final TransitStopFacility stop = scenario.getTransitSchedule().getFactory()
				.createTransitStopFacility(Id.create("stop", TransitStopFacility.class), new Coord(0, 0), false);
		private final TransitLine line = scenario.getTransitSchedule().getFactory()
				.createTransitLine(Id.create("line", TransitLine.class));
		private final RecordingPolicy policy = new RecordingPolicy();
		private final RecordingNativeStopHandler handler = new RecordingNativeStopHandler();
		private final TransitStopAgentTracker tracker;
		private final PassengerAccessEgressImpl adapter;
		private final TransitQVehicle vehicle;
		private final List<Event> events = new ArrayList<>();

		private Fixture(int capacity, boolean emitDenied, boolean installPolicy) {
			scenario.getConfig().vspExperimental().setGeneratingBoardingDeniedEvent(emitDenied);
			if (installPolicy) {
				scenario.addScenarioElement(TransitBoardingPolicy.SCENARIO_ELEMENT, policy);
			}
			var events = EventsUtils.createEventsManager();
			events.addHandler((BasicEventHandler) this.events::add);
			tracker = new TransitStopAgentTracker(events);
			adapter = new PassengerAccessEgressImpl(null, tracker, scenario, events);
			VehicleType type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class));
			type.getCapacity().setSeats(capacity);
			type.getCapacity().setStandingRoom(0);
			vehicle = new TransitQVehicle(VehicleUtils.createVehicle(Id.createVehicleId("vehicle"), type));
			vehicle.setStopHandler(handler);
		}

		private void enqueue(String... ids) {
			enqueueAt(0, ids);
		}

		private void enqueueAt(double time, String... ids) {
			for (String id : ids) {
				tracker.addAgentToStop(time, new FakePassengerAgent(Id.createPersonId(id), null), stop.getId());
			}
		}

		private void callStop() {
			adapter.calculateStopTimeAndTriggerBoarding(null, line, vehicle, stop, List.of(), 60);
		}
	}

	private static final class RecordingPolicy implements TransitBoardingPolicy {
		private final List<String> evaluatedIds = new ArrayList<>();
		private final List<List<String>> acceptedSnapshots = new ArrayList<>();
		private double windowEnd = Double.POSITIVE_INFINITY;
		private final List<Double> timeClocks = new ArrayList<>();
		private boolean arrivalOrder;

		@Override
		public boolean usesQueueArrivalTime() { return arrivalOrder; }

		@Override
		public BoardingDecision boardingTimeDecision(PTPassengerAgent passenger, TransitVehicle vehicle,
				TransitLine line, TransitRoute route, TransitStopFacility stop, double nowSeconds) {
			timeClocks.add(nowSeconds);
			return passenger.getId().toString().startsWith("f-") && nowSeconds >= windowEnd
					? BoardingDecision.deny(DenialReason.OUTSIDE_SHARED_WINDOW) : BoardingDecision.allow();
		}

		@Override
		public int priority(PTPassengerAgent passenger, TransitVehicle vehicle,
				TransitLine line, TransitRoute route, TransitStopFacility stop) {
			return !arrivalOrder && passenger.getId().toString().startsWith("p-") ? 100 : 0;
		}

		@Override
		public boolean allowsBoarding(PTPassengerAgent passenger, TransitVehicle vehicle,
				TransitLine line, TransitRoute route, TransitStopFacility stop,
				List<PTPassengerAgent> leaving, List<PTPassengerAgent> accepted) {
			evaluatedIds.add(passenger.getId().toString());
			acceptedSnapshots.add(ids(accepted));
			return true;
		}
	}

	/** Mutates only native vehicle state, mirroring the stop handler's alight-before-board contract. */
	private static final class RecordingNativeStopHandler implements TransitStopHandler {
		private double stopTime;
		private List<String> leavingIds = List.of();
		private List<String> enteringIds = List.of();
		private final List<String> nativeTransitions = new ArrayList<>();

		@Override
		public double handleTransitStop(TransitStopFacility stop, double now,
				List<PTPassengerAgent> leaving, List<PTPassengerAgent> entering,
				List<PTPassengerAgent> relocating, PassengerAccessEgress access, TransitVehicle vehicle) {
			leavingIds = ids(leaving);
			enteringIds = ids(entering);
			for (PTPassengerAgent agent : leaving) {
				assertTrue(vehicle.removePassenger(agent));
				nativeTransitions.add("alight:" + agent.getId());
			}
			for (PTPassengerAgent agent : entering) {
				assertTrue(vehicle.addPassenger(agent), "native capacity must be available for every selected entrant");
				nativeTransitions.add("board:" + agent.getId());
			}
			return stopTime;
		}

		@Override
		public Message toMessage() {
			throw new UnsupportedOperationException("Local deterministic fixture only");
		}
	}

	private static List<String> ids(List<PTPassengerAgent> agents) {
		return agents.stream().map(agent -> agent.getId().toString()).toList();
	}
}
