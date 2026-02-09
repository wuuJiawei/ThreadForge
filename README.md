# ThreadForge

ThreadForge 是一个面向 Java 8+ 的结构化并发框架，目标是让并发代码更简单、更安全、更可观测。

## 当前实现状态

- 结构化作用域：`ThreadScope`
- 任务句柄：`Task<T>`
- 失败策略：`FailurePolicy`
- 协作式取消：`CancellationToken`
- 有界通道：`Channel<T>`
- 调度策略：`Scheduler`
- 延迟/周期任务：`DelayScheduler` + `ScheduledTask`
- 生命周期观测：`ThreadHook` + `TaskInfo`
- 组合式任务 API：`Task.thenApply` / `Task.thenCompose` / `Task.exceptionally`

## 设计目标

- 默认行为正确：默认失败快速传播、默认超时、自动清理资源
- 结构化生命周期：任务必须绑定在 `ThreadScope` 内
- 跨 JDK 兼容：JDK 21+ 优先虚拟线程，其它版本自动降级
- 可观测：为任务启动/成功/失败/取消提供 hook

## 快速开始

```java
try (ThreadScope scope = ThreadScope.open()) {
    Task<String> task = scope.submit("load-user", () -> "u-100");
    String user = task.await();
}
```

## 核心 API

### ThreadScope

```java
ThreadScope.open()
    .withScheduler(Scheduler.detect())
    .withFailurePolicy(FailurePolicy.FAIL_FAST)
    .withConcurrencyLimit(32)
    .withDeadline(Duration.ofSeconds(30))
    .withHook(hook);
```

任务提交：

```java
Task<T> submit(Callable<T> callable)
Task<T> submit(String name, Callable<T> callable)
```

等待任务：

```java
Outcome await(Collection<? extends Task<?>> tasks)
Outcome await(Task<?> first, Task<?>... rest)

List<T> awaitAll(Collection<? extends Task<T>> tasks)
List<T> awaitAll(Task<T> first, Task<T>... rest)
```

调度任务：

```java
ScheduledTask schedule(Duration delay, Callable<T> callable)
ScheduledTask schedule(Duration delay, Runnable runnable)
ScheduledTask scheduleAtFixedRate(Duration initial, Duration period, Runnable runnable)
ScheduledTask scheduleWithFixedDelay(Duration initial, Duration delay, Runnable runnable)
```

清理回调：

```java
scope.defer(() -> resource.close());
```

### Task

```java
long id()
String name()
Task.State state()
boolean isDone()
boolean isCancelled()
boolean isFailed()
boolean cancel()
T await()
CompletableFuture<T> toCompletableFuture()

<U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> fn)
<U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn)
CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn)
```

### FailurePolicy

- `FAIL_FAST`：首个失败直接抛出，并取消其他任务
- `COLLECT_ALL`：等待全部结束，若有失败抛 `AggregateException`
- `SUPERVISOR`：不自动取消，失败写入 `Outcome`
- `CANCEL_OTHERS`：失败后取消其余任务，不直接抛出
- `IGNORE_ALL`：忽略失败，返回不含失败的 `Outcome`

### ThreadHook

```java
.withHook(new ThreadHook() {
    @Override
    public void onStart(TaskInfo info) {}

    @Override
    public void onSuccess(TaskInfo info, Duration duration) {}

    @Override
    public void onFailure(TaskInfo info, Throwable error, Duration duration) {}

    @Override
    public void onCancel(TaskInfo info, Duration duration) {}
});
```

## 示例

### 1. 并发 RPC 聚合

```java
try (ThreadScope scope = ThreadScope.open()) {
    Task<String> user = scope.submit("user", () -> "u-100");
    Task<Integer> orders = scope.submit("orders", () -> 3);

    scope.await(user, orders);
    String profile = user.await() + ":" + orders.await();
}
```

### 2. 并发度控制

```java
try (ThreadScope scope = ThreadScope.open().withConcurrencyLimit(50)) {
    List<Task<Integer>> tasks = new ArrayList<>();
    for (int id : ids) {
        tasks.add(scope.submit(() -> externalApiCall(id)));
    }
    List<Integer> values = scope.awaitAll(tasks);
}
```

### 3. 全量收集失败

```java
try (ThreadScope scope = ThreadScope.open().withFailurePolicy(FailurePolicy.SUPERVISOR)) {
    Task<Integer> ok = scope.submit(() -> 1);
    Task<Integer> bad = scope.submit(() -> { throw new IllegalStateException("boom"); });

    Outcome outcome = scope.await(ok, bad);
    if (outcome.hasFailures()) {
        // 统一处理失败
    }
}
```

### 4. 超时取消

```java
try (ThreadScope scope = ThreadScope.open().withDeadline(Duration.ofMillis(200))) {
    Task<Integer> a = scope.submit(() -> rpcA());
    Task<Integer> b = scope.submit(() -> rpcB());
    scope.await(a, b);
} catch (ScopeTimeoutException timeout) {
    fallback();
}
```

### 5. 生产者-消费者

```java
try (ThreadScope scope = ThreadScope.open()) {
    Channel<Integer> ch = Channel.bounded(128);

    scope.submit(() -> {
        for (int i = 1; i <= 5; i++) {
            ch.send(i);
        }
        ch.close();
        return null;
    });

    Task<Integer> sum = scope.submit(() -> {
        int total = 0;
        for (Integer v : ch) {
            total += v;
        }
        return total;
    });

    Integer result = sum.await();
}
```

### 6. 延迟与周期任务

```java
try (ThreadScope scope = ThreadScope.open()) {
    scope.schedule(Duration.ofMillis(200), () -> doOnce());

    ScheduledTask heartbeat = scope.scheduleAtFixedRate(
        Duration.ofMillis(0),
        Duration.ofSeconds(5),
        () -> reportHeartbeat()
    );

    // 需要时可取消
    heartbeat.cancel();
}
```

### 7. 组合式写法

```java
try (ThreadScope scope = ThreadScope.open()) {
    Task<Integer> base = scope.submit(() -> 21);

    Integer value = base
        .thenApply(v -> v * 2)
        .thenCompose(v -> CompletableFuture.completedFuture(v + 1))
        .exceptionally(err -> 0)
        .join();
}
```

## JDK 兼容性

- JDK 21+：`Scheduler.detect()` 会优先使用虚拟线程执行器
- JDK 8-20：自动降级到通用线程池执行
- 对外 API 保持一致，无需按 JDK 分叉业务代码

## 构建与测试

```bash
mvn verify
```

项目已启用 JaCoCo 覆盖率门禁：`LINE >= 80%`。
