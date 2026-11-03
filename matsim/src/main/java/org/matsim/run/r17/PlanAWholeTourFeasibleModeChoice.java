package org.matsim.run.r17;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ChangeModeConfigGroup;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.PlanAlgorithm;
import org.matsim.core.population.algorithms.TripsToLegsAlgorithm;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.modules.AbstractMultithreadedModule;
import org.matsim.core.replanning.modules.TripsToLegsModule;
import org.matsim.core.replanning.selectors.RandomPlanSelector;
import org.matsim.core.router.PlanRouter;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Plan-A-only whole-tour mode-choice strategy with an operational feasibility guard.
 *
 * <p>The standard {@code ChangeTripMode} already changes every leg in a plan to the
 * same mode. This strategy preserves that behavior and its {@code changeMode}
 * configuration, but retains only alternatives that can be fully routed within the
 * QSim horizon. A provisional {@code pt} alternative is additionally rejected if
 * the router returns a trip that contains only walking legs while retaining
 * {@code routingMode=pt}; that is MATSim's no-service fallback rather than a usable
 * public-transport itinerary. Its end-time safety margin is derived from the
 * maximum positive draw of the configured {@code timeAllocationMutator}; this
 * prevents a newly created tour from being immediately vulnerable to a legal
 * positive time mutation. Every routed PT boarding must also have a same-line
 * service whose departure tick is strictly later than the access/transfer-arrival
 * tick. The six configured modes remain the global mode universe;
 * the actually feasible alternative set is tour- and departure-time-specific.</p>
 *
 * <p>Use this class by its fully qualified name in a replanning strategy setting. It
 * deliberately lives outside {@code org.matsim.core}, so MATSim can construct it as a
 * custom {@link Provider} without overriding global default bindings.</p>
 */
public final class PlanAWholeTourFeasibleModeChoice implements Provider<PlanStrategy> {
	private final GlobalConfigGroup globalConfig;
	private final ChangeModeConfigGroup changeModeConfig;
	private final ActivityFacilities facilities;
	private final Provider<TripRouter> tripRouterProvider;
	private final TimeInterpretation timeInterpretation;
	private final double qsimEndTime;
	private final double qsimTimeStep;
	private final TransitSchedule transitSchedule;
	private final double timeMutationFeasibilityMargin;
	private final PlanAFeasibilityAudit feasibilityAudit;

	@Inject
	public PlanAWholeTourFeasibleModeChoice(
			Config config,
			Scenario scenario,
			GlobalConfigGroup globalConfig,
			ChangeModeConfigGroup changeModeConfig,
			ActivityFacilities facilities,
			Provider<TripRouter> tripRouterProvider,
			TimeInterpretation timeInterpretation,
			PlanAFeasibilityAudit feasibilityAudit) {
		this.globalConfig = globalConfig;
		this.changeModeConfig = changeModeConfig;
		this.facilities = facilities;
		this.tripRouterProvider = tripRouterProvider;
		this.timeInterpretation = timeInterpretation;
		this.qsimEndTime = config.qsim().getEndTime().orElseThrow(
				() -> new IllegalStateException("Plan A whole-tour feasibility guard requires qsim.endTime"));
		this.qsimTimeStep = requirePositiveQsimTimeStep(config.qsim().getTimeStepSize());
		this.transitSchedule = scenario.getTransitSchedule();
		this.timeMutationFeasibilityMargin = maximumPositiveTimeMutation(
				config.timeAllocationMutator().getMutationRange(),
				config.timeAllocationMutator().getMutationRangeStep());
		this.feasibilityAudit = feasibilityAudit;
	}

	@Override
	public PlanStrategy get() {
		PlanStrategyImpl.Builder builder = new PlanStrategyImpl.Builder(new RandomPlanSelector<>());
		builder.addStrategyModule(new TripsToLegsModule(globalConfig));
		builder.addStrategyModule(new WholeTourMutationAndRouteModule(
				globalConfig,
				changeModeConfig,
				facilities,
				tripRouterProvider,
				timeInterpretation,
				qsimEndTime,
				qsimTimeStep,
				transitSchedule,
				timeMutationFeasibilityMargin,
				feasibilityAudit));
		return builder.build();
	}

	/** A tour completing at the protected end-time boundary is rejected. */
	static boolean completesBeforeQsimEnd(double terminalArrivalTime, double qsimEndTime, double safetyMargin) {
		return Double.isFinite(terminalArrivalTime)
				&& Double.isFinite(safetyMargin)
				&& safetyMargin >= 0.0
				&& terminalArrivalTime < qsimEndTime - safetyMargin;
	}

