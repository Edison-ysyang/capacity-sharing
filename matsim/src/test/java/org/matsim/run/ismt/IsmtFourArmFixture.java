package org.matsim.run.ismt;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.utils.objectattributes.ObjectAttributes;
import org.matsim.utils.objectattributes.ObjectAttributesXmlWriter;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tiny deterministic input generator; it never substitutes a simulation module or a scientific writer. */
final class IsmtFourArmFixture {
    static final String PASSENGER_LINE = "passenger_line";
    static final String DEDICATED_LINE = "freight_line";
    static final double PASSENGER_SELECTOR_WEIGHT = 0.8;
    static final double PASSENGER_REROUTE_WEIGHT = 0.2;
    static final double ROAD_ROUTING_RANDOMNESS = 3.0;
    static final int ROAD_THREADS = 7;
    static final String ROAD_CAR_PASSENGER = "p00";
    static final String ROAD_RIDE_PASSENGER = "p01";
    static final Set<String> ROAD_PASSENGER_IDS = Set.of(ROAD_CAR_PASSENGER, ROAD_RIDE_PASSENGER);
    static final Set<String> ROAD_COMPETING_LINKS = Set.of(
            "road_short_1", "road_short_2", "road_fast_1", "road_fast_2");
    static final List<String> FREIGHT_IDS = List.of("cargo_order", "cargo_order_cap_02", "cargo_order_cap_03");
    static final List<String> PASSENGER_IDS = java.util.stream.IntStream.range(0, 11)
            .mapToObj(i -> String.format(Locale.ROOT, "p%02d", i)).toList();
    private static final String ORDER_HEADER = "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n";

    record Fixture(Path workspace, Path root, Map<ExperimentSpec.Arm, ExperimentSpec> specs) { }
    private record Service(Link originLink, Link destinationLink, TransitStopFacility originStop,
            TransitStopFacility destinationStop, TransitLine line, TransitRoute route) { }
    private record RoadTrip(Link originLink, Link destinationLink) { }

    static Fixture create(Path workspace, Path root) throws IOException {
        return create(workspace, root, false);
    }

    static Fixture createRoadCompetition(Path workspace, Path root) throws IOException {
        return create(workspace, root, true);
    }

