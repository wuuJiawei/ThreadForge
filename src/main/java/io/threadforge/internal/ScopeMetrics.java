package io.threadforge.internal;

import io.threadforge.ScopeMetricsSnapshot;
import io.threadforge.Task;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead task metrics recorder for one scope.
 */
public final class ScopeMetrics {

    private final LongAdder started;
    private final LongAdder succeeded;
    private final LongAdder failed;
    private final LongAdder cancelled;
    private final LongAdder totalDurationNanos;
    private final AtomicLong maxDurationNanos;

    public ScopeMetrics() {
        this.started = new LongAdder();
        this.succeeded = new LongAdder();
        this.failed = new LongAdder();
        this.cancelled = new LongAdder();
        this.totalDurationNanos = new LongAdder();
        this.maxDurationNanos = new AtomicLong(0L);
    }

    public void recordStart() {
        started.increment();
    }

    public void recordTerminal(Task.State state, long durationNanos) {
        long safeDuration = Math.max(0L, durationNanos);
        totalDurationNanos.add(safeDuration);
        updateMax(safeDuration);

        if (state == Task.State.SUCCESS) {
            succeeded.increment();
        } else if (state == Task.State.FAILED) {
            failed.increment();
        } else if (state == Task.State.CANCELLED) {
            cancelled.increment();
        }
    }

    public ScopeMetricsSnapshot snapshot() {
        return new ScopeMetricsSnapshot(
            started.sum(),
            succeeded.sum(),
            failed.sum(),
            cancelled.sum(),
            totalDurationNanos.sum(),
            maxDurationNanos.get()
        );
    }

    private void updateMax(long durationNanos) {
        long current = maxDurationNanos.get();
        while (durationNanos > current) {
            if (maxDurationNanos.compareAndSet(current, durationNanos)) {
                return;
            }
            current = maxDurationNanos.get();
        }
    }
}
