# Changelog

All notable changes to this project are recorded in this file.

Rule: keep entries in reverse chronological order (newest first).

## [1.1.0] - 2026-02-19

1. Added `RetryPolicy` support at scope-level and per-task level, so retry behavior can be configured directly in `ThreadScope.submit(...)`.
2. Added per-task timeout (`TaskTimeoutException`) to complement existing scope deadline control.
3. Added automatic context propagation for both platform threads and virtual threads, including scheduled tasks and nested submissions.
4. Added OpenTelemetry integration (`withOpenTelemetry(...)`) for task lifecycle tracing without hard dependency on OTel at compile time.
5. Added task priority scheduling (`TaskPriority`, `Scheduler.priority(...)`), and refactored internals (`ThreadScope` slimming + internal package reorganization) while keeping public API compatibility.

## [1.0.2] - 2026-02-13

- Release maintenance update for `1.0.2`.

## [1.0.1] - 2026-02-10

- Release maintenance update for `1.0.1`.
