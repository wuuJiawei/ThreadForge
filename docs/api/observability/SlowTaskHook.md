# Observability / SlowTaskHook

`SlowTaskHook` 用于在任务执行时间超过阈值时发出事件。

- 类型：`public final class SlowTaskHook`
- 创建方式：`SlowTaskHook.create(Duration threshold, Consumer<SlowTaskEvent> consumer)`
- 接入方式：`ThreadScope.open().withHook(...)`

## 典型用法

```java
ThreadHook hook = SlowTaskHook.create(Duration.ofMillis(200), event -> {
    System.out.println("slow task: " + event.info().name() + ", cost=" + event.duration().toMillis());
});

ThreadScope scope = ThreadScope.open().withHook(hook);
```

## 触发时机

- `onSuccess`：慢成功任务
- `onFailure`：慢失败任务
- `onCancel`：慢取消任务

未超过阈值的任务不会发出事件。
