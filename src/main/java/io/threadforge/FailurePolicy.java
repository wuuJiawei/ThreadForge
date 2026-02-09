package io.threadforge;

/**
 * Defines how a scope handles task failures during await operations.
 */
public enum FailurePolicy {
    /** Cancel remaining tasks and throw the first observed failure. */
    FAIL_FAST,
    /** Wait for all tasks and throw one aggregate exception if any task failed. */
    COLLECT_ALL,
    /** Never auto-cancel sibling tasks; return failures in {@link Outcome}. */
    SUPERVISOR,
    /** Cancel sibling tasks on first failure but do not throw from await. */
    CANCEL_OTHERS,
    /** Ignore task failures during await and return an outcome without failures. */
    IGNORE_ALL
}
