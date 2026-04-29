---
name: threadforge
description: "Use when working with ThreadForge, a structured concurrency framework for Java 8+. Covers ThreadScope, Task, FailurePolicy, RetryPolicy, Context propagation, Channel, scheduling, and observability. Triggers: structured concurrency, ThreadScope, parallel task execution, Java concurrent programming, scope-based task management, virtual threads, task cancellation, retry policy, producer-consumer channel."
---

# ThreadForge

## Overview

ThreadForge is a structured concurrency framework for Java that reduces cognitive overhead in concurrent programming. All tasks belong to a `ThreadScope` with bounded lifecycle, automatic cancellation, unified failure semantics, and built-in observability.

- Package: `io.threadforge`
- Minimum Java: 8 (JDK 21+ uses virtual threads automatically)
- groupId: `pub.lighting`, artifactId: `threadforge-core`

## Installation

Maven:

```xml
<dependency>
    <groupId>pub.lighting</groupId>
    <artifactId>threadforge-core</artifactId>
    <version>1.1.2</version>
</dependency>
```

Gradle:

```groovy
implementation("pub.lighting:threadforge-core:1.1.2")
```

## Import List

```java
import io.threadforge.ThreadScope;
import io.threadforge.Task;
import io.threadforge.Task.State;
import io.threadforge.Outcome;
import io.threadforge.ScopeJoiner;
import io.threadforge.Scheduler;
import io.threadforge.DelayScheduler;
import io.threadforge.ScheduledTask;
import io.threadforge.Channel;
import io.threadforge.FailurePolicy;
import io.threadforge.JoinStrategy;
import io.threadforge.RetryPolicy;
import io.threadforge.SlowTaskEvent;
import io.threadforge.SlowTaskHook;
import io.threadforge.TaskPriority;
import io.threadforge.Context;
import io.threadforge.CancellationToken;
import io.threadforge.ThreadHook;
import io.threadforge.TaskInfo;
import io.threadforge.ScopeMetricsSnapshot;
import io.threadforge.CancelledException;
import io.threadforge.ScopeTimeoutException;
import io.threadforge.TaskTimeoutException;
import io.threadforge.AggregateException;
import io.threadforge.TaskExecutionException;
import io.threadforge.ChannelClosedException;
```

## Core Pattern

Every ThreadForge usage follows this pattern:

1. Open a `ThreadScope` (try-with-resources)
2. Configure the scope (optional, before first submit)
3. Submit tasks
4. Await results
5. Scope auto-closes, cancelling unfinished tasks

```java
try (ThreadScope scope = ThreadScope.open()) {
    Task<String> task = scope.submit("load-user", () -> fetchUser());
    String user = task.await();
}
```

## API Quick Reference

### ThreadScope Configuration

```java
ThreadScope.open()
    .withScheduler(Scheduler.fixed(8))           // or Scheduler.priority(4), Scheduler.detect()
    .withFailurePolicy(FailurePolicy.FAIL_FAST)   // default
    .withRetryPolicy(RetryPolicy.noRetry())       // default
    .withDefaultTaskPriority(TaskPriority.NORMAL) // default
    .withConcurrencyLimit(32)                     // bound parallel tasks
    .withDeadline(Duration.ofSeconds(30))         // default 30s
    .withOpenTelemetry()                          // optional OTel tracing
    .withHook(new ThreadHook() { /* ... */ });    // optional lifecycle callbacks
```

**Critical rule**: `with*` methods MUST be called before the first `submit()` or `schedule()` call. After that, configuration is locked.

### Task Submission

```java
// Basic
Task<T> submit(Callable<T> callable)
Task<T> submit(String name, Callable<T> callable)

// With priority (requires Scheduler.priority)
Task<T> submit(String name, Callable<T> callable, TaskPriority priority)

// With per-task timeout
Task<T> submit(String name, Callable<T> callable, Duration timeout)

// With retry override
Task<T> submit(String name, Callable<T> callable, RetryPolicy retryPolicy)

// Combined: retry + timeout
Task<T> submit(String name, Callable<T> callable, RetryPolicy retryPolicy, Duration timeout)
```

