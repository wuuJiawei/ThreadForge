# ThreadForge API Wiki

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


这是 ThreadForge 的完整 API 调用文档首页。

当前 Maven 坐标：`pub.lighting:threadforge-core:1.0.1`。

## 快速导航

- [[Core-ThreadScope]]
- [[Core-Task]]
- [[Core-Outcome]]
- [[Runtime-Scheduler]]
- [[Runtime-DelayScheduler]]
- [[Runtime-ScheduledTask]]
- [[Dataflow-Channel]]
- [[Control-FailurePolicy]]
- [[Control-CancellationToken]]
- [[Observability-ThreadHook]]
- [[Observability-TaskInfo]]
- [[Observability-ScopeMetricsSnapshot]]
- [[Errors-Exceptions]]
- [[Cookbook-Common-Patterns]]

## 开始使用

```java
import io.threadforge.Task;
import io.threadforge.ThreadScope;

try (ThreadScope scope = ThreadScope.open()) {
    Task<String> task = scope.submit("hello", () -> "world");
    String value = task.await();
}
```

## 默认行为

- 默认失败策略：`FailurePolicy.FAIL_FAST`
- 默认 deadline：`Duration.ofSeconds(30)`
- 默认调度器：`Scheduler.detect()`
