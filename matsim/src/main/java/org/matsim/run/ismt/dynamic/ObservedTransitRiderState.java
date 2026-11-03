package org.matsim.run.ismt.dynamic;

import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonContinuesInVehicleEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.mobsim.qsim.pt.PersonEntersPtVehicleEvent;
import org.matsim.core.mobsim.qsim.pt.PersonLeavesPtVehicleEvent;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingDeniedDetailEvent;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState.Motion;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState.Service;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState.WorkObservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Unwired, pure PT rider event accounting, not another boarding/capacity allocator.
 * Only observed actors appear in State. Static actor/service/continuation contracts
 * are private. Freight release is checked per unit; a release is not a stop arrival.
 * Ordinary car enter/leave events (including a transit driver's vehicle entry) are
 * ignored. Exact native PT producers and their actual receipt order are retained.
 * There is no artificial leave-before-enter sorting or capacity threshold here.
 *
 * <p>Vehicle arrival can precede a person's waiting event. A native denial can be
 * published at the end of a stop call after the person has boarded; it adds a fact,
 * not a queue membership. Diagnostic detail is not a second denial and need not
 * arrive next to the native denial. Activity facilities are never PT-stop proxies.
 *
 * <p>Driver arrival ends work, not rider membership. Until a continuation event the
 * source membership is retained as AWAITING_CONTINUATION_EVENT. A facility-bearing
 * PersonContinuesInVehicle transfers event-accounted membership to the named target
 * vehicle, possibly before its physical addPassenger call. Candidate successors
 * come from explicit contracts, never a nearest-departure guess. Legacy/synthetic
 * same-vehicle records with the OLD line/route are accepted only against those
 * contracts; the corrected native producer reports the target line/route. This
 * compatibility path is not evidence that native old-route emission occurs.
 * Only an actual successor START resolves
 * a pending trip; an already observed successor still at its origin can also resolve
 * it. Wenden never resolves a passenger trip. Abort preserves unresolved membership.
 * Event-accounted occupancy is therefore NOT instantaneous physical occupancy.
 *
 * <p>The composing layer must supply correctly paired service before/after states,
 * release state, uninterrupted closed prefix and epoch from the same assembly.
 * ReceivedEvent is not an unforgeable provenance token. Owner binding prevents
 * forged/imported rider States but does not certify those external sources. This
 * component performs no QSim calls, modifies no native object, and makes no producer,
 * signal-safety, full-demand or complete M/T comparison claim.</p>
 */
public final class ObservedTransitRiderState {
    public enum Kind { PASSENGER, FREIGHT_UNIT }
    public enum Phase {
        WAITING, RIDING, ALIGHTED, AWAITING_CONTINUATION_EVENT, PENDING_CONTINUATION, UNRESOLVED_ABORT
    }

    public record DenialObservation(long closedSequence, TripKey trip, String vehicleId,
                                    String stopId, double occurredAtSeconds) {
        public DenialObservation {
            require(closedSequence > 0, "Positive denial event sequence required");
            Objects.requireNonNull(trip); text(vehicleId, "vehicle"); text(stopId, "stop"); time(occurredAtSeconds);
        }
    }

    /** Candidates are registered timetable identities, not claims that they have started. */
    public record PendingContinuation(TripKey sourceTrip, String fromVehicleId, String targetVehicleId,
                                      String targetStopId, Set<TripKey> candidates, double observedAtSeconds) {
        public PendingContinuation {
            Objects.requireNonNull(sourceTrip); text(fromVehicleId, "from vehicle"); text(targetVehicleId, "target vehicle");
            text(targetStopId, "target stop"); candidates = Set.copyOf(candidates);
            require(!candidates.isEmpty(), "At least one registered continuation candidate required"); time(observedAtSeconds);
        }
    }

    /** lastInteractionStopId is a boarding/alighting/continuation stop, not a live position. */
    public record RiderObservation(String personId, Kind kind, Phase phase,
                                   Optional<String> lastInteractionStopId, Optional<String> destinationStopId,
                                   Optional<String> vehicleId, Optional<TripKey> trip,
                                   Optional<PendingContinuation> pendingContinuation,
                                   double firstObservedAtSeconds, double lastObservedAtSeconds,
                                   List<DenialObservation> nativeDenials) {
        public RiderObservation {
            text(personId, "person"); Objects.requireNonNull(kind); Objects.requireNonNull(phase);
            Objects.requireNonNull(lastInteractionStopId); Objects.requireNonNull(destinationStopId);
            Objects.requireNonNull(vehicleId); Objects.requireNonNull(trip); Objects.requireNonNull(pendingContinuation);
            lastInteractionStopId.ifPresent(value -> text(value, "stop"));
            destinationStopId.ifPresent(value -> text(value, "destination")); vehicleId.ifPresent(value -> text(value, "vehicle"));
            time(firstObservedAtSeconds); time(lastObservedAtSeconds);
            require(lastObservedAtSeconds >= firstObservedAtSeconds, "Rider time regressed");
            nativeDenials = List.copyOf(nativeDenials);
            if (phase == Phase.WAITING || phase == Phase.ALIGHTED) require(vehicleId.isEmpty() && trip.isEmpty()
                    && pendingContinuation.isEmpty() && lastInteractionStopId.isPresent(), "Invalid off-vehicle phase");
            if (phase == Phase.RIDING || phase == Phase.AWAITING_CONTINUATION_EVENT) require(vehicleId.isPresent()
                    && trip.isPresent() && pendingContinuation.isEmpty(), "Missing observed rider service");
            if (phase == Phase.PENDING_CONTINUATION) require(vehicleId.isPresent() && trip.isEmpty()
                    && pendingContinuation.isPresent(), "Pending trip must not be guessed");
        }
        public long nativeDeniedCount() { return nativeDenials.size(); }
    }

    /** One actually observed passenger PT-leg arrival, including subsequent transfer legs. */
    public record PassengerWaitingArrival(String personId, String stopId, String destinationStopId,
                                          double occurredAtSeconds, long closedSequence) {
        public PassengerWaitingArrival {
            text(personId, "person"); text(stopId, "stop"); text(destinationStopId, "destination");
            time(occurredAtSeconds); require(closedSequence > 0, "Positive waiting event sequence required");
        }
    }

    private record WaitingEpisode(String stop, String destination, double started, long closedSequence,
                                  OptionalDouble ended) {
        PassengerWaitingArrival passengerArrival(String person) {
            return new PassengerWaitingArrival(person, stop, destination, started, closedSequence);
        }
    }

    public static final class State {
        private final ObservedTransitRiderState owner;
        private final ObservationStringMap<RiderObservation> riders;
        private final ObservationStringMap<List<WaitingEpisode>> waitingHistory;
        private State(ObservedTransitRiderState owner, ObservationStringMap<RiderObservation> riders,
                      ObservationStringMap<List<WaitingEpisode>> waitingHistory) {
            this.owner = owner; this.riders = riders; this.waitingHistory = waitingHistory;
        }
        public Map<String, RiderObservation> riders() { return riders; }

        /**
         * Complete observed passenger waiting-arrival history in closed receipt order.
         * Boarding, alighting and abort do not erase arrivals; freight is excluded.
         * These immutable values are derived from this privately constructed owner-bound
         * State, not from population plans or a future demand registry.
         */
        public List<PassengerWaitingArrival> passengerWaitingArrivals() {
            var arrivals = new ArrayList<PassengerWaitingArrival>();
            waitingHistory.forEach((person, episodes) -> {
                if (riders.get(person).kind() == Kind.PASSENGER)
                    episodes.forEach(episode -> arrivals.add(episode.passengerArrival(person)));
            });
            arrivals.sort(java.util.Comparator.comparingLong(PassengerWaitingArrival::closedSequence));
            return List.copyOf(arrivals);
        }

        /** Current PT-leg waiting episodes, not the actor's first-ever observed arrival. */
        public Map<String, PassengerWaitingArrival> currentlyWaitingPassengers() {
            var waiting = new LinkedHashMap<String, PassengerWaitingArrival>();
            riders.forEach((person, rider) -> {
                if (rider.kind() != Kind.PASSENGER || rider.phase() != Phase.WAITING) return;
                var episodes = waitingHistory.get(person);
                require(episodes != null && !episodes.isEmpty(), "Waiting passenger lacks an observed episode");
                var current = episodes.getLast();
                require(current.ended().isEmpty() && rider.lastInteractionStopId().orElseThrow().equals(current.stop())
                        && rider.destinationStopId().orElseThrow().equals(current.destination()), "Waiting episode disagrees with rider state");
                waiting.put(person, current.passengerArrival(person));
            });
            return Map.copyOf(waiting);
        }

        /** Includes unresolved retained memberships; never use this map to admit or reject a passenger. */
        public Map<String, Set<String>> eventAccountedOccupantsByVehicle() {
            var occupants = new LinkedHashMap<String, Set<String>>();
            riders.values().forEach(rider -> rider.vehicleId().ifPresent(vehicle ->
                    occupants.computeIfAbsent(vehicle, ignored -> new HashSet<>()).add(rider.personId())));
            var copy = new LinkedHashMap<String, Set<String>>();
            occupants.forEach((vehicle, people) -> copy.put(vehicle, Set.copyOf(people)));
            return Collections.unmodifiableMap(copy);
        }
    }

    private final Map<TripKey, Service> services;
    private final Set<String> vehicles;
    private final Set<String> stops;
    private final Map<String, Kind> actors;
    private final Map<TripKey, List<TripKey>> legalContinuations;

    public ObservedTransitRiderState(List<Service> services, Set<String> passengerIds, Set<String> freightUnitIds,
                                     Map<TripKey, List<TripKey>> legalContinuations) {
        var indexed = new LinkedHashMap<TripKey, Service>();
        var vehicleIds = new HashSet<String>(); var stopIds = new HashSet<String>();
        for (Service service : List.copyOf(services)) {
            require(indexed.putIfAbsent(service.trip(), service) == null, "Duplicate service");
            vehicleIds.add(service.vehicleId()); stopIds.addAll(service.orderedStopIds());
        }
        require(!indexed.isEmpty(), "Explicit service registry required");
        this.services = Map.copyOf(indexed); this.vehicles = Set.copyOf(vehicleIds); this.stops = Set.copyOf(stopIds);
        var classified = new LinkedHashMap<String, Kind>();
        for (String person : Set.copyOf(passengerIds)) { text(person, "passenger"); classified.put(person, Kind.PASSENGER); }
        for (String person : Set.copyOf(freightUnitIds)) {
            text(person, "freight unit"); require(classified.putIfAbsent(person, Kind.FREIGHT_UNIT) == null, "Actor classes overlap");
        }
        actors = Map.copyOf(classified);
        var chains = new LinkedHashMap<TripKey, List<TripKey>>();
        Objects.requireNonNull(legalContinuations).forEach((source, targets) -> {
            require(indexed.containsKey(source), "Unregistered continuation source");
            var copy = List.copyOf(targets); var distinct = new HashSet<TripKey>();
            for (TripKey target : copy) require(indexed.containsKey(target) && !target.equals(source) && distinct.add(target),
                    "Invalid/duplicate continuation target");
            chains.put(source, copy);
        });
        this.legalContinuations = Map.copyOf(chains);
    }

    public State initialState() { return new State(this, ObservationStringMap.empty(), ObservationStringMap.empty()); }

    public State apply(State before, ReceivedEvent event, ObservedTransitServiceState.State serviceBefore,
                       ObservedTransitServiceState.State serviceAfter, KnownFreightReleaseState.State releasedAfter) {
        require(Objects.requireNonNull(before).owner == this, "State belongs to another rider registry");
        Objects.requireNonNull(event); Objects.requireNonNull(serviceBefore); Objects.requireNonNull(serviceAfter);
        Objects.requireNonNull(releasedAfter);
        // Most QSim events are unrelated (links, scores, ordinary car/walk work).
        // Do not clone the whole population state for these no-op observations.
        boolean relevant = switch (event.eventType()) {
            case AgentWaitingForPtEvent.EVENT_TYPE, PersonEntersPtVehicleEvent.EVENT_TYPE,
                    PersonLeavesPtVehicleEvent.EVENT_TYPE, BoardingDeniedEvent.EVENT_TYPE,
                    TransitBoardingDeniedDetailEvent.EVENT_TYPE, PersonContinuesInVehicleEvent.EVENT_TYPE,
                    TransitDriverStartsEvent.EVENT_TYPE -> true;
            case PersonArrivalEvent.EVENT_TYPE, PersonStuckEvent.EVENT_TYPE -> {
                String person = event.attributes().get("person");
                yield "pt".equals(event.attributes().get("legMode")) || person != null && (serviceAfter.hasObservedDriver(person)
                        || event.eventType().equals(PersonStuckEvent.EVENT_TYPE) && hasActivePtObservation(before.riders.get(person)));
            }
            default -> false;
        };
        if (!relevant) return before;
        if (event.eventType().equals(TransitBoardingDeniedDetailEvent.EVENT_TYPE)) {
            validateDetail(event, releasedAfter);
            return before;
        }
        var riders = before.riders.mutableCopy();
        var history = before.waitingHistory.mutableCopy();
        switch (event.eventType()) {
            case AgentWaitingForPtEvent.EVENT_TYPE -> {
                exact(event, AgentWaitingForPtEvent.class);
                String person = attr(event, "person");
                require(person.equals(attr(event, "agent")), "Waiting person/agent mismatch");
                Kind kind = actor(person, event, releasedAfter);
                String stop = knownStop(attr(event, "atStop")); String destination = knownStop(attr(event, "destinationStop"));
                RiderObservation old = riders.get(person); chronological(old, event);
                require(old == null || old.phase() == Phase.ALIGHTED, "Waiting event cannot reset an existing rider state");
                riders.put(person, new RiderObservation(person, kind, Phase.WAITING, Optional.of(stop), Optional.of(destination),
                        Optional.empty(), Optional.empty(), Optional.empty(), old == null ? event.occurredAtSeconds() : old.firstObservedAtSeconds(),
                        event.occurredAtSeconds(), old == null ? List.of() : old.nativeDenials()));
                var episodes = new ArrayList<>(history.getOrDefault(person, List.of()));
                episodes.add(new WaitingEpisode(stop, destination, event.occurredAtSeconds(), event.closedSequence(), OptionalDouble.empty()));
                history.put(person, List.copyOf(episodes));
            }
            case PersonEntersPtVehicleEvent.EVENT_TYPE, PersonLeavesPtVehicleEvent.EVENT_TYPE -> {
                boolean entering = event.eventType().equals(PersonEntersPtVehicleEvent.EVENT_TYPE);
                exact(event, entering ? PersonEntersPtVehicleEvent.class : PersonLeavesPtVehicleEvent.class);
                String person = attr(event, "person"); actor(person, event, releasedAfter);
                RiderObservation old = requireRider(riders, person); chronological(old, event);
                String vehicle = knownVehicle(attr(event, "vehicle"));
                WorkObservation work = atStop(serviceAfter, vehicle, event);
                TripKey trip = work.trip().orElseThrow(); String stop = work.atStop().orElseThrow();
                require(trip.line().equals(attr(event, "transitLine")) && trip.route().equals(attr(event, "transitRoute")), "PT event route disagrees with observed work");
                if (entering) {
                    require(old.phase() == Phase.WAITING && old.lastInteractionStopId().orElseThrow().equals(stop), "Boarding without observed waiting at this stop");
                    closeWaiting(history, person, event.occurredAtSeconds());
                    riders.put(person, changed(old, Phase.RIDING, Optional.of(stop), Optional.of(vehicle), Optional.of(trip), Optional.empty(), event, old.nativeDenials()));
                } else {
                    require(old.phase() == Phase.RIDING && old.vehicleId().orElseThrow().equals(vehicle)
                            && old.trip().orElseThrow().equals(trip), "Alighting without a matching observed ride");
                    require(old.destinationStopId().orElseThrow().equals(stop), "Alighting stop differs from this PT leg's observed destination");
                    riders.put(person, changed(old, Phase.ALIGHTED, Optional.of(stop), Optional.empty(), Optional.empty(), Optional.empty(), event, old.nativeDenials()));
                }
            }
            case BoardingDeniedEvent.EVENT_TYPE -> {
                exact(event, BoardingDeniedEvent.class);
                String person = attr(event, "person"); actor(person, event, releasedAfter);
                RiderObservation old = requireRider(riders, person); chronological(old, event);
                WorkObservation work = atStop(serviceAfter, knownVehicle(attr(event, "vehicle")), event);
                String stop = work.atStop().orElseThrow();
                boolean overlappingWait = history.getOrDefault(person, List.of()).stream().anyMatch(episode ->
                        episode.stop().equals(stop) && episode.started() <= event.occurredAtSeconds()
                                && (episode.ended().isEmpty() || episode.ended().getAsDouble() >= work.lastObservedAtSeconds()));
                require(overlappingWait, "Denial without an observed wait overlapping this stop call");
                require(old.nativeDenials().stream().noneMatch(denial -> denial.closedSequence() == event.closedSequence()), "Native denial event applied twice");
                var denials = new ArrayList<>(old.nativeDenials());
                denials.add(new DenialObservation(event.closedSequence(), work.trip().orElseThrow(), work.vehicleId(), stop, event.occurredAtSeconds()));
                riders.put(person, changed(old, old.phase(), old.lastInteractionStopId(), old.vehicleId(), old.trip(), old.pendingContinuation(), event, denials));
            }
            case PersonContinuesInVehicleEvent.EVENT_TYPE -> continueRider(riders, event, serviceAfter, releasedAfter);
            case TransitDriverStartsEvent.EVENT_TYPE -> startWork(riders, event, serviceAfter);
            case PersonArrivalEvent.EVENT_TYPE, PersonStuckEvent.EVENT_TYPE -> {
                boolean abort = event.eventType().equals(PersonStuckEvent.EVENT_TYPE);
                String person = attr(event, "person");
                if (serviceAfter.hasObservedDriver(person)) {
                    exact(event, abort ? PersonStuckEvent.class : PersonArrivalEvent.class);
                    require(!actors.containsKey(person), "A transit driver is registered as a rider");
                    endWork(riders, event, person, abort, serviceBefore, serviceAfter);
                } else if (abort && (hasActivePtObservation(riders.get(person)) || "pt".equals(event.attributes().get("legMode")))) {
                    exact(event, PersonStuckEvent.class); Kind kind = actor(person, event, releasedAfter);
                    RiderObservation old = riders.get(person); chronological(old, event);
                    require(old == null || old.phase() != Phase.UNRESOLVED_ABORT, "Duplicate rider abort");
                    if (old != null && old.phase() == Phase.WAITING) closeWaiting(history, person, event.occurredAtSeconds());
                    riders.put(person, old == null ? new RiderObservation(person, kind, Phase.UNRESOLVED_ABORT,
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), event.occurredAtSeconds(), event.occurredAtSeconds(), List.of())
                            : changed(old, Phase.UNRESOLVED_ABORT, old.lastInteractionStopId(), old.vehicleId(), old.trip(), old.pendingContinuation(), event, old.nativeDenials()));
                } else if (!abort && "pt".equals(event.attributes().get("legMode"))) {
                    exact(event, PersonArrivalEvent.class); actor(person, event, releasedAfter);
                    RiderObservation old = requireRider(riders, person); chronological(old, event);
                    require(old.phase() == Phase.ALIGHTED, "Passenger PT arrival without observed alighting");
                    riders.put(person, changed(old, old.phase(), old.lastInteractionStopId(), old.vehicleId(), old.trip(), old.pendingContinuation(), event, old.nativeDenials()));
                }
            }
            default -> { return before; }
        }
        return new State(this, riders.snapshot(), history.snapshot());
    }

    private void continueRider(Map<String, RiderObservation> riders, ReceivedEvent event,
                               ObservedTransitServiceState.State serviceState, KnownFreightReleaseState.State releases) {
        exact(event, PersonContinuesInVehicleEvent.class);
        String person = attr(event, "person"); actor(person, event, releases);
        RiderObservation old = requireRider(riders, person); chronological(old, event);
        require(old.phase() == Phase.AWAITING_CONTINUATION_EVENT, "Continuation without an ended, retained source ride");
        TripKey source = old.trip().orElseThrow(); String fromVehicle = knownVehicle(attr(event, "fromVehicle"));
        require(old.vehicleId().orElseThrow().equals(fromVehicle), "Continuation source vehicle mismatch");
        WorkObservation sourceWork = serviceState.observedServices().get(source);
        require(sourceWork != null && sourceWork.motion() == Motion.ENDED, "Source work has not ended normally");
        registered(sourceWork); require(event.occurredAtSeconds() >= sourceWork.lastObservedAtSeconds(), "Continuation before source end");
        String targetVehicle = knownVehicle(attr(event, "vehicle")); String stop = knownStop(attr(event, "facility"));
        String line = attr(event, "transitLine"); String route = attr(event, "transitRoute");
        boolean reportsOldRoute = fromVehicle.equals(targetVehicle) && source.line().equals(line) && source.route().equals(route);
        var candidates = new HashSet<TripKey>();
        for (TripKey target : legalContinuations.getOrDefault(source, List.of())) {
            Service contract = services.get(target); WorkObservation observed = serviceState.observedServices().get(target);
            if (contract.vehicleId().equals(targetVehicle) && contract.orderedStopIds().getFirst().equals(stop)
                    && contract.orderedStopIds().contains(old.destinationStopId().orElseThrow())
                    && (reportsOldRoute || target.line().equals(line) && target.route().equals(route))
                    && (observed == null || observed.active())) candidates.add(target);
        }
        require(!candidates.isEmpty(), "No registered continuation matches target vehicle, facility and route");
        var pending = new PendingContinuation(source, fromVehicle, targetVehicle, stop, candidates, event.occurredAtSeconds());
        WorkObservation activeTarget = serviceState.latestWorkByVehicle().get(targetVehicle);
        if (activeTarget != null && activeTarget.active() && activeTarget.trip().isPresent()) {
            TripKey target = activeTarget.trip().orElseThrow(); registered(activeTarget);
            require(candidates.contains(target) && activeTarget.nextStopIndex() == 0
                    && (activeTarget.motion() == Motion.STARTED || activeTarget.motion() == Motion.AT_STOP)
                    && activeTarget.startedAtSeconds() <= event.occurredAtSeconds(), "Continuation target is not an observed successor at its origin");
            riders.put(person, changed(old, Phase.RIDING, Optional.of(stop), Optional.of(targetVehicle), Optional.of(target), Optional.empty(), event, old.nativeDenials()));
        } else {
            riders.put(person, changed(old, Phase.PENDING_CONTINUATION, Optional.of(stop), Optional.of(targetVehicle), Optional.empty(), Optional.of(pending), event, old.nativeDenials()));
        }
    }

    private void startWork(Map<String, RiderObservation> riders, ReceivedEvent event, ObservedTransitServiceState.State serviceState) {
        exact(event, TransitDriverStartsEvent.class);
        String vehicle = knownVehicle(attr(event, "vehicleId")); String driver = attr(event, "driverId");
        require(!actors.containsKey(driver), "A transit driver is registered as a rider");
        WorkObservation work = serviceState.latestWorkByVehicle().get(vehicle);
        require(work != null && work.motion() == Motion.STARTED && work.driverId().equals(driver)
                && work.startedAtSeconds() == event.occurredAtSeconds(), "Missing matching observed START transition");
        TripKey announced = new TripKey(attr(event, "transitLineId"), attr(event, "transitRouteId"), attr(event, "departureId"));
        require(work.trip().map(announced::equals).orElseGet(() -> announced.equals(new TripKey("Wenden", "Wenden", "Wenden"))),
                "START fields disagree with observed work");
        registered(work);
        if (work.trip().isEmpty()) return; // Wenden is not a passenger successor.
        for (RiderObservation old : List.copyOf(riders.values())) {
            if (old.phase() != Phase.PENDING_CONTINUATION || !old.vehicleId().orElseThrow().equals(vehicle)) continue;
            chronological(old, event);
            PendingContinuation pending = old.pendingContinuation().orElseThrow();
            require(pending.candidates().contains(work.trip().orElseThrow()), "A different service started on a pending target vehicle");
            riders.put(old.personId(), changed(old, Phase.RIDING, Optional.of(pending.targetStopId()), Optional.of(vehicle), work.trip(), Optional.empty(), event, old.nativeDenials()));
        }
    }

    private void endWork(Map<String, RiderObservation> riders, ReceivedEvent event, String driver, boolean abort,
                         ObservedTransitServiceState.State before, ObservedTransitServiceState.State after) {
        List<WorkObservation> activePrevious = before.latestWorkByVehicle().values().stream()
                .filter(value -> value.driverId().equals(driver) && value.active()).toList();
        require(activePrevious.size() == 1, "Exactly one previously active driver work required");
        WorkObservation previous = activePrevious.getFirst();
        WorkObservation work = after.latestWorkByVehicle().get(previous.vehicleId());
        require(work != null && work.driverId().equals(driver) && work.trip().equals(previous.trip())
                && work.motion() == (abort ? Motion.ABORTED : Motion.ENDED)
                && work.lastObservedAtSeconds() == event.occurredAtSeconds(), "Missing matching observed work end");
        registered(work);
        for (RiderObservation old : List.copyOf(riders.values())) {
            if (old.vehicleId().isEmpty() || !old.vehicleId().get().equals(work.vehicleId()) || old.phase() == Phase.UNRESOLVED_ABORT) continue;
            boolean thisRide = old.trip().isPresent() && old.trip().equals(work.trip());
            if (abort && (thisRide || old.phase() == Phase.PENDING_CONTINUATION)) {
                chronological(old, event);
                riders.put(old.personId(), changed(old, Phase.UNRESOLVED_ABORT, old.lastInteractionStopId(), old.vehicleId(), old.trip(), old.pendingContinuation(), event, old.nativeDenials()));
            } else if (!abort && thisRide && old.phase() == Phase.RIDING) {
                chronological(old, event);
                riders.put(old.personId(), changed(old, Phase.AWAITING_CONTINUATION_EVENT, old.lastInteractionStopId(), old.vehicleId(), old.trip(), Optional.empty(), event, old.nativeDenials()));
            }
        }
    }

    private Kind actor(String person, ReceivedEvent event, KnownFreightReleaseState.State releases) {
        Kind kind = actors.get(person); require(kind != null, "Unregistered PT person " + person);
        if (kind == Kind.FREIGHT_UNIT) {
            List<Double> released = releases.knownOrders().values().stream().map(order -> order.releasedUnits().get(person)).filter(Objects::nonNull).toList();
            require(released.size() == 1 && released.getFirst() <= event.occurredAtSeconds(), "Freight unit has not actually released before this PT event");
        }
        return kind;
    }

    private void validateDetail(ReceivedEvent event, KnownFreightReleaseState.State releases) {
        exact(event, TransitBoardingDeniedDetailEvent.class);
        // Diagnostic order is independent; it changes neither rider time nor phase/count.
        actor(attr(event, "person"), event, releases);
        String vehicle = knownVehicle(attr(event, "vehicle")); knownStop(attr(event, "stop"));
        String departure = event.attributes().get("departure");
        if (departure != null && !departure.isBlank()) {
            TripKey trip = new TripKey(attr(event, "transitLine"), attr(event, "transitRoute"), departure);
            require(services.containsKey(trip) && services.get(trip).vehicleId().equals(vehicle), "Unregistered diagnostic service");
        }
    }

    private WorkObservation atStop(ObservedTransitServiceState.State state, String vehicle, ReceivedEvent event) {
        WorkObservation work = state.latestWorkByVehicle().get(vehicle);
        require(work != null && work.motion() == Motion.AT_STOP && work.trip().isPresent(), "PT interaction without observed service at stop");
        registered(work); require(event.occurredAtSeconds() >= work.lastObservedAtSeconds(), "PT interaction predates observed arrival");
        return work;
    }

    private void registered(WorkObservation work) {
        knownVehicle(work.vehicleId());
        if (work.trip().isPresent()) {
            Service service = services.get(work.trip().get());
            require(service != null && service.vehicleId().equals(work.vehicleId()), "Service state disagrees with private rider registry");
            work.atStop().ifPresent(stop -> require(work.nextStopIndex() < service.orderedStopIds().size()
                    && service.orderedStopIds().get(work.nextStopIndex()).equals(stop), "Service stop state disagrees with rider registry"));
        }
    }

    private static RiderObservation changed(RiderObservation old, Phase phase, Optional<String> stop,
                                            Optional<String> vehicle, Optional<TripKey> trip, Optional<PendingContinuation> pending,
                                            ReceivedEvent event, List<DenialObservation> denials) {
        return new RiderObservation(old.personId(), old.kind(), phase, stop, old.destinationStopId(), vehicle, trip, pending,
                old.firstObservedAtSeconds(), event.occurredAtSeconds(), denials);
    }

    private static void closeWaiting(Map<String, List<WaitingEpisode>> history, String person, double time) {
        var episodes = new ArrayList<>(history.getOrDefault(person, List.of()));
        require(!episodes.isEmpty() && episodes.getLast().ended().isEmpty(), "No open waiting episode");
        WaitingEpisode old = episodes.getLast(); require(time >= old.started(), "Waiting time regressed");
        episodes.set(episodes.size() - 1, new WaitingEpisode(old.stop(), old.destination(), old.started(), old.closedSequence(), OptionalDouble.of(time)));
        history.put(person, List.copyOf(episodes));
    }

    private static RiderObservation requireRider(Map<String, RiderObservation> riders, String person) {
        RiderObservation rider = riders.get(person); require(rider != null, "No previous PT observation for " + person); return rider;
    }
    private static boolean hasActivePtObservation(RiderObservation rider) {
        return rider != null && rider.phase() != Phase.ALIGHTED && rider.phase() != Phase.UNRESOLVED_ABORT;
    }
    private String knownVehicle(String vehicle) { require(vehicles.contains(vehicle), "Unregistered PT vehicle " + vehicle); return vehicle; }
    private String knownStop(String stop) { require(stops.contains(stop), "Unregistered PT stop " + stop); return stop; }
    private static String attr(ReceivedEvent event, String key) { String value = event.attributes().get(key); text(value, key); return value; }
    private static void chronological(RiderObservation old, ReceivedEvent event) {
        require(old == null || event.occurredAtSeconds() >= old.lastObservedAtSeconds(), "Person event time regressed");
    }
    private static void exact(ReceivedEvent event, Class<?> type) {
        require(event.eventClass().equals(type.getName()), "Unregistered event producer");
        require(event.eventType().equals(attr(event, Event.ATTRIBUTE_TYPE)), "Event type attribute mismatch");
        double attributeTime = Double.parseDouble(attr(event, Event.ATTRIBUTE_TIME)); time(attributeTime);
        require(attributeTime == event.occurredAtSeconds(), "Event time attribute mismatch");
    }
    private static void text(String value, String field) { require(value != null && !value.isBlank(), "Missing " + field); }
    private static void time(double value) { require(Double.isFinite(value) && value >= 0, "Finite nonnegative time required"); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
