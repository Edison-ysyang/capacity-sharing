package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.pt.config.TransitConfigGroup;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.ScheduledTrip;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.SingleLineForecastSupply.*;

/** Static native objects only: no QSim, executing-plan inspection, events or simulation. */
class SingleLineForecastSupplyTest {
    @Test void fourRoutesOnOneLineAreAcceptedAndKeysAreStable() {
        var f = new Fixture();
        for (String route : List.of("z", "b", "a", "c")) f.route(route, "d", 100);
        var supply = from(f.scenario);
        assertEquals("line", supply.lineId());
        assertEquals(List.of("a", "b", "c", "z"), supply.trips().keySet().stream().map(TripKey::route).toList());
        assertEquals(TransitConfigGroup.BoardingAcceptance.checkLineAndStop, supply.boardingAcceptance());
        assertEquals(1, supply.qsimTimeStepSeconds());
        assertEquals(4, supply.trips().size());
        for (var trip : supply.trips().values()) {
            assertEquals(5, trip.capacity());
            assertEquals(1, trip.accessTimeSeconds());
            assertEquals(1, trip.egressTimeSeconds());
            assertEquals(DwellMode.COMPLEX_SERIAL, trip.dwellMode());
        }
    }

    @Test void nativeParallelModeAndNondefaultPerPersonTimesArePreserved() {
        var f = new Fixture(); f.route("a", "d", 100);
        f.type.setDoorOperationMode(VehicleType.DoorOperationMode.parallel);
        f.type.setAccessTime(0.25); f.type.setEgressTime(2.5);
        var trip = from(f.scenario).trips().values().iterator().next();
        assertEquals(DwellMode.COMPLEX_PARALLEL, trip.dwellMode());
        assertEquals(0.25, trip.accessTimeSeconds()); assertEquals(2.5, trip.egressTimeSeconds());
        assertTrue(trip.dwellMode().boardingBeforeAlightingInSameCall());
        assertFalse(DwellMode.COMPLEX_SERIAL.boardingBeforeAlightingInSameCall());
        assertEquals(1, trip.dwellMode().doorOpeningSeconds());
        assertEquals(1, trip.dwellMode().doorClosingSeconds());
        assertEquals(1, trip.dwellMode().requestedServiceStepSeconds());
    }

    @Test void callPermissionsAwaitAndMinimumDurationAreNotDiscarded() {
        var f = new Fixture(); var route = f.route("a", "d", 100);
        var first = route.getStops().getFirst();
        first.setAwaitDepartureTime(false); first.setAllowBoarding(false); first.setAllowAlighting(false);
        // Retain a minimum longer than the published dwell; a forecast must propagate it.
        first.setMinimumStopDuration(17.5);
        Call call = from(f.scenario).trips().values().iterator().next().calls().getFirst();
        assertFalse(call.awaitDepartureTime()); assertFalse(call.allowBoarding()); assertFalse(call.allowAlighting());
        assertEquals(17.5, call.minimumStopDurationSeconds());
        assertEquals(0, call.arrivalOffsetSeconds()); assertEquals(10, call.departureOffsetSeconds());
    }

    @Test void detachedSupplySurvivesNativeMutationAndCannotBeMutated() {
        var f = new Fixture(); var route = f.route("a", "d", 100);
        var supply = from(f.scenario); var trip = supply.trips().values().iterator().next();
        f.type.getCapacity().setSeats(99); f.type.setAccessTime(9);
        route.getStops().getFirst().setAllowBoarding(false);
        route.getStops().getFirst().setMinimumStopDuration(99);
        f.line.removeRoute(route);
        assertEquals(5, trip.capacity()); assertEquals(1, trip.accessTimeSeconds());
        assertTrue(trip.calls().getFirst().allowBoarding()); assertEquals(0, trip.calls().getFirst().minimumStopDurationSeconds());
        assertThrows(UnsupportedOperationException.class, () -> supply.trips().clear());
        assertThrows(UnsupportedOperationException.class, () -> trip.calls().clear());
    }

    @Test void tripDefensivelyCopiesCallsAndRejectsBadOrderingAndDuplicates() {
        var calls = new ArrayList<>(List.of(new Call("a", 0, 10), new Call("b", 20, 30)));
        var scheduled = new ScheduledTrip(new TripKey("l", "r", "d"), "v", 10);
        var trip = new Trip(scheduled, 1, 0, 0, DwellMode.COMPLEX_SERIAL, calls);
        calls.clear(); assertEquals(2, trip.calls().size());
        assertThrows(IllegalArgumentException.class, () -> new Trip(scheduled, 1, 0, 0, DwellMode.COMPLEX_SERIAL,
                List.of(new Call("a", 0, 10), new Call("a", 20, 30))));
        assertThrows(IllegalArgumentException.class, () -> new Trip(scheduled, 1, 0, 0, DwellMode.COMPLEX_SERIAL,
                List.of(new Call("a", 0, 10), new Call("b", 9, 30))));
        assertThrows(IllegalArgumentException.class, () -> new Trip(scheduled, 1, 0, 0, DwellMode.COMPLEX_SERIAL,
                List.of(new Call("a", 0, 10))));
    }

