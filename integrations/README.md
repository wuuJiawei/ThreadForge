# ThreadForge Integrations

Optional observability integrations live here so `threadforge-core` can stay dependency-light.

Current modules:

- [`threadforge-micrometer`](./threadforge-micrometer/): Micrometer `ThreadHook`
- [`threadforge-slf4j`](./threadforge-slf4j/): MDC / SLF4J bridge for `Context` propagation

Because the repository `main` branch is ahead of Maven Central `1.1.2`, install the local core artifact first:

```bash
mvn -B -ntp -DskipTests install
```
