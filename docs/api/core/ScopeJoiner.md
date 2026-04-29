# ScopeJoiner

`ScopeJoiner` 是 `ThreadScope` 上的高阶编排入口，用于表达“多个 callable 如何收敛结果”。

获取方式：

```java
ScopeJoiner joiner = scope.joiner();
```

也可以直接通过 `ThreadScope.join(...)` 走策略入口。

## 适用能力

- `firstSuccess`
- `quorum(n)`
- `hedged(delay, primary, backup...)`

这些能力都会把任务提交到当前 `ThreadScope` 内，因此会复用：

- scope deadline
- scope cancellation
- retry policy
- scheduler
- hook / metrics

## firstSuccess

```java
String result = scope.joiner().firstSuccess(callA, callB, callC);
```

语义：

- 并发启动全部 callable
- 第一个成功结果返回后，取消其余未完成任务
- 若没有任何任务成功，抛 `AggregateException` 或 `CancelledException`

## quorum

```java
List<String> result = scope.joiner().quorum(2, replicaA, replicaB, replicaC);
```

语义：

- 并发启动全部 callable
- 收集成功结果，达到 `n` 个后立即返回
- 达到 quorum 后取消其余未完成任务
- 如果剩余任务数已不足以满足 quorum，抛异常

## hedged

```java
String result = scope.joiner().hedged(Duration.ofMillis(50), primaryCall, backupCall);
```

语义：

- 第一个 callable 立即启动
- 备份 callable 在 hedge delay 到达后释放
- 谁先成功就返回谁，并取消其余未完成任务

当前实现中，若提供多个 backup callable，它们会在同一 hedge delay 到达后释放。

## 策略入口

```java
String winner = scope.join(
    JoinStrategy.<String>firstSuccess(),
    callA,
    callB,
    callC
);
```

若你想显式持有策略对象或在不同入口间复用策略，优先使用 `JoinStrategy`。
