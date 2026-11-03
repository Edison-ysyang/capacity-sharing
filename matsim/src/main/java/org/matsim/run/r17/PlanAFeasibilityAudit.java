package org.matsim.run.r17;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.timing.TimeInterpretation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Per-iteration integrity audit for the Plan-A operational-feasibility guard. */
@Singleton
public final class PlanAFeasibilityAudit implements BeforeMobsimListener, IterationEndsListener {
	static final String PRE_MOBSIM_AUDIT_FILE =
			"plan_a_pre_mobsim_selected_plan_feasibility_audit.csv";
	static final String PRE_MOBSIM_UNSAFE_DETAILS_FILE =
			"plan_a_pre_mobsim_unsafe_selected_plan_details.csv";

	private final OutputDirectoryHierarchy controllerIO;
	private final Scenario scenario;
	private final FreightOrderRegistry freightOrderRegistry;
	private final TimeInterpretation timeInterpretation;
	private final double qsimEndTime;
	private final double qsimTimeStep;

	private final AtomicLong wholeTourModeProposals = new AtomicLong();
	private final AtomicLong rejectedWalkProposals = new AtomicLong();
	private final AtomicLong ptProposals = new AtomicLong();
	private final AtomicLong rejectedPtFallbackProposals = new AtomicLong();
	private final AtomicLong rejectedHorizonProposals = new AtomicLong();
	private final AtomicLong modeBoardingPlannedServiceMissedLegs = new AtomicLong();
	private final AtomicLong modeBoardingNextServiceRescueLegs = new AtomicLong();
	private final AtomicLong rejectedBoardingUncatchableProposals = new AtomicLong();
	private final AtomicLong rejectedBoardingScheduleMissingProposals = new AtomicLong();
	private final AtomicLong rejectedBoardingScheduleAmbiguousProposals = new AtomicLong();
	private final AtomicLong noFeasibleModeAlternatives = new AtomicLong();

	/* Legacy narrow all-walk counters are retained for receipt continuity. */
	private final AtomicLong walkTimeMutationProposals = new AtomicLong();
	private final AtomicLong revertedWalkTimeMutations = new AtomicLong();
	private final AtomicLong timeMutationProposals = new AtomicLong();
	private final AtomicLong timeMutationBoardingPlannedServiceMissedLegs = new AtomicLong();
	private final AtomicLong timeMutationBoardingNextServiceRescueLegs = new AtomicLong();
	private final AtomicLong revertedInfeasibleTimeMutations = new AtomicLong();
	private final AtomicLong revertedBoardingUncatchableTimeMutations = new AtomicLong();
	private final AtomicLong revertedBoardingScheduleMissingTimeMutations = new AtomicLong();
	private final AtomicLong revertedBoardingScheduleAmbiguousTimeMutations = new AtomicLong();
	private final AtomicLong planMemoryFallbackAttempts = new AtomicLong();
	private final AtomicLong planMemorySelectedSnapshotRestores = new AtomicLong();
	private final AtomicLong planMemoryAlternativeRestores = new AtomicLong();
	private final AtomicLong planMemoryFallbackExhausted = new AtomicLong();
	private final AtomicLong planMemoryAlternativeCandidatesChecked = new AtomicLong();

	private final AtomicLong beforeMobsimSelectedPlansChecked = new AtomicLong();
	private final AtomicLong beforeMobsimUnsafeSelectedPlans = new AtomicLong();
	private final AtomicLong beforeMobsimBoardingPlannedServiceMissedLegs = new AtomicLong();
	private final AtomicLong beforeMobsimBoardingNextServiceRescueLegs = new AtomicLong();
	private final AtomicLong beforeMobsimBoardingUncatchableLegs = new AtomicLong();
	private final AtomicLong beforeMobsimBoardingScheduleMissingLegs = new AtomicLong();
	private final AtomicLong beforeMobsimBoardingScheduleAmbiguousLegs = new AtomicLong();

