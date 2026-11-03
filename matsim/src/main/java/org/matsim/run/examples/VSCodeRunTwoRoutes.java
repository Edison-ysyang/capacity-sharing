package org.matsim.run.examples;

import java.io.IOException;

public final class VSCodeRunTwoRoutes {
	private VSCodeRunTwoRoutes() {
	}

	public static void main(String[] args) throws IOException {
		RunExampleFromVSCode.run("two-routes", "config.xml");
	}
}
