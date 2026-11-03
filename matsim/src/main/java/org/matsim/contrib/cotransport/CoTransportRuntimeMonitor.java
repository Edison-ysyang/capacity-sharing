package org.matsim.contrib.cotransport;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.BoardingDeniedEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleCapacity;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class CoTransportRuntimeMonitor implements PersonEntersVehicleEventHandler, PersonLeavesVehicleEventHandler,
		TransitDriverStartsEventHandler, VehicleArrivesAtFacilityEventHandler, VehicleDepartsAtFacilityEventHandler,
		BoardingDeniedEventHandler, ActivityStartEventHandler, PersonStuckEventHandler, IterationEndsListener {

	/**
	 * Weights of the aggregate freight service score reported in
	 * {@code coTransport_summary.csv}: {@code freightScore = ON_TIME_WEIGHT * onTimeRate
	 * + COMPLETION_WEIGHT * completionRate - delay - denied - incomplete penalties}.
	 * These weights only affect the reported indicator, not agent scoring.
	 */
	static final double FREIGHT_SCORE_ON_TIME_WEIGHT = 100.0;
	static final double FREIGHT_SCORE_COMPLETION_WEIGHT = 20.0;

	private final CoTransportSettings settings;
	private final CoTransportPhaseController phaseController;
	private final FreightOrderRegistry registry;
	private final Scenario scenario;
	private final EventsManager events;
	private final ExposureIntegrator exposureIntegrator;
	private final ExposureValuation exposureValuation;
	private final CoTransportBoardingPolicy.SharedBoardingWindow sharedBoardingWindow;
	private final WholeOrderBoardingAudit wholeOrderBoardingAudit;
	private final Map<Id<Vehicle>, VehicleCapacityState> vehicleStates = new HashMap<>();
	private final Map<Id<Vehicle>, Id<TransitStopFacility>> vehicleStops = new HashMap<>();
	private final Map<Id<Vehicle>, String> vehicleLines = new HashMap<>();
	private final Map<Id<Vehicle>, String> vehicleRoutes = new HashMap<>();
	private final Map<Id<Person>, PassengerRideState> passengerRides = new HashMap<>();
	private final Map<Id<Vehicle>, Set<Id<Person>>> passengerRidersByVehicle = new HashMap<>();
	private final Map<Id<Vehicle>, Double> lastExposureUpdateTime = new HashMap<>();
	private final Map<Id<Vehicle>, Double> lastObservedVehicleEventTime = new HashMap<>();
	private final Map<Id<Person>, Id<Vehicle>> observedOccupantVehicles = new HashMap<>();
	private final Map<Id<Person>, PassengerExposureAccount> passengerExposureAccounts = new HashMap<>();
	private final List<String> exposureInvalidEventReasons = new ArrayList<>();
	private int exposureIteration = -1;
	private boolean detailedVehicleOutput = true;
	private boolean outputConfigurationLocked;
	private boolean outputConfigurationSet;
	private final Set<Id<Vehicle>> dedicatedFreightVehicles = new HashSet<>();
	private final Map<Id<Person>, DeniedBoardingResponseState> deniedResponses = new LinkedHashMap<>();
	private final Map<String, FreightOrderRuntimeStats> freightStats = new LinkedHashMap<>();
	private final List<LoadObservation> loadObservations = new ArrayList<>();
	private final Set<Id<Person>> transitDrivers = new HashSet<>();
	private int passengerBoardings;
	private int freightBoardings;
	private int freightDeniedBoardings;
	private int passengerDeniedBoardings;
	private int freightEvents;
	private int passengerStuckEvents;
	private int freightStuckEvents;
	private double passengerPenalty;
	private double passengerPhysicalCrowdingPenalty;
	private double passengerFreightCoPresencePenalty;
	private double freightDeniedPenalty;
	private double passengerDeniedPenalty;
	private double maxLoadRatio;
	private double maxFreightShare;
	private final Set<Id<Person>> passengerDeniedPersons = new HashSet<>();
	private final Set<Id<Person>> passengerStuckPersons = new HashSet<>();
	/*
	 * Aggregate stuck counts are sufficient for a production dashboard but not
	 * for a fail-closed experiment audit.  Preserve the exact event payload so a
	 * failed technical run can identify whether the cause was a passenger,
	 * freight order, mode, link, or QSim end-time boundary.
	 */
	private final List<StuckOccurrence> stuckOccurrences = new ArrayList<>();

	@Inject
	public CoTransportRuntimeMonitor(CoTransportSettings settings, CoTransportPhaseController phaseController,
			FreightOrderRegistry registry, Scenario scenario, EventsManager events) {
		this.settings = settings;
		this.phaseController = phaseController;
		this.registry = registry;
		this.scenario = scenario;
		sharedBoardingWindow = CoTransportBoardingPolicy.physicalBoardingWindow(settings, scenario);
		wholeOrderBoardingAudit = settings.freightBoardingMode() == CoTransportSettings.FreightBoardingMode.WHOLE_ORDER
				? new WholeOrderBoardingAudit(registry) : null;
		this.events = events;
		this.exposureIntegrator = ExposureIntegrator.capacityNormalized();
		this.exposureValuation = new ExposureValuation(settings.passengerPhysicalCrowdingPenaltyPerMinute(),
				settings.passengerFreightCoPresencePenaltyPerMinute());
		// Validate the scenario threshold even when no passenger ever boards.
		new ExposureIntegrator.Interval(0.0, 0.0, 0.0, settings.comfortThreshold());
		for (FreightOrder order : registry.orders()) {
			freightStats.put(order.orderId(), new FreightOrderRuntimeStats(order));
		}
	}

	@Override
	public void handleEvent(TransitDriverStartsEvent event) {
		outputConfigurationLocked = true;
		VehicleCapacityState preceding = vehicleStates.get(event.getVehicleId());
		if (preceding != null && preceding.load() != 0) {
			throw invalidExposureEvent("Vehicle " + event.getVehicleId()
					+ " starts another transit driver while occupants remain on board");
		}
		recordVehicleEventTime(event.getVehicleId(), event.getTime());
		transitDrivers.add(event.getDriverId());
		vehicleStates.put(event.getVehicleId(), createVehicleState(event.getVehicleId()));
		vehicleLines.put(event.getVehicleId(), event.getTransitLineId().toString());
		vehicleRoutes.put(event.getVehicleId(), event.getTransitRouteId().toString());
		passengerRidersByVehicle.put(event.getVehicleId(), new HashSet<>());
		lastExposureUpdateTime.put(event.getVehicleId(), event.getTime());
		if (settings.dedicatedFreightLineId() != null
				&& settings.dedicatedFreightLineId().equals(event.getTransitLineId().toString())) {
			dedicatedFreightVehicles.add(event.getVehicleId());
		} else {
			// Service identity belongs to the current trip, not permanently to a physical
			// vehicle which may later be reassigned to an ordinary passenger service.
			dedicatedFreightVehicles.remove(event.getVehicleId());
		}
	}

	@Override
	public void handleEvent(VehicleArrivesAtFacilityEvent event) {
		if (vehicleStates.containsKey(event.getVehicleId())) {
			recordVehicleEventTime(event.getVehicleId(), event.getTime());
		}
		vehicleStops.put(event.getVehicleId(), event.getFacilityId());
	}

	@Override
	public void handleEvent(VehicleDepartsAtFacilityEvent event) {
		if (wholeOrderBoardingAudit != null) wholeOrderBoardingAudit.assertVehicleBatchComplete(event.getVehicleId());
		VehicleCapacityState state = vehicleStates.get(event.getVehicleId());
		if (state != null) {
			recordVehicleEventTime(event.getVehicleId(), event.getTime());
			assertOccupancyWithinCapacity(state);
			maxLoadRatio = Math.max(maxLoadRatio, state.loadRatio());
			maxFreightShare = Math.max(maxFreightShare, state.freightShareOfCapacity());
			loadObservations.add(new LoadObservation(
					event.getTime(), event.getVehicleId(),
					vehicleLines.getOrDefault(event.getVehicleId(), ""),
					vehicleRoutes.getOrDefault(event.getVehicleId(), ""),
					event.getFacilityId(), state.load(), state.load() - state.freightUnits(),
					state.freightUnits(), state.capacity(), state.loadRatio(), state.freightShareOfCapacity()));
		}
	}

	@Override
	public void handleEvent(PersonEntersVehicleEvent event) {
		if (transitDrivers.contains(event.getPersonId())) {
			return;
		}
		VehicleCapacityState state = vehicleStates.get(event.getVehicleId());
		if (state == null) {
			return;
		}
		if (observedOccupantVehicles.containsKey(event.getPersonId())) {
			throw invalidExposureEvent("Duplicate entering event for " + event.getPersonId()
					+ "; already observed in " + observedOccupantVehicles.get(event.getPersonId()));
		}
		try {
			assertBoardingCapacityAvailable(state, event.getPersonId());
		} catch (IllegalStateException error) {
			throw invalidExposureEvent(error.getMessage());
		}
		boolean freight = registry.isFreightPerson(event.getPersonId());
		ensureFreightIsArmed(event.getPersonId(), freight);
		if (freight && sharedBoardingWindow != null && !sharedBoardingWindow.contains(event.getTime()))
			throw invalidExposureEvent("Observed actual freight boarding outside shared window: "
					+ event.getPersonId() + " at " + event.getTime());
		if (!freight && dedicatedFreightVehicles.contains(event.getVehicleId())) {
			throw invalidExposureEvent("Passenger " + event.getPersonId()
					+ " attempted to board freight-only train " + event.getVehicleId());
		}
		if (freight && settings.dedicatedFreightLineId() != null
				&& !settings.dedicatedFreightLineId().isBlank()
				&& !dedicatedFreightVehicles.contains(event.getVehicleId())) {
			throw invalidExposureEvent("Freight agent " + event.getPersonId()
					+ " attempted to board passenger-service train " + event.getVehicleId());
		}
		if (freight && wholeOrderBoardingAudit != null)
			wholeOrderBoardingAudit.entered(event.getPersonId(), event.getVehicleId(), event.getTime());
		recordVehicleEventTime(event.getVehicleId(), event.getTime());
		updatePassengerExposure(event.getVehicleId(), event.getTime());
		state.enter(event.getPersonId(), freight);
		observedOccupantVehicles.put(event.getPersonId(), event.getVehicleId());
		assertOccupancyWithinCapacity(state);
		maxLoadRatio = Math.max(maxLoadRatio, state.loadRatio());
		maxFreightShare = Math.max(maxFreightShare, state.freightShareOfCapacity());
		if (freight) {
			freightEvents++;
			freightBoardings++;
			FreightOrder order = registry.orderForPerson(event.getPersonId());
			freightStats.get(order.orderId()).boarded(event.getPersonId(), event.getTime());
		} else {
			passengerBoardings++;
			passengerRides.put(event.getPersonId(), new PassengerRideState(event.getVehicleId(), event.getTime()));
			passengerExposureAccounts.computeIfAbsent(event.getPersonId(), ignored -> new PassengerExposureAccount());
			passengerRidersByVehicle.get(event.getVehicleId()).add(event.getPersonId());
		}
		DeniedBoardingResponseState response = deniedResponses.get(event.getPersonId());
		if (response != null) {
			response.boarded(event.getTime(), event.getVehicleId());
		}
	}

	@Override
	public void handleEvent(PersonLeavesVehicleEvent event) {
		if (transitDrivers.contains(event.getPersonId())) {
			return;
		}
		if (wholeOrderBoardingAudit != null) wholeOrderBoardingAudit.assertVehicleBatchComplete(event.getVehicleId());
		VehicleCapacityState state = vehicleStates.get(event.getVehicleId());
		if (state == null) {
			return;
		}
		if (!state.contains(event.getPersonId())
				|| !event.getVehicleId().equals(observedOccupantVehicles.get(event.getPersonId()))) {
			throw invalidExposureEvent("Unmatched leaving event for " + event.getPersonId()
					+ " in " + event.getVehicleId());
		}
		recordVehicleEventTime(event.getVehicleId(), event.getTime());
		updatePassengerExposure(event.getVehicleId(), event.getTime());
		boolean freight = registry.isFreightPerson(event.getPersonId());
		ensureFreightIsArmed(event.getPersonId(), freight);
		if (freight) {
			freightEvents++;
		}
		state.leave(event.getPersonId());
		observedOccupantVehicles.remove(event.getPersonId());
		assertOccupancyWithinCapacity(state);
		PassengerRideState ride = passengerRides.remove(event.getPersonId());
		if (ride != null) {
			passengerRidersByVehicle.get(event.getVehicleId()).remove(event.getPersonId());
			passengerExposureAccounts.get(event.getPersonId()).addAppliedPenalties(
					ride.physicalCrowdingPenalty(), ride.freightCoPresencePenalty());
			if (ride.physicalCrowdingPenalty() > 0.0) {
				passengerPhysicalCrowdingPenalty += ride.physicalCrowdingPenalty();
				events.processEvent(new PersonScoreEvent(event.getTime(), event.getPersonId(),
						-ride.physicalCrowdingPenalty(), "coTransportPassengerPhysicalCrowding"));
			}
			if (ride.freightCoPresencePenalty() > 0.0) {
				passengerFreightCoPresencePenalty += ride.freightCoPresencePenalty();
				events.processEvent(new PersonScoreEvent(event.getTime(), event.getPersonId(),
						-ride.freightCoPresencePenalty(), "coTransportPassengerFreightCoPresence"));
			}
			passengerPenalty += ride.accumulatedPenalty();
		}
	}

	@Override
	public void handleEvent(PersonStuckEvent event) {
		if (transitDrivers.contains(event.getPersonId())) {
			return;
		}
		boolean freight = registry.isFreightPerson(event.getPersonId());
		FreightOrder order = freight ? registry.orderForPerson(event.getPersonId()) : null;
		stuckOccurrences.add(new StuckOccurrence(
				event.getPersonId().toString(),
				freight ? "freight" : "passenger",
				order == null ? "" : order.orderId(),
				event.getTime(),
				event.getLinkId() == null ? "" : event.getLinkId().toString(),
				event.getLegMode() == null ? "" : event.getLegMode(),
				event.getReason() == null ? "" : event.getReason()));
		if (freight) {
			ensureFreightIsArmed(event.getPersonId(), true);
			freightStuckEvents++;
			freightEvents++;
		} else {
			passengerStuckEvents++;
			passengerStuckPersons.add(event.getPersonId());
		}
	}

	@Override
	public void handleEvent(BoardingDeniedEvent event) {
		boolean freight = registry.isFreightPerson(event.getPersonId());
		ensureFreightIsArmed(event.getPersonId(), freight);
		FreightOrder order = freight ? registry.orderForPerson(event.getPersonId()) : null;
		deniedResponses.computeIfAbsent(event.getPersonId(), personId -> new DeniedBoardingResponseState(
				personId, freight, order == null ? "" : order.orderId()))
				.denied(event.getTime(), event.getVehicleId(), vehicleStops.get(event.getVehicleId()));
		if (!freight) {
			passengerDeniedBoardings++;
			passengerDeniedPersons.add(event.getPersonId());
			passengerDeniedPenalty += settings.passengerDeniedBoardingPenalty();
			events.processEvent(new PersonScoreEvent(event.getTime(), event.getPersonId(),
					-settings.passengerDeniedBoardingPenalty(), "coTransportPassengerDeniedBoarding"));
			return;
		}
		freightDeniedBoardings++;
		freightEvents++;
		freightStats.get(order.orderId()).denied();
		freightDeniedPenalty += settings.deniedBoardingPenalty();
		events.processEvent(new PersonScoreEvent(event.getTime(), event.getPersonId(),
				-settings.deniedBoardingPenalty(), "coTransportFreightDeniedBoarding"));
	}

	@Override
	public void handleEvent(ActivityStartEvent event) {
		boolean freight = registry.isFreightPerson(event.getPersonId());
		ensureFreightIsArmed(event.getPersonId(), freight);
		if (!freight || !event.getActType().startsWith(settings.freightActivityPrefix())) {
			return;
		}
		freightEvents++;
		FreightOrder order = registry.orderForPerson(event.getPersonId());
		FreightOrderRuntimeStats stats = freightStats.get(order.orderId());
		stats.delivered(event.getPersonId(), event.getTime(), settings.freightServiceWindowEndTime());
		DeniedBoardingResponseState response = deniedResponses.get(event.getPersonId());
		if (response != null) {
			response.delivered(event.getTime());
		}
		double lateMinutes = Math.max(0.0, event.getTime() - order.deadlineTime()) / 60.0;
		if (lateMinutes > 0.0) {
			double penalty = lateMinutes * settings.freightLatePenaltyPerMinute() * order.priority();
			stats.addDelayPenalty(penalty);
			events.processEvent(new PersonScoreEvent(event.getTime(), event.getPersonId(), -penalty, "coTransportFreightLateWindow"));
		}
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		if (wholeOrderBoardingAudit != null) wholeOrderBoardingAudit.assertAllBatchesComplete();
		/*
		 * Write the individual evidence before the aggregate summary.  The Plan-A
		 * executor observes the summary as its finalized integrity trigger, so
		 * this ordering guarantees that a positive summary never races ahead of
		 * its person-level diagnostic receipt.
		 */
		writeStuckEventReport(event);
		writeOrderReport(event);
		writeWholeOrderBoardingReport(event);
		writeSummaryReport(event);
		writeDeniedResponseReport(event);
		writeDeniedBoardingEventReport(event);
		writeLoadObservationReport(event);
		writeActivationAudit(event);
	}

	@Override
	public void reset(int iteration) {
		outputConfigurationLocked = true;
		exposureIteration = iteration;
		vehicleStates.clear();
		vehicleStops.clear();
		vehicleLines.clear();
		vehicleRoutes.clear();
		passengerRides.clear();
		passengerRidersByVehicle.clear();
		lastExposureUpdateTime.clear();
		lastObservedVehicleEventTime.clear();
		observedOccupantVehicles.clear();
		passengerExposureAccounts.clear();
		exposureInvalidEventReasons.clear();
		dedicatedFreightVehicles.clear();
		transitDrivers.clear();
		passengerBoardings = 0;
		freightBoardings = 0;
		freightDeniedBoardings = 0;
		passengerDeniedBoardings = 0;
		freightEvents = 0;
		passengerStuckEvents = 0;
		freightStuckEvents = 0;
		passengerPenalty = 0.0;
		passengerPhysicalCrowdingPenalty = 0.0;
		passengerFreightCoPresencePenalty = 0.0;
		freightDeniedPenalty = 0.0;
		passengerDeniedPenalty = 0.0;
		maxLoadRatio = 0.0;
		maxFreightShare = 0.0;
		passengerDeniedPersons.clear();
		passengerStuckPersons.clear();
		stuckOccurrences.clear();
		deniedResponses.clear();
		loadObservations.clear();
		if (wholeOrderBoardingAudit != null) wholeOrderBoardingAudit.reset();
		for (FreightOrder order : registry.orders()) {
			freightStats.put(order.orderId(), new FreightOrderRuntimeStats(order));
		}
	}

	private void writeWholeOrderBoardingReport(IterationEndsEvent event) {
		if (wholeOrderBoardingAudit == null) return;
		Path path = reportPath(event.getServices().getControllerIO().getIterationFilename(
				event.getIteration(), "coTransport_whole_order_boardings.csv"));
		try {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = reportWriter(path)) {
				writer.write("iteration,order_id,time_seconds,vehicle_id,capacity_units,unit_ids\n");
				for (WholeOrderBoardingAudit.Receipt receipt : wholeOrderBoardingAudit.receipts()) {
					writer.write(String.join(",", Integer.toString(event.getIteration()), csv(receipt.orderId()),
							Double.toString(receipt.time()), csv(receipt.vehicle().toString()),
							Integer.toString(receipt.units().size()),
							csv(receipt.units().stream().map(Id::toString).collect(java.util.stream.Collectors.joining(";")))));
					writer.write("\n");
				}
			}
		} catch (IOException error) {
			throw new IllegalStateException("Could not write whole-order boarding receipts: " + path, error);
		}
	}

	private void writeStuckEventReport(IterationEndsEvent event) {
		Path path = reportPath(event.getServices().getControllerIO().getIterationFilename(
				event.getIteration(), "coTransport_stuck_events.csv"));
		try {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = reportWriter(path)) {
				writer.write("iteration,person_id,agent_type,order_id,time_seconds,time,link_id,leg_mode,reason\n");
				for (StuckOccurrence occurrence : stuckOccurrences) {
					writer.write(String.join(",",
							Integer.toString(event.getIteration()),
							csv(occurrence.personId()),
							occurrence.agentType(),
							csv(occurrence.orderId()),
							String.format(java.util.Locale.ROOT, "%.3f", occurrence.time()),
							format(occurrence.time()),
							csv(occurrence.linkId()),
							csv(occurrence.legMode()),
							csv(occurrence.reason())));
					writer.write("\n");
				}
			}
		} catch (IOException error) {
			throw new IllegalStateException("Could not write co-transport stuck-event detail: " + path, error);
		}
	}

	private VehicleCapacityState createVehicleState(Id<Vehicle> vehicleId) {
		Vehicle vehicle = scenario.getTransitVehicles().getVehicles().get(vehicleId);
		if (vehicle == null) {
			throw new IllegalStateException("Transit vehicle is missing from transitVehicles: " + vehicleId);
		}
		VehicleCapacity vehicleCapacity = vehicle.getType().getCapacity();
		int capacity = vehicleCapacity.getSeats()
				+ (vehicleCapacity.getStandingRoom() == null ? 0 : vehicleCapacity.getStandingRoom());
		if (capacity <= 0) {
			throw new IllegalStateException("Transit vehicle has no usable capacity: " + vehicleId);
		}
		return new VehicleCapacityState(vehicleId, capacity);
	}

	static void assertBoardingCapacityAvailable(VehicleCapacityState state, Id<Person> personId) {
		if (state.load() >= state.capacity()) {
			throw new IllegalStateException("Vehicle " + state.vehicleId() + " is already at capacity "
					+ state.capacity() + " when person " + personId + " produced a boarding event");
		}
	}

	static void assertOccupancyWithinCapacity(VehicleCapacityState state) {
		if (state.load() < 0 || state.load() > state.capacity()) {
			throw new IllegalStateException("Vehicle " + state.vehicleId() + " occupancy " + state.load()
					+ " is outside [0," + state.capacity() + "]");
		}
		if (state.freightUnits() < 0 || state.freightUnits() > state.load()) {
			throw new IllegalStateException("Vehicle " + state.vehicleId() + " freight occupancy "
					+ state.freightUnits() + " is outside [0," + state.load() + "]");
		}
	}

	private void updatePassengerExposure(Id<Vehicle> vehicleId, double time) {
		VehicleCapacityState state = vehicleStates.get(vehicleId);
		Double previousTime = lastExposureUpdateTime.get(vehicleId);
		if (!Double.isFinite(time) || time < 0.0 || (previousTime != null && time < previousTime)) {
			throw invalidExposureEvent("Invalid exposure interval for " + vehicleId + ": " + previousTime + " -> " + time);
		}
		lastExposureUpdateTime.put(vehicleId, time);
		if (state == null || previousTime == null || time == previousTime) {
			return;
		}
		Set<Id<Person>> riders = passengerRidersByVehicle.get(vehicleId);
		if (riders == null || riders.isEmpty()) {
			return;
		}
		ExposureIntegrator.RawExposure raw = exposureIntegrator.integrate(new ExposureIntegrator.Interval(
				(time - previousTime) / 60.0, state.loadRatio(), state.freightShareOfCapacity(), settings.comfortThreshold()));
		if (raw.physical() == 0.0 && raw.freightCoPresence() == 0.0) {
			return;
		}
		for (Id<Person> personId : riders) {
			PassengerRideState ride = passengerRides.get(personId);
			if (ride == null || !vehicleId.equals(ride.vehicleId())) {
				throw invalidExposureEvent("Observed passenger ride is inconsistent for " + personId + " in " + vehicleId);
			}
			ride.addExposure(raw, exposureValuation);
			passengerExposureAccounts.get(personId).addRawExposure(raw);
		}
	}

	private void recordVehicleEventTime(Id<Vehicle> vehicleId, double time) {
		Double previous = lastObservedVehicleEventTime.get(vehicleId);
		if (!Double.isFinite(time) || time < 0.0 || (previous != null && time < previous)) {
			throw invalidExposureEvent("Nonfinite, negative, or reversed transit event time for " + vehicleId
					+ ": " + previous + " -> " + time);
		}
		lastObservedVehicleEventTime.put(vehicleId, time);
	}

	private IllegalStateException invalidExposureEvent(String reason) {
		exposureInvalidEventReasons.add(reason);
		return new IllegalStateException(reason);
	}

	/**
	 * Canonically ordered immutable person and iteration accounts, including zero
	 * accounts for passengers with no PT exposure. No score is inferred from beta
	 * division. Call after event processing and before the next iteration's reset.
	 */
	public PassengerExposureSnapshot passengerExposureSnapshot() {
		return passengerExposureSnapshot(true);
	}

	/** Startup-only output option. Legacy monitors retain detailed files by default. */
	public void configureDetailedVehicleOutput(boolean enabled) {
		if (outputConfigurationLocked || outputConfigurationSet) {
			throw new IllegalStateException("Detailed vehicle output must be configured once before the first iteration");
		}
		detailedVehicleOutput = enabled;
		outputConfigurationSet = true;
	}

	public boolean detailedVehicleOutputEnabled() { return detailedVehicleOutput; }

	/** Excluding zero/non-PT rows reduces summary-writing allocations, not totals. */
	public PassengerExposureSnapshot passengerExposureSnapshot(boolean includeAllPassengers) {
		Map<String, Id<Person>> ids = new TreeMap<>();
		if (includeAllPassengers) {
			for (Id<Person> id : scenario.getPopulation().getPersons().keySet()) {
				if (!registry.isFreightPerson(id) && !transitDrivers.contains(id)) {
					ids.put(id.toString(), id);
				}
			}
		}
		passengerExposureAccounts.keySet().forEach(id -> ids.put(id.toString(), id));
		Map<String, PassengerExposureSnapshot.PersonExposure> persons = new LinkedHashMap<>();
		ExposureIntegrator.RawExposure totalRaw = new ExposureIntegrator.RawExposure(0.0, 0.0);
		double physicalApplied = 0.0;
		double coPresenceApplied = 0.0;
		for (Map.Entry<String, Id<Person>> entry : ids.entrySet()) {
			PassengerExposureAccount account = passengerExposureAccounts.get(entry.getValue());
			ExposureIntegrator.RawExposure raw = account == null
					? new ExposureIntegrator.RawExposure(0.0, 0.0) : account.rawExposure();
			ExposureValuation.Penalties applied = account == null
					? new ExposureValuation.Penalties(0.0, 0.0) : account.appliedPenalties();
			persons.put(entry.getKey(), new PassengerExposureSnapshot.PersonExposure(entry.getKey(), raw,
					exposureValuation.value(raw), applied, passengerRides.containsKey(entry.getValue())));
			totalRaw = totalRaw.plus(raw);
			physicalApplied += applied.physical();
			coPresenceApplied += applied.freightCoPresence();
		}
		return new PassengerExposureSnapshot(exposureIteration, persons, totalRaw, exposureValuation.value(totalRaw),
				new ExposureValuation.Penalties(physicalApplied, coPresenceApplied), passengerRides.size(), exposureInvalidEventReasons);
	}

	/** Lossless immutable order evidence for the common artifact writer, before CSV rounding. */
	public List<FreightOrderSnapshot> freightOrderSnapshots(double executionHorizonSeconds) {
		return freightStats.values().stream()
				.sorted(java.util.Comparator.comparing(stats -> stats.order().orderId()))
				.map(stats -> new FreightOrderSnapshot(stats.order(), stats.snapshot(executionHorizonSeconds),
						stats.deniedBoardings(), stats.boardingEvents(), stats.deliveryEvents()))
				.toList();
	}

	/** Native-event counts; detail denial events must not be added again to these totals. */
	public RuntimeCounts runtimeCounts() {
		return new RuntimeCounts(phaseController.isActive(), passengerBoardings, freightBoardings,
				passengerDeniedBoardings, freightDeniedBoardings, passengerStuckEvents, freightStuckEvents,
				freightEvents, maxLoadRatio, maxFreightShare,
				vehicleStates.values().stream().mapToInt(VehicleCapacityState::freightUnits).sum(),
				phaseController.activationCount(), phaseController.firstActivationIteration());
	}

	public record FreightOrderSnapshot(FreightOrder order, OrderOutcomeReconstructor.Outcome outcome,
			int deniedBoardings, int boardingEvents, int deliveryEvents) { }

	public record RuntimeCounts(boolean phaseActive, int passengerBoardings, int freightBoardings,
			int passengerDeniedBoardings, int freightDeniedBoardings, int passengerStuckEvents,
			int freightStuckEvents, int freightEvents, double maxLoadRatio, double maxFreightShareOfCapacity,
			int freightUnitsStillOnboard, int phaseActivationCount, int phaseActivationIteration) { }

	static double calculateExposurePenalty(double rideMinutes, double freightShare, double loadRatio,
			double comfortThreshold, double penaltyPerMinute) {
		return calculateExposurePenalty(rideMinutes, freightShare, loadRatio, comfortThreshold,
				penaltyPerMinute, penaltyPerMinute);
	}

	static double calculateExposurePenalty(double rideMinutes, double freightShare, double loadRatio,
			double comfortThreshold, double physicalCrowdingPenaltyPerMinute,
			double freightCoPresencePenaltyPerMinute) {
		return calculateExposurePenaltyComponents(rideMinutes, freightShare, loadRatio, comfortThreshold,
				physicalCrowdingPenaltyPerMinute, freightCoPresencePenaltyPerMinute).total();
	}

	static ExposurePenaltyComponents calculateExposurePenaltyComponents(double rideMinutes, double freightShare,
			double loadRatio, double comfortThreshold, double physicalCrowdingPenaltyPerMinute,
			double freightCoPresencePenaltyPerMinute) {
		ExposureIntegrator.RawExposure raw = ExposureIntegrator.capacityNormalized().integrate(
				new ExposureIntegrator.Interval(rideMinutes, loadRatio, freightShare, comfortThreshold));
		ExposureValuation.Penalties penalties = new ExposureValuation(physicalCrowdingPenaltyPerMinute,
				freightCoPresencePenaltyPerMinute).value(raw);
		return new ExposurePenaltyComponents(penalties.physical(), penalties.freightCoPresence());
	}

	record ExposurePenaltyComponents(double physicalCrowding, double freightCoPresence) {
		double total() {
			return physicalCrowding + freightCoPresence;
		}
	}

	private static final class PassengerExposureAccount {
		private double rawPhysical;
		private double rawCoPresence;
		private double appliedPhysical;
		private double appliedCoPresence;

		void addRawExposure(ExposureIntegrator.RawExposure raw) {
			double updatedPhysical = rawPhysical + raw.physical();
			double updatedCoPresence = rawCoPresence + raw.freightCoPresence();
			ExposureIntegrator.requireNonnegativeFinite(updatedPhysical, "person rawPhysical");
			ExposureIntegrator.requireNonnegativeFinite(updatedCoPresence, "person rawCoPresence");
			rawPhysical = updatedPhysical;
			rawCoPresence = updatedCoPresence;
		}

		void addAppliedPenalties(double physical, double coPresence) {
			ExposureValuation.Penalties updated = new ExposureValuation.Penalties(
					appliedPhysical + physical, appliedCoPresence + coPresence);
			appliedPhysical = updated.physical();
			appliedCoPresence = updated.freightCoPresence();
		}

		ExposureIntegrator.RawExposure rawExposure() {
			return new ExposureIntegrator.RawExposure(rawPhysical, rawCoPresence);
		}

		ExposureValuation.Penalties appliedPenalties() {
			return new ExposureValuation.Penalties(appliedPhysical, appliedCoPresence);
		}
	}

	private void writeOrderReport(IterationEndsEvent event) {
		Path path = reportPath(event.getServices().getControllerIO().getIterationFilename(event.getIteration(), "coTransport_freight_orders.csv"));
		try {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = reportWriter(path)) {
				writer.write("order_id,capacity_units,units_boarded,units_delivered,window_units_delivered,uncompleted_units,window_uncompleted_units,denied_boardings,release_time,deadline_time,service_window_start,service_window_end,first_boarding_time,last_delivery_time,late_minutes,delay_penalty,incomplete_penalty,window_incomplete_penalty,completion_state,window_completion_state,wait_to_first_board_min,boarding_events,delivery_events,boarded_agent_ids,delivered_agent_ids,agent_balance_state\n");
				for (FreightOrderRuntimeStats stats : freightStats.values()) {
					stats.assertConserved();
					FreightOrder order = stats.order();
					double lastDelivery = stats.lastDeliveryTime();
					double lateMinutes = Double.isNaN(lastDelivery) ? 0.0 : Math.max(0.0, lastDelivery - order.deadlineTime()) / 60.0;
					int uncompletedUnits = uncompletedUnits(stats);
					int windowUncompletedUnits = windowUncompletedUnits(stats);
					double incompletePenalty = incompletePenalty(stats);
					double windowIncompletePenalty = windowIncompletePenalty(stats);
					writer.write(String.join(",",
							order.orderId(),
							Integer.toString(order.capacityUnits()),
							Integer.toString(stats.unitsBoarded()),
							Integer.toString(stats.unitsDelivered()),
							Integer.toString(stats.unitsDeliveredWithinServiceWindow()),
							Integer.toString(uncompletedUnits),
							Integer.toString(windowUncompletedUnits),
							Integer.toString(stats.deniedBoardings()),
							format(order.releaseTime()),
							format(order.deadlineTime()),
							format(settings.freightServiceWindowStartTime()),
							format(settings.freightServiceWindowEndTime()),
							format(stats.firstBoardingTime()),
							format(stats.lastDeliveryTime()),
							String.format(java.util.Locale.ROOT, "%.3f", lateMinutes),
							String.format(java.util.Locale.ROOT, "%.3f", stats.delayPenalty()),
							String.format(java.util.Locale.ROOT, "%.3f", incompletePenalty),
							String.format(java.util.Locale.ROOT, "%.3f", windowIncompletePenalty),
							uncompletedUnits == 0 ? "completed" : "incomplete",
							windowUncompletedUnits == 0 ? "completed_by_service_window" : "incomplete_at_service_window_end",
							formatDecimal(stats.waitToFirstBoardMinutes()),
							Integer.toString(stats.boardingEvents()),
							Integer.toString(stats.deliveryEvents()),
							stats.boardedAgentIds(),
							stats.deliveredAgentIds(),
							stats.agentBalanceState()));
					writer.write("\n");
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not write co-transport order report: " + path, e);
		}
	}

	private void writeSummaryReport(IterationEndsEvent event) {
		PassengerExposureSnapshot exposure = passengerExposureSnapshot(false);
		Path path = reportPath(event.getServices().getControllerIO().getIterationFilename(event.getIteration(), "coTransport_summary.csv"));
		try {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = reportWriter(path)) {
				int completedOrders = 0;
				int onTimeOrders = 0;
				int incompleteOrders = 0;
				int capacityUnits = 0;
				int deliveredUnits = 0;
				int uncompletedUnits = 0;
				int windowIncompleteOrders = 0;
				int windowUncompletedUnits = 0;
				double freightDelayPenalty = 0.0;
				double freightIncompletePenalty = 0.0;
				double windowFreightIncompletePenalty = 0.0;
				List<Double> completedLatenessMinutes = new ArrayList<>();
				List<Double> waitToFirstBoardMinutes = new ArrayList<>();
				for (FreightOrderRuntimeStats stats : freightStats.values()) {
					stats.assertConserved();
					capacityUnits += stats.order().capacityUnits();
					deliveredUnits += stats.unitsDelivered();
					if (stats.unitsDelivered() >= stats.order().capacityUnits()) {
						completedOrders++;
						completedLatenessMinutes.add(stats.completedOrderLatenessMinutes());
						if (!Double.isNaN(stats.lastDeliveryTime()) && stats.lastDeliveryTime() <= stats.order().deadlineTime()) {
							onTimeOrders++;
						}
					} else {
						incompleteOrders++;
					}
					uncompletedUnits += uncompletedUnits(stats);
					if (windowUncompletedUnits(stats) > 0) {
						windowIncompleteOrders++;
					}
					windowUncompletedUnits += windowUncompletedUnits(stats);
					freightDelayPenalty += stats.delayPenalty();
					freightIncompletePenalty += incompletePenalty(stats);
					windowFreightIncompletePenalty += windowIncompletePenalty(stats);
					if (!Double.isNaN(stats.waitToFirstBoardMinutes())) {
						waitToFirstBoardMinutes.add(stats.waitToFirstBoardMinutes());
					}
				}
				double completionRate = freightStats.isEmpty() ? 0.0 : (double) completedOrders / freightStats.size();
				double windowCompletionRate = freightStats.isEmpty() ? 0.0 : (double) (freightStats.size() - windowIncompleteOrders) / freightStats.size();
				double onTimeRate = freightStats.isEmpty() ? 0.0 : (double) onTimeOrders / freightStats.size();
				double unitCompletionRate = capacityUnits == 0 ? 0.0 : (double) deliveredUnits / capacityUnits;
				List<Double> observedLoadRatios = loadObservations.stream().map(LoadObservation::loadRatio).toList();
				List<Double> observedFreightShares = loadObservations.stream().map(LoadObservation::freightShare).toList();
				double observedMaxLoadRatio = observedLoadRatios.isEmpty()
						? maxLoadRatio : percentile(observedLoadRatios, 1.0);
				double observedMaxFreightShare = observedFreightShares.isEmpty()
						? maxFreightShare : percentile(observedFreightShares, 1.0);
				double freightScore = phaseController.isActive()
						? FREIGHT_SCORE_ON_TIME_WEIGHT * onTimeRate + FREIGHT_SCORE_COMPLETION_WEIGHT * completionRate
						- freightDelayPenalty - freightDeniedPenalty - freightIncompletePenalty
						: 0.0;
				double passengerPenaltyTotal = passengerPenalty + passengerDeniedPenalty;
				writer.write("iteration,co_transport_active,freight_orders,completed_orders,on_time_orders,incomplete_orders,uncompleted_units,window_incomplete_orders,window_uncompleted_units,completion_rate,window_completion_rate,on_time_rate,freight_service_window_start,freight_service_window_end,freight_boardings,freight_denied_boardings,freight_stuck_events,passenger_boardings,passenger_denied_boardings,passenger_denied_persons,passenger_stuck_events,passenger_stuck_persons,passenger_physical_crowding_penalty,passenger_freight_copresence_penalty,passenger_crowding_penalty,passenger_denied_penalty,passenger_total_penalty,freight_delay_penalty,freight_denied_penalty,freight_incomplete_penalty,window_freight_incomplete_penalty,freight_score,freight_capacity_units,freight_delivered_units,unit_completion_rate,completed_orders_with_lateness,lateness_mean_min,lateness_p50_min,lateness_p95_min,orders_with_first_boarding,wait_to_first_board_mean_min,wait_to_first_board_p50_min,wait_to_first_board_p95_min,load_observations,mean_load_ratio,p95_load_ratio,max_load_ratio,mean_freight_share,p95_freight_share,max_freight_share,hard_capacity_ratio_active,configured_hard_capacity_ratio,passenger_raw_physical_exposure,passenger_raw_freight_copresence_exposure,unfinished_passenger_rides,exposure_event_state_valid\n");
				writer.write(String.join(",",
						Integer.toString(event.getIteration()),
						Boolean.toString(phaseController.isActive()),
						Integer.toString(freightStats.size()),
						Integer.toString(completedOrders),
						Integer.toString(onTimeOrders),
						Integer.toString(incompleteOrders),
						Integer.toString(uncompletedUnits),
						Integer.toString(windowIncompleteOrders),
						Integer.toString(windowUncompletedUnits),
						String.format(java.util.Locale.ROOT, "%.4f", completionRate),
						String.format(java.util.Locale.ROOT, "%.4f", windowCompletionRate),
						String.format(java.util.Locale.ROOT, "%.4f", onTimeRate),
						format(settings.freightServiceWindowStartTime()),
						format(settings.freightServiceWindowEndTime()),
						Integer.toString(freightBoardings),
						Integer.toString(freightDeniedBoardings),
						Integer.toString(freightStuckEvents),
						Integer.toString(passengerBoardings),
						Integer.toString(passengerDeniedBoardings),
						Integer.toString(passengerDeniedPersons.size()),
						Integer.toString(passengerStuckEvents),
						Integer.toString(passengerStuckPersons.size()),
						String.format(java.util.Locale.ROOT, "%.3f", passengerPhysicalCrowdingPenalty),
						String.format(java.util.Locale.ROOT, "%.3f", passengerFreightCoPresencePenalty),
						String.format(java.util.Locale.ROOT, "%.3f", passengerPenalty),
						String.format(java.util.Locale.ROOT, "%.3f", passengerDeniedPenalty),
						String.format(java.util.Locale.ROOT, "%.3f", passengerPenaltyTotal),
						String.format(java.util.Locale.ROOT, "%.3f", freightDelayPenalty),
						String.format(java.util.Locale.ROOT, "%.3f", freightDeniedPenalty),
						String.format(java.util.Locale.ROOT, "%.3f", freightIncompletePenalty),
						String.format(java.util.Locale.ROOT, "%.3f", windowFreightIncompletePenalty),
						String.format(java.util.Locale.ROOT, "%.3f", freightScore),
						Integer.toString(capacityUnits),
						Integer.toString(deliveredUnits),
						formatRate(unitCompletionRate),
						Integer.toString(completedLatenessMinutes.size()),
						formatDecimal(mean(completedLatenessMinutes)),
						formatDecimal(percentile(completedLatenessMinutes, 0.50)),
						formatDecimal(percentile(completedLatenessMinutes, 0.95)),
						Integer.toString(waitToFirstBoardMinutes.size()),
						formatDecimal(mean(waitToFirstBoardMinutes)),
						formatDecimal(percentile(waitToFirstBoardMinutes, 0.50)),
						formatDecimal(percentile(waitToFirstBoardMinutes, 0.95)),
						Integer.toString(loadObservations.size()),
						formatRate(mean(observedLoadRatios)),
						formatRate(percentile(observedLoadRatios, 0.95)),
						formatRate(observedMaxLoadRatio),
						formatRate(mean(observedFreightShares)),
						formatRate(percentile(observedFreightShares, 0.95)),
						formatRate(observedMaxFreightShare),
							Boolean.toString(hardCapacityRatioActive()),
						formatRate(settings.hardCapacityRatio()),
						Double.toString(exposure.rawExposure().physical()),
						Double.toString(exposure.rawExposure().freightCoPresence()),
						Integer.toString(exposure.unfinishedRides()),
						Boolean.toString(exposure.validEventState())));
				writer.write("\n");
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not write co-transport summary report: " + path, e);
		}
	}

	private void writeDeniedResponseReport(IterationEndsEvent event) {
		Path path = reportPath(event.getServices().getControllerIO().getIterationFilename(event.getIteration(), "coTransport_denied_responses.csv"));
		try {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = reportWriter(path)) {
				writer.write("person_id,agent_type,order_id,denied_boardings,first_denied_time,last_denied_time,first_denied_vehicle,first_denied_stop,boarded_after_denied,first_boarding_after_denied,boarding_after_denied_vehicle,wait_after_first_denied_min,delivered_after_denied,delivery_after_denied_time,response_state\n");
				for (DeniedBoardingResponseState response : deniedResponses.values()) {
					writer.write(String.join(",",
							response.personId().toString(),
							response.freight() ? "freight" : "passenger",
							response.orderId(),
							Integer.toString(response.deniedBoardings()),
							format(response.firstDeniedTime()),
							format(response.lastDeniedTime()),
							response.firstDeniedVehicle(),
							response.firstDeniedStop(),
							Boolean.toString(response.boardedAfterDenied()),
							format(response.firstBoardingAfterDeniedTime()),
							response.boardingAfterDeniedVehicle(),
							formatDecimal(response.waitAfterFirstDeniedMinutes()),
							Boolean.toString(response.deliveredAfterDenied()),
							format(response.deliveryAfterDeniedTime()),
							response.responseState()));
					writer.write("\n");
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not write co-transport denied-response report: " + path, e);
		}
	}

	private void writeDeniedBoardingEventReport(IterationEndsEvent event) {
		Path path = reportPath(event.getServices().getControllerIO().getIterationFilename(
				event.getIteration(), "coTransport_denied_boarding_events.csv"));
		try {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = reportWriter(path)) {
				writer.write("person_id,agent_type,order_id,denial_sequence,denied_time,vehicle_id,line_id,route_id,stop_id\n");
				for (DeniedBoardingResponseState response : deniedResponses.values()) {
					for (DeniedBoardingOccurrence occurrence : response.denialOccurrences()) {
						writer.write(String.join(",",
								response.personId().toString(),
								response.freight() ? "freight" : "passenger",
								response.orderId(),
								Integer.toString(occurrence.sequence()),
								format(occurrence.time()),
								occurrence.vehicleId() == null ? "" : occurrence.vehicleId().toString(),
								occurrence.vehicleId() == null ? "" : vehicleLines.getOrDefault(occurrence.vehicleId(), ""),
								occurrence.vehicleId() == null ? "" : vehicleRoutes.getOrDefault(occurrence.vehicleId(), ""),
								occurrence.stopId() == null ? "" : occurrence.stopId().toString()));
						writer.write("\n");
					}
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not write denied-boarding event detail: " + path, e);
		}
	}

	private void writeLoadObservationReport(IterationEndsEvent event) {
		if (!detailedVehicleOutput) return;
		Path path = reportPath(event.getServices().getControllerIO().getIterationFilename(
				event.getIteration(), "coTransport_load_observations.csv"));
		try {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = reportWriter(path)) {
				writer.write("time,vehicle,line,route,stop,total_occupancy,passenger_occupancy,freight_occupancy,capacity,load_ratio,freight_share\n");
				for (LoadObservation observation : loadObservations) {
					writer.write(String.join(",",
							format(observation.time()),
							observation.vehicleId().toString(),
							observation.lineId(),
							observation.routeId(),
							observation.stopId() == null ? "" : observation.stopId().toString(),
							Integer.toString(observation.totalOccupancy()),
							Integer.toString(observation.passengerOccupancy()),
							Integer.toString(observation.freightOccupancy()),
							Integer.toString(observation.capacity()),
							formatRate(observation.loadRatio()),
							formatRate(observation.freightShare())));
					writer.write("\n");
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not write co-transport load observations: " + path, e);
		}
	}

	private void writeActivationAudit(IterationEndsEvent event) {
		Path path = reportPath(event.getServices().getControllerIO().getOutputFilename("r17_activation_audit.csv"));
		String header = "iteration,phase_active,treatment_active,schedule_active,freight_active,registered_freight_persons,freight_agents_installed,freight_events,freight_boardings,passenger_stuck_events,passenger_stuck_persons\n";
		String row = String.join(",",
				Integer.toString(event.getIteration()),
				Boolean.toString(phaseController.isActive()),
				Boolean.toString(phaseController.isTreatmentActive()),
				Boolean.toString(phaseController.isScheduleActive()),
				Boolean.toString(phaseController.isFreightActive()),
				Integer.toString(phaseController.registeredFreightPersonCount()),
				Integer.toString(phaseController.installedFreightPersonCount()),
				Integer.toString(freightEvents),
				Integer.toString(freightBoardings),
				Integer.toString(passengerStuckEvents),
				Integer.toString(passengerStuckPersons.size())) + "\n";
		try {
			if (settings.compressReports()) {
				appendCompressedRootReport(path, header, row);
				return;
			}
			boolean writeHeader = !Files.exists(path) || Files.size(path) == 0L;
			try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				if (writeHeader) writer.write(header);
				writer.write(row);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not write co-transport activation audit: " + path, e);
		}
	}

	/** These helpers affect encoding and filenames only; report loops and fields are unchanged. */
	private Path reportPath(String uncompressedFilename) {
		return Path.of(uncompressedFilename + (settings.compressReports() ? ".gz" : ""));
	}

	private BufferedWriter reportWriter(Path path) throws IOException {
		return settings.compressReports() ? IOUtils.getBufferedWriter(path.toString()) : Files.newBufferedWriter(path);
	}

	/**
	 * IOUtils deliberately forbids gzip append. Rewrite this small cumulative root table in
	 * a same-directory temporary gzip, copying its complete previous text without filtering,
	 * then replace the final gzip after close. No uncompressed report is materialized.
	 */
	private static void appendCompressedRootReport(Path path, String header, String row) throws IOException {
		Files.createDirectories(path.getParent());
		Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString() + "-", ".gz");
		try {
			boolean writeHeader = true;
			try (BufferedWriter writer = IOUtils.getBufferedWriter(temporary.toString())) {
				if (Files.exists(path) && Files.size(path) > 0L) {
					try (BufferedReader reader = IOUtils.getBufferedReader(path.toString())) {
						char[] buffer = new char[8192];
						int count;
						while ((count = reader.read(buffer)) != -1) {
							writer.write(buffer, 0, count);
							if (count > 0) writeHeader = false;
						}
					}
				}
				if (writeHeader) writer.write(header);
				writer.write(row);
			}
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String format(double seconds) {
		if (Double.isNaN(seconds)) {
			return "";
		}
		int value = (int) Math.round(seconds);
		int h = value / 3600;
		int m = (value % 3600) / 60;
		int s = value % 60;
		return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, s);
	}

	private static String formatDecimal(double value) {
		if (Double.isNaN(value)) {
			return "";
		}
		return String.format(java.util.Locale.ROOT, "%.3f", value);
	}

	private static String formatRate(double value) {
		if (Double.isNaN(value)) {
			return "";
		}
		return String.format(java.util.Locale.ROOT, "%.6f", value);
	}

	private static String csv(String value) {
		if (value == null) {
			return "";
		}
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	static double mean(List<Double> values) {
		if (values.isEmpty()) {
			return 0.0;
		}
		return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
	}

	/** The legacy config value is retained for compatibility but is not a runtime gate. */
	static boolean hardCapacityRatioActive() {
		return false;
	}

	static double percentile(List<Double> values, double quantile) {
		if (values.isEmpty()) {
			return 0.0;
		}
		if (quantile < 0.0 || quantile > 1.0) {
			throw new IllegalArgumentException("quantile must be in [0,1], got " + quantile);
		}
		List<Double> ordered = new ArrayList<>(values);
		ordered.sort(Double::compareTo);
		double position = (ordered.size() - 1) * quantile;
		int lower = (int) Math.floor(position);
		int upper = (int) Math.ceil(position);
		if (lower == upper) {
			return ordered.get(lower);
		}
		double fraction = position - lower;
		return ordered.get(lower) * (1.0 - fraction) + ordered.get(upper) * fraction;
	}

	private int uncompletedUnits(FreightOrderRuntimeStats stats) {
		return Math.max(0, stats.order().capacityUnits() - stats.unitsDelivered());
	}

	private int windowUncompletedUnits(FreightOrderRuntimeStats stats) {
		if (!settings.hasFreightServiceWindow()) {
			return uncompletedUnits(stats);
		}
		return Math.max(0, stats.order().capacityUnits() - stats.unitsDeliveredWithinServiceWindow());
	}

	private double incompletePenalty(FreightOrderRuntimeStats stats) {
		return phaseController.isActive()
				? uncompletedUnits(stats) * settings.freightIncompletePenaltyPerUnit() * stats.order().priority()
				: 0.0;
	}

	private double windowIncompletePenalty(FreightOrderRuntimeStats stats) {
		return phaseController.isActive()
				? windowUncompletedUnits(stats) * settings.freightIncompletePenaltyPerUnit() * stats.order().priority()
				: 0.0;
	}

	private void ensureFreightIsArmed(Id<Person> personId, boolean freight) {
		if (freight && !phaseController.isActive()) {
			throw invalidExposureEvent("Freight person " + personId
					+ " produced an event while coTransport is inactive in iteration "
					+ phaseController.currentIteration());
		}
	}

	private record LoadObservation(double time, Id<Vehicle> vehicleId, String lineId, String routeId,
			Id<TransitStopFacility> stopId, int totalOccupancy, int passengerOccupancy,
			int freightOccupancy, int capacity, double loadRatio, double freightShare) {
	}

	private record StuckOccurrence(String personId, String agentType, String orderId, double time,
			String linkId, String legMode, String reason) {
	}

	record DeniedBoardingOccurrence(int sequence, double time, Id<Vehicle> vehicleId,
			Id<TransitStopFacility> stopId) {
	}

	static final class DeniedBoardingResponseState {
		private final Id<Person> personId;
		private final boolean freight;
		private final String orderId;
		private final List<DeniedBoardingOccurrence> denialOccurrences = new ArrayList<>();
		private int deniedBoardings;
		private double firstDeniedTime = Double.NaN;
		private double lastDeniedTime = Double.NaN;
		private Id<Vehicle> firstDeniedVehicle;
		private Id<TransitStopFacility> firstDeniedStop;
		private double firstBoardingAfterDeniedTime = Double.NaN;
		private Id<Vehicle> boardingAfterDeniedVehicle;
		private double deliveryAfterDeniedTime = Double.NaN;

		DeniedBoardingResponseState(Id<Person> personId, boolean freight, String orderId) {
			this.personId = personId;
			this.freight = freight;
			this.orderId = orderId;
		}

		void denied(double time, Id<Vehicle> vehicleId, Id<TransitStopFacility> stopId) {
			deniedBoardings++;
			denialOccurrences.add(new DeniedBoardingOccurrence(deniedBoardings, time, vehicleId, stopId));
			if (Double.isNaN(firstDeniedTime)) {
				firstDeniedTime = time;
				firstDeniedVehicle = vehicleId;
				firstDeniedStop = stopId;
			}
			lastDeniedTime = time;
		}

		private void boarded(double time, Id<Vehicle> vehicleId) {
			if (Double.isNaN(firstBoardingAfterDeniedTime) && !Double.isNaN(firstDeniedTime) && time >= firstDeniedTime) {
				firstBoardingAfterDeniedTime = time;
				boardingAfterDeniedVehicle = vehicleId;
			}
		}

		private void delivered(double time) {
			if (Double.isNaN(deliveryAfterDeniedTime) && !Double.isNaN(firstDeniedTime) && time >= firstDeniedTime) {
				deliveryAfterDeniedTime = time;
			}
		}

		private Id<Person> personId() {
			return personId;
		}

		private boolean freight() {
			return freight;
		}

		private String orderId() {
			return orderId;
		}

		private int deniedBoardings() {
			return deniedBoardings;
		}

		List<DeniedBoardingOccurrence> denialOccurrences() {
			return List.copyOf(denialOccurrences);
		}

		private double firstDeniedTime() {
			return firstDeniedTime;
		}

		private double lastDeniedTime() {
			return lastDeniedTime;
		}

		private String firstDeniedVehicle() {
			return firstDeniedVehicle == null ? "" : firstDeniedVehicle.toString();
		}

		private String firstDeniedStop() {
			return firstDeniedStop == null ? "" : firstDeniedStop.toString();
		}

		private boolean boardedAfterDenied() {
			return !Double.isNaN(firstBoardingAfterDeniedTime);
		}

		private double firstBoardingAfterDeniedTime() {
			return firstBoardingAfterDeniedTime;
		}

		private String boardingAfterDeniedVehicle() {
			return boardingAfterDeniedVehicle == null ? "" : boardingAfterDeniedVehicle.toString();
		}

		private double waitAfterFirstDeniedMinutes() {
			if (Double.isNaN(firstDeniedTime) || Double.isNaN(firstBoardingAfterDeniedTime)) {
				return Double.NaN;
			}
			return Math.max(0.0, firstBoardingAfterDeniedTime - firstDeniedTime) / 60.0;
		}

		private boolean deliveredAfterDenied() {
			return !Double.isNaN(deliveryAfterDeniedTime);
		}

		private double deliveryAfterDeniedTime() {
			return deliveryAfterDeniedTime;
		}

		private String responseState() {
			if (deliveredAfterDenied()) {
				return "freight_delivered_after_denied";
			}
			if (boardedAfterDenied()) {
				return "boarded_later_same_iteration";
			}
			return "still_waiting_or_unresolved";
		}
	}
}
