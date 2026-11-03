package org.matsim.run.ismt;

import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorStopFinder;
import com.google.inject.matcher.Matchers;
import com.google.inject.spi.ProvisionListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Injector;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.router.TripRouter;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-injector regression for the dormant freight router's effect on passenger RNGs.
 * No QSim, replacement router, replacement stop finder, or policy-effect estimate is used.
 */
class IsmtPreactivationRoutingRandomTest {
    @TempDir Path temporaryDirectory;

    @Test
    void allArmsConstructTheSamePassengerRoutersAndPreserveTheFollowingRandomStream() throws Exception {
        Path workspace = temporaryDirectory.toRealPath();
        var fixture = IsmtFourArmFixture.create(workspace, Files.createDirectory(workspace.resolve("fixture")));
        var observations = new EnumMap<ExperimentSpec.Arm, List<ConstructionObservation>>(ExperimentSpec.Arm.class);

        for (var arm : ExperimentSpec.Arm.values()) {
            var spec = fixture.specs().get(arm);
            var config = ExperimentConfiguration.create(spec, workspace, workspace.resolve("probe_" + arm));
            assertEquals(Set.of("pt", "freight_pt"), config.transit().getTransitModes(),
                    "The regression must retain the formal all-arm two-mode contract");
            assertEquals(3.0, config.routing().getRoutingRandomness(), 0.0);
            var scenario = ScenarioUtils.loadScenario(config);
            assertTrue(scenario.getTransitSchedule().getTransitLines().keySet().stream()
                    .noneMatch(id -> id.toString().equals(IsmtFourArmFixture.DEDICATED_LINE)),
                    "The live schedule must still be the passenger baseline");

            var observer = new StopFinderObserver();
            var injector = new Injector.InjectorBuilder(scenario)
                    .addStandardModules()
                    .addOverridingModule(new IsmtControllerModule(spec))
                    .addModule(new AbstractModule() {
                        @Override public void install() {
                            binder().bindListener(Matchers.any(), observer);
                        }
                    }).build();

            var armObservations = new ArrayList<ConstructionObservation>();
            // Repeat the construction to catch an accidental first-use-only equality.
            // Reset immediately before requesting TripRouter, as the controller does
            // before the iteration-0 canonicalizer constructs its routing modules.
            for (int attempt = 0; attempt < 3; attempt++) {
                MatsimRandom.reset(3000);
                MatsimRandom.getRandom().nextDouble();
                int before = observer.size();
                TripRouter router = injector.getInstance(TripRouter.class);
                assertTrue(router.getRegisteredModes().containsAll(Set.of("pt", "freight_pt")));
                int created = observer.size() - before;
                assertTrue(created > 0, "The test must observe actual passenger Raptor construction");
                var following = MatsimRandom.getLocalInstance();
                armObservations.add(new ConstructionObservation(created,
                        List.of(following.nextLong(), following.nextLong(), following.nextLong())));
            }
            observations.put(arm, List.copyOf(armObservations));
        }

        List<ConstructionObservation> control = observations.get(ExperimentSpec.Arm.CONTROL);
        assertAll("Dormant arm payloads must not shift passenger routing RNGs: " + observations,
                () -> assertEquals(control, observations.get(ExperimentSpec.Arm.MIXED), "Control / Mixed"),
                () -> assertEquals(control, observations.get(ExperimentSpec.Arm.TIMETABLE_ONLY), "Control / Timetable-only"),
                () -> assertEquals(control, observations.get(ExperimentSpec.Arm.DEDICATED), "Control / Dedicated"));
    }

    private record ConstructionObservation(int stopFindersCreated, List<Long> followingRandomValues) { }

    /** Observes real Guice provisions without altering their providers or random draws. */
    private static final class StopFinderObserver implements ProvisionListener {
        private final Set<DefaultRaptorStopFinder> instances =
                Collections.newSetFromMap(new IdentityHashMap<>());

        @Override public <T> void onProvision(ProvisionInvocation<T> provision) {
            T instance = provision.provision();
            if (instance instanceof DefaultRaptorStopFinder stopFinder) instances.add(stopFinder);
        }

        int size() { return instances.size(); }
    }
}