    private static Fixture create(Path workspace, Path root, boolean roadCompetition) throws IOException {
        Path inputs = Files.createDirectory(root.resolve("inputs"));
        Path configFile = inputs.resolve("common_config.xml");
        Config config = config(roadCompetition);
        new ConfigWriter(config).write(configFile.toString());
        Scenario scenario = ScenarioUtils.createScenario(config);
        Service passenger = passengerService(scenario);
        RoadTrip roadTrip = roadCompetition ? roadCompetition(scenario) : null;
        Path network = inputs.resolve("network.xml");
        new NetworkWriter(scenario.getNetwork()).write(network.toString());
        Path baselineSchedule = inputs.resolve("baseline_schedule.xml");
        Path baselineVehicles = inputs.resolve("baseline_transit_vehicles.xml");
        writeSupply(scenario, baselineSchedule, baselineVehicles);
        Service dedicated = addService(scenario, passenger.originLink(), passenger.destinationLink(),
                passenger.originStop(), passenger.destinationStop(), DEDICATED_LINE, "freight_route",
                "dedicated_vehicle", List.of(8 * 3600.0 + 120));
        Path activatedSchedule = inputs.resolve("activated_schedule.xml");
        Path activatedVehicles = inputs.resolve("activated_transit_vehicles.xml");
        writeSupply(scenario, activatedSchedule, activatedVehicles);

        if (roadCompetition) {
            addRoadPerson(scenario, ROAD_CAR_PASSENGER, TransportMode.car, roadTrip, 7 * 3600 + 55 * 60);
            addRoadPerson(scenario, ROAD_RIDE_PASSENGER, TransportMode.ride, roadTrip, 7 * 3600 + 55 * 60 + 1);
        } else {
            for (int i = 0; i < PASSENGER_IDS.size(); i++)
                addPerson(scenario, PASSENGER_IDS.get(i), passenger, false, 7 * 3600 + 55 * 60 + i);
        }
        Path memory = inputs.resolve("passenger_plan_memory.xml");
        new PopulationWriter(scenario.getPopulation()).write(memory.toString());
        for (int i = 0; i < FREIGHT_IDS.size(); i++)
            addPerson(scenario, FREIGHT_IDS.get(i), passenger, true, 7 * 3600 + 54 * 60 + i);
        Path mixedPopulation = inputs.resolve("mixed_population.xml");
        new PopulationWriter(scenario.getPopulation()).write(mixedPopulation.toString());
        FREIGHT_IDS.forEach(id -> scenario.getPopulation().removePerson(Id.createPersonId(id)));
        for (int i = 0; i < FREIGHT_IDS.size(); i++)
            addPerson(scenario, FREIGHT_IDS.get(i), dedicated, true, 7 * 3600 + 54 * 60 + i);
        Path dedicatedPopulation = inputs.resolve("dedicated_population.xml");
        new PopulationWriter(scenario.getPopulation()).write(dedicatedPopulation.toString());

        Path attributes = inputs.resolve("person_attributes.xml");
        new ObjectAttributesXmlWriter(new ObjectAttributes()).writeFile(attributes.toString());
        Path roadVehicles = inputs.resolve("road_vehicles.xml");
        var roads = VehicleUtils.createVehiclesContainer();
        // vehicleDefinitions_v2.0 requires >=1 vehicleType but permits zero vehicle instances.
        roads.addVehicleType(roads.getFactory().createVehicleType(Id.create(TransportMode.car, VehicleType.class))
                .setNetworkMode(TransportMode.car));
        if (roadCompetition)
            roads.addVehicleType(roads.getFactory().createVehicleType(Id.create(TransportMode.ride, VehicleType.class))
                    .setNetworkMode(TransportMode.ride));
        new MatsimVehicleWriter(roads).writeFile(roadVehicles.toString());
        Path fullOrders = inputs.resolve("order_pool.csv");
        Files.writeString(fullOrders, ORDER_HEADER + "cargo_order,07:30:00,09:00:00,0.3,3.0,standard,1,3\n",
                StandardOpenOption.CREATE_NEW);
        Path emptyOrders = inputs.resolve("empty_execution_orders.csv");
        Files.writeString(emptyOrders, ORDER_HEADER, StandardOpenOption.CREATE_NEW);

        Map<ExperimentSpec.Arm, ExperimentSpec> specs = new EnumMap<>(ExperimentSpec.Arm.class);
        Files.createDirectory(root.resolve("specs"));
        for (ExperimentSpec.Arm arm : ExperimentSpec.Arm.values()) {
            boolean freight = arm == ExperimentSpec.Arm.MIXED || arm == ExperimentSpec.Arm.DEDICATED;
            boolean dedicatedSupply = arm == ExperimentSpec.Arm.TIMETABLE_ONLY || arm == ExperimentSpec.Arm.DEDICATED;
            Path population = arm == ExperimentSpec.Arm.MIXED ? mixedPopulation
                    : arm == ExperimentSpec.Arm.DEDICATED ? dedicatedPopulation : memory;
            var inputRefs = new ExperimentSpec.Inputs(ref(workspace, configFile), ref(workspace, network),
                    ref(workspace, population), ref(workspace, memory), ref(workspace, attributes),
                    ref(workspace, baselineSchedule), ref(workspace, baselineVehicles),
                    ref(workspace, dedicatedSupply ? activatedSchedule : baselineSchedule),
                    ref(workspace, dedicatedSupply ? activatedVehicles : baselineVehicles),
                    ref(workspace, roadVehicles), ref(workspace, fullOrders), ref(workspace, freight ? fullOrders : emptyOrders));
            var access = arm == ExperimentSpec.Arm.MIXED ? ExperimentSpec.Access.SHARED_PASSENGER_SERVICE
                    : arm == ExperimentSpec.Arm.DEDICATED ? ExperimentSpec.Access.DEDICATED_ONLY : ExperimentSpec.Access.NO_FREIGHT;
            var spec = new ExperimentSpec(ExperimentSpec.SCHEMA,
                    roadCompetition ? "four-arm-road-rng-nonformal-fixture" : "four-arm-nonformal-fixture",
                    "tiny-B1", arm,
                    new ExperimentSpec.Seeds(4711, 41001, 4711), inputRefs,
                    new ExperimentSpec.Protocol(0, 20, 0, 20, 23, 23, 23, 23, 10 * 3600, 11 * 3600),
                    new ExperimentSpec.FreightDemand(freight ? 1 : 0, 10, 7.5 * 3600, 9 * 3600,
                            "freight_delivery", "_cap_"),
                    new ExperimentSpec.Policies(access, dedicatedSupply ? DEDICATED_LINE : "",
                            List.of("pt", "freight_pt"), 0.1, 0.9, 0.95, 0, true, 0.8,
                            0.3, 0.4, 0.2, 1, 10, 1),
                    new ExperimentSpec.OutputOptions("ismt-output-v1", ExperimentSpec.ExposureOutput.REPORTING_WINDOW,
                            false, 0, 0), "tiny-integration-nonformal");
            specs.put(arm, spec);
            ExperimentSpecIO.writeNew(root.resolve("specs").resolve(arm.name() + ".json"), spec);
        }
        ExperimentRunner.validateMatrixSpecifications(new ArrayList<>(specs.values()));
        return new Fixture(workspace, root, Map.copyOf(specs));
    }

