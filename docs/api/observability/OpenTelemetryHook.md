# Observability / OpenTelemetryHook

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`OpenTelemetryHook` 是 ThreadForge 的 OpenTelemetry 生命周期追踪适配器。

- 类型：`public final class OpenTelemetryHook implements ThreadHook`
- 推荐入口：`ThreadScope.withOpenTelemetry(...)`
- 作用：为每个任务创建 span，写入成功/失败/取消状态与耗时属性

## 启用方式

### `ThreadScope withOpenTelemetry()`

- 默认 instrumentation name：`io.threadforge`

### `ThreadScope withOpenTelemetry(String instrumentationName)`

- 指定 instrumentation name

## 依赖要求

运行时 classpath 需包含 OpenTelemetry API，例如：

```xml
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-api</artifactId>
  <version>${otel.version}</version>
</dependency>
```

若未包含依赖，启用时会快速失败并抛 `IllegalStateException`。

## 示例

```java
try (ThreadScope scope = ThreadScope.open()
    .withOpenTelemetry("io.threadforge.demo")) {

    Task<String> t = scope.submit("rpc-a", () -> callRemote());
    t.await();
}
```

## 与分布式上下文传播

- ThreadForge 在 `submit/schedule` 时会捕获当前 OpenTelemetry 上下文
- 任务执行前恢复父上下文，再创建任务 span
- 该机制同时适用于平台线程和虚拟线程
