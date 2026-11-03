package org.matsim.contrib.cotransport;

import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.ActorClass;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.BoardingDecision;
import org.matsim.core.mobsim.qsim.pt.TransitBoardingPolicy.DenialReason;

import java.util.Objects;

/**
 * Stateless policy arithmetic over a native-vehicle snapshot. This object cannot allocate
 * physical places. QSim independently enforces native capacity before consulting the policy.
 */
@FunctionalInterface
public interface BoardingAdmissionPolicy {
	record Context(ActorClass actorClass, boolean freightActive, boolean serviceEligible,
			boolean dedicatedService, int nativeCapacity, int passengersOnBoard, int freightOnBoard,
			int acceptedPassengers, int acceptedFreight, double freightShareCap, double passengerLoadGate) {
		public Context {
			if (nativeCapacity < 0 || passengersOnBoard < 0 || freightOnBoard < 0
					|| acceptedPassengers < 0 || acceptedFreight < 0) {
				throw new IllegalArgumentException("Native capacity and occupant counts must be nonnegative");
			}
			if (!Double.isFinite(freightShareCap) || freightShareCap < 0 || freightShareCap > 1
					|| !Double.isFinite(passengerLoadGate) || passengerLoadGate < 0 || passengerLoadGate > 1) {
				throw new IllegalArgumentException("Admission thresholds must be finite fractions in [0,1]");
			}
		}

		public long projectedPassengers() { return (long) passengersOnBoard + acceptedPassengers; }
		public long projectedFreightBeforeCandidate() { return (long) freightOnBoard + acceptedFreight; }
		public long nativePlacesRemaining() {
			return nativeCapacity - projectedPassengers() - projectedFreightBeforeCandidate();
		}
		public TransitAccessPolicy.Context accessContext() {
			return new TransitAccessPolicy.Context(actorClass, freightActive, serviceEligible, dedicatedService);
		}
	}

	BoardingDecision evaluate(Context context);

	static BoardingAdmissionPolicy mixed() { return compose(TransitAccessPolicy.mixed(), true); }
	static BoardingAdmissionPolicy dedicated() { return compose(TransitAccessPolicy.dedicated(), false); }

	/** Access and mixed-share rules are independent experimental dimensions. */
	static BoardingAdmissionPolicy compose(TransitAccessPolicy accessPolicy, boolean applyMixedLimits) {
		Objects.requireNonNull(accessPolicy);
		return context -> {
			if (context.nativePlacesRemaining() < 1) {
				return BoardingDecision.deny(DenialReason.NATIVE_CAPACITY);
			}
			BoardingDecision access = accessPolicy.evaluate(context.accessContext());
			if (!access.allowed() || context.actorClass() != ActorClass.FREIGHT || !applyMixedLimits) return access;
			long limit = (long) Math.floor(context.nativeCapacity() * context.freightShareCap());
			if (context.projectedFreightBeforeCandidate() + 1 > limit) {
				return BoardingDecision.deny(DenialReason.FREIGHT_SHARE_CAP);
			}
			if ((double) context.projectedPassengers() / context.nativeCapacity() > context.passengerLoadGate()) {
				return BoardingDecision.deny(DenialReason.PASSENGER_LOAD_GATE);
			}
			return BoardingDecision.allow();
		};
	}
}
