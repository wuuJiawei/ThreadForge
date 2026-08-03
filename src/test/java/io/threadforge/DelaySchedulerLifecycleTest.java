package io.threadforge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelaySchedulerLifecycleTest {

    @Test
    void singleThreadSchedulerCanBeClosedAndRejectsNewTasks() throws Exception {
        DelayScheduler scheduler = DelayScheduler.singleThread();
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<Thread> schedulerThread = new AtomicReference<Thread>();
        scheduler.schedule(Duration.ZERO, new Runnable() {
            @Override
            public void run() {
                schedulerThread.set(Thread.currentThread());
                ran.countDown();
            }
        });
        assertTrue(ran.await(1L, TimeUnit.SECONDS));

        scheduler.close();
        schedulerThread.get().join(1000L);

        assertFalse(schedulerThread.get().isAlive());
        assertThrows(RejectedExecutionException.class, () ->
            scheduler.schedule(Duration.ZERO, new Runnable() {
                @Override
                public void run() {
                }
            }));
    }

    @Test
    void closingOwnedSchedulerTwiceIsIdempotent() {
        DelayScheduler scheduler = DelayScheduler.singleThread();
        scheduler.close();
        scheduler.close();
    }

    @Test
    void closingSharedSchedulerDoesNotBreakIt() throws Exception {
        DelayScheduler scheduler = DelayScheduler.shared();
        scheduler.close();

        CountDownLatch ran = new CountDownLatch(1);
        scheduler.schedule(Duration.ZERO, new Runnable() {
            @Override
            public void run() {
                ran.countDown();
            }
        });
        assertTrue(ran.await(1L, TimeUnit.SECONDS));
    }

    @Test
    void closingExternalWrapperDoesNotCloseExternalExecutor() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            DelayScheduler scheduler = DelayScheduler.from(executor);
            scheduler.close();

            assertFalse(executor.isShutdown());
            CountDownLatch ran = new CountDownLatch(1);
            scheduler.schedule(Duration.ZERO, new Runnable() {
                @Override
                public void run() {
                    ran.countDown();
                }
            });
            assertTrue(ran.await(1L, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
