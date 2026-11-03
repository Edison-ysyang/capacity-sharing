package org.matsim.run.ismt.dynamic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.mobsim.framework.PlanAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.pt.PtConstants;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.routes.ExperimentalTransitRoute;
import org.matsim.pt.routes.TransitPassengerRoute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.matsim.run.ismt.dynamic.CausalMixedServiceForecast.AccessEgressEstimate;
import static org.matsim.run.ismt.dynamic.ClosedPassengerArrivalForecast.Od;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.ControlPoint;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.VehicleConnection;

/**
 * Privileged, read-only assembly of the fixed-baseline M/T input domain. Capture
 * while the COMPLETE prebuilt population is present, before Startup removes
 * inactive freight. It does not route plans: native Controller Startup precedes
 * PrepareForSim, and each BeforeMobsim precedes PrepareForMobsim. Consequently call
 * forIteration from the QSim preparation-start input factory, AFTER that native
 * per-iteration routing. Raw unrouted freight is allowed at capture, never in an
 * active day's direct-PT registration.
 *
 * <p>This object and Day are assembly handles, not policy observations. Never pass
 * them, their full contracts, or the registry to a policy. Only the detached supply,
 * static OD durations, admission values and closed observation snapshots belong in
 * the predictor. Full future order identities remain private until used to build
 * the observation component's assembly-only contracts. No person, plan, score,
 * timetable, config, RNG or lifecycle is changed here.
 *
 * <p>Prepared checking reads actual PlanAgent current plans. It neither invokes nor
 * certifies the preparation barrier; the caller must invoke it at Session's real
 * afterPrepared callback and prevent concurrent mutations. This is not a runtime
 * producer/factory seal, actual door/teleportation timing model or safety approval.
 */
public final class DynamicMixedScenarioInputs {
    public record Admission(double freightShareCap, double passengerLoadGate) {
        public Admission { fraction(freightShareCap); fraction(passengerLoadGate); }
    }
    public record InputAudit(int iteration, boolean freightActive, int passengers, int freightUnits,
                             int registeredOrders, int staticFreightOds, int baselineTrips,
                             String domain, String accessEgressMeaning) { }
    public record PreparedAudit(int iteration, int verifiedFreightUnits,
                                PreparedQSimInputAudit.Report populationAndStructure) {
        public PreparedAudit { Objects.requireNonNull(populationAndStructure); }
        public boolean preparationBarrierCertified() { return false; }
        public boolean actualTimingCertified() { return false; }
    }

    private record Order(String id, double release, double deadline, List<String> units) { }
    private record Initial(String releaseType, double releaseTime, String deliveryType) { }
    /** Only fields used by this bounded domain are compared; scores/PT boarding forecasts are not. */
    private record LegValue(String mode, String routeClass, String startLink, String endLink, double time) { }
    private record DirectPlan(String person, Initial activities, String ptMode, String ptRouteClass,
                              String line, String route, Od od, List<LegValue> access, List<LegValue> egress,
                              AccessEgressEstimate estimate) { }
    private record ConfigIdentity(Set<String> transitModes, Set<String> networkModes, String activityInterpretation,
                                  String tripHandling, boolean teleportTimeCheck, boolean delayedZeroTeleport,
                                  double startSeconds) { }

    public static final class Day {
        private final DynamicMixedScenarioInputs owner;
        private final int iteration;
        private final DynamicMixedQSimModule.Inputs inputs;
        private final Map<Od, AccessEgressEstimate> accessEgress;
        private final Map<String, DirectPlan> directPlans;
        private final InputAudit audit;

        private Day(DynamicMixedScenarioInputs owner, int iteration, DynamicMixedQSimModule.Inputs inputs,
                    Map<Od, AccessEgressEstimate> accessEgress, Map<String, DirectPlan> directPlans, InputAudit audit) {
            this.owner = owner; this.iteration = iteration; this.inputs = inputs;
            this.accessEgress = immutable(accessEgress); this.directPlans = immutable(directPlans); this.audit = audit;
        }
        public int iteration() { return iteration; }
        public DynamicMixedQSimModule.Inputs qsimInputs() { return inputs; }
        public SingleLineForecastSupply supply() { return owner.supply; }
        public Map<Od, AccessEgressEstimate> accessEgress() { return accessEgress; }
        public Admission admission() { return owner.admission; }
        public InputAudit inputAudit() { return audit; }

