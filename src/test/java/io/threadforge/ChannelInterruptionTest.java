package io.threadforge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelInterruptionTest {

    @Test
    void cancellingBlockedReceiverInterruptsAndExitsRunner() throws Exception {
        Channel<Integer> channel = Channel.bounded(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Integer> task = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    started.countDown();
                    try {
                        return channel.receive();
                    } catch (CancelledException expected) {
                        interruptPreserved.set(Thread.currentThread().isInterrupted());
                        throw expected;
                    }
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertTrue(task.cancel());
            task.awaitExecutionFinished(Duration.ofMillis(500));
            assertTrue(interruptPreserved.get());
        } finally {
            channel.close();
        }
    }

    @Test
    void cancellingBlockedSenderInterruptsAndExitsRunner() throws Exception {
        Channel<Integer> channel = Channel.bounded(1);
        channel.send(1);
        CountDownLatch started = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open()) {
            Task<Void> task = scope.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    started.countDown();
                    channel.send(2);
                    return null;
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertTrue(task.cancel());
            task.awaitExecutionFinished(Duration.ofMillis(500));
        } finally {
            channel.close();
        }
    }

    @Test
    void scopeDeadlineTerminatesChannelWait() throws Exception {
        Channel<Integer> channel = Channel.bounded(1);
        CountDownLatch started = new CountDownLatch(1);
        try (ThreadScope scope = ThreadScope.open().withDeadline(Duration.ofMillis(50))) {
            Task<Integer> task = scope.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    started.countDown();
                    return channel.receive();
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertThrows(ScopeTimeoutException.class, () -> scope.await(task));
            task.awaitExecutionFinished(Duration.ofMillis(500));
        } finally {
            channel.close();
        }
    }

    @Test
    void closedChannelDrainsBufferBeforeIteratorEnds() {
        Channel<Integer> channel = Channel.bounded(3);
        channel.send(1);
        channel.send(2);
        channel.close();

        assertEquals(Integer.valueOf(1), channel.receive());
        List<Integer> remaining = new ArrayList<Integer>();
        for (Integer value : channel) {
            remaining.add(value);
        }
        assertEquals(Arrays.asList(2), remaining);
        assertThrows(ChannelClosedException.class, channel::receive);
    }
}
