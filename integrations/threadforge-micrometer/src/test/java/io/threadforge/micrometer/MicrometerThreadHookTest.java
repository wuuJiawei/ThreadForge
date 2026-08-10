package io.threadforge.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.threadforge.TaskInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicrometerThreadHookTest {

    @Test
    void recordsStartAndEveryTerminalState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerThreadHook hook = MicrometerThreadHook.create(registry, "test.task");
        TaskInfo info = new TaskInfo(1L, 1L, "task", Instant.now(), "fixed");

        hook.onStart(info);
        hook.onSuccess(info, Duration.ofMillis(10));
        hook.onFailure(info, new IllegalStateException("boom"), Duration.ofMillis(20));
        hook.onCancel(info, Duration.ofMillis(30));

        assertEquals(1.0d, registry.get("test.task.started")
            .tags("scheduler", "fixed", "state", "started").counter().count());
        assertEquals(1.0d, completed(registry, "success"));
        assertEquals(1.0d, completed(registry, "failed"));
        assertEquals(1.0d, completed(registry, "cancelled"));
        assertEquals(1L, durationCount(registry, "success"));
        assertEquals(1L, durationCount(registry, "failed"));
        assertEquals(1L, durationCount(registry, "cancelled"));
    }

    private static double completed(SimpleMeterRegistry registry, String state) {
        return registry.get("test.task.completed")
            .tags("scheduler", "fixed", "state", state).counter().count();
    }

    private static long durationCount(SimpleMeterRegistry registry, String state) {
        return registry.get("test.task.duration")
            .tags("scheduler", "fixed", "state", state).timer().count();
    }
}
