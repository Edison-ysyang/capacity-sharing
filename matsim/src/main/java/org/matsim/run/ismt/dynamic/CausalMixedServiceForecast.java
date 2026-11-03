package org.matsim.run.ismt.dynamic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.matsim.contrib.cotransport.CoTransportBoardingPolicy.SharedBoardingWindow;

import static org.matsim.run.ismt.dynamic.ClosedPassengerArrivalForecast.Od;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.Limits;
import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import static org.matsim.run.ismt.dynamic.RollingDeparturePolicy.*;

/**
 * Source-bound assembler for the explicit finite-capacity mixed-service forecast.
 * It consumes only a closed Snapshot, detached published supply and static OD
 * access/egress estimates. It has no population, plan or full-order-registry input.
 * The first call on each new Snapshot MUST be the policy's unchanged current
 * commitments. That map and all demand/cohort identities are frozen for the whole
 * decision, including subsequent coordinate-search sweeps. This call-order contract
 * is supplied by RollingDeparturePolicy, not independently authenticated here.
 *
 * <p>Passenger arrivals are stationary trailing-window fluid estimates through the
 * fixed execution horizon, not future realized people. Only an OD tail after every
 * published service's latest possible boarding opportunity is omitted, with its
 * expected constant mass reported. Protection uses a fixed OD-specific arrival
 * envelope of adjustable trains, not all full-day arrivals; buckets crossing its
 * boundary are split. Long-horizon stationarity remains an explicit limitation.
 * Freight is restricted to known released, direct-PT units. The
 * kernel uses batched predicted stop opportunities, deterministic ID tie breaking,
 * finite physical capacity, passenger priority and mixed admission inequalities.
 * Neither fractional door timing, same-second native queue ordering, dynamic
 * rerouting nor physical instantaneous occupancy is reproduced. At an observed
 * stop, the cutoff-clipped arrival/minimum-dwell treatment is also an approximation.
 * Prepared single-line/no-through-chain/direct-freight input and actual native
 * execution remain separate assembly gates; this class cannot certify them.
 *
 * <p>Uncompleted waiting and lateness are horizon-censored accounts, not complete
 * realized outcomes. Missing registrations, unsupported states and exhausted
 * computational budgets return unavailable forecasts; none is replaced by zero.
 * A new simulation day requires a new instance. No native state or RNG is changed.</p>
 */
public final class CausalMixedServiceForecast implements ForecastModel {
    public static final String APPROXIMATION = "closed-prefix-stationary-fluid-OD;finite-capacity-batched-stops;"
            + "observed-baseline-residual-delay;deterministic-ID-ties;horizon-censored-accounts-v1";
    public static final String WINDOWED_APPROXIMATION = APPROXIMATION
            + ";shared-window-boarding-only;onboard-completion-permitted;windowed-origin-adjustments-v2";
    private static final Comparator<TripKey> TRIP_ORDER = Comparator.comparing(TripKey::line)
            .thenComparing(TripKey::route).thenComparing(TripKey::departure);

    public record Settings(double observationStartSeconds, double trailingWindowSeconds,
                           double adjustableDepartureLookaheadSeconds, double arrivalCohortStepSeconds,
                           double executionEndSeconds, int maximumPassengerCohorts, int maximumStopEvents,
                           double freightShareCap, double passengerLoadGate, Limits limits,
                           SharedBoardingWindow sharedWindow) {
        public Settings(double observationStartSeconds, double trailingWindowSeconds,
                double adjustableDepartureLookaheadSeconds, double arrivalCohortStepSeconds,
                double executionEndSeconds, int maximumPassengerCohorts, int maximumStopEvents,
                double freightShareCap, double passengerLoadGate, Limits limits) {
            this(observationStartSeconds, trailingWindowSeconds, adjustableDepartureLookaheadSeconds,
                    arrivalCohortStepSeconds, executionEndSeconds, maximumPassengerCohorts, maximumStopEvents,
                    freightShareCap, passengerLoadGate, limits, null);
        }
        public Settings {
            time(observationStartSeconds); positive(trailingWindowSeconds); positive(adjustableDepartureLookaheadSeconds);
            positive(arrivalCohortStepSeconds); time(executionEndSeconds);
            require(executionEndSeconds > observationStartSeconds, "Empty execution horizon");
            require(maximumPassengerCohorts > 0 && maximumStopEvents > 0, "Explicit positive computational budgets required");
            fraction(freightShareCap); fraction(passengerLoadGate); Objects.requireNonNull(limits);
            if (sharedWindow != null) require(sharedWindow.endExclusiveSeconds() <= executionEndSeconds,
                    "Shared window exceeds forecast execution horizon");
        }
    }

    /** Static declared durations, never actual future access/egress completion times. */
    public record AccessEgressEstimate(double accessSeconds, double egressSeconds) {
        public AccessEgressEstimate { time(accessSeconds); time(egressSeconds); }
    }

