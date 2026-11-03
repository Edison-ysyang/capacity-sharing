package org.matsim.run.r17;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.config.groups.TimeAllocationMutatorConfigGroup;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.MutateActivityTimeAllocation;
import org.matsim.core.population.algorithms.PlanAlgorithm;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.modules.AbstractMultithreadedModule;
import org.matsim.core.replanning.selectors.RandomPlanSelector;
import org.matsim.core.router.PlanRouter;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

/**
 * Plan-A replacement for {@code TimeAllocationMutator_ReRoute}.
 *
 * <p>It uses MATSim's native time-allocation mutation unchanged. Every selected
 * plan is snapshotted, mutated, rerouted, and evaluated before it can enter plan
 * memory. A mutation is undone when any mode reaches the QSim end time or when a
 * PT-routed trip collapses to the router's fallback-only walk or has no future
 * QSim-boardable scheduled service. The historical
 * all-walk counters are retained for audit continuity, but the operational guard is
 * deliberately mode-agnostic.</p>
 *
 * <p>Like the Plan-A mode-choice guard, this class is intentionally a custom FQCN
 * strategy rather than a global override of a MATSim default strategy.</p>
 */
public final class PlanAWalkFeasibleTimeAllocationMutatorReRoute implements Provider<PlanStrategy> {
	private final GlobalConfigGroup globalConfig;
	private final TimeAllocationMutatorConfigGroup timeMutationConfig;
	private final ActivityFacilities facilities;
	private final Provider<TripRouter> tripRouterProvider;
	private final TimeInterpretation timeInterpretation;
	private final double qsimEndTime;
	private final double qsimTimeStep;
	private final TransitSchedule transitSchedule;
	private final PlanAFeasibilityAudit feasibilityAudit;

	@Inject
	public PlanAWalkFeasibleTimeAllocationMutatorReRoute(
			Config config,
			Scenario scenario,
			GlobalConfigGroup globalConfig,
			TimeAllocationMutatorConfigGroup timeMutationConfig,
			ActivityFacilities facilities,
			Provider<TripRouter> tripRouterProvider,
			TimeInterpretation timeInterpretation,
			PlanAFeasibilityAudit feasibilityAudit) {
		this.globalConfig = globalConfig;
		this.timeMutationConfig = timeMutationConfig;
		this.facilities = facilities;
		this.tripRouterProvider = tripRouterProvider;
		this.timeInterpretation = timeInterpretation;
		this.qsimEndTime = config.qsim().getEndTime().orElseThrow(
				() -> new IllegalStateException("Plan A walking time-mutation guard requires qsim.endTime"));
		this.qsimTimeStep = PlanAWholeTourFeasibleModeChoice.requirePositiveQsimTimeStep(
				config.qsim().getTimeStepSize());
		this.transitSchedule = scenario.getTransitSchedule();
		this.feasibilityAudit = feasibilityAudit;
	}

	@Override
	public PlanStrategy get() {
		PlanStrategyImpl.Builder builder = new PlanStrategyImpl.Builder(new RandomPlanSelector<>());
		builder.addStrategyModule(new WalkFeasibleTimeMutationAndRouteModule(
				globalConfig,
				timeMutationConfig,
				facilities,
				tripRouterProvider,
				timeInterpretation,
				qsimEndTime,
				qsimTimeStep,
				transitSchedule,
				feasibilityAudit));
		return builder.build();
	}

	static boolean isWholeTourWalk(Plan plan) {
		var trips = TripStructureUtils.getTrips(plan);
		return !trips.isEmpty() && trips.stream().allMatch(
				trip -> TransportMode.walk.equals(TripStructureUtils.identifyMainMode(trip.getTripElements())));
	}

	/**
	 * A post-mutation plan only needs to be executable in the current iteration, so
	 * it is checked at the actual QSim boundary (zero additional mutation margin).
	 * Future mutations are independently guarded when they are drawn.
	 */
	static PlanAWholeTourFeasibleModeChoice.WholeTourFeasibility evaluatePostMutationFeasibility(
			Plan plan,
			TimeInterpretation timeInterpretation,
			double qsimEndTime,
			double qsimTimeStep,
			PlanATransitBoardingDeadlineIndex boardingDeadlineIndex) {
		return PlanAWholeTourFeasibleModeChoice.evaluateWholeTourFeasibility(
				plan, timeInterpretation, qsimEndTime, 0.0, qsimTimeStep, boardingDeadlineIndex);
	}

	private static final class WalkFeasibleTimeMutationAndRouteModule extends AbstractMultithreadedModule {
		private final TimeAllocationMutatorConfigGroup timeMutationConfig;
		private final ActivityFacilities facilities;
		private final Provider<TripRouter> tripRouterProvider;
		private final TimeInterpretation timeInterpretation;
		private final double qsimEndTime;
		private final double qsimTimeStep;
		private final TransitSchedule transitSchedule;
		private PlanATransitBoardingDeadlineIndex boardingDeadlineIndex;
		private final PlanAFeasibilityAudit feasibilityAudit;

		private WalkFeasibleTimeMutationAndRouteModule(
				GlobalConfigGroup globalConfig,
				TimeAllocationMutatorConfigGroup timeMutationConfig,
				ActivityFacilities facilities,
				Provider<TripRouter> tripRouterProvider,
				TimeInterpretation timeInterpretation,
				double qsimEndTime,
				double qsimTimeStep,
				TransitSchedule transitSchedule,
				PlanAFeasibilityAudit feasibilityAudit) {
			super(globalConfig);
			this.timeMutationConfig = timeMutationConfig;
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
			/* One live-timetable snapshot is shared by every worker in this phase. */
			boardingDeadlineIndex = PlanATransitBoardingDeadlineIndex.build(
					transitSchedule,
					qsimTimeStep,
					timeInterpretation.getSimulationStartTime());
		}

