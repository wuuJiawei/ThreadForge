package io.threadforge;

import java.time.Duration;

final class ThreadHooks {

    private ThreadHooks() {
    }

    static ThreadHook compose(final ThreadHook left, final ThreadHook right) {
        return new ThreadHook() {
            @Override
            public void onStart(TaskInfo info) {
                safeStart(left, info);
                safeStart(right, info);
            }

            @Override
            public void onSuccess(TaskInfo info, Duration duration) {
                safeSuccess(left, info, duration);
                safeSuccess(right, info, duration);
            }

            @Override
            public void onFailure(TaskInfo info, Throwable error, Duration duration) {
                safeFailure(left, info, error, duration);
                safeFailure(right, info, error, duration);
            }

            @Override
            public void onCancel(TaskInfo info, Duration duration) {
                safeCancel(left, info, duration);
                safeCancel(right, info, duration);
            }
        };
    }

    private static void safeStart(ThreadHook threadHook, TaskInfo info) {
        try {
            threadHook.onStart(info);
        } catch (Throwable ignored) {
        }
    }

    private static void safeSuccess(ThreadHook threadHook, TaskInfo info, Duration duration) {
        try {
            threadHook.onSuccess(info, duration);
        } catch (Throwable ignored) {
        }
    }

    private static void safeFailure(ThreadHook threadHook, TaskInfo info, Throwable error, Duration duration) {
        try {
            threadHook.onFailure(info, error, duration);
        } catch (Throwable ignored) {
        }
    }

    private static void safeCancel(ThreadHook threadHook, TaskInfo info, Duration duration) {
        try {
            threadHook.onCancel(info, duration);
        } catch (Throwable ignored) {
        }
    }
}
