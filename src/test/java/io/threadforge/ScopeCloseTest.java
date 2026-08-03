package io.threadforge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeCloseTest {

    @Test
    void closeWaitsForTaskFinallyBeforeReturningAndRunningCleanup() throws Exception {
        ThreadScope scope = ThreadScope.open();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finallyEntered = new CountDownLatch(1);
        CountDownLatch allowFinallyExit = new CountDownLatch(1);
        AtomicBoolean taskExited = new AtomicBoolean();
        AtomicBoolean cleanupRan = new AtomicBoolean();
        AtomicBoolean cleanupSawExit = new AtomicBoolean();
        AtomicBoolean closeReturned = new AtomicBoolean();
        try {
            scope.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } finally {
                        finallyEntered.countDown();
                        allowFinallyExit.await();
                        taskExited.set(true);
                    }
                    return null;
                }
            });
            scope.defer(() -> {
                cleanupRan.set(true);
                cleanupSawExit.set(taskExited.get());
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));

            Thread closer = closeInNewThread(scope, closeReturned);
            assertTrue(finallyEntered.await(1L, TimeUnit.SECONDS));
            assertFalse(closeReturned.get());
            assertFalse(cleanupSawExit.get());

            allowFinallyExit.countDown();
            closer.join(1000L);
            assertFalse(closer.isAlive());
            assertTrue(closeReturned.get());
            assertTrue(taskExited.get());
            assertTrue(cleanupRan.get());
            assertTrue(cleanupSawExit.get());
        } finally {
            allowFinallyExit.countDown();
            scope.close();
        }
    }

    @Test
    void queuedTaskNeverRunsWhenScopeCloses() throws Exception {
        ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.fixed(1));
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerFinally = new CountDownLatch(1);
        CountDownLatch releaseBlockerFinally = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        AtomicBoolean closeReturned = new AtomicBoolean();
        try {
            scope.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    blockerStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } finally {
                        blockerFinally.countDown();
                        releaseBlockerFinally.await();
                    }
                    return null;
                }
            });
            scope.submit(() -> {
                queuedRan.set(true);
                return null;
            });
            assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

            Thread closer = closeInNewThread(scope, closeReturned);
            assertTrue(blockerFinally.await(1L, TimeUnit.SECONDS));
            assertFalse(closeReturned.get());
            releaseBlockerFinally.countDown();
            closer.join(1000L);

            assertFalse(closer.isAlive());
            assertFalse(queuedRan.get());
        } finally {
            releaseBlockerFinally.countDown();
            scope.close();
        }
    }

    @Test
    void interruptIgnoringTaskKeepsCloseBlockedUntilItActuallyEnds() throws Exception {
        ThreadScope scope = ThreadScope.open();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closeReturned = new AtomicBoolean();
        try {
            scope.submit(new Callable<Void>() {
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
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));

            Thread closer = closeInNewThread(scope, closeReturned);
            assertTrue(interruptObserved.await(1L, TimeUnit.SECONDS));
            assertFalse(closeReturned.get());

            release.countDown();
            closer.join(1000L);
            assertFalse(closer.isAlive());
            assertTrue(closeReturned.get());
        } finally {
            release.countDown();
            scope.close();
        }
    }

    @Test
    void closeAlsoWaitsForRunningScheduledWork() throws Exception {
        ThreadScope scope = ThreadScope.open();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closeReturned = new AtomicBoolean();
        try {
            scope.schedule(java.time.Duration.ZERO, new Runnable() {
                @Override
                public void run() {
                    started.countDown();
                    while (true) {
                        try {
                            release.await();
                            return;
                        } catch (InterruptedException ignored) {
                            interruptObserved.countDown();
                        }
                    }
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));

            Thread closer = closeInNewThread(scope, closeReturned);
            assertTrue(interruptObserved.await(1L, TimeUnit.SECONDS));
            assertFalse(closeReturned.get());
            release.countDown();
            closer.join(1000L);

            assertFalse(closer.isAlive());
            assertTrue(closeReturned.get());
        } finally {
            release.countDown();
            scope.close();
        }
    }

    private static Thread closeInNewThread(ThreadScope scope, AtomicBoolean returned) {
        Thread closer = new Thread(new Runnable() {
            @Override
            public void run() {
                scope.close();
                returned.set(true);
            }
        });
        closer.start();
        return closer;
    }
}
