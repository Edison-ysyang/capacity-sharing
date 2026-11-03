package org.matsim.run.r17;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlansConfigGroup.TripDurationHandling;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAWholeTourFeasibleModeChoiceTest {
	private static final double STEP = 1.0;

	@Test
	void identifiesPtFallbackOnlyWalkTripsWithoutRejectingNormalPtOrWalkingTrips() {
		Plan fallbackOnlyPt = twoLegPlan(
				TransportMode.walk, TransportMode.pt,
				TransportMode.non_network_walk, TransportMode.pt);
		Plan normalPt = ptWithWalkingAccessAndEgress();
		Plan directWalk = twoLegPlan(
				TransportMode.walk, TransportMode.walk,
				TransportMode.walk, TransportMode.walk);

		assertTrue(PlanAWholeTourFeasibleModeChoice.hasPtFallbackOnlyWalkTrip(fallbackOnlyPt));
		assertFalse(PlanAWholeTourFeasibleModeChoice.hasPtFallbackOnlyWalkTrip(normalPt));
		assertFalse(PlanAWholeTourFeasibleModeChoice.hasPtFallbackOnlyWalkTrip(directWalk));
	}

	@Test
	void appliesOneModeToEveryLegInTheTour() {
		Plan plan = PopulationUtils.createPlan();
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		Leg first = PopulationUtils.createAndAddLeg(plan, TransportMode.car);
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));
		Leg second = PopulationUtils.createAndAddLeg(plan, TransportMode.pt);
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));

		PlanAWholeTourFeasibleModeChoice.applyWholeTourMode(plan, TransportMode.bike);

		assertEquals(TransportMode.bike, first.getMode());
		assertEquals(TransportMode.bike, second.getMode());
		assertEquals(TransportMode.bike, TripStructureUtils.getRoutingMode(first));
		assertEquals(TransportMode.bike, TripStructureUtils.getRoutingMode(second));
	}

	@Test
	void appliesQsimMonotonicClockAndStrictHorizonToNonPtTours() {
		var config = ConfigUtils.createConfig();
		config.plans().setTripDurationHandling(TripDurationHandling.ignoreDelays);
		Plan plan = lateIntermediateArrivalThenLongFinalLeg();
		var index = index(PlanATransitCatchabilityTestFixtures.emptySchedule(), STEP, 0.0);

		var feasibility = evaluate(plan, config, 108_000.0, 2_100.0, STEP, index);

		assertEquals(106_000.0, feasibility.terminalArrivalTime(), 1.0e-9);
		assertTrue(feasibility.reachesQsimEndBoundary());
		assertFalse(feasibility.isFeasible());
		assertEquals("qsim_end_boundary", PlanAFeasibilityAudit.describeInfeasibility(feasibility));
	}

	@Test
	void reportsEveryPreMobsimFailureBranchInsteadOfOnlyAnUnsafeCount() {
		var config = ConfigUtils.createConfig();
		config.plans().setTripDurationHandling(TripDurationHandling.ignoreDelays);
		Plan fallbackOnlyPt = twoLegPlan(
				TransportMode.walk, TransportMode.pt,
				TransportMode.non_network_walk, TransportMode.pt);
		var feasibility = evaluate(
				fallbackOnlyPt,
				config,
				108_000.0,
				0.0,
				STEP,
				index(PlanATransitCatchabilityTestFixtures.emptySchedule(), STEP, 0.0));

		assertFalse(feasibility.isFeasible());
		assertEquals(
				"pt_fallback_only_walk|qsim_end_boundary",
				PlanAFeasibilityAudit.describeInfeasibility(feasibility));
	}

	@Test
	void longDwellWithBoardingTimeEqualToReadyPassesWhenDepartureTickIsLater() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "route", "access", "egress",
				10.0, 35.0, 110.0, true, 90.0);
		Plan plan = onePtLegPlan(
				100.0,
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 100.0, 100.0));

		var feasibility = evaluate(
				plan, ConfigUtils.createConfig(), 1_000.0, 0.0, STEP, index(schedule, STEP, 0.0));

		assertTrue(feasibility.isFeasible());
		assertEquals(200.0, feasibility.terminalArrivalTime(), 1.0e-9);
		assertEquals(0, feasibility.boardingPlannedServiceMissedLegs());
	}

	@Test
	void lastDepartureOnReadyTickIsUncatchableButExactlyOneTickLaterPasses() {
		var equalSchedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				equalSchedule, "line", "route", "access", "egress",
				0.0, 10.0, 60.0, true, 90.0);
		var laterSchedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				laterSchedule, "line", "route", "access", "egress",
				0.0, 11.0, 61.0, true, 90.0);
		Plan equal = onePtLegPlan(
				100.0,
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 90.0, 60.0));
		Plan oneTickLater = onePtLegPlan(
				100.0,
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 90.0, 61.0));

		var rejected = evaluate(
				equal, ConfigUtils.createConfig(), 1_000.0, 0.0, STEP,
				index(equalSchedule, STEP, 0.0));
		var accepted = evaluate(
				oneTickLater, ConfigUtils.createConfig(), 1_000.0, 0.0, STEP,
				index(laterSchedule, STEP, 0.0));

		assertFalse(rejected.isFeasible());
		assertEquals(1, rejected.boardingUncatchableLegs());
		assertTrue(accepted.isFeasible());
		assertEquals(151.0, accepted.terminalArrivalTime(), 1.0e-9);
	}

	@Test
	void missingPlannedDepartureIsRescuedByNextSameLineServiceAndPropagatesItsArrival() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "planned-route", "access", "egress",
				0.0, 10.0, 60.0, true, 90.0);
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "other-route", "access", "egress",
				0.0, 10.0, 70.0, true, 190.0);
		Plan plan = onePtLegPlan(
				100.0,
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "planned-route", "access", "egress", 90.0, 60.0));

		var feasibility = evaluate(
				plan, ConfigUtils.createConfig(), 1_000.0, 0.0, STEP, index(schedule, STEP, 0.0));

		assertTrue(feasibility.isFeasible());
		assertEquals(1, feasibility.boardingPlannedServiceMissedLegs());
		assertEquals(1, feasibility.boardingNextServiceRescueLegs());
		assertEquals(260.0, feasibility.terminalArrivalTime(), 1.0e-9);
	}

	@Test
	void propagatesSelectedServiceArrivalAcrossASecondPtBoarding() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line-1", "route-1", "access-1", "egress-1",
				0.0, 1.0, 100.0, true, 100.0);
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line-2", "route-2", "access-2", "egress-2",
				0.0, 5.0, 50.0, true, 200.0, 215.0);
		Plan plan = PopulationUtils.createPlan();
		var home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		home.setEndTime(100.0);
		addPtLeg(plan, PlanATransitCatchabilityTestFixtures.passengerRoute(
				"line-1", "route-1", "access-1", "egress-1", 100.0, 100.0));
		addInteraction(plan);
		Leg transfer = PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		transfer.setTravelTime(10.0);
		TripStructureUtils.setRoutingMode(transfer, TransportMode.pt);
		addInteraction(plan);
		addPtLeg(plan, PlanATransitCatchabilityTestFixtures.passengerRoute(
				"line-2", "route-2", "access-2", "egress-2", 200.0, 50.0));
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));

		var feasibility = evaluate(
				plan, ConfigUtils.createConfig(), 1_000.0, 0.0, STEP, index(schedule, STEP, 0.0));

		assertTrue(feasibility.isFeasible());
		assertEquals(265.0, feasibility.terminalArrivalTime(), 1.0e-9);
		assertEquals(1, feasibility.boardingPlannedServiceMissedLegs());
		assertEquals(1, feasibility.boardingNextServiceRescueLegs());
	}

	@Test
	void missingBoardingLineStopAndInvalidRouteStructureFailClosed() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "route", "access", "egress",
				0.0, 10.0, 60.0, true, 100.0);
		DefaultTransitPassengerRoute missingBoarding = new DefaultTransitPassengerRoute(
				null, null,
				Id.create("access", TransitStopFacility.class),
				Id.create("egress", TransitStopFacility.class),
				Id.create("line", TransitLine.class),
				Id.create("route", TransitRoute.class));
		missingBoarding.setTravelTime(60.0);
		DefaultTransitPassengerRoute missingLine = new DefaultTransitPassengerRoute(
				null, null,
				Id.create("access", TransitStopFacility.class),
				Id.create("egress", TransitStopFacility.class),
				null,
				Id.create("route", TransitRoute.class));
		missingLine.setBoardingTime(100.0);
		missingLine.setTravelTime(60.0);
		DefaultTransitPassengerRoute missingAccess = new DefaultTransitPassengerRoute(
				null, null,
				null,
				Id.create("egress", TransitStopFacility.class),
				Id.create("line", TransitLine.class),
				Id.create("route", TransitRoute.class));
		missingAccess.setBoardingTime(100.0);
		missingAccess.setTravelTime(60.0);
		DefaultTransitPassengerRoute wrongRoute =
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "wrong-route", "access", "egress", 100.0, 60.0);

		var missing = evaluate(
				onePtLegPlan(100.0, missingBoarding),
				ConfigUtils.createConfig(), 1_000.0, 0.0, STEP, index(schedule, STEP, 0.0));
		var wrong = evaluate(
				onePtLegPlan(100.0, wrongRoute),
				ConfigUtils.createConfig(), 1_000.0, 0.0, STEP, index(schedule, STEP, 0.0));
		var noLine = evaluate(
				onePtLegPlan(100.0, missingLine),
				ConfigUtils.createConfig(), 1_000.0, 0.0, STEP, index(schedule, STEP, 0.0));
		var noAccess = evaluate(
				onePtLegPlan(100.0, missingAccess),
				ConfigUtils.createConfig(), 1_000.0, 0.0, STEP, index(schedule, STEP, 0.0));

		assertEquals(1, missing.boardingScheduleMissingLegs());
		assertEquals(1, wrong.boardingScheduleMissingLegs());
		assertEquals(1, noLine.boardingScheduleMissingLegs());
		assertEquals(1, noAccess.boardingScheduleMissingLegs());
		assertFalse(missing.isFeasible());
		assertFalse(wrong.isFeasible());
	}

	@Test
	void unsupportedAwaitDepartureFailsClosedWhenNoProvableServiceExists() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "route", "access", "egress",
				0.0, 10.0, 60.0, false, 100.0);
		Plan plan = onePtLegPlan(
				100.0,
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 100.0, 60.0));

		var feasibility = evaluate(
				plan, ConfigUtils.createConfig(), 1_000.0, 0.0, STEP, index(schedule, STEP, 0.0));

		assertEquals(1, feasibility.boardingScheduleMissingLegs());
		assertFalse(feasibility.isFeasible());
	}

	@Test
	void arrivalOnlyAccessOffsetFailsClosedEvenWhenAwaitDepartureFlagIsTrue() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addArrivalOnlyRoute(
				schedule, "line", "route", "access", "egress", 100.0);
		Plan plan = onePtLegPlan(
				100.0,
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 110.0, 60.0));

		var feasibility = evaluate(
				plan, ConfigUtils.createConfig(), 1_000.0, 0.0, STEP, index(schedule, STEP, 0.0));

		assertEquals(1, feasibility.boardingScheduleMissingLegs());
		assertFalse(feasibility.isFeasible());
	}

	@Test
	void sameDepartureTickWithDifferentArrivalsIsAmbiguous() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "planned-route", "access", "egress",
				0.0, 20.0, 100.0, true, 90.0);
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "other-route", "access", "egress",
				0.0, 20.0, 110.0, true, 90.0);
		Plan plan = onePtLegPlan(
				100.0,
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "planned-route", "access", "egress", 90.0, 100.0));

		var feasibility = evaluate(
				plan, ConfigUtils.createConfig(), 1_000.0, 0.0, STEP, index(schedule, STEP, 0.0));

		assertEquals(1, feasibility.boardingScheduleAmbiguousLegs());
		assertFalse(feasibility.isFeasible());
	}

	@Test
	void reproducesS1r4OriginalAndPlus900SecondLastTrainCases() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "route", "access", "egress",
				1_618.0, 1_653.0, 2_000.0, true, 79_970.0, 80_810.0);
		var index = index(schedule, STEP, 0.0);
		Plan original = ptPlanWithAccessWalk(
				81_308.0, 255.0,
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 81_588.0, 840.0));
		Plan plus900 = ptPlanWithAccessWalk(
				82_208.0, 255.0,
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 82_463.0, 840.0));

		var originalResult =
				evaluate(original, ConfigUtils.createConfig(), 108_000.0, 0.0, STEP, index);
		var plus900Result =
				evaluate(plus900, ConfigUtils.createConfig(), 108_000.0, 0.0, STEP, index);

		assertTrue(originalResult.isFeasible());
		assertEquals(81_970.0, originalResult.terminalArrivalTime(), 1.0e-9);
		assertFalse(plus900Result.isFeasible());
		assertEquals(1, plus900Result.boardingUncatchableLegs());
		assertEquals(0, plus900Result.boardingNextServiceRescueLegs());
	}

	@Test
	void fractionalActivityAndWalkAdvanceAtEveryQsimTickWithNonZeroOrigin() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "route", "access", "egress",
				0.0, 2.0, 20.0, true, 100.0);
		Plan plan = ptPlanWithAccessWalk(
				100.1, 1.1,
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 100.0, 20.0));
		double origin = 0.5;
		var config = ConfigUtils.createConfig();
		config.qsim().setStartTime(origin);

		var feasibility = evaluate(
				plan,
				config,
				1_000.0,
				0.0,
				STEP,
				index(schedule, STEP, origin));

		assertFalse(feasibility.isFeasible(),
				"activity 100.1 -> tick 100.5, walk 1.1 -> tick 102.5, equal to departure tick");
		assertEquals(1, feasibility.boardingUncatchableLegs());
	}

	@Test
	void nonDefaultTenSecondStepUsesTickOrderingRatherThanRawSlack() {
		double step = 10.0;
		var equalSchedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				equalSchedule, "line", "route", "access", "egress",
				0.0, 0.0, 60.0, true, 100.0);
		var laterTickSchedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				laterTickSchedule, "line", "route", "access", "egress",
				0.0, 0.1, 60.1, true, 100.0);
		var route = PlanATransitCatchabilityTestFixtures.passengerRoute(
				"line", "route", "access", "egress", 100.0, 60.0);

		var equal = evaluate(
				onePtLegPlan(100.0, route),
				ConfigUtils.createConfig(),
				1_000.0,
				0.0,
				step,
				index(equalSchedule, step, 0.0));
		var laterTick = evaluate(
				onePtLegPlan(100.0, route.clone()),
				ConfigUtils.createConfig(),
				1_000.0,
				0.0,
				step,
				index(laterTickSchedule, step, 0.0));

		assertFalse(equal.isFeasible());
		assertTrue(laterTick.isFeasible(),
				"100.1 seconds is on tick 110 and is strictly later than ready tick 100");
		assertEquals(170.0, laterTick.terminalArrivalTime(), 1.0e-9);
	}

	@Test
	void chainedTransitPassengerRouteFailsClosedUntilExplicitlySupported() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "route", "access", "egress",
				0.0, 10.0, 60.0, true, 100.0);
		DefaultTransitPassengerRoute outer =
				PlanATransitCatchabilityTestFixtures.passengerRoute(
						"line", "route", "access", "egress", 100.0, 60.0);
		outer.chainedRoute = PlanATransitCatchabilityTestFixtures.passengerRoute(
				"line", "route", "access", "egress", 100.0, 60.0);

		var feasibility = evaluate(
				onePtLegPlan(100.0, outer),
				ConfigUtils.createConfig(),
				1_000.0,
				0.0,
				STEP,
				index(schedule, STEP, 0.0));

		assertFalse(feasibility.isFeasible());
		assertEquals(1, feasibility.boardingScheduleMissingLegs());
	}

	@Test
	void nonPtTourIsUnaffectedByAnEmptyTimetable() {
		Plan plan = PopulationUtils.createPlan();
		var home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		home.setEndTime(100.0);
		Leg car = PopulationUtils.createAndAddLeg(plan, TransportMode.car);
		car.setTravelTime(50.0);
		TripStructureUtils.setRoutingMode(car, TransportMode.car);
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));

		var feasibility = evaluate(
				plan, ConfigUtils.createConfig(), 1_000.0, 0.0, 10.0,
				index(PlanATransitCatchabilityTestFixtures.emptySchedule(), 10.0, 0.0));

		assertTrue(feasibility.isFeasible());
		assertEquals(150.0, feasibility.terminalArrivalTime(), 1.0e-9);
	}

	private static PlanAWholeTourFeasibleModeChoice.WholeTourFeasibility evaluate(
			Plan plan,
			org.matsim.core.config.Config config,
			double qsimEnd,
			double margin,
			double step,
			PlanATransitBoardingDeadlineIndex index) {
		return PlanAWholeTourFeasibleModeChoice.evaluateWholeTourFeasibility(
				plan, TimeInterpretation.create(config), qsimEnd, margin, step, index);
	}

	private static PlanATransitBoardingDeadlineIndex index(
			org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
			double step,
			double origin) {
		return PlanATransitBoardingDeadlineIndex.build(schedule, step, origin);
	}

	private static Plan onePtLegPlan(double readyTime, DefaultTransitPassengerRoute route) {
		Plan plan = PopulationUtils.createPlan();
		var home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		home.setEndTime(readyTime);
		addPtLeg(plan, route);
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));
		return plan;
	}

	private static Plan ptPlanWithAccessWalk(
			double originEndTime,
			double accessTravelTime,
			DefaultTransitPassengerRoute route) {
		Plan plan = PopulationUtils.createPlan();
		var home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		home.setEndTime(originEndTime);
		Leg access = PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		access.setTravelTime(accessTravelTime);
		TripStructureUtils.setRoutingMode(access, TransportMode.pt);
		addInteraction(plan);
		addPtLeg(plan, route);
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));
		return plan;
	}

	private static void addPtLeg(Plan plan, DefaultTransitPassengerRoute route) {
		Leg leg = PopulationUtils.createAndAddLeg(plan, TransportMode.pt);
		TripStructureUtils.setRoutingMode(leg, TransportMode.pt);
		leg.setRoute(route);
	}

	private static void addInteraction(Plan plan) {
		var interaction = PopulationUtils.createAndAddActivityFromCoord(
				plan, "pt interaction", new Coord(50, 0));
		interaction.setMaximumDuration(0.0);
	}

	private static Plan twoLegPlan(
			String firstMode, String firstRoutingMode, String secondMode, String secondRoutingMode) {
		Plan plan = PopulationUtils.createPlan();
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		Leg first = PopulationUtils.createAndAddLeg(plan, firstMode);
		TripStructureUtils.setRoutingMode(first, firstRoutingMode);
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));
		Leg second = PopulationUtils.createAndAddLeg(plan, secondMode);
		TripStructureUtils.setRoutingMode(second, secondRoutingMode);
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		return plan;
	}

	private static Plan ptWithWalkingAccessAndEgress() {
		Plan plan = PopulationUtils.createPlan();
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		Leg access = PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		TripStructureUtils.setRoutingMode(access, TransportMode.pt);
		addInteraction(plan);
		addPtLeg(plan, PlanATransitCatchabilityTestFixtures.passengerRoute(
				"line", "route", "access", "egress", 100.0, 60.0));
		addInteraction(plan);
		Leg egress = PopulationUtils.createAndAddLeg(plan, TransportMode.walk);
		TripStructureUtils.setRoutingMode(egress, TransportMode.pt);
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
