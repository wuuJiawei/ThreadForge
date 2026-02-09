# Control / CancellationToken

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`CancellationToken` 表示协作式取消信号。

- 类型：`public interface CancellationToken`
- 获取方式：`ThreadScope.token()`

## 方法

### `boolean isCancelled()`

查询是否已请求取消。

### `void cancel()`

请求取消。

在 `ThreadScope` 中会联动：

- 取消未完成任务
- 取消已注册计划任务

### `void throwIfCancelled()`

若已取消，抛 `CancelledException`。

## 推荐模式

在长任务循环里主动检查：

```java
Task<Void> task = scope.submit(() -> {
    while (true) {
        scope.token().throwIfCancelled();
        doOneBatch();
    }
});
```