    /** Full per-unit completion statuses are retained in result, including censored and aborted units. */
    public record DetailedForecast(Forecast forecast, String demandProjectionId,
                                   int iteration, long epoch, long viewSequence, String closedPrefixSha256,
                                   OptionalDouble closedThroughSeconds, double decisionTimeSeconds,
                                   double forecastEndSeconds, double arrivalProjectionEndSeconds,
                                   boolean newDemandPreparation, int currentWaitingPassengers,
                                   int initiallyRidingPassengers, int futureArrivalCohorts,
                                   int protectedCohorts, double protectedPassengerMass, int knownFreightUnits,
                                   boolean hasProtectedPassengerCohort, Map<Od, Double> protectedArrivalThroughSeconds,
                                   Map<Od, Double> capacityArrivalThroughSeconds, double omittedUnserviceableTailPassengerMass,
                                   String approximation, Map<MixedCapacityProjection.Completion, Long> completionCounts,
                                   Optional<MixedCapacityProjection.Result> result) {
        public DetailedForecast {
            Objects.requireNonNull(forecast); Objects.requireNonNull(closedThroughSeconds);
            Objects.requireNonNull(result); completionCounts = Map.copyOf(completionCounts);
            protectedArrivalThroughSeconds = Map.copyOf(protectedArrivalThroughSeconds);
            capacityArrivalThroughSeconds = Map.copyOf(capacityArrivalThroughSeconds);
        }
    }

    private record Prepared(ClosedCoTransportObservationState.Snapshot source, Map<TripKey, Double> noAction,
                            String id, double cutoff, double arrivalEnd, List<MixedCapacityProjection.Passenger> passengers,
                            List<MixedCapacityProjection.Passenger> protectedPassengersById,
                            List<MixedCapacityProjection.Freight> freight, List<MixedCapacityProjection.Service> observedServices,
                            Set<TripKey> observedTrips, Set<String> releasedUnits,
                            Map<Od, Double> protectionBounds,
                            Map<Od, Double> capacityBounds, double omittedTailMass,
                            int waitingCount, int ridingCount, int futureCount, int protectedCount, double protectedMass,
                            ForecastStatus status, String reason) { }

    private final Settings settings;
    private final SingleLineForecastSupply supply;
    private final Map<Od, AccessEgressEstimate> accessEgress;
    private final ClosedPassengerArrivalForecast arrivals;
    private final MixedCapacityProjection kernel = new MixedCapacityProjection();
    private final Consumer<DetailedForecast> audit;
    private final Thread owner = Thread.currentThread();
    private Prepared prepared;
    private DetailedForecast last;
    private long demandPreparationCount;

    public CausalMixedServiceForecast(Settings settings, SingleLineForecastSupply supply,
                                     Map<Od, AccessEgressEstimate> accessEgress, Consumer<DetailedForecast> audit) {
        this.settings = Objects.requireNonNull(settings); this.supply = Objects.requireNonNull(supply);
        this.accessEgress = Map.copyOf(accessEgress); this.audit = Objects.requireNonNull(audit);
        arrivals = new ClosedPassengerArrivalForecast(settings.observationStartSeconds(), settings.trailingWindowSeconds());
        this.accessEgress.forEach((od, estimate) -> {
            Objects.requireNonNull(estimate); require(legalOd(od), "Static access/egress OD is outside single-line supply");
        });
    }

    public Optional<DetailedForecast> lastForecast() { requireOwner(); return Optional.ofNullable(last); }
    public long demandPreparationCount() { requireOwner(); return demandPreparationCount; }

    /**
     * Read-only capability for the immediately preceding successful ORIGINAL
     * no-action forecast. It is not a day-completion flag: a new Snapshot, a newly
     * released unit, or even a subsequent candidate projection requires a new
     * decision's reference. Only observed direct-PT deliveries qualify. Their actual
     * first boarding/delivery times are seeded unchanged by MixedCapacityProjection,
     * so their (possibly nonzero) waiting/lateness accounts cannot improve with a
     * future departure. This does not certify any candidate's passenger protection.
     */
    @Override public Optional<FreightCost> fixedObservedDeliveredFreightCost(
            ClosedCoTransportObservationState.Snapshot observations, Projection originalNoActionProjection) {
        requireOwner(); Objects.requireNonNull(observations); Objects.requireNonNull(originalNoActionProjection);
        if (prepared == null || prepared.source() != observations || prepared.status() != ForecastStatus.READY
                || last == null || !last.newDemandPreparation() || last.forecast().status() != ForecastStatus.READY
                || last.forecast().projection().orElse(null) != originalNoActionProjection || last.result().isEmpty()) return Optional.empty();
        var position = observations.position();
        var journeys = observations.journeys();
        if (position.closedThroughSeconds().isEmpty() || !journeys.registrationPresent()
                || !journeys.allKnownReleasedUnitsCovered() || prepared.releasedUnits().isEmpty()) return Optional.empty();
        double cutoff = position.closedThroughSeconds().getAsDouble();
        Set<String> released = new HashSet<>();
        observations.releases().knownOrders().values().forEach(order -> released.addAll(order.releasedUnits().keySet()));
        if (!released.equals(prepared.releasedUnits()) || !released.equals(originalNoActionProjection.releasedFreightUnitIds())
                || !released.equals(journeys.knownReleasedUnitIds()) || !released.equals(journeys.journeys().keySet())
                || !released.equals(last.result().orElseThrow().freight().keySet())) return Optional.empty();
        for (String unit : released) {
            var journey = journeys.journeys().get(unit);
            if (journey.phase() != KnownFreightJourneyState.Phase.DELIVERED || journey.firstBoardedAtSeconds().isEmpty()
                    || journey.deliveredAtSeconds().isEmpty() || journey.lastObservedAtSeconds() > cutoff
                    || journey.firstBoardedAtSeconds().getAsDouble() > cutoff || journey.deliveredAtSeconds().getAsDouble() > cutoff
                    || last.result().orElseThrow().freight().get(unit).completion() != MixedCapacityProjection.Completion.DELIVERED_OBSERVED)
                return Optional.empty();
        }
        // Return the exact original aggregation; do not re-sum in a different order or replace it with zero.
        return Optional.of(originalNoActionProjection.freight());
    }

