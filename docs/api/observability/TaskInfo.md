# Observability / TaskInfo

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`TaskInfo` 是任务元数据快照，用于 hook 中做日志与统计。

- 类型：`public final class TaskInfo`
- 不可变对象

## 构造

### `TaskInfo(long scopeId, long taskId, String name, Instant createdAt, String schedulerName)`

通常由框架内部构造后传给 hook。

## 读取方法

### `long scopeId()`

所属 scope ID。

### `long taskId()`

任务 ID。

### `String name()`

任务名（`submit(name, ...)`）。

### `Instant createdAt()`

任务创建时间。

### `String schedulerName()`

调度器名称。