	@Inject
	public PlanAFeasibilityAudit(
			Config config,
			Scenario scenario,
			FreightOrderRegistry freightOrderRegistry,
			TimeInterpretation timeInterpretation,
			OutputDirectoryHierarchy controllerIO) {
		this.controllerIO = controllerIO;
		this.scenario = scenario;
		this.freightOrderRegistry = freightOrderRegistry;
		this.timeInterpretation = timeInterpretation;
		this.qsimEndTime = config.qsim().getEndTime().orElseThrow(
				() -> new IllegalStateException("Plan A feasibility audit requires qsim.endTime"));
		this.qsimTimeStep = PlanAWholeTourFeasibleModeChoice.requirePositiveQsimTimeStep(
				config.qsim().getTimeStepSize());
	}

	void recordWholeTourModeProposal() {
		wholeTourModeProposals.incrementAndGet();
	}

	void recordRejectedWalkProposal() {
		rejectedWalkProposals.incrementAndGet();
	}

	void recordPtProposal() {
		ptProposals.incrementAndGet();
	}

	void recordRejectedPtFallbackOnlyWalkProposal() {
		rejectedPtFallbackProposals.incrementAndGet();
	}

	void recordRejectedTourEndTimeProposal() {
		rejectedHorizonProposals.incrementAndGet();
	}

	void recordModeBoardingDiagnostics(
			PlanAWholeTourFeasibleModeChoice.WholeTourFeasibility feasibility) {
		modeBoardingPlannedServiceMissedLegs.addAndGet(
				feasibility.boardingPlannedServiceMissedLegs());
		modeBoardingNextServiceRescueLegs.addAndGet(
				feasibility.boardingNextServiceRescueLegs());
	}

	void recordRejectedBoardingUncatchableProposal() {
		rejectedBoardingUncatchableProposals.incrementAndGet();
	}

	void recordRejectedBoardingScheduleMissingProposal() {
		rejectedBoardingScheduleMissingProposals.incrementAndGet();
	}

	void recordRejectedBoardingScheduleAmbiguousProposal() {
		rejectedBoardingScheduleAmbiguousProposals.incrementAndGet();
	}

	void recordNoFeasibleModeAlternative() {
		noFeasibleModeAlternatives.incrementAndGet();
	}

	void recordWalkTimeMutationProposal() {
		walkTimeMutationProposals.incrementAndGet();
	}

	void recordRevertedWalkTimeMutation() {
		revertedWalkTimeMutations.incrementAndGet();
	}

	void recordTimeMutationProposal() {
		timeMutationProposals.incrementAndGet();
	}

	void recordTimeMutationBoardingDiagnostics(
			PlanAWholeTourFeasibleModeChoice.WholeTourFeasibility feasibility) {
		timeMutationBoardingPlannedServiceMissedLegs.addAndGet(
				feasibility.boardingPlannedServiceMissedLegs());
		timeMutationBoardingNextServiceRescueLegs.addAndGet(
				feasibility.boardingNextServiceRescueLegs());
	}

	void recordRevertedInfeasibleTimeMutation() {
		revertedInfeasibleTimeMutations.incrementAndGet();
	}

	void recordRevertedBoardingUncatchableTimeMutation() {
		revertedBoardingUncatchableTimeMutations.incrementAndGet();
	}

	void recordRevertedBoardingScheduleMissingTimeMutation() {
		revertedBoardingScheduleMissingTimeMutations.incrementAndGet();
	}

	void recordRevertedBoardingScheduleAmbiguousTimeMutation() {
		revertedBoardingScheduleAmbiguousTimeMutations.incrementAndGet();
	}

