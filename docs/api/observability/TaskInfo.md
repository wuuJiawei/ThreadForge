# Observability / TaskInfo

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
