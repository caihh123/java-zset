package org.example;

import lombok.Getter;
import lombok.Setter;

import java.util.*;

public class ZSet<K extends Comparable<K>, V extends Comparable<V>> {

    public static class Default {
        public static final float P = 0.25F;
        public static int INIT_CAPACITY = 128;
        public static int MAX_LEVEL = 32;
        public static int FREE_LIST_SIZE = 32;
    }

    private final Map<K, Node<K, V>> dict;
    private final SkipList<K, V> zsl;

    @FunctionalInterface
    public interface CallbackWithParam<K, V> {
        boolean call(int rank, K key, V value);
    }

    @FunctionalInterface
    public interface CallbackWithRange<K, V> {
        boolean call(K key, V value);
    }

    public ZSet() {
        this.zsl = new SkipList<>(Default.MAX_LEVEL);
        dict = new HashMap<>(Default.INIT_CAPACITY);
    }

    public Collection<V> values() {
        List<V> values = new ArrayList<>();
        dict.forEach((key, value) -> values.add(value.item));
        return values;
    }

    public boolean isEmpty() {
        return dict.isEmpty();
    }

    public V update(K key, V value) {
        V remove = null;
        Node<K, V> old = dict.get(key);
        if (old != null) {
            if (zsl.updateItem(old, value)) {
                return null;
            }
            remove = zsl.delete(old);
        }
        Node<K, V> item = zsl.insert(key, value);
        dict.put(key, item);
        return remove;
    }

    public V delete(K key) {
        Node<K, V> old = dict.get(key);
        if (old == null) {
            return null;
        }
        V remove = zsl.delete(old);
        dict.remove(key);
        return remove;
    }

    public V get(K key) {
        Node<K, V> node = dict.get(key);
        if (node == null) {
            return null;
        }
        return node.item;
    }

    public V get(int rank) {
        Node<K, V> node = zsl.rank(rank);
        if (node == null) {
            return null;
        }
        return node.item;
    }

    public int rank(K key) {
        Node<K, V> node = dict.get(key);
        if (node == null) {
            return 0;
        }
        return zsl.rank(node.item);
    }

    public V first() {
        Node<K, V> min = zsl.min();
        if (min == null) {
            return null;
        }
        return min.item;
    }

    public V last() {
        Node<K, V> max = zsl.max();
        if (max == null) {
            return null;
        }
        return max.item;
    }

    public Entry<K, V> next(CallbackWithRange<K, V> greater) {
        return zsl.next(greater);
    }

    public void forEach(CallbackWithRange<K, V> iterator) {
        dict.forEach((key, value) -> iterator.call(key, value.item));
    }

    public Entry<K, V> prev(CallbackWithRange<K, V> less) {
        return zsl.prev(less);
    }

    public void range(CallbackWithRange<K, V> min, CallbackWithRange<K, V> max, boolean reverse, CallbackWithParam<K, V> iterator) {
        int length = zsl.length;
        Node<K, V> minNode, maxNode;
        int minRank, maxRank;
        if (min == null) {
            minNode = zsl.min();
            minRank = 1;
        } else {
            Entry<K, V> entry = zsl.next(min);
            if (entry == null) {
                return;
            }
            minNode = dict.get(entry.key);
            minRank = entry.rank;
        }
        if (max == null) {
            maxNode = zsl.max();
            maxRank = length;
        } else {
            Entry<K, V> entry = zsl.prev(max);
            if (entry == null) {
                return;
            }
            maxNode = dict.get(entry.key);
            maxRank = entry.rank;
        }
        if (maxNode == null) {
            return;
        }
        if (reverse) {
            Node<K, V> node = maxNode;
            for (int i = maxRank; i >= minRank; i--) {
                if (!iterator.call(length - i + 1, node.key, node.item)) {
                    break;
                }
                node = node.backward;
            }
        } else {
            Node<K, V> node = minNode;
            for (int i = minRank; i <= maxRank; i++) {
                if (!iterator.call(i, node.key, node.item)) {
                    break;
                }
                node = node.levels[0].forward;
            }
        }
    }

    public void range(CallbackWithParam<K, V> iterator) {
        range(0, dict.size(), false, iterator);
    }

