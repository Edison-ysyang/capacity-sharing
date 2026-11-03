package org.matsim.contrib.cotransport;

import org.matsim.core.config.Config;
import org.matsim.core.config.ReflectiveConfigGroup;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serializable configuration of the passenger-freight co-transport extension.
 *
 * <p>The group is deliberately flat so an {@code output_config.xml} contains every
 * effective policy and scoring parameter. Paths may be absolute or relative to the
 * MATSim config file.</p>
 */
public final class CoTransportConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "coTransport";

	private String freightOrdersFile;
	private String baselineTransitScheduleFile;
	private String activatedTransitScheduleFile;
	private String activatedTransitVehiclesFile;
	private CoTransportSettings.ArmMode armMode = CoTransportSettings.ArmMode.ALWAYS_ACTIVE;
	private CoTransportSettings.BoardingOrder boardingOrder = CoTransportSettings.BoardingOrder.PASSENGER_FIRST;
	private CoTransportSettings.FreightBoardingMode freightBoardingMode = CoTransportSettings.FreightBoardingMode.WHOLE_ORDER;
	private CoTransportSettings.WholeOrderQueuePolicy wholeOrderQueuePolicy = CoTransportSettings.WholeOrderQueuePolicy.STRICT_FIFO;
	private boolean compressReports;
	private int activationIteration;
	private int deactivationIteration = -1;
	private int innovationOffIteration = 150;
	private int innovationReopenIteration = 180;
	private int innovationFinalOffIteration = 300;
	private boolean manageInnovationSchedule = true;
	private double freightEquivalentPassengersPerM3 = 4.0;
	private double comfortThreshold = 0.75;
	private double hardCapacityRatio = 0.95;
	private double passengerLoadGate = 1.0;
	private double freightShareCap = 1.0;
	private double freightHandlingDwellSeconds;
	private double freightLatePenaltyPerMinute = 0.35;
	private double freightDeniedBoardingPenalty = 8.0;
	private double freightIncompletePenaltyPerUnit = 25.0;
	private double freightServiceWindowStartTime = Double.NaN;
	private double freightServiceWindowEndTime = Double.NaN;
	private double passengerDeniedBoardingPenalty = 4.0;
	private double passengerPhysicalCrowdingPenaltyPerMinute = 0.18;
	private double passengerFreightCoPresencePenaltyPerMinute = 0.18;
	private String freightActivityPrefix = "freight_";
	private String freightShadowToken = "_cap_";
	private String dedicatedFreightLineId;

	public CoTransportConfigGroup() {
		super(GROUP_NAME);
	}

	@StringGetter("freightOrdersFile")
	public String getFreightOrdersFile() {
		return freightOrdersFile;
	}

	@StringSetter("freightOrdersFile")
	public void setFreightOrdersFile(String freightOrdersFile) {
		this.freightOrdersFile = blankToNull(freightOrdersFile);
	}

	@StringGetter("baselineTransitScheduleFile")
	public String getBaselineTransitScheduleFile() {
		return baselineTransitScheduleFile;
	}

	@StringSetter("baselineTransitScheduleFile")
	public void setBaselineTransitScheduleFile(String baselineTransitScheduleFile) {
		this.baselineTransitScheduleFile = blankToNull(baselineTransitScheduleFile);
	}

	@StringGetter("activatedTransitScheduleFile")
	public String getActivatedTransitScheduleFile() {
		return activatedTransitScheduleFile;
	}

	@StringSetter("activatedTransitScheduleFile")
	public void setActivatedTransitScheduleFile(String activatedTransitScheduleFile) {
		this.activatedTransitScheduleFile = blankToNull(activatedTransitScheduleFile);
	}

	@StringGetter("activatedTransitVehiclesFile")
	public String getActivatedTransitVehiclesFile() {
		return activatedTransitVehiclesFile;
	}

	@StringSetter("activatedTransitVehiclesFile")
	public void setActivatedTransitVehiclesFile(String activatedTransitVehiclesFile) {
		this.activatedTransitVehiclesFile = blankToNull(activatedTransitVehiclesFile);
	}

	@StringGetter("armMode")
	public CoTransportSettings.ArmMode getArmMode() {
		return armMode;
	}

	@StringSetter("armMode")
	public void setArmMode(CoTransportSettings.ArmMode armMode) {
		this.armMode = armMode;
	}

	@StringGetter("boardingOrder")
	public CoTransportSettings.BoardingOrder getBoardingOrder() {
		return boardingOrder;
	}

	@StringSetter("boardingOrder")
	public void setBoardingOrder(CoTransportSettings.BoardingOrder boardingOrder) {
		if (boardingOrder == null) {
			throw new IllegalArgumentException("coTransport.boardingOrder must not be null");
		}
		this.boardingOrder = boardingOrder;
	}

	@StringGetter("freightBoardingMode")
	public CoTransportSettings.FreightBoardingMode getFreightBoardingMode() { return freightBoardingMode; }

	@StringSetter("freightBoardingMode")
	public void setFreightBoardingMode(CoTransportSettings.FreightBoardingMode value) {
		if (value == null) throw new IllegalArgumentException("coTransport.freightBoardingMode must not be null");
		freightBoardingMode = value;
	}

	@StringGetter("wholeOrderQueuePolicy")
	public CoTransportSettings.WholeOrderQueuePolicy getWholeOrderQueuePolicy() { return wholeOrderQueuePolicy; }

	@StringSetter("wholeOrderQueuePolicy")
	public void setWholeOrderQueuePolicy(CoTransportSettings.WholeOrderQueuePolicy value) {
		if (value == null) throw new IllegalArgumentException("coTransport.wholeOrderQueuePolicy must not be null");
		wholeOrderQueuePolicy = value;
	}

	@StringGetter("compressReports")
	public boolean isCompressReports() { return compressReports; }

	@StringSetter("compressReports")
	public void setCompressReports(boolean value) { compressReports = value; }

	@StringGetter("activationIteration")
	public int getActivationIteration() {
		return activationIteration;
	}

	@StringSetter("activationIteration")
	public void setActivationIteration(int activationIteration) {
		this.activationIteration = activationIteration;
	}

	@StringGetter("deactivationIteration")
	public int getDeactivationIteration() {
		return deactivationIteration;
	}

	@StringSetter("deactivationIteration")
	public void setDeactivationIteration(int deactivationIteration) {
		this.deactivationIteration = deactivationIteration;
	}

	@StringGetter("innovationOffIteration")
	public int getInnovationOffIteration() {
		return innovationOffIteration;
	}

	@StringSetter("innovationOffIteration")
	public void setInnovationOffIteration(int innovationOffIteration) {
		this.innovationOffIteration = innovationOffIteration;
	}

	@StringGetter("innovationReopenIteration")
	public int getInnovationReopenIteration() {
		return innovationReopenIteration;
	}

	@StringSetter("innovationReopenIteration")
	public void setInnovationReopenIteration(int innovationReopenIteration) {
		this.innovationReopenIteration = innovationReopenIteration;
	}

	@StringGetter("innovationFinalOffIteration")
	public int getInnovationFinalOffIteration() {
		return innovationFinalOffIteration;
	}

	@StringSetter("innovationFinalOffIteration")
	public void setInnovationFinalOffIteration(int innovationFinalOffIteration) {
		this.innovationFinalOffIteration = innovationFinalOffIteration;
	}

	@StringGetter("manageInnovationSchedule")
	public boolean isManageInnovationSchedule() {
		return manageInnovationSchedule;
	}

	@StringSetter("manageInnovationSchedule")
	public void setManageInnovationSchedule(boolean manageInnovationSchedule) {
		this.manageInnovationSchedule = manageInnovationSchedule;
	}

	@StringGetter("freightEquivalentPassengersPerM3")
	public double getFreightEquivalentPassengersPerM3() {
		return freightEquivalentPassengersPerM3;
	}

	@StringSetter("freightEquivalentPassengersPerM3")
	public void setFreightEquivalentPassengersPerM3(double value) {
		freightEquivalentPassengersPerM3 = value;
	}

	@StringGetter("comfortThreshold")
	public double getComfortThreshold() {
		return comfortThreshold;
	}

	@StringSetter("comfortThreshold")
	public void setComfortThreshold(double comfortThreshold) {
		this.comfortThreshold = comfortThreshold;
	}

	@StringGetter("hardCapacityRatio")
	public double getHardCapacityRatio() {
		return hardCapacityRatio;
	}

	@StringSetter("hardCapacityRatio")
	public void setHardCapacityRatio(double hardCapacityRatio) {
		this.hardCapacityRatio = hardCapacityRatio;
	}

	@StringGetter("passengerLoadGate")
	public double getPassengerLoadGate() {
		return passengerLoadGate;
	}

	@StringSetter("passengerLoadGate")
	public void setPassengerLoadGate(double passengerLoadGate) {
		this.passengerLoadGate = passengerLoadGate;
	}

	@StringGetter("freightShareCap")
	public double getFreightShareCap() {
		return freightShareCap;
	}

	@StringSetter("freightShareCap")
	public void setFreightShareCap(double freightShareCap) {
		this.freightShareCap = freightShareCap;
	}

	@StringGetter("freightHandlingDwellSeconds")
	public double getFreightHandlingDwellSeconds() {
		return freightHandlingDwellSeconds;
	}

	@StringSetter("freightHandlingDwellSeconds")
	public void setFreightHandlingDwellSeconds(double freightHandlingDwellSeconds) {
		this.freightHandlingDwellSeconds = freightHandlingDwellSeconds;
	}

	@StringGetter("freightLatePenaltyPerMinute")
	public double getFreightLatePenaltyPerMinute() {
		return freightLatePenaltyPerMinute;
	}

	@StringSetter("freightLatePenaltyPerMinute")
	public void setFreightLatePenaltyPerMinute(double value) {
		freightLatePenaltyPerMinute = value;
	}

	@StringGetter("freightDeniedBoardingPenalty")
	public double getFreightDeniedBoardingPenalty() {
		return freightDeniedBoardingPenalty;
	}

	@StringSetter("freightDeniedBoardingPenalty")
	public void setFreightDeniedBoardingPenalty(double value) {
		freightDeniedBoardingPenalty = value;
	}

	@StringGetter("freightIncompletePenaltyPerUnit")
	public double getFreightIncompletePenaltyPerUnit() {
		return freightIncompletePenaltyPerUnit;
	}

	@StringSetter("freightIncompletePenaltyPerUnit")
	public void setFreightIncompletePenaltyPerUnit(double value) {
		freightIncompletePenaltyPerUnit = value;
	}

	@StringGetter("freightServiceWindowStartTime")
	public double getFreightServiceWindowStartTime() {
		return freightServiceWindowStartTime;
	}

	@StringSetter("freightServiceWindowStartTime")
	public void setFreightServiceWindowStartTime(double value) {
		freightServiceWindowStartTime = value;
	}

	@StringGetter("freightServiceWindowEndTime")
	public double getFreightServiceWindowEndTime() {
		return freightServiceWindowEndTime;
	}

	@StringSetter("freightServiceWindowEndTime")
	public void setFreightServiceWindowEndTime(double value) {
		freightServiceWindowEndTime = value;
	}

	@StringGetter("passengerDeniedBoardingPenalty")
	public double getPassengerDeniedBoardingPenalty() {
		return passengerDeniedBoardingPenalty;
	}

	@StringSetter("passengerDeniedBoardingPenalty")
	public void setPassengerDeniedBoardingPenalty(double value) {
		passengerDeniedBoardingPenalty = value;
	}

	@StringGetter("passengerPhysicalCrowdingPenaltyPerMinute")
	public double getPassengerPhysicalCrowdingPenaltyPerMinute() {
		return passengerPhysicalCrowdingPenaltyPerMinute;
	}

	@StringSetter("passengerPhysicalCrowdingPenaltyPerMinute")
	public void setPassengerPhysicalCrowdingPenaltyPerMinute(double value) {
		passengerPhysicalCrowdingPenaltyPerMinute = value;
	}

	@StringGetter("passengerFreightCoPresencePenaltyPerMinute")
	public double getPassengerFreightCoPresencePenaltyPerMinute() {
		return passengerFreightCoPresencePenaltyPerMinute;
	}

	@StringSetter("passengerFreightCoPresencePenaltyPerMinute")
	public void setPassengerFreightCoPresencePenaltyPerMinute(double value) {
		passengerFreightCoPresencePenaltyPerMinute = value;
	}

	@StringGetter("freightActivityPrefix")
	public String getFreightActivityPrefix() {
		return freightActivityPrefix;
	}

	@StringSetter("freightActivityPrefix")
	public void setFreightActivityPrefix(String freightActivityPrefix) {
		this.freightActivityPrefix = freightActivityPrefix;
	}

	@StringGetter("freightShadowToken")
	public String getFreightShadowToken() {
		return freightShadowToken;
	}

	@StringSetter("freightShadowToken")
	public void setFreightShadowToken(String freightShadowToken) {
		this.freightShadowToken = freightShadowToken;
	}

	@StringGetter("dedicatedFreightLineId")
	public String getDedicatedFreightLineId() {
		return dedicatedFreightLineId;
	}

	@StringSetter("dedicatedFreightLineId")
	public void setDedicatedFreightLineId(String dedicatedFreightLineId) {
		this.dedicatedFreightLineId = blankToNull(dedicatedFreightLineId);
	}

	public boolean hasFreightOrdersFile() {
		return freightOrdersFile != null;
	}

	public CoTransportSettings toSettings(Config config) {
		if (!hasFreightOrdersFile()) {
			throw new IllegalStateException("coTransport.freightOrdersFile must be configured");
		}
		CoTransportSettings.Builder builder = CoTransportSettings.builder(resolve(config, freightOrdersFile))
				.armMode(armMode)
				.boardingOrder(boardingOrder)
				.freightBoardingMode(freightBoardingMode)
				.wholeOrderQueuePolicy(wholeOrderQueuePolicy)
				.compressReports(compressReports)
				.activationIteration(activationIteration)
				.deactivationIteration(deactivationIteration)
				.innovationOffIteration(innovationOffIteration)
				.innovationReopenIteration(innovationReopenIteration)
				.innovationFinalOffIteration(innovationFinalOffIteration)
				.manageInnovationSchedule(manageInnovationSchedule)
				.freightEquivalentPassengersPerM3(freightEquivalentPassengersPerM3)
				.comfortThreshold(comfortThreshold)
				.hardCapacityRatio(hardCapacityRatio)
				.passengerLoadGate(passengerLoadGate)
				.freightShareCap(freightShareCap)
				.freightHandlingDwellSeconds(freightHandlingDwellSeconds)
				.freightLatePenaltyPerMinute(freightLatePenaltyPerMinute)
				.deniedBoardingPenalty(freightDeniedBoardingPenalty)
				.freightIncompletePenaltyPerUnit(freightIncompletePenaltyPerUnit)
				.passengerDeniedBoardingPenalty(passengerDeniedBoardingPenalty)
				.passengerPhysicalCrowdingPenaltyPerMinute(passengerPhysicalCrowdingPenaltyPerMinute)
				.passengerFreightCoPresencePenaltyPerMinute(passengerFreightCoPresencePenaltyPerMinute)
				.freightActivityPrefix(freightActivityPrefix)
				.freightShadowToken(freightShadowToken)
				.dedicatedFreightLineId(dedicatedFreightLineId);
		if (!Double.isNaN(freightServiceWindowStartTime) || !Double.isNaN(freightServiceWindowEndTime)) {
			builder.freightServiceWindow(freightServiceWindowStartTime, freightServiceWindowEndTime);
		}
		if (baselineTransitScheduleFile != null) {
			builder.baselineTransitScheduleFile(resolve(config, baselineTransitScheduleFile));
		}
		if (activatedTransitScheduleFile != null) {
			builder.activatedTransitScheduleFile(resolve(config, activatedTransitScheduleFile));
		}
		if (activatedTransitVehiclesFile != null) {
			builder.activatedTransitVehiclesFile(resolve(config, activatedTransitVehiclesFile));
		}
		return builder.build();
	}

	public void apply(CoTransportSettings settings) {
		setFreightOrdersFile(settings.freightOrdersCsv().toString());
		setBaselineTransitScheduleFile(settings.baselineTransitScheduleFile() == null
				? null : settings.baselineTransitScheduleFile().toString());
		setActivatedTransitScheduleFile(settings.activatedTransitScheduleFile() == null
				? null : settings.activatedTransitScheduleFile().toString());
		setActivatedTransitVehiclesFile(settings.activatedTransitVehiclesFile() == null
				? null : settings.activatedTransitVehiclesFile().toString());
		setArmMode(settings.armMode());
		setBoardingOrder(settings.boardingOrder());
		setFreightBoardingMode(settings.freightBoardingMode());
		setWholeOrderQueuePolicy(settings.wholeOrderQueuePolicy());
		setCompressReports(settings.compressReports());
		setActivationIteration(settings.activationIteration());
		setDeactivationIteration(settings.deactivationIteration());
		setInnovationOffIteration(settings.innovationOffIteration());
		setInnovationReopenIteration(settings.innovationReopenIteration());
		setInnovationFinalOffIteration(settings.innovationFinalOffIteration());
		setManageInnovationSchedule(settings.manageInnovationSchedule());
		setFreightEquivalentPassengersPerM3(settings.freightEquivalentPassengersPerM3());
		setComfortThreshold(settings.comfortThreshold());
		setHardCapacityRatio(settings.hardCapacityRatio());
		setPassengerLoadGate(settings.passengerLoadGate());
		setFreightShareCap(settings.freightShareCap());
		setFreightHandlingDwellSeconds(settings.freightHandlingDwellSeconds());
		setFreightLatePenaltyPerMinute(settings.freightLatePenaltyPerMinute());
		setFreightDeniedBoardingPenalty(settings.deniedBoardingPenalty());
		setFreightIncompletePenaltyPerUnit(settings.freightIncompletePenaltyPerUnit());
		setFreightServiceWindowStartTime(settings.freightServiceWindowStartTime());
		setFreightServiceWindowEndTime(settings.freightServiceWindowEndTime());
		setPassengerDeniedBoardingPenalty(settings.passengerDeniedBoardingPenalty());
		setPassengerPhysicalCrowdingPenaltyPerMinute(settings.passengerPhysicalCrowdingPenaltyPerMinute());
		setPassengerFreightCoPresencePenaltyPerMinute(settings.passengerFreightCoPresencePenaltyPerMinute());
		setFreightActivityPrefix(settings.freightActivityPrefix());
		setFreightShadowToken(settings.freightShadowToken());
		setDedicatedFreightLineId(settings.dedicatedFreightLineId());
	}

	@Override
	protected void checkConsistency(Config config) {
		super.checkConsistency(config);
		if (armMode == null) {
			throw new IllegalArgumentException("coTransport.armMode must not be null");
		}
		if (boardingOrder == null) {
			throw new IllegalArgumentException("coTransport.boardingOrder must not be null");
		}
		if (freightBoardingMode == null || wholeOrderQueuePolicy == null) {
			throw new IllegalArgumentException("coTransport freightBoardingMode and wholeOrderQueuePolicy must not be null");
		}
		if (activationIteration < 0) {
			throw new IllegalArgumentException("coTransport.activationIteration must be >= 0");
		}
		if ((activatedTransitScheduleFile == null) != (activatedTransitVehiclesFile == null)) {
			throw new IllegalArgumentException("coTransport activated timetable and vehicle files must be configured together");
		}
		if (deactivationIteration < -1
				|| deactivationIteration >= 0 && deactivationIteration < activationIteration) {
			throw new IllegalArgumentException("coTransport.deactivationIteration must be -1 or >= activationIteration");
		}
		if (innovationOffIteration < 0 || innovationReopenIteration <= innovationOffIteration
				|| innovationFinalOffIteration <= innovationReopenIteration) {
			throw new IllegalArgumentException("coTransport innovation iterations must satisfy 0 <= off < reopen < finalOff");
		}
		requireNonNegative("freightEquivalentPassengersPerM3", freightEquivalentPassengersPerM3);
		requireUnitInterval("comfortThreshold", comfortThreshold);
		requireUnitInterval("hardCapacityRatio", hardCapacityRatio);
		requireUnitInterval("passengerLoadGate", passengerLoadGate);
		requireUnitInterval("freightShareCap", freightShareCap);
		requireNonNegative("freightHandlingDwellSeconds", freightHandlingDwellSeconds);
		requireNonNegative("freightLatePenaltyPerMinute", freightLatePenaltyPerMinute);
		requireNonNegative("freightDeniedBoardingPenalty", freightDeniedBoardingPenalty);
		requireNonNegative("freightIncompletePenaltyPerUnit", freightIncompletePenaltyPerUnit);
		requireNonNegative("passengerDeniedBoardingPenalty", passengerDeniedBoardingPenalty);
		requireNonNegative("passengerPhysicalCrowdingPenaltyPerMinute", passengerPhysicalCrowdingPenaltyPerMinute);
		requireNonNegative("passengerFreightCoPresencePenaltyPerMinute", passengerFreightCoPresencePenaltyPerMinute);
		if (Double.isNaN(freightServiceWindowStartTime) != Double.isNaN(freightServiceWindowEndTime)
				|| !Double.isNaN(freightServiceWindowStartTime)
				&& freightServiceWindowEndTime < freightServiceWindowStartTime) {
			throw new IllegalArgumentException("coTransport freight service window must have both bounds and end >= start");
		}
	}

	@Override
	public Map<String, String> getComments() {
		Map<String, String> comments = super.getComments();
		comments.put("armMode", "ALWAYS_ACTIVE preserves legacy behaviour; ITERATION_WINDOW gates freight agents and swaps schedules; DISABLED never arms freight.");
		comments.put("boardingOrder", "Immutable startup choice: PASSENGER_FIRST (legacy default), or FIRST_COME_FIRST_SERVED by actual arrival at the native boarding queue after freight preparation/access walking. Eligibility gates, native capacity and dedicated-service isolation are unchanged.");
		comments.put("freightBoardingMode", "WHOLE_ORDER (new default) commits all registered capacity units to one boarding opportunity without splitting; UNIT_SPLIT explicitly reproduces the historical independent-unit mechanism. Old output identities remain unchanged.");
		comments.put("wholeOrderQueuePolicy", "STRICT_FIFO (default) prevents later candidates in the same ordered queue from bypassing a ready whole order that cannot be admitted; FIT_FIRST explicitly allows capacity-fitting bypass. Only applies to WHOLE_ORDER.");
		comments.put("compressReports", "Losslessly write all runtime-monitor CSV reports with .gz suffix; preserves every row and field. Default false retains historical CSV filenames. This does not change simulation behaviour, event sampling or other modules' reports.");
		comments.put("activationIteration", "First active MATSim iteration (inclusive) in ITERATION_WINDOW mode.");
		comments.put("deactivationIteration", "First inactive MATSim iteration after activation; -1 means no deactivation.");
		comments.put("innovationOffIteration", "First iteration with passenger innovative-strategy weights set to zero.");
		comments.put("innovationReopenIteration", "Iteration restoring configured passenger innovative-strategy weights.");
		comments.put("innovationFinalOffIteration", "First iteration of the final passenger convergence window.");
		comments.put("manageInnovationSchedule", "Whether the module registers the three passenger innovation change requests; config-driven R17 defaults true, legacy Java settings default false.");
		comments.put("baselineTransitScheduleFile", "Required when an inactive iteration is executed, so inactive phases cannot silently retain an intervention timetable.");
		comments.put("activatedTransitScheduleFile", "Optional intervention timetable payload installed only at an active iteration; permits an exactly baseline-identical live startup timetable.");
		comments.put("activatedTransitVehiclesFile", "Vehicle payload paired with activatedTransitScheduleFile and installed only at an active iteration.");
		comments.put("passengerLoadGate", "Passenger load-ratio threshold above which new freight boarding is physically blocked.");
		comments.put("freightShareCap", "Physical upper bound on freight share of vehicle capacity at boarding.");
		comments.put("freightHandlingDwellSeconds", "Extra dwell applied once per stop when freight boards or alights; seconds.");
		comments.put("passengerPhysicalCrowdingPenaltyPerMinute", "Coefficient on load above comfortThreshold, utility per passenger-minute.");
		comments.put("passengerFreightCoPresencePenaltyPerMinute", "Separate coefficient on freight co-presence share, utility per passenger-minute.");
		return comments;
	}

	private static Path resolve(Config config, String filename) {
		URL url = getInputFileURL(config.getContext(), filename);
		if (!"file".equalsIgnoreCase(url.getProtocol())) {
			throw new IllegalArgumentException("coTransport currently requires a local file path, got " + url);
		}
		try {
			return Path.of(new URI(url.toExternalForm()));
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid coTransport file path: " + filename, e);
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static void requireNonNegative(String name, double value) {
		if (!Double.isFinite(value) || value < 0.0) {
			throw new IllegalArgumentException("coTransport." + name + " must be finite and >= 0");
		}
	}

	private static void requireUnitInterval(String name, double value) {
		if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
			throw new IllegalArgumentException("coTransport." + name + " must be in [0, 1]");
		}
	}
}
