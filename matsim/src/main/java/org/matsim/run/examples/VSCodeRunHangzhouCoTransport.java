package org.matsim.run.examples;

import java.io.IOException;

public final class VSCodeRunHangzhouCoTransport {
	private VSCodeRunHangzhouCoTransport() {
	}

	public static void main(String[] args) throws IOException {
		RunExampleFromVSCode.run("hangzhou-co-transport", "config.xml");
	}
}