		@Override
		public PlanAlgorithm getPlanAlgoInstance() {
			if (boardingDeadlineIndex == null) {
				throw new IllegalStateException(
						"Plan A time-mutation timetable index was not refreshed before replanning");
			}
			PlanAlgorithm nativeMutation = new MutateActivityTimeAllocation(
					timeMutationConfig.getMutationRange(),
					timeMutationConfig.isAffectingDuration(),
					MatsimRandom.getLocalInstance(),
					timeMutationConfig.getLatestActivityEndTime(),
					timeMutationConfig.isMutateAroundInitialEndTimeOnly(),
					timeMutationConfig.getMutationRangeStep());
			return new WalkFeasibleTimeMutationAndRouteAlgorithm(
					nativeMutation,
					new PlanRouter(tripRouterProvider.get(), facilities, timeInterpretation),
					timeInterpretation,
					qsimEndTime,
					qsimTimeStep,
					boardingDeadlineIndex,
					feasibilityAudit);
		}
	}

	private static final class WalkFeasibleTimeMutationAndRouteAlgorithm implements PlanAlgorithm {
		private final PlanAlgorithm nativeMutation;
		private final PlanRouter planRouter;
		private final TimeInterpretation timeInterpretation;
		private final double qsimEndTime;
		private final double qsimTimeStep;
		private final PlanATransitBoardingDeadlineIndex boardingDeadlineIndex;
		private final PlanAFeasibilityAudit feasibilityAudit;

		private WalkFeasibleTimeMutationAndRouteAlgorithm(
				PlanAlgorithm nativeMutation,
				PlanRouter planRouter,
				TimeInterpretation timeInterpretation,
				double qsimEndTime,
				double qsimTimeStep,
				PlanATransitBoardingDeadlineIndex boardingDeadlineIndex,
				PlanAFeasibilityAudit feasibilityAudit) {
			this.nativeMutation = nativeMutation;
			this.planRouter = planRouter;
			this.timeInterpretation = timeInterpretation;
			this.qsimEndTime = qsimEndTime;
			this.qsimTimeStep = qsimTimeStep;
			this.boardingDeadlineIndex = boardingDeadlineIndex;
			this.feasibilityAudit = feasibilityAudit;
		}

		@Override
		public void run(Plan plan) {
			boolean wholeTourWalk = isWholeTourWalk(plan);
			feasibilityAudit.recordTimeMutationProposal();
			if (wholeTourWalk) {
				feasibilityAudit.recordWalkTimeMutationProposal();
			}
			Plan beforeMutation = PopulationUtils.createPlan(plan.getPerson());
			PopulationUtils.copyFromTo(plan, beforeMutation, true);

			nativeMutation.run(plan);
			planRouter.run(plan);

			PlanAWholeTourFeasibleModeChoice.WholeTourFeasibility mutatedFeasibility =
					evaluatePostMutationFeasibility(
							plan, timeInterpretation, qsimEndTime, qsimTimeStep, boardingDeadlineIndex);
			feasibilityAudit.recordTimeMutationBoardingDiagnostics(mutatedFeasibility);
			if (!mutatedFeasibility.isFeasible()) {
				feasibilityAudit.recordRevertedInfeasibleTimeMutation();
				if (mutatedFeasibility.hasBoardingUncatchable()) {
					feasibilityAudit.recordRevertedBoardingUncatchableTimeMutation();
				}
				if (mutatedFeasibility.hasBoardingScheduleMissing()) {
					feasibilityAudit.recordRevertedBoardingScheduleMissingTimeMutation();
				}
				if (mutatedFeasibility.hasBoardingScheduleAmbiguity()) {
					feasibilityAudit.recordRevertedBoardingScheduleAmbiguousTimeMutation();
				}
				if (wholeTourWalk) {
					feasibilityAudit.recordRevertedWalkTimeMutation();
				}
				/*
				 * copyFromTo restores both times and the already routed historical
				 * itinerary. Rerouting it again is neither a restoration nor harmless:
				 * after a timetable intervention it can replace a QSim-catchable old PT
				 * route with the router's fallback-only walk. Prefer the exact snapshot,
				 * then another executable memory plan, without rewriting plan memory.
				 */
				var recovery = PlanAPlanMemoryFeasibilityFallback.restore(
						plan,
						beforeMutation,
						timeInterpretation,
						qsimEndTime,
						qsimTimeStep,
						boardingDeadlineIndex);
				feasibilityAudit.recordPlanMemoryFallback(recovery);
				if (!recovery.recovered()) {
					var restored = recovery.snapshotFeasibility();
					throw new IllegalStateException("Plan-A found no operationally feasible plan-memory fallback"
							+ " for person " + plan.getPerson().getId()
							+ "; selected snapshot and " + recovery.alternativeCandidatesChecked()
							+ " alternative memory plan(s) failed the live-timetable invariant."
							+ " terminalArrival=" + restored.terminalArrivalTime()
							+ "; ptFallbackOnlyWalk=" + restored.hasPtFallbackOnlyWalk()
							+ "; reachesQsimEndBoundary=" + restored.reachesQsimEndBoundary()
							+ "; boardingUncatchableLegs=" + restored.boardingUncatchableLegs()
							+ "; boardingScheduleMissingLegs=" + restored.boardingScheduleMissingLegs()
							+ "; boardingScheduleAmbiguousLegs=" + restored.boardingScheduleAmbiguousLegs());
				}
			}
		}
	}
}