    @Test void noLineTwoLinesAndEmptyLineAreRejected() {
        var f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        f.route("a", "d", 100);
        f.scenario.getTransitSchedule().removeTransitLine(f.line);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        f.scenario.getTransitSchedule().addTransitLine(f.line);
        f.scenario.getTransitSchedule().addTransitLine(f.scenario.getTransitSchedule().getFactory().createTransitLine(Id.create("other", TransitLine.class)));
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
    }

    @Test void missingOrDisabledNativeTransitIsRejected() {
        var f = new Fixture(); f.route("a", "d", 100);
        f.scenario.getConfig().transit().setUsingTransitInMobsim(false);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        f.scenario.getConfig().transit().setUsingTransitInMobsim(true);
        f.scenario.getConfig().transit().setUseTransit(false);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
    }

    @Test void reusedVehicleAcrossRoutesAndMissingVehicleAreRejected() {
        var f = new Fixture(); var a = f.route("a", "d", 100); var b = f.route("b", "d", 200);
        var firstId = a.getDepartures().values().iterator().next().getVehicleId();
        b.getDepartures().values().iterator().next().setVehicleId(firstId);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        b.getDepartures().values().iterator().next().setVehicleId(Id.createVehicleId("missing"));
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
    }

    @Test void repeatedExactStopWithinRouteIsRejectedButCrossRouteSharingIsAllowed() {
        var f = new Fixture(); f.route("a", "d", 100); f.route("b", "d", 200);
        assertEquals(2, from(f.scenario).trips().size());
        var route = f.line.getRoutes().get(Id.create("b", TransitRoute.class));
        route.getStops().getLast().setStopFacility(f.origin);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
    }

    @Test void sameIdDetachedFacilityDoesNotCountAsRegisteredNativeStop() {
        var f = new Fixture(); var route = f.route("a", "d", 100);
        var detached = f.scenario.getTransitSchedule().getFactory().createTransitStopFacility(f.origin.getId(), new Coord(3, 4), false);
        route.getStops().getFirst().setStopFacility(detached);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
    }

    @Test void missingExplicitOffsetsAreNotInferredFromTheOtherOffset() {
        for (boolean missingArrival : List.of(true, false)) {
            var f = new Fixture();
            var sf = f.scenario.getTransitSchedule().getFactory();
            var start = sf.createTransitRouteStop(f.origin, missingArrival ? OptionalTime.undefined() : OptionalTime.defined(0),
                    missingArrival ? OptionalTime.defined(10) : OptionalTime.undefined());
            f.route("a", "d", 100, List.of(start, sf.createTransitRouteStop(f.destination, 20, 30)));
            assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        }
    }

    @Test void nonfiniteNegativeAndInvertedCallValuesAreRejected() {
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Call("a", bad, 10));
            assertThrows(IllegalArgumentException.class, () -> new Call("a", 0, bad));
            assertThrows(IllegalArgumentException.class, () -> new Call("a", 0, 10, true, true, true, bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new Call("a", 11, 10));
    }

    @Test void invalidNativeCapacityAndOverflowAreRejected() {
        var f = new Fixture(); f.route("a", "d", 100);
        f.type.getCapacity().setSeats(-1);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        f.type.getCapacity().setSeats(null);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        f.type.getCapacity().setSeats(2).setStandingRoom(-1);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        f.type.getCapacity().setSeats(Integer.MAX_VALUE).setStandingRoom(1);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
    }

    @Test void nativeNullStandingRoomIsZeroAndNoDriverSeatIsSubtracted() {
        var f = new Fixture(); f.route("a", "d", 100);
        f.type.getCapacity().setSeats(2).setStandingRoom(null);
        assertEquals(2, from(f.scenario).trips().values().iterator().next().capacity());
        f.type.getCapacity().setSeats(0);
        assertEquals(0, from(f.scenario).trips().values().iterator().next().capacity());
    }

