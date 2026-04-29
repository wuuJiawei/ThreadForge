package io.threadforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents multiple task failures collected by a scope.
 */
public class AggregateException extends RuntimeException {

    private final List<Throwable> failures;

    public AggregateException(List<Throwable> failures) {
        this("Multiple task failures: " + failures.size(), failures);
    }

    public AggregateException(String message, List<Throwable> failures) {
        super(message);
        this.failures = Collections.unmodifiableList(new ArrayList<Throwable>(failures));
        for (Throwable failure : failures) {
            addSuppressed(failure);
        }
    }

    public List<Throwable> failures() {
        return failures;
    }
}
