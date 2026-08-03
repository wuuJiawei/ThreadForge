package io.threadforge;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryPolicyValidationTest {

    @Test
    void exponentialBackoffRejectsNonFiniteAndTooSmallMultipliers() {
        assertInvalidMultiplier(Double.NaN);
        assertInvalidMultiplier(Double.POSITIVE_INFINITY);
        assertInvalidMultiplier(Double.NEGATIVE_INFINITY);
        assertInvalidMultiplier(0.999d);
    }

    @Test
    void exponentialBackoffAcceptsOneAndNormalMultipliers() {
        RetryPolicy constant = RetryPolicy.exponentialBackoff(
            3, Duration.ofMillis(10), 1.0d, Duration.ofSeconds(1));
        assertEquals(Duration.ofMillis(10), constant.nextDelay(1, new RuntimeException("x")));
        assertEquals(Duration.ofMillis(10), constant.nextDelay(2, new RuntimeException("x")));

        RetryPolicy doubling = RetryPolicy.exponentialBackoff(
            4, Duration.ofMillis(10), 2.0d, Duration.ofSeconds(1));
        assertEquals(Duration.ofMillis(10), doubling.nextDelay(1, new RuntimeException("x")));
        assertEquals(Duration.ofMillis(20), doubling.nextDelay(2, new RuntimeException("x")));
        assertEquals(Duration.ofMillis(40), doubling.nextDelay(3, new RuntimeException("x")));
    }

    @Test
    void exponentialBackoffClampsCalculationOverflowToMaxDelay() {
        Duration initial = Duration.ofSeconds(Long.MAX_VALUE / 4L);
        Duration max = Duration.ofSeconds(Long.MAX_VALUE);
        RetryPolicy policy = RetryPolicy.exponentialBackoff(3, initial, 8.0d, max);

        Duration delay = policy.nextDelay(2, new RuntimeException("x"));

        assertEquals(max, delay);
        assertFalse(delay.isNegative());
    }

    @Test
    void exponentialBackoffPreservesHugeDelayWhenMultiplierIsOne() {
        Duration initial = Duration.ofSeconds(Long.MAX_VALUE / 4L);
        Duration max = Duration.ofSeconds(Long.MAX_VALUE);
        RetryPolicy policy = RetryPolicy.exponentialBackoff(3, initial, 1.0d, max);

        assertEquals(initial, policy.nextDelay(2, new RuntimeException("x")));
    }

    @Test
    void exponentialBackoffAlwaysClampsAtMaxDelay() {
        Duration max = Duration.ofMillis(25);
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
            5, Duration.ofMillis(10), 3.0d, max);

        assertEquals(Duration.ofMillis(10), policy.nextDelay(1, new RuntimeException("x")));
        assertEquals(max, policy.nextDelay(2, new RuntimeException("x")));
        assertEquals(max, policy.nextDelay(4, new RuntimeException("x")));
    }

    private static void assertInvalidMultiplier(final double multiplier) {
        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.exponentialBackoff(
                3, Duration.ofMillis(1), multiplier, Duration.ofSeconds(1)));
    }
}
