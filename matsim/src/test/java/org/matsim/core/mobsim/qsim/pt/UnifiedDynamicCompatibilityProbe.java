package org.matsim.core.mobsim.qsim.pt;

import java.nio.file.Files;
import java.nio.file.Path;
import org.matsim.run.ismt.dynamic.DynamicMixedControllerModule;

/** Reuses the six-day real Controller fixture under old and unified runtime JARs. */
public final class UnifiedDynamicCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("new-output-directory");
        System.out.println("APPLICATION_LOCATION=" + DynamicMixedControllerModule.class.getProtectionDomain().getCodeSource().getLocation());
        var test = new DynamicMixedControllerModuleNativeTest();
        test.temporary = Files.createDirectory(Path.of(args[0]));
        test.realControllerPreservesPassiveEquivalenceAndRunsDynamicMixedControlAfterInnovationCloses();
    }
}
