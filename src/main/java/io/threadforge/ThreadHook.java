package io.threadforge;

import java.time.Duration;

/**
 * Observability callbacks for task lifecycle events.
 * Implementations should avoid blocking the worker thread for long periods.
 */
public interface ThreadHook {

    default void onStart(TaskInfo info) {
    }

    default void onSuccess(TaskInfo info, Duration duration) {
    }

    default void onFailure(TaskInfo info, Throwable error, Duration duration) {
    }

    default void onCancel(TaskInfo info, Duration duration) {
    }
}
