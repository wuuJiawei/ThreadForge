package io.threadforge.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.threadforge.TaskInfo;
import io.threadforge.ThreadHook;

import java.time.Duration;
import java.util.Objects;

public final class MicrometerThreadHook implements ThreadHook {

    private static final String DEFAULT_PREFIX = "threadforge.task";

    private final MeterRegistry registry;
    private final String prefix;

    private MicrometerThreadHook(MeterRegistry registry, String prefix) {
        this.registry = registry;
        this.prefix = prefix;
    }

    public static MicrometerThreadHook create(MeterRegistry registry) {
        return create(registry, DEFAULT_PREFIX);
    }

    public static MicrometerThreadHook create(MeterRegistry registry, String prefix) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.trim().isEmpty()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        return new MicrometerThreadHook(registry, prefix);
    }

    @Override
    public void onStart(TaskInfo info) {
        counter("started", info.schedulerName(), "started").increment();
    }

    @Override
    public void onSuccess(TaskInfo info, Duration duration) {
        recordCompletion(info, duration, "success");
    }

    @Override
    public void onFailure(TaskInfo info, Throwable error, Duration duration) {
        recordCompletion(info, duration, "failed");
    }

    @Override
    public void onCancel(TaskInfo info, Duration duration) {
        recordCompletion(info, duration, "cancelled");
    }

    private void recordCompletion(TaskInfo info, Duration duration, String state) {
        counter("completed", info.schedulerName(), state).increment();
        Timer.builder(prefix + ".duration")
            .tag("scheduler", info.schedulerName())
            .tag("state", state)
            .register(registry)
            .record(duration);
    }

    private Counter counter(String suffix, String schedulerName, String state) {
        return Counter.builder(prefix + "." + suffix)
            .tag("scheduler", schedulerName)
            .tag("state", state)
            .register(registry);
    }
}
