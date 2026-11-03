package org.matsim.run.ismt;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only validation of one run's standardized scientific artifacts, not matrix equivalence. */
public interface RunValidator {
	ValidationResult validate(Path runRoot, ExperimentSpec spec);

	record ValidationResult(boolean valid, Map<String, Boolean> checks, List<String> issues,
			int finalIteration, int activationCount, int activationIteration,
			Map<Integer, PassengerScientificFingerprint.Fingerprint> preActivationFingerprint,
			Map<String, Long> artifactRowCounts) {
		public ValidationResult {
			checks = Collections.unmodifiableMap(new LinkedHashMap<>(checks));
			issues = List.copyOf(issues);
			preActivationFingerprint = Collections.unmodifiableMap(new LinkedHashMap<>(preActivationFingerprint));
			artifactRowCounts = Collections.unmodifiableMap(new LinkedHashMap<>(artifactRowCounts));
			if (valid && (!issues.isEmpty() || checks.values().stream().anyMatch(value -> !value))) {
				throw new IllegalArgumentException("A valid result cannot contain failed validation gates");
			}
		}
	}
}
