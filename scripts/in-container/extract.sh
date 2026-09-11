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

# shellcheck disable=SC1091
source build/envsetup.sh

# The first breakfast is expected to fail if vendor/ has not been populated
# yet — that is exactly what the LineageOS guide warns about. Let it.
echo "==> breakfast ${DEVICE} (first pass; a vendor makefile error here is normal)"
if ! breakfast "$DEVICE"; then
    echo "    first breakfast failed as expected; continuing to blob extraction"
fi

# Some Tegra targets need otatools present before the extract script runs.
echo "==> m otatools"
m otatools

echo "==> extracting proprietary files into vendor/nvidia (cache: ${CACHE})"
cd "device/nvidia/${DEVICE}"
if [[ -x ./extract-files.py ]]; then
    ./extract-files.py
else
    # -c / --cache-dir keeps the downloaded L4T and OTA archives outside the
    # tree so a `repo sync` or a tree wipe does not cost you the re-download.
    # If your checkout's extract-files.sh does not accept -c, drop the flag.
    ./extract-files.sh -c "$CACHE" || ./extract-files.sh
fi

cd "$SRC"
echo "==> breakfast ${DEVICE} (second pass, should succeed now)"
breakfast "$DEVICE"

echo "==> done. Next: ./scripts/jetson-build build"
