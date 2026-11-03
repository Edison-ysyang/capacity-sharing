package org.matsim.contrib.cotransport;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.mobsim.framework.PassengerAgent;
import org.matsim.core.mobsim.qsim.pt.PTPassengerAgent;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy;
import org.matsim.core.mobsim.qsim.pt.TransitVehicle;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configured boarding order, mixed-train freight caps, and freight-handling dwell.
 *
 * <p>The standard MATSim physical capacity check remains authoritative. This policy adds
 * two stricter mixed-operation gates before a freight unit can take a remaining place:
 * passengers already on board plus accepted at the stop must not exceed the passenger
 * load gate, and the projected freight count must not exceed the train-level freight-share
 * cap. Dedicated services remain isolated by transit-line id.</p>
 */
@Singleton
public final class CoTransportBoardingPolicy implements TransitBoardingPolicy, IterationStartsListener {
	public static final String SHARED_BOARDING_WINDOW_ELEMENT = "coTransportSharedBoardingWindow";

	/** Explicit physical-boarding window only; no claim about on-board freight after its end. */
	public record SharedBoardingWindow(double startInclusiveSeconds, double endExclusiveSeconds) {
		public SharedBoardingWindow {
			if (!Double.isFinite(startInclusiveSeconds) || startInclusiveSeconds < 0
					|| !Double.isFinite(endExclusiveSeconds) || endExclusiveSeconds <= startInclusiveSeconds)
				throw new IllegalArgumentException("Shared boarding window requires finite 0 <= start < end");
		}
		public boolean contains(double timeSeconds) {
			if (!Double.isFinite(timeSeconds) || timeSeconds < 0)
				throw new IllegalArgumentException("Physical boarding clock must be finite and nonnegative");
			return timeSeconds >= startInclusiveSeconds && timeSeconds < endExclusiveSeconds;
		}
	}

	private final CoTransportSettings settings;
	private final FreightOrderRegistry registry;
	private final CoTransportPhaseController phaseController;
	private final Scenario scenario;
	private final BoardingAdmissionPolicy admissionPolicy;
	private final BoardingOrderPolicy boardingOrderPolicy;
	private final TransitAccessPolicy transitAccessPolicy;
	private final SharedBoardingWindow sharedBoardingWindow;
	private final Map<Id<Vehicle>, DwellState> dwellStates = new HashMap<>();
	private final Map<String, BoardingGroup> wholeOrderGroups = new HashMap<>();

	@Inject
	public CoTransportBoardingPolicy(CoTransportSettings settings, FreightOrderRegistry registry,
			CoTransportPhaseController phaseController, Scenario scenario) {
		this.settings = settings;
		this.registry = registry;
		this.phaseController = phaseController;
		this.scenario = scenario;
		this.boardingOrderPolicy = BoardingOrderPolicy.forMode(settings.boardingOrder());
		if (settings.freightBoardingMode() == CoTransportSettings.FreightBoardingMode.WHOLE_ORDER) {
			for (FreightOrder order : registry.orders())
				wholeOrderGroups.put(order.orderId(), new BoardingGroup(order.orderId(), registry.unitIdsForOrder(order.orderId())));
		}
		this.sharedBoardingWindow = physicalBoardingWindow(settings, scenario);
		this.transitAccessPolicy = hasDedicatedService() ? TransitAccessPolicy.dedicated() : TransitAccessPolicy.mixed();
		this.admissionPolicy = BoardingAdmissionPolicy.compose(transitAccessPolicy, !hasDedicatedService());
		Object existing = scenario.getScenarioElement(TransitBoardingPolicy.SCENARIO_ELEMENT);
		if (existing != null && existing != this) {
			throw new IllegalStateException("A TransitBoardingPolicy is already installed: "
					+ existing.getClass().getName());
		}
		if (existing == null) {
			scenario.addScenarioElement(TransitBoardingPolicy.SCENARIO_ELEMENT, this);
		}
	}

	@Override
	public BoardingDecision boardingTimeDecision(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop, double nowSeconds) {
		if (sharedBoardingWindow == null || !registry.isFreightPerson(passenger.getId()))
			return BoardingDecision.allow();
		return sharedBoardingWindow.contains(nowSeconds) ? BoardingDecision.allow()
				: BoardingDecision.deny(DenialReason.OUTSIDE_SHARED_WINDOW);
	}

	public SharedBoardingWindow sharedBoardingWindow() { return sharedBoardingWindow; }