    public void range(int start, int end, boolean reverse, CallbackWithParam<K, V> iterator) {
        int length = zsl.length;
        if (start < 0) {
            start = length + start;
        }
        if (end < 0) {
            end = length + end;
        }
        if (start < 0) {
            start = 0;
        }
        if (start > end || start >= length) {
            return;
        }
        if (end >= length) {
            end = length - 1;
        }

        int rangeLen = end - start + 1;
        if (reverse) {
            Node<K, V> node = zsl.rank(length - start);
            if (node == null) {
                return;
            }
            for (int i = 1; i <= rangeLen; i++) {
                if (!iterator.call(start + i, node.key, node.item)) {
                    break;
                }
                node = node.backward;
            }
        } else {
            Node<K, V> node = zsl.rank(start + 1);
            if (node == null) {
                return;
            }
            for (int i = 1; i <= rangeLen; i++) {
                if (!iterator.call(start + i, node.key, node.item)) {
                    break;
                }
                node = node.levels[0].forward;
            }
        }
    }

    private static class SkipList<K extends Comparable<K>, V extends Comparable<V>> {

        Node<K, V> header, tail;
        int length, level, maxLevel;

        private final FreeList<K, V> free;
        private final Random random;

        SkipList(int maxLevel) {
            this.random = new Random();
            this.free = new FreeList<>(Default.FREE_LIST_SIZE);
            if (maxLevel < 1 || maxLevel > Default.MAX_LEVEL) {
                throw new IllegalArgumentException("maxLevel must between [1,32]");
            }
            header = new Node<>(maxLevel);
            level = 1;
            this.maxLevel = maxLevel;
        }

        public Node<K, V> min() {
            return header.levels[0].forward;
        }

        public Node<K, V> max() {
            return tail;
        }

        public int rank(V value) {
            int rank = 0;
            Node<K, V> x = header;
            for (int i = level - 1; i >= 0; i--) {
                for (Node<K, V> y = x.levels[i].forward; y != null && !less(value, y.item); y = x.levels[i].forward) {
                    rank += x.levels[i].span;
                    x = y;
                }
                if (x != header && !less(x.item, value)) {
                    return rank;
                }
            }
            return 0;
        }

        @SuppressWarnings("DuplicatedCode")
        public Entry<K, V> next(CallbackWithRange<K, V> greater) {
            Node<K, V> x = header;
            int rank = 0;
            for (int i = level - 1; i >= 0; i--) {
                while (x.levels[i].forward != null && !greater.call(x.levels[i].forward.key, x.levels[i].forward.item)) {
                    rank += x.levels[i].span;
                    x = x.levels[i].forward;
                }
            }
            if (rank < 1) {
                return null;
            }
            return new Entry<>(x.levels[0].forward.key, x.levels[0].forward.item, rank + x.levels[0].span);
        }

        @SuppressWarnings("DuplicatedCode")
        public Entry<K, V> prev(CallbackWithRange<K, V> less) {
            Node<K, V> x = header;
            int rank = 0;
            for (int i = level - 1; i >= 0; i--) {
                while (x.levels[i].forward != null && !less.call(x.levels[i].forward.key, x.levels[i].forward.item)) {
                    rank += x.levels[i].span;
                    x = x.levels[i].forward;
                }
            }
            if (rank < 1) {
                return null;
            }
            return new Entry<>(x.key, x.item, rank);
        }

        public Node<K, V> rank(int rank) {
            int traversed = 0;
            Node<K, V> x = header;
            for (int i = level - 1; i >= 0; i--) {
                while (x.levels[i].forward != null && traversed + x.levels[i].span <= rank) {
                    traversed += x.levels[i].span;
                    x = x.levels[i].forward;
                }
                if (traversed == rank) {
                    return x;
                }
            }
            return null;
        }

        public V delete(Node<K, V> node) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = new Node[maxLevel];
            Node<K, V> x = header;
            for (int i = level - 1; i >= 0; i--) {
                while (x.levels[i].forward != null && less(x.levels[i].forward.item, node.item)) {
                    x = x.levels[i].forward;
                }
                update[i] = x;
            }
            x = x.levels[0].forward;
            if (x != null && !less(node.item, x.item)) {
                for (int i = 0; i < level; i++) {
                    if (update[i].levels[i].forward == x) {
                        update[i].levels[i].span += x.levels[i].span - 1;
                        update[i].levels[i].forward = x.levels[i].forward;
                    } else {
                        update[i].levels[i].span--;
                    }
                }
                while (level > 1 && header.levels[level - 1].forward == null) {
                    level--;
                }
                if (x.levels[0].forward == null) {
                    tail = x.backward;
                } else {
                    x.levels[0].forward.backward = x.backward;
                }
                V removedItem = x.item;
                free.free(x);
                length--;
                return removedItem;
            }
            return null;
        }

