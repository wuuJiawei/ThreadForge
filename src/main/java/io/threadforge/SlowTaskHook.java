package io.threadforge;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Hook that emits an event when a task finishes slower than a configured threshold.
 */
public final class SlowTaskHook implements ThreadHook {

    private final Duration threshold;
    private final Consumer<SlowTaskEvent> consumer;

    private SlowTaskHook(Duration threshold, Consumer<SlowTaskEvent> consumer) {
        this.threshold = threshold;
        this.consumer = consumer;
    }

    public static SlowTaskHook create(Duration threshold, Consumer<SlowTaskEvent> consumer) {
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(consumer, "consumer");
        if (threshold.isNegative() || threshold.isZero()) {
            throw new IllegalArgumentException("threshold must be > 0");
        }
        return new SlowTaskHook(threshold, consumer);
    }

    public Duration threshold() {
        return threshold;
    }

    @Override
    public void onSuccess(TaskInfo info, Duration duration) {
        emitIfSlow(info, Task.State.SUCCESS, duration, null);
    }

    @Override
    public void onFailure(TaskInfo info, Throwable error, Duration duration) {
        emitIfSlow(info, Task.State.FAILED, duration, error);
    }

    @Override
    public void onCancel(TaskInfo info, Duration duration) {
        emitIfSlow(info, Task.State.CANCELLED, duration, null);
    }

    private void emitIfSlow(TaskInfo info, Task.State state, Duration duration, Throwable error) {
        if (duration.compareTo(threshold) < 0) {
            return;
        }
        consumer.accept(new SlowTaskEvent(info, state, duration, error));
    }
}
