package io.threadforge.slf4j;

import io.threadforge.Context;
import io.threadforge.TaskInfo;
import io.threadforge.ThreadHook;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MdcThreadHook implements ThreadHook {

    private final String[] keys;
    private final boolean captureAll;
    private final ConcurrentMap<Long, Map<String, String>> previousStates;

    private MdcThreadHook(String[] keys, boolean captureAll) {
        this.keys = keys;
        this.captureAll = captureAll;
        this.previousStates = new ConcurrentHashMap<Long, Map<String, String>>();
    }

    public static MdcThreadHook captureAll() {
        return new MdcThreadHook(new String[0], true);
    }

    public static MdcThreadHook captureKeys(String... keys) {
        Objects.requireNonNull(keys, "keys");
        String[] copy = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            copy[i] = Objects.requireNonNull(keys[i], "key");
        }
        return new MdcThreadHook(copy, false);
    }

    @Override
    public void onStart(TaskInfo info) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        previousStates.put(info.taskId(), previous == null ? Collections.<String, String>emptyMap() : previous);

        Map<String, String> next = captureAll ? captureAllStringValues() : captureSelectedKeys();
        if (next.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(next);
    }

    @Override
    public void onSuccess(TaskInfo info, Duration duration) {
        restore(info.taskId());
    }

    @Override
    public void onFailure(TaskInfo info, Throwable error, Duration duration) {
        restore(info.taskId());
    }

    @Override
    public void onCancel(TaskInfo info, Duration duration) {
        restore(info.taskId());
    }

    private Map<String, String> captureAllStringValues() {
        Map<String, Object> snapshot = Context.snapshot();
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            if (entry.getValue() instanceof String) {
                values.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return values;
    }

    private Map<String, String> captureSelectedKeys() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String key : keys) {
            Object value = Context.get(key);
            if (value instanceof String) {
                values.put(key, (String) value);
            }
        }
        return values;
    }

    private void restore(long taskId) {
        Map<String, String> previous = previousStates.remove(taskId);
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(previous);
    }
}
