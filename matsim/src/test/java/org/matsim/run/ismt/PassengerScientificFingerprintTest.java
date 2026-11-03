package org.matsim.run.ismt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.mobsim.qsim.pt.*;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PassengerScientificFingerprintTest {
	@TempDir Path temp;
	private int fixtureNumber;
	private static final Id<Person> PAX = Id.createPersonId("p1");
	private static final Id<Vehicle> VEHICLE = Id.createVehicleId("vehicle");
	private static final Id<TransitLine> LINE = Id.create("line", TransitLine.class);
	private static final Id<TransitRoute> ROUTE = Id.create("route", TransitRoute.class);
	private static final Id<TransitStopFacility> STOP = Id.create("stop", TransitStopFacility.class);

	@Test
	void excludesFreightDriversNewPersonsScoresAndDetailThresholds() throws Exception {
		Fixture control = fixture();
		Fixture detailed = fixture();
		Event nativeDenial = new BoardingDeniedEvent(10, PAX, VEHICLE);
		control.fingerprint().handleEvent(nativeDenial);
		detailed.fingerprint().handleEvent(nativeDenial);
		detailed.fingerprint().handleEvent(new BoardingDeniedEvent(10, Id.createPersonId("cargo"), VEHICLE));
		detailed.fingerprint().handleEvent(new PersonEntersPtVehicleEvent(10, Id.createPersonId("pt_driver"), VEHICLE, LINE, ROUTE));
		detailed.scenario().getPopulation().addPerson(detailed.scenario().getPopulation().getFactory().createPerson(Id.createPersonId("late_added")));
		detailed.fingerprint().handleEvent(departure(10, "late_added", "pt"));
		detailed.fingerprint().handleEvent(new PersonScoreEvent(Double.NaN, PAX, -900, "outside_qsim_scoring"));
		detailed.fingerprint().handleEvent(new LinkEnterEvent(999, VEHICLE, Id.createLinkId("other")));
		detailed.fingerprint().handleEvent(new TransitDriverStartsEvent(999, PAX, VEHICLE, LINE, ROUTE, Id.create("d", Departure.class)));
		detailed.fingerprint().handleEvent(detail(0.1));
		detailed.fingerprint().handleEvent(detail(0.9));
		assertEquals(2, detailed.fingerprint().passengerCount());
		var baseline = control.fingerprint().finishIteration(0);
		var observed = detailed.fingerprint().finishIteration(0);
		assertEquals(1, observed.eventCount());
		assertEquals(baseline.sha256(), observed.sha256());
	}

	@Test
	void sameTimeNativeEventPermutationIsInvariantWithoutDroppingMultiplicities() throws Exception {
		List<Event> events = allWhitelistedEvents();
		Fixture a = fixture();
		Fixture b = fixture();
		Fixture duplicate = fixture();
		for (Event event : events) {
			a.fingerprint().handleEvent(event);
			duplicate.fingerprint().handleEvent(event);
		}
		List<Event> reversed = new ArrayList<>(events);
		Collections.reverse(reversed);
		for (Event event : reversed) b.fingerprint().handleEvent(event);
		duplicate.fingerprint().handleEvent(events.getFirst());
		var left = a.fingerprint().finishIteration(0);
		var right = b.fingerprint().finishIteration(0);
		var repeated = duplicate.fingerprint().finishIteration(0);
		assertEquals(15, left.eventCount());
		assertEquals(left.sha256(), right.sha256());
		assertEquals(16, repeated.eventCount());
		assertNotEquals(left.sha256(), repeated.sha256());
	}

	@Test
	void actualPassengerTimeRouteVehicleWaitingDestinationActivityAndCoordinatesRemainVisible() throws Exception {
		assertDifferent(new PersonEntersPtVehicleEvent(10, PAX, VEHICLE, LINE, ROUTE),
				new PersonEntersPtVehicleEvent(11, PAX, VEHICLE, LINE, ROUTE));
		assertDifferent(new PersonEntersPtVehicleEvent(10, PAX, VEHICLE, LINE, ROUTE),
				new PersonEntersPtVehicleEvent(10, PAX, VEHICLE, LINE, Id.create("other", TransitRoute.class)));
		assertDifferent(new PersonEntersPtVehicleEvent(10, PAX, VEHICLE, LINE, ROUTE),
				new PersonEntersPtVehicleEvent(10, PAX, Id.createVehicleId("other"), LINE, ROUTE));
		assertDifferent(new AgentWaitingForPtEvent(10, PAX, STOP, Id.create("A", TransitStopFacility.class)),
				new AgentWaitingForPtEvent(10, PAX, STOP, Id.create("B", TransitStopFacility.class)));
		assertDifferent(activity("work", new Coord(1, 2)), activity("home", new Coord(1, 2)));
		assertDifferent(activity("work", new Coord(1, 2)), activity("work", new Coord(1, 3)));
		assertDifferent(departure(10, "p1", "pt"), departure(10, "p1", "walk"));
		assertDifferent(departure(10, "p1", "pt"), departure(10, "p2", "pt"));
	}

	@Test
	void stuckReasonUsesActualTypedFieldDespiteMatsimAttributeMapDefect() throws Exception {
		PersonStuckEvent first = new PersonStuckEvent(10, PAX, Id.createLinkId("link"), "pt", "reason_a");
		PersonStuckEvent second = new PersonStuckEvent(10, PAX, Id.createLinkId("link"), "pt", "reason_b");
		// MATSim 2026.0 maps both reasons to legMode; our fingerprint must not lose this difference.
		assertEquals(first.getAttributes(), second.getAttributes());
		assertDifferent(first, second);
	}

	@Test
	void resetClearsBucketsCountsAndHashWhilePreservingCompletedIterationEvidence() throws Exception {
		Fixture fixture = fixture();
		fixture.fingerprint().handleEvent(departure(10, "p1", "pt"));
		var first = fixture.fingerprint().finishIteration(0);
		fixture.fingerprint().reset(1);
		fixture.fingerprint().handleEvent(departure(10, "p1", "pt"));
		var next = fixture.fingerprint().finishIteration(1);
		assertEquals(first.sha256(), next.sha256());
		assertEquals(1, next.eventCount());
		fixture.fingerprint().reset(2);
		assertEquals(0, fixture.fingerprint().finishIteration(2).eventCount());
		assertEquals(3, fixture.fingerprint().completedFingerprints().size());
		assertThrows(UnsupportedOperationException.class, () -> fixture.fingerprint().completedFingerprints().clear());
	}

	@Test
	void invalidPassengerTimesAndIterationLifecyclesFailClosed() throws Exception {
		Fixture fixture = fixture();
		for (double time : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
			assertThrows(IllegalArgumentException.class, () -> fixture.fingerprint().handleEvent(departure(time, "p1", "pt")));
		}
		fixture.fingerprint().handleEvent(departure(10, "p1", "pt"));
		assertThrows(IllegalStateException.class, () -> fixture.fingerprint().handleEvent(departure(9, "p1", "pt")));
		assertThrows(IllegalStateException.class, () -> fixture.fingerprint().reset(1));
		assertThrows(IllegalStateException.class, () -> fixture.fingerprint().finishIteration(1));
		assertEquals(1, fixture.fingerprint().finishIteration(0).eventCount());
		assertThrows(IllegalStateException.class, () -> fixture.fingerprint().reset(0));
		assertThrows(IllegalArgumentException.class, () -> fixture.fingerprint().reset(4));
	}

	@Test
	void unknownSubclassOfNativeScientificEventCannotSilentlyEscapeCoverage() throws Exception {
		Fixture fixture = fixture();
		Event custom = new PersonArrivalEvent(10, PAX, Id.createLinkId("link"), "pt") { };
		assertThrows(IllegalArgumentException.class, () -> fixture.fingerprint().handleEvent(custom));
	}

	@Test
	void listenerWritesVersionedRowsExactlyOnceAndRefusesExistingOutput() throws Exception {
		Fixture fixture = fixture();
		fixture.fingerprint().handleEvent(departure(10, "p1", "pt"));
		fixture.fingerprint().notifyIterationEnds(new IterationEndsEvent(null, 0, false));
		fixture.fingerprint().reset(1);
		fixture.fingerprint().notifyIterationEnds(new IterationEndsEvent(null, 1, false));
		List<String> rows = Files.readAllLines(fixture.path());
		assertEquals(3, rows.size());
		assertEquals("experiment_id,block_id,arm,iteration,event_count,event_sha256,schema_version", rows.getFirst());
		assertTrue(rows.get(1).startsWith("fingerprint-test,B1,MIXED,0,1,"));
		assertTrue(rows.get(2).endsWith("," + PassengerScientificFingerprint.SCHEMA_VERSION));
		assertThrows(IllegalStateException.class,
				() -> fixture.fingerprint().notifyIterationEnds(new IterationEndsEvent(null, 1, false)));
		assertEquals(rows, Files.readAllLines(fixture.path()));
		assertThrows(IllegalStateException.class,
				() -> new PassengerScientificFingerprint(spec(), fixture.registry(), fixture.scenario(), fixture.path()));
	}

	private void assertDifferent(Event first, Event second) throws Exception {
		Fixture a = fixture(); Fixture b = fixture();
		a.fingerprint().handleEvent(first); b.fingerprint().handleEvent(second);
		assertNotEquals(a.fingerprint().finishIteration(0).sha256(), b.fingerprint().finishIteration(0).sha256());
	}

	private Fixture fixture() throws Exception {
		Path directory = temp.resolve("fixture-" + fixtureNumber++);
		Files.createDirectories(directory);
		Path orders = directory.resolve("orders.csv");
		Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
				+ "cargo,00:00:00,01:00:00,0.2,1,standard,1,1\n");
		FreightOrderRegistry registry = new FreightOrderRegistry(CoTransportSettings.builder(orders).build());
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		for (String id : List.of("p1", "p2", "cargo")) {
			scenario.getPopulation().addPerson(scenario.getPopulation().getFactory().createPerson(Id.createPersonId(id)));
		}
		Path path = directory.resolve(PassengerScientificFingerprint.FILE_NAME);
		PassengerScientificFingerprint fingerprint = new PassengerScientificFingerprint(spec(), registry, scenario, path);
		fingerprint.reset(0);
		return new Fixture(fingerprint, scenario, registry, path);
	}

	private static ExperimentSpec spec() {
		var ref = new ExperimentSpec.FileRef("fixture.xml", "0".repeat(64));
		return new ExperimentSpec(ExperimentSpec.SCHEMA, "fingerprint-test", "B1", ExperimentSpec.Arm.MIXED,
				new ExperimentSpec.Seeds(1, 2, 3),
				new ExperimentSpec.Inputs(ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref, ref),
				new ExperimentSpec.Protocol(0, 1, 2, 2, 2, 3, 2, 3, 86400, 129600),
				new ExperimentSpec.FreightDemand(1, 5, 0, 59400, "freight_destination", "_cap_"),
				new ExperimentSpec.Policies(ExperimentSpec.Access.SHARED_PASSENGER_SERVICE, "", List.of("pt", "freight_pt"), .1, .9, 1, 0,
						true, .6, .8, .8, 1, 1, 1, 1),
				new ExperimentSpec.OutputOptions("ismt-output-v1", ExperimentSpec.ExposureOutput.TERMINAL, false, 0, 0), "test");
	}

	private static PersonDepartureEvent departure(double time, String person, String mode) {
		return new PersonDepartureEvent(time, Id.createPersonId(person), Id.createLinkId("link"), mode, mode);
	}

	private static ActivityStartEvent activity(String type, Coord coord) {
		return new ActivityStartEvent(10, PAX, Id.createLinkId("link"), Id.create("facility", ActivityFacility.class), type, coord);
	}

	private static TransitBoardingDeniedDetailEvent detail(double cap) {
		return new TransitBoardingDeniedDetailEvent(1, PAX, VEHICLE, LINE, ROUTE, null, STOP,
				TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY,
				new TransitBoardingPolicy.DenialDetails(TransitBoardingPolicy.ActorClass.PASSENGER, 10, 0, 10, cap, .9));
	}

	private static List<Event> allWhitelistedEvents() {
		return List.of(new PersonInitializedEvent(10, PAX, Id.createLinkId("link"), null, "home"),
				activity("work", new Coord(1, 2)), new ActivityEndEvent(10, PAX, Id.createLinkId("link"), null, "home", null),
				departure(10, "p1", "pt"), new PersonArrivalEvent(10, PAX, Id.createLinkId("link"), "pt"),
				new TeleportationArrivalEvent(10, PAX, 100, "walk"),
				new AgentWaitingForPtEvent(10, PAX, STOP, Id.create("destination", TransitStopFacility.class)),
				new PersonEntersVehicleEvent(10, PAX, VEHICLE), new PersonLeavesVehicleEvent(10, PAX, VEHICLE),
				new PersonEntersPtVehicleEvent(10, PAX, VEHICLE, LINE, ROUTE),
				new PersonLeavesPtVehicleEvent(10, PAX, VEHICLE, LINE, ROUTE),
				new PersonContinuesInVehicleEvent(10, PAX, VEHICLE, Id.createVehicleId("next"), STOP, LINE, ROUTE),
				new BoardingDeniedEvent(10, PAX, VEHICLE), new PersonStuckEvent(10, PAX, Id.createLinkId("link"), "pt", "reason"),
				new PersonStuckAndContinueEvent(10, PAX, Id.createLinkId("link"), "pt"));
	}

	private record Fixture(PassengerScientificFingerprint fingerprint, Scenario scenario, FreightOrderRegistry registry, Path path) { }
}
