# Observability / ScopeMetricsSnapshot

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`ScopeMetricsSnapshot` 是 `ThreadScope` 内置指标的不可变快照。

- 类型：`public final class ScopeMetricsSnapshot`
- 获取方式：`ThreadScope.metrics()`
- 目标：提供低开销的任务耗时与状态计数观测

## 指标字段

### `long started()`

已开始执行的任务数。

### `long succeeded()`

成功完成的任务数。

### `long failed()`

失败完成的任务数。

### `long cancelled()`

取消完成的任务数。

### `long completed()`

已结束任务总数（`succeeded + failed + cancelled`）。

### `Duration totalDuration()`

全部已结束任务累计运行时长。

### `Duration averageDuration()`

全部已结束任务平均运行时长；若无已结束任务返回 `Duration.ZERO`。

### `Duration maxDuration()`

单任务最大运行时长；若无已结束任务返回 `Duration.ZERO`。

## 与 ThreadHook 的关系

- 内置指标默认启用，无需配置
- `ThreadHook` 仍可用于接入日志/监控系统
- 两者可同时使用：内置指标负责聚合快照，hook 负责外部扩展

## 示例

```java
try (ThreadScope scope = ThreadScope.open()) {
    Task<Integer> a = scope.submit(() -> 1);
    Task<Integer> b = scope.submit(() -> 2);
    scope.await(a, b);

    ScopeMetricsSnapshot m = scope.metrics();
    long done = m.completed();
    Duration avg = m.averageDuration();
}
```
