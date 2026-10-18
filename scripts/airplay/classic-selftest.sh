#!/usr/bin/env bash
#
# End-to-end test of the classic AirPlay 1 / RAOP path, with no Apple device
# and no network beyond the Jetson itself.
#
#   ./classic-selftest.sh [seconds]
#
# raopsend streams a 440 Hz tone to the receiver's own RTSP port as
# uncompressed L16, and the tone is read back off the MS2109 HDMI capture and
# checked by frequency. That last part is the point: a byte counter or a level
# meter cannot tell silence from signal, and a wrong session key produces
# convincing-looking output -- see docs/airplay.md, "How the wrong key was
# caught". A known-frequency source makes the check unambiguous.
#
# What it exercises: ANNOUNCE and its SDP parse, the unencrypted branch, the
# Transport: SETUP, RECORD, the RTP receive loop, the L16 render path and
# AudioTrack through to HDMI. It does NOT exercise the RSA key unwrap or
# Apple-Challenge, which need a real sender.
#
# SPDX-License-Identifier: Apache-2.0
set -uo pipefail

SECONDS_TO_SEND="${1:-10}"
OUT=/srv/build/jetson-tv/lineage/out/target/product/porg/system/bin/raopsend
WAV="$(mktemp -t classic-selftest-XXXX.wav)"
A() { docker exec adbnode adb "$@"; }

if ! A devices 2>/dev/null | sed -n '2p' | grep -q device; then
  echo "no adb device; is adbnode up and the Jetson powered?" >&2
  exit 1
fi
[[ -f $OUT ]] || { echo "raopsend not built: m raopsend" >&2; exit 1; }

port=$(A logcat -d -s UxPlay 2>/dev/null | grep -oE "server on port [0-9]+" | tail -1 | awk '{print $NF}')
[[ -n $port ]] || { echo "could not find the receiver's RTSP port in logcat" >&2; exit 1; }
echo "==> receiver on port $port"

docker cp "$OUT" adbnode:/tmp/raopsend >/dev/null
A push /tmp/raopsend /data/local/tmp/raopsend >/dev/null 2>&1
A shell chmod 755 /data/local/tmp/raopsend

# capture first: the stream starts within a second and we must not miss it
arecord -D plughw:CARD=MS2109,DEV=0 -f S16_LE -r 48000 -c 2 \
        -d $((SECONDS_TO_SEND + 4)) "$WAV" 2>/dev/null &
cap=$!
sleep 1
echo "==> streaming ${SECONDS_TO_SEND}s of 440 Hz"
A shell "/data/local/tmp/raopsend --title 'Classic self-test' --artist raopsend \
         127.0.0.1 $port $SECONDS_TO_SEND" 2>&1 | tail -3
wait $cap

python3 - "$WAV" <<'PY'
import sys, wave, struct, math
w = wave.open(sys.argv[1]); n = w.getnframes(); sr = w.getframerate()
s = struct.unpack("<%dh" % (n * 2), w.readframes(n)); L = s[0::2]
def goertzel(x, f, sr):
    k = 2 * math.cos(2 * math.pi * f / sr); s1 = s2 = 0.0
    for v in x:
        s0 = v + k * s1 - s2; s2 = s1; s1 = s0
    return math.sqrt(abs(s1 * s1 + s2 * s2 - k * s1 * s2)) / len(x)
win = sr
best = max(range(0, max(1, len(L) - win), win),
           key=lambda i: max(abs(x) for x in L[i:i + win]))
seg = L[best:best + win]
tone = goertzel(seg, 440, sr)
others = [goertzel(seg, f, sr) for f in (220, 330, 550, 660, 880, 1320)]
peak = max(abs(x) for x in seg)
print("    peak %d (%.1f%% FS) in the loudest second, at %.1fs" % (peak, 100 * peak / 32768, best / sr))
print("    440 Hz %.0f   next loudest other band %.0f" % (tone, max(others)))
ok = tone > 500 and tone > 10 * max(max(others), 1)
print("    %s" % ("PASS: a clean 440 Hz tone reached HDMI"
                  if ok else "FAIL: no clean tone -- see docs/airplay.md"))
sys.exit(0 if ok else 1)
PY
rc=$?
rm -f "$WAV"
exit $rc
