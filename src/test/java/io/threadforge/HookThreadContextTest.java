package io.threadforge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookThreadContextTest {

    @Test
    void runningTimeoutFinishesHookOnRunnerAfterUserCodeExits() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch failureHook = new CountDownLatch(1);
        AtomicReference<Thread> startThread = new AtomicReference<Thread>();
        AtomicReference<Thread> failureThread = new AtomicReference<Thread>();
        AtomicInteger failures = new AtomicInteger();
        ThreadHook hook = new ThreadHook() {
            @Override
            public void onStart(TaskInfo info) {
                startThread.set(Thread.currentThread());
            }

            @Override
            public void onFailure(TaskInfo info, Throwable error, Duration duration) {
                failureThread.set(Thread.currentThread());
                failures.incrementAndGet();
                failureHook.countDown();
            }
        };

        ThreadScope scope = ThreadScope.open().withHook(hook);
        try {
            Task<Void> task = scope.submit(new Callable<Void>() {
                @Override
                public Void call() {
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
            assertFalse(failureHook.await(50L, TimeUnit.MILLISECONDS));

            release.countDown();
            task.awaitExecutionFinished(Duration.ofSeconds(1));
            assertTrue(failureHook.await(1L, TimeUnit.SECONDS));
            assertSame(startThread.get(), failureThread.get());
            assertEquals(1, failures.get());
        } finally {
            release.countDown();
            scope.close();
        }
    }

    @Test
    void queuedTimeoutDoesNotCallStartButReportsFailureOnce() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch queuedFailure = new CountDownLatch(1);
        AtomicInteger queuedStarts = new AtomicInteger();
        AtomicInteger queuedFailures = new AtomicInteger();
        ThreadHook hook = new ThreadHook() {
            @Override
            public void onStart(TaskInfo info) {
                if ("queued".equals(info.name())) {
                    queuedStarts.incrementAndGet();
                }
            }

            @Override
            public void onFailure(TaskInfo info, Throwable error, Duration duration) {
                if ("queued".equals(info.name())) {
                    queuedFailures.incrementAndGet();
                    queuedFailure.countDown();
                }
            }
        };

        try (ThreadScope scope = ThreadScope.open()
            .withScheduler(Scheduler.fixed(1))
            .withHook(hook)) {
            Task<Void> blocker = scope.submit("blocker", new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    blockerStarted.countDown();
                    releaseBlocker.await();
                    return null;
                }
            });
            assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));
            Task<Void> queued = scope.submit("queued", () -> null, Duration.ofMillis(40));
            assertThrows(TaskTimeoutException.class, queued::await);
            releaseBlocker.countDown();
            blocker.await();
            queued.awaitExecutionFinished(Duration.ofSeconds(1));
            assertTrue(queuedFailure.await(1L, TimeUnit.SECONDS));
            assertEquals(0, queuedStarts.get());
            assertEquals(1, queuedFailures.get());
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test
    void hookExceptionsNeverChangeTaskResult() {
        ThreadHook throwing = new ThreadHook() {
            @Override
            public void onStart(TaskInfo info) {
                throw new IllegalStateException("start-hook");
            }

            @Override
            public void onSuccess(TaskInfo info, Duration duration) {
                throw new IllegalStateException("success-hook");
            }
        };
        try (ThreadScope scope = ThreadScope.open().withHook(throwing)) {
            assertEquals(Integer.valueOf(7), scope.submit(() -> 7).await());
        }
    }
}