        public boolean less(V item1, V item2) {
            int omp = item1.compareTo(item2);
            return omp < 0;
        }

        public boolean updateItem(Node<K, V> node, V value) {
            if ((node.levels[0].forward == null || !less(node.levels[0].forward.item, value)) && (node.backward == null || !less(node.backward.item, value))) {
                node.item = value;
                return true;
            }
            return false;
        }

        public int randomLevel() {
            int lvl = 1;
            random.nextFloat();
            while (lvl < Default.MAX_LEVEL && random.nextFloat() < Default.P) {
                lvl++;
            }
            return lvl;
        }

        public Node<K, V> insert(K key, V value) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = new Node[maxLevel];
            int[] rank = new int[maxLevel];
            Node<K, V> x = header;
            for (int i = level - 1; i >= 0; i--) {
                if (i == level - 1) {
                    rank[i] = 0;
                } else {
                    rank[i] = rank[i + 1];
                }
                while (x.levels[i].forward != null && less(x.levels[i].forward.item, value)) {
                    rank[i] += x.levels[i].span;
                    x = x.levels[i].forward;
                }
                update[i] = x;
            }

            int lvl = randomLevel();
            if (lvl > level) {
                for (int i = level; i < lvl; i++) {
                    rank[i] = 0;
                    update[i] = header;
                    update[i].levels[i].span = length;
                }
                level = lvl;
            }

            x = free.get(lvl, key, value);
            for (int i = 0; i < lvl; i++) {
                x.levels[i].forward = update[i].levels[i].forward;
                update[i].levels[i].forward = x;

                x.levels[i].span = update[i].levels[i].span - (rank[0] - rank[i]);
                update[i].levels[i].span = (rank[0] - rank[i]) + 1;
            }

            for (int i = lvl; i < level; i++) {
                update[i].levels[i].span++;
            }

            if (update[0] == header) {
                x.backward = null;
            } else {
                x.backward = update[0];
            }
            if (x.levels[0].forward == null) {
                tail = x;
            } else {
                x.levels[0].forward.backward = x;
            }
            length++;
            return x;
        }
    }

    public static class Node<K extends Comparable<K>, V extends Comparable<V>> {
        K key;
        @Setter
        V item;
        Node<K, V> backward;
        SkipListLevel<K, V>[] levels;

        public Node(int level) {
            this.levels = newLevels(level);
        }

        private SkipListLevel<K, V>[] newLevels(int level) {
            @SuppressWarnings("unchecked")
            SkipListLevel<K, V>[] levels = new SkipListLevel[level];
            for (int i = 0; i < level; i++) {
                levels[i] = new SkipListLevel<>();
            }
            return levels;
        }

        public Node(int level, K key, V value) {
            this.key = key;
            this.item = value;
            this.levels = newLevels(level);
        }

        public void extend(int level, K key, V value) {
            this.key = key;
            item = value;
            backward = null;
            this.levels = newLevels(level);
        }

        public void clear() {
            key = null;
            item = null;
            backward = null;
            this.levels = newLevels(this.levels.length);
        }
    }

    public static class SkipListLevel<K extends Comparable<K>, V extends Comparable<V>> {
        Node<K, V> forward;
        int span;
    }

    public static class FreeList<K extends Comparable<K>, V extends Comparable<V>> {
        LinkedList<Node<K, V>> free;
        int size;

        public FreeList(int size) {
            this.size = size;
            this.free = new LinkedList<>();
        }

        public Node<K, V> get(int level, K key, V value) {
            if (free.isEmpty()) {
                return new Node<K, V>(level, key, value);
            }
            Node<K, V> node = free.removeLast();
            if (node == null) {
                return new Node<>(level, key, value);
            }
            node.extend(level, key, value);
            return node;
        }

        public void free(Node<K, V> node) {
            node.clear();
            if (free.size() < size) {
                free.addFirst(node);
            }
        }
    }

    @Getter
    public static class Entry<K extends Comparable<K>, V extends Comparable<V>> implements Comparable<Entry<K, V>> {

        private final K key;
        private final V value;
        private final int rank;

        Entry(K key, V value, int rank) {
            this.key = key;
            this.value = value;
            this.rank = rank;
        }

        @Override
        public int compareTo(Entry<K, V> other) {
            int valueComparison = this.value.compareTo(other.value);
            if (valueComparison != 0) {
                return valueComparison;
            }
            return this.key.compareTo(other.key);
        }

        @Override
        public String toString() {
            return "Key: " + key + ", Value: " + value;
        }
    }
}
