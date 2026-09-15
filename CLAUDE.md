@AGENTS.md

# CLAUDE.md

`AGENTS.md` is the canonical, vendor-neutral contract for this repository and is imported above.
Follow it rather than duplicating policy here — everything about architecture, invariants, commands,
branching, and CI lives there.

If the `@AGENTS.md` import is not supported by the Claude surface you are running on, open
`AGENTS.md` directly before doing anything else.

## Claude Code specifics

- This repository has no commits yet on some checkouts. **Never `git add`, commit, push, tag, or
  publish without explicit user consent**, even when the change is obviously complete.
- Prefer the Gradle wrapper over any globally installed Gradle; the daemon toolchain is pinned to
  JDK 21 in `gradle/gradle-daemon-jvm.properties`.
- `ANDROID_HOME` may be unset in this environment. Publishing and Android tasks need it:
  `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew …`, or write `sdk.dir` into a local
  `local.properties` (gitignored).
- Long Gradle invocations should run in the background; a full `:socketio:jvmTest` takes ~1 minute
  warm, and iOS target compilation considerably longer from cold.
