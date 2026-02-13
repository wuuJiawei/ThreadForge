# Control / RetryPolicy

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`RetryPolicy` 定义任务失败后的重试行为。

- 类型：`public final class RetryPolicy`
- 默认：`RetryPolicy.noRetry()`（只执行 1 次）

## 常用工厂方法

### `RetryPolicy.noRetry()`

- 不重试，失败直接结束

### `RetryPolicy.attempts(int maxAttempts)`

- 最多执行 `maxAttempts` 次（包含首次执行）
- 重试间隔为 0

### `RetryPolicy.fixedDelay(int maxAttempts, Duration delay)`

- 固定间隔重试

### `RetryPolicy.exponentialBackoff(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay)`

- 指数退避重试，延迟计算为 `initialDelay * multiplier^(attempt-1)`
- 延迟不会超过 `maxDelay`

## 在 ThreadScope 中使用

```java
try (ThreadScope scope = ThreadScope.open()
    .withRetryPolicy(RetryPolicy.fixedDelay(3, Duration.ofMillis(50)))) {

    Task<String> t = scope.submit("flaky-rpc", () -> callRemote());
    scope.await(t);
    String value = t.await();
}
```

## 单任务覆盖默认策略

```java
try (ThreadScope scope = ThreadScope.open()
    .withRetryPolicy(RetryPolicy.noRetry())) {

    Task<String> t = scope.submit(
        "critical-rpc",
        () -> callRemote(),
        RetryPolicy.attempts(2)
    );
}
```

## 自定义规则（Builder）

```java
RetryPolicy policy = RetryPolicy.builder()
    .maxAttempts(4)
    .retryIf((attempt, error) -> error instanceof java.io.IOException)
    .backoff((attempt, error) -> Duration.ofMillis(attempt * 20L))
    .build();
```
