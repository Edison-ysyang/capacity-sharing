package org.matsim.run.ismt.dynamic;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.multibindings.OptionalBinder;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.core.controler.IterationCounter;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.SimStepEventsRuntimeInspection;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimPreparationCheck;
import org.matsim.core.mobsim.qsim.QSimStepBoundaryCheck;
import org.matsim.core.mobsim.qsim.QSimRuntimeInspection;
import org.matsim.core.mobsim.qsim.pt.AbstractTransitDriverAgent;
import org.matsim.core.mobsim.qsim.pt.ControlledTransitDispatchBridge;
import org.matsim.core.mobsim.qsim.pt.DefaultTransitDriverAgentFactory;
import org.matsim.core.mobsim.qsim.pt.TransitDriverAgentFactory;
import org.matsim.core.mobsim.qsim.pt.TransitStopAgentTracker;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngineI;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimRuntimeInspection;
import org.matsim.pt.Umlauf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

import static org.matsim.run.ismt.dynamic.DispatchCommitmentState.*;
import static org.matsim.run.ismt.dynamic.DispatchConstraintChecker.*;

/**
 * Explicit v2 local-QSim assembly, shared by all four arms and OFF/zero paths.
 * Bind this in the QSim child injector, never reuse a Session across QSim runs.
 * Each session receives the native IterationCounter, builds a fresh ledger and
 * observation state, and leaves population plans, scoring and innovation untouched.
 * The caller still installs the common passenger/freight module and registers the
 * actual per-iteration demand/supply and innovation protocol. This module does not
 * implement the old experiment runner's activation, artifacts or formal provenance.
 *
 * <p>Only an active T requests a policy. It receives immutable closed observations
 * and commitments, not QSim, future realized demand or mutable timetable objects.
 * Proposed existing departure changes pass the registered bridge constraints;
 * infeasible proposals are recorded as rejected without relaxing constraints.
 * Passenger protection/forecast/objective semantics belong to the explicitly
 * supplied policy and are NOT certified merely by installing this module.
 *
 * <p>Native preparation hooks establish the real start/prepared boundaries. Runtime
 * inventories are compared again before every policy call. They are NOT a seal,
 * intervening-mutation history or complete producer/callback certification. The
 * synchronous one-runner fixture and exact sim-step-parallel/single-event-worker
 * path are separate explicit runtime modes. The latter retains the configured QSim
 * runner count (including the historical seven) and uses two-step native-drain
 * observation lag. Both install an opt-in native runner-completion check before
 * changing departure queues. No setting is silently changed in T and no formal
 * experiment is authorized by this API.</p>
 */
