package org.matsim.contrib.cotransport;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Route;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;
import org.matsim.pt.routes.TransitPassengerRoute;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Set;

public final class FreightOrderRegistry {
	private final CoTransportSettings settings;
	private final FreightUnitizer unitizer;
	private final Map<String, FreightOrder> ordersById = new LinkedHashMap<>();
	private final Map<Id<Person>, String> personIdToOrderId = new LinkedHashMap<>();
	private final Map<String, List<Id<Person>>> unitIdsByOrder = new LinkedHashMap<>();

	@Inject
	public FreightOrderRegistry(CoTransportSettings settings, Scenario scenario) {
		this(settings);
		validateWholeOrderPlans(scenario.getPopulation());
	}

	/** Ledger-only construction is retained for read-only audits without a loaded Scenario. */
	public FreightOrderRegistry(CoTransportSettings settings) {
		this(settings, FreightUnitizer.proportional(settings.freightEquivalentPassengersPerM3(), settings.freightShadowToken()));
	}

	/** Validate configured whole-order identities before a phase controller removes dormant units. */
	public static FreightOrderRegistry fromScenario(CoTransportSettings settings, Scenario scenario) {
		return new FreightOrderRegistry(settings, scenario);
	}

	public FreightOrderRegistry(CoTransportSettings settings, FreightUnitizer unitizer) {
		this.settings = settings;
		this.unitizer = java.util.Objects.requireNonNull(unitizer);
		loadOrders();
	}

	public boolean isFreightPerson(Id<Person> personId) {
		return personIdToOrderId.containsKey(personId);
	}

	public boolean isPrimaryFreightPerson(Id<Person> personId) {
		String orderId = personIdToOrderId.get(personId);
		return orderId != null && ordersById.get(orderId).primaryPersonId().equals(personId);
	}

	public FreightOrder orderForPerson(Id<Person> personId) {
		String orderId = personIdToOrderId.get(personId);
		return orderId == null ? null : ordersById.get(orderId);
	}

	public Iterable<FreightOrder> orders() {
		return List.copyOf(ordersById.values());
	}

	public Set<Id<Person>> unitIds() {
		return Collections.unmodifiableSet(personIdToOrderId.keySet());
	}

	public List<Id<Person>> unitIdsForOrder(String orderId) {
		if (!ordersById.containsKey(orderId)) throw new IllegalArgumentException("Unknown freight order: " + orderId);
		return unitIdsByOrder.get(orderId);
	}

	/** Validate the prebuilt population before the phase controller temporarily removes inactive freight. */
	public PopulationReconciliation validatePopulation(Population population) {
		List<String> missing = personIdToOrderId.keySet().stream()
				.filter(id -> !population.getPersons().containsKey(id)).map(Id::toString).toList();
		List<String> unexpected = population.getPersons().values().stream()
				.filter(this::hasFreightMarker).filter(person -> !isFreightPerson(person.getId()))
				.map(person -> person.getId().toString()).toList();
		if (!missing.isEmpty() || !unexpected.isEmpty()) {
			throw new IllegalStateException("Freight population/order mismatch: missing units=" + missing
					+ "; unregistered freight persons=" + unexpected);
		}
		return new PopulationReconciliation(ordersById.size(), personIdToOrderId.size(), personIdToOrderId.size());
	}

	/** Separate from ID conservation: require explicit freight grouping and one prebuilt selected plan. */
	public void validateFixedPlans(Population population) {
		// Inspect the raw attribute before validatePopulation calls the native String-casting getter.
		// Missing persons remain the responsibility of the unchanged ID-conservation check below.
		for (Id<Person> id : personIdToOrderId.keySet()) {
			Person person = population.getPersons().get(id);
			if (person != null && !"freight".equals(person.getAttributes().getAttribute("subpopulation"))) {
				throw new IllegalStateException("Freight unit " + id + " must have String subpopulation='freight'");
			}
		}
		validatePopulation(population);
		for (Id<Person> id : personIdToOrderId.keySet()) {
			Person person = population.getPersons().get(id);
			if (person.getPlans().size() != 1 || person.getSelectedPlan() == null
					|| person.getSelectedPlan().getPlanElements().isEmpty()) {
				throw new IllegalStateException("Freight unit must have exactly one prebuilt selected fixed plan: " + id);
			}
		}
	}

