package io.threadforge;

/**
 * Raised when a scope exceeds its configured deadline.
 */
public class ScopeTimeoutException extends RuntimeException {

    public ScopeTimeoutException(String message) {
        super(message);
    }
}
