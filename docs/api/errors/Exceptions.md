# Errors / Exceptions

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


ThreadForge 的公开异常类型如下。

## `CancelledException extends RuntimeException`

取消语义异常。

触发场景：

- 任务运行中断
- token 已取消后执行 `throwIfCancelled()`
- `Task.await()` 等待期间被取消

## `ScopeTimeoutException extends RuntimeException`

作用域截止时间超时。

触发场景：

- `ThreadScope.await(...)` 超过 deadline
- 并发许可等待超过 deadline

## `TaskTimeoutException extends RuntimeException`

任务级超时异常。

触发场景：

- 使用 `submit(..., Duration timeout)` 或 `submit(..., RetryPolicy, Duration timeout)` 提交任务
- 该任务执行超过自身 timeout

## `AggregateException extends RuntimeException`

批量失败聚合异常。

触发场景：

- `FailurePolicy.COLLECT_ALL` 且存在失败任务

方法：

- `List<Throwable> failures()`：失败列表（只读）

## `TaskExecutionException extends RuntimeException`

`Task.await()` 遇到受检异常时的包装异常。

- `getCause()` 保留原始 checked exception

## `ChannelClosedException extends RuntimeException`

通道关闭语义异常。

触发场景：

- 已关闭通道继续 `send`
- 关闭且耗尽后继续 `receive`

## 异常处理建议

- 聚合场景优先在 `ThreadScope.await(...)` 边界处理
- `CancelledException` 一般视为流程控制，不当做业务错误
- `ScopeTimeoutException` 需要配套降级逻辑
- `TaskTimeoutException` 建议按任务维度做重试/熔断统计
- `AggregateException` 建议遍历 `failures()` 做分类处理
