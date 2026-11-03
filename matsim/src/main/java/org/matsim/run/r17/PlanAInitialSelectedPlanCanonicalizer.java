package org.matsim.run.r17;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.algorithms.TripsToLegsAlgorithm;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.PlanRouter;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.pt.routes.TransitPassengerRoute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Canonicalizes the selected initial passenger plans before iteration 0.
 *
 * <p>Plan A retains the supplied transit timetable. It therefore does not treat a
 * requested {@code pt} trip that the actual router turns into direct walk as an
 * available PT alternative. The listener runs exactly once immediately before
 * iteration-0 QSim, after {@code PrepareForSim} has created person vehicles and
 * initial network routes and after the co-transport phase has installed the
 * iteration-0 timetable. It then checks all selected passenger tours against the
 * actual router, QSim horizon, and QSim-tick next-service catchability invariant. An
 * infeasible selected plan is replaced
 * deterministically by the first feasible whole-tour alternative in the configured
 * {@code changeMode.modes} order. The raw population file is never overwritten.</p>
 *
 * <p>This is a feasibility canonicalization, not a behavioural choice model:
 * the original selected plan is always retained when it is feasible. If it is
 * not feasible, the configured mode order supplies a reproducible fallback
 * policy. Freight persons are deliberately excluded because their {@code freight_pt}
 * routing is governed by the co-transport extension rather than passenger
 * {@code changeMode} settings.</p>
 *
 * <p>Two pre-Mobsim output artifacts are written: an authoritative JSON summary
 * and a CSV containing every normalized person. A missing feasible alternative
 * is fatal, so an unsafe plan cannot enter iteration 0 silently.</p>
 */
@Singleton
public final class PlanAInitialSelectedPlanCanonicalizer implements BeforeMobsimListener {
	static final String JSON_FILE = "plan_a_initial_route_feasibility_audit.json";
	static final String CSV_FILE = "plan_a_initial_route_feasibility_audit.csv";
	private static final String SCHEMA =
			"plan_a_initial_route_feasibility_audit_s1r5_boarding_catchability_v1";
	private static final String REVISION =
			"s1r5_option_a_original_timetable_qsim_next_service_catchability_guard";
	private static final String EXECUTION_PHASE =
			"BeforeMobsim_iteration_0_after_PrepareForSim_before_PrepareForMobsim_and_QSim";
	private static final Logger LOG = LogManager.getLogger(PlanAInitialSelectedPlanCanonicalizer.class);

	private final Scenario scenario;
	private final ActivityFacilities facilities;
	private final Provider<TripRouter> tripRouterProvider;
	private final TimeInterpretation timeInterpretation;
	private final double qsimEndTime;
	private final double qsimTimeStep;
	private final double feasibilityMargin;
	private final List<String> configuredModes;
	private final boolean ignoreCarAvailability;
	private final int firstIteration;
	private final FreightOrderRegistry freightOrderRegistry;
	private final OutputDirectoryHierarchy controllerIO;

	@Inject
	public PlanAInitialSelectedPlanCanonicalizer(
			Config config,
			Scenario scenario,
			ActivityFacilities facilities,
			Provider<TripRouter> tripRouterProvider,
			TimeInterpretation timeInterpretation,
			FreightOrderRegistry freightOrderRegistry,
			OutputDirectoryHierarchy controllerIO) {
		this.scenario = scenario;
		this.facilities = facilities;
		this.tripRouterProvider = tripRouterProvider;
		this.timeInterpretation = timeInterpretation;
		this.qsimEndTime = config.qsim().getEndTime().orElseThrow(
				() -> new IllegalStateException("Plan A initial canonicalization requires qsim.endTime"));
		this.qsimTimeStep = PlanAWholeTourFeasibleModeChoice.requirePositiveQsimTimeStep(
				config.qsim().getTimeStepSize());
		this.feasibilityMargin = maximumPositiveTimeMutation(
				config.timeAllocationMutator().getMutationRange(),
				config.timeAllocationMutator().getMutationRangeStep());
		this.configuredModes = List.copyOf(Arrays.asList(config.changeMode().getModes()));
		if (configuredModes.isEmpty()) {
			throw new IllegalStateException("Plan A initial canonicalization requires non-empty changeMode.modes");
		}
		this.ignoreCarAvailability = config.changeMode().getIgnoreCarAvailability();
		this.firstIteration = config.controller().getFirstIteration();
		this.freightOrderRegistry = freightOrderRegistry;
		this.controllerIO = controllerIO;
	}

