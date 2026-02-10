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
 * 延迟/周期任务调度器。
 *
 * <p>用于承载 once/fixed-rate/fixed-delay 三类定时任务。
 * 在多个 scope 之间共享时是线程安全的。
 */
public final class DelayScheduler {

    private static final DelayScheduler SHARED = new DelayScheduler(createSharedExecutor(), false);

    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;

    /**
     * 私有构造函数，封装执行器及其所有权语义。
     */
    private DelayScheduler(ScheduledExecutorService executor, boolean ownsExecutor) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * 创建单线程定时调度器。
     *
     * <p>该实例拥有底层执行器，关闭 scope 时可被回收。
     */
    public static DelayScheduler singleThread() {
        return new DelayScheduler(Executors.newSingleThreadScheduledExecutor(), true);
    }

    /**
     * 获取进程级共享调度器实例。
     *
     * <p>默认由框架托管，适合绝大多数场景。
     */
    public static DelayScheduler shared() {
        return SHARED;
    }

    /**
     * 基于外部 {@link ScheduledExecutorService} 构造包装。
     *
     * <p>外部执行器生命周期由调用方负责。
     */
    public static DelayScheduler from(ScheduledExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        return new DelayScheduler(executor, false);
    }

    /**
     * 提交一次性延迟任务（Callable 版本）。
     *
     * <p>示例：
     * <pre>{@code
     * scheduler.schedule(Duration.ofMillis(200), () -> {
     *     doOnce();
     *     return 1;
     * });
     * }</pre>
     */
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

    /**
     * 提交一次性延迟任务（Runnable 版本）。
     */
    public ScheduledTask schedule(Duration delay, final Runnable runnable) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(runnable, "runnable");
        ScheduledFuture<?> future = executor.schedule(runnable, delay.toMillis(), TimeUnit.MILLISECONDS);
        return new DefaultScheduledTask(future);
    }

    /**
     * 固定频率调度任务。
     *
     * <p>示例：
     * <pre>{@code
     * ScheduledTask heartbeat = scheduler.scheduleAtFixedRate(
     *     Duration.ZERO,
     *     Duration.ofSeconds(5),
     *     () -> reportHeartbeat()
     * );
     * }</pre>
     */
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

    /**
     * 固定延迟调度任务。
     *
     * <p>语义：上一次执行结束后，再等待 delay 执行下一次。
     */
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

    /**
     * 当当前调度器拥有执行器所有权时，关闭执行器。
     */
    void shutdownIfOwned() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    /**
     * 创建框架默认共享的单线程调度执行器。
     */
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

    /**
     * 默认计划任务句柄实现。
     */
    private static final class DefaultScheduledTask implements ScheduledTask {

        private final ScheduledFuture<?> future;

        /**
         * 基于底层 ScheduledFuture 构建句柄。
         */
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

    /**
     * 定时线程命名工厂，便于定位线程来源。
     */
    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger id;

        /**
         * 使用给定前缀构造命名工厂。
         */
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
