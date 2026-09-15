@AGENTS.md

# CLAUDE.md

`AGENTS.md` is the canonical, vendor-neutral contract for this repository and is imported above.
**Follow it. Do not duplicate any of it here** — architecture, invariants, commands, branching,
release process and CI all live there, and a second copy only drifts.

If the `@AGENTS.md` import is not supported by the Claude surface you are running on, open
`AGENTS.md` directly before doing anything else.

Skills live in [`.agents/skills/`](.agents/skills/), reachable as `.claude/skills` through a
committed symlink.

## Claude Code harness specifics

Only things that are true of *this harness* belong here. Everything else goes in `AGENTS.md`.

- **`gh` resolves the wrong account in a non-interactive shell.** This machine picks a GitHub
  account per repository via a `gh` wrapper in `~/.config/zsh/gh-account.zsh`, which a
  non-interactive shell does not load — the wrapper errors and falls through to the default
  account, which has no write access here. Export the config directory explicitly:
  `export GH_CONFIG_DIR="$HOME/.config/gh-personal"`.
- **`python3` is the Xcode Command Line Tools stub** and is currently blocked by an unaccepted
  Xcode licence. Use `jq`, `yq`, `awk` or `node` instead; do not reach for Python in scripts.
- **Run long Gradle invocations in the background.** A warm `:socketio:jvmTest` takes about a
  minute; iOS target compilation is considerably longer from cold.
- **`ANDROID_HOME` is unset here.** Prefix Android and publishing tasks with
  `ANDROID_HOME="$HOME/Library/Android/sdk"`, or write `sdk.dir` into a local `local.properties`.
