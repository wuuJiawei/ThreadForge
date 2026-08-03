package io.threadforge;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Objects;

/**
 * Retry policy for task execution.
 *
 * <p>A policy is immutable and can be reused across scopes/tasks.
 */
public final class RetryPolicy {

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

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
            if (Double.isNaN(multiplier) || Double.isInfinite(multiplier) || multiplier < 1.0d) {
                throw new IllegalArgumentException("multiplier must be finite and >= 1.0");
            }
            this.backoffStrategy = new BackoffStrategy() {
                @Override
                public Duration nextDelay(int attempt, Throwable failure) {
                    int exponent = Math.max(0, attempt - 1);
                    return exponentialDelay(initialDelay, multiplier, exponent, maxDelay);
                }
            };
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(maxAttempts, retryCondition, backoffStrategy);
        }
    }

    private static Duration exponentialDelay(
        Duration initialDelay,
        double multiplier,
        int exponent,
        Duration maxDelay
    ) {
        if (initialDelay.isZero()) {
            return Duration.ZERO;
        }
        if (initialDelay.compareTo(maxDelay) >= 0) {
            return maxDelay;
        }
        if (exponent == 0 || multiplier == 1.0d) {
            return initialDelay;
        }

        double factor = Math.pow(multiplier, exponent);
        if (Double.isInfinite(factor)) {
            return maxDelay;
        }

        BigDecimal computedNanos = new BigDecimal(toNanos(initialDelay))
            .multiply(BigDecimal.valueOf(factor));
        BigInteger maxNanos = toNanos(maxDelay);
        if (computedNanos.compareTo(new BigDecimal(maxNanos)) >= 0) {
            return maxDelay;
        }

        BigInteger[] secondsAndNanos = computedNanos.toBigInteger().divideAndRemainder(NANOS_PER_SECOND);
        return Duration.ofSeconds(secondsAndNanos[0].longValueExact(), secondsAndNanos[1].longValue());
    }

    private static BigInteger toNanos(Duration duration) {
        return BigInteger.valueOf(duration.getSeconds())
            .multiply(NANOS_PER_SECOND)
            .add(BigInteger.valueOf(duration.getNano()));
    }
}
