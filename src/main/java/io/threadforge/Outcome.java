package io.threadforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregate result of waiting for multiple tasks.
 */
public final class Outcome {

    private final int total;
    private final int succeeded;
    private final int cancelled;
    private final List<Throwable> failures;

    public Outcome(int total, int succeeded, int cancelled, List<Throwable> failures) {
        this.total = total;
        this.succeeded = succeeded;
        this.cancelled = cancelled;
        this.failures = Collections.unmodifiableList(new ArrayList<Throwable>(failures));
    }

    public int total() {
        return total;
    }

    public int succeeded() {
        return succeeded;
    }

    public int cancelled() {
        return cancelled;
    }

    public int failed() {
        return failures.size();
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    public List<Throwable> failures() {
        return failures;
    }
}
