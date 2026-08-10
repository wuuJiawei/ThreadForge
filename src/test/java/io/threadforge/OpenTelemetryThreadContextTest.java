package io.threadforge;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryThreadContextTest {

    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void installOpenTelemetry() {
        GlobalOpenTelemetry.resetForTest();
        tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();
    }

    @AfterEach
    void resetOpenTelemetry() {
        tracerProvider.close();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void successRestoresWorkerOpenTelemetryContext() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            try (ThreadScope scope = ThreadScope.open()
                .withScheduler(Scheduler.from(worker)).withOpenTelemetry("test")) {
                assertTrue(scope.submit(() -> Span.current().getSpanContext().isValid()).await());
            }
            assertWorkerHasNoSpan(worker);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void runningTimeoutClosesOpenTelemetryScopeOnRunner() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.submit(() -> null).get(1L, TimeUnit.SECONDS);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.from(worker)).withOpenTelemetry("test-timeout");
        try {
            Task<Void> task = scope.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    assertTrue(Span.current().getSpanContext().isValid());
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
            }, Duration.ofMillis(500));
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertThrows(TaskTimeoutException.class, task::await);
            assertTrue(interruptObserved.await(1L, TimeUnit.SECONDS));
            release.countDown();
            assertWorkerHasNoSpan(worker);
        } finally {
            release.countDown();
            scope.close();
            worker.shutdownNow();
        }
    }

    private static void assertWorkerHasNoSpan(ExecutorService worker) throws Exception {
        assertFalse(worker.submit(() -> Span.current().getSpanContext().isValid()).get(1L, TimeUnit.SECONDS));
    }
}
