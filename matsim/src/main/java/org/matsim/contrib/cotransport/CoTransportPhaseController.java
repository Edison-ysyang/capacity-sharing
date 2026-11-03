package org.matsim.contrib.cotransport;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.ReplanningUtils;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.pt.router.TransitScheduleChangedEvent;
import org.matsim.pt.transitSchedule.api.MinimalTransferTimes;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Arms and disarms freight inside one uninterrupted controller run.
 *
 * <p>Inactive phases remove freight persons before replanning and QSim. If any inactive
 * iteration is configured, a baseline timetable is mandatory and is installed into the
 * live schedule container. Requiring that file is intentional: an intervention timetable
 * must never be silently treated as a no-freight baseline.</p>
 */
@Singleton
public final class CoTransportPhaseController implements StartupListener, IterationStartsListener {
	private static final Logger LOG = LogManager.getLogger(CoTransportPhaseController.class);

	private final CoTransportSettings settings;
	private final Scenario scenario;
	private final EventsManager events;
	private final StrategyManager strategyManager;
	private final Map<ReplanningConfigGroup.StrategySettings, PlanStrategy> configuredStrategies;
	private final int firstIteration;
	private final Map<Id<Person>, Person> freightPersons = new LinkedHashMap<>();
	private final ScheduleContents activatedSchedule;
	private final ScheduleContents baselineSchedule;
	private final VehicleContents activatedVehicles;
	private final VehicleContents baselineVehicles;
	private final boolean scheduleInterventionExists;
	private boolean active;
	private boolean activatedStateInstalled;
	private int currentIteration = -1;
	private boolean innovationRequestsRegistered;
	private List<InnovationChangeRequest> registeredInnovationRequests = List.of();
	private int activationCount;
	private int firstActivationIteration = -1;

	@Inject
	public CoTransportPhaseController(CoTransportSettings settings, FreightOrderRegistry registry,
			Scenario scenario, EventsManager events, ControllerConfigGroup controllerConfig,
			StrategyManager strategyManager) {
		this(settings, registry, scenario, events, controllerConfig, strategyManager,
				strategyManager.getConfiguredStrategies());
	}

	/**
	 * Compatibility constructor for manually assembled managers. Injected managers always use
	 * their own captured bindings; supplied legacy bindings are accepted only for a manager
	 * without captured configuration and are identity-checked before changes are registered.
	 */
	public CoTransportPhaseController(CoTransportSettings settings, FreightOrderRegistry registry,
			Scenario scenario, EventsManager events, ControllerConfigGroup controllerConfig,
			StrategyManager strategyManager,
			Map<ReplanningConfigGroup.StrategySettings, PlanStrategy> configuredStrategies) {
		this.settings = settings;
		this.scenario = scenario;
		this.events = events;
		this.strategyManager = strategyManager;
		Map<ReplanningConfigGroup.StrategySettings, PlanStrategy> actualBindings = strategyManager.getConfiguredStrategies();
		this.configuredStrategies = Collections.unmodifiableMap(new LinkedHashMap<>(
				actualBindings.isEmpty() ? configuredStrategies : actualBindings));
		this.firstIteration = controllerConfig.getFirstIteration();
		this.active = settings.isActiveAt(firstIteration);
		// Reconcile the complete offline population before any inactive-phase removal.
		// This registry is observational; it must never materialize missing units.
		registry.validatePopulation(scenario.getPopulation());
		for (Person person : scenario.getPopulation().getPersons().values()) {
			if (registry.isFreightPerson(person.getId())) {
				freightPersons.put(person.getId(), person);
			}
		}
		this.activatedSchedule = settings.activatedTransitScheduleFile() == null
				? ScheduleContents.capture(scenario.getTransitSchedule())
				: loadSchedule(settings.activatedTransitScheduleFile());
		this.activatedVehicles = settings.activatedTransitVehiclesFile() == null
				? VehicleContents.capture(scenario.getTransitVehicles())
				: loadVehicles(settings.activatedTransitVehiclesFile());
		if (hasInactiveIteration(settings, controllerConfig.getFirstIteration(), controllerConfig.getLastIteration())) {
			if (settings.baselineTransitScheduleFile() == null) {
				throw new IllegalStateException("coTransport armMode=" + settings.armMode()
						+ " executes at least one inactive iteration, but baselineTransitScheduleFile is not configured. "
						+ "A static intervention timetable is not a valid no-freight baseline.");
			}
			this.baselineSchedule = loadSchedule(settings);
			validateVehicles(this.baselineSchedule, scenario);
			this.baselineVehicles = VehicleContents.capture(scenario.getTransitVehicles());
			this.scheduleInterventionExists = !activatedSchedule.signature().equals(baselineSchedule.signature());
		} else {
			this.baselineSchedule = null;
			this.baselineVehicles = null;
			this.scheduleInterventionExists = false;
		}
		// Revisioned scenarios load baseline timetable/vehicles as the live startup state.  Legacy
		// scenarios retain the old activated-live capture and will be rolled back at Startup.
		this.activatedStateInstalled = settings.activatedTransitScheduleFile() == null
				&& settings.activatedTransitVehiclesFile() == null;
	}

