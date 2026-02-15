# ThreadForge

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


ThreadForge 是一个减少多线程心智负担的结构化并发框架，目标是让并发代码更简单、更安全、更可观测。

> 先降低并发代码的认知成本，再追求吞吐与性能。

## 初衷（Why ThreadForge）

在真实业务里，Java 的各种并发模型往往过于复杂，新手上手成本太高，现有的代码经过多线程的歪歪绕绕后也难以维护。

比如下面这些显而易见的问题:

- 任务生命周期分散在多个类和线程，边界不清晰
- 失败传播、超时和取消逻辑重复且容易遗漏
- 清理动作（资源释放、回滚）分散，异常路径经常漏收口
- 观测信息（任务开始/结束/失败）不统一，排障成本高

ThreadForge 的目标是把这些分散的并发控制点收敛到一个可推理的模型里，让团队在维护并发代码时更省脑力。

## ThreadForge 如何减少心智负担

- 结构化作用域：所有任务都归属 `ThreadScope`，生命周期有边界
- 默认安全策略：默认 `FAIL_FAST` + 默认 deadline + 自动取消/清理
- 统一失败语义：通过 `FailurePolicy` 明确不同场景的失败处理方式
- 统一观测入口：通过 `ThreadHook` 和 `TaskInfo` 做生命周期埋点
- 内置低开销指标：默认聚合任务耗时与状态计数，可按需读取快照
- 跨 JDK 一致调用：JDK 21+ 优先虚拟线程，旧版本自动降级

## 设计目标

- 默认行为正确：默认失败快速传播、默认超时、自动清理资源
- 结构化生命周期：任务必须绑定在 `ThreadScope` 内
- 跨 JDK 兼容：JDK 21+ 优先虚拟线程，其它版本自动降级
- 可观测：为任务启动/成功/失败/取消提供 hook

## 当前实现状态

- 结构化作用域：`ThreadScope`
- 任务句柄：`Task<T>`
- 失败策略：`FailurePolicy`
- 失败重试策略：`RetryPolicy`
- 协作式取消：`CancellationToken`
- 有界通道：`Channel<T>`
- 调度策略：`Scheduler`
- 延迟/周期任务：`DelayScheduler` + `ScheduledTask`
- 生命周期观测：`ThreadHook` + `TaskInfo`
- 内置指标快照：`ScopeMetricsSnapshot`
- 组合式编排 API：`Task.thenApply` / `Task.thenCompose` / `Task.exceptionally`

## 快速开始

```java
try (ThreadScope scope = ThreadScope.open()) {
    Task<String> task = scope.submit("load-user", () -> "u-100");
    String user = task.await();
}
```

## 安装

Maven:

```xml
<dependency>
    <groupId>pub.lighting</groupId>
    <artifactId>threadforge-core</artifactId>
    <version>1.0.2</version>
</dependency>
```

Gradle:

```gradle
implementation("pub.lighting:threadforge-core:1.0.2")
```

## 核心 API

### ThreadScope

```java
ThreadScope.open()
    .withScheduler(Scheduler.detect())
    .withFailurePolicy(FailurePolicy.FAIL_FAST)
    .withRetryPolicy(RetryPolicy.noRetry())
    .withConcurrencyLimit(32)
    .withDeadline(Duration.ofSeconds(30))
    .withHook(hook);
```

任务提交：

```java
Task<T> submit(Callable<T> callable)
Task<T> submit(String name, Callable<T> callable)
Task<T> submit(Callable<T> callable, RetryPolicy retryPolicy)
Task<T> submit(String name, Callable<T> callable, RetryPolicy retryPolicy)
Task<T> submit(Callable<T> callable, Duration timeout)
Task<T> submit(String name, Callable<T> callable, Duration timeout)
Task<T> submit(Callable<T> callable, RetryPolicy retryPolicy, Duration timeout)
Task<T> submit(String name, Callable<T> callable, RetryPolicy retryPolicy, Duration timeout)
```

等待任务：

```java
Outcome await(Collection<? extends Task<?>> tasks)
Outcome await(Task<?> first, Task<?>... rest)

List<T> awaitAll(Collection<? extends Task<T>> tasks)
List<T> awaitAll(Task<T> first, Task<T>... rest)

ScopeMetricsSnapshot metrics()
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

### RetryPolicy

- `RetryPolicy.noRetry()`：默认行为，只执行 1 次
- `RetryPolicy.attempts(n)`：最多执行 `n` 次（不含延迟）
- `RetryPolicy.fixedDelay(n, delay)`：固定间隔重试
- `RetryPolicy.exponentialBackoff(n, initial, multiplier, max)`：指数退避

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

### ScopeMetricsSnapshot（内置指标）

```java
ScopeMetricsSnapshot snapshot = scope.metrics();

long started = snapshot.started();
long succeeded = snapshot.succeeded();
long failed = snapshot.failed();
long cancelled = snapshot.cancelled();
long completed = snapshot.completed();

Duration total = snapshot.totalDuration();
Duration avg = snapshot.averageDuration();
Duration max = snapshot.maxDuration();
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

### 5. 任务级超时（Per-Task Timeout）

```java
try (ThreadScope scope = ThreadScope.open()
    .withFailurePolicy(FailurePolicy.SUPERVISOR)) {

    Task<Integer> slow = scope.submit(
        "slow-rpc",
        () -> callSlowRpc(),
        Duration.ofMillis(150)
    );

    Outcome outcome = scope.await(slow);
    if (outcome.hasFailures() && outcome.failures().get(0) instanceof TaskTimeoutException) {
        // 只处理该任务超时，不影响其他任务继续执行
    }
}
```

### 6. 生产者-消费者

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

### 7. 延迟与周期任务

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

### 8. 组合式写法

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

### 9. 失败自动重试

```java
try (ThreadScope scope = ThreadScope.open()
    .withRetryPolicy(RetryPolicy.fixedDelay(3, Duration.ofMillis(50)))) {

    Task<String> task = scope.submit("flaky-rpc", () -> callRemote());
    scope.await(task);
    String value = task.await();
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

## API 文档与 GitHub Wiki

- 完整 API 文档（按功能目录分类）：`docs/api/README.md`
- Wiki 生成清单：`docs/api/wiki-manifest.tsv`
- 生成本地 Wiki 页面：

```bash
./scripts/generate-github-wiki.sh
```

- 发布到 GitHub Wiki：

```bash
./scripts/publish-github-wiki.sh git@github.com:<owner>/<repo>.git
```

默认会生成到 `docs/github-wiki/`，并包含 `Home.md` 与 `_Sidebar.md`。

## License

MIT，详见 `LICENSE`。