    @Override public Forecast project(ClosedCoTransportObservationState.Snapshot observations, Map<TripKey, Double> departures) {
        requireOwner(); Objects.requireNonNull(observations); Objects.requireNonNull(departures);
        boolean fresh = prepared == null || prepared.source() != observations;
        var position = observations.position();
        if (fresh && prepared != null) {
            var previous = prepared.source().position();
            if (position.iteration() != previous.iteration() || position.epoch() != previous.epoch()
                    || position.viewSequence() <= previous.viewSequence() || position.decisionTimeSeconds() <= previous.decisionTimeSeconds())
                return publishFailure(observations, "replayed-foreign-or-cross-day-snapshot", false);
        }
        if (fresh) {
            demandPreparationCount = Math.incrementExact(demandPreparationCount);
            try { prepared = prepare(observations, immutableTimes(departures)); }
            catch (IllegalArgumentException | ArithmeticException failure) {
                prepared = unavailable(observations, Map.of(), ForecastStatus.UNSUPPORTED_STATE, failure.getMessage());
            }
        }
        Forecast forecast; Optional<MixedCapacityProjection.Result> result = Optional.empty();
        if (prepared.status() != ForecastStatus.READY) forecast = new Forecast(prepared.status(), prepared.reason(), Optional.empty());
        else {
            try {
                Map<TripKey, Double> candidate = immutableTimes(departures);
                var services = candidateServices(prepared, candidate);
                var projected = kernel.project(new MixedCapacityProjection.Inputs(prepared.cutoff(), settings.executionEndSeconds(),
                        settings.freightShareCap(), settings.passengerLoadGate(), settings.maximumStopEvents(),
                        services, prepared.passengers(), prepared.freight(), settings.sharedWindow()));
                long missed = 0; double lateness = 0, waiting = 0;
                for (String unit : projected.freight().keySet().stream().sorted().toList()) {
                    var outcome = projected.freight().get(unit);
                    if (outcome.missedDeadlineOrUnserved()) missed = Math.incrementExact(missed);
                    lateness = add(lateness, outcome.latenessAccountSeconds()); waiting = add(waiting, outcome.waitingAccountSeconds());
                }
                var protectedCosts = new LinkedHashMap<String, PassengerCost>();
                require(projected.passengers().size() == prepared.passengers().size(), "Projected passenger coverage changed");
                // The original sort/filter order is frozen once per Snapshot. Retain
                // each protected member's original mass; no candidate-dependent selection.
                for (var member : prepared.protectedPassengersById()) {
                    var value = projected.passengers().get(member.id());
                    require(value != null && value.protectedCohort() && value.mass() == member.mass(), "Projected protected member or mass changed");
                    protectedCosts.put(member.id(), new PassengerCost(value.mass(), value.meanWaitingSeconds()));
                }
                forecast = Forecast.ready(new Projection(prepared.id(), settings.executionEndSeconds(), prepared.releasedUnits(),
                        new FreightCost(missed, lateness, waiting), protectedCosts));
                result = Optional.of(projected);
            } catch (IllegalArgumentException | ArithmeticException failure) {
                forecast = new Forecast(ForecastStatus.UNSUPPORTED_STATE, reason(failure.getMessage()), Optional.empty());
            }
        }
        return publish(prepared, forecast, result, fresh);
    }

