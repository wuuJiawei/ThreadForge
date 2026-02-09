# ThreadForge API Wiki

这是 ThreadForge 的完整 API 调用文档首页。

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
