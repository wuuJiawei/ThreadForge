package io.threadforge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityHookTest {

    @Test
    void threadHookCanComposeWithAndThen() {
        final List<String> events = new CopyOnWriteArrayList<String>();

        ThreadHook left = new ThreadHook() {
            @Override
            public void onStart(TaskInfo info) {
                events.add("left-start");
            }

            @Override
            public void onSuccess(TaskInfo info, Duration duration) {
                events.add("left-success");
            }
        };

        ThreadHook right = new ThreadHook() {
            @Override
            public void onStart(TaskInfo info) {
                events.add("right-start");
            }

            @Override
            public void onSuccess(TaskInfo info, Duration duration) {
                events.add("right-success");
            }
        };

        try (ThreadScope scope = ThreadScope.open().withHook(left.andThen(right))) {
            Task<Integer> task = scope.submit(() -> 1);
            assertEquals(Integer.valueOf(1), task.await());
        }

        assertEquals(4, events.size());
        assertEquals("left-start", events.get(0));
        assertEquals("right-start", events.get(1));
        assertEquals("left-success", events.get(2));
        assertEquals("right-success", events.get(3));
    }

    @Test
    void slowTaskHookEmitsForSlowSuccessAndFailure() {
        final List<SlowTaskEvent> events = new ArrayList<SlowTaskEvent>();

        try (ThreadScope scope = ThreadScope.open()
            .withFailurePolicy(FailurePolicy.SUPERVISOR)
            .withHook(SlowTaskHook.create(Duration.ofMillis(40), events::add))) {
            Task<Integer> slowSuccess = scope.submit("slow-success", () -> {
                Thread.sleep(70L);
                return 1;
            });
            Task<Integer> slowFailure = scope.submit("slow-failure", () -> {
                Thread.sleep(60L);
                throw new IllegalStateException("boom");
            });

            Outcome outcome = scope.await(slowSuccess, slowFailure);
            assertEquals(1, outcome.succeeded());
            assertEquals(1, outcome.failed());
        }

        assertEquals(2, events.size());
        SlowTaskEvent successEvent = findByName(events, "slow-success");
        SlowTaskEvent failureEvent = findByName(events, "slow-failure");
        assertEquals(Task.State.SUCCESS, successEvent.state());
        assertTrue(successEvent.duration().toMillis() >= 40L);
        assertEquals(Task.State.FAILED, failureEvent.state());
        assertNotNull(failureEvent.error());
    }

    @Test
    void slowTaskHookIgnoresFastTasksAndValidatesThreshold() {
        assertThrows(IllegalArgumentException.class, () ->
            SlowTaskHook.create(Duration.ZERO, new java.util.function.Consumer<SlowTaskEvent>() {
                @Override
                public void accept(SlowTaskEvent slowTaskEvent) {
                }
            })
        );

        final List<SlowTaskEvent> events = new ArrayList<SlowTaskEvent>();

        try (ThreadScope scope = ThreadScope.open().withHook(SlowTaskHook.create(
            Duration.ofMillis(100),
            events::add
        ))) {
            Task<Integer> fast = scope.submit(() -> 1);
            assertEquals(Integer.valueOf(1), fast.await());
        }

        assertTrue(events.isEmpty());
    }

    private SlowTaskEvent findByName(List<SlowTaskEvent> events, String name) {
        for (SlowTaskEvent event : events) {
            if (event.info().name().equals(name)) {
                return event;
            }
        }
        throw new AssertionError("Missing event for task: " + name);
    }
}
