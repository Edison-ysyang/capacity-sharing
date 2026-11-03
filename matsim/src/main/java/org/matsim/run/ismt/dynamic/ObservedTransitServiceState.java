package org.matsim.run.ismt.dynamic;

import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Pure, event-accounted service state; not a live vehicle or capacity model.
 * Registered routes/vehicle assignments are private validation inputs. A service
 * appears in the public state ONLY after an observed TransitDriverStarts event.
 * Stop events have no departure identifier and are associated with that observed
 * active work, never with the nearest future timetable departure.
 *
 * <p>Receipt/publication order is preserved. Time must not regress within one
 * vehicle's work, but independent vehicles need not have globally sorted event
 * times. Repeated stop IDs are resolved by their ordered call index. "Wenden"
 * is explicitly observed non-service work, not an invented passenger departure.
 * A driver's arrival ends its work only; native passenger relocation can follow
 * that event. It does NOT prove alighting, zero occupancy, vehicle readiness,
 * passenger completion or satisfaction of railway operating constraints.</p>
 *
 * <p>State construction is private and bound to this registry. Transitions are
 * immutable and applyAll rejects an invalid tail without changing the input.
 * ReceivedEvent is a value, not a source certificate: the composing layer must
 * consume uninterrupted closed StepViews from one observer/epoch. This component
 * alone proves neither source provenance nor a complete producer inventory.
 * It ignores non-service events and does not mutate MATSim IDs, vehicles or plans.
 * Exact native event classes are supported; replacement/subclass producers require
 * an explicit new mapping rather than silently sharing the same event-type label.
 */
public final class ObservedTransitServiceState {
    public record Service(TripKey trip, String vehicleId, List<String> orderedStopIds) {
        public Service {
            Objects.requireNonNull(trip, "trip"); text(vehicleId, "vehicle");
            require(!trip.line().equals("Wenden") && !trip.route().equals("Wenden")
                    && !trip.departure().equals("Wenden"), "Reserved Wenden identity");
            orderedStopIds = List.copyOf(orderedStopIds);
            require(!orderedStopIds.isEmpty(), "A registered service needs ordered stops");
            orderedStopIds.forEach(stop -> text(stop, "stop"));
        }
    }

    public enum Motion { STARTED, AT_STOP, BETWEEN_STOPS, ENDED, ABORTED }

    /** Empty trip means observed Wenden, not an unknown future passenger service. */
    public record WorkObservation(Optional<TripKey> trip, String vehicleId, String driverId,
                                  Motion motion, int nextStopIndex, Optional<String> atStop,
                                  Optional<String> lastObservedStop, double startedAtSeconds,
                                  double lastObservedAtSeconds, OptionalDouble lastFacilityDelaySeconds) {
        public WorkObservation {
            Objects.requireNonNull(trip); Objects.requireNonNull(motion); Objects.requireNonNull(atStop);
            Objects.requireNonNull(lastObservedStop); Objects.requireNonNull(lastFacilityDelaySeconds);
            text(vehicleId, "vehicle"); text(driverId, "driver");
            time(startedAtSeconds); time(lastObservedAtSeconds);
            require(lastObservedAtSeconds >= startedAtSeconds && nextStopIndex >= 0, "Invalid work state");
            require((motion == Motion.AT_STOP) == atStop.isPresent(), "Current stop is only known while at stop");
            if (lastFacilityDelaySeconds.isPresent()) finite(lastFacilityDelaySeconds.getAsDouble(), "delay");
        }
        public boolean active() { return motion != Motion.ENDED && motion != Motion.ABORTED; }
    }

    public static final class State {
        private final ObservedTransitServiceState owner;
        private final Map<TripKey, WorkObservation> observedServices;
        private final Map<String, WorkObservation> latestWorkByVehicle;
        private final Map<String, WorkObservation> latestWorkByDriver;
        private State(ObservedTransitServiceState owner, Map<TripKey, WorkObservation> services,
                      Map<String, WorkObservation> vehicles, Map<String, WorkObservation> drivers) {
            this.owner = owner;
            observedServices = Map.copyOf(services); latestWorkByVehicle = Map.copyOf(vehicles);
            latestWorkByDriver = Map.copyOf(drivers);
        }
        public Map<TripKey, WorkObservation> observedServices() { return observedServices; }
        public Map<String, WorkObservation> latestWorkByVehicle() { return latestWorkByVehicle; }
        public boolean hasObservedDriver(String personId) { return latestWorkByDriver.containsKey(personId); }
    }

