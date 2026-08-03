package io.threadforge;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 作用域内单个任务的句柄。
 *
 * <p>{@code Task} 对 {@link CompletableFuture} 做了语义封装：
 * 补充了任务状态、取消语义和异常统一转换，便于在业务代码中按结构化并发方式使用。
 *
 * <p>示例：
 * <pre>{@code
 * Task<Integer> t = scope.submit("calc", () -> 21);
 * Integer v = t.await();
 * }</pre>
 */
public final class Task<T> {

    /** 任务生命周期状态。 */
    public enum State {
        /** 已创建但尚未运行。 */
        PENDING,
        /** 正在运行。 */
        RUNNING,
        /** 成功完成。 */
        SUCCESS,
        /** 失败完成。 */
        FAILED,
        /** 已取消。 */
        CANCELLED
    }

    private final long id;
    private final String name;
    private final CompletableFuture<T> future;
    private final Object lifecycleLock;
    private final CompletableFuture<Void> executionFinished;
    private State state;
    private Thread runnerThread;
    private boolean executionEntered;
    private Future<?> execution;
    private Runnable executionFinishedCallback;

    /** 包级构造函数，仅供 {@link ThreadScope} 创建任务句柄。 */
    Task(long id, String name, CompletableFuture<T> future) {
        this.id = id;
        this.name = name;
        this.future = future;
        this.lifecycleLock = new Object();
        this.executionFinished = new CompletableFuture<Void>();
        this.state = State.PENDING;
    }

    /** 任务 ID（在同一个 scope 内单调递增）。 */
    public long id() {
        return id;
    }

    /** 任务名称。 */
    public String name() {
        return name;
    }

    /** 获取当前任务状态快照。 */
    public State state() {
        synchronized (lifecycleLock) {
            return state;
        }
    }

    /** 任务是否已经逻辑结束。 */
    public boolean isDone() {
        return future.isDone();
    }

    /** 任务是否处于取消状态。 */
    public boolean isCancelled() {
        synchronized (lifecycleLock) {
            return state == State.CANCELLED || future.isCancelled();
        }
    }

    /** 任务是否处于失败状态。 */
    public boolean isFailed() {
        return state() == State.FAILED;
    }

    /**
     * 取消任务。只有成功赢得终态竞争时才会修改状态或中断执行线程。
     */
    public boolean cancel() {
        Thread runner;
        Future<?> executionToCancel;
        Runnable callback = null;
        synchronized (lifecycleLock) {
            if (isTerminal(state) || future.isDone()) {
                return false;
            }
            State previous = state;
            state = State.CANCELLED;
            if (!future.cancel(true)) {
                state = previous;
                return false;
            }
            runner = runnerThread;
            executionToCancel = execution;
            if (!executionEntered) {
                callback = markExecutionFinishedLocked();
            }
        }
        if (executionToCancel != null) {
            executionToCancel.cancel(true);
        }
        if (runner != null) {
            runner.interrupt();
        }
        runCallback(callback);
        return true;
    }