public final class DynamicMixedQSimModule extends AbstractQSimModule {
    public record Inputs(List<ScheduledTrip> baselineTrips,
                         List<ObservedTransitServiceState.Service> services,
                         List<KnownFreightReleaseState.Contract> freightContracts,
                         Set<String> passengerIds, Set<String> expectedPopulationIds,
                         List<ControlPoint> controlPoints, List<VehicleConnection> connections,
                         double startTimeSeconds, List<KnownFreightJourneyState.Contract> journeyContracts,
                         RollingDepartureCandidates.AdjustmentWindow adjustmentWindow) {
        public Inputs(List<ScheduledTrip> baselineTrips, List<ObservedTransitServiceState.Service> services,
                      List<KnownFreightReleaseState.Contract> freightContracts,
                      Set<String> passengerIds, Set<String> expectedPopulationIds,
                      List<ControlPoint> controlPoints, List<VehicleConnection> connections,
                      double startTimeSeconds, List<KnownFreightJourneyState.Contract> journeyContracts) {
            this(baselineTrips, services, freightContracts, passengerIds, expectedPopulationIds,
                    controlPoints, connections, startTimeSeconds, journeyContracts, null);
        }
        /** Compatibility path: absent journey registration is unavailable evidence, not zero completion. */
        public Inputs(List<ScheduledTrip> baselineTrips, List<ObservedTransitServiceState.Service> services,
                      List<KnownFreightReleaseState.Contract> freightContracts,
                      Set<String> passengerIds, Set<String> expectedPopulationIds,
                      List<ControlPoint> controlPoints, List<VehicleConnection> connections,
                      double startTimeSeconds) {
            this(baselineTrips, services, freightContracts, passengerIds, expectedPopulationIds,
                    controlPoints, connections, startTimeSeconds, List.of());
        }
        public Inputs {
            baselineTrips = List.copyOf(baselineTrips); services = List.copyOf(services);
            freightContracts = List.copyOf(freightContracts); passengerIds = Set.copyOf(passengerIds);
            expectedPopulationIds = Set.copyOf(expectedPopulationIds);
            controlPoints = List.copyOf(controlPoints); connections = List.copyOf(connections);
            journeyContracts = List.copyOf(journeyContracts);
            KnownFreightJourneyState.validateAgainstReleaseContracts(journeyContracts, freightContracts);
            if (!Double.isFinite(startTimeSeconds) || startTimeSeconds < 0)
                throw new IllegalArgumentException("Explicit finite nonnegative start required");
            if (!expectedPopulationIds.containsAll(passengerIds))
                throw new IllegalArgumentException("Passenger IDs are outside the expected population");
            for (var contract : freightContracts) if (!expectedPopulationIds.containsAll(contract.unitIds()))
                throw new IllegalArgumentException("Freight unit IDs are outside the expected population");
        }
    }

    @FunctionalInterface public interface Policy {
        Map<TripKey, Double> propose(ClosedCoTransportObservationState.Snapshot observations,
                                    DispatchCommitmentState.Snapshot commitments);
    }
    @FunctionalInterface public interface PolicyFactory { Policy create(int iteration); }

    /** Value-only closed-prefix evidence, emitted before this step's policy proposal. */
    public record ClosedEvidence(NativeEventPrefixObserver.StepView view, Snapshot commitmentsBeforePolicy) {
        public ClosedEvidence { Objects.requireNonNull(view); Objects.requireNonNull(commitmentsBeforePolicy); }
    }

    /**
     * Trusted assembly hooks, never supplied to the policy. The verifier runs at the
     * real post-prepare barrier and must only read actual native plans. The evidence
     * sink receives closed facts only, not pending events or a mutable QSim. Callbacks
     * do not themselves certify input coverage, terminal completeness or immutability.
     */
    public record AssemblyCallbacks(BiConsumer<Integer, QSim> preparedVerifier, Consumer<ClosedEvidence> closedEvidence) {
        public AssemblyCallbacks { Objects.requireNonNull(preparedVerifier); Objects.requireNonNull(closedEvidence); }
        public static AssemblyCallbacks none() { return new AssemblyCallbacks((iteration, qsim) -> { }, evidence -> { }); }
    }

    /** Diagnostics only: neither this value nor phase eligibility is a scientific validation result. */
    public record DayReceipt(int iteration, DynamicMixedDesign.Phase phase, boolean preparedInputVerified,
                             long closedViewCount, long policyCalls, int controlledDrivers, int nativeDrivers,
                             long committedChanges, boolean poisoned, boolean terminalPrefixCertified,
                             boolean registrationSealed, boolean allProducersCertified,
                             String schemaId, DynamicMixedDesign.Arm arm, long epoch,
                             int qsimRunnerCount, String eventManagerClass, int closureLagSteps,
                             boolean nativeEventsProcessingFinished) { }

    private record Binding(DynamicMixedDesign design, IntFunction<Inputs> inputs,
                           PolicyFactory policyFactory, Consumer<Session> sessions, AssemblyCallbacks callbacks) { }
    private final Binding binding;

    public DynamicMixedQSimModule(DynamicMixedDesign design, IntFunction<Inputs> inputs,
                                  PolicyFactory policyFactory, Consumer<Session> sessions) {
        this(design, inputs, policyFactory, sessions, AssemblyCallbacks.none());
    }

