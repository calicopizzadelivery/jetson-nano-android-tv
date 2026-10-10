#!/usr/bin/env bash
#
# Is the box ready for an Apple device? Answers in one go.
#
#   ./preflight.sh            # checks only
#   ./preflight.sh --tone     # also run a self-test and listen to HDMI
#
# Every one of these has been the reason a session started badly at least
# once, so they are checked rather than assumed. The receiver being OFF after
# a factory reset is the big one: persist.jetsontv.airplay.enabled lives in
# /data, so a wipe silently disables AirPlay and nothing advertises.
#
# SPDX-License-Identifier: Apache-2.0
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
A() { docker exec adbnode adb "$@"; }
ok=0; bad=0
say()  { printf '  %-44s %s\n' "$1" "$2"; }
pass() { say "$1" "OK${2:+ — $2}"; ok=$((ok+1)); }
fail() { say "$1" "FAIL${2:+ — $2}"; bad=$((bad+1)); }

echo "AirPlay preflight"

# 1. the device
if [ -n "$(A devices 2>/dev/null | sed -n '2p')" ]; then
  pass "adb device" "$(A devices 2>/dev/null | sed -n '2p' | awk '{print $1}')"
else
  fail "adb device" "not attached"; echo; echo "nothing else can be checked"; exit 1
fi

# 2. the app. Capture first and match in the shell: `cmd | grep -q` exits
# early, the adb on the left takes SIGPIPE, and with pipefail that reads as a
# failure even when the match succeeded.
pkgs=$(A shell 'pm list packages' 2>/dev/null)
case $pkgs in *org.lineageos.tv.airplay*) pass "receiver installed";;
             *) fail "receiver installed";; esac
procs=$(A shell 'ps -A' 2>/dev/null)
case $procs in *org.lineageos.tv.airplay*) pass "receiver running";;
             *) fail "receiver running" "starts on demand once enabled";; esac

# 3. its state, straight from the provider
state=$(A shell 'content call --uri content://org.lineageos.tv.airplay.settings --method get' 2>/dev/null | tr -d '\r')
enabled=$(sed -n 's/.*enabled=\([a-z]*\).*/\1/p' <<<"$state")
pin=$(sed -n 's/.*require_pin=\([a-z]*\).*/\1/p' <<<"$state")
paired=$(sed -n 's/.*paired_names=\[\([^]]*\)\].*/\1/p' <<<"$state")
name=$(sed -n 's/.*name=\([^,}]*\).*/\1/p' <<<"$state")
if [ "$enabled" = "true" ]; then pass "AirPlay enabled" "as \"$name\""
else fail "AirPlay enabled" "Settings > System > AirPlay, or scripts/airplay/tvnav.py go AirPlay"; fi
if [ "$pin" = "true" ]; then pass "PIN required for new devices"
else say "PIN required for new devices" "off — fine, but the pairing checks need it on"; fi
if [ -z "$paired" ]; then pass "paired devices" "none, a clean first-pairing test"
else say "paired devices" "$paired  (FORGET_DEVICES to retest pairing)"; fi

# 4. the network, and what a sender actually sees
ip=$(A shell 'ip -4 addr show eth0' 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p')
[ -n "$ip" ] && pass "network" "eth0 $ip" || fail "network" "no address on eth0"
seen=$(python3 "$HERE/mdns_probe.py" -t 6 2>/dev/null)
case $seen in *[Jj]etson*) pass "advertising over mDNS" "visible to senders on this LAN";;
            *) fail "advertising over mDNS" "senders will not list it";; esac

# 5. the ELD self-test fixture, which a wipe removes. Only root can stat
# inside the app's data dir, so without it we cannot tell present from
# missing -- say so rather than reporting a false "missing".
eld=$(A shell 'ls /data/user/0/org.lineageos.tv.airplay/files/selftest-eld.bin 2>&1' | tr -d '\r')
case $eld in
  *selftest-eld.bin) case $eld in *Permission*|*denied*)
        say "selftest-eld.bin" "cannot check without adb root";;
     *) pass "selftest-eld.bin present";; esac;;
  *) say "selftest-eld.bin" "missing — only the 'eld' self-test needs it";;
esac

# 6. optional: prove the renderers and the HDMI audio path
if [ "${1:-}" = "--tone" ]; then
  echo
  echo "self-test (first run is discarded: a cold codec costs ~40 ms and drops frames)"
  for run in warmup measured; do
    A logcat -c >/dev/null 2>&1
    A shell 'am start-foreground-service -a org.lineageos.tv.airplay.SELFTEST \
      -n org.lineageos.tv.airplay/.AirPlayService --ei seconds 14 --es audio pcm' >/dev/null 2>&1
    if [ "$run" = measured ]; then
      sleep 2
      python3 "$HERE/tone_check.py" -s 11 2>/dev/null | sed 's/^/  /'
    fi
    sleep 5
    [ "$run" = measured ] && A logcat -d -s AirPlay 2>/dev/null \
      | grep -E 'audio:|video:' | sed 's/^/  /'
  done
fi

echo
echo "$ok ok, $bad failed"
[ "$bad" -eq 0 ] && echo "ready — checklist is in docs/airplay.md, \"Testing with an iPhone or Mac\""
exit "$bad"
