# JetsonTV — containerised LineageOS build environment

JetsonTV is a TV box built on the NVIDIA Jetson Nano. This repo builds its
image, LineageOS **Android TV** for the Nano (LineageOS codename `porg`),
inside a pinned Ubuntu 22.04 container, so the host distro is irrelevant and
the toolchain can't drift.

Target by default: `lineage-22.2` (Android 15), which is what the official
`porg` nightlies are currently built from.

---

## Why a container, and why jammy

AOSP builds are unusually sensitive to host toolchain versions, and LineageOS
only really tests against particular Ubuntu releases. Ubuntu 24.04 dropped or
renamed several packages the LineageOS build guide asks for (`libsdl1.2-dev`,
`lib32readline-dev`), so the image pins **22.04**. Your host can be anything.

The **build** is containerised. **Flashing is not** — see below.

---

## One-time setup

### 1. Host prerequisites

Everything that needs root is in one idempotent script: Docker Engine and the
compose v2 plugin, your user in the `docker` group, a stable fstab mountpoint
for the build disk, and the three build directories chowned to you.

```bash
lsblk -o NAME,SIZE,FSTYPE,UUID,MOUNTPOINT      # find your build disk's UUID
sudo BUILD_DISK_UUID=<uuid> bash scripts/host-setup.sh
```

It prints the fstab line it wants to add and waits for you to confirm, backs
up `/etc/fstab` first, and mounts with `nofail` so a missing disk can never
drop the machine into an emergency shell at boot. If your disk already has a
stable mountpoint, use `SKIP_DISK=1` and set `MOUNTPOINT` to where it lives.
Other knobs: `SKIP_DOCKER=1`, `PROJECT_DIR=`, `ASSUME_YES=1`.

Being added to the `docker` group only takes effect at next login — the script
tells you if that applies.

The tree is large: LineageOS asks for ~400 GB for lineage-21 and up, plus
ccache and the downloaded NVIDIA archives. Three directories, because they
have different lifetimes:

| Dir       | Holds                                                        | Size    |
|-----------|--------------------------------------------------------------|---------|
| `lineage` | source tree + `out/`                                          | ~400 GB |
| `ccache`  | compiler cache                                                | 25–50 GB|
| `dlcache` | downloaded L4T tarballs + SHIELD OTA images for blob extraction | ~20 GB |

`dlcache` sits outside the tree deliberately, so a `repo sync` or a tree wipe
never costs you the re-download.

### 2. Configure

```bash
cp .env.example .env
$EDITOR .env          # set the three paths to match host-setup.sh's MOUNTPOINT,
                      # and check HOST_UID/HOST_GID against `id -u` / `id -g`
```

`HOST_UID`/`HOST_GID` matter: the container's build user is created with those
ids so everything it writes into the bind-mounted tree comes out owned by you.

### 3. Build the image

```bash
./scripts/jetson-build image
```

---

## Normal use

```bash
./scripts/jetson-build doctor    # verify the environment before spending hours
./scripts/jetson-build sync      # repo init (first run) + repo sync   — hours
./scripts/jetson-build extract   # breakfast + pull the NVIDIA blobs   — ~20 min
./scripts/jetson-build build     # mka bacon                           — hours
./scripts/jetson-build status    # disks, ccache, tree state
./scripts/jetson-build shell     # interactive shell in the build env
```

or `./scripts/jetson-build all` to run the three in order.

Output lands in `$SRC_DIR/out/target/product/porg/`:
`lineage-22.2-*-UNOFFICIAL-porg.zip` and `recovery.img`.

### VS Code

`.devcontainer/` attaches to the *same* compose service, so you can have a
build running from the CLI and a VS Code window open on the tree at once.
"Reopen in Container", workspace is `/srv/lineage`. The devcontainer settings
exclude `out/`, `.repo/` and `prebuilts/` from the file watcher — without that
VS Code tries to index 400 GB and falls over.

---

## Testing the AirPlay receiver on the bench

