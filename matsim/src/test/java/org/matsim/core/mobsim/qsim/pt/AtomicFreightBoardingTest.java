package org.matsim.core.mobsim.qsim.pt;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Message;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.PassengerAgent;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.interfaces.MobsimVehicle;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.fakes.FakePassengerAgent;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Direct deterministic adapter and native-vehicle checks only. No QSim, Controller,
 * Mobsim, timetable predictor, event replay, or simulation execution is constructed.
 */
class AtomicFreightBoardingTest {

	@Test
	void fiveUnitOrderCannotPartiallyEnterFourPlacesInEitherDiagnosticBranch() {
		for (boolean emitDenied : List.of(false, true)) {
			Fixture fixture = new Fixture(4, emitDenied);
			var members = fixture.defineOrder("order", 5);
			fixture.enqueue(5, members);
			fixture.selectAt(30);
			assertTrue(fixture.handler.selected.isEmpty());
			fixture.assertNoEntryAndOriginalWaiting(members, 5);
			if (emitDenied) fixture.assertDenialForEach(members, TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY);
		}
	}

	@Test
	void fiveUnitOrderIsSelectedAsOneContinuousBatchWhenFivePlacesExist() {
		Fixture fixture = new Fixture(5, true);
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		fixture.selectAt(30);
		assertEquals(ids(members), ids(fixture.handler.selected));
		assertTrue(fixture.denialEvents().isEmpty());
		// The recording handler does not mutate the queue; selection is not entry.
		fixture.assertNoEntryAndOriginalWaiting(members, 5);
	}

	@Test
	void missingUnitLeavesEveryPresentMemberWaitingWithoutPartialSelection() {
		Fixture fixture = new Fixture(10, true);
		var members = fixture.defineOrder("order", 5);
		var present = members.subList(0, 4);
		fixture.enqueue(5, present);
		fixture.selectAt(30);
		assertTrue(fixture.handler.selected.isEmpty());
		fixture.assertNoEntryAndOriginalWaiting(present, 5);
		fixture.assertDenialForEach(present, TransitBoardingPolicy.DenialReason.WHOLE_ORDER_NOT_READY);
	}

	@Test
	void oneRouteIneligibleUnitMakesTheWholeOrderIneligible() {
		Fixture fixture = new Fixture(10, true);
		var members = fixture.defineOrder("order", 5);
		members.getLast().routeEligible = false;
		fixture.enqueue(5, members);
		fixture.selectAt(30);
		assertTrue(fixture.handler.selected.isEmpty());
		fixture.assertNoEntryAndOriginalWaiting(members, 5);
		assertTrue(fixture.denialEvents().stream().allMatch(event -> event.getReason()
				== TransitBoardingPolicy.DenialReason.WHOLE_ORDER_INELIGIBLE));
		assertFalse(fixture.denialEvents().isEmpty());
	}

	@Test
	void actualEntryRechecksFivePlacesAndCommitsExactlyFiveEntriesTogether() {
		Fixture fixture = new Fixture(5, true);
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		assertEquals(5, fixture.adapter.handlePassengersEntering(asAgents(members), fixture.vehicle,
				fixture.stop.getId(), 30));
		assertEquals(ids(members), fixture.vehicle.getPassengers().stream()
				.map(agent -> agent.getId().toString()).toList());
		assertTrue(fixture.tracker.getAgentsAtFacility(fixture.stop.getId()).isEmpty());
		assertEquals(5, fixture.entryEvents().size());
		for (RecordingPassenger member : members) {
			assertSame(fixture.vehicle, member.getVehicle());
			assertEquals(1, member.vehicleAssignments);
			assertThrows(IllegalStateException.class,
					() -> fixture.tracker.getQueueArrival(member, fixture.stop.getId()));
			verify(fixture.internal).unregisterAdditionalAgentOnLink(member.getId(), fixture.linkId);
		}
		verifyNoMoreInteractions(fixture.internal);
		for (PersonEntersPtVehicleEvent event : fixture.entryEvents()) {
			assertEquals(30, event.getTime());
			assertEquals(fixture.vehicle.getId(), event.getVehicleId());
			assertEquals(fixture.line.getId(), event.getTransitLine());
			assertEquals(fixture.route.getId(), event.getTransitRoute());
		}
	}

