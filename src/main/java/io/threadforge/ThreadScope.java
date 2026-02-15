package io.threadforge;

import io.threadforge.internal.DefaultCancellationToken;
import io.threadforge.internal.ScopeMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * ThreadForge 的结构化并发作用域。
 *
 * <p>一个 {@code ThreadScope} 会把“任务提交、失败策略、超时取消、清理动作、观测指标”
 * 收敛在同一生命周期边界内，避免并发代码分散在多个组件里难以推理。
 *
 * <p>线程安全约束：
 * 配置方法（{@code with*}）只能在第一次提交/调度前调用；
 * 任务提交与等待（{@code submit}/{@code await}）支持并发调用。
 *
 * <p>上下文传播：
 * 框架会自动捕获提交线程中的 {@link Context}，并在任务线程中恢复，
 * 任务结束后恢复线程原始上下文，避免线程池复用导致串值。
 *
 * <p>推荐用法示例：
 * <pre>{@code
 * try (ThreadScope scope = ThreadScope.open()
 *     .withFailurePolicy(FailurePolicy.FAIL_FAST)
 *     .withDeadline(Duration.ofSeconds(2))) {
 *     Task<Integer> a = scope.submit("rpc-a", () -> 1);
 *     Task<Integer> b = scope.submit("rpc-b", () -> 2);
 *     scope.await(a, b);
 * }
 * }</pre>
 */
public final class ThreadScope implements AutoCloseable {

    private static final AtomicLong SCOPE_IDS = new AtomicLong(1L);
    private static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(30);
    private static final ThreadHook NOOP_HOOK = new ThreadHook() {
    };

    private final long scopeId;
    private final AtomicLong taskIdGen;
    private final AtomicBoolean closed;
    private final AtomicBoolean configLocked;
    private final Queue<Task<?>> tasks;
    private final Queue<ScheduledTask> scheduledTasks;
    private final Deque<Runnable> deferred;
    private final DefaultCancellationToken token;
    private final DelayScheduler delayScheduler;
    private final ScopeMetrics metrics;

    private volatile Scheduler scheduler;
    private volatile FailurePolicy failurePolicy;
    private volatile RetryPolicy retryPolicy;
    private volatile Duration deadline;
    private volatile ThreadHook hook;
    private volatile Semaphore concurrencySemaphore;
    private volatile ScheduledTask deadlineTriggerTask;
    private volatile long deadlineAtNanos;
    private volatile boolean deadlineTriggered;

    /**
     * 私有构造函数，统一设置默认配置和内部基础设施。
     *
     * <p>默认值：
     * {@code scheduler=Scheduler.detect()}，
     * {@code failurePolicy=FAIL_FAST}，
     * {@code deadline=30s}。
     */
    private ThreadScope() {
        this.scopeId = SCOPE_IDS.getAndIncrement();
        this.taskIdGen = new AtomicLong(1L);
        this.closed = new AtomicBoolean(false);
        this.configLocked = new AtomicBoolean(false);
        this.tasks = new ConcurrentLinkedQueue<Task<?>>();
        this.scheduledTasks = new ConcurrentLinkedQueue<ScheduledTask>();
        this.deferred = new java.util.concurrent.ConcurrentLinkedDeque<Runnable>();
        this.scheduler = Scheduler.detect();
        this.failurePolicy = FailurePolicy.FAIL_FAST;
        this.retryPolicy = RetryPolicy.noRetry();
        this.deadline = DEFAULT_DEADLINE;
        this.hook = NOOP_HOOK;
        this.delayScheduler = DelayScheduler.shared();
        this.metrics = new ScopeMetrics();
        this.token = new DefaultCancellationToken(new Runnable() {
            @Override
            public void run() {
                cancelOutstandingTasks();
            }
        });
        rescheduleDeadlineMonitor();
    }

