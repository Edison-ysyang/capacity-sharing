package org.matsim.run.r17;

import org.matsim.core.config.Config;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.utils.misc.Time;

import java.util.Map;

/**
 * Keeps Plan A's behavioural feasibility horizon separate from QSim's execution horizon.
 *
 * <p>The scenario's {@code qsim.endTime} remains the behavioural horizon used by
 * canonicalization and replanning. Immediately before QSim is constructed, the
 * controller listener temporarily replaces it with {@link #getExecutionEndTime()}.
 * The original value is restored as soon as Mobsim returns.</p>
 */
public final class PlanAExecutionHorizonConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "planAExecutionHorizon";
	public static final String EXECUTION_END_TIME = "executionEndTime";
	public static final double DEFAULT_EXECUTION_END_TIME = 36.0 * 3600.0;

	private double executionEndTime = DEFAULT_EXECUTION_END_TIME;

	public PlanAExecutionHorizonConfigGroup() {
		super(GROUP_NAME);
	}

	public double getExecutionEndTime() {
		return executionEndTime;
	}

	public void setExecutionEndTime(double executionEndTime) {
		this.executionEndTime = executionEndTime;
	}

	@StringGetter(EXECUTION_END_TIME)
	public String getExecutionEndTimeAsString() {
		return Time.writeTime(executionEndTime);
	}

	@StringSetter(EXECUTION_END_TIME)
	public void setExecutionEndTime(String executionEndTime) {
		this.executionEndTime = Time.parseTime(executionEndTime);
	}

	@Override
	protected void checkConsistency(Config config) {
		super.checkConsistency(config);
		if (!Double.isFinite(executionEndTime) || executionEndTime <= 0.0) {
			throw new IllegalArgumentException(
					GROUP_NAME + "." + EXECUTION_END_TIME + " must be a finite positive time");
		}
		double behaviouralEndTime = config.qsim().getEndTime().orElseThrow(
				() -> new IllegalArgumentException(
						GROUP_NAME + " requires a defined qsim.endTime behavioural horizon"));
		if (!Double.isFinite(behaviouralEndTime) || behaviouralEndTime <= 0.0) {
			throw new IllegalArgumentException("qsim.endTime must be a finite positive behavioural horizon");
		}
		if (executionEndTime <= behaviouralEndTime) {
			throw new IllegalArgumentException(GROUP_NAME + "." + EXECUTION_END_TIME
					+ " must be later than qsim.endTime; got executionEndTime=" + executionEndTime
					+ " and qsim.endTime=" + behaviouralEndTime);
		}
		if (config.qsim().getSimEndtimeInterpretation()
				!= QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime) {
			throw new IllegalArgumentException(GROUP_NAME
					+ " requires qsim.simEndtimeInterpretation=onlyUseEndtime so QSim physically executes "
					+ "through the configured execution horizon");
		}
	}

	@Override
	public Map<String, String> getComments() {
		Map<String, String> comments = super.getComments();
		comments.put(EXECUTION_END_TIME,
				"Temporary QSim-only end time. qsim.endTime remains the shorter Plan-A behavioural/replanning horizon. Default: 36:00:00.");
		return comments;
	}
}
