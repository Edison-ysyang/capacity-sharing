package org.matsim.run.examples;

import org.matsim.contrib.cotransport.PassengerOnlyAnalysisModule;

import java.io.IOException;

public final class VSCodeRunHangzhouHighDemandBaseline {
	private VSCodeRunHangzhouHighDemandBaseline() {
	}

	public static void main(String[] args) throws IOException {
		String scenarioName = args.length > 0 ? args[0] : "hangzhou-high-demand-baseline";
		RunExampleFromVSCode.run(scenarioName, "config.xml", (controler, scenarioDirectory) -> {
			controler.getConfig().controller().setCreateGraphsInterval(0);
			controler.addOverridingModule(new PassengerOnlyAnalysisModule());
			if (scenarioName.endsWith("-direct")) {
				controler.getConfig().controller().setFirstIteration(0);
				controler.getConfig().controller().setLastIteration(0);
			}
		});
	}
}