    public DynamicMixedQSimModule(DynamicMixedDesign design, IntFunction<Inputs> inputs,
                                  PolicyFactory policyFactory, Consumer<Session> sessions, AssemblyCallbacks callbacks) {
        binding = new Binding(Objects.requireNonNull(design), Objects.requireNonNull(inputs),
                policyFactory, Objects.requireNonNull(sessions), Objects.requireNonNull(callbacks));
        if (design.controlEnabled() && (design.limits().maximumAdvanceSeconds() > 0
                || design.limits().maximumDelaySeconds() > 0)) Objects.requireNonNull(policyFactory, "Active T requires an explicit policy factory");
    }

    @Override protected void configureQSim() {
        bind(Binding.class).toInstance(binding);
        bind(Session.class).in(Singleton.class);
        bind(QSimPreparationCheck.class).to(Session.class);
        bind(QSimStepBoundaryCheck.class).to(Session.class);
        OptionalBinder.newOptionalBinder(binder(), TransitDriverAgentFactory.class).setBinding().to(Session.class);
    }

    /** Assembly-only handle. Public accessors return audit values, never mutable native components. */
    public static final class Session implements QSimPreparationCheck, QSimStepBoundaryCheck, TransitDriverAgentFactory {
        private final Binding binding;
        private final int iteration;
        private final DynamicMixedDesign.Phase phase;
        private final DefaultTransitDriverAgentFactory nativeFactory = new DefaultTransitDriverAgentFactory();
        private QSim owner;
        private Thread thread;
        private Inputs inputs;
        private DispatchCommitmentState ledger;
        private DispatchCommitmentState.Snapshot initial;
        private ClosedCoTransportObservationState observations;
        private ClosedCoTransportObservationState.Snapshot closed;
        private ControlledTransitDispatchBridge bridge;
        private NativeEventPrefixObserver observer;
        private Policy policy;
        private QNetsimEngineI engine;
        private EventsManager events;
        private int configuredRunners;
        private QNetsimRuntimeInspection.ExecutionBarrier executionBarrier;
        private SimStepEventsRuntimeInspection.Snapshot preparedParallelEvents;
        private QSimRuntimeInspection.Snapshot preparedComponents;
        private EventsManagerImpl.RuntimeInspectionSnapshot preparedHandlers;
        private PreparedQSimInputAudit.Report inputReport;
        private boolean prepared, poisoned, inClosedDelivery, nativeEventsProcessingFinished;
        private long views, policyCalls;
        private int controlledDrivers, nativeDrivers;

        @Inject Session(Binding binding, IterationCounter counter) {
            this.binding = binding;
            iteration = Objects.requireNonNull(counter.getIterationNumber());
            phase = binding.design().phaseAt(iteration);
        }

        @Override public void beforePreparation(QSim qsim) {
            require(QSimRuntimeInspection.isAtPreparationStart(qsim), "Not the native preparation-start callback");
            require(owner == null, "Session cannot be reused for a second QSim/day");
            owner = qsim; thread = Thread.currentThread();
            try {
                inputs = Objects.requireNonNull(binding.inputs().apply(iteration), "Missing explicit day inputs");
                validateSupply();
                ledger = new DispatchCommitmentState(inputs.baselineTrips(), binding.design().limits());
                initial = ledger.beginIteration(iteration, inputs.startTimeSeconds(), phase.controlEligible());
                observations = new ClosedCoTransportObservationState(iteration, initial.version().epoch(), inputs.services(),
                        inputs.freightContracts(), inputs.passengerIds(), Map.of(), inputs.journeyContracts());
                if (phase.controlEligible()) {
                    policy = Objects.requireNonNull(binding.policyFactory().create(iteration), "Null active policy");
                    bridge = new ControlledTransitDispatchBridge(owner, ledger, binding.design().protocol().activationIteration(),
                            inputs.controlPoints(), inputs.connections(), (active, now) -> submitPolicy(active, now), inputs.adjustmentWindow());
                }
                observer = NativeEventPrefixObserver.attachForPreparation(owner, iteration, initial.version().epoch(),
                        inputs.startTimeSeconds(), this::acceptClosed);
                observations.bindTo(observer);
                binding.sessions().accept(this);
            } catch (RuntimeException failure) {
                poisoned = true;
                // Failure before native preparation may otherwise leave a partially attached handler.
                if (observer != null) observer.close();
                throw failure;
            }
        }

