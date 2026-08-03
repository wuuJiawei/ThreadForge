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
- `scope.submit(name, callable)` — submit a value-returning task
- `scope.submit(name, runnable)` — submit a basic no-result task and receive `Task<Void>`
- `task.toCompletableFuture()` — observe/compose results only; use `task.cancel()` for cancellation
- `Channel.send/receive` — blocking waits are interruptible and throw `CancelledException`
- `task.await()` / `scope.await(...)` — caller interruption propagates without changing target task states
- `scope.close()` — waits for started work to exit before deferred cleanup; interruption-ignoring code keeps it blocked
- Registrations racing with `close()` either succeed and are cleaned up or fail with `IllegalStateException`
- `scope.await(tasks)` / `scope.awaitAll(tasks)` — wait for completion
- `scope.joiner().firstSuccess(...)` — return first successful result, cancel unfinished siblings
- `scope.joiner().quorum(n, ...)` — return once `n` tasks succeed
- `scope.joiner().hedged(delay, primary, backup...)` — start one task now and release backup tasks after the hedge delay
- `SlowTaskHook.create(threshold, consumer)` — emit events for tasks slower than a threshold
- ThreadLocal hooks install and restore context on the same runner thread, including timeout/cancel paths
- `hookA.andThen(hookB)` — compose multiple hooks
- `task.await()` — get single task result
- `scope.schedule(duration, callable)` — delayed execution
- `scope.scheduleAtFixedRate(initial, period, runnable)` — periodic execution

## FailurePolicy

- `FAIL_FAST` (default) — first completed failure cancels all, throws
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
- `ScopeJoiner` launches tasks inside the same `ThreadScope`; deadline, cancellation, retry, and hooks still apply
- Basic `Runnable` submissions use scope defaults; use `Callable<T>` overloads for per-task priority, retry, or timeout overrides
- `integrations/threadforge-micrometer` and `integrations/threadforge-slf4j` provide optional observability bridges without changing core semantics

## Scheduler

- `Scheduler.detect()` — auto-selects virtual threads (JDK 21+) or common pool
- `Scheduler.fixed(n)` — fixed thread pool
- `Scheduler.priority(n)` — priority-based pool (use with `TaskPriority`)
- Owned schedulers (`fixed`/`priority`) belong to one scope and close with it; use `Scheduler.from(...)` for a caller-managed executor shared across scopes
- Submissions to a shut-down scheduler are rejected instead of remaining pending
- `DelayScheduler.singleThread()` is `AutoCloseable` and owned; closing `shared()` or `from(executor)` does not close shared/external executors

## Exceptions

- `ScopeTimeoutException` — scope deadline exceeded
- `TaskTimeoutException` — per-task timeout
- `CancelledException` — task cancelled
- `AggregateException` — multiple failures (`COLLECT_ALL`)
- `TaskExecutionException` — wraps checked exceptions
- `AggregateException("No task completed successfully", ...)` or quorum-related aggregate failure can come from joiner APIs