    private Prepared prepare(ClosedCoTransportObservationState.Snapshot source, Map<TripKey, Double> noAction) {
        var position = source.position();
        require(settings.executionEndSeconds() > position.decisionTimeSeconds(), "Decision is at or beyond execution horizon");
        var demand = arrivals.forecast(source);
        if (!demand.warmupKnown()) return unavailable(source, noAction, ForecastStatus.INSUFFICIENT_HISTORY, "full-trailing-window-not-observed");
        double cutoff = position.closedThroughSeconds().orElseThrow();
        double arrivalEnd = settings.executionEndSeconds();
        require(source.journeys().registrationPresent() && source.journeys().allKnownReleasedUnitsCovered(),
                "journey-registration-or-released-unit-coverage-unavailable");
        var released = new TreeMap<String, KnownFreightReleaseState.KnownOrder>();
        source.releases().knownOrders().values().forEach(order -> order.releasedUnits().forEach((unit, at) -> {
            require(at <= cutoff && order.deadlineSeconds() <= settings.executionEndSeconds(), "Release/deadline exceeds available observation or forecast horizon");
            require(released.putIfAbsent(unit, order) == null, "Repeated released freight unit");
        }));
        require(source.journeys().knownReleasedUnitIds().equals(released.keySet())
                && source.journeys().journeys().keySet().equals(released.keySet()), "Journey and release prefix disagree");
        var observedTrips = new HashSet<TripKey>(); var observed = new ArrayList<MixedCapacityProjection.Service>();
        for (var work : source.services().latestWorkByVehicle().values())
            require(work.trip().isPresent(), "Wenden/through-chain work is outside forecast domain");
        for (var entry : source.services().observedServices().entrySet().stream().sorted(Map.Entry.comparingByKey(TRIP_ORDER)).toList()) {
            var work = entry.getValue(); var trip = supply.trips().get(entry.getKey());
            require(trip != null && work.trip().orElseThrow().equals(entry.getKey())
                    && work.vehicleId().equals(trip.scheduled().vehicleId()), "Observed trip/vehicle is outside detached supply");
            require(work.startedAtSeconds() <= cutoff && work.lastObservedAtSeconds() <= cutoff, "Future observed service state");
            observedTrips.add(entry.getKey());
            require(work.motion() != ObservedTransitServiceState.Motion.ABORTED, "Aborted service has unresolved execution state");
            if (work.motion() == ObservedTransitServiceState.Motion.ENDED) continue;
            int next = work.nextStopIndex();
            require(next >= 0 && next <= trip.calls().size(), "Invalid observed next call");
            if (next == trip.calls().size()) {
                require(work.motion() == ObservedTransitServiceState.Motion.BETWEEN_STOPS, "Observed calls exhausted in invalid phase");
                continue;
            }
            double baseline = trip.scheduled().baselineDepartureSeconds(); double delay;
            switch (work.motion()) {
                case STARTED -> { require(next == 0, "Started work has advanced stop index"); delay = work.startedAtSeconds() - baseline; }
                case AT_STOP -> {
                    require(work.atStop().orElseThrow().equals(trip.calls().get(next).stopId()), "Observed at-stop identity mismatch");
                    delay = work.lastObservedAtSeconds() - add(baseline, trip.calls().get(next).arrivalOffsetSeconds());
                }
                case BETWEEN_STOPS -> {
                    require(next > 0 && work.lastObservedStop().orElseThrow().equals(trip.calls().get(next - 1).stopId()), "Observed departed-call identity mismatch");
                    delay = work.lastObservedAtSeconds() - add(baseline, trip.calls().get(next - 1).departureOffsetSeconds());
                }
                default -> throw new IllegalArgumentException("Unsupported service phase");
            }
            // Do not add either lastFacilityDelay or candidate schedule shifts to this actual baseline residual.
            observed.add(new MixedCapacityProjection.Service(trip, baseline, next, finite(delay), cutoff));
        }
        Set<TripKey> resumable = new HashSet<>(); observed.forEach(service -> resumable.add(service.supply().scheduled().key()));
        var capacityBounds = new LinkedHashMap<Od, Double>();
        for (var service : observed) addCapacityBounds(capacityBounds, service, service, cutoff);
        for (var entry : noAction.entrySet()) if (!observedTrips.contains(entry.getKey())) {
            var trip = supply.trips().get(entry.getKey());
            // Conservative envelope over all registered shifts, including the unchanged commitments.
            double latestOrigin = Math.max(entry.getValue(), add(trip.scheduled().baselineDepartureSeconds(), settings.limits().maximumDelaySeconds()));
            double earliestOrigin = Math.max(0, trip.scheduled().baselineDepartureSeconds() - settings.limits().maximumAdvanceSeconds());
            if (settings.sharedWindow() != null) {
                if (!settings.sharedWindow().contains(trip.scheduled().baselineDepartureSeconds()))
                    earliestOrigin = latestOrigin = entry.getValue();
                else {
                    earliestOrigin = Math.max(earliestOrigin, settings.sharedWindow().startInclusiveSeconds());
                    latestOrigin = Math.min(latestOrigin, Math.nextDown(settings.sharedWindow().endExclusiveSeconds()));
                }
            }
            addCapacityBounds(capacityBounds, new MixedCapacityProjection.Service(trip, earliestOrigin, 0, 0, cutoff),
                    new MixedCapacityProjection.Service(trip, latestOrigin, 0, 0, cutoff), cutoff);
        }
        var protectionBounds = new LinkedHashMap<Od, Double>();
        double lock = add(position.decisionTimeSeconds(), settings.limits().lockWindowSeconds());
        double departureSelectionEnd = add(position.decisionTimeSeconds(), settings.adjustableDepartureLookaheadSeconds());
        if (settings.limits().maximumAdvanceSeconds() > 0 || settings.limits().maximumDelaySeconds() > 0) {
            for (var entry : noAction.entrySet()) {
                double existing = entry.getValue(); var trip = supply.trips().get(entry.getKey());
                if (observedTrips.contains(entry.getKey()) || existing <= lock || existing > departureSelectionEnd) continue;
                if (settings.sharedWindow() != null && !settings.sharedWindow().contains(trip.scheduled().baselineDepartureSeconds())) continue;
                double lower = Math.max(lock, Math.max(0, trip.scheduled().baselineDepartureSeconds() - settings.limits().maximumAdvanceSeconds()));
                double upper = Math.min(departureSelectionEnd, add(trip.scheduled().baselineDepartureSeconds(), settings.limits().maximumDelaySeconds()));
                if (settings.sharedWindow() != null) {
                    lower = Math.max(lower, settings.sharedWindow().startInclusiveSeconds());
                    upper = Math.min(upper, Math.nextDown(settings.sharedWindow().endExclusiveSeconds()));
                }
                if (upper >= lower && upper > lock && (lower != existing || upper != existing))
                    addProtectionBounds(protectionBounds, trip, Math.max(lower, Math.nextUp(lock)), upper);
            }
        }
        var passengers = new ArrayList<MixedCapacityProjection.Passenger>(); var identities = new HashSet<>(released.keySet());
        int currentWaiting = 0, currentRiding = 0, future = 0;
        for (var rider : source.riders().riders().values().stream().sorted(Comparator.comparing(ObservedTransitRiderState.RiderObservation::personId)).toList()) {
            require(rider.firstObservedAtSeconds() <= cutoff && rider.lastObservedAtSeconds() <= cutoff, "Future rider state");
            require(rider.phase() != ObservedTransitRiderState.Phase.UNRESOLVED_ABORT
                    && rider.phase() != ObservedTransitRiderState.Phase.PENDING_CONTINUATION
                    && rider.phase() != ObservedTransitRiderState.Phase.AWAITING_CONTINUATION_EVENT, "Unresolved/through-chain rider state");
            if (rider.kind() != ObservedTransitRiderState.Kind.PASSENGER) continue;
            if (rider.phase() == ObservedTransitRiderState.Phase.WAITING) {
                var episode = demand.currentlyWaitingPassengers().get(rider.personId());
                require(episode != null && episode.occurredAtSeconds() <= cutoff, "Missing actual current passenger waiting episode");
                var od = new Od(episode.stopId(), episode.destinationStopId()); require(legalOd(od), "Observed passenger OD is outside supply");
                addPassenger(passengers, new MixedCapacityProjection.Passenger(unique("observed:" + rider.personId(), identities),
                        od.stopId(), od.destinationStopId(), episode.occurredAtSeconds(), 1,
                        episode.occurredAtSeconds() <= protectionBounds.getOrDefault(od, Double.NEGATIVE_INFINITY), Optional.empty()));
                currentWaiting++;
            } else if (rider.phase() == ObservedTransitRiderState.Phase.RIDING) {
                TripKey trip = ridingTrip(rider, resumable, source, cutoff);
                addPassenger(passengers, new MixedCapacityProjection.Passenger(unique("riding:" + rider.personId(), identities),
                        rider.lastInteractionStopId().orElseThrow(), rider.destinationStopId().orElseThrow(), cutoff, 1, false, Optional.of(trip)));
                currentRiding++;
            } // ALIGHTED: no future plan or next trip is read or invented.
        }
        long prospectiveCount = passengers.size();
        double omittedTailMass = 0;
        for (var entry : demand.arrivalsByOd().entrySet()) if (entry.getValue().ratePerSecond().orElseThrow() > 0) {
            double odEnd = Math.max(cutoff, Math.min(arrivalEnd, capacityBounds.getOrDefault(entry.getKey(), cutoff)));
            int bucketCount = bucketCount(cutoff, odEnd);
            omittedTailMass = add(omittedTailMass, entry.getValue().ratePerSecond().orElseThrow() * (arrivalEnd - odEnd));
            prospectiveCount = Math.addExact(prospectiveCount, bucketCount);
            double bound = protectionBounds.getOrDefault(entry.getKey(), Double.NEGATIVE_INFINITY);
            if (bound > cutoff && bound < odEnd) {
                double index = Math.floor((bound - cutoff) / settings.arrivalCohortStepSeconds());
                if (bound != add(cutoff, index * settings.arrivalCohortStepSeconds())) prospectiveCount = Math.incrementExact(prospectiveCount);
            }
            require(prospectiveCount <= settings.maximumPassengerCohorts(), "Passenger cohort budget exhausted; no truncation permitted");
        }
        for (var entry : demand.arrivalsByOd().entrySet()) {
            Od od = entry.getKey(); require(legalOd(od), "Historical passenger OD is outside supply");
            double rate = entry.getValue().ratePerSecond().orElseThrow();
            if (rate == 0) continue;
            double odEnd = Math.max(cutoff, Math.min(arrivalEnd, capacityBounds.getOrDefault(od, cutoff)));
            int bucketCount = bucketCount(cutoff, odEnd);
            for (int bucket = 0; bucket < bucketCount; bucket++) {
                double left = add(cutoff, bucket * settings.arrivalCohortStepSeconds());
                double right = Math.min(odEnd, add(cutoff, (bucket + 1.0) * settings.arrivalCohortStepSeconds()));
                require(right > left, "Arrival cohort step is not representable");
                double bound = protectionBounds.getOrDefault(od, Double.NEGATIVE_INFINITY);
                double split = bound > left && bound < right ? bound : right;
                addArrivalCohort(passengers, identities, od, bucket + ":0", left, split, rate, split <= bound); future++;
                if (split < right) { addArrivalCohort(passengers, identities, od, bucket + ":1", split, right, rate, false); future++; }
            }
        }
        var freight = new ArrayList<MixedCapacityProjection.Freight>();
        for (var entry : released.entrySet()) {
            String unit = entry.getKey(); var order = entry.getValue(); var journey = source.journeys().journeys().get(unit);
            require(journey.orderId().equals(order.orderId()) && journey.releasedAtSeconds() == order.releasedUnits().get(unit)
                    && journey.lastObservedAtSeconds() <= cutoff, "Journey/release identity or observed time mismatch");
            var od = new Od(order.originStopId(), order.destinationStopId()); require(legalOd(od), "Freight OD is outside supply");
            var estimate = accessEgress.get(od); require(estimate != null, "Missing explicit known-freight OD access/egress estimate");
            var rider = source.riders().riders().get(unit);
            if (rider != null) require(rider.kind() == ObservedTransitRiderState.Kind.FREIGHT_UNIT
                    && rider.destinationStopId().orElseThrow().equals(od.destinationStopId()), "Freight transfer/non-direct destination is unsupported");
            double ready = cutoff; OptionalDouble pendingDelivery = OptionalDouble.empty(); Optional<TripKey> riding = Optional.empty();
            switch (journey.phase()) {
                case RELEASED -> { require(rider == null, "Released freight already has a conflicting PT state"); ready = Math.max(cutoff, add(journey.releasedAtSeconds(), estimate.accessSeconds())); }
                case WAITING -> {
                    require(rider != null && rider.phase() == ObservedTransitRiderState.Phase.WAITING
                            && rider.lastInteractionStopId().orElseThrow().equals(od.stopId()) && journey.firstBoardedAtSeconds().isEmpty(), "Freight waiting transfer/phase unsupported");
                    ready = journey.currentWaitingAtSeconds().orElseThrow(); require(ready <= cutoff, "Future freight waiting fact");
                }
                case RIDING -> {
                    require(rider != null && rider.phase() == ObservedTransitRiderState.Phase.RIDING
                            && rider.lastInteractionStopId().orElseThrow().equals(od.stopId()) && journey.lastAlightedAtSeconds().isEmpty()
                            && rider.vehicleId().equals(journey.ridingVehicleId()), "Freight riding transfer/phase unsupported");
                    riding = Optional.of(ridingTrip(rider, resumable, source, cutoff));
                }
                case ALIGHTED -> {
                    require(rider != null && rider.phase() == ObservedTransitRiderState.Phase.ALIGHTED
                            && rider.lastInteractionStopId().orElseThrow().equals(od.destinationStopId()), "Freight alighting phase/destination mismatch");
                    pendingDelivery = OptionalDouble.of(Math.max(cutoff, add(journey.lastAlightedAtSeconds().orElseThrow(), estimate.egressSeconds())));
                }
                case DELIVERED -> require(journey.firstBoardedAtSeconds().isPresent(), "Observed delivery without direct-PT first boarding is unsupported");
                case ABORTED -> { /* Actual known abort remains an explicit uncompleted unit, never queued again. */ }
            }
            freight.add(new MixedCapacityProjection.Freight(unit, od.stopId(), od.destinationStopId(), journey.releasedAtSeconds(),
                    Math.max(ready, journey.releasedAtSeconds()), order.deadlineSeconds(), estimate.egressSeconds(),
                    journey.firstBoardedAtSeconds(), journey.deliveredAtSeconds(), pendingDelivery,
                    journey.phase() == KnownFreightJourneyState.Phase.ABORTED, riding));
        }
        int protectedCount = 0; double mass = 0;
        for (var passenger : passengers) if (passenger.protectedCohort()) { protectedCount++; mass = add(mass, passenger.mass()); }
        var protectedById = passengers.stream().filter(MixedCapacityProjection.Passenger::protectedCohort)
                .sorted(Comparator.comparing(MixedCapacityProjection.Passenger::id)).toList();
        return new Prepared(source, noAction, projectionId(source, noAction), cutoff, arrivalEnd, List.copyOf(passengers),
                protectedById, List.copyOf(freight), List.copyOf(observed), Set.copyOf(observedTrips), Set.copyOf(released.keySet()),
                Map.copyOf(protectionBounds), Map.copyOf(capacityBounds), omittedTailMass,
                currentWaiting, currentRiding, future, protectedCount, mass, ForecastStatus.READY, "closed-demand-prepared");
    }