        @Override public void afterPrepared(QSim qsim) {
            require(QSimRuntimeInspection.isAtPreparationCheck(qsim), "Not the native post-prepare callback");
            requireOwner(qsim); require(!prepared, "Duplicate prepared callback");
            try {
                require(owner.getSimTimer().getTimeOfDay() == inputs.startTimeSeconds(), "Actual QSim start differs from registered start");
                inputReport = PreparedQSimInputAudit.inspect(owner, inputs.expectedPopulationIds());
                inputReport.requireSupportedInput();
                binding.callbacks().preparedVerifier().accept(iteration, owner);
                validateSupply();
                engine = owner.getChildInjector().getInstance(QNetsimEngineI.class);
                events = owner.getEventsManager();
                configuredRunners = owner.getScenario().getConfig().qsim().getNumberOfThreads();
                preparedComponents = QSimRuntimeInspection.inspect(owner);
                if (observer.usesParallelDrainBoundary()) {
                    preparedParallelEvents = SimStepEventsRuntimeInspection.inspect(events, List.of(Event.class));
                } else {
                    require(events.getClass() == EventsManagerImpl.class, "Exact synchronous manager required");
                    preparedHandlers = ((EventsManagerImpl) events).inspectRuntimeRegistrations(List.of(Event.class));
                }
                executionBarrier = QNetsimRuntimeInspection.trackPreparedExecution(owner, engine);
                executionBarrier.requirePrepared(owner);
                if (bridge != null) bridge.bindExecutionBarrier(executionBarrier);
                requireRuntime();
                prepared = true;
            } catch (RuntimeException | Error failure) { poisoned = true; throw failure; }
        }

        @Override public void afterEventsProcessed(QSim qsim, double time) {
            requireOwner(qsim);
            require(QSimRuntimeInspection.isAtEventsProcessed(qsim) && prepared, "Not a prepared native event-drain callback");
            try { observer.afterNativeEventsProcessed(qsim, time); }
            catch (RuntimeException failure) { poisoned = true; throw failure; }
        }

        @Override public void afterEventsFinished(QSim qsim) {
            requireOwner(qsim);
            require(QSimRuntimeInspection.isAtEventsFinished(qsim), "Not a native event-finish callback");
            if (observer != null) observer.afterNativeEventsFinished(qsim);
            nativeEventsProcessingFinished = true;
        }

        @Override public AbstractTransitDriverAgent createTransitDriver(Umlauf umlauf, InternalInterface internal,
                                                                         TransitStopAgentTracker tracker) {
            requireOwner(internal.getMobsim());
            require(!prepared && !poisoned, "Driver construction outside day preparation");
            if (bridge == null) {
                var driver = nativeFactory.createTransitDriver(umlauf, internal, tracker);
                nativeDrivers++; return driver;
            }
            var driver = bridge.createTransitDriver(umlauf, internal, tracker);
            controlledDrivers++; return driver;
        }

