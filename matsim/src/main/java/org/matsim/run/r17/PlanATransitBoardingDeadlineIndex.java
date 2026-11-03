package org.matsim.run.r17;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, current-timetable index of services that QSim can actually board.
 *
 * <p>{@code TransitAgentImpl} does not bind a passenger to a departure id. With
 * line-and-stop boarding acceptance, a passenger can enter the next vehicle on
 * the requested line that visits the exact access stop and subsequently reaches
 * the desired egress stop. The planned route id is therefore validated
 * structurally, but it does not restrict the boardable candidate set.</p>
 *
 * <p>Catchability is evaluated on QSim ticks, not by comparing raw doubles:
 * {@code ceil(ready/dt) < ceil(vehicleDeparture/dt)}. The selected service's
 * scheduled egress-arrival tick is returned so a later transfer starts from the
 * same discrete execution clock. Unsupported candidates are excluded; a key
 * with no provable service fails closed as missing.</p>
 *
 * <p>Lifecycle/build budget: each active Plan-A replanning module creates one
 * snapshot for all of its workers, and the pre-Mobsim invariant creates one from
 * the timetable currently installed by co-transport. Iteration 0 canonicalization
 * creates one additional snapshot. No algorithm builds or scans the timetable
 * per person.</p>
 */
final class PlanATransitBoardingDeadlineIndex {
	enum Status {
		SAFE,
		UNCATCHABLE,
		MISSING,
		AMBIGUOUS
	}

	record Evaluation(
			Status status,
			double scheduledVehicleDepartureTickTime,
			double scheduledEgressArrivalTickTime,
			boolean plannedServiceMissed,
			boolean nextServiceRescue) {
		private static Evaluation safe(
				double departureTickTime,
				double arrivalTickTime,
				boolean plannedServiceMissed,
				boolean nextServiceRescue) {
			return new Evaluation(
					Status.SAFE,
					departureTickTime,
					arrivalTickTime,
					plannedServiceMissed,
					nextServiceRescue);
		}

		private static Evaluation uncatchable(boolean plannedServiceMissed) {
			return new Evaluation(
					Status.UNCATCHABLE,
					Double.NaN,
					Double.NaN,
					plannedServiceMissed,
					false);
		}

		private static Evaluation missing() {
			return new Evaluation(Status.MISSING, Double.NaN, Double.NaN, false, false);
		}

		private static Evaluation ambiguous(boolean plannedServiceMissed) {
			return new Evaluation(
					Status.AMBIGUOUS,
					Double.NaN,
					Double.NaN,
					plannedServiceMissed,
					false);
		}
	}

	private final Map<ServiceKey, List<Service>> servicesByKey;
	private final Set<PlannedRouteKey> structurallyValidPlannedRoutes;
	private final double qsimTimeStep;
	private final double qsimTimeOrigin;

	private PlanATransitBoardingDeadlineIndex(
			Map<ServiceKey, List<Service>> servicesByKey,
			Set<PlannedRouteKey> structurallyValidPlannedRoutes,
			double qsimTimeStep,
			double qsimTimeOrigin) {
		this.servicesByKey = Map.copyOf(servicesByKey);
		this.structurallyValidPlannedRoutes = Set.copyOf(structurallyValidPlannedRoutes);
		this.qsimTimeStep = qsimTimeStep;
		this.qsimTimeOrigin = qsimTimeOrigin;
	}

