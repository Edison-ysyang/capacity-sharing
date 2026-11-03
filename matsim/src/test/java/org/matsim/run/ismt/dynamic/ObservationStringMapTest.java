package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ObservationStringMapTest {
    @Test void forkedBuildersCannotMutateEarlierOrSiblingSnapshots() {
        var empty = ObservationStringMap.<Integer>empty(); var builder = empty.mutableCopy();
        builder.put("a", 1); var first = builder.snapshot(); var sibling = first.mutableCopy();
        builder.put("a", 2); builder.put("b", 3); sibling.put("c", 4);
        assertEquals(Map.of(), empty); assertEquals(Map.of("a", 1), first);
        assertEquals(Map.of("a", 2, "b", 3), builder.snapshot());
        assertEquals(Map.of("a", 1, "c", 4), sibling.snapshot());
    }

    @Test void ascendingAndDescendingPopulationSizedInsertionsStayBalanced() {
        for (boolean ascending : List.of(true, false)) {
            var builder = ObservationStringMap.<Integer>empty().mutableCopy();
            final int count = 200_000;
            for (int index = 0; index < count; index++) {
                int value = ascending ? index : count - index - 1;
                builder.put(Integer.toString(1_000_000 + value), value);
            }
            var snapshot = builder.snapshot();
            assertEquals(count, snapshot.size()); assertTrue(snapshot.treeHeight() <= 26, "AVL height at 200k actors");
            int index = 0;
            for (var entry : snapshot.entrySet()) {
                assertEquals(Integer.toString(1_000_000 + index), entry.getKey()); assertEquals(index++, entry.getValue());
            }
            assertEquals(count, index);
        }
    }

    @Test void randomizedUpdatesAndRetainedSnapshotsMatchReferenceTreeMap() {
        var random = new Random(8907); var builder = ObservationStringMap.<Integer>empty().mutableCopy();
        var reference = new TreeMap<String, Integer>();
        var snapshots = new ArrayList<ObservationStringMap<Integer>>(); var expected = new ArrayList<Map<String, Integer>>();
        for (int index = 0; index < 20_000; index++) {
            String key = "actor-" + random.nextInt(4000); int value = random.nextInt();
            assertEquals(reference.put(key, value), builder.put(key, value));
            if (index % 2000 == 0) { snapshots.add(builder.snapshot()); expected.add(Map.copyOf(reference)); }
        }
        assertEquals(reference, builder.snapshot()); assertEquals(builder.snapshot(), reference);
        for (int index = 0; index < snapshots.size(); index++) assertEquals(expected.get(index), snapshots.get(index));
    }

    @Test void collisionLikeStringKeysAndUnicodeRemainSeparateAndLexical() {
        var builder = ObservationStringMap.<String>empty().mutableCopy();
        var expected = new TreeMap<String, String>();
        for (String key : List.of("Aa", "BB", "AaAa", "BBBB", "桂花", "双浦", "", "a\u0000b")) {
            builder.put(key, key); expected.put(key, key);
        }
        assertEquals(expected, builder.snapshot());
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(builder.snapshot().keySet()));
    }

    @Test void immutableSnapshotsRejectEntryAndCollectionMutation() {
        var builder = ObservationStringMap.<Integer>empty().mutableCopy(); builder.put("a", 1); var snapshot = builder.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("b", 2));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.remove("a"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entrySet().iterator().next().setValue(9));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.values().remove(1));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.keySet().clear());
        assertEquals(Map.of("a", 1), snapshot);
    }

    @Test void builderViewsArePointInTimeAndNullValuesCannotMakeContainsAmbiguous() {
        var builder = ObservationStringMap.<Integer>empty().mutableCopy(); builder.put("a", 1);
        var entries = builder.entrySet(); builder.put("b", 2);
        assertEquals(1, entries.size()); assertEquals(2, builder.size());
        assertThrows(NullPointerException.class, () -> builder.put(null, 1));
        assertThrows(NullPointerException.class, () -> builder.put("null", null));
        assertNull(builder.get(42)); assertNull(builder.get(null)); assertFalse(builder.containsKey("missing"));
    }
}