        private void acceptClosed(NativeEventPrefixObserver.StepView view) {
            requireOwner(owner);
            require(prepared && !poisoned && !inClosedDelivery, "Day is unprepared, poisoned or reentered");
            inClosedDelivery = true;
            try {
                executionBarrier.requireBeforeStep(owner, view.decisionTimeSeconds());
                requireRuntime();
                closed = observations.accept(view);
                require(phase.freightTreatmentActive() || closed.releases().knownOrders().isEmpty(),
                        "Freight release leaked into a no-freight/common-history phase");
                for (var event : view.newlyClosedEvents()) {
                    if (!event.eventClass().equals(TransitDriverStartsEvent.class.getName())) continue;
                    var attributes = event.attributes();
                    TripKey trip = new TripKey(attributes.get(TransitDriverStartsEvent.ATTRIBUTE_TRANSIT_LINE_ID),
                            attributes.get(TransitDriverStartsEvent.ATTRIBUTE_TRANSIT_ROUTE_ID),
                            attributes.get(TransitDriverStartsEvent.ATTRIBUTE_DEPARTURE_ID));
                    // The service mapper already validates reserved, non-service Wenden events.
                    if (ledger.baseline().containsKey(trip)) ledger.recordStarted(trip, event.occurredAtSeconds(), view.decisionTimeSeconds());
                }
                ledger.advanceTo(view.decisionTimeSeconds());
                views++;
                binding.callbacks().closedEvidence().accept(new ClosedEvidence(view, ledger.snapshot()));
                if (bridge != null) bridge.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(owner, view.decisionTimeSeconds()));
            } catch (RuntimeException | Error failure) { poisoned = true; throw failure; }
            finally { inClosedDelivery = false; }
        }

        private void submitPolicy(ControlledTransitDispatchBridge active, double now) {
            require(inClosedDelivery && closed != null && closed.position().decisionTimeSeconds() == now,
                    "Policy is outside the native closed-observation delivery");
            policyCalls++;
            Map<TripKey, Double> proposal = Map.copyOf(Objects.requireNonNull(policy.propose(closed, ledger.snapshot())));
            if (proposal.isEmpty()) return;
            var cutoff = closed.position().closedThroughSeconds();
            require(cutoff.isPresent(), "An active proposal requires an established closed observation cutoff");
            Batch batch = new Batch(ledger.snapshot().version(), now,
                    List.of(new ObservedFact("closed-prefix-" + closed.position().closedPrefixSha256(), cutoff.getAsDouble(), now)), proposal);
            try { active.submit(batch); }
            catch (ControlledTransitDispatchBridge.PrecommitRejected rejected) {
                // The bridge retains the precise rejected proposal/constraints. No relaxation or success is inferred.
            }
        }

        private void requireRuntime() {
            require(preparedComponents.activeNetworkEngineIs(engine)
                    && QSimRuntimeInspection.activeNetworkEngineIs(owner, engine)
                    && QSimRuntimeInspection.hasSingleNetworkEngineRegistration(owner, engine), "Primary engine identity/multiplicity changed");
            var runners = QNetsimRuntimeInspection.inspect(engine);
            require(configuredRunners > 0 && owner.getScenario().getConfig().qsim().getNumberOfThreads() == configuredRunners
                    && runners.constructionThreadCount() == configuredRunners && runners.constructionRunnerCount() == configuredRunners
                    && runners.actualRunnerCount().isPresent() && runners.actualRunnerCount().getAsInt() == configuredRunners,
                    "Prepared native engine differs from the registered materialized runner count");
            require(owner.getEventsManager() == events, "Event manager changed");
            require(preparedComponents.sameComponentsAs(QSimRuntimeInspection.inspect(owner)), "QSim component identity/order changed since preparation");
            if (preparedParallelEvents != null) {
                var current = SimStepEventsRuntimeInspection.inspect(events, List.of(Event.class));
                require(current.parallelMode() && !current.failed() && current.constructionWorkerCount() == 1
                        && current.materializedWorkerCount().orElse(-1) == 1, "Native single event worker is not active/healthy");
                require(preparedParallelEvents.sameRuntimeAs(current) && preparedParallelEvents.sameRegistrationsAs(current),
                        "Event worker generation or handler registration changed since preparation");
            } else require(preparedHandlers.sameRegistrationsAs(((EventsManagerImpl) events).inspectRuntimeRegistrations(List.of(Event.class))),
                        "Event-handler identity/order changed since preparation");
        }

