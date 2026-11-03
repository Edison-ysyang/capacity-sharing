package org.matsim.run.examples;

import com.google.inject.Singleton;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.run.r17.PlanAExecutionHorizonConfigGroup;
import org.matsim.run.r17.PlanAExecutionHorizonControllerListeners;
import org.matsim.run.r17.PlanAExecutionHorizonSwitch;
import org.matsim.run.r17.PlanAFeasibilityAudit;
import org.matsim.run.r17.PlanAInitialSelectedPlanCanonicalizer;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs the immutable R17 Plan-A bindings with separate behavioural and QSim horizons.
 *
 * <p>The input config keeps {@code qsim.endTime=30:00:00}. The additional
 * {@code planAExecutionHorizon.executionEndTime} parameter defaults to
 * {@code 36:00:00}; it is exposed to QSim only, then immediately restored
 * before scoring and replanning.</p>
 *
 * <p>Usage: {@code RunHangzhouR17PlanASplitHorizonExperiment
 * <absoluteScenarioDirectory> <absoluteOutputDirectory> [configFileName]}</p>
 */
public final class RunHangzhouR17PlanASplitHorizonExperiment {
	private RunHangzhouR17PlanASplitHorizonExperiment() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 2 || args.length > 3) {
			throw new IllegalArgumentException("Usage: RunHangzhouR17PlanASplitHorizonExperiment "
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
							/*
							 * Preserve the existing Plan-A bindings verbatim: the
							 * singleton audit is shared with both FQCN strategies and
							 * the iteration-0 canonicalizer precedes its audit.
							 */
							bind(PlanAFeasibilityAudit.class).in(Singleton.class);
							addControllerListenerBinding().to(PlanAFeasibilityAudit.class);
							addControllerListenerBinding().to(PlanAInitialSelectedPlanCanonicalizer.class);

							/*
							 * Opposite listener priorities guarantee the temporary
							 * execution-only horizon is installed last before QSim and
							 * restored first after QSim.
							 */
							bind(PlanAExecutionHorizonSwitch.class).in(Singleton.class);
							addControllerListenerBinding()
									.to(PlanAExecutionHorizonControllerListeners.Before.class)
									.in(Singleton.class);
							addControllerListenerBinding()
									.to(PlanAExecutionHorizonControllerListeners.After.class)
									.in(Singleton.class);
						}
					});
				},
				new CoTransportConfigGroup(),
				new PlanAExecutionHorizonConfigGroup());
	}
}
