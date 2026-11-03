package org.matsim.contrib.cotransport;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

/** Mutable event adapter; order arithmetic and conservation live in the pure reconstructor. */
final class FreightOrderRuntimeStats {
	private final OrderOutcomeReconstructor reconstructor = new OrderOutcomeReconstructor();
	private OrderOutcomeReconstructor.State state;
	private int boardingEvents;
	private int deliveryEvents;
	private int deniedBoardings;
	private double serviceWindowEndTime = Double.NaN;
	private double delayPenalty;

	FreightOrderRuntimeStats(FreightOrder order) {
		state = reconstructor.initial(order);
	}

	void boarded(Id<Person> personId, double time) {
		boardingEvents++;
		state = reconstructor.boarded(state, personId.toString(), time);
	}

	void delivered(Id<Person> personId, double time, double serviceWindowEndTime) {
		deliveryEvents++;
		if (!state.deliveryTimes().isEmpty()
				&& Double.compare(this.serviceWindowEndTime, serviceWindowEndTime) != 0) {
			throw new IllegalStateException("Service-window cutoff changed within an order iteration");
		}
		// Validate the cutoff before committing either the state or the report setting.
		reconstructor.snapshot(state, serviceWindowEndTime, Double.NaN);
		state = reconstructor.delivered(state, personId.toString(), time);
		this.serviceWindowEndTime = serviceWindowEndTime;
	}

	void denied() { deniedBoardings++; }

	void addDelayPenalty(double penalty) {
		if (!Double.isFinite(penalty) || penalty < 0 || !Double.isFinite(delayPenalty + penalty)) {
			throw new IllegalArgumentException("Freight delay penalty must be finite and nonnegative");
		}
		delayPenalty += penalty;
	}

	FreightOrder order() { return state.order(); }
	int unitsBoarded() { return state.firstBoardingTimes().size(); }
	int unitsDelivered() { return state.deliveryTimes().size(); }
	int unitsDeliveredWithinServiceWindow() { return outcome().unitsDeliveredWithinServiceWindow(); }
	int deniedBoardings() { return deniedBoardings; }
	double firstBoardingTime() { return outcome().firstUnitBoardTime(); }
	/** Latest first boarding among distinct units, not a subsequent transfer event. */
	double lastBoardingTime() { return outcome().lastUnitBoardTime(); }
	double lastDeliveryTime() { return outcome().lastUnitDeliveryTime(); }
	double delayPenalty() { return delayPenalty; }
	int boardingEvents() { return boardingEvents; }
	int deliveryEvents() { return deliveryEvents; }
	double waitToFirstBoardMinutes() { return outcome().firstBoardWaitMinutes(); }
	double completedOrderLatenessMinutes() { return outcome().latenessMinutes(); }
	boolean completed() { return outcome().completed(); }
	boolean completedWithinServiceWindow() { return outcome().completedInServiceWindow(); }
	boolean completedWithinExecutionHorizon(double horizonTime) { return snapshot(horizonTime).completedWithinExecutionHorizon(); }

	OrderOutcomeReconstructor.Outcome snapshot(double executionHorizonTime) {
		return reconstructor.snapshot(state, serviceWindowEndTime, executionHorizonTime);
	}

	String boardedAgentIds() { return String.join("|", state.firstBoardingTimes().keySet()); }
	String deliveredAgentIds() { return String.join("|", state.deliveryTimes().keySet()); }
	String agentBalanceState() { return completed() ? "completed_conserved" : "incomplete_conserved"; }

	void assertConserved() {
		// Every immutable State is validated at construction; retain the monitor's existing check API.
		if (unitsBoarded() > order().capacityUnits() || unitsDelivered() > unitsBoarded()
				|| unitsDeliveredWithinServiceWindow() > unitsDelivered()) {
			throw new IllegalStateException("Order-unit conservation failed: " + order().orderId());
		}
	}

	private OrderOutcomeReconstructor.Outcome outcome() { return snapshot(Double.NaN); }
}
