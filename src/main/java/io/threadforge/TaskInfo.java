package io.threadforge;

import java.time.Instant;

/**
 * Immutable metadata for a submitted task.
 */
public final class TaskInfo {

    private final long scopeId;
    private final long taskId;
    private final String name;
    private final Instant createdAt;
    private final String schedulerName;

    public TaskInfo(long scopeId, long taskId, String name, Instant createdAt, String schedulerName) {
        this.scopeId = scopeId;
        this.taskId = taskId;
        this.name = name;
        this.createdAt = createdAt;
        this.schedulerName = schedulerName;
    }

    public long scopeId() {
        return scopeId;
    }

    public long taskId() {
        return taskId;
    }

    public String name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String schedulerName() {
        return schedulerName;
    }
}
