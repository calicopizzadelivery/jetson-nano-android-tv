#!/usr/bin/env bash
#
# Check the build environment is actually usable, before spending hours
# discovering it isn't. Runs inside the container.
#
#     ./scripts/jetson-build doctor
#
# Exits non-zero if any check fails.
#
set -uo pipefail

PASS=0
FAIL=0

ok()   { printf '  \033[32mok\033[0m    %-34s %s\n' "$1" "${2:-}"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %-34s %s\n' "$1" "${2:-}"; FAIL=$((FAIL+1)); }
info() { printf '  \033[36m--\033[0m    %-34s %s\n' "$1" "${2:-}"; }

check() { # check <label> <expected> <actual>
    if [[ "$2" == "$3" ]]; then ok "$1" "$3"; else bad "$1" "got '$3', want '$2'"; fi
}

printf '\n\033[1mIdentity\033[0m\n'
check "running as non-root"  "builder" "$(id -un)"
check "uid"                  "1000"    "$(id -u)"
check "gid"                  "1000"    "$(id -g)"

printf '\n\033[1mLimits\033[0m\n'
# Soong opens far more than the default 1024 descriptors and dies if it can't.
nofile="$(ulimit -n)"
if [[ "$nofile" -ge 32768 ]]; then ok "open file limit" "$nofile"
else bad "open file limit" "$nofile — need >=32768; check ulimits in docker-compose.yml"; fi

printf '\n\033[1mMounts\033[0m\n'
for m in /srv/lineage /ccache /dlcache; do
    if ! mountpoint -q "$m"; then
        bad "$m" "not a mountpoint — bind mount missing"
    elif [[ -w "$m" ]]; then
        ok "$m" "writable, $(df -h --output=avail "$m" | tail -1 | tr -d ' ') free"
    else
        bad "$m" "not writable — check host directory ownership"
    fi
done
if [[ -d /opt/jetson-tv ]]; then
    if touch /opt/jetson-tv/.wtest 2>/dev/null; then
        rm -f /opt/jetson-tv/.wtest
        bad "/opt/jetson-tv" "writable — should be mounted read-only"
    else
        ok "/opt/jetson-tv" "present, read-only"
    fi
else
    bad "/opt/jetson-tv" "missing"
fi

printf '\n\033[1mWrite-through ownership\033[0m\n'
# The whole point of matching uid/gid: files the build writes must land on the
# host owned by the host user, not by root.
probe=/srv/lineage/.doctor-probe
if touch "$probe" 2>/dev/null; then
    owner="$(stat -c '%u:%g' "$probe")"
    rm -f "$probe"
    check "new file ownership in tree" "1000:1000" "$owner"
else
    bad "new file ownership in tree" "could not create $probe"
fi

printf '\n\033[1mToolchain\033[0m\n'
for t in repo git git-lfs ccache python3 make bison flex bc zip lz4 rsync \
         xmllint openssl adb fastboot; do
    p="$(command -v "$t" 2>/dev/null)"
    if [[ -n "$p" ]]; then ok "$t" "$p"; else bad "$t" "not on PATH"; fi
done

printf '\n\033[1mConfiguration\033[0m\n'
gn="$(git config --global user.name  || true)"
ge="$(git config --global user.email || true)"
[[ -n "$gn" ]] && ok "git user.name"  "$gn" || bad "git user.name"  "unset — repo sync will refuse; set GIT_USER_NAME in .env"
[[ -n "$ge" ]] && ok "git user.email" "$ge" || bad "git user.email" "unset — repo sync will refuse; set GIT_USER_EMAIL in .env"

check "USE_CCACHE" "1" "${USE_CCACHE:-}"
check "CCACHE_DIR" "/ccache" "${CCACHE_DIR:-}"
cmax="$(ccache --get-config max_size 2>/dev/null)"
[[ -n "$cmax" ]] && ok "ccache max_size" "$cmax" || bad "ccache max_size" "could not read ccache config"

info "LINEAGE_BRANCH" "${LINEAGE_BRANCH:-unset}"
info "LINEAGE_DEVICE" "${LINEAGE_DEVICE:-unset}"
info "JOBS"           "${JOBS:-<unset — Soong decides>}"
info "nproc"          "$(nproc)"
info "RAM"            "$(free -h | awk '/^Mem:/{print $2}')"

printf '\n\033[1mTree\033[0m\n'
if [[ -d /srv/lineage/.repo ]]; then
    ok "source tree" "$(du -sh /srv/lineage 2>/dev/null | cut -f1) at /srv/lineage"
else
    info "source tree" "not synced yet — run: ./scripts/jetson-build sync"
fi

printf '\n'
if [[ "$FAIL" -eq 0 ]]; then
    printf '\033[32m%d checks passed.\033[0m\n\n' "$PASS"
else
    printf '\033[31m%d passed, %d failed.\033[0m\n\n' "$PASS" "$FAIL"
fi
exit $(( FAIL > 0 ))
