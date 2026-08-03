package io.threadforge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwaitInterruptionTest {

    @Test
    void taskAwaitPropagatesWaiterInterruptionWithoutChangingTask() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Void> task = scope.submit(blockingTask(taskStarted, releaseTask));
            assertTrue(taskStarted.await(1L, TimeUnit.SECONDS));

            WaitResult result = interruptWaiter(new Runnable() {
                @Override
                public void run() {
                    task.await();
                }
            });

            assertTrue(result.failure.get() instanceof CancelledException);
            assertTrue(result.interruptPreserved.get());
            assertEquals(Task.State.RUNNING, task.state());
        } finally {
            releaseTask.countDown();
        }
    }

    @Test
    void scopeAwaitPropagatesWaiterInterruptionWithoutFalseOutcome() throws Exception {
        CountDownLatch tasksStarted = new CountDownLatch(2);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.SUPERVISOR)) {
            Task<Void> first = scope.submit(blockingTask(tasksStarted, releaseTasks));
            Task<Void> second = scope.submit(blockingTask(tasksStarted, releaseTasks));
            assertTrue(tasksStarted.await(1L, TimeUnit.SECONDS));

            WaitResult result = interruptWaiter(new Runnable() {
                @Override
                public void run() {
                    scope.await(first, second);
                }
            });

            assertTrue(result.failure.get() instanceof CancelledException);
            assertTrue(result.interruptPreserved.get());
            assertEquals(Task.State.RUNNING, first.state());
            assertEquals(Task.State.RUNNING, second.state());
        } finally {
            releaseTasks.countDown();
        }
    }

    private static Callable<Void> blockingTask(CountDownLatch started, CountDownLatch release) {
        return new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                started.countDown();
                release.await();
                return null;
            }
        };
    }

    private static WaitResult interruptWaiter(Runnable await) throws Exception {
        WaitResult result = new WaitResult();
        CountDownLatch waiterReady = new CountDownLatch(1);
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                waiterReady.countDown();
                try {
                    await.run();
                } catch (Throwable failure) {
                    result.failure.set(failure);
                    result.interruptPreserved.set(Thread.currentThread().isInterrupted());
                }
            }
        });
        waiter.start();
        assertTrue(waiterReady.await(1L, TimeUnit.SECONDS));
        waiter.interrupt();
        waiter.join(1000L);
        assertTrue(!waiter.isAlive());
        return result;
    }

    private static final class WaitResult {
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        private final AtomicBoolean interruptPreserved = new AtomicBoolean();
    }
}
