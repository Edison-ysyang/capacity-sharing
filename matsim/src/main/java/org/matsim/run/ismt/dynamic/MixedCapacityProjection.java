package org.matsim.run.ismt.dynamic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

import org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SharedBoardingWindow;

import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;

/**
 * Finite-capacity, deterministic forecast, not a second QSim allocator. Passenger
 * cohorts are fluid expected mass; freight consists of indivisible capacity units.
 * Loading is a batched opportunity at a predicted stop departure, with alighting
 * at predicted arrival. This is NOT the native ComplexTransitStopHandler's per-tick
 * door process or a guarantee that observed event-accounted occupancy is physical
 * occupancy. Candidate forecasts use the same demand and these same approximations.
 *
 * <p>Service times use timetable offsets plus an explicitly supplied observed-delay
 * estimate. Minimum dwell propagates downstream; demand does not add a fabricated
 * freight handling dwell. Exact per-person native door timing is not reproduced.
 * Native capacities and freight-share/load-gate arithmetic are retained, extended
 * to fluid passenger mass. At integer passenger mass the admission inequalities
 * coincide with the registered mixed rules. There is no line/route rerouting, signal
 * model, random future demand, native mutation or actual-safety claim here.</p>
 */
public final class MixedCapacityProjection {
    public enum Kind { PASSENGER, FREIGHT_UNIT }
    public enum Completion { DELIVERED_OBSERVED, DELIVERED_PREDICTED, UNDELIVERED_AT_HORIZON, ABORTED_OBSERVED }
    public record Passenger(String id, String originStop, String destinationStop, double readyAtSeconds,
                            double mass, boolean protectedCohort, Optional<TripKey> initiallyRiding) {
        public Passenger {
            text(id); text(originStop); text(destinationStop); time(readyAtSeconds); positive(mass);
            Objects.requireNonNull(initiallyRiding);
        }
    }
    public record Freight(String unitId, String originStop, String destinationStop, double releasedAtSeconds,
                          double readyAtSeconds, double deadlineSeconds, double egressEstimateSeconds,
                          OptionalDouble firstBoardedAtSeconds, OptionalDouble deliveredAtSeconds,
                          OptionalDouble pendingDeliveryAtSeconds, boolean aborted, Optional<TripKey> initiallyRiding) {
        public Freight {
            text(unitId); text(originStop); text(destinationStop); time(releasedAtSeconds); time(readyAtSeconds);
            time(deadlineSeconds); time(egressEstimateSeconds); Objects.requireNonNull(initiallyRiding);
            for (var value : List.of(firstBoardedAtSeconds, deliveredAtSeconds, pendingDeliveryAtSeconds)) {
                Objects.requireNonNull(value); value.ifPresent(MixedCapacityProjection::time);
            }
            require(readyAtSeconds >= releasedAtSeconds, "Ready before release");
            firstBoardedAtSeconds.ifPresent(at -> require(at >= releasedAtSeconds, "Board before release"));
            deliveredAtSeconds.ifPresent(at -> require(firstBoardedAtSeconds.isPresent() && at >= firstBoardedAtSeconds.getAsDouble(), "Delivery without prior boarding"));
            require(!(initiallyRiding.isPresent() && (deliveredAtSeconds.isPresent() || pendingDeliveryAtSeconds.isPresent() || aborted)), "Inconsistent freight phase");
            require(!(deliveredAtSeconds.isPresent() && (pendingDeliveryAtSeconds.isPresent() || aborted)), "Conflicting terminal freight state");
            require(!(aborted && pendingDeliveryAtSeconds.isPresent()), "Aborted freight cannot have a pending delivery");
            if (initiallyRiding.isPresent() || pendingDeliveryAtSeconds.isPresent()) require(firstBoardedAtSeconds.isPresent(), "Missing first boarding");
            pendingDeliveryAtSeconds.ifPresent(at -> require(at >= firstBoardedAtSeconds.orElseThrow(), "Pending delivery precedes first boarding"));
        }
    }