	/**
	 * This listener must precede the Plan-A no-reroute audit (default priority 0),
	 * but it deliberately follows controller-wide {@code PrepareForSim}. That phase
	 * creates person vehicles and initial network routes, which are required for
	 * executable car/ride/bike/bus feasibility checks. QSim has not started yet.
	 */
	@Override
	public double priority() {
		return 1000.0;
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		if (event.getIteration() != firstIteration) {
			return;
		}
		CanonicalizationSummary summary = canonicalizeSelectedPassengerPlans();
		writeAudit(summary);
		if (summary.noFeasibleAlternative > 0 || summary.malformedOrUnroutable > 0) {
			throw new IllegalStateException("Plan A initial selected-plan canonicalization found "
					+ summary.noFeasibleAlternative + " person(s) without a feasible alternative and "
					+ summary.malformedOrUnroutable + " malformed/unroutable selected plan(s). "
					+ "See " + controllerIO.getOutputFilename(JSON_FILE));
		}
		LOG.info("Plan A initial route canonicalization completed: selectedPersonsChecked={}, selectedTripsChecked={}, "
				+ "normalizedPersons={}, ptFallbackBefore={}, horizonViolationBefore={}, "
				+ "boardingPlannedServiceMissedLegsBefore={}, boardingNextServiceRescueLegsBefore={}, "
				+ "boardingUncatchableLegsBefore={}, boardingScheduleMissingLegsBefore={}, "
				+ "boardingScheduleAmbiguousLegsBefore={}",
				summary.selectedPersonsChecked, summary.selectedTripsChecked, summary.normalizedPersons,
				summary.ptFallbackBefore, summary.horizonViolationBefore,
				summary.boardingPlannedServiceMissedBefore, summary.boardingNextServiceRescueBefore,
				summary.boardingUncatchableBefore,
				summary.boardingScheduleMissingBefore, summary.boardingScheduleAmbiguousBefore);
	}

