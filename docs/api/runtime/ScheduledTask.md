# Runtime / ScheduledTask

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
