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

    @Override
    public String toString() {
        Duration total = totalDuration();
        Duration avg = averageDuration();
        Duration max = maxDuration();

        StringBuilder sb = new StringBuilder(256);
        sb.append("ScopeMetricsSnapshot{\n");
        sb.append("  started=").append(started).append(",\n");
        sb.append("  succeeded=").append(succeeded).append(",\n");
        sb.append("  failed=").append(failed).append(",\n");
        sb.append("  cancelled=").append(cancelled).append(",\n");
        sb.append("  completed=").append(completed()).append(",\n");
        sb.append("  totalDuration=").append(total).append(" (").append(total.toMillis()).append(" ms),\n");
        sb.append("  averageDuration=").append(avg).append(" (").append(avg.toMillis()).append(" ms),\n");
        sb.append("  maxDuration=").append(max).append(" (").append(max.toMillis()).append(" ms)\n");
        sb.append("}");
        return sb.toString();
    }
}