	private CanonicalizationSummary canonicalizeSelectedPassengerPlans() {
		CanonicalizationSummary summary = new CanonicalizationSummary(
				configuredModes, feasibilityMargin, qsimEndTime, qsimTimeStep);
		PlanRouter router = new PlanRouter(tripRouterProvider.get(), facilities, timeInterpretation);
		TripsToLegsAlgorithm tripsToLegs = new TripsToLegsAlgorithm(TripStructureUtils.getRoutingModeIdentifier());
		PlanATransitBoardingDeadlineIndex boardingDeadlineIndex =
				PlanATransitBoardingDeadlineIndex.build(
						scenario.getTransitSchedule(),
						qsimTimeStep,
						timeInterpretation.getSimulationStartTime());

		for (Person person : scenario.getPopulation().getPersons().values()) {
			if (freightOrderRegistry.isFreightPerson(person.getId())) {
				summary.skippedFreightPersons++;
				continue;
			}
			Plan selectedPlan = person.getSelectedPlan();
			if (selectedPlan == null) {
				summary.malformedOrUnroutable++;
				summary.records.add(PlanAuditRecord.malformed(person.getId().toString(), "missing_selected_plan"));
				continue;
			}

			summary.selectedPersonsChecked++;
			int initialTripCount = TripStructureUtils.getTrips(selectedPlan).size();
			summary.selectedTripsChecked += initialTripCount;
			String originalMode = modeSignature(selectedPlan);
			Map<String, Long> originalModeCounts = requestedModeCounts(selectedPlan);
			summary.addRequestedModes(originalModeCounts);

			RouteAttempt originalAttempt = routeAndAudit(selectedPlan, router, boardingDeadlineIndex);
			summary.ptFallbackBefore += originalAttempt.audit.ptFallbackOnlyTrips;
			summary.horizonViolationBefore += originalAttempt.audit.horizonViolationTrips;
			summary.boardingPlannedServiceMissedBefore +=
					originalAttempt.audit.boardingPlannedServiceMissedLegs;
			summary.boardingNextServiceRescueBefore +=
					originalAttempt.audit.boardingNextServiceRescueLegs;
			summary.boardingUncatchableBefore += originalAttempt.audit.boardingUncatchableLegs;
			summary.boardingScheduleMissingBefore += originalAttempt.audit.boardingScheduleMissingLegs;
			summary.boardingScheduleAmbiguousBefore += originalAttempt.audit.boardingScheduleAmbiguousLegs;
			if (originalAttempt.audit.routeError != null) {
				summary.originalRouteErrors++;
			}

			if (originalAttempt.audit.feasible()) {
				summary.feasibleBefore++;
				summary.feasibleAfter++;
				summary.boardingPlannedServiceMissedAfter +=
						originalAttempt.audit.boardingPlannedServiceMissedLegs;
				summary.boardingNextServiceRescueAfter +=
						originalAttempt.audit.boardingNextServiceRescueLegs;
				summary.addCanonicalModes(requestedModeCounts(selectedPlan));
				continue;
			}

			List<String> attempts = new ArrayList<>();
			attempts.add("original:" + originalAttempt.audit.reasonCode());
			RouteAttempt accepted = null;
			String acceptedMode = null;
			for (String candidateMode : orderedCandidateModes(person)) {
				try {
					tripsToLegs.run(selectedPlan);
					applyWholeTourMode(selectedPlan, candidateMode);
					RouteAttempt candidate = routeAndAudit(selectedPlan, router, boardingDeadlineIndex);
					attempts.add(candidateMode + ":" + candidate.audit.reasonCode());
					if (candidate.audit.feasible()) {
						accepted = candidate;
						acceptedMode = candidateMode;
						break;
					}
				} catch (RuntimeException error) {
					attempts.add(candidateMode + ":canonicalization_error");
				}
			}

			if (accepted == null) {
				summary.noFeasibleAlternative++;
				if (originalAttempt.audit.routeError != null) {
					summary.malformedOrUnroutable++;
				}
				summary.records.add(PlanAuditRecord.noAlternative(
						person.getId().toString(), originalMode, originalAttempt.audit, String.join("|", attempts)));
				continue;
			}

			summary.normalizedPersons++;
			summary.normalizedTrips += accepted.audit.tripCount;
			summary.feasibleAfter++;
			summary.ptFallbackAfter += accepted.audit.ptFallbackOnlyTrips;
			summary.horizonViolationAfter += accepted.audit.horizonViolationTrips;
			summary.boardingPlannedServiceMissedAfter +=
					accepted.audit.boardingPlannedServiceMissedLegs;
			summary.boardingNextServiceRescueAfter +=
					accepted.audit.boardingNextServiceRescueLegs;
			summary.boardingUncatchableAfter += accepted.audit.boardingUncatchableLegs;
			summary.boardingScheduleMissingAfter += accepted.audit.boardingScheduleMissingLegs;
			summary.boardingScheduleAmbiguousAfter += accepted.audit.boardingScheduleAmbiguousLegs;
			summary.addCanonicalModes(requestedModeCounts(selectedPlan));
			summary.records.add(PlanAuditRecord.normalized(
					person.getId().toString(), originalMode, acceptedMode, originalAttempt.audit, accepted.audit,
					String.join("|", attempts)));
		}
		return summary;
	}

