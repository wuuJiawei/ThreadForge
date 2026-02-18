package io.threadforge;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * OpenTelemetry integration hook for task lifecycle tracing.
 *
 * <p>Create via {@link #create(String)} and register with {@link ThreadScope#withOpenTelemetry()}.
 */
public final class OpenTelemetryHook implements ThreadHook {

    private final String instrumentationName;
    private final Object tracer;
    private final ConcurrentMap<Long, SpanState> spans;

    private OpenTelemetryHook(String instrumentationName, Object tracer) {
        this.instrumentationName = instrumentationName;
        this.tracer = tracer;
        this.spans = new ConcurrentHashMap<Long, SpanState>();
    }

    /**
     * Create OpenTelemetry hook with instrumentation scope name.
     */
    public static OpenTelemetryHook create(String instrumentationName) {
        Objects.requireNonNull(instrumentationName, "instrumentationName");
        if (instrumentationName.trim().isEmpty()) {
            throw new IllegalArgumentException("instrumentationName must not be blank");
        }
        if (!OpenTelemetryBridge.isAvailable()) {
            throw new IllegalStateException(
                "OpenTelemetry API not found. Add dependency: io.opentelemetry:opentelemetry-api"
            );
        }
        Object tracer = OpenTelemetryBridge.createTracer(instrumentationName);
        if (tracer == null) {
            throw new IllegalStateException("Failed to create OpenTelemetry tracer");
        }
        return new OpenTelemetryHook(instrumentationName, tracer);
    }

    /**
     * Instrumentation scope name used by this hook.
     */
    public String instrumentationName() {
        return instrumentationName;
    }

    @Override
    public void onStart(TaskInfo info) {
        Object span = OpenTelemetryBridge.startSpan(tracer, spanName(info), info);
        if (span == null) {
            return;
        }
        Object scope = OpenTelemetryBridge.spanMakeCurrent(span);
        SpanState previous = spans.put(info.taskId(), new SpanState(span, scope));
        if (previous != null) {
            finish(previous, null, null, false);
        }
    }

    @Override
    public void onSuccess(TaskInfo info, Duration duration) {
        SpanState spanState = spans.remove(info.taskId());
        finish(spanState, duration, null, false);
    }

    @Override
    public void onFailure(TaskInfo info, Throwable error, Duration duration) {
        SpanState spanState = spans.remove(info.taskId());
        finish(spanState, duration, error, false);
    }

    @Override
    public void onCancel(TaskInfo info, Duration duration) {
        SpanState spanState = spans.remove(info.taskId());
        finish(spanState, duration, null, true);
    }

    private void finish(SpanState spanState, Duration duration, Throwable error, boolean cancelled) {
        if (spanState == null) {
            return;
        }
        try {
            if (duration != null) {
                OpenTelemetryBridge.spanSetDuration(spanState.span, duration.toMillis());
            }
            if (cancelled) {
                OpenTelemetryBridge.spanSetCancelled(spanState.span, true);
            }
            if (error != null) {
                OpenTelemetryBridge.spanRecordFailure(spanState.span, error);
            }
            OpenTelemetryBridge.spanEnd(spanState.span);
        } finally {
            OpenTelemetryBridge.closeScope(spanState.scope);
        }
    }

    private String spanName(TaskInfo info) {
        return "threadforge.task " + info.name();
    }

    private static final class SpanState {
        private final Object span;
        private final Object scope;

        private SpanState(Object span, Object scope) {
            this.span = span;
            this.scope = scope;
        }
    }
}