	/**
	 * Whole-order agents are capacity shadows of one service chain, not independently routed
	 * consignments. This read-only startup guard checks their full plan semantics, excluding
	 * plan scores and arbitrary audit attributes. UNIT_SPLIT preserves the old validation path.
	 * Chained PT continuation is not yet supported: the native relocation path transfers
	 * individual agents. Whole-order continuation requires a future atomic relocation extension.
	 */
	public void validateWholeOrderPlans(Population population) {
		if (settings.freightBoardingMode() != CoTransportSettings.FreightBoardingMode.WHOLE_ORDER) return;
		validateFixedPlans(population);
		for (FreightOrder order : ordersById.values()) {
			List<Id<Person>> units = unitIdsForOrder(order.orderId());
			List<Object> reference = null;
			for (Id<Person> id : units) {
				Plan plan = population.getPersons().get(id).getSelectedPlan();
				if (!(plan.getPlanElements().getFirst() instanceof Activity first)
						|| !first.getEndTime().isDefined() || first.getEndTime().seconds() != order.releaseTime()) {
					throw new IllegalStateException("WHOLE_ORDER release activity must match registered release_time: " + id);
				}
				if (!(plan.getPlanElements().getLast() instanceof Activity last)
						|| !last.getType().startsWith(settings.freightActivityPrefix())) {
					throw new IllegalStateException("WHOLE_ORDER requires a final freight delivery activity: " + id);
				}
				List<Object> signature = serviceChainSignature(plan, id);
				if (reference == null) reference = signature;
				else if (!reference.equals(signature)) {
					throw new IllegalStateException("WHOLE_ORDER units disagree on release, preparation/access, PT line/route/stops, "
							+ "or final delivery chain: order=" + order.orderId() + "; unit=" + id);
				}
			}
		}
	}

	private static List<Object> serviceChainSignature(Plan plan, Id<Person> id) {
		List<Object> signature = new ArrayList<>();
		int transitLegs = 0;
		for (var element : plan.getPlanElements()) {
			if (element instanceof Activity activity) {
				signature.add(new ActivitySignature(activity.getType(), string(activity.getLinkId()),
						string(activity.getFacilityId()), string(activity.getCoord()), activity.getEndTime().toString(),
						activity.getMaximumDuration().toString()));
			} else if (element instanceof Leg leg) {
				Route route = leg.getRoute();
				if (route == null) throw new IllegalStateException("WHOLE_ORDER requires every leg to have a prebuilt route: " + id);
				if ("pt".equals(leg.getMode()) || "freight_pt".equals(leg.getMode())) {
					if (!(route instanceof TransitPassengerRoute)) {
						throw new IllegalStateException("WHOLE_ORDER requires explicit PT line, route and access/egress stops: " + id);
					}
					transitLegs++;
				}
				signature.add(new LegSignature(leg.getMode(), leg.getRoutingMode(), leg.getDepartureTime().toString(),
						leg.getTravelTime().toString(), routeSignature(route, id)));
			} else throw new IllegalStateException("Unsupported WHOLE_ORDER plan element: " + id);
		}
		if (transitLegs == 0) throw new IllegalStateException("WHOLE_ORDER service chain has no explicit PT leg: " + id);
		return List.copyOf(signature);
	}

	private static RouteSignature routeSignature(Route route, Id<Person> id) {
		TransitSignature transit = null;
		if (route instanceof TransitPassengerRoute pt) {
			if (pt.getChainedRoute() != null) {
				throw new IllegalStateException("WHOLE_ORDER does not support chained PT continuation: " + id
						+ "; native relocation transfers individual units. An atomic whole-order continuation extension is required.");
			}
			if (pt.getLineId() == null || pt.getRouteId() == null || pt.getAccessStopId() == null || pt.getEgressStopId() == null) {
				throw new IllegalStateException("Incomplete WHOLE_ORDER PT route metadata: " + id);
			}
			transit = new TransitSignature(pt.getLineId().toString(), pt.getRouteId().toString(),
					pt.getAccessStopId().toString(), pt.getEgressStopId().toString());
		}
		return new RouteSignature(route.getRouteType(), string(route.getStartLinkId()), string(route.getEndLinkId()),
				route.getTravelTime().toString(), route.getDistance(), route.getRouteDescription(), transit);
	}

	private static String string(Object value) { return value == null ? null : value.toString(); }
	private record ActivitySignature(String type, String link, String facility, String coordinate, String end, String duration) { }
	private record LegSignature(String mode, String routingMode, String departure, String travel, RouteSignature route) { }
	private record RouteSignature(String type, String startLink, String endLink, String travel, double distance,
			String description, TransitSignature transit) { }
	private record TransitSignature(String line, String route, String access, String egress) { }

	private boolean hasFreightMarker(Person person) {
		return "freight".equals(PopulationUtils.getSubpopulation(person))
				|| person.getPlans().stream().flatMap(plan -> plan.getPlanElements().stream())
				.anyMatch(element -> element instanceof Activity activity
						&& activity.getType().startsWith(settings.freightActivityPrefix()));
	}

