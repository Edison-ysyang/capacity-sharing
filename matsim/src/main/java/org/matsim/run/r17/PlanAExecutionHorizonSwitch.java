package org.matsim.run.r17;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stateful, fail-closed switch shared by the two controller-listener phases.
 *
 * <p>A single controller-listener priority cannot be both last before Mobsim
 * and first after Mobsim. Consequently, thin before/after adapters share this
 * singleton. Every successful transition is durably reflected in a run-root
 * receipt so a postflight can distinguish a completed restore from a killed
 * process that stopped while QSim's longer horizon was active.</p>
 */
@Singleton
public final class PlanAExecutionHorizonSwitch {
	public static final String AUDIT_FILE = "plan_a_execution_horizon_audit.json";
	public static final String AUDIT_SCHEMA = "r17_plan_a_execution_horizon_audit_v1";

	static final String QSIM_ACTIVE = "QSIM_ACTIVE";
	static final String BEHAVIOURAL_RESTORED = "BEHAVIORAL_RESTORED";
	static final String FAIL_CLOSED_RESTORED = "FAIL_CLOSED_RESTORED";

	private final Config config;
	private final Path auditPath;
	private final double behaviouralEndTime;
	private final double executionEndTime;
	private final Map<Integer, AuditRecord> records = new LinkedHashMap<>();

	private boolean executionHorizonActive;
	private int activeIteration = -1;

	@Inject
	public PlanAExecutionHorizonSwitch(Config config, OutputDirectoryHierarchy controllerIO) {
		this.config = config;
		ConfigGroup rawGroup = config.getModule(PlanAExecutionHorizonConfigGroup.GROUP_NAME);
		if (!(rawGroup instanceof PlanAExecutionHorizonConfigGroup horizonConfig)) {
			throw new IllegalStateException("Missing materialized "
					+ PlanAExecutionHorizonConfigGroup.GROUP_NAME + " config group");
		}
		horizonConfig.checkConsistency(config);
		this.behaviouralEndTime = requireQsimEndTime();
		this.executionEndTime = horizonConfig.getExecutionEndTime();
		this.auditPath = Path.of(controllerIO.getOutputPath()).resolve(AUDIT_FILE);
	}

	synchronized void activateExecutionHorizon(int iteration) {
		if (executionHorizonActive) {
			failClosed("Cannot activate Plan-A execution horizon for iteration " + iteration
					+ " while iteration " + activeIteration + " is still active");
		}

		double current = requireQsimEndTime();
		if (!sameTime(current, behaviouralEndTime)) {
			restoreBehaviouralHorizon();
			throw new IllegalStateException("Plan-A split horizon refused to start QSim iteration " + iteration
					+ ": qsim.endTime changed from the behavioural horizon " + behaviouralEndTime
					+ " to " + current + " before activation; restored the behavioural horizon");
		}

		config.qsim().setEndTime(executionEndTime);
		if (!sameTime(requireQsimEndTime(), executionEndTime)) {
			restoreBehaviouralHorizon();
			throw new IllegalStateException("Could not activate Plan-A QSim execution horizon "
					+ executionEndTime + " for iteration " + iteration);
		}

		executionHorizonActive = true;
		activeIteration = iteration;
		records.put(iteration, AuditRecord.active(iteration, behaviouralEndTime, executionEndTime));
		try {
			writeAudit();
		} catch (RuntimeException error) {
			records.remove(iteration);
			restoreBehaviouralHorizon();
			executionHorizonActive = false;
			activeIteration = -1;
			throw new IllegalStateException("Plan-A split horizon refused to start QSim because its activation "
					+ "receipt could not be committed: " + auditPath, error);
		}
	}

	synchronized void restoreBehaviouralHorizon(int iteration) {
		if (!executionHorizonActive) {
			double current = requireQsimEndTime();
			if (!sameTime(current, behaviouralEndTime)) {
				restoreBehaviouralHorizon();
				throw new IllegalStateException("Plan-A split horizon found qsim.endTime=" + current
						+ " without an active execution-horizon transition; restored " + behaviouralEndTime);
			}
			/*
			 * A preceding BeforeMobsim listener may have failed before the activation
			 * adapter ran. AbstractController still calls AfterMobsim listeners in
			 * that case, so an inactive, already-restored state is a valid no-op.
			 */
			return;
		}

		String failure = null;
		double current = requireQsimEndTime();
		if (activeIteration != iteration) {
			failure = "AfterMobsim iteration " + iteration + " does not match active iteration " + activeIteration;
		} else if (!sameTime(current, executionEndTime)) {
			failure = "qsim.endTime changed during Mobsim from execution horizon "
					+ executionEndTime + " to " + current;
		}

		int receiptIteration = activeIteration;
		restoreBehaviouralHorizon();
		executionHorizonActive = false;
		activeIteration = -1;
		records.put(receiptIteration, failure == null
				? AuditRecord.restored(receiptIteration, behaviouralEndTime, executionEndTime)
				: AuditRecord.failClosed(receiptIteration, behaviouralEndTime, executionEndTime));
		writeAudit();

		if (failure != null) {
			throw new IllegalStateException("Plan-A split horizon failed closed after restoring the behavioural "
					+ "horizon: " + failure);
		}
	}

