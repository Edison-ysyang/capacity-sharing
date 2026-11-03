package org.matsim.run.ismt.dynamic;

import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.mobsim.qsim.pt.PersonEntersPtVehicleEvent;
import org.matsim.core.mobsim.qsim.pt.PersonLeavesPtVehicleEvent;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Pure, immutable account of actual journeys of already released freight units.
 * Full contracts remain assembly-private. A release is not a stop arrival, an
 * alighting is not a delivery, and the first PT boarding survives later transfers.
 * Delivery uses ActivityStart.actType.startsWith(the explicitly registered prefix),
 * exactly the activity-prefix rule used by CoTransportRuntimeMonitor. No prefix is
 * inferred from an origin/release activity. No missing time is replaced with zero.
 *
 * <p>The composer must pair each event with its release-after state from the same
 * closed prefix. This reducer checks consistent release deltas, event envelopes,
 * per-unit chronology and its own State owner; public ReceivedEvent values do not
 * themselves certify native provenance or the event-source epoch. Actual service,
 * stop and boarding eligibility are checked by the separate rider/service reducers.
 * A driver abort alone is not invented as a unit PersonStuck event. This is neither
 * a terminal completeness certificate nor a second vehicle-capacity authority.</p>
 */
public final class KnownFreightJourneyState {
    /** Assembly-only input; never exposed in a policy snapshot. */
    public record Contract(String orderId, List<String> unitIds, String deliveryActivityPrefix) {
        public Contract {
            text(orderId, "orderId"); text(deliveryActivityPrefix, "deliveryActivityPrefix");
            unitIds = List.copyOf(Objects.requireNonNull(unitIds));
            require(!unitIds.isEmpty(), "A journey contract needs at least one unit");
            var seen = new HashSet<String>();
            for (String unit : unitIds) { text(unit, "unitId"); require(seen.add(unit), "Duplicate unit in journey contract"); }
        }
    }

    public enum Phase { RELEASED, WAITING, RIDING, ALIGHTED, DELIVERED, ABORTED }

    /** Times are actual closed-event times, not access/egress forecasts. */
    public record Journey(String orderId, String unitId, Phase phase, double releasedAtSeconds,
                          OptionalDouble firstWaitingAtSeconds, OptionalDouble currentWaitingAtSeconds,
                          OptionalDouble firstBoardedAtSeconds, OptionalDouble lastAlightedAtSeconds,
                          OptionalDouble deliveredAtSeconds, OptionalDouble abortedAtSeconds,
                          Optional<String> ridingVehicleId, double lastObservedAtSeconds,
                          long lastClosedSequence) {
        public Journey {
            text(orderId, "orderId"); text(unitId, "unitId"); Objects.requireNonNull(phase);
            time(releasedAtSeconds); time(lastObservedAtSeconds);
            require(lastObservedAtSeconds >= releasedAtSeconds && lastClosedSequence > 0, "Invalid journey chronology");
            for (OptionalDouble value : List.of(firstWaitingAtSeconds, currentWaitingAtSeconds,
                    firstBoardedAtSeconds, lastAlightedAtSeconds, deliveredAtSeconds, abortedAtSeconds)) {
                Objects.requireNonNull(value);
                if (value.isPresent()) require(time(value.getAsDouble()) >= releasedAtSeconds
                        && value.getAsDouble() <= lastObservedAtSeconds, "Journey time lies outside observed interval");
            }
            Objects.requireNonNull(ridingVehicleId); ridingVehicleId.ifPresent(value -> text(value, "vehicle"));
            require(currentWaitingAtSeconds.isPresent() == (phase == Phase.WAITING), "Invalid current waiting episode");
            require(ridingVehicleId.isPresent() == (phase == Phase.RIDING), "Invalid current riding vehicle");
            require(deliveredAtSeconds.isPresent() == (phase == Phase.DELIVERED), "Invalid delivery phase");
            require(abortedAtSeconds.isPresent() == (phase == Phase.ABORTED), "Invalid abort phase");
        }
    }

    private record Release(String orderId, double at) { }

    /** Owner-bound, privately constructed, and limited to already released identities. */
    public static final class State {
        private final Object owner;
        private final boolean registrationPresent;
        private final KnownFreightReleaseState.State releaseSource;
        private final Map<String, Release> releases;
        private final Map<String, Journey> journeys;
        private final Set<String> uncovered;

        private State(Object owner, boolean registrationPresent, KnownFreightReleaseState.State releaseSource,
                      Map<String, Release> releases, Map<String, Journey> journeys) {
            this.owner = owner; this.registrationPresent = registrationPresent; this.releaseSource = releaseSource;
            this.releases = releases; this.journeys = journeys;
            var missing = new HashSet<>(releases.keySet()); missing.removeAll(journeys.keySet());
            uncovered = Set.copyOf(missing);
        }
        public Map<String, Journey> journeys() { return journeys; }
        public Set<String> knownReleasedUnitIds() { return releases.keySet(); }
        public Set<String> uncoveredReleasedUnitIds() { return uncovered; }
        /** Empty legacy registration is unavailable evidence, even before any release. */
        public boolean registrationPresent() { return registrationPresent; }
        /** Covers only the known released prefix, never an unreleased contract suffix. */
        public boolean allKnownReleasedUnitsCovered() { return registrationPresent && uncovered.isEmpty(); }
    }

