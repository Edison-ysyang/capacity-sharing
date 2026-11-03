package org.matsim.run.ismt.dynamic;

import org.matsim.api.core.v01.Scenario;
import org.matsim.pt.config.TransitConfigGroup;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.ScheduledTrip;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Detached static supply for a single-line, non-through-chain forecast domain.
 * Multiple routes/branches on that line are supported; each vehicle may have only
 * one departure. Exact stop IDs, not stop names, coordinates or link IDs, identify
 * calls. A stop may be shared across routes but must not repeat within one route.
 *
 * <p>This reads no population, executing plan, order, event, injector or RNG and
 * changes no native object. It is not a prepared-input, running stop-handler-factory,
 * capacity occupancy, physical-resource or safety certificate. A real prepared
 * QSim must separately pass {@link PreparedQSimInputAudit}, including supported
 * typed PT plans and no through chains, before this forecast domain is used.
 *
 * <p>Enabled native transit selects ComplexTransitStopHandler in the default
 * TransitEngineModule. Its serial mode finishes alighting before boarding; its
 * parallel mode processes boarding before alighting in the same handler call.
 * Both use fractional per-person time counters, one-second service requests and
 * conditional one-second door opening/closing. These are not a simple exact
 * {@code access*n + egress*m} or {@code max(...)} dwell formula. Native minimum
 * stop duration and await-departure rules act outside that handler; boarding-policy
 * overrides can further affect stops. A forecast using batched opportunities is
 * an explicit approximation, not a reproduction of those native transitions.
 */
public final class SingleLineForecastSupply {
    private static final Comparator<TripKey> KEY_ORDER = Comparator.comparing(TripKey::line)
            .thenComparing(TripKey::route).thenComparing(TripKey::departure);

    /** Names describe the native default handler semantics, not an inspected live binding. */
    public enum DwellMode {
        COMPLEX_SERIAL, COMPLEX_PARALLEL;

        public double doorOpeningSeconds() { return 1.0; }
        public double doorClosingSeconds() { return 1.0; }
        public double requestedServiceStepSeconds() { return 1.0; }
        public boolean boardingBeforeAlightingInSameCall() { return this == COMPLEX_PARALLEL; }
    }

    public record Call(String stopId, double arrivalOffsetSeconds, double departureOffsetSeconds,
                       boolean awaitDepartureTime, boolean allowBoarding, boolean allowAlighting,
                       double minimumStopDurationSeconds) {
        public Call {
            text(stopId, "stop ID");
            nonnegative(arrivalOffsetSeconds, "arrival offset");
            nonnegative(departureOffsetSeconds, "departure offset");
            nonnegative(minimumStopDurationSeconds, "minimum stop duration");
            require(departureOffsetSeconds >= arrivalOffsetSeconds, "Departure offset precedes arrival");
        }

        /** Explicit convenience semantics: awaited, both movements allowed, no minimum duration. */
        public Call(String stopId, double arrivalOffsetSeconds, double departureOffsetSeconds) {
            this(stopId, arrivalOffsetSeconds, departureOffsetSeconds, true, true, true, 0);
        }
    }

    public record Trip(ScheduledTrip scheduled, int capacity, double accessTimeSeconds,
                       double egressTimeSeconds, DwellMode dwellMode, List<Call> calls) {
        public Trip {
            Objects.requireNonNull(scheduled, "scheduled trip");
            require(capacity >= 0, "Negative native passenger capacity");
            nonnegative(accessTimeSeconds, "per-person access time");
            nonnegative(egressTimeSeconds, "per-person egress time");
            Objects.requireNonNull(dwellMode, "dwell mode");
            calls = List.copyOf(calls);
            require(calls.size() >= 2, "At least two calls are required in this forecast domain");
            var visited = new HashSet<String>();
            double previousDeparture = -1;
            for (var call : calls) {
                require(visited.add(call.stopId()), "Repeated exact stop ID in one route");
                require(call.arrivalOffsetSeconds() >= previousDeparture, "Call offsets imply negative running time");
                nonnegative(scheduled.baselineDepartureSeconds() + call.arrivalOffsetSeconds(), "absolute arrival");
                nonnegative(scheduled.baselineDepartureSeconds() + call.departureOffsetSeconds(), "absolute departure");
                previousDeparture = call.departureOffsetSeconds();
            }
        }
    }

    private final String lineId;
    private final TransitConfigGroup.BoardingAcceptance boardingAcceptance;
    private final double qsimTimeStepSeconds;
    private final Map<TripKey, Trip> trips;

    private SingleLineForecastSupply(String lineId, TransitConfigGroup.BoardingAcceptance boardingAcceptance,
                                     double qsimTimeStepSeconds, Map<TripKey, Trip> trips) {
        this.lineId = lineId;
        this.boardingAcceptance = boardingAcceptance;
        this.qsimTimeStepSeconds = qsimTimeStepSeconds;
        this.trips = Collections.unmodifiableMap(new LinkedHashMap<>(trips));
    }