	@Override
	public void notifyStartup(StartupEvent event) {
		registerInnovationRequests();
		applyIteration(firstIteration);
	}

	@Override
	public double priority() {
		return 1000.0;
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		// IterationStarts precedes native replanning. Never let a removed/replaced strategy
		// turn a previously valid change request into the native manager's silent no-op.
		validateInnovationBindings(registeredInnovationRequests);
		applyIteration(event.getIteration());
	}

	public boolean isActive() {
		return active;
	}

	public boolean isFreightActive() {
		return active && !freightPersons.isEmpty();
	}

	public boolean isScheduleActive() {
		return active && scheduleInterventionExists;
	}

	public boolean isTreatmentActive() {
		return isFreightActive() || isScheduleActive();
	}

	public int registeredFreightPersonCount() {
		return freightPersons.size();
	}

	public int installedFreightPersonCount() {
		return countInstalledFreightPersons();
	}

	public int currentIteration() {
		return currentIteration;
	}

	/** Counts installed active transitions, including an empty Control sham. */
	public int activationCount() { return activationCount; }
	public int firstActivationIteration() { return firstActivationIteration; }

	void applyIteration(int iteration) {
		boolean shouldBeActive = settings.isActiveAt(iteration);
		boolean startsActivePhase = shouldBeActive && (!active || currentIteration < 0);
		if (shouldBeActive) {
			installInterventionState(activatedSchedule, activatedVehicles, true);
			restoreFreightPersons();
		} else {
			removeFreightPersons();
			installInterventionState(baselineSchedule, baselineVehicles, false);
		}
		if (startsActivePhase) {
			activationCount++;
			if (firstActivationIteration < 0) firstActivationIteration = iteration;
		}
		active = shouldBeActive;
		currentIteration = iteration;
		LOG.info("coTransport phase at iteration {}: {}, freightPersonsInPopulation={}", iteration,
				active ? "ACTIVE" : "INACTIVE", countInstalledFreightPersons());
	}

	static boolean hasInactiveIteration(CoTransportSettings settings, int firstIteration, int lastIteration) {
		if (lastIteration < firstIteration || settings.armMode() == CoTransportSettings.ArmMode.ALWAYS_ACTIVE) {
			return false;
		}
		if (settings.armMode() == CoTransportSettings.ArmMode.DISABLED) {
			return true;
		}
		return firstIteration < settings.activationIteration()
				|| settings.deactivationIteration() >= 0
				&& lastIteration >= settings.deactivationIteration();
	}

	private void registerInnovationRequests() {
		if (innovationRequestsRegistered || !settings.manageInnovationSchedule()) {
			return;
		}
		List<InnovationChangeRequest> requests = innovationChangeRequests(settings, configuredStrategies);
		// Validate the whole batch before registering any change request.
		validateInnovationBindings(requests);
		for (InnovationChangeRequest request : requests) {
			strategyManager.addChangeRequest(request.iteration(), request.strategy(), request.subpopulation(), request.weight());
		}
		registeredInnovationRequests = List.copyOf(requests);
		innovationRequestsRegistered = true;
	}

