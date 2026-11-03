package org.matsim.contrib.cotransport;

import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.ActorClass;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.BoardingDecision;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.DenialReason;

/** Pure service eligibility and two-sided isolation; never reads or reserves capacity. */
@FunctionalInterface
public interface TransitAccessPolicy {
	record Context(ActorClass actorClass, boolean freightActive, boolean serviceEligible,
			boolean dedicatedService) { }

	BoardingDecision evaluate(Context context);

	static TransitAccessPolicy mixed() {
		return context -> basicEligibility(context);
	}

	static TransitAccessPolicy dedicated() {
		return context -> {
			BoardingDecision eligible = basicEligibility(context);
			if (!eligible.allowed()) return eligible;
			boolean freight = context.actorClass() == ActorClass.FREIGHT;
			return freight == context.dedicatedService() ? BoardingDecision.allow()
					: BoardingDecision.deny(DenialReason.ISOLATION_RULE);
		};
	}

	private static BoardingDecision basicEligibility(Context context) {
		if (context.actorClass() == null || context.actorClass() == ActorClass.INVALID) {
			return BoardingDecision.deny(DenialReason.INVALID_AGENT_CLASS);
		}
		if (!context.serviceEligible() || (context.actorClass() == ActorClass.FREIGHT && !context.freightActive())) {
			return BoardingDecision.deny(DenialReason.INELIGIBLE_SERVICE);
		}
		return BoardingDecision.allow();
	}
}