	private RouteAttempt routeAndAudit(
			Plan plan,
			PlanRouter router,
			PlanATransitBoardingDeadlineIndex boardingDeadlineIndex) {
		try {
			router.run(plan);
			return new RouteAttempt(auditRoutedPlan(
					plan, timeInterpretation, qsimEndTime, feasibilityMargin, qsimTimeStep,
					boardingDeadlineIndex));
		} catch (RuntimeException error) {
			return new RouteAttempt(PlanRouteFeasibility.routeError(error));
		}
	}

	private List<String> orderedCandidateModes(Person person) {
		boolean forbidCar = !ignoreCarAvailability && "never".equals(PersonUtils.getCarAvail(person));
		List<String> modes = new ArrayList<>();
		for (String mode : configuredModes) {
			if (!(forbidCar && TransportMode.car.equals(mode))) {
				modes.add(mode);
			}
		}
		return modes;
	}

	private void writeAudit(CanonicalizationSummary summary) {
		Path csvPath = Path.of(controllerIO.getOutputFilename(CSV_FILE));
		Path jsonPath = Path.of(controllerIO.getOutputFilename(JSON_FILE));
		try {
			writeCsv(csvPath, summary);
			writeJson(jsonPath, summary);
		} catch (IOException error) {
			throw new UncheckedIOException("Could not write Plan A initial route feasibility audit", error);
		}
	}

	private static void writeCsv(Path path, CanonicalizationSummary summary) throws IOException {
		try (var writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			writer.write("person_id,status,old_mode,new_mode,old_reason,new_reason,old_trip_count,new_trip_count,"
					+ "old_pt_fallback_only_trips,new_pt_fallback_only_trips,old_horizon_violation_trips,"
					+ "new_horizon_violation_trips,old_boarding_planned_service_missed_legs,"
					+ "new_boarding_planned_service_missed_legs,old_boarding_next_service_rescue_legs,"
					+ "new_boarding_next_service_rescue_legs,old_boarding_uncatchable_legs,"
					+ "new_boarding_uncatchable_legs,old_boarding_schedule_missing_legs,"
					+ "new_boarding_schedule_missing_legs,old_boarding_schedule_ambiguous_legs,"
					+ "new_boarding_schedule_ambiguous_legs,old_terminal_arrival_seconds,"
					+ "new_terminal_arrival_seconds,attempts\n");
			for (PlanAuditRecord record : summary.records) {
				writer.write(record.asCsvLine());
				writer.write('\n');
			}
		}
	}