### Awaiting Results

```java
// Single task
T value = task.await();

// Multiple tasks — returns Outcome summary
Outcome outcome = scope.await(task1, task2, task3);
Outcome outcome = scope.await(collectionOfTasks);

// Collect all successful results
List<T> results = scope.awaitAll(task1, task2, task3);
List<T> results = scope.awaitAll(collectionOfTasks);
```

### Higher-Order Joiners

```java
// Strategy-driven
String winner = scope.join(
    JoinStrategy.<String>firstSuccess(),
    callA,
    callB,
    callC
);

List<String> quorum = scope.join(
    JoinStrategy.<String>quorum(2),
    replicaA,
    replicaB,
    replicaC
);

// Convenience entry point
ScopeJoiner joiner = scope.joiner();
String hedged = joiner.hedged(Duration.ofMillis(50), primaryCall, backupCall);
```

Joiner rules:

- `firstSuccess` returns the first successful value and cancels unfinished sibling tasks
- `quorum(n)` returns the first `n` successful results and cancels unfinished sibling tasks once quorum is reached
- `hedged(delay, primary, backup...)` starts the first callable immediately and releases backup callables after the hedge delay
- if no successful result can be produced, joiner throws `AggregateException` or `CancelledException`
- joiners do not create a new scope; they submit tasks into the current `ThreadScope`

### FailurePolicy

| Policy | Behavior | Use Case |
|---|---|---|
| `FAIL_FAST` | First failure throws, cancel remaining | Default; fail-fast pipelines |
| `COLLECT_ALL` | Wait all, throw `AggregateException` if any failed | Batch jobs needing all results |
| `SUPERVISOR` | Never auto-cancel; failures in `Outcome` | Independent tasks, no cascading |
| `CANCEL_OTHERS` | Cancel siblings on failure, don't throw | Fan-out with graceful degradation |
| `IGNORE_ALL` | Ignore failures, return Outcome without them | Best-effort processing |

### RetryPolicy

```java
RetryPolicy.noRetry()                                          // Default: execute once
RetryPolicy.attempts(3)                                        // 3 attempts, no delay
RetryPolicy.fixedDelay(3, Duration.ofMillis(50))               // Fixed 50ms between retries
RetryPolicy.exponentialBackoff(3, initial, 2.0, maxDelay)     // Exponential backoff
RetryPolicy.builder()                                          // Full customization
    .maxAttempts(5)
    .retryIf((attempt, error) -> !(error instanceof Error))
    .backoff((attempt, error) -> Duration.ofMillis(100L * attempt))
    .build();
```

Note: `maxAttempts` includes the first attempt. CancelledException and Error are never retried by default.

### Scheduler

```java
Scheduler.detect()              // Auto: virtual threads on JDK 21+, common pool otherwise
Scheduler.commonPool()          // ForkJoinPool.commonPool(), scope does NOT own
Scheduler.fixed(8)              // Fixed thread pool, scope OWNS and closes
Scheduler.priority(4)           // Priority queue pool, scope OWNS
Scheduler.virtualThreads()      // Explicit virtual threads, scope does NOT own
Scheduler.from(executorService) // Wrap external executor, scope does NOT own
```

### Context Propagation

ThreadForge automatically captures `Context` at submit time and restores it in the task thread. After task completes, original thread context is restored.

```java
Context.put("traceId", "req-1001");

try (ThreadScope scope = ThreadScope.open()) {
    Task<String> task = scope.submit(() -> {
        String traceId = Context.get("traceId"); // "req-1001"
        return process(traceId);
    });
}

// API
Context.put(String key, Object value)
<T> T Context.get(String key)
Context.remove(String key)
Context.clear()
Map<String, Object> Context.snapshot()
```

