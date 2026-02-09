# Control / FailurePolicy

`FailurePolicy` 控制 `ThreadScope.await(...)` 遇到失败时的行为。

- 类型：`public enum FailurePolicy`

## 枚举值

### `FAIL_FAST`

- 行为：首个失败立即抛出，取消其余任务
- 适用：强一致聚合、任意子任务失败即整体失败

### `COLLECT_ALL`

- 行为：等待所有任务；若存在失败，抛 `AggregateException`
- 适用：批处理，需要一次拿到全部失败

### `SUPERVISOR`

- 行为：不自动取消兄弟任务；失败写入 `Outcome.failures()`
- 适用：弱依赖场景，允许部分失败

### `CANCEL_OTHERS`

- 行为：首个失败触发取消其他任务，但 `await` 本身不直接抛该失败
- 结果：失败进入 `Outcome.failures()`
- 适用：希望缩短失败后的尾部耗时，同时做统一失败统计

### `IGNORE_ALL`

- 行为：忽略失败，返回不含失败明细的 `Outcome`
- 风险：可能掩盖业务异常，建议仅用于明确可降级场景

## 行为对比

| 策略 | 是否取消其余任务 | `await` 是否抛异常 | 失败是否保留在 `Outcome` |
| --- | --- | --- | --- |
| `FAIL_FAST` | 是 | 是（首个失败） | 否 |
| `COLLECT_ALL` | 否 | 是（`AggregateException`） | 否 |
| `SUPERVISOR` | 否 | 否 | 是 |
| `CANCEL_OTHERS` | 是 | 否 | 是 |
| `IGNORE_ALL` | 视运行态 | 否 | 否 |