    /** nextCall is an exact stop index. Already completed calls must not be replayed. */
    public record Service(SingleLineForecastSupply.Trip supply, double originSeconds, int nextCall,
                          double delayEstimateSeconds, double firstArrivalFloorSeconds) {
        public Service {
            Objects.requireNonNull(supply); time(originSeconds); finite(delayEstimateSeconds); time(firstArrivalFloorSeconds);
            require(nextCall >= 0 && nextCall < supply.calls().size(), "Invalid next forecast call");
        }
    }
    public record Inputs(double startSeconds, double horizonSeconds, double freightShareCap, double passengerLoadGate,
                         int maximumStopEvents, List<Service> services, List<Passenger> passengers, List<Freight> freight,
                         SharedBoardingWindow boardingWindow) {
        public Inputs(double startSeconds, double horizonSeconds, double freightShareCap, double passengerLoadGate,
                int maximumStopEvents, List<Service> services, List<Passenger> passengers, List<Freight> freight) {
            this(startSeconds, horizonSeconds, freightShareCap, passengerLoadGate, maximumStopEvents,
                    services, passengers, freight, null);
        }
        public Inputs {
            time(startSeconds); time(horizonSeconds); require(horizonSeconds > startSeconds, "Empty forecast horizon");
            fraction(freightShareCap); fraction(passengerLoadGate); require(maximumStopEvents > 0, "Explicit event budget required");
            services = List.copyOf(services); passengers = List.copyOf(passengers); freight = List.copyOf(freight);
            var trips = new HashSet<TripKey>(); var vehicles = new HashSet<String>(); var ids = new HashSet<String>();
            for (Service service : services) require(trips.add(service.supply().scheduled().key())
                    && vehicles.add(service.supply().scheduled().vehicleId()), "Repeated forecast trip or vehicle");
            for (Passenger passenger : passengers) {
                require(ids.add(passenger.id()), "Repeated prediction identity");
                passenger.initiallyRiding().ifPresent(trip -> require(trips.contains(trip), "Unregistered initial passenger trip"));
                if (passenger.initiallyRiding().isPresent()) require(passenger.readyAtSeconds() <= startSeconds, "Future passenger labelled initially riding");
                require(passenger.readyAtSeconds() <= horizonSeconds, "Passenger demand beyond forecast horizon");
            }
            for (Freight unit : freight) {
                require(ids.add(unit.unitId()), "Repeated prediction identity");
                require(unit.releasedAtSeconds() <= startSeconds, "Unreleased future freight in prediction");
                unit.initiallyRiding().ifPresent(trip -> require(trips.contains(trip), "Unregistered initial freight trip"));
                if (unit.initiallyRiding().isPresent()) require(unit.readyAtSeconds() <= startSeconds, "Future freight readiness labelled initially riding");
                unit.deliveredAtSeconds().ifPresent(at -> require(at <= startSeconds, "Future delivery labelled observed"));
                unit.firstBoardedAtSeconds().ifPresent(at -> require(at <= startSeconds, "Future boarding labelled observed"));
                if (boardingWindow != null) unit.firstBoardedAtSeconds().ifPresent(at ->
                        require(boardingWindow.contains(at), "Observed freight boarding lies outside the registered window"));
            }
        }
    }
    public record PassengerResult(double mass, double meanWaitingSeconds, double unboardedMass, boolean protectedCohort) { }
    public record FreightResult(double releasedAtSeconds, double deadlineSeconds, OptionalDouble firstBoardedAtSeconds,
                                OptionalDouble deliveredAtSeconds, Completion completion, double waitingAccountSeconds,
                                double latenessAccountSeconds, boolean missedDeadlineOrUnserved) { }
    public record StopLoad(TripKey trip, int call, double arrivalSeconds, double departureSeconds,
                           double passengersAfterBoarding, int freightAfterBoarding, double passengerMassBoarded, int freightBoarded) { }
    public record Result(Map<String, PassengerResult> passengers, Map<String, FreightResult> freight,
                         List<StopLoad> loads, int processedStopEvents) {
        public Result { passengers = Map.copyOf(passengers); freight = Map.copyOf(freight); loads = List.copyOf(loads); }
    }

