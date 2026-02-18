# Observability / ThreadHook

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`ThreadHook` 用于订阅任务生命周期事件。

- 类型：`public interface ThreadHook`
- 所有方法均为 `default`，可按需覆写
- 配置方式：`ThreadScope.open().withHook(hook)`
- 与内置指标关系：`ThreadScope.metrics()` 负责聚合快照，hook 负责外部系统接入
- OpenTelemetry 可直接使用：`ThreadScope.open().withOpenTelemetry(...)`

## 回调方法

### `void onStart(TaskInfo info)`

任务开始执行时触发。

### `void onSuccess(TaskInfo info, Duration duration)`

任务成功完成时触发。

### `void onFailure(TaskInfo info, Throwable error, Duration duration)`

任务失败时触发。

### `void onCancel(TaskInfo info, Duration duration)`

任务取消时触发。

## 约束与建议

- 回调运行在工作线程上下文，避免耗时阻塞
- 框架会吞掉 hook 内异常，不影响主任务流程
- 可用于埋点、日志、追踪、告警

## 示例

```java
ThreadHook hook = new ThreadHook() {
    @Override
    public void onStart(TaskInfo info) {
        System.out.println("start: " + info.name());
    }

    @Override
    public void onFailure(TaskInfo info, Throwable error, java.time.Duration duration) {
        System.err.println("fail: " + info.name() + ", cost=" + duration.toMillis());
    }
};

ThreadScope scope = ThreadScope.open().withHook(hook);
```