    /**
     * 等待任务完成并返回结果。
     *
     * <p>若被取消抛 {@link CancelledException}；运行时异常/错误原样传播；
     * checked exception 包装为 {@link TaskExecutionException}。
     */
    public T await() {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException("Task interrupted", e);
        } catch (CancellationException e) {
            throw new CancelledException("Task cancelled", e);
        } catch (ExecutionException e) {
            rethrow(e.getCause());
            return null;
        }
    }

    /** 在指定超时时间内等待任务完成（包级方法，供 scope 内部使用）。 */
    T await(Duration timeout) {
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException("Task interrupted", e);
        } catch (CancellationException e) {
            throw new CancelledException("Task cancelled", e);
        } catch (ExecutionException e) {
            rethrow(e.getCause());
            return null;
        } catch (TimeoutException e) {
            throw new ScopeTimeoutException("Task await timed out");
        }
    }

    /**
     * 暴露底层 {@link CompletableFuture}，用于与外部 API 互操作。
     */
    public CompletableFuture<T> toCompletableFuture() {
        return future;
    }

    /** 任务成功后做同步映射。 */
    public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> function) {
        return future.thenApply(function);
    }

    /** 任务成功后做异步映射。 */
    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends java.util.concurrent.CompletionStage<U>> function) {
        return future.thenCompose(function);
    }

    /** 任务异常完成时提供兜底值映射。 */
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> function) {
        return future.exceptionally(function);
    }

    void attachExecution(Future<?> execution) {
        synchronized (lifecycleLock) {
            this.execution = execution;
        }
    }

    void whenExecutionFinished(Runnable callback) {
        boolean runNow;
        synchronized (lifecycleLock) {
            if (executionFinished.isDone()) {
                runNow = true;
            } else {
                executionFinishedCallback = callback;
                runNow = false;
            }
        }
        if (runNow) {
            callback.run();
        }
    }

    boolean beginExecution(Thread runner) {
        synchronized (lifecycleLock) {
            executionEntered = true;
            if (state != State.PENDING) {
                return false;
            }
            state = State.RUNNING;
            runnerThread = runner;
            return true;
        }
    }

    void markExecutionFinished(Thread runner) {
        Runnable callback;
        synchronized (lifecycleLock) {
            if (runnerThread == runner) {
                runnerThread = null;
            }
            callback = markExecutionFinishedLocked();
        }
        runCallback(callback);
    }

    boolean completeSuccess(T value) {
        synchronized (lifecycleLock) {
            if (isTerminal(state)) {
                return false;
            }
            State previous = state;
            state = State.SUCCESS;
            if (!future.complete(value)) {
                state = previous;
                return false;
            }
            return true;
        }
    }

    boolean completeFailure(Throwable failure, boolean interrupt) {
        Thread runner;
        Future<?> executionToCancel;
        Runnable callback = null;
        synchronized (lifecycleLock) {
            if (isTerminal(state)) {
                return false;
            }
            State previous = state;
            state = State.FAILED;
            if (!future.completeExceptionally(failure)) {
                state = previous;
                return false;
            }
            runner = runnerThread;
            executionToCancel = execution;
            if (!executionEntered) {
                callback = markExecutionFinishedLocked();
            }
        }
        if (interrupt) {
            if (executionToCancel != null) {
                executionToCancel.cancel(true);
            }
            if (runner != null) {
                runner.interrupt();
            }
        }
        runCallback(callback);
        return true;
    }

    boolean completeCancelled(CancelledException cancellation) {
        synchronized (lifecycleLock) {
            if (isTerminal(state)) {
                return false;
            }
            State previous = state;
            state = State.CANCELLED;
            if (!future.completeExceptionally(cancellation)) {
                state = previous;
                return false;
            }
            return true;
        }
    }

    /** Compatibility hooks used by package-level coverage tests. */
    void markRunning(Thread runner) {
        beginExecution(runner);
    }

    void markSuccess() {
        synchronized (lifecycleLock) {
            if (!isTerminal(state)) {
                state = State.SUCCESS;
            }
        }
    }

    void markFailed() {
        synchronized (lifecycleLock) {
            if (!isTerminal(state)) {
                state = State.FAILED;
            }
        }
    }

    void markCancelled() {
        synchronized (lifecycleLock) {
            if (!isTerminal(state)) {
                state = State.CANCELLED;
            }
        }
    }

    void interruptRunner() {
        Thread runner;
        synchronized (lifecycleLock) {
            runner = runnerThread;
        }
        if (runner != null) {
            runner.interrupt();
        }
    }

    boolean isExecutionFinished() {
        return executionFinished.isDone();
    }

    void awaitExecutionFinished(Duration timeout) {
        try {
            executionFinished.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancelledException("Interrupted while waiting for task execution to finish", interrupted);
        } catch (ExecutionException impossible) {
            throw new IllegalStateException(impossible);
        } catch (TimeoutException timeoutException) {
            throw new ScopeTimeoutException("Task execution did not finish in time");
        }
    }

    boolean hasRunnerThread() {
        synchronized (lifecycleLock) {
            return runnerThread != null;
        }
    }

    private Runnable markExecutionFinishedLocked() {
        if (executionFinished.isDone()) {
            return null;
        }
        executionFinished.complete(null);
        Runnable callback = executionFinishedCallback;
        executionFinishedCallback = null;
        return callback;
    }

    private static boolean isTerminal(State state) {
        return state == State.SUCCESS || state == State.FAILED || state == State.CANCELLED;
    }

    private static void runCallback(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    private void rethrow(Throwable cause) {
        if (cause instanceof CancelledException) {
            throw (CancelledException) cause;
        }
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        throw new TaskExecutionException("Task execution failed", cause);
    }
}