    private final Map<String, Contract> contractsByUnit;

    public KnownFreightJourneyState(List<Contract> contracts) {
        var units = new LinkedHashMap<String, Contract>(); var orders = new HashSet<String>();
        for (Contract contract : List.copyOf(Objects.requireNonNull(contracts))) {
            require(orders.add(contract.orderId()), "Duplicate journey order ID");
            for (String unit : contract.unitIds()) require(units.putIfAbsent(unit, contract) == null,
                    "Unit belongs to multiple journey contracts");
        }
        contractsByUnit = Collections.unmodifiableMap(units);
    }

    /** Assembly validation; partial registration remains explicitly partial after release. */
    public static void validateAgainstReleaseContracts(List<Contract> journeys,
                                                       List<KnownFreightReleaseState.Contract> releases) {
        var mapper = new KnownFreightJourneyState(journeys);
        var orders = new HashSet<String>(); var byUnit = new LinkedHashMap<String, String>();
        for (var release : List.copyOf(Objects.requireNonNull(releases))) {
            require(orders.add(release.orderId()), "Duplicate release order ID");
            for (String unit : release.unitIds()) require(byUnit.putIfAbsent(unit, release.orderId()) == null,
                    "Unit belongs to multiple release contracts");
        }
        mapper.contractsByUnit.forEach((unit, contract) -> require(contract.orderId().equals(byUnit.get(unit)),
                "Journey order/unit is outside its release contract"));
    }

    public State initialState() { return new State(this, !contractsByUnit.isEmpty(), null, Map.of(), Map.of()); }

