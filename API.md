# ThreadForge - Java Structured Concurrency Framework

## Core Components

### 1. ThreadScope
Main entry point for structured concurrency.

```java
try (ThreadScope scope = ThreadScope.open()) {
    Task<String> task = scope.submit("my-task", () -> doWork());
    return task.await();
}
```

### 2. Task<T>
Enhanced Future with structured lifecycle.

```java
Task.State state = task.state();
task.isDone();
task.isCancelled();
task.isFailed();
T value = task.await();
task.cancel();
```

### 3. CancellationToken
Cooperative cancellation.

```java
CancellationToken token = scope.token();
while (!token.isCancelled()) {
    // work...
    token.throwIfCancelled();
}
token.cancel();
```

### 4. Channel<T>
Go-inspired producer-consumer channels.

```java
Channel<Item> ch = Channel.bounded(1024);
channel.send(item);
Item item = channel.receive();
for (Item item : channel) { process(item); }
```

### 5. Scheduler
Execution strategy abstraction.

```java
Scheduler virtualThreads = Scheduler.virtualThreads();
Scheduler fixed = Scheduler.fixed(10);
Scheduler.from(executorService);
```

### 6. FailurePolicy
Error handling strategies.

- FAIL_FAST: Cancel others, throw first exception
- COLLECT_ALL: Run all, collect all failures
- SUPERVISOR: Continue on failure, suppress exceptions
- CANCEL_OTHERS: Cancel others, suppress exceptions
- IGNORE_ALL: Silently ignore failures

### 7. ThreadHook
Observability hooks.

```java
.onStart(TaskInfo)
.onSuccess(TaskInfo, Duration)
.onFailure(TaskInfo, Throwable, Duration)
.onCancel(TaskInfo, Duration)
```

### 8. DelayScheduler
For scheduled tasks.

```java
schedule(Duration delay, Runnable)
scheduleAtFixedRate(Duration initial, Duration period, Runnable)
scheduleWithFixedDelay(Duration initial, Duration delay, Runnable)
```

## Full API Documentation

See the complete API design document at:
https://github.com/your-org/ThreadForge/wiki/API-Design

## Usage Examples

### Concurrent RPC Aggregation
```java
try (ThreadScope scope = ThreadScope.open()) {
    Task<User> user = scope.submit(() -> userClient.get(uid));
    Task<List<Order>> orders = scope.submit(() -> orderClient.list(uid));
    return scope.awaitAll(user, orders).map((u, os) -> new UserHome(u, os));
}
```

### Concurrency Limit
```java
try (ThreadScope scope = ThreadScope.open().withConcurrencyLimit(50)) {
    List<Task<Result>> tasks = ids.stream()
        .map(id -> scope.submit(() -> externalApi.call(id)))
        .toList();
    return scope.awaitAll(tasks);
}
```

### Producer-Consumer
```java
try (ThreadScope scope = ThreadScope.open()) {
    Channel<Job> ch = Channel.bounded(1024);
    scope.submit(() -> { for(Job j : jobs) ch.send(j); ch.close(); return null; });
    Task<Integer> consumer = scope.submit(() -> {
        int n = 0;
        for (Job j : ch) { process(j); n++; }
        return n;
    });
    return consumer.await();
}
```
