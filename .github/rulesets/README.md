# Rulesets

Importable GitHub rulesets. These are the branch and tag policy for this repository, kept in the
repo so a policy change is reviewed in a pull request rather than clicked into the web console.

## Import through the GitHub console

**Settings → Rules → Rulesets → New ruleset → Import a ruleset**, then upload the JSON file.

Import [`protect-main.json`](protect-main.json) and [`protect-release-tags.json`](protect-release-tags.json).
Re-importing a file with the same name creates a second ruleset — edit the existing one, or delete
it first.

## Apply from the command line instead

`../../scripts/setup-github-repo.sh` applies both files through the API, is idempotent (it updates a
ruleset of the same name rather than duplicating it), and also sets repository metadata and the
merge policy:

```bash
./scripts/setup-github-repo.sh --dry-run
./scripts/setup-github-repo.sh
```

## What the policy does

### `protect-main.json`

Targets `~DEFAULT_BRANCH`, so it follows the default branch rather than hard-coding `main`.

| Rule | Effect |
|------|--------|
| `deletion` | The branch cannot be deleted |
| `non_fast_forward` | No force-pushes — history on the default branch is append-only |
| `pull_request` | Direct pushes are blocked; changes land through a PR, squash-merged, with review threads resolved |
| `required_status_checks` | `quality-and-test`, `server-compatibility`, `platform-smoke` and `consumer-smoke` must pass, and the branch must be up to date with the base first |

`required_approving_review_count` is **0** — a PR is still required, and CI still has to be green,
but a single maintainer is not blocked waiting for an approver who does not exist. Raise it to `1`
as soon as a second maintainer can review.

> The four contexts are GitHub **job** names from `.github/workflows/socketio-ci.yml`. A context
> that does not match a real job is never reported, so the rule waits forever and no PR can merge.
> `setup-github-repo.sh` validates the names against the workflow file before applying anything.

### `protect-release-tags.json`

Targets `refs/tags/v*`. Once `v0.0.1` is pushed it cannot be deleted or moved, so the commit behind
a published Maven Central version stays reproducible. Maven Central will not accept a re-publish of
an existing version, so a mutable tag can only ever create a discrepancy between the tag and the
artifact.

## Bypass actors

`bypass_actors` is empty — the policy applies to everyone, including repository admins. Add an entry
only with a deliberate reason; a standing admin bypass makes the ruleset decorative.
