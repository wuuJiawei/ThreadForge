# ThreadForge

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Changelog](https://img.shields.io/badge/Changelog-v1.1.2-0ea5e9)](./CHANGELOG.md)
[![CI](https://github.com/wuuJiawei/ThreadForge/actions/workflows/ci.yml/badge.svg)](https://github.com/wuuJiawei/ThreadForge/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)

最新版本变更可直接查看 [`CHANGELOG.md`](./CHANGELOG.md)。

ThreadForge 是一个减少多线程心智负担的结构化并发框架，目标是让并发代码更简单、更安全、更可观测。

> 先降低并发代码的认知成本，再追求吞吐与性能。

## 快速导航

- 人类开发者安装与首次上手：[`docs/getting-started/human-install.md`](./docs/getting-started/human-install.md)
- AI 助手安装与项目规则注入：[`docs/ai/README.md`](./docs/ai/README.md)
- Runnable examples：[`examples/README.md`](./examples/README.md)
- JMH benchmarks：[`benchmarks/README.md`](./benchmarks/README.md)
- Optional observability integrations：[`integrations/README.md`](./integrations/README.md)
- API 文档入口：[`docs/api/README.md`](./docs/api/README.md)
- 功能现状与缺口：[`docs/FEATURE.md`](./docs/FEATURE.md)
- 后续路线图：[`docs/ROADMAP.md`](./docs/ROADMAP.md)

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
- 自动上下文传播：`Context` 在提交/调度时自动捕获并传播
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
- 任务优先级：`TaskPriority`
- 协作式取消：`CancellationToken`
- 上下文传播：`Context`
- 有界通道：`Channel<T>`
- 调度策略：`Scheduler`
- 延迟/周期任务：`DelayScheduler` + `ScheduledTask`
- OpenTelemetry 集成：`withOpenTelemetry(...)` + `OpenTelemetryHook`
- 生命周期观测：`ThreadHook` + `TaskInfo`
- 内置指标快照：`ScopeMetricsSnapshot`
- 组合式编排 API：`Task.thenApply` / `Task.thenCompose` / `Task.exceptionally`
- `main` 分支新增高阶编排 API：`JoinStrategy` + `ScopeJoiner`（`firstSuccess` / `quorum` / `hedged`，将在下个版本发布）

## 快速开始

```java
import io.threadforge.Task;
import io.threadforge.ThreadScope;

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
    <version>1.1.2</version>
</dependency>
```

Gradle:

```gradle
implementation("pub.lighting:threadforge-core:1.1.2")
```

首次接入建议直接从 [`docs/getting-started/human-install.md`](./docs/getting-started/human-install.md) 开始，那里包含：

- 依赖安装
- 第一个可运行示例
- 什么时候该用 ThreadForge，什么时候不该用
- 默认失败、超时、取消语义说明

## 兼容性与构建

| JDK | 运行支持 | CI 验证 | 说明 |
|---|---|---|---|
| 8 | Yes | Yes | 最低支持版本，产物字节码目标保持为 Java 8 |
| 11 | Yes | Yes | 推荐的本地开发/构建基线 |
| 17 | Yes | Yes | LTS 版本兼容性验证 |
| 21 | Yes | Yes | LTS 版本兼容性验证；运行时优先虚拟线程 |

- 最低支持 Java 版本：`Java 8`
- Maven 构建默认使用 `maven-compiler-plugin` 的 `release=8`，在较新 JDK 上生成 Java 8 兼容产物
- GitHub Actions CI 会在 JDK `8 / 11 / 17 / 21` 上执行 `mvn -B -ntp clean verify`

### 本地构建

常规验证：

```bash
mvn -B -ntp clean verify
```

在指定 JDK 下验证 Java 8 兼容目标：

```bash
JAVA_HOME=/path/to/jdk8 PATH="$JAVA_HOME/bin:$PATH" mvn -B -ntp clean verify
JAVA_HOME=/path/to/jdk21 PATH="$JAVA_HOME/bin:$PATH" mvn -B -ntp clean verify
```

发布前的本地检查：

```bash
mvn -B -ntp -P release clean verify
```

## Examples And Benchmarks

- Runnable examples live in [`examples/`](./examples/)
- JMH benchmarks live in [`benchmarks/`](./benchmarks/)
- Because `main` is ahead of Maven Central `1.1.2`, install the local artifact first when running them from this repository:

```bash
mvn -B -ntp -DskipTests install
```

## Optional Integrations

Optional observability integrations now live under [`integrations/`](./integrations/):

- Micrometer: `integrations/threadforge-micrometer`
- SLF4J / MDC bridge: `integrations/threadforge-slf4j`
- slow-task detection remains part of `threadforge-core` through `SlowTaskHook`

## 核心 API

说明：

- 当前已发布的是核心结构化并发 API
- `main` 分支已经包含 `JoinStrategy` / `ScopeJoiner`
- Maven Central 当前最新版本仍是 `1.1.2`；若要使用 joiner API，请等待下个 release 或直接从源码构建

### ThreadScope

```java
ThreadScope.open()
    .withScheduler(Scheduler.priority(8))
    .withFailurePolicy(FailurePolicy.FAIL_FAST)
    .withRetryPolicy(RetryPolicy.noRetry())
    .withDefaultTaskPriority(TaskPriority.NORMAL)
    .withOpenTelemetry()
    .withConcurrencyLimit(32)
    .withDeadline(Duration.ofSeconds(30))
    .withHook(hook);
```

任务提交：

```java
Task<T> submit(Callable<T> callable)
Task<T> submit(String name, Callable<T> callable)
Task<T> submit(Callable<T> callable, TaskPriority taskPriority)
Task<T> submit(String name, Callable<T> callable, TaskPriority taskPriority)
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

优先级调度器：

```java
Scheduler.priority(int size)
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

### TaskPriority

- `TaskPriority.HIGH`
- `TaskPriority.NORMAL`
- `TaskPriority.LOW`

说明：
- 配合 `Scheduler.priority(...)` 使用时，队列会优先执行高优先级任务
- 同优先级按提交顺序执行（FIFO）
- 在非优先级调度器下可正常运行，但不保证严格优先级顺序

### Context

```java
Context.put("traceId", "req-1001");
String traceId = Context.get("traceId");
Context.remove("traceId");
Context.clear();
Map<String, Object> values = Context.snapshot();
```

- `Context` 会在 `submit/schedule` 时自动传播到任务线程（平台线程与虚拟线程都支持）
- 任务结束后会自动恢复线程原始上下文，避免线程复用导致串值

### OpenTelemetry

- `withOpenTelemetry()`：使用默认 instrumentation name `io.threadforge`
- `withOpenTelemetry("your.instrumentation.name")`：指定 instrumentation name
- 若 classpath 缺少 OpenTelemetry API，会在启用时快速失败并提示依赖

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

### 6. 上下文自动传播（Context Propagation）

```java
Context.put("traceId", "req-1001");

try (ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.fixed(4))) {
    Task<String> trace = scope.submit(() -> Context.get("traceId"));
    assert "req-1001".equals(trace.await());
}
```

### 7. 优先级队列

```java
try (ThreadScope scope = ThreadScope.open()
    .withScheduler(Scheduler.priority(4))) {

    Task<Integer> low = scope.submit("low", () -> doLow(), TaskPriority.LOW);
    Task<Integer> high = scope.submit("high", () -> doHigh(), TaskPriority.HIGH);

    scope.await(low, high);
}
```

### 8. 生产者-消费者

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

### 9. 延迟与周期任务

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

### 10. 组合式写法

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

### 11. OpenTelemetry 追踪

```java
try (ThreadScope scope = ThreadScope.open()
    .withOpenTelemetry("io.threadforge.demo")) {

    Task<String> task = scope.submit("rpc-a", () -> callRemote());
    task.await();
}
```

### 12. 失败自动重试

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

## Release 流程

- release tag 格式固定为 `vX.Y.Z`，例如 `v1.1.2`
- `release.yml` 只接受 `main` 或 `master` 历史中的 tag commit，并要求 tag 版本与 `pom.xml` 中的 `project.version` 一致
- 真实发版顺序是：先在本地执行 `mvn -B -ntp -P release clean deploy` 发布到 Maven Central，再 push 对应 tag 创建 GitHub Release
- push tag 后会自动执行：`clean verify`、生成 release notes、创建 GitHub Release 并上传 jar 资产
- 自动 release notes 基于 git commit 历史生成，分类为 `Features`、`Improvements`、`Fixes`、`Docs`、`Build / CI`
- Maven Central 发布保持本地手动执行，不经过 GitHub Actions
- 维护者操作说明见 [`docs/releases.md`](./docs/releases.md)

## 更新日志

- 完整更新记录：[`CHANGELOG.md`](./CHANGELOG.md)
- 维护者发版手册：[`docs/releases.md`](./docs/releases.md)


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
