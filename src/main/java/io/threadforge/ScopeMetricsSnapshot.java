package io.threadforge;

import java.time.Duration;

/**
 * Immutable scope-level task runtime metrics snapshot.
 */
public final class ScopeMetricsSnapshot {

    private final long started;
    private final long succeeded;
    private final long failed;
    private final long cancelled;
    private final long totalDurationNanos;
    private final long maxDurationNanos;

    public ScopeMetricsSnapshot(
        long started,
        long succeeded,
        long failed,
        long cancelled,
        long totalDurationNanos,
        long maxDurationNanos
    ) {
        this.started = started;
        this.succeeded = succeeded;
        this.failed = failed;
        this.cancelled = cancelled;
        this.totalDurationNanos = totalDurationNanos;
        this.maxDurationNanos = maxDurationNanos;
    }

    public long started() {
        return started;
    }

    public long succeeded() {
        return succeeded;
    }

    public long failed() {
        return failed;
    }

    public long cancelled() {
        return cancelled;
    }

    public long completed() {
        return succeeded + failed + cancelled;
    }

    public Duration totalDuration() {
        return Duration.ofNanos(totalDurationNanos);
    }

    public Duration averageDuration() {
        long completed = completed();
        if (completed == 0L) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(totalDurationNanos / completed);
    }

    public Duration maxDuration() {
        return Duration.ofNanos(maxDurationNanos);
    }
}