	private void validateInnovationBindings(List<InnovationChangeRequest> requests) {
		for (InnovationChangeRequest request : requests) {
			boolean registered = strategyManager.getStrategies(request.subpopulation()).stream()
					.anyMatch(actual -> actual == request.strategy());
			if (!registered) {
				throw new IllegalStateException("coTransport innovation schedule targets an unregistered strategy instance "
						+ "for subpopulation=" + request.subpopulation() + ", iteration=" + request.iteration()
						+ ". Strategy bindings must come from the actual StrategyManager, not a reinjected provider map.");
			}
		}
	}

	static List<InnovationChangeRequest> innovationChangeRequests(CoTransportSettings settings,
			Map<ReplanningConfigGroup.StrategySettings, PlanStrategy> configuredStrategies) {
		List<InnovationChangeRequest> requests = new ArrayList<>();
		if (!settings.manageInnovationSchedule()) {
			return requests;
		}
		for (Map.Entry<ReplanningConfigGroup.StrategySettings, PlanStrategy> entry : configuredStrategies.entrySet()) {
			ReplanningConfigGroup.StrategySettings strategySettings = entry.getKey();
			PlanStrategy strategy = entry.getValue();
			if ("freight".equals(strategySettings.getSubpopulation())
					|| !ReplanningUtils.isInnovativeStrategy(strategy)) {
				continue;
			}
			requests.add(new InnovationChangeRequest(settings.innovationOffIteration(), strategy,
					strategySettings.getSubpopulation(), 0.0));
			requests.add(new InnovationChangeRequest(settings.innovationReopenIteration(), strategy,
					strategySettings.getSubpopulation(), strategySettings.getWeight()));
			requests.add(new InnovationChangeRequest(settings.innovationFinalOffIteration(), strategy,
					strategySettings.getSubpopulation(), 0.0));
		}
		return requests;
	}

	private void removeFreightPersons() {
		for (Id<Person> personId : freightPersons.keySet()) {
			scenario.getPopulation().removePerson(personId);
		}
	}

	private void restoreFreightPersons() {
		for (Person person : freightPersons.values()) {
			if (!scenario.getPopulation().getPersons().containsKey(person.getId())) {
				scenario.getPopulation().addPerson(person);
			}
		}
	}

	private int countInstalledFreightPersons() {
		int count = 0;
		for (Id<Person> personId : freightPersons.keySet()) {
			if (scenario.getPopulation().getPersons().containsKey(personId)) {
				count++;
			}
		}
		return count;
	}

	private void installInterventionState(ScheduleContents scheduleContents, VehicleContents vehicleContents,
			boolean activated) {
		if (activatedStateInstalled == activated) {
			return;
		}
		if (scheduleContents == null || vehicleContents == null) {
			throw new IllegalStateException("No baseline transit schedule is available for an inactive coTransport phase");
		}
		vehicleContents.installInto(scenario.getTransitVehicles());
		scheduleContents.installInto(scenario.getTransitSchedule());
		activatedStateInstalled = activated;
		events.processEvent(new TransitScheduleChangedEvent(0.0));
	}

	private static ScheduleContents loadSchedule(CoTransportSettings settings) {
		return loadSchedule(settings.baselineTransitScheduleFile());
	}

	private static ScheduleContents loadSchedule(java.nio.file.Path path) {
		Scenario holder = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(holder).readFile(path.toString());
		return ScheduleContents.capture(holder.getTransitSchedule());
	}

	private static VehicleContents loadVehicles(java.nio.file.Path path) {
		Scenario holder = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimVehicleReader(holder.getTransitVehicles()).readFile(path.toString());
		return VehicleContents.capture(holder.getTransitVehicles());
	}

	private static void validateVehicles(ScheduleContents contents, Scenario scenario) {
		for (TransitLine line : contents.lines()) {
			for (var route : line.getRoutes().values()) {
				for (var departure : route.getDepartures().values()) {
					if (!scenario.getTransitVehicles().getVehicles().containsKey(departure.getVehicleId())) {
						throw new IllegalStateException("Baseline transit schedule references missing vehicle "
								+ departure.getVehicleId() + " on line " + line.getId());
					}
				}
			}
		}
	}

	private record TransferTime(Id<TransitStopFacility> from, Id<TransitStopFacility> to, double seconds) {
	}

