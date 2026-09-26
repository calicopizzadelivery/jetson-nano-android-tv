#!/usr/bin/env bash
#
# Local changes we deliberately carry on top of the synced LineageOS trees.
#
# These are real modifications to upstream repos, not workarounds for a broken
# checkout, so they live here rather than as hand edits: `repo sync` will
# revert them and this puts them back. Every change is idempotent, and each one
# says what it is for and how to tell if upstream has made it unnecessary.
#
set -euo pipefail

SRC="${SRC:-/srv/lineage}"
note() { echo "    [local] $*"; }

# ---------------------------------------------------------------------------
# 1. Bluetooth Low Energy
#
# net/bluetooth/Kconfig has `config BT_LE ... default y`, but every tegra
# defconfig ships "# CONFIG_BT_LE is not set", so the kernel we build has no
# BLE at all. Android TV remotes are BLE, so this makes remote pairing
# impossible regardless of which radio is fitted, and it is a plausible root
# cause for the long-standing "BLE doesn't work on ARM64 Tegra" reports.
#
# Drop this when a defconfig arrives with CONFIG_BT_LE=y already set.
# ---------------------------------------------------------------------------
enable_bt_le() {
    local cfgdir="${SRC}/kernel/nvidia/kernel-4.9/arch/arm64/configs"
    [[ -d ${cfgdir} ]] || return 0
    local f changed=0
    for f in tegra_android_defconfig tegra_android_recovery_defconfig \
             tegra_defconfig defconfig; do
        [[ -f ${cfgdir}/${f} ]] || continue
        if grep -q '^# CONFIG_BT_LE is not set' "${cfgdir}/${f}"; then
            sed -i 's|^# CONFIG_BT_LE is not set|CONFIG_BT_LE=y|' "${cfgdir}/${f}"
            note "BT_LE enabled in ${f}"
            changed=1
        fi
    done
    [[ ${changed} -eq 0 ]] && note "BT_LE already enabled"
    return 0
}

# ---------------------------------------------------------------------------
# 2. wifi_loader.sh: wrong module path, and Broadcom-only card detection
#
# Two separate defects in device/nvidia/tegra-common/initfiles/wifi_loader.sh:
#
#   a) It insmods from /system/lib/modules, which does not exist on this
#      build — the modules are installed to /vendor/lib/modules. So the
#      script cannot load *any* wifi module, Broadcom included.
#
#   b) perform_enumeration() only scans for Broadcom vendor ids (0x02d0 SDIO,
#      0x14e4 PCIe). A Realtek card is never matched, `vendor` stays empty and
#      it logs "WiFi auto card detection fail" — even though the tree builds
#      and ships a working rtl8822ce.ko from kernel/nvidia/nvidia.
#
# Together these mean an RTL8822CE card looks completely dead while the driver
# sits unloaded on the filesystem. That is the most likely explanation for the
# "RTL8822CE tested, does not work" note in this project's history.
#
# RTL8822CE is PCI 10ec:c822. Its Bluetooth is a separate USB function handled
# by btusb/btrtl, not by this script.
# ---------------------------------------------------------------------------
fix_wifi_loader() {
    local f="${SRC}/device/nvidia/tegra-common/initfiles/wifi_loader.sh"
    [[ -f ${f} ]] || return 0

    if grep -q '/system/lib/modules' "${f}"; then
        sed -i 's|/system/lib/modules|/vendor/lib/modules|g' "${f}"
        note "wifi_loader.sh: module path -> /vendor/lib/modules"
    fi

    if ! grep -q 'RTLK_PCIE' "${f}"; then
        sed -i 's|^BRCM_PCIE=0x14e4$|BRCM_PCIE=0x14e4\nRTLK_PCIE=0x10ec|' "${f}"
        sed -i 's|if \[ "$vendor" = "$BRCM_PCIE" \]; then|if [ "$vendor" = "$BRCM_PCIE" -o "$vendor" = "$RTLK_PCIE" ]; then|' "${f}"
        note "wifi_loader.sh: detect Realtek PCIe (0x10ec)"
    fi

    if ! grep -q 'rtl8822ce.ko' "${f}"; then
        python3 - "$f" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
anchor = """elif [ $device = "0x4355" -o $device = "0x43ef" ]; then
	if [ -e /vendor/lib/modules/bcmdhd_pcie.ko ]; then
		/vendor/bin/log -t "wifiloader" -p i "load bcmdhd_pcie module"
		insmod /vendor/lib/modules/bcmdhd_pcie.ko
	fi
fi"""
add = """elif [ $device = "0x4355" -o $device = "0x43ef" ]; then
	if [ -e /vendor/lib/modules/bcmdhd_pcie.ko ]; then
		/vendor/bin/log -t "wifiloader" -p i "load bcmdhd_pcie module"
		insmod /vendor/lib/modules/bcmdhd_pcie.ko
	fi
elif [ $device = "0xc822" ]; then
	# RTL8822CE. cfg80211 first: the Realtek driver is built against it and
	# nothing else pulls it in on a board with no Broadcom radio fitted.
	if [ -e /vendor/lib/modules/cfg80211.ko ]; then
		insmod /vendor/lib/modules/cfg80211.ko 2>/dev/null
	fi
	if [ -e /vendor/lib/modules/rtl8822ce.ko ]; then
		/vendor/bin/log -t "wifiloader" -p i "load rtl8822ce module"
		insmod /vendor/lib/modules/rtl8822ce.ko
	fi
fi"""
assert s.count(anchor) == 1, "anchor not found once in wifi_loader.sh"
open(p, "w").write(s.replace(anchor, add))
PY
        note "wifi_loader.sh: load rtl8822ce.ko for 10ec:c822"
    fi
    return 0
}

# ---------------------------------------------------------------------------
# 3. decodetest
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
# 4. AmbientDream
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

enable_bt_le
fix_wifi_loader
install_decodetest
install_ambient_dream
note "done"
