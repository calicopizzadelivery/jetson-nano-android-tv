#!/usr/bin/env bash
#
# Build the installable zip + recovery image. Runs inside the container.
#
set -euo pipefail

SRC=/srv/lineage
DEVICE="${LINEAGE_DEVICE:-porg}"

cd "$SRC"
[[ -d .repo ]] || { echo "no source tree yet — run 'jetson-build sync' first" >&2; exit 1; }

# Soong cannot analyse this tree until the blobs have been extracted. The
# generated vendor/nvidia/common/exclude-bp.mk is what puts
#   -vendor/nvidia/common -device/nvidia/tegra-common/vendor
# at the front of PRODUCT_SOURCE_ROOT_DIRS, pruning every vendor branch from
# the blueprint scan and re-including only the rel-shield-r/* ones this device
# needs. Without it Soong parses r35 and r36 as well, and since those two carry
# byte-identical l4t/ and nvpmodel/ blueprints it dies on a dozen duplicate
# module names. Fail with the real cause rather than that.
[[ -f vendor/nvidia/common/exclude-bp.mk ]] || {
    echo "vendor/nvidia is not populated — run 'jetson-build extract' first" >&2
    exit 1
}

# AOSP's envsetup.sh is not nounset-safe: _gettop_once tests "$TOP" before
# anything assigns it, so `set -u` aborts the source at build/envsetup.sh:21.
# The build functions it defines (breakfast, m, mka) have the same habit, so
# nounset stays off for the rest of the script. -e and pipefail remain on.
set +u

# shellcheck disable=SC1091
source build/envsetup.sh

# breakfast rather than a hand-written lunch target: the target name gained a
# release-config segment in Android 15 (lineage_porg-bp1a-userdebug), and
# breakfast works that out for us across branches.
breakfast "$DEVICE"

start=$(date +%s)
if [[ -n "${JOBS:-}" ]]; then
    echo "==> mka bacon -j${JOBS}"
    mka bacon -j"${JOBS}"
else
    echo "==> mka bacon"
    mka bacon
fi
end=$(date +%s)

printf '==> build finished in %d min\n' $(( (end - start) / 60 ))
echo
echo "Artifacts in \$OUT (host: \$SRC_DIR/out/target/product/${DEVICE}):"
ls -lh "out/target/product/${DEVICE}"/lineage-*.zip \
       "out/target/product/${DEVICE}"/recovery.img 2>/dev/null || true
echo
echo "Flash from the HOST, not from in here — tegraflash needs USB recovery-mode"
echo "access and udev rules. See README.md, 'Flashing'."
