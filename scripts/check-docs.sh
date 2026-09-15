#!/usr/bin/env bash
# Guards the hand-written guide against drifting from the code.
#
# The Dokka reference is generated and cannot drift. guide/ is prose, and prose goes stale
# silently: a renamed type, a changed default or a new target leaves documentation that is
# confidently wrong. These checks make that a build failure instead.
#
#   ./scripts/check-docs.sh
#
# Checked:
#   1. every SUMMARY.md entry exists, and every guide page is listed in SUMMARY.md
#   2. internal links resolve, and #anchors match a real heading
#   3. install snippets in every user-facing .md match VERSION_NAME
#   4. API identifiers named in the guide exist in the committed API dump
#
# Not checked: whether snippets compile. That needs them restructured as {{#include}} of real
# compiled sources — see the note at the end of the file.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GUIDE="$ROOT/guide"
SUMMARY="$GUIDE/SUMMARY.md"
API_DUMP="$ROOT/socketio/api/socketio.klib.api"
VERSION="$(grep -E '^VERSION_NAME=' "$ROOT/gradle.properties" | head -1 | cut -d= -f2)"

FAILURES=0
ok()   { printf '\033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '\033[31m✗\033[0m %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
note() { printf '  \033[90m%s\033[0m\n' "$1"; }

# Types that appear in the guide but belong to other libraries, not our public API.
EXTERNAL='^(Flow|StateFlow|JsonObject|JsonElement|JsonPrimitive|ByteString|HttpClient|OkHttp|OkHttpClient|CertificatePinner|WebSockets|Darwin|CIO|ViewModel|Dispatchers|HostnameVerifier|X509TrustManager|IllegalArgumentException|UIApplication|ProtocolConformanceTest|Int|String|Boolean|Number|Activity|Info|Gradle|Kotlin|Socket|IO|Engine|JavaScript|Maven|Central|Dokka|CONNECT|BINARY_EVENT|TLS|HTTP|HTTPS|ATS|API|UI|REST|SKIE|KMP|JSON|AAR|URL|URLs|ID|CI|SUMMARY)$'

# ------------------------------------------------- 1. SUMMARY <-> files

listed="$(grep -oE '\]\(([a-zA-Z0-9-]+)\.md\)' "$SUMMARY" | sed -E 's/\]\(//; s/\)//' | sort -u)"
present="$(cd "$GUIDE" && ls ./*.md | sed 's|^\./||' | grep -v '^SUMMARY.md$' | sort -u)"

missing="$(comm -23 <(echo "$listed") <(echo "$present"))"
orphan="$(comm -13 <(echo "$listed") <(echo "$present"))"

[ -z "$missing" ] || { bad "SUMMARY.md lists pages that do not exist"; echo "$missing" | sed 's/^/    /'; }
[ -z "$orphan" ]  || { bad "guide pages missing from SUMMARY.md (they will not be built)"; echo "$orphan" | sed 's/^/    /'; }
[ -z "$missing" ] && [ -z "$orphan" ] && ok "SUMMARY.md matches $(echo "$present" | wc -l | tr -d ' ') pages"

# ------------------------------------------------- 2. links and anchors

link_failures=0
for f in "$GUIDE"/*.md; do
    while read -r link; do
        [ -n "$link" ] || continue
        target="${link%%#*}"
        anchor="${link##*#}"
        [ -f "$GUIDE/$target" ] || {
            bad "$(basename "$f"): link to missing page '$target'"
            link_failures=$((link_failures + 1)); continue
        }
        [ "$anchor" = "$link" ] && continue   # no anchor to check
        # mdBook slugs: lowercase, spaces to dashes, punctuation dropped.
        slugs="$(grep -oE '^#{2,4} .*' "$GUIDE/$target" \
                 | sed -E 's/^#+ //' | tr '[:upper:]' '[:lower:]' \
                 | sed -E 's/`//g; s/[^a-z0-9 -]//g; s/ +/-/g')"
        echo "$slugs" | grep -qx "$anchor" || {
            bad "$(basename "$f"): '#$anchor' matches no heading in $target"
            link_failures=$((link_failures + 1))
        }
    done < <(grep -oE '\]\([a-zA-Z0-9-]+\.md(#[a-z0-9-]+)?\)' "$f" | sed -E 's/\]\(//; s/\)//')
done
[ "$link_failures" -eq 0 ] && ok "all internal links and anchors resolve"

# ------------------------------------------------- 3. version strings

# Every user-facing markdown file, not just the guide — the root README quoted 0.0.1 for a whole
# release because this check only looked at guide/. Excluded: docs/ (historical design records,
# where old versions are the point) and CHANGELOG.md (lists every version by definition).
stale="$(grep -rnoE 'io\.github\.adventures92:sockit:[0-9]+\.[0-9]+\.[0-9]+' \
           --include='*.md' "$ROOT" 2>/dev/null \
         | grep -vE "/(docs|build|guide-build|node_modules|\.git)/|/CHANGELOG\.md:" \
         | grep -v ":sockit:$VERSION" || true)"
if [ -n "$stale" ]; then
    bad "a markdown file quotes a version other than VERSION_NAME ($VERSION)"
    echo "$stale" | sed "s|$ROOT/||" | sed 's/^/    /'
else
    ok "every install snippet across the repo quotes $VERSION"
fi

# ------------------------------------------------- 4. API identifiers exist

if [ ! -f "$API_DUMP" ]; then
    note "no API dump at ${API_DUMP#"$ROOT"/} — skipping identifier check (run :socketio:apiDump)"
else
    unknown=""
    while read -r ident; do
        [ -n "$ident" ] || continue
        echo "$ident" | grep -qE "$EXTERNAL" && continue
        # Match the bare name anywhere in the dump: types, and members after a dot.
        grep -qE "(^|[/.<, ])${ident}([ (/.>,]|$)" "$API_DUMP" && continue
        unknown="$unknown$ident\n"
    done < <(grep -ohE '`[A-Z][A-Za-z0-9]*(\.[A-Za-z][A-Za-z0-9]*)?`' "$GUIDE"/*.md \
             | tr -d '`' | cut -d. -f1 | sort -u)

    if [ -n "$unknown" ]; then
        bad "guide names types absent from the API dump — renamed, removed, or a typo"
        printf "$unknown" | sed 's/^/    /'
        note "if one is an external type, add it to EXTERNAL in this script"
    else
        ok "every API type named in the guide exists in the public API"
    fi
fi

# ------------------------------------------------------------- summary

echo
if [ "$FAILURES" -eq 0 ]; then
    ok "documentation checks passed"
else
    bad "$FAILURES documentation check(s) failed"
    echo
    note "The guide is hand-written and the compiler does not read it. These checks exist"
    note "because a renamed type or changed default leaves prose that is confidently wrong."
    exit 1
fi

# Not yet checked: that the guide's Kotlin snippets compile. Most are fragments — they reference
# `jwt`, `render`, an ambient coroutine scope — so they would need restructuring as
# {{#include}} of real compiled sources before this could be mechanical. That is the strongest
# version of this guard and the obvious next step; .github/consumer-smoke already proves the
# pattern works for the quickstart.
