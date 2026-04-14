# ThreadForge — Structured Concurrency for Java

This project uses ThreadForge (`pub.lighting:threadforge-core`, package `io.threadforge`) for structured concurrency.

## Core Pattern

All tasks run within a `ThreadScope` using try-with-resources:

```java
try (ThreadScope scope = ThreadScope.open()) {
    Task<String> task = scope.submit("name", () -> doWork());
    String result = task.await();
}
```

## Key API

- `ThreadScope.open().withFailurePolicy().withDeadline().withScheduler().withRetryPolicy().withConcurrencyLimit()`
- `scope.submit(name, callable)` — submit a task
- `scope.await(tasks)` / `scope.awaitAll(tasks)` — wait for completion
- `task.await()` — get single task result
- `scope.schedule(duration, callable)` — delayed execution
- `scope.scheduleAtFixedRate(initial, period, runnable)` — periodic execution

## FailurePolicy

- `FAIL_FAST` (default) — first failure cancels all, throws
- `SUPERVISOR` — no auto-cancel, check `Outcome.hasFailures()`
- `COLLECT_ALL` — wait all, throw `AggregateException`
- `CANCEL_OTHERS` — cancel siblings, don't throw
- `IGNORE_ALL` — ignore failures

## Rules

- Configure scope with `with*` methods BEFORE first `submit()` — config locks after that
- Always use try-with-resources for `ThreadScope`
- Default deadline is 30 seconds — override with `.withDeadline()`
- `RetryPolicy.maxAttempts` includes the first attempt (3 = 1 initial + 2 retries)
- `Context` auto-propagates from submit thread to task thread

## Scheduler

- `Scheduler.detect()` — auto-selects virtual threads (JDK 21+) or common pool
- `Scheduler.fixed(n)` — fixed thread pool
- `Scheduler.priority(n)` — priority-based pool (use with `TaskPriority`)

## Exceptions

- `ScopeTimeoutException` — scope deadline exceeded
- `TaskTimeoutException` — per-task timeout
- `CancelledException` — task cancelled
- `AggregateException` — multiple failures (`COLLECT_ALL`)
- `TaskExecutionException` — wraps checked exceptions
