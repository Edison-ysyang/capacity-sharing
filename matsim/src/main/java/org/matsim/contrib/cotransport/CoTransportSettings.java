package org.matsim.contrib.cotransport;

import java.nio.file.Path;

/**
 * Immutable parameter set of the passenger-freight co-transport extension.
 *
 * <p>All behavioural parameters of the co-transport mechanism are collected here so that a
 * scenario run is fully described by (a) the MATSim {@code Config}, (b) the freight order CSV,
 * and (c) one instance of this class. This supports reproducible experiment registries.</p>
 *
 * <p>The telescoping constructors retain source compatibility, but new defaults require
 * whole-order boarding. Reproducing historical independently split units requires an explicit
 * {@code Builder.freightBoardingMode(UNIT_SPLIT)}; a missing parameter must not silently be
 * interpreted as the old mechanism. New code should prefer {@link Builder}.</p>
 */
public final class CoTransportSettings {
	/** Boarding order selected before startup and held fixed for the complete run. */
	public enum BoardingOrder {
		PASSENGER_FIRST,
		FIRST_COME_FIRST_SERVED
	}

	/** Capacity representation is independent of passenger/freight queue precedence. */
	public enum FreightBoardingMode {
		WHOLE_ORDER,
		UNIT_SPLIT
	}

	/** Whether a ready order that cannot fit blocks following candidates in its queue. */
	public enum WholeOrderQueuePolicy {
		STRICT_FIFO,
		FIT_FIRST
	}

	public enum ArmMode {
		ALWAYS_ACTIVE,
		ITERATION_WINDOW,
		DISABLED
	}

	private final Path freightOrdersCsv;
	private final Path baselineTransitScheduleFile;
	private final Path activatedTransitScheduleFile;
	private final Path activatedTransitVehiclesFile;
	private final ArmMode armMode;
	private final BoardingOrder boardingOrder;
	private final FreightBoardingMode freightBoardingMode;
	private final WholeOrderQueuePolicy wholeOrderQueuePolicy;
	private final boolean compressReports;
	private final int activationIteration;
	private final int deactivationIteration;
	private final int innovationOffIteration;
	private final int innovationReopenIteration;
	private final int innovationFinalOffIteration;
	private final boolean manageInnovationSchedule;
	private final double freightEquivalentPassengersPerM3;
	private final double comfortThreshold;
	private final double hardCapacityRatio;
	private final double passengerLoadGate;
	private final double freightShareCap;
	private final double freightHandlingDwellSeconds;
	private final double freightLatePenaltyPerMinute;
	private final double deniedBoardingPenalty;
	private final double freightIncompletePenaltyPerUnit;
	private final double freightServiceWindowStartTime;
	private final double freightServiceWindowEndTime;
	private final double passengerDeniedBoardingPenalty;
	private final double passengerPhysicalCrowdingPenaltyPerMinute;
	private final double passengerFreightCoPresencePenaltyPerMinute;
	private final String freightActivityPrefix;
	private final String freightShadowToken;
	private final String dedicatedFreightLineId;

	public CoTransportSettings(Path freightOrdersCsv) {
		this(freightOrdersCsv, 4.0, 0.75, 0.95, 0.35, 8.0, 25.0, Double.NaN, Double.NaN, 4.0, 0.18, "freight_", "_cap_", null);
	}

	public CoTransportSettings(Path freightOrdersCsv, double freightEquivalentPassengersPerM3, double comfortThreshold,
			double hardCapacityRatio, double freightLatePenaltyPerMinute, double deniedBoardingPenalty,
			double passengerCrowdingPenaltyPerMinute, String freightActivityPrefix, String freightShadowToken) {
		this(freightOrdersCsv, freightEquivalentPassengersPerM3, comfortThreshold, hardCapacityRatio,
				freightLatePenaltyPerMinute, deniedBoardingPenalty, 25.0, Double.NaN, Double.NaN, 4.0, passengerCrowdingPenaltyPerMinute,
				freightActivityPrefix, freightShadowToken, null);
	}

