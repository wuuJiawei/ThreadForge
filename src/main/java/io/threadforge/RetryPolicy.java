package io.threadforge;

import java.time.Duration;
import java.util.Objects;

/**
 * Retry policy for task execution.
 *
 * <p>A policy is immutable and can be reused across scopes/tasks.
 */
public final class RetryPolicy {

    /**
     * Decide whether to retry after a failed attempt.
     */
    public interface RetryCondition {
        boolean shouldRetry(int attempt, Throwable failure);
    }

    /**
     * Compute delay before the next attempt.
     */
    public interface BackoffStrategy {
        Duration nextDelay(int attempt, Throwable failure);
    }

    private static final RetryCondition DEFAULT_RETRY_CONDITION = new RetryCondition() {
        @Override
        public boolean shouldRetry(int attempt, Throwable failure) {
            return !(failure instanceof CancelledException) && !(failure instanceof Error);
        }
    };

    private static final BackoffStrategy NO_BACKOFF = new BackoffStrategy() {
        @Override
        public Duration nextDelay(int attempt, Throwable failure) {
            return Duration.ZERO;
        }
    };

    private static final RetryPolicy NO_RETRY = new RetryPolicy(1, DEFAULT_RETRY_CONDITION, NO_BACKOFF);

    private final int maxAttempts;
    private final RetryCondition retryCondition;
    private final BackoffStrategy backoffStrategy;

    private RetryPolicy(int maxAttempts, RetryCondition retryCondition, BackoffStrategy backoffStrategy) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0");
        }
        this.maxAttempts = maxAttempts;
        this.retryCondition = Objects.requireNonNull(retryCondition, "retryCondition");
        this.backoffStrategy = Objects.requireNonNull(backoffStrategy, "backoffStrategy");
    }

    /**
     * No retry, execute once only.
     */
    public static RetryPolicy noRetry() {
        return NO_RETRY;
    }

    /**
     * Retry with no delay between attempts.
     */
    public static RetryPolicy attempts(int maxAttempts) {
        return builder()
            .maxAttempts(maxAttempts)
            .build();
    }

    /**
     * Retry with fixed delay between attempts.
     */
    public static RetryPolicy fixedDelay(int maxAttempts, Duration delay) {
        return builder()
            .maxAttempts(maxAttempts)
            .fixedDelay(delay)
            .build();
    }

    /**
     * Retry with exponential backoff.
     *
     * <p>Delay formula: {@code initialDelay * multiplier^(attempt-1)}, capped by {@code maxDelay}.
     */
    public static RetryPolicy exponentialBackoff(
        int maxAttempts,
        Duration initialDelay,
        double multiplier,
        Duration maxDelay
    ) {
        return builder()
            .maxAttempts(maxAttempts)
            .exponentialBackoff(initialDelay, multiplier, maxDelay)
            .build();
    }

    /**
     * Create a builder for advanced customization.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Max execution attempts (including the first attempt).
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Retry condition.
     */
    public RetryCondition retryCondition() {
        return retryCondition;
    }

    /**
     * Backoff strategy.
     */
    public BackoffStrategy backoffStrategy() {
        return backoffStrategy;
    }

    boolean allowsRetry(int attempt, Throwable failure) {
        if (attempt >= maxAttempts) {
            return false;
        }
        return retryCondition.shouldRetry(attempt, failure);
    }

    Duration nextDelay(int attempt, Throwable failure) {
        Duration delay = backoffStrategy.nextDelay(attempt, failure);
        if (delay == null || delay.isNegative()) {
            return Duration.ZERO;
        }
        return delay;
    }

    public static final class Builder {
        private int maxAttempts = 1;
        private RetryCondition retryCondition = DEFAULT_RETRY_CONDITION;
        private BackoffStrategy backoffStrategy = NO_BACKOFF;

        private Builder() {
        }

        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts <= 0) {
                throw new IllegalArgumentException("maxAttempts must be > 0");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder retryIf(RetryCondition retryCondition) {
            this.retryCondition = Objects.requireNonNull(retryCondition, "retryCondition");
            return this;
        }

        public Builder backoff(BackoffStrategy backoffStrategy) {
            this.backoffStrategy = Objects.requireNonNull(backoffStrategy, "backoffStrategy");
            return this;
        }

        public Builder fixedDelay(final Duration delay) {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must be >= 0");
            }
            this.backoffStrategy = new BackoffStrategy() {
                @Override
                public Duration nextDelay(int attempt, Throwable failure) {
                    return delay;
                }
            };
            return this;
        }

        public Builder exponentialBackoff(
            final Duration initialDelay,
            final double multiplier,
            final Duration maxDelay
        ) {
            Objects.requireNonNull(initialDelay, "initialDelay");
            Objects.requireNonNull(maxDelay, "maxDelay");
            if (initialDelay.isNegative()) {
                throw new IllegalArgumentException("initialDelay must be >= 0");
            }
            if (maxDelay.isNegative() || maxDelay.isZero()) {
                throw new IllegalArgumentException("maxDelay must be > 0");
            }
            if (multiplier < 1.0d) {
                throw new IllegalArgumentException("multiplier must be >= 1.0");
            }
            this.backoffStrategy = new BackoffStrategy() {
                @Override
                public Duration nextDelay(int attempt, Throwable failure) {
                    int exponent = Math.max(0, attempt - 1);
                    double computed = initialDelay.toNanos() * Math.pow(multiplier, exponent);
                    if (Double.isInfinite(computed) || computed >= Long.MAX_VALUE) {
                        return maxDelay;
                    }
                    long delayNanos = Math.min((long) computed, maxDelay.toNanos());
                    if (delayNanos <= 0L) {
                        return Duration.ZERO;
                    }
                    return Duration.ofNanos(delayNanos);
                }
            };
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(maxAttempts, retryCondition, backoffStrategy);
        }
    }
}
