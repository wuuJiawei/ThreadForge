package io.threadforge;

import java.time.Duration;
import java.util.Objects;

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

    default ThreadHook andThen(final ThreadHook next) {
        Objects.requireNonNull(next, "next");
        final ThreadHook current = this;
        return new ThreadHook() {
            @Override
            public void onStart(TaskInfo info) {
                try {
                    current.onStart(info);
                } catch (Throwable ignored) {
                }
                try {
                    next.onStart(info);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onSuccess(TaskInfo info, Duration duration) {
                try {
                    current.onSuccess(info, duration);
                } catch (Throwable ignored) {
                }
                try {
                    next.onSuccess(info, duration);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onFailure(TaskInfo info, Throwable error, Duration duration) {
                try {
                    current.onFailure(info, error, duration);
                } catch (Throwable ignored) {
                }
                try {
                    next.onFailure(info, error, duration);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onCancel(TaskInfo info, Duration duration) {
                try {
                    current.onCancel(info, duration);
                } catch (Throwable ignored) {
                }
                try {
                    next.onCancel(info, duration);
                } catch (Throwable ignored) {
                }
            }
        };
    }
}
