# Jetson Nano 4GB — hardware plan and use-case verdicts

*Scope narrowed 10 Sep 2026: target is Jetson Nano 4GB (mix of production eMMC modules and developer SD modules). Use case is Kodi / Plex / Google Cast / emulation.*

## Bluetooth — buy a BCM4356 card

The LineageOS `porg` tree ships firmware for exactly two Broadcom parts and nothing else. From `device/nvidia/tegra-common/extract/file.list`:

```
stock-t210  vendor/firmware/bcm4350.hcd     bcm/bcm4354/BCM4350C0.hcd
stock-t210  vendor/firmware/bcm4356a3.hcd   bcm/bcm4356/BCM4356A3.hcd
stock-t210  vendor/firmware/brcmfmac4356-pcie.bin  bcm/bcm4356/
stock-t210  vendor/firmware/fw_bcmdhd.bin          bcm/bcm4354/
```

`.hcd` = Broadcom Bluetooth patchram. BCM4354 is SDIO (SHIELD/TX1 on-module), so for an M.2 Key E slot the answer is **BCM4356 over PCIe + USB**.

Maintainer (Steel01/webgeek1234) statements:

- 20 Feb 2023: "I picked a bcm4356 module to use as the supported module for Lineage, since all other Lineage supported t210 devices use a bcm4354 or bcm4356."
- 1 Feb 2023, on Intel AC8265: "The only supported wireless card is a bcm4356. Someone put in some work to support intel cards, but it was not completed."
- Nov 2023, on BCM94360Z3 / BCM94352Z: only BCM4356 firmware is included; other Broadcom/Cypress parts need a custom build with no guarantee.
- 21 Jan 2024, on a Lenovo **BCM94356Z**: "as long as it works with standard 4356 firmware, it should work fine with the official builds."

**Recommended part: BCM94356Z**, M.2 2230 Key A/E — Lenovo FRU `00JT478`, shipped in T460s / X260 / Yoga 260 / Y700. Cheap and plentiful on eBay/AliExpress as pulls. WiFi 5 (867 Mbps) + **Bluetooth 4.1**.

Known-bad, do not buy:

- **Intel AC8265** — the card everyone sells for the Nano. Unsupported; Intel work was started and abandoned.
- **RTL8822CE** — tested on Nano LineageOS in Feb–Mar 2023, neither WiFi nor BT worked. Steel01 offered a patch + property override ("Realtek 'mostly' works with the broadcom wifi hal"), the reporter tested it, still dead, and the maintainer declined to chase it: "someone else that does wish to do so will have to figure out why it doesn't work."
- BCM94360Z3, BCM94352Z — wrong firmware.

Why non-Broadcom fails even though Linux has drivers: Android wants a vendor-specific WiFi HAL, and the tree has no runtime detection of which module is fitted. Steel01: "First is that I don't have an easy way to dynamically detect which wireless module is installed. Second is that android requires different hals for different vendors wireless modules."

**Caveat to test:** BLE was one of the issues that held back LineageOS 19.1/20 on ARM64 Tegra at the time. 22.2 ships now, so presumably fixed, but verify BLE remote pairing early — a Bluetooth stack that does classic A2DP but not BLE is useless for a TV remote.

**BT 4.1 ceiling:** fine for BLE remotes, Xbox/DualShock/DualSense/8BitDo controllers, A2DP headphones. No LE Audio, no BT 5.x range/throughput.

## Module SKUs — both developer and production are supported

`device/nvidia/porg/flash_package/p3450.sh` reads the module SKU and selects layout + DTB automatically:

| SKU | Module | Boot media | Flash layout | DTB |
|---|---|---|---|---|
| 0 | P3448-0000 (devkit module) | microSD | `flash_android_t210_max-spi_sd_p3448.xml` | `tegra210-p3448-0000-p3449-0000-a02` / `-b00` |
| 2 | P3448-0002 (production, 16GB eMMC) | eMMC | `flash_android_t210_emmc_p3448.xml` | `tegra210-p3448-0002-p3449-0000-a02` / `-b00` |
| 3 | P3448-0003 (Nano 2GB) | microSD | `..._max-spi_sd_p3448.xml` | `tegra210-p3448-0003-p3542-0000` |

BCT: `P3448_A00_lpddr4_204Mhz_P987.cfg` (0003 variant for SKU 3). Signing and flashing run through `tegraflash.py` from the same script. Officially supported carrier revisions are a02 and b01.

So a single flash package covers both module types — no fork needed. eMMC modules give a materially better appliance (no SD wear-out, faster random I/O); dev modules are better for iterating since a bad image is a card swap rather than a USB-recovery reflash.

**Known constraint:** the bootloader cannot be updated in place (L4T cboot limitation) — bootloader changes need a full reflash over USB recovery, which on Nano means jumpering FRC/REC before applying power.

## Graphics and media — better than expected

Contrary to the "no NVIDIA Android userspace" story that applies to Xavier/Orin, T210 gets the **full SHIELD stack**, extracted from NVIDIA SHIELD recovery OTA images (`stock-t210`, `stock-foster`, `stock-sif`) plus public L4T r32.7.6 / r32.6.1 tarballs and the `nv-tegra-t210-rel24` prebuilts git. From `file.list` (~350 lines):

