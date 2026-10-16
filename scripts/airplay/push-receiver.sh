#!/usr/bin/env bash
#
# Push a rebuilt AirPlay receiver to the Jetson without a flash.
#
#   ./push-receiver.sh          # native library only (the usual case)
#   ./push-receiver.sh --app    # the app as well, for a Java change
#
# A userdebug build lets /system be remounted, so iterating on UxPlay is a
# push and a reboot rather than a sideload. Two things make this less obvious
# than it looks, and both have cost a round before:
#
#   - The app's lib/arm64/libuxplay_jni.so is a SYMLINK into /system/lib64,
#     so pushing the app directory alone runs new Java against old native
#     code. The library is the thing that has to move.
#   - The odex and vdex go with the APK, or ART rejects the stale pair and
#     silently runs the app interpreted.
#
# SPDX-License-Identifier: Apache-2.0
set -uo pipefail

OUT=/srv/build/jetson-tv/lineage/out/target/product/porg
A() { docker exec adbnode adb "$@"; }

push_app=0
[[ "${1:-}" == "--app" ]] && push_app=1

if ! A devices 2>/dev/null | sed -n '2p' | grep -q device; then
  echo "no adb device. Is the adbnode container up, and is the Jetson powered?" >&2
  echo "  cd ../qtpy-relay-controller && ./tools/jetson-power.py cycle" >&2
  exit 1
fi

lib=$OUT/system/lib64/libuxplay_jni.so
[[ -f $lib ]] || { echo "not built: $lib" >&2; exit 1; }
echo "==> libuxplay_jni.so  $(date -r "$lib" '+%Y-%m-%d %H:%M')"

A root >/dev/null && sleep 2
A remount 2>&1 | grep -v "verity" || true   # "Error setting verity state" is normal

docker cp "$lib" adbnode:/tmp/libuxplay_jni.so
docker exec adbnode adb push /tmp/libuxplay_jni.so /system/lib64/ || exit 1

if (( push_app )); then
  app=$OUT/system/system_ext/priv-app/AirPlayReceiver
  [[ -d $app ]] || { echo "not built: $app" >&2; exit 1; }
  echo "==> AirPlayReceiver  $(date -r "$app" '+%Y-%m-%d %H:%M')"
  docker cp "$app/." adbnode:/tmp/AirPlayReceiver/
  docker exec adbnode adb push /tmp/AirPlayReceiver/. /system_ext/priv-app/AirPlayReceiver/ || exit 1
  A shell restorecon -R /system_ext/priv-app/AirPlayReceiver
fi

A shell restorecon /system/lib64/libuxplay_jni.so
echo "==> rebooting"
A reboot
