package io.threadforge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DelaySchedulerDurationValidationTest {

    @Test
    void oneShotDelayRejectsNegativeAndAllowsZero() {
        RecordingExecutor executor = new RecordingExecutor();
        try {
            DelayScheduler scheduler = DelayScheduler.from(executor);

            assertThrows(IllegalArgumentException.class, () ->
                scheduler.schedule(Duration.ofNanos(-1L), new Runnable() {
                    @Override
                    public void run() {
                    }
                }));
            assertThrows(IllegalArgumentException.class, () ->
                scheduler.schedule(Duration.ofNanos(-1L), new Callable<Integer>() {
                    @Override
                    public Integer call() {
                        return 1;
                    }
                }));
            assertEquals(0, executor.calls);

            scheduler.schedule(Duration.ZERO, new Runnable() {
                @Override
                public void run() {
                }
            });
            assertEquals(0L, executor.delay);
            assertEquals(TimeUnit.NANOSECONDS, executor.unit);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oneShotDelayPreservesNanosecondsAndSaturatesHugeDuration() {
        RecordingExecutor executor = new RecordingExecutor();
        try {
            DelayScheduler scheduler = DelayScheduler.from(executor);

            scheduler.schedule(Duration.ofNanos(1L), new Runnable() {
                @Override
                public void run() {
                }
            });
            assertEquals(1L, executor.delay);
            assertEquals(TimeUnit.NANOSECONDS, executor.unit);

            scheduler.schedule(Duration.ofSeconds(Long.MAX_VALUE), new Runnable() {
                @Override
                public void run() {
                }
            });
            assertEquals(Long.MAX_VALUE, executor.delay);
            assertEquals(TimeUnit.NANOSECONDS, executor.unit);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fixedRateValidatesInitialDelayAndPositivePeriod() {
        RecordingExecutor executor = new RecordingExecutor();
        try {
            DelayScheduler scheduler = DelayScheduler.from(executor);
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                }
            };

            assertThrows(IllegalArgumentException.class, () ->
                scheduler.scheduleAtFixedRate(Duration.ofNanos(-1L), Duration.ofNanos(1L), runnable));
            assertThrows(IllegalArgumentException.class, () ->
                scheduler.scheduleAtFixedRate(Duration.ZERO, Duration.ZERO, runnable));
            assertThrows(IllegalArgumentException.class, () ->
                scheduler.scheduleAtFixedRate(Duration.ZERO, Duration.ofNanos(-1L), runnable));
            assertEquals(0, executor.calls);

            scheduler.scheduleAtFixedRate(Duration.ZERO, Duration.ofNanos(1L), runnable);
            assertEquals(0L, executor.initialDelay);
            assertEquals(1L, executor.interval);
            assertEquals(TimeUnit.NANOSECONDS, executor.unit);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fixedDelayValidatesInitialDelayAndPositiveDelay() {
        RecordingExecutor executor = new RecordingExecutor();
        try {
            DelayScheduler scheduler = DelayScheduler.from(executor);
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                }
            };

            assertThrows(IllegalArgumentException.class, () ->
                scheduler.scheduleWithFixedDelay(Duration.ofNanos(-1L), Duration.ofNanos(1L), runnable));
            assertThrows(IllegalArgumentException.class, () ->
                scheduler.scheduleWithFixedDelay(Duration.ZERO, Duration.ZERO, runnable));
            assertThrows(IllegalArgumentException.class, () ->
                scheduler.scheduleWithFixedDelay(Duration.ZERO, Duration.ofNanos(-1L), runnable));
            assertEquals(0, executor.calls);

            scheduler.scheduleWithFixedDelay(Duration.ZERO, Duration.ofNanos(1L), runnable);
            assertEquals(0L, executor.initialDelay);
            assertEquals(1L, executor.interval);
            assertEquals(TimeUnit.NANOSECONDS, executor.unit);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {
        private int calls;
        private long delay;
        private long initialDelay;
        private long interval;
        private TimeUnit unit;

        private RecordingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            calls++;
            this.delay = delay;
            this.unit = unit;
            return new NeverScheduledFuture<Object>();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            calls++;
            this.delay = delay;
            this.unit = unit;
            return new NeverScheduledFuture<V>();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
            Runnable command,
            long initialDelay,
            long period,
            TimeUnit unit
        ) {
            calls++;
            this.initialDelay = initialDelay;
            this.interval = period;
            this.unit = unit;
            return new NeverScheduledFuture<Object>();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable command,
            long initialDelay,
            long delay,
            TimeUnit unit
        ) {
            calls++;
            this.initialDelay = initialDelay;
            this.interval = delay;
            this.unit = unit;
            return new NeverScheduledFuture<Object>();
        }
    }

    private static final class NeverScheduledFuture<V> implements ScheduledFuture<V> {
        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return Long.MAX_VALUE;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        @Override
        public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
            throw new TimeoutException();
        }
    }
}
