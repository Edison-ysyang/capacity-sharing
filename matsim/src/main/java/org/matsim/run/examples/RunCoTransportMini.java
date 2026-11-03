package org.matsim.run.examples;

import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.contrib.cotransport.PassengerFreightCoTransportModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Path;

/** A single-iteration, file-based example of the co-transport extension. */
public final class RunCoTransportMini {
    private RunCoTransportMini() { }

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: RunCoTransportMini <config.xml> <new-output-directory>");
        }
        Config config = ConfigUtils.loadConfig(args[0], new CoTransportConfigGroup());
        config.controller().setOutputDirectory(Path.of(args[1]).toAbsolutePath().toString());
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new PassengerFreightCoTransportModule());
        controler.run();
    }
}
