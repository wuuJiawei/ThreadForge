# ThreadForge AI Integration

This directory contains AI-friendly integration files that help AI coding assistants (Claude Code, Cursor, Copilot, Codex CLI, etc.) write correct ThreadForge code.

## What's Included

| File | Format | Target Tool |
|---|---|---|
| `threadforge.SKILL.md` | SKILL.md | Claude Code, OpenCode |
| `threadforge.mdc` | Cursor .mdc | Cursor |
| `threadforge-agents.md` | AGENTS.md snippet | Codex CLI, Gemini CLI, Cursor (fallback) |

All three files contain the same core API knowledge in different formats.

## Installation

### Claude Code

Copy the SKILL.md to your global skills directory:

```bash
mkdir -p ~/.claude/skills/threadforge
cp threadforge.SKILL.md ~/.claude/skills/threadforge/SKILL.md
```

Or place it in your project's `.claude/skills/` directory:

```bash
mkdir -p .claude/skills/threadforge
cp threadforge.SKILL.md .claude/skills/threadforge/SKILL.md
```

### OpenCode

Copy to the OpenCode skills directory:

```bash
mkdir -p ~/.agents/skills/threadforge
cp threadforge.SKILL.md ~/.agents/skills/threadforge/SKILL.md
```

### Cursor

Copy the `.mdc` file to your project's Cursor rules:

```bash
mkdir -p .cursor/rules
cp threadforge.mdc .cursor/rules/threadforge.mdc
```

Or use the AGENTS.md approach (simpler, cross-tool):

```bash
cat threadforge-agents.md >> AGENTS.md
```

### Codex CLI / Gemini CLI

Append the AGENTS.md snippet to your project:

```bash
cat threadforge-agents.md >> AGENTS.md
```

### GitHub Copilot

Append to your copilot instructions:

```bash
mkdir -p .github
cat threadforge-agents.md >> .github/copilot-instructions.md
```

### Windsurf

```bash
mkdir -p .windsurf/rules
cp threadforge.mdc .windsurf/rules/threadforge.md
```

## Version

These files are based on ThreadForge **1.1.2**. Update when upgrading the library.

## Updating

The canonical source is `threadforge.SKILL.md`. The `.mdc` and `agents.md` files are derived from it. When the ThreadForge API changes:

1. Update `threadforge.SKILL.md` first
2. Sync changes to `threadforge.mdc` and `threadforge-agents.md`
3. Re-copy to your tool's configuration directory
