#!/usr/bin/env bash
#
# Host prerequisites for the containerised LineageOS build environment.
# Everything in here needs root; everything else in this repo does not.
#
#     sudo bash scripts/host-setup.sh
#
# It will:
#   1. install Docker Engine + the compose v2 plugin from Docker's own apt repo
#   2. add your user to the docker group
#   3. give a build disk a stable mountpoint via /etc/fstab (optional)
#   4. create the three build directories and chown them to you
#
# Idempotent — safe to re-run. Skips anything already done.
#
# Override any of these:
#   BUILD_DISK_UUID=...    filesystem UUID of the disk to mount (blkid)
#   MOUNTPOINT=/srv/build  where to mount it
#   PROJECT_DIR=jetson-tv  subdirectory under the mountpoint
#   SKIP_DOCKER=1          leave Docker alone
#   SKIP_DISK=1            leave fstab and mounting alone
#   ASSUME_YES=1           don't prompt
#
set -euo pipefail

MOUNTPOINT="${MOUNTPOINT:-/srv/build}"
PROJECT_DIR="${PROJECT_DIR:-jetson-tv}"
BUILD_DISK_UUID="${BUILD_DISK_UUID:-}"
SKIP_DOCKER="${SKIP_DOCKER:-0}"
SKIP_DISK="${SKIP_DISK:-0}"
ASSUME_YES="${ASSUME_YES:-0}"

die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
note() { printf '\033[36m==>\033[0m %s\n' "$*"; }
skip() { printf '    \033[32m(already done)\033[0m %s\n' "$*"; }

[[ $EUID -eq 0 ]] || die "run me with sudo: sudo bash $0"

# Who to hand ownership to. Under sudo the invoking user is in SUDO_USER.
TARGET_USER="${SUDO_USER:-root}"
[[ "$TARGET_USER" != "root" ]] || die "run via sudo from your normal user, not as root directly"
TARGET_UID="$(id -u "$TARGET_USER")"
TARGET_GID="$(id -g "$TARGET_USER")"

confirm() {
    [[ "$ASSUME_YES" == "1" ]] && return 0
    local reply
    read -r -p "$1 [y/N] " reply </dev/tty
    [[ "$reply" =~ ^[Yy]$ ]]
}

# ---------------------------------------------------------------------------
# 1 + 2. Docker
# ---------------------------------------------------------------------------
install_docker() {
    if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
        skip "docker $(docker --version | awk '{print $3}' | tr -d ,) with compose v2"
    else
        note "installing Docker Engine + compose plugin from download.docker.com"
        . /etc/os-release
        install -m 0755 -d /etc/apt/keyrings
        if [[ ! -f /etc/apt/keyrings/docker.asc ]]; then
            curl -fsSL "https://download.docker.com/linux/ubuntu/gpg" \
                 -o /etc/apt/keyrings/docker.asc
            chmod a+r /etc/apt/keyrings/docker.asc
        fi
        printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu %s stable\n' \
            "$(dpkg --print-architecture)" "$VERSION_CODENAME" \
            > /etc/apt/sources.list.d/docker.list
        apt-get update -qq
        apt-get install -y \
            docker-ce docker-ce-cli containerd.io \
            docker-buildx-plugin docker-compose-plugin
        systemctl enable --now docker
    fi

    if id -nG "$TARGET_USER" | tr ' ' '\n' | grep -qx docker; then
        skip "$TARGET_USER is in the docker group"
    else
        note "adding $TARGET_USER to the docker group"
        usermod -aG docker "$TARGET_USER"
        NEEDS_RELOGIN=1
    fi
}

# ---------------------------------------------------------------------------
# 3. Stable mountpoint
#
# The desktop auto-mounts removable-looking disks under /media/$USER/<uuid>,
# which is fine for a file manager and useless for a bind mount in a compose
# file: the path is ugly, and it only exists once someone has logged into a
# graphical session. An fstab entry fixes both.
#
# `nofail` matters — without it, a disk that is missing or renamed at boot
# drops the machine into an emergency shell instead of booting.
# ---------------------------------------------------------------------------
setup_disk() {
    [[ -n "$BUILD_DISK_UUID" ]] || die "BUILD_DISK_UUID is not set.
  Find it with:  lsblk -o NAME,SIZE,FSTYPE,UUID,MOUNTPOINT
  Then re-run:   sudo BUILD_DISK_UUID=<uuid> bash $0"

    local dev="/dev/disk/by-uuid/$BUILD_DISK_UUID"
    [[ -e "$dev" ]] || die "no filesystem with UUID $BUILD_DISK_UUID"

    if grep -q "$BUILD_DISK_UUID" /etc/fstab; then
        skip "fstab already has an entry for $BUILD_DISK_UUID"
    else
        local fstype line
        fstype="$(blkid -o value -s TYPE "$dev")"
        line="UUID=$BUILD_DISK_UUID  $MOUNTPOINT  $fstype  defaults,nofail,x-gvfs-hide  0  2"

        printf '\n  about to append to /etc/fstab:\n    %s\n\n' "$line"
        confirm "  add it?" || die "declined; nothing changed"

        cp -a /etc/fstab "/etc/fstab.bak.$(date +%Y%m%d%H%M%S)"
        printf '\n# Build disk for the Jetson Nano / LineageOS tree. nofail so a missing\n# disk never blocks boot.\n%s\n' "$line" >> /etc/fstab
        note "wrote fstab entry (previous fstab backed up alongside it)"
    fi

    # If the desktop already grabbed it, hand it back before mounting properly.
    local current
    current="$(findmnt -no TARGET --source "$dev" 2>/dev/null | head -1 || true)"
    if [[ -n "$current" && "$current" != "$MOUNTPOINT" ]]; then
        note "unmounting desktop auto-mount at $current"
        umount "$current" || die "could not unmount $current — close anything using it and re-run"
    fi

    mkdir -p "$MOUNTPOINT"
    if findmnt -no TARGET "$MOUNTPOINT" >/dev/null 2>&1; then
        skip "$MOUNTPOINT is mounted"
    else
        note "mounting $MOUNTPOINT"
        systemctl daemon-reload
        mount "$MOUNTPOINT"
    fi
    findmnt -o TARGET,SOURCE,FSTYPE,SIZE,AVAIL "$MOUNTPOINT"
}

# ---------------------------------------------------------------------------
# 4. Build directories
# ---------------------------------------------------------------------------
setup_dirs() {
    local base="$MOUNTPOINT/$PROJECT_DIR" d
    for d in lineage ccache dlcache; do
        mkdir -p "$base/$d"
    done
    chown -R "$TARGET_UID:$TARGET_GID" "$base"
    note "build directories under $base owned by $TARGET_USER"
    ls -la "$base"
}

# ---------------------------------------------------------------------------

NEEDS_RELOGIN=0

if [[ "$SKIP_DOCKER" == "1" ]]; then note "skipping Docker"; else install_docker; fi
if [[ "$SKIP_DISK"   == "1" ]]; then note "skipping disk setup"; else setup_disk; fi
setup_dirs

printf '\n\033[1mHost setup complete.\033[0m\n'
if [[ "$NEEDS_RELOGIN" == "1" ]]; then
    cat <<EOF

  One more thing: you were just added to the docker group, and group
  membership is only picked up at login. Either log out and back in, or
  start a shell that has it:

      exec su - $TARGET_USER

  Check with:  docker run --rm hello-world
EOF
fi
printf '\nNext:  ./scripts/jetson-build image\n\n'
