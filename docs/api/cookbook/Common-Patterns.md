# Cookbook / 常见调用模式

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


## 1. 并发聚合（失败即停）

```java
try (ThreadScope scope = ThreadScope.open()
    .withFailurePolicy(FailurePolicy.FAIL_FAST)) {

    Task<String> user = scope.submit("user", () -> loadUser());
    Task<Integer> orders = scope.submit("orders", () -> loadOrders());

    scope.await(user, orders);
    String profile = user.await() + ":" + orders.await();
}
```

## 2. 弱依赖聚合（容忍部分失败）

```java
try (ThreadScope scope = ThreadScope.open()
    .withFailurePolicy(FailurePolicy.SUPERVISOR)) {

    Task<Integer> a = scope.submit(() -> rpcA());
    Task<Integer> b = scope.submit(() -> rpcB());

    Outcome outcome = scope.await(a, b);
    if (outcome.hasFailures()) {
        // 统一记录失败，但流程继续
    }
}
```

## 3. 并发上限 + 批量 awaitAll

```java
try (ThreadScope scope = ThreadScope.open()
    .withScheduler(Scheduler.fixed(8))
    .withConcurrencyLimit(8)) {

    List<Task<Integer>> tasks = new ArrayList<>();
    for (int id : ids) {
        tasks.add(scope.submit(() -> call(id)));
    }

    List<Integer> values = scope.awaitAll(tasks); // 失败项为 null
}
```

## 4. 超时保护

```java
try (ThreadScope scope = ThreadScope.open()
    .withDeadline(Duration.ofMillis(200))) {

    Task<Integer> a = scope.submit(() -> rpcA());
    Task<Integer> b = scope.submit(() -> rpcB());
    scope.await(a, b);
} catch (ScopeTimeoutException timeout) {
    fallback();
}
```

## 5. 生产者-消费者

```java
try (ThreadScope scope = ThreadScope.open()) {
    Channel<Integer> ch = Channel.bounded(64);

    scope.submit(() -> {
        for (int i = 1; i <= 100; i++) {
            ch.send(i);
        }
        ch.close();
        return null;
    });

    Task<Integer> sum = scope.submit(() -> {
        int total = 0;
        for (Integer v : ch) {
            total += v;
        }
        return total;
    });

    Integer result = sum.await();
}
```

## 6. 周期任务与取消

```java
try (ThreadScope scope = ThreadScope.open()) {
    ScheduledTask heartbeat = scope.scheduleAtFixedRate(
        Duration.ZERO,
        Duration.ofSeconds(5),
        () -> reportHeartbeat()
    );

    // ...
    heartbeat.cancel();
}
```

## 7. try-with-resources + defer 资源清理

```java
try (ThreadScope scope = ThreadScope.open()) {
    Connection conn = openConnection();
    scope.defer(() -> closeQuietly(conn));

    Task<Integer> t = scope.submit(() -> query(conn));
    t.await();
}
```
