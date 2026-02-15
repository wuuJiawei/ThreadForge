package io.threadforge;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * 任务生命周期状态。
     */
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
    private final AtomicReference<State> state;
    private final AtomicReference<Thread> runnerThread;

    /**
     * 包级构造函数，仅供 {@link ThreadScope} 创建任务句柄。
     */
    Task(long id, String name, CompletableFuture<T> future) {
        this.id = id;
        this.name = name;
        this.future = future;
        this.state = new AtomicReference<State>(State.PENDING);
        this.runnerThread = new AtomicReference<Thread>();
    }

    /**
     * 任务 ID（在同一个 scope 内单调递增）。
     */
    public long id() {
        return id;
    }

    /**
     * 任务名称。
     */
    public String name() {
        return name;
    }

    /**
     * 获取当前任务状态快照。
     */
    public State state() {
        return state.get();
    }

    /**
     * 任务是否已经结束（成功/失败/取消任一状态）。
     */
    public boolean isDone() {
        return future.isDone();
    }

    /**
     * 任务是否处于取消状态。
     */
    public boolean isCancelled() {
        return state.get() == State.CANCELLED || future.isCancelled();
    }

    /**
     * 任务是否处于失败状态。
     */
    public boolean isFailed() {
        return state.get() == State.FAILED;
    }

    /**
     * 取消任务，并尝试中断执行线程。
     *
     * <p>返回值与 {@link CompletableFuture#cancel(boolean)} 语义一致。
     */
    public boolean cancel() {
        state.set(State.CANCELLED);
        Thread runner = runnerThread.get();
        if (runner != null) {
            runner.interrupt();
        }
        return future.cancel(true);
    }

    /**
     * 等待任务完成并返回结果。
     *
     * <p>异常语义：
     * 若被取消抛 {@link CancelledException}；
     * 若任务抛运行时异常/错误则原样传播；
     * 若任务抛 checked exception 则包装为 {@link TaskExecutionException}。
     */
    public T await() {
        try {
            T value = future.get();
            state.compareAndSet(State.RUNNING, State.SUCCESS);
            state.compareAndSet(State.PENDING, State.SUCCESS);
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException("Task interrupted", e);
        } catch (CancellationException e) {
            state.set(State.CANCELLED);
            throw new CancelledException("Task cancelled", e);
        } catch (ExecutionException e) {
            state.set(State.FAILED);
            rethrow(e.getCause());
            return null;
        }
    }

    /**
     * 在指定超时时间内等待任务完成（包级方法，供 scope 内部使用）。
     *
     * <p>超时会抛出 {@link ScopeTimeoutException}。
     */
    T await(Duration timeout) {
        try {
            T value = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            state.compareAndSet(State.RUNNING, State.SUCCESS);
            state.compareAndSet(State.PENDING, State.SUCCESS);
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException("Task interrupted", e);
        } catch (CancellationException e) {
            state.set(State.CANCELLED);
            throw new CancelledException("Task cancelled", e);
        } catch (ExecutionException e) {
            state.set(State.FAILED);
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

    /**
     * 任务成功后做同步映射。
     *
     * <p>示例：
     * <pre>{@code
     * Integer result = task.thenApply(v -> v + 1).join();
     * }</pre>
     */
    public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> function) {
        return future.thenApply(function);
    }

    /**
     * 任务成功后做异步映射。
     */
    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends java.util.concurrent.CompletionStage<U>> function) {
        return future.thenCompose(function);
    }

    /**
     * 任务异常完成时提供兜底值映射。
     */
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> function) {
        return future.exceptionally(function);
    }

    /**
     * 标记任务进入运行状态，并记录执行线程。
     */
    boolean markRunning(Thread runner) {
        boolean marked = state.compareAndSet(State.PENDING, State.RUNNING);
        if (marked) {
            runnerThread.set(runner);
        }
        return marked;
    }

    /**
     * 标记任务成功完成。
     */
    void markSuccess() {
        runnerThread.set(null);
        state.set(State.SUCCESS);
    }

    /**
     * 标记任务失败完成。
     */
    void markFailed() {
        runnerThread.set(null);
        state.set(State.FAILED);
    }

    /**
     * 标记任务取消完成。
     */
    void markCancelled() {
        runnerThread.set(null);
        state.set(State.CANCELLED);
    }

    /**
     * 中断当前运行线程（若存在）。
     */
    void interruptRunner() {
        Thread runner = runnerThread.get();
        if (runner != null) {
            runner.interrupt();
        }
    }

    /**
     * 统一异常转换并重新抛出。
     */
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
