# ThreadForge Agent Index

- If the task involves release, publish, tag, changelog, or Maven Central, read `docs/releases.md` first.
- Treat real version tags as real releases, not dry runs.
- Keep the changelog entry visible near the top of `README.md`.
- If Central fails on signature validation, compare the current signature with the previous successful release before retrying.
- In repository docs, use relative paths only. Do not write workstation-specific absolute paths, local usernames, key material, fingerprints, or other sensitive environment-specific data.

## AI Integration Files

This repository ships AI-friendly integration files in `docs/ai/` for developers using ThreadForge in their own projects. These files teach AI coding assistants (Claude Code, Cursor, Copilot, etc.) how to write correct ThreadForge code.

- `docs/ai/threadforge.SKILL.md` — canonical skill file (Claude Code / OpenCode)
- `docs/ai/threadforge.mdc` — Cursor rules format
- `docs/ai/threadforge-agents.md` — AGENTS.md snippet (Codex CLI / Gemini CLI / universal)
- `docs/ai/README.md` — installation instructions for each tool

When updating the public API, also update these files to keep them in sync.
