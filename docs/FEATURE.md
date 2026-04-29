# ThreadForge Feature Status

## Product Position

ThreadForge is a structured concurrency library for Java focused on reducing the cognitive load of concurrent programming.

Current product line:

- one published core artifact: `pub.lighting:threadforge-core`
- minimum runtime target: Java 8
- JDK 21+ automatically prefers virtual threads through `Scheduler.detect()`

## Shipped Capabilities

The following capabilities are already part of the public API in `1.1.2`:

| Area | Capability | Public API |
|---|---|---|
| Scope lifecycle | Structured task scope | `ThreadScope` |
| Task handle | Task state, await, cancel, interop | `Task<T>` |
| Failure handling | Fail-fast, supervisor, aggregate modes | `FailurePolicy` |
| Retry | Scope default retry and per-task override | `RetryPolicy` |
| Timeout | Scope deadline and per-task timeout | `withDeadline(...)`, `submit(..., Duration)` |
| Scheduling | common pool, fixed pool, priority pool, virtual threads | `Scheduler` |
| Delayed execution | one-shot and periodic scheduling | `DelayScheduler`, `ScheduledTask` |
| Priority | priority-aware execution | `TaskPriority`, `Scheduler.priority(...)` |
| Context propagation | submit-time capture and task-time restore | `Context` |
| Dataflow | bounded producer-consumer channel | `Channel<T>` |
| Observability | task lifecycle hooks and scope metrics | `ThreadHook`, `TaskInfo`, `ScopeMetricsSnapshot` |
| Tracing | optional OpenTelemetry bridge | `withOpenTelemetry(...)`, `OpenTelemetryHook` |
| Composition | basic `CompletableFuture` interop | `Task.thenApply`, `thenCompose`, `exceptionally` |

## Current Gaps

These are the main product gaps today:

- no higher-order join API for `firstSuccess`, quorum, or hedged execution
- no official `examples/` directory with runnable business-style samples
- no benchmark module for reproducible performance comparisons
- no Micrometer or MDC/SLF4J integration module yet
- no dedicated Spring Boot starter yet

## Active Next Features

The roadmap is tracked in [`docs/ROADMAP.md`](./ROADMAP.md). The next planned feature lines are:

| Priority | Branch | Theme | Planned Outcome |
|---|---|---|---|
| P0 | `feature/docs-governance-onboarding` | Docs governance + onboarding | clearer README, human install guide, AI install guide |
| P0 | `feature/scope-joiners` | Higher-order orchestration | `JoinStrategy`, `ScopeJoiner`, `firstSuccess`, `quorum`, `hedged` |
| P1 | `feature/examples-benchmarks` | Adoption assets | runnable examples and JMH baselines |
| P1 | `feature/observability-ecosystem` | Observability ecosystem | Micrometer, MDC/SLF4J bridge, slow-task diagnostics |

## Explicitly Deferred

The following areas are intentionally not part of the current phase:

- Spring Boot starter and auto-configuration
- Actuator endpoint exposure
- circuit breaker and bulkhead integrations
- reactive streams integration
- checkpoint / resume style long-task persistence

## Documentation Contract

When a public API changes, the following files must stay in sync:

- `README.md`
- `docs/api/README.md`
- `docs/ai/threadforge.SKILL.md`
- `docs/ai/threadforge.mdc`
- `docs/ai/threadforge-agents.md`
- `docs/FEATURE.md`
- `docs/ROADMAP.md`

If the change is user-visible, also update `CHANGELOG.md`.
