package org.matsim.run.r17;

import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.timing.TimeInterpretation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Restores an executable historical plan after an innovative proposal is rejected.
 *
 * <p>MATSim selects one of at most {@code maxAgentPlanMemorySize} historical plans,
 * copies it, and applies an innovation to that copy.  Under a live timetable
 * intervention, rerouting the copied historical plan can itself return a
 * fallback-only PT walk.  The rejected proposal must therefore be replaced by an
 * already routed, currently executable memory plan rather than rerouting the
 * historical route a second time.</p>
 *
 * <p>The originally sampled snapshot is preferred.  If it is not executable under
 * the live timetable, the remaining memory plans are checked without mutation in
 * descending score order (stable memory order breaks ties).  Only the working copy
 * is changed; the historical plan memory is never pruned or rewritten here.</p>
 */
final class PlanAPlanMemoryFeasibilityFallback {
	enum Source {
		SELECTED_SNAPSHOT,
		ALTERNATIVE_MEMORY,
		NONE
	}

	record Recovery(
			Source source,
			int alternativeCandidatesChecked,
			PlanAWholeTourFeasibleModeChoice.WholeTourFeasibility snapshotFeasibility) {
		boolean recovered() {
			return source != Source.NONE;
		}
	}

	private PlanAPlanMemoryFeasibilityFallback() {
	}

	static Recovery restore(
			Plan workingPlan,
			Plan selectedSnapshot,
			TimeInterpretation timeInterpretation,
			double qsimEndTime,
			double qsimTimeStep,
			PlanATransitBoardingDeadlineIndex boardingDeadlineIndex) {
		var snapshotFeasibility = evaluate(
				selectedSnapshot,
				timeInterpretation,
				qsimEndTime,
				qsimTimeStep,
				boardingDeadlineIndex);
		if (snapshotFeasibility.isFeasible()) {
			PopulationUtils.copyFromTo(selectedSnapshot, workingPlan, true);
			return new Recovery(Source.SELECTED_SNAPSHOT, 0, snapshotFeasibility);
		}

		List<Plan> alternatives = new ArrayList<>(workingPlan.getPerson().getPlans());
		alternatives.removeIf(candidate -> candidate == workingPlan);
		alternatives.sort(Comparator.comparingDouble(PlanAPlanMemoryFeasibilityFallback::scoreForFallback)
				.reversed());

		int checked = 0;
		for (Plan alternative : alternatives) {
			checked++;
			var feasibility = evaluate(
					alternative,
					timeInterpretation,
					qsimEndTime,
					qsimTimeStep,
					boardingDeadlineIndex);
			if (feasibility.isFeasible()) {
				PopulationUtils.copyFromTo(alternative, workingPlan, true);
				return new Recovery(Source.ALTERNATIVE_MEMORY, checked, snapshotFeasibility);
			}
		}

		PopulationUtils.copyFromTo(selectedSnapshot, workingPlan, true);
		return new Recovery(Source.NONE, checked, snapshotFeasibility);
	}

	private static PlanAWholeTourFeasibleModeChoice.WholeTourFeasibility evaluate(
			Plan plan,
			TimeInterpretation timeInterpretation,
			double qsimEndTime,
			double qsimTimeStep,
			PlanATransitBoardingDeadlineIndex boardingDeadlineIndex) {
		return PlanAWalkFeasibleTimeAllocationMutatorReRoute.evaluatePostMutationFeasibility(
				plan,
				timeInterpretation,
				qsimEndTime,
				qsimTimeStep,
				boardingDeadlineIndex);
	}

	private static double scoreForFallback(Plan plan) {
		Double score = plan.getScore();
		return score == null || !Double.isFinite(score) ? Double.NEGATIVE_INFINITY : score;
	}
}
