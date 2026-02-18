package io.threadforge;

import io.threadforge.internal.DefaultCancellationToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                Scheduler.priority(0);
            }
        });
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
    void scopeMetricsSnapshotToStringIsFormatted() {
        ScopeMetricsSnapshot snapshot = new ScopeMetricsSnapshot(
            5L,
            3L,
            1L,
            1L,
            Duration.ofMillis(200).toNanos(),
            Duration.ofMillis(120).toNanos()
        );

        String text = snapshot.toString();
        assertTrue(text.startsWith("ScopeMetricsSnapshot{\n"));
        assertTrue(text.contains("started=5"));
        assertTrue(text.contains("succeeded=3"));
        assertTrue(text.contains("failed=1"));
        assertTrue(text.contains("cancelled=1"));
        assertTrue(text.contains("completed=5"));
        assertTrue(text.contains("totalDuration=PT0.2S (200 ms)"));
        assertTrue(text.contains("averageDuration=PT0.04S (40 ms)"));
        assertTrue(text.contains("maxDuration=PT0.12S (120 ms)"));
        assertTrue(text.endsWith("\n}"));
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

        TaskTimeoutException timeout = new TaskTimeoutException("task-timeout");
        assertEquals("task-timeout", timeout.getMessage());
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

    @Test
    void retryPolicyFactoriesAndDefaultsWork() {
        RetryPolicy noRetry = RetryPolicy.noRetry();
        assertEquals(1, noRetry.maxAttempts());
        assertFalse(noRetry.allowsRetry(1, new RuntimeException("x")));

        RetryPolicy attempts = RetryPolicy.attempts(3);
        assertTrue(attempts.allowsRetry(1, new IllegalStateException("boom")));
        assertFalse(attempts.allowsRetry(3, new IllegalStateException("boom")));
        assertFalse(attempts.allowsRetry(1, new CancelledException("cancel")));
        assertEquals(Duration.ZERO, attempts.nextDelay(1, new RuntimeException("x")));
    }

    @Test
    void retryPolicyBuilderSupportsCustomConditionAndBackoff() {
        RetryPolicy custom = RetryPolicy.builder()
            .maxAttempts(4)
            .retryIf(new RetryPolicy.RetryCondition() {
                @Override
                public boolean shouldRetry(int attempt, Throwable failure) {
                    return failure instanceof IllegalStateException;
                }
            })
            .backoff(new RetryPolicy.BackoffStrategy() {
                @Override
                public Duration nextDelay(int attempt, Throwable failure) {
                    return Duration.ofMillis(attempt * 10L);
                }
            })
            .build();

        assertTrue(custom.allowsRetry(1, new IllegalStateException("retry")));
        assertFalse(custom.allowsRetry(1, new IllegalArgumentException("no-retry")));
        assertEquals(Duration.ofMillis(20), custom.nextDelay(2, new IllegalStateException("retry")));
    }

    @Test
    void retryPolicyFixedAndExponentialBackoffWork() {
        RetryPolicy fixed = RetryPolicy.fixedDelay(3, Duration.ofMillis(5));
        assertEquals(Duration.ofMillis(5), fixed.nextDelay(1, new RuntimeException("x")));

        RetryPolicy exp = RetryPolicy.exponentialBackoff(4, Duration.ofMillis(10), 2.0d, Duration.ofMillis(25));
        assertEquals(Duration.ofMillis(10), exp.nextDelay(1, new RuntimeException("x")));
        assertEquals(Duration.ofMillis(20), exp.nextDelay(2, new RuntimeException("x")));
        assertEquals(Duration.ofMillis(25), exp.nextDelay(3, new RuntimeException("x")));
    }

    @Test
    void retryPolicyValidationRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                RetryPolicy.attempts(0);
            }
        });

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                RetryPolicy.fixedDelay(2, Duration.ofMillis(-1));
            }
        });

        assertThrows(NullPointerException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                RetryPolicy.builder().retryIf(null);
            }
        });

        assertThrows(NullPointerException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                RetryPolicy.builder().backoff(null);
            }
        });

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                RetryPolicy.builder().exponentialBackoff(Duration.ofMillis(1), 0.5d, Duration.ofSeconds(1));
            }
        });
    }

    @Test
    void submitRejectsInvalidTaskTimeout() {
        try (ThreadScope scope = ThreadScope.open()) {
            assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    scope.submit(new Callable<Integer>() {
                        @Override
                        public Integer call() {
                            return 1;
                        }
                    }, Duration.ZERO);
                }
            });
        }
    }

    @Test
    void prioritySubmitOverloadsAndValidationWork() {
        try (ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.priority(2))) {
            assertThrows(NullPointerException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    scope.withDefaultTaskPriority(null);
                }
            });

            assertThrows(NullPointerException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    scope.submit(new Callable<Integer>() {
                        @Override
                        public Integer call() {
                            return 1;
                        }
                    }, (TaskPriority) null);
                }
            });

            Task<Integer> p1 = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    return 1;
                }
            }, TaskPriority.HIGH, RetryPolicy.noRetry());
            Task<Integer> p2 = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    return 2;
                }
            }, TaskPriority.NORMAL, Duration.ofSeconds(1));
            Task<Integer> p3 = scope.submit("p3", new Callable<Integer>() {
                @Override
                public Integer call() {
                    return 3;
                }
            }, TaskPriority.LOW, RetryPolicy.noRetry(), Duration.ofSeconds(1));

            scope.await(p1, p2, p3);
            assertEquals(Integer.valueOf(1), p1.await());
            assertEquals(Integer.valueOf(2), p2.await());
            assertEquals(Integer.valueOf(3), p3.await());
        }
    }

    @Test
    void contextBasicOperationsWork() {
        Context.clear();
        try {
            assertTrue(Context.snapshot().isEmpty());

            Context.put("k1", "v1");
            Context.put("k2", 7);
            assertEquals("v1", Context.<String>get("k1"));
            assertEquals(Integer.valueOf(7), Context.<Integer>get("k2"));
            assertEquals(2, Context.snapshot().size());

            Context.put("k2", null);
            assertTrue(Context.get("k2") == null);

            Context.remove("k1");
            assertTrue(Context.snapshot().isEmpty());
        } finally {
            Context.clear();
        }
    }

    @Test
    void contextValidatesNullKeys() {
        assertThrows(NullPointerException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                Context.put(null, "x");
            }
        });
        assertThrows(NullPointerException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                Context.get(null);
            }
        });
        assertThrows(NullPointerException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                Context.remove(null);
            }
        });
    }

    @Test
    void openTelemetryBridgeNoOpPathsAreSafeWhenUnavailable() {
        if (OpenTelemetryBridge.isAvailable()) {
            return;
        }
        assertTrue(OpenTelemetryBridge.currentContext() == null);
        assertTrue(OpenTelemetryBridge.makeCurrent(new Object()) == null);
        assertTrue(OpenTelemetryBridge.createTracer("io.threadforge.test") == null);
        assertTrue(OpenTelemetryBridge.startSpan(null, "x", new TaskInfo(1L, 2L, "t", Instant.now(), "s")) == null);
        assertTrue(OpenTelemetryBridge.spanMakeCurrent(new Object()) == null);
        OpenTelemetryBridge.spanSetDuration(null, 10L);
        OpenTelemetryBridge.spanSetCancelled(null, true);
        OpenTelemetryBridge.spanRecordFailure(null, new RuntimeException("x"));
        OpenTelemetryBridge.spanEnd(null);
        OpenTelemetryBridge.closeScope(null);
    }

    @Test
    void openTelemetryHookFallbackPathsAreCoveredWithoutApi() throws Exception {
        if (OpenTelemetryBridge.isAvailable()) {
            return;
        }

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                OpenTelemetryHook.create("io.threadforge");
            }
        });

        Constructor<OpenTelemetryHook> hookConstructor = OpenTelemetryHook.class.getDeclaredConstructor(String.class, Object.class);
        hookConstructor.setAccessible(true);
        OpenTelemetryHook hook = hookConstructor.newInstance("io.threadforge.test", new Object());
        assertEquals("io.threadforge.test", hook.instrumentationName());

        TaskInfo info = new TaskInfo(11L, 22L, "task-a", Instant.now(), "fixed(1)");
        hook.onStart(info);
        hook.onSuccess(info, Duration.ofMillis(3));
        hook.onFailure(info, new RuntimeException("boom"), Duration.ofMillis(4));
        hook.onCancel(info, Duration.ofMillis(5));

        Class<?> spanStateClass = Class.forName("io.threadforge.OpenTelemetryHook$SpanState");
        Constructor<?> spanStateConstructor = spanStateClass.getDeclaredConstructor(Object.class, Object.class);
        spanStateConstructor.setAccessible(true);

        Field spansField = OpenTelemetryHook.class.getDeclaredField("spans");
        spansField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Long, Object> spans = (Map<Long, Object>) spansField.get(hook);

        Object spanState1 = spanStateConstructor.newInstance(new Object(), new Object());
        spans.put(22L, spanState1);
        hook.onSuccess(info, Duration.ofMillis(6));

        Object spanState2 = spanStateConstructor.newInstance(new Object(), new Object());
        spans.put(22L, spanState2);
        hook.onFailure(info, new IllegalStateException("f"), Duration.ofMillis(7));

        Object spanState3 = spanStateConstructor.newInstance(new Object(), new Object());
        spans.put(22L, spanState3);
        hook.onCancel(info, Duration.ofMillis(8));
    }
}
