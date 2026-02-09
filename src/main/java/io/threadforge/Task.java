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
 * Structured task handle wrapping a {@link CompletableFuture}.
 * State transitions are thread-safe.
 */
public final class Task<T> {

    public enum State {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    private final long id;
    private final String name;
    private final CompletableFuture<T> future;
    private final AtomicReference<State> state;
    private final AtomicReference<Thread> runnerThread;

    Task(long id, String name, CompletableFuture<T> future) {
        this.id = id;
        this.name = name;
        this.future = future;
        this.state = new AtomicReference<State>(State.PENDING);
        this.runnerThread = new AtomicReference<Thread>();
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public State state() {
        return state.get();
    }

    public boolean isDone() {
        return future.isDone();
    }

    public boolean isCancelled() {
        return state.get() == State.CANCELLED || future.isCancelled();
    }

    public boolean isFailed() {
        return state.get() == State.FAILED;
    }

    public boolean cancel() {
        state.set(State.CANCELLED);
        Thread runner = runnerThread.get();
        if (runner != null) {
            runner.interrupt();
        }
        return future.cancel(true);
    }

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

    public CompletableFuture<T> toCompletableFuture() {
        return future;
    }

    /**
     * Chains a synchronous transformation on successful completion.
     */
    public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> function) {
        return future.thenApply(function);
    }

    /**
     * Chains an asynchronous transformation on successful completion.
     */
    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends java.util.concurrent.CompletionStage<U>> function) {
        return future.thenCompose(function);
    }

    /**
     * Provides fallback value mapping when task completes exceptionally.
     */
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> function) {
        return future.exceptionally(function);
    }

    boolean markRunning(Thread runner) {
        boolean marked = state.compareAndSet(State.PENDING, State.RUNNING);
        if (marked) {
            runnerThread.set(runner);
        }
        return marked;
    }

    void markSuccess() {
        runnerThread.set(null);
        state.set(State.SUCCESS);
    }

    void markFailed() {
        runnerThread.set(null);
        state.set(State.FAILED);
    }

    void markCancelled() {
        runnerThread.set(null);
        state.set(State.CANCELLED);
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
