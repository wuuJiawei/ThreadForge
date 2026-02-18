# Runtime / Scheduler

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`Scheduler` 是任务执行策略抽象，决定 `ThreadScope.submit(...)` 使用的执行器。

- 类型：`public final class Scheduler`
- 特性：不可变、线程安全

## 工厂方法

### `static Scheduler commonPool()`

使用 `ForkJoinPool.commonPool()`。

### `static Scheduler fixed(int size)`

创建固定线程池调度器。

- 参数：`size > 0`
- 线程池特性：
  - 核心线程数 = 最大线程数 = `size`
  - 队列容量 = `max(256, size * 100)`
  - 拒绝策略 = `CallerRunsPolicy`
  - 允许核心线程超时回收

### `static Scheduler priority(int size)`

创建支持任务优先级队列的固定线程池调度器。

- 参数：`size > 0`
- 线程池特性：
  - 核心线程数 = 最大线程数 = `size`
  - 队列类型 = `PriorityBlockingQueue`
  - 拒绝策略 = `CallerRunsPolicy`
- 说明：
  - 需配合 `TaskPriority` 使用
  - 同优先级按提交顺序执行

### `static Scheduler from(ExecutorService executor)`

基于外部执行器创建调度器。

- 参数：`executor != null`
- 生命周期：外部执行器由调用方管理（scope 不会关闭它）

### `static Scheduler virtualThreads()`

优先使用虚拟线程执行器。

- JDK 支持虚拟线程时：返回共享虚拟线程调度器
- 不支持时：降级为 `commonPool()`

### `static Scheduler detect()`

运行时自动探测。

- 若支持虚拟线程：等价于 `virtualThreads()`
- 否则：等价于 `commonPool()`

### `static boolean isVirtualThreadSupported()`

当前 JDK 是否支持虚拟线程执行器。

## 读取方法

### `String name()`

调度器名称，如 `commonPool`、`fixed(8)`、`priority(8)`、`external`、`virtualThreads`。

### `boolean isVirtualThreadMode()`

是否虚拟线程模式。

## 推荐用法

```java
ThreadScope scope = ThreadScope.open()
    .withScheduler(Scheduler.detect());
```

高负载限流场景：

```java
ThreadScope scope = ThreadScope.open()
    .withScheduler(Scheduler.fixed(16))
    .withConcurrencyLimit(16);
```
