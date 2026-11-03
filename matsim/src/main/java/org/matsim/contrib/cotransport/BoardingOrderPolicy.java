package org.matsim.contrib.cotransport;

import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.ActorClass;

import java.util.Objects;

/**
 * Pure ordering seam, separate from service eligibility, capacity and time gates.
 * Choosing an order never makes an otherwise ineligible actor admissible.
 */
public interface BoardingOrderPolicy {
	int priority(ActorClass actorClass);
	boolean usesQueueArrivalTime();

	static BoardingOrderPolicy forMode(CoTransportSettings.BoardingOrder mode) {
		Objects.requireNonNull(mode, "boarding order");
		return switch (mode) {
			case PASSENGER_FIRST -> BuiltIn.PASSENGER_FIRST;
			case FIRST_COME_FIRST_SERVED -> BuiltIn.FIRST_COME_FIRST_SERVED;
		};
	}

	enum BuiltIn implements BoardingOrderPolicy {
		PASSENGER_FIRST {
			@Override public int priority(ActorClass actorClass) {
				return Objects.requireNonNull(actorClass) == ActorClass.FREIGHT ? 0 : 100;
			}
			@Override public boolean usesQueueArrivalTime() { return false; }
		},
		FIRST_COME_FIRST_SERVED {
			@Override public int priority(ActorClass actorClass) {
				Objects.requireNonNull(actorClass);
				return 0;
			}
			@Override public boolean usesQueueArrivalTime() { return true; }
		}
	}
}