        /** Call only at a real, externally established post-prepare barrier. No lifecycle is invoked. */
        public PreparedAudit verifyPrepared(QSim qsim) {
            owner.checkThread(); Objects.requireNonNull(qsim);
            require(owner.latest == this, "Stale day cannot verify a later preparation");
            require(qsim.getScenario() == owner.scenario, "Prepared QSim belongs to another Scenario");
            owner.validateStatic();
            PreparedQSimInputAudit.Report report = PreparedQSimInputAudit.inspect(qsim, inputs.expectedPopulationIds());
            report.requireSupportedInput();
            for (var entry : directPlans.entrySet()) {
                var agent = qsim.getAgents().get(Id.createPersonId(entry.getKey()));
                require(agent instanceof PlanAgent, "Prepared freight is not a PlanAgent");
                DirectPlan actual = owner.directPlan(((PlanAgent) agent).getCurrentPlan(), entry.getKey(), owner.orderByUnit.get(entry.getKey()));
                require(actual.equals(entry.getValue()), "Actual prepared freight plan differs from registered direct-PT input: " + entry.getKey());
            }
            return new PreparedAudit(iteration, directPlans.size(), report);
        }
    }

    private final Scenario scenario;
    private final FreightOrderRegistry registry;
    private final CoTransportSettings settings;
    private final DynamicMixedDesign design;
    private final SingleLineForecastSupply supply;
    private final Admission admission;
    private final org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SharedBoardingWindow sharedWindow;
    private final ConfigIdentity configIdentity;
    private final List<ControlPoint> controlPoints;
    private final List<VehicleConnection> connections;
    private final Set<String> passengerIds, unitIds;
    private final List<Order> orders;
    private final Map<String, Order> orderByUnit;
    private final Map<String, Initial> initialActivities;
    private final Thread thread = Thread.currentThread();
    private Day latest;
    private Map<String, DirectPlan> firstActivePlans;