	public CoTransportSettings(Path freightOrdersCsv, double freightEquivalentPassengersPerM3, double comfortThreshold,
			double hardCapacityRatio, double freightLatePenaltyPerMinute, double deniedBoardingPenalty,
			double passengerDeniedBoardingPenalty, double passengerCrowdingPenaltyPerMinute,
			String freightActivityPrefix, String freightShadowToken) {
		this(freightOrdersCsv, freightEquivalentPassengersPerM3, comfortThreshold, hardCapacityRatio,
				freightLatePenaltyPerMinute, deniedBoardingPenalty, 25.0, Double.NaN, Double.NaN, passengerDeniedBoardingPenalty,
				passengerCrowdingPenaltyPerMinute, freightActivityPrefix, freightShadowToken, null);
	}

	public CoTransportSettings(Path freightOrdersCsv, double freightEquivalentPassengersPerM3, double comfortThreshold,
			double hardCapacityRatio, double freightLatePenaltyPerMinute, double deniedBoardingPenalty,
			double freightIncompletePenaltyPerUnit, double passengerDeniedBoardingPenalty, double passengerCrowdingPenaltyPerMinute,
			String freightActivityPrefix, String freightShadowToken, String dedicatedFreightLineId) {
		this(freightOrdersCsv, freightEquivalentPassengersPerM3, comfortThreshold, hardCapacityRatio,
				freightLatePenaltyPerMinute, deniedBoardingPenalty, freightIncompletePenaltyPerUnit,
				Double.NaN, Double.NaN, passengerDeniedBoardingPenalty, passengerCrowdingPenaltyPerMinute,
				freightActivityPrefix, freightShadowToken, dedicatedFreightLineId);
	}

	public CoTransportSettings(Path freightOrdersCsv, double freightEquivalentPassengersPerM3, double comfortThreshold,
			double hardCapacityRatio, double freightLatePenaltyPerMinute, double deniedBoardingPenalty,
			double freightIncompletePenaltyPerUnit, double freightServiceWindowStartTime, double freightServiceWindowEndTime,
			double passengerDeniedBoardingPenalty, double passengerCrowdingPenaltyPerMinute,
			String freightActivityPrefix, String freightShadowToken, String dedicatedFreightLineId) {
		this(freightOrdersCsv, null, null, null, ArmMode.ALWAYS_ACTIVE, BoardingOrder.PASSENGER_FIRST,
				FreightBoardingMode.WHOLE_ORDER, WholeOrderQueuePolicy.STRICT_FIFO,
				0, -1, 150, 180, 300, false,
				freightEquivalentPassengersPerM3, comfortThreshold, hardCapacityRatio, 1.0, 1.0, 0.0,
				freightLatePenaltyPerMinute, deniedBoardingPenalty, freightIncompletePenaltyPerUnit,
				freightServiceWindowStartTime, freightServiceWindowEndTime, passengerDeniedBoardingPenalty,
				passengerCrowdingPenaltyPerMinute, passengerCrowdingPenaltyPerMinute,
				freightActivityPrefix, freightShadowToken, dedicatedFreightLineId, false);
	}

