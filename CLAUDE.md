# Project: Jetson Nano → Android TV box

Context for any Claude session working in this repo. Written by a Cowork session
on 2026-09-10 that had file access to this folder but no shell on the host.

## Goal

Turn NVIDIA Jetson Nano 4GB boards into usable TV boxes running **LineageOS
Android TV**, and build a reproducible, automated build environment for doing so.

Long-term: possibly extend to Jetson Orin (AGX / NX / Nano). Deferred for now.

## Hardware

Verified on the host 2026-09-10 (first session with an actual shell).

- Host build machine: `thebe`, **Ubuntu 24.04.4 LTS (noble)**, x86_64.
  **Rebuilt 2026-09-23**: the same OS install and both NVMe drives moved from a
  Dell Precision 7820 (112 threads / 187 GB) into an **ASUS PRIME TRX40-PRO**
  with a **Threadripper 3970X (32c/64t)** and **125 GB** (all 8 DIMM slots —
  the memory was filled out after the 2026-09-23 note said 94 GB / 6 slots).
  Leave `JOBS` blank — 125 GB clears LineageOS's 64 GB floor comfortably, and
  at ~1.5 GB per thread 64 threads wants ~96 GB, which fits. If a link step
  ever OOMs anyway, the first thing to try is `JOBS=48`.
- **`sudo` requires a password here**, so Claude cannot run it. Anything
  needing root goes in `scripts/host-setup.sh` for the user to run.
- **Docker was not installed** (the original note claiming it was, was wrong).
  `scripts/host-setup.sh` installs Docker CE + compose v2 from Docker's own
  apt repo.
- Build disk: Samsung 980 1 TB, ext4, UUID
  `bb1724cf-c151-4944-90c6-23b72ca9335f`, mounted at **`/srv/build`** by fstab
  (`nofail`), build dirs at `/srv/build/jetson-tv/{lineage,ccache,dlcache}`.
  **Its device name is not stable** — on the new board it has come up as
  `nvme0n1` and `nvme1n1` on alternate boots; always address it by UUID.
  Root has 1.7 TB free as fallback space.
- **2026-09-13 scare, resolved 2026-09-23.** The disk was pulled on suspicion
  of failure and came back in the new machine with everything intact
  (verified: tree spot-checks clean, L4T rootfs has all 23 setuid files, SD
  image passes `unzip -t`, `doctor` 31/31). The journal shows the NVMe never
  logged an error; the `Buffer I/O error` lines from that evening were on
  **`sdd`** — the USB card reader holding the Jetson's dying microSD. Check
  *which* device an I/O error names before blaming the SSD.
