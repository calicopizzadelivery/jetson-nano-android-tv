# Project: Jetson Nano → Android TV box

Context for any Claude session working in this repo. Written by a Cowork session
on 2026-09-10 that had file access to this folder but no shell on the host.

## Goal

Turn NVIDIA Jetson Nano 4GB boards into usable TV boxes running **LineageOS
Android TV**, and build a reproducible, automated build environment for doing so.

Long-term: possibly extend to Jetson Orin (AGX / NX / Nano). Deferred for now.

## Hardware

Verified on the host 2026-09-10 (first session with an actual shell).

- Host build machine: `thebe`, **Ubuntu 24.04.4 LTS (noble)**, x86_64,
  **187 GB RAM / 112 cores**. No need to cap build parallelism — leave `JOBS`
  blank.
- **`sudo` requires a password here**, so Claude cannot run it. Anything
  needing root goes in `scripts/host-setup.sh` for the user to run.
- **Docker was not installed** (the original note claiming it was, was wrong).
  `scripts/host-setup.sh` installs Docker CE + compose v2 from Docker's own
  apt repo.
- Build disk: `nvme1n1p1`, 931.5 GB ext4, UUID
  `bb1724cf-c151-4944-90c6-23b72ca9335f`, ~870 GB free. It was only being
  auto-mounted by the desktop at `/media/flippy/<uuid>`; `host-setup.sh` gives
  it an fstab entry at **`/srv/build`** (`nofail`), with the build dirs at
  `/srv/build/jetson-tv/{lineage,ccache,dlcache}`.
  Root (`nvme0n1p2`) also has 1.7 TB free if the 1 TB ever gets tight.
- Targets: Jetson Nano 4GB — a mix of **production modules** (P3448-0002,
  16 GB eMMC) and **developer modules** (P3448-0000, microSD). Both are
  supported by the same flash package. **Not yet attached to the host** as of
  2026-09-10; expected 2026-09-11.

## Use case

Kodi / Plex / emulation box. Google Cast is a confirmed dead end (see below).

## Key findings (researched, sourced)

**LineageOS already supports this device officially.** Codename `porg` =
"NVIDIA Jetson Nano [Android TV]". Weekly signed nightlies, currently
lineage-22.2 (Android 15). Maintainers: webgeek1234 (Steel01) and npjohnson.
`device/nvidia/porg` inherits directly from `device/nvidia/foster` — the SHIELD
Android TV tree. This is a retarget of SHIELD, not a fresh port.

**Blobs come from public downloads, not a device.** `extract-files.sh` pulls
L4T r32.7.6 / r32.6.1 tarballs (firmware, bootloader, tegraflash) and SHIELD
recovery OTA images (GPU userspace including `vulkan.tegra.so`, the NVMM/OpenMAX
media stack, Dolby audio libs, and BCM4356/BCM4354 wifi+BT firmware). No adb
device needed. Full hardware acceleration including Vulkan is available on T210 —
unlike Xavier/Orin, which have no NVIDIA Android userspace.

**Bluetooth: only BCM4356 works.** The tree ships `BCM4356A3.hcd` and
`BCM4350C0.hcd` and nothing else. Buy a **BCM94356Z** (Lenovo FRU 00JT478),
M.2 2230 Key E, WiFi 5 + BT 4.1. Do NOT buy the Intel AC8265 (the card everyone
sells for the Nano — unsupported) or RTL8822CE (tested, does not work).

**Hard blockers, do not waste time on these:**
- HDCP is not implemented in NVIDIA's Tegra Linux driver and never will be.
- Widevine L1 requires a factory-provisioned keybox. L3 only → SD Netflix.
- Google announced (Oct 2025) it will block YouTube on uncertified AOSP devices.
- Google Cast receiver fails with "Cast certificate is not valid" — the cast
  certificate is provisioned per-device by the licensed OEM. Use Plex Companion,
  Jellyfin, Kodi UPnP/DLNA or FCast instead.
- Tegra X1's NVDEC has no AV1 decode. H.264/HEVC/VP9/VP8/MPEG-2/VC-1 only.

## What's in this repo

A containerised build environment. Written and syntax-checked but **never
executed** — first run is still pending.

```
docker/Dockerfile          Ubuntu 22.04 + LineageOS build deps + repo
docker/entrypoint.sh       git identity, ccache config
docker-compose.yml         service, bind mounts, ulimits
.env.example               copy to .env, fill in the three disk paths
scripts/host-setup.sh      one-time root setup: docker, fstab mount, build dirs
scripts/jetson-build       host driver: image/up/shell/sync/extract/build/status
scripts/in-container/*.sh  sync, extract, build
scripts/probe.sh           read-only host probe → probe-output.txt
.devcontainer/             VS Code attach config
```

