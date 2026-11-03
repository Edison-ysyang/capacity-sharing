package org.matsim.core.mobsim.qsim.pt;

import org.matsim.core.events.handler.EventHandler;

/** Observes detail events without counting them as additional native boarding denials. */
public interface TransitBoardingDeniedDetailEventHandler extends EventHandler {
	void handleEvent(TransitBoardingDeniedDetailEvent event);
}
