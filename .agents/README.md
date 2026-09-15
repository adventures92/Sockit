# `.agents/`

Vendor-neutral home for agent tooling, alongside [`AGENTS.md`](../AGENTS.md).

```
.agents/skills/<name>/SKILL.md     canonical
.claude/skills -> ../.agents/skills   symlink, tracked by git
```

## Why a symlink

Claude Code reads skills from `.claude/skills/`. Rather than keep a second copy there, `.claude/skills`
is a **symlink committed to the repository** — git stores it as mode `120000`, so it exists and
resolves immediately after clone. No bootstrap script, no git hook, no drift between two copies.

A hook would not work anyway: hooks live in `.git/hooks`, which git does not distribute, so every
clone would need a manual `core.hooksPath` step and a missed step means a silently stale skill.

Other agents can point at `.agents/skills/` directly, or add their own symlink next to the Claude one.

> **Windows:** symlinks need developer mode or `git config core.symlinks true`. Everyone on this
> project is on macOS; if that changes, the fallback is a setup target rather than a hook.

## Skills

| Skill | Purpose |
|-------|---------|
| [`release`](skills/release/SKILL.md) | Cut a release — pre-flight, tag, and verify the artifact landed on Maven Central |

## Writing a skill here

Front-matter needs `name` and `description`. The description decides whether an agent loads the
skill, so write it as *when to use this*, not *what it contains*.

```markdown
---
name: release
description: Cut a Sockit release — pre-flight checks, tag, and verify the artifact actually landed
  on Maven Central. Use when asked to release, publish, cut a version, or ship to Maven Central.
---
```

Keep skills about **this repository's** hard-won specifics — the traps, the exact commands, the
things that have actually broken. General knowledge the model already has is noise.