	static double requirePositiveQsimTimeStep(double qsimTimeStep) {
		if (!Double.isFinite(qsimTimeStep) || qsimTimeStep <= 0.0) {
			throw new IllegalArgumentException(
					"Plan A boarding-feasibility guard requires a finite positive qsim.timeStepSize, got "
							+ qsimTimeStep);
		}
		return qsimTimeStep;
	}

	/**
	 * Matches the discrete draw support in {@code MutateActivityTimeAllocation}.
	 * For the Plan-A setting {@code range=2700, step=300}, this is 2100 seconds.
	 */
	static double maximumPositiveTimeMutation(double mutationRange, double mutationRangeStep) {
		double range = Math.max(0.0, mutationRange);
		double step = Math.max(1.0, mutationRangeStep);
		int bins = (int) Math.ceil(range / step);
		return bins == 0 ? 0.0 : Math.max(0.0, -range + 2.0 * (bins - 1) * step);
	}

	/** Applies one main/routing mode to every leg, preserving whole-tour continuity. */
	static void applyWholeTourMode(Plan plan, String newMode) {
		for (PlanElement element : plan.getPlanElements()) {
			if (element instanceof Leg leg) {
				leg.setMode(newMode);
				TripStructureUtils.setRoutingMode(leg, newMode);
				Route route = leg.getRoute();
				if (route instanceof NetworkRoute networkRoute) {
					networkRoute.setVehicleId(null);
				}
			}
		}
	}

	/**
	 * Evaluates the exact route-level feasibility rule shared by Plan-A initial-input
	 * canonicalisation, whole-tour mode choice, and time-allocation mutation.
	 *
	 * <p>The route test intentionally looks at the routing mode rather than a person
	 * attribute: a failed PT routing request is encoded by MATSim as a walking leg
	 * with {@code routingMode=pt}. A normal PT itinerary contains at least one actual
	 * {@code pt} leg with a valid {@link TransitPassengerRoute}, so normal
	 * access/egress walking remains eligible. Each transit leg is additionally
	 * checked against the same QSim-tick next-service catchability rule used by the
	 * initial canonicalizer, time mutator, and pre-Mobsim invariant.</p>
	 */
	static WholeTourFeasibility evaluateWholeTourFeasibility(
			Plan plan,
			TimeInterpretation timeInterpretation,
			double qsimEndTime,
			double safetyMargin,
			double qsimTimeStep,
			PlanATransitBoardingDeadlineIndex boardingDeadlineIndex) {
		TripArrivalEvaluation tripArrivals = evaluateMainTripArrivals(
				plan, timeInterpretation, qsimEndTime, safetyMargin, qsimTimeStep, boardingDeadlineIndex);
		return new WholeTourFeasibility(
				hasPtFallbackOnlyWalkTrip(plan),
				tripArrivals.terminalArrivalTime(),
				tripArrivals.hasHorizonViolation(),
				tripArrivals.boardingPlannedServiceMissedLegs(),
				tripArrivals.boardingNextServiceRescueLegs(),
				tripArrivals.boardingUncatchableLegs(),
				tripArrivals.boardingScheduleMissingLegs(),
				tripArrivals.boardingScheduleAmbiguousLegs());
	}

