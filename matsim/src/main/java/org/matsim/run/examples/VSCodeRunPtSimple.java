package org.matsim.run.examples;

import java.io.IOException;

public final class VSCodeRunPtSimple {
	private VSCodeRunPtSimple() {
	}

	public static void main(String[] args) throws IOException {
		RunExampleFromVSCode.run("pt-simple", "config.xml");
	}
}
