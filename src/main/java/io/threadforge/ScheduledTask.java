package io.threadforge;

/**
 * Handle for scheduled jobs.
 */
public interface ScheduledTask {

    /**
     * Attempts to cancel this scheduled task.
     */
    boolean cancel();

    /**
     * @return true when cancellation has been requested.
     */
    boolean isCancelled();

    /**
     * @return true when task execution has finished or has been cancelled.
     */
    boolean isDone();
}