    public State apply(State before, ReceivedEvent event, KnownFreightReleaseState.State releasedAfter) {
        require(Objects.requireNonNull(before).owner == this, "State belongs to another journey component");
        Objects.requireNonNull(event); Objects.requireNonNull(releasedAfter);
        Map<String, Release> releases = before.releases;
        Map<String, Journey> journeys = before.journeys;
        boolean releaseChanged = before.releaseSource != releasedAfter;
        if (releaseChanged) {
            var observed = new LinkedHashMap<String, Release>();
            releasedAfter.knownOrders().values().forEach(order -> order.releasedUnits().forEach((unit, at) ->
                    require(observed.putIfAbsent(unit, new Release(order.orderId(), at)) == null, "Duplicate released unit")));
            before.releases.forEach((unit, release) -> require(release.equals(observed.get(unit)), "Release history was changed or removed"));
            var added = new HashSet<>(observed.keySet()); added.removeAll(before.releases.keySet());
            require(added.size() <= 1, "A single event cannot introduce several releases");
            if (!added.isEmpty()) {
                String unit = added.iterator().next(); Release release = observed.get(unit);
                exact(event, ActivityEndEvent.class, ActivityEndEvent.EVENT_TYPE);
                require(unit.equals(attr(event, "person")) && release.at() == event.occurredAtSeconds(),
                        "Release-after delta does not match this actual ActivityEnd");
                Contract contract = contractsByUnit.get(unit);
                if (contract != null) {
                    require(contract.orderId().equals(release.orderId()), "Journey/release order mismatch");
                    var copy = new LinkedHashMap<>(journeys);
                    copy.put(unit, new Journey(release.orderId(), unit, Phase.RELEASED, release.at(),
                            OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                            OptionalDouble.empty(), OptionalDouble.empty(), Optional.empty(), release.at(), event.closedSequence()));
                    journeys = immutable(copy);
                }
            }
            releases = Collections.unmodifiableMap(observed);
        }

        String person = event.attributes().get("person");
        Contract contract = contractsByUnit.get(person);
        boolean waiting = matches(event, AgentWaitingForPtEvent.class, AgentWaitingForPtEvent.EVENT_TYPE);
        boolean boarding = matches(event, PersonEntersPtVehicleEvent.class, PersonEntersPtVehicleEvent.EVENT_TYPE);
        boolean alighting = matches(event, PersonLeavesPtVehicleEvent.class, PersonLeavesPtVehicleEvent.EVENT_TYPE);
        boolean abort = matches(event, PersonStuckEvent.class, PersonStuckEvent.EVENT_TYPE);
        boolean delivery = false;
        if (contract != null && matches(event, ActivityStartEvent.class, ActivityStartEvent.EVENT_TYPE)) {
            exact(event, ActivityStartEvent.class, ActivityStartEvent.EVENT_TYPE);
            delivery = attr(event, ActivityStartEvent.ATTRIBUTE_ACTTYPE).startsWith(contract.deliveryActivityPrefix());
        }
        if (contract != null && !releaseChanged && matches(event, ActivityEndEvent.class, ActivityEndEvent.EVENT_TYPE)
                && journeys.containsKey(person)) {
            require(event.closedSequence() > journeys.get(person).lastClosedSequence(), "Repeated or reordered unit ActivityEnd");
        }
        if (contract != null && (waiting || boarding || alighting || abort || delivery)) {
            if (waiting) exact(event, AgentWaitingForPtEvent.class, AgentWaitingForPtEvent.EVENT_TYPE);
            else if (boarding) exact(event, PersonEntersPtVehicleEvent.class, PersonEntersPtVehicleEvent.EVENT_TYPE);
            else if (alighting) exact(event, PersonLeavesPtVehicleEvent.class, PersonLeavesPtVehicleEvent.EVENT_TYPE);
            else if (abort) exact(event, PersonStuckEvent.class, PersonStuckEvent.EVENT_TYPE);
            else exact(event, ActivityStartEvent.class, ActivityStartEvent.EVENT_TYPE);
            Journey old = journeys.get(person);
            require(old != null, "Freight journey event precedes actual release");
            require(event.occurredAtSeconds() >= old.lastObservedAtSeconds()
                    && event.closedSequence() > old.lastClosedSequence(), "Journey event is duplicate or regresses chronology");
            require(old.phase() != Phase.DELIVERED && old.phase() != Phase.ABORTED, "Journey event follows terminal outcome");
            OptionalDouble firstWait = old.firstWaitingAtSeconds(), currentWait = OptionalDouble.empty();
            OptionalDouble firstBoard = old.firstBoardedAtSeconds(), lastAlight = old.lastAlightedAtSeconds();
            OptionalDouble delivered = OptionalDouble.empty(), aborted = OptionalDouble.empty();
            Optional<String> vehicle = Optional.empty(); Phase phase;
            double at = event.occurredAtSeconds();
            if (waiting) {
                require(old.phase() == Phase.RELEASED || old.phase() == Phase.ALIGHTED, "Duplicate or misplaced waiting episode");
                require(person.equals(attr(event, "agent")), "Waiting person/agent mismatch");
                attr(event, "atStop"); attr(event, "destinationStop");
                firstWait = firstWait.isPresent() ? firstWait : OptionalDouble.of(at);
                currentWait = OptionalDouble.of(at); phase = Phase.WAITING;
            } else if (boarding) {
                require(old.phase() == Phase.WAITING, "PT boarding requires actual waiting");
                vehicle = Optional.of(attr(event, "vehicle")); attr(event, "transitLine"); attr(event, "transitRoute");
                firstBoard = firstBoard.isPresent() ? firstBoard : OptionalDouble.of(at); phase = Phase.RIDING;
            } else if (alighting) {
                require(old.phase() == Phase.RIDING && old.ridingVehicleId().orElseThrow().equals(attr(event, "vehicle")),
                        "PT alighting requires the matching actual vehicle");
                attr(event, "transitLine"); attr(event, "transitRoute");
                lastAlight = OptionalDouble.of(at); phase = Phase.ALIGHTED;
            } else if (delivery) {
                require(old.phase() == Phase.RELEASED || old.phase() == Phase.ALIGHTED,
                        "Delivery ActivityStart cannot occur while waiting or riding");
                delivered = OptionalDouble.of(at); phase = Phase.DELIVERED;
            } else { aborted = OptionalDouble.of(at); phase = Phase.ABORTED; }
            var copy = new LinkedHashMap<>(journeys);
            copy.put(person, new Journey(old.orderId(), person, phase, old.releasedAtSeconds(), firstWait,
                    currentWait, firstBoard, lastAlight, delivered, aborted, vehicle, at, event.closedSequence()));
            journeys = immutable(copy);
        }
        if (!releaseChanged && journeys == before.journeys) return before;
        return new State(this, !contractsByUnit.isEmpty(), releasedAfter, releases, journeys);
    }

    private static Map<String, Journey> immutable(Map<String, Journey> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    private static boolean matches(ReceivedEvent event, Class<? extends Event> type, String eventType) {
        return event.eventClass().equals(type.getName()) || event.eventType().equals(eventType);
    }
    private static void exact(ReceivedEvent event, Class<? extends Event> eventClass, String type) {
        require(event.eventClass().equals(eventClass.getName()) && event.eventType().equals(type)
                && type.equals(event.attributes().get(Event.ATTRIBUTE_TYPE)), "Journey event class/type mismatch");
        double attributeTime;
        try { attributeTime = time(Double.parseDouble(attr(event, Event.ATTRIBUTE_TIME))); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid journey event time", failure); }
        require(attributeTime == time(event.occurredAtSeconds()), "Journey event scalar/attribute time mismatch");
        require(event.availableBySeconds() >= event.occurredAtSeconds(), "Future journey fact");
    }
    private static String attr(ReceivedEvent event, String name) { String value = event.attributes().get(name); text(value, name); return value; }
    private static void text(String value, String field) { require(value != null && !value.isBlank(), "Nonblank " + field + " required"); }
    private static double time(double value) { require(Double.isFinite(value) && value >= 0, "Finite nonnegative time required"); return value == 0 ? 0 : value; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
