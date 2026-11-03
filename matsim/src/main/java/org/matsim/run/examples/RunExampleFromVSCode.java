package org.matsim.run.examples;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

final class RunExampleFromVSCode {
	private RunExampleFromVSCode() {
	}

	static void run(String scenarioName, String configFileName) throws IOException {
		run(scenarioName, configFileName, (controler, scenarioDirectory) -> {
		});
	}

	static void run(String scenarioName, String configFileName, ControllerCustomizer customizer) throws IOException {
		run(scenarioName, configFileName, customizer, new ConfigGroup[0]);
	}

	static void run(String scenarioName, String configFileName, ControllerCustomizer customizer,
			ConfigGroup... additionalConfigGroups) throws IOException {
		Path matsimRoot = findMatsimRoot();
		Path scenarioSource = matsimRoot.resolve("examples/scenarios").resolve(scenarioName);
		Path runDirectory = matsimRoot.getParent()
				.resolve("run-output/vscode-click-run")
				.resolve(scenarioName);
		Path scenarioDirectory = runDirectory.resolve("scenario");
		Path outputDirectory = scenarioDirectory.resolve("output");

		if (!Files.isDirectory(scenarioSource)) {
			throw new IllegalStateException("Scenario not found: " + scenarioSource);
		}

		deleteDirectory(runDirectory);
		copyDirectory(scenarioSource, scenarioDirectory);

		Path configFile = scenarioDirectory.resolve(configFileName);
		if (!Files.isRegularFile(configFile)) {
			throw new IllegalStateException("Config not found: " + configFile);
		}

		System.out.println("MATSim root: " + matsimRoot);
		System.out.println("Scenario: " + scenarioName);
		System.out.println("Config: " + configFile);
		System.out.println("Output: " + outputDirectory);

		Config config = ConfigUtils.loadConfig(configFile.toString(), additionalConfigGroups);
		config.controller().setOutputDirectory(outputDirectory.toString());
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);

		Controler controler = new Controler(config);
		customizer.customize(controler, scenarioDirectory);
		controler.run();
	}

	/**
	 * Runs an already-materialized scenario without copying it or deleting any directory.
	 *
	 * <p>This is intended for registered experiments where the scenario inputs and the
	 * output location are separate provenance artifacts. Both locations must be absolute;
	 * the output directory must not exist at launch time. These checks make reruns fail
	 * closed instead of overwriting a previous result.</p>
	 */
	static void runExistingScenario(Path scenarioDirectory, Path outputDirectory, String configFileName,
			ControllerCustomizer customizer, ConfigGroup... additionalConfigGroups) throws IOException {
		if (!scenarioDirectory.isAbsolute()) {
			throw new IllegalArgumentException("Scenario directory must be absolute: " + scenarioDirectory);
		}
		if (!outputDirectory.isAbsolute()) {
			throw new IllegalArgumentException("Output directory must be absolute: " + outputDirectory);
		}
		if (configFileName == null || configFileName.isBlank() || Path.of(configFileName).isAbsolute()) {
			throw new IllegalArgumentException("Config file name must be a non-blank relative path: " + configFileName);
		}

		Path normalizedScenarioDirectory = scenarioDirectory.normalize();
		Path normalizedOutputDirectory = outputDirectory.normalize();
		if (!Files.isDirectory(normalizedScenarioDirectory)) {
			throw new IllegalStateException("Scenario directory not found: " + normalizedScenarioDirectory);
		}
		if (normalizedOutputDirectory.startsWith(normalizedScenarioDirectory)
				|| normalizedScenarioDirectory.startsWith(normalizedOutputDirectory)) {
			throw new IllegalArgumentException("Scenario and output directories must be separate: scenario="
					+ normalizedScenarioDirectory + ", output=" + normalizedOutputDirectory);
		}
		if (Files.exists(normalizedOutputDirectory)) {
			throw new IllegalStateException("Refusing to reuse an existing output directory: " + normalizedOutputDirectory);
		}

		Path configFile = normalizedScenarioDirectory.resolve(configFileName).normalize();
		if (!configFile.startsWith(normalizedScenarioDirectory) || !Files.isRegularFile(configFile)) {
			throw new IllegalStateException("Config not found inside scenario directory: " + configFile);
		}

		System.out.println("Scenario: " + normalizedScenarioDirectory);
		System.out.println("Config: " + configFile);
		System.out.println("Output: " + normalizedOutputDirectory);

		Config config = ConfigUtils.loadConfig(configFile.toString(), additionalConfigGroups);
		config.controller().setOutputDirectory(normalizedOutputDirectory.toString());
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists);

		Controler controler = new Controler(config);
		customizer.customize(controler, normalizedScenarioDirectory);
		controler.run();
	}

	@FunctionalInterface
	interface ControllerCustomizer {
		void customize(Controler controler, Path scenarioDirectory);
	}

	private static Path findMatsimRoot() {
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

		for (Path path = current; path != null; path = path.getParent()) {
			if (Files.isDirectory(path.resolve("matsim/src/main/java"))
					&& Files.isDirectory(path.resolve("examples/scenarios"))) {
				return path;
			}
			Path child = path.resolve("matsim-libs-2026.0");
			if (Files.isDirectory(child.resolve("matsim/src/main/java"))
					&& Files.isDirectory(child.resolve("examples/scenarios"))) {
				return child;
			}
		}

		throw new IllegalStateException("Could not find matsim-libs-2026.0 from " + current);
	}

	private static void copyDirectory(Path source, Path target) throws IOException {
		Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
			@Override
			public java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(target.resolve(source.relativize(dir)));
				return java.nio.file.FileVisitResult.CONTINUE;
			}

			@Override
			public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
				return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
	}

	private static void deleteDirectory(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			return;
		}

		Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
			@Override
			public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return java.nio.file.FileVisitResult.CONTINUE;
			}

			@Override
			public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				Files.delete(dir);
				return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
	}
}
