package org.worldspeaker.bootstrap;

import java.util.Objects;

/** Fixed-capacity original-deadline queue with bounded scans for cooldown-ready dispositions. */
final class SmokeDispositionQueue<T> {
    static final int CAPACITY = 16;

    private final Entry<T>[] entries;
    private int size;

    @SuppressWarnings("unchecked")
    SmokeDispositionQueue() {
        entries = (Entry<T>[]) new Entry<?>[CAPACITY];
    }

    boolean offer(T value, long originalDeadlineNanos, long stableOrder) {
        return offer(value, 0, originalDeadlineNanos, stableOrder);
    }

    boolean offer(T value, long notBeforeNanos, long originalDeadlineNanos, long stableOrder) {
        Objects.requireNonNull(value, "value");
        if (contains(value) || size == entries.length) return false;
        Entry<T> next = new Entry<>(value, notBeforeNanos, originalDeadlineNanos, stableOrder);
        int index = size;
        while (index > 0 && compare(next, entries[index - 1]) < 0) {
            entries[index] = entries[index - 1];
            index--;
        }
        entries[index] = next;
        size++;
        return true;
    }

    T peek() { return size == 0 ? null : entries[0].value(); }

    Long peekDeadline() { return size == 0 ? null : entries[0].originalDeadlineNanos(); }

    /** Returns the earliest original-deadline item that is eligible now, scanning at most CAPACITY rows. */
    Long peekReadyDeadline(long nowNanos) {
        for (int index = 0; index < size; index++) {
            Entry<T> entry = entries[index];
            if (ready(entry, nowNanos)) return entry.originalDeadlineNanos();
        }
        return null;
    }

    T remove() {
        if (size == 0) return null;
        T value = entries[0].value();
        removeAt(0);
        return value;
    }

    /** Removes the earliest eligible item without allowing a future cooldown item to block other work. */
    T removeReady(long nowNanos) {
        for (int index = 0; index < size; index++) {
            if (ready(entries[index], nowNanos)) {
                T value = entries[index].value();
                removeAt(index);
                return value;
            }
        }
        return null;
    }

    boolean remove(T value) {
        for (int index = 0; index < size; index++) {
            if (entries[index].value() == value) {
                removeAt(index);
                return true;
            }
        }
        return false;
    }

    boolean removeMatching(java.util.function.Predicate<T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        for (int index = 0; index < size; index++) {
            if (predicate.test(entries[index].value())) {
                removeAt(index);
                return true;
            }
        }
        return false;
    }

    boolean updateDeadline(T value, long originalDeadlineNanos) {
        for (int index = 0; index < size; index++) {
            Entry<T> current = entries[index];
            if (current.value() != value) continue;
            if (compareDeadline(originalDeadlineNanos, current.originalDeadlineNanos()) >= 0) return true;
            removeAt(index);
            Entry<T> next = new Entry<>(value, current.notBeforeNanos(), originalDeadlineNanos,
                    current.stableOrder());
            int insertion = size;
            while (insertion > 0 && compare(next, entries[insertion - 1]) < 0) {
                entries[insertion] = entries[insertion - 1];
                insertion--;
            }
            entries[insertion] = next;
            size++;
            return true;
        }
        return false;
    }

    boolean updateDeadlineMatching(java.util.function.Predicate<T> predicate, long originalDeadlineNanos) {
        for (int index = 0; index < size; index++) {
            T value = entries[index].value();
            if (predicate.test(value)) return updateDeadline(value, originalDeadlineNanos);
        }
        return false;
    }

    boolean contains(T value) {
        for (int index = 0; index < size; index++) {
            if (entries[index].value() == value) return true;
        }
        return false;
    }

    int size() { return size; }

    void clear() {
        for (int index = 0; index < size; index++) entries[index] = null;
        size = 0;
    }

    private void removeAt(int index) {
        for (int next = index + 1; next < size; next++) entries[next - 1] = entries[next];
        entries[--size] = null;
    }

    private static int compare(Entry<?> left, Entry<?> right) {
        int deadline = compareDeadline(left.originalDeadlineNanos(), right.originalDeadlineNanos());
        return deadline != 0 ? deadline : Long.compare(left.stableOrder(), right.stableOrder());
    }

    private static boolean ready(Entry<?> entry, long nowNanos) {
        return nowNanos - entry.notBeforeNanos() >= 0;
    }

    /** Monotonic deadlines are ordered by elapsed distance, including signed nanoTime rollover. */
    private static int compareDeadline(long left, long right) {
        return Long.compare(left - right, 0);
    }

    private record Entry<T>(T value, long notBeforeNanos, long originalDeadlineNanos, long stableOrder) {}
}
