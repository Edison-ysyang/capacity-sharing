/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** */
package org.matsim.core.mobsim.qsim.pt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.List;
import java.util.Objects;

/**
 * Optional QSim hook for experiment-specific boarding priorities and constraints.
 *
 * <p>The policy is stored as a {@code Scenario} element under {@link #SCENARIO_ELEMENT}.
 * When no policy is installed, transit boarding keeps the standard MATSim ordering and
 * capacity behaviour.  Implementations must be deterministic and must not mutate the
 * supplied collections.</p>
 */
public interface TransitBoardingPolicy {
	String SCENARIO_ELEMENT = "transitBoardingPolicy";

	enum ActorClass { PASSENGER, FREIGHT, INVALID }

	enum DenialReason {
		NATIVE_CAPACITY, INELIGIBLE_SERVICE, ISOLATION_RULE,
		FREIGHT_SHARE_CAP, PASSENGER_LOAD_GATE, INVALID_AGENT_CLASS, OUTSIDE_SHARED_WINDOW,
		WHOLE_ORDER_NOT_READY, WHOLE_ORDER_INELIGIBLE, WHOLE_ORDER_QUEUE_BLOCKED
	}

	/** Authoritative expected membership, not just the units currently seen in a queue. */
	record BoardingGroup(String groupId, List<Id<Person>> memberIds) {
		public BoardingGroup {
			if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("Boarding group ID is required");
			memberIds = List.copyOf(memberIds);
			if (memberIds.isEmpty() || new java.util.HashSet<>(memberIds).size() != memberIds.size())
				throw new IllegalArgumentException("A boarding group requires unique, nonempty expected members");
		}
	}

	/** A policy decision is not a capacity reservation. Native QSim retains that authority. */
	record BoardingDecision(boolean allowed, DenialReason denialReason) {
		public BoardingDecision {
			if (allowed == (denialReason != null)) {
				throw new IllegalArgumentException("Allowed decisions must have no denial reason; denials require one");
			}
		}

		public static BoardingDecision allow() { return new BoardingDecision(true, null); }
		public static BoardingDecision deny(DenialReason reason) {
			return new BoardingDecision(false, Objects.requireNonNull(reason));
		}
	}

	/**
	 * Observational snapshot of native occupants after alighting and previously accepted
	 * candidates at this stop. NaN thresholds mean not configured by a legacy policy.
	 */
	record DenialDetails(ActorClass actorClass, int passengers, int freight, int nativeCapacity,
			double freightShareCap, double passengerLoadGate) {
		public DenialDetails {
			Objects.requireNonNull(actorClass);
			if (passengers < 0 || freight < 0 || nativeCapacity < 0) {
				throw new IllegalArgumentException("Native occupancy and capacity cannot be negative");
			}
		}
	}

	/**
	 * Higher values board first.  Java's stable sort preserves the original stop-queue
	 * order among passengers with the same priority.
	 */
	default int priority(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop) {
		return 0;
	}

	/**
	 * If true, equal-priority candidates are ordered by their native stop-queue arrival
	 * time, then by registration sequence for exact-time ties. The queue clock starts
	 * after access/preparation, not at an order's release or at a published departure.
	 * Returning false preserves the legacy stable priority sort and queue order.
	 */
	default boolean usesQueueArrivalTime() {
		return false;
	}

	/**
	 * Null preserves native individual boarding. A nonnull group is admitted and committed
	 * as one indivisible batch; all its expected members must be ready and route-compatible.
	 * This describes identity only and never reserves or mutates native vehicle capacity.
	 */
	default BoardingGroup indivisibleBoardingGroup(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop) {
		return null;
	}

	/**
	 * Optional strict whole-order FIFO: an otherwise eligible complete group which cannot
	 * fit may block later candidates. Ineligible/window-closed groups must not block them.
	 * The default preserves the legacy work-conserving individual candidate scan.
	 */
	default boolean blocksFollowingCandidatesAfterGroupDenial(DenialReason reason) {
		return false;
	}

	/** Capacity-independent service/actor eligibility, checked before whole-order FIFO blockage. */
	default BoardingDecision boardingServiceEligibilityDecision(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop) {
		return BoardingDecision.allow();
	}

	/**
	 * Returns whether this candidate may use one of the still available physical places.
	 * The accepted list contains candidates already admitted during the current stop call.
	 */
	default boolean allowsBoarding(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop,
			List<PTPassengerAgent> passengersLeaving,
			List<PTPassengerAgent> acceptedPassengers) {
		return true;
	}

	/** Called only after the native adapter has established an available physical place. */
	default BoardingDecision decideBoarding(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop,
			List<PTPassengerAgent> passengersLeaving, List<PTPassengerAgent> acceptedPassengers) {
		return allowsBoarding(passenger, vehicle, line, route, stop, passengersLeaving, acceptedPassengers)
				? BoardingDecision.allow() : BoardingDecision.deny(DenialReason.INELIGIBLE_SERVICE);
	}

	/**
	 * Additional time gate evaluated using the native stop/physical-entry clock, never
	 * a published timetable or lagged event mirror. Checked during candidate selection
	 * and again immediately before a native addPassenger. It must not reserve capacity
	 * or mutate passengers. The default preserves legacy admission; passing this gate
	 * does not certify that the ride will finish within any operating window.
	 */
	default BoardingDecision boardingTimeDecision(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop, double nowSeconds) {
		return BoardingDecision.allow();
	}

	/**
	 * Captures diagnostics only; must never decide or mutate physical capacity. Invoked
	 * for native-capacity denials too, without calling the policy decision function.
	 */
	default DenialDetails denialDetails(PTPassengerAgent passenger, TransitVehicle vehicle,
			TransitLine line, TransitRoute route, TransitStopFacility stop,
			List<PTPassengerAgent> passengersLeaving, List<PTPassengerAgent> acceptedPassengers) {
		return new DenialDetails(ActorClass.PASSENGER,
				vehicle.getPassengers().size() - passengersLeaving.size() + acceptedPassengers.size(),
				0, vehicle.getPassengerCapacity(), Double.NaN, Double.NaN);
	}

	/** Observes the accepted boarding/alighting lists before a stop handler mutates them. */
	default void observeStopInteraction(TransitVehicle vehicle, TransitLine line, TransitRoute route,
			TransitStopFacility stop, List<PTPassengerAgent> passengersLeaving,
			List<PTPassengerAgent> passengersEntering) {
	}

	/**
	 * Optionally extends the stop duration after the normal boarding handler has run.
	 * Implementations may keep QSim-local state, but must return {@code baseStopTime}
	 * unchanged when no intervention applies.
	 */
	default double adjustStopTime(TransitVehicle vehicle, TransitLine line, TransitRoute route,
			TransitStopFacility stop, List<PTPassengerAgent> passengersLeaving,
			List<PTPassengerAgent> passengersEntering, double baseStopTime) {
		return baseStopTime;
	}
}
