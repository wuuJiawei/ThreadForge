# Core / Task

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`Task<T>` 表示 scope 内单个任务的结构化句柄，封装 `CompletableFuture<T>`。

- 类型：`public final class Task<T>`
- 状态机：`PENDING -> RUNNING -> SUCCESS | FAILED | CANCELLED`

## 获取与等待

### `long id()`

任务 ID（scope 内自增）。

### `String name()`

任务名称。

### `Task.State state()`

当前状态（`PENDING/RUNNING/SUCCESS/FAILED/CANCELLED`）。

### `boolean isDone()`

任务是否完成（成功/失败/取消均为完成）。

### `boolean isCancelled()`

是否取消。

### `boolean isFailed()`

是否失败。

### `T await()`

阻塞等待并返回结果。

可能抛出：

- `CancelledException`：任务被取消、中断或取消传播
- `TaskTimeoutException`：任务触发任务级超时
- `TaskExecutionException`：任务以受检异常失败（checked exception）
- 原始 `RuntimeException` / `Error`：任务以非受检异常或 Error 失败

语义补充：

- 若等待线程被中断，会恢复中断标记并抛 `CancelledException`
- 成功返回后状态会标记为 `SUCCESS`

### `CompletableFuture<T> toCompletableFuture()`

暴露底层 `CompletableFuture`，便于与生态 API 互操作。

## 取消

### `boolean cancel()`

请求取消任务。

- 会将状态设为 `CANCELLED`
- 若任务正在运行，会中断运行线程
- 返回值语义与 `CompletableFuture.cancel(true)` 一致

## 组合式 API

### `<U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> fn)`

成功后做同步映射。

### `<U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn)`

成功后做异步扁平映射。

### `CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn)`

异常回退。

> 注意：组合式 API 返回 `CompletableFuture`，不是 `Task`。

## 示例

```java
Task<Integer> base = scope.submit(() -> 21);

Integer value = base
    .thenApply(v -> v * 2)
    .thenCompose(v -> java.util.concurrent.CompletableFuture.completedFuture(v + 1))
    .exceptionally(err -> 0)
    .join();
```
