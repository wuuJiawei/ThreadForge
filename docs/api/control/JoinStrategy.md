# JoinStrategy

`JoinStrategy<T, R>` 是 `ScopeJoiner` / `ThreadScope.join(...)` 使用的策略对象，用于声明一组 callable 如何收敛成结果。

## 工厂方法

### firstSuccess

```java
JoinStrategy<String, String> strategy = JoinStrategy.firstSuccess();
```

语义：

- 任一 callable 成功即返回
- 取消其他未完成任务

### quorum

```java
JoinStrategy<String, List<String>> strategy = JoinStrategy.quorum(2);
```

语义：

- 收集成功结果
- 达到要求的成功数后返回
- 若已不可能达到 quorum，则抛异常

约束：

- `requiredSuccesses > 0`
- `requiredSuccesses <= callable 数量`

### hedged

```java
JoinStrategy<String, String> strategy = JoinStrategy.hedged(Duration.ofMillis(50));
```

语义：

- 主请求立即发起
- 备份请求在 hedge delay 后释放
- 第一个成功结果返回

约束：

- `hedgeDelay > 0`
- 实际 join 时至少提供 2 个 callable
