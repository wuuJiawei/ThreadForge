package io.threadforge;

import io.threadforge.internal.DefaultCancellationToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreCoverageTest {

    @Test
    void taskInfoAndOutcomeAccessorsWork() {
        Instant now = Instant.now();
        TaskInfo info = new TaskInfo(11L, 22L, "worker", now, "fixed(2)");
        assertEquals(11L, info.scopeId());
        assertEquals(22L, info.taskId());
        assertEquals("worker", info.name());
        assertEquals(now, info.createdAt());
        assertEquals("fixed(2)", info.schedulerName());

        List<Throwable> failures = new ArrayList<Throwable>();
        failures.add(new IllegalStateException("boom"));
        Outcome outcome = new Outcome(3, 1, 1, failures);
        assertEquals(3, outcome.total());
        assertEquals(1, outcome.succeeded());
        assertEquals(1, outcome.cancelled());
        assertEquals(1, outcome.failed());
        assertTrue(outcome.hasFailures());
        assertEquals(1, outcome.failures().size());
        assertThrows(UnsupportedOperationException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                outcome.failures().add(new RuntimeException("cannot mutate"));
            }
        });

        Outcome clean = new Outcome(1, 1, 0, Collections.<Throwable>emptyList());
        assertFalse(clean.hasFailures());
    }

    @Test
    void taskCoversAccessorsAndRethrowBranches() {
        Task<String> successTask = new Task<String>(7L, "ok", CompletableFuture.completedFuture("done"));
        assertEquals(7L, successTask.id());
        assertEquals("ok", successTask.name());
        assertEquals("done", successTask.await());
        assertFalse(successTask.isFailed());

        Task<String> markedFailed = new Task<String>(8L, "failed-state", new CompletableFuture<String>());
        assertFalse(markedFailed.isFailed());
        markedFailed.markFailed();
        assertTrue(markedFailed.isFailed());

        CompletableFuture<String> checkedFuture = new CompletableFuture<String>();
        checkedFuture.completeExceptionally(new Exception("checked"));
        Task<String> checkedFailureTask = new Task<String>(9L, "checked", checkedFuture);
        TaskExecutionException wrapped = assertThrows(TaskExecutionException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                checkedFailureTask.await();
            }
        });
        assertEquals("checked", wrapped.getCause().getMessage());

        CompletableFuture<String> cancelledCauseFuture = new CompletableFuture<String>();
        final CancelledException cancelCause = new CancelledException("inner-cancel");
        cancelledCauseFuture.completeExceptionally(cancelCause);
        Task<String> cancelledCauseTask = new Task<String>(10L, "cancelled-cause", cancelledCauseFuture);
        CancelledException rethrownCancelled = assertThrows(CancelledException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                cancelledCauseTask.await();
            }
        });
        assertSame(cancelCause, rethrownCancelled);

        CompletableFuture<String> errorFuture = new CompletableFuture<String>();
        errorFuture.completeExceptionally(new AssertionError("fatal"));
        Task<String> errorTask = new Task<String>(11L, "error", errorFuture);
        AssertionError thrownError = assertThrows(AssertionError.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                errorTask.await();
            }
        });
        assertEquals("fatal", thrownError.getMessage());
    }

    @Test
    void taskHandlesCancelTimeoutAndInterruption() {
        CompletableFuture<String> cancelledFuture = new CompletableFuture<String>();
        cancelledFuture.cancel(true);
        Task<String> cancelledTask = new Task<String>(20L, "cancelled", cancelledFuture);
        assertThrows(CancelledException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                cancelledTask.await();
            }
        });
        assertTrue(cancelledTask.isCancelled());

        Task<String> timeoutTask = new Task<String>(21L, "timeout", new CompletableFuture<String>());
        assertThrows(ScopeTimeoutException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                timeoutTask.await(Duration.ofMillis(20));
            }
        });

        final Task<String> interruptedTask = new Task<String>(22L, "interrupted", new CompletableFuture<String>());
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancelledException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    interruptedTask.await();
                }
            });
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void schedulerFromExternalExecutorKeepsOwnershipExternal() {
        assertThrows(NullPointerException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                Scheduler.from(null);
            }
        });

        ExecutorService external = Executors.newSingleThreadExecutor();
        try {
            Scheduler scheduler = Scheduler.from(external);
            assertEquals("external", scheduler.name());
            scheduler.shutdownIfOwned();
            assertFalse(external.isShutdown());
        } finally {
            external.shutdownNow();
        }
    }

    @Test
    void delaySchedulerCoversCallableAndFixedDelayPaths() throws Exception {
        assertThrows(NullPointerException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                DelayScheduler.from(null);
            }
        });

        ScheduledExecutorService external = Executors.newSingleThreadScheduledExecutor();
        try {
            DelayScheduler externalScheduler = DelayScheduler.from(external);
            externalScheduler.shutdownIfOwned();
            assertFalse(external.isShutdown());
        } finally {
            external.shutdownNow();
        }

        DelayScheduler scheduler = DelayScheduler.singleThread();
        try {
            final CountDownLatch callableRan = new CountDownLatch(1);
            ScheduledTask callableTask = scheduler.schedule(Duration.ofMillis(10), new Callable<Integer>() {
                @Override
                public Integer call() {
                    callableRan.countDown();
                    return 123;
                }
            });
            assertTrue(callableRan.await(1L, TimeUnit.SECONDS));
            assertTrue(callableTask.isDone());

            final AtomicInteger ticks = new AtomicInteger();
            ScheduledTask periodic = scheduler.scheduleWithFixedDelay(
                Duration.ofMillis(0),
                Duration.ofMillis(20),
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
            Thread.sleep(80L);
            assertTrue(ticks.get() <= beforeCancel + 2);
        } finally {
            scheduler.shutdownIfOwned();
        }
    }

    @Test
    void defaultCancellationTokenMarkCancelledWithoutCallback() {
        final AtomicInteger callbackCalls = new AtomicInteger();

        DefaultCancellationToken token = new DefaultCancellationToken(new Runnable() {
            @Override
            public void run() {
                callbackCalls.incrementAndGet();
            }
        });
        assertTrue(token.markCancelledWithoutCallback());
        assertFalse(token.markCancelledWithoutCallback());
        assertEquals(0, callbackCalls.get());
        assertThrows(CancelledException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                token.throwIfCancelled();
            }
        });
        token.cancel();
        assertEquals(0, callbackCalls.get());

        DefaultCancellationToken regular = new DefaultCancellationToken(new Runnable() {
            @Override
            public void run() {
                callbackCalls.incrementAndGet();
            }
        });
        regular.cancel();
        regular.cancel();
        assertEquals(1, callbackCalls.get());
    }

    @Test
    void exceptionConstructorsRetainMessageAndCause() {
        CancelledException simple = new CancelledException("simple");
        assertEquals("simple", simple.getMessage());

        RuntimeException cause = new RuntimeException("root-cause");
        CancelledException withCause = new CancelledException("wrapped", cause);
        assertEquals("wrapped", withCause.getMessage());
        assertSame(cause, withCause.getCause());

        TaskExecutionException wrapped = new TaskExecutionException("execution-failed", cause);
        assertEquals("execution-failed", wrapped.getMessage());
        assertSame(cause, wrapped.getCause());
    }

    @Test
    void taskCompositionApisWork() {
        Task<Integer> base = new Task<Integer>(30L, "base", CompletableFuture.completedFuture(21));
        Integer doubled = base.thenApply(x -> x * 2).join();
        assertEquals(Integer.valueOf(42), doubled);

        Task<Integer> asyncBase = new Task<Integer>(31L, "async", CompletableFuture.completedFuture(10));
        Integer composed = asyncBase.thenCompose(x -> CompletableFuture.completedFuture(x + 5)).join();
        assertEquals(Integer.valueOf(15), composed);

        CompletableFuture<Integer> failure = new CompletableFuture<Integer>();
        failure.completeExceptionally(new IllegalStateException("broken"));
        Task<Integer> fallbackTask = new Task<Integer>(32L, "fallback", failure);
        Integer recovered = fallbackTask.exceptionally(t -> 99).join();
        assertEquals(Integer.valueOf(99), recovered);
    }

    @Test
    void scopeVarargsAwaitOverloadsWork() {
        try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.SUPERVISOR)) {
            Task<Integer> t1 = scope.submit(() -> 1);
            Task<Integer> t2 = scope.submit(() -> 2);
            Task<Integer> bad = scope.submit(() -> {
                throw new IllegalArgumentException("x");
            });

            Outcome outcome = scope.await(t1, t2, bad);
            assertEquals(3, outcome.total());
            assertEquals(2, outcome.succeeded());
            assertEquals(1, outcome.failed());
            assertTrue(outcome.hasFailures());

            List<Integer> values = scope.awaitAll(t1, t2);
            assertEquals(2, values.size());
            assertEquals(Integer.valueOf(1), values.get(0));
            assertEquals(Integer.valueOf(2), values.get(1));
        }
    }
}
