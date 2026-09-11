#!/usr/bin/env bash
#
# repo init (first run) + repo sync. Runs inside the container.
#
set -euo pipefail

SRC=/srv/lineage
BRANCH="${LINEAGE_BRANCH:-lineage-22.2}"
MANIFEST="https://github.com/LineageOS/android.git"

cd "$SRC"

if [[ ! -d .repo ]]; then
    echo "==> repo init on ${BRANCH}"
    repo init -u "$MANIFEST" -b "$BRANCH" --git-lfs --no-clone-bundle
else
    current="$(repo info -o 2>/dev/null | awk '/Manifest branch/{print $3}' | head -1 || true)"
    if [[ -n "$current" && "$current" != *"$BRANCH"* ]]; then
        echo "==> switching manifest from ${current} to ${BRANCH}"
        repo init -u "$MANIFEST" -b "$BRANCH" --git-lfs --no-clone-bundle
    fi
fi

# Deliberately no -j / -c flags: the LineageOS manifest ships sensible repo
# defaults (-j4 -c) and the project explicitly recommends not overriding them.
# If your connection chokes, run `repo sync -j2` by hand from `jetson-build shell`.
echo "==> repo sync (first run pulls a few hundred GB; go and do something else)"
repo sync

echo "==> done. Next: ./scripts/jetson-build extract"
