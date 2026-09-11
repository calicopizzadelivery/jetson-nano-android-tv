#!/usr/bin/env bash
#
# Build the installable zip + recovery image. Runs inside the container.
#
set -euo pipefail

SRC=/srv/lineage
DEVICE="${LINEAGE_DEVICE:-porg}"

cd "$SRC"
[[ -d .repo ]] || { echo "no source tree yet — run 'jetson-build sync' first" >&2; exit 1; }

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