    private DynamicMixedScenarioInputs(Scenario scenario, FreightOrderRegistry registry, CoTransportSettings settings,
                                      DynamicMixedDesign design, List<ControlPoint> points, List<VehicleConnection> connections) {
        this.scenario = Objects.requireNonNull(scenario); this.registry = Objects.requireNonNull(registry);
        this.settings = Objects.requireNonNull(settings); this.design = Objects.requireNonNull(design);
        requireSupportedBoardingMode(settings, design);
        Object explicitWindow = scenario.getScenarioElement(org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT);
        require(explicitWindow == null || explicitWindow instanceof org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SharedBoardingWindow,
                "Invalid shared-window assembly element");
        sharedWindow = (org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SharedBoardingWindow) explicitWindow;
        require(design.arm() == DynamicMixedDesign.Arm.MIXED || design.arm() == DynamicMixedDesign.Arm.DYNAMIC_MIXED,
                "Only common fixed-baseline M/T assembly is supported");
        require(settings.armMode() == CoTransportSettings.ArmMode.ITERATION_WINDOW
                        && settings.activationIteration() == design.protocol().activationIteration()
                        && settings.deactivationIteration() == -1, "Freight phase settings disagree with design");
        require(settings.dedicatedFreightLineId() == null || settings.dedicatedFreightLineId().isBlank(), "Dedicated admission is outside this domain");
        require(settings.freightHandlingDwellSeconds() == 0, "This forecast domain requires the registered zero extra handling dwell");
        text(settings.freightActivityPrefix());
        admission = new Admission(settings.freightShareCap(), settings.passengerLoadGate());
        supply = SingleLineForecastSupply.from(scenario); configIdentity = configIdentity(scenario);
        this.controlPoints = List.copyOf(points); this.connections = List.copyOf(connections);
        new DispatchConstraintChecker(supply.trips().values().stream().map(SingleLineForecastSupply.Trip::scheduled).toList(),
                this.controlPoints, this.connections); // validate registration references, not baseline feasibility
        registry.validateFixedPlans(scenario.getPopulation());
        registry.validateWholeOrderPlans(scenario.getPopulation());
        var ordered = new TreeMap<String, Order>(); var byUnit = new TreeMap<String, Order>();
        var initial = new TreeMap<String, Initial>();
        for (var order : registry.orders()) {
            List<String> units = registry.unitIdsForOrder(order.orderId()).stream().map(Object::toString).sorted().toList();
            require(units.size() == order.capacityUnits() && units.contains(order.primaryPersonId().toString()), "Order/unit count mismatch");
            var value = new Order(order.orderId(), order.releaseTime(), order.deadlineTime(), units);
            require(ordered.putIfAbsent(value.id(), value) == null, "Repeated order identity");
            for (String unit : units) {
                require(byUnit.putIfAbsent(unit, value) == null, "Repeated freight unit");
                var person = scenario.getPopulation().getPersons().get(Id.createPersonId(unit));
                initial.put(unit, initial(person.getSelectedPlan(), unit, value));
            }
        }
        require(!ordered.isEmpty(), "M/T assembly requires registered freight orders");
        orders = List.copyOf(ordered.values()); orderByUnit = immutable(byUnit); initialActivities = immutable(initial);
        unitIds = orderedSet(byUnit.keySet());
        require(unitIds.equals(registry.unitIds().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet())), "Registry unit inventory mismatch");
        var passengers = new TreeSet<>(populationIds()); passengers.removeAll(unitIds); passengerIds = orderedSet(passengers);
    }

    public static DynamicMixedScenarioInputs capture(Scenario scenario, FreightOrderRegistry registry,
            CoTransportSettings settings, DynamicMixedDesign design, List<ControlPoint> controlPoints,
            List<VehicleConnection> connections) {
        return new DynamicMixedScenarioInputs(scenario, registry, settings, design, controlPoints, connections);
    }

    /** Pure configuration guard: no predictor, Scenario mutation or simulation is invoked. */
    public static void requireSupportedBoardingMode(CoTransportSettings settings, DynamicMixedDesign design) {
        Objects.requireNonNull(settings); Objects.requireNonNull(design);
        if (design.arm() == DynamicMixedDesign.Arm.DYNAMIC_MIXED
                && settings.freightBoardingMode() == CoTransportSettings.FreightBoardingMode.WHOLE_ORDER) {
            throw new IllegalStateException("Dynamic T cannot run with freightBoardingMode=WHOLE_ORDER: "
                    + "the current MixedCapacityProjection predictor only allocates independent capacity units. "
                    + "A compatible whole-order predictor and registered experiment are required. "
                    + "Explicit UNIT_SPLIT is available only for the historical divisible-order mechanism.");
        }
    }

    /** After native per-iteration routing, before QSim prepares agents. Failure never publishes a partial Day. */
    public Day forIteration(int iteration) {
        checkThread(); var phase = design.phaseAt(iteration);
        require(latest == null || iteration >= latest.iteration, "Regressed scenario-input iteration");
        require(settings.isActiveAt(iteration) == phase.freightTreatmentActive(), "Freight phase disagrees with settings");
        validateStatic();
        var expected = new TreeSet<>(passengerIds);
        if (phase.freightTreatmentActive()) expected.addAll(unitIds);
        require(populationIds().equals(expected), "Installed Scenario population differs from explicit phase expectation");
        var direct = new TreeMap<String, DirectPlan>();
        var estimates = new TreeMap<Od, AccessEgressEstimate>(Comparator.comparing(Od::stopId).thenComparing(Od::destinationStopId));
        var release = new ArrayList<KnownFreightReleaseState.Contract>();
        var journeys = new ArrayList<KnownFreightJourneyState.Contract>();
        if (phase.freightTreatmentActive()) {
            registry.validateFixedPlans(scenario.getPopulation());
            for (Order order : orders) {
                DirectPlan reference = null;
                for (String unit : order.units()) {
                    var person = scenario.getPopulation().getPersons().get(Id.createPersonId(unit));
                    DirectPlan plan = directPlan(person.getSelectedPlan(), unit, order);
                    require(plan.activities().equals(initialActivities.get(unit)), "Freight initial/delivery activities changed since capture");
                    direct.put(unit, plan);
                    if (reference == null) reference = plan;
                    else require(reference.od().equals(plan.od()) && reference.activities().releaseType().equals(plan.activities().releaseType()),
                            "One order's units disagree on exact OD or release activity");
                    var previous = estimates.putIfAbsent(plan.od(), plan.estimate());
                    require(previous == null || previous.equals(plan.estimate()), "Conflicting static access/egress estimates for one exact OD");
                }
                release.add(new KnownFreightReleaseState.Contract(order.id(), reference.od().stopId(), reference.od().destinationStopId(),
                        order.release(), order.deadline(), order.units(), reference.activities().releaseType()));
                journeys.add(new KnownFreightJourneyState.Contract(order.id(), order.units(), settings.freightActivityPrefix()));
            }
            if (firstActivePlans != null) require(firstActivePlans.equals(direct), "Fixed freight routing changed between active days");
        }
        if (latest != null && latest.iteration == iteration) {
            require(latest.directPlans.equals(direct), "Repeated day assembly observed changed freight plans");
            return latest;
        }
        var trips = supply.trips().values().stream().map(SingleLineForecastSupply.Trip::scheduled).toList();
        var services = supply.trips().values().stream().map(trip -> new ObservedTransitServiceState.Service(
                trip.scheduled().key(), trip.scheduled().vehicleId(), trip.calls().stream().map(SingleLineForecastSupply.Call::stopId).toList())).toList();
        var input = new DynamicMixedQSimModule.Inputs(trips, services, release, passengerIds, expected,
                controlPoints, connections, configIdentity.startSeconds(), journeys, sharedWindow == null ? null
                : new RollingDepartureCandidates.AdjustmentWindow(sharedWindow.startInclusiveSeconds(), sharedWindow.endExclusiveSeconds()));
        Day staged = new Day(this, iteration, input, estimates, direct, new InputAudit(iteration, phase.freightTreatmentActive(),
                passengerIds.size(), direct.size(), release.size(), estimates.size(), trips.size(),
                "fixed-single-line-direct-PT-freight-with-optional-teleported-walk",
                "published-route-travel-time-preferred-over-leg;zero-duration-PT-stages;not-actual-teleportation-timing"));
        if (phase.freightTreatmentActive() && firstActivePlans == null) firstActivePlans = immutable(direct);
        latest = staged; return staged;
    }

    private Initial initial(Plan plan, String unit, Order order) {
        require(plan != null && plan.getPerson() != null && plan.getPerson().getId().toString().equals(unit), "Freight plan owner mismatch");
        require("freight".equals(plan.getPerson().getAttributes().getAttribute("subpopulation")), "Freight plan owner lost its subpopulation");
        var elements = plan.getPlanElements();
        require(elements.size() >= 3 && elements.getFirst() instanceof Activity && elements.getLast() instanceof Activity,
                "Freight requires explicit initial and terminal activities");
        Activity origin = (Activity) elements.getFirst(), destination = (Activity) elements.getLast();
        text(origin.getType()); text(destination.getType());
        require(!origin.getType().startsWith(settings.freightActivityPrefix())
                        && destination.getType().startsWith(settings.freightActivityPrefix()), "Invalid initial/delivery activity-prefix contract");
        var releaseTime = TimeInterpretation.create(scenario.getConfig()).decideOnActivityEndTime(origin, configIdentity.startSeconds());
        require(releaseTime.isDefined(), "Undefined initial freight release time");
        double at = releaseTime.seconds();
        time(at); require(at == order.release(), "Initial planned release differs from registry order release");
        return new Initial(origin.getType(), at, destination.getType());
    }

    private DirectPlan directPlan(Plan plan, String unit, Order order) {
        Initial activities = initial(plan, unit, order); var elements = plan.getPlanElements();
        require(elements.size() % 2 == 1, "Plan must alternate activity and leg");
        int ptIndex = -1; TransitPassengerRoute pt = null; Leg ptLeg = null;
        for (int index = 0; index < elements.size(); index++) {
            if (index % 2 == 0) {
                require(elements.get(index) instanceof Activity, "Plan element is not an activity");
                if (index > 0 && index < elements.size() - 1) {
                    Activity stage = (Activity) elements.get(index);
                    require(PtConstants.TRANSIT_ACTIVITY_TYPE.equals(stage.getType()) && stage.getEndTime().isUndefined()
                                    && stage.getMaximumDuration().isDefined() && stage.getMaximumDuration().seconds() == 0,
                            "Only explicitly zero-duration PT interaction stages are supported");
                }
            } else {
                require(elements.get(index) instanceof Leg, "Plan element is not a leg");
                Leg leg = (Leg) elements.get(index);
                boolean transitMode = configIdentity.transitModes().contains(leg.getMode());
                if (transitMode || leg.getRoute() instanceof TransitPassengerRoute) {
                    require(transitMode && pt == null && leg.getRoute() instanceof TransitPassengerRoute, "Freight requires exactly one typed PT leg, without transfer");
                    pt = (TransitPassengerRoute) leg.getRoute(); ptLeg = leg; ptIndex = index;
                    require(pt.getClass() == DefaultTransitPassengerRoute.class || pt.getClass() == ExperimentalTransitRoute.class,
                            "Unknown typed PT route implementation");
                    require(pt.getChainedRoute() == null, "Through-chain freight is unsupported");
                } else require(TransportMode.walk.equals(leg.getMode()) && !configIdentity.networkModes().contains(leg.getMode()),
                        "Only teleported walk access/egress is supported");
            }
        }
        require(pt != null, "No direct typed PT leg in freight plan");
        String line = id(pt.getLineId()), route = id(pt.getRouteId());
        Od od = new Od(id(pt.getAccessStopId()), id(pt.getEgressStopId()));
        var routeSupply = supply.trips().values().stream().filter(trip -> trip.scheduled().key().line().equals(line)
                && trip.scheduled().key().route().equals(route)).findFirst();
        require(routeSupply.isPresent(), "Freight references an unregistered single-line route");
        int board = -1, alight = -1;
        var calls = routeSupply.orElseThrow().calls();
        for (int i = 0; i < calls.size(); i++) {
            var call = calls.get(i);
            if (call.stopId().equals(od.stopId()) && call.allowBoarding()) board = i;
            if (call.stopId().equals(od.destinationStopId()) && call.allowAlighting()) alight = i;
        }
        require(board >= 0 && alight > board, "Freight exact stops are unavailable, reversed, or disallow boarding/alighting");
        var originFacility = scenario.getTransitSchedule().getFacilities().get(pt.getAccessStopId());
        var destinationFacility = scenario.getTransitSchedule().getFacilities().get(pt.getEgressStopId());
        require(originFacility != null && destinationFacility != null
                        && id(pt.getStartLinkId()).equals(id(originFacility.getLinkId()))
                        && id(pt.getEndLinkId()).equals(id(destinationFacility.getLinkId())), "PT route endpoints disagree with exact stop links");
        var access = new ArrayList<LegValue>(); var egress = new ArrayList<LegValue>();
        double accessTime = 0, egressTime = 0;
        var interpretation = TimeInterpretation.create(scenario.getConfig());
        for (int i = 1; i < elements.size(); i += 2) {
            if (i == ptIndex) continue;
            Leg leg = (Leg) elements.get(i);
            require(leg.getRoute() != null, "Missing published access/egress route");
            var plannedTime = interpretation.decideOnLegTravelTime(leg);
            require(plannedTime.isDefined(), "Undefined static access/egress duration");
            double duration = plannedTime.seconds(); time(duration);
            var value = new LegValue(leg.getMode(), leg.getRoute().getClass().getName(), id(leg.getRoute().getStartLinkId()),
                    id(leg.getRoute().getEndLinkId()), duration);
            if (i < ptIndex) { access.add(value); accessTime += duration; }
            else { egress.add(value); egressTime += duration; }
            time(accessTime); time(egressTime);
        }
        if (!access.isEmpty()) require(access.getLast().endLink().equals(id(pt.getStartLinkId())), "Access walk does not end at the PT access link");
        if (!egress.isEmpty()) require(egress.getFirst().startLink().equals(id(pt.getEndLinkId())), "Egress walk does not start at the PT egress link");
        return new DirectPlan(unit, activities, ptLeg.getMode(), pt.getClass().getName(), line, route, od,
                List.copyOf(access), List.copyOf(egress), new AccessEgressEstimate(accessTime, egressTime));
    }

    private void validateStatic() {
        require(Objects.equals(sharedWindow, scenario.getScenarioElement(org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SHARED_BOARDING_WINDOW_ELEMENT)),
                "Registered physical boarding window changed after capture");
        if (sharedWindow != null) {
            Object installed = scenario.getScenarioElement(org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.SCENARIO_ELEMENT);
            require(installed instanceof org.matsim.contrib.cotransport.CoTransportBoardingPolicy
                    && sharedWindow.equals(((org.matsim.contrib.cotransport.CoTransportBoardingPolicy) installed).sharedBoardingWindow()),
                    "The actual native boarding policy is not bound to the captured shared window");
        }
        require(configIdentity.equals(configIdentity(scenario)), "Transit/routing/time configuration changed after capture");
        var current = SingleLineForecastSupply.from(scenario);
        require(supply.lineId().equals(current.lineId()) && supply.trips().equals(current.trips())
                        && supply.boardingAcceptance() == current.boardingAcceptance()
                        && supply.qsimTimeStepSeconds() == current.qsimTimeStepSeconds(), "Static forecast supply changed after capture");
    }
    private Set<String> populationIds() {
        var ids = new TreeSet<String>();
        scenario.getPopulation().getPersons().forEach((key, person) -> {
            require(key != null && person != null && key.equals(person.getId()), "Invalid Scenario population registry entry");
            require(ids.add(id(key)), "Repeated Scenario person identity");
        });
        return ids;
    }
    private static ConfigIdentity configIdentity(Scenario scenario) {
        var config = scenario.getConfig();
        require(config.qsim().getStartTime().isDefined(), "Explicit QSim start is required");
        double start = config.qsim().getStartTime().seconds(); time(start);
        return new ConfigIdentity(Set.copyOf(config.transit().getTransitModes()), Set.copyOf(config.qsim().getMainModes()),
                config.plans().getActivityDurationInterpretation().name(), config.plans().getTripDurationHandling().name(),
                config.qsim().isUsingTravelTimeCheckInTeleportation(), config.qsim().getDelayInstantTeleportationArrivals(), start);
    }
    private void checkThread() { require(Thread.currentThread() == thread, "Wrong scenario-assembly owner thread"); }
    private static String id(Object value) { require(value != null, "Missing identity"); String result = value.toString(); text(result); return result; }
    private static void text(String value) { require(value != null && !value.isBlank(), "Blank identity"); }
    private static void time(double value) { require(Double.isFinite(value) && value >= 0, "Invalid static planned duration/time"); }
    private static void fraction(double value) { require(Double.isFinite(value) && value >= 0 && value <= 1, "Invalid admission fraction"); }
    private static <K, V> Map<K, V> immutable(Map<K, V> source) { return Collections.unmodifiableMap(new LinkedHashMap<>(source)); }
    private static Set<String> orderedSet(Set<String> source) { return Collections.unmodifiableSet(new TreeSet<>(source)); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