	/**
	 * True when a PT-routed trip has no valid transit leg.
	 *
	 * <p>This is deliberately scoped to {@code routingMode=pt}. A direct walking
	 * alternative remains valid; a normal PT itinerary with walking access/egress and
	 * one or more {@code pt} legs backed by a {@link TransitPassengerRoute} remains
	 * valid too.</p>
	 */
	static boolean hasPtFallbackOnlyWalkTrip(Plan plan) {
		for (Trip trip : TripStructureUtils.getTrips(plan)) {
			List<Leg> legs = trip.getLegsOnly();
			if (legs.isEmpty() || !TransportMode.pt.equals(TripStructureUtils.getRoutingMode(legs.get(0)))) {
				continue;
			}
			if (legs.stream().noneMatch(PlanAWholeTourFeasibleModeChoice::isUsableTransitLeg)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isUsableTransitLeg(Leg leg) {
		return TransportMode.pt.equals(leg.getMode())
				&& leg.getRoute() instanceof TransitPassengerRoute route
				&& route.getLineId() != null
				&& route.getRouteId() != null;
	}

	/** Package-visible route-level result for the Plan-A canonicalisation and strategies. */
	static final class WholeTourFeasibility {
		private final boolean ptFallbackOnlyWalk;
		private final double terminalArrivalTime;
		private final boolean reachesQsimEndBoundary;
		private final int boardingPlannedServiceMissedLegs;
		private final int boardingNextServiceRescueLegs;
		private final int boardingUncatchableLegs;
		private final int boardingScheduleMissingLegs;
		private final int boardingScheduleAmbiguousLegs;

		private WholeTourFeasibility(
				boolean ptFallbackOnlyWalk,
				double terminalArrivalTime,
				boolean reachesQsimEndBoundary,
				int boardingPlannedServiceMissedLegs,
				int boardingNextServiceRescueLegs,
				int boardingUncatchableLegs,
				int boardingScheduleMissingLegs,
				int boardingScheduleAmbiguousLegs) {
			this.ptFallbackOnlyWalk = ptFallbackOnlyWalk;
			this.terminalArrivalTime = terminalArrivalTime;
			this.reachesQsimEndBoundary = reachesQsimEndBoundary;
			this.boardingPlannedServiceMissedLegs = boardingPlannedServiceMissedLegs;
			this.boardingNextServiceRescueLegs = boardingNextServiceRescueLegs;
			this.boardingUncatchableLegs = boardingUncatchableLegs;
			this.boardingScheduleMissingLegs = boardingScheduleMissingLegs;
			this.boardingScheduleAmbiguousLegs = boardingScheduleAmbiguousLegs;
		}

		boolean isFeasible() {
			return !ptFallbackOnlyWalk
					&& !reachesQsimEndBoundary
					&& boardingUncatchableLegs == 0
					&& boardingScheduleMissingLegs == 0
					&& boardingScheduleAmbiguousLegs == 0;
		}

		boolean hasPtFallbackOnlyWalk() {
			return ptFallbackOnlyWalk;
		}

		boolean reachesQsimEndBoundary() {
			return reachesQsimEndBoundary;
		}

		boolean hasBoardingUncatchable() {
			return boardingUncatchableLegs > 0;
		}

		int boardingPlannedServiceMissedLegs() {
			return boardingPlannedServiceMissedLegs;
		}

		int boardingNextServiceRescueLegs() {
			return boardingNextServiceRescueLegs;
		}

		int boardingUncatchableLegs() {
			return boardingUncatchableLegs;
		}

		boolean hasBoardingScheduleMissing() {
			return boardingScheduleMissingLegs > 0;
		}

		int boardingScheduleMissingLegs() {
			return boardingScheduleMissingLegs;
		}

		boolean hasBoardingScheduleAmbiguity() {
			return boardingScheduleAmbiguousLegs > 0;
		}

		int boardingScheduleAmbiguousLegs() {
			return boardingScheduleAmbiguousLegs;
		}

		double terminalArrivalTime() {
			return terminalArrivalTime;
		}
	}

	/**
	 * Tracks every main-trip arrival on QSim's monotonic clock.
	 *
	 * <p>{@code TimeTracker} intentionally follows the plan interpretation and may
	 * move back to a later activity's nominal fixed end time when
	 * {@code tripDurationHandling=ignoreDelays}. QSim does not: its activity agent
	 * sets each departure to {@code max(now, interpretedActivityEndTime)}. Applying
	 * the same lower bound here prevents an initially late arrival from making a
	 * following long leg look artificially feasible. Every main-trip completion is
	 * checked, rather than only the terminal one.</p>
	 */
	static TripArrivalEvaluation evaluateMainTripArrivals(
			Plan plan,
			TimeInterpretation timeInterpretation,
			double qsimEndTime,
			double safetyMargin,
			double qsimTimeStep,
			PlanATransitBoardingDeadlineIndex boardingDeadlineIndex) {
		requirePositiveQsimTimeStep(qsimTimeStep);
		if (boardingDeadlineIndex == null) {
			throw new IllegalArgumentException("Plan A boarding-feasibility guard requires a timetable index");
		}
		double currentTime = timeInterpretation.getSimulationStartTime();
		int horizonViolationTrips = 0;
		int boardingPlannedServiceMissedLegs = 0;
		int boardingNextServiceRescueLegs = 0;
		int boardingUncatchableLegs = 0;
		int boardingScheduleMissingLegs = 0;
		int boardingScheduleAmbiguousLegs = 0;
		for (Trip trip : TripStructureUtils.getTrips(plan)) {
			currentTime = advanceQsimCompatibleTime(
					trip.getOriginActivity(),
					timeInterpretation,
					currentTime,
					boardingDeadlineIndex);
			for (PlanElement element : trip.getTripElements()) {
				if (element instanceof Leg leg && leg.getRoute() instanceof TransitPassengerRoute transitRoute) {
					PlanATransitBoardingDeadlineIndex.Evaluation boarding =
							boardingDeadlineIndex.evaluate(transitRoute, currentTime, qsimTimeStep);
					if (boarding.plannedServiceMissed()) {
						boardingPlannedServiceMissedLegs++;
					}
					if (boarding.nextServiceRescue()) {
						boardingNextServiceRescueLegs++;
					}
					switch (boarding.status()) {
						case UNCATCHABLE -> boardingUncatchableLegs++;
						case MISSING -> boardingScheduleMissingLegs++;
						case AMBIGUOUS -> boardingScheduleAmbiguousLegs++;
						case SAFE -> currentTime = boarding.scheduledEgressArrivalTickTime();
					}
					if (boarding.status() != PlanATransitBoardingDeadlineIndex.Status.SAFE) {
						/*
						 * A route travel time is not an executable downstream clock once no
						 * boardable service has been resolved. Stop at the primary error.
						 */
						return new TripArrivalEvaluation(
								Double.NaN,
								horizonViolationTrips,
								boardingPlannedServiceMissedLegs,
								boardingNextServiceRescueLegs,
								boardingUncatchableLegs,
								boardingScheduleMissingLegs,
								boardingScheduleAmbiguousLegs);
					}
				} else {
					currentTime = advanceQsimCompatibleTime(
							element, timeInterpretation, currentTime, boardingDeadlineIndex);
				}
			}
			double tripArrivalTime = currentTime;
			if (!completesBeforeQsimEnd(tripArrivalTime, qsimEndTime, safetyMargin)) {
				horizonViolationTrips++;
			}
		}
		return new TripArrivalEvaluation(
				currentTime,
				horizonViolationTrips,
				boardingPlannedServiceMissedLegs,
				boardingNextServiceRescueLegs,
				boardingUncatchableLegs,
				boardingScheduleMissingLegs,
				boardingScheduleAmbiguousLegs);
	}

	private static double advanceQsimCompatibleTime(
			PlanElement element,
			TimeInterpretation timeInterpretation,
			double currentTime,
			PlanATransitBoardingDeadlineIndex boardingDeadlineIndex) {
		if (!Double.isFinite(currentTime)) {
			return Double.POSITIVE_INFINITY;
		}
		double interpretedEndTime = timeInterpretation.decideOnElementEndTime(element, currentTime)
				.orElse(Double.POSITIVE_INFINITY);
		return boardingDeadlineIndex.ceilToQsimTickTime(
				Math.max(currentTime, interpretedEndTime));
	}

	/** Shared timing receipt for the initial canonicalizer and Plan-A strategies. */
	static record TripArrivalEvaluation(
			double terminalArrivalTime,
			int horizonViolationTrips,
			int boardingPlannedServiceMissedLegs,
			int boardingNextServiceRescueLegs,
			int boardingUncatchableLegs,
			int boardingScheduleMissingLegs,
			int boardingScheduleAmbiguousLegs) {
		boolean hasHorizonViolation() {
			return horizonViolationTrips > 0;
		}
	}

	private static final class WholeTourMutationAndRouteModule extends AbstractMultithreadedModule {
		private final String[] configuredModes;
		private final boolean ignoreCarAvailability;
		private final boolean switchOnlyWithinConfiguredModes;
		private final ActivityFacilities facilities;
		private final Provider<TripRouter> tripRouterProvider;
		private final TimeInterpretation timeInterpretation;
		private final double qsimEndTime;
		private final double qsimTimeStep;
		private final TransitSchedule transitSchedule;
		private PlanATransitBoardingDeadlineIndex boardingDeadlineIndex;
		private final double timeMutationFeasibilityMargin;
		private final PlanAFeasibilityAudit feasibilityAudit;

		private WholeTourMutationAndRouteModule(
				GlobalConfigGroup globalConfig,
				ChangeModeConfigGroup changeModeConfig,
				ActivityFacilities facilities,
				Provider<TripRouter> tripRouterProvider,
				TimeInterpretation timeInterpretation,
				double qsimEndTime,
				double qsimTimeStep,
				TransitSchedule transitSchedule,
				double timeMutationFeasibilityMargin,
				PlanAFeasibilityAudit feasibilityAudit) {
			super(globalConfig);
			this.configuredModes = changeModeConfig.getModes().clone();
			this.ignoreCarAvailability = changeModeConfig.getIgnoreCarAvailability();
			this.switchOnlyWithinConfiguredModes = changeModeConfig.getBehavior()
					.equals(ChangeModeConfigGroup.Behavior.fromSpecifiedModesToSpecifiedModes);
			this.facilities = facilities;
			this.tripRouterProvider = tripRouterProvider;
			this.timeInterpretation = timeInterpretation;
			this.qsimEndTime = qsimEndTime;
			this.qsimTimeStep = qsimTimeStep;
			this.transitSchedule = transitSchedule;
			this.timeMutationFeasibilityMargin = timeMutationFeasibilityMargin;
			this.feasibilityAudit = feasibilityAudit;
		}

		@Override
		protected void beforePrepareReplanningHook(ReplanningContext replanningContext) {
			/*
			 * One immutable snapshot is built for this entire multithreaded
			 * replanning phase; no per-person schedule scan is performed.
			 */
			boardingDeadlineIndex = PlanATransitBoardingDeadlineIndex.build(
					transitSchedule,
					qsimTimeStep,
					timeInterpretation.getSimulationStartTime());
		}

		@Override
		public PlanAlgorithm getPlanAlgoInstance() {
			if (boardingDeadlineIndex == null) {
				throw new IllegalStateException(
						"Plan A mode-choice timetable index was not refreshed before replanning");
			}
			return new WholeTourMutationAndRouteAlgorithm(
					configuredModes,
					ignoreCarAvailability,
					switchOnlyWithinConfiguredModes,
					new PlanRouter(tripRouterProvider.get(), facilities, timeInterpretation),
					timeInterpretation,
					qsimEndTime,
					qsimTimeStep,
					boardingDeadlineIndex,
					timeMutationFeasibilityMargin,
					MatsimRandom.getLocalInstance(),
					feasibilityAudit);
		}
	}

	private static final class WholeTourMutationAndRouteAlgorithm implements PlanAlgorithm {
		private final String[] configuredModes;
		private final boolean ignoreCarAvailability;
		private final boolean switchOnlyWithinConfiguredModes;
		private final PlanRouter planRouter;
		private final TimeInterpretation timeInterpretation;
		private final double qsimEndTime;
		private final double qsimTimeStep;
		private final PlanATransitBoardingDeadlineIndex boardingDeadlineIndex;
		private final double timeMutationFeasibilityMargin;
		private final Random random;
		private final TripsToLegsAlgorithm tripsToLegs;
		private final PlanAFeasibilityAudit feasibilityAudit;

		private WholeTourMutationAndRouteAlgorithm(
				String[] configuredModes,
				boolean ignoreCarAvailability,
				boolean switchOnlyWithinConfiguredModes,
				PlanRouter planRouter,
				TimeInterpretation timeInterpretation,
				double qsimEndTime,
				double qsimTimeStep,
				PlanATransitBoardingDeadlineIndex boardingDeadlineIndex,
				double timeMutationFeasibilityMargin,
				Random random,
				PlanAFeasibilityAudit feasibilityAudit) {
			this.configuredModes = configuredModes;
			this.ignoreCarAvailability = ignoreCarAvailability;
			this.switchOnlyWithinConfiguredModes = switchOnlyWithinConfiguredModes;
			this.planRouter = planRouter;
			this.timeInterpretation = timeInterpretation;
			this.qsimEndTime = qsimEndTime;
			this.qsimTimeStep = qsimTimeStep;
			this.boardingDeadlineIndex = boardingDeadlineIndex;
			this.timeMutationFeasibilityMargin = timeMutationFeasibilityMargin;
			this.random = random;
			this.tripsToLegs = new TripsToLegsAlgorithm(TripStructureUtils.getRoutingModeIdentifier());
			this.feasibilityAudit = feasibilityAudit;
		}

		@Override
		public void run(Plan plan) {
			String originalMode = firstLegMode(plan);
			if (originalMode == null) {
				return;
			}

			List<String> candidates = candidateModes(plan, originalMode);
			if (candidates.isEmpty()) {
				planRouter.run(plan);
				WholeTourFeasibility feasibility = evaluateWholeTourFeasibility(
						plan, timeInterpretation, qsimEndTime, 0.0,
						qsimTimeStep, boardingDeadlineIndex);
				if (!feasibility.isFeasible()) {
					restorePlanMemoryOrFail(plan, snapshot(plan), "empty candidate set");
				}
				return;
			}

			/*
			 * Test candidates in a random order without replacement. The first feasible
			 * element of a uniformly random permutation is itself uniformly distributed
			 * over the feasible alternatives, while no infeasible route is retained in
			 * the plan memory.
			 */
			Plan beforeModeChoice = PopulationUtils.createPlan(plan.getPerson());
			PopulationUtils.copyFromTo(plan, beforeModeChoice, true);
			List<String> remainingCandidates = new ArrayList<>(candidates);
			while (!remainingCandidates.isEmpty()) {
				String newMode = remainingCandidates.remove(random.nextInt(remainingCandidates.size()));
				PopulationUtils.copyFromTo(beforeModeChoice, plan, true);
				tripsToLegs.run(plan);
				applyWholeTourMode(plan, newMode);
				planRouter.run(plan);

				feasibilityAudit.recordWholeTourModeProposal();
				if (TransportMode.pt.equals(newMode)) {
					feasibilityAudit.recordPtProposal();
				}
				WholeTourFeasibility feasibility = evaluateWholeTourFeasibility(
						plan, timeInterpretation, qsimEndTime, timeMutationFeasibilityMargin,
						qsimTimeStep, boardingDeadlineIndex);
				feasibilityAudit.recordModeBoardingDiagnostics(feasibility);
				if (feasibility.isFeasible()) {
					return;
				}

				if (TransportMode.walk.equals(newMode)) {
					feasibilityAudit.recordRejectedWalkProposal();
				}
				if (feasibility.hasPtFallbackOnlyWalk()) {
					feasibilityAudit.recordRejectedPtFallbackOnlyWalkProposal();
				}
				if (feasibility.reachesQsimEndBoundary()) {
					feasibilityAudit.recordRejectedTourEndTimeProposal();
				}
				if (feasibility.hasBoardingUncatchable()) {
					feasibilityAudit.recordRejectedBoardingUncatchableProposal();
				}
				if (feasibility.hasBoardingScheduleMissing()) {
					feasibilityAudit.recordRejectedBoardingScheduleMissingProposal();
				}
				if (feasibility.hasBoardingScheduleAmbiguity()) {
					feasibilityAudit.recordRejectedBoardingScheduleAmbiguousProposal();
				}
			}

			/*
			 * D0 canonicalisation guarantees that the pre-existing selected plan is
			 * feasible. If every alternative is ruled out at this departure time, retain
			 * that plan rather than keeping the last rejected provisional route.
			 */
			feasibilityAudit.recordNoFeasibleModeAlternative();
			restorePlanMemoryOrFail(plan, beforeModeChoice, "all configured modes rejected");
		}

		private Plan snapshot(Plan plan) {
			Plan snapshot = PopulationUtils.createPlan(plan.getPerson());
			PopulationUtils.copyFromTo(plan, snapshot, true);
			return snapshot;
		}

		private void restorePlanMemoryOrFail(Plan plan, Plan selectedSnapshot, String branch) {
			var recovery = PlanAPlanMemoryFeasibilityFallback.restore(
					plan,
					selectedSnapshot,
					timeInterpretation,
					qsimEndTime,
					qsimTimeStep,
					boardingDeadlineIndex);
			feasibilityAudit.recordPlanMemoryFallback(recovery);
			if (!recovery.recovered()) {
				throw new IllegalStateException(
						"Plan-A whole-tour mode choice found no executable plan-memory fallback for person "
								+ plan.getPerson().getId() + " (" + branch + ") after checking "
								+ recovery.alternativeCandidatesChecked() + " alternative plan(s)");
			}
		}

		private List<String> candidateModes(Plan plan, String currentMode) {
			if (switchOnlyWithinConfiguredModes && !Arrays.asList(configuredModes).contains(currentMode)) {
				return List.of();
			}

			boolean forbidCar = !ignoreCarAvailability && "never".equals(PersonUtils.getCarAvail(plan.getPerson()));
			List<String> candidates = new ArrayList<>();
			for (String candidate : configuredModes) {
				if (!candidate.equals(currentMode) && !(forbidCar && TransportMode.car.equals(candidate))) {
					candidates.add(candidate);
				}
			}
			return candidates;
		}

		private static String firstLegMode(Plan plan) {
			for (PlanElement element : plan.getPlanElements()) {
				if (element instanceof Leg leg) {
					return leg.getMode();
				}
			}
			return null;
		}
	}

}
