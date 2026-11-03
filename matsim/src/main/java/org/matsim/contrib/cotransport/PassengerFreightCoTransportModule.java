package org.matsim.contrib.cotransport;

import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;

public final class PassengerFreightCoTransportModule extends AbstractModule {
	private final CoTransportSettings settings;

	/** Uses the serialized {@code coTransport} config group. */
	public PassengerFreightCoTransportModule() {
		this.settings = null;
	}

	/**
	 * Legacy programmatic API. The supplied values are materialized into the config group so
	 * they also appear in {@code output_config.xml}.
	 */
	public PassengerFreightCoTransportModule(CoTransportSettings settings) {
		this.settings = settings;
	}

	@Override
	public void install() {
		CoTransportConfigGroup configGroup = ConfigUtils.addOrGetModule(getConfig(), CoTransportConfigGroup.class);
		CoTransportSettings effectiveSettings;
		if (settings == null) {
			effectiveSettings = configGroup.toSettings(getConfig());
		} else {
			configGroup.apply(settings);
			effectiveSettings = settings;
		}
		// Config groups present when the Controler is constructed are already
		// bound by ExplodedConfigModule. Rebinding this instance here causes a
		// Guice BindingAlreadySet failure for serialized R17 configurations.
		// Runtime services consume the immutable CoTransportSettings below.
		bind(CoTransportSettings.class).toInstance(effectiveSettings);
		bind(FreightOrderRegistry.class).asEagerSingleton();
		bind(DedicatedFreightServiceValidator.class).asEagerSingleton();
		bind(CoTransportPhaseController.class).asEagerSingleton();
		bind(CoTransportBoardingPolicy.class).asEagerSingleton();
		bind(CoTransportRuntimeMonitor.class).asEagerSingleton();
		bind(IterationEventFingerprint.class).asEagerSingleton();
		install(new PassengerOnlyAnalysisModule());
		// TripRouter instantiates every registered mode. Use the same passenger
		// factory and deferred freight lifecycle in every arm, including those
		// without a dedicated line, so dormant freight cannot shift routing RNGs.
		if (getConfig().transit().isUseTransit()) {
			bind(PassengerOnlyPtRoutingModuleProvider.class).asEagerSingleton();
			bind(FreightOnlyPtRoutingModuleProvider.class).asEagerSingleton();
			addRoutingModuleBinding("pt").toProvider(PassengerOnlyPtRoutingModuleProvider.class);
			addRoutingModuleBinding("freight_pt").toProvider(FreightOnlyPtRoutingModuleProvider.class);
			addEventHandlerBinding().to(PassengerOnlyPtRoutingModuleProvider.class);
			addEventHandlerBinding().to(FreightOnlyPtRoutingModuleProvider.class);
		}
		addEventHandlerBinding().to(CoTransportRuntimeMonitor.class);
		addEventHandlerBinding().to(IterationEventFingerprint.class);
		addControllerListenerBinding().to(CoTransportPhaseController.class);
		addControllerListenerBinding().to(CoTransportBoardingPolicy.class);
		addControllerListenerBinding().to(CoTransportRuntimeMonitor.class);
		addControllerListenerBinding().to(IterationEventFingerprint.class);
	}
}
