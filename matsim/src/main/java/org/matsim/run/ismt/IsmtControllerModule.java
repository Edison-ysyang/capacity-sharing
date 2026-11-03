package org.matsim.run.ismt;

import com.google.inject.Singleton;
import org.matsim.core.controler.AbstractModule;

/** Common controller bindings for all arms, reusing the established split-horizon Plan-A adapters. */
public final class IsmtControllerModule extends AbstractModule {
    private final ExperimentSpec spec;
    public IsmtControllerModule(ExperimentSpec spec) { this.spec = spec; }

    @Override public void install() {
        bind(ExperimentSpec.class).toInstance(spec);
        install(new SharedIsmtExecutionModule());
        bind(IsmtRunArtifactWriter.class).in(Singleton.class);
        bind(RunArtifactWriter.class).to(IsmtRunArtifactWriter.class);
        addControllerListenerBinding().to(IsmtRunArtifactWriter.class);
        addEventHandlerBinding().to(IsmtRunArtifactWriter.class);
        bind(PassengerScientificFingerprint.class).in(Singleton.class);
        addControllerListenerBinding().to(PassengerScientificFingerprint.class);
        addEventHandlerBinding().to(PassengerScientificFingerprint.class);
        bind(PassengerPlanFingerprint.class).in(Singleton.class);
        addControllerListenerBinding().to(PassengerPlanFingerprint.class);
    }
}
