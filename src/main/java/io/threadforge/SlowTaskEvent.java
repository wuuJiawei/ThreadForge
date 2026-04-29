package io.threadforge;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable event describing a slow task that crossed a configured threshold.
 */
public final class SlowTaskEvent {

    private final TaskInfo info;
    private final Task.State state;
    private final Duration duration;
    private final Throwable error;

    public SlowTaskEvent(TaskInfo info, Task.State state, Duration duration, Throwable error) {
        this.info = Objects.requireNonNull(info, "info");
        this.state = Objects.requireNonNull(state, "state");
        this.duration = Objects.requireNonNull(duration, "duration");
        this.error = error;
    }

    public TaskInfo info() {
        return info;
    }

    public Task.State state() {
        return state;
    }

    public Duration duration() {
        return duration;
    }

    public Throwable error() {
        return error;
    }
}
