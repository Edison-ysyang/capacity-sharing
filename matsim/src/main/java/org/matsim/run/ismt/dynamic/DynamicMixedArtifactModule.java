package org.matsim.run.ismt.dynamic;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.cotransport.CoTransportRuntimeMonitor;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.run.ismt.IsmtRunArtifactWriter;
import org.matsim.run.ismt.PassengerPlanFingerprint;
import org.matsim.run.ismt.PassengerScientificFingerprint;
import org.matsim.run.ismt.RunArtifactWriter;
import org.matsim.run.ismt.ScientificRunContext;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Optional v2 scientific output assembly alongside DynamicMixedControllerModule.
 * No legacy ExperimentSpec or legacy Controller module is bound or fabricated.
 * Keeps original monitors, calculations, fingerprint algorithms and listener
 * priorities. Context must agree with the actually bound design, Controller
 * endpoints, split horizons and loaded order count before any writer is obtained.
 * Does not create a formal experiment receipt, dispatch log or reuse certificate.
 */
public final class DynamicMixedArtifactModule extends AbstractModule {
    private final ScientificRunContext requested;

    public DynamicMixedArtifactModule(ScientificRunContext requested) {
        this.requested = Objects.requireNonNull(requested);
        var design = requested.dynamicDesign().orElseThrow(
                () -> new IllegalArgumentException("Dynamic artifacts require an explicit v2 context, never a legacy arm"));
        if (design.arm() != DynamicMixedDesign.Arm.MIXED && design.arm() != DynamicMixedDesign.Arm.DYNAMIC_MIXED)
            throw new IllegalArgumentException("Current dynamic artifact assembly supports the common M/T lifecycle only");
    }

    @Override public void install() {
        bind(RunArtifactWriter.class).to(IsmtRunArtifactWriter.class);
        addControllerListenerBinding().to(IsmtRunArtifactWriter.class);
        addEventHandlerBinding().to(IsmtRunArtifactWriter.class);
        addControllerListenerBinding().to(PassengerScientificFingerprint.class);
        addEventHandlerBinding().to(PassengerScientificFingerprint.class);
        addControllerListenerBinding().to(PassengerPlanFingerprint.class);
    }

    @Provides @Singleton
    ScientificRunContext context(DynamicMixedDesign actual, Config config, FreightOrderRegistry orders) {
        int count = 0;
        for (var ignored : orders.orders()) count++;
        validateContext(requested, actual, config, count);
        return requested;
    }

    static void validateContext(ScientificRunContext requested, DynamicMixedDesign actual, Config config, int orders) {
        if (!requested.dynamicDesign().orElseThrow().equals(actual))
            throw new IllegalStateException("Artifact context differs from the installed dynamic design");
        if (requested.firstIteration() != config.controller().getFirstIteration()
                || requested.finalIteration() != config.controller().getLastIteration())
            throw new IllegalStateException("Artifact iteration endpoints differ from the actual Controller");
        if (requested.orderCount() != orders)
            throw new IllegalStateException("Artifact order count differs from the loaded registry");
        if (config.qsim().getEndTime().isUndefined()
                || Double.compare(config.qsim().getEndTime().seconds(), requested.behaviouralHorizonSeconds()) != 0)
            throw new IllegalStateException("Artifact behavioural horizon differs from the native configuration");
        if (!(config.getModule(PlanAExecutionHorizonConfigGroup.GROUP_NAME) instanceof PlanAExecutionHorizonConfigGroup horizon)
                || Double.compare(horizon.getExecutionEndTime(), requested.executionHorizonSeconds()) != 0)
            throw new IllegalStateException("Artifact execution horizon differs from the materialized split horizon");
    }

    @Provides @Singleton
    IsmtRunArtifactWriter writer(ScientificRunContext context, OutputDirectoryHierarchy io,
            CoTransportRuntimeMonitor monitor, FreightOrderRegistry orders) {
        return new IsmtRunArtifactWriter(context, io, monitor, orders);
    }

    @Provides @Singleton
    PassengerPlanFingerprint plans(ScientificRunContext context, Scenario scenario, FreightOrderRegistry orders,
            OutputDirectoryHierarchy io) {
        return new PassengerPlanFingerprint(context, scenario, orders,
                Path.of(io.getOutputPath()).resolve(PassengerPlanFingerprint.FILE_NAME));
    }

    @Provides @Singleton
    PassengerScientificFingerprint events(ScientificRunContext context, Scenario scenario, FreightOrderRegistry orders,
            OutputDirectoryHierarchy io) {
        return new PassengerScientificFingerprint(context, orders, scenario,
                Path.of(io.getOutputPath()).resolve(PassengerScientificFingerprint.FILE_NAME));
    }
}
