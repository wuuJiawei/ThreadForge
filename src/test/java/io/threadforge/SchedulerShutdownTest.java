package io.threadforge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerShutdownTest {

    @Test
    void fixedSchedulerRunsOnCallerWhenQueueIsFull() throws Exception {
        Scheduler scheduler = Scheduler.fixed(1);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) scheduler.executor();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    workerStarted.countDown();
                    try {
                        releaseWorker.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertTrue(workerStarted.await(1L, TimeUnit.SECONDS));

            int queueCapacity = executor.getQueue().remainingCapacity();
            for (int i = 0; i < queueCapacity; i++) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                    }
                });
            }

            final AtomicReference<Thread> executionThread = new AtomicReference<Thread>();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    executionThread.set(Thread.currentThread());
                }
            });

            assertSame(Thread.currentThread(), executionThread.get());
        } finally {
            releaseWorker.countDown();
            scheduler.shutdownIfOwned();
        }
    }

    @Test
    void submissionAfterOwnedSchedulerShutdownFailsTask() throws Exception {
        Scheduler scheduler = Scheduler.fixed(1);
        scheduler.shutdownIfOwned();

        try (ThreadScope scope = ThreadScope.open().withScheduler(scheduler)) {
            Task<Integer> task = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    return 1;
                }
            });

            ExecutionException failure = assertThrows(ExecutionException.class, () ->
                task.internalFuture().get(1L, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof RejectedExecutionException);
            assertEquals(Task.State.FAILED, task.state());
        }
    }

    @Test
    void closingFirstScopeMakesSharedOwnedSchedulerRejectSecondScope() throws Exception {
        Scheduler scheduler = Scheduler.fixed(1);
        ThreadScope first = ThreadScope.open().withScheduler(scheduler);
        assertEquals(Integer.valueOf(1), first.submit(() -> 1).await());
        first.close();

        try (ThreadScope second = ThreadScope.open().withScheduler(scheduler)) {
            Task<Integer> rejected = second.submit(() -> 2);
            ExecutionException failure = assertThrows(ExecutionException.class, () ->
                rejected.internalFuture().get(1L, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof RejectedExecutionException);
            assertEquals(Task.State.FAILED, rejected.state());
        }
    }

    @Test
    void closingScopeDoesNotShutdownExternalScheduler() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.from(executor));
            assertEquals(Integer.valueOf(1), scope.submit(() -> 1).await());
            scope.close();

            assertFalse(executor.isShutdown());
        } finally {
            executor.shutdownNow();
        }
    }
}
