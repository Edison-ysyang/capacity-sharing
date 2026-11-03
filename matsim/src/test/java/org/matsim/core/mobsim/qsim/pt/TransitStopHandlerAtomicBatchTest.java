package org.matsim.core.mobsim.qsim.pt;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.pt.transitSchedule.api.ChainedDeparture;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.VehicleType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Direct handler calls with fake adapters only: no QSim, Controller or predictor. */
class TransitStopHandlerAtomicBatchTest {

	@Test
	void defaultBatchEntryPreservesSingleAgentTransitionAndDoesNotMutateCandidates() {
		Fixture fixture = new Fixture();
		LegacyAccess access = new LegacyAccess();
		List<PTPassengerAgent> entering = candidates(2);
		assertEquals(1, access.handlePassengersEntering(entering, fixture.vehicle, fixture.stop.getId(), 7));
		assertEquals(2, entering.size());
		assertEquals(List.of(entering.getFirst()), access.individualEntries);
		access.allow = false;
		assertEquals(0, access.handlePassengersEntering(entering, fixture.vehicle, fixture.stop.getId(), 8));
		assertEquals(2, entering.size());
		assertEquals(0, access.handlePassengersEntering(List.of(), fixture.vehicle, fixture.stop.getId(), 9));
		assertEquals(2, access.individualEntries.size());
	}

	@Test
	void bothComplexDoorModesCommitFiveTogetherAndChargeAllFiveBeforeNextCandidate() {
		for (VehicleType.DoorOperationMode mode : doorModes()) {
			Fixture fixture = new Fixture();
			ComplexTransitStopHandler stopHandler = complex(mode);
			BatchAccess access = new BatchAccess(5);
			List<PTPassengerAgent> entering = candidates(6);
			List<PTPassengerAgent> five = List.copyOf(entering.subList(0, 5));
			PTPassengerAgent passenger = entering.getLast();

			assertEquals(1, fixture.call(stopHandler, access, entering, 0)); // open doors
			assertTrue(access.prefixes.isEmpty());
			assertEquals(1, fixture.call(stopHandler, access, entering, 1));
			assertEquals(List.of(five), access.prefixes);
			assertEquals(List.of(1.0), access.entryTimes);
			assertEquals(List.of(passenger), entering);
			assertEquals(1, fixture.call(stopHandler, access, entering, 2));
			assertEquals(List.of(five, List.of(passenger)), access.prefixes);
			assertEquals(List.of(1.0, 2.0), access.entryTimes);
			assertTrue(entering.isEmpty());
			assertTrue(access.individualEntries.isEmpty(), "The handler must use the batch entry point");
			assertEquals(1, fixture.call(stopHandler, access, entering, 3)); // close doors
			assertEquals(0, fixture.call(stopHandler, access, entering, 4));
		}
	}

	@Test
	void largeBatchKeepsResidualProcessingBudgetAfterCandidatesHaveBeenRemoved() {
		for (VehicleType.DoorOperationMode mode : doorModes()) {
			Fixture fixture = new Fixture();
			ComplexTransitStopHandler stopHandler = complex(mode);
			BatchAccess access = new BatchAccess(13);
			List<PTPassengerAgent> entering = candidates(13);
			assertEquals(1, fixture.call(stopHandler, access, entering, 0));
			assertEquals(1, fixture.call(stopHandler, access, entering, 1));
			assertTrue(entering.isEmpty());
			assertEquals(1, fixture.call(stopHandler, access, entering, 2));
			assertEquals(1, fixture.call(stopHandler, access, entering, 3));
			assertEquals(1, fixture.call(stopHandler, access, entering, 4)); // close doors
			assertEquals(0, fixture.call(stopHandler, access, entering, 5));
			assertEquals(1, access.prefixes.size());
			assertEquals(13, access.prefixes.getFirst().size());
		}
	}

	@Test
	void complexRejectionLeavesAllFiveSelectedAndCanRetryWithoutIndividualEntry() {
		for (VehicleType.DoorOperationMode mode : doorModes()) {
			Fixture fixture = new Fixture();
			ComplexTransitStopHandler stopHandler = complex(mode);
			BatchAccess access = new BatchAccess(5);
			access.allow = false;
			List<PTPassengerAgent> entering = candidates(5);
			List<PTPassengerAgent> original = List.copyOf(entering);
			fixture.call(stopHandler, access, entering, 0);
			assertEquals(1, fixture.call(stopHandler, access, entering, 1));
			assertEquals(original, entering);
			assertTrue(access.prefixes.isEmpty());
			assertTrue(access.individualEntries.isEmpty());
			access.allow = true;
			assertEquals(1, fixture.call(stopHandler, access, entering, 2));
			assertEquals(List.of(original), access.prefixes);
			assertEquals(List.of(2.0), access.entryTimes);
			assertTrue(entering.isEmpty());
		}
	}

	@Test
	void simpleHandlerUsesOneAtomicPrefixAndPreservesNativePerCandidateStopTime() {
		Fixture fixture = new Fixture();
		SimpleTransitStopHandler stopHandler = new SimpleTransitStopHandler();
		BatchAccess access = new BatchAccess(5);
		List<PTPassengerAgent> entering = candidates(6);
		List<PTPassengerAgent> five = List.copyOf(entering.subList(0, 5));
		PTPassengerAgent last = entering.getLast();
		assertEquals(39, fixture.call(stopHandler, access, entering, 10)); // 6 * 4 + 15
		assertEquals(List.of(five, List.of(last)), access.prefixes);
		assertEquals(List.of(10.0, 10.0), access.entryTimes);
		assertTrue(access.individualEntries.isEmpty());
		assertTrue(entering.isEmpty());
	}

