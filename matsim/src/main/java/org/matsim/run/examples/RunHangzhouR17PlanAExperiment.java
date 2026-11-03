package org.matsim.run.examples;

import com.google.inject.Singleton;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.run.r17.PlanAFeasibilityAudit;
import org.matsim.run.r17.PlanAInitialSelectedPlanCanonicalizer;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Launches a materialized R17 Plan A scenario with immutable inputs and a new output directory.
 *
 * <p>Usage: {@code RunHangzhouR17PlanAExperiment <absoluteScenarioDirectory>
 * <absoluteOutputDirectory> [configFileName]}</p>
 */
public final class RunHangzhouR17PlanAExperiment {
	private RunHangzhouR17PlanAExperiment() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 2 || args.length > 3) {
			throw new IllegalArgumentException("Usage: RunHangzhouR17PlanAExperiment "
					+ "<absoluteScenarioDirectory> <absoluteOutputDirectory> [configFileName]");
		}

		Path scenarioDirectory = Path.of(args[0]);
		Path outputDirectory = Path.of(args[1]);
		String configFileName = args.length == 3 ? args[2] : "config.xml";
		RunExampleFromVSCode.runExistingScenario(scenarioDirectory, outputDirectory, configFileName,
				(controler, ignoredScenarioDirectory) -> {
					RunHangzhouR17JournalExperiment.configureR17CoTransport(controler, configFileName);
					controler.addOverridingModule(new AbstractModule() {
						@Override
						public void install() {
							// MATSim disables just-in-time bindings.  Bind the shared
							// audit explicitly so both custom FQCN strategies and the
							// controller listener receive the same instance.
							bind(PlanAFeasibilityAudit.class).in(Singleton.class);
							addControllerListenerBinding().to(PlanAFeasibilityAudit.class);
							// Canonicalize every selected passenger plan after PrepareForSim
							// has created vehicles/routes and before iteration-0 QSim begins.
							// This is the Option-A v6.2 original-timetable feasibility
							// contract; it is ordered ahead of the audit listener.
							addControllerListenerBinding().to(PlanAInitialSelectedPlanCanonicalizer.class);
						}
					});
				},
				new CoTransportConfigGroup());
	}
}
