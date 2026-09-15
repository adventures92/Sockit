#!/usr/bin/env bash
# Prepare a release: bump VERSION_NAME, promote the CHANGELOG's Unreleased section, and rewrite the
# link refs. Makes no git or network calls — prepare-release.yml commits the result and opens a PR,
# and running it locally is the way to see the diff before dispatching the workflow.
#
#   ./scripts/prepare-release.sh patch|minor|major
#   ./scripts/prepare-release.sh --version 1.2.3     # explicit, skips the bump arithmetic
#
# Refuses to do anything when the release would be wrong:
#   - no `## [Unreleased]` section
#   - Unreleased section is empty  -> a release with no changes, and empty GitHub Release notes
#   - the target tag already exists

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$ROOT/gradle.properties"
CHANGELOG="$ROOT/CHANGELOG.md"
REPO_URL="https://github.com/adventures92/Sockit"

info() { printf '\033[36m→\033[0m %s\n' "$1"; }
ok()   { printf '\033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '\033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }

# ------------------------------------------------------------------ arguments

BUMP=""
EXPLICIT=""
case "${1:-}" in
    patch|minor|major) BUMP="$1" ;;
    --version)         EXPLICIT="${2:-}"; [[ -n "$EXPLICIT" ]] || fail "--version needs a value" ;;
    *)                 fail "usage: $(basename "$0") patch|minor|major | --version X.Y.Z" ;;
esac

# -------------------------------------------------------------- current state

CURRENT="$(grep -E '^VERSION_NAME=' "$PROPS" | head -1 | cut -d= -f2)"
[[ -n "$CURRENT" ]] || fail "no VERSION_NAME in gradle.properties"
[[ "$CURRENT" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "VERSION_NAME '$CURRENT' is not X.Y.Z"

if [[ -n "$EXPLICIT" ]]; then
    NEXT="$EXPLICIT"
    [[ "$NEXT" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$NEXT' is not a semantic version"
else
    IFS=. read -r MAJ MIN PAT <<< "$CURRENT"
    case "$BUMP" in
        major) NEXT="$((MAJ + 1)).0.0" ;;
        minor) NEXT="$MAJ.$((MIN + 1)).0" ;;
        patch) NEXT="$MAJ.$MIN.$((PAT + 1))" ;;
    esac
fi

info "$CURRENT → $NEXT"

# ------------------------------------------------------------------ guardrails

grep -q '^## \[Unreleased\]' "$CHANGELOG" || fail "CHANGELOG.md has no '## [Unreleased]' section"

# Everything between "## [Unreleased]" and the next "## [" heading.
UNRELEASED="$(awk '/^## \[Unreleased\]/{c=1;next} c&&/^## \[/{exit} c' "$CHANGELOG" | tr -d '[:space:]')"
[[ -n "$UNRELEASED" ]] || fail "the Unreleased section is empty — nothing to release
     Add entries under '## [Unreleased]' first. Releasing an empty section would publish a version
     with no changes and produce empty GitHub Release notes."

if git -C "$ROOT" rev-parse -q --verify "refs/tags/v$NEXT" >/dev/null 2>&1; then
    fail "tag v$NEXT already exists locally"
fi
grep -q "^## \[$NEXT\]" "$CHANGELOG" && fail "CHANGELOG.md already has a '## [$NEXT]' section"

ok "Unreleased has content, v$NEXT is free"

# -------------------------------------------------------------------- rewrite

TODAY="$(date -u +%Y-%m-%d)"

# 1. VERSION_NAME. Anchored so it cannot match a comment or another key.
awk -v v="$NEXT" '{ if ($0 ~ /^VERSION_NAME=/) print "VERSION_NAME=" v; else print }' \
    "$PROPS" > "$PROPS.tmp" && mv "$PROPS.tmp" "$PROPS"
ok "VERSION_NAME=$NEXT"

# 2. Promote Unreleased, leaving a fresh empty one above it.
awk -v v="$NEXT" -v d="$TODAY" '
    /^## \[Unreleased\]/ && !done {
        print "## [Unreleased]"
        print ""
        print "## [" v "] - " d
        done = 1
        next
    }
    { print }
' "$CHANGELOG" > "$CHANGELOG.tmp" && mv "$CHANGELOG.tmp" "$CHANGELOG"
ok "promoted Unreleased → [$NEXT] - $TODAY"

# 3. Link refs: repoint Unreleased at the new tag and add a ref for it.
awk -v v="$NEXT" -v url="$REPO_URL" '
    /^\[Unreleased\]:/ {
        print "[Unreleased]: " url "/compare/v" v "...HEAD"
        print "[" v "]: " url "/releases/tag/v" v
        next
    }
    { print }
' "$CHANGELOG" > "$CHANGELOG.tmp" && mv "$CHANGELOG.tmp" "$CHANGELOG"
ok "link refs updated"

# ------------------------------------------------------------------- handover

# prepare-release.yml reads these to title the branch, commit and pull request.
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
        echo "current=$CURRENT"
        echo "version=$NEXT"
        echo "date=$TODAY"
    } >> "$GITHUB_OUTPUT"
fi

echo
ok "prepared $NEXT — review the diff, then commit"
