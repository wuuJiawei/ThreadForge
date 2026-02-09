package io.threadforge;

/**
 * Raised when task execution fails with a checked exception.
 */
public class TaskExecutionException extends RuntimeException {

    public TaskExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
