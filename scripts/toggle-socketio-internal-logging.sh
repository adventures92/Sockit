#!/usr/bin/env bash
# Toggle Socket.IO library internal logging for local development.
#
# Writes gradle/socketio-dev.local.properties (gitignored). All Gradle builds
# pick up the flag from that file unless overridden with -Psocketio.internalLogging=...
#
# Usage:
#   ./scripts/toggle-socketio-internal-logging.sh          # toggle on/off
#   ./scripts/toggle-socketio-internal-logging.sh on
#   ./scripts/toggle-socketio-internal-logging.sh off
#   ./scripts/toggle-socketio-internal-logging.sh status

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS_FILE="$ROOT/gradle/socketio-dev.local.properties"
EXAMPLE_FILE="$ROOT/gradle/socketio-dev.local.properties.example"

is_enabled() {
    [[ -f "$PROPS_FILE" ]] && grep -qE '^socketio\.internalLogging=true\s*$' "$PROPS_FILE"
}

write_enabled() {
    local enabled="$1"
    mkdir -p "$(dirname "$PROPS_FILE")"
    cat >"$PROPS_FILE" <<EOF
# Local dev only — gitignored. Created/updated by scripts/toggle-socketio-internal-logging.sh
# Copy from socketio-dev.local.properties.example if missing.
socketio.internalLogging=$enabled
EOF
}

ensure_example() {
    if [[ ! -f "$EXAMPLE_FILE" ]]; then
        cat >"$EXAMPLE_FILE" <<'EOF'
# Example — copy to socketio-dev.local.properties (gitignored) or use the toggle script.
socketio.internalLogging=false
EOF
    fi
}

print_status() {
    if is_enabled; then
        echo "Socket.IO internal logging: ON  ($PROPS_FILE)"
        echo "Android → Logcat (Log.d) · iOS → NSLog · JVM tests → Gradle stdout"
    else
        echo "Socket.IO internal logging: OFF"
        if [[ ! -f "$PROPS_FILE" ]]; then
            echo "(no local file — run this script to enable)"
        fi
    fi
}

case "${1:-toggle}" in
    on)
        write_enabled true
        echo "Socket.IO internal logging: ON"
        echo "Rebuild to apply, e.g. ./gradlew :androidApp:installDebug"
        ;;
    off)
        write_enabled false
        echo "Socket.IO internal logging: OFF"
        echo "Rebuild to apply."
        ;;
    status)
        print_status
        ;;
    toggle)
        if is_enabled; then
            write_enabled false
            echo "Socket.IO internal logging: OFF"
        else
            write_enabled true
            echo "Socket.IO internal logging: ON"
        fi
        echo "Rebuild to apply, e.g. ./gradlew :androidApp:installDebug"
        ;;
    *)
        echo "Usage: $0 [on|off|toggle|status]" >&2
        exit 1
        ;;
esac

ensure_example