	void recordPlanMemoryFallback(PlanAPlanMemoryFeasibilityFallback.Recovery recovery) {
		planMemoryFallbackAttempts.incrementAndGet();
		planMemoryAlternativeCandidatesChecked.addAndGet(recovery.alternativeCandidatesChecked());
		switch (recovery.source()) {
			case SELECTED_SNAPSHOT -> planMemorySelectedSnapshotRestores.incrementAndGet();
			case ALTERNATIVE_MEMORY -> planMemoryAlternativeRestores.incrementAndGet();
			case NONE -> planMemoryFallbackExhausted.incrementAndGet();
		}
	}

	/**
	 * Builds one immutable snapshot from the currently installed timetable and
	 * verifies every selected passenger plan immediately before QSim.
	 */
	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		PlanATransitBoardingDeadlineIndex timetableIndex =
				PlanATransitBoardingDeadlineIndex.build(
						scenario.getTransitSchedule(),
						qsimTimeStep,
						timeInterpretation.getSimulationStartTime());
		long checked = 0L;
		long unsafe = 0L;
		long plannedMissed = 0L;
		long rescued = 0L;
		long uncatchable = 0L;
		long missing = 0L;
		long ambiguous = 0L;
		List<UnsafeSelectedPlanDetail> unsafeDetails = new ArrayList<>();
		for (Person person : scenario.getPopulation().getPersons().values()) {
			if (freightOrderRegistry.isFreightPerson(person.getId())) {
				continue;
			}
			checked++;
			if (person.getSelectedPlan() == null) {
				unsafe++;
				unsafeDetails.add(UnsafeSelectedPlanDetail.missingSelectedPlan(person.getId().toString()));
				continue;
			}
			try {
				var feasibility = PlanAWholeTourFeasibleModeChoice.evaluateWholeTourFeasibility(
						person.getSelectedPlan(),
						timeInterpretation,
						qsimEndTime,
						0.0,
						qsimTimeStep,
						timetableIndex);
				plannedMissed += feasibility.boardingPlannedServiceMissedLegs();
				rescued += feasibility.boardingNextServiceRescueLegs();
				uncatchable += feasibility.boardingUncatchableLegs();
				missing += feasibility.boardingScheduleMissingLegs();
				ambiguous += feasibility.boardingScheduleAmbiguousLegs();
				if (!feasibility.isFeasible()) {
					unsafe++;
					unsafeDetails.add(UnsafeSelectedPlanDetail.fromFeasibility(
							person.getId().toString(), feasibility));
				}
			} catch (RuntimeException error) {
				unsafe++;
				unsafeDetails.add(UnsafeSelectedPlanDetail.fromException(person.getId().toString(), error));
			}
		}
		beforeMobsimSelectedPlansChecked.addAndGet(checked);
		beforeMobsimUnsafeSelectedPlans.addAndGet(unsafe);
		beforeMobsimBoardingPlannedServiceMissedLegs.addAndGet(plannedMissed);
		beforeMobsimBoardingNextServiceRescueLegs.addAndGet(rescued);
		beforeMobsimBoardingUncatchableLegs.addAndGet(uncatchable);
		beforeMobsimBoardingScheduleMissingLegs.addAndGet(missing);
		beforeMobsimBoardingScheduleAmbiguousLegs.addAndGet(ambiguous);
		writePreMobsimReceipt(
				event.getIteration(),
				checked,
				unsafe,
				plannedMissed,
				rescued,
				uncatchable,
				missing,
				ambiguous);
		writeUnsafeSelectedPlanDetails(event.getIteration(), unsafeDetails);
		if (unsafe > 0L) {
			throw new IllegalStateException(
					"Plan A refused to start QSim iteration " + event.getIteration()
							+ " because " + unsafe + " of " + checked
							+ " selected passenger plan(s) failed the no-reroute route-feasibility invariant"
							+ " (boardingUncatchableLegs=" + uncatchable
							+ ", boardingScheduleMissingLegs=" + missing
							+ ", boardingScheduleAmbiguousLegs=" + ambiguous + "). See "
							+ controllerIO.getOutputFilename(PRE_MOBSIM_AUDIT_FILE));
		}
	}

	/**
	 * Returns every disqualifying branch, rather than only the aggregate unsafe
	 * count. This diagnostic is written before the existing fail-closed throw and
	 * does not normalise, reroute, or otherwise change the selected plan.
	 */
	static String describeInfeasibility(PlanAWholeTourFeasibleModeChoice.WholeTourFeasibility feasibility) {
		List<String> reasons = new ArrayList<>();
		if (feasibility.hasPtFallbackOnlyWalk()) {
			reasons.add("pt_fallback_only_walk");
		}
		if (feasibility.reachesQsimEndBoundary()) {
			reasons.add("qsim_end_boundary");
		}
		if (feasibility.hasBoardingUncatchable()) {
			reasons.add("boarding_uncatchable");
		}
		if (feasibility.hasBoardingScheduleMissing()) {
			reasons.add("boarding_schedule_missing");
		}
		if (feasibility.hasBoardingScheduleAmbiguity()) {
			reasons.add("boarding_schedule_ambiguous");
		}
		return reasons.isEmpty() ? "unknown_infeasible" : String.join("|", reasons);
	}

	private void writePreMobsimReceipt(
			int iteration,
			long checked,
			long unsafe,
			long plannedMissed,
			long rescued,
			long uncatchable,
			long missing,
			long ambiguous) {
		Path path = Path.of(controllerIO.getOutputFilename(PRE_MOBSIM_AUDIT_FILE));
		try {
			boolean writeHeader = !Files.exists(path) || Files.size(path) == 0L;
			try (FileChannel channel = FileChannel.open(
					path,
					StandardOpenOption.CREATE,
					StandardOpenOption.WRITE,
					StandardOpenOption.APPEND)) {
				var writer =
						new java.io.BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8));
				if (writeHeader) {
					writer.write(
							"iteration,selected_plans_checked,unsafe_selected_plans,"
									+ "boarding_planned_service_missed_legs,"
									+ "boarding_next_service_rescue_legs,"
									+ "boarding_uncatchable_legs,boarding_schedule_missing_legs,"
									+ "boarding_schedule_ambiguous_legs,qsim_time_step_seconds\n");
				}
				writer.write(
						iteration + "," + checked + "," + unsafe + "," + plannedMissed + ","
								+ rescued + "," + uncatchable + "," + missing + "," + ambiguous + ","
								+ qsimTimeStep + "\n");
				writer.flush();
				channel.force(true);
			}
		} catch (IOException error) {
			throw new UncheckedIOException(
					"Could not write pre-Mobsim Plan-A feasibility receipt: " + path, error);
		}
	}

	private void writeUnsafeSelectedPlanDetails(int iteration, List<UnsafeSelectedPlanDetail> details) {
		if (details.isEmpty()) {
			return;
		}
		Path path = Path.of(controllerIO.getOutputFilename(PRE_MOBSIM_UNSAFE_DETAILS_FILE));
		try {
			boolean writeHeader = !Files.exists(path) || Files.size(path) == 0L;
			try (FileChannel channel = FileChannel.open(
					path,
					StandardOpenOption.CREATE,
					StandardOpenOption.WRITE,
					StandardOpenOption.APPEND)) {
				var writer = new java.io.BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8));
				if (writeHeader) {
					writer.write(
							"iteration,person_id,failure_reasons,terminal_arrival_time_seconds,"
									+ "pt_fallback_only_walk,qsim_end_boundary,"
									+ "boarding_uncatchable_legs,boarding_schedule_missing_legs,"
									+ "boarding_schedule_ambiguous_legs,exception_class,exception_message\n");
				}
				for (UnsafeSelectedPlanDetail detail : details) {
					writer.write(iteration + "," + csv(detail.personId) + "," + csv(detail.failureReasons) + ","
							+ detail.terminalArrivalTime + "," + detail.ptFallbackOnlyWalk + ","
							+ detail.qsimEndBoundary + "," + detail.boardingUncatchableLegs + ","
							+ detail.boardingScheduleMissingLegs + ","
							+ detail.boardingScheduleAmbiguousLegs + "," + csv(detail.exceptionClass) + ","
							+ csv(detail.exceptionMessage) + "\n");
				}
				writer.flush();
				channel.force(true);
			}
		} catch (IOException error) {
			throw new UncheckedIOException(
					"Could not write unsafe selected-plan Plan-A feasibility details: " + path, error);
		}
	}

	private static String csv(String value) {
		return '"' + value.replace("\"", "\"\"").replace("\n", "\\n").replace("\r", "\\r") + '"';
	}

	private static final class UnsafeSelectedPlanDetail {
		private final String personId;
		private final String failureReasons;
		private final double terminalArrivalTime;
		private final boolean ptFallbackOnlyWalk;
		private final boolean qsimEndBoundary;
		private final int boardingUncatchableLegs;
		private final int boardingScheduleMissingLegs;
		private final int boardingScheduleAmbiguousLegs;
		private final String exceptionClass;
		private final String exceptionMessage;

		private UnsafeSelectedPlanDetail(
				String personId,
				String failureReasons,
				double terminalArrivalTime,
				boolean ptFallbackOnlyWalk,
				boolean qsimEndBoundary,
				int boardingUncatchableLegs,
				int boardingScheduleMissingLegs,
				int boardingScheduleAmbiguousLegs,
				String exceptionClass,
				String exceptionMessage) {
			this.personId = personId;
			this.failureReasons = failureReasons;
			this.terminalArrivalTime = terminalArrivalTime;
			this.ptFallbackOnlyWalk = ptFallbackOnlyWalk;
			this.qsimEndBoundary = qsimEndBoundary;
			this.boardingUncatchableLegs = boardingUncatchableLegs;
			this.boardingScheduleMissingLegs = boardingScheduleMissingLegs;
			this.boardingScheduleAmbiguousLegs = boardingScheduleAmbiguousLegs;
			this.exceptionClass = exceptionClass;
			this.exceptionMessage = exceptionMessage;
		}

		private static UnsafeSelectedPlanDetail missingSelectedPlan(String personId) {
			return new UnsafeSelectedPlanDetail(personId, "selected_plan_missing", Double.NaN, false, false,
					0, 0, 0, "", "");
		}

		private static UnsafeSelectedPlanDetail fromFeasibility(
				String personId, PlanAWholeTourFeasibleModeChoice.WholeTourFeasibility feasibility) {
			return new UnsafeSelectedPlanDetail(
					personId,
					describeInfeasibility(feasibility),
					feasibility.terminalArrivalTime(),
					feasibility.hasPtFallbackOnlyWalk(),
					feasibility.reachesQsimEndBoundary(),
					feasibility.boardingUncatchableLegs(),
					feasibility.boardingScheduleMissingLegs(),
					feasibility.boardingScheduleAmbiguousLegs(),
					"",
					"");
		}

		private static UnsafeSelectedPlanDetail fromException(String personId, RuntimeException error) {
			return new UnsafeSelectedPlanDetail(personId, "evaluation_exception", Double.NaN, false, false,
					0, 0, 0, error.getClass().getName(), String.valueOf(error.getMessage()));
		}
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		Path path =
				Path.of(controllerIO.getOutputFilename("plan_a_whole_tour_feasibility_audit.csv"));
		try {
			boolean writeHeader = !Files.exists(path) || Files.size(path) == 0L;
			try (var writer = Files.newBufferedWriter(
					path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				if (writeHeader) {
					writer.write(
							"iteration,whole_tour_mode_proposals,rejected_walk_proposals,"
									+ "walk_time_mutation_proposals,reverted_walk_time_mutations,"
									+ "pt_proposals,rejected_pt_fallback_proposals,rejected_horizon_proposals,"
									+ "mode_boarding_planned_service_missed_legs,"
									+ "mode_boarding_next_service_rescue_legs,"
									+ "rejected_boarding_uncatchable_proposals,"
									+ "rejected_boarding_schedule_missing_proposals,"
									+ "rejected_boarding_schedule_ambiguous_proposals,"
									+ "no_feasible_mode_alternatives,time_mutation_proposals,"
									+ "time_mutation_boarding_planned_service_missed_legs,"
									+ "time_mutation_boarding_next_service_rescue_legs,"
									+ "reverted_infeasible_time_mutations,"
									+ "reverted_boarding_uncatchable_time_mutations,"
									+ "reverted_boarding_schedule_missing_time_mutations,"
									+ "reverted_boarding_schedule_ambiguous_time_mutations,"
									+ "plan_memory_fallback_attempts,"
									+ "plan_memory_selected_snapshot_restores,"
									+ "plan_memory_alternative_restores,"
									+ "plan_memory_fallback_exhausted,"
									+ "plan_memory_alternative_candidates_checked,"
									+ "before_mobsim_selected_plans_checked,"
									+ "before_mobsim_unsafe_selected_plans,"
									+ "before_mobsim_boarding_planned_service_missed_legs,"
									+ "before_mobsim_boarding_next_service_rescue_legs,"
									+ "before_mobsim_boarding_uncatchable_legs,"
									+ "before_mobsim_boarding_schedule_missing_legs,"
									+ "before_mobsim_boarding_schedule_ambiguous_legs\n");
				}
				writer.write(
						event.getIteration() + "," + take(wholeTourModeProposals) + ","
								+ take(rejectedWalkProposals) + "," + take(walkTimeMutationProposals) + ","
								+ take(revertedWalkTimeMutations) + "," + take(ptProposals) + ","
								+ take(rejectedPtFallbackProposals) + "," + take(rejectedHorizonProposals) + ","
								+ take(modeBoardingPlannedServiceMissedLegs) + ","
								+ take(modeBoardingNextServiceRescueLegs) + ","
								+ take(rejectedBoardingUncatchableProposals) + ","
								+ take(rejectedBoardingScheduleMissingProposals) + ","
								+ take(rejectedBoardingScheduleAmbiguousProposals) + ","
								+ take(noFeasibleModeAlternatives) + "," + take(timeMutationProposals) + ","
								+ take(timeMutationBoardingPlannedServiceMissedLegs) + ","
								+ take(timeMutationBoardingNextServiceRescueLegs) + ","
								+ take(revertedInfeasibleTimeMutations) + ","
								+ take(revertedBoardingUncatchableTimeMutations) + ","
								+ take(revertedBoardingScheduleMissingTimeMutations) + ","
								+ take(revertedBoardingScheduleAmbiguousTimeMutations) + ","
								+ take(planMemoryFallbackAttempts) + ","
								+ take(planMemorySelectedSnapshotRestores) + ","
								+ take(planMemoryAlternativeRestores) + ","
								+ take(planMemoryFallbackExhausted) + ","
								+ take(planMemoryAlternativeCandidatesChecked) + ","
								+ take(beforeMobsimSelectedPlansChecked) + ","
								+ take(beforeMobsimUnsafeSelectedPlans) + ","
								+ take(beforeMobsimBoardingPlannedServiceMissedLegs) + ","
								+ take(beforeMobsimBoardingNextServiceRescueLegs) + ","
								+ take(beforeMobsimBoardingUncatchableLegs) + ","
								+ take(beforeMobsimBoardingScheduleMissingLegs) + ","
								+ take(beforeMobsimBoardingScheduleAmbiguousLegs) + "\n");
			}
		} catch (IOException error) {
			throw new UncheckedIOException(
					"Could not write Plan-A whole-tour feasibility audit: " + path, error);
		}
	}

	private static long take(AtomicLong counter) {
		return counter.getAndSet(0L);
	}
}
