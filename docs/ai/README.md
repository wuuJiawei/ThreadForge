# ThreadForge AI Rule Files

This directory contains repository AI rule files such as `threadforge.SKILL.md`, `threadforge.mdc`, and `threadforge-agents.md`.

These files are for installing ThreadForge-specific coding rules into AI tools.

If you want AI guidance for adding ThreadForge as a Maven/Gradle dependency to a Java project, use:

- [`../getting-started/ai-consumer-guide.md`](../getting-started/ai-consumer-guide.md)

## Pick the Right File

| File | Use When | Target Tools |
|---|---|---|
| `threadforge.SKILL.md` | your tool supports a dedicated skill format | Claude Code, OpenCode, skill-based agents |
| `threadforge.mdc` | your tool reads Cursor-style rule files | Cursor, Windsurf-compatible rule systems |
| `threadforge-agents.md` | your tool reads AGENTS-style repository instructions | Codex CLI, Gemini CLI, Copilot instruction files, generic AGENTS consumers |

All three files describe the current API surface on this repository's `main` branch.

Important:

- Maven Central latest is still `1.1.2`
- `main` already contains the new joiner APIs (`JoinStrategy` / `ScopeJoiner`)
- if your project is pinned to `1.1.2`, do not let the assistant generate joiner code yet

## What These Files Teach the Assistant

- always use `ThreadScope` with try-with-resources
- configure `with*` methods before the first `submit()` or `schedule()`
- understand default deadline, retry, cancellation, and failure semantics
- use the actual `io.threadforge` API that exists in the target project version
- avoid inventing roadmap features that do not exist yet

## Installation by Tool

### Codex CLI / Generic AGENTS

Append the AGENTS snippet to the target project's `AGENTS.md`:

```bash
cat docs/ai/threadforge-agents.md >> AGENTS.md
```

If the project does not already have an `AGENTS.md`, create one first:

```bash
touch AGENTS.md
cat docs/ai/threadforge-agents.md >> AGENTS.md
```

### Claude Code

Install as a global or project-local skill:

```bash
mkdir -p ~/.claude/skills/threadforge
cp docs/ai/threadforge.SKILL.md ~/.claude/skills/threadforge/SKILL.md
```

Project-local alternative:

```bash
mkdir -p .claude/skills/threadforge
cp docs/ai/threadforge.SKILL.md .claude/skills/threadforge/SKILL.md
```

### OpenCode

```bash
mkdir -p ~/.agents/skills/threadforge
cp docs/ai/threadforge.SKILL.md ~/.agents/skills/threadforge/SKILL.md
```

### Cursor

Use the dedicated rule file:

```bash
mkdir -p .cursor/rules
cp docs/ai/threadforge.mdc .cursor/rules/threadforge.mdc
```

Fallback option if the team standardizes on AGENTS-style rules:

```bash
cat docs/ai/threadforge-agents.md >> AGENTS.md
```

### GitHub Copilot

Append the AGENTS-style guidance to repository instructions:

```bash
mkdir -p .github
cat docs/ai/threadforge-agents.md >> .github/copilot-instructions.md
```

### Windsurf

```bash
mkdir -p .windsurf/rules
cp docs/ai/threadforge.mdc .windsurf/rules/threadforge.md
```

## Human + AI Recommended Setup

For teams that want both human onboarding and AI guidance in one repo:

1. Add the Maven or Gradle dependency from [`../../README.md`](../../README.md).
2. Read the human quick-start in [`../getting-started/human-install.md`](../getting-started/human-install.md).
3. Install one AI integration file that matches the team's primary tool.
4. Keep the copied file synchronized when upgrading ThreadForge.

## Maintenance Rules

When the public API changes:

1. Update `threadforge.SKILL.md` first as the canonical AI source.
2. Sync the same semantics into `threadforge.mdc` and `threadforge-agents.md`.
3. Update `README.md`, `docs/api/README.md`, and `docs/FEATURE.md` if the change is user-visible.
4. Update `CHANGELOG.md` for released or release-bound changes.

When the roadmap changes without a shipped API change:

- do not teach the AI files to use the planned feature as if it already exists
- keep future work in `docs/ROADMAP.md`, not in the AI rules

## Version

These files currently track the repository `main` branch, which is ahead of Maven Central `1.1.2`.
