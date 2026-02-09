# Runtime / DelayScheduler

`DelayScheduler` 提供延迟和周期任务调度能力。

- 类型：`public final class DelayScheduler`
- 线程安全：可在多个 scope 间共享

## 工厂方法

### `static DelayScheduler singleThread()`

创建单线程延迟调度器。

### `static DelayScheduler shared()`

获取全局共享调度器（单线程）。

### `static DelayScheduler from(ScheduledExecutorService executor)`

基于外部 `ScheduledExecutorService` 封装。

- 参数：`executor != null`
- 生命周期：外部执行器由调用方负责关闭

## 调度方法

### `<T> ScheduledTask schedule(Duration delay, Callable<T> callable)`

延迟执行一次（可返回值）。

### `ScheduledTask schedule(Duration delay, Runnable runnable)`

延迟执行一次（无返回值）。

### `ScheduledTask scheduleAtFixedRate(Duration initial, Duration period, Runnable runnable)`

固定频率调度。

### `ScheduledTask scheduleWithFixedDelay(Duration initial, Duration delay, Runnable runnable)`

固定延迟调度。

通用约束：

- 参数不可为 `null`
- 返回值均为 `ScheduledTask`，可取消

## 与 ThreadScope 的关系

`ThreadScope` 内部默认使用 `DelayScheduler.shared()` 管理截止时间与 `schedule*` 提交；
当 scope 关闭时，会取消对应计划任务。
