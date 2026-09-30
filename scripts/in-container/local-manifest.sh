#!/usr/bin/env bash
#
# Point the four projects we carry commits in at our forks, by installing
# manifests/jetson-tv.xml as a repo local manifest and syncing them.
#
# This replaces the patch queue as the *build* mechanism for those projects.
# patches/ stays, but as what it was always meant to be: changes formatted for
# posting upstream, not the thing that gets the code into the tree.
#
set -euo pipefail

SRC=/srv/lineage
SRCMANIFEST=/opt/jetson-tv/local-manifest.xml
DST="${SRC}/.repo/local_manifests/zz-jetson-tv.xml"

PROJECTS=(
    device/nvidia/porg
    device/nvidia/tegra-common
    kernel/nvidia/kernel-4.9
    packages/apps/TvSettings
    packages/apps/Catapult
    vendor/lineage
    external/shairport-sync
    external/popt
    external/uxplay
    external/libplist
)

note() { printf '    [manifest] %s\n' "$1"; }

# --check verifies without touching the network, for callers that only need to
# know the tree is still pointed at the forks (see build.sh). A plain `repo
# sync` re-reads the manifest and can quietly put these projects back on
# LineageOS, taking our commits out of the build without any visible error.
if [[ ${1:-} == --check ]]; then
    missing=0
    for p in "${PROJECTS[@]}"; do
        url="$(git -C "${SRC}/${p}" remote -v 2>/dev/null | awk 'NR==1{print $2}')"
        [[ ${url} == *calicopizzadelivery/* ]] || { echo "  ${p} is on ${url:-nothing}, not our fork" >&2; missing=1; }
    done
    if [[ ${missing} -eq 1 ]]; then
        echo "the forked projects are not in the tree — run 'jetson-build extract'," >&2
        echo "or scripts/in-container/local-manifest.sh by hand. See docs/forks.md" >&2
        exit 1
    fi
    note "all ${#PROJECTS[@]} forked projects present"
    exit 0
fi

[[ -f ${SRCMANIFEST} ]] || { note "no manifest to install"; exit 0; }
[[ -d ${SRC}/.repo ]] || { echo "no source tree yet — run 'jetson-build sync' first" >&2; exit 1; }

# Installing this before roomservice has run would be actively harmful: it puts
# device/nvidia/porg in the tree, so `breakfast porg` finds the device and never
# calls roomservice — and roomservice is what adds the other ~30 NVIDIA projects
# that porg depends on. Wait until roomservice has done its job.
if [[ ! -f ${SRC}/.repo/local_manifests/roomservice.xml ]]; then
    note "roomservice has not run yet; leaving the forks out of the manifest"
    note "this is the cold-tree case — 'extract' installs it after first breakfast"
    exit 0
fi

# A fork that is not reachable would otherwise fail deep inside repo sync with a
# git error. Say the actual thing instead, once, before touching the manifest.
fork="$(sed -n 's|.*name="\(calicopizzadelivery/[^"]*\)".*|\1|p' "${SRCMANIFEST}" | head -1)"
if ! git ls-remote --exit-code "https://github.com/${fork}" >/dev/null 2>&1; then
    echo "cannot reach https://github.com/${fork}" >&2
    echo "the forks this manifest points at do not exist or are unreachable;" >&2
    echo "see docs/forks.md" >&2
    exit 1
fi

mkdir -p "$(dirname "${DST}")"
if cmp -s "${SRCMANIFEST}" "${DST}"; then
    note "local manifest already current"
else
    cp "${SRCMANIFEST}" "${DST}"
    note "installed $(basename "${DST}")"
fi

# Sync only these four. A bare `repo sync` here would re-sync 1141 projects for
# no reason.
#
# --force-sync is required, not defensive: the project *name* changes
# (LineageOS/x -> calicopizzadelivery/x), so repo has to move the checkout to a
# different object directory and refuses without it. It does discard
# uncommitted work in these four paths, which is the trade — commit before
# running this.
note "syncing ${#PROJECTS[@]} forked projects"
cd "${SRC}"
repo sync --no-clone-bundle --force-sync "${PROJECTS[@]}"

for p in "${PROJECTS[@]}"; do
    note "$(printf '%-26s' "${p}") $(git -C "${SRC}/${p}" rev-parse --short HEAD)"
done
