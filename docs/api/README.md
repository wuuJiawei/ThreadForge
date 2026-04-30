# ThreadForge API 调用文档

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


本目录是 `io.threadforge` 的完整公开 API 文档，适用于开源仓库直接阅读，也可自动生成 GitHub Wiki。

如果你是第一次接触 ThreadForge，建议先读：

- 人类开发者快速上手：[`../getting-started/human-install.md`](../getting-started/human-install.md)
- AI 助手接入规则：[`../getting-started/ai-consumer-guide.md`](../getting-started/ai-consumer-guide.md)
- 功能现状：[`../FEATURE.md`](../FEATURE.md)
- 路线图：[`../ROADMAP.md`](../ROADMAP.md)

发布坐标：

- `groupId`: `pub.lighting`
- `artifactId`: `threadforge-core`
- `version`: `1.2.0`

## 文档目标

- 覆盖全部公开类型与方法（按功能目录分类）
- 描述参数约束、返回值、异常语义、线程与生命周期语义
- 提供可直接复制的调用示例
- 支持一键生成/发布 GitHub Wiki

## 目录导航

- [Core / ThreadScope](core/ThreadScope.md)
- [Core / ScopeJoiner](core/ScopeJoiner.md)
- [Core / Task](core/Task.md)
- [Core / Outcome](core/Outcome.md)
- [Runtime / Scheduler](runtime/Scheduler.md)
- [Runtime / DelayScheduler](runtime/DelayScheduler.md)
- [Runtime / ScheduledTask](runtime/ScheduledTask.md)
- [Dataflow / Channel](dataflow/Channel.md)
- [Control / FailurePolicy](control/FailurePolicy.md)
- [Control / RetryPolicy](control/RetryPolicy.md)
- [Control / JoinStrategy](control/JoinStrategy.md)
- [Control / TaskPriority](control/TaskPriority.md)
- [Control / Context](control/Context.md)
- [Control / CancellationToken](control/CancellationToken.md)
- [Observability / ThreadHook](observability/ThreadHook.md)
- [Observability / OpenTelemetryHook](observability/OpenTelemetryHook.md)
- [Observability / SlowTaskHook](observability/SlowTaskHook.md)
- [Observability / SlowTaskEvent](observability/SlowTaskEvent.md)
- [Observability / TaskInfo](observability/TaskInfo.md)
- [Observability / ScopeMetricsSnapshot](observability/ScopeMetricsSnapshot.md)
- [Errors / Exceptions](errors/Exceptions.md)
- [Cookbook / 常见调用模式](cookbook/Common-Patterns.md)

## 最小可运行示例

```java
import io.threadforge.Task;
import io.threadforge.ThreadScope;

try (ThreadScope scope = ThreadScope.open()) {
    Task<String> userTask = scope.submit("load-user", () -> "u-100");
    String user = userTask.await();
}
```

## API 设计总览

- 结构化并发入口：`ThreadScope`
- 高阶编排入口：`ScopeJoiner` + `JoinStrategy`
- 任务句柄：`Task<T>`
- 等待结果摘要：`Outcome`
- 执行器抽象：`Scheduler`
- 延迟/周期调度：`DelayScheduler` + `ScheduledTask`
- 生产者/消费者通道：`Channel<T>`
- 失败处理策略：`FailurePolicy`
- 失败重试策略：`RetryPolicy`
- 任务优先级：`TaskPriority`
- 上下文传播：`Context`
- 协作式取消：`CancellationToken`
- 生命周期观测：`ThreadHook` + `TaskInfo`
- 慢任务诊断：`SlowTaskHook` + `SlowTaskEvent`
- OpenTelemetry 追踪：`withOpenTelemetry(...)` + `OpenTelemetryHook`
- 内置指标快照：`ScopeMetricsSnapshot`
- 语义异常：`CancelledException` / `ScopeTimeoutException` / `TaskTimeoutException` / `AggregateException` / `TaskExecutionException` / `ChannelClosedException`

当前后续规划主要聚焦 Spring Boot starter / Actuator 这类框架级集成能力。

## JDK 兼容性

- JDK 21+：`Scheduler.detect()` 优先虚拟线程
- JDK 8-20：自动降级为 `ForkJoinPool.commonPool()`
- 业务代码调用方式保持一致

## Wiki 自动生成

```bash
# 1) 生成本地 Wiki 页面
./scripts/generate-github-wiki.sh

# 2) 发布到 GitHub Wiki（推送到 <repo>.wiki.git）
./scripts/publish-github-wiki.sh git@github.com:<owner>/<repo>.git
```

生成结果默认输出到：`docs/github-wiki/`