### Channel (Producer-Consumer)

```java
Channel<T> channel = Channel.bounded(capacity);
channel.send(value);     // Blocks when buffer full
T value = channel.receive(); // Blocks when empty
channel.close();         // Signal no more sends
// Iterable: for (T v : channel) { ... } stops after closed and drained
```

### Scheduling

```java
// One-shot delay
ScheduledTask t = scope.schedule(Duration.ofMillis(200), () -> doOnce());

// Fixed rate
ScheduledTask heartbeat = scope.scheduleAtFixedRate(
    Duration.ZERO, Duration.ofSeconds(5), () -> reportHeartbeat());

// Fixed delay (wait after previous completes)
ScheduledTask poll = scope.scheduleWithFixedDelay(
    Duration.ZERO, Duration.ofSeconds(10), () -> poll());

// Cancel any scheduled task
t.cancel();
```

### Task Composition

```java
Task<Integer> base = scope.submit(() -> 21);

Integer result = base
    .thenApply(v -> v * 2)                              // Map success
    .thenCompose(v -> CompletableFuture.completedFuture(v + 1))  // FlatMap
    .exceptionally(err -> 0)                            // Fallback on error
    .join();                                            // Get result (returns CompletableFuture)
```

### Observability

```java
// Lifecycle hooks
ThreadHook hook = new ThreadHook() {
    @Override public void onStart(TaskInfo info) {}
    @Override public void onSuccess(TaskInfo info, Duration duration) {}
    @Override public void onFailure(TaskInfo info, Throwable error, Duration duration) {}
    @Override public void onCancel(TaskInfo info, Duration duration) {}
};

// Compose multiple hooks
ThreadHook combined = hook.andThen(
    SlowTaskHook.create(Duration.ofMillis(200), event -> {
        System.out.println("slow: " + event.info().name());
    })
);

ThreadScope scope = ThreadScope.open().withHook(combined);

// Metrics snapshot
ScopeMetricsSnapshot snapshot = scope.metrics();
snapshot.started()         // long
snapshot.succeeded()       // long
snapshot.failed()          // long
snapshot.cancelled()       // long
snapshot.completed()       // long (succeeded + failed + cancelled)
snapshot.totalDuration()   // Duration
snapshot.averageDuration() // Duration
snapshot.maxDuration()     // Duration
```

Slow-task event API:

```java
SlowTaskHook.create(Duration.ofMillis(200), event -> {
    TaskInfo info = event.info();
    Task.State state = event.state();
    Duration duration = event.duration();
    Throwable error = event.error();
});
```

### Cleanup Callbacks

```java
scope.defer(() -> resource.close()); // Runs when scope closes, even on failure
```

### Exceptions

| Exception | Trigger |
|---|---|
| `CancelledException` | Task cancelled or interrupted |
| `ScopeTimeoutException` | Scope deadline exceeded |
| `TaskTimeoutException` | Per-task timeout exceeded |
| `AggregateException` | `COLLECT_ALL` with multiple failures |
| `TaskExecutionException` | Wraps checked exceptions from tasks |
| `ChannelClosedException` | Send/receive on closed channel |

## Common Patterns

### 1. Concurrent RPC Aggregation

```java
try (ThreadScope scope = ThreadScope.open()) {
    Task<User> user = scope.submit("user", () -> userRpc(id));
    Task<List<Order>> orders = scope.submit("orders", () -> ordersRpc(id));
    scope.await(user, orders);
    return buildProfile(user.await(), orders.await());
}
```

### 2. Fail-Fast with Timeout

```java
try (ThreadScope scope = ThreadScope.open()
    .withDeadline(Duration.ofMillis(200))) {
    Task<String> a = scope.submit("rpc-a", () -> rpcA());
    Task<String> b = scope.submit("rpc-b", () -> rpcB());
    scope.await(a, b);
} catch (ScopeTimeoutException e) {
    fallback();
}
```

