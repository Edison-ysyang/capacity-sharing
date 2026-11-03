package org.matsim.contrib.cotransport;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only startup guard for the explicit dedicated-service supply. This is deliberately
 * separate from native boarding: it detects invalid input identities, not simulated demand
 * outcomes. Orders larger than every vehicle on their planned route are reported as
 * structurally unserviceable, not removed, split, resized or forced to complete.
 *
 * <p>The input entry points remain dedicatedFreightLineId, activatedTransitScheduleFile and
 * activatedTransitVehiclesFile. Future timetable controllers may replace the supply through
 * the existing schedule-change interface; this validator creates no dispatch controller.</p>
 */
@Singleton
public final class DedicatedFreightServiceValidator {
	private final SupplyAudit audit;

	@Inject
	public DedicatedFreightServiceValidator(CoTransportSettings settings, FreightOrderRegistry registry, Scenario scenario) {
		if (!usesDedicatedService(settings)) { audit = SupplyAudit.none(); return; }
		TransitSchedule schedule = scenario.getTransitSchedule();
		Vehicles vehicles = scenario.getTransitVehicles();
		if (settings.activatedTransitScheduleFile() != null || settings.activatedTransitVehiclesFile() != null) {
			Scenario holder = ScenarioUtils.createScenario(ConfigUtils.createConfig());
			if (settings.activatedTransitScheduleFile() != null) {
				new TransitScheduleReader(holder).readFile(settings.activatedTransitScheduleFile().toString());
				schedule = holder.getTransitSchedule();
			}
			if (settings.activatedTransitVehiclesFile() != null) {
				new MatsimVehicleReader(holder.getTransitVehicles()).readFile(settings.activatedTransitVehiclesFile().toString());
				vehicles = holder.getTransitVehicles();
			}
		}
		audit = validate(settings, registry, scenario.getPopulation(), schedule, vehicles);
	}

	public SupplyAudit audit() { return audit; }

	/** In-memory validation only: no simulation, rerouting, predictor or capacity reservation. */
	public static SupplyAudit validate(CoTransportSettings settings, FreightOrderRegistry registry,
			Population population, TransitSchedule schedule, Vehicles vehicles) {
		if (!usesDedicatedService(settings)) return SupplyAudit.none();
		if (settings.freightBoardingMode() == CoTransportSettings.FreightBoardingMode.WHOLE_ORDER
				&& !settings.hasFreightServiceWindow())
			throw new IllegalStateException("WHOLE_ORDER dedicated service requires an explicit freightServiceWindow");
		Id<TransitLine> lineId = Id.create(settings.dedicatedFreightLineId(), TransitLine.class);
		TransitLine line = schedule.getTransitLines().get(lineId);
		if (line == null || line.getRoutes().isEmpty())
			throw new IllegalStateException("Dedicated freight line is missing or has no routes: " + lineId);
		Map<Id<TransitRoute>, Integer> maximumRouteCapacity = new LinkedHashMap<>();
		Set<Id<Vehicle>> dedicatedVehicleIds = new HashSet<>();
		int departures = 0;
		int minimumCapacity = Integer.MAX_VALUE;
		int maximumCapacity = 0;
		for (TransitRoute route : line.getRoutes().values()) {
			if (route.getRoute() == null || route.getStops().size() < 2 || route.getDepartures().isEmpty())
				throw new IllegalStateException("Dedicated route requires a network route, at least two stops and departures: " + route.getId());
			for (TransitRouteStop stop : route.getStops()) {
				if (!schedule.getFacilities().containsKey(stop.getStopFacility().getId()))
					throw new IllegalStateException("Dedicated route stop is absent from schedule facilities: " + stop.getStopFacility().getId());
			}
			int routeMaximumCapacity = 0;
			for (var departure : route.getDepartures().values()) {
				if (!Double.isFinite(departure.getDepartureTime()) || departure.getDepartureTime() < 0)
					throw new IllegalStateException("Invalid dedicated departure clock: " + departure.getId());
				Vehicle vehicle = vehicles.getVehicles().get(departure.getVehicleId());
				if (vehicle == null)
					throw new IllegalStateException("Dedicated departure references missing vehicle: " + departure.getVehicleId());
				var capacity = vehicle.getType().getCapacity();
				Integer seats = capacity == null ? null : capacity.getSeats();
				Integer standing = capacity == null ? null : capacity.getStandingRoom();
				if (seats == null || seats < 0 || standing != null && standing < 0)
					throw new IllegalStateException("Invalid dedicated native capacity: " + vehicle.getId());
				int nativeCapacity = Math.addExact(seats, standing == null ? 0 : standing);
				if (nativeCapacity <= 0)
					throw new IllegalStateException("Dedicated vehicle has no native capacity: " + vehicle.getId());
				minimumCapacity = Math.min(minimumCapacity, nativeCapacity);
				maximumCapacity = Math.max(maximumCapacity, nativeCapacity);
				routeMaximumCapacity = Math.max(routeMaximumCapacity, nativeCapacity);
				dedicatedVehicleIds.add(vehicle.getId());
				departures++;
			}
			maximumRouteCapacity.put(route.getId(), routeMaximumCapacity);
		}
		Set<String> oversizedOrders = new HashSet<>();
		for (Person person : population.getPersons().values()) {
			boolean freight = registry.isFreightPerson(person.getId());
			if (freight && person.getSelectedPlan() == null)
				throw new IllegalStateException("Freight unit has no selected plan: " + person.getId());
			for (var plan : person.getPlans()) {
				for (var element : plan.getPlanElements()) {
					if (!(element instanceof Leg leg)) continue;
					if (!freight && "freight_pt".equals(leg.getMode()))
						throw new IllegalStateException("Passenger plan uses freight-only routing mode: " + person.getId());
					if (!(leg.getRoute() instanceof TransitPassengerRoute pt)) continue;
					validatePassengerRoute(person.getId(), freight, pt, lineId, line, registry, maximumRouteCapacity, oversizedOrders);
				}
			}
		}
		return new SupplyAudit(lineId.toString(), line.getRoutes().size(), departures, dedicatedVehicleIds.size(),
				minimumCapacity, maximumCapacity, oversizedOrders.stream().sorted().toList());
	}