    @Test void invalidPerPersonTimesAreRejectedAndZeroIsSupported() {
        var f = new Fixture(); f.route("a", "d", 100);
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            f.type.setAccessTime(bad);
            assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
            f.type.setAccessTime(0); f.type.setEgressTime(bad);
            assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        }
        f.type.setAccessTime(0); f.type.setEgressTime(0);
        assertEquals(0, from(f.scenario).trips().values().iterator().next().accessTimeSeconds());
    }

    @Test void invalidMinimumStopAndEmptyDepartureRouteAreRejected() {
        var f = new Fixture(); var route = f.route("a", "d", 100);
        route.getStops().getFirst().setMinimumStopDuration(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        route.getStops().getFirst().setMinimumStopDuration(0);
        route.removeDeparture(route.getDepartures().values().iterator().next());
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
    }

    @Test void malformedNativeAttributesFailClosedAndStringDoorModeUsesNativeParsing() {
        var f = new Fixture(); f.route("a", "d", 100);
        f.type.getAttributes().putAttribute("doorOperationMode", "PaRaLlEl");
        assertEquals(DwellMode.COMPLEX_PARALLEL, from(f.scenario).trips().values().iterator().next().dwellMode());
        f.type.getAttributes().putAttribute("doorOperationMode", "unsupported");
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        f.type.setDoorOperationMode(VehicleType.DoorOperationMode.serial);
        f.type.getAttributes().putAttribute("accessTimeInSecondsPerPerson", "1.0");
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
        f.type.setAccessTime(1);
        f.type.getAttributes().putAttribute("egressTimeInSecondsPerPerson", 1);
        assertThrows(IllegalArgumentException.class, () -> from(f.scenario));
    }

    @Test void absoluteOffsetOverflowIsRejected() {
        var scheduled = new ScheduledTrip(new TripKey("l", "r", "d"), "v", Double.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> new Trip(scheduled, 1, 1, 1, DwellMode.COMPLEX_SERIAL,
                List.of(new Call("a", 0, 1), new Call("b", Double.MAX_VALUE, Double.MAX_VALUE))));
    }

    @Test void boardingAcceptanceAndQsimStepAreCapturedWithoutChangingConfig() {
        var f = new Fixture(); f.route("a", "d", 100);
        f.scenario.getConfig().transit().setBoardingAcceptance(TransitConfigGroup.BoardingAcceptance.checkStopOnly);
        f.scenario.getConfig().qsim().setTimeStepSize(0.5);
        var supply = from(f.scenario);
        assertEquals(TransitConfigGroup.BoardingAcceptance.checkStopOnly, supply.boardingAcceptance());
        assertEquals(0.5, supply.qsimTimeStepSeconds());
        assertEquals(0, f.scenario.getPopulation().getPersons().size());
        assertEquals(1, f.line.getRoutes().size());
    }

    private static final class Fixture {
        final Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        final TransitLine line;
        final TransitStopFacility origin, destination;
        final VehicleType type;

        Fixture() {
            scenario.getConfig().transit().setUseTransit(true);
            scenario.getConfig().transit().setUsingTransitInMobsim(true);
            var sf = scenario.getTransitSchedule().getFactory();
            line = sf.createTransitLine(Id.create("line", TransitLine.class));
            scenario.getTransitSchedule().addTransitLine(line);
            origin = sf.createTransitStopFacility(Id.create("origin", TransitStopFacility.class), new Coord(0, 0), false);
            destination = sf.createTransitStopFacility(Id.create("destination", TransitStopFacility.class), new Coord(1, 0), false);
            origin.setLinkId(Id.createLinkId("l0")); destination.setLinkId(Id.createLinkId("l1"));
            scenario.getTransitSchedule().addStopFacility(origin); scenario.getTransitSchedule().addStopFacility(destination);
            type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class));
            type.getCapacity().setSeats(2).setStandingRoom(3);
            scenario.getTransitVehicles().addVehicleType(type);
        }

        TransitRoute route(String routeId, String departureId, double time) {
            var sf = scenario.getTransitSchedule().getFactory();
            var first = sf.createTransitRouteStop(origin, 0, 10); first.setAwaitDepartureTime(true);
            var last = sf.createTransitRouteStop(destination, 20, 30); last.setAwaitDepartureTime(true);
            return route(routeId, departureId, time, List.of(first, last));
        }

        TransitRoute route(String routeId, String departureId, double time, List<TransitRouteStop> calls) {
            var sf = scenario.getTransitSchedule().getFactory();
            var path = RouteUtils.createLinkNetworkRouteImpl(origin.getLinkId(), destination.getLinkId());
            path.setLinkIds(origin.getLinkId(), List.of(), destination.getLinkId());
            var route = sf.createTransitRoute(Id.create(routeId, TransitRoute.class), path, calls, TransportMode.pt);
            var vehicle = VehicleUtils.createVehicle(Id.createVehicleId("vehicle-" + routeId + "-" + departureId), type);
            scenario.getTransitVehicles().addVehicle(vehicle);
            Departure departure = sf.createDeparture(Id.create(departureId, Departure.class), time);
            departure.setVehicleId(vehicle.getId()); route.addDeparture(departure); line.addRoute(route);
            return route;
        }
    }
}