    /**
     * Inspect the scenario's current static objects. Default native Complex-handler
     * configuration is required; an overriding factory is not discoverable here.
     * Unassigned vehicles need not be forecast and do not establish a circulation.
     */
    public static SingleLineForecastSupply from(Scenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        var transit = scenario.getConfig().transit();
        require(transit.isUseTransit() && transit.isUsingTransitInMobsim(), "Enabled native transit is required");
        var acceptance = Objects.requireNonNull(transit.getBoardingAcceptance(), "boarding acceptance");
        double step = scenario.getConfig().qsim().getTimeStepSize();
        require(Double.isFinite(step) && step > 0, "Finite positive QSim time step required");
        var schedule = Objects.requireNonNull(scenario.getTransitSchedule(), "schedule");
        var vehicles = Objects.requireNonNull(scenario.getTransitVehicles(), "transit vehicles");
        require(schedule.getTransitLines().size() == 1, "Exactly one transit line is supported");
        var lineEntry = schedule.getTransitLines().entrySet().iterator().next();
        var line = Objects.requireNonNull(lineEntry.getValue(), "line");
        require(line.getId().equals(lineEntry.getKey()), "Line registry key mismatch");
        String lineId = line.getId().toString();
        text(lineId, "line ID");
        require(!line.getRoutes().isEmpty(), "Empty line");
        var result = new TreeMap<TripKey, Trip>(KEY_ORDER);
        var usedVehicles = new HashSet<String>();
        for (var routeEntry : line.getRoutes().entrySet()) {
            var route = Objects.requireNonNull(routeEntry.getValue(), "route");
            require(route.getId().equals(routeEntry.getKey()), "Route registry key mismatch");
            text(route.getId().toString(), "route ID");
            require(!route.getDepartures().isEmpty(), "Every registered route must have a departure");
            var calls = new ArrayList<Call>();
            for (var stop : route.getStops()) {
                Objects.requireNonNull(stop, "route stop");
                var facility = Objects.requireNonNull(stop.getStopFacility(), "stop facility");
                require(schedule.getFacilities().get(facility.getId()) == facility,
                        "Route stop is not the registered facility instance");
                require(stop.getArrivalOffset().isDefined() && stop.getDepartureOffset().isDefined(),
                        "Every call requires explicit arrival and departure offsets");
                calls.add(new Call(facility.getId().toString(), stop.getArrivalOffset().seconds(),
                        stop.getDepartureOffset().seconds(), stop.isAwaitDepartureTime(),
                        stop.isAllowBoarding(), stop.isAllowAlighting(), stop.getMinimumStopDuration()));
            }
            for (var departureEntry : route.getDepartures().entrySet()) {
                var departure = Objects.requireNonNull(departureEntry.getValue(), "departure");
                require(departure.getId().equals(departureEntry.getKey()), "Departure registry key mismatch");
                var vehicleId = Objects.requireNonNull(departure.getVehicleId(), "departure vehicle ID");
                var vehicle = vehicles.getVehicles().get(vehicleId);
                require(vehicle != null && vehicleId.equals(vehicle.getId()), "Departure vehicle is missing or misregistered");
                require(usedVehicles.add(vehicleId.toString()), "Vehicle reuse/through-chain supply is unsupported");
                var type = Objects.requireNonNull(vehicle.getType(), "vehicle type");
                require(vehicles.getVehicleTypes().get(type.getId()) == type, "Vehicle type is not its registered native instance");
                var capacity = Objects.requireNonNull(type.getCapacity(), "explicit vehicle capacity");
                Integer seats = capacity.getSeats(), standing = capacity.getStandingRoom();
                require(seats != null && seats >= 0 && (standing == null || standing >= 0), "Invalid native capacity components");
                // QVehicleImpl uses null standing room as zero and does not subtract a driver seat.
                long total = (long) seats + (standing == null ? 0 : standing);
                require(total <= Integer.MAX_VALUE, "Native capacity sum overflows an integer");
                DwellMode mode;
                double access, egress;
                try {
                    mode = switch (VehicleUtils.getDoorOperationMode(type)) {
                        case serial -> DwellMode.COMPLEX_SERIAL;
                        case parallel -> DwellMode.COMPLEX_PARALLEL;
                    };
                    access = VehicleUtils.getAccessTime(type);
                    egress = VehicleUtils.getEgressTime(type);
                } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException("Unsupported native door/access/egress attributes", invalid);
                }
                var key = new TripKey(lineId, route.getId().toString(), departure.getId().toString());
                var trip = new Trip(new ScheduledTrip(key, vehicleId.toString(), departure.getDepartureTime()),
                        (int) total, access, egress, mode, calls);
                require(result.putIfAbsent(key, trip) == null, "Duplicate full trip key");
            }
        }
        require(!result.isEmpty(), "Empty trip supply");
        return new SingleLineForecastSupply(lineId, acceptance, step, result);
    }

    public String lineId() { return lineId; }
    public TransitConfigGroup.BoardingAcceptance boardingAcceptance() { return boardingAcceptance; }
    public double qsimTimeStepSeconds() { return qsimTimeStepSeconds; }
    public Map<TripKey, Trip> trips() { return trips; }

    private static void text(String value, String field) {
        require(value != null && !value.isBlank(), "Missing " + field);
    }

    private static void nonnegative(double value, String field) {
        require(Double.isFinite(value) && value >= 0, "Invalid " + field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
