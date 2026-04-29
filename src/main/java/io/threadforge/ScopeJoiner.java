package io.threadforge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Higher-order orchestration helpers for fan-out and fan-in execution inside one {@link ThreadScope}.
 */
public final class ScopeJoiner {

    private final ThreadScope scope;

    ScopeJoiner(ThreadScope scope) {
        this.scope = scope;
    }

    public <T, R> R join(JoinStrategy<T, R> strategy, Collection<? extends Callable<T>> callables) {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(callables, "callables");

        List<Callable<T>> taskList = new ArrayList<Callable<T>>(callables);
        if (taskList.isEmpty()) {
            throw new IllegalArgumentException("callables must not be empty");
        }
        if (strategy.isQuorum() && strategy.requiredSuccesses() > taskList.size()) {
            throw new IllegalArgumentException("requiredSuccesses must be <= number of callables");
        }
        if (strategy.isHedged() && taskList.size() < 2) {
            throw new IllegalArgumentException("hedged join requires at least 2 callables");
        }

        JoinState<T, R> state = new JoinState<T, R>(scope, strategy, taskList.size());
        if (strategy.isHedged()) {
            state.launch(taskList.get(0));
            for (int i = 1; i < taskList.size(); i++) {
                state.scheduleHedged(taskList.get(i), strategy.hedgeDelay());
            }
        } else {
            for (Callable<T> callable : taskList) {
                state.launch(callable);
            }
        }

        return scope.awaitJoinFuture(state.result());
    }

    @SafeVarargs
    public final <T, R> R join(JoinStrategy<T, R> strategy, Callable<T> first, Callable<T>... rest) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(rest, "rest");
        List<Callable<T>> taskList = new ArrayList<Callable<T>>(rest.length + 1);
        taskList.add(first);
        taskList.addAll(Arrays.asList(rest));
        return join(strategy, taskList);
    }

    public <T> T firstSuccess(Collection<? extends Callable<T>> callables) {
        return join(JoinStrategy.<T>firstSuccess(), callables);
    }

    @SafeVarargs
    public final <T> T firstSuccess(Callable<T> first, Callable<T>... rest) {
        return join(JoinStrategy.<T>firstSuccess(), first, rest);
    }

    public <T> List<T> quorum(int requiredSuccesses, Collection<? extends Callable<T>> callables) {
        return join(JoinStrategy.<T>quorum(requiredSuccesses), callables);
    }

    @SafeVarargs
    public final <T> List<T> quorum(int requiredSuccesses, Callable<T> first, Callable<T>... rest) {
        return join(JoinStrategy.<T>quorum(requiredSuccesses), first, rest);
    }

    @SafeVarargs
    public final <T> T hedged(Duration delay, Callable<T> first, Callable<T>... rest) {
        return join(JoinStrategy.<T>hedged(delay), first, rest);
    }

    private static final class JoinState<T, R> {

        private final ThreadScope scope;
        private final JoinStrategy<T, R> strategy;
        private final int totalCandidates;
        private final CompletableFuture<R> result;
        private final List<Task<T>> tasks;
        private final List<ScheduledTask> launchers;
        private final List<Throwable> failures;
        private final List<T> successes;
        private final Object monitor;

        private int completed;

        private JoinState(ThreadScope scope, JoinStrategy<T, R> strategy, int totalCandidates) {
            this.scope = scope;
            this.strategy = strategy;
            this.totalCandidates = totalCandidates;
            this.result = new CompletableFuture<R>();
            this.tasks = new ArrayList<Task<T>>();
            this.launchers = new ArrayList<ScheduledTask>();
            this.failures = new ArrayList<Throwable>();
            this.successes = new ArrayList<T>();
            this.monitor = new Object();
        }

        private CompletableFuture<R> result() {
            return result;
        }

        private void scheduleHedged(final Callable<T> callable, Duration delay) {
            ScheduledTask launcher = scope.schedule(delay, new Runnable() {
                @Override
                public void run() {
                    if (result.isDone()) {
                        return;
                    }
                    scope.token().throwIfCancelled();
                    launch(callable);
                }
            });
            synchronized (monitor) {
                launchers.add(launcher);
            }
        }

        private void launch(Callable<T> callable) {
            final Task<T> task = scope.submit(callable);
            synchronized (monitor) {
                tasks.add(task);
            }
            task.toCompletableFuture().whenComplete(new java.util.function.BiConsumer<T, Throwable>() {
                @Override
                public void accept(T value, Throwable throwable) {
                    handleCompletion(task, value, throwable);
                }
            });
        }

        @SuppressWarnings("unchecked")
        private void handleCompletion(Task<T> task, T value, Throwable throwable) {
            Throwable failureToComplete = null;
            Object successToComplete = null;

            synchronized (monitor) {
                completed++;
                if (throwable == null) {
                    successes.add(value);
                    if (!result.isDone()) {
                        if (strategy.isFirstSuccess() || strategy.isHedged()) {
                            successToComplete = value;
                        } else if (strategy.isQuorum() && successes.size() >= strategy.requiredSuccesses()) {
                            successToComplete = Collections.unmodifiableList(
                                new ArrayList<T>(successes.subList(0, strategy.requiredSuccesses()))
                            );
                        }
                    }
                } else if (!(unwrap(throwable) instanceof CancelledException)) {
                    failures.add(unwrap(throwable));
                }

                if (successToComplete == null && !result.isDone() && cannotSucceed()) {
                    failureToComplete = failure();
                }
            }

            if (successToComplete != null) {
                if (result.complete((R) successToComplete)) {
                    cancelPending(task);
                }
                return;
            }

            if (failureToComplete != null && result.completeExceptionally(failureToComplete)) {
                cancelPending(null);
            }
        }

        private boolean cannotSucceed() {
            int target = strategy.isQuorum() ? strategy.requiredSuccesses() : 1;
            int remainingPossible = totalCandidates - completed;
            return successes.size() + remainingPossible < target;
        }

        private Throwable failure() {
            if (strategy.isQuorum()) {
                if (failures.isEmpty()) {
                    return new CancelledException(
                        "Quorum not reached: required " + strategy.requiredSuccesses() + " successful results"
                    );
                }
                return new AggregateException(
                    "Quorum not reached: required " + strategy.requiredSuccesses() + " successful results but got " + successes.size(),
                    failures
                );
            }

            if (failures.isEmpty()) {
                return new CancelledException("No task completed successfully");
            }
            return new AggregateException("No task completed successfully", failures);
        }

        private void cancelPending(Task<T> winner) {
            List<Task<T>> tasksSnapshot;
            List<ScheduledTask> launchersSnapshot;
            synchronized (monitor) {
                tasksSnapshot = new ArrayList<Task<T>>(tasks);
                launchersSnapshot = new ArrayList<ScheduledTask>(launchers);
            }

            for (ScheduledTask launcher : launchersSnapshot) {
                if (!launcher.isDone()) {
                    launcher.cancel();
                }
            }

            for (Task<T> task : tasksSnapshot) {
                if (task != winner && !task.isDone()) {
                    task.cancel();
                }
            }
        }

        private Throwable unwrap(Throwable throwable) {
            Throwable current = throwable;
            while (current instanceof ExecutionException && current.getCause() != null) {
                current = current.getCause();
            }
            while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }
    }
}