        private void validateSupply() {
            var actual = new LinkedHashMap<TripKey, ScheduledTrip>();
            var services = new LinkedHashMap<TripKey, ObservedTransitServiceState.Service>();
            for (var line : owner.getScenario().getTransitSchedule().getTransitLines().values()) {
                for (var route : line.getRoutes().values()) {
                    List<String> stops = route.getStops().stream().map(stop -> stop.getStopFacility().getId().toString()).toList();
                    for (var departure : route.getDepartures().values()) {
                        TripKey key = new TripKey(line.getId().toString(), route.getId().toString(), departure.getId().toString());
                        String vehicle = Objects.requireNonNull(departure.getVehicleId()).toString();
                        require(actual.putIfAbsent(key, new ScheduledTrip(key, vehicle, departure.getDepartureTime())) == null, "Duplicate actual trip");
                        services.put(key, new ObservedTransitServiceState.Service(key, vehicle, stops));
                    }
                }
            }
            var expected = new LinkedHashMap<TripKey, ScheduledTrip>();
            for (var trip : inputs.baselineTrips()) require(expected.putIfAbsent(trip.key(), trip) == null, "Duplicate registered trip");
            var expectedServices = new LinkedHashMap<TripKey, ObservedTransitServiceState.Service>();
            for (var service : inputs.services()) require(expectedServices.putIfAbsent(service.trip(), service) == null, "Duplicate registered service");
            require(actual.equals(expected) && services.equals(expectedServices), "Registered baseline/services differ from actual day supply");
        }

        private void requireOwner(Object qsim) {
            require(owner != null && owner == qsim && Thread.currentThread() == thread, "Wrong QSim/day/thread");
        }
        private static void require(boolean condition, String message) {
            if (!condition) throw new IllegalStateException(message);
        }

        public int iteration() { return iteration; }
        public DynamicMixedDesign.Phase phase() { return phase; }
        public DispatchCommitmentState.Snapshot initialCommitments() { return Objects.requireNonNull(initial); }
        public DispatchCommitmentState.Snapshot snapshot() { return Objects.requireNonNull(ledger).snapshot(); }
        public PreparedQSimInputAudit.Report inputReport() { return Objects.requireNonNull(inputReport); }
        public ClosedCoTransportObservationState.Snapshot closedSnapshot() { return Objects.requireNonNull(closed); }
        public List<ControlledTransitDispatchBridge.ApplicationAudit> applications() { return bridge == null ? List.of() : bridge.history(); }
        public List<DecisionRecord> commitmentHistory() { return Objects.requireNonNull(ledger).history(); }
        public NativeEventPrefixObserver.CloseReceipt observationCloseReceipt() { return Objects.requireNonNull(observer).closeReceipt(); }

        /** Available after observer cleanup only; native terminal tail is explicitly not certified. */
        public DayReceipt receipt() {
            var close = Objects.requireNonNull(observer).closeReceipt();
            boolean nativeFailed = executionBarrier != null && executionBarrier.snapshot().phase()
                    == QNetsimRuntimeInspection.ExecutionPhase.FAILED;
            if (events != null && observer.usesParallelDrainBoundary()) nativeFailed |= SimStepEventsRuntimeInspection.inspect(events).failed();
            return new DayReceipt(iteration, phase, prepared && inputReport.verifiedSupportedInput(), views, policyCalls,
                    controlledDrivers, nativeDrivers, ledger.history().stream().mapToLong(record -> record.changes().size()).sum(),
                    poisoned || close.poisoned() || observer.isPoisoned() || nativeFailed || bridge != null && bridge.isPoisoned(), false, false, false,
                    binding.design().schemaId(), binding.design().arm(), initial.version().epoch(), configuredRunners,
                    events == null ? "unprepared" : events.getClass().getName(), observer.usesParallelDrainBoundary() ? 2 : 1,
                    nativeEventsProcessingFinished);
        }
    }
}
