package io.threadforge.internal;

import io.threadforge.CancelledException;
import io.threadforge.CancellationToken;

import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultCancellationToken implements CancellationToken {

    private final AtomicBoolean cancelled;
    private final Runnable onCancel;

    public DefaultCancellationToken(Runnable onCancel) {
        this.cancelled = new AtomicBoolean(false);
        this.onCancel = onCancel;
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            onCancel.run();
        }
    }

    @Override
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancelledException("Cancellation requested");
        }
    }

    public boolean markCancelledWithoutCallback() {
        return cancelled.compareAndSet(false, true);
    }
}
