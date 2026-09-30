# Open items (RAIL)

The running action item list (RAIL): what is still to test or build, grouped
by what it is waiting on. Each item points at the document with the detail. When one closes,
move its result into that document and strike it here.

Last reviewed 30 September 2026.

## Waiting on the Wi-Fi/Bluetooth radio

The bench has no M.2 Key E card. RTL8822CE is the card to buy for quantity;
BCM94356Z is the only chip whose Bluetooth firmware the image ships.

1. **Wi-Fi.** Both `wifi_loader.sh` fixes are untested: modules loaded from
   `/vendor/lib/modules`, and Realtek PCIe (`10ec:c822`) detection. Check that
   the module loads, the interface scans, it connects on 5 GHz, and what the
   throughput is. See `patches/README.md`, tegra-common/0001.
2. **BLE remote pairing.** `CONFIG_BT_LE` is in the kernel now
   (`kernel/0001`), but no remote has paired yet. This also decides whether
   the setup wizard's accessory step can be completed with a real remote.
3. **Bluetooth on an RTL8822CE.** Its Bluetooth half is on USB and needs
   Realtek firmware. The image's Bluetooth HAL and firmware are set up for the
   Broadcom parts. Find out whether that card gives Wi-Fi only.
4. **Bluetooth audio and controllers on real hardware.** The Audio output tile
   is verified against a virtual A2DP speaker only
   (`docs/emulator-bluetooth.md`). Controllers matter for game streaming, below.
5. **The Bluetooth crash loop at boot.** With no radio, `com.android.bluetooth`
   aborts in `hci_backend_aidl.cc:34` several times per boot. Each abort writes
   about 660 KB of tombstone plus dropbox copies. It should stop once a
   controller answers. Decide what a box with no radio does: probably keep
   Bluetooth disabled rather than crash-loop. See `docs/emmc-writes.md`.
6. **Miracast sink**, for Windows and Android senders. It needs Wi-Fi Direct,
   so nothing can start until the radio works. See
   `docs/streaming-targets.md`.

## Waiting on Apple hardware

7. **AirPlay, closed loop.** It has never met a real sender. Check PIN pairing
   first: a code the first time, a wrong code refused, a returning device let
   through, forgetting devices, and PIN off. Then mirroring from an iPhone and
   a Mac, audio from Music, lip sync, metadata and cover art, volume, and Back
   ending the session. The checklist, and the `sf` flag to watch, are in
   `docs/airplay.md`. A Mac is not needed: an iPhone mirrors from Control
   Center.

## Waiting on other hardware

8. **HDMI audio beyond stereo.** Multichannel LPCM and Dolby/DTS passthrough
   need an AVR or soundbar. The MS2109 captures 2-channel LPCM only.
9. **HDR output.** Needs an HDR television. The capture device's EDID offers
   no HDR, so the bench cannot say. Matters for Kodi/Plex and game streaming.
10. **HDMI-CEC** with a real television. The CEC HAL is running; nothing has
    been checked: TV remote passthrough, and power on and off with the TV.
11. **The remote's Settings button.** The framework consumes
    `KEYCODE_SETTINGS` (`PhoneWindowManager`, `config_settingsKeyBehavior` =
    0 opens Settings), so the launcher's handler for it (Catapult/0001) never
    runs. The keyboard Menu key does open the panel. Fixable with a config
    overlay; confirm the behaviour with a real remote first.
12. **A developer module (P3448-0000, microSD).** Only a production eMMC
    module has been flashed and booted.

## Can be done on the bench now

13. **VP9 and MPEG-2 decode** with Surface output. They are "unproven, not
    failed", because `decodetest` decodes to ByteBuffers. See CLAUDE.md,
    "Hardware codecs".
14. **A virtual BLE remote** on the emulator (Bumble and Rootcanal). It would
    exercise Android's BLE HID path, though not the Tegra kernel's.
15. **UxPlay hardening pass.** Two findings remain: the mirroring codec
    packet's parameter-set lengths are unchecked, and `X-Apple-Session-ID` on
    RTSP reaches an `assert`. See `docs/airplay.md`, Security.
16. **Pairing controls on the TV.** Turning the PIN on and off, and forgetting
    devices, are adb-only today. The Streaming tile is the natural home.
17. **Shairport leftovers** in the porg fork: the `shairport` SELinux domain,
    uid 7500, and the `interrupt` property label.
