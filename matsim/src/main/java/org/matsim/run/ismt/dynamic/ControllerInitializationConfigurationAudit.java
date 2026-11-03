package org.matsim.run.ismt.dynamic;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.consistency.ConfigConsistencyCheckerImpl;
import org.matsim.core.config.consistency.UnmaterializedConfigGroupChecker;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.pt.config.TransitConfigGroup;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Bounded, configuration-only replay of two native initialization effects. The native Controler
 * checks consistency before creating its injector; ScoringConfigGroup then appends missing unscored
 * interaction activities. When transit uses SwissRailRaptor, TransitRouterModule installs
 * SwissRailRaptorModule, whose configuration effect is addOrGetModule(SwissRailRaptorConfigGroup).
 * This class calls those configuration APIs in that order, not the Controller, injector or router.
 *
 * <p>The suppliers MUST return fresh, exclusively owned configurations, not a live/shared run's
 * Config. This method consumes/mutates those configurations, including on failure. It does not load
 * Scenario, population, plans or network, write files, create output directories, or run simulation.
 * All raw and intermediate full-tree snapshots/differences remain in the report. No parameter,
 * module, position, strategy order or input path is filtered from comparison.</p>
 *
 * <p>Consistency is allowed to append ONLY the exact native default interaction entries missing for
 * the configured network/scoring modes. Any other visible consistency mutation fails closed. Raptor
 * materialization must exactly reproduce its typed defaults plus every existing explicit value and
 * ordered parameter set. This deliberately does not support arbitrary other module initialization
 * effects. Raw explicit values remain available even when native parsing canonicalizes their text.
 * Exposed-tree checks do not certify hidden object state, arbitrary supplier/checker side effects,
 * historical binary identity, actual injection, full lifecycle equivalence or reuse eligibility.</p>
 */
public final class ControllerInitializationConfigurationAudit {
    public static final String SCHEMA = "ismt-controller-config-initialization-audit-v1";
    private static final String SCORING = "/modules/=scoring/parameterSets/=scoringParameters";
    private static final String RAPTOR = "/modules/=swissRailRaptor";
    private ControllerInitializationConfigurationAudit() { }

