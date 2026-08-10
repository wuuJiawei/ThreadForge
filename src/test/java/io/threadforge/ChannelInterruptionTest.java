package io.threadforge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelInterruptionTest {

    @Test
    void cancellingBlockedReceiverInterruptsAndExitsRunner() throws Exception {
        Channel<Integer> channel = Channel.bounded(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Integer> task = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    started.countDown();
                    try {
                        return channel.receive();
                    } catch (CancelledException expected) {
                        interruptPreserved.set(Thread.currentThread().isInterrupted());
                        throw expected;
                    }
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertTrue(task.cancel());
            task.awaitExecutionFinished(Duration.ofMillis(500));
            assertTrue(interruptPreserved.get());
        } finally {
            channel.close();
        }
    }

    @Test
    void cancellingBlockedSenderInterruptsAndExitsRunner() throws Exception {
        Channel<Integer> channel = Channel.bounded(1);
        channel.send(1);
        CountDownLatch started = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Void> task = scope.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    started.countDown();
                    channel.send(2);
                    return null;
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertTrue(task.cancel());
            task.awaitExecutionFinished(Duration.ofMillis(500));
        } finally {
            channel.close();
        }
    }

    @Test
    void scopeDeadlineTerminatesChannelWait() throws Exception {
        Channel<Integer> channel = Channel.bounded(1);
        CountDownLatch started = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open().withDeadline(Duration.ofMillis(50))) {
            Task<Integer> task = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    started.countDown();
                    return channel.receive();
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertThrows(ScopeTimeoutException.class, () -> scope.await(task));
            task.awaitExecutionFinished(Duration.ofMillis(500));
        } finally {
            channel.close();
        }
    }

    @Test
    void cancelledChannelWaitDoesNotLeakInterruptToNextTask() throws Exception {
        InterruptRetainingExecutor executor = new InterruptRetainingExecutor();
        Channel<Integer> cancelledChannel = Channel.bounded(1);
        CountDownLatch cancelledStarted = new CountDownLatch(1);
        Channel<Integer> deadlineChannel = Channel.bounded(1);
        CountDownLatch deadlineStarted = new CountDownLatch(1);
        try {
            Scheduler scheduler = Scheduler.from(executor);
            try (ThreadScope first = ThreadScope.open().withScheduler(scheduler)) {
                Task<Integer> cancelled = first.submit(new Callable<Integer>() {
                    @Override
                    public Integer call() {
                        cancelledStarted.countDown();
                        return cancelledChannel.receive();
                    }
                });
                assertTrue(cancelledStarted.await(1L, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel());
            }
            cancelledChannel.close();

            try (ThreadScope second = ThreadScope.open()
                .withScheduler(scheduler)
                .withDeadline(Duration.ofMillis(50))) {
                Task<Integer> deadlineTask = second.submit(new Callable<Integer>() {
                    @Override
                    public Integer call() {
                        deadlineStarted.countDown();
                        return deadlineChannel.receive();
                    }
                });
                assertTrue(deadlineStarted.await(1L, TimeUnit.SECONDS));
                assertThrows(ScopeTimeoutException.class, () -> second.await(deadlineTask));
            }
        } finally {
            deadlineChannel.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closedChannelDrainsBufferBeforeIteratorEnds() {
        Channel<Integer> channel = Channel.bounded(3);
        channel.send(1);
        channel.send(2);
        channel.close();

        assertEquals(Integer.valueOf(1), channel.receive());
        List<Integer> remaining = new ArrayList<Integer>();
        for (Integer value : channel) {
            remaining.add(value);
        }
        assertEquals(Arrays.asList(2), remaining);
        assertThrows(ChannelClosedException.class, channel::receive);
    }

    private static final class InterruptRetainingExecutor extends AbstractExecutorService {
        private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicReference<Thread> worker = new AtomicReference<Thread>();

        private InterruptRetainingExecutor() {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    worker.set(Thread.currentThread());
                    try {
                        while (!shutdown.get()) {
                            Runnable command = queue.poll();
                            if (command != null) {
                                command.run();
                            } else {
                                Thread.yield();
                            }
                        }
                    } finally {
                        terminated.set(true);
                    }
                }
            }, "threadforge-test-interrupt-retaining");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown.get()) {
                throw new RejectedExecutionException("executor is shut down");
            }
            queue.offer(command);
        }

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown.set(true);
            Thread thread = worker.get();
            if (thread != null) {
                thread.interrupt();
            }
            return new java.util.ArrayList<Runnable>(queue);
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return terminated.get();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!terminated.get() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            return terminated.get();
        }

    }
}
