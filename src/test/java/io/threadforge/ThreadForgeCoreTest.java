package io.threadforge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadForgeCoreTest {

    @Test
    void defaultsAreAsExpected() {
        try (ThreadScope scope = ThreadScope.open()) {
            assertEquals(FailurePolicy.FAIL_FAST, scope.failurePolicy());
            assertEquals(Duration.ofSeconds(30), scope.deadline());
            assertNotNull(scope.scheduler());
        }
    }

    @Test
    void schedulerVirtualThreadFallbackIsPredictable() {
        Scheduler scheduler = Scheduler.virtualThreads();
        if (Scheduler.isVirtualThreadSupported()) {
            assertTrue(scheduler.isVirtualThreadMode());
        } else {
            assertFalse(scheduler.isVirtualThreadMode());
            assertEquals("commonPool", scheduler.name());
        }
    }

    @Test
    void detectReturnsSharedSchedulerInstance() {
        Scheduler a = Scheduler.detect();
        Scheduler b = Scheduler.detect();
        assertSame(a, b);
    }

    @Test
    void fixedSchedulerUsesBoundedQueue() {
        Scheduler scheduler = Scheduler.fixed(2);
        try {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) scheduler.executor();
            assertTrue(executor.getQueue().remainingCapacity() < Integer.MAX_VALUE);
        } finally {
            scheduler.shutdownIfOwned();
        }
    }

    @Test
    void failFastCancelsPeersAndThrowsFirstFailure() {
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.FAIL_FAST)) {
            Task<Integer> bad = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalStateException("boom");
                }
            });
            Task<Integer> slow = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    Thread.sleep(1000L);
                    return 1;
                }
            });

            RuntimeException thrown = assertThrows(RuntimeException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    scope.await(Arrays.<Task<?>>asList(bad, slow));
                }
            });

            assertEquals("boom", thrown.getMessage());
            assertTrue(slow.isCancelled() || slow.isDone());
        }
    }

    @Test
    void collectAllThrowsAggregateWithAllFailures() {
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.COLLECT_ALL)) {
            Task<Integer> bad1 = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalArgumentException("f1");
                }
            });
            Task<Integer> bad2 = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalStateException("f2");
                }
            });

            AggregateException ex = assertThrows(AggregateException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    scope.await(Arrays.<Task<?>>asList(bad1, bad2));
                }
            });

            assertEquals(2, ex.failures().size());
        }
    }

    @Test
    void supervisorReturnsFailuresWithoutThrowing() {
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.SUPERVISOR)) {
            Task<Integer> ok = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    return 1;
                }
            });
            Task<Integer> bad1 = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalArgumentException("f1");
                }
            });
            Task<Integer> bad2 = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalStateException("f2");
                }
            });

            Outcome outcome = scope.await(Arrays.<Task<?>>asList(ok, bad1, bad2));
            assertEquals(1, outcome.succeeded());
            assertEquals(2, outcome.failed());
            assertTrue(outcome.hasFailures());
        }
    }

    @Test
    void deadlineTriggersCancellation() {
        try (ThreadScope scope = ThreadScope.open().withDeadline(Duration.ofMillis(100))) {
            Task<Integer> task = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    Thread.sleep(1000L);
                    return 7;
                }
            });

            assertThrows(ScopeTimeoutException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    scope.await(Collections.<Task<?>>singletonList(task));
                }
            });

            assertTrue(scope.token().isCancelled());
        }
    }

    @Test
    void concurrencyLimitCapsParallelism() {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();

        try (ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.fixed(8)).withConcurrencyLimit(2)) {
            List<Task<Integer>> tasks = new ArrayList<Task<Integer>>();
            for (int i = 0; i < 10; i++) {
                tasks.add(scope.submit(new java.util.concurrent.Callable<Integer>() {
                    @Override
                    public Integer call() throws Exception {
                        int current = running.incrementAndGet();
                        updateMax(maxRunning, current);
                        Thread.sleep(80L);
                        running.decrementAndGet();
                        return current;
                    }
                }));
            }

            scope.awaitAll(tasks);
            assertTrue(maxRunning.get() <= 2);
        }
    }

    @Test
    void concurrencyLimitBackpressuresSubmitter() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch secondSubmitted = new CountDownLatch(1);

        try (ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.fixed(2)).withConcurrencyLimit(1)) {
            scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    release.await(1L, TimeUnit.SECONDS);
                    return 1;
                }
            });

            Thread releaser = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(150L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    release.countDown();
                }
            });
            releaser.start();

            long started = System.nanoTime();
            scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    secondSubmitted.countDown();
                    return 2;
                }
            });
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis >= 100L);
            assertTrue(secondSubmitted.await(1L, TimeUnit.SECONDS));
            releaser.join(1000L);
        }
    }

    @Test
    void deferRunsInReverseOrder() {
        final List<Integer> order = new ArrayList<Integer>();
        try (ThreadScope scope = ThreadScope.open()) {
            scope.defer(new Runnable() {
                @Override
                public void run() {
                    order.add(1);
                }
            });
            scope.defer(new Runnable() {
                @Override
                public void run() {
                    order.add(2);
                }
            });
        }
        assertEquals(Arrays.asList(2, 1), order);
    }

    @Test
    void deferExceptionDoesNotSwallowMainException() {
        RuntimeException ex = assertThrows(RuntimeException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                try (ThreadScope scope = ThreadScope.open()) {
                    scope.defer(new Runnable() {
                        @Override
                        public void run() {
                            throw new IllegalStateException("defer");
                        }
                    });
                    throw new RuntimeException("main");
                }
            }
        });

        assertEquals("main", ex.getMessage());
        assertEquals(1, ex.getSuppressed().length);
        assertEquals("defer", ex.getSuppressed()[0].getMessage());
    }

    @Test
    void channelSupportsProducerConsumerAndCloseBehavior() throws Exception {
        Channel<Integer> channel = Channel.bounded(4);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> producer = executor.submit(new Runnable() {
                @Override
                public void run() {
                    for (int i = 1; i <= 5; i++) {
                        channel.send(i);
                    }
                    channel.close();
                }
            });

            List<Integer> consumed = new ArrayList<Integer>();
            for (Integer value : channel) {
                consumed.add(value);
            }
            producer.get(1L, TimeUnit.SECONDS);

            assertEquals(Arrays.asList(1, 2, 3, 4, 5), consumed);
            assertThrows(ChannelClosedException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    channel.receive();
                }
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void delaySchedulerSchedulesAndCancelsTasks() throws Exception {
        try (ThreadScope scope = ThreadScope.open()) {
            CountDownLatch once = new CountDownLatch(1);
            scope.schedule(Duration.ofMillis(50), new Runnable() {
                @Override
                public void run() {
                    once.countDown();
                }
            });

            assertTrue(once.await(1L, TimeUnit.SECONDS));

            AtomicInteger ticks = new AtomicInteger();
            ScheduledTask periodic = scope.scheduleAtFixedRate(
                Duration.ofMillis(10),
                Duration.ofMillis(25),
                new Runnable() {
                    @Override
                    public void run() {
                        ticks.incrementAndGet();
                    }
                }
            );

            Thread.sleep(120L);
            int beforeCancel = ticks.get();
            assertTrue(beforeCancel >= 2);
            periodic.cancel();
            Thread.sleep(100L);
            assertTrue(ticks.get() <= beforeCancel + 1);
        }
    }

    @Test
    void threadHookObservesLifecycleEvents() throws Exception {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final CountDownLatch longTaskStarted = new CountDownLatch(1);

        ThreadHook hook = new ThreadHook() {
            @Override
            public void onStart(TaskInfo info) {
                starts.incrementAndGet();
            }

            @Override
            public void onSuccess(TaskInfo info, Duration duration) {
                successes.incrementAndGet();
            }

            @Override
            public void onFailure(TaskInfo info, Throwable error, Duration duration) {
                failures.incrementAndGet();
            }

            @Override
            public void onCancel(TaskInfo info, Duration duration) {
                cancels.incrementAndGet();
            }
        };

        try (ThreadScope scope = ThreadScope.open()
            .withScheduler(Scheduler.fixed(4))
            .withFailurePolicy(FailurePolicy.SUPERVISOR)
            .withHook(hook)) {

            Task<Integer> ok = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    return 1;
                }
            });

            Task<Integer> bad = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    throw new IllegalStateException("err");
                }
            });

            Task<Integer> cancellable = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    longTaskStarted.countDown();
                    Thread.sleep(1000L);
                    return 9;
                }
            });

            assertTrue(longTaskStarted.await(1L, TimeUnit.SECONDS));
            cancellable.cancel();

            scope.await(Arrays.<Task<?>>asList(ok, bad, cancellable));

            assertTrue(starts.get() >= 2);
            assertEquals(1, successes.get());
            assertTrue(failures.get() >= 1);
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
            while (cancels.get() < 1 && System.nanoTime() < waitUntil) {
                Thread.sleep(10L);
            }
            assertTrue(cancels.get() >= 1);
        }
    }

    @Test
    void builtInMetricsCollectDurationsAndRemainHookCompatible() throws Exception {
        final AtomicInteger hookSuccess = new AtomicInteger();
        final AtomicInteger hookFailure = new AtomicInteger();
        final AtomicInteger hookCancel = new AtomicInteger();
        final CountDownLatch cancellableStarted = new CountDownLatch(1);

        ThreadHook hook = new ThreadHook() {
            @Override
            public void onSuccess(TaskInfo info, Duration duration) {
                hookSuccess.incrementAndGet();
            }

            @Override
            public void onFailure(TaskInfo info, Throwable error, Duration duration) {
                hookFailure.incrementAndGet();
            }

            @Override
            public void onCancel(TaskInfo info, Duration duration) {
                hookCancel.incrementAndGet();
            }
        };

        try (ThreadScope scope = ThreadScope.open()
            .withScheduler(Scheduler.fixed(4))
            .withFailurePolicy(FailurePolicy.SUPERVISOR)
            .withHook(hook)) {

                Task<Integer> ok = scope.submit(new java.util.concurrent.Callable<Integer>() {
                    @Override
                    public Integer call() throws Exception {
                        Thread.sleep(20L);
                        return 1;
                    }
                });

                Task<Integer> bad = scope.submit(new java.util.concurrent.Callable<Integer>() {
                    @Override
                    public Integer call() {
                        throw new IllegalStateException("boom");
                    }
                });

                Task<Integer> cancellable = scope.submit(new java.util.concurrent.Callable<Integer>() {
                    @Override
                    public Integer call() throws Exception {
                        cancellableStarted.countDown();
                        Thread.sleep(1000L);
                        return 9;
                    }
                });

                assertTrue(cancellableStarted.await(1L, TimeUnit.SECONDS));
                cancellable.cancel();

                scope.await(Arrays.<Task<?>>asList(ok, bad, cancellable));

                ScopeMetricsSnapshot snapshot = scope.metrics();
                assertEquals(3L, snapshot.started());
                assertEquals(1L, snapshot.succeeded());
                assertEquals(1L, snapshot.failed());
                assertEquals(1L, snapshot.cancelled());
                assertEquals(3L, snapshot.completed());
                assertTrue(snapshot.totalDuration().toNanos() > 0L);
                assertTrue(snapshot.maxDuration().toNanos() >= snapshot.averageDuration().toNanos());
            }

        assertEquals(1, hookSuccess.get());
        assertEquals(1, hookFailure.get());
        assertTrue(hookCancel.get() >= 1);
    }

    @Test
    void producerConsumerExampleWorksInsideScope() {
        try (ThreadScope scope = ThreadScope.open()) {
            final Channel<Integer> channel = Channel.bounded(8);
            scope.submit(new java.util.concurrent.Callable<Void>() {
                @Override
                public Void call() {
                    for (int i = 1; i <= 5; i++) {
                        channel.send(i);
                    }
                    channel.close();
                    return null;
                }
            });

            Task<Integer> sum = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    int total = 0;
                    for (Integer value : channel) {
                        total += value;
                    }
                    return total;
                }
            });

            assertEquals(Integer.valueOf(15), sum.await());
        }
    }

    @Test
    void rpcAggregationExampleWorksInsideScope() {
        try (ThreadScope scope = ThreadScope.open()) {
            Task<String> user = scope.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    return "u-100";
                }
            });
            Task<Integer> orders = scope.submit(new java.util.concurrent.Callable<Integer>() {
                @Override
                public Integer call() {
                    return 3;
                }
            });

            scope.await(Arrays.<Task<?>>asList(user, orders));
            String profile = user.await() + ":" + orders.await();
            assertEquals("u-100:3", profile);
        }
    }

    private static void updateMax(AtomicInteger maxRunning, int current) {
        int previous;
        do {
            previous = maxRunning.get();
            if (current <= previous) {
                return;
            }
        } while (!maxRunning.compareAndSet(previous, current));
    }
}