    private enum EventType { ARRIVAL, BOARDING }
    private record StopEvent(double time, TripKey trip, int call, EventType type, double arrivedAt) { }
    private static final Comparator<TripKey> TRIP_ORDER = Comparator.comparing(TripKey::line).thenComparing(TripKey::route).thenComparing(TripKey::departure);
    private static final Comparator<StopEvent> EVENT_ORDER = Comparator.comparingDouble(StopEvent::time)
            .thenComparing(StopEvent::trip, TRIP_ORDER).thenComparingInt(StopEvent::call).thenComparing(StopEvent::type);
    private static final class Waiting {
        final String id, origin, destination;
        final Kind kind;
        final double ready;
        double mass;
        Waiting(String id, String origin, String destination, Kind kind, double ready, double mass) {
            this.id = id; this.origin = origin; this.destination = destination; this.kind = kind; this.ready = ready; this.mass = mass;
        }
    }
    private record Load(String id, String destination, Kind kind, double mass) { }
    /** Index by known readiness so a stop never scans an entire future service day's cohorts. */
    private static final class WaitingQueue {
        final Map<Kind, TreeMap<Double, List<Waiting>>> byKind = new EnumMap<>(Kind.class);
        void add(Waiting value) {
            byKind.computeIfAbsent(value.kind, ignored -> new TreeMap<>())
                    .computeIfAbsent(value.ready, ignored -> new ArrayList<>()).add(value);
        }
        void sortTies() {
            byKind.values().forEach(times -> times.values().forEach(bucket -> bucket.sort(Comparator.comparing(value -> value.id))));
        }
        List<Waiting> available(double time) {
            var ready = new ArrayList<Waiting>();
            for (Kind kind : Kind.values()) {
                var times = byKind.get(kind);
                if (times != null) times.headMap(time, true).values().forEach(ready::addAll);
            }
            return ready;
        }
        void prune(double time) {
            byKind.values().forEach(times -> times.headMap(time, true).entrySet().removeIf(entry -> {
                entry.getValue().removeIf(value -> value.mass == 0);
                return entry.getValue().isEmpty();
            }));
        }
    }
    private static final class Vehicle {
        final Service service;
        final AlightingIndex alighting;
        final List<Load> loads = new ArrayList<>();
        double delay;
        Vehicle(Service service) {
            this.service = service; delay = service.delayEstimateSeconds();
            alighting = new AlightingIndex(service.supply());
        }
    }

    /** Immutable, service-local topology only; no candidate time or mutable queue is cached. */
    static final class AlightingIndex {
        private final int callCount;
        private final Map<String, Integer> lastAllowed;
        AlightingIndex(SingleLineForecastSupply.Trip trip) {
            var calls = Objects.requireNonNull(trip).calls(); callCount = calls.size();
            var last = new HashMap<String, Integer>();
            for (int index = 0; index < calls.size(); index++)
                if (calls.get(index).allowAlighting()) last.put(calls.get(index).stopId(), index);
            lastAllowed = Map.copyOf(last);
        }
        boolean allowsAfter(int currentCall, String destination) {
            require(currentCall >= 0 && currentCall < callCount, "Invalid current alighting-index call");
            return lastAllowed.getOrDefault(Objects.requireNonNull(destination), -1) > currentCall;
        }
    }

