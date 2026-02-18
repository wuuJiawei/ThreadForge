package io.threadforge.internal.otel;

import io.threadforge.TaskInfo;

/**
 * Reflection bridge for OpenTelemetry API.
 *
 * <p>This keeps threadforge-core free from hard OpenTelemetry dependency.
 */
public final class OpenTelemetryBridge {

    private OpenTelemetryBridge() {
    }

    public static boolean isAvailable() {
        return load("io.opentelemetry.context.Context") != null
            && load("io.opentelemetry.api.GlobalOpenTelemetry") != null;
    }

    public static Object currentContext() {
        Class<?> contextClass = load("io.opentelemetry.context.Context");
        if (contextClass == null) {
            return null;
        }
        return invokeStatic(contextClass, "current");
    }

    public static Object makeCurrent(Object context) {
        if (context == null) {
            return null;
        }
        return invoke(context, "makeCurrent");
    }

    public static void closeScope(Object scope) {
        if (scope == null) {
            return;
        }
        invoke(scope, "close");
    }

    public static Object createTracer(String instrumentationName) {
        Class<?> global = load("io.opentelemetry.api.GlobalOpenTelemetry");
        if (global == null) {
            return null;
        }
        return invokeStatic(global, "getTracer", new Class<?>[]{String.class}, new Object[]{instrumentationName});
    }

    public static Object startSpan(Object tracer, String spanName, TaskInfo info) {
        if (tracer == null) {
            return null;
        }
        Object builder = invoke(tracer, "spanBuilder", new Class<?>[]{String.class}, new Object[]{spanName});
        if (builder == null) {
            return null;
        }
        invoke(builder, "setAttribute", new Class<?>[]{String.class, String.class},
            new Object[]{"threadforge.scope_id", String.valueOf(info.scopeId())});
        invoke(builder, "setAttribute", new Class<?>[]{String.class, long.class},
            new Object[]{"threadforge.task_id", Long.valueOf(info.taskId())});
        invoke(builder, "setAttribute", new Class<?>[]{String.class, String.class},
            new Object[]{"threadforge.task_name", info.name()});
        invoke(builder, "setAttribute", new Class<?>[]{String.class, String.class},
            new Object[]{"threadforge.scheduler", info.schedulerName()});
        return invoke(builder, "startSpan");
    }

    public static Object spanMakeCurrent(Object span) {
        if (span == null) {
            return null;
        }
        return invoke(span, "makeCurrent");
    }

    public static void spanSetDuration(Object span, long durationMillis) {
        if (span == null) {
            return;
        }
        invoke(span, "setAttribute", new Class<?>[]{String.class, long.class},
            new Object[]{"threadforge.duration_ms", Long.valueOf(durationMillis)});
    }

    public static void spanSetCancelled(Object span, boolean cancelled) {
        if (span == null) {
            return;
        }
        invoke(span, "setAttribute", new Class<?>[]{String.class, boolean.class},
            new Object[]{"threadforge.cancelled", Boolean.valueOf(cancelled)});
    }

    public static void spanRecordFailure(Object span, Throwable error) {
        if (span == null || error == null) {
            return;
        }
        invoke(span, "recordException", new Class<?>[]{Throwable.class}, new Object[]{error});

        Class<?> statusCodeClass = load("io.opentelemetry.api.trace.StatusCode");
        if (statusCodeClass != null && statusCodeClass.isEnum()) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object errorCode = Enum.valueOf((Class<Enum>) statusCodeClass, "ERROR");
            invoke(span, "setStatus", new Class<?>[]{statusCodeClass}, new Object[]{errorCode});
        }
    }

    public static void spanEnd(Object span) {
        if (span == null) {
            return;
        }
        invoke(span, "end");
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeStatic(Class<?> type, String methodName) {
        return invokeStatic(type, methodName, new Class<?>[0], new Object[0]);
    }

    private static Object invokeStatic(Class<?> type, String methodName, Class<?>[] argTypes, Object[] args) {
        try {
            return type.getMethod(methodName, argTypes).invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] argTypes, Object[] args) {
        try {
            return target.getClass().getMethod(methodName, argTypes).invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
