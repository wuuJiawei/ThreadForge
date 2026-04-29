# Observability / SlowTaskEvent

`SlowTaskEvent` 是 `SlowTaskHook` 发出的不可变事件对象。

## 读取方法

- `TaskInfo info()`：任务元数据
- `Task.State state()`：结束状态
- `Duration duration()`：本次执行耗时
- `Throwable error()`：失败原因，成功/取消时通常为 `null`

## 适用场景

- 慢任务告警
- 低频诊断日志
- 与外部告警系统桥接