	private static void writeJson(Path path, CanonicalizationSummary summary) throws IOException {
		try (var writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			writer.write("{\n");
			jsonField(writer, "schema", SCHEMA, true);
			jsonField(writer, "revision", REVISION, true);
			jsonField(writer, "execution_phase", EXECUTION_PHASE, true);
			jsonField(writer, "selected_persons_checked", summary.selectedPersonsChecked, true);
			jsonField(writer, "selected_trips_checked", summary.selectedTripsChecked, true);
			jsonField(writer, "feasible_before", summary.feasibleBefore, true);
			jsonField(writer, "feasible_after", summary.feasibleAfter, true);
			jsonField(writer, "normalized_persons", summary.normalizedPersons, true);
			jsonField(writer, "normalized_trips", summary.normalizedTrips, true);
			jsonField(writer, "pt_fallback_before", summary.ptFallbackBefore, true);
			jsonField(writer, "pt_fallback_after", summary.ptFallbackAfter, true);
			jsonField(writer, "horizon_violation_before", summary.horizonViolationBefore, true);
			jsonField(writer, "horizon_violation_after", summary.horizonViolationAfter, true);
			jsonField(writer, "boarding_planned_service_missed_legs_before",
					summary.boardingPlannedServiceMissedBefore, true);
			jsonField(writer, "boarding_planned_service_missed_legs_after",
					summary.boardingPlannedServiceMissedAfter, true);
			jsonField(writer, "boarding_next_service_rescue_legs_before",
					summary.boardingNextServiceRescueBefore, true);
			jsonField(writer, "boarding_next_service_rescue_legs_after",
					summary.boardingNextServiceRescueAfter, true);
			jsonField(writer, "boarding_uncatchable_legs_before",
					summary.boardingUncatchableBefore, true);
			jsonField(writer, "boarding_uncatchable_legs_after",
					summary.boardingUncatchableAfter, true);
			jsonField(writer, "boarding_schedule_missing_legs_before",
					summary.boardingScheduleMissingBefore, true);
			jsonField(writer, "boarding_schedule_missing_legs_after",
					summary.boardingScheduleMissingAfter, true);
			jsonField(writer, "boarding_schedule_ambiguous_legs_before",
					summary.boardingScheduleAmbiguousBefore, true);
			jsonField(writer, "boarding_schedule_ambiguous_legs_after",
					summary.boardingScheduleAmbiguousAfter, true);
			jsonField(writer, "original_route_errors", summary.originalRouteErrors, true);
			jsonField(writer, "malformed_unroutable", summary.malformedOrUnroutable, true);
			jsonField(writer, "no_feasible_alternative", summary.noFeasibleAlternative, true);
			jsonField(writer, "skipped_freight_persons", summary.skippedFreightPersons, true);
			jsonField(writer, "qsim_end_time_seconds", summary.qsimEndTime, true);
			jsonField(writer, "qsim_time_step_seconds", summary.qsimTimeStep, true);
			jsonField(writer, "pt_boarding_catchability_rule",
					"find the earliest QSim-boardable same-line service from the exact access stop "
							+ "to a downstream desired egress stop with readyTick < departureTick; "
							+ "propagate that service's scheduled egress-arrival tick to later legs",
					true);
			jsonField(writer, "time_mutation_guard_margin_seconds", summary.feasibilityMargin, true);
			jsonStringArray(writer, "configured_global_modes", summary.configuredModes, true);
			jsonField(writer, "deterministic_fallback_policy",
					"retain original selected plan when route-feasible; otherwise test changeMode.modes in configured order "
							+ "and retain the first route-feasible whole-tour alternative", true);
			jsonMap(writer, "requested_mode_counts", summary.requestedModeCounts, true);
			jsonMap(writer, "canonical_mode_counts", summary.canonicalModeCounts, false);
			writer.write("}\n");
		}
	}

	private static void jsonField(java.io.Writer writer, String key, String value, boolean comma) throws IOException {
		writer.write("  \"");
		writer.write(jsonEscape(key));
		writer.write("\": \"");
		writer.write(jsonEscape(value));
		writer.write('\"');
		if (comma) {
			writer.write(',');
		}
		writer.write('\n');
	}

	private static void jsonField(java.io.Writer writer, String key, long value, boolean comma) throws IOException {
		writer.write("  \"");
		writer.write(jsonEscape(key));
		writer.write("\": ");
		writer.write(Long.toString(value));
		if (comma) {
			writer.write(',');
		}
		writer.write('\n');
	}

	private static void jsonField(java.io.Writer writer, String key, double value, boolean comma) throws IOException {
		writer.write("  \"");
		writer.write(jsonEscape(key));
		writer.write("\": ");
		writer.write(Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "null");
		if (comma) {
			writer.write(',');
		}
		writer.write('\n');
	}

	private static void jsonStringArray(java.io.Writer writer, String key, List<String> values, boolean comma) throws IOException {
		writer.write("  \"");
		writer.write(jsonEscape(key));
		writer.write("\": [");
		for (int index = 0; index < values.size(); index++) {
			if (index > 0) {
				writer.write(", ");
			}
			writer.write('\"');
			writer.write(jsonEscape(values.get(index)));
			writer.write('\"');
		}
		writer.write(']');
		if (comma) {
			writer.write(',');
		}
		writer.write('\n');
	}