    private final Map<TripKey, Service> services;
    private final Set<String> registeredVehicles;

    public ObservedTransitServiceState(List<Service> contracts) {
        Map<TripKey, Service> indexed = new HashMap<>();
        Set<String> vehicles = new HashSet<>();
        for (Service service : List.copyOf(contracts)) {
            require(indexed.putIfAbsent(service.trip(), service) == null, "Duplicate registered trip");
            vehicles.add(service.vehicleId());
        }
        require(!indexed.isEmpty(), "Explicit service registry required");
        services = Map.copyOf(indexed); registeredVehicles = Set.copyOf(vehicles);
    }

    public State initialState() { return new State(this, Map.of(), Map.of(), Map.of()); }

    public State apply(State before, ReceivedEvent event) { return applyAll(before, List.of(event)); }

    /** Pure batch transition: no partial result is published when a later event is invalid. */
    public State applyAll(State before, List<ReceivedEvent> events) {
        require(Objects.requireNonNull(before).owner == this, "State belongs to a different registry");
        Map<TripKey, WorkObservation> observed = before.observedServices;
        Map<String, WorkObservation> vehicles = before.latestWorkByVehicle;
        Map<String, WorkObservation> drivers = before.latestWorkByDriver;
        boolean copied = false;
        for (ReceivedEvent event : List.copyOf(events)) {
            // Most QSim events are link/score/passenger events. Do not clone the
            // whole service registry for each irrelevant event in a full-day run.
            boolean relevant = switch (event.eventType()) {
                case TransitDriverStartsEvent.EVENT_TYPE, VehicleArrivesAtFacilityEvent.EVENT_TYPE,
                     VehicleDepartsAtFacilityEvent.EVENT_TYPE -> true;
                case PersonArrivalEvent.EVENT_TYPE, PersonStuckEvent.EVENT_TYPE -> drivers.containsKey(attr(event, "person"));
                default -> false;
            };
            if (!relevant) continue;
            if (!copied) {
                observed = new HashMap<>(observed); vehicles = new HashMap<>(vehicles); drivers = new HashMap<>(drivers);
                copied = true;
            }
            switch (event.eventType()) {
                case TransitDriverStartsEvent.EVENT_TYPE -> {
                    exact(event, TransitDriverStartsEvent.class);
                    String driver = attr(event, TransitDriverStartsEvent.ATTRIBUTE_DRIVER_ID);
                    String vehicle = attr(event, TransitDriverStartsEvent.ATTRIBUTE_VEHICLE_ID);
                    require(registeredVehicles.contains(vehicle), "Unregistered transit vehicle " + vehicle);
                    TripKey key = new TripKey(attr(event, TransitDriverStartsEvent.ATTRIBUTE_TRANSIT_LINE_ID),
                            attr(event, TransitDriverStartsEvent.ATTRIBUTE_TRANSIT_ROUTE_ID),
                            attr(event, TransitDriverStartsEvent.ATTRIBUTE_DEPARTURE_ID));
                    boolean wenden = key.equals(new TripKey("Wenden", "Wenden", "Wenden"));
                    if (!wenden) {
                        Service service = services.get(key);
                        require(service != null && service.vehicleId().equals(vehicle), "Unknown trip or changed vehicle assignment");
                        require(!observed.containsKey(key), "A service cannot start twice");
                    }
                    WorkObservation previous = vehicles.get(vehicle);
                    if (previous != null) {
                        require(!previous.active(), "Previous work on this vehicle has not ended");
                        chronological(previous, event);
                    }
                    WorkObservation priorDriverWork = drivers.get(driver);
                    if (priorDriverWork != null) {
                        require(!priorDriverWork.active(), "Driver is still active");
                        chronological(priorDriverWork, event);
                    }
                    WorkObservation work = new WorkObservation(wenden ? Optional.empty() : Optional.of(key), vehicle,
                            driver, Motion.STARTED, 0, Optional.empty(), Optional.empty(), event.occurredAtSeconds(),
                            event.occurredAtSeconds(), OptionalDouble.empty());
                    store(work, observed, vehicles, drivers);
                }
                case VehicleArrivesAtFacilityEvent.EVENT_TYPE, VehicleDepartsAtFacilityEvent.EVENT_TYPE -> {
                    boolean arrival = event.eventType().equals(VehicleArrivesAtFacilityEvent.EVENT_TYPE);
                    exact(event, arrival ? VehicleArrivesAtFacilityEvent.class : VehicleDepartsAtFacilityEvent.class);
                    String vehicle = attr(event, "vehicle");
                    WorkObservation old = vehicles.get(vehicle);
                    require(old != null && old.active() && old.trip().isPresent(), "Stop event without an observed active service");
                    chronological(old, event);
                    Service service = services.get(old.trip().orElseThrow());
                    String stop = attr(event, "facility");
                    require(old.nextStopIndex() < service.orderedStopIds().size()
                            && stop.equals(service.orderedStopIds().get(old.nextStopIndex())), "Unexpected ordered stop call");
                    double delay = Double.parseDouble(attr(event, "delay")); finite(delay, "delay");
                    if (arrival) {
                        require(old.motion() == Motion.STARTED || old.motion() == Motion.BETWEEN_STOPS,
                                "Repeated arrival without a departure");
                    } else require(old.motion() == Motion.AT_STOP && old.atStop().orElseThrow().equals(stop),
                            "Departure without a matching arrival");
                    WorkObservation work = new WorkObservation(old.trip(), vehicle, old.driverId(),
                            arrival ? Motion.AT_STOP : Motion.BETWEEN_STOPS,
                            old.nextStopIndex() + (arrival ? 0 : 1), arrival ? Optional.of(stop) : Optional.empty(),
                            Optional.of(stop), old.startedAtSeconds(), event.occurredAtSeconds(), OptionalDouble.of(delay));
                    store(work, observed, vehicles, drivers);
                }
                case PersonArrivalEvent.EVENT_TYPE, PersonStuckEvent.EVENT_TYPE -> {
                    String driver = attr(event, "person");
                    WorkObservation old = drivers.get(driver);
                    if (old == null) continue; // A passenger arrival is not the driver's work ending.
                    boolean abort = event.eventType().equals(PersonStuckEvent.EVENT_TYPE);
                    exact(event, abort ? PersonStuckEvent.class : PersonArrivalEvent.class);
                    String vehicle = old.vehicleId();
                    require(old.active() && old.equals(vehicles.get(vehicle)), "Driver work is no longer active");
                    chronological(old, event);
                    if (!abort && old.trip().isPresent()) {
                        require(old.motion() == Motion.BETWEEN_STOPS
                                && old.nextStopIndex() == services.get(old.trip().orElseThrow()).orderedStopIds().size(),
                                "Driver ended before completing registered stop calls");
                    }
                    WorkObservation work = new WorkObservation(old.trip(), vehicle, driver,
                            abort ? Motion.ABORTED : Motion.ENDED, old.nextStopIndex(), Optional.empty(), old.lastObservedStop(),
                            old.startedAtSeconds(), event.occurredAtSeconds(), old.lastFacilityDelaySeconds());
                    store(work, observed, vehicles, drivers);
                }
                default -> { /* This service-only component does not infer passenger or order state. */ }
            }
        }
        return copied ? new State(this, observed, vehicles, drivers) : before;
    }

    private static void store(WorkObservation work, Map<TripKey, WorkObservation> observed,
                              Map<String, WorkObservation> vehicles, Map<String, WorkObservation> drivers) {
        work.trip().ifPresent(key -> observed.put(key, work)); vehicles.put(work.vehicleId(), work);
        drivers.put(work.driverId(), work);
    }
    private static void exact(ReceivedEvent event, Class<?> type) {
        require(event.eventClass().equals(type.getName()), "Unregistered producer for " + event.eventType());
    }
    private static String attr(ReceivedEvent event, String key) {
        String value = event.attributes().get(key); text(value, "event attribute " + key); return value;
    }
    private static void chronological(WorkObservation previous, ReceivedEvent event) {
        require(event.occurredAtSeconds() >= previous.lastObservedAtSeconds(), "Vehicle work event time regressed");
    }
    private static void text(String value, String what) { require(value != null && !value.isBlank(), "Missing " + what); }
    private static void time(double time) { finite(time, "time"); require(time >= 0, "Negative time"); }
    private static void finite(double value, String what) { require(Double.isFinite(value), "Non-finite " + what); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
