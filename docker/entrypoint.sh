#!/usr/bin/env bash
# Runs on every container start, as the unprivileged build user.
# Keep this cheap — it must not touch the 400 GB tree.
set -euo pipefail

# repo refuses to sync without a git identity.
if [[ -n "${GIT_USER_NAME:-}" ]]; then
    git config --global user.name "${GIT_USER_NAME}"
fi
if [[ -n "${GIT_USER_EMAIL:-}" ]]; then
    git config --global user.email "${GIT_USER_EMAIL}"
fi

# Stops duplicated Change-Id trailers when cherry-picking (per the LineageOS guide).
git config --global trailer.changeid.key "Change-Id"

# The tree is bind-mounted from the host; without this git treats every repo in
# it as "dubious ownership" whenever uid mapping is even slightly off.
git config --global --add safe.directory '*'

git lfs install --skip-repo >/dev/null 2>&1 || true

if [[ -w "${CCACHE_DIR:-/ccache}" ]]; then
    ccache -M "${CCACHE_SIZE:-50G}"    >/dev/null
    ccache -o compression=true          >/dev/null
fi

exec "$@"