	private static void jsonMap(java.io.Writer writer, String key, Map<String, Long> values, boolean comma) throws IOException {
		writer.write("  \"");
		writer.write(jsonEscape(key));
		writer.write("\": {");
		boolean first = true;
		for (Map.Entry<String, Long> entry : values.entrySet()) {
			if (!first) {
				writer.write(", ");
			}
			writer.write('\"');
			writer.write(jsonEscape(entry.getKey()));
			writer.write("\": ");
			writer.write(Long.toString(entry.getValue()));
			first = false;
		}
		writer.write('}');
		if (comma) {
			writer.write(',');
		}
		writer.write('\n');
	}

	/** Returns infeasibility counts after an actual router has populated a plan. */
	static PlanRouteFeasibility auditRoutedPlan(Plan plan, TimeInterpretation timeInterpretation,
			double qsimEndTime, double feasibilityMargin, double qsimTimeStep,
			PlanATransitBoardingDeadlineIndex boardingDeadlineIndex) {
		int tripCount = 0;
		int requestedPtTrips = 0;
		int ptFallbackOnlyTrips = 0;

		try {
			for (Trip trip : TripStructureUtils.getTrips(plan)) {
				tripCount++;
				String requestedMode = requestedRoutingMode(trip);
				if (TransportMode.pt.equals(requestedMode)) {
					requestedPtTrips++;
					if (!containsTransitPassengerLeg(trip)) {
						ptFallbackOnlyTrips++;
					}
				}
			}
			PlanAWholeTourFeasibleModeChoice.TripArrivalEvaluation timing =
					PlanAWholeTourFeasibleModeChoice.evaluateMainTripArrivals(
							plan, timeInterpretation, qsimEndTime, feasibilityMargin, qsimTimeStep,
							boardingDeadlineIndex);
			return new PlanRouteFeasibility(tripCount, requestedPtTrips, ptFallbackOnlyTrips,
					timing.horizonViolationTrips(),
					timing.boardingPlannedServiceMissedLegs(),
					timing.boardingNextServiceRescueLegs(),
					timing.boardingUncatchableLegs(),
					timing.boardingScheduleMissingLegs(), timing.boardingScheduleAmbiguousLegs(),
					timing.terminalArrivalTime(), null);
		} catch (RuntimeException error) {
			return PlanRouteFeasibility.routeError(error);
		}
	}

	static boolean containsTransitPassengerLeg(Trip trip) {
		return trip.getLegsOnly().stream().anyMatch(leg -> TransportMode.pt.equals(leg.getMode())
				&& leg.getRoute() instanceof TransitPassengerRoute transitRoute
				&& transitRoute.getLineId() != null
				&& transitRoute.getRouteId() != null);
	}

	static boolean completesBeforeQsimEnd(double terminalArrivalTime, double qsimEndTime, double safetyMargin) {
		return Double.isFinite(terminalArrivalTime)
				&& Double.isFinite(safetyMargin)
				&& safetyMargin >= 0.0
				&& terminalArrivalTime < qsimEndTime - safetyMargin;
	}

	static double maximumPositiveTimeMutation(double mutationRange, double mutationRangeStep) {
		double range = Math.max(0.0, mutationRange);
		double step = Math.max(1.0, mutationRangeStep);
		int bins = (int) Math.ceil(range / step);
		return bins == 0 ? 0.0 : Math.max(0.0, -range + 2.0 * (bins - 1) * step);
	}

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