    /**
     * 创建新的作用域实例。
     *
     * <p>每次调用都会返回全新作用域，互不共享取消状态、任务队列和指标。
     *
     * <p>示例：
     * <pre>{@code
     * try (ThreadScope scope = ThreadScope.open()) {
     *     Task<String> t = scope.submit(() -> "ok");
     *     t.await();
     * }
     * }</pre>
     */
    public static ThreadScope open() {
        return new ThreadScope();
    }

    /**
     * 指定任务调度策略。
     *
     * <p>必须在首次 {@code submit}/{@code schedule} 前调用，否则会抛
     * {@link IllegalStateException}。
     *
     * <p>示例：
     * <pre>{@code
     * ThreadScope scope = ThreadScope.open()
     *     .withScheduler(Scheduler.fixed(8));
     * }</pre>
     */
    public ThreadScope withScheduler(Scheduler scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        ensureConfigurable();
        this.scheduler = scheduler;
        return this;
    }

    /**
     * 指定等待阶段的失败策略。
     *
     * <p>策略会影响 {@code await(...)} 遇到失败后的行为，例如
     * “立即抛错并取消其他任务”或“收集失败后统一返回/抛出”。
     */
    public ThreadScope withFailurePolicy(FailurePolicy failurePolicy) {
        Objects.requireNonNull(failurePolicy, "failurePolicy");
        ensureConfigurable();
        this.failurePolicy = failurePolicy;
        return this;
    }

    /**
     * 设置任务失败后的重试策略。
     *
     * <p>默认值为 {@link RetryPolicy#noRetry()}。
     */
    public ThreadScope withRetryPolicy(RetryPolicy retryPolicy) {
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        ensureConfigurable();
        this.retryPolicy = retryPolicy;
        return this;
    }

