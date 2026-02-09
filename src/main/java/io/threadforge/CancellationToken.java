package io.threadforge;

/**
 * Cooperative cancellation token.
 * Implementations must be thread-safe.
 */
public interface CancellationToken {

    /**
     * @return true when cancellation has been requested.
     */
    boolean isCancelled();

    /**
     * Requests cancellation.
     */
    void cancel();

    /**
     * Throws {@link CancelledException} if cancellation has been requested.
     */
    void throwIfCancelled();
}
