#!/bin/bash
# A Sunshine host for the game-streaming tests, run as the desktop user with
# no root: a nested X display (Xephyr, so nothing of the real desktop is
# captured), pattern.py drawing into it, and Sunshine capturing it with
# NVENC. See docs/game-streaming.md.
#
#   scripts/gamestream/host.sh start [WxH] [ball|zone]   # default 1920x1080 ball
#   scripts/gamestream/host.sh pin 1234                   # Moonlight's pairing PIN
#   scripts/gamestream/host.sh stop
#
# SUNSHINE points at the Sunshine AppImage; STATE is where its config, keys,
# pairings and log live (kept between runs, so the Jetson stays paired).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STATE="${STATE:-$HOME/.cache/jetson-tv/gamestream/state}"
SUNSHINE="${SUNSHINE:-$HOME/.cache/jetson-tv/gamestream/Sunshine_2026.914.233613_x86_64.AppImage}"
XDISPLAY=:99
# Web UI and pairing API credentials; the web UI only answers on localhost.
USER_NAME=jetson
PASS_FILE="$STATE/webui-password"

mkdir -p "$STATE"
[ -f "$PASS_FILE" ] || head -c 12 /dev/urandom | base64 | tr -d '/+=' > "$PASS_FILE"

stop_pid() {
    # Wait for it to go: a new Xephyr started while the old one still holds
    # /tmp/.X99-lock refuses the display, and everything after it fails.
    local f="$STATE/$1.pid" pid
    if [ -f "$f" ]; then
        pid=$(cat "$f")
        kill "$pid" 2>/dev/null || true
        for _ in $(seq 50); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
        rm -f "$f"
    fi
}

case "${1:-}" in
start)
    size="${2:-1920x1080}"
    pattern="${3:-ball}"
    cat > "$STATE/sunshine.conf" <<EOF
sunshine_name = thebe-test
min_log_level = info
log_path = $STATE/sunshine.log
file_apps = $STATE/apps.json
file_state = $STATE/sunshine_state.json
credentials_file = $STATE/sunshine_credentials.json
pkey = $STATE/cakey.pem
cert = $STATE/cacert.pem
capture = x11
encoder = nvenc
origin_web_ui_allowed = pc
upnp = disabled
address_family = ipv4
keyboard = disabled
mouse = disabled
controller = disabled
stream_audio = disabled
EOF
    cat > "$STATE/apps.json" <<'EOF'
{"env": {}, "apps": [{"name": "Test pattern", "output": "", "cmd": "", "prep-cmd": [], "detached": []}]}
EOF
    stop_pid pattern; stop_pid sunshine; stop_pid xephyr
    Xephyr "$XDISPLAY" -screen "$size" -ac -br -noreset -title "Sunshine test display ($size)" \
        >"$STATE/xephyr.log" 2>&1 &
    echo $! > "$STATE/xephyr.pid"
    for _ in $(seq 50); do DISPLAY=$XDISPLAY xdpyinfo >/dev/null 2>&1 && break; sleep 0.1; done
    DISPLAY=$XDISPLAY python3 "$HERE/pattern.py" --size "$size" --pattern "$pattern" \
        >"$STATE/pattern.log" 2>&1 &
    echo $! > "$STATE/pattern.pid"
    "$SUNSHINE" "$STATE/sunshine.conf" --creds "$USER_NAME" "$(cat "$PASS_FILE")" >/dev/null 2>&1 || true
    DISPLAY=$XDISPLAY "$SUNSHINE" "$STATE/sunshine.conf" >"$STATE/sunshine.stdout" 2>&1 &
    echo $! > "$STATE/sunshine.pid"
    echo "started: display $XDISPLAY $size ($pattern), sunshine pid $(cat "$STATE/sunshine.pid")"
    ;;
pin)
    # Sunshine lists pending pair requests (GET) and takes the PIN against one
    # of them by id (POST); with one pending, that is the Jetson's.
    auth=(-sk -u "$USER_NAME:$(cat "$PASS_FILE")")
    id=$(curl "${auth[@]}" https://localhost:47990/api/pin |
        python3 -c 'import json,sys; p=json.load(sys.stdin).get("pairings",[]); print(p[0]["id"] if p else "")')
    [ -n "$id" ] || { echo "no pending pair request: start pairing in Moonlight first"; exit 1; }
    curl "${auth[@]}" -H 'Content-Type: application/json' \
        -d "{\"pairing_id\":\"$id\",\"pin\":\"$2\",\"name\":\"jetson\"}" https://localhost:47990/api/pin
    echo
    ;;
stop)
    stop_pid pattern; stop_pid sunshine; stop_pid xephyr
    echo stopped
    ;;
*)
    sed -n '2,15p' "$0"; exit 2 ;;
esac
