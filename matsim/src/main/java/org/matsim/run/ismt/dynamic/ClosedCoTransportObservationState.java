package org.matsim.run.ismt.dynamic;

import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.KnownFreightReleaseState.Contract;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.RuntimeEvidence;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.StepView;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Unwired, single-observer/epoch composition of closed service, release and rider
 * observations. Full static validation inputs and the producer stay on the assembly
 * side; pass only Snapshot to a policy. No future registry, native object, random
 * generator, mutable timetable or second capacity allocator is exposed there.
 *
 * <p>For live assembly call bindTo after observer attachment and before its first
 * delivery. Binding later also permits ordered offline audit of stored views; it
 * does not certify live consumption. Every
 * view, including empty views, must be consumed in order on this component's owner
 * thread. Identity, iteration/epoch, issued-view sequence, closed-event sequence,
 * watermark, available times and cumulative content hash are checked.
 * The source declares a one-step synchronous or two-step native-drain closure lag;
 * the latter has two initial unknown views and explicit drain-coverage timestamps,
 * not an actual worker receipt clock. The protocol cannot change within the day.
 * All event transitions are staged; only a complete valid delta publishes a new Snapshot.
 * Any failure poisons further consumption, retaining the last accepted state for
 * diagnostic inspection only. A new QSim/day requires a new component.</p>
 *
 * <p>This binds provenance to the specified observer instance; it does not upgrade
 * that observer's incomplete runtime/producer evidence. Nor does consuming a stored
 * view prove the consumer ran at its live decision time: the native control bridge
 * must still validate its actual callback clock. Native capacity, complete terminal
 * scientific accounts, operating constraints, predictions and passenger protection
 * remain separate responsibilities.</p>
 */
public final class ClosedCoTransportObservationState {
    public record PrefixPosition(int iteration, long epoch, long viewSequence, double decisionTimeSeconds,
                                 OptionalDouble closedThroughSeconds, long closedEventCount,
                                 String closedPrefixSha256, RuntimeEvidence sourceEvidence) { }

    /** Constructor is private: arbitrary public event values cannot be imported as a snapshot. */
    public static final class Snapshot {
        private final PrefixPosition position;
        private final ObservedTransitServiceState.State services;
        private final KnownFreightReleaseState.State releases;
        private final ObservedTransitRiderState.State riders;
        private final KnownFreightJourneyState.State journeys;
        private Snapshot(PrefixPosition position, ObservedTransitServiceState.State services,
                         KnownFreightReleaseState.State releases, ObservedTransitRiderState.State riders,
                         KnownFreightJourneyState.State journeys) {
            this.position = position; this.services = services; this.releases = releases; this.riders = riders;
            this.journeys = journeys;
        }
        public PrefixPosition position() { return position; }
        public ObservedTransitServiceState.State services() { return services; }
        public KnownFreightReleaseState.State releases() { return releases; }
        public ObservedTransitRiderState.State riders() { return riders; }
        public KnownFreightJourneyState.State journeys() { return journeys; }
    }

    private final int iteration;
    private final long epoch;
    private final Thread ownerThread = Thread.currentThread();
    private final ObservedTransitServiceState serviceMapper;
    private final KnownFreightReleaseState releaseMapper;
    private final ObservedTransitRiderState riderMapper;
    private final KnownFreightJourneyState journeyMapper;
    private ObservedTransitServiceState.State services;
    private KnownFreightReleaseState.State releases;
    private ObservedTransitRiderState.State riders;
    private KnownFreightJourneyState.State journeys;
    private Object expectedSourceIdentity;
    private StepView lastView;
    private StepView precedingView;
    private Snapshot lastSnapshot;
    private boolean poisoned;

    public ClosedCoTransportObservationState(int iteration, long epoch, List<Service> serviceContracts,
                                            List<Contract> freightContracts, Set<String> passengerIds,
                                            Map<TripKey, List<TripKey>> legalContinuations) {
        this(iteration, epoch, serviceContracts, freightContracts, passengerIds, legalContinuations, List.of());
    }

    /** Explicit journey registration; the legacy overload deliberately supplies unavailable journey evidence. */
    public ClosedCoTransportObservationState(int iteration, long epoch, List<Service> serviceContracts,
                                            List<Contract> freightContracts, Set<String> passengerIds,
                                            Map<TripKey, List<TripKey>> legalContinuations,
                                            List<KnownFreightJourneyState.Contract> journeyContracts) {
        require(iteration >= 0 && epoch > 0, "Explicit iteration and positive epoch required");
        this.iteration = iteration; this.epoch = epoch;
        serviceContracts = List.copyOf(serviceContracts); freightContracts = List.copyOf(freightContracts);
        passengerIds = Set.copyOf(passengerIds);
        Set<String> stops = new HashSet<>();
        for (Service service : serviceContracts) stops.addAll(service.orderedStopIds());
        Set<String> freightUnits = new HashSet<>();
        for (Contract contract : freightContracts) {
            require(stops.contains(contract.originStopId()) && stops.contains(contract.destinationStopId()),
                    "Freight contract must identify registered PT stops, not activity facilities");
            freightUnits.addAll(contract.unitIds());
        }
        require(passengerIds.stream().noneMatch(freightUnits::contains), "Passenger and freight identities overlap");
        KnownFreightJourneyState.validateAgainstReleaseContracts(journeyContracts, freightContracts);
        serviceMapper = new ObservedTransitServiceState(serviceContracts);
        releaseMapper = new KnownFreightReleaseState(freightContracts);
        riderMapper = new ObservedTransitRiderState(serviceContracts, passengerIds, Set.copyOf(freightUnits), legalContinuations);
        journeyMapper = new KnownFreightJourneyState(journeyContracts);
        services = serviceMapper.initialState(); releases = releaseMapper.initialState(); riders = riderMapper.initialState();
        journeys = journeyMapper.initialState();
    }

