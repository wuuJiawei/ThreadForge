package io.threadforge;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduler for delayed and periodic tasks.
 * Thread-safe when shared across scopes.
 */
public final class DelayScheduler {

    private static final DelayScheduler SHARED = new DelayScheduler(createSharedExecutor(), false);

    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;

    private DelayScheduler(ScheduledExecutorService executor, boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    public static DelayScheduler singleThread() {
        return new DelayScheduler(Executors.newSingleThreadScheduledExecutor(), true);
    }

    public static DelayScheduler shared() {
        return SHARED;
    }

    public static DelayScheduler from(ScheduledExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        return new DelayScheduler(executor, false);
    }

    public <T> ScheduledTask schedule(Duration delay, final Callable<T> callable) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(callable, "callable");
        ScheduledFuture<?> future = executor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    callable.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
        return new DefaultScheduledTask(future);
    }

    public ScheduledTask schedule(Duration delay, final Runnable runnable) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(runnable, "runnable");
        ScheduledFuture<?> future = executor.schedule(runnable, delay.toMillis(), TimeUnit.MILLISECONDS);
        return new DefaultScheduledTask(future);
    }

    public ScheduledTask scheduleAtFixedRate(Duration initial, Duration period, Runnable runnable) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(runnable, "runnable");
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
            runnable,
            initial.toMillis(),
            period.toMillis(),
            TimeUnit.MILLISECONDS
        );
        return new DefaultScheduledTask(future);
    }

    public ScheduledTask scheduleWithFixedDelay(Duration initial, Duration delay, Runnable runnable) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(runnable, "runnable");
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
            runnable,
            initial.toMillis(),
            delay.toMillis(),
            TimeUnit.MILLISECONDS
        );
        return new DefaultScheduledTask(future);
    }

    void shutdownIfOwned() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private static ScheduledExecutorService createSharedExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            1,
            new NamedThreadFactory("threadforge-delay")
        );
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static final class DefaultScheduledTask implements ScheduledTask {

        private final ScheduledFuture<?> future;

        private DefaultScheduledTask(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Override
        public boolean cancel() {
            return future.cancel(true);
        }

        @Override
        public boolean isCancelled() {
            return future.isCancelled();
        }

        @Override
        public boolean isDone() {
            return future.isDone();
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger id;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
            this.id = new AtomicInteger(1);
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + id.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
