package org.matsim.run.ismt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.routes.GenericRouteImpl;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.replanning.inheritance.PlanInheritanceModule;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.utils.objectattributes.attributable.Attributable;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;
import org.matsim.vehicles.PersonVehicles;
import org.matsim.vehicles.PersonVehicleTypes;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PassengerPlanFingerprintTest {
    // Reviewed native keys from VehicleUtils. Direct fixture attachment preserves the original
    // container/map identity needed by the nonmutation tests; public getters verify the contract.
    private static final String VEHICLES_KEY = "vehicles";
    private static final String VEHICLE_TYPES_KEY = "vehicleTypes";
    @TempDir Path temp;
    private int fixtureNumber;

    @Test void batchedStringsMatchJdkBytesAndDigestForEveryUtf16CodeUnit() throws Exception {
        char[] everyCodeUnit = new char[65536];
        for (int index = 0; index < everyCodeUnit.length; index++) everyCodeUnit[index] = (char) index;
        List<String> cases = Arrays.asList(null, "", "route 123 456", "\u4e2d\u6587",
                "\uD83D\uDE80", "\uD800", "\uDC00", "x\uD800y\uDC00z", new String(everyCodeUnit), null, "");
        byte[] expected = encodedStrings(cases, true, PassengerPlanFingerprint.STRING_BUFFER_BYTES);
        byte[] actual = encodedStrings(cases, false, PassengerPlanFingerprint.STRING_BUFFER_BYTES);
        assertArrayEquals(expected, actual);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected),
                MessageDigest.getInstance("SHA-256").digest(actual));
    }

    @Test void batchedStringsKeepFramingAndSurrogatesAcrossReusedBufferBoundaries() throws Exception {
        for (int bytes : new int[]{2, 6, PassengerPlanFingerprint.STRING_BUFFER_BYTES}) {
            int units = bytes / 2;
            List<String> cases = new ArrayList<>();
            for (int length : new int[]{units - 1, units, units + 1, 3 * units - 1, 3 * units, 3 * units + 1}) {
                char[] codeUnits = new char[length];
                for (int index = 0; index < length; index++) codeUnits[index] = (char) (index * 31);
                if (length > units) {
                    codeUnits[units - 1] = '\uD83D'; // A valid pair split across two writes.
                    codeUnits[units] = '\uDE80';
                }
                cases.add(new String(codeUnits));
                cases.add(null); cases.add(""); cases.add("\uD800x\uDC00");
            }
            byte[] expected = encodedStrings(cases, true, bytes);
            byte[] actual = encodedStrings(cases, false, bytes);
            assertArrayEquals(expected, actual, "String buffer bytes: " + bytes);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected),
                    MessageDigest.getInstance("SHA-256").digest(actual));
        }
    }

    @Test void longStringWritesReuseTheCallerBufferInBoundedBatches() throws Exception {
        byte[] buffer = new byte[PassengerPlanFingerprint.STRING_BUFFER_BYTES];
        List<Integer> writeLengths = new ArrayList<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes) {
            @Override public synchronized void write(byte[] payload, int offset, int length) throws IOException {
                assertSame(buffer, payload); assertEquals(0, offset);
                assertTrue(length > 0 && length <= buffer.length && length % 2 == 0);
                writeLengths.add(length);
                super.write(payload, offset, length);
            }
        }) {
            PassengerPlanFingerprint.writeString(out, "x".repeat(buffer.length + 1), buffer);
            PassengerPlanFingerprint.writeString(out, null, buffer);
            PassengerPlanFingerprint.writeString(out, "", buffer);
        }
        assertEquals(List.of(buffer.length, buffer.length, 2), writeLengths);
        // The length/null/empty frames and all payload bytes remain identical.
        ByteArrayOutputStream reference = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(reference)) {
            out.writeInt(buffer.length + 1); out.writeChars("x".repeat(buffer.length + 1));
            out.writeInt(-1); out.writeInt(0);
        }
        assertArrayEquals(reference.toByteArray(), bytes.toByteArray());
    }

    private static byte[] encodedStrings(List<String> strings, boolean jdkReference, int bufferBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[bufferBytes];
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (String text : strings) {
                out.writeBoolean(true); out.writeInt(0xCAFEBABE);
                if (jdkReference) {
                    out.writeInt(text == null ? -1 : text.length());
                    if (text != null) out.writeChars(text);
                } else PassengerPlanFingerprint.writeString(out, text, buffer);
                out.writeLong(0x0123456789ABCDEFL); out.writeBoolean(false);
            }
        }
        return bytes.toByteArray();
    }

    @Test void selectedPlanAndEveryStoredScoreAffectTheFingerprint() throws Exception {
        Fixture fixture = fixture(false);
        assertChange(fixture, f -> f.person().setSelectedPlan(f.person().getPlans().get(1)));
        assertChange(fixture, f -> f.person().getSelectedPlan().setScore(Math.nextUp(2.0)));
        assertChange(fixture, f -> f.person().getPlans().getFirst().setScore(Math.nextUp(1.0)));
        assertChange(fixture, f -> Collections.swap(f.person().getPlans(), 0, 1));
        assertChange(fixture, f -> f.person().getPlans().getFirst().setType("different-plan-type"));
    }

    @Test void exactActivityLegAndRouteFieldsAreNotRoundedAway() throws Exception {
        Fixture fixture = fixture(false);
        assertChange(fixture, f -> f.activity().setEndTime(Math.nextUp(28800.0)));
        assertChange(fixture, f -> f.activity().setStartTime(0.00000000000001));
        assertChange(fixture, f -> f.activity().setMaximumDuration(Math.nextUp(28800.0)));
        assertChange(fixture, f -> f.activity().setCoord(new Coord(1, 2, Math.nextUp(3.0))));
        assertChange(fixture, f -> f.activity().setType("new-home"));
        assertChange(fixture, f -> f.activity().setLinkId(Id.createLinkId("different")));
        assertChange(fixture, f -> f.activity().setFacilityId(Id.create("new-facility", org.matsim.facilities.ActivityFacility.class)));
        assertChange(fixture, f -> f.leg().setDepartureTime(Math.nextUp(28800.0)));
        assertChange(fixture, f -> f.leg().setTravelTime(Math.nextUp(60.0)));
        assertChange(fixture, f -> f.leg().setMode("bike"));
        assertChange(fixture, f -> f.leg().setRoutingMode("bus"));
        assertChange(fixture, f -> f.leg().getRoute().setDistance(Math.nextUp(100.0)));
        assertChange(fixture, f -> f.leg().getRoute().setTravelTime(Math.nextUp(60.0)));
        assertChange(fixture, f -> f.leg().getRoute().setStartLinkId(Id.createLinkId("different-route-start")));
        assertChange(fixture, f -> f.leg().getRoute().setEndLinkId(Id.createLinkId("different-route-end")));
        assertChange(fixture, f -> f.leg().getRoute().setRouteDescription("changed description"));
    }

    @Test void exactTransitBoardingAndChainedTimesSupplementRoundedNativeDescription() throws Exception {
        Fixture fixture = fixture(false);
        DefaultTransitPassengerRoute route = transitRoute();
        route.setBoardingTime(28800.0);
        fixture.leg().setRoute(route);
        String nativeBefore = route.getRouteDescription();
        String before = fixture.hash();
        route.setBoardingTime(Math.nextUp(28800.0));
        assertEquals(nativeBefore, route.getRouteDescription(), "Native PT description rounds time; it is not the exact-state oracle");
        assertNotEquals(before, fixture.hash());
        route.chainedRoute = transitRoute();
        route.chainedRoute.setBoardingTime(28900.0);
        nativeBefore = route.getRouteDescription(); before = fixture.hash();
        route.chainedRoute.setBoardingTime(Math.nextUp(28900.0));
        assertEquals(nativeBefore, route.getRouteDescription());
        assertNotEquals(before, fixture.hash());
        route.chainedRoute.chainedRoute = route;
        assertThrows(IllegalArgumentException.class, fixture::hash);
    }

    @Test void networkVehicleCostLinksAndRouteAttributesRemainVisible() throws Exception {
        Fixture fixture = fixture(false);
        NetworkRoute route = RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("a"), Id.createLinkId("b"));
        route.setLinkIds(Id.createLinkId("a"), List.of(Id.createLinkId("mid")), Id.createLinkId("b"));
        route.setTravelCost(1.0); fixture.leg().setRoute(route);
        assertChange(fixture, f -> route.setVehicleId(Id.createVehicleId("assigned-vehicle")));
        assertChange(fixture, f -> route.setTravelCost(Math.nextUp(1.0)));
        assertChange(fixture, f -> route.setLinkIds(Id.createLinkId("a"), List.of(Id.createLinkId("other-mid")), Id.createLinkId("b")));
        AttributedRoute attributed = new AttributedRoute();
        fixture.leg().setRoute(attributed);
        assertChange(fixture, f -> attributed.getAttributes().putAttribute("capacity", 2));
    }

    @Test void personPlanActivityLegAndLegacyAttributesAreAllIncluded() throws Exception {
        Fixture fixture = fixture(false);
        assertChange(fixture, f -> f.person().getAttributes().putAttribute("income", 100.0));
        assertChange(fixture, f -> f.person().getSelectedPlan().getAttributes().putAttribute("mechanism", "new"));
        assertChange(fixture, f -> f.activity().getAttributes().putAttribute("opening", 12));
        assertChange(fixture, f -> f.leg().getAttributes().putAttribute("routing-control", 0.5));
        assertChange(fixture, f -> f.person().getCustomAttributes().put("legacy-person", List.of(1, 2)));
        assertChange(fixture, f -> f.person().getSelectedPlan().getCustomAttributes().put("legacy-plan", "keep"));
        assertChange(fixture, f -> f.person().getSelectedPlan().setIterationCreated(19));
        assertChange(fixture, f -> f.person().getSelectedPlan().setPlanMutator("ReRoute"));
    }

    @Test void freightAndPersonInsertionOrderDoNotAffectPassengerHash() throws Exception {
        Fixture forward = fixture(false), reverse = fixture(true);
        assertEquals(forward.hash(), reverse.hash());
        Person freight = forward.scenario().getPopulation().getPersons().get(Id.createPersonId("cargo"));
        freight.getAttributes().putAttribute("opaque-freight-only", new Object());
        freight.getSelectedPlan().setScore(-999999.0);
        assertEquals(forward.hash(), reverse.hash());
        forward.scenario().getPopulation().removePerson(freight.getId());
        assertEquals(forward.hash(), reverse.hash());
        assertEquals(2, forward.fingerprint().fingerprintNow().passengerCount());
    }

    @Test void mapsAndSetsCanonicalizeOrderButTypesAndListOrderRemainDistinct() throws Exception {
        Fixture first = fixture(false), second = fixture(false);
        Map<String, Object> a = new LinkedHashMap<>(); a.put("b", 2); a.put("a", List.of(1, "1"));
        Map<String, Object> b = new LinkedHashMap<>(); b.put("a", List.of(1, "1")); b.put("b", 2);
        first.person().getAttributes().putAttribute("structured", a);
        second.person().getAttributes().putAttribute("structured", b);
        first.person().getAttributes().putAttribute("set", new LinkedHashSet<>(List.of("z", "a")));
        second.person().getAttributes().putAttribute("set", new LinkedHashSet<>(List.of("a", "z")));
        assertEquals(first.hash(), second.hash());
        b.put("a", List.of("1", 1)); assertNotEquals(first.hash(), second.hash());
        b.put("a", List.of(1, "1")); b.put("b", 2L); assertNotEquals(first.hash(), second.hash());
    }

    @Test void unsupportedOrCyclicAttributeValuesFailInsteadOfUsingObjectToString() throws Exception {
        Fixture fixture = fixture(false);
        fixture.person().getAttributes().putAttribute("opaque", new Object());
        assertThrows(IllegalArgumentException.class, fixture::hash);
        fixture.person().getAttributes().removeAttribute("opaque");
        Map<String, Object> cycle = new HashMap<>(); cycle.put("self", cycle);
        fixture.person().getAttributes().putAttribute("cycle", cycle);
        assertThrows(IllegalArgumentException.class, fixture::hash);
    }

    @Test void nativePersonVehiclesEncodeEveryModeAndIdInCanonicalOrder() throws Exception {
        Fixture first = fixture(false), second = fixture(false);
        Map<String, Id<Vehicle>> forward = new LinkedHashMap<>(), reverse = new LinkedHashMap<>();
        forward.put("car", Id.createVehicleId("car-vehicle")); forward.put("bike", Id.createVehicleId("bike-vehicle"));
        reverse.put("bike", Id.createVehicleId("bike-vehicle")); reverse.put("car", Id.createVehicleId("car-vehicle"));
        first.person().getAttributes().putAttribute(VEHICLES_KEY, new PersonVehicles(forward));
        second.person().getAttributes().putAttribute(VEHICLES_KEY, new PersonVehicles(reverse));
        assertEquals(first.hash(), second.hash());
        assertEquals(first.hash(), first.hash());
        assertEquals(List.of("car", "bike"), new ArrayList<>(forward.keySet()));
        assertEquals(List.of("bike", "car"), new ArrayList<>(reverse.keySet()));
        assertChange(first, f -> forward.put("bike", Id.createVehicleId("changed-bike-vehicle")));
        assertChange(first, f -> forward.put("car", Id.createVehicleId("changed-car-vehicle")));
        assertChange(first, f -> forward.put("other-mode", forward.remove("bike")));
    }

    @Test void nativePersonVehicleTypesEncodeEveryModeAndIdInCanonicalOrder() throws Exception {
        Fixture first = fixture(false), second = fixture(false);
        PersonVehicleTypes forward = new PersonVehicleTypes(), reverse = new PersonVehicleTypes();
        // These legal custom mode names share a String hash, exercising opposite HashMap bucket order.
        forward.addModeVehicleType("Aa", Id.create("type-a", VehicleType.class));
        forward.addModeVehicleType("BB", Id.create("type-b", VehicleType.class));
        reverse.addModeVehicleType("BB", Id.create("type-b", VehicleType.class));
        reverse.addModeVehicleType("Aa", Id.create("type-a", VehicleType.class));
        first.person().getAttributes().putAttribute(VEHICLE_TYPES_KEY, forward);
        second.person().getAttributes().putAttribute(VEHICLE_TYPES_KEY, reverse);
        assertNotEquals(new ArrayList<>(forward.getModeVehicleTypes().keySet()), new ArrayList<>(reverse.getModeVehicleTypes().keySet()));
        assertEquals(first.hash(), second.hash());
        assertChange(first, f -> forward.addModeVehicleType("Aa", Id.create("changed-a", VehicleType.class)));
        assertChange(first, f -> forward.addModeVehicleType("BB", Id.create("changed-b", VehicleType.class)));
        assertChange(first, f -> forward.getModeVehicleTypes().put("other-mode", forward.getModeVehicleTypes().remove("Aa")));
    }

    @Test void nativeVehicleContainersRemainDistinctFromEachOtherAndPlainMaps() throws Exception {
        Fixture fixture = fixture(false);
        fixture.person().getAttributes().putAttribute("mapping", new PersonVehicles());
        String vehicles = fixture.hash();
        fixture.person().getAttributes().putAttribute("mapping", new PersonVehicleTypes());
        String types = fixture.hash();
        fixture.person().getAttributes().putAttribute("mapping", Map.of());
        assertNotEquals(vehicles, types);
        assertNotEquals(vehicles, fixture.hash());
        assertNotEquals(types, fixture.hash());
    }

    @Test void unreviewedPersonVehiclesSubclassStillFailsClosedWithoutCallingItsGetter() throws Exception {
        Fixture fixture = fixture(false);
        fixture.person().getAttributes().putAttribute(VEHICLES_KEY, new PersonVehicles() {
            @Override public Map<String, Id<Vehicle>> getModeVehicles() {
                throw new AssertionError("Unreviewed subclass getter must not be invoked");
            }
        });
        assertThrows(IllegalArgumentException.class, fixture::hash);
    }

    @Test void observationDoesNotCreateIdsLazyContainersOrConsumeGlobalAndLocalRandomDraws() throws Exception {
        Fixture fixture = fixture(false);
        Map<String, Id<Vehicle>> vehicleMap = new LinkedHashMap<>();
        vehicleMap.put("car", Id.createVehicleId("assigned-car")); vehicleMap.put("bike", Id.createVehicleId("assigned-bike"));
        PersonVehicles vehicles = new PersonVehicles(vehicleMap);
        PersonVehicleTypes vehicleTypes = new PersonVehicleTypes();
        vehicleTypes.addModeVehicleType("car", Id.create("assigned-type", VehicleType.class));
        fixture.person().getAttributes().putAttribute(VEHICLES_KEY, vehicles);
        fixture.person().getAttributes().putAttribute(VEHICLE_TYPES_KEY, vehicleTypes);
        Map<String, Id<Vehicle>> vehiclesBefore = new LinkedHashMap<>(vehicleMap);
        Map<String, Id<VehicleType>> typesMap = vehicleTypes.getModeVehicleTypes();
        Map<String, Id<VehicleType>> typesBefore = new LinkedHashMap<>(typesMap);
        int vehicleIdsBefore = Id.getNumberOfIds(Vehicle.class), typeIdsBefore = Id.getNumberOfIds(VehicleType.class);
        Plan plan = fixture.person().getSelectedPlan();
        String lazyId = "uncreated-fingerprint-plan-id-" + temp.getFileName();
        plan.getAttributes().putAttribute(PlanInheritanceModule.PLAN_ID, lazyId);
        assertNull(Id.get(lazyId, Plan.class));
        int idsBefore = Id.getNumberOfIds(Plan.class);
        assertNull(field(fixture.person(), "customizableDelegate"));
        assertNull(field(plan, "customizableDelegate"));
        List<? extends Plan> plansBefore = List.copyOf(fixture.person().getPlans());
        try {
            MatsimRandom.reset(908172);
            long expectedGlobal = MatsimRandom.getRandom().nextLong();
            long expectedLocal = MatsimRandom.getLocalInstance().nextLong();
            MatsimRandom.reset(908172);
            String first = fixture.hash();
            assertEquals(first, fixture.hash());
            assertEquals(expectedGlobal, MatsimRandom.getRandom().nextLong());
            assertEquals(expectedLocal, MatsimRandom.getLocalInstance().nextLong());
        } finally { MatsimRandom.reset(); }
        assertEquals(idsBefore, Id.getNumberOfIds(Plan.class));
        assertEquals(vehicleIdsBefore, Id.getNumberOfIds(Vehicle.class));
        assertEquals(typeIdsBefore, Id.getNumberOfIds(VehicleType.class));
        assertSame(vehicles, fixture.person().getAttributes().getAttribute(VEHICLES_KEY));
        assertSame(vehicleTypes, fixture.person().getAttributes().getAttribute(VEHICLE_TYPES_KEY));
        assertSame(vehicleMap, vehicles.getModeVehicles()); assertSame(typesMap, vehicleTypes.getModeVehicleTypes());
        assertSame(vehicleMap, VehicleUtils.getVehicleIds(fixture.person()));
        assertSame(typesMap, VehicleUtils.getVehicleTypes(fixture.person()));
        assertEquals(vehiclesBefore, vehicleMap); assertEquals(typesBefore, typesMap);
        assertEquals(new ArrayList<>(vehiclesBefore.keySet()), new ArrayList<>(vehicleMap.keySet()));
        assertEquals(new ArrayList<>(typesBefore.keySet()), new ArrayList<>(typesMap.keySet()));
        assertNull(Id.get(lazyId, Plan.class));
        assertNull(field(fixture.person(), "customizableDelegate"));
        assertNull(field(plan, "customizableDelegate"));
        assertSame(plan, fixture.person().getSelectedPlan());
        assertEquals(plansBefore, fixture.person().getPlans());
        assertFalse(Files.exists(fixture.path()));
    }

    @Test void listenerWritesVersionedContiguousRowsAndRerunsExactlyWithoutOverwritingOtherEvidence() throws Exception {
        Fixture first = fixture(false), rerun = fixture(false);
        for (int iteration = 0; iteration <= 3; iteration++) {
            first.fingerprint().notifyIterationEnds(new IterationEndsEvent(null, iteration, iteration == 3));
            rerun.fingerprint().notifyIterationEnds(new IterationEndsEvent(null, iteration, iteration == 3));
        }
        assertEquals(Files.readString(first.path()), Files.readString(rerun.path()));
        List<String> lines = Files.readAllLines(first.path());
        assertEquals(5, lines.size());
        assertEquals("schema_version,experiment_id,block_id,arm,iteration,passenger_count,plan_sha256", lines.getFirst());
        assertTrue(lines.get(1).startsWith(PassengerPlanFingerprint.SCHEMA_VERSION + ",plan-fingerprint-test,B1,MIXED,0,2,"));
        assertEquals(50.0, first.fingerprint().priority());
        assertThrows(UnsupportedOperationException.class, () -> first.fingerprint().completedFingerprints().clear());
        assertThrows(IllegalStateException.class, () -> first.fingerprint().notifyIterationEnds(new IterationEndsEvent(null, 3, true)));
        assertThrows(IllegalStateException.class, () -> new PassengerPlanFingerprint(spec(), first.scenario(), first.registry(), first.path()));
        Fixture gap = fixture(false);
        assertThrows(IllegalStateException.class, () -> gap.fingerprint().notifyIterationEnds(new IterationEndsEvent(null, 1, false)));
        assertFalse(Files.exists(gap.path()));
    }

    private void assertChange(Fixture fixture, Consumer<Fixture> mutation) {
        String before = fixture.hash(); mutation.accept(fixture); assertNotEquals(before, fixture.hash());
    }

    private Fixture fixture(boolean reverse) throws Exception {
        Path directory = temp.resolve("fixture-" + fixtureNumber++); Files.createDirectories(directory);
        Path orders = directory.resolve("orders.csv");
        Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
                + "cargo,00:00:00,01:00:00,0.2,1,standard,1,1\n");
        FreightOrderRegistry registry = new FreightOrderRegistry(CoTransportSettings.builder(orders).build());
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        List<String> names = reverse ? List.of("cargo", "p2", "p1") : List.of("p1", "p2", "cargo");
        for (String name : names) {
            Person person = scenario.getPopulation().getFactory().createPerson(Id.createPersonId(name));
            for (int index = 0; index < 2; index++) {
                Plan plan = scenario.getPopulation().getFactory().createPlan(); plan.setType("plan-" + index); plan.setScore((double) index + 1);
                Activity home = scenario.getPopulation().getFactory().createActivityFromCoord("home", new Coord(1, 2, 3));
                home.setLinkId(Id.createLinkId("a")); home.setEndTime(28800.0); home.setMaximumDuration(28800.0);
                Leg leg = scenario.getPopulation().getFactory().createLeg("walk"); leg.setRoutingMode("walk");
                leg.setDepartureTime(28800.0); leg.setTravelTime(60.0);
                Route route = new GenericRouteImpl(Id.createLinkId("a"), Id.createLinkId("b"));
                route.setDistance(100.0); route.setTravelTime(60.0); route.setRouteDescription("a b"); leg.setRoute(route);
                Activity work = scenario.getPopulation().getFactory().createActivityFromLinkId("work", Id.createLinkId("b"));
                work.setStartTime(28860.0);
                plan.addActivity(home); plan.addLeg(leg); plan.addActivity(work); person.addPlan(plan);
            }
            person.setSelectedPlan(person.getPlans().getFirst()); scenario.getPopulation().addPerson(person);
        }
        Path path = directory.resolve(PassengerPlanFingerprint.FILE_NAME);
        return new Fixture(new PassengerPlanFingerprint(spec(), scenario, registry, path), scenario, registry, path);
    }

    private static DefaultTransitPassengerRoute transitRoute() {
        return new DefaultTransitPassengerRoute(Id.createLinkId("a"), Id.createLinkId("b"),
                Id.create("stop-a", TransitStopFacility.class), Id.create("stop-b", TransitStopFacility.class),
                Id.create("line", TransitLine.class), Id.create("route", TransitRoute.class));
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); assertTrue(field.trySetAccessible()); return field.get(object);
    }

    private static ExperimentSpec spec() {
        var ref = new ExperimentSpec.FileRef("fixture.xml", "0".repeat(64));
        return new ExperimentSpec(ExperimentSpec.SCHEMA, "plan-fingerprint-test", "B1", ExperimentSpec.Arm.MIXED,
                new ExperimentSpec.Seeds(1, 2, 3), new ExperimentSpec.Inputs(ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref),
                new ExperimentSpec.Protocol(0, 1, 2, 2, 2, 3, 2, 3, 86400, 129600),
                new ExperimentSpec.FreightDemand(1, 5, 0, 59400, "freight_destination", "_cap_"),
                new ExperimentSpec.Policies(ExperimentSpec.Access.SHARED_PASSENGER_SERVICE, "", List.of("pt", "freight_pt"),
                        0.1, 0.9, 1.0, 0, true, 0.6, 0.8, 0.8, 1, 1, 1, 1),
                new ExperimentSpec.OutputOptions("ismt-output-v1", ExperimentSpec.ExposureOutput.TERMINAL, false, 0, 0), "fixture");
    }

    private record Fixture(PassengerPlanFingerprint fingerprint, Scenario scenario, FreightOrderRegistry registry, Path path) {
        Person person() { return scenario.getPopulation().getPersons().get(Id.createPersonId("p1")); }
        Activity activity() { return (Activity) person().getSelectedPlan().getPlanElements().getFirst(); }
        Leg leg() { return (Leg) person().getSelectedPlan().getPlanElements().get(1); }
        String hash() { return fingerprint.fingerprintNow().sha256(); }
    }

    private static final class AttributedRoute implements Route, Attributable {
        private final GenericRouteImpl route = new GenericRouteImpl(Id.createLinkId("a"), Id.createLinkId("b"));
        private final Attributes attributes = new AttributesImpl();
        @Override public Attributes getAttributes() { return attributes; }
        @Override public double getDistance() { return route.getDistance(); }
        @Override public void setDistance(double distance) { route.setDistance(distance); }
        @Override public OptionalTime getTravelTime() { return route.getTravelTime(); }
        @Override public void setTravelTime(double seconds) { route.setTravelTime(seconds); }
        @Override public void setTravelTimeUndefined() { route.setTravelTimeUndefined(); }
        @Override public Id<Link> getStartLinkId() { return route.getStartLinkId(); }
        @Override public Id<Link> getEndLinkId() { return route.getEndLinkId(); }
        @Override public void setStartLinkId(Id<Link> link) { route.setStartLinkId(link); }
        @Override public void setEndLinkId(Id<Link> link) { route.setEndLinkId(link); }
        @Override public String getRouteDescription() { return route.getRouteDescription(); }
        @Override public void setRouteDescription(String description) { route.setRouteDescription(description); }
        @Override public String getRouteType() { return "attributed-generic-test"; }
        @Override public Route clone() { throw new UnsupportedOperationException("Fingerprint must not clone routes"); }
    }
}
