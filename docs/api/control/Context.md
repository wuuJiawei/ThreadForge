# Control / Context

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`Context` 是 ThreadForge 内置的轻量级上下文容器（基于 `ThreadLocal<Map<String, Object>>`）。

- 类型：`public final class Context`
- 目标：让任务在平台线程和虚拟线程下都能以同一方式传递请求上下文（例如 traceId、tenantId）

## 自动传播规则

- 在 `ThreadScope.submit(...)` 与 `ThreadScope.schedule(...)` 时，自动捕获提交线程的 `Context`
- 任务执行前自动恢复捕获快照
- 任务执行后自动恢复线程原始上下文，防止线程池复用串值

## API 清单

### `static void put(String key, Object value)`

- 写入上下文
- 若 `value == null`，等价于 `remove(key)`

### `static <T> T get(String key)`

- 读取上下文值（返回 `null` 表示不存在）

### `static void remove(String key)`

- 删除指定 key

### `static void clear()`

- 清空当前线程上下文

### `static Map<String, Object> snapshot()`

- 返回当前线程上下文快照（只读）

## 示例

```java
Context.put("traceId", "req-1001");
Context.put("tenantId", "acme");

try (ThreadScope scope = ThreadScope.open().withScheduler(Scheduler.fixed(8))) {
    Task<String> task = scope.submit("rpc-a", () -> {
        String traceId = Context.get("traceId");
        String tenantId = Context.get("tenantId");
        return traceId + ":" + tenantId;
    });

    String value = task.await(); // req-1001:acme
}

Context.clear();
```
