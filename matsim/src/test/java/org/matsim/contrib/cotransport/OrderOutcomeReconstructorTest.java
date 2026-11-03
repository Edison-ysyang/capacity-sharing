package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderOutcomeReconstructorTest {
	private final OrderOutcomeReconstructor reconstructor = new OrderOutcomeReconstructor();

	@Test
	void transitionsAreImmutableAndFirstBoardWaitAndFinalCompletionAreUnitAware() {
		var initial = reconstructor.initial(order(2));
		var first = reconstructor.boarded(initial, "order", 160);
		var second = reconstructor.boarded(first, "order_cap_02", 220);
		var partial = reconstructor.delivered(second, "order", 240);
		var complete = reconstructor.delivered(partial, "order_cap_02", 400);
		assertEquals(0, initial.firstBoardingTimes().size());
		assertEquals(1, first.firstBoardingTimes().size());
		assertThrows(UnsupportedOperationException.class, () -> first.firstBoardingTimes().put("other", 180.0));
		var partialOutcome = reconstructor.snapshot(partial, 350, 500);
		assertFalse(partialOutcome.completed());
		assertTrue(Double.isNaN(partialOutcome.completionTime()));
		assertTrue(Double.isNaN(partialOutcome.latenessMinutes()));
		var outcome = reconstructor.snapshot(complete, 350, 500);
		assertEquals(160, outcome.firstUnitBoardTime());
		assertEquals(220, outcome.lastUnitBoardTime());
		assertEquals(400, outcome.completionTime());
		assertEquals(1, outcome.firstBoardWaitMinutes());
		assertEquals(2.5, outcome.latenessMinutes());
		assertTrue(outcome.completed());
		assertFalse(outcome.completedInServiceWindow());
		assertTrue(outcome.completedWithinExecutionHorizon());
		assertFalse(outcome.onTime());
	}

	@Test
	void horizonAndServiceWindowHaveInclusiveBoundariesAndAreSeparateFromAllObservedCompletion() {
		var boarded = reconstructor.boarded(reconstructor.initial(order(1)), "order", 100);
		var complete = reconstructor.delivered(boarded, "order", 250);
		var boundary = reconstructor.snapshot(complete, 250, 250);
		assertTrue(boundary.completedInServiceWindow());
		assertTrue(boundary.completedWithinExecutionHorizon());
		assertTrue(boundary.onTime());
		assertEquals(0, boundary.latenessMinutes());
		assertFalse(reconstructor.snapshot(complete, 200, 249).completedWithinExecutionHorizon());
		assertTrue(reconstructor.snapshot(complete, 200, 249).completed());
	}

	@Test
	void repeatedTransfersDoNotMoveTheLastDistinctUnitsFirstBoardTime() {
		var state = reconstructor.boarded(reconstructor.initial(order(2)), "order", 160);
		state = reconstructor.boarded(state, "order_cap_02", 180);
		state = reconstructor.boarded(state, "order", 220);
		var outcome = reconstructor.snapshot(state, 350, 500);
		assertEquals(2, outcome.unitsBoarded());
		assertEquals(180, outcome.lastUnitBoardTime());
	}

	@Test
	void invalidEventOrderingDuplicateCompletionAndNonfiniteTimesFailWithoutMutatingState() {
		var initial = reconstructor.initial(order(1));
		assertThrows(IllegalStateException.class, () -> reconstructor.boarded(initial, "order", 99));
		assertThrows(IllegalArgumentException.class, () -> reconstructor.boarded(initial, "order", Double.NaN));
		assertThrows(IllegalStateException.class, () -> reconstructor.delivered(initial, "order", 200));
		var boarded = reconstructor.boarded(initial, "order", 160);
		assertThrows(IllegalStateException.class, () -> reconstructor.delivered(boarded, "order", 159));
		var transferred = reconstructor.boarded(boarded, "order", 190);
		assertThrows(IllegalStateException.class, () -> reconstructor.delivered(transferred, "order", 180));
		assertThrows(IllegalStateException.class, () -> reconstructor.boarded(boarded, "excess", 180));
		var complete = reconstructor.delivered(boarded, "order", 200);
		assertThrows(IllegalStateException.class, () -> reconstructor.delivered(complete, "order", 250));
		assertThrows(IllegalStateException.class, () -> reconstructor.boarded(complete, "order", 250));
		assertEquals(1, complete.deliveryTimes().size());
	}

	private static FreightOrder order(int units) {
		return new FreightOrder("order", 100, 250, 1, 2, "standard", 1, units);
	}
}