	public record PopulationReconciliation(int orders, int expectedUnits, int prebuiltUnits) { }

	public String normalizeOrderId(Id<Person> personId) {
		return personIdToOrderId.get(personId);
	}

	private void loadOrders() {
		if (!Files.isRegularFile(settings.freightOrdersCsv())) {
			throw new IllegalStateException("Freight orders CSV not found: " + settings.freightOrdersCsv());
		}
		try (BufferedReader reader = Files.newBufferedReader(settings.freightOrdersCsv())) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				throw new IllegalArgumentException("Freight orders CSV has no header: " + settings.freightOrdersCsv());
			}
			List<String> headers = parseCsvLine(headerLine);
			Map<String, Integer> index = new LinkedHashMap<>();
			for (int i = 0; i < headers.size(); i++) {
				if (index.putIfAbsent(headers.get(i), i) != null) {
					throw new IllegalArgumentException("Duplicate freight CSV header: " + headers.get(i));
				}
			}
			for (String required : List.of("order_id", "release_time", "deadline_time", "volume_m3", "mass_kg", "service_class", "priority")) {
				if (!index.containsKey(required)) throw new IllegalArgumentException("Missing freight CSV header: " + required);
			}
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				if (line.isBlank()) {
					continue;
				}
				List<String> row = parseCsvLine(line);
				String orderId = get(row, index, "order_id");
				double release = parseTime(get(row, index, "release_time"));
				double deadline = parseTime(get(row, index, "deadline_time"));
				double volume = Double.parseDouble(get(row, index, "volume_m3"));
				double mass = Double.parseDouble(get(row, index, "mass_kg"));
				String serviceClass = get(row, index, "service_class");
				int priority = Integer.parseInt(get(row, index, "priority"));
				Integer explicitUnits = null;
				if (index.containsKey("capacity_units") && !get(row, index, "capacity_units").isBlank()) {
					explicitUnits = Integer.parseInt(get(row, index, "capacity_units"));
				}
				int capacityUnits = unitizer.capacityUnits(volume, explicitUnits);
				FreightOrder order = new FreightOrder(orderId, release, deadline, volume, mass, serviceClass, priority, capacityUnits);
				if (ordersById.putIfAbsent(orderId, order) != null) {
					throw new IllegalStateException("Duplicate freight order id in "
							+ settings.freightOrdersCsv() + ": " + orderId);
				}
				List<String> unitIds = unitizer.unitIds(orderId, capacityUnits);
				if (unitIds.size() != capacityUnits) {
					throw new IllegalStateException("Freight unitizer did not conserve order " + orderId);
				}
				if (!orderId.equals(unitIds.getFirst())) {
					throw new IllegalStateException("Freight unitizer must preserve the primary order ID: " + orderId);
				}
				for (String unitId : unitIds) {
					if (unitId == null || unitId.isBlank()) throw new IllegalStateException("Freight unitizer returned a blank unit ID");
					Id<Person> personId = Id.createPersonId(unitId);
					String previousOrder = personIdToOrderId.putIfAbsent(personId, orderId);
					if (previousOrder != null) {
						throw new IllegalStateException("Duplicate freight unit ID " + personId
								+ " in orders " + previousOrder + " and " + orderId);
					}
				}
				unitIdsByOrder.put(orderId, unitIds.stream().map(Id::createPersonId).toList());
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not read freight orders from " + settings.freightOrdersCsv(), e);
		}
	}

	private static String get(List<String> row, Map<String, Integer> index, String key) {
		Integer i = index.get(key);
		if (i == null || i >= row.size()) {
			return "";
		}
		return row.get(i);
	}

	private static double parseTime(String value) {
		String[] parts = value.split(":");
		if (parts.length != 3) {
			throw new IllegalArgumentException("Expected HH:mm:ss time, got: " + value);
		}
		int hours = Integer.parseInt(parts[0]);
		int minutes = Integer.parseInt(parts[1]);
		double seconds = Double.parseDouble(parts[2]);
		if (hours < 0 || minutes < 0 || minutes >= 60 || !Double.isFinite(seconds) || seconds < 0 || seconds >= 60) {
			throw new IllegalArgumentException("Invalid HH:mm:ss freight time: " + value);
		}
		return hours * 3600.0 + minutes * 60.0 + seconds;
	}

	private static List<String> parseCsvLine(String line) {
		List<String> values = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '"') {
				if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					current.append('"');
					i++;
				} else {
					quoted = !quoted;
				}
			} else if (c == ',' && !quoted) {
				values.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		if (quoted) throw new IllegalArgumentException("Unclosed quoted freight CSV field");
		values.add(current.toString());
		return values;
	}
}
