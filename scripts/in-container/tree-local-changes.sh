#!/usr/bin/env bash
#
# Install our own sources into the synced tree.
#
# This used to also patch upstream repos in place -- CONFIG_BT_LE in the tegra
# defconfigs, and the two wifi_loader.sh defects. Those are now real commits on
# our forks of kernel/nvidia/kernel-4.9 and device/nvidia/tegra-common, synced
# by local-manifest.sh, so nothing here edits code we do not own.
#
# What is left is vendor/jetson-tv: it is ours, it is not a repo project, and
# `repo sync` neither provides nor removes it. Every step is idempotent.
#
set -euo pipefail

SRC="${SRC:-/srv/lineage}"
note() { echo "    [local] $*"; }

# ---------------------------------------------------------------------------
# 1. decodetest
#
# This build ships no video player and nothing registers for a video/* VIEW
# intent, so there is no way to tell a hardware decoder from a software
# fallback by using the device. decodetest drives NDK MediaCodec directly and
# prints the component the framework picked, which makes
# "OMX.Nvidia.h264.decode" vs "c2.android.avc.decoder" unambiguous.
#
# It is copied into vendor/ rather than committed to an upstream repo, and
# vendor/jetson-tv is ours, so extract-files.sh's vendor cleaning (which only
# touches vendor/nvidia) leaves it alone.
#
#   m decodetest && adb push $OUT/vendor/bin/decodetest /data/local/tmp/
#   adb shell /data/local/tmp/decodetest /sdcard/clip.mp4
# ---------------------------------------------------------------------------
install_decodetest() {
    local src="/opt/jetson-tv/decodetest"
    local dst="${SRC}/vendor/jetson-tv/decodetest"
    [[ -d ${src} ]] || return 0
    mkdir -p "${dst}"
    local changed=0 f
    for f in decodetest.cpp Android.bp; do
        if ! cmp -s "${src}/${f}" "${dst}/${f}"; then
            cp "${src}/${f}" "${dst}/${f}"; changed=1
        fi
    done
    [[ ${changed} -eq 1 ]] && note "decodetest installed to vendor/jetson-tv" \
                           || note "decodetest already current"
    return 0
}

# ---------------------------------------------------------------------------
# 2. raopsend
#
# A classic-RAOP sender for testing the AirPlay receiver from the device
# itself. It has to run there: RAOP carries audio over UDP, adb forward is TCP
# only, and the emulator console's redir targets eth0 while the emulator's
# IPv4 address lands on wlan0.
#
#   m raopsend && adb push $OUT/system/bin/raopsend /data/local/tmp/
#   adb shell /data/local/tmp/raopsend 127.0.0.1 5000 6
# ---------------------------------------------------------------------------
install_raopsend() {
    local src="/opt/jetson-tv/raopsend"
    local dst="${SRC}/vendor/jetson-tv/raopsend"
    [[ -d ${src} ]] || return 0
    if [[ -d ${dst} ]] && diff -rq "${src}" "${dst}" >/dev/null 2>&1; then
        note "raopsend already current"
        return 0
    fi
    rm -rf "${dst}"
    mkdir -p "$(dirname "${dst}")"
    cp -r "${src}" "${dst}"
    note "raopsend installed to vendor/jetson-tv"
    return 0
}

# ---------------------------------------------------------------------------
# 3. AmbientDream
#
# A clock over a slow slideshow of NASA photographs, for a box that is expected
# to always be driving a television. Copied into vendor/ for the same reason as
# decodetest: vendor/jetson-tv is ours, and extract-files.sh only cleans
# vendor/nvidia.
#
#   m AmbientDream && adb install -r $OUT/product/app/AmbientDream/AmbientDream.apk
#   settings put secure screensaver_components \
#       org.lineageos.tv.ambient/org.lineageos.tv.ambient.AmbientDreamService
# ---------------------------------------------------------------------------
install_ambient_dream() {
    local src="/opt/jetson-tv/AmbientDream"
    local dst="${SRC}/vendor/jetson-tv/AmbientDream"
    [[ -d ${src} ]] || return 0
    if [[ -d ${dst} ]] && diff -rq "${src}" "${dst}" >/dev/null 2>&1; then
        note "AmbientDream already current"
        return 0
    fi
    rm -rf "${dst}"
    mkdir -p "$(dirname "${dst}")"
    cp -r "${src}" "${dst}"
    note "AmbientDream installed to vendor/jetson-tv"
    return 0
}

# The AirPlay control service: holds audio focus, sets the property init
# watches, and turns Shairport's metadata pipe into a MediaSession.
install_airplay_receiver() {
    local src="/opt/jetson-tv/AirPlayReceiver"
    local dst="${SRC}/vendor/jetson-tv/AirPlayReceiver"
    [[ -d ${src} ]] || return 0
    if [[ -d ${dst} ]] && diff -rq "${src}" "${dst}" >/dev/null 2>&1; then
        note "AirPlayReceiver already current"
        return 0
    fi
    rm -rf "${dst}"
    mkdir -p "$(dirname "${dst}")"
    cp -r "${src}" "${dst}"
    note "AirPlayReceiver installed to vendor/jetson-tv"
    return 0
}

# JetsonTV's name and default wallpaper: see Branding/Android.bp.
install_branding() {
    local src="/opt/jetson-tv/Branding"
    local dst="${SRC}/vendor/jetson-tv/Branding"
    [[ -d ${src} ]] || return 0
    if [[ -d ${dst} ]] && diff -rq "${src}" "${dst}" >/dev/null 2>&1; then
        note "Branding already current"
        return 0
    fi
    rm -rf "${dst}"
    mkdir -p "$(dirname "${dst}")"
    cp -r "${src}" "${dst}"
    note "Branding installed to vendor/jetson-tv"
    return 0
}

# The product makefile that puts AmbientDream in the image. Lives beside the
# app rather than in device/nvidia/porg so that porg's own tree carries only an
# inherit-product-if-exists and still builds without us.
install_vendor_mk() {
    local src="/opt/jetson-tv/jetson-tv.mk"
    local dst="${SRC}/vendor/jetson-tv/jetson-tv.mk"
    [[ -f ${src} ]] || return 0
    if cmp -s "${src}" "${dst}"; then
        note "vendor/jetson-tv/jetson-tv.mk already current"
        return 0
    fi
    mkdir -p "$(dirname "${dst}")"
    cp "${src}" "${dst}"
    note "vendor/jetson-tv/jetson-tv.mk installed"
    return 0
}

# Third-party apps built into the image (Moonlight, ...), pinned in
# PrebuiltApps/apps.json: fetched into /dlcache, checked, and turned into
# modules under vendor/jetson-tv/PrebuiltApps. See prebuilt_apps.py for why
# it takes the shape it does.
install_prebuilt_apps() {
    SRC="${SRC}" DLCACHE="${DLCACHE:-/dlcache}" python3 /opt/jetson-tv/prebuilt_apps.py
}

install_decodetest
install_raopsend
install_ambient_dream
install_airplay_receiver
install_branding
install_prebuilt_apps
install_vendor_mk
note "done"
