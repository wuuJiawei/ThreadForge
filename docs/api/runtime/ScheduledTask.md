# Runtime / ScheduledTask

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`ScheduledTask` 是延迟/周期任务句柄。

- 类型：`public interface ScheduledTask`

## 方法

### `boolean cancel()`

请求取消计划任务。

### `boolean isCancelled()`

是否已取消。

### `boolean isDone()`

是否已结束（包括正常结束和取消）。

## 示例

```java
ScheduledTask heartbeat = scope.scheduleAtFixedRate(
    java.time.Duration.ZERO,
    java.time.Duration.ofSeconds(5),
    () -> reportHeartbeat()
);

// 按需停止
heartbeat.cancel();
```
