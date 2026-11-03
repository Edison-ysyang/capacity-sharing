package org.matsim.contrib.cotransport;

import org.matsim.core.controler.AbstractModule;

public final class PassengerOnlyAnalysisModule extends AbstractModule {
	@Override
	public void install() {
		bind(PassengerOnlyIterationStats.class).asEagerSingleton();
		addControllerListenerBinding().to(PassengerOnlyIterationStats.class);
	}
}
