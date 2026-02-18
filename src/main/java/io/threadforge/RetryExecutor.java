package io.threadforge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

final class RetryExecutor {

    private RetryExecutor() {
    }

    static <T> T execute(Callable<T> callable, RetryPolicy retryPolicy, CancellationToken token) throws Exception {
        int attempt = 1;
        List<Throwable> previousFailures = null;

        while (true) {
            token.throwIfCancelled();
            try {
                return callable.call();
            } catch (InterruptedException interruptedException) {
                throw interruptedException;
            } catch (CancelledException cancelledException) {
                throw cancelledException;
            } catch (Throwable failure) {
                if (!retryPolicy.allowsRetry(attempt, failure)) {
                    if (previousFailures != null) {
                        for (Throwable previousFailure : previousFailures) {
                            if (previousFailure != failure) {
                                failure.addSuppressed(previousFailure);
                            }
                        }
                    }
                    throw failure;
                }

                if (previousFailures == null) {
                    previousFailures = new ArrayList<Throwable>();
                }
                previousFailures.add(failure);

                sleepBeforeRetry(retryPolicy.nextDelay(attempt, failure), token);
                attempt++;
            }
        }
    }

    private static void sleepBeforeRetry(Duration delay, CancellationToken token) throws InterruptedException {
        if (delay == null || delay.isNegative() || delay.isZero()) {
            return;
        }
        long remainingMillis = delay.toMillis();
        if (remainingMillis == 0L) {
            remainingMillis = 1L;
        }
        while (remainingMillis > 0L) {
            token.throwIfCancelled();
            long chunk = Math.min(remainingMillis, 100L);
            Thread.sleep(chunk);
            remainingMillis -= chunk;
        }
    }
}
