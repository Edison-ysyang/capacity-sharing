package org.matsim.run.ismt;

import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.cotransport.CoTransportConfigGroup;
import org.matsim.contrib.cotransport.CoTransportSettings;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.config.ConfigUtils;

import java.util.Objects;
import java.util.stream.StreamSupport;

/**
 * Reads the registered freight settings and reconciles an already loaded scenario.
 * This seam neither loads or changes plans nor installs an execution module, boarding
 * window, service supply, controller or predictor. In particular, declaring a freight
 * service window in the settings does not install a physical boarding-window policy.
 */
public final class SharedFreightScenarioPreparation {
    private SharedFreightScenarioPreparation() { }

    /** Preserve each registered runner family's validation and failure precedence. */
    public enum ValidationOrder {
        REGISTERED_V1_DEMAND_FIRST,
        REGISTERED_V2_POPULATION_FIRST
    }

    public record Prepared(CoTransportSettings settings, FreightOrderRegistry registry,
                           FreightOrderRegistry.PopulationReconciliation conservation) { }

    public static Prepared prepare(Scenario scenario, int declaredOrderCount, ValidationOrder validationOrder) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(validationOrder, "validationOrder");
        var config = scenario.getConfig();
        var settings = ConfigUtils.addOrGetModule(config, CoTransportConfigGroup.class).toSettings(config);
        var registry = new FreightOrderRegistry(settings);
        FreightOrderRegistry.PopulationReconciliation conservation;
        if (validationOrder == ValidationOrder.REGISTERED_V1_DEMAND_FIRST) {
            if (StreamSupport.stream(registry.orders().spliterator(), false).count() != declaredOrderCount)
                throw new IllegalStateException("Declared demand differs from the execution order table");
            registry.validateFixedPlans(scenario.getPopulation());
            conservation = registry.validatePopulation(scenario.getPopulation());
        } else {
            conservation = registry.validatePopulation(scenario.getPopulation());
            if (conservation.orders() != declaredOrderCount)
                throw new IllegalStateException("Declared freight demand differs from the execution order table");
            registry.validateFixedPlans(scenario.getPopulation());
        }
        // Append after the registered legacy guards to preserve UNIT_SPLIT failure precedence.
        registry.validateWholeOrderPlans(scenario.getPopulation());
        return new Prepared(settings, registry, conservation);
    }
}