    private TripKey ridingTrip(ObservedTransitRiderState.RiderObservation rider, Set<TripKey> resumable,
                              ClosedCoTransportObservationState.Snapshot source, double cutoff) {
        require(rider.lastObservedAtSeconds() <= cutoff && rider.trip().isPresent(), "Initial rider lacks a closed observed trip");
        TripKey key = rider.trip().orElseThrow(); var work = source.services().observedServices().get(key); var trip = supply.trips().get(key);
        require(resumable.contains(key) && work != null && trip != null && rider.vehicleId().orElseThrow().equals(work.vehicleId()),
                "Initial rider is not on a resumable observed service");
        String destination = rider.destinationStopId().orElseThrow(); boolean futureAlight = false;
        for (int call = work.nextStopIndex(); call < trip.calls().size(); call++)
            if (trip.calls().get(call).allowAlighting() && trip.calls().get(call).stopId().equals(destination)) futureAlight = true;
        require(futureAlight, "Initial rider destination has already been passed or is unavailable");
        return key;
    }

    private List<MixedCapacityProjection.Service> candidateServices(Prepared frozen, Map<TripKey, Double> candidate) {
        var services = new ArrayList<>(frozen.observedServices());
        double lock = add(frozen.source().position().decisionTimeSeconds(), settings.limits().lockWindowSeconds());
        for (var entry : candidate.entrySet()) {
            double original = frozen.noAction().get(entry.getKey());
            if (frozen.observedTrips().contains(entry.getKey()) || original <= lock)
                require(entry.getValue() == original, "Candidate changed an observed/locked departure");
            if (!frozen.observedTrips().contains(entry.getKey())) services.add(new MixedCapacityProjection.Service(
                    supply.trips().get(entry.getKey()), entry.getValue(), 0, 0, frozen.cutoff()));
        }
        services.sort(Comparator.comparing(value -> value.supply().scheduled().key(), TRIP_ORDER));
        return List.copyOf(services);
    }

