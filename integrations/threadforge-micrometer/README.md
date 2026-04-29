# ThreadForge Micrometer Integration

Build first:

```bash
mvn -B -ntp -DskipTests install
mvn -B -ntp -f integrations/threadforge-micrometer/pom.xml compile
```

Usage:

```java
MeterRegistry registry = new SimpleMeterRegistry();

ThreadHook hook = MicrometerThreadHook.create(registry)
    .andThen(SlowTaskHook.create(Duration.ofMillis(200), event -> {
        System.out.println("slow task: " + event.info().name());
    }));

try (ThreadScope scope = ThreadScope.open().withHook(hook)) {
    scope.submit("rpc-a", () -> callRemote()).await();
}
```

Metrics emitted:

- `threadforge.task.started`
- `threadforge.task.completed`
- `threadforge.task.duration`

Low-cardinality tags:

- `scheduler`
- `state`