	/**
	 * A serialized WHOLE_ORDER window is a physical boarding constraint for both mixed and
	 * dedicated trains. It must not depend on a runner remembering an extra Scenario element.
	 * Explicit legacy UNIT_SPLIT replay retains its prior element-only admission semantics.
	 */
	static SharedBoardingWindow physicalBoardingWindow(CoTransportSettings settings, Scenario scenario) {
		Object registered = scenario.getScenarioElement(SHARED_BOARDING_WINDOW_ELEMENT);
		if (registered != null && !(registered instanceof SharedBoardingWindow))
			throw new IllegalStateException("Shared boarding window scenario element has the wrong type");
		SharedBoardingWindow explicit = (SharedBoardingWindow) registered;
		if (explicit != null && (!settings.hasFreightServiceWindow()
				|| settings.freightServiceWindowStartTime() != explicit.startInclusiveSeconds()
				|| settings.freightServiceWindowEndTime() != explicit.endExclusiveSeconds()))
			throw new IllegalStateException("Physical boarding window differs from the explicit freight service window");
		if (explicit != null) return explicit;
		return settings.freightBoardingMode() == CoTransportSettings.FreightBoardingMode.WHOLE_ORDER
				&& settings.hasFreightServiceWindow()
				? new SharedBoardingWindow(settings.freightServiceWindowStartTime(), settings.freightServiceWindowEndTime())
				: null;
	}

	@Override
	public int priority(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop) {
		return boardingOrderPolicy.priority(classifyActor(passenger));
	}

	@Override
	public boolean usesQueueArrivalTime() {
		return boardingOrderPolicy.usesQueueArrivalTime();
	}

	@Override
	public BoardingGroup indivisibleBoardingGroup(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop) {
		return wholeOrderGroups.get(registry.normalizeOrderId(passenger.getId()));
	}

	@Override
	public boolean blocksFollowingCandidatesAfterGroupDenial(DenialReason reason) {
		return settings.freightBoardingMode() == CoTransportSettings.FreightBoardingMode.WHOLE_ORDER
				&& settings.wholeOrderQueuePolicy() == CoTransportSettings.WholeOrderQueuePolicy.STRICT_FIFO
				&& (reason == DenialReason.NATIVE_CAPACITY || reason == DenialReason.FREIGHT_SHARE_CAP);
	}

	@Override
	public BoardingDecision boardingServiceEligibilityDecision(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop) {
		return transitAccessPolicy.evaluate(new TransitAccessPolicy.Context(classifyActor(passenger),
				phaseController.isFreightActive(), line != null, isDedicatedLine(line)));
	}

	@Override
	public boolean allowsBoarding(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop,
			List<PTPassengerAgent> passengersLeaving,
			List<PTPassengerAgent> acceptedPassengers) {
		return decideBoarding(passenger, vehicle, line, route, stop, passengersLeaving, acceptedPassengers).allowed();
	}

	@Override
	public BoardingDecision decideBoarding(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop,
			List<PTPassengerAgent> passengersLeaving, List<PTPassengerAgent> acceptedPassengers) {
		if (vehicle.getPassengerCapacity() - vehicle.getPassengers().size()
				+ passengersLeaving.size() - acceptedPassengers.size() < 1) {
			return BoardingDecision.deny(DenialReason.NATIVE_CAPACITY);
		}
		ActorClass actorClass = classifyActor(passenger);
		if (actorClass != ActorClass.FREIGHT) {
			// Passenger rules do not depend on the freight count. Avoid scanning a train
			// for every ordinary boarding candidate in the full-scale passenger model.
			return transitAccessPolicy.evaluate(new TransitAccessPolicy.Context(actorClass,
					phaseController.isFreightActive(), line != null, isDedicatedLine(line)));
		}
		return admissionPolicy.evaluate(nativeContext(passenger, vehicle, line, passengersLeaving, acceptedPassengers));
	}

	@Override
	public DenialDetails denialDetails(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop,
			List<PTPassengerAgent> passengersLeaving, List<PTPassengerAgent> acceptedPassengers) {
		BoardingAdmissionPolicy.Context context = nativeContext(passenger, vehicle, line,
				passengersLeaving, acceptedPassengers);
		return new DenialDetails(context.actorClass(), Math.toIntExact(context.projectedPassengers()),
				Math.toIntExact(context.projectedFreightBeforeCandidate()), context.nativeCapacity(),
				context.freightShareCap(), context.passengerLoadGate());
	}

