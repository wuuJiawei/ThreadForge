package io.threadforge;

/**
 * Raised when a task or scope is cancelled.
 */
public class CancelledException extends RuntimeException {

    public CancelledException(String message) {
        super(message);
    }

    public CancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