	@Test
	void actualEntryWithOnlyFourPlacesDoesNotEvenAttemptNativeAddition() {
		Fixture fixture = new Fixture(4, true);
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		assertEquals(0, fixture.adapter.handlePassengersEntering(asAgents(members), fixture.vehicle,
				fixture.stop.getId(), 30));
		assertEquals(0, fixture.nativeAddCalls);
		fixture.assertNoEntryAndOriginalWaiting(members, 5);
	}

	@Test
	void legacySinglePassengerEntryCannotBypassTheIndivisibleGroupContract() {
		Fixture fixture = new Fixture(5, true);
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		assertThrows(IllegalStateException.class, () -> fixture.adapter.handlePassengerEntering(
				members.getFirst(), fixture.vehicle, fixture.stop.getId(), 30));
		assertEquals(0, fixture.nativeAddCalls);
		fixture.assertNoEntryAndOriginalWaiting(members, 5);
	}

	@Test
	void groupSelectedBeforeCutoffCannotPartiallyCommitAtTheActualCutoff() {
		Fixture fixture = new Fixture(5, true);
		fixture.policy.windowEnd = 60;
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		fixture.selectAt(59);
		assertEquals(ids(members), ids(fixture.handler.selected));
		assertEquals(0, fixture.adapter.handlePassengersEntering(fixture.handler.selected,
				fixture.vehicle, fixture.stop.getId(), 60));
		assertEquals(0, fixture.nativeAddCalls);
		fixture.assertNoEntryAndOriginalWaiting(members, 5);
	}

	@Test
	void failedThirdNativeAddRollsBackEarlierAddsBeforeAnyEntrySideEffects() {
		Fixture fixture = new Fixture(5, true);
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		fixture.failNativeAddAt = 3;
		assertEquals(0, fixture.adapter.handlePassengersEntering(asAgents(members), fixture.vehicle,
				fixture.stop.getId(), 30));
		assertEquals(3, fixture.nativeAddCalls);
		fixture.assertNoEntryAndOriginalWaiting(members, 5);
		// Removal during rollback must not erase the queue clocks: a later batch succeeds.
		fixture.failNativeAddAt = -1;
		assertEquals(5, fixture.adapter.handlePassengersEntering(asAgents(members), fixture.vehicle,
				fixture.stop.getId(), 40));
		assertEquals(5, fixture.entryEvents().size());
		assertTrue(fixture.entryEvents().stream().allMatch(event -> event.getTime() == 40));
	}

	@Test
	void rollbackPreservesAnExistingNativeOccupant() {
		Fixture fixture = new Fixture(6, true);
		var occupant = new RecordingPassenger("p-onboard", fixture.linkId);
		assertTrue(fixture.vehicle.addPassenger(occupant));
		fixture.nativeAddCalls = 0;
		fixture.failNativeAddAt = 3;
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		assertEquals(0, fixture.adapter.handlePassengersEntering(asAgents(members), fixture.vehicle,
				fixture.stop.getId(), 30));
		assertEquals(List.of(occupant), List.copyOf(fixture.vehicle.getPassengers()));
		assertTrue(fixture.entryEvents().isEmpty());
		verifyNoInteractions(fixture.internal);
		for (RecordingPassenger member : members) {
			assertEquals(5, fixture.tracker.getQueueArrival(member, fixture.stop.getId()).readyTimeSeconds());
			assertNull(member.getVehicle());
			assertEquals(0, member.vehicleAssignments);
		}
	}

