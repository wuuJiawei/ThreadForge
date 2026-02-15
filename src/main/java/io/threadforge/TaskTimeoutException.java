package io.threadforge;

/**
 * Raised when a task exceeds its own configured timeout.
 */
public class TaskTimeoutException extends RuntimeException {

    public TaskTimeoutException(String message) {
        super(message);
    }
}
