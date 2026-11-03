package org.matsim.core.mobsim.qsim.pt;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.interfaces.MobsimVehicle;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.fakes.FakePassengerAgent;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Direct native entry transitions only: no QSim, Controller, Mobsim or control forecast. */
class PassengerAccessEgressQueueRemovalTest {

	@Test
	void successfulNativeEntryRemovesWaitingAgentAndArrivalAndEmitsOneEntryEvent() {
		for (String personId : List.of("passenger", "freight_cap_0")) {
			Fixture fixture = new Fixture();
			RecordingPassenger passenger = new RecordingPassenger(personId, fixture.linkId);
			fixture.tracker.addAgentToStop(5, passenger, fixture.stop.getId());
			assertEquals(5, fixture.tracker.getQueueArrival(passenger, fixture.stop.getId()).readyTimeSeconds());

			assertTrue(fixture.adapter.handlePassengerEntering(passenger, fixture.vehicle, fixture.stop.getId(), 20));

			assertEquals(List.of(passenger), List.copyOf(fixture.vehicle.getPassengers()));
			assertFalse(fixture.tracker.getAgentsAtFacility(fixture.stop.getId()).contains(passenger));
			assertThrows(IllegalStateException.class, () -> fixture.tracker.getQueueArrival(passenger, fixture.stop.getId()));
			assertSame(fixture.vehicle, passenger.getVehicle());
			assertEquals(1, passenger.vehicleAssignments);
			verify(fixture.internal).unregisterAdditionalAgentOnLink(passenger.getId(), fixture.linkId);
			verifyNoMoreInteractions(fixture.internal);
			fixture.assertSingleEntry(passenger, 20);
		}
	}

	@Test
	void fullNativeVehiclePreservesOriginalWaitingEpisodeUntilEntryEventuallySucceeds() {
		Fixture fixture = new Fixture();
		FakePassengerAgent occupant = new FakePassengerAgent(Id.createPersonId("already-onboard"), null);
		assertTrue(fixture.vehicle.addPassenger(occupant));
		RecordingPassenger waiting = new RecordingPassenger("waiting", fixture.linkId);
		fixture.tracker.addAgentToStop(5, waiting, fixture.stop.getId());
		var originalArrival = fixture.tracker.getQueueArrival(waiting, fixture.stop.getId());

		assertFalse(fixture.adapter.handlePassengerEntering(waiting, fixture.vehicle, fixture.stop.getId(), 20));

		assertEquals(List.of(occupant), List.copyOf(fixture.vehicle.getPassengers()));
		assertEquals(List.of(waiting), fixture.tracker.getAgentsAtFacility(fixture.stop.getId()));
		assertSame(originalArrival, fixture.tracker.getQueueArrival(waiting, fixture.stop.getId()));
		assertNull(waiting.getVehicle());
		assertEquals(0, waiting.vehicleAssignments);
		verifyNoInteractions(fixture.internal);
		assertTrue(fixture.entryEvents().isEmpty());

		assertTrue(fixture.vehicle.removePassenger(occupant));
		assertTrue(fixture.adapter.handlePassengerEntering(waiting, fixture.vehicle, fixture.stop.getId(), 30));
		assertTrue(fixture.tracker.getAgentsAtFacility(fixture.stop.getId()).isEmpty());
		assertThrows(IllegalStateException.class, () -> fixture.tracker.getQueueArrival(waiting, fixture.stop.getId()));
		assertEquals(List.of(waiting), List.copyOf(fixture.vehicle.getPassengers()));
		assertSame(fixture.vehicle, waiting.getVehicle());
		verify(fixture.internal).unregisterAdditionalAgentOnLink(waiting.getId(), fixture.linkId);
		verifyNoMoreInteractions(fixture.internal);
		fixture.assertSingleEntry(waiting, 30);
	}

	private static final class Fixture {
		final Id<Link> linkId = Id.createLinkId("boarding-link");
		final InternalInterface internal = mock(InternalInterface.class);
		final List<Event> events = new ArrayList<>();
		final TransitStopAgentTracker tracker;
		final TransitStopFacility stop;
		final TransitLine line;
		final TransitRoute route;
		final TransitQVehicle vehicle;
		final PassengerAccessEgressImpl adapter;

		Fixture() {
			var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
			var scheduleFactory = scenario.getTransitSchedule().getFactory();
			stop = scheduleFactory.createTransitStopFacility(Id.create("stop", TransitStopFacility.class), new Coord(0, 0), false);
			stop.setLinkId(linkId);
			scenario.getTransitSchedule().addStopFacility(stop);
			line = scheduleFactory.createTransitLine(Id.create("line", TransitLine.class));
			route = mock(TransitRoute.class);
			when(route.getId()).thenReturn(Id.create("route", TransitRoute.class));
			TransitDriverAgent driver = mock(TransitDriverAgent.class);
			when(driver.getTransitLine()).thenReturn(line);
			when(driver.getTransitRoute()).thenReturn(route);
			var type = VehicleUtils.createVehicleType(Id.create("one-place", org.matsim.vehicles.VehicleType.class));
			type.getCapacity().setSeats(1);
			type.getCapacity().setStandingRoom(0);
			vehicle = new TransitQVehicle(VehicleUtils.createVehicle(Id.createVehicleId("vehicle"), type));
			vehicle.setDriver(driver);
			EventsManager manager = EventsUtils.createEventsManager();
			manager.addHandler(new BasicEventHandler() {
				@Override public void handleEvent(Event event) { events.add(event); }
			});
			tracker = new TransitStopAgentTracker(manager);
			adapter = new PassengerAccessEgressImpl(internal, tracker, scenario, manager);
		}

		List<PersonEntersPtVehicleEvent> entryEvents() {
			return events.stream().filter(PersonEntersPtVehicleEvent.class::isInstance)
					.map(PersonEntersPtVehicleEvent.class::cast).toList();
		}

		void assertSingleEntry(RecordingPassenger passenger, double time) {
			var entering = entryEvents();
			assertEquals(1, entering.size());
			var event = entering.getFirst();
			assertEquals(time, event.getTime());
			assertEquals(passenger.getId(), event.getPersonId());
			assertEquals(vehicle.getId(), event.getVehicleId());
			assertEquals(line.getId(), event.getTransitLine());
			assertEquals(route.getId(), event.getTransitRoute());
		}
	}

	private static final class RecordingPassenger extends FakePassengerAgent {
		private final Id<Link> linkId;
		private MobsimVehicle assignedVehicle;
		private int vehicleAssignments;

		RecordingPassenger(String personId, Id<Link> linkId) {
			super(Id.createPersonId(personId), null);
			this.linkId = linkId;
		}

		@Override public Id<Link> getCurrentLinkId() { return linkId; }
		@Override public MobsimVehicle getVehicle() { return assignedVehicle; }
		@Override public void setVehicle(MobsimVehicle vehicle) {
			assignedVehicle = vehicle;
			vehicleAssignments++;
		}
	}
}
