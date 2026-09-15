## What changed

<!-- One or two sentences. What does this do that the previous behaviour did not? -->

## Why

<!-- The problem this solves. Link the issue if there is one: Fixes #123 -->

## Checklist

- [ ] `./gradlew spotlessApply` run, and `./gradlew :socketio:apiCheck spotlessCheck :socketio:detekt` passes
- [ ] Compiles on every target — `:socketio:compileAndroidMain :socketio:compileKotlinIosSimulatorArm64 :socketio:compileKotlinJvm`
- [ ] `./gradlew :socketio:jvmTest` passes
- [ ] Entry added under `## [Unreleased]` in `CHANGELOG.md`

### If this touches the public `api/` package

- [ ] `./gradlew :socketio:apiDump` run and the updated `socketio/api/` files committed
- [ ] KDoc added or updated — only `api/` is documented by Dokka
- [ ] I understand this is a compatibility commitment once released

### If this touches the protocol, engine or transports

- [ ] A vector from the upstream [`socket.io-protocol`](https://github.com/socketio/socket.io-protocol) test suite is covered by `ProtocolConformanceTest`
- [ ] Connection state is only mutated on the `WorkQueue` worker
- [ ] No JDK 21+ collection APIs (`removeFirst()` / `removeLast()`) added to `commonMain`

## Notes for the reviewer

<!-- Anything non-obvious: a trade-off you made, something you deliberately left out, a follow-up. -->
