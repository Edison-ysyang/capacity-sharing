package org.matsim.run.examples;

import java.io.IOException;

public final class VSCodeRunEquil {
	private VSCodeRunEquil() {
	}

	public static void main(String[] args) throws IOException {
		RunExampleFromVSCode.run("equil", "config.xml");
	}
}