    public Result project(Inputs input) {
        Objects.requireNonNull(input);
        var vehicles = new HashMap<TripKey, Vehicle>();
        var queues = new HashMap<String, WaitingQueue>();
        var passengerQueues = new HashMap<String, Waiting>();
        var freightIndex = new LinkedHashMap<String, Freight>();
        var waited = new HashMap<String, Double>();
        var firstBoard = new HashMap<String, Double>();
        var deliveries = new HashMap<String, Double>();
        var events = new PriorityQueue<>(EVENT_ORDER);
        for (Service service : input.services()) {
            TripKey key = service.supply().scheduled().key(); vehicles.put(key, new Vehicle(service));
            double arrival = Math.max(input.startSeconds(), Math.max(service.firstArrivalFloorSeconds(),
                    add(service.originSeconds(), service.supply().calls().get(service.nextCall()).arrivalOffsetSeconds(), service.delayEstimateSeconds())));
            events.add(new StopEvent(arrival, key, service.nextCall(), EventType.ARRIVAL, arrival));
        }
        for (Passenger passenger : input.passengers()) {
            waited.put(passenger.id(), 0.0);
            if (passenger.initiallyRiding().isPresent()) {
                vehicles.get(passenger.initiallyRiding().orElseThrow()).loads.add(new Load(passenger.id(), passenger.destinationStop(), Kind.PASSENGER, passenger.mass()));
            } else {
                var queued = new Waiting(passenger.id(), passenger.originStop(), passenger.destinationStop(), Kind.PASSENGER, passenger.readyAtSeconds(), passenger.mass());
                passengerQueues.put(passenger.id(), queued); enqueue(queues, queued);
            }
        }
        for (Freight unit : input.freight()) {
            freightIndex.put(unit.unitId(), unit); unit.firstBoardedAtSeconds().ifPresent(at -> firstBoard.put(unit.unitId(), at));
            if (unit.deliveredAtSeconds().isPresent()) deliveries.put(unit.unitId(), unit.deliveredAtSeconds().getAsDouble());
            else if (unit.pendingDeliveryAtSeconds().isPresent()) {
                double delivery = Math.max(input.startSeconds(), unit.pendingDeliveryAtSeconds().getAsDouble());
                if (delivery <= input.horizonSeconds()) deliveries.put(unit.unitId(), delivery);
            } else if (unit.initiallyRiding().isPresent()) vehicles.get(unit.initiallyRiding().orElseThrow()).loads.add(
                    new Load(unit.unitId(), unit.destinationStop(), Kind.FREIGHT_UNIT, 1));
            else if (!unit.aborted()) enqueue(queues, new Waiting(unit.unitId(), unit.originStop(), unit.destinationStop(), Kind.FREIGHT_UNIT,
                    unit.readyAtSeconds(), 1));
        }
        for (Vehicle vehicle : vehicles.values()) require(occupancy(vehicle) <= vehicle.service.supply().capacity(), "Initial forecast occupancy exceeds native capacity");
        queues.values().forEach(WaitingQueue::sortTies);
        var traces = new ArrayList<StopLoad>(); int processed = 0;
        while (!events.isEmpty() && events.peek().time() <= input.horizonSeconds()) {
            require(++processed <= input.maximumStopEvents(), "Forecast stop-event budget exhausted; no truncation is permitted");
            StopEvent event = events.remove(); Vehicle vehicle = vehicles.get(event.trip());
            var supply = vehicle.service.supply(); var call = supply.calls().get(event.call());
            if (event.type() == EventType.ARRIVAL) {
                if (call.allowAlighting()) {
                    var iterator = vehicle.loads.iterator();
                    while (iterator.hasNext()) {
                        Load load = iterator.next();
                        if (!load.destination().equals(call.stopId())) continue;
                        if (load.kind() == Kind.FREIGHT_UNIT) {
                            double delivery = add(event.time(), freightIndex.get(load.id()).egressEstimateSeconds());
                            if (delivery <= input.horizonSeconds()) deliveries.put(load.id(), delivery);
                        }
                        iterator.remove();
                    }
                }
                double departure = Math.max(add(vehicle.service.originSeconds(), call.departureOffsetSeconds(), vehicle.delay),
                        add(event.time(), call.minimumStopDurationSeconds()));
                events.add(new StopEvent(departure, event.trip(), event.call(), EventType.BOARDING, event.time()));
                continue;
            }
            double pax = passengerMass(vehicle); int cargo = freightCount(vehicle);
            double boardedPassengers = 0; int boardedFreight = 0;
            WaitingQueue queue = queues.get(call.stopId());
            if (call.allowBoarding()) {
                for (Waiting demand : queue == null ? List.<Waiting>of() : queue.available(event.time())) {
                    double remaining = supply.capacity() - pax - cargo;
                    // This boarding loop cannot release capacity. All later masses
                    // would be zero; retain prune(), the capacity check and every stop event.
                    if (remaining <= 0) break;
                    if (demand.mass == 0 || demand.ready > event.time() || !vehicle.alighting.allowsAfter(event.call(), demand.destination)) continue;
                    double mass;
                    if (demand.kind == Kind.PASSENGER) mass = Math.min(demand.mass, Math.max(0, remaining));
                    else {
                        if (input.boardingWindow() != null && !input.boardingWindow().contains(event.time())) continue;
                        long freightLimit = (long) Math.floor(supply.capacity() * input.freightShareCap());
                        boolean allowed = remaining >= 1 && cargo + 1 <= freightLimit && supply.capacity() > 0
                                && pax / supply.capacity() <= input.passengerLoadGate();
                        mass = allowed ? 1 : 0;
                    }
                    if (mass == 0) continue;
                    demand.mass -= mass;
                    vehicle.loads.add(new Load(demand.id, demand.destination, demand.kind, mass));
                    if (demand.kind == Kind.PASSENGER) {
                        pax += mass; boardedPassengers += mass;
                        waited.put(demand.id, add(waited.get(demand.id), mass * Math.max(0, event.time() - demand.ready)));
                    } else {
                        cargo++; boardedFreight++; firstBoard.putIfAbsent(demand.id, event.time());
                    }
                }
                if (queue != null) queue.prune(event.time());
            }
            require(pax + cargo <= supply.capacity(), "Projected boarding exceeded native capacity");
            traces.add(new StopLoad(event.trip(), event.call(), event.arrivedAt(), event.time(), pax, cargo, boardedPassengers, boardedFreight));
            vehicle.delay = event.time() - vehicle.service.originSeconds() - call.departureOffsetSeconds(); finite(vehicle.delay);
            int next = event.call() + 1;
            if (next < supply.calls().size()) {
                double arrival = Math.max(event.time(), add(vehicle.service.originSeconds(), supply.calls().get(next).arrivalOffsetSeconds(), vehicle.delay));
                events.add(new StopEvent(arrival, event.trip(), next, EventType.ARRIVAL, arrival));
            }
        }
        var passengerResults = new LinkedHashMap<String, PassengerResult>();
        for (Passenger passenger : input.passengers()) {
            // The queue's nonnegative residual is authoritative. Re-summing several
            // fractional boardings and subtracting from the original mass can create
            // a spurious negative remainder through floating-point cancellation.
            double unboarded = passengerQueues.containsKey(passenger.id()) ? passengerQueues.get(passenger.id()).mass : 0;
            require(unboarded >= 0, "Boarded more passenger mass than projected");
            double censoredWait = add(waited.get(passenger.id()), unboarded * Math.max(0, input.horizonSeconds() - passenger.readyAtSeconds()));
            passengerResults.put(passenger.id(), new PassengerResult(passenger.mass(), censoredWait / passenger.mass(), unboarded, passenger.protectedCohort()));
        }
        var freightResults = new LinkedHashMap<String, FreightResult>();
        for (Freight unit : input.freight()) {
            Double board = firstBoard.get(unit.unitId()), delivery = deliveries.get(unit.unitId());
            Completion status = unit.aborted() ? Completion.ABORTED_OBSERVED : delivery == null ? Completion.UNDELIVERED_AT_HORIZON
                    : unit.deliveredAtSeconds().isPresent() ? Completion.DELIVERED_OBSERVED : Completion.DELIVERED_PREDICTED;
            double waiting = Math.max(0, (board == null ? input.horizonSeconds() : board) - unit.releasedAtSeconds());
            double lateness = Math.max(0, (delivery == null ? input.horizonSeconds() : delivery) - unit.deadlineSeconds());
            boolean missed = delivery == null ? unit.deadlineSeconds() <= input.horizonSeconds() : delivery > unit.deadlineSeconds();
            freightResults.put(unit.unitId(), new FreightResult(unit.releasedAtSeconds(), unit.deadlineSeconds(), optional(board), optional(delivery), status, waiting, lateness, missed));
        }
        return new Result(passengerResults, freightResults, traces, processed);
    }