	private CoTransportSettings(Path freightOrdersCsv, Path baselineTransitScheduleFile,
			Path activatedTransitScheduleFile, Path activatedTransitVehiclesFile, ArmMode armMode,
			BoardingOrder boardingOrder,
			FreightBoardingMode freightBoardingMode, WholeOrderQueuePolicy wholeOrderQueuePolicy,
			int activationIteration, int deactivationIteration, int innovationOffIteration,
			int innovationReopenIteration, int innovationFinalOffIteration, boolean manageInnovationSchedule,
			double freightEquivalentPassengersPerM3,
			double comfortThreshold, double hardCapacityRatio, double passengerLoadGate, double freightShareCap,
			double freightHandlingDwellSeconds,
			double freightLatePenaltyPerMinute, double deniedBoardingPenalty,
			double freightIncompletePenaltyPerUnit, double freightServiceWindowStartTime,
			double freightServiceWindowEndTime, double passengerDeniedBoardingPenalty,
			double passengerPhysicalCrowdingPenaltyPerMinute,
			double passengerFreightCoPresencePenaltyPerMinute, String freightActivityPrefix,
			String freightShadowToken, String dedicatedFreightLineId, boolean compressReports) {
		this.freightOrdersCsv = freightOrdersCsv;
		this.baselineTransitScheduleFile = baselineTransitScheduleFile;
		this.activatedTransitScheduleFile = activatedTransitScheduleFile;
		this.activatedTransitVehiclesFile = activatedTransitVehiclesFile;
		this.armMode = armMode;
		this.boardingOrder = boardingOrder;
		this.freightBoardingMode = freightBoardingMode;
		this.wholeOrderQueuePolicy = wholeOrderQueuePolicy;
		this.compressReports = compressReports;
		this.activationIteration = activationIteration;
		this.deactivationIteration = deactivationIteration;
		this.innovationOffIteration = innovationOffIteration;
		this.innovationReopenIteration = innovationReopenIteration;
		this.innovationFinalOffIteration = innovationFinalOffIteration;
		this.manageInnovationSchedule = manageInnovationSchedule;
		this.freightEquivalentPassengersPerM3 = freightEquivalentPassengersPerM3;
		this.comfortThreshold = comfortThreshold;
		this.hardCapacityRatio = hardCapacityRatio;
		this.passengerLoadGate = passengerLoadGate;
		this.freightShareCap = freightShareCap;
		this.freightHandlingDwellSeconds = freightHandlingDwellSeconds;
		this.freightLatePenaltyPerMinute = freightLatePenaltyPerMinute;
		this.deniedBoardingPenalty = deniedBoardingPenalty;
		this.freightIncompletePenaltyPerUnit = freightIncompletePenaltyPerUnit;
		this.freightServiceWindowStartTime = freightServiceWindowStartTime;
		this.freightServiceWindowEndTime = freightServiceWindowEndTime;
		this.passengerDeniedBoardingPenalty = passengerDeniedBoardingPenalty;
		this.passengerPhysicalCrowdingPenaltyPerMinute = passengerPhysicalCrowdingPenaltyPerMinute;
		this.passengerFreightCoPresencePenaltyPerMinute = passengerFreightCoPresencePenaltyPerMinute;
		this.freightActivityPrefix = freightActivityPrefix;
		this.freightShadowToken = freightShadowToken;
		this.dedicatedFreightLineId = dedicatedFreightLineId;
	}

	public Path freightOrdersCsv() {
		return freightOrdersCsv;
	}

	public Path baselineTransitScheduleFile() {
		return baselineTransitScheduleFile;
	}

	/** Intervention timetable installed only in active iterations; null preserves legacy live-schedule capture. */
	public Path activatedTransitScheduleFile() {
		return activatedTransitScheduleFile;
	}

	/** Intervention transit-vehicle inventory installed only in active iterations; null preserves legacy behaviour. */
	public Path activatedTransitVehiclesFile() {
		return activatedTransitVehiclesFile;
	}

	public ArmMode armMode() {
		return armMode;
	}

	/** Immutable queue-order policy; freight eligibility and native vehicle capacity remain separate. */
	public BoardingOrder boardingOrder() {
		return boardingOrder;
	}

	public FreightBoardingMode freightBoardingMode() { return freightBoardingMode; }

	public WholeOrderQueuePolicy wholeOrderQueuePolicy() { return wholeOrderQueuePolicy; }

	/** Lossless gzip of runtime-monitor CSV reports; false retains historical filenames. */
	public boolean compressReports() { return compressReports; }

	public int activationIteration() {
		return activationIteration;
	}

	public int deactivationIteration() {
		return deactivationIteration;
	}

	public int innovationOffIteration() {
		return innovationOffIteration;
	}

	public int innovationReopenIteration() {
		return innovationReopenIteration;
	}

	public int innovationFinalOffIteration() {
		return innovationFinalOffIteration;
	}

	public boolean manageInnovationSchedule() {
		return manageInnovationSchedule;
	}

	public boolean isActiveAt(int iteration) {
		return switch (armMode) {
			case ALWAYS_ACTIVE -> true;
			case DISABLED -> false;
			case ITERATION_WINDOW -> iteration >= activationIteration
					&& (deactivationIteration < 0 || iteration < deactivationIteration);
		};
	}

	public double freightEquivalentPassengersPerM3() {
		return freightEquivalentPassengersPerM3;
	}

	public double comfortThreshold() {
		return comfortThreshold;
	}

	public double hardCapacityRatio() {
		return hardCapacityRatio;
	}

	public double freightShareCap() {
		return freightShareCap;
	}