    /**
     * 设置并发上限（基于信号量）。
     *
     * <p>当达到上限时，后续提交会阻塞等待许可，形成“背压”。
     *
     * <p>示例：
     * <pre>{@code
     * ThreadScope scope = ThreadScope.open().withConcurrencyLimit(32);
     * }</pre>
     */
    public ThreadScope withConcurrencyLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        ensureConfigurable();
        this.concurrencySemaphore = new Semaphore(limit);
        return this;
    }

    /**
     * 设置作用域级截止时间。
     *
     * <p>超时后会触发作用域取消，后续等待通常抛出 {@link ScopeTimeoutException}。
     *
     * <p>示例：
     * <pre>{@code
     * ThreadScope scope = ThreadScope.open()
     *     .withDeadline(Duration.ofMillis(300));
     * }</pre>
     */
    public ThreadScope withDeadline(Duration deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("deadline must be > 0");
        }
        ensureConfigurable();
        this.deadline = deadline;
        rescheduleDeadlineMonitor();
        return this;
    }

    /**
     * 设置任务生命周期回调。
     *
     * <p>内置指标始终可用；hook 适合桥接外部日志、指标、Tracing 系统。
     */
    public ThreadScope withHook(ThreadHook hook) {
        Objects.requireNonNull(hook, "hook");
        ensureConfigurable();
        this.hook = hook;
        return this;
    }

    /**
     * 获取当前调度器。
     */
    public Scheduler scheduler() {
        return scheduler;
    }

    /**
     * 获取当前失败策略。
     */
    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }

    /**
     * 获取当前重试策略。
     */
    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /**
     * 获取当前 deadline 配置。
     */
    public Duration deadline() {
        return deadline;
    }

    /**
     * 获取作用域取消令牌。
     *
     * <p>可在任务内部主动检查/响应取消：
     * <pre>{@code
     * scope.submit(() -> {
     *     while (true) {
     *         scope.token().throwIfCancelled();
     *         // do work
     *     }
     * });
     * }</pre>
     */
    public CancellationToken token() {
        return token;
    }

    /**
     * 获取当前作用域内置运行时指标快照。
     *
     * <p>该方法只返回快照，不会阻塞任务执行线程。
     *
     * <p>示例：
     * <pre>{@code
     * ScopeMetricsSnapshot snapshot = scope.metrics();
     * long completed = snapshot.completed();
     * Duration avg = snapshot.averageDuration();
     * }</pre>
     */
    public ScopeMetricsSnapshot metrics() {
        return metrics.snapshot();
    }

    /**
     * 注册关闭阶段清理动作（LIFO，后注册先执行）。
     *
     * <p>适用于资源回收、回滚、连接关闭等收尾逻辑。
     */
    public void defer(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        ensureOpen();
        deferred.addFirst(cleanup);
    }

    /**
     * 提交匿名任务，任务名自动生成为 {@code task-<id>}。
     */
    public <T> Task<T> submit(Callable<T> callable) {
        long id = taskIdGen.getAndIncrement();
        return submit("task-" + id, callable, retryPolicy, null, id);
    }

    /**
     * 提交具名任务。
     *
     * <p>示例：
     * <pre>{@code
     * Task<String> user = scope.submit("load-user", () -> fetchUser());
     * }</pre>
     */
    public <T> Task<T> submit(String name, Callable<T> callable) {
        long id = taskIdGen.getAndIncrement();
        return submit(name, callable, retryPolicy, null, id);
    }

    /**
     * 提交匿名任务，并覆盖当前 scope 的默认重试策略。
     */
    public <T> Task<T> submit(Callable<T> callable, RetryPolicy retryPolicy) {
        long id = taskIdGen.getAndIncrement();
        return submit("task-" + id, callable, retryPolicy, null, id);
    }

    /**
     * 提交具名任务，并覆盖当前 scope 的默认重试策略。
     */
    public <T> Task<T> submit(String name, Callable<T> callable, RetryPolicy retryPolicy) {
        long id = taskIdGen.getAndIncrement();
        return submit(name, callable, retryPolicy, null, id);
    }

    /**
     * 提交匿名任务，并设置该任务的独立超时。
     */
    public <T> Task<T> submit(Callable<T> callable, Duration timeout) {
        long id = taskIdGen.getAndIncrement();
        return submit("task-" + id, callable, retryPolicy, timeout, id);
    }

    /**
     * 提交具名任务，并设置该任务的独立超时。
     */
    public <T> Task<T> submit(String name, Callable<T> callable, Duration timeout) {
        long id = taskIdGen.getAndIncrement();
        return submit(name, callable, retryPolicy, timeout, id);
    }

    /**
     * 提交匿名任务，并同时设置重试策略和任务级超时。
     */
    public <T> Task<T> submit(Callable<T> callable, RetryPolicy retryPolicy, Duration timeout) {
        long id = taskIdGen.getAndIncrement();
        return submit("task-" + id, callable, retryPolicy, timeout, id);
    }

    /**
     * 提交具名任务，并同时设置重试策略和任务级超时。
     */
    public <T> Task<T> submit(String name, Callable<T> callable, RetryPolicy retryPolicy, Duration timeout) {
        long id = taskIdGen.getAndIncrement();
        return submit(name, callable, retryPolicy, timeout, id);
    }

    /**
     * 等待一组任务完成，并按失败策略生成聚合结果。
     *
     * <p>不同 {@link FailurePolicy} 会影响失败处理语义：
     * FAIL_FAST 直接抛出首个失败；
     * COLLECT_ALL 在结束后抛 {@link AggregateException}；
     * SUPERVISOR/CANCEL_OTHERS/IGNORE_ALL 返回 {@link Outcome}。
     *
     * <p>示例：
     * <pre>{@code
     * Outcome outcome = scope.await(Arrays.asList(taskA, taskB));
     * if (outcome.hasFailures()) {
     *     // classify failures
     * }
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public Outcome await(Collection<? extends Task<?>> awaitedTasks) {
        Objects.requireNonNull(awaitedTasks, "awaitedTasks");
        ensureOpen();

        List<Task<?>> taskList = new ArrayList<Task<?>>(awaitedTasks);
        if (taskList.isEmpty()) {
            return new Outcome(0, 0, 0, Collections.<Throwable>emptyList());
        }

        int succeeded = 0;
        int cancelled = 0;
        List<Throwable> failures = new ArrayList<Throwable>();

        for (Task<?> task : taskList) {
            try {
                Duration remaining = remainingDeadline();
                if (remaining == null) {
                    task.await();
                } else {
                    task.await(remaining);
                }
                succeeded++;
            } catch (ScopeTimeoutException timeout) {
                triggerDeadline();
                throw new ScopeTimeoutException("ThreadScope deadline exceeded");
            } catch (CancelledException cancelledException) {
                cancelled++;
            } catch (RuntimeException failure) {
                if (failurePolicy == FailurePolicy.FAIL_FAST) {
                    cancelOthers(taskList, task);
                    throw failure;
                }
                if (failurePolicy == FailurePolicy.CANCEL_OTHERS) {
                    cancelOthers(taskList, task);
                    failures.add(failure);
                } else if (failurePolicy == FailurePolicy.COLLECT_ALL || failurePolicy == FailurePolicy.SUPERVISOR) {
                    failures.add(failure);
                }
            }
        }

        if (deadlineTriggered) {
            throw new ScopeTimeoutException("ThreadScope deadline exceeded");
        }

        if (failurePolicy == FailurePolicy.COLLECT_ALL && !failures.isEmpty()) {
            throw new AggregateException(failures);
        }

        if (failurePolicy == FailurePolicy.IGNORE_ALL) {
            failures = Collections.emptyList();
        }

        return new Outcome(taskList.size(), succeeded, cancelled, failures);
    }

    /**
     * {@link #await(Collection)} 的可变参数重载。
     */
    public Outcome await(Task<?> first, Task<?>... rest) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(rest, "rest");
        List<Task<?>> taskList = new ArrayList<Task<?>>(rest.length + 1);
        taskList.add(first);
        taskList.addAll(Arrays.<Task<?>>asList(rest));
        return await(taskList);
    }

    /**
     * 等待同类型任务集合，并返回与输入顺序一致的结果列表。
     *
     * <p>失败或取消的任务位置会返回 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> awaitAll(Collection<? extends Task<T>> awaitedTasks) {
        Objects.requireNonNull(awaitedTasks, "awaitedTasks");
        List<Task<T>> taskList = new ArrayList<Task<T>>(awaitedTasks);

        await((Collection<? extends Task<?>>) (Collection<?>) taskList);

        List<T> values = new ArrayList<T>(taskList.size());
        for (Task<T> task : taskList) {
            if (task.state() == Task.State.SUCCESS) {
                values.add(task.await());
            } else {
                values.add(null);
            }
        }
        return Collections.unmodifiableList(values);
    }

    /**
     * {@link #awaitAll(Collection)} 的可变参数重载。
     */
    @SafeVarargs
    public final <T> List<T> awaitAll(Task<T> first, Task<T>... rest) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(rest, "rest");
        List<Task<T>> taskList = new ArrayList<Task<T>>(rest.length + 1);
        taskList.add(first);
        taskList.addAll(Arrays.<Task<T>>asList(rest));
        return awaitAll(taskList);
    }

    /**
     * 提交一次性延迟任务（可返回值）。
     *
     * <p>任务执行前会检查取消令牌，已取消时抛出 {@link CancelledException}。
     */
    public <T> ScheduledTask schedule(Duration delay, final Callable<T> callable) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(callable, "callable");
        lockConfiguration();
        ensureOpen();
        compactFinishedScheduledTasks();
        final Context.Snapshot contextSnapshot = Context.capture();

        ScheduledTask task = delayScheduler.schedule(delay, new Callable<T>() {
            @Override
            public T call() throws Exception {
                Context.Snapshot previous = Context.install(contextSnapshot);
                try {
                    token.throwIfCancelled();
                    return callable.call();
                } finally {
                    Context.restore(previous);
                }
            }
        });
        scheduledTasks.add(task);
        return task;
    }

    /**
     * 提交一次性延迟任务（无返回值）。
     */
    public ScheduledTask schedule(Duration delay, final Runnable runnable) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(runnable, "runnable");
        lockConfiguration();
        ensureOpen();
        compactFinishedScheduledTasks();
        final Context.Snapshot contextSnapshot = Context.capture();

        ScheduledTask task = delayScheduler.schedule(delay, new Runnable() {
            @Override
            public void run() {
                Context.Snapshot previous = Context.install(contextSnapshot);
                try {
                    token.throwIfCancelled();
                    runnable.run();
                } finally {
                    Context.restore(previous);
                }
            }
        });
        scheduledTasks.add(task);
        return task;
    }

    /**
     * 固定频率周期调度任务。
     *
     * <p>常用于心跳、指标上报等固定节拍场景。
     */
    public ScheduledTask scheduleAtFixedRate(Duration initial, Duration period, final Runnable runnable) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(runnable, "runnable");
        lockConfiguration();
        ensureOpen();
        compactFinishedScheduledTasks();
        final Context.Snapshot contextSnapshot = Context.capture();

        ScheduledTask task = delayScheduler.scheduleAtFixedRate(initial, period, new Runnable() {
            @Override
            public void run() {
                Context.Snapshot previous = Context.install(contextSnapshot);
                try {
                    token.throwIfCancelled();
                    runnable.run();
                } finally {
                    Context.restore(previous);
                }
            }
        });
        scheduledTasks.add(task);
        return task;
    }

    /**
     * 固定延迟周期调度任务。
     *
     * <p>本次执行结束后，等待固定 delay 再启动下一次执行。
     */
    public ScheduledTask scheduleWithFixedDelay(Duration initial, Duration delay, final Runnable runnable) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(runnable, "runnable");
        lockConfiguration();
        ensureOpen();
        compactFinishedScheduledTasks();
        final Context.Snapshot contextSnapshot = Context.capture();

        ScheduledTask task = delayScheduler.scheduleWithFixedDelay(initial, delay, new Runnable() {
            @Override
            public void run() {
                Context.Snapshot previous = Context.install(contextSnapshot);
                try {
                    token.throwIfCancelled();
                    runnable.run();
                } finally {
                    Context.restore(previous);
                }
            }
        });
        scheduledTasks.add(task);
        return task;
    }

    /**
     * 关闭作用域并执行统一收尾。
     *
     * <p>关闭顺序：
     * 1) 触发 token 取消；
     * 2) 取消计划任务；
     * 3) 取消未完成任务；
     * 4) 执行 defer 清理（LIFO）；
     * 5) 关闭 scope 持有的执行器。
     *
     * <p>该方法幂等，重复调用安全。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        Throwable primary = null;

        token.cancel();

        for (ScheduledTask scheduledTask : scheduledTasks) {
            try {
                scheduledTask.cancel();
            } catch (Throwable t) {
                primary = combine(primary, t);
            }
        }

        for (Task<?> task : tasks) {
            try {
                if (!task.isDone()) {
                    task.cancel();
                }
            } catch (Throwable t) {
                primary = combine(primary, t);
            }
        }

        for (Runnable cleanup : deferred) {
            try {
                cleanup.run();
            } catch (Throwable t) {
                primary = combine(primary, t);
            }
        }

        try {
            if (deadlineTriggerTask != null) {
                deadlineTriggerTask.cancel();
            }
        } catch (Throwable t) {
            primary = combine(primary, t);
        }

        scheduler.shutdownIfOwned();
        delayScheduler.shutdownIfOwned();

        if (primary != null) {
            if (primary instanceof RuntimeException) {
                throw (RuntimeException) primary;
            }
            throw new RuntimeException(primary);
        }

    }

    /**
     * 内部提交实现：完成配置锁定、并发许可获取、future 绑定、调度执行。
     */
    private <T> Task<T> submit(
        String name,
        final Callable<T> callable,
        RetryPolicy retryPolicy,
        Duration timeout,
        long id
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(callable, "callable");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        validateTaskTimeout(timeout);
        lockConfiguration();
        ensureOpen();
        final Semaphore semaphore = concurrencySemaphore;
        final boolean permitAcquired = acquireSubmissionPermit(semaphore);
        final RetryPolicy taskRetryPolicy = retryPolicy;
        final Duration taskTimeout = timeout;

        final CompletableFuture<T> future = new CompletableFuture<T>();
        final Task<T> task = new Task<T>(id, name, future);
        final TaskInfo info = new TaskInfo(scopeId, id, name, Instant.now(), scheduler.name());
        final Context.Snapshot contextSnapshot = Context.capture();
        final ScheduledTask timeoutTask = scheduleTaskTimeout(task, info, taskTimeout);
        tasks.add(task);
        future.whenComplete(new BiConsumer<T, Throwable>() {
            @Override
            public void accept(T value, Throwable throwable) {
                tasks.remove(task);
                if (timeoutTask != null) {
                    timeoutTask.cancel();
                }
            }
        });

        try {
            scheduler.executor().execute(new Runnable() {
                @Override
                public void run() {
                    Context.Snapshot previous = Context.install(contextSnapshot);
                    try {
                        runTask(task, info, callable, taskRetryPolicy, permitAcquired ? semaphore : null);
                    } finally {
                        Context.restore(previous);
                    }
                }
            });
        } catch (RejectedExecutionException rejectedExecutionException) {
            if (permitAcquired && semaphore != null) {
                semaphore.release();
            }
            task.markFailed();
            future.completeExceptionally(rejectedExecutionException);
            safeHookFailure(info, rejectedExecutionException, 0L);
        }

        return task;
    }

    /**
     * 在工作线程内执行任务主体，并统一处理状态迁移、异常传播、观测打点。
     */
    private <T> void runTask(
        Task<T> task,
        TaskInfo info,
        Callable<T> callable,
        RetryPolicy retryPolicy,
        Semaphore acquiredSemaphore
    ) {
        long started = System.nanoTime();
        CompletableFuture<T> future = task.toCompletableFuture();

        try {
            if (task.isCancelled() || token.isCancelled()) {
                completeTaskCancelled(task, future, new CancelledException("Task cancelled before start"), info, started);
                return;
            }

            if (!task.markRunning(Thread.currentThread())) {
                return;
            }

            safeHookStart(info);
            token.throwIfCancelled();

            T value = executeWithRetry(callable, retryPolicy);
            if (future.complete(value)) {
                task.markSuccess();
                safeHookSuccess(info, elapsedNanos(started));
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            completeTaskCancelled(task, future, new CancelledException("Task interrupted", interruptedException), info, started);
        } catch (CancelledException cancelledException) {
            completeTaskCancelled(task, future, cancelledException, info, started);
        } catch (Throwable throwable) {
            completeTaskFailure(task, future, throwable, info, started);
        } finally {
            if (acquiredSemaphore != null) {
                acquiredSemaphore.release();
            }
        }
    }

    /**
     * 在同一个任务上下文内执行重试，不额外拆分任务句柄。
     */
    private <T> T executeWithRetry(Callable<T> callable, RetryPolicy retryPolicy) throws Exception {
        int attempt = 1;
        List<Throwable> previousFailures = null;

        while (true) {
            token.throwIfCancelled();
            try {
                return callable.call();
            } catch (InterruptedException interruptedException) {
                throw interruptedException;
            } catch (CancelledException cancelledException) {
                throw cancelledException;
            } catch (Throwable failure) {
                if (!retryPolicy.allowsRetry(attempt, failure)) {
                    if (previousFailures != null) {
                        for (Throwable previousFailure : previousFailures) {
                            if (previousFailure != failure) {
                                failure.addSuppressed(previousFailure);
                            }
                        }
                    }
                    throw failure;
                }

                if (previousFailures == null) {
                    previousFailures = new ArrayList<Throwable>();
                }
                previousFailures.add(failure);

                sleepBeforeRetry(retryPolicy.nextDelay(attempt, failure));
                attempt++;
            }
        }
    }

    /**
     * 可中断的重试等待，周期性检查取消信号。
     */
    private void sleepBeforeRetry(Duration delay) throws InterruptedException {
        if (delay == null || delay.isNegative() || delay.isZero()) {
            return;
        }
        long remainingMillis = delay.toMillis();
        if (remainingMillis == 0L) {
            remainingMillis = 1L;
        }
        while (remainingMillis > 0L) {
            token.throwIfCancelled();
            long chunk = Math.min(remainingMillis, 100L);
            Thread.sleep(chunk);
            remainingMillis -= chunk;
        }
    }

    private <T> void completeTaskCancelled(
        Task<T> task,
        CompletableFuture<T> future,
        CancelledException cancelledException,
        TaskInfo info,
        long started
    ) {
        if (future.completeExceptionally(cancelledException)) {
            task.markCancelled();
            safeHookCancel(info, elapsedNanos(started));
            return;
        }
        if (future.isCancelled() || task.state() == Task.State.CANCELLED) {
            task.markCancelled();
            safeHookCancel(info, elapsedNanos(started));
        }
    }

    private <T> void completeTaskFailure(
        Task<T> task,
        CompletableFuture<T> future,
        Throwable throwable,
        TaskInfo info,
        long started
    ) {
        if (future.completeExceptionally(throwable)) {
            task.markFailed();
            safeHookFailure(info, throwable, elapsedNanos(started));
        }
    }

    private ScheduledTask scheduleTaskTimeout(final Task<?> task, final TaskInfo info, final Duration timeout) {
        if (timeout == null) {
            return null;
        }
        return delayScheduler.schedule(timeout, new Runnable() {
            @Override
            public void run() {
                TaskTimeoutException timeoutException = taskTimeoutException(info, timeout);
                if (task.toCompletableFuture().completeExceptionally(timeoutException)) {
                    task.markFailed();
                    task.interruptRunner();
                    safeHookFailure(info, timeoutException, timeout.toNanos());
                }
            }
        });
    }

    private TaskTimeoutException taskTimeoutException(TaskInfo info, Duration timeout) {
        return new TaskTimeoutException("Task '" + info.name() + "' timed out after " + timeout.toMillis() + " ms");
    }

    private void validateTaskTimeout(Duration timeout) {
        if (timeout == null) {
            return;
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("task timeout must be > 0");
        }
    }

    /**
     * 取消当前作用域仍未完成的普通任务和计划任务。
     */
    private void cancelOutstandingTasks() {
        for (Task<?> task : tasks) {
            if (!task.isDone()) {
                task.cancel();
            }
        }
        compactFinishedScheduledTasks();
        for (ScheduledTask scheduledTask : scheduledTasks) {
            scheduledTask.cancel();
        }
    }

    /**
     * 在“取消其他任务”语义下，取消失败任务之外的兄弟任务。
     */
    private void cancelOthers(List<Task<?>> taskList, Task<?> failedTask) {
        for (Task<?> task : taskList) {
            if (task != failedTask && !task.isDone()) {
                task.cancel();
            }
        }
    }

    /**
     * 计算当前剩余 deadline。
     *
     * <p>若已到期，会主动触发取消并抛出 {@link ScopeTimeoutException}。
     */
    private Duration remainingDeadline() {
        if (deadline == null) {
            return null;
        }
        if (deadlineTriggered) {
            throw new ScopeTimeoutException("ThreadScope deadline exceeded");
        }

        long remainingNanos = deadlineAtNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            triggerDeadline();
            throw new ScopeTimeoutException("ThreadScope deadline exceeded");
        }
        return Duration.ofNanos(remainingNanos);
    }

    /**
     * 触发 deadline 超时状态，并传播为 token 取消。
     */
    private void triggerDeadline() {
        if (!deadlineTriggered) {
            deadlineTriggered = true;
            token.cancel();
        }
    }

    /**
     * 重新注册 deadline 监控任务。
     *
     * <p>每次 deadline 配置变更后都会调用该方法。
     */
    private void rescheduleDeadlineMonitor() {
        this.deadlineAtNanos = System.nanoTime() + deadline.toNanos();
        if (deadlineTriggerTask != null) {
            deadlineTriggerTask.cancel();
        }
        deadlineTriggerTask = delayScheduler.schedule(deadline, new Runnable() {
            @Override
            public void run() {
                triggerDeadline();
            }
        });
    }

    /**
     * 获取提交许可（并发限流）。
     *
     * <p>采用短周期轮询 + 剩余 deadline 控制，避免无限阻塞。
     */
    private boolean acquireSubmissionPermit(Semaphore semaphore) {
        if (semaphore == null) {
            return false;
        }
        while (true) {
            token.throwIfCancelled();

            Duration remaining = remainingDeadline();
            long nanos = Math.min(remaining.toNanos(), TimeUnit.MILLISECONDS.toNanos(100));
            if (nanos <= 0L) {
                triggerDeadline();
                throw new ScopeTimeoutException("ThreadScope deadline exceeded");
            }

            try {
                if (semaphore.tryAcquire(nanos, TimeUnit.NANOSECONDS)) {
                    return true;
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new CancelledException("Interrupted while waiting for concurrency permit", interruptedException);
            }
        }
    }

    /**
     * 清理已经结束的计划任务句柄，避免内部集合无限增长。
     */
    private void compactFinishedScheduledTasks() {
        for (ScheduledTask scheduledTask : scheduledTasks) {
            if (scheduledTask.isDone()) {
                scheduledTasks.remove(scheduledTask);
            }
        }
    }

    /**
     * 聚合关闭阶段异常：保留首个异常并把后续异常附加为 suppressed。
     */
    private Throwable combine(Throwable primary, Throwable next) {
        if (primary == null) {
            return next;
        }
        primary.addSuppressed(next);
        return primary;
    }

    /**
     * 校验作用域仍处于打开状态。
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ThreadScope already closed");
        }
    }

    /**
     * 校验当前阶段允许修改配置。
     */
    private void ensureConfigurable() {
        ensureOpen();
        if (configLocked.get()) {
            throw new IllegalStateException("ThreadScope configuration is locked after first task submission");
        }
    }

    /**
     * 在首次提交/调度后锁定配置，防止运行时改变语义。
     */
    private void lockConfiguration() {
        configLocked.set(true);
    }

    /**
     * 计算运行耗时纳秒值，并确保非负。
     */
    private long elapsedNanos(long startedAtNanos) {
        return Math.max(0L, System.nanoTime() - startedAtNanos);
    }

    /**
     * 安全触发 onStart：
     * 1) 记录内置指标；
     * 2) 若未配置 hook，快速返回；
     * 3) 若配置了 hook，吞掉 hook 内异常，避免影响主流程。
     */
    private void safeHookStart(TaskInfo info) {
        metrics.recordStart();
        if (hook == NOOP_HOOK) {
            return;
        }
        try {
            hook.onStart(info);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 安全触发 onSuccess，并记录成功态指标。
     */
    private void safeHookSuccess(TaskInfo info, long durationNanos) {
        metrics.recordTerminal(Task.State.SUCCESS, durationNanos);
        if (hook == NOOP_HOOK) {
            return;
        }
        try {
            hook.onSuccess(info, Duration.ofNanos(durationNanos));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 安全触发 onFailure，并记录失败态指标。
     */
    private void safeHookFailure(TaskInfo info, Throwable throwable, long durationNanos) {
        metrics.recordTerminal(Task.State.FAILED, durationNanos);
        if (hook == NOOP_HOOK) {
            return;
        }
        try {
            hook.onFailure(info, throwable, Duration.ofNanos(durationNanos));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 安全触发 onCancel，并记录取消态指标。
     */
    private void safeHookCancel(TaskInfo info, long durationNanos) {
        metrics.recordTerminal(Task.State.CANCELLED, durationNanos);
        if (hook == NOOP_HOOK) {
            return;
        }
        try {
            hook.onCancel(info, Duration.ofNanos(durationNanos));
        } catch (Throwable ignored) {
        }
    }
}