	static PlanATransitBoardingDeadlineIndex build(
			TransitSchedule transitSchedule,
			double qsimTimeStep,
			double qsimTimeOrigin) {
		PlanAWholeTourFeasibleModeChoice.requirePositiveQsimTimeStep(qsimTimeStep);
		if (!Double.isFinite(qsimTimeOrigin)) {
			throw new IllegalArgumentException(
					"Plan A boarding-feasibility guard requires a finite QSim time origin");
		}
		if (transitSchedule == null) {
			return new PlanATransitBoardingDeadlineIndex(
					Map.of(), Set.of(), qsimTimeStep, qsimTimeOrigin);
		}

		Map<ServiceKey, List<Service>> mutableServices = new HashMap<>();
		Set<PlannedRouteKey> validPlannedRoutes = new HashSet<>();
		for (TransitLine line : transitSchedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				List<? extends TransitRouteStop> stops = route.getStops();
				for (int accessIndex = 0; accessIndex < stops.size() - 1; accessIndex++) {
					TransitRouteStop accessStop = stops.get(accessIndex);
					if (!accessStop.isAllowBoarding()) {
						continue;
					}
					var departureOffset = accessStop.getDepartureOffset();
					var arrivalOffset =
							accessStop.getArrivalOffset().or(() -> departureOffset);
					boolean supportedAccess =
							arrivalOffset.isDefined()
									&& departureOffset.isDefined()
									&& accessStop.isAwaitDepartureTime();

					for (int egressIndex = accessIndex + 1; egressIndex < stops.size(); egressIndex++) {
						TransitRouteStop egressStop = stops.get(egressIndex);
						if (!egressStop.isAllowAlighting()) {
							continue;
						}
						PlannedRouteKey plannedRouteKey = new PlannedRouteKey(
								line.getId(),
								route.getId(),
								accessStop.getStopFacility().getId(),
								egressStop.getStopFacility().getId());
						validPlannedRoutes.add(plannedRouteKey);

						var egressArrivalOffset =
								egressStop.getArrivalOffset().or(egressStop::getDepartureOffset);
						if (!supportedAccess || egressArrivalOffset.isUndefined()) {
							continue;
						}

						double accessArrivalOffsetSeconds = arrivalOffset.seconds();
						double accessDepartureOffsetSeconds = departureOffset.seconds();
						double egressArrivalOffsetSeconds = egressArrivalOffset.seconds();
						if (!Double.isFinite(accessArrivalOffsetSeconds)
								|| !Double.isFinite(accessDepartureOffsetSeconds)
								|| !Double.isFinite(egressArrivalOffsetSeconds)
								|| accessDepartureOffsetSeconds < accessArrivalOffsetSeconds
								|| egressArrivalOffsetSeconds < accessDepartureOffsetSeconds) {
							continue;
						}

						ServiceKey serviceKey = new ServiceKey(
								line.getId(),
								accessStop.getStopFacility().getId(),
								egressStop.getStopFacility().getId());
						List<Service> services =
								mutableServices.computeIfAbsent(serviceKey, ignored -> new ArrayList<>());
						for (var departure : route.getDepartures().values()) {
							double departureBase = departure.getDepartureTime();
							long accessArrivalTick =
									toQsimTick(
											departureBase + accessArrivalOffsetSeconds,
											qsimTimeStep,
											qsimTimeOrigin);
							long accessDepartureTick =
									toQsimTick(
											departureBase + accessDepartureOffsetSeconds,
											qsimTimeStep,
											qsimTimeOrigin);
							long egressArrivalTick =
									toQsimTick(
											departureBase + egressArrivalOffsetSeconds,
											qsimTimeStep,
											qsimTimeOrigin);
							if (accessArrivalTick == Long.MIN_VALUE
									|| accessDepartureTick == Long.MIN_VALUE
									|| egressArrivalTick == Long.MIN_VALUE
									|| accessArrivalTick > accessDepartureTick
									|| accessDepartureTick > egressArrivalTick) {
								continue;
							}
							services.add(new Service(
									route.getId(),
									accessArrivalTick,
									accessDepartureTick,
									egressArrivalTick));
						}
					}
				}
			}
		}

