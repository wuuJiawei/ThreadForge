package io.threadforge;

import io.threadforge.internal.cancellation.DefaultCancellationToken;
import io.threadforge.internal.hook.ThreadHooks;
import io.threadforge.internal.metrics.ScopeMetrics;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Object lifecycleLock;
    private final AtomicBoolean configLocked;
    private final Queue<Task<?>> tasks;
    private final Queue<ScheduledTask> scheduledTasks;
    private final Deque<Runnable> deferred;
    private final DefaultCancellationToken token;
    private final DelayScheduler delayScheduler;
    private final DelayScheduler controlDelayScheduler;
    private final ScopeMetrics metrics;

    private volatile Scheduler scheduler;
    private volatile FailurePolicy failurePolicy;
    private volatile RetryPolicy retryPolicy;
    private volatile TaskPriority defaultTaskPriority;
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
        this.lifecycleLock = new Object();
        this.configLocked = new AtomicBoolean(false);
        this.tasks = new ConcurrentLinkedQueue<Task<?>>();
        this.scheduledTasks = new ConcurrentLinkedQueue<ScheduledTask>();
        this.deferred = new java.util.concurrent.ConcurrentLinkedDeque<Runnable>();
        this.scheduler = Scheduler.detect();
        this.failurePolicy = FailurePolicy.FAIL_FAST;
        this.retryPolicy = RetryPolicy.noRetry();
        this.defaultTaskPriority = TaskPriority.NORMAL;
        this.deadline = DEFAULT_DEADLINE;
        this.hook = NOOP_HOOK;
        this.delayScheduler = DelayScheduler.shared();
        this.controlDelayScheduler = DelayScheduler.control();
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
     * 设置默认任务优先级。
     *
     * <p>仅在优先级调度器（如 {@link Scheduler#priority(int)}）中会影响队列顺序。
     */
    public ThreadScope withDefaultTaskPriority(TaskPriority taskPriority) {
        Objects.requireNonNull(taskPriority, "taskPriority");
        ensureConfigurable();
        this.defaultTaskPriority = taskPriority;
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
     * 启用 OpenTelemetry 任务追踪（默认 instrumentation name: {@code io.threadforge}）。
     *
     * <p>要求运行时 classpath 存在 OpenTelemetry API 依赖。
     */
    public ThreadScope withOpenTelemetry() {
        return withOpenTelemetry("io.threadforge");
    }

    /**
     * 启用 OpenTelemetry 任务追踪，并指定 instrumentation name。
     */
    public ThreadScope withOpenTelemetry(String instrumentationName) {
        Objects.requireNonNull(instrumentationName, "instrumentationName");
        ensureConfigurable();
        ThreadHook otelHook = OpenTelemetryHook.create(instrumentationName);
        if (hook == NOOP_HOOK) {
            this.hook = otelHook;
        } else {
            this.hook = ThreadHooks.compose(this.hook, otelHook);
        }
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
     * 获取默认任务优先级。
     */
    public TaskPriority defaultTaskPriority() {
        return defaultTaskPriority;
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
     * Return a helper for higher-order orchestration patterns such as first-success, quorum, and hedged execution.
     */
    public ScopeJoiner joiner() {
        ensureOpen();
        return new ScopeJoiner(this);
    }

    public <T, R> R join(JoinStrategy<T, R> strategy, Collection<? extends Callable<T>> callables) {
        return joiner().join(strategy, callables);
    }

    @SafeVarargs
    public final <T, R> R join(JoinStrategy<T, R> strategy, Callable<T> first, Callable<T>... rest) {
        return joiner().join(strategy, first, rest);
    }

    /**
     * 注册关闭阶段清理动作（LIFO，后注册先执行）。
     *
     * <p>适用于资源回收、回滚、连接关闭等收尾逻辑。
     */
    public void defer(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        synchronized (lifecycleLock) {
            ensureOpen();
            deferred.addFirst(cleanup);
        }
    }

    private long nextTaskId() {
        return taskIdGen.getAndIncrement();
    }

    public <T> Task<T> submit(Callable<T> callable) {
        long id = nextTaskId();
        return submit("task-" + id, callable, defaultTaskPriority, retryPolicy, null, id);
    }

    public <T> Task<T> submit(String name, Callable<T> callable) {
        long id = nextTaskId();
        return submit(name, callable, defaultTaskPriority, retryPolicy, null, id);
    }

    /**
     * 提交无返回值的匿名任务。
     */
    public Task<Void> submit(Runnable runnable) {
        return submit(asCallable(runnable));
    }

    /**
     * 提交无返回值的具名任务。
     */
    public Task<Void> submit(String name, Runnable runnable) {
        return submit(name, asCallable(runnable));
    }

    public <T> Task<T> submit(Callable<T> callable, TaskPriority taskPriority) {
        long id = nextTaskId();
        return submit("task-" + id, callable, taskPriority, retryPolicy, null, id);
    }

    public <T> Task<T> submit(String name, Callable<T> callable, TaskPriority taskPriority) {
        long id = nextTaskId();
        return submit(name, callable, taskPriority, retryPolicy, null, id);
    }

    public <T> Task<T> submit(Callable<T> callable, RetryPolicy retryPolicy) {
        long id = nextTaskId();
        return submit("task-" + id, callable, defaultTaskPriority, retryPolicy, null, id);
    }

    public <T> Task<T> submit(String name, Callable<T> callable, RetryPolicy retryPolicy) {
        long id = nextTaskId();
        return submit(name, callable, defaultTaskPriority, retryPolicy, null, id);
    }

    public <T> Task<T> submit(Callable<T> callable, TaskPriority taskPriority, RetryPolicy retryPolicy) {
        long id = nextTaskId();
        return submit("task-" + id, callable, taskPriority, retryPolicy, null, id);
    }

    public <T> Task<T> submit(String name, Callable<T> callable, TaskPriority taskPriority, RetryPolicy retryPolicy) {
        long id = nextTaskId();
        return submit(name, callable, taskPriority, retryPolicy, null, id);
    }

    public <T> Task<T> submit(Callable<T> callable, Duration timeout) {
        long id = nextTaskId();
        return submit("task-" + id, callable, defaultTaskPriority, retryPolicy, timeout, id);
    }

    public <T> Task<T> submit(String name, Callable<T> callable, Duration timeout) {
        long id = nextTaskId();
        return submit(name, callable, defaultTaskPriority, retryPolicy, timeout, id);
    }

    public <T> Task<T> submit(Callable<T> callable, TaskPriority taskPriority, Duration timeout) {
        long id = nextTaskId();
        return submit("task-" + id, callable, taskPriority, retryPolicy, timeout, id);
    }

    public <T> Task<T> submit(String name, Callable<T> callable, TaskPriority taskPriority, Duration timeout) {
        long id = nextTaskId();
        return submit(name, callable, taskPriority, retryPolicy, timeout, id);
    }

    public <T> Task<T> submit(Callable<T> callable, RetryPolicy retryPolicy, Duration timeout) {
        long id = nextTaskId();
        return submit("task-" + id, callable, defaultTaskPriority, retryPolicy, timeout, id);
    }

    public <T> Task<T> submit(String name, Callable<T> callable, RetryPolicy retryPolicy, Duration timeout) {
        long id = nextTaskId();
        return submit(name, callable, defaultTaskPriority, retryPolicy, timeout, id);
    }

    public <T> Task<T> submit(Callable<T> callable, TaskPriority taskPriority, RetryPolicy retryPolicy, Duration timeout) {
        long id = nextTaskId();
        return submit("task-" + id, callable, taskPriority, retryPolicy, timeout, id);
    }

    public <T> Task<T> submit(String name, Callable<T> callable, TaskPriority taskPriority, RetryPolicy retryPolicy, Duration timeout) {
        long id = nextTaskId();
        return submit(name, callable, taskPriority, retryPolicy, timeout, id);
    }
    @SuppressWarnings("unchecked")
    public Outcome await(Collection<? extends Task<?>> awaitedTasks) {
        Objects.requireNonNull(awaitedTasks, "awaitedTasks");
        ensureOpen();

        List<Task<?>> taskList = new ArrayList<Task<?>>(awaitedTasks);
        if (taskList.isEmpty()) {
            return new Outcome(0, 0, 0, Collections.<Throwable>emptyList());
        }
        if (failurePolicy == FailurePolicy.FAIL_FAST) {
            return awaitFailFast(taskList);
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
                if (Thread.currentThread().isInterrupted()) {
                    throw cancelledException;
                }
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

    private Outcome awaitFailFast(final List<Task<?>> taskList) {
        final BlockingQueue<Task<?>> completions = new LinkedBlockingQueue<Task<?>>();
        for (final Task<?> task : taskList) {
            task.internalFuture().whenComplete(new java.util.function.BiConsumer<Object, Throwable>() {
                @Override
                public void accept(Object value, Throwable failure) {
                    completions.offer(task);
                }
            });
        }

        int succeeded = 0;
        int cancelled = 0;
        for (int completed = 0; completed < taskList.size(); completed++) {
            Task<?> task = takeCompletedTask(completions);
            try {
                task.await();
                succeeded++;
            } catch (CancelledException cancellation) {
                cancelled++;
            } catch (RuntimeException failure) {
                cancelOthers(taskList, task);
                throw failure;
            } catch (Error failure) {
                cancelOthers(taskList, task);
                throw failure;
            }
        }

        if (deadlineTriggered) {
            throw new ScopeTimeoutException("ThreadScope deadline exceeded");
        }
        return new Outcome(taskList.size(), succeeded, cancelled, Collections.<Throwable>emptyList());
    }

    private Task<?> takeCompletedTask(BlockingQueue<Task<?>> completions) {
        try {
            Duration remaining = remainingDeadline();
            Task<?> completed = completions.poll(remaining.toNanos(), TimeUnit.NANOSECONDS);
            if (completed == null) {
                triggerDeadline();
                throw new ScopeTimeoutException("ThreadScope deadline exceeded");
            }
            return completed;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancelledException("Interrupted while waiting for scope tasks", interrupted);
        }
    }

    public Outcome await(Task<?> first, Task<?>... rest) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(rest, "rest");
        List<Task<?>> taskList = new ArrayList<Task<?>>(rest.length + 1);
        taskList.add(first);
        taskList.addAll(Arrays.<Task<?>>asList(rest));
        return await(taskList);
    }

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

    @SafeVarargs
    public final <T> List<T> awaitAll(Task<T> first, Task<T>... rest) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(rest, "rest");
        List<Task<T>> taskList = new ArrayList<Task<T>>(rest.length + 1);
        taskList.add(first);
        taskList.addAll(Arrays.<Task<T>>asList(rest));
        return awaitAll(taskList);
    }

    public <T> ScheduledTask schedule(Duration delay, final Callable<T> callable) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(callable, "callable");
        lockConfiguration();
        synchronized (lifecycleLock) {
            ensureOpen();
            compactFinishedScheduledTasks();
            final ExecutionContextCarrier executionContext = ExecutionContextCarrier.capture();
            ScheduledTask task = scheduleDispatched(delay, new Runnable() {
                @Override
                public void run() {
                    try {
                        executionContext.wrapCallable(callable, token).call();
                    } catch (RuntimeException runtimeException) {
                        throw runtimeException;
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }
            });
            scheduledTasks.add(task);
            return task;
        }
    }

    public ScheduledTask schedule(Duration delay, final Runnable runnable) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(runnable, "runnable");
        lockConfiguration();
        synchronized (lifecycleLock) {
            ensureOpen();
            compactFinishedScheduledTasks();
            final ExecutionContextCarrier executionContext = ExecutionContextCarrier.capture();
            ScheduledTask task = scheduleDispatched(delay, executionContext.wrapRunnable(runnable, token));
            scheduledTasks.add(task);
            return task;
        }
    }

    public ScheduledTask scheduleAtFixedRate(Duration initial, Duration period, final Runnable runnable) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(runnable, "runnable");
        lockConfiguration();
        synchronized (lifecycleLock) {
            ensureOpen();
            compactFinishedScheduledTasks();
            final ExecutionContextCarrier executionContext = ExecutionContextCarrier.capture();
            final DispatchingScheduledTask task = new DispatchingScheduledTask(
                scheduler.executor(), executionContext.wrapRunnable(runnable, token)
            );
            task.bind(delayScheduler.scheduleAtFixedRate(initial, period, task));
            scheduledTasks.add(task);
            return task;
        }
    }

    public ScheduledTask scheduleWithFixedDelay(Duration initial, Duration delay, final Runnable runnable) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(runnable, "runnable");
        lockConfiguration();
        synchronized (lifecycleLock) {
            ensureOpen();
            compactFinishedScheduledTasks();
            final ExecutionContextCarrier executionContext = ExecutionContextCarrier.capture();
            final DispatchingScheduledTask task = new DispatchingScheduledTask(
                scheduler.executor(), executionContext.wrapRunnable(runnable, token)
            );
            task.bind(delayScheduler.scheduleWithFixedDelay(initial, delay, task));
            scheduledTasks.add(task);
            return task;
        }
    }

    @Override
    public void close() {
        List<Task<?>> closingTasks;
        List<ScheduledTask> closingScheduledTasks;
        List<Runnable> closingDeferred;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closingTasks = new ArrayList<Task<?>>(tasks);
            closingScheduledTasks = new ArrayList<ScheduledTask>(scheduledTasks);
            closingDeferred = new ArrayList<Runnable>(deferred);
        }

        Throwable primary = null;

        token.cancel();

        for (ScheduledTask scheduledTask : closingScheduledTasks) {
            try {
                scheduledTask.cancel();
            } catch (Throwable t) {
                primary = combine(primary, t);
            }
        }

        for (Task<?> task : closingTasks) {
            try {
                if (!task.isDone()) {
                    task.cancel();
                }
            } catch (Throwable t) {
                primary = combine(primary, t);
            }
        }

        for (ScheduledTask scheduledTask : closingScheduledTasks) {
            if (scheduledTask instanceof DispatchingScheduledTask) {
                ((DispatchingScheduledTask) scheduledTask).awaitDispatchedWork();
            }
        }

        for (Task<?> task : closingTasks) {
            task.awaitExecutionFinished();
        }

        for (Runnable cleanup : closingDeferred) {
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
    private <T> Task<T> submit(
        String name,
        final Callable<T> callable,
        TaskPriority taskPriority,
        RetryPolicy retryPolicy,
        Duration timeout,
        long id
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(callable, "callable");
        Objects.requireNonNull(taskPriority, "taskPriority");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        validateTaskTimeout(timeout);
        lockConfiguration();
        ensureOpen();
        final Semaphore semaphore = concurrencySemaphore;
        final boolean permitAcquired = acquireSubmissionPermit(semaphore);
        final RetryPolicy taskRetryPolicy = retryPolicy;
        final Duration taskTimeout = timeout;
        final Task<T> task;
        final TaskInfo info;
        final TaskExecution execution;
        final TaskHookState hookState;

        synchronized (lifecycleLock) {
            if (closed.get()) {
                if (permitAcquired && semaphore != null) {
                    semaphore.release();
                }
                ensureOpen();
            }
            final CompletableFuture<T> future = new CompletableFuture<T>();
            task = new Task<T>(id, name, future);
            info = new TaskInfo(scopeId, id, name, Instant.now(), scheduler.name());
            hookState = new TaskHookState(info, System.nanoTime());
            final ExecutionContextCarrier executionContext = ExecutionContextCarrier.capture();
            execution = new TaskExecution(task, executionContext.wrapRunnable(new Runnable() {
                @Override
                public void run() {
                    runTask(task, callable, taskRetryPolicy, hookState);
                }
            }));
            task.attachExecution(execution);
            tasks.add(task);
            task.whenExecutionFinished(new Runnable() {
                @Override
                public void run() {
                    hookState.finishAfterExecution(task);
                    tasks.remove(task);
                    if (permitAcquired && semaphore != null) {
                        semaphore.release();
                    }
                }
            });
            final ScheduledTask timeoutTask = scheduleTaskTimeout(task, info, taskTimeout, hookState);
            if (timeoutTask != null) {
                future.whenComplete(new java.util.function.BiConsumer<T, Throwable>() {
                    @Override
                    public void accept(T value, Throwable throwable) {
                        timeoutTask.cancel();
                    }
                });
            }

        }

        try {
            scheduler.executor().execute(Scheduler.prioritized(execution, taskPriority, id));
        } catch (RejectedExecutionException rejectedExecutionException) {
            if (task.completeFailure(rejectedExecutionException, true)) {
                hookState.finishUnstarted(task);
            }
        }
        return task;
    }

    private static Callable<Void> asCallable(final Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        return new Callable<Void>() {
            @Override
            public Void call() {
                runnable.run();
                return null;
            }
        };
    }

    private <T> void runTask(
        Task<T> task,
        Callable<T> callable,
        RetryPolicy retryPolicy,
        TaskHookState hookState
    ) {
        try {
            if (task.state() != Task.State.RUNNING || token.isCancelled()) {
                task.completeCancelled(new CancelledException("Task cancelled before start"));
                return;
            }
            if (!hookState.start(task)) {
                return;
            }
            if (task.state() != Task.State.RUNNING) {
                return;
            }
            token.throwIfCancelled();

            T value = RetryExecutor.execute(callable, retryPolicy, token);
            task.completeSuccess(value);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            task.completeCancelled(new CancelledException("Task interrupted", interruptedException));
        } catch (CancelledException cancelledException) {
            task.completeCancelled(cancelledException);
        } catch (Throwable throwable) {
            task.completeFailure(throwable, false);
        } finally {
            hookState.finishStarted(task);
        }
    }

    private ScheduledTask scheduleTaskTimeout(
        final Task<?> task,
        final TaskInfo info,
        final Duration timeout,
        final TaskHookState hookState
    ) {
        if (timeout == null) {
            return null;
        }
        return controlDelayScheduler.schedule(timeout, new Runnable() {
            @Override
            public void run() {
                TaskTimeoutException timeoutException = taskTimeoutException(info, timeout);
                if (task.completeFailure(timeoutException, true)) {
                    hookState.finishTimeout(task, timeoutException, timeout.toNanos());
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

    private void cancelOthers(List<Task<?>> taskList, Task<?> failedTask) {
        for (Task<?> task : taskList) {
            if (task != failedTask && !task.isDone()) {
                task.cancel();
            }
        }
    }

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

    private void triggerDeadline() {
        if (!deadlineTriggered) {
            deadlineTriggered = true;
            token.cancel();
        }
    }

    private void rescheduleDeadlineMonitor() {
        this.deadlineAtNanos = System.nanoTime() + deadline.toNanos();
        if (deadlineTriggerTask != null) {
            deadlineTriggerTask.cancel();
        }
        deadlineTriggerTask = controlDelayScheduler.schedule(deadline, new Runnable() {
            @Override
            public void run() {
                triggerDeadline();
            }
        });
    }

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

    private void compactFinishedScheduledTasks() {
        for (ScheduledTask scheduledTask : scheduledTasks) {
            if (scheduledTask.isDone()) {
                scheduledTasks.remove(scheduledTask);
            }
        }
    }

    private Throwable combine(Throwable primary, Throwable next) {
        if (primary == null) {
            return next;
        }
        primary.addSuppressed(next);
        return primary;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ThreadScope already closed");
        }
    }

    int trackedTaskCount() {
        return tasks.size();
    }

    private void ensureConfigurable() {
        ensureOpen();
        if (configLocked.get()) {
            throw new IllegalStateException("ThreadScope configuration is locked after first task submission");
        }
    }

    private void lockConfiguration() {
        configLocked.set(true);
    }

    <T> T awaitJoinFuture(CompletableFuture<T> future) {
        Objects.requireNonNull(future, "future");

        while (true) {
            token.throwIfCancelled();
            try {
                Duration remaining = remainingDeadline();
                long waitNanos = Math.min(remaining.toNanos(), TimeUnit.MILLISECONDS.toNanos(100));
                return future.get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new CancelledException("Interrupted while waiting for joined tasks", interruptedException);
            } catch (TimeoutException timeoutException) {
                continue;
            } catch (ExecutionException executionException) {
                rethrowJoinFailure(executionException.getCause());
                return null;
            }
        }
    }

    private long elapsedNanos(long startedAtNanos) {
        return Math.max(0L, System.nanoTime() - startedAtNanos);
    }

    private void rethrowJoinFailure(Throwable cause) {
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        throw new TaskExecutionException("Joined task execution failed", cause);
    }

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

    private ScheduledTask scheduleDispatched(Duration delay, Runnable runnable) {
        final DispatchingScheduledTask task = new DispatchingScheduledTask(scheduler.executor(), runnable);
        task.bind(delayScheduler.schedule(delay, task));
        return task;
    }

    private void dispatchHookFailure(final TaskInfo info, final Throwable throwable, final long durationNanos) {
        try {
            scheduler.executor().execute(new Runnable() {
                @Override
                public void run() {
                    safeHookFailure(info, throwable, durationNanos);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private final class TaskHookState {
        private final TaskInfo info;
        private final long createdAtNanos;
        private boolean started;
        private boolean terminal;
        private long startedAtNanos;

        private TaskHookState(TaskInfo info, long createdAtNanos) {
            this.info = info;
            this.createdAtNanos = createdAtNanos;
        }

        private boolean start(Task<?> task) {
            synchronized (this) {
                if (terminal || task.state() != Task.State.RUNNING) {
                    return false;
                }
                started = true;
                startedAtNanos = System.nanoTime();
            }
            safeHookStart(info);
            return true;
        }

        private void finishStarted(Task<?> task) {
            long duration;
            synchronized (this) {
                if (!started || terminal) {
                    return;
                }
                terminal = true;
                duration = elapsedNanos(startedAtNanos);
            }
            emitTerminal(task, duration);
        }

        private void finishTimeout(Task<?> task, Throwable failure, long durationNanos) {
            synchronized (this) {
                if (started || terminal) {
                    return;
                }
                terminal = true;
            }
            dispatchHookFailure(info, failure, durationNanos);
        }

        private void finishUnstarted(Task<?> task) {
            synchronized (this) {
                if (started || terminal) {
                    return;
                }
                terminal = true;
            }
            emitTerminal(task, elapsedNanos(createdAtNanos));
        }

        private void finishAfterExecution(Task<?> task) {
            if (task.state() == Task.State.CANCELLED) {
                finishUnstarted(task);
            }
        }

        private void emitTerminal(Task<?> task, long durationNanos) {
            Task.State terminalState = task.state();
            if (terminalState == Task.State.SUCCESS) {
                safeHookSuccess(info, durationNanos);
            } else if (terminalState == Task.State.FAILED) {
                safeHookFailure(info, task.terminalFailure(), durationNanos);
            } else if (terminalState == Task.State.CANCELLED) {
                safeHookCancel(info, durationNanos);
            }
        }
    }

    private static final class TaskExecution extends FutureTask<Void> {
        private final Task<?> task;

        private TaskExecution(Task<?> task, Runnable runnable) {
            super(runnable, null);
            this.task = task;
        }

        @Override
        public void run() {
            Thread runner = Thread.currentThread();
            if (!task.beginExecution(runner)) {
                task.markExecutionFinished(runner);
                return;
            }
            try {
                super.run();
            } finally {
                task.markExecutionFinished(runner);
            }
        }
    }

    private static final class DispatchingScheduledTask implements ScheduledTask, Runnable {
        private final ExecutorService executor;
        private final Runnable command;
        private final AtomicBoolean cancelled;
        private final Queue<FutureTask<Void>> dispatched;
        private final Object dispatchMonitor;
        private volatile ScheduledTask timer;

        private DispatchingScheduledTask(ExecutorService executor, Runnable command) {
            this.executor = executor;
            this.command = command;
            this.cancelled = new AtomicBoolean();
            this.dispatched = new ConcurrentLinkedQueue<FutureTask<Void>>();
            this.dispatchMonitor = new Object();
        }

        private void bind(ScheduledTask timer) {
            this.timer = timer;
            if (cancelled.get()) {
                timer.cancel();
            }
        }

        @Override
        public void run() {
            FutureTask<Void> work;
            synchronized (dispatchMonitor) {
                if (cancelled.get()) {
                    return;
                }
                work = new DispatchedWork(command);
                dispatched.add(work);
            }
            if (cancelled.get()) {
                work.cancel(true);
                return;
            }
            try {
                executor.execute(work);
            } catch (RejectedExecutionException rejected) {
                work.cancel(false);
                throw rejected;
            }
        }

        @Override
        public boolean cancel() {
            boolean changed;
            synchronized (dispatchMonitor) {
                changed = cancelled.compareAndSet(false, true);
            }
            ScheduledTask currentTimer = timer;
            if (currentTimer != null) {
                changed |= currentTimer.cancel();
            }
            for (FutureTask<Void> work : dispatched) {
                changed |= work.cancel(true);
            }
            return changed;
        }

        private void awaitDispatchedWork() {
            boolean interrupted = false;
            synchronized (dispatchMonitor) {
                while (!dispatched.isEmpty()) {
                    try {
                        dispatchMonitor.wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            ScheduledTask currentTimer = timer;
            return cancelled.get() || (currentTimer != null && currentTimer.isDone() && dispatched.isEmpty());
        }

        private final class DispatchedWork extends FutureTask<Void> {
            private final AtomicBoolean started;

            private DispatchedWork(Runnable runnable) {
                super(runnable, null);
                this.started = new AtomicBoolean();
            }

            @Override
            public void run() {
                started.set(true);
                try {
                    super.run();
                } finally {
                    removeDispatchedWork(this);
                }
            }

            @Override
            protected void done() {
                if (!started.get()) {
                    removeDispatchedWork(this);
                }
            }
        }

        private void removeDispatchedWork(FutureTask<Void> work) {
            synchronized (dispatchMonitor) {
                dispatched.remove(work);
                dispatchMonitor.notifyAll();
            }
        }
    }
}
