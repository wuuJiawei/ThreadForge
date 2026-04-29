package io.threadforge;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Value object describing a higher-order join strategy for tasks launched inside one {@link ThreadScope}.
 */
public final class JoinStrategy<T, R> {

    enum Mode {
        FIRST_SUCCESS,
        QUORUM,
        HEDGED
    }

    private final Mode mode;
    private final int requiredSuccesses;
    private final Duration hedgeDelay;

    private JoinStrategy(Mode mode, int requiredSuccesses, Duration hedgeDelay) {
        this.mode = mode;
        this.requiredSuccesses = requiredSuccesses;
        this.hedgeDelay = hedgeDelay;
    }

    /**
     * Return the first successful result and cancel unfinished sibling tasks.
     */
    public static <T> JoinStrategy<T, T> firstSuccess() {
        return new JoinStrategy<T, T>(Mode.FIRST_SUCCESS, 1, null);
    }

    /**
     * Return once {@code requiredSuccesses} successful results have been collected.
     */
    public static <T> JoinStrategy<T, List<T>> quorum(int requiredSuccesses) {
        if (requiredSuccesses <= 0) {
            throw new IllegalArgumentException("requiredSuccesses must be > 0");
        }
        return new JoinStrategy<T, List<T>>(Mode.QUORUM, requiredSuccesses, null);
    }

    /**
     * Start the first task immediately and release backup tasks after the configured hedge delay.
     */
    public static <T> JoinStrategy<T, T> hedged(Duration hedgeDelay) {
        Objects.requireNonNull(hedgeDelay, "hedgeDelay");
        if (hedgeDelay.isZero() || hedgeDelay.isNegative()) {
            throw new IllegalArgumentException("hedgeDelay must be > 0");
        }
        return new JoinStrategy<T, T>(Mode.HEDGED, 1, hedgeDelay);
    }

    public int requiredSuccesses() {
        return requiredSuccesses;
    }

    public Duration hedgeDelay() {
        return hedgeDelay;
    }

    public boolean isFirstSuccess() {
        return mode == Mode.FIRST_SUCCESS;
    }

    public boolean isQuorum() {
        return mode == Mode.QUORUM;
    }

    public boolean isHedged() {
        return mode == Mode.HEDGED;
    }

    Mode mode() {
        return mode;
    }
}
