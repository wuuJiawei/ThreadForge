# Control / TaskPriority

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`TaskPriority` 定义任务优先级。

- 类型：`public enum TaskPriority`
- 枚举值：`HIGH` / `NORMAL` / `LOW`

## 使用方式

### 指定调度器

```java
ThreadScope scope = ThreadScope.open()
    .withScheduler(Scheduler.priority(8));
```

### 指定默认优先级

```java
scope.withDefaultTaskPriority(TaskPriority.NORMAL);
```

### 单任务覆盖优先级

```java
Task<Integer> high = scope.submit("important", () -> work(), TaskPriority.HIGH);
```

## 语义说明

- 在 `Scheduler.priority(...)` 下，优先级越高越先出队执行
- 同优先级按提交顺序执行（FIFO）
- 在非优先级调度器下，任务仍可执行，但不保证严格优先级顺序
