package org.matsim.run.ismt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Standalone bounded cross-build probe; loaded with an explicitly selected application JAR. */
public final class RegisteredArmCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("prepare|run workspace inputs new-output");
        Path workspace = Path.of(args[1]).toRealPath(), input = Path.of(args[2]);
        System.out.println("APPLICATION_LOCATION=" + ExperimentRunner.class.getProtectionDomain().getCodeSource().getLocation());
        if (args[0].equals("prepare")) {
            Files.createDirectory(input);
            var fixture = IsmtFourArmFixture.create(workspace, input);
            for (var arm : List.of(ExperimentSpec.Arm.CONTROL, ExperimentSpec.Arm.MIXED, ExperimentSpec.Arm.DEDICATED))
                ExperimentSpecIO.writeNew(input.resolve(arm.name() + ".json"), fixture.specs().get(arm));
        } else if (args[0].equals("run")) {
            Path output = Path.of(args[3]); Files.createDirectory(output);
            for (var arm : List.of(ExperimentSpec.Arm.CONTROL, ExperimentSpec.Arm.MIXED, ExperimentSpec.Arm.DEDICATED)) {
                var spec = ExperimentSpecIO.read(input.resolve(arm.name() + ".json"));
                var prepared = ExperimentRunner.prepareSimulation(spec, workspace, output.resolve(arm.name()));
                prepared.controler().run();
            }
        } else throw new IllegalArgumentException("Unknown operation");
    }
}