Design decisions worth preserving:

- **Ubuntu 22.04, not 24.04.** Noble dropped `libsdl1.2-dev` and
  `lib32readline-dev`, both on LineageOS's required package list.
- **Three separate bind mounts** — tree, ccache, and `dlcache`. `dlcache` is
  outside the tree deliberately so a `repo sync` or tree wipe doesn't cost a
  ~20 GB re-download of the L4T and OTA archives.
- **`ulimits: nofile 32768/65536`** in compose — Soong fails on the default 1024.
- **Container UID/GID match the host user** so build output isn't root-owned.
- **Flashing stays on the host.** `tegraflash` needs USB recovery-mode access
  and udev rules; containerising it gains nothing.

## Confirmed against upstream (2026-09-10, by reading the lineage-22.2 sources)

- `device/nvidia/porg/extract-files.sh` is a one-line `exec` into
  `device/nvidia/tegra-common/extract/extract-files.sh`, which **does** parse
  `-c | --cache-dir` — so `extract.sh` passing `-c /dlcache` is correct. It also
  has `-p | --prime-cache` if you ever want to pre-download the archives without
  extracting.
- That script defaults `SRC` to `download`, which is the mechanism behind
  "no adb device needed".
- There is **no `extract-files.py`** for `porg` on any of lineage-21/22.1/22.2,
  so the `if [[ -x ./extract-files.py ]]` branch in `scripts/in-container/extract.sh`
  is currently dead code. Harmless — upstream is migrating devices to the Python
  extractor over time, so leave it for when `porg` follows.

## Unverified — confirm before relying on

- Whether `p3450.sh` SKU-2 detection works in practice on a production eMMC
  module. The XML layout exists (`flash_android_t210_emmc_p3448.xml`) and the
  script selects it by SKU, but this hasn't been exercised.
- Whether BLE (not just A2DP) works on 22.2. BLE was among the issues that held
  back 19.1/20 on ARM64 Tegra. Test remote pairing early.

## Status

Done (2026-09-10):

- `devcontainer.json` moved to `.devcontainer/`; scripts made executable; repo
  file modes normalised to 644/755 (they were 600 from the authoring session).
- `.env` written for thebe — paths under `/srv/build/jetson-tv`, `JOBS` blank,
  `CCACHE_SIZE=100G`.
- `scripts/host-setup.sh` written and **run**: Docker CE 29.8 + compose v2
  installed, user in the `docker` group, SSD mounted at `/srv/build` via fstab,
  build dirs created and chowned.
- **Image builds.** `jetson-build image` → `jetson-tv/lineage-build:jammy`,
  1.28 GB. All 61 apt package names verified against packages.ubuntu.com/jammy
  first; all exist, `lib32ncurses5-dev` included.
- **Container runs and passes `jetson-build doctor` 30/30** — uid/gid 1000
  write-through to the host tree, `nofile` 32768, all three bind mounts
  writable, `/opt/jetson-tv` read-only, full toolchain present, git identity
  set, ccache at 100 GB.
- Network from inside the container reaches GitHub; `lineage-22.2` confirmed to
  exist on both the manifest and `android_device_nvidia_porg`. repo launcher
  2.65, git 2.34.1, Python 3.10.12.
- Git repo initialised, `.env` gitignored.

Note for future sessions: **Claude's shell does not have the docker group**
(the session predates the `usermod`). Prefix docker commands with
`sg docker -c '...'`. Beware that `sg` changes the *effective* gid, which is
why `jetson-build` reads the primary gid from passwd rather than `id -g`.

Remaining:

1. `./scripts/jetson-build sync` — first `repo sync`, several hours and a few
   hundred GB. Not yet started.
2. `extract` → `build`.
3. Verify on-device: BLE remote pairing, HDMI audio passthrough, hardware decode
   of H.264/HEVC/VP9 samples. Hardware expected 2026-09-11.

## Reference

Fuller research lives in the "Nvidia Shield Clone" Claude project:
`claude/research/google-tv-on-jetson-brief.md` and
`claude/research/jetson-nano-hardware-plan.md`.

Upstream: https://wiki.lineageos.org/devices/porg/build/variant1/ ·
https://github.com/LineageOS/android_device_nvidia_porg ·
`#LineageOS-dev` on Libera.Chat
