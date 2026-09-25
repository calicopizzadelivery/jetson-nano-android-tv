#!/usr/bin/env bash
#
# Set up the device tree and pull the NVIDIA proprietary bits. Runs in-container.
#
# For porg this does NOT need a device attached over adb. The Tegra extract
# tooling downloads what it needs: public L4T tarballs (r32.7.6 / r32.6.1) for
# firmware, bootloader and tegraflash, and SHIELD recovery OTA images for the
# GPU userspace (libEGL_tegra, vulkan.tegra, gralloc.tegra), the NVMM/OpenMAX
# media stack, Dolby audio libs and the BCM4356/BCM4354 wifi+bluetooth firmware.
#
set -euo pipefail

SRC=/srv/lineage
DEVICE="${LINEAGE_DEVICE:-porg}"
CACHE=/dlcache

cd "$SRC"
[[ -d .repo ]] || { echo "no source tree yet — run 'jetson-build sync' first" >&2; exit 1; }

# AOSP's envsetup.sh is not nounset-safe: _gettop_once tests "$TOP" before
# anything assigns it, so `set -u` aborts the source at build/envsetup.sh:21.
# The build functions it defines (breakfast, m, mka) have the same habit, so
# nounset stays off for the rest of the script. -e and pipefail remain on.
set +u

# shellcheck disable=SC1091
source build/envsetup.sh

# The first breakfast is expected to fail if vendor/ has not been populated
# yet — that is exactly what the LineageOS guide warns about. Let it.
echo "==> breakfast ${DEVICE} (first pass; a vendor makefile error here is normal)"
if ! breakfast "$DEVICE"; then
    echo "    first breakfast failed as expected; continuing to blob extraction"
fi

# There is deliberately no `m otatools` here. An earlier revision of this
# script ran it before extraction, assuming Tegra needed it. It cannot work:
# every prebuilt_* module under device/nvidia/tegra-common/vendor depends on a
# generated "<file>_{32,64}-defaults" module, and extract_utils.sh (around line
# 473) only writes those into vendor/nvidia while extracting. With vendor/
# still empty, Soong fails analysis on all of them:
#
#   error: .../vendor/r35/l4t/Android.bp:1:1: "prebuilt_ld-linux-aarch64.so.1"
#          depends on undefined module "ld-linux-aarch64.so.1_64-defaults".
#
# extract-files.sh does not need otatools either. Its precheck
# (extract_utils.sh:84) wants only brotli, 7z, simg2img, tar, ar, zstd, wget,
# identify, convert and unlz4 — all installed in the image — plus the
# patchelf-0_18 prebuilt from prebuilts/extract-tools. Whatever needs otatools
# later builds it as a normal dependency.

echo "==> extracting proprietary files into vendor/nvidia (cache: ${CACHE})"
cd "device/nvidia/${DEVICE}"
if [[ -x ./extract-files.py ]]; then
    ./extract-files.py
else
    # The two cache flags are not interchangeable. -c / --cache-dir means
    # "extract from this already-primed cache" and gives up with
    #
    #   Cache is missing sources, please re-prime the cache.
    #
    # when the directory is empty; -p / --prime-cache is what fills it. So
    # prime first if -c finds nothing, then extract from the primed cache.
    #
    # An earlier revision fell back to a bare `./extract-files.sh` here, which
    # hid that message and re-downloaded roughly 20 GB into the container's
    # /tmp, leaving ${CACHE} empty and the next tree wipe just as expensive.
    # Never silently drop the cache: if priming fails, the whole step fails.
    if ! ./extract-files.sh -c "$CACHE"; then
        echo "    cache at ${CACHE} is not primed yet; priming it"
        ./extract-files.sh -p "$CACHE"
        ./extract-files.sh -c "$CACHE"
    fi
fi

cd "$SRC"
echo "==> breakfast ${DEVICE} (second pass, should succeed now)"
breakfast "$DEVICE"

echo "==> done. Next: ./scripts/jetson-build build"
