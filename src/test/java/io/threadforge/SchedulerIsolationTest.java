package io.threadforge;

import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SchedulerIsolationTest {

    @RepeatedTest(50)
    void blockingScheduledUserJobDoesNotDelayTimeoutsInOtherScopes() throws Exception {
        final CountDownLatch userJobStarted = new CountDownLatch(1);
        final CountDownLatch releaseUserJob = new CountDownLatch(1);

        try (ThreadScope blockingScope = ThreadScope.open();
             ThreadScope firstTimedScope = ThreadScope.open();
             ThreadScope secondTimedScope = ThreadScope.open()) {
            blockingScope.schedule(Duration.ZERO, new Runnable() {
                @Override
                public void run() {
                    userJobStarted.countDown();
                    await(releaseUserJob);
                }
            });
            assertTrue(userJobStarted.await(1L, TimeUnit.SECONDS));

            Task<Void> first = timedBlockingTask(firstTimedScope);
            Task<Void> second = timedBlockingTask(secondTimedScope);

            assertTimeoutPreemptively(Duration.ofMillis(500), new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    assertThrows(TaskTimeoutException.class, first::await);
                    assertThrows(TaskTimeoutException.class, second::await);
                }
            });
        } finally {
            releaseUserJob.countDown();
        }
    }

    @RepeatedTest(50)
    void cancellingPeriodicTaskStopsDispatchingWork() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        try (ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.from(executor))) {
            ScheduledTask periodic = scope.scheduleAtFixedRate(
                Duration.ZERO,
                Duration.ofMillis(5),
                new Runnable() {
                    @Override
                    public void run() {
                    }
                }
            );

            assertTrue(executor.firstSubmission.await(1L, TimeUnit.SECONDS));
            periodic.cancel();
            int submissionsAtCancel = executor.submissions.get();

            CountDownLatch observationComplete = new CountDownLatch(1);
            DelayScheduler.shared().schedule(Duration.ofMillis(40), new Runnable() {
                @Override
                public void run() {
                    observationComplete.countDown();
                }
            });
            assertTrue(observationComplete.await(1L, TimeUnit.SECONDS));
            assertEquals(submissionsAtCancel, executor.submissions.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Task<Void> timedBlockingTask(ThreadScope scope) {
        return scope.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                new CountDownLatch(1).await();
                return null;
            }
        }, Duration.ofMillis(40));
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class RecordingExecutor extends AbstractExecutorService {
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private final AtomicInteger submissions = new AtomicInteger();
        private final CountDownLatch firstSubmission = new CountDownLatch(1);

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown.set(true);
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown.get();
        }

        @Override
        public void execute(Runnable command) {
            submissions.incrementAndGet();
            firstSubmission.countDown();
        }
    }
}