    /** Explicit binding rejects even an otherwise identical observer from the same iteration/epoch. */
    public synchronized void bindTo(NativeEventPrefixObserver observer) {
        try {
            requireOwner(); requireOpen(); Objects.requireNonNull(observer);
            require(expectedSourceIdentity == null && lastView == null, "Observation source already bound");
            require(observer.sourceIteration() == iteration && observer.sourceEpoch() == epoch, "Wrong source iteration/epoch");
            require(!observer.isPoisoned(), "Cannot bind a poisoned source");
            expectedSourceIdentity = observer.sourceIdentity();
        } catch (RuntimeException | Error failure) { poisoned = true; throw failure; }
    }

    public synchronized Snapshot accept(StepView view) {
        try {
            requireOwner(); requireOpen(); validateNext(Objects.requireNonNull(view));
            var stagedServices = services;
            var stagedReleases = releases;
            var stagedRiders = riders;
            var stagedJourneys = journeys;
            for (ReceivedEvent event : view.newlyClosedEvents()) {
                var beforeServices = stagedServices;
                stagedServices = serviceMapper.apply(stagedServices, event);
                stagedReleases = releaseMapper.apply(stagedReleases, event);
                stagedRiders = riderMapper.apply(stagedRiders, event, beforeServices, stagedServices, stagedReleases);
                stagedJourneys = journeyMapper.apply(stagedJourneys, event, stagedReleases);
            }
            PrefixPosition position = new PrefixPosition(iteration, epoch, view.viewSequence(), view.decisionTimeSeconds(),
                    view.closedThroughSeconds(), view.closedEventCount(), view.closedPrefixSha256(), view.evidence());
            Snapshot next = new Snapshot(position, stagedServices, stagedReleases, stagedRiders, stagedJourneys);
            services = stagedServices; releases = stagedReleases; riders = stagedRiders;
            journeys = stagedJourneys;
            precedingView = lastView; lastView = view; lastSnapshot = next;
            return next;
        } catch (RuntimeException | Error failure) { poisoned = true; throw failure; }
    }

    public synchronized Optional<Snapshot> snapshot() { requireOpen(); return Optional.ofNullable(lastSnapshot); }
    public synchronized boolean isPoisoned() { return poisoned; }
    /** Audit only: after a failure this may be stale and must not be used for new control. */
    public synchronized Optional<Snapshot> lastAcceptedSnapshotForAudit() { return Optional.ofNullable(lastSnapshot); }

    private void validateNext(StepView view) {
        require(expectedSourceIdentity != null && view.sourceIdentity() == expectedSourceIdentity, "Foreign or unbound observation source");
        require(view.iteration() == iteration && view.epoch() == epoch, "Cross-iteration/epoch view");
        long expectedView = lastView == null ? 1 : Math.incrementExact(lastView.viewSequence());
        require(view.viewSequence() == expectedView, "Skipped, replayed or reordered observation view");
        String hash = lastView == null ? NativeEventPrefixObserver.initialPrefixHash(iteration, epoch) : lastView.closedPrefixSha256();
        long eventCount = lastView == null ? 0 : lastView.closedEventCount();
        require(hash.equals(view.precedingClosedPrefixSha256()), "Closed-prefix chain is discontinuous");
        int lag = view.evidence().closureLagSteps();
        require(lag == 1 || lag == 2, "Unsupported native closure lag");
        if (lastView == null) {
            require(view.closedThroughSeconds().isEmpty() && view.newlyClosedEvents().isEmpty()
                    && view.closedEventCount() == 0, "The first source view must be unknown/empty");
        } else {
            require(view.decisionTimeSeconds() > lastView.decisionTimeSeconds(), "Decision time did not advance");
            require(lag == lastView.evidence().closureLagSteps(), "Native closure protocol changed mid-day");
            StepView expectedClosed = lag == 1 ? lastView : precedingView;
            if (expectedClosed == null) require(view.closedThroughSeconds().isEmpty() && view.newlyClosedEvents().isEmpty(),
                    "Parallel source must retain an unknown second view until two native drains");
            else require(view.closedThroughSeconds().isPresent()
                            && view.closedThroughSeconds().getAsDouble() == expectedClosed.decisionTimeSeconds(),
                    "Closed watermark does not match the declared native drain lag");
            if (lag == 2) require(view.evidence().nativeEventDrainChecked(), "Parallel source lacks a successful native event drain");
        }
        for (ReceivedEvent event : view.newlyClosedEvents()) {
            eventCount = Math.incrementExact(eventCount);
            require(event.closedSequence() == eventCount, "Missing or repeated closed event");
            require(view.closedThroughSeconds().isPresent()
                    && event.occurredAtSeconds() <= view.closedThroughSeconds().getAsDouble()
                    && event.availableBySeconds() <= view.decisionTimeSeconds(), "Event is outside the available closed prefix");
            require(event.firstCoveredByDrain().isPresent() == (lag == 2), "Event timing provenance differs from the native source protocol");
            if (lastView != null && lastView.closedThroughSeconds().isPresent()) {
                require(event.occurredAtSeconds() > lastView.closedThroughSeconds().getAsDouble(), "Event revises the preceding closed prefix");
            }
            hash = NativeEventPrefixObserver.extendHash(hash, event);
        }
        require(eventCount == view.closedEventCount() && hash.equals(view.closedPrefixSha256()), "Closed-prefix content/count mismatch");
    }

    private void requireOwner() { require(Thread.currentThread() == ownerThread, "Wrong observation composition thread"); }
    private void requireOpen() { require(!poisoned, "Observation composition is poisoned"); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
