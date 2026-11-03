package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResolvedNonDispatchConfigurationTest {
    private static final String X = "/modules/=x";

    @Test void moduleAndParameterInsertionOrderDoNotChangeSnapshot() {
        Config first = new Config(); Config second = new Config();
        ConfigGroup a = group("x", "a", "1"); a.addParam("b", "2");
        ConfigGroup b = group("x", "b", "2"); b.addParam("a", "1");
        first.addModule(a); first.addModule(group("y", "c", "3"));
        second.addModule(group("y", "c", "3")); second.addModule(b);
        var one = snapshot(first); var two = snapshot(second);
        assertEquals(one.fields(), two.fields()); assertEquals(one.sha256(), two.sha256());
        assertTrue(ResolvedNonDispatchConfiguration.compare(one, two).strictlyEqual());
    }

    @Test void allModulesIncludingPreviouslyUnknownOnesAreCompared() {
        Config first = ConfigUtils.createConfig(); Config second = ConfigUtils.createConfig();
        first.qsim().setNumberOfThreads(7); second.qsim().setNumberOfThreads(1);
        first.eventsManager().setEventsQueueSize(999); second.eventsManager().setEventsQueueSize(111);
        first.addModule(group("unregisteredFutureModule", "scientificParameter", "1"));
        second.addModule(group("unregisteredFutureModule", "scientificParameter", "2"));
        var result = ResolvedNonDispatchConfiguration.compare(snapshot(first), snapshot(second));
        assertEquals(3, result.differences().size()); assertEquals(3, result.unexplainedDifferences().size());
        assertTrue(result.differences().stream().anyMatch(value -> value.path().equals("/modules/=qsim/params/=numberOfThreads")));
        assertTrue(result.differences().stream().anyMatch(value -> value.path().contains("unregisteredFutureModule")));
    }

    @Test void orderedNestedParameterSetsAreNotTreatedAsSets() {
        Config first = tree("first", "second"); Config second = tree("second", "first");
        var result = ResolvedNonDispatchConfiguration.compare(snapshot(first), snapshot(second));
        assertEquals(2, result.differences().size());
        assertEquals(X + "/parameterSets/=strategy/0/parameterSets/=inner/0/params/=weight", result.differences().getFirst().path());
        assertNotEquals(snapshot(first).sha256(), snapshot(second).sha256());
    }

    @Test void emptyModulesAndEmptyNestedGroupsRemainVisible() {
        Config first = new Config(); Config second = new Config();
        second.addModule(new ConfigGroup("empty"));
        assertFalse(ResolvedNonDispatchConfiguration.compare(snapshot(first), snapshot(second)).strictlyEqual());
        var before = snapshot(second); second.getModules().get("empty").addParameterSet(new ConfigGroup("nested"));
        var after = snapshot(second);
        assertTrue(after.fields().containsKey("/modules/=empty/parameterSets/=nested/0/params/@count"));
        assertFalse(ResolvedNonDispatchConfiguration.compare(before, after).strictlyEqual());
    }

    @Test void nullAbsentAndLiteralNullAreDistinct() {
        Config config = new Config(); var module = new ConfigGroup("x"); config.addModule(module);
        var absent = snapshot(config); module.addParam("value", null); var presentNull = snapshot(config);
        module.addParam("value", "null"); var literal = snapshot(config);
        var added = ResolvedNonDispatchConfiguration.compare(absent, presentNull).differences().stream()
                .filter(value -> value.path().equals(X + "/params/=value")).findFirst().orElseThrow();
        assertEquals(new ResolvedNonDispatchConfiguration.Value(false, null), added.historical());
        assertEquals(new ResolvedNonDispatchConfiguration.Value(true, null), added.target());
        assertNotEquals(presentNull.sha256(), literal.sha256());
    }

    @Test void escapedNamesCannotCollideWithStructuralMetadataOrOtherKeys() {
        Config config = new Config(); var group = group("a/b~c", "@count", "literal");
        group.addParam("a/b", "slash"); group.addParam("a~1b", "tilde"); config.addModule(group);
        var fields = snapshot(config).fields();
        assertEquals("literal", fields.get("/modules/=a~1b~0c/params/=@count"));
        assertEquals("3", fields.get("/modules/=a~1b~0c/params/@count"));
        assertEquals("slash", fields.get("/modules/=a~1b~0c/params/=a~1b"));
        assertEquals("tilde", fields.get("/modules/=a~1b~0c/params/=a~01b"));
    }

    @Test void configContextIsAnExplicitDifference() throws Exception {
        Config first = new Config(); Config second = new Config();
        first.setContext(URI.create("file:/declared/a/").toURL()); second.setContext(URI.create("file:/declared/b/").toURL());
        var result = ResolvedNonDispatchConfiguration.compare(snapshot(first), snapshot(second));
        assertEquals(List.of("/context"), result.differences().stream().map(ResolvedNonDispatchConfiguration.Difference::path).toList());
    }

    @Test void fileAliasesAndOutputDirectoriesAreNotAutomaticallyEquivalent() {
        Config first = new Config(); Config second = new Config();
        first.addModule(group("x", "path", "same-content/original.xml"));
        second.addModule(group("x", "path", "same-content/alias.xml"));
        var a = snapshot(first); var b = snapshot(second); var raw = ResolvedNonDispatchConfiguration.compare(a, b);
        var difference = raw.differences().getFirst();
        var explanation = new ResolvedNonDispatchConfiguration.ExactDifferenceExplanation(difference.path(), difference.historical(),
                difference.target(), "Declared alias only; byte/path containment and scientific equivalence require separate evidence.");
        var annotated = ResolvedNonDispatchConfiguration.compare(a, b, List.of(explanation));
        assertFalse(annotated.strictlyEqual()); assertEquals(1, annotated.differences().size());
        assertTrue(annotated.unexplainedDifferences().isEmpty());
        assertEquals(a.sha256(), annotated.historicalSha256()); assertEquals(b.sha256(), annotated.targetSha256());
    }

    @Test void staleWildcardModuleAndWrongValueExplanationsAreRejected() {
        Config config = new Config(); var group = group("x", "value", "old"); config.addModule(group);
        var old = snapshot(config); group.addParam("value", "new"); var target = snapshot(config);
        var before = new ResolvedNonDispatchConfiguration.Value(true, "old");
        var after = new ResolvedNonDispatchConfiguration.Value(true, "new");
        for (String path : List.of(X, X + "/params/*", "/missing")) {
            var explanation = new ResolvedNonDispatchConfiguration.ExactDifferenceExplanation(path, before, after, "no broad exemptions");
            assertThrows(IllegalArgumentException.class, () -> ResolvedNonDispatchConfiguration.compare(old, target, List.of(explanation)));
        }
        var reversed = new ResolvedNonDispatchConfiguration.ExactDifferenceExplanation(X + "/params/=value", after, before, "wrong sides");
        assertThrows(IllegalArgumentException.class, () -> ResolvedNonDispatchConfiguration.compare(old, target, List.of(reversed)));
        var exact = new ResolvedNonDispatchConfiguration.ExactDifferenceExplanation(X + "/params/=value", before, after, "specific");
        assertThrows(IllegalArgumentException.class, () -> ResolvedNonDispatchConfiguration.compare(old, target, List.of(exact, exact)));
        assertThrows(IllegalArgumentException.class, () -> ResolvedNonDispatchConfiguration.compare(old, old, List.of(exact)));
    }

    @Test void snapshotsAndComparisonsAreImmutableDetachedValues() {
        Config config = new Config(); var group = group("x", "v", "1"); config.addModule(group);
        var old = snapshot(config); group.addParam("v", "2"); var target = snapshot(config);
        assertEquals("1", old.fields().get(X + "/params/=v"));
        assertThrows(UnsupportedOperationException.class, () -> old.fields().put("fake", "fake"));
        var comparison = ResolvedNonDispatchConfiguration.compare(old, target);
        assertThrows(UnsupportedOperationException.class, () -> comparison.differences().clear());
        assertThrows(UnsupportedOperationException.class, () -> comparison.unexplainedDifferences().clear());
    }

    @Test void cyclicGroupsFailWithoutInfiniteRecursion() {
        Config config = new Config(); var group = new ConfigGroup("x"); group.addParameterSet(group); config.addModule(group);
        assertThrows(IllegalArgumentException.class, () -> snapshot(config));
    }

    @Test void visibleMutationDuringSnapshotFailsAndDoesNotProduceAnIdentity() {
        Config config = new Config();
        config.addModule(new ConfigGroup("x") {
            private int reads;
            @Override public Map<String, String> getParams() { return Map.of("unstable", Integer.toString(++reads)); }
        });
        assertThrows(IllegalStateException.class, () -> snapshot(config));
    }

    @Test void explanationMustBeMeaningfulAndAbsentValueCannotSmuggleText() {
        var absent = new ResolvedNonDispatchConfiguration.Value(false, null);
        var present = new ResolvedNonDispatchConfiguration.Value(true, "1");
        assertThrows(IllegalArgumentException.class, () -> new ResolvedNonDispatchConfiguration.Value(false, "x"));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedNonDispatchConfiguration.ExactDifferenceExplanation("/x", absent, present, " "));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedNonDispatchConfiguration.ExactDifferenceExplanation("/x", present, present, "same"));
    }

    private static ResolvedNonDispatchConfiguration.Snapshot snapshot(Config config) {
        return ResolvedNonDispatchConfiguration.snapshot(config);
    }
    private static ConfigGroup group(String name, String key, String value) {
        var group = new ConfigGroup(name); group.addParam(key, value); return group;
    }
    private static Config tree(String... values) {
        Config config = new Config(); var group = new ConfigGroup("x"); config.addModule(group);
        for (String value : values) {
            var child = new ConfigGroup("strategy"); child.addParameterSet(group("inner", "weight", value)); group.addParameterSet(child);
        }
        return config;
    }
}
