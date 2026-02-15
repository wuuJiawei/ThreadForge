package io.threadforge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight task context based on ThreadLocal.
 *
 * <p>ThreadScope automatically captures this context at submit/schedule time
 * and restores it when the task runs, for both platform threads and virtual threads.
 */
public final class Context {

    private static final ThreadLocal<Map<String, Object>> LOCAL = new ThreadLocal<Map<String, Object>>();
    private static final Snapshot EMPTY = new Snapshot(Collections.<String, Object>emptyMap());

    private Context() {
    }

    /**
     * Put a value into current thread context.
     *
     * <p>If {@code value == null}, it behaves like {@link #remove(String)}.
     */
    public static void put(String key, Object value) {
        Objects.requireNonNull(key, "key");
        if (value == null) {
            remove(key);
            return;
        }
        Map<String, Object> map = LOCAL.get();
        if (map == null) {
            map = new LinkedHashMap<String, Object>();
            LOCAL.set(map);
        }
        map.put(key, value);
    }

    /**
     * Get a value from current thread context.
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        Objects.requireNonNull(key, "key");
        Map<String, Object> map = LOCAL.get();
        if (map == null) {
            return null;
        }
        return (T) map.get(key);
    }

    /**
     * Remove one key from current thread context.
     */
    public static void remove(String key) {
        Objects.requireNonNull(key, "key");
        Map<String, Object> map = LOCAL.get();
        if (map == null) {
            return;
        }
        map.remove(key);
        if (map.isEmpty()) {
            LOCAL.remove();
        }
    }

    /**
     * Clear current thread context.
     */
    public static void clear() {
        LOCAL.remove();
    }

    /**
     * Snapshot current thread context as an immutable map.
     */
    public static Map<String, Object> snapshot() {
        Snapshot snapshot = capture();
        if (snapshot.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(snapshot.values));
    }

    static Snapshot capture() {
        Map<String, Object> map = LOCAL.get();
        if (map == null || map.isEmpty()) {
            return EMPTY;
        }
        return new Snapshot(new LinkedHashMap<String, Object>(map));
    }

    static Snapshot install(Snapshot snapshot) {
        Snapshot previous = capture();
        apply(snapshot);
        return previous;
    }

    static void restore(Snapshot previous) {
        apply(previous);
    }

    private static void apply(Snapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            LOCAL.remove();
            return;
        }
        LOCAL.set(new LinkedHashMap<String, Object>(snapshot.values));
    }

    static final class Snapshot {
        private final Map<String, Object> values;

        private Snapshot(Map<String, Object> values) {
            this.values = values;
        }

        private boolean isEmpty() {
            return values.isEmpty();
        }
    }
}