	record InnovationChangeRequest(int iteration, PlanStrategy strategy, String subpopulation, double weight) {
	}

	private record ScheduleContents(List<TransitStopFacility> facilities, List<TransitLine> lines,
			List<TransferTime> transferTimes, Map<String, Object> attributes, String signature) {
		private static ScheduleContents capture(TransitSchedule schedule) {
			List<TransferTime> transfers = new ArrayList<>();
			MinimalTransferTimes.MinimalTransferTimesIterator iterator = schedule.getMinimalTransferTimes().iterator();
			while (iterator.hasNext()) {
				iterator.next();
				transfers.add(new TransferTime(iterator.getFromStopId(), iterator.getToStopId(), iterator.getSeconds()));
			}
			return new ScheduleContents(new ArrayList<>(schedule.getFacilities().values()),
					new ArrayList<>(schedule.getTransitLines().values()), transfers,
					new LinkedHashMap<>(schedule.getAttributes().getAsMap()), signatureOf(schedule));
		}

		private static String signatureOf(TransitSchedule schedule) {
			StringBuilder signature = new StringBuilder();
			for (TransitStopFacility facility : schedule.getFacilities().values()) {
				signature.append("F|").append(facility.getId()).append('|')
						.append(facility.getCoord()).append('|').append(facility.getLinkId()).append('\n');
			}
			for (TransitLine line : schedule.getTransitLines().values()) {
				signature.append("L|").append(line.getId()).append('\n');
				for (var route : line.getRoutes().values()) {
					signature.append("R|").append(route.getId()).append('|').append(route.getTransportMode()).append('\n');
					for (var stop : route.getStops()) {
						signature.append("S|").append(stop.getStopFacility().getId()).append('|')
								.append(stop.getArrivalOffset()).append('|').append(stop.getDepartureOffset()).append('\n');
					}
					for (var departure : route.getDepartures().values()) {
						signature.append("D|").append(departure.getId()).append('|')
								.append(departure.getDepartureTime()).append('|').append(departure.getVehicleId()).append('\n');
					}
				}
			}
			return signature.toString();
		}

		private void installInto(TransitSchedule target) {
			for (TransitLine line : new ArrayList<>(target.getTransitLines().values())) {
				target.removeTransitLine(line);
			}
			for (TransitStopFacility facility : new ArrayList<>(target.getFacilities().values())) {
				target.removeStopFacility(facility);
			}
			clearTransferTimes(target.getMinimalTransferTimes());
			target.getAttributes().clear();
			for (TransitStopFacility facility : facilities) {
				target.addStopFacility(facility);
			}
			for (TransitLine line : lines) {
				target.addTransitLine(line);
			}
			for (TransferTime transfer : transferTimes) {
				target.getMinimalTransferTimes().set(transfer.from(), transfer.to(), transfer.seconds());
			}
			attributes.forEach(target.getAttributes()::putAttribute);
		}

		private static void clearTransferTimes(MinimalTransferTimes times) {
			List<TransferTime> existing = new ArrayList<>();
			MinimalTransferTimes.MinimalTransferTimesIterator iterator = times.iterator();
			while (iterator.hasNext()) {
				iterator.next();
				existing.add(new TransferTime(iterator.getFromStopId(), iterator.getToStopId(), iterator.getSeconds()));
			}
			for (TransferTime transfer : existing) {
				times.remove(transfer.from(), transfer.to());
			}
		}
	}

	private record VehicleContents(List<VehicleType> types, List<Vehicle> vehicles) {
		private static VehicleContents capture(Vehicles source) {
			return new VehicleContents(new ArrayList<>(source.getVehicleTypes().values()),
					new ArrayList<>(source.getVehicles().values()));
		}

		private void installInto(Vehicles target) {
			for (Vehicle vehicle : new ArrayList<>(target.getVehicles().values())) {
				target.removeVehicle(vehicle.getId());
			}
			for (VehicleType type : new ArrayList<>(target.getVehicleTypes().values())) {
				target.removeVehicleType(type.getId());
			}
			for (VehicleType type : types) {
				target.addVehicleType(type);
			}
			for (Vehicle vehicle : vehicles) {
				target.addVehicle(vehicle);
			}
		}
	}
}
