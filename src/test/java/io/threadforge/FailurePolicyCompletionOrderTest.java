package io.threadforge;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailurePolicyCompletionOrderTest {

    @Test
    void failFastObservesFailureBeforeEarlierSlowTask() throws Exception {
        assertFailFastCompletionOrder(false);
    }

    @Test
    void failFastIsIndependentOfCollectionOrder() throws Exception {
        assertFailFastCompletionOrder(true);
    }

    @Test
    void collectAllWaitsAndAggregatesFailures() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.COLLECT_ALL)) {
            Task<Integer> slow = scope.submit(() -> {
                release.await();
                return 1;
            });
            Task<Integer> failed = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalStateException("boom");
                }
            });
            release.countDown();
            AggregateException aggregate = assertThrows(AggregateException.class,
                () -> scope.await(slow, failed));
            assertEquals(1, aggregate.failures().size());
            assertEquals(Task.State.SUCCESS, slow.state());
        } finally {
            release.countDown();
        }
    }

    @Test
    void supervisorCollectsRealStatesWithoutCancellingSiblings() throws Exception {
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.SUPERVISOR)) {
            Task<Integer> success = scope.submit(() -> 1);
            Task<Integer> failed = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalArgumentException("bad");
                }
            });
            Outcome outcome = scope.await(success, failed);
            assertEquals(2, outcome.total());
            assertEquals(1, outcome.succeeded());
            assertEquals(1, outcome.failed());
            assertEquals(0, outcome.cancelled());
        }
    }

    @Test
    void cancelOthersCancelsStillRunningSiblingAndReturnsFailure() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowInterrupted = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.CANCEL_OTHERS)) {
            Task<Integer> slow = scope.submit(blockingTask(slowStarted, slowInterrupted));
            assertTrue(slowStarted.await(1L, TimeUnit.SECONDS));
            Task<Integer> failed = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalStateException("boom");
                }
            });

            Outcome outcome = scope.await(failed, slow);
            assertEquals(1, outcome.failed());
            assertEquals(1, outcome.cancelled());
            assertTrue(slowInterrupted.await(1L, TimeUnit.SECONDS));
        }
    }

    @Test
    void ignoreAllReturnsNoFailuresWithoutCancellingTasks() {
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.IGNORE_ALL)) {
            Task<Integer> success = scope.submit(() -> 1);
            Task<Integer> failed = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalStateException("ignored");
                }
            });
            Outcome outcome = scope.await(success, failed);
            assertEquals(1, outcome.succeeded());
            assertFalse(outcome.hasFailures());
            assertEquals(Task.State.FAILED, failed.state());
        }
    }

    private static void assertFailFastCompletionOrder(boolean failedFirstInCollection) throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowInterrupted = new CountDownLatch(1);
        CountDownLatch failureThrown = new CountDownLatch(1);
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.FAIL_FAST)) {
            Task<Integer> slow = scope.submit(blockingTask(slowStarted, slowInterrupted));
            assertTrue(slowStarted.await(1L, TimeUnit.SECONDS));
            Task<Integer> failed = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    failureThrown.countDown();
                    throw new IllegalStateException("fast-failure");
                }
            });
            assertTrue(failureThrown.await(1L, TimeUnit.SECONDS));

            Future<Outcome> awaiting = waiter.submit(() -> failedFirstInCollection
                ? scope.await(Arrays.<Task<?>>asList(failed, slow))
                : scope.await(Arrays.<Task<?>>asList(slow, failed)));
            ExecutionException wrapper = assertThrows(ExecutionException.class,
                () -> awaiting.get(500L, TimeUnit.MILLISECONDS));
            assertTrue(wrapper.getCause() instanceof IllegalStateException);
            assertEquals("fast-failure", wrapper.getCause().getMessage());
            assertTrue(slowInterrupted.await(1L, TimeUnit.SECONDS));
            assertEquals(Task.State.CANCELLED, slow.state());
        } finally {
            waiter.shutdownNow();
        }
    }

    private static Callable<Integer> blockingTask(CountDownLatch started, CountDownLatch interrupted) {
        return new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    throw expected;
                }
                return 1;
            }
        };
    }
}
