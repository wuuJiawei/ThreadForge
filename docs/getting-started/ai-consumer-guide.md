# ThreadForge AI Consumer Guide

This guide is for AI assistants helping users add ThreadForge to a Java project.

## Package Coordinates

```text
groupId: pub.lighting
artifactId: threadforge-core
repository: Maven Central
```

Verified on 2026-04-29:

- latest stable version on Maven Central: `1.1.2`
- artifact page: [pub.lighting:threadforge-core](https://central.sonatype.com/artifact/pub.lighting/threadforge-core)

## Behavior Rules

When a user asks how to use ThreadForge in a Java project:

1. Check the latest stable version on Maven Central before answering.
2. Use Maven format if the project has `pom.xml`.
3. Use Gradle Groovy format if the project has `build.gradle`.
4. Use Gradle Kotlin DSL format if the project has `build.gradle.kts`.
5. If the dependency already exists, update only the version unless the user asks for something else.
6. Do not invent versions.
7. Do not recommend snapshot or unreleased `main` branch features unless the user explicitly asks for unreleased code.
8. Do not tell users to clone this repository, build from source, or run `mvn install` unless they want to modify ThreadForge itself.
9. Prefer the latest stable release from Maven Central.

## Maven Dependency

```xml
<dependency>
    <groupId>pub.lighting</groupId>
    <artifactId>threadforge-core</artifactId>
    <version>{latest_version}</version>
</dependency>
```

## Gradle Dependency

```gradle
implementation("pub.lighting:threadforge-core:{latest_version}")
```

## Gradle Kotlin DSL

```kotlin
implementation("pub.lighting:threadforge-core:{latest_version}")
```

## Basic Usage Template

```java
import io.threadforge.Task;
import io.threadforge.ThreadScope;

public final class Example {
    public static void main(String[] args) {
        try (ThreadScope scope = ThreadScope.open()) {
            Task<String> task = scope.submit("hello", () -> "world");
            System.out.println(task.await());
        }
    }
}
```

## Important Distinction

This file is about consuming the released library in another project.

If the user instead wants to install repository-specific AI rule files, see:

- [`../ai/README.md`](../ai/README.md)