		Map<ServiceKey, List<Service>> immutableServices = new HashMap<>();
		for (Map.Entry<ServiceKey, List<Service>> entry : mutableServices.entrySet()) {
			List<Service> services = entry.getValue();
			services.sort(Comparator
					.comparingLong(Service::accessDepartureTick)
					.thenComparingLong(Service::egressArrivalTick)
					.thenComparing(service -> service.routeId().toString()));
			if (!services.isEmpty()) {
				immutableServices.put(entry.getKey(), List.copyOf(services));
			}
		}
		return new PlanATransitBoardingDeadlineIndex(
				immutableServices,
				validPlannedRoutes,
				qsimTimeStep,
				qsimTimeOrigin);
	}

	Evaluation evaluate(
			TransitPassengerRoute passengerRoute,
			double accessOrTransferArrivalTime,
			double expectedQsimTimeStep) {
		if (Double.compare(qsimTimeStep, expectedQsimTimeStep) != 0) {
			throw new IllegalArgumentException(
					"Timetable index qsim step " + qsimTimeStep
							+ " does not match evaluator step " + expectedQsimTimeStep);
		}
		if (!Double.isFinite(accessOrTransferArrivalTime)
				|| passengerRoute == null
				|| passengerRoute.getBoardingTime().isUndefined()
				|| passengerRoute.getLineId() == null
				|| passengerRoute.getRouteId() == null
				|| passengerRoute.getAccessStopId() == null
				|| passengerRoute.getEgressStopId() == null
				|| passengerRoute.getChainedRoute() != null) {
			return Evaluation.missing();
		}

		PlannedRouteKey plannedRouteKey = new PlannedRouteKey(
				passengerRoute.getLineId(),
				passengerRoute.getRouteId(),
				passengerRoute.getAccessStopId(),
				passengerRoute.getEgressStopId());
		if (!structurallyValidPlannedRoutes.contains(plannedRouteKey)) {
			return Evaluation.missing();
		}

		List<Service> services = servicesByKey.get(new ServiceKey(
				passengerRoute.getLineId(),
				passengerRoute.getAccessStopId(),
				passengerRoute.getEgressStopId()));
		if (services == null || services.isEmpty()) {
			return Evaluation.missing();
		}

		long readyTick =
				toQsimTick(accessOrTransferArrivalTime, qsimTimeStep, qsimTimeOrigin);
		long boardingTick =
				toQsimTick(
						passengerRoute.getBoardingTime().seconds(),
						qsimTimeStep,
						qsimTimeOrigin);
		if (readyTick == Long.MIN_VALUE || boardingTick == Long.MIN_VALUE) {
			return Evaluation.missing();
		}

		Long plannedDepartureTick =
				resolvePlannedDepartureTick(services, passengerRoute.getRouteId(), boardingTick);
		boolean plannedServiceMissed =
				plannedDepartureTick != null && readyTick >= plannedDepartureTick;

		int firstIndex = firstStrictlyLaterDeparture(services, readyTick);
		if (firstIndex >= services.size()) {
			return Evaluation.uncatchable(plannedServiceMissed);
		}

		Service first = services.get(firstIndex);
		int equivalentEnd = firstIndex + 1;
		while (equivalentEnd < services.size()
				&& services.get(equivalentEnd).accessDepartureTick()
						== first.accessDepartureTick()) {
			equivalentEnd++;
		}
		for (int index = firstIndex + 1; index < equivalentEnd; index++) {
			if (services.get(index).egressArrivalTick() != first.egressArrivalTick()) {
				return Evaluation.ambiguous(plannedServiceMissed);
			}
		}
		boolean nextServiceRescue =
				plannedServiceMissed
						&& plannedDepartureTick != null
						&& first.accessDepartureTick() > plannedDepartureTick;
		return Evaluation.safe(
				tickTime(first.accessDepartureTick()),
				tickTime(first.egressArrivalTick()),
				plannedServiceMissed,
				nextServiceRescue);
	}

	private static Long resolvePlannedDepartureTick(
			List<Service> services,
			Id<TransitRoute> plannedRouteId,
			long boardingTick) {
		Long match = null;
		for (Service service : services) {
			if (!plannedRouteId.equals(service.routeId())
					|| service.accessArrivalTick() > boardingTick
					|| boardingTick > service.accessDepartureTick()) {
				continue;
			}
			if (match != null && match.longValue() != service.accessDepartureTick()) {
				/* Diagnostic is unknown when the route data do not identify one service. */
				return null;
			}
			match = service.accessDepartureTick();
		}
		return match;
	}

	private static int firstStrictlyLaterDeparture(List<Service> services, long readyTick) {
		int low = 0;
		int high = services.size();
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (services.get(middle).accessDepartureTick() <= readyTick) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		return low;
	}

	static long toQsimTick(double time, double qsimTimeStep) {
		return toQsimTick(time, qsimTimeStep, 0.0);
	}

	static long toQsimTick(double time, double qsimTimeStep, double qsimTimeOrigin) {
		if (!Double.isFinite(time)
				|| !Double.isFinite(qsimTimeStep)
				|| qsimTimeStep <= 0.0
				|| !Double.isFinite(qsimTimeOrigin)) {
			return Long.MIN_VALUE;
		}
		double quotient = (time - qsimTimeOrigin) / qsimTimeStep;
		double nearestInteger = Math.rint(quotient);
		double scale = Math.max(1.0, Math.abs(quotient));
		if (Math.abs(quotient - nearestInteger) <= 8.0 * Math.ulp(scale)) {
			quotient = nearestInteger;
		}
		double tick = Math.ceil(quotient);
		if (tick < Long.MIN_VALUE + 1.0 || tick > Long.MAX_VALUE) {
			return Long.MIN_VALUE;
		}
		return (long) tick;
	}

	private double tickTime(long tick) {
		return qsimTimeOrigin + tick * qsimTimeStep;
	}

	double ceilToQsimTickTime(double time) {
		long tick = toQsimTick(time, qsimTimeStep, qsimTimeOrigin);
		return tick == Long.MIN_VALUE ? Double.POSITIVE_INFINITY : tickTime(tick);
	}

	private record ServiceKey(
			Id<TransitLine> lineId,
			Id<TransitStopFacility> accessStopId,
			Id<TransitStopFacility> egressStopId) {
	}

	private record PlannedRouteKey(
			Id<TransitLine> lineId,
			Id<TransitRoute> routeId,
			Id<TransitStopFacility> accessStopId,
			Id<TransitStopFacility> egressStopId) {
	}

	private record Service(
			Id<TransitRoute> routeId,
			long accessArrivalTick,
			long accessDepartureTick,
			long egressArrivalTick) {
	}
}