    public record Tree(String schemaVersion, String sha256, Map<String, String> fields) {
        public Tree { fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields)); }
    }
    public record Stage(String operation, Tree before, Tree after,
                        List<ResolvedNonDispatchConfiguration.Difference> changes) {
        public Stage { changes = List.copyOf(changes); }
    }
    public record Side(Tree raw, List<Stage> stages, Tree initialized) {
        public Side { stages = List.copyOf(stages); }
    }
    public record Report(String schemaVersion, Side historical, Side current,
                         List<ResolvedNonDispatchConfiguration.Difference> rawDifferences,
                         List<ResolvedNonDispatchConfiguration.Difference> initializedDifferences) {
        public Report {
            if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported schema");
            Objects.requireNonNull(historical); Objects.requireNonNull(current);
            rawDifferences = List.copyOf(rawDifferences);
            initializedDifferences = List.copyOf(initializedDifferences);
        }
        public boolean rawStrictlyEqual() { return rawDifferences.isEmpty(); }
        public boolean initializedStrictlyEqual() { return initializedDifferences.isEmpty(); }
        public boolean actualControllerLifecycleVerified() { return false; }
        public boolean sourceBuildRuntimeCompatibilityVerified() { return false; }
        public boolean historicalReuseApproved() { return false; }
        public boolean simulationStarted() { return false; }
    }
    private record Result(Side side, ResolvedNonDispatchConfiguration.Snapshot raw,
                          ResolvedNonDispatchConfiguration.Snapshot initialized) { }

    /** Suppliers are invoked once each, before either configuration is changed. See ownership contract. */
    public static Report compareFresh(Supplier<Config> historicalLoader, Supplier<Config> currentLoader) {
        Config historical = Objects.requireNonNull(Objects.requireNonNull(historicalLoader).get());
        Config current = Objects.requireNonNull(Objects.requireNonNull(currentLoader).get());
        if (historical == current) throw new IllegalArgumentException("Require independent fresh configurations");
        requireDisjointGroups(historical, current);
        // Capture both raw trees before either replay, then check again when consumed.
        var oldRaw = ResolvedNonDispatchConfiguration.snapshot(historical);
        var newRaw = ResolvedNonDispatchConfiguration.snapshot(current);
        Result oldResult = replay(historical, oldRaw);
        Result newResult = replay(current, newRaw);
        return new Report(SCHEMA, oldResult.side(), newResult.side(),
                ResolvedNonDispatchConfiguration.compare(oldRaw, newRaw).differences(),
                ResolvedNonDispatchConfiguration.compare(oldResult.initialized(), newResult.initialized()).differences());
    }

    private static Result replay(Config config, ResolvedNonDispatchConfiguration.Snapshot raw) {
        requireTree(raw.fields(), ResolvedNonDispatchConfiguration.snapshot(config), "Fresh configuration drift");
        var expectedConsistency = expectedAfterConsistency(config, raw);
        // Same configuration-checker order as Controler construction/createInjector. No lock flags or
        // injector are constructed: they are not exposed configuration values or certified here.
        config.addConfigConsistencyChecker(new ConfigConsistencyCheckerImpl());
        config.removeConfigConsistencyChecker(UnmaterializedConfigGroupChecker.class);
        config.checkConsistency();
        config.addConfigConsistencyChecker(new UnmaterializedConfigGroupChecker());
        var consistent = ResolvedNonDispatchConfiguration.snapshot(config);
        requireTree(expectedConsistency, consistent, "Unsupported visible consistency mutation");

        var expectedRaptor = expectedAfterRaptor(config, consistent);
        if (config.transit().isUseTransit()) {
            if (config.transit().getRoutingAlgorithmType() != TransitConfigGroup.TransitRoutingAlgorithmType.SwissRailRaptor)
                throw new IllegalArgumentException("Only the native SwissRailRaptor transit branch is supported");
            ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);
        }
        var initialized = ResolvedNonDispatchConfiguration.snapshot(config);
        requireTree(expectedRaptor, initialized, "Unsupported visible Raptor materialization mutation");
        List<Stage> stages = List.of(
                stage("NATIVE_CONFIG_CONSISTENCY_WITH_EXACT_MISSING_STAGE_DEFAULTS", raw, consistent),
                stage("CONDITIONAL_NATIVE_SWISS_RAIL_RAPTOR_CONFIG_MATERIALIZATION", consistent, initialized));
        return new Result(new Side(tree(raw), stages, tree(initialized)), raw, initialized);
    }

    private static Map<String, String> expectedAfterConsistency(Config config,
                                                               ResolvedNonDispatchConfiguration.Snapshot raw) {
        Map<String, String> expected = new TreeMap<>(raw.fields());
        int index = 0;
        for (ConfigGroup group : config.scoring().getParameterSets("scoringParameters")) {
            if (!(group instanceof ScoringConfigGroup.ScoringParameterSet scoring))
                throw new IllegalArgumentException("Require typed scoring parameter sets");
            String parent = SCORING + "/" + index++;
            var existingTypes = new LinkedHashSet<>(scoring.getActivityParamsPerType().keySet());
            var orderedModes = new LinkedHashSet<>(config.routing().getNetworkModes());
            orderedModes.addAll(scoring.getModes().keySet());
            int size = scoring.getParameterSets("activityParams").size();
            int originalSize = size;
            for (String mode : orderedModes) {
                if (existingTypes.add(ScoringConfigGroup.createStageActivityType(mode)))
                    flatten(parent + "/parameterSets/=activityParams/" + size++,
                            ScoringConfigGroup.createStageActivityParams(mode), expected);
            }
            if (size != originalSize) {
                expected.put(parent + "/parameterSets/=activityParams/@count", Integer.toString(size));
                if (!scoring.getParameterSets().containsKey("activityParams"))
                    expected.put(parent + "/parameterSets/@count", Integer.toString(scoring.getParameterSets().size() + 1));
            }
        }
        return expected;
    }

    private static Map<String, String> expectedAfterRaptor(Config config,
                                                          ResolvedNonDispatchConfiguration.Snapshot before) {
        Map<String, String> expected = new TreeMap<>(before.fields());
        if (!config.transit().isUseTransit()) return expected;
        if (config.transit().getRoutingAlgorithmType() != TransitConfigGroup.TransitRoutingAlgorithmType.SwissRailRaptor)
            throw new IllegalArgumentException("Only the native SwissRailRaptor transit branch is supported");
        ConfigGroup existing = config.getModules().get("swissRailRaptor");
        if (existing != null && existing.getClass() == SwissRailRaptorConfigGroup.class) return expected;
        if (existing != null && existing.getClass() != ConfigGroup.class)
            throw new IllegalArgumentException("Unsupported custom Raptor configuration class");
        var materialized = new SwissRailRaptorConfigGroup();
        if (existing != null) ConfigUtils.copyFromTo(existing, materialized);
        expected.keySet().removeIf(path -> path.startsWith(RAPTOR + "/"));
        flatten(RAPTOR, materialized, expected);
        if (existing == null) expected.put("/modules/@count", Integer.toString(config.getModules().size() + 1));
        return expected;
    }

    private static Stage stage(String name, ResolvedNonDispatchConfiguration.Snapshot before,
                               ResolvedNonDispatchConfiguration.Snapshot after) {
        return new Stage(name, tree(before), tree(after), ResolvedNonDispatchConfiguration.compare(before, after).differences());
    }
    private static Tree tree(ResolvedNonDispatchConfiguration.Snapshot snapshot) {
        return new Tree(snapshot.schemaVersion(), snapshot.sha256(), snapshot.fields());
    }
    private static void requireTree(Map<String, String> expected, ResolvedNonDispatchConfiguration.Snapshot actual, String message) {
        if (expected.equals(actual.fields())) return;
        var keys = new TreeSet<>(expected.keySet()); keys.addAll(actual.fields().keySet());
        for (String key : keys) {
            if (expected.containsKey(key) != actual.fields().containsKey(key)
                    || !Objects.equals(expected.get(key), actual.fields().get(key)))
                throw new IllegalStateException(message + ": " + key);
        }
        throw new IllegalStateException(message);
    }
    private static void flatten(String path, ConfigGroup group, Map<String, String> fields) {
        fields.put(path + "/@name", group.getName());
        fields.put(path + "/params/@count", Integer.toString(group.getParams().size()));
        group.getParams().forEach((name, value) -> fields.put(path + "/params/" + escape(name), value));
        fields.put(path + "/parameterSets/@count", Integer.toString(group.getParameterSets().size()));
        group.getParameterSets().forEach((type, groups) -> {
            String child = path + "/parameterSets/" + escape(type);
            fields.put(child + "/@count", Integer.toString(groups.size()));
            int index = 0;
            for (ConfigGroup nested : groups) flatten(child + "/" + index++, nested, fields);
        });
    }
    private static String escape(String name) { return "=" + name.replace("~", "~0").replace("/", "~1"); }

    private static void requireDisjointGroups(Config first, Config second) {
        var groups = new IdentityHashMap<ConfigGroup, Boolean>();
        first.getModules().values().forEach(group -> collect(group, groups));
        var other = new IdentityHashMap<ConfigGroup, Boolean>();
        second.getModules().values().forEach(group -> collect(group, other));
        for (ConfigGroup group : other.keySet()) if (groups.containsKey(group))
            throw new IllegalArgumentException("Fresh configurations share a configuration group");
    }
    private static void collect(ConfigGroup group, IdentityHashMap<ConfigGroup, Boolean> seen) {
        if (seen.put(group, Boolean.TRUE) != null) return;
        group.getParameterSets().values().forEach(groups -> groups.forEach(nested -> collect(nested, seen)));
    }
}
