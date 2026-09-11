#!/usr/bin/env bash
#
# Host probe. Run once on thebe:
#
#     bash scripts/probe.sh
#
# Writes scripts/../probe-output.txt next to this repo, which Claude can read
# directly out of the connected folder. Everything here is read-only — it
# inspects, it does not change anything.
#
# Read the output before handing it over if you like; it contains your block
# device layout, fstab and docker state, and nothing else.
#
set -uo pipefail

OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/probe-output.txt"
: > "$OUT"

section() { printf '\n===== %s =====\n' "$1" >> "$OUT"; }
run()     { printf '\n$ %s\n' "$*" >> "$OUT"; "$@" >> "$OUT" 2>&1 || printf '(exit %d)\n' $? >> "$OUT"; }
runsh()   { printf '\n$ %s\n' "$1" >> "$OUT"; bash -c "$1" >> "$OUT" 2>&1 || printf '(exit %d)\n' $? >> "$OUT"; }

{
  printf 'probe generated %s on %s\n' "$(date -Is)" "$(hostname)"
} >> "$OUT"

section "System"
run uname -a
runsh 'cat /etc/os-release 2>/dev/null | head -8'
runsh 'echo "uid=$(id -u) gid=$(id -g) groups=$(id -Gn)"'
run nproc
runsh 'free -h'

section "Block devices"
runsh 'lsblk -o NAME,SIZE,TYPE,FSTYPE,LABEL,PARTLABEL,MOUNTPOINT'
runsh 'lsblk -dno NAME,SIZE,MODEL,ROTA,TRAN'

section "Mounts and free space"
runsh 'findmnt -t ext4,xfs,btrfs,zfs,f2fs,vfat,ntfs,ntfs3,exfat -o TARGET,SOURCE,FSTYPE,SIZE,USED,AVAIL,OPTIONS'
runsh 'df -hT -x tmpfs -x devtmpfs -x squashfs -x overlay'

section "UUIDs (for writing an fstab entry)"
runsh 'blkid 2>/dev/null || sudo -n blkid 2>/dev/null || echo "blkid needs root — re-run: sudo blkid"'

section "fstab"
runsh 'cat /etc/fstab'

section "Docker"
runsh 'docker --version 2>&1'
runsh 'docker compose version 2>&1'
runsh 'docker info --format "server: {{.ServerVersion}} | storage: {{.Driver}} | root: {{.DockerRootDir}} | cgroup: {{.CgroupVersion}}" 2>&1'
runsh 'id -nG | tr " " "\n" | grep -qx docker && echo "user IS in the docker group" || echo "user is NOT in the docker group (docker will need sudo)"'
runsh 'systemctl is-active docker 2>&1'

section "Docker disk usage"
runsh 'docker system df 2>&1'

section "Other container runtimes"
runsh 'podman --version 2>&1 || echo "podman not installed"'

printf '\n===== end =====\n' >> "$OUT"

echo "Wrote: $OUT"
echo
echo "Give it a look, then tell Claude it's ready — it can read that file directly."
