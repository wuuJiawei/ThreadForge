package io.threadforge.examples;

import io.threadforge.FailurePolicy;
import io.threadforge.ScopeMetricsSnapshot;
import io.threadforge.Task;
import io.threadforge.ThreadScope;

import java.time.Duration;

public final class ParallelAggregationExample {

    private ParallelAggregationExample() {
    }

    public static void main(String[] args) {
        try (ThreadScope scope = ThreadScope.open()
            .withFailurePolicy(FailurePolicy.FAIL_FAST)
            .withDeadline(Duration.ofSeconds(2))) {

            Task<String> profile = scope.submit("load-profile", () -> {
                Thread.sleep(80L);
                return "profile:u-100";
            });
            Task<Integer> orders = scope.submit("load-orders", () -> {
                Thread.sleep(120L);
                return 3;
            });

            scope.await(profile, orders);

            String result = profile.await() + ",orders=" + orders.await();
            ScopeMetricsSnapshot metrics = scope.metrics();

            System.out.println("aggregation=" + result);
            System.out.println("tasksStarted=" + metrics.started() + ",tasksCompleted=" + metrics.completed());
        } catch (Exception exception) {
            throw new RuntimeException("Parallel aggregation example failed", exception);
        }
    }
}
