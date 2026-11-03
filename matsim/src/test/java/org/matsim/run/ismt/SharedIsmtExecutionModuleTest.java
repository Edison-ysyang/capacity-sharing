package org.matsim.run.ismt;

import com.google.inject.Guice;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.google.inject.spi.InstanceBinding;
import com.google.inject.spi.LinkedKeyBinding;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.cotransport.CoTransportBoardingPolicy;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.contrib.cotransport.CoTransportPhaseController;
import org.matsim.contrib.cotransport.CoTransportRuntimeMonitor;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.IterationEventFingerprint;
import org.matsim.contrib.cotransport.PassengerOnlyIterationStats;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.listener.ControllerListener;
import org.matsim.run.r17.PlanAExecutionHorizonControllerListeners;
import org.matsim.run.r17.PlanAFeasibilityAudit;
import org.matsim.run.r17.PlanAInitialSelectedPlanCanonicalizer;
import org.matsim.dsim.ExecutionContext;
import org.matsim.dsim.LocalContext;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Binding inspection only; native runner equivalence is covered by separate integration fixtures. */
class SharedIsmtExecutionModuleTest {
    @Test void defaultAndExplicitNullReadSerializedSettingsWithoutReplacingThem() {
        for (SharedIsmtExecutionModule module : List.of(new SharedIsmtExecutionModule(),
                new SharedIsmtExecutionModule(null))) {
            Config config = config();
            CoTransportConfigGroup co = ConfigUtils.addOrGetModule(config, CoTransportConfigGroup.class);
            co.setFreightShareCap(0.13);
            co.setPassengerLoadGate(0.83);
            CoTransportSettings bound = settings(elements(config, module));
            assertEquals(0.13, bound.freightShareCap(), 0);
            assertEquals(0.83, bound.passengerLoadGate(), 0);
            assertEquals(0.13, co.getFreightShareCap(), 0);
            assertEquals(0.83, co.getPassengerLoadGate(), 0);
        }
    }

    @Test void explicitSettingsRetainInstanceAndAreMaterializedIntoConfiguration() {
        Config config = config();
        CoTransportSettings explicit = CoTransportSettings.builder(Path.of("shared-module-test-orders.csv"))
                .freightShareCap(0.24).passengerLoadGate(0.77).build();
        assertSame(explicit, settings(elements(config, new SharedIsmtExecutionModule(explicit))));
        CoTransportConfigGroup co = ConfigUtils.addOrGetModule(config, CoTransportConfigGroup.class);
        assertEquals(0.24, co.getFreightShareCap(), 0);
        assertEquals(0.77, co.getPassengerLoadGate(), 0);
    }

    @Test void sharedListenerRegistrationOrderRemainsPassengerFreightThenPlanA() {
        var elements = elements(config(), new SharedIsmtExecutionModule());
        var listeners = elements.stream()
                .filter(LinkedKeyBinding.class::isInstance)
                .map(element -> (LinkedKeyBinding<?>) element)
                .filter(binding -> binding.getKey().getTypeLiteral().getRawType() == ControllerListener.class)
                .map(binding -> binding.getLinkedKey().getTypeLiteral().getRawType()).toList();
        assertEquals(List.of(PassengerOnlyIterationStats.class, CoTransportPhaseController.class,
                CoTransportBoardingPolicy.class, CoTransportRuntimeMonitor.class,
                IterationEventFingerprint.class, PlanAFeasibilityAudit.class,
                PlanAInitialSelectedPlanCanonicalizer.class,
                PlanAExecutionHorizonControllerListeners.Before.class,
                PlanAExecutionHorizonControllerListeners.After.class), listeners);
        assertTrue(elements.stream().filter(com.google.inject.Binding.class::isInstance)
                .map(element -> (com.google.inject.Binding<?>) element)
                .noneMatch(binding -> binding.getKey().getTypeLiteral().getRawType().getName()
                        .startsWith("org.matsim.run.ismt.dynamic.")),
                "The common lifecycle must not install a dynamic arm or controller implicitly");
    }

    private static Config config() {
        Config config = ConfigUtils.createConfig(new CoTransportConfigGroup());
        config.transit().setUseTransit(false);
        ConfigUtils.addOrGetModule(config, CoTransportConfigGroup.class)
                .setFreightOrdersFile("shared-module-test-orders.csv");
        return config;
    }

    private static List<Element> elements(Config config, SharedIsmtExecutionModule module) {
        var bootstrap = Guice.createInjector(new com.google.inject.AbstractModule() {
            @Override protected void configure() {
                bind(Config.class).toInstance(config);
                bind(ExecutionContext.class).toInstance(LocalContext.create(config));
            }
        });
        bootstrap.injectMembers(module);
        return Elements.getElements(module);
    }

    private static CoTransportSettings settings(List<Element> elements) {
        var bindings = elements.stream().filter(InstanceBinding.class::isInstance)
                .map(element -> (InstanceBinding<?>) element)
                .filter(binding -> binding.getKey().getTypeLiteral().getRawType() == CoTransportSettings.class)
                .toList();
        assertEquals(1, bindings.size(), "Exactly one shared settings binding is required");
        return (CoTransportSettings) bindings.getFirst().getInstance();
    }
}
