# Open items (RAIL)

The running action item list (RAIL): what is still to test or build, grouped
by what it is waiting on. Each item points at the document with the detail. When one closes,
move its result into that document and strike it here.

Last reviewed 4 October 2026.

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
   **Test with a SHIELD remote first:** the image already carries SHIELD's
   support for all three. There are key layouts for the 2015, 2017 and 2019
   remotes (`0955:7212`, `7213`, `7217`), and NVIDIA's driver for them,
   `hid-jarvis-remote.ko` (`hid-atv-jarvis.c`, which also turns the
   microphone into an ALSA capture card), loads at boot. Pairing mode: hold
   Select until the light flashes; a remote paired before needs Home + Back.
   Things to check beyond the d-pad: the 2019 remote's Settings key (which
   our framework config sends to Settings, not the launcher), its Netflix
   key (`BUTTON_4`, which nothing here handles), volume (box volume, or the
   TV's over CEC), and whether the microphone shows up. Its IR blaster and
   remote finder are set up from NVIDIA's own app, which we do not have.
3. **Bluetooth on an RTL8822CE.** Its Bluetooth half is on USB and needs
   Realtek firmware. Looked at 1 October: porg uses the generic `btlinux`
   HAL, which drives whatever the kernel's `btusb` brings up, and the image
   has `btusb` and `btrtl`. What it lacks is the firmware:
   `rtl_bt/rtl8822cu_fw.bin` and `rtl8822cu_config.bin` from
   linux-firmware. Realtek's own `rtk_btusb.ko` also ships and loads, and
   would compete with `btusb` for the device, so it probably has to stay
   unloaded. Likely a firmware drop plus a module-list change, but untested.
   Until then, a box meant to be used with a Bluetooth remote needs the
   Broadcom card.
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
~~16. **Pairing controls on the TV.**~~ Done 1 October: the Streaming tile
    turns the code on and off and lists and forgets paired devices. See
    `docs/airplay.md`, "Pairing with a PIN".
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
18b. **Source release, before any image is published.** Every image carries
    copyleft code: the kernel, about two dozen AOSP userspace projects, our
    AirPlay stack and the four third-party apps. Each release needs its
    pinned manifest, a source bundle published beside the image, and a
    pointer to it in the device's notices. The manifest, the bundle script
    and the notice can be built now; the publishing waits for the first
    release. See `docs/source-release.md`.
18c. **Kodi's eMMC writes, once it has a real library:** thumbnails and its
    SQLite databases. Use `scripts/emmc-*`. Also HEVC playback in Kodi, which
    needs a clip made somewhere with an HEVC encoder.
~~18d. **The box calls itself "SHIELD Android TV".**~~ Done 30 September: it
    is JetsonTV, for the device name, Bluetooth and AirPlay. See
    `docs/branding.md`.
19. **Housekeeping.** `/dlcache` is still unprimed (next `extract`).
    *`patches/README.md` was brought up to date 3 October: every entry now
    carries a status, the DocumentsUI, atv, UxPlay and libplist changes are
    exported, and the Shairport-era porg entries are marked superseded.*
19a. **Send the upstreamable changes.** Nothing has been offered to anyone
    yet. Every one of these is exported and written up in
    `patches/README.md`; the subsection below is the shortlist and what to
    say when sending each. Taken 4 October from an inventory of all 12 forks.

### What is ready to send, and to whom

Of 26 changes across 12 forks, these stand on their own — they fix something
general and carry nothing specific to this box. The rest depend on our AirPlay
receiver, our key handler, or porg policy, and are ours to keep.

**To AOSP, and to LineageOS**

- **`DocumentsUI/0001` — a d-pad can reach the file list.** The strongest
  candidate here. Directional focus does not cross from the picker's header
  into its file list, and the Tab key that would is on no remote or game
  controller, so on a television the files cannot be reached at all. It fixes
  the picker for *any* d-pad device; Android TV is simply the case nobody
  tests. Say that focus still starts on the header button when one is shown,
  which one press now leaves — before, it could not be left.

**To LineageOS**

- **`Catapult/0009` — one tile per app.** An app with separate TV and phone
  launchers appeared twice. Apps with only a phone launcher are unaffected.
- **`Catapult/0010` — show the wallpaper on the home screen.** The launcher
  painted a flat colour over whatever wallpaper was set. A stock build would
  show LineageOS's own default.
- **`TvSettings/0002` — a row for the screen saver's own settings.** A screen
  saver with anything to configure is configurable exactly once, at the moment
  it is first chosen, because `onPreferenceChange` only fires on a *change*.
- **`tegra-common/0001`, the path half only.** Loading modules from
  `/vendor/lib/modules` is unambiguous. **Do not send the Realtek half**: no
  radio has ever run it. Split the commit before offering it.

**To UxPlay** (<https://github.com/FDH2/UxPlay>) — the most useful thing here
to anyone else, and nothing to do with Android:

- **`uxplay/0003` — bound the fp-setup mode and the SETUP ekey/eiv sizes.**
  A sender chose both and neither was checked, so any device on the same
  network could walk a mode off the end of a table or overrun the buffers the
  key material is copied into. **No pairing needed to reach it.**
- **`uxplay/0004` — make PIN pairing required, not merely offered.** A client
  could skip pair-verify and stream anyway, and one that asked to pair without
  requesting a code was handed "0000".
- **`uxplay/0002` — bound the ALAC decoder against crafted frames.** Any LAN
  device could overrun the heap. The same decoder came from Shairport and
  exists in other projects, so report it there too.

Say plainly in all three that **no real Apple sender has met this build**: the
fixes are compiled, running and fuzzed, but not demonstrated against the
hardware they defend against.

**Not a patch, a question** — `atv/0001`. An Android TV product cannot add its
own device key handler without editing `device/lineage/atv`, because
`config_deviceKeyHandlerLibs` and `config_deviceKeyHandlerClasses` are one
resource each: an overlay replaces them rather than extending them, and a
product overlay listed *after* that file still loses. Worth asking LineageOS
whether products are meant to append, rather than sending our one-line entry.

**Android plumbing, offer but do not push**: `uxplay/0001`, `uxplay/0005` and
both libplist patches build these libraries with Soong and host UxPlay's
protocol library over JNI. Useful to anyone putting UxPlay on Android, of no
use to a desktop build. `uxplay/0006` and `libplist/0002` only declare licences
for Android's notice system.

## Game streaming (new, 30 September)

20. **Moonlight (with a Sunshine host) and Steam Link.** *Moonlight measured
    30 September: viable, and now in the image (`docs/prebuilt-apps.md`).* 1080p60 is solid (60/60 fps, 0% drops, ~1 ms
    decode). 4K60 decodes at a full 60 fps too, at the same ~70%-of-400% CPU.
    See `docs/game-streaming.md`. Still to do:
    - 4K against a real gaming PC with GPU capture: thebe's rootless
      Xephyr/XShm capture was the 4K bottleneck.
    - Controllers: USB now; Bluetooth waits on items 2 and 4. See 21.
    - Audio and surround, and HDR (items 8 and 9).
    - Wi-Fi, once the radio is in.
    - Steam Link, if it can be installed without the Play Store.
    The assessment below was written before measuring and is kept for
    comparison.
21. **8BitDo pads over USB and 2.4 GHz.** Looked at 1 October. The kernel
    has the gamepad drivers (`xpad` with rumble, `hid-nintendo` backported,
    `hid-sony`, `hid-microsoft`, `hid-steam`), all registered on porg, and
    the image has key maps for Xbox, PlayStation, Switch Pro, Steam and the
    8BitDo SN30 Pro (`2dc8:6101`, Android mode). Bluetooth in 8BitDo's
    Android/D-input mode is plain HID and should work once there is a radio.
    The gap is X-input over USB or 8BitDo's 2.4 GHz receiver: newer pads
    report 8BitDo's own vendor ID there (`2dc8:3106` Ultimate / Pro 2 wired,
    `2dc8:310a` Ultimate 2C, plus the Ultimate 2), and this 4.9 `xpad` does
    not list `0x2dc8` at all. Upstream added it in 2024-25. *Backported 1
    October (kernel/0002), with key layouts for the seven IDs; in the image
    and booted. Confirmed 2 October with an Ultimate 2C on its own 2.4 GHz
    receiver*: `xpad` claims it, and all four face buttons, both shoulders,
    Select, Start, both stick clicks, both sticks at full travel, both
    analogue triggers and the d-pad are correct. The key layout is provably
    ours, not the generic fallback: Android reads the right stick as Z/RZ
    and the triggers as LTRIGGER/RTRIGGER, which only our file specifies.
    Two things to know about that receiver:
    - **Idle, it is `2dc8:301c` with the product string "IDLE"**, a
      vendor-specific HID interface with no gamepad descriptor, so
      `hid-generic` takes it and no input device appears. It re-enumerates
      as `2dc8:310a` when the pad connects. Not a fault: no pad is attached
      to it yet.
    - **It also presents two further HID interfaces**, which Android
      registers as a separate mouse and keyboard device sharing the same
      key layout. Harmless so far; the thing to watch is an app seeing two
      controllers.
    **Confirmed in Moonlight 2-3 October**, against Sunshine on thebe with
    `CONTROLLER=enabled`: the pad drives Moonlight's own UI, Moonlight
    advertises it to Sunshine as an Xbox Series pad, and A, B, X, Y, L1, R1,
    L3, R3, the d-pad, Select, Start, both sticks and both analogue triggers
    all arrive on the host. **Rumble works** in both directions
    (`gamepad_probe.py rumble`). `scripts/gamestream/gamepad_hud.py` draws
    the pad's state onto the streamed picture, so this is now testable by
    looking at the television.
    - **Square, Star, L4 and R4 send nothing.** Pressed repeatedly on the
      2.4 GHz receiver, they produce no event at all on the Jetson, on either
      of the dongle's HID interfaces. They are pad-internal: Star switches
      profile, and the paddles report an existing button only once 8BitDo's
      own software assigns them one. Nothing to map.
    - **The centre button is a setting**, System > Buttons > Controller Home
      button (TvSettings/0004): on, `JetsonTVKeyHandler` makes it go Home;
      off, apps get `BUTTON_MODE`, so Moonlight hands the host a real Guide
      button for Steam Big Picture. Both verified on porg. Moonlight also
      synthesises Guide from Start+Select either way.
    Still to do: the pad inside Lemuroid (needs a ROM) and Kodi. Lemuroid's
    folder picker was unreachable with a d-pad until 3 October; see
    `docs/prebuilt-apps.md` and the DocumentsUI fork.

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