	double getBehaviouralEndTime() {
		return behaviouralEndTime;
	}

	double getExecutionEndTime() {
		return executionEndTime;
	}

	synchronized boolean isExecutionHorizonActive() {
		return executionHorizonActive;
	}

	private void failClosed(String message) {
		int receiptIteration = activeIteration;
		restoreBehaviouralHorizon();
		executionHorizonActive = false;
		activeIteration = -1;
		if (receiptIteration >= 0) {
			records.put(receiptIteration,
					AuditRecord.failClosed(receiptIteration, behaviouralEndTime, executionEndTime));
			writeAudit();
		}
		throw new IllegalStateException(message + "; restored behavioural qsim.endTime=" + behaviouralEndTime);
	}

	private void restoreBehaviouralHorizon() {
		config.qsim().setEndTime(behaviouralEndTime);
		if (!sameTime(requireQsimEndTime(), behaviouralEndTime)) {
			throw new IllegalStateException("Could not restore Plan-A behavioural qsim.endTime="
					+ behaviouralEndTime);
		}
	}

	private double requireQsimEndTime() {
		return config.qsim().getEndTime().orElseThrow(
				() -> new IllegalStateException("Plan-A split horizon requires a defined qsim.endTime"));
	}

	private void writeAudit() {
		AuditRecord latest = null;
		for (AuditRecord record : records.values()) {
			latest = record;
		}
		if (latest == null) {
			throw new IllegalStateException("Refusing to write an empty Plan-A execution-horizon receipt");
		}

		try {
			Files.createDirectories(auditPath.getParent());
			Path temporary = auditPath.resolveSibling(auditPath.getFileName() + ".tmp");
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
					BufferedWriter writer = new BufferedWriter(
							Channels.newWriter(channel, StandardCharsets.UTF_8))) {
				writer.write(toJson(latest));
				writer.flush();
				channel.force(true);
			}
			try {
				Files.move(temporary, auditPath, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, auditPath, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException error) {
			throw new UncheckedIOException("Could not write Plan-A execution-horizon audit: " + auditPath, error);
		}
	}

	private String toJson(AuditRecord latest) {
		StringBuilder json = new StringBuilder(512 + records.size() * 240);
		json.append("{\n")
				.append("  \"schema\": \"").append(AUDIT_SCHEMA).append("\",\n")
				.append("  \"execution_phase\": \"").append(latest.executionPhase).append("\",\n")
				.append("  \"iteration\": ").append(latest.iteration).append(",\n")
				.append("  \"behavioral_horizon_before_seconds\": ")
				.append(number(latest.behaviouralBeforeSeconds)).append(",\n")
				.append("  \"qsim_execution_horizon_seconds\": ")
				.append(number(latest.qsimExecutionSeconds)).append(",\n")
				.append("  \"behavioral_horizon_restored_seconds\": ")
				.append(nullableNumber(latest.behaviouralRestoredSeconds)).append(",\n")
				.append("  \"restored_after_mobsim\": ").append(latest.restoredAfterMobsim).append(",\n")
				.append("  \"records\": [\n");
		int index = 0;
		for (AuditRecord record : records.values()) {
			if (index++ > 0) {
				json.append(",\n");
			}
			json.append("    {\"iteration\": ").append(record.iteration)
					.append(", \"execution_phase\": \"").append(record.executionPhase)
					.append("\", \"behavioral_horizon_before_seconds\": ")
					.append(number(record.behaviouralBeforeSeconds))
					.append(", \"qsim_execution_horizon_seconds\": ")
					.append(number(record.qsimExecutionSeconds))
					.append(", \"behavioral_horizon_restored_seconds\": ")
					.append(nullableNumber(record.behaviouralRestoredSeconds))
					.append(", \"restored_after_mobsim\": ").append(record.restoredAfterMobsim)
					.append("}");
		}
		return json.append("\n  ]\n}\n").toString();
	}

	private static boolean sameTime(double left, double right) {
		return Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
	}

	private static String number(double value) {
		return Double.toString(value);
	}

	private static String nullableNumber(Double value) {
		return value == null ? "null" : number(value);
	}

	private record AuditRecord(
			int iteration,
			String executionPhase,
			double behaviouralBeforeSeconds,
			double qsimExecutionSeconds,
			Double behaviouralRestoredSeconds,
			boolean restoredAfterMobsim) {

		static AuditRecord active(int iteration, double behavioural, double execution) {
			return new AuditRecord(iteration, QSIM_ACTIVE, behavioural, execution, null, false);
		}

		static AuditRecord restored(int iteration, double behavioural, double execution) {
			return new AuditRecord(iteration, BEHAVIOURAL_RESTORED, behavioural, execution, behavioural, true);
		}

		static AuditRecord failClosed(int iteration, double behavioural, double execution) {
			return new AuditRecord(iteration, FAIL_CLOSED_RESTORED, behavioural, execution, behavioural, true);
		}
	}
}
