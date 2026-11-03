package org.matsim.run.examples;

import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.PassengerFreightCoTransportModule;

import java.io.IOException;
import java.nio.file.Path;

public final class VSCodeRunHangzhouGuihuaWeiyeFreight {
	private VSCodeRunHangzhouGuihuaWeiyeFreight() {
	}

	public static void main(String[] args) throws IOException {
		RunExampleFromVSCode.run("hangzhou-guihua-weiye-freight", "config.xml", (controler, scenarioDirectory) -> {
			Path orders = scenarioDirectory.resolve("cotransport/guihua_weiye_freight_orders.csv");
			controler.getConfig().vspExperimental().setGeneratingBoardingDeniedEvent(true);
			controler.addOverridingModule(new PassengerFreightCoTransportModule(new CoTransportSettings(orders)));
		});
	}
}