### 3. Per-Task Timeout (Independent)

```java
try (ThreadScope scope = ThreadScope.open()
    .withFailurePolicy(FailurePolicy.SUPERVISOR)) {
    Task<Integer> slow = scope.submit("slow-rpc", () -> callSlowRpc(), Duration.ofMillis(150));
    Outcome outcome = scope.await(slow);
    if (outcome.hasFailures()) {
        outcome.failures().forEach(err -> {
            if (err instanceof TaskTimeoutException) handleTimeout();
        });
    }
}
```

### 4. Batch Processing with Concurrency Limit

```java
try (ThreadScope scope = ThreadScope.open().withConcurrencyLimit(50)) {
    List<Task<Result>> tasks = new ArrayList<>();
    for (int id : ids) {
        tasks.add(scope.submit("process-" + id, () -> externalApiCall(id)));
    }
    List<Result> results = scope.awaitAll(tasks);
}
```

### 5. Retry with Backoff

```java
try (ThreadScope scope = ThreadScope.open()
    .withRetryPolicy(RetryPolicy.exponentialBackoff(
        4, Duration.ofMillis(100), 2.0, Duration.ofSeconds(5)))) {
    Task<String> task = scope.submit("flaky-rpc", () -> callRemote());
    scope.await(task);
}
```

### 6. Producer-Consumer with Channel

```java
try (ThreadScope scope = ThreadScope.open()) {
    Channel<String> ch = Channel.bounded(128);
    scope.submit("producer", () -> {
        for (String item : source) { ch.send(item); }
        ch.close();
        return null;
    });
    Task<Integer> count = scope.submit("consumer", () -> {
        int total = 0;
        for (String v : ch) { total += process(v); }
        return total;
    });
    return count.await();
}
```

### 7. OpenTelemetry Tracing

```java
try (ThreadScope scope = ThreadScope.open()
    .withOpenTelemetry("io.myapp")) {
    Task<String> task = scope.submit("rpc-a", () -> callRemote());
    task.await();
}
// Requires OpenTelemetry API on classpath
```

## Common Mistakes

| Mistake | Fix |
|---|---|
| Calling `with*` after `submit()` | All configuration must happen before first task submission |
| Using `task.await()` outside scope | Will block forever or throw if scope is closed |
| Not closing scope | Always use try-with-resources: `try (ThreadScope scope = ...)` |
| Ignoring `Outcome.hasFailures()` | Check outcome after `scope.await()` with `SUPERVISOR`/`COLLECT_ALL` |
| Using `Scheduler.priority()` without `TaskPriority` | Tasks default to `NORMAL`; works fine but no priority differentiation |
| Sending to closed Channel | `ChannelClosedException` thrown; check `channel.close()` timing |
| Confusing `maxAttempts` with retry count | `maxAttempts(3)` = 1 initial + 2 retries = 3 total executions |
| Catching `ScopeTimeoutException` with `FAIL_FAST` | `FAIL_FAST` throws the task failure, not timeout; use `withDeadline` separately |
| Inventing roadmap-only APIs beyond shipped joiners | Use only `JoinStrategy` / `ScopeJoiner` methods that already exist in the codebase |

## Migration from java.util.concurrent

| From | To |
|---|---|
| `ExecutorService.submit()` | `scope.submit()` |
| `CompletableFuture.get()` | `task.await()` |
| `CompletableFuture.allOf()` | `scope.await(tasks)` / `scope.awaitAll(tasks)` |
| `ExecutorService.shutdownNow()` | `scope.close()` (try-with-resources) |
| `ThreadLocal` manual propagation | `Context.put/get` (auto-propagated) |
| Custom retry loops | `RetryPolicy` + `scope.withRetryPolicy()` |
| Manual timeout management | `scope.withDeadline()` or per-task `Duration` timeout |