	public double passengerLoadGate() {
		return passengerLoadGate;
	}

	public double freightHandlingDwellSeconds() {
		return freightHandlingDwellSeconds;
	}

	public double freightLatePenaltyPerMinute() {
		return freightLatePenaltyPerMinute;
	}

	public double deniedBoardingPenalty() {
		return deniedBoardingPenalty;
	}

	public double freightIncompletePenaltyPerUnit() {
		return freightIncompletePenaltyPerUnit;
	}

	public double freightServiceWindowStartTime() {
		return freightServiceWindowStartTime;
	}

	public double freightServiceWindowEndTime() {
		return freightServiceWindowEndTime;
	}

	public boolean hasFreightServiceWindow() {
		return !Double.isNaN(freightServiceWindowStartTime) && !Double.isNaN(freightServiceWindowEndTime);
	}

	public double passengerDeniedBoardingPenalty() {
		return passengerDeniedBoardingPenalty;
	}

	public double passengerCrowdingPenaltyPerMinute() {
		return passengerPhysicalCrowdingPenaltyPerMinute;
	}

	public double passengerPhysicalCrowdingPenaltyPerMinute() {
		return passengerPhysicalCrowdingPenaltyPerMinute;
	}

	public double passengerFreightCoPresencePenaltyPerMinute() {
		return passengerFreightCoPresencePenaltyPerMinute;
	}

	public String freightActivityPrefix() {
		return freightActivityPrefix;
	}

	public String freightShadowToken() {
		return freightShadowToken;
	}

	public String dedicatedFreightLineId() {
		return dedicatedFreightLineId;
	}

	/**
	 * Creates a builder initialized with the default parameter values used by
	 * {@link #CoTransportSettings(Path)}.
	 */
	public static Builder builder(Path freightOrdersCsv) {
		return new Builder(freightOrdersCsv);
	}

	/**
	 * Fluent builder for {@link CoTransportSettings}. Defaults match the single-argument
	 * constructor, so existing experiment parameterizations are reproducible.
	 */
	public static final class Builder {
		private final Path freightOrdersCsv;
		private Path baselineTransitScheduleFile;
		private Path activatedTransitScheduleFile;
		private Path activatedTransitVehiclesFile;
		private ArmMode armMode = ArmMode.ALWAYS_ACTIVE;
		private BoardingOrder boardingOrder = BoardingOrder.PASSENGER_FIRST;
		private FreightBoardingMode freightBoardingMode = FreightBoardingMode.WHOLE_ORDER;
		private WholeOrderQueuePolicy wholeOrderQueuePolicy = WholeOrderQueuePolicy.STRICT_FIFO;
		private boolean compressReports;
		private int activationIteration;
		private int deactivationIteration = -1;
		private int innovationOffIteration = 150;
		private int innovationReopenIteration = 180;
		private int innovationFinalOffIteration = 300;
		private boolean manageInnovationSchedule;
		private double freightEquivalentPassengersPerM3 = 4.0;
		private double comfortThreshold = 0.75;
		private double hardCapacityRatio = 0.95;
		private double passengerLoadGate = 1.0;
		private double freightShareCap = 1.0;
		private double freightHandlingDwellSeconds;
		private double freightLatePenaltyPerMinute = 0.35;
		private double deniedBoardingPenalty = 8.0;
		private double freightIncompletePenaltyPerUnit = 25.0;
		private double freightServiceWindowStartTime = Double.NaN;
		private double freightServiceWindowEndTime = Double.NaN;
		private double passengerDeniedBoardingPenalty = 4.0;
		private double passengerPhysicalCrowdingPenaltyPerMinute = 0.18;
		private double passengerFreightCoPresencePenaltyPerMinute = 0.18;
		private String freightActivityPrefix = "freight_";
		private String freightShadowToken = "_cap_";
		private String dedicatedFreightLineId = null;

		private Builder(Path freightOrdersCsv) {
			this.freightOrdersCsv = freightOrdersCsv;
		}

		/** Baseline timetable installed during inactive iterations. */
		public Builder baselineTransitScheduleFile(Path value) {
			this.baselineTransitScheduleFile = value;
			return this;
		}