The image ships **BearPortExpress**, our AirPlay receiver — a fork of
[UxPlay](https://github.com/FDH2/UxPlay) with classic AirPlay 1 / RAOP support
added by reading
[shairport-sync](https://github.com/mikebrady/shairport-sync). Thanks to both
projects; neither of them is responsible for our bugs. GPL-3.0-or-later. See
[docs/airplay.md](docs/airplay.md).
Most of it can only be exercised by a real Apple device, with one exception
that matters because it needs none.

### `raopsend` — a classic RAOP sender you can run on the device

`scripts/in-container/raopsend` is a small AirPlay 1 / RAOP sender written for
this project. It generates its own 440 Hz tone and carries its own cover art,
so it needs no media files, no Mac, no iPhone and no LAN:

```bash
m raopsend                                    # in the build container
./scripts/airplay/classic-selftest.sh 10      # push it, stream, verify
```

It runs **on** the Jetson rather than the host, because RAOP carries audio
over UDP and `adb forward` is TCP only.

Two choices keep it short. It sends uncompressed `L16/44100/2`, so there is no
ALAC encoder; and a stream carrying neither `a=aesiv` nor `a=rsaaeskey` is
treated as unencrypted, so there is no key exchange to implement.

### Why the test checks a frequency, not a level

`classic-selftest.sh` records the Jetson's HDMI output through the MS2109
capture dongle and runs a Goertzel filter at 440 Hz against its neighbouring
bands. That is deliberate, and the project has earned the lesson twice:

- A Bluetooth **byte counter** once "proved" a working audio chain that was
  in fact completely silent — starting a stream wakes A2DP, and SBC silence
  costs the same bytes as signal.
- A **wrong session key** once produced a convincing now-playing screen with
  correct metadata, cover art, track changes and a healthy peak level, while
  rendering uniformly random bytes.

A level meter, a byte counter and a screenshot all fail to tell signal from
noise. A known-frequency source does not: a passing run reads 440 Hz at 6504
with the next loudest band at 30, and a peak of 17999 — exactly the amplitude
`raopsend.c` generates.

### What it covers, and what it does not

**Covers**: `ANNOUNCE` and its SDP parse, the unencrypted branch, the
`Transport:` SETUP, `RECORD`, the RTP receive loop, the L16 render path, and
AudioTrack through to HDMI.

**Does not cover**: the RSA unwrap of `a=rsaaeskey`, `Apple-Challenge`
signing, and the ALAC decode path — those need a real sender. A green run
means the plumbing works, not that Apple Music does.

---

## Before you buy hardware

**[docs/jetson-nano-hardware-plan.md](docs/jetson-nano-hardware-plan.md)** is
the part most likely to save you weeks. The short version:

- The **only** supported wireless card is a **BCM4356** (buy a Lenovo
  BCM94356Z, FRU `00JT478`). The Intel AC8265 that every Nano vendor sells is
  *not* supported, and the RTL8822CE was tested and does not work.
- Both the developer (microSD, P3448-0000) and production (16 GB eMMC,
  P3448-0002) modules work from the same flash package.
- Permanently out of reach, for cryptographic rather than packaging reasons:
  HDCP, Widevine L1, Google Cast, and the certified YouTube ATV app. Plex
  Companion, Jellyfin, Kodi UPnP/DLNA and FCast all work instead.
- Tegra X1 has no AV1 decode. Everything else (H.264/HEVC/VP9/VP8/MPEG-2/VC-1)
  is hardware accelerated, and Vulkan is available.

## About the blob extraction

Unlike most LineageOS devices, `porg` does **not** need a running device over
adb to extract proprietary files. The Tegra extract tooling downloads what it
needs:

- **public L4T tarballs** (r32.7.6 and r32.6.1) — firmware (`nvhost_nvdec020_ns.fw`
  and friends), bootloader, and the `tegraflash` tools;
- **SHIELD recovery OTA images** — the GPU userspace (`libEGL_tegra.so`,
  `libGLESv2_tegra.so`, `vulkan.tegra.so`, `gralloc.tegra.so`,
  `libnvhwcomposer.so`), the NVMM/OpenMAX media stack, the Dolby audio libs,
  and the BCM4356/BCM4354 wifi + Bluetooth firmware (`BCM4356A3.hcd`).

That last point is why the Jetson Nano build has full hardware acceleration
including Vulkan, while the Xavier and Orin trees do not — those SoCs have no
NVIDIA Android userspace and are stuck on Mesa/nouveau.

`extract.sh` passes `-c /dlcache` to the Tegra `extract-files.sh` to keep those
downloads out of the tree. Verified against lineage-22.2: `porg`'s
`extract-files.sh` execs into `tegra-common/extract/extract-files.sh`, which
parses `-c | --cache-dir`, and defaults its source to `download`.

---

## Flashing (host side, not in the container)

`tegraflash` needs USB recovery-mode access and udev rules; passing that
through a container is more trouble than it's worth. Flash from the host.

The flash package `p3450.sh` auto-detects the module SKU and picks the right
layout and device tree:

| SKU | Module        | Boot media | Layout                                    |
|-----|---------------|------------|-------------------------------------------|
| 0   | P3448-0000    | microSD    | `flash_android_t210_max-spi_sd_p3448.xml` |
| 2   | P3448-0002    | 16 GB eMMC | `flash_android_t210_emmc_p3448.xml`       |
| 3   | P3448-0003    | microSD    | `flash_android_t210_max-spi_sd_p3448.xml` |

So the same package covers both the developer (SD) and production (eMMC)
modules — no fork needed.

To get the board into recovery mode: with the board **unplugged**, jumper the
FRC/REC pin, then apply power, and remove the jumper once APX enumerates
(`lsusb` shows an NVIDIA device). Note that on Nano the bootloader cannot be
updated in place — an L4T cboot limitation — so bootloader changes always mean
a full USB-recovery reflash.

---

## RAM

LineageOS suggests 64 GB for lineage-21 and up. Below that, linking is where
the build goes OOM. Set `JOBS` in `.env` to cap parallelism — roughly
(RAM in GB / 4) is a sane starting point — and consider enabling zram on the
host. ccache makes the second and subsequent builds dramatically cheaper, so
it's worth letting the first one grind.

---

## Layout

```
.
├── .devcontainer/devcontainer.json   VS Code attach config
├── docker/
│   ├── Dockerfile                    Ubuntu 22.04 + LineageOS build deps + repo
│   └── entrypoint.sh                 git identity, ccache config
├── docker-compose.yml                service, bind mounts, ulimits
├── .env.example                      copy to .env and edit
└── scripts/
    ├── host-setup.sh                 one-time root prerequisites
    ├── jetson-build                  host-side driver (run this)
    ├── probe.sh                      read-only host report
    ├── airplay/
    │   ├── classic-selftest.sh       440 Hz through the classic RAOP path
    │   ├── push-receiver.sh          rebuild -> device, no flash
    │   ├── preflight.sh              bench readiness checks
    │   ├── watch.sh                  follow an AirPlay session live
    │   ├── mdns_probe.py             what we advertise, as published
    │   ├── tone_check.py             frequency check on a capture
    │   └── tvnav.py                  drive the leanback UI headlessly
    └── in-container/
        ├── doctor.sh                 environment sanity checks
        ├── sync.sh                   repo init + sync
        ├── extract.sh                breakfast + blob extraction
        ├── build.sh                  mka bacon
        └── raopsend/                 classic RAOP sender, runs on the device
```
