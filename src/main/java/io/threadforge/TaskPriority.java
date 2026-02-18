package io.threadforge;

/**
 * Task priority used by priority-capable schedulers.
 */
public enum TaskPriority {
    HIGH(0),
    NORMAL(1),
    LOW(2);

    private final int rank;

    TaskPriority(int rank) {
        this.rank = rank;
    }

    int rank() {
        return rank;
    }
}