	@Test
	void fifoUsesTheLatestMemberReadyTimeRatherThanTheFirstMembersClock() {
		Fixture fixture = new Fixture(5, true);
		fixture.policy.arrivalOrder = true;
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members.subList(0, 4));
		var passenger = fixture.enqueuePassenger("p-before-complete-order", 15);
		fixture.enqueue(20, members.subList(4, 5));
		fixture.selectAt(30);
		assertEquals(List.of(passenger.getId().toString()), ids(fixture.handler.selected));
		fixture.assertDenialForEach(members, TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY);
	}

	@Test
	void fifoExactReadyTimeTieUsesLastMembersRegistrationSequence() {
		Fixture fixture = new Fixture(5, true);
		fixture.policy.arrivalOrder = true;
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(10, members.subList(0, 4));
		var passenger = fixture.enqueuePassenger("p-before-last-order-member", 10);
		fixture.enqueue(10, members.subList(4, 5));
		fixture.selectAt(30);
		assertEquals(List.of(passenger.getId().toString()), ids(fixture.handler.selected));
	}

	@Test
	void passengerFirstConsumesPassengerPlacesBeforeTestingTheWholeOrder() {
		Fixture fixture = new Fixture(5, true);
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		var passenger = fixture.enqueuePassenger("p-later", 20);
		fixture.selectAt(30);
		assertEquals(List.of(passenger.getId().toString()), ids(fixture.handler.selected));
		fixture.assertDenialForEach(members, TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY);
	}

	@Test
	void configuredFifoCapacityBlockingPreventsAYoungerPassengerBypassingTheOrder() {
		Fixture fixture = new Fixture(4, true);
		fixture.policy.arrivalOrder = true;
		fixture.policy.blockAfterCapacityDenial = true;
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		fixture.enqueuePassenger("p-younger", 20);
		fixture.selectAt(30);
		assertTrue(fixture.handler.selected.isEmpty());
		fixture.assertDenialForEach(members, TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY);
	}

	@Test
	void defaultNonblockingPolicyCanSkipAnOversizedOrderWithoutSplittingIt() {
		Fixture fixture = new Fixture(4, true);
		fixture.policy.arrivalOrder = true;
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		var passenger = fixture.enqueuePassenger("p-younger", 20);
		fixture.selectAt(30);
		assertEquals(List.of(passenger.getId().toString()), ids(fixture.handler.selected));
		fixture.assertDenialForEach(members, TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY);
	}

	@Test
	void freightShareDecisionMustAcceptEveryUnitBeforeTheOrderIsSelected() {
		Fixture fixture = new Fixture(10, true);
		fixture.policy.freightUnitCap = 4;
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		fixture.selectAt(30);
		assertTrue(fixture.handler.selected.isEmpty());
		fixture.assertNoEntryAndOriginalWaiting(members, 5);
		fixture.assertDenialForEach(members, TransitBoardingPolicy.DenialReason.FREIGHT_SHARE_CAP);
	}

	@Test
	void fifoCanBlockFollowingCandidatesWhenTheWholeOrderExceedsItsFreightShareLimit() {
		Fixture fixture = new Fixture(10, true);
		fixture.policy.arrivalOrder = true;
		fixture.policy.blockAfterCapacityDenial = true;
		fixture.policy.freightUnitCap = 4;
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		var passenger = fixture.enqueuePassenger("p-younger", 20);
		fixture.selectAt(30);
		assertTrue(fixture.handler.selected.isEmpty());
		fixture.assertDenialForEach(members, TransitBoardingPolicy.DenialReason.FREIGHT_SHARE_CAP);
		assertTrue(fixture.denialEvents().stream().anyMatch(event -> event.getPersonId().equals(passenger.getId())
				&& event.getReason() == TransitBoardingPolicy.DenialReason.WHOLE_ORDER_QUEUE_BLOCKED));
	}

	@Test
	void outsideWindowOrderNeverBlocksAnOtherwiseEligiblePassenger() {
		Fixture fixture = new Fixture(10, true);
		fixture.policy.arrivalOrder = true;
		fixture.policy.blockAfterCapacityDenial = true;
		fixture.policy.windowEnd = 30;
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		var passenger = fixture.enqueuePassenger("p-younger", 20);
		fixture.selectAt(30);
		assertEquals(List.of(passenger.getId().toString()), ids(fixture.handler.selected));
		fixture.assertDenialForEach(members, TransitBoardingPolicy.DenialReason.OUTSIDE_SHARED_WINDOW);
	}

	@Test
	void successfulRetryBeforeStopCompletionClearsItsEarlierPendingDenials() {
		Fixture fixture = new Fixture(5, true);
		var occupants = new ArrayList<RecordingPassenger>();
		for (int i = 1; i <= 5; i++) {
			var occupant = new RecordingPassenger("p-alighting-" + i, fixture.linkId);
			occupant.exitStopId = fixture.stop.getId();
			assertTrue(fixture.vehicle.addPassenger(occupant));
			occupants.add(occupant);
		}
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		fixture.handler.stopTime = 1;
		fixture.handler.beforeReturn = () -> {
			// Selection credits the five imminent leavers, but native places are not free yet.
			assertEquals(ids(members), ids(fixture.handler.selected));
			assertEquals(0, fixture.adapter.handlePassengersEntering(fixture.handler.selected,
					fixture.vehicle, fixture.stop.getId(), 30));
		};
		fixture.selectAt(30);
		assertEquals(List.copyOf(occupants), List.copyOf(fixture.vehicle.getPassengers()));
		assertTrue(fixture.entryEvents().isEmpty());
		assertTrue(fixture.denialEvents().isEmpty(), "Nonzero stop time must keep diagnostics pending");
		assertTrue(fixture.events.stream().noneMatch(BoardingDeniedEvent.class::isInstance));
		verifyNoInteractions(fixture.internal);
		for (RecordingPassenger member : members) {
			assertEquals(5, fixture.tracker.getQueueArrival(member, fixture.stop.getId()).readyTimeSeconds());
			assertNull(member.getVehicle());
			assertEquals(0, member.vehicleAssignments);
		}

		// Native alighting is deliberately performed only after the failed entry attempt.
		for (RecordingPassenger occupant : occupants) assertTrue(fixture.vehicle.removePassenger(occupant));
		fixture.handler.beforeReturn = () -> assertEquals(5, fixture.adapter.handlePassengersEntering(
				fixture.handler.selected, fixture.vehicle, fixture.stop.getId(), 31));
		fixture.selectAt(31);
		assertEquals(5, fixture.entryEvents().size());
		assertTrue(fixture.entryEvents().stream().allMatch(event -> event.getTime() == 31));
		assertTrue(fixture.tracker.getAgentsAtFacility(fixture.stop.getId()).isEmpty());

		fixture.handler.beforeReturn = null;
		fixture.handler.stopTime = 0;
		fixture.selectAt(32);
		assertTrue(fixture.handler.selected.isEmpty());
		assertEquals(5, fixture.vehicle.getPassengers().size());
		assertTrue(fixture.denialEvents().isEmpty(), "Already-boarded units must not receive stale detail events");
		assertTrue(fixture.events.stream().noneMatch(BoardingDeniedEvent.class::isInstance),
				"Already-boarded units must not receive stale generic denial events at stop completion");
	}

	@Test
	void actualCommitRejectsTheWholeOrderIfOneMemberHasADifferentDestination() {
		Fixture fixture = new Fixture(5, true);
		var members = fixture.defineOrder("order", 5);
		for (RecordingPassenger member : members)
			member.destinationStopId = Id.create("common-destination", TransitStopFacility.class);
		members.getLast().destinationStopId = Id.create("different-destination", TransitStopFacility.class);
		fixture.enqueue(5, members);
		assertEquals(0, fixture.adapter.handlePassengersEntering(asAgents(members), fixture.vehicle,
				fixture.stop.getId(), 30));
		assertEquals(0, fixture.nativeAddCalls);
		fixture.assertNoEntryAndOriginalWaiting(members, 5);
	}

	@Test
	void serviceIsolationPrecedesCapacityAndDoesNotBlockAnEligiblePassenger() {
		Fixture fixture = new Fixture(4, true);
		fixture.policy.arrivalOrder = true;
		fixture.policy.blockAfterCapacityDenial = true;
		fixture.policy.freightServiceDenied = true;
		var members = fixture.defineOrder("order", 5);
		fixture.enqueue(5, members);
		var passenger = fixture.enqueuePassenger("p-younger-on-passenger-service", 20);
		fixture.selectAt(30);
		assertEquals(List.of(passenger.getId().toString()), ids(fixture.handler.selected));
		fixture.assertDenialForEach(members, TransitBoardingPolicy.DenialReason.ISOLATION_RULE);
		assertTrue(fixture.denialEvents().stream().noneMatch(event -> event.getReason()
				== TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY));
		assertTrue(fixture.denialEvents().stream().noneMatch(event -> event.getPersonId().equals(passenger.getId())));
	}

	private static final class Fixture {
		final Id<Link> linkId = Id.createLinkId("boarding-link");
		final InternalInterface internal = mock(InternalInterface.class);
		final List<Event> events = new ArrayList<>();
		final GroupPolicy policy = new GroupPolicy();
		final RecordingStopHandler handler = new RecordingStopHandler();
		final TransitStopFacility stop;
		final TransitLine line;
		final TransitRoute route;
		final TransitStopAgentTracker tracker;
		final PassengerAccessEgressImpl adapter;
		final TransitQVehicle vehicle;
		int failNativeAddAt = -1;
		int nativeAddCalls;

		Fixture(int capacity, boolean emitDenied) {
			var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
			scenario.getConfig().vspExperimental().setGeneratingBoardingDeniedEvent(emitDenied);
			scenario.addScenarioElement(TransitBoardingPolicy.SCENARIO_ELEMENT, policy);
			var factory = scenario.getTransitSchedule().getFactory();
			stop = factory.createTransitStopFacility(Id.create("stop", TransitStopFacility.class), new Coord(0, 0), false);
			stop.setLinkId(linkId);
			scenario.getTransitSchedule().addStopFacility(stop);
			line = factory.createTransitLine(Id.create("line", TransitLine.class));
			route = mock(TransitRoute.class);
			when(route.getId()).thenReturn(Id.create("route", TransitRoute.class));
			TransitDriverAgent driver = mock(TransitDriverAgent.class);
			when(driver.getTransitLine()).thenReturn(line);
			when(driver.getTransitRoute()).thenReturn(route);
			VehicleType type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class));
			type.getCapacity().setSeats(capacity);
			type.getCapacity().setStandingRoom(0);
			vehicle = new TransitQVehicle(VehicleUtils.createVehicle(Id.createVehicleId("vehicle"), type)) {
				@Override public boolean addPassenger(PassengerAgent passenger) {
					nativeAddCalls++;
					return nativeAddCalls != failNativeAddAt && super.addPassenger(passenger);
				}
			};
			vehicle.setDriver(driver);
			vehicle.setStopHandler(handler);
			var manager = EventsUtils.createEventsManager();
			manager.addHandler((BasicEventHandler) events::add);
			tracker = new TransitStopAgentTracker(manager);
			adapter = new PassengerAccessEgressImpl(internal, tracker, scenario, manager);
		}

		List<RecordingPassenger> defineOrder(String orderId, int units) {
			var members = new ArrayList<RecordingPassenger>();
			for (int i = 1; i <= units; i++) members.add(new RecordingPassenger("f-" + orderId + "-" + i, linkId));
			var group = new TransitBoardingPolicy.BoardingGroup(orderId, members.stream()
					.map(RecordingPassenger::getId).toList());
			for (RecordingPassenger member : members) policy.groups.put(member.getId(), group);
			return List.copyOf(members);
		}

		void enqueue(double time, List<RecordingPassenger> members) {
			for (RecordingPassenger member : members) tracker.addAgentToStop(time, member, stop.getId());
		}

		RecordingPassenger enqueuePassenger(String id, double time) {
			var passenger = new RecordingPassenger(id, linkId);
			tracker.addAgentToStop(time, passenger, stop.getId());
			return passenger;
		}

		void selectAt(double time) {
			adapter.calculateStopTimeAndTriggerBoarding(route, line, vehicle, stop, List.of(), time);
		}

		List<PersonEntersPtVehicleEvent> entryEvents() {
			return events.stream().filter(PersonEntersPtVehicleEvent.class::isInstance)
					.map(PersonEntersPtVehicleEvent.class::cast).toList();
		}

		List<TransitBoardingDeniedDetailEvent> denialEvents() {
			return events.stream().filter(TransitBoardingDeniedDetailEvent.class::isInstance)
					.map(TransitBoardingDeniedDetailEvent.class::cast).toList();
		}

		void assertNoEntryAndOriginalWaiting(List<RecordingPassenger> members, double originalReadyTime) {
			assertTrue(vehicle.getPassengers().isEmpty());
			assertTrue(entryEvents().isEmpty());
			verifyNoInteractions(internal);
			for (RecordingPassenger member : members) {
				assertTrue(tracker.getAgentsAtFacility(stop.getId()).contains(member));
				assertEquals(originalReadyTime, tracker.getQueueArrival(member, stop.getId()).readyTimeSeconds());
				assertNull(member.getVehicle());
				assertEquals(0, member.vehicleAssignments);
			}
		}

		void assertDenialForEach(List<RecordingPassenger> members, TransitBoardingPolicy.DenialReason reason) {
			for (RecordingPassenger member : members) assertTrue(denialEvents().stream()
					.anyMatch(event -> event.getPersonId().equals(member.getId()) && event.getReason() == reason),
					"Missing " + reason + " diagnostic for " + member.getId());
		}
	}

	private static final class GroupPolicy implements TransitBoardingPolicy {
		final Map<Id<Person>, BoardingGroup> groups = new LinkedHashMap<>();
		boolean arrivalOrder;
		boolean blockAfterCapacityDenial;
		boolean freightServiceDenied;
		int freightUnitCap = Integer.MAX_VALUE;
		double windowEnd = Double.POSITIVE_INFINITY;

		@Override public BoardingGroup indivisibleBoardingGroup(PTPassengerAgent passenger, TransitVehicle vehicle,
				TransitLine line, TransitRoute route, TransitStopFacility stop) {
			return groups.get(passenger.getId());
		}

		@Override public boolean usesQueueArrivalTime() { return arrivalOrder; }

		@Override public int priority(PTPassengerAgent passenger, TransitVehicle vehicle,
				TransitLine line, TransitRoute route, TransitStopFacility stop) {
			return !arrivalOrder && !groups.containsKey(passenger.getId()) ? 100 : 0;
		}

		@Override public BoardingDecision boardingTimeDecision(PTPassengerAgent passenger, TransitVehicle vehicle,
				TransitLine line, TransitRoute route, TransitStopFacility stop, double nowSeconds) {
			return groups.containsKey(passenger.getId()) && nowSeconds >= windowEnd
					? BoardingDecision.deny(DenialReason.OUTSIDE_SHARED_WINDOW) : BoardingDecision.allow();
		}

		@Override public BoardingDecision boardingServiceEligibilityDecision(PTPassengerAgent passenger,
				TransitVehicle vehicle, TransitLine line, TransitRoute route, TransitStopFacility stop) {
			return freightServiceDenied && groups.containsKey(passenger.getId())
					? BoardingDecision.deny(DenialReason.ISOLATION_RULE) : BoardingDecision.allow();
		}

		@Override public boolean blocksFollowingCandidatesAfterGroupDenial(DenialReason reason) {
			return blockAfterCapacityDenial && (reason == DenialReason.NATIVE_CAPACITY
					|| reason == DenialReason.FREIGHT_SHARE_CAP);
		}

		@Override public BoardingDecision decideBoarding(PTPassengerAgent passenger, TransitVehicle vehicle,
				TransitLine line, TransitRoute route, TransitStopFacility stop,
				List<PTPassengerAgent> leaving, List<PTPassengerAgent> accepted) {
			if (!groups.containsKey(passenger.getId())) return BoardingDecision.allow();
			long occupiedFreight = vehicle.getPassengers().stream()
					.filter(agent -> groups.containsKey(agent.getId())).count()
					- leaving.stream().filter(agent -> groups.containsKey(agent.getId())).count();
			long acceptedFreight = accepted.stream().filter(agent -> groups.containsKey(agent.getId())).count();
			return occupiedFreight + acceptedFreight + 1 > freightUnitCap
					? BoardingDecision.deny(DenialReason.FREIGHT_SHARE_CAP) : BoardingDecision.allow();
		}
	}

	/** Captures selected entrants only; it does not pretend to execute a simulation. */
	private static final class RecordingStopHandler implements TransitStopHandler {
		List<PTPassengerAgent> selected = List.of();
		double stopTime;
		Runnable beforeReturn;

		@Override public double handleTransitStop(TransitStopFacility stop, double now,
				List<PTPassengerAgent> leaving, List<PTPassengerAgent> entering, List<PTPassengerAgent> relocating,
				PassengerAccessEgress access, TransitVehicle vehicle) {
			selected = List.copyOf(entering);
			if (beforeReturn != null) beforeReturn.run();
			return stopTime;
		}

		@Override public Message toMessage() {
			throw new UnsupportedOperationException("Direct adapter fixture only");
		}
	}

	private static final class RecordingPassenger extends FakePassengerAgent {
		final Id<Link> linkId;
		boolean routeEligible = true;
		Id<TransitStopFacility> exitStopId;
		Id<TransitStopFacility> destinationStopId;
		MobsimVehicle assignedVehicle;
		int vehicleAssignments;

		RecordingPassenger(String id, Id<Link> linkId) {
			super(Id.createPersonId(id), null);
			this.linkId = linkId;
		}

		@Override public boolean getEnterTransitRoute(TransitLine line, TransitRoute route,
				List<TransitRouteStop> stops, TransitVehicle vehicle) { return routeEligible; }
		@Override public boolean getExitAtStop(TransitStopFacility stop) {
			return exitStopId != null && exitStopId.equals(stop.getId());
		}
		@Override public Id<TransitStopFacility> getDesiredDestinationStopId() { return destinationStopId; }
		@Override public Id<Link> getCurrentLinkId() { return linkId; }
		@Override public MobsimVehicle getVehicle() { return assignedVehicle; }
		@Override public void setVehicle(MobsimVehicle vehicle) {
			assignedVehicle = vehicle;
			vehicleAssignments++;
		}
	}

	private static List<PTPassengerAgent> asAgents(List<RecordingPassenger> members) {
		return new ArrayList<>(members);
	}

	private static List<String> ids(List<? extends PTPassengerAgent> members) {
		return members.stream().map(member -> member.getId().toString()).toList();
	}
}
