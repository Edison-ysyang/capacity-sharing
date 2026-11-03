package org.matsim.run.r17;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.PlanAlgorithm;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.modules.AbstractMultithreadedModule;
import org.matsim.core.replanning.selectors.RandomPlanSelector;
import org.matsim.core.router.PlanRouter;
import org.matsim.core.router.TripRouter;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

/**
 * Plan-A replacement for MATSim's {@code ReRoute} strategy.
 *
 * <p>The stock strategy can write a router fallback-only walk itinerary into plan memory
 * when a live timetable changes.  Plan-A already rejects that output in its mode-choice
 * and time-mutation strategies; this provider applies the identical whole-tour invariant
 * to re-routing.  An infeasible proposal is discarded and the pre-existing selected plan
 * is retained unchanged.  It does not repair a route, choose another mode, or change the
 * timetable.</p>
 */
public final class PlanAFeasibleReRoute implements Provider<PlanStrategy> {
	private final GlobalConfigGroup globalConfig;
	private final ActivityFacilities facilities;
	private final Provider<TripRouter> tripRouterProvider;
	private final TimeInterpretation timeInterpretation;
	private final double qsimEndTime;
	private final double qsimTimeStep;
	private final TransitSchedule transitSchedule;
	private final PlanAFeasibilityAudit feasibilityAudit;

	@Inject
	public PlanAFeasibleReRoute(
			Config config,
			Scenario scenario,
			GlobalConfigGroup globalConfig,
			ActivityFacilities facilities,
			Provider<TripRouter> tripRouterProvider,
			TimeInterpretation timeInterpretation,
			PlanAFeasibilityAudit feasibilityAudit) {
		this.globalConfig = globalConfig;
		this.facilities = facilities;
		this.tripRouterProvider = tripRouterProvider;
		this.timeInterpretation = timeInterpretation;
		this.qsimEndTime = config.qsim().getEndTime().orElseThrow(
				() -> new IllegalStateException("Plan A feasible re-route requires qsim.endTime"));
		this.qsimTimeStep = PlanAWholeTourFeasibleModeChoice.requirePositiveQsimTimeStep(
				config.qsim().getTimeStepSize());
		this.transitSchedule = scenario.getTransitSchedule();
		this.feasibilityAudit = feasibilityAudit;
	}

	@Override
	public PlanStrategy get() {
		PlanStrategyImpl.Builder builder = new PlanStrategyImpl.Builder(new RandomPlanSelector<>());
		builder.addStrategyModule(new FeasibleReRouteModule(
				globalConfig, facilities, tripRouterProvider, timeInterpretation, qsimEndTime,
				qsimTimeStep, transitSchedule, feasibilityAudit));
		return builder.build();
	}

	static boolean acceptsReroutedPlan(PlanAWholeTourFeasibleModeChoice.WholeTourFeasibility feasibility) {
		return feasibility.isFeasible();
	}

	private static final class FeasibleReRouteModule extends AbstractMultithreadedModule {
		private final ActivityFacilities facilities;
		private final Provider<TripRouter> tripRouterProvider;
		private final TimeInterpretation timeInterpretation;
		private final double qsimEndTime;
		private final double qsimTimeStep;
		private final TransitSchedule transitSchedule;
		private PlanATransitBoardingDeadlineIndex boardingDeadlineIndex;
		private final PlanAFeasibilityAudit feasibilityAudit;

		private FeasibleReRouteModule(
				GlobalConfigGroup globalConfig,
				ActivityFacilities facilities,
				Provider<TripRouter> tripRouterProvider,
				TimeInterpretation timeInterpretation,
				double qsimEndTime,
				double qsimTimeStep,
				TransitSchedule transitSchedule,
				PlanAFeasibilityAudit feasibilityAudit) {
			super(globalConfig);
			this.facilities = facilities;
			this.tripRouterProvider = tripRouterProvider;
			this.timeInterpretation = timeInterpretation;
			this.qsimEndTime = qsimEndTime;
			this.qsimTimeStep = qsimTimeStep;
			this.transitSchedule = transitSchedule;
			this.feasibilityAudit = feasibilityAudit;
		}

		@Override
		protected void beforePrepareReplanningHook(ReplanningContext replanningContext) {
			boardingDeadlineIndex = PlanATransitBoardingDeadlineIndex.build(
					transitSchedule, qsimTimeStep, timeInterpretation.getSimulationStartTime());
		}

		@Override
		public PlanAlgorithm getPlanAlgoInstance() {
			if (boardingDeadlineIndex == null) {
				throw new IllegalStateException("Plan A re-route timetable index was not refreshed before replanning");
			}
			return new FeasibleReRouteAlgorithm(
					new PlanRouter(tripRouterProvider.get(), facilities, timeInterpretation),
					timeInterpretation, qsimEndTime, qsimTimeStep, boardingDeadlineIndex,
					feasibilityAudit);
		}
	}

	private static final class FeasibleReRouteAlgorithm implements PlanAlgorithm {
		private final PlanRouter planRouter;
		private final TimeInterpretation timeInterpretation;
		private final double qsimEndTime;
		private final double qsimTimeStep;
		private final PlanATransitBoardingDeadlineIndex boardingDeadlineIndex;
		private final PlanAFeasibilityAudit feasibilityAudit;

		private FeasibleReRouteAlgorithm(
				PlanRouter planRouter,
				TimeInterpretation timeInterpretation,
				double qsimEndTime,
				double qsimTimeStep,
				PlanATransitBoardingDeadlineIndex boardingDeadlineIndex,
				PlanAFeasibilityAudit feasibilityAudit) {
			this.planRouter = planRouter;
			this.timeInterpretation = timeInterpretation;
			this.qsimEndTime = qsimEndTime;
			this.qsimTimeStep = qsimTimeStep;
			this.boardingDeadlineIndex = boardingDeadlineIndex;
			this.feasibilityAudit = feasibilityAudit;
		}

		@Override
		public void run(Plan plan) {
			Plan beforeReRoute = PopulationUtils.createPlan(plan.getPerson());
			PopulationUtils.copyFromTo(plan, beforeReRoute, true);
			planRouter.run(plan);
			var feasibility = PlanAWalkFeasibleTimeAllocationMutatorReRoute.evaluatePostMutationFeasibility(
					plan, timeInterpretation, qsimEndTime, qsimTimeStep, boardingDeadlineIndex);
			if (!acceptsReroutedPlan(feasibility)) {
				var recovery = PlanAPlanMemoryFeasibilityFallback.restore(
						plan,
						beforeReRoute,
						timeInterpretation,
						qsimEndTime,
						qsimTimeStep,
						boardingDeadlineIndex);
				feasibilityAudit.recordPlanMemoryFallback(recovery);
				if (!recovery.recovered()) {
					throw new IllegalStateException(
							"Plan-A feasible ReRoute found no executable plan-memory fallback for person "
									+ plan.getPerson().getId() + " after checking "
									+ recovery.alternativeCandidatesChecked() + " alternative plan(s)");
				}
			}
		}
	}
}
