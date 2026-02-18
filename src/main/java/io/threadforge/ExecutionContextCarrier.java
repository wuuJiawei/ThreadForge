package io.threadforge;

import io.threadforge.internal.otel.OpenTelemetryBridge;

import java.util.concurrent.Callable;

final class ExecutionContextCarrier {

    private final Context.Snapshot contextSnapshot;
    private final Object otelParentContext;

    private ExecutionContextCarrier(Context.Snapshot contextSnapshot, Object otelParentContext) {
        this.contextSnapshot = contextSnapshot;
        this.otelParentContext = otelParentContext;
    }

    static ExecutionContextCarrier capture() {
        return new ExecutionContextCarrier(Context.capture(), OpenTelemetryBridge.currentContext());
    }

    <T> Callable<T> wrapCallable(final Callable<T> callable, final CancellationToken token) {
        return new Callable<T>() {
            @Override
            public T call() throws Exception {
                return callInCaptured(new Callable<T>() {
                    @Override
                    public T call() throws Exception {
                        token.throwIfCancelled();
                        return callable.call();
                    }
                });
            }
        };
    }

    Runnable wrapRunnable(final Runnable runnable, final CancellationToken token) {
        return new Runnable() {
            @Override
            public void run() {
                runInCaptured(new Runnable() {
                    @Override
                    public void run() {
                        token.throwIfCancelled();
                        runnable.run();
                    }
                });
            }
        };
    }

    Runnable wrapRunnable(final Runnable runnable) {
        return new Runnable() {
            @Override
            public void run() {
                runInCaptured(runnable);
            }
        };
    }

    private <T> T callInCaptured(Callable<T> callable) throws Exception {
        Context.Snapshot previous = Context.install(contextSnapshot);
        Object scope = OpenTelemetryBridge.makeCurrent(otelParentContext);
        try {
            return callable.call();
        } finally {
            OpenTelemetryBridge.closeScope(scope);
            Context.restore(previous);
        }
    }

    private void runInCaptured(Runnable runnable) {
        Context.Snapshot previous = Context.install(contextSnapshot);
        Object scope = OpenTelemetryBridge.makeCurrent(otelParentContext);
        try {
            runnable.run();
        } finally {
            OpenTelemetryBridge.closeScope(scope);
            Context.restore(previous);
        }
    }
}