    private static ExperimentSpec.FileRef ref(Path workspace, Path file) throws IOException {
        Path real = file.toRealPath();
        if (!real.startsWith(workspace.toRealPath())) throw new IOException("Fixture input escapes workspace: " + file);
        return new ExperimentSpec.FileRef(workspace.toRealPath().relativize(real).toString(), ExperimentConfiguration.sha256(real));
    }

    private static Config config(boolean roadCompetition) {
        String threadProperty = roadCompetition ? Integer.toString(ROAD_THREADS)
                : System.getProperty("ismt.smoke.threads", "1");
        final int threads;
        try {
            threads = Integer.parseInt(threadProperty);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("ismt.smoke.threads must be an integer in [1, 16]: " + threadProperty, error);
        }
        if (threads < 1 || threads > 16)
            throw new IllegalArgumentException("ismt.smoke.threads must be an integer in [1, 16]: " + threadProperty);
        Config config = ConfigUtils.createConfig();
        config.global().setNumberOfThreads(threads);
        // The unified input contract intentionally retains a separately hashed person-attributes XML.
        config.plans().setInsistingOnUsingDeprecatedPersonAttributeFile(true);
        config.qsim().setNumberOfThreads(threads);
        if (roadCompetition) {
            config.routing().removeTeleportedModeParams(TransportMode.ride);
            config.routing().setNetworkModes(Set.of(TransportMode.car, TransportMode.ride));
            config.routing().setRoutingRandomness(ROAD_ROUTING_RANDOMNESS);
            config.qsim().setMainModes(Set.of(TransportMode.car, TransportMode.ride));
            config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
            config.travelTimeCalculator().setAnalyzedModes(Set.of(TransportMode.car, TransportMode.ride));
            for (String mode : List.of(TransportMode.car, TransportMode.ride))
                config.scoring().getOrCreateModeParams(mode).setMonetaryDistanceRate(-0.001);
        }
        config.qsim().setStartTime(7 * 3600);
        config.qsim().setEndTime(10 * 3600);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
        config.transit().setUseTransit(true);
        config.transit().setTransitModes(Set.of("pt", "freight_pt"));
        config.routing().setNetworkRouteConsistencyCheck(RoutingConfigGroup.NetworkRouteConsistencyCheck.disable);
        config.controller().setCompressionType(ControllerConfigGroup.CompressionType.none);
        config.controller().setWriteEventsInterval(0);
        config.controller().setWritePlansInterval(0);
        config.controller().setWriteTripsInterval(1);
        config.controller().setCreateGraphsInterval(0);
        config.controller().setDumpDataAtEnd(true);
        config.scoring().setWriteExperiencedPlans(false);
        for (String type : List.of("home", "cargo_origin"))
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(type).setTypicalDuration(16 * 3600));
        for (String type : List.of("work", "freight_delivery"))
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(type).setTypicalDuration(8 * 3600));
        config.scoring().addModeParams(new ScoringConfigGroup.ModeParams("freight_pt")
                .setConstant(0).setMarginalUtilityOfTraveling(-6));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setStrategyName("ChangeExpBeta").setWeight(PASSENGER_SELECTOR_WEIGHT));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setStrategyName("ReRoute").setWeight(PASSENGER_REROUTE_WEIGHT));
        config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
                .setStrategyName("KeepLastSelected").setSubpopulation("freight").setWeight(1));
        // Disable only through the explicit synthetic 0/20/23 phase schedule, not the
        // native default fraction of this deliberately short controller run.
        config.replanning().setFractionOfIterationsToDisableInnovation(1.0);
        config.changeMode().setModes(new String[]{TransportMode.pt});
        config.timeAllocationMutator().setMutationRange(0);
        config.timeAllocationMutator().setMutationRangeStep(0);
        return config;
    }

    private static Service passengerService(Scenario scenario) {
        Node n0 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n0"), new Coord(0, 0));
        Node n1 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n1"), new Coord(10000, 0));
        Node n2 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n2"), new Coord(20000, 0));
        Link origin = NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("origin_link"), n0, n1, 10000, 20, 36000, 1);
        Link destination = NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("destination_link"), n1, n2, 10000, 20, 36000, 1);
        NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("return_1"), n2, n1, 10000, 20, 36000, 1);
        NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("return_0"), n1, n0, 10000, 20, 36000, 1);
        var factory = scenario.getTransitSchedule().getFactory();
        TransitStopFacility originStop = factory.createTransitStopFacility(Id.create("origin_stop", TransitStopFacility.class), new Coord(5000, 0), false);
        TransitStopFacility destinationStop = factory.createTransitStopFacility(Id.create("destination_stop", TransitStopFacility.class), new Coord(15000, 0), false);
        originStop.setLinkId(origin.getId());
        destinationStop.setLinkId(destination.getId());
        scenario.getTransitSchedule().addStopFacility(originStop);
        scenario.getTransitSchedule().addStopFacility(destinationStop);
        VehicleType type = scenario.getTransitVehicles().getFactory().createVehicleType(Id.create("capacity10", VehicleType.class));
        // The scheduled bus uses the road network, as in CreateVehiclesForSchedule's default.
        type.setNetworkMode(TransportMode.car);
        type.getCapacity().setSeats(10);
        type.getCapacity().setStandingRoom(0);
        type.setMaximumVelocity(20);
        type.setAccessTime(0);
        type.setEgressTime(0);
        scenario.getTransitVehicles().addVehicleType(type);
        return addService(scenario, origin, destination, originStop, destinationStop, PASSENGER_LINE,
                "passenger_route", "passenger_vehicle", List.of(28800.0, 29100.0, 29400.0, 29700.0));
    }

    private static RoadTrip roadCompetition(Scenario scenario) {
        Node ptAnchor = scenario.getNetwork().getNodes().get(Id.createNodeId("n0"));
        Node start = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("road_start"), new Coord(0, 2000));
        Node split = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("road_split"), new Coord(100, 2000));
        Node shortMid = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("road_short_mid"), new Coord(1100, 2500));
        Node fastMid = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("road_fast_mid"), new Coord(1100, 1500));
        Node merge = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("road_merge"), new Coord(2100, 2000));
        Node end = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("road_end"), new Coord(2200, 2000));
        // Keep every car/ride link in one strongly connected modal network. The
        // first integration attempt used a disconnected one-way test island and
        // was correctly refused by NetworkRoutingProvider before route choice.
        roadLink(scenario, "road_pt_connector", ptAnchor, start, 2000, 20);
        roadLink(scenario, "road_pt_connector_return", start, ptAnchor, 2000, 20);
        Link origin = roadLink(scenario, "road_origin", start, split, 100, 20);
        roadLink(scenario, "road_origin_return", split, start, 100, 20);
        // One route is shorter but slower; the other is longer but faster. With a
        // nonzero monetary distance rate, the formal sigma=3.0 changes their ranking.
        roadLink(scenario, "road_short_1", split, shortMid, 1000, 5);
        roadLink(scenario, "road_short_2", shortMid, merge, 1000, 5);
        roadLink(scenario, "road_fast_1", split, fastMid, 2000, 20);
        roadLink(scenario, "road_fast_2", fastMid, merge, 2000, 20);
        roadLink(scenario, "road_short_2_return", merge, shortMid, 1000, 5);
        roadLink(scenario, "road_short_1_return", shortMid, split, 1000, 5);
        roadLink(scenario, "road_fast_2_return", merge, fastMid, 2000, 20);
        roadLink(scenario, "road_fast_1_return", fastMid, split, 2000, 20);
        Link destination = roadLink(scenario, "road_destination", merge, end, 100, 20);
        roadLink(scenario, "road_destination_return", end, merge, 100, 20);
        return new RoadTrip(origin, destination);
    }

    private static Link roadLink(Scenario scenario, String id, Node from, Node to, double length, double freeSpeed) {
        Link link = NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId(id),
                from, to, length, freeSpeed, 36000, 1);
        link.setAllowedModes(Set.of(TransportMode.car, TransportMode.ride));
        return link;
    }

    private static Service addService(Scenario scenario, Link origin, Link destination, TransitStopFacility originStop,
            TransitStopFacility destinationStop, String lineId, String routeId, String vehiclePrefix, List<Double> times) {
        var schedule = scenario.getTransitSchedule();
        var factory = schedule.getFactory();
        TransitRouteStop from = factory.createTransitRouteStop(originStop, 0, 0);
        TransitRouteStop to = factory.createTransitRouteStop(destinationStop, 600, 600);
        // Plan-A's tick-level boarding proof requires a timetable-bound access stop.
        from.setAwaitDepartureTime(true);
        to.setAwaitDepartureTime(true);
        NetworkRoute networkRoute = RouteUtils.createLinkNetworkRouteImpl(origin.getId(), destination.getId());
        networkRoute.setLinkIds(origin.getId(), List.of(), destination.getId());
        TransitRoute route = factory.createTransitRoute(Id.create(routeId, TransitRoute.class), networkRoute,
                new ArrayList<>(List.of(from, to)), "bus");
        for (int i = 0; i < times.size(); i++) {
            VehicleType type = scenario.getTransitVehicles().getVehicleTypes().values().iterator().next();
            Vehicle vehicle = scenario.getTransitVehicles().getFactory().createVehicle(Id.createVehicleId(vehiclePrefix + "_" + i), type);
            scenario.getTransitVehicles().addVehicle(vehicle);
            Departure departure = factory.createDeparture(Id.create(lineId + "_departure_" + i, Departure.class), times.get(i));
            departure.setVehicleId(vehicle.getId());
            route.addDeparture(departure);
        }
        TransitLine line = factory.createTransitLine(Id.create(lineId, TransitLine.class));
        line.addRoute(route);
        schedule.addTransitLine(line);
        return new Service(origin, destination, originStop, destinationStop, line, route);
    }

    private static void writeSupply(Scenario scenario, Path schedule, Path vehicles) {
        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(schedule.toString());
        new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(vehicles.toString());
    }

    private static void addPerson(Scenario scenario, String id, Service service, boolean freight, double departure) {
        var factory = scenario.getPopulation().getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        Activity from = factory.createActivityFromCoord(freight ? "cargo_origin" : "home", service.originStop().getCoord());
        from.setLinkId(service.originLink().getId());
        from.setEndTime(departure);
        plan.addActivity(from);
        Leg leg = factory.createLeg(freight && DEDICATED_LINE.equals(service.line().getId().toString()) ? "freight_pt" : TransportMode.pt);
        leg.setRoute(new DefaultTransitPassengerRoute(service.originStop(), service.line(), service.route(), service.destinationStop()));
        plan.addLeg(leg);
        Activity to = factory.createActivityFromCoord(freight ? "freight_delivery" : "work", service.destinationStop().getCoord());
        to.setLinkId(service.destinationLink().getId());
        plan.addActivity(to);
        person.addPlan(plan);
        if (freight) PopulationUtils.putSubpopulation(person, "freight");
        scenario.getPopulation().addPerson(person);
    }

    private static void addRoadPerson(Scenario scenario, String id, String mode, RoadTrip trip, double departure) {
        var factory = scenario.getPopulation().getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        Activity from = factory.createActivityFromCoord("home", trip.originLink().getCoord());
        from.setLinkId(trip.originLink().getId());
        from.setEndTime(departure);
        plan.addActivity(from);
        plan.addLeg(factory.createLeg(mode));
        Activity to = factory.createActivityFromCoord("work", trip.destinationLink().getCoord());
        to.setLinkId(trip.destinationLink().getId());
        plan.addActivity(to);
        person.addPlan(plan);
        scenario.getPopulation().addPerson(person);
    }
}
