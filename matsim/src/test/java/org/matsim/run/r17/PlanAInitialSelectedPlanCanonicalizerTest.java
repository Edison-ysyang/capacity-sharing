package org.matsim.run.r17;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlansConfigGroup.TripDurationHandling;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.timing.TimeInterpretation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAInitialSelectedPlanCanonicalizerTest {
	private static final double QSIM_END = 108_000.0;
	private static final double STEP = 1.0;

	@Test
	void rejectsPtRequestedTripWhenRouterOnlyProducedDirectWalk() {
		Plan plan = oneTripPlan(TransportMode.pt, TransportMode.walk, 10_000.0, 60.0);

		var audit = audit(plan, PlanATransitCatchabilityTestFixtures.emptySchedule());

		assertEquals(1, audit.requestedPtTrips());
		assertEquals(1, audit.ptFallbackOnlyTrips());
		assertFalse(audit.feasible());
		assertEquals("pt_fallback_only", audit.reasonCode());
	}

	@Test
	void acceptsAnActualTransitLegWithAProvableNextService() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "route", "access", "egress",
				0.0, 30.0, 100.0, true, 10_000.0);
		Plan plan = oneTripPlan(TransportMode.pt, TransportMode.pt, 10_000.0, 60.0);
		((Leg) plan.getPlanElements().get(1)).setRoute(
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 10_000.0, 100.0));

		var audit = audit(plan, schedule);

		assertEquals(1, audit.requestedPtTrips());
		assertEquals(0, audit.ptFallbackOnlyTrips());
		assertTrue(audit.feasible());
		assertEquals(10_100.0, audit.terminalArrivalTime(), 1.0e-9);
	}

	@Test
	void appliesTheSameStrictHorizonBoundaryAsFutureTimeMutationGuard() {
		Plan plan = oneTripPlan(TransportMode.car, TransportMode.car, 105_800.0, 100.0);

		var audit = audit(plan, PlanATransitCatchabilityTestFixtures.emptySchedule());

		assertEquals(1, audit.horizonViolationTrips());
		assertFalse(audit.feasible());
		assertFalse(PlanAInitialSelectedPlanCanonicalizer.completesBeforeQsimEnd(
				105_900.0, QSIM_END, 2_100.0));
		assertTrue(PlanAInitialSelectedPlanCanonicalizer.completesBeforeQsimEnd(
				105_899.0, QSIM_END, 2_100.0));
	}

	@Test
	void rejectsFinalLegThatOnlyExceedsTheHorizonOnQsimMonotonicTime() {
		var config = ConfigUtils.createConfig();
		config.plans().setTripDurationHandling(TripDurationHandling.ignoreDelays);
		Plan plan = lateIntermediateArrivalThenLongFinalLeg();
		var index = PlanATransitBoardingDeadlineIndex.build(
				PlanATransitCatchabilityTestFixtures.emptySchedule(), STEP, 0.0);

		var audit = PlanAInitialSelectedPlanCanonicalizer.auditRoutedPlan(
				plan, TimeInterpretation.create(config), QSIM_END, 2_100.0, STEP, index);

		assertEquals(2, audit.tripCount());
		assertEquals(1, audit.horizonViolationTrips());
		assertEquals(106_000.0, audit.terminalArrivalTime(), 1.0e-9);
		assertFalse(audit.feasible());
	}

	@Test
	void reportsUncatchableLastServiceAsAnExplicitRouteReasonWithoutFakeHorizon() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "route", "access", "egress",
				0.0, 0.0, 60.0, true, 10_000.0);
		Plan plan = oneTripPlan(TransportMode.pt, TransportMode.pt, 10_000.0, 60.0);
		((Leg) plan.getPlanElements().get(1)).setRoute(
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 10_000.0, 60.0));

		var audit = audit(plan, schedule);

		assertEquals(1, audit.boardingUncatchableLegs());
		assertEquals(1, audit.boardingPlannedServiceMissedLegs());
		assertEquals(0, audit.horizonViolationTrips());
		assertEquals("boarding_uncatchable", audit.reasonCode());
		assertFalse(audit.feasible());
	}

	private static PlanAInitialSelectedPlanCanonicalizer.PlanRouteFeasibility audit(
			Plan plan,
			org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
		var config = ConfigUtils.createConfig();
		TimeInterpretation timeInterpretation = TimeInterpretation.create(config);
		var index = PlanATransitBoardingDeadlineIndex.build(schedule, STEP, 0.0);
		return PlanAInitialSelectedPlanCanonicalizer.auditRoutedPlan(
				plan, timeInterpretation, QSIM_END, 2_100.0, STEP, index);
	}

	private static Plan oneTripPlan(
			String requestedMode,
			String legMode,
			double departureTime,
			double travelTime) {
		Plan plan = PopulationUtils.createPlan();
		var origin = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		origin.setEndTime(departureTime);
		Leg leg = PopulationUtils.createAndAddLeg(plan, legMode);
		leg.setTravelTime(travelTime);
		TripStructureUtils.setRoutingMode(leg, requestedMode);
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));
		return plan;
	}

	private static Plan lateIntermediateArrivalThenLongFinalLeg() {
		Plan plan = PopulationUtils.createPlan();
		var home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		home.setEndTime(62_000.0);
		Leg outbound = PopulationUtils.createAndAddLeg(plan, TransportMode.car);
		outbound.setTravelTime(30_000.0);
		TripStructureUtils.setRoutingMode(outbound, TransportMode.car);
		var work = PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));
		work.setEndTime(70_000.0);
		Leg returnLeg = PopulationUtils.createAndAddLeg(plan, TransportMode.car);
		returnLeg.setTravelTime(14_000.0);
		TripStructureUtils.setRoutingMode(returnLeg, TransportMode.car);
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		return plan;
	}
}