		/** External timetable payload installed on activation, keeping the live startup timetable baseline-identical. */
		public Builder activatedTransitScheduleFile(Path value) {
			this.activatedTransitScheduleFile = value;
			return this;
		}

		/** External vehicle payload paired with {@link #activatedTransitScheduleFile(Path)}. */
		public Builder activatedTransitVehiclesFile(Path value) {
			this.activatedTransitVehiclesFile = value;
			return this;
		}

		/** Timeline policy controlling whether and when freight is armed. */
		public Builder armMode(ArmMode value) {
			this.armMode = value;
			return this;
		}

		/**
		 * Selects boarding order before startup. FCFS uses arrival at the native boarding queue,
		 * after any freight preparation and access walk, not order release time.
		 */
		public Builder boardingOrder(BoardingOrder value) {
			this.boardingOrder = value;
			return this;
		}

		public Builder freightBoardingMode(FreightBoardingMode value) {
			this.freightBoardingMode = value;
			return this;
		}

		public Builder wholeOrderQueuePolicy(WholeOrderQueuePolicy value) {
			this.wholeOrderQueuePolicy = value;
			return this;
		}

		/** Lossless runtime-monitor output compression only; no behavioural or sampling effect. */
		public Builder compressReports(boolean value) {
			this.compressReports = value;
			return this;
		}

		/** First active iteration (inclusive) in {@link ArmMode#ITERATION_WINDOW}. */
		public Builder activationIteration(int value) {
			this.activationIteration = value;
			return this;
		}

		/** First inactive iteration after activation; -1 keeps the intervention active. */
		public Builder deactivationIteration(int value) {
			this.deactivationIteration = value;
			return this;
		}

		/** First convergence window: set passenger innovative-strategy weights to zero. */
		public Builder innovationOffIteration(int value) {
			this.innovationOffIteration = value;
			return this;
		}

		/** Restore configured passenger innovative-strategy weights. */
		public Builder innovationReopenIteration(int value) {
			this.innovationReopenIteration = value;
			return this;
		}

		/** Final convergence window: set passenger innovative-strategy weights to zero. */
		public Builder innovationFinalOffIteration(int value) {
			this.innovationFinalOffIteration = value;
			return this;
		}

		/** Enables the off/reopen/final-off change requests for passenger innovation. */
		public Builder manageInnovationSchedule(boolean value) {
			this.manageInnovationSchedule = value;
			return this;
		}

		/** Passenger-equivalent capacity units per cubic metre of freight volume. */
		public Builder freightEquivalentPassengersPerM3(double value) {
			this.freightEquivalentPassengersPerM3 = value;
			return this;
		}

		/** Load-factor threshold above which passenger crowding disutility accrues. */
		public Builder comfortThreshold(double value) {
			this.comfortThreshold = value;
			return this;
		}

		/** Reserved hard-capacity ratio (currently informational). */
		public Builder hardCapacityRatio(double value) {
			this.hardCapacityRatio = value;
			return this;
		}

		/** Passenger load-ratio threshold above which new freight boarding is blocked. */
		public Builder passengerLoadGate(double value) {
			this.passengerLoadGate = value;
			return this;
		}

		/** Physical upper bound on freight share of vehicle capacity at mixed-service boarding. */
		public Builder freightShareCap(double value) {
			this.freightShareCap = value;
			return this;
		}

		/** Extra dwell once per stop when at least one freight unit boards or alights. */
		public Builder freightHandlingDwellSeconds(double value) {
			this.freightHandlingDwellSeconds = value;
			return this;
		}

		/** Freight lateness penalty per minute, scaled by order priority. */
		public Builder freightLatePenaltyPerMinute(double value) {
			this.freightLatePenaltyPerMinute = value;
			return this;
		}

		/** Penalty per freight denied-boarding event. */
		public Builder deniedBoardingPenalty(double value) {
			this.deniedBoardingPenalty = value;
			return this;
		}

		/** Penalty per undelivered freight capacity unit, scaled by order priority. */
		public Builder freightIncompletePenaltyPerUnit(double value) {
			this.freightIncompletePenaltyPerUnit = value;
			return this;
		}

		/** Freight service window [start, end] in seconds since midnight; NaN disables. */
		public Builder freightServiceWindow(double startTimeSeconds, double endTimeSeconds) {
			this.freightServiceWindowStartTime = startTimeSeconds;
			this.freightServiceWindowEndTime = endTimeSeconds;
			return this;
		}

