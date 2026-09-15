---
name: release
description: Cut a Sockit release — pre-flight checks, tag, and verify the artifact actually landed on Maven Central. Use when asked to release, publish, cut a version, or ship to Maven Central.
---

# Releasing Sockit

Publishing is **irreversible**. Maven Central never accepts a re-publish of a version, and
`protect-release-tags` blocks deleting or moving a `v*` tag. A mistake costs a version number.

Everything after `git push origin vX.Y.Z` is automated by `.github/workflows/release.yml`. This
skill covers the part around it: deciding it is safe to tag, and confirming afterwards that the
artifact is genuinely usable rather than merely uploaded.

## Ground rules

- **Never tag without explicit user consent.** Tagging publishes to Maven Central permanently.
- The version comes from `gradle.properties` (`VERSION_NAME`), overridden in CI with
  `-PVERSION_NAME=<tag without v>`. There is no second version file.
- `gh` must act as the repository owner. This machine resolves the account per-repo via
  `~/.config/zsh/gh-account.zsh`; a non-interactive shell does not load that, so export it:
  `export GH_CONFIG_DIR="$HOME/.config/gh-personal"`.

## 1. Pre-flight

Run all of these. Any failure stops the release.

```bash
export GH_CONFIG_DIR="$HOME/.config/gh-personal"
VERSION=0.0.2   # the version being released

git switch main && git fetch origin --prune && git pull --ff-only
[ -z "$(git status --porcelain)" ]                      # clean tree
grep -q "^VERSION_NAME=$VERSION$" gradle.properties     # version bumped
grep -q "^## \[$VERSION\]" CHANGELOG.md                 # changelog section exists
! grep -q SNAPSHOT gradle.properties                    # Central rejects -SNAPSHOT
git ls-remote --tags origin "refs/tags/v$VERSION" | grep -q . && echo "TAG EXISTS — stop"
curl -sS -o /dev/null -w "%{http_code}\n" \
  "https://repo1.maven.org/maven2/io/github/adventures92/sockit/$VERSION/"   # expect 404
gh secret list --repo adventures92/Sockit                # expect 5 secrets
```

Then rehearse. `dry_run` runs the whole gate and builds the artifacts but skips publishing:

```bash
gh workflow run release.yml --repo adventures92/Sockit --ref main \
  -f version=$VERSION -f dry_run=true
```

> A `workflow_dispatch` can return **HTTP 500** for a few minutes after a workflow file changes on
> the default branch. It is transient — retry with backoff rather than debugging it.

The dry run does **not** exercise the secrets: they are only read by the `publish` job, which it
skips. A credential fault surfaces for the first time on the real tag.

## 2. Tag

```bash
git tag -a "v$VERSION" -m "Sockit $VERSION

<one-paragraph summary>

Published as io.github.adventures92:sockit:$VERSION"

git push origin "v$VERSION"
```

That triggers two independent workflows:

| Workflow | Does |
|----------|------|
| `release.yml` | verify → publish to Central → build APK + docs → create the GitHub Release |
| `docs.yml` | build Dokka → publish to GitHub Pages |

Watch it:

```bash
RUN=$(gh run list --workflow=release.yml --repo adventures92/Sockit --limit 1 \
        --json databaseId --jq '.[0].databaseId')
until [ "$(gh run view "$RUN" --repo adventures92/Sockit --json status --jq .status)" = completed ]
do sleep 30; done
gh run view "$RUN" --repo adventures92/Sockit --json conclusion,jobs \
  --jq '.conclusion, (.jobs[] | "\(.conclusion)  \(.name)")'
```

## 3. Verify it actually landed

A green `publish` job means Central *accepted* the bundle. It does not mean the artifact is usable.
The public mirror lags 15–30 minutes; the search index lags longer.

```bash
for m in sockit sockit-android sockit-iosarm64 sockit-iossimulatorarm64 sockit-jvm; do
  printf "%-28s " "$m"
  curl -sS -o /dev/null -w "%{http_code}\n" \
    "https://repo1.maven.org/maven2/io/github/adventures92/$m/$VERSION/"
done
```

Once they return 200, check the things that have actually been broken before:

```bash
B="https://repo1.maven.org/maven2/io/github/adventures92/sockit/$VERSION"

# javadoc jar is real, not an empty stub
curl -sS -o jd.jar "$B/sockit-$VERSION-javadoc.jar" && unzip -l jd.jar | tail -1

# signature verifies against the public key
curl -sS -o p.pom "$B/sockit-$VERSION.pom" && curl -sS -o p.asc "$B/sockit-$VERSION.pom.asc"
gpg --verify p.asc p.pom

# Android consumers get the api() dependencies on their COMPILE classpath
curl -sS "https://repo1.maven.org/maven2/io/github/adventures92/sockit-android/$VERSION/sockit-android-$VERSION.module" \
  | jq -r '.variants[] | select(.name=="androidApiElements-published") | .dependencies[].module'
```

The last one must list `kotlinx-serialization-json` and `kotlinx-io-core`. If it does not, the
public API is unusable from Android even though the publish succeeded — see
[dependency scopes](../../../AGENTS.md#public-api-dependency-scopes).

## Traps that have actually bitten this repo

**`GITHUB_TOKEN` events do not trigger workflows.** The GitHub Release is created by `release.yml`
using the built-in token, so a workflow listening on `release: [published]` never fires — GitHub's
recursion guard. `docs.yml` triggers on the **tag** for this reason. If you add a workflow meant to
run after a release, trigger it on the tag, not the release.

**Matrix jobs never report their bare name.** `server-compatibility` reports as
`server-compatibility (4.5.4, 0)` and so on. Requiring the bare name as a status check blocks every
pull request forever on a context that cannot arrive. The `server-compatibility-gate` fan-in job
exists to give the ruleset one stable name; require that.

**iOS tests cannot start the echo server.** A simulator test cannot fork a process, so
`PlatformEchoServer` only probes `localhost:3000`. Any job running `iosSimulatorArm64Test` must
start the server itself. `jvmTest` is different — `SocketTestServer` spawns node on an ephemeral
port.

**`dokkaHtml` is a disabled V1 task.** It runs, produces nothing, and reports success. Wiring the
javadoc jar to it shipped empty javadoc jars. Always `dokkaGeneratePublicationHtml`.

**A tag cannot be replayed.** `push: tags` fires on push only. To re-run a tag-triggered workflow
for an existing version, use `workflow_dispatch` — do not delete and re-push the tag.

## After the release

- File a [klibs.io index request](https://github.com/JetBrains/klibs-io-issue-management/issues/new?labels=index-request&template=index_request.yml)
  for a first release, to skip the ~1 month automatic indexing.
- The Maven Central badge in `README.md` goes live once the search index catches up.
- Start a fresh `## [Unreleased]` section in `CHANGELOG.md`.
