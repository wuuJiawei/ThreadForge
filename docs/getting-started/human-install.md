# ThreadForge Human Install Guide

This guide is for developers who want to get ThreadForge running quickly and understand the default execution model before writing larger concurrent flows.

## 1. Add the Dependency

Maven:

```xml
<dependency>
    <groupId>pub.lighting</groupId>
    <artifactId>threadforge-core</artifactId>
    <version>1.1.2</version>
</dependency>
```

Gradle:

```gradle
implementation("pub.lighting:threadforge-core:1.1.2")
```

## 2. Understand the Core Pattern

Every task belongs to a `ThreadScope`. Open a scope, submit tasks, await results, and let try-with-resources close the scope.

```java
import io.threadforge.Task;
import io.threadforge.ThreadScope;

try (ThreadScope scope = ThreadScope.open()) {
    Task<String> userTask = scope.submit("load-user", () -> "u-100");
    String user = userTask.await();
}
```

This is the minimum useful ThreadForge program.

## 3. Know the Defaults

Before you use the library in production code, know the defaults:

- default failure policy: `FAIL_FAST`
- default scope deadline: `30s`
- default retry policy: no retry
- default scheduler: `Scheduler.detect()`
- default task priority: `TaskPriority.NORMAL`

That means the first task failure will usually cancel sibling tasks and surface immediately.

## 4. First Practical Example

Use ThreadForge when you have a small group of related tasks that should succeed or fail as one unit.

```java
import io.threadforge.FailurePolicy;
import io.threadforge.Task;
import io.threadforge.ThreadScope;

import java.time.Duration;

try (ThreadScope scope = ThreadScope.open()
    .withFailurePolicy(FailurePolicy.FAIL_FAST)
    .withDeadline(Duration.ofSeconds(2))) {
    Task<String> profile = scope.submit("load-profile", () -> loadProfile());
    Task<String> orders = scope.submit("load-orders", () -> loadOrders());

    scope.await(profile, orders);
    return profile.await() + orders.await();
}
```

## 5. When ThreadForge Fits

ThreadForge is a good fit when:

- several related tasks share one lifecycle boundary
- timeout and cancellation should be centralized
- failure handling should be explicit and consistent
- you want a smaller mental model than manually wiring many `CompletableFuture`s

## 6. When It Does Not Fit

ThreadForge is not the first tool to reach for when:

- you need a reactive streaming framework
- you need a Spring Boot starter or Actuator integration today
- you only have one synchronous call and no concurrency boundary
- your task graph is already owned by another framework runtime

## 7. Common First Mistakes

- calling `with*` methods after the first `submit()` or `schedule()`
- forgetting try-with-resources around `ThreadScope`
- assuming `RetryPolicy.attempts(3)` means three retries instead of three total attempts
- ignoring `Outcome` when using `SUPERVISOR` or `IGNORE_ALL`
- treating roadmap features as shipped API

## 8. Where to Go Next

- overview and install entry: [`../../README.md`](../../README.md)
- API docs: [`../api/README.md`](../api/README.md)
- AI installation guide: [`../ai/README.md`](../ai/README.md)
- feature status: [`../FEATURE.md`](../FEATURE.md)
- roadmap: [`../ROADMAP.md`](../ROADMAP.md)