    private Map<TripKey, Double> immutableTimes(Map<TripKey, Double> values) {
        require(values.keySet().equals(supply.trips().keySet()), "Complete exact existing-trip commitment keyset required");
        var ordered = new TreeMap<TripKey, Double>(TRIP_ORDER);
        values.forEach((key, value) -> {
            time(Objects.requireNonNull(value)); double baseline = supply.trips().get(key).scheduled().baselineDepartureSeconds();
            require(baseline - value <= settings.limits().maximumAdvanceSeconds()
                    && value - baseline <= settings.limits().maximumDelaySeconds(), "Commitment exceeds original-baseline displacement limits");
            if (settings.sharedWindow() != null) require(baseline == value
                    || settings.sharedWindow().contains(baseline) && settings.sharedWindow().contains(value),
                    "Commitment violates the registered shared adjustment window");
            ordered.put(key, value);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private Prepared unavailable(ClosedCoTransportObservationState.Snapshot source, Map<TripKey, Double> times,
                                 ForecastStatus status, String reason) {
        var released = new HashSet<String>();
        source.releases().knownOrders().values().forEach(order -> released.addAll(order.releasedUnits().keySet()));
        return new Prepared(source, times, projectionId(source, times), source.position().closedThroughSeconds().orElse(0),
                settings.executionEndSeconds(), List.of(), List.of(), List.of(), List.of(), Set.of(), Set.copyOf(released), Map.of(), Map.of(), 0,
                0, 0, 0, 0, 0, status, reason(reason));
    }
    private Forecast publishFailure(ClosedCoTransportObservationState.Snapshot source, String message, boolean fresh) {
        return publish(unavailable(source, Map.of(), ForecastStatus.UNSUPPORTED_STATE, message),
                new Forecast(ForecastStatus.UNSUPPORTED_STATE, message, Optional.empty()), Optional.empty(), fresh);
    }
    private Forecast publish(Prepared value, Forecast forecast, Optional<MixedCapacityProjection.Result> result, boolean fresh) {
        var counts = new EnumMap<MixedCapacityProjection.Completion, Long>(MixedCapacityProjection.Completion.class);
        result.ifPresent(output -> output.freight().values().forEach(unit -> counts.merge(unit.completion(), 1L, Math::addExact)));
        var position = value.source().position();
        last = new DetailedForecast(forecast, value.id(), position.iteration(), position.epoch(), position.viewSequence(), position.closedPrefixSha256(),
                position.closedThroughSeconds(), position.decisionTimeSeconds(), settings.executionEndSeconds(), value.arrivalEnd(), fresh,
                value.waitingCount(), value.ridingCount(), value.futureCount(), value.protectedCount(), value.protectedMass(), value.releasedUnits().size(),
                value.protectedCount() > 0, value.protectionBounds(), value.capacityBounds(), value.omittedTailMass(),
                settings.sharedWindow() == null ? APPROXIMATION : WINDOWED_APPROXIMATION, counts, result);
        audit.accept(last); return forecast;
    }
    private String projectionId(ClosedCoTransportObservationState.Snapshot source, Map<TripKey, Double> times) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, source.position().iteration() + ":" + source.position().epoch() + ":" + source.position().viewSequence());
            update(digest, source.position().closedPrefixSha256()); update(digest, settings.toString());
            update(digest, Double.toHexString(source.position().decisionTimeSeconds()));
            times.entrySet().stream().sorted(Map.Entry.comparingByKey(TRIP_ORDER)).forEach(entry -> {
                update(digest, entry.getKey().line()); update(digest, entry.getKey().route()); update(digest, entry.getKey().departure());
                update(digest, Double.toHexString(entry.getValue()));
            });
            return "causal-mixed-v1:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII)); digest.update((byte) ':'); digest.update(bytes);
    }
    private void addPassenger(List<MixedCapacityProjection.Passenger> values, MixedCapacityProjection.Passenger passenger) {
        require(values.size() < settings.maximumPassengerCohorts(), "Passenger cohort budget exhausted; no truncation permitted"); values.add(passenger);
    }
    private boolean legalOd(Od od) { return supply.trips().values().stream().anyMatch(trip -> legalOd(trip, od)); }
    private int bucketCount(double cutoff, double end) {
        double count = Math.ceil((end - cutoff) / settings.arrivalCohortStepSeconds());
        require(Double.isFinite(count) && count >= 0 && count <= Integer.MAX_VALUE, "Passenger cohort budget overflow");
        return (int) count;
    }
    /** Matches the kernel's timing envelope; pruning is only after every possible remaining boarding. */
    private void addCapacityBounds(Map<Od, Double> bounds, MixedCapacityProjection.Service earliest,
                                   MixedCapacityProjection.Service latest, double cutoff) {
        double[] low = remainingDepartures(earliest, cutoff), high = remainingDepartures(latest, cutoff);
        var trip = latest.supply();
        for (int index = latest.nextCall(); index < trip.calls().size(); index++) {
            if (low[index] > settings.executionEndSeconds()) break;
            var call = trip.calls().get(index);
            if (call.allowBoarding()) for (int after = index + 1; after < trip.calls().size(); after++) {
                var destination = trip.calls().get(after);
                if (destination.allowAlighting()) bounds.merge(new Od(call.stopId(), destination.stopId()),
                        Math.min(settings.executionEndSeconds(), high[index]), Math::max);
            }
        }
    }
    private static double[] remainingDepartures(MixedCapacityProjection.Service service, double cutoff) {
        var trip = service.supply(); double delay = service.delayEstimateSeconds();
        double[] departures = new double[trip.calls().size()];
        double arrival = Math.max(cutoff, Math.max(service.firstArrivalFloorSeconds(),
                add(add(service.originSeconds(), trip.calls().get(service.nextCall()).arrivalOffsetSeconds()), delay)));
        for (int index = service.nextCall(); index < trip.calls().size(); index++) {
            var call = trip.calls().get(index);
            double departure = Math.max(add(add(service.originSeconds(), call.departureOffsetSeconds()), delay),
                    add(arrival, call.minimumStopDurationSeconds()));
            departures[index] = departure;
            delay = finite(departure - service.originSeconds() - call.departureOffsetSeconds());
            if (index + 1 < trip.calls().size()) arrival = Math.max(departure,
                    add(add(service.originSeconds(), trip.calls().get(index + 1).arrivalOffsetSeconds()), delay));
        }
        return departures;
    }
    /** Full-horizon mass is used for capacity; only this fixed boarding-opportunity envelope enters protection. */
    private void addProtectionBounds(Map<Od, Double> bounds, SingleLineForecastSupply.Trip trip, double earliestOrigin, double latestOrigin) {
        double delay = 0, precedingDeparture = 0;
        for (int index = 0; index < trip.calls().size(); index++) {
            var call = trip.calls().get(index);
            double arrival = Math.max(precedingDeparture, add(call.arrivalOffsetSeconds(), delay));
            double departure = Math.max(add(call.departureOffsetSeconds(), delay), add(arrival, call.minimumStopDurationSeconds()));
            delay = finite(departure - call.departureOffsetSeconds()); precedingDeparture = departure;
            if (!call.allowBoarding() || add(earliestOrigin, departure) > settings.executionEndSeconds()) continue;
            double through = Math.min(settings.executionEndSeconds(), add(latestOrigin, departure));
            for (int after = index + 1; after < trip.calls().size(); after++) {
                var destination = trip.calls().get(after);
                if (destination.allowAlighting()) bounds.merge(new Od(call.stopId(), destination.stopId()), through, Math::max);
            }
        }
    }
    private void addArrivalCohort(List<MixedCapacityProjection.Passenger> passengers, Set<String> identities, Od od,
                                  String bucket, double left, double right, double rate, boolean protectedCohort) {
        double mass = finite(rate * (right - left)); positive(mass);
        double midpoint = add(left, (right - left) / 2);
        require(midpoint > left && midpoint < right, "Arrival cohort midpoint is not representable");
        addPassenger(passengers, new MixedCapacityProjection.Passenger(unique("forecast:" + od.stopId().length() + ":" + od.stopId()
                + ":" + od.destinationStopId().length() + ":" + od.destinationStopId() + ":" + bucket, identities),
                od.stopId(), od.destinationStopId(), midpoint, mass, protectedCohort, Optional.empty()));
    }
    private static boolean legalOd(SingleLineForecastSupply.Trip trip, Od od) {
        boolean board = false;
        for (var call : trip.calls()) {
            if (board && call.stopId().equals(od.destinationStopId()) && call.allowAlighting()) return true;
            if (call.stopId().equals(od.stopId()) && call.allowBoarding()) board = true;
        }
        return false;
    }
    private static String unique(String base, Set<String> used) { String value = base; int suffix = 0; while (!used.add(value)) value = base + ":collision:" + ++suffix; return value; }
    private void requireOwner() { require(Thread.currentThread() == owner, "Wrong forecast owner thread"); }
    private static String reason(String value) { return value == null || value.isBlank() ? "unsupported-forecast-state" : value; }
    private static double add(double a, double b) { return finite(a + b); }
    private static double finite(double value) { require(Double.isFinite(value), "Nonfinite forecast arithmetic"); return value; }
    private static void time(double value) { require(Double.isFinite(value) && value >= 0, "Finite nonnegative time required"); }
    private static void positive(double value) { require(Double.isFinite(value) && value > 0, "Finite positive value required"); }
    private static void fraction(double value) { require(Double.isFinite(value) && value >= 0 && value <= 1, "Explicit fraction in [0,1] required"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
