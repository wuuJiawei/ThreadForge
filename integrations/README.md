# ThreadForge Integrations

Optional observability integrations live here so `threadforge-core` can stay dependency-light.

Current modules:

- [`threadforge-micrometer`](./threadforge-micrometer/): Micrometer `ThreadHook`
- [`threadforge-slf4j`](./threadforge-slf4j/): MDC / SLF4J bridge for `Context` propagation

If you are running the integrations against unpublished local changes, install the local core artifact first:

```bash
mvn -B -ntp -DskipTests install
```