	/** Native QSim is the only source: the observational event mirror is deliberately absent. */
	private BoardingAdmissionPolicy.Context nativeContext(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, List<PTPassengerAgent> passengersLeaving, List<PTPassengerAgent> acceptedPassengers) {
		Set<Id<org.matsim.api.core.v01.population.Person>> leavingIds = new HashSet<>();
		for (PTPassengerAgent leaving : passengersLeaving) {
			leavingIds.add(leaving.getId());
		}
		int freightOnBoard = 0;
		int passengersOnBoard = 0;
		for (PassengerAgent onBoard : vehicle.getPassengers()) {
			if (leavingIds.contains(onBoard.getId())) {
				continue;
			}
			if (registry.isFreightPerson(onBoard.getId())) {
				freightOnBoard++;
			} else {
				passengersOnBoard++;
			}
		}
		int acceptedFreight = 0;
		int acceptedPassengerCount = 0;
		for (PTPassengerAgent accepted : acceptedPassengers) {
			if (registry.isFreightPerson(accepted.getId())) {
				acceptedFreight++;
			} else {
				acceptedPassengerCount++;
			}
		}
		return new BoardingAdmissionPolicy.Context(classifyActor(passenger), phaseController.isFreightActive(),
				line != null, isDedicatedLine(line), vehicle.getPassengerCapacity(), passengersOnBoard,
				freightOnBoard, acceptedPassengerCount, acceptedFreight, settings.freightShareCap(), settings.passengerLoadGate());
	}

	static boolean allowsMixedFreightBoarding(int capacity, int freightOnBoard,
			int passengersOnBoard, int acceptedFreight, int acceptedPassengers,
			double freightShareCap, double passengerLoadGate) {
		if (capacity <= 0) {
			return false;
		}
		return BoardingAdmissionPolicy.mixed().evaluate(new BoardingAdmissionPolicy.Context(
				ActorClass.FREIGHT, true, true, false, capacity, passengersOnBoard, freightOnBoard,
				acceptedPassengers, acceptedFreight, freightShareCap, passengerLoadGate)).allowed();
	}

	@Override
	public void observeStopInteraction(TransitVehicle vehicle, TransitLine line, TransitRoute route,
			TransitStopFacility stop, List<PTPassengerAgent> passengersLeaving,
			List<PTPassengerAgent> passengersEntering) {
		if (!phaseController.isFreightActive() || settings.freightHandlingDwellSeconds() <= 0.0) {
			return;
		}
		boolean handlesFreight = passengersLeaving.stream().anyMatch(agent -> registry.isFreightPerson(agent.getId()))
				|| passengersEntering.stream().anyMatch(agent -> registry.isFreightPerson(agent.getId()));
		if (!handlesFreight) {
			return;
		}
		DwellState state = dwellStates.computeIfAbsent(vehicle.getId(), ignored -> new DwellState(stop.getId()));
		if (!state.stopId.equals(stop.getId())) {
			state = new DwellState(stop.getId());
			dwellStates.put(vehicle.getId(), state);
		}
		state.freightHandled = true;
	}

	@Override
	public double adjustStopTime(TransitVehicle vehicle, TransitLine line, TransitRoute route,
			TransitStopFacility stop, List<PTPassengerAgent> passengersLeaving,
			List<PTPassengerAgent> passengersEntering, double baseStopTime) {
		if (baseStopTime > 0.0 || settings.freightHandlingDwellSeconds() <= 0.0) {
			return baseStopTime;
		}
		DwellState state = dwellStates.get(vehicle.getId());
		if (state == null || !state.stopId.equals(stop.getId()) || !state.freightHandled) {
			return baseStopTime;
		}
		if (!state.dwellApplied) {
			state.dwellApplied = true;
			return settings.freightHandlingDwellSeconds();
		}
		dwellStates.remove(vehicle.getId());
		return baseStopTime;
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		dwellStates.clear();
	}

	private boolean isDedicatedLine(TransitLine line) {
		return line != null && hasDedicatedService()
				&& settings.dedicatedFreightLineId().equals(line.getId().toString());
	}

	private boolean hasDedicatedService() {
		return settings.dedicatedFreightLineId() != null && !settings.dedicatedFreightLineId().isBlank();
	}

	private ActorClass classifyActor(PTPassengerAgent passenger) {
		return registry.isFreightPerson(passenger.getId()) ? ActorClass.FREIGHT
				: scenario.getPopulation().getPersons().containsKey(passenger.getId()) ? ActorClass.PASSENGER : ActorClass.INVALID;
	}

	private static final class DwellState {
		private final Id<TransitStopFacility> stopId;
		private boolean freightHandled;
		private boolean dwellApplied;

		private DwellState(Id<TransitStopFacility> stopId) {
			this.stopId = stopId;
		}
	}
}