	private static void validatePassengerRoute(Id<Person> personId, boolean freight, TransitPassengerRoute pt,
			Id<TransitLine> lineId, TransitLine line, FreightOrderRegistry registry,
			Map<Id<TransitRoute>, Integer> maximumRouteCapacity, Set<String> oversizedOrders) {
		if (freight != lineId.equals(pt.getLineId()))
			throw new IllegalStateException("Dedicated input route violates two-sided service isolation: person="
					+ personId + "; freight=" + freight + "; line=" + pt.getLineId());
		if (freight) {
			TransitRoute route = line.getRoutes().get(pt.getRouteId());
			if (route == null) throw new IllegalStateException("Freight plan references a missing dedicated route: " + pt.getRouteId());
			boolean validSegment = false;
			for (int access = 0; access < route.getStops().size() - 1; access++) {
				TransitRouteStop from = route.getStops().get(access);
				if (!from.isAllowBoarding() || !from.getStopFacility().getId().equals(pt.getAccessStopId())) continue;
				for (int egress = access + 1; egress < route.getStops().size(); egress++) {
					TransitRouteStop to = route.getStops().get(egress);
					if (to.isAllowAlighting() && to.getStopFacility().getId().equals(pt.getEgressStopId())) validSegment = true;
				}
			}
			if (!validSegment)
				throw new IllegalStateException("Freight plan has no ordered, boardable/alightable dedicated segment: " + personId);
			FreightOrder order = registry.orderForPerson(personId);
			if (order.capacityUnits() > maximumRouteCapacity.get(route.getId())) oversizedOrders.add(order.orderId());
		}
		if (pt.getChainedRoute() != null)
			validatePassengerRoute(personId, freight, pt.getChainedRoute(), lineId, line, registry, maximumRouteCapacity, oversizedOrders);
	}

	private static boolean usesDedicatedService(CoTransportSettings settings) {
		return settings.dedicatedFreightLineId() != null && !settings.dedicatedFreightLineId().isBlank();
	}

	public record SupplyAudit(String lineId, int routes, int departures, int distinctVehicles,
			int minimumNativeCapacity, int maximumNativeCapacity, List<String> structurallyOversizedOrders) {
		public SupplyAudit { structurallyOversizedOrders = List.copyOf(structurallyOversizedOrders); }
		private static SupplyAudit none() { return new SupplyAudit("", 0, 0, 0, 0, 0, List.of()); }
	}
}