18. **eMMC write reduction.** See `docs/emmc-writes.md`.
18a. **Write barriers on /data**, for routine power pulls. *Shelved 30
    September.* Measured cost: about 1-1.5 ms per fsync. The plan: porg ships
    its own fstab with `barrier=1`, drops `noauto_da_alloc`, and checks
    `/cache` at boot. First, a relay-controller power-pull test to see whether
    this eMMC ever corrupts with barriers off, or only loses its last writes.
    Barriers do not help asynchronous saves such as `SharedPreferences.apply()`,
    which the AirPlay paired-devices list uses. See `docs/emmc-writes.md`,
    "Power loss".
18b. **Source mirrors for third-party apps, before any image is published.**
    Moonlight (GPL-3.0) now ships in the image. Mirror moonlight-android at
    v12.2 with its submodules, and do the same for each app added to
    `PrebuiltApps/apps.json` (Kodi, if it goes in). See
    `docs/prebuilt-apps.md`.
19. **Housekeeping.** `/dlcache` is still unprimed (next `extract`). Several
    statuses in `patches/README.md` are stale: porg/0002 now runs on hardware,
    and Catapult/0001's Menu key is verified while its Settings key is not.

## Game streaming (new, 30 September)

20. **Moonlight (with a Sunshine host) and Steam Link.** *Moonlight measured
    30 September: viable, and now in the image (`docs/prebuilt-apps.md`).* 1080p60 is solid (60/60 fps, 0% drops, ~1 ms
    decode). 4K60 decodes at a full 60 fps too, at the same ~70%-of-400% CPU.
    See `docs/game-streaming.md`. Still to do:
    - 4K against a real gaming PC with GPU capture: thebe's rootless
      Xephyr/XShm capture was the 4K bottleneck.
    - Controllers: USB now; Bluetooth waits on items 2 and 4.
    - Audio and surround, and HDR (items 8 and 9).
    - Wi-Fi, once the radio is in.
    - Steam Link, if it can be installed without the Play Store.
    The assessment below was written before measuring and is kept for
    comparison.

### Moonlight and Steam Link on this platform

This is the same silicon and the same decoder stack as the 2015 and 2017
SHIELD TV, which Moonlight's Android client was largely developed around
(NVIDIA GameStream began on SHIELD). porg ships SHIELD's own
`OMX.Nvidia.*` decoders, extracted from SHIELD OTAs. So "about as good as a
SHIELD TV" is the expectation to test against.

From the image and the bench:

| | This box | What it means for streaming |
| --- | --- | --- |
| Decoders | H.264, HEVC, VP9 to 3840x2176, rated 4K60, 120 Mbps cap | 4K60 HEVC is inside the rated envelope; 1080p60 has a lot of headroom |
| Measured throughput, 1080p | HEVC 409-589 fps, H.264 346 fps (`media_codecs_performance.xml`) | About 2-3 ms per frame of decoder time at 1080p. Latency is not the same as throughput, but this is fast |
| AV1 | none (T210) | Irrelevant: use HEVC, which Sunshine and Moonlight both prefer here anyway |
| Output | 3840x2160 at 60 Hz active; 50/60/30 Hz modes | No 120 Hz mode, so 4K60 or 1080p60 is the ceiling |
| Network | Gigabit Ethernet, full duplex | Far more than 4K60 needs (roughly 50-80 Mbps). Wired is the recommendation |
| CPU | 4x A57 at 1.48 GHz | Enough: the client's work is network and input, and decode is in hardware |
| HDR | unknown on this bench | HEVC Main10 decode exists; whether porg's display path signals HDR needs an HDR television (item 9) |
| Surround | unknown | Moonlight can play 5.1/7.1 as LPCM; untested here (item 8) |
| Controllers | USB works today; Bluetooth waits on the radio | Xbox Series pads are BLE, so they also wait on item 2 |

**Estimate (to be measured):** 1080p60 H.264 or HEVC should be solid over
Ethernet, with decode a few milliseconds. 4K60 HEVC should work, but inside the
decoder's rated limit rather than with headroom. End to end, a SHIELD-class
client on a wired LAN is typically in the low tens of milliseconds, and the
television's own input lag often dominates. None of these numbers has been
measured on this box yet.

**Risks:**
- Moonlight may tune behaviour by device model, and this box reports
  `porg`/Jetson Nano, not a SHIELD.
- There is no Play Store. Moonlight is available as an APK from its GitHub
  releases and from F-Droid. Steam Link is distributed through the Play Store,
  so getting it onto a box without Play is an open question.
- Bluetooth controllers are the real gap until the radio arrives.

**Test plan:** install Moonlight from its GitHub release. Pair it with a
Sunshine host on the wired LAN. Run 1080p60 and 4K60, H.264 and HEVC, with
Moonlight's performance overlay on, and record network latency, decode time
and dropped frames. Then repeat with Steam Link if it can be installed.
