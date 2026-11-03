package org.matsim.run.r17;

import com.google.inject.Inject;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.BeforeMobsimListener;

/**
 * Oppositely prioritized adapters around the shared execution-horizon switch.
 *
 * <p>Controller-listener priorities are phase-independent. Separate adapters
 * are therefore necessary: activation must be the last ordinary
 * {@code BeforeMobsim} action (after Plan A's priority-1000 canonicalizer and
 * priority-0 audit), whereas restoration must be the first ordinary
 * {@code AfterMobsim} action.</p>
 */
public final class PlanAExecutionHorizonControllerListeners {
	public static final double BEFORE_MOBSIM_PRIORITY = -1000.0;
	public static final double AFTER_MOBSIM_PRIORITY = 1000.0;

	private PlanAExecutionHorizonControllerListeners() {
	}

	public static final class Before implements BeforeMobsimListener {
		private final PlanAExecutionHorizonSwitch horizonSwitch;

		@Inject
		public Before(PlanAExecutionHorizonSwitch horizonSwitch) {
			this.horizonSwitch = horizonSwitch;
		}

		@Override
		public double priority() {
			return BEFORE_MOBSIM_PRIORITY;
		}

		@Override
		public void notifyBeforeMobsim(BeforeMobsimEvent event) {
			horizonSwitch.activateExecutionHorizon(event.getIteration());
		}
	}

	public static final class After implements AfterMobsimListener {
		private final PlanAExecutionHorizonSwitch horizonSwitch;

		@Inject
		public After(PlanAExecutionHorizonSwitch horizonSwitch) {
			this.horizonSwitch = horizonSwitch;
		}

		@Override
		public double priority() {
			return AFTER_MOBSIM_PRIORITY;
		}

		@Override
		public void notifyAfterMobsim(AfterMobsimEvent event) {
			horizonSwitch.restoreBehaviouralHorizon(event.getIteration());
		}
	}
}
