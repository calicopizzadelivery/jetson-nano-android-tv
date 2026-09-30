#!/bin/bash
# One streaming configuration, start to finish: set Moonlight's options on
# the Jetson, start the stream from its UI, measure off HDMI, and end the
# session. host.sh must already be running at the matching size.
#
#   scripts/gamestream/run_config.sh NAME RES FPS KBPS FORMAT PACING [SECONDS]
#   scripts/gamestream/run_config.sh 1080p60-hevc 1920x1080 60 20000 forceh265 latency
#
# FORMAT is Moonlight's video_format (auto forceh265 neverh265 forceav1),
# PACING its frame_pacing (latency balanced cap-fps smoothness). Writes
# OUT/NAME.json (measure.py) and OUT/NAME.png (a frame with Moonlight's
# performance overlay). The tap positions are for Moonlight 12.2's layout at
# 1920x1080 UI: first PC in the grid, then first app.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"
OUT="${OUT:-.}"
STATE="${STATE:-$HOME/.cache/jetson-tv/gamestream/state}"
name=$1 res=$2 fps=$3 kbps=$4 format=$5 pacing=$6 secs=${7:-30}

auth=(-sk -u "jetson:$(cat "$STATE/webui-password")")
# End whatever the host is still running, so the new session starts clean.
curl "${auth[@]}" -X POST https://localhost:47990/api/apps/close >/dev/null || true

ADB="$ADB" "$HERE/moonlight_prefs.py" list_resolution="$res" list_fps="$fps" \
    seekbar_bitrate_kbps="$kbps" video_format="$format" frame_pacing="$pacing" \
    checkbox_enable_perf_overlay=true checkbox_enable_post_stream_toast=true >/dev/null
$ADB shell 'am start -n com.limelight/.PcView >/dev/null; sleep 5
            input tap 371 227; sleep 4
            input tap 188 300; sleep 10
            dumpsys window | grep -m1 mCurrentFocus' | grep -q "com.limelight.Game" ||
    { echo "$name: the stream did not start" >&2; exit 1; }

mkdir -p "$OUT"
"$HERE/measure.py" --seconds "$secs" --snapshot "$OUT/$name.png" > "$OUT/$name.json"
$ADB shell am force-stop com.limelight
curl "${auth[@]}" -X POST https://localhost:47990/api/apps/close >/dev/null || true
echo "$name: $(python3 -c "import json;d=json.load(open('$OUT/$name.json'));l=d['latency_ms'];print(f\"median {l['median']} ms, p95 {l['p95']} ms, steps {d['counter_steps']}\")")"
