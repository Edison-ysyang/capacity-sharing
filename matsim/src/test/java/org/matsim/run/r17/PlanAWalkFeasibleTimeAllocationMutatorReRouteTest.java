package org.matsim.run.r17;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.timing.TimeInterpretation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAWalkFeasibleTimeAllocationMutatorReRouteTest {
	@Test
	void recognizesOnlyAllWalkingToursForTheTimeMutationGuard() {
		Plan walking = twoLegPlan(TransportMode.walk, TransportMode.walk);
		Plan mixed = twoLegPlan(TransportMode.walk, TransportMode.pt);

		assertTrue(PlanAWalkFeasibleTimeAllocationMutatorReRoute.isWholeTourWalk(walking));
		assertFalse(PlanAWalkFeasibleTimeAllocationMutatorReRoute.isWholeTourWalk(mixed));
	}

	@Test
	void evaluatesPostMutationFeasibilityForEveryModeAndPtFallbacks() {
		var config = ConfigUtils.createConfig();
		TimeInterpretation timeInterpretation = TimeInterpretation.create(config);
		var emptyIndex = PlanATransitBoardingDeadlineIndex.build(
				PlanATransitCatchabilityTestFixtures.emptySchedule(), 1.0, 0.0);
		Plan ptFallback = timedTwoLegPlan(
				TransportMode.walk, TransportMode.pt,
				TransportMode.walk, TransportMode.pt);
		Plan lateCar = timedTwoLegPlan(
				TransportMode.car, TransportMode.car,
				TransportMode.car, TransportMode.car);

		var fallbackFeasibility = PlanAWalkFeasibleTimeAllocationMutatorReRoute
				.evaluatePostMutationFeasibility(
						ptFallback, timeInterpretation, 1_000.0, 1.0, emptyIndex);
		var lateCarFeasibility = PlanAWalkFeasibleTimeAllocationMutatorReRoute
				.evaluatePostMutationFeasibility(
						lateCar, timeInterpretation, 500.0, 1.0, emptyIndex);

		assertFalse(fallbackFeasibility.isFeasible());
		assertTrue(fallbackFeasibility.hasPtFallbackOnlyWalk());
		assertFalse(fallbackFeasibility.reachesQsimEndBoundary());
		assertFalse(lateCarFeasibility.isFeasible());
		assertFalse(lateCarFeasibility.hasPtFallbackOnlyWalk());
		assertTrue(lateCarFeasibility.reachesQsimEndBoundary());
		assertFalse(PlanAFeasibleReRoute.acceptsReroutedPlan(fallbackFeasibility));
		assertFalse(PlanAFeasibleReRoute.acceptsReroutedPlan(lateCarFeasibility));
	}

	@Test
	void postMutationUsesTheSameNextServiceRescueAndArrivalPropagation() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "route", "access", "egress",
				0.0, 10.0, 60.0, true, 90.0, 190.0);
		Plan plan = PopulationUtils.createPlan();
		var home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		home.setEndTime(100.0);
		Leg leg = PopulationUtils.createAndAddLeg(plan, TransportMode.pt);
		TripStructureUtils.setRoutingMode(leg, TransportMode.pt);
		leg.setRoute(PlanATransitCatchabilityTestFixtures.passengerRoute(
				"line", "route", "access", "egress", 90.0, 60.0));
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));
		var index = PlanATransitBoardingDeadlineIndex.build(schedule, 1.0, 0.0);

		var feasibility = PlanAWalkFeasibleTimeAllocationMutatorReRoute
				.evaluatePostMutationFeasibility(
						plan,
						TimeInterpretation.create(ConfigUtils.createConfig()),
						1_000.0,
						1.0,
						index);

		assertTrue(feasibility.isFeasible());
		assertTrue(PlanAFeasibleReRoute.acceptsReroutedPlan(feasibility));
		assertEquals(1, feasibility.boardingPlannedServiceMissedLegs());
		assertEquals(1, feasibility.boardingNextServiceRescueLegs());
		assertEquals(250.0, feasibility.terminalArrivalTime(), 1.0e-9);
	}

	@Test
	void restoresAnAlreadyRoutedSnapshotWithoutReroutingIt() {
		var schedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		PlanATransitCatchabilityTestFixtures.addRoute(
				schedule, "line", "route", "access", "egress",
				0.0, 10.0, 60.0, true, 90.0, 190.0);
		Person person = PopulationUtils.getFactory().createPerson(
				org.matsim.api.core.v01.Id.createPersonId("snapshot"));
		Plan snapshot = oneBoardablePtPlan(person, 100.0, 90.0, 60.0);
		person.addPlan(snapshot);
		Plan working = PopulationUtils.createPlan(person);
		PopulationUtils.copyFromTo(
				timedTwoLegPlan(TransportMode.walk, TransportMode.pt, TransportMode.walk, TransportMode.pt),
				working,
				true);
		person.addPlan(working);
		person.setSelectedPlan(working);

		var recovery = PlanAPlanMemoryFeasibilityFallback.restore(
				working,
				snapshot,
				TimeInterpretation.create(ConfigUtils.createConfig()),
				1_000.0,
				1.0,
				PlanATransitBoardingDeadlineIndex.build(schedule, 1.0, 0.0));

		assertTrue(recovery.recovered());
		assertEquals(PlanAPlanMemoryFeasibilityFallback.Source.SELECTED_SNAPSHOT, recovery.source());
		assertEquals(0, recovery.alternativeCandidatesChecked());
		assertFalse(PlanAWholeTourFeasibleModeChoice.hasPtFallbackOnlyWalkTrip(working));
	}

	@Test
	void fallsBackToHighestScoredExecutableMemoryPlanWithoutChangingMemorySize() {
		var emptySchedule = PlanATransitCatchabilityTestFixtures.emptySchedule();
		Person person = PopulationUtils.getFactory().createPerson(
				org.matsim.api.core.v01.Id.createPersonId("memory"));
		Plan unsafe = timedTwoLegPlan(
				TransportMode.walk, TransportMode.pt, TransportMode.walk, TransportMode.pt);
		unsafe.setScore(10.0);
		person.addPlan(unsafe);
		Plan safe = timedTwoLegPlan(
				TransportMode.walk, TransportMode.walk, TransportMode.walk, TransportMode.walk);
		safe.setScore(5.0);
		person.addPlan(safe);
		Plan working = PopulationUtils.createPlan(person);
		PopulationUtils.copyFromTo(unsafe, working, true);
		person.addPlan(working);
		person.setSelectedPlan(working);
		int memorySizeBefore = person.getPlans().size();

		var recovery = PlanAPlanMemoryFeasibilityFallback.restore(
				working,
				unsafe,
				TimeInterpretation.create(ConfigUtils.createConfig()),
				1_000.0,
				1.0,
				PlanATransitBoardingDeadlineIndex.build(emptySchedule, 1.0, 0.0));

		assertTrue(recovery.recovered());
		assertEquals(PlanAPlanMemoryFeasibilityFallback.Source.ALTERNATIVE_MEMORY, recovery.source());
		assertEquals(2, recovery.alternativeCandidatesChecked());
		assertEquals(memorySizeBefore, person.getPlans().size());
		assertFalse(PlanAWholeTourFeasibleModeChoice.hasPtFallbackOnlyWalkTrip(working));
		assertEquals(TransportMode.walk, ((Leg) working.getPlanElements().get(1)).getMode());
	}

	private static Plan oneBoardablePtPlan(
			Person person, double readyTime, double boardingTime, double travelTime) {
		Plan plan = PopulationUtils.createPlan(person);
		var home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		home.setEndTime(readyTime);
		Leg leg = PopulationUtils.createAndAddLeg(plan, TransportMode.pt);
		TripStructureUtils.setRoutingMode(leg, TransportMode.pt);
		leg.setRoute(PlanATransitCatchabilityTestFixtures.passengerRoute(
				"line", "route", "access", "egress", boardingTime, travelTime));
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));
		return plan;
	}

	private static Plan twoLegPlan(String firstMode, String secondMode) {
		Plan plan = PopulationUtils.createPlan();
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		var first = PopulationUtils.createAndAddLeg(plan, firstMode);
		TripStructureUtils.setRoutingMode(first, firstMode);
		PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));
		var second = PopulationUtils.createAndAddLeg(plan, secondMode);
		TripStructureUtils.setRoutingMode(second, secondMode);
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		return plan;
	}

	private static Plan timedTwoLegPlan(
			String firstMode, String firstRoutingMode, String secondMode, String secondRoutingMode) {
		Plan plan = PopulationUtils.createPlan();
		var home = PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		home.setEndTime(100.0);
		var first = PopulationUtils.createAndAddLeg(plan, firstMode);
		first.setTravelTime(50.0);
		TripStructureUtils.setRoutingMode(first, firstRoutingMode);
		var work = PopulationUtils.createAndAddActivityFromCoord(plan, "work", new Coord(100, 0));
		work.setEndTime(400.0);
		var second = PopulationUtils.createAndAddLeg(plan, secondMode);
		second.setTravelTime(100.0);
		TripStructureUtils.setRoutingMode(second, secondRoutingMode);
		PopulationUtils.createAndAddActivityFromCoord(plan, "home", new Coord(0, 0));
		return plan;
	}
}
