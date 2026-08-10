package io.threadforge;

import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeRegistrationRaceTest {

    @RepeatedTest(100)
    void deferEitherRegistersAndRunsOrFailsClearly() throws Exception {
        ThreadScope scope = ThreadScope.open();
        AtomicBoolean cleanupRan = new AtomicBoolean();
        RaceResult<Void> result = raceClose(scope, () -> {
            scope.defer(() -> cleanupRan.set(true));
            return null;
        });
        assertTrue(result.failedClearly() || cleanupRan.get());
    }

    @RepeatedTest(100)
    void submitEitherRegistersAndIsClosedOrFailsClearly() throws Exception {
        ThreadScope scope = ThreadScope.open();
        RaceResult<Task<Integer>> result = raceClose(scope, () -> scope.submit(() -> 1));
        if (result.failedClearly()) {
            return;
        }
        Task<Integer> task = result.value;
        assertTrue(task.isDone());
        assertTrue(task.isExecutionFinished());
    }

    @RepeatedTest(100)
    void scheduleEitherRegistersAndIsCancelledOrFailsClearly() throws Exception {
        ThreadScope scope = ThreadScope.open();
        RaceResult<ScheduledTask> result = raceClose(scope,
            () -> scope.schedule(Duration.ofDays(1), new Runnable() {
                @Override
                public void run() {
                }
            }));
        if (result.failedClearly()) {
            return;
        }
        assertTrue(result.value.isCancelled() || result.value.isDone());
    }

    private static <T> RaceResult<T> raceClose(ThreadScope scope, java.util.concurrent.Callable<T> registration)
        throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<T> register = executor.submit(() -> {
                barrier.await();
                return registration.call();
            });
            Future<?> close = executor.submit(() -> {
                barrier.await();
                scope.close();
                return null;
            });
            close.get(1L, TimeUnit.SECONDS);
            try {
                return RaceResult.success(register.get(1L, TimeUnit.SECONDS));
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                assertTrue(cause instanceof IllegalStateException || cause instanceof CancelledException);
                return RaceResult.failure();
            }
        } finally {
            scope.close();
            executor.shutdownNow();
        }
    }

    private static final class RaceResult<T> {
        private final T value;
        private final boolean failed;

        private RaceResult(T value, boolean failed) {
            this.value = value;
            this.failed = failed;
        }

        private static <T> RaceResult<T> success(T value) {
            return new RaceResult<T>(value, false);
        }

        private static <T> RaceResult<T> failure() {
            return new RaceResult<T>(null, true);
        }

        private boolean failedClearly() {
            return failed;
        }
    }
}
