# ThreadForge Human Install Guide

This guide is for developers who want to add ThreadForge to an existing Java project as a dependency.

## Latest Stable Release

Verified on 2026-04-30:

- repository: Maven Central
- groupId: `pub.lighting`
- artifactId: `threadforge-core`
- latest stable version: `1.2.0`

Reference:

- [Maven Central artifact page](https://central.sonatype.com/artifact/pub.lighting/threadforge-core)

## Maven

Add this to `pom.xml`:

```xml
<dependency>
    <groupId>pub.lighting</groupId>
    <artifactId>threadforge-core</artifactId>
    <version>1.2.0</version>
</dependency>
```

## Gradle

Add this to `build.gradle`:

```gradle
implementation("pub.lighting:threadforge-core:1.2.0")
```

## Gradle Kotlin DSL

Add this to `build.gradle.kts`:

```kotlin
implementation("pub.lighting:threadforge-core:1.2.0")
```

## Basic Usage

```java
import io.threadforge.Task;
import io.threadforge.ThreadScope;

public final class Example {
    public static void main(String[] args) {
        try (ThreadScope scope = ThreadScope.open()) {
            Task<String> task = scope.submit("load-user", () -> "u-100");
            System.out.println(task.await());
        }
    }
}
```

## What To Expect By Default

- default failure policy: `FAIL_FAST`
- default scope deadline: `30s`
- default retry policy: no retry
- default scheduler: `Scheduler.detect()`
- default task priority: `TaskPriority.NORMAL`

That means the first task failure usually cancels sibling tasks and surfaces immediately.

## Do Not Start With Source Build

If you only want to use ThreadForge in your own project:

- use the Maven Central dependency
- do not clone this repository
- do not run `mvn install`

Only build from source if you want to modify or contribute to ThreadForge itself.

## Where To Go Next

- overview and dependency snippets: [`../../README.md`](../../README.md)
- AI consumer guide: [`./ai-consumer-guide.md`](./ai-consumer-guide.md)
- API docs: [`../api/README.md`](../api/README.md)
- runnable examples: [`../../examples/README.md`](../../examples/README.md)
