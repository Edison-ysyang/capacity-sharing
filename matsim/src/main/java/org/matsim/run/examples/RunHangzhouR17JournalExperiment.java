package org.matsim.run.examples;

import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.contrib.cotransport.PassengerFreightCoTransportModule;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;

import java.io.IOException;

/** Config-driven entry point shared by all R17 journal experiment arms. */
public final class RunHangzhouR17JournalExperiment {
	private RunHangzhouR17JournalExperiment() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length == 0 || args[0].isBlank()) {
			throw new IllegalArgumentException("Usage: RunHangzhouR17JournalExperiment <scenarioName> [configFileName]");
		}
		String scenarioName = args[0];
		String configFileName = args.length > 1 ? args[1] : "config.xml";
		RunExampleFromVSCode.run(scenarioName, configFileName, (controler, scenarioDirectory) -> {
			configureR17CoTransport(controler, configFileName);
		}, new CoTransportConfigGroup());
	}

	static void configureR17CoTransport(Controler controler, String configFileName) {
		CoTransportConfigGroup coTransport = ConfigUtils.addOrGetModule(
				controler.getConfig(), CoTransportConfigGroup.class);
		if (!coTransport.hasFreightOrdersFile()) {
			throw new IllegalStateException("R17 requires a serialized <module name=\"coTransport\"> in "
					+ configFileName + " with freightOrdersFile configured");
		}
		controler.getConfig().vspExperimental().setGeneratingBoardingDeniedEvent(true);
		controler.addOverridingModule(new PassengerFreightCoTransportModule());
	}
}
