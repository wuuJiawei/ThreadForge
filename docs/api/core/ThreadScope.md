# Core / ThreadScope

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`ThreadScope` 是 ThreadForge 的核心入口，负责任务提交、并发控制、失败传播、超时取消、资源清理。

- 类型：`public final class ThreadScope implements AutoCloseable`
- 线程安全：提交和等待可并发调用；`with*` 配置方法只能在首次提交/调度前调用

## 快速示例

```java
import io.threadforge.FailurePolicy;
import io.threadforge.Task;
import io.threadforge.ThreadScope;

import java.time.Duration;

try (ThreadScope scope = ThreadScope.open()
    .withFailurePolicy(FailurePolicy.FAIL_FAST)
    .withConcurrencyLimit(32)
    .withDeadline(Duration.ofSeconds(2))) {

    Task<Integer> a = scope.submit("a", () -> 1);
    Task<Integer> b = scope.submit("b", () -> 2);

    scope.await(a, b);
    int sum = a.await() + b.await();
}
```

## 默认行为

- `scheduler = Scheduler.detect()`
- `failurePolicy = FailurePolicy.FAIL_FAST`
- `deadline = Duration.ofSeconds(30)`
- 作用域关闭时自动取消未完成任务和计划任务

## API 清单

### `static ThreadScope open()`

创建新的作用域。

### `ThreadScope withScheduler(Scheduler scheduler)`

设置任务执行策略。

- 参数：`scheduler != null`
- 返回：当前 scope（链式调用）
- 异常：
  - `NullPointerException`：`scheduler` 为 `null`
  - `IllegalStateException`：配置已锁定（首次提交/调度后）

### `ThreadScope withFailurePolicy(FailurePolicy failurePolicy)`

设置等待阶段的失败策略。

- 参数：`failurePolicy != null`
- 异常：同上（`NullPointerException` / `IllegalStateException`）

### `ThreadScope withConcurrencyLimit(int limit)`

设置并发上限（基于 `Semaphore`）。

- 参数：`limit > 0`
- 语义：超过上限时，后续 `submit` 会阻塞等待许可
- 异常：
  - `IllegalArgumentException`：`limit <= 0`
  - `IllegalStateException`：配置已锁定

### `ThreadScope withDeadline(Duration deadline)`

设置作用域截止时间。

- 参数：`deadline > 0`
- 语义：
  - 到期会触发 scope 取消
  - `await`/`submit` 可能抛 `ScopeTimeoutException`
- 异常：
  - `NullPointerException`：`deadline == null`
  - `IllegalArgumentException`：`deadline <= 0`
  - `IllegalStateException`：配置已锁定

### `ThreadScope withHook(ThreadHook hook)`

设置任务生命周期回调。

- 参数：`hook != null`
- 异常：`NullPointerException` / `IllegalStateException`

### `Scheduler scheduler()`

获取当前调度策略。

### `FailurePolicy failurePolicy()`

获取当前失败策略。

### `Duration deadline()`

获取当前截止时间。

### `CancellationToken token()`

获取 scope 的取消令牌。

### `ScopeMetricsSnapshot metrics()`

获取当前作用域的内置指标快照。

- 语义：
  - 默认启用，无需额外配置
  - 只返回快照，不阻塞任务执行线程
  - 与 `ThreadHook` 可并存，互不替代

### `void defer(Runnable cleanup)`

注册关闭时的清理动作。

- 执行时机：`close()` 阶段
- 执行顺序：后注册先执行（LIFO）
- 异常：
  - `NullPointerException`：`cleanup == null`
  - `IllegalStateException`：scope 已关闭

### `<T> Task<T> submit(Callable<T> callable)`

提交匿名任务，自动命名为 `task-<id>`。

### `<T> Task<T> submit(String name, Callable<T> callable)`

提交具名任务。

- 参数：`name != null` 且 `callable != null`
- 返回：任务句柄 `Task<T>`
- 语义：
  - 首次提交后锁定所有 `with*` 配置
  - 若设置并发上限，提交线程可能阻塞等待许可
  - 如果等待许可过程中超时，会抛 `ScopeTimeoutException`
  - 如果等待许可过程中被取消，会抛 `CancelledException`

### `Outcome await(Collection<? extends Task<?>> tasks)`

等待任务集合完成并按策略处理失败。

- 参数：`tasks != null`
- 返回：`Outcome`
- 空集合：返回 `Outcome(total=0, succeeded=0, cancelled=0, failures=[])`
- 典型异常：
  - `ScopeTimeoutException`：截止时间到达
  - `RuntimeException`：`FAIL_FAST` 下的首个失败
  - `AggregateException`：`COLLECT_ALL` 下有失败

### `Outcome await(Task<?> first, Task<?>... rest)`

`await(Collection)` 的可变参数重载。

### `<T> List<T> awaitAll(Collection<? extends Task<T>> tasks)`

等待同类型任务并返回结果列表。

- 返回顺序：与输入顺序一致
- 失败/取消项：返回 `null`
- 返回集合：不可变列表

### `<T> List<T> awaitAll(Task<T> first, Task<T>... rest)`

`awaitAll(Collection)` 的可变参数重载。

### `<T> ScheduledTask schedule(Duration delay, Callable<T> callable)`

延迟执行一次（可返回值版本）。

- 语义：执行前先检查 `token.throwIfCancelled()`

### `ScheduledTask schedule(Duration delay, Runnable runnable)`

延迟执行一次（`Runnable` 版本）。

### `ScheduledTask scheduleAtFixedRate(Duration initial, Duration period, Runnable runnable)`

固定频率周期执行。

### `ScheduledTask scheduleWithFixedDelay(Duration initial, Duration delay, Runnable runnable)`

固定延迟周期执行。

- 以上 `schedule*` 通用约束：参数均不可为 `null`，scope 需未关闭
- 首次调度同样会锁定配置
- 返回：`ScheduledTask`，可取消

### `void close()`

关闭作用域并释放资源。

关闭流程：

1. 触发 token 取消
2. 取消全部计划任务
3. 取消全部未完成任务
4. 依次执行 `defer` 清理（LIFO）
5. 取消截止时间监控任务
6. 关闭由 scope 持有的执行器

异常语义：

- `close()` 本身是幂等的
- 清理过程中的异常会聚合（通过 `addSuppressed`）后抛出
- 如果主流程已有异常（比如 try-with-resources 体内抛错），清理异常会以 suppressed 形式附加

## 失败策略速览

详见：`docs/api/control/FailurePolicy.md`

- `FAIL_FAST`：首错即抛并取消其余任务
- `COLLECT_ALL`：等待全部，失败后统一抛 `AggregateException`
- `SUPERVISOR`：不自动取消，失败写入 `Outcome`
- `CANCEL_OTHERS`：取消其余任务，不直接抛失败
- `IGNORE_ALL`：忽略失败，`Outcome.failures()` 为空
