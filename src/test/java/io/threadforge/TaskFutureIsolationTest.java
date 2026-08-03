package io.threadforge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFutureIsolationTest {

    @Test
    void externalCompletionCannotMutateUnderlyingTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Integer> task = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    started.countDown();
                    release.await();
                    return 7;
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));

            CompletableFuture<Integer> observer = task.toCompletableFuture();
            assertFalse(observer.complete(99));
            assertFalse(observer.completeExceptionally(new IllegalStateException("fake")));
            assertFalse(task.isDone());

            release.countDown();
            assertEquals(Integer.valueOf(7), task.await());
            assertEquals(Integer.valueOf(7), observer.join());
        } finally {
            release.countDown();
        }
    }

    @Test
    void externalCancellationDoesNotInterruptOrCancelUnderlyingTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Integer> task = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        interrupted.set(true);
                        throw failure;
                    }
                    return 5;
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));

            assertFalse(task.toCompletableFuture().cancel(true));
            assertFalse(task.isCancelled());
            assertFalse(interrupted.get());

            release.countDown();
            assertEquals(Integer.valueOf(5), task.await());
        } finally {
            release.countDown();
        }
    }

    @Test
    void underlyingTerminalResultsPropagateToObserver() {
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.SUPERVISOR)) {
            Task<Integer> success = scope.submit(() -> 3);
            Task<Integer> failure = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalArgumentException("boom");
                }
            });
            CountDownLatch started = new CountDownLatch(1);
            Task<Integer> cancelled = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    started.countDown();
                    new CountDownLatch(1).await();
                    return 1;
                }
            });
            try {
                assertTrue(started.await(1L, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }

            CompletableFuture<Integer> successObserver = success.toCompletableFuture();
            CompletableFuture<Integer> failureObserver = failure.toCompletableFuture();
            CompletableFuture<Integer> cancelledObserver = cancelled.toCompletableFuture();
            assertTrue(cancelled.cancel());

            assertEquals(Integer.valueOf(3), successObserver.join());
            assertThrows(java.util.concurrent.CompletionException.class, failureObserver::join);
            assertThrows(java.util.concurrent.CancellationException.class, cancelledObserver::join);
            assertTrue(cancelledObserver.isCancelled());
        }
    }

    @Test
    void compositionMethodsRetainCompletableFutureBehavior() {
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Integer> task = scope.submit(() -> 2);
            assertEquals(Integer.valueOf(3), task.thenApply(value -> value + 1).join());
            assertEquals(Integer.valueOf(4), task.thenCompose(value ->
                CompletableFuture.completedFuture(value * 2)).join());

            Task<Integer> failure = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalStateException("boom");
                }
            });
            assertEquals(Integer.valueOf(9), failure.exceptionally(ignored -> 9).join());
        }
    }
}
