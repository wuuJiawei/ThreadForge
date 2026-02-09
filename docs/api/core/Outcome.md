# Core / Outcome

`Outcome` 是 `ThreadScope.await(...)` 的聚合结果对象。

- 类型：`public final class Outcome`
- 不可变：内部失败列表为只读副本

## 构造

### `Outcome(int total, int succeeded, int cancelled, List<Throwable> failures)`

通常由框架内部构造，业务侧主要做读取。

## 读取方法

### `int total()`

参与等待的任务总数。

### `int succeeded()`

成功任务数。

### `int cancelled()`

取消任务数。

### `int failed()`

失败任务数（等于 `failures().size()`）。

### `boolean hasFailures()`

是否存在失败。

### `List<Throwable> failures()`

失败明细（只读列表）。

## 语义说明

- 当 `FailurePolicy.IGNORE_ALL` 生效时，`failures()` 为空
- `SUPERVISOR/CANCEL_OTHERS` 常配合 `Outcome` 做批量容错统计
