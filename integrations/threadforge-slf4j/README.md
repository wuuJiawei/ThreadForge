# ThreadForge SLF4J / MDC Integration

Build first:

```bash
mvn -B -ntp -DskipTests install
mvn -B -ntp -f integrations/threadforge-slf4j/pom.xml compile
```

Usage:

```java
Context.put("traceId", "req-1001");
Context.put("tenant", "demo");

ThreadHook hook = MdcThreadHook.captureKeys("traceId", "tenant");

try (ThreadScope scope = ThreadScope.open().withHook(hook)) {
    scope.submit("rpc-a", () -> {
        logger.info("context is now visible in MDC");
        return null;
    }).await();
}
```

Behavior:

- reads values from `Context`
- installs them into MDC when the task starts
- restores the previous MDC map when the task completes
- ignores non-string `Context` values
