package org.matsim.run.examples;

import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.PassengerFreightCoTransportModule;

import java.io.IOException;
import java.nio.file.Path;

public final class VSCodeRunHangzhouHighDemandCoTransport {
	private static final double FREIGHT_SERVICE_WINDOW_START = 13.5 * 3600.0;
	private static final double FREIGHT_SERVICE_WINDOW_END = 16.5 * 3600.0;
	private static final double DEFAULT_COMFORT_THRESHOLD = 0.60;
	private static final double DEFAULT_PASSENGER_DENIED_BOARDING_PENALTY = 12.0;
	private static final double DEFAULT_PASSENGER_CROWDING_PENALTY_PER_MINUTE = 0.80;

	private VSCodeRunHangzhouHighDemandCoTransport() {
	}

	public static void main(String[] args) throws IOException {
		String scenarioName = args.length > 0 ? args[0] : "hangzhou-high-demand-cotransport";
		double comfortThreshold = doubleSetting("hangzhou.cotransport.comfortThreshold",
				"HANGZHOU_COTRANSPORT_COMFORT_THRESHOLD", DEFAULT_COMFORT_THRESHOLD);
		double passengerDeniedBoardingPenalty = doubleSetting("hangzhou.cotransport.passengerDeniedBoardingPenalty",
				"HANGZHOU_PASSENGER_DENIED_BOARDING_PENALTY", DEFAULT_PASSENGER_DENIED_BOARDING_PENALTY);
		double passengerCrowdingPenaltyPerMinute = doubleSetting("hangzhou.cotransport.passengerCrowdingPenaltyPerMinute",
				"HANGZHOU_PASSENGER_CROWDING_PENALTY_PER_MIN", DEFAULT_PASSENGER_CROWDING_PENALTY_PER_MINUTE);
		RunExampleFromVSCode.run(scenarioName, "config.xml", (controler, scenarioDirectory) -> {
			Path orders = scenarioDirectory.resolve("cotransport/high_demand_freight_orders.csv");
			controler.getConfig().controller().setCreateGraphsInterval(0);
			controler.getConfig().vspExperimental().setGeneratingBoardingDeniedEvent(true);
			controler.addOverridingModule(new PassengerFreightCoTransportModule(new CoTransportSettings(
					orders, 5.0, comfortThreshold, 0.95, 0.55, 10.0, 25.0,
					FREIGHT_SERVICE_WINDOW_START, FREIGHT_SERVICE_WINDOW_END, passengerDeniedBoardingPenalty,
					passengerCrowdingPenaltyPerMinute, "freight_", "_cap_",
					null)));
		});
	}

	private static double doubleSetting(String property, String env, double fallback) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			value = System.getenv(env);
		}
		return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
	}
}
