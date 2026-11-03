package org.matsim.run.examples;

import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.PassengerFreightCoTransportModule;

import java.io.IOException;
import java.nio.file.Path;

public final class VSCodeRunHangzhouHighDemandDedicatedFreightSmoke {
	private static final double FREIGHT_SERVICE_WINDOW_START = 13.5 * 3600.0;
	private static final double FREIGHT_SERVICE_WINDOW_END = 16.5 * 3600.0;

	private VSCodeRunHangzhouHighDemandDedicatedFreightSmoke() {
	}

	public static void main(String[] args) throws IOException {
		RunExampleFromVSCode.run("hangzhou-high-demand-dedicated-freight", "config.xml", (controler, scenarioDirectory) -> {
			Path orders = scenarioDirectory.resolve("cotransport/high_demand_freight_orders.csv");
			controler.getConfig().controller().setLastIteration(0);
			controler.getConfig().controller().setCreateGraphsInterval(0);
			controler.getConfig().controller().setWritePlansInterval(0);
			controler.getConfig().controller().setWriteTripsInterval(1);
			controler.getConfig().vspExperimental().setGeneratingBoardingDeniedEvent(true);
			controler.addOverridingModule(new PassengerFreightCoTransportModule(new CoTransportSettings(
					orders, 5.0, 0.70, 0.95, 0.55, 10.0, 25.0,
					FREIGHT_SERVICE_WINDOW_START, FREIGHT_SERVICE_WINDOW_END, 5.0, 0.28, "freight_", "_cap_",
					"hangzhou_metro_line_6_dedicated_freight")));
		});
	}
}
