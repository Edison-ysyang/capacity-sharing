package org.matsim.contrib.cotransport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure observational order transitions. Time is in seconds; wait and lateness are in minutes.
 * The caller supplies only boarding events and configured destination ActivityStart events.
 * This component cannot allocate or release native transit capacity.
 */
public final class OrderOutcomeReconstructor {
	public State initial(FreightOrder order) {
		return new State(order, Map.of(), Map.of(), order.releaseTime());
	}

	public State boarded(State state, String unitId, double time) {
		validateEvent(unitId, time);
		if (time < state.lastEventTime()) throw new IllegalStateException("Reverse-ordered freight event: " + unitId);
		if (time < state.order().releaseTime()) {
			throw new IllegalStateException("Freight unit boarded before order release: " + unitId);
		}
		if (state.deliveryTimes().containsKey(unitId)) {
			throw new IllegalStateException("Freight unit boarded after completed delivery: " + unitId);
		}
		Double first = state.firstBoardingTimes().get(unitId);
		if (first != null) {
			if (time < first) throw new IllegalStateException("Reverse-ordered freight boarding: " + unitId);
			// Transfers count as events, not as additional order units or new first boardings.
			return new State(state.order(), state.firstBoardingTimes(), state.deliveryTimes(), time);
		}
		if (state.firstBoardingTimes().size() == state.order().capacityUnits()) {
			throw new IllegalStateException("Order " + state.order().orderId() + " exceeds capacity_units="
					+ state.order().capacityUnits());
		}
		Map<String, Double> boarded = new LinkedHashMap<>(state.firstBoardingTimes());
		boarded.put(unitId, time);
		return new State(state.order(), boarded, state.deliveryTimes(), time);
	}

	public State delivered(State state, String unitId, double time) {
		validateEvent(unitId, time);
		if (time < state.lastEventTime()) throw new IllegalStateException("Reverse-ordered freight event: " + unitId);
		Double boarded = state.firstBoardingTimes().get(unitId);
		if (boarded == null) {
			throw new IllegalStateException("Freight unit delivered without a prior boarding: " + unitId);
		}
		if (time < boarded) throw new IllegalStateException("Freight delivery precedes boarding: " + unitId);
		if (state.deliveryTimes().containsKey(unitId)) {
			throw new IllegalStateException("Freight unit delivered more than once: " + unitId);
		}
		Map<String, Double> delivered = new LinkedHashMap<>(state.deliveryTimes());
		delivered.put(unitId, time);
		return new State(state.order(), state.firstBoardingTimes(), delivered, time);
	}

	public Outcome snapshot(State state, double serviceWindowEndTime, double executionHorizonTime) {
		validateCutoff(serviceWindowEndTime, "service window");
		validateCutoff(executionHorizonTime, "execution horizon");
		boolean complete = state.deliveryTimes().size() == state.order().capacityUnits();
		double first = state.firstBoardingTimes().values().stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
		double lastBoard = state.firstBoardingTimes().values().stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
		double lastDelivery = state.deliveryTimes().values().stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
		int withinWindow = (int) state.deliveryTimes().values().stream().filter(t -> within(t, serviceWindowEndTime)).count();
		int withinHorizon = (int) state.deliveryTimes().values().stream().filter(t -> within(t, executionHorizonTime)).count();
		return new Outcome(state.firstBoardingTimes().size(), state.deliveryTimes().size(), withinWindow,
				withinHorizon, first, lastBoard, lastDelivery, complete ? lastDelivery : Double.NaN,
				Double.isNaN(first) ? Double.NaN : (first - state.order().releaseTime()) / 60.0,
				complete ? Math.max(0, lastDelivery - state.order().deadlineTime()) / 60.0 : Double.NaN,
				complete, complete && withinWindow == state.order().capacityUnits(),
				complete && withinHorizon == state.order().capacityUnits(),
				complete && lastDelivery <= state.order().deadlineTime());
	}

	private static boolean within(double time, double end) {
		return Double.isNaN(end) || time <= end;
	}

	private static void validateEvent(String unitId, double time) {
		if (unitId == null || unitId.isBlank() || !Double.isFinite(time) || time < 0) {
			throw new IllegalArgumentException("Freight events require a nonblank unit ID and finite nonnegative time");
		}
	}

	private static void validateCutoff(double cutoff, String name) {
		// NaN retains the legacy explicitly-unbounded report mode. Formal specs require finite cutoffs.
		if (!Double.isNaN(cutoff) && (!Double.isFinite(cutoff) || cutoff < 0)) {
			throw new IllegalArgumentException("Invalid " + name + " cutoff: " + cutoff);
		}
	}

	public record State(FreightOrder order, Map<String, Double> firstBoardingTimes, Map<String, Double> deliveryTimes,
			double lastEventTime) {
		public State {
			if (order == null) throw new IllegalArgumentException("Order must not be null");
			if (!Double.isFinite(lastEventTime) || lastEventTime < order.releaseTime()) {
				throw new IllegalArgumentException("Order event time must be finite and no earlier than release");
			}
			firstBoardingTimes = Collections.unmodifiableMap(new LinkedHashMap<>(firstBoardingTimes));
			deliveryTimes = Collections.unmodifiableMap(new LinkedHashMap<>(deliveryTimes));
			if (firstBoardingTimes.size() > order.capacityUnits()
					|| !firstBoardingTimes.keySet().containsAll(deliveryTimes.keySet())) {
				throw new IllegalStateException("Order-unit conservation failed: " + order.orderId());
			}
			for (Map.Entry<String, Double> event : firstBoardingTimes.entrySet()) {
				validateEvent(event.getKey(), event.getValue());
				if (event.getValue() < order.releaseTime()) throw new IllegalStateException("Boarding precedes order release");
				if (event.getValue() > lastEventTime) throw new IllegalStateException("Boarding exceeds last event time");
			}
			for (Map.Entry<String, Double> event : deliveryTimes.entrySet()) {
				validateEvent(event.getKey(), event.getValue());
				if (event.getValue() < firstBoardingTimes.get(event.getKey())) {
					throw new IllegalStateException("Delivery precedes unit boarding");
				}
				if (event.getValue() > lastEventTime) throw new IllegalStateException("Delivery exceeds last event time");
			}
		}
	}

	public record Outcome(int unitsBoarded, int unitsDelivered, int unitsDeliveredWithinServiceWindow,
			int unitsDeliveredWithinExecutionHorizon, double firstUnitBoardTime, double lastUnitBoardTime,
			double lastUnitDeliveryTime, double completionTime, double firstBoardWaitMinutes, double latenessMinutes,
			boolean completed, boolean completedInServiceWindow, boolean completedWithinExecutionHorizon,
			boolean onTime) { }
}
