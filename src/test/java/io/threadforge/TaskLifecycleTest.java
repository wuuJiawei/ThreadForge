package io.threadforge;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskLifecycleTest {

    @Test
    void runningTaskTimeoutInterruptsRunner() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Void> task = scope.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                        throw expected;
                    }
                    return null;
                }
            }, Duration.ofMillis(40));

            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertThrows(TaskTimeoutException.class, task::await);
            assertTrue(interrupted.await(1L, TimeUnit.SECONDS));
            assertEquals(Task.State.FAILED, task.state());
        }
    }

    @Test
    void queuedTaskTimeoutPreventsCallableExecution() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicBoolean called = new AtomicBoolean();
        try (ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.fixed(1))) {
            Task<Void> blocker = scope.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    blockerStarted.countDown();
                    releaseBlocker.await();
                    return null;
                }
            });
            assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

            Task<Void> queued = scope.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    called.set(true);
                    return null;
                }
            }, Duration.ofMillis(40));

            assertThrows(TaskTimeoutException.class, queued::await);
            releaseBlocker.countDown();
            blocker.await();
            queued.awaitExecutionFinished(Duration.ofSeconds(1));
            assertFalse(called.get());
            assertEquals(Task.State.FAILED, queued.state());
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test
    void successfulCancelInterruptsRunner() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Void> task = scope.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                        throw expected;
                    }
                    return null;
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertTrue(task.cancel());
            assertTrue(interrupted.await(1L, TimeUnit.SECONDS));
            assertEquals(Task.State.CANCELLED, task.state());
        }
    }

    @Test
    void cancelCannotOverwriteSuccessfulOrFailedTerminalState() {
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.SUPERVISOR)) {
            Task<Integer> success = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    return 1;
                }
            });
            Task<Integer> failure = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalStateException("boom");
                }
            });

            scope.await(Arrays.<Task<?>>asList(success, failure));
            assertFalse(success.cancel());
            assertEquals(Task.State.SUCCESS, success.state());
            assertFalse(failure.cancel());
            assertEquals(Task.State.FAILED, failure.state());
        }
    }

    @Test
    void cancelledStateCannotBeOverwritten() {
        Task<Integer> task = new Task<Integer>(1L, "cancelled", new CompletableFuture<Integer>());
        assertTrue(task.cancel());
        task.markSuccess();
        task.markFailed();
        assertEquals(Task.State.CANCELLED, task.state());
    }

    @Test
    void callableCancelledExceptionProducesStableCancelledState() {
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Void> task = scope.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    throw new CancelledException("self-cancelled");
                }
            });
            assertThrows(CancelledException.class, task::await);
            assertEquals(Task.State.CANCELLED, task.state());
            task.markSuccess();
            task.markFailed();
            assertEquals(Task.State.CANCELLED, task.state());
        }
    }

    @RepeatedTest(50)
    void timeoutCompletionAndCancelRaceHasExactlyOneTerminalWinner() throws Exception {
        Task<Integer> task = new Task<Integer>(1L, "race", new CompletableFuture<Integer>());
        Thread runner = new Thread();
        assertTrue(task.beginExecution(runner));
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CyclicBarrier barrier = new CyclicBarrier(3);
        try {
            Future<Boolean> success = executor.submit(() -> {
                barrier.await();
                return task.completeSuccess(1);
            });
            Future<Boolean> failure = executor.submit(() -> {
                barrier.await();
                return task.completeFailure(new TaskTimeoutException("timeout"), true);
            });
            Future<Boolean> cancel = executor.submit(() -> {
                barrier.await();
                return task.cancel();
            });

            int winners = (success.get(1L, TimeUnit.SECONDS) ? 1 : 0)
                + (failure.get(1L, TimeUnit.SECONDS) ? 1 : 0)
                + (cancel.get(1L, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
            assertTrue(task.isDone());
            assertTrue(task.state() == Task.State.SUCCESS
                || task.state() == Task.State.FAILED
                || task.state() == Task.State.CANCELLED);
        } finally {
            task.markExecutionFinished(runner);
            executor.shutdownNow();
        }
    }

    @Test
    void runnerThreadIsClearedOnlyAfterExecutionExits() {
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Integer> task = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    return 1;
                }
            });
            assertEquals(Integer.valueOf(1), task.await());
            task.awaitExecutionFinished(Duration.ofSeconds(1));
            assertTrue(task.isExecutionFinished());
            assertFalse(task.hasRunnerThread());
        }
    }

    @Test
    void scopeTracksTimedOutTaskUntilIgnoringRunnerActuallyExits() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch timeoutInterruptObserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Void> task = scope.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    started.countDown();
                    while (true) {
                        try {
                            release.await();
                            return null;
                        } catch (InterruptedException ignored) {
                            timeoutInterruptObserved.countDown();
                        }
                    }
                }
            }, Duration.ofMillis(40));

            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertThrows(TaskTimeoutException.class, task::await);
            assertTrue(timeoutInterruptObserved.await(1L, TimeUnit.SECONDS));
            assertFalse(task.isExecutionFinished());
            assertEquals(1, scope.trackedTaskCount());

            release.countDown();
            task.awaitExecutionFinished(Duration.ofSeconds(1));
            assertEquals(0, scope.trackedTaskCount());
            assertFalse(task.hasRunnerThread());
        } finally {
            release.countDown();
        }
    }
}