	@Test
	void simpleRejectionDoesNotConsumeAnyGroupMember() {
		Fixture fixture = new Fixture();
		BatchAccess access = new BatchAccess(5);
		access.allow = false;
		List<PTPassengerAgent> entering = candidates(5);
		List<PTPassengerAgent> original = List.copyOf(entering);
		assertEquals(35, fixture.call(new SimpleTransitStopHandler(), access, entering, 10));
		assertEquals(original, entering);
		assertTrue(access.prefixes.isEmpty());
		assertTrue(access.individualEntries.isEmpty());
	}

	@Test
	void allHandlersFailClosedForInvalidCommittedPrefixSizes() {
		for (int result : List.of(-1, 6)) {
			for (TransitStopHandler stopHandler : allHandlers()) {
				Fixture fixture = new Fixture();
				PassengerAccessEgress access = new LegacyAccess() {
					@Override public int handlePassengersEntering(List<PTPassengerAgent> entering,
							TransitVehicle vehicle, Id<TransitStopFacility> stop, double time) { return result; }
				};
				List<PTPassengerAgent> entering = candidates(5);
				List<PTPassengerAgent> original = List.copyOf(entering);
				if (stopHandler instanceof ComplexTransitStopHandler) fixture.call(stopHandler, access, entering, 0);
				assertThrows(IllegalStateException.class, () -> fixture.call(stopHandler, access, entering, 1));
				assertEquals(original, entering);
			}
		}
	}

	@Test
	void allHandlersRejectAdaptersThatMutateTheSuppliedList() {
		for (TransitStopHandler stopHandler : allHandlers()) {
			Fixture fixture = new Fixture();
			PassengerAccessEgress access = new LegacyAccess() {
				@Override public int handlePassengersEntering(List<PTPassengerAgent> entering,
						TransitVehicle vehicle, Id<TransitStopFacility> stop, double time) {
					entering.removeFirst();
					return 1;
				}
			};
			List<PTPassengerAgent> entering = candidates(5);
			if (stopHandler instanceof ComplexTransitStopHandler) fixture.call(stopHandler, access, entering, 0);
			assertThrows(IllegalStateException.class, () -> fixture.call(stopHandler, access, entering, 1));
		}
	}

	private static List<VehicleType.DoorOperationMode> doorModes() {
		return List.of(VehicleType.DoorOperationMode.parallel, VehicleType.DoorOperationMode.serial);
	}

	private static ComplexTransitStopHandler complex(VehicleType.DoorOperationMode mode) {
		return new ComplexTransitStopHandler(new ComplexTransitStopHandler.Msg(mode, 0.25, 0.25));
	}

	private static List<TransitStopHandler> allHandlers() {
		return List.of(complex(VehicleType.DoorOperationMode.parallel),
				complex(VehicleType.DoorOperationMode.serial), new SimpleTransitStopHandler());
	}

	private static List<PTPassengerAgent> candidates(int count) {
		List<PTPassengerAgent> result = new ArrayList<>();
		for (int i = 0; i < count; i++) result.add(mock(PTPassengerAgent.class));
		return result;
	}

	private static class LegacyAccess implements PassengerAccessEgress {
		boolean allow = true;
		final List<PTPassengerAgent> individualEntries = new ArrayList<>();
		@Override public boolean handlePassengerEntering(PTPassengerAgent agent, TransitVehicle vehicle,
				Id<TransitStopFacility> stop, double time) {
			individualEntries.add(agent);
			return allow;
		}
		@Override public boolean handlePassengerLeaving(PTPassengerAgent agent, TransitVehicle vehicle,
				Id<Link> link, double time) { return true; }
		@Override public void handlePassengerRelocating(PTPassengerAgent agent, TransitVehicle vehicle,
				Id<TransitStopFacility> stop, double time) { }
		@Override public void relocatePassengers(TransitDriverAgentImpl driver,
				List<ChainedDeparture> departures, double time) { }
	}

	private static final class BatchAccess extends LegacyAccess {
		final int firstBatchSize;
		final List<List<PTPassengerAgent>> prefixes = new ArrayList<>();
		final List<Double> entryTimes = new ArrayList<>();
		BatchAccess(int firstBatchSize) { this.firstBatchSize = firstBatchSize; }
		@Override public int handlePassengersEntering(List<PTPassengerAgent> entering, TransitVehicle vehicle,
				Id<TransitStopFacility> stop, double time) {
			if (!allow) return 0;
			int size = prefixes.isEmpty() ? firstBatchSize : 1;
			prefixes.add(List.copyOf(entering.subList(0, size)));
			entryTimes.add(time);
			return size;
		}
	}

	private static final class Fixture {
		final TransitVehicle vehicle = mock(TransitVehicle.class);
		final TransitStopFacility stop = mock(TransitStopFacility.class);
		Fixture() {
			when(stop.getId()).thenReturn(Id.create("stop", TransitStopFacility.class));
			when(stop.getLinkId()).thenReturn(Id.createLinkId("link"));
		}
		double call(TransitStopHandler handler, PassengerAccessEgress access,
				List<PTPassengerAgent> entering, double time) {
			return handler.handleTransitStop(stop, time, new ArrayList<>(), entering,
					List.of(), access, vehicle);
		}
	}
}
