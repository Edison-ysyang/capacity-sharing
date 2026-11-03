package org.matsim.run.ismt;

import com.google.inject.Singleton;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.PassengerFreightCoTransportModule;
import org.matsim.core.controler.AbstractModule;
import org.matsim.run.r17.PlanAExecutionHorizonControllerListeners;
import org.matsim.run.r17.PlanAExecutionHorizonSwitch;
import org.matsim.run.r17.PlanAFeasibilityAudit;
import org.matsim.run.r17.PlanAInitialSelectedPlanCanonicalizer;

/**
 * Shared passenger/freight execution and Plan-A lifecycle bindings for both ISMT
 * runner families. Installation order matches their original common block.
 *
 * <p>This module does not select an arm, install a boarding window, change supply,
 * enable dispatch, or bind scientific writers. Those remain explicit caller
 * responsibilities. Native capacity, scoring and activation implementations are
 * reused without changing their rules.</p>
 */
public final class SharedIsmtExecutionModule extends AbstractModule {
    private final CoTransportSettings settings;

    /** Read the existing serialized coTransport configuration. */
    public SharedIsmtExecutionModule() {
        this.settings = null;
    }

    /**
     * Materialize explicit settings as in PassengerFreightCoTransportModule.
     * A null value retains that module's serialized-configuration semantics.
     */
    public SharedIsmtExecutionModule(CoTransportSettings settings) {
        this.settings = settings;
    }

    @Override public void install() {
        install(settings == null ? new PassengerFreightCoTransportModule()
                : new PassengerFreightCoTransportModule(settings));
        bind(PlanAFeasibilityAudit.class).in(Singleton.class);
        addControllerListenerBinding().to(PlanAFeasibilityAudit.class);
        addControllerListenerBinding().to(PlanAInitialSelectedPlanCanonicalizer.class);
        bind(PlanAExecutionHorizonSwitch.class).in(Singleton.class);
        addControllerListenerBinding().to(PlanAExecutionHorizonControllerListeners.Before.class).in(Singleton.class);
        addControllerListenerBinding().to(PlanAExecutionHorizonControllerListeners.After.class).in(Singleton.class);
    }
}