	private static Map<String, Long> requestedModeCounts(Plan plan) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (Trip trip : TripStructureUtils.getTrips(plan)) {
			String mode = requestedRoutingMode(trip);
			counts.merge(mode == null ? "__missing__" : mode, 1L, Long::sum);
		}
		return counts;
	}

	/**
	 * Reads the mode explicitly requested from the leg routing metadata rather
	 * than an analysis main-mode classifier. A Raptor fallback can be physically
	 * a walking leg while still being a requested PT trip, which is the exact
	 * distinction that this audit must retain.
	 */
	static String requestedRoutingMode(Trip trip) {
		List<Leg> legs = trip.getLegsOnly();
		if (legs.isEmpty()) {
			return null;
		}
		String routingMode = TripStructureUtils.getRoutingMode(legs.getFirst());
		return routingMode == null ? legs.getFirst().getMode() : routingMode;
	}

	private static String modeSignature(Plan plan) {
		Map<String, Long> counts = requestedModeCounts(plan);
		if (counts.isEmpty()) {
			return "__no_trips__";
		}
		if (counts.size() == 1) {
			return counts.keySet().iterator().next();
		}
		return "mixed(" + String.join("+", counts.keySet()) + ")";
	}

	private static String csv(String value) {
		if (value == null) {
			return "";
		}
		return '\"' + value.replace("\"", "\"\"") + '\"';
	}

	private static String jsonEscape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private record RouteAttempt(PlanRouteFeasibility audit) {
	}

	static record PlanRouteFeasibility(int tripCount, int requestedPtTrips, int ptFallbackOnlyTrips,
			int horizonViolationTrips, int boardingPlannedServiceMissedLegs,
			int boardingNextServiceRescueLegs, int boardingUncatchableLegs,
			int boardingScheduleMissingLegs, int boardingScheduleAmbiguousLegs,
			double terminalArrivalTime, String routeError) {
		static PlanRouteFeasibility routeError(RuntimeException error) {
			return new PlanRouteFeasibility(0, 0, 0, 0, 0, 0, 0, 0, 0, Double.NaN,
					error.getClass().getSimpleName() + ": " + error.getMessage());
		}

		boolean feasible() {
			return routeError == null
					&& ptFallbackOnlyTrips == 0
					&& horizonViolationTrips == 0
					&& boardingUncatchableLegs == 0
					&& boardingScheduleMissingLegs == 0
					&& boardingScheduleAmbiguousLegs == 0;
		}

		String reasonCode() {
			if (routeError != null) {
				return "route_error";
			}
			List<String> reasons = new ArrayList<>();
			if (ptFallbackOnlyTrips > 0) {
				reasons.add("pt_fallback");
			}
			if (boardingUncatchableLegs > 0) {
				reasons.add("boarding_uncatchable");
			}
			if (boardingScheduleMissingLegs > 0) {
				reasons.add("boarding_schedule_missing");
			}
			if (boardingScheduleAmbiguousLegs > 0) {
				reasons.add("boarding_schedule_ambiguous");
			}
			if (horizonViolationTrips > 0) {
				reasons.add("horizon");
			}
			if (reasons.isEmpty()) {
				return "feasible";
			}
			if (reasons.size() == 1 && "pt_fallback".equals(reasons.getFirst())) {
				return "pt_fallback_only";
			}
			return String.join("_and_", reasons);
		}
	}

	private record PlanAuditRecord(String personId, String status, String oldMode, String newMode,
			String oldReason, String newReason, PlanRouteFeasibility oldAudit,
			PlanRouteFeasibility newAudit, String attempts) {
		static PlanAuditRecord normalized(String personId, String oldMode, String newMode,
				PlanRouteFeasibility oldAudit, PlanRouteFeasibility newAudit, String attempts) {
			return new PlanAuditRecord(personId, "normalized", oldMode, newMode, oldAudit.reasonCode(),
					newAudit.reasonCode(), oldAudit, newAudit, attempts);
		}

		static PlanAuditRecord noAlternative(String personId, String oldMode,
				PlanRouteFeasibility oldAudit, String attempts) {
			return new PlanAuditRecord(personId, "no_feasible_alternative", oldMode, "", oldAudit.reasonCode(),
					"", oldAudit,
					new PlanRouteFeasibility(0, 0, 0, 0, 0, 0, 0, 0, 0, Double.NaN, null), attempts);
		}

		static PlanAuditRecord malformed(String personId, String reason) {
			PlanRouteFeasibility error =
					new PlanRouteFeasibility(0, 0, 0, 0, 0, 0, 0, 0, 0, Double.NaN, reason);
			return new PlanAuditRecord(personId, "malformed", "", "", "route_error", "", error,
					new PlanRouteFeasibility(0, 0, 0, 0, 0, 0, 0, 0, 0, Double.NaN, null), reason);
		}

		String asCsvLine() {
			return String.join(",", csv(personId), csv(status), csv(oldMode), csv(newMode), csv(oldReason),
					csv(newReason), Integer.toString(oldAudit.tripCount), Integer.toString(newAudit.tripCount),
					Integer.toString(oldAudit.ptFallbackOnlyTrips), Integer.toString(newAudit.ptFallbackOnlyTrips),
					Integer.toString(oldAudit.horizonViolationTrips), Integer.toString(newAudit.horizonViolationTrips),
					Integer.toString(oldAudit.boardingPlannedServiceMissedLegs),
					Integer.toString(newAudit.boardingPlannedServiceMissedLegs),
					Integer.toString(oldAudit.boardingNextServiceRescueLegs),
					Integer.toString(newAudit.boardingNextServiceRescueLegs),
					Integer.toString(oldAudit.boardingUncatchableLegs),
					Integer.toString(newAudit.boardingUncatchableLegs),
					Integer.toString(oldAudit.boardingScheduleMissingLegs),
					Integer.toString(newAudit.boardingScheduleMissingLegs),
					Integer.toString(oldAudit.boardingScheduleAmbiguousLegs),
					Integer.toString(newAudit.boardingScheduleAmbiguousLegs),
					doubleCsv(oldAudit.terminalArrivalTime), doubleCsv(newAudit.terminalArrivalTime), csv(attempts));
		}

		private static String doubleCsv(double value) {
			return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "";
		}
	}

	private static final class CanonicalizationSummary {
		private final List<String> configuredModes;
		private final double feasibilityMargin;
		private final double qsimEndTime;
		private final double qsimTimeStep;
		private final Map<String, Long> requestedModeCounts = new LinkedHashMap<>();
		private final Map<String, Long> canonicalModeCounts = new LinkedHashMap<>();
		private final List<PlanAuditRecord> records = new ArrayList<>();
		private long selectedPersonsChecked;
		private long selectedTripsChecked;
		private long feasibleBefore;
		private long feasibleAfter;
		private long normalizedPersons;
		private long normalizedTrips;
		private long ptFallbackBefore;
		private long ptFallbackAfter;
		private long horizonViolationBefore;
		private long horizonViolationAfter;
		private long boardingPlannedServiceMissedBefore;
		private long boardingPlannedServiceMissedAfter;
		private long boardingNextServiceRescueBefore;
		private long boardingNextServiceRescueAfter;
		private long boardingUncatchableBefore;
		private long boardingUncatchableAfter;
		private long boardingScheduleMissingBefore;
		private long boardingScheduleMissingAfter;
		private long boardingScheduleAmbiguousBefore;
		private long boardingScheduleAmbiguousAfter;
		private long originalRouteErrors;
		private long malformedOrUnroutable;
		private long noFeasibleAlternative;
		private long skippedFreightPersons;

		private CanonicalizationSummary(
				List<String> configuredModes,
				double feasibilityMargin,
				double qsimEndTime,
				double qsimTimeStep) {
			this.configuredModes = configuredModes;
			this.feasibilityMargin = feasibilityMargin;
			this.qsimEndTime = qsimEndTime;
			this.qsimTimeStep = qsimTimeStep;
		}

		private void addRequestedModes(Map<String, Long> counts) {
			addModes(requestedModeCounts, counts);
		}

		private void addCanonicalModes(Map<String, Long> counts) {
			addModes(canonicalModeCounts, counts);
		}

		private static void addModes(Map<String, Long> target, Map<String, Long> counts) {
			for (Map.Entry<String, Long> entry : counts.entrySet()) {
				target.merge(entry.getKey(), entry.getValue(), Long::sum);
			}
		}
	}
}