    private static void enqueue(Map<String, WaitingQueue> queues, Waiting demand) { queues.computeIfAbsent(demand.origin, ignored -> new WaitingQueue()).add(demand); }
    private static double passengerMass(Vehicle vehicle) {
        double value = 0; for (Load load : vehicle.loads) if (load.kind() == Kind.PASSENGER) value = add(value, load.mass()); return value;
    }
    private static int freightCount(Vehicle vehicle) {
        int value = 0; for (Load load : vehicle.loads) if (load.kind() == Kind.FREIGHT_UNIT) value = Math.incrementExact(value); return value;
    }
    private static double occupancy(Vehicle vehicle) { return passengerMass(vehicle) + freightCount(vehicle); }
    private static OptionalDouble optional(Double value) { return value == null ? OptionalDouble.empty() : OptionalDouble.of(value); }
    private static double add(double... values) { double sum = 0; for (double value : values) sum += value; finite(sum); return sum; }
    private static void text(String value) { require(value != null && !value.isBlank(), "Nonblank identity required"); }
    private static void finite(double value) { require(Double.isFinite(value), "Nonfinite prediction arithmetic"); }
    private static void time(double value) { require(Double.isFinite(value) && value >= 0, "Invalid prediction time"); }
    private static void positive(double value) { require(Double.isFinite(value) && value > 0, "Invalid mass"); }
    private static void fraction(double value) { require(Double.isFinite(value) && value >= 0 && value <= 1, "Invalid admission fraction"); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
