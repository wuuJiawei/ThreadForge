package io.threadforge;

import io.threadforge.internal.DefaultCancellationToken;

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
 * Structured concurrency scope.
 *
 * <p>Thread-safe for submitting and waiting from multiple threads, but configuration methods
 * (`with*`) must be called before first submission/schedule.</p>
 */
public final class ThreadScope implements AutoCloseable {

    private static final AtomicLong SCOPE_IDS = new AtomicLong(1L);
    private static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(30);

    private final long scopeId;
    private final AtomicLong taskIdGen;
    private final AtomicBoolean closed;
    private final AtomicBoolean configLocked;
    private final Queue<Task<?>> tasks;
    private final Queue<ScheduledTask> scheduledTasks;
    private final Deque<Runnable> deferred;
    private final DefaultCancellationToken token;
    private final DelayScheduler delayScheduler;

    private volatile Scheduler scheduler;
    private volatile FailurePolicy failurePolicy;
    private volatile Duration deadline;
    private volatile ThreadHook hook;
    private volatile Semaphore concurrencySemaphore;
    private volatile ScheduledTask deadlineTriggerTask;
    private volatile long deadlineAtNanos;
    private volatile boolean deadlineTriggered;

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
        this.deadline = DEFAULT_DEADLINE;
        this.hook = new ThreadHook() {
        };
        this.delayScheduler = DelayScheduler.shared();
        this.token = new DefaultCancellationToken(new Runnable() {
            @Override
            public void run() {
                cancelOutstandingTasks();
            }
        });
        rescheduleDeadlineMonitor();
    }

    public static ThreadScope open() {
        return new ThreadScope();
    }

    public ThreadScope withScheduler(Scheduler scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        ensureConfigurable();
        this.scheduler = scheduler;
        return this;
    }

    public ThreadScope withFailurePolicy(FailurePolicy failurePolicy) {
        Objects.requireNonNull(failurePolicy, "failurePolicy");
        ensureConfigurable();
        this.failurePolicy = failurePolicy;
        return this;
    }

    public ThreadScope withConcurrencyLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        ensureConfigurable();
        this.concurrencySemaphore = new Semaphore(limit);
        return this;
    }

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

    public ThreadScope withHook(ThreadHook hook) {
        Objects.requireNonNull(hook, "hook");
        ensureConfigurable();
        this.hook = hook;
        return this;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }

    public Duration deadline() {
        return deadline;
    }

    public CancellationToken token() {
        return token;
    }

    public void defer(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        ensureOpen();
        deferred.addFirst(cleanup);
    }

    public <T> Task<T> submit(Callable<T> callable) {
        long id = taskIdGen.getAndIncrement();
        return submit("task-" + id, callable, id);
    }

    public <T> Task<T> submit(String name, Callable<T> callable) {
        long id = taskIdGen.getAndIncrement();
        return submit(name, callable, id);
    }

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
        ensureOpen();
        compactFinishedScheduledTasks();

        ScheduledTask task = delayScheduler.schedule(delay, new Callable<T>() {
            @Override
            public T call() throws Exception {
                token.throwIfCancelled();
                return callable.call();
            }
        });
        scheduledTasks.add(task);
        return task;
    }

    public ScheduledTask schedule(Duration delay, final Runnable runnable) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(runnable, "runnable");
        lockConfiguration();
        ensureOpen();
        compactFinishedScheduledTasks();

        ScheduledTask task = delayScheduler.schedule(delay, new Runnable() {
            @Override
            public void run() {
                token.throwIfCancelled();
                runnable.run();
            }
        });
        scheduledTasks.add(task);
        return task;
    }

    public ScheduledTask scheduleAtFixedRate(Duration initial, Duration period, final Runnable runnable) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(runnable, "runnable");
        lockConfiguration();
        ensureOpen();
        compactFinishedScheduledTasks();

        ScheduledTask task = delayScheduler.scheduleAtFixedRate(initial, period, new Runnable() {
            @Override
            public void run() {
                token.throwIfCancelled();
                runnable.run();
            }
        });
        scheduledTasks.add(task);
        return task;
    }

    public ScheduledTask scheduleWithFixedDelay(Duration initial, Duration delay, final Runnable runnable) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(runnable, "runnable");
        lockConfiguration();
        ensureOpen();
        compactFinishedScheduledTasks();

        ScheduledTask task = delayScheduler.scheduleWithFixedDelay(initial, delay, new Runnable() {
            @Override
            public void run() {
                token.throwIfCancelled();
                runnable.run();
            }
        });
        scheduledTasks.add(task);
        return task;
    }

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

    private <T> Task<T> submit(String name, final Callable<T> callable, long id) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(callable, "callable");
        lockConfiguration();
        ensureOpen();
        final Semaphore semaphore = concurrencySemaphore;
        final boolean permitAcquired = acquireSubmissionPermit(semaphore);

        final CompletableFuture<T> future = new CompletableFuture<T>();
        final Task<T> task = new Task<T>(id, name, future);
        final TaskInfo info = new TaskInfo(scopeId, id, name, Instant.now(), scheduler.name());
        tasks.add(task);
        future.whenComplete(new BiConsumer<T, Throwable>() {
            @Override
            public void accept(T value, Throwable throwable) {
                tasks.remove(task);
            }
        });

        try {
            scheduler.executor().execute(new Runnable() {
                @Override
                public void run() {
                    runTask(task, info, callable, permitAcquired ? semaphore : null);
                }
            });
        } catch (RejectedExecutionException rejectedExecutionException) {
            if (permitAcquired && semaphore != null) {
                semaphore.release();
            }
            task.markFailed();
            future.completeExceptionally(rejectedExecutionException);
            safeHookFailure(info, rejectedExecutionException, Duration.ZERO);
        }

        return task;
    }

    private <T> void runTask(Task<T> task, TaskInfo info, Callable<T> callable, Semaphore acquiredSemaphore) {
        long started = System.nanoTime();

        try {
            if (task.isCancelled() || token.isCancelled()) {
                task.markCancelled();
                task.toCompletableFuture().completeExceptionally(new CancelledException("Task cancelled before start"));
                safeHookCancel(info, Duration.ofNanos(Math.max(0L, System.nanoTime() - started)));
                return;
            }

            if (!task.markRunning(Thread.currentThread())) {
                return;
            }

            safeHookStart(info);
            token.throwIfCancelled();

            T value = callable.call();
            task.markSuccess();
            task.toCompletableFuture().complete(value);
            safeHookSuccess(info, Duration.ofNanos(Math.max(0L, System.nanoTime() - started)));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            task.markCancelled();
            CancelledException cancelledException = new CancelledException("Task interrupted", interruptedException);
            task.toCompletableFuture().completeExceptionally(cancelledException);
            safeHookCancel(info, Duration.ofNanos(Math.max(0L, System.nanoTime() - started)));
        } catch (CancelledException cancelledException) {
            task.markCancelled();
            task.toCompletableFuture().completeExceptionally(cancelledException);
            safeHookCancel(info, Duration.ofNanos(Math.max(0L, System.nanoTime() - started)));
        } catch (Throwable throwable) {
            task.markFailed();
            task.toCompletableFuture().completeExceptionally(throwable);
            safeHookFailure(info, throwable, Duration.ofNanos(Math.max(0L, System.nanoTime() - started)));
        } finally {
            if (acquiredSemaphore != null) {
                acquiredSemaphore.release();
            }
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
        deadlineTriggerTask = delayScheduler.schedule(deadline, new Runnable() {
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

    private void ensureConfigurable() {
        ensureOpen();
        if (configLocked.get()) {
            throw new IllegalStateException("ThreadScope configuration is locked after first task submission");
        }
    }

    private void lockConfiguration() {
        configLocked.set(true);
    }

    private void safeHookStart(TaskInfo info) {
        try {
            hook.onStart(info);
        } catch (Throwable ignored) {
        }
    }

    private void safeHookSuccess(TaskInfo info, Duration duration) {
        try {
            hook.onSuccess(info, duration);
        } catch (Throwable ignored) {
        }
    }

    private void safeHookFailure(TaskInfo info, Throwable throwable, Duration duration) {
        try {
            hook.onFailure(info, throwable, duration);
        } catch (Throwable ignored) {
        }
    }

    private void safeHookCancel(TaskInfo info, Duration duration) {
        try {
            hook.onCancel(info, duration);
        } catch (Throwable ignored) {
        }
    }
}
