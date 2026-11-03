package org.matsim.run.ismt.dynamic;

import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.HasPersonId;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Unwired, pure transition from an externally closed event prefix to known freight
 * releases. Full contracts stay private to this component. A contract first becomes
 * public when one of its registered units ends the explicitly contracted initial
 * activity; only that unit is released. Contracted units are not a ready-queue count.
 * Neither activity facilities nor links are interpreted as public-transport stops.
 * Arrival at a stop, boarding and delivery belong to a separate state reducer.
 *
 * <p>Each State is immutable, privately constructed and bound to this component.
 * Applying an event never mutates this component or an earlier State, including on
 * rejection. The caller owns prefix/epoch validation and must fold actual observer
 * deltas exactly once: ReceivedEvent is a public value type, not a provenance token.
 * This component validates relevant event fields but does not certify that an event
 * was emitted by QSim, or that a matching activity type really is initial in a plan.
 * That activity-type contract must be checked when assembling the external inputs.
 * No occurrence-time monotonicity is assumed across publication deltas.</p>
 */
public final class KnownFreightReleaseState {
    /** Assembly-only input; never put this full contract in a controller snapshot. */
    public record Contract(String orderId, String originStopId, String destinationStopId,
                           double earliestReleaseSeconds, double deadlineSeconds,
                           List<String> unitIds, String releaseActivityType) {
        public Contract {
            requireId(orderId, "orderId");
            requireId(originStopId, "originStopId");
            requireId(destinationStopId, "destinationStopId");
            requireId(releaseActivityType, "releaseActivityType");
            earliestReleaseSeconds = finiteTime(earliestReleaseSeconds);
            deadlineSeconds = finiteTime(deadlineSeconds);
            // A contract can already be late at its first possible release.
            unitIds = List.copyOf(Objects.requireNonNull(unitIds, "unitIds"));
            if (unitIds.isEmpty()) throw new IllegalArgumentException("An order needs at least one unit");
            var seen = new HashSet<String>();
            for (String unitId : unitIds) {
                requireId(unitId, "unitId");
                if (!seen.add(unitId)) throw new IllegalArgumentException("Duplicate unit within contract: " + unitId);
            }
        }
    }

    /**
     * Public information after the first release. releasedUnits contains actual
     * occurrence times and only released IDs, not the unreleased contract suffix.
     * Its size is a release count, not a stop-arrival or boarding count.
     */
    public record KnownOrder(String orderId, String originStopId, String destinationStopId,
                             double deadlineSeconds, int contractedUnits,
                             Map<String, Double> releasedUnits) {
        public KnownOrder {
            requireId(orderId, "orderId");
            requireId(originStopId, "originStopId");
            requireId(destinationStopId, "destinationStopId");
            deadlineSeconds = finiteTime(deadlineSeconds);
            if (contractedUnits <= 0) throw new IllegalArgumentException("Positive contracted unit count required");
            Objects.requireNonNull(releasedUnits, "releasedUnits");
            if (releasedUnits.isEmpty() || releasedUnits.size() > contractedUnits) {
                throw new IllegalArgumentException("A known order needs between one and contractedUnits releases");
            }
            var copy = new LinkedHashMap<String, Double>();
            for (var entry : releasedUnits.entrySet()) {
                requireId(entry.getKey(), "releasedUnitId");
                copy.put(entry.getKey(), finiteTime(Objects.requireNonNull(entry.getValue(), "release time")));
            }
            releasedUnits = Collections.unmodifiableMap(copy);
        }
    }

    /** No public constructor or mutation/import API; caller-created maps cannot forge a State. */
    public static final class State {
        private final Object owner;
        private final Map<String, KnownOrder> knownOrders;

        private State(Object owner, Map<String, KnownOrder> knownOrders) {
            this.owner = owner;
            this.knownOrders = Collections.unmodifiableMap(new LinkedHashMap<>(knownOrders));
        }

        public Map<String, KnownOrder> knownOrders() { return knownOrders; }
    }

    private final Object stateOwner = new Object();
    private final Map<String, Contract> contractsByUnit;

