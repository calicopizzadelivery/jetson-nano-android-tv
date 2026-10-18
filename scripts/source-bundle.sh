#!/usr/bin/env bash
#
# Build the copyleft source bundle that has to sit beside a published image.
#
#   ./scripts/source-bundle.sh releases/jetsontv-0.1.0.xml [outdir]
#
# Reads a pinned manifest, archives every project that carries a copyleft
# obligation AT THE COMMIT THE MANIFEST NAMES, adds this repo, and writes
# jetsontv-<version>-source.tar.xz plus a MANIFEST.txt saying what is in it
# and why.
#
# Why a bundle at all, when the manifest already pins everything: GPL-2.0
# section 3 wants the source offered "from the same place" as the binary, and
# a manifest pointing at eleven GitHub repos is not that. See
# docs/source-release.md for which licence wants what, and for how long.
#
# The project list below is the one from that document, worked out with AOSP's
# compliance_listshare over an actual build. It is deliberately broader than
# the strict minimum: angle, icu and freetype are flagged only because of a
# few files or a dual licence Android takes the permissive half of, and
# shipping them costs a few hundred megabytes and removes the argument.
#
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

MANIFEST=${1:?usage: source-bundle.sh <pinned-manifest.xml> [outdir]}
OUTDIR=${2:-.}
TREE=${TREE:-/srv/build/jetson-tv/lineage}
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)

[[ -f $MANIFEST ]] || { echo "no such manifest: $MANIFEST" >&2; exit 1; }
[[ -d $TREE/.repo ]] || { echo "no repo tree at $TREE (set TREE=)" >&2; exit 1; }

VERSION=$(basename "$MANIFEST" .xml); VERSION=${VERSION#jetsontv-}
STAGE=$(mktemp -d -t jetsontv-src-XXXX)
trap 'rm -rf "$STAGE"' EXIT
BUNDLE="$OUTDIR/jetsontv-${VERSION}-source.tar.xz"

# Projects that carry a copyleft obligation. Paths are manifest paths.
# Kernel and modules are first because they are the largest and the clearest.
PATHS=(
  kernel/nvidia/kernel-4.9 kernel/nvidia/nvidia kernel/nvidia/nvgpu
  kernel/nvidia/cypress-fmac kernel/nvidia/exfat
  external/uxplay external/libplist
  external/angle external/bcc external/dnsmasq external/e2fsprogs
  external/erofs-utils external/exfatprogs external/f2fs-tools
  external/freetype external/fsverity-utils external/gptfdisk
  external/hyphenation-patterns external/icu external/iproute2
  external/iptables external/iputils external/libchrome external/libexif
  external/libnl external/libxml2 external/ntfs-3g external/openthread
  external/selinux external/zstd packages/modules/NeuralNetworks
  device/nvidia/porg device/nvidia/tegra-common vendor/lineage
  packages/apps/TvSettings packages/apps/Catapult
)

echo "==> reading $MANIFEST"
mapfile -t PINS < <(python3 - "$MANIFEST" <<'PY'
import sys, re, io
s = io.open(sys.argv[1], encoding='utf-8').read()
for m in re.finditer(r'<project\b[^>]*>', s):
    tag = m.group(0)
    path = re.search(r'path="([^"]+)"', tag)
    name = re.search(r'name="([^"]+)"', tag)
    rev  = re.search(r'revision="([0-9a-f]{40})"', tag)
    if rev:
        print((path.group(1) if path else name.group(1)), rev.group(1))
PY
)
declare -A REV
for line in "${PINS[@]}"; do REV["${line%% *}"]="${line##* }"; done
echo "    $((${#REV[@]})) projects pinned"

mkdir -p "$STAGE/source"
{
  echo "JetsonTV $VERSION — copyleft source bundle"
  echo
  echo "Every project below is archived at the exact commit named in"
  echo "$(basename "$MANIFEST"), which is the commit the released image was"
  echo "built from. See docs/source-release.md for which licence requires"
  echo "what, and scripts/README or the top-level README for how to build."
  echo
  printf '%-44s %s\n' "PROJECT" "COMMIT"
} > "$STAGE/source/MANIFEST.txt"

missing=0; included=0
for p in "${PATHS[@]}"; do
  sha=${REV[$p]:-}
  if [[ -z $sha ]]; then
    echo "    !! not in manifest: $p" >&2; missing=$((missing+1)); continue
  fi
  if [[ ! -d $TREE/$p/.git ]]; then
    echo "    !! not checked out: $p" >&2; missing=$((missing+1)); continue
  fi
  mkdir -p "$STAGE/source/$p"
  if ! git -C "$TREE/$p" archive "$sha" 2>/dev/null | tar -x -C "$STAGE/source/$p"; then
    echo "    !! $p has no commit $sha — fetch it first" >&2; missing=$((missing+1)); continue
  fi
  printf '%-44s %s\n' "$p" "$sha" >> "$STAGE/source/MANIFEST.txt"
  included=$((included+1))
  printf '\r    archived %d/%d' "$included" "${#PATHS[@]}"
done
echo

# this repo: GPL-3 counts the scripts that build and install the work
mkdir -p "$STAGE/source/jetson-tv-build"
git -C "$REPO_ROOT" archive HEAD | tar -x -C "$STAGE/source/jetson-tv-build"
printf '%-44s %s\n' "(this repo)" "$(git -C "$REPO_ROOT" rev-parse HEAD)" \
  >> "$STAGE/source/MANIFEST.txt"
cp "$MANIFEST" "$STAGE/source/"

echo "==> compressing (this takes a while; the tree alone is ~2.5 GB)"
mkdir -p "$OUTDIR"
tar -C "$STAGE/source" -cf - . | xz -T0 -6 > "$BUNDLE"

echo "==> $BUNDLE"
ls -lh "$BUNDLE" | awk '{print "    "$5}'
echo "    $included projects, $missing skipped"
sha256sum "$BUNDLE" | awk '{print "    sha256 "$1}'
[[ $missing -eq 0 ]] || { echo "    NOT COMPLETE: $missing project(s) missing" >&2; exit 1; }