- **Hazard while no disk is mounted:** `docker compose up` creates missing
  bind-mount sources as root-owned empty dirs on the root filesystem.
  `jetson-build`'s preflight refuses to start if the `.env` dirs are missing,
  which is the right failure — don't bypass it by creating them by hand under
  `/srv/build` unless a disk is actually mounted there.
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
scripts/jetson-build       host driver: image/up/shell/sync/forks/extract/build
scripts/in-container/*.sh  sync, extract, build, local-manifest, tree-local-changes
scripts/in-container/local-manifest.xml   repo local manifest -> our forks
scripts/in-container/AmbientDream/        the screensaver, mirrored into vendor/
scripts/in-container/AirPlayReceiver/     the AirPlay receiver (hosts UxPlay), ditto
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

- ~~Whether `p3450.sh` SKU-2 detection works on a production eMMC module.~~
  **Confirmed working 2026-09-25** on a sku 2 / fab 400 module; see the
  flashing section above.
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

Note for future sessions: Claude's shell **does** have the docker group as of
2026-09-24 — the `sg docker -c '...'` workaround the earlier sessions needed is
no longer required. Keep in mind that `sg` changes the *effective* gid, which is
why `jetson-build` reads the primary gid from passwd rather than `id -g`; that
logic is still correct and should stay.

- **`repo sync` done and intact** (re-verified 2026-09-23 after the disk
  scare): lineage-22.2, 1141 projects, 172 GB in
  `/srv/build/jetson-tv/lineage`, 676 GB free. `prebuilts/jdk` present (AOSP
  brings its own JDK — no host JDK needed). `ccache` and `dlcache` are still
  empty — `extract` has never run.
- Also on the disk, belonging to the sibling bench repos: `/srv/build/l4t`
  (NVIDIA L4T R32.7.6 BSP tree, 17 GB, prepared rootfs — used by
  `jetson-flash-node` and `qtpy-relay-controller`) and
  `/srv/build/l4t-sdimage/jetson-nano-jp461-sd-card-image.zip` (6.2 GB).

`device/nvidia/{porg,tegra-common,t210-common}` are **not** in the tree yet, and
that is expected: the LineageOS base manifest carries no device trees.
`breakfast porg` runs `roomservice`, which writes
`.repo/local_manifests/roomservice.xml` and syncs them. That happens as the
first step of `extract`.

- **`extract` done (2026-09-24)**, the first time it had ever been run.
  roomservice synced 23 NVIDIA projects, and all eleven blob sources came down
  and unpacked into `vendor/nvidia` (484 MB): NVIDIA's licensed T210 TLK
  binaries (rel-24 and rel-30), four SHIELD OTAs (`foster_e`, `darcy`,
  `mdarcy`, `sif_32b`), and the L4T tarballs for r32.7.6 / r32.6.1 / R35.6.2 /
  R36.4.4. No device over adb, exactly as the research said. A full Soong
  analysis (`m nothing`) then passed in 3m32s.

**Nothing may invoke Soong before `extract` has run.** This is the single most
important ordering constraint in the tree, and it is not obvious:

- Every `prebuilt_*` module under `device/nvidia/tegra-common/vendor` depends
  on a generated `<file>_{32,64}-defaults` module, and `extract_utils.sh`
  (~line 473) only writes those into `vendor/nvidia` during extraction.
- Extraction also generates `vendor/nvidia/common/exclude-bp.mk`, which puts
  `-vendor/nvidia/common -device/nvidia/tegra-common/vendor` at the front of
  `PRODUCT_SOURCE_ROOT_DIRS`. Those `-` entries prune the whole vendor tree
  from the blueprint scan, after which only the `rel-shield-r/*` directories
  this device needs are re-included.
- Without that pruning Soong parses `vendor/r35` and `vendor/r36` too. Since
  upstream 0f16ddf "vendor: Convert to blueprint" (2025-06-17) those two carry
  byte-identical `l4t/` and `nvpmodel/` blueprints, and because
  `tegra-common/vendor/Android.bp` declares one `soong_namespace` over every
  branch below it, twelve module names collide and Soong will not bootstrap.
  It looks like an upstream bug and is really just "you have not extracted
  yet". `build.sh` now checks for `exclude-bp.mk` and says so directly.

`breakfast` is safe before extraction — it only evaluates the product config,
not the blueprints — which is why `extract` can bootstrap a cold tree at all.

- **`build` done (2026-09-24)** — `mka bacon`, 180108/180108 targets, no
  failures, **57m59s** on the 3970X with a cold ccache (5.8% hit rate, 2 GB
  written; a rebuild should be far quicker). Artifacts in
  `/srv/build/jetson-tv/lineage/out/target/product/porg`:
  `lineage-22.2-20260925-UNOFFICIAL-porg.zip` (771 MB, signed with the AOSP
  `testkey`, `unzip -t` clean), `recovery.img` (20 MB), `boot.img` (14 MB).
  Spot-checked in the vendor image and all present as the research predicted:
  `vulkan.tegra.so` + `libEGL_tegra.so` + `gralloc.tegra.so` in both 32- and
  64-bit, `bcm4356a3.hcd` and the `brcmfmac4356-pcie` firmware, and 22
  `libnvmm*` media libraries.

- **Flashed and booted (2026-09-25).** The build runs on real hardware:
  LineageOS 22.2 Android TV came up on a production Jetson Nano and reached the
  setup wizard. Target was module P3448-0002 `699-13448-0002-400 F.0`, EEPROM
  sku 2 / fab 400, on a B01 carrier. `p3450.sh`'s SKU-2 detection — listed
  below under "Unverified" until now — works: it read the EEPROM, chose
  `flash_android_t210_emmc_p3448.xml` and `tegra210-p3448-0002-p3449-0000-b00`,
  and the bootloader agreed (`BoardID = 3448, SKU = 0x2`).

### Flashing porg, end to end

The install is two stages: tegraflash writes the bootloader and recovery, then
the zip is sideloaded from recovery. `APP` and `vendor` are deliberately
flashed *empty* — the zip fills them.

1. `m p3450_flash_package` → `$OUT/p3450_flash_package.txz` (22 MB).
   **Its rules are not re-runnable.** The signing steps do
   `mv $OUT/signed $OUT/signed_boot`, which moves *into* the destination once
   it exists, and the packaging step ends with `cd $(dir $@); tar -cJf $@ *`,
   which tars the archive into itself on a second run. Before re-running:
   `rm -rf $OUT/signed*` and the stale `.txz` in the intermediates dir.
2. Extract the txz somewhere and fix the version files. The recipe builds them
   with `$(TOYBOX_HOST) cksum`, and AOSP's toybox has no `cksum`; the failure
   is swallowed because it sits in a pipeline whose exit status comes from
   `awk`. Both `emmc_bootblob_ver.txt` and `qspi_bootblob_ver.txt` are flashed
   into the VER/VER_b partitions, so append the missing line by hand:
   `read -r crc bytes _ < <(cksum "$f"); printf 'BYTES:%s CRC32:%s\n' "$bytes" "$crc" >> "$f"`
3. `jetson-flash-node`'s `recovery` verb to get the module into RCM (0955:7f21),
   then run `./flash.sh` from the extracted package as **root with USB
   passthrough** — `helpers.sh` exits unless `EUID` is 0 and needs `xxd` and
   `fdtput`. The flash-node image has all three.
4. Boot to recovery, then **format cache and data before sideloading**. CAC is
   flashed with no filesystem, so recovery cannot stage an install and
   `adb sideload` ends instantly with `Total xfer: 0.00x` and
   `Can't mount /cache/recovery/last_install`. Factory reset → Format cache
   partition, then Format data.
5. Apply update → Apply from ADB, then `adb sideload <the zip>`. Sideload mode
   needs no adb authorisation, which matters because the device shows up as
   `unauthorized` otherwise and there is no way to accept the prompt headlessly.

Driving that menu headlessly needs both bench tools: the FRDM-K64F injects
arrow keys and Enter over USB, and the MS2109 shows the result — but only if
one capture stream is held open across the whole sequence. See the HPD note in
`jetson-flash-node/tools/hdmi.py`; it is the single most misleading failure
mode on this bench.

### Driving a flash headlessly (2026-09-30)

`adb reboot sideload` puts the device straight into sideload mode without
touching the recovery menu, which removes the FRDM-K64F and the MS2109 from the
loop for the install itself. Two things still bite:

- **adb in recovery is `unauthorized`** and there is no way to accept the
  prompt headlessly, so `adb reboot` cannot bring it back out. Either use
  `adb reboot sideload-auto-reboot` in the first place, or power-cycle out of
  the finished install — the BCB is cleared once the install completes, so a
  cold boot goes to the system.
- **The host adb server and the `adbnode` container fight for the USB claim.**
  The device shows up in neither until one of them lets go: `adb kill-server`
  on the host, then `adb kill-server && adb start-server` in the container.
  Starting a host adb server for the emulator is enough to cause it.

Recovery had already decided there was no display by the time the capture
stream raised HPD, so its menu was unreadable — colour bars only. See the HPD
note in `jetson-flash-node/tools/hdmi.py`: to see anything, hold one capture
stream open and power-cycle *into* it.

### AirPlay audio works on porg (2026-09-30)

The receiver plays: a 12 s stream arrives on the TV's HDMI as 12.0 s of tone,
no gaps, every session, under enforcing SELinux, started by init as its own uid.
Seven defects stood between here and there; `docs/streaming-targets.md`, "Why
it was silent, and then why it dropped out", has each with its evidence. Three
worth knowing before touching audio on this box again:

- **Never run a native audio client as uid `audioserver`.** libaudioclient
  decides by uid that it *is* audioserver and waits forever for an in-process
  service. The AirPlay daemon is `system_ext_airplay` (7500, porg `config.fs`).
- **A refused `service_manager find` is not harmless.** The client cannot tell
  it from "not started yet" and waits 5 s per attempt. `dontaudit` hides it.
- **Verify audio by frequency, not by bytes or level.** Record the MS2109
  (`arecord -D plughw:2,0 -f S16_LE -r 48000 -c 2`) and look for the tone.
  A byte counter on the emulator's Bluetooth speaker once "proved" a chain
  that was silent: starting a stream wakes A2DP, and SBC silence costs the
  same bytes as signal.

### AirPlay is UxPlay now (2026-09-30)

Shairport was replaced the same day it started working, because the goal is
screen mirroring from Macs and iPhones, not only audio. UxPlay's protocol
library runs **inside `AirPlayReceiver`** over JNI (`external/uxplay`, with
`external/libplist`); MediaCodec and AudioTrack do the rendering. Everything is
in **`docs/airplay.md`**, including the licensing: GPLv3, with BoringSSL linked
dynamically as the system crypto library. That was the user's explicit call,
so do not reopen it.

- **Not yet seen with a real Apple device — come back to this when one is
  available.** It is the first item under "Still to do" in
  `docs/airplay.md`, with the checklist. Mirroring needs FairPlay, and no
  open-source sender does it. UxPlay also refuses classic `ANNOUNCE`, so
  `raopsend` and pyatv can no longer drive audio. Instead, a debug-only
  **self-test** (`SELFTEST` action, modes `pcm` / `eld` / `music`) pushes a
  test card and a tone through the real renderers. On porg all three were
  clean on HDMI, with video 0.0 ms from due. The docs have a checklist for the
  first iPhone/Mac session.
- **To iterate without a flash:** `adb root && adb remount`, push the app dir
  **and `/system/lib64/libuxplay_jni.so`** (the app's `lib/arm64` entry is
  only a symlink to it), then reboot.
- The ALAC decoder inherited from shairport could be driven to overrun the
  heap by any device on the LAN. It is fixed and fuzzed in the fork.
- **New devices need a PIN shown on the TV** (`persist.jetsontv.airplay.pin`,
  default on), once per device. Our fork enforces it; upstream only asked for
  it. The code screen is verified, but pairing with a real client is not.
  Both are in `docs/airplay.md`, "Pairing with a PIN".
- The Shairport uid lesson below still stands for native audio clients, but
  the receiver is now an ordinary app uid playing through AudioTrack.

### On-device findings (2026-09-25)

**There is a root-capable shell on the serial console.** This is a userdebug
build, so `/dev/ttyUSB0` gives a `console:/ $` prompt as uid 2000 with no
login. It is the way in when adb is not yet available, and it is what got the
device past first boot.

**The setup wizard cannot be completed on this bench.** Its "Searching for
accessories" step waits for a BLE remote and ignores every key, so with no
radio fitted it never advances — the HID injector is irrelevant there, and
Android does see it (`dumpsys input` lists the injector as a full keyboard).
Bypass it from the serial console:

    settings put global device_provisioned 1
    settings put secure user_setup_complete 1
    settings put global adb_enabled 1
    pm disable-user --user 0 org.lineageos.setupwizard

adb notes: keep one long-lived adb container, because a fresh one generates a
new RSA key each run and re-prompts (keys now persist in
`/srv/build/jetson-tv/adbkeys`). If adb wedges while the gadget is present,
`settings put global adb_enabled 0` then `1` re-enumerates it.

**Why the wizard is captive, and how to add a Skip.** Full source is in the
tree: `packages/apps/SetupWizard` (LineageOS) and `packages/apps/TvSettings`.

`BluetoothSetupActivity.onStartSubactivity()` launches
`com.android.tv.settings/.accessories.AddAccessoryActivity` with
`no_input_mode = true`. That extra is the whole mechanism — it means "the user
has no other input device", so the screen deliberately offers no way out.
`AddAccessoryActivity.onKeyUp()` handles `KEYCODE_BACK`, but only calls
`cancelBtPairing()` and only when `mPairingBluetooth && !mDone`; on the
"Searching for accessories" screen nothing has been found yet, so BACK does
nothing at all and never calls `finish()`. `onSubactivityResult()` also
re-launches the step when the child returns `onBackPressed`, which is why
force-stopping the activity just brings it back.

Two levels of fix:

- **Blunt, no code change.** The same method already starts with
  `if (!hasLeanback(this) || SetupWizardUtils.isBluetoothDisabled())
  { finishAction(RESULT_SKIP); return; }`, and `isBluetoothDisabled()` reads
  the `config.disable_bluetooth` system property. Setting that in the product
  makefile skips the step outright — fine for a bench, wrong for a shipped box.
- **The real fix.** Pass `no_input_mode` conditionally: false when a physical
  keyboard or DPAD is already attached, so the screen stops being captive, and
  add an explicit Skip in `AddAccessoryActivity` when `!mNoInputMode`. Detect
  with `android.view.InputDevice.getDeviceIds()` and
  `getKeyboardType() == KEYBOARD_TYPE_ALPHABETIC` (or `SOURCE_DPAD`), skipping
  virtual devices. Note `InputDeviceCriteria` in that package is **not** the
  helper for this — it classifies *Bluetooth* device classes, not local input.

**Hardware codecs work and are preferred.** Every clip selected an
`OMX.Nvidia.*` component, never a `c2.android.*` software fallback:

| path | component | result |
| --- | --- | --- |
| H.264 encode | `OMX.Nvidia.h264.encoder` | 1280x720 @ 20 Mbps via `screenrecord` |
| H.264 decode | `OMX.Nvidia.h264.decode` | ok |
| HEVC decode | `OMX.Nvidia.h265.decode` | 1920x1080, via a MediaProvider thumbnail |
| VP8 decode | `OMX.Nvidia.vp8.decode` | 1920x1080 at 116.7 fps |
| VP9 decode | `OMX.Nvidia.vp9.decode` | instantiates, then `ERROR(0x80001020)`, 0 frames |
| MPEG-2 decode | `OMX.Nvidia.mpeg2v.decode` | same |
| AV1 | — | absent, as expected on T210 |

VP9 and MPEG-2 are **unproven, not failed**: `decodetest` decodes to
ByteBuffers with no output Surface, and those two components appear to require
one. HEVC at 1080p works through the normal Surface-backed path. Adding
Surface output to `decodetest` would settle it.

### Two defects fixed in scripts/in-container/tree-local-changes.sh

**BLE was disabled in the kernel.** `net/bluetooth/Kconfig` has
`config BT_LE ... default y`, but every tegra defconfig shipped
`# CONFIG_BT_LE is not set`, so the kernel had no BLE at all. That would break
remote pairing on any radio, and is a plausible root cause for the
long-standing "BLE doesn't work on ARM64 Tegra" reports.

**`wifi_loader.sh` could not load any wifi module.** Two separate defects in
`device/nvidia/tegra-common/initfiles/wifi_loader.sh`: it insmods from
`/system/lib/modules`, which does not exist on this build (modules install to
`/vendor/lib/modules`), and `perform_enumeration()` only scans for Broadcom
vendor ids, so a Realtek card is never matched. The tree *does* build and ship
a working `rtl8822ce.ko` from `kernel/nvidia/nvidia`, which sits unloaded —
almost certainly why "RTL8822CE tested, does not work" is in this file's
history. Now fixed, with detection for Realtek PCIe (`0x10ec`) and an insmod
branch for `10ec:c822`.

Neither can be *proven* until a radio is fitted. **RTL8822CE is the card to
buy** for quantity: the driver is already in-tree, the cards are ~$8-12 and
ubiquitous, and it is M.2 2230 Key E. BCM4356 remains the only chip with wifi
firmware in the image (`brcmfmac4356-pcie.bin`), but eBay has ~3 listings.

Remaining:

1. Verify on-device once a radio is fitted: BLE remote pairing and wifi.
2. HDMI audio passthrough — the MS2109 presents an ALSA *capture* device
   (`card 2: MS2109`, S16_LE 48 kHz stereo), so `arecord -D hw:MS2109,0` is a
   real test. It only receives 2-channel LPCM, so it cannot validate
   multichannel or Dolby/DTS bitstream passthrough.
2. `/dlcache` is **still empty** — this first extract downloaded straight to
   the container's `/tmp`. `-c/--cache-dir` means "extract from an
   already-primed cache" and aborts on an empty one; `-p/--prime-cache` is
   what fills it. `extract.sh` now primes before extracting, but priming costs
   the ~20 GB download again, so it will only happen on the next `extract`.

## Open items and eMMC wear (2026-09-30)

**Game streaming works (2026-09-30).** Moonlight 12.2 on the Jetson against
Sunshine on thebe, measured by reading a clock strip back off HDMI:
1080p60 is solid, with 60/60 fps, no drops, about 1 ms of decode, and about
70% of 400% CPU. The harness is in `scripts/gamestream/`, results and traps
in `docs/game-streaming.md`. thebe's rootless capture (Xephyr and XShm)
limits 4K; test 4K against a real gaming PC.

**Third-party apps are pinned in `scripts/in-container/PrebuiltApps/apps.json`**
(Moonlight 12.2 and Kodi 21.2 today). `prebuilt_apps.py` fetches them into `/dlcache` on
every build, checks the SHA-256, and generates the modules. The APK goes in
byte for byte so its own signature survives, and its JNI libraries are
extracted beside it: a bundled app never has them extracted at install, so a
compressed library otherwise fails to load at runtime. The reasoning, and how
to add anything else, is in `docs/prebuilt-apps.md`.

**`docs/open-items.md` is the running list** of what is left to test or
build, grouped by what it waits on: the radio, Apple hardware, other
hardware, or nothing. It includes game streaming (Moonlight/Sunshine, Steam
Link). Add to it rather than scattering "still to do" sections.

**The image does not write-bash the eMMC.** Measured idle at about
0.06 GB/day with the screen on and 0.15 GB/day with the screensaver running,
plus about 17 MB per boot. The only waste is the Bluetooth crash loop on a box
with no radio. The details, and how to measure again, are in
`docs/emmc-writes.md`. Two things mislead: the eMMC queue has `iostats` off
by default, so `/proc/diskstats` looks idle whatever happens; and "Stay awake"
is on on the bench, so the screensaver never starts by itself.

## Forks (2026-09-27)

Six projects carry commits of ours and are now **synced from forks**, not
patched: `device/nvidia/porg`, `device/nvidia/tegra-common`,
`kernel/nvidia/kernel-4.9`, `packages/apps/TvSettings`,
`packages/apps/Catapult`, `vendor/lineage` — all under `calicopizzadelivery`,
on `lineage-22.2-jetson-tv` except the kernel, which forks `lineage-22.2_4.9`
and so is on `lineage-22.2_4.9-jetson-tv`. Four more are projects LineageOS
does not have, on `android-jetson-tv`: `external/uxplay` and
`external/libplist` (in the image), and `external/shairport-sync` and
`external/popt` (synced, no longer built).

**`tree-local-changes.sh` no longer patches upstream repos.** The BT_LE
defconfigs and the `wifi_loader.sh` fixes are commits on our forks now; that
script only installs `vendor/jetson-tv`. `repo status` across the whole tree
reports clean.

The mechanism is a repo **local manifest**, not submodules; submodules cannot
work here because the source tree is populated by `repo`, not by this repo.
Full reasoning, the three load-bearing details of the manifest, and the
ordering constraint against roomservice are in **`docs/forks.md`** — read it
before touching `local-manifest.sh` or running `repo sync` by hand.

The one dangerous failure is silent: a plain `repo sync` on a tree without the
local manifest puts those four back on LineageOS, and the build then produces
an image missing our work with no error. `build.sh` runs
`local-manifest.sh --check` to catch exactly that.

`patches/` still exists, but only as changes formatted for posting upstream.

## Testing Bluetooth without hardware (2026-09-28)

The emulator can host a **virtual Bluetooth A2DP speaker**, so audio routing is
testable with the bench powered down. netsim and Rootcanal already run under
every emulator, and Bumble (vendored at `external/python/bumble`) attaches to
them as a second device.

    ./scripts/emulator-bt-speaker.sh

**`docs/emulator-bluetooth.md`** has the whole thing, including the five traps
that cost a cycle each. The one worth knowing before touching any priv-app:
**`adb install -r` shadows the image copy with a `/data` update that keeps its
own manifest**, so permission changes look like they are being ignored no matter
how often you rebuild. `pm uninstall-system-updates <pkg>` reverts it.

Not yet tried: the same rig with a virtual BLE remote, which would answer the
open "does BLE pairing work on ARM64 Tegra" question above without a radio.

## Publishing

**Published**: https://github.com/calicopizzadelivery/jetson-nano-android-tv
(public, Apache-2.0). First pushed 2026-09-13; brought up to date 2026-09-24
with the disk notes, the extract/build fixes and the first successful build.
`origin` is the SSH remote, matching `gh`'s configured git protocol.

The repo's local `user.email` is set to the GitHub `noreply` address
(`300208363+calicopizzadelivery@users.noreply.github.com`) so a pseudonymous
handle isn't publicly tied to a personal email. **Leave that config alone** —
it is what keeps new commits clean, and it differs from `GIT_USER_EMAIL` in
`.env`, which is only the in-container identity `repo` needs. Re-verified
2026-09-24: zero occurrences of the personal address in tracked files or
anywhere in history, and no credential-shaped strings in history either.

`hardware/` is gitignored — NVIDIA design packages, and reference material for
the separate MythTV Porg carrier-board project rather than anything this build
needs.

## Reference

Fuller research lives in the "Nvidia Shield Clone" Claude project:
`claude/research/google-tv-on-jetson-brief.md` and
`claude/research/jetson-nano-hardware-plan.md`.

Upstream: https://wiki.lineageos.org/devices/porg/build/variant1/ ·
https://github.com/LineageOS/android_device_nvidia_porg ·
`#LineageOS-dev` on Libera.Chat