    public KnownFreightReleaseState(List<Contract> contracts) {
        Objects.requireNonNull(contracts, "contracts");
        var orders = new HashSet<String>();
        var units = new LinkedHashMap<String, Contract>();
        for (Contract contract : List.copyOf(contracts)) {
            if (!orders.add(contract.orderId())) {
                throw new IllegalArgumentException("Duplicate order ID: " + contract.orderId());
            }
            for (String unitId : contract.unitIds()) {
                if (units.putIfAbsent(unitId, contract) != null) {
                    throw new IllegalArgumentException("Unit belongs to multiple contracts: " + unitId);
                }
            }
        }
        contractsByUnit = Collections.unmodifiableMap(units);
    }

    /** Classification only. This must not be treated as evidence that a unit is released. */
    public boolean isFreightUnit(String personId) {
        return personId != null && contractsByUnit.containsKey(personId);
    }

    public State initialState() { return new State(stateOwner, Map.of()); }

    /**
     * Releases one registered unit on its matching ActivityEnd; irrelevant events
     * return the same State. Duplicate releases, releases before the contractual
     * lower bound, malformed ActivityEnd envelopes and foreign States are rejected.
     * Deadline is deliberately not an admission condition.
     */
    public State apply(State before, NativeEventPrefixObserver.ReceivedEvent event) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(event, "event");
        if (before.owner != stateOwner) throw new IllegalArgumentException("State belongs to another release component");
        if (!ActivityEndEvent.class.getName().equals(event.eventClass())) return before;
        validateActivityEndEnvelope(event);

        String personId = event.attributes().get(HasPersonId.ATTRIBUTE_PERSON);
        requireId(personId, "ActivityEnd person");
        String activityType = event.attributes().get(ActivityEndEvent.ATTRIBUTE_ACTTYPE);
        if (activityType == null) throw new IllegalArgumentException("Missing ActivityEnd actType");
        Contract contract = contractsByUnit.get(personId);
        if (contract == null || !contract.releaseActivityType().equals(activityType)) return before;

        double occurredAt = finiteTime(event.occurredAtSeconds());
        if (occurredAt < contract.earliestReleaseSeconds()) {
            throw new IllegalArgumentException("Unit released before contractual earliest release: " + personId);
        }
        KnownOrder previous = before.knownOrders.get(contract.orderId());
        if (previous != null && previous.releasedUnits().containsKey(personId)) {
            throw new IllegalArgumentException("Unit released more than once: " + personId);
        }

        var released = new LinkedHashMap<String, Double>();
        if (previous != null) released.putAll(previous.releasedUnits());
        released.put(personId, occurredAt);
        var known = new KnownOrder(contract.orderId(), contract.originStopId(), contract.destinationStopId(),
                contract.deadlineSeconds(), contract.unitIds().size(), released);
        var next = new LinkedHashMap<>(before.knownOrders);
        next.put(contract.orderId(), known);
        return new State(stateOwner, next);
    }

    private static void validateActivityEndEnvelope(NativeEventPrefixObserver.ReceivedEvent event) {
        if (!ActivityEndEvent.EVENT_TYPE.equals(event.eventType())
                || !event.eventType().equals(event.attributes().get(Event.ATTRIBUTE_TYPE))) {
            throw new IllegalArgumentException("ActivityEnd class/type/attribute mismatch");
        }
        String time = event.attributes().get(Event.ATTRIBUTE_TIME);
        if (time == null) throw new IllegalArgumentException("Missing ActivityEnd time attribute");
        final double attributeTime;
        try {
            attributeTime = finiteTime(Double.parseDouble(time));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid ActivityEnd time attribute", invalid);
        }
        if (attributeTime != event.occurredAtSeconds()) {
            throw new IllegalArgumentException("ActivityEnd scalar/attribute time mismatch");
        }
    }

    private static void requireId(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Nonblank " + field + " required");
    }

    private static double finiteTime(double time) {
        if (!Double.isFinite(time) || time < 0) throw new IllegalArgumentException("Finite nonnegative time required");
        return time == 0 ? 0 : time;
    }
}
