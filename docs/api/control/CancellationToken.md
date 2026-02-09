# Control / CancellationToken

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
