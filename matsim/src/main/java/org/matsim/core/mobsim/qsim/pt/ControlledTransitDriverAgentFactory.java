package org.matsim.core.mobsim.qsim.pt;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.pt.Umlauf;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Limits;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Explicit, currently unwired factory for local-QSim dynamic-control drivers.
 *
 * <p>Disabled, pre-activation, and zero-amplitude paths call the default native
 * factory once, before accessing any active context. They do not construct a
 * controlled driver, composite TripKey, or extra MATSim Id, consume random numbers,
 * or requeue activities. A default driver is never constructed and then wrapped.</p>
 *
 * <p>The active context is supplied lazily once per factory and bound to one local
 * QSim instance. A new factory/context is required for the next QSim iteration.
 * No passenger innovation cutoff is interpreted as an operating-control cutoff.
 * This class does not install dependency-injection bindings, establish complete
 * operating feasibility, or certify OFF/zero scientific-output equivalence.</p>
 */
public final class ControlledTransitDriverAgentFactory implements TransitDriverAgentFactory {
    public record ActiveContext(ControlledTransitDriverAgent.AcceptedCommitmentProvider provider,
                                double initialPhysicalReadySeconds, long epoch) {
        public ActiveContext {
            Objects.requireNonNull(provider, "provider");
            if (!Double.isFinite(initialPhysicalReadySeconds) || initialPhysicalReadySeconds < 0) {
                throw new IllegalArgumentException("Initial physical readiness must be finite and non-negative");
            }
            if (epoch <= 0) throw new IllegalArgumentException("A positive fixed QSim epoch is required");
            if (initialPhysicalReadySeconds == 0.0) initialPhysicalReadySeconds = 0.0;
        }
    }

    private final boolean controlActive;
    private final Supplier<ActiveContext> contextSupplier;
    private final DefaultTransitDriverAgentFactory defaultFactory = new DefaultTransitDriverAgentFactory();
    private ActiveContext context;
    private QSim owner;
    private boolean contextRequested;

    /** No scientific parameter is defaulted. A bypass path may omit the unused context supplier. */
    public ControlledTransitDriverAgentFactory(boolean enabled, int iteration, int activationIteration,
                                               Limits limits, Supplier<ActiveContext> contextSupplier) {
        if (iteration < 0 || activationIteration < 0) {
            throw new IllegalArgumentException("Iteration and activation iteration must be non-negative");
        }
        Objects.requireNonNull(limits, "limits");
        this.controlActive = enabled && iteration >= activationIteration
                && (limits.maximumAdvanceSeconds() > 0 || limits.maximumDelaySeconds() > 0);
        if (controlActive) Objects.requireNonNull(contextSupplier, "Active control requires a lazy context supplier");
        this.contextSupplier = contextSupplier;
    }

    public boolean isControlActive() { return controlActive; }

    @Override
    public AbstractTransitDriverAgent createTransitDriver(Umlauf umlauf, InternalInterface internalInterface,
                                                          TransitStopAgentTracker transitStopAgentTracker) {
        if (!controlActive) {
            return defaultFactory.createTransitDriver(umlauf, internalInterface, transitStopAgentTracker);
        }
        ActiveContext active = contextFor(internalInterface);
        return new ControlledTransitDriverAgent(umlauf, TransportMode.car, transitStopAgentTracker,
                internalInterface, active.provider(), active.initialPhysicalReadySeconds(), active.epoch());
    }

    private synchronized ActiveContext contextFor(InternalInterface internalInterface) {
        Objects.requireNonNull(internalInterface, "internalInterface");
        if (!(internalInterface.getMobsim() instanceof QSim qsim)) {
            throw new IllegalArgumentException("Active controlled factory supports local QSim only");
        }
        if (owner != null && owner != qsim) {
            throw new IllegalStateException("Controlled factory cannot be reused across QSim instances or iterations");
        }
        if (context == null) {
            if (contextRequested) {
                throw new IllegalStateException("Active context initialization already failed; create a new QSim-scoped factory");
            }
            contextRequested = true;
            ActiveContext supplied = Objects.requireNonNull(contextSupplier.get(), "Active context supplier returned null");
            // Publish ownership only after a valid immutable context is available.
            context = supplied;
            owner = qsim;
        }
        return context;
    }
}