		/** Penalty per passenger denied-boarding event. */
		public Builder passengerDeniedBoardingPenalty(double value) {
			this.passengerDeniedBoardingPenalty = value;
			return this;
		}

		/** Passenger crowding/freight-exposure penalty per in-vehicle minute. */
		public Builder passengerCrowdingPenaltyPerMinute(double value) {
			this.passengerPhysicalCrowdingPenaltyPerMinute = value;
			this.passengerFreightCoPresencePenaltyPerMinute = value;
			return this;
		}

		/** Passenger disutility coefficient for physical load above the comfort threshold. */
		public Builder passengerPhysicalCrowdingPenaltyPerMinute(double value) {
			this.passengerPhysicalCrowdingPenaltyPerMinute = value;
			return this;
		}

		/** Passenger disutility coefficient for sharing vehicle capacity with freight. */
		public Builder passengerFreightCoPresencePenaltyPerMinute(double value) {
			this.passengerFreightCoPresencePenaltyPerMinute = value;
			return this;
		}

		/** Activity-type prefix identifying freight delivery activities. */
		public Builder freightActivityPrefix(String value) {
			this.freightActivityPrefix = value;
			return this;
		}

		/** Token separating order id and capacity-unit index in shadow agent ids. */
		public Builder freightShadowToken(String value) {
			this.freightShadowToken = value;
			return this;
		}

		/** Transit line id of the dedicated freight service; null/blank means mixed operation. */
		public Builder dedicatedFreightLineId(String value) {
			this.dedicatedFreightLineId = value;
			return this;
		}

		public CoTransportSettings build() {
			if (freightOrdersCsv == null) {
				throw new IllegalArgumentException("freightOrdersCsv must not be null");
			}
			if (armMode == null) {
				throw new IllegalArgumentException("armMode must not be null");
			}
			if (boardingOrder == null) {
				throw new IllegalArgumentException("boardingOrder must not be null");
			}
			if (freightBoardingMode == null || wholeOrderQueuePolicy == null) {
				throw new IllegalArgumentException("freightBoardingMode and wholeOrderQueuePolicy must not be null");
			}
			if (activationIteration < 0 || deactivationIteration < -1
					|| deactivationIteration >= 0 && deactivationIteration < activationIteration) {
				throw new IllegalArgumentException("Invalid activation/deactivation iteration window");
			}
			if (innovationOffIteration < 0 || innovationReopenIteration <= innovationOffIteration
					|| innovationFinalOffIteration <= innovationReopenIteration) {
				throw new IllegalArgumentException("Innovation iterations must satisfy 0 <= off < reopen < finalOff");
			}
			if (!Double.isFinite(freightHandlingDwellSeconds) || freightHandlingDwellSeconds < 0.0) {
				throw new IllegalArgumentException("freightHandlingDwellSeconds must be finite and >= 0");
			}
			if ((activatedTransitScheduleFile == null) != (activatedTransitVehiclesFile == null)) {
				throw new IllegalArgumentException("Activated timetable and transit-vehicle payloads must be configured together");
			}
			return new CoTransportSettings(freightOrdersCsv, baselineTransitScheduleFile,
					activatedTransitScheduleFile, activatedTransitVehiclesFile, armMode, boardingOrder,
					freightBoardingMode, wholeOrderQueuePolicy,
					activationIteration, deactivationIteration, innovationOffIteration,
					innovationReopenIteration, innovationFinalOffIteration, manageInnovationSchedule,
					freightEquivalentPassengersPerM3,
					comfortThreshold, hardCapacityRatio, passengerLoadGate, freightShareCap,
					freightHandlingDwellSeconds, freightLatePenaltyPerMinute,
					deniedBoardingPenalty, freightIncompletePenaltyPerUnit, freightServiceWindowStartTime,
					freightServiceWindowEndTime, passengerDeniedBoardingPenalty,
					passengerPhysicalCrowdingPenaltyPerMinute,
					passengerFreightCoPresencePenaltyPerMinute, freightActivityPrefix,
					freightShadowToken, dedicatedFreightLineId, compressReports);
		}
	}
}
