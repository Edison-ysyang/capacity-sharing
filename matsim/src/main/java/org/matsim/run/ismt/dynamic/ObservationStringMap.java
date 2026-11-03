package org.matsim.run.ismt.dynamic;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Package-local persistent AVL storage for immutable, per-event observation snapshots.
 * Updating one actor copies O(log n) nodes, not the complete observed population/history.
 * Values must already be immutable; a builder owns only its root reference, never tree nodes.
 * String lexical iteration is deterministic. Removal is intentionally unsupported because
 * the observation ledger retains every seen actor and waiting episode throughout the day.
 */
final class ObservationStringMap<V> extends AbstractMap<String, V> {
    private record Node<V>(String key, V value, Node<V> left, Node<V> right, int height, int size) { }
    private final Node<V> root;
    private ObservationStringMap(Node<V> root) { this.root = root; }
    static <V> ObservationStringMap<V> empty() { return new ObservationStringMap<>(null); }
    Builder<V> mutableCopy() { return new Builder<>(root); }
    @Override public V get(Object key) { return lookup(root, key); }
    @Override public boolean containsKey(Object key) { return get(key) != null; }
    @Override public int size() { return size(root); }
    @Override public Set<Entry<String, V>> entrySet() { return entries(root); }
    int treeHeight() { return height(root); }

    static final class Builder<V> extends AbstractMap<String, V> {
        private Node<V> root;
        private Builder(Node<V> root) { this.root = root; }
        ObservationStringMap<V> snapshot() { return new ObservationStringMap<>(root); }
        @Override public V get(Object key) { return lookup(root, key); }
        @Override public boolean containsKey(Object key) { return get(key) != null; }
        @Override public int size() { return ObservationStringMap.size(root); }
        @Override public Set<Entry<String, V>> entrySet() { return entries(root); }
        @Override public V put(String key, V value) {
            Objects.requireNonNull(key); Objects.requireNonNull(value);
            V old = lookup(root, key); root = insert(root, key, value); return old;
        }
    }

    private static <V> V lookup(Node<V> node, Object key) {
        if (!(key instanceof String text)) return null;
        while (node != null) {
            int order = text.compareTo(node.key());
            if (order == 0) return node.value();
            node = order < 0 ? node.left() : node.right();
        }
        return null;
    }
    private static int height(Node<?> node) { return node == null ? 0 : node.height(); }
    private static int size(Node<?> node) { return node == null ? 0 : node.size(); }
    private static <V> Node<V> node(String key, V value, Node<V> left, Node<V> right) {
        return new Node<>(key, value, left, right, 1 + Math.max(height(left), height(right)),
                Math.addExact(1, Math.addExact(size(left), size(right))));
    }
    private static <V> Node<V> insert(Node<V> current, String key, V value) {
        if (current == null) return node(key, value, null, null);
        int order = key.compareTo(current.key());
        if (order == 0) return current.value() == value ? current : node(key, value, current.left(), current.right());
        var updated = order < 0 ? node(current.key(), current.value(), insert(current.left(), key, value), current.right())
                : node(current.key(), current.value(), current.left(), insert(current.right(), key, value));
        int balance = height(updated.left()) - height(updated.right());
        if (balance > 1) {
            var left = updated.left();
            if (height(left.left()) < height(left.right()))
                updated = node(updated.key(), updated.value(), rotateLeft(left), updated.right());
            return rotateRight(updated);
        }
        if (balance < -1) {
            var right = updated.right();
            if (height(right.right()) < height(right.left()))
                updated = node(updated.key(), updated.value(), updated.left(), rotateRight(right));
            return rotateLeft(updated);
        }
        return updated;
    }
    private static <V> Node<V> rotateLeft(Node<V> value) {
        var top = value.right();
        return node(top.key(), top.value(), node(value.key(), value.value(), value.left(), top.left()), top.right());
    }
    private static <V> Node<V> rotateRight(Node<V> value) {
        var top = value.left();
        return node(top.key(), top.value(), top.left(), node(value.key(), value.value(), top.right(), value.right()));
    }
    private static <V> Set<Map.Entry<String, V>> entries(Node<V> root) {
        return Collections.unmodifiableSet(new AbstractSet<>() {
            @Override public int size() { return ObservationStringMap.size(root); }
            @Override public Iterator<Map.Entry<String, V>> iterator() {
                return new Iterator<>() {
                    private final ArrayDeque<Node<V>> stack = new ArrayDeque<>();
                    { pushLeft(root); }
                    private void pushLeft(Node<V> node) {
                        while (node != null) { stack.push(node); node = node.left(); }
                    }
                    @Override public boolean hasNext() { return !stack.isEmpty(); }
                    @Override public Map.Entry<String, V> next() {
                        if (stack.isEmpty()) throw new NoSuchElementException();
                        var current = stack.pop(); pushLeft(current.right());
                        return Map.entry(current.key(), current.value());
                    }
                };
            }
        });
    }
}