- **Graphics:** `libEGL_tegra.so`, `libGLESv2_tegra.so`, `libGLESv1_CM_tegra.so`, `libglcore.so`, `gralloc.tegra.so`, `libnvhwcomposer.so`, the `libnvrm_*` family — and **`vulkan.tegra.so`**. Vulkan is available, which matters a great deal for emulators.
- **Media:** the full NVMM/OpenMAX stack — `libnvomx.so`, `libnvmm*.so`, `libnvmedia.so`, `libstagefrighthw.so`, `libnvparser.so`. Hardware decode via NVDEC (`nvhost_nvdec020_ns.fw` is in the L4T firmware pull).
- **Audio:** `audio.primary.tegra.so`, plus Dolby — `ddp_enc_lib_ac3.so`, `ddp_enc_lib_eac3.so`, `ddp_udc_lib_ac3.so`, `dp_dap_lib.so`, `DolbyAudioService.apk`. The tree carries **dolby / nodolby variants** of hwcomposer, `libnvomx` and `libnvmmlite_video` (Dolby set from `stock-t210`, non-Dolby from `stock-foster`).
- A Mesa path (`TARGET_GRAPHICS=mesa` → `BOARD_MESA3D_GALLIUM_DRIVERS += nouveau tegra`) exists as an alternative and is what Xavier/Orin need; T210 does not depend on it. NVK covers Kepler and later, so Maxwell/GM20B is in range if that path is ever preferred.

**Codec ceiling — the real media limitation.** Tegra X1's NVDEC does H.264, HEVC, VP9, VP8, MPEG-2, VC-1. It does **not** do AV1. In 2026 that is a growing share of streaming and of newer rips. AV1 will fall back to software on four Cortex-A57s — fine for 1080p at low bitrate, not for 4K.

## Use-case verdicts

| Use case | Verdict |
|---|---|
| **Kodi** | Strong. Native Android build, MediaCodec hardware decode through the NVMM stack, HDMI 2.0 out. Watch AV1 content and passthrough audio config. |
| **Plex** | Strong as a *client*. Direct play of H.264/HEVC/VP9 is what the hardware is built for. Do not run Plex *server* transcoding on it. |
| **Emulation** | Strong — this is literally SHIELD/Switch silicon with Vulkan available. Up to GameCube/Wii/PS2-class is the realistic ceiling; 4GB RAM is the binding constraint more than the GPU. |
| **Google Cast** | **Blocked.** Not a packaging problem — a cryptographic one. Sideloaded `com.google.android.apps.mediashell` fails with "Cast certificate is not valid" / "No private key, hence empty signature". The cast certificate is provisioned per-device by the licensed OEM, exactly like a Widevine keybox. |

### Other hard blockers

- HDCP is not implemented in NVIDIA's Tegra Linux driver and never will be.
- Widevine L1 requires a factory-provisioned keybox. L3 only → SD Netflix.
- Google announced (Oct 2025) it will block YouTube on uncertified AOSP devices.

### Casting alternatives that do work

- **Plex Companion** — the Plex mobile/web app controls and pushes to a Plex for Android TV client over Plex's own protocol. Covers the Plex half of "cast" completely.
- **Jellyfin** — same idea via its session API to Jellyfin Android TV.
- **Kodi UPnP/DLNA renderer** — built in, plus the Kore remote app.
- **FCast** — open-source casting protocol with an Android TV receiver, if you want something Cast-shaped that isn't Google's.
- **YouTube via DIAL** is a dead end: it needs the certified YouTube ATV app, which is the app Google announced it would block on AOSP devices.

## Sources

- [Jetson Nano LineageOS RTL8822CE — no wifi or BT](https://forums.developer.nvidia.com/t/jetson-nano-lineageos-rtl8822ce-no-wifi-or-bt/243353)
- [Lineage Android OS for the Jetson Nano](https://forums.developer.nvidia.com/t/lineage-android-os-for-the-jetson-nano/193845) (pages 6–7 for wireless module discussion)
- [LineageOS/android_device_nvidia_porg](https://github.com/LineageOS/android_device_nvidia_porg) — `flash_package/p3450.sh`, `flash_android_t210_emmc_p3448.xml`
- [LineageOS/android_device_nvidia_tegra-common](https://github.com/LineageOS/android_device_nvidia_tegra-common) — `extract/file.list`, `extract/sources.txt`, `BoardConfigTegra.mk`
- [LineageOS/android_device_nvidia_t210-common](https://github.com/LineageOS/android_device_nvidia_t210-common) — `extract/file.list`, `extract/sources.txt`
- [porg build guide — LineageOS Wiki](https://wiki.lineageos.org/devices/porg/build/variant1/) · [porg nightlies](https://download.lineageos.org/devices/porg/builds)
- [Broadcom BCM94356Z — WikiDevi](https://wikidevi.com/wiki/Broadcom_BCM94356Z)
- [opengapps issue #568 — AndroidMediaShell fails](https://github.com/opengapps/opengapps/issues/568)
- [NVK — Mesa documentation](https://docs.mesa3d.org/drivers/nvk.html)
