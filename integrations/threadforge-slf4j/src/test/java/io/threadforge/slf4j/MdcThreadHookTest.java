package io.threadforge.slf4j;

import io.threadforge.CancelledException;
import io.threadforge.Context;
import io.threadforge.FailurePolicy;
import io.threadforge.Scheduler;
import io.threadforge.Task;
import io.threadforge.TaskInfo;
import io.threadforge.TaskTimeoutException;
import io.threadforge.ThreadScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdcThreadHookTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
        Context.clear();
    }

    @Test
    void successFailureAndCancelRestoreReusedWorkerMdc() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            setWorkerMdc(worker, "base");
            MdcThreadHook hook = MdcThreadHook.captureAll();

            Context.put("traceId", "success");
            try (ThreadScope scope = ThreadScope.open()
                .withScheduler(Scheduler.from(worker)).withHook(hook)) {
                assertEquals("success", scope.submit(() -> MDC.get("traceId")).await());
            }
            assertWorkerMdc(worker, "base");

            Context.put("traceId", "failure");
            try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.SUPERVISOR)
                .withScheduler(Scheduler.from(worker)).withHook(hook)) {
                Task<Void> failed = scope.submit(new Callable<Void>() {
                    @Override
                    public Void call() {
                        assertEquals("failure", MDC.get("traceId"));
                        throw new IllegalStateException("boom");
                    }
                });
                assertThrows(IllegalStateException.class, failed::await);
            }
            assertWorkerMdc(worker, "base");

            Context.put("traceId", "cancel");
            CountDownLatch started = new CountDownLatch(1);
            try (ThreadScope scope = ThreadScope.open()
                .withScheduler(Scheduler.from(worker)).withHook(hook)) {
                Task<Void> cancelled = scope.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        assertEquals("cancel", MDC.get("traceId"));
                        started.countDown();
                        new CountDownLatch(1).await();
                        return null;
                    }
                });
                assertTrue(started.await(1L, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel());
                assertThrows(CancelledException.class, cancelled::await);
            }
            assertWorkerMdc(worker, "base");
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void runningTimeoutRestoresMdcOnRunnerThread() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.from(worker))
            .withHook(MdcThreadHook.captureAll());
        try {
            setWorkerMdc(worker, "base");
            Context.put("traceId", "timeout");
            Task<Void> task = scope.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    assertEquals("timeout", MDC.get("traceId"));
                    started.countDown();
                    while (true) {
                        try {
                            release.await();
                            return null;
                        } catch (InterruptedException ignored) {
                            interruptObserved.countDown();
                        }
                    }
                }
            }, Duration.ofMillis(40));
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertThrows(TaskTimeoutException.class, task::await);
            assertTrue(interruptObserved.await(1L, TimeUnit.SECONDS));
            release.countDown();
            assertWorkerMdc(worker, "base");
        } finally {
            release.countDown();
            scope.close();
            worker.shutdownNow();
        }
    }

    @Test
    void terminalWithoutStartDoesNotClearCurrentMdc() {
        MDC.put("timer", "keep");
        MdcThreadHook hook = MdcThreadHook.captureAll();
        hook.onFailure(info(1L, 1L), new TaskTimeoutException("timeout"), Duration.ofMillis(1));
        assertEquals("keep", MDC.get("timer"));
    }

    @Test
    void sameHookSupportsSameTaskIdFromDifferentScopes() throws Exception {
        MdcThreadHook hook = MdcThreadHook.captureAll();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CyclicBarrier started = new CyclicBarrier(2);
        CyclicBarrier finish = new CyclicBarrier(2);
        try {
            Future<String> first = threads.submit(() -> exerciseHook(hook, info(1L, 1L), "first", started, finish));
            Future<String> second = threads.submit(() -> exerciseHook(hook, info(2L, 1L), "second", started, finish));
            assertEquals("first", first.get(1L, TimeUnit.SECONDS));
            assertEquals("second", second.get(1L, TimeUnit.SECONDS));
        } finally {
            threads.shutdownNow();
        }
    }

    private static String exerciseHook(
        MdcThreadHook hook,
        TaskInfo info,
        String workerValue,
        CyclicBarrier started,
        CyclicBarrier finish
    ) throws Exception {
        MDC.put("worker", workerValue);
        Context.put("traceId", "task-" + workerValue);
        hook.onStart(info);
        assertEquals("task-" + workerValue, MDC.get("traceId"));
        started.await();
        finish.await();
        hook.onSuccess(info, Duration.ZERO);
        assertNull(MDC.get("traceId"));
        return MDC.get("worker");
    }

    private static TaskInfo info(long scopeId, long taskId) {
        return new TaskInfo(scopeId, taskId, "task", Instant.now(), "test");
    }

    private static void setWorkerMdc(ExecutorService worker, String value) throws Exception {
        worker.submit(() -> MDC.put("worker", value)).get(1L, TimeUnit.SECONDS);
    }

    private static void assertWorkerMdc(ExecutorService worker, String value) throws Exception {
        worker.submit(() -> {
            assertEquals(value, MDC.get("worker"));
            assertNull(MDC.get("traceId"));
        }).get(1L, TimeUnit.SECONDS);
    }
}
