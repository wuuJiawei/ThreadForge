# ThreadForge Roadmap

> Last updated: 2026-04-29
> Planning baseline: post-`1.1.2`
> Spring Boot starter / Actuator integration: deferred for a later phase

## Roadmap Principles

- Keep `threadforge-core` small, predictable, and JDK 8+ compatible.
- One major capability track uses one dedicated `feature/*` branch.
- Public API changes must ship with tests, docs, and at least one runnable example.
- Human onboarding and AI onboarding are first-class product surfaces, not afterthoughts.
- Framework-specific integrations stay outside `threadforge-core`.

## Active Feature Branches

| Branch | Theme | Priority | Purpose |
|---|---|---:|---|
| `feature/docs-governance-onboarding` | Docs governance + onboarding | P0 | Unify repo messaging and improve first-run experience for humans and AI tools |
| `feature/examples-benchmarks` | Examples + benchmarks | P1 | Add runnable business examples and measurable performance baselines |
| `feature/observability-ecosystem` | Observability ecosystem | P1 | Extend beyond OTel with Micrometer, MDC propagation, and slow-task diagnostics |

## Delivered On Main

| Branch | Theme | Outcome |
|---|---|---|
| `feature/scope-joiners` | Higher-order orchestration API | `JoinStrategy`, `ScopeJoiner`, `firstSuccess`, `quorum`, `hedged` implemented on `main` and queued for the next release |

## Proposed Delivery Sequence

### Phase 1: Product Surface Cleanup

Branch: `feature/docs-governance-onboarding`

Goals:

- Align `README.md`, `docs/FEATURE.md`, `docs/ROADMAP.md`, `CHANGELOG.md`, and `docs/api/README.md`.
- Make the public story consistent: what exists now, what is next, and what is explicitly deferred.
- Add separate quick-start tracks:
  - human install and first-use guide
  - AI assistant install and project-context guide

Deliverables:

- `README.md` navigation cleanup and clearer install path
- human onboarding doc with dependency setup, first scope example, and common pitfalls
- AI onboarding doc that explains how to install `docs/ai/threadforge.SKILL.md`, `docs/ai/threadforge.mdc`, and `docs/ai/threadforge-agents.md`
- synchronized wording across roadmap, feature list, and changelog

Acceptance criteria:

- A new human user can go from Maven dependency to first successful example in under 10 minutes.
- An AI-assisted user can install the repo guidance into Codex / Claude Code / Cursor without guessing which file to use.
- No public doc claims a feature is both shipped and pending at the same time.

### Phase 2: Higher-Order Orchestration

Status: delivered on `main`, pending next version release

Branch: `feature/scope-joiners`

Goals:

- Add explicit structured fan-in APIs instead of forcing users to hand-roll result collection and cancellation logic.
- Keep the API readable enough that it still feels lighter than `CompletableFuture` orchestration.

Initial scope:

- `JoinStrategy`
- `ScopeJoiner` or equivalent public entry point
- `firstSuccess`
- `quorum(int requiredSuccesses)`
- `hedged(Duration delay)` or equivalent hedged-request API

Design constraints:

- Reuse existing `FailurePolicy`, deadlines, cancellation, metrics, and hooks instead of creating a parallel control plane.
- Keep exceptions and cancellation semantics explicit and testable.
- Avoid hiding task ownership or lifecycle boundaries.

Deliverables:

- core API implementation in `src/main/java`
- focused unit tests for success, timeout, cancellation, and partial failure cases
- API docs in `docs/api/`
- README and cookbook examples

Acceptance criteria:

- `firstSuccess` cancels losers after the first successful result.
- `quorum(n)` returns as soon as success quorum is met and defines clear behavior for impossible quorum.
- `hedged` starts backup work only after the configured delay and preserves structured cancellation semantics.

### Phase 3: Adoption Assets

Branch: `feature/examples-benchmarks`

Goals:

- Give users runnable examples that look like real service code.
- Add benchmark evidence so ThreadForge can be compared with baseline Java concurrency patterns.

Initial scope:

- `examples/` module or directory with copy-paste-ready samples
- `benchmarks/` module using JMH
- comparison scenarios against raw `ExecutorService` / `CompletableFuture`

Example candidates:

- parallel fan-out request aggregation
- first-success fallback across multiple providers
- quorum voting across replicas
- channel-based producer/consumer pipeline
- deadline + retry + cancellation interplay

Benchmark candidates:

- task submission overhead
- fan-out/fan-in latency
- retry overhead
- priority scheduler behavior under mixed load
- virtual thread vs fixed pool execution profile

Acceptance criteria:

- At least three examples compile and run as documented.
- Benchmark commands are documented and reproducible.
- README links directly to examples and benchmark results.

### Phase 4: Observability Ecosystem

Branch: `feature/observability-ecosystem`

Goals:

- Expand observability without forcing Spring Boot adoption.
- Provide optional integrations that keep the core dependency-light.

Initial scope:

- Micrometer integration module
- MDC / SLF4J context propagation bridge
- slow-task threshold detection and warning hook
- improved observability docs and examples

Out of scope for this phase:

- Spring Boot starter
- Actuator endpoint exposure
- auto-configuration

Implementation notes:

- Keep Micrometer and SLF4J support optional modules, not mandatory `threadforge-core` dependencies.
- Slow-task detection should compose with existing `ThreadHook` and `TaskInfo` instead of introducing a new runtime model.

Acceptance criteria:

- Users can publish task counters / duration metrics to Micrometer without custom boilerplate.
- MDC values survive task handoff where supported by the chosen bridge.
- Slow-task alerts can be enabled with a clear threshold and predictable callback behavior.

## Human and AI Onboarding Track

This work is part of `feature/docs-governance-onboarding`, but it is important enough to call out separately.

### For Humans

We need one obvious path that answers:

- What is ThreadForge?
- Which dependency do I install?
- What is the smallest useful example?
- When should I use it instead of `CompletableFuture` or raw executors?
- What are the default failure, timeout, and cancellation semantics?

Target artifacts:

- README quick-start refinement
- `docs/getting-started/human-install.md` or equivalent
- example-driven migration notes

### For AI Tools

The repo already ships AI integration files in `docs/ai/`, but the install path needs to become more operational and easier to discover.

We need one obvious path that answers:

- Which file should be used by Codex / Claude Code / Cursor / generic AGENTS consumers?
- How should the file be installed into a project?
- What ThreadForge rules must the assistant follow when writing code?
- How do we keep the AI instructions synchronized with public API changes?

Target artifacts:

- tighter `docs/ai/README.md`
- explicit install snippets for each supported tool family
- maintenance checklist tying API changes to `docs/ai/*`

## Dependencies Between Features

- `feature/docs-governance-onboarding` should start first because all later work needs a stable public narrative.
- `feature/examples-benchmarks` depends partly on delivered joiner APIs, because the best examples should exercise the new orchestration patterns.
- `feature/observability-ecosystem` can proceed in parallel with `feature/examples-benchmarks`, but its docs should land after the docs baseline cleanup.

## Release Guidance

This roadmap intentionally avoids fixing exact release numbers until implementation starts, but the current expectation is:

- first release after `1.1.2`: docs cleanup + onboarding + joiner APIs
- next release: examples + benchmarks
- following release: observability ecosystem additions

Before any release work, follow `docs/releases.md`.
