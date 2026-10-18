# Open items (RAIL)

The running action item list (RAIL): what is still to test or build, grouped
by what it is waiting on. Each item points at the document with the detail. When one closes,
move its result into that document and strike it here.

Last reviewed 4 October 2026.

## Wi-Fi and Bluetooth

An **Intel Wireless-AC 8265** is fitted as of 4 October, and **Wi-Fi works**:
cold boot, firmware loads, `wlan0` scans both bands, and the picker in
Settings lists real access points. Everything about how, and the five defects
in NVIDIA's bring-up that had to be fixed first, is in `docs/wireless.md`.

1. ~~**Wi-Fi.**~~ *Closed 4 October.* Still open underneath it:
   - **Associate and measure.** Nothing has joined a network yet — it needs a
     passphrase. Check 5 GHz, then throughput, then game streaming over it
     (item 22).
   - **A Broadcom and a Realtek card have still never been tried.** Only the
     Intel branch of `wifi_loader.sh` has run. See items 1a and 3.
   - **Fix `lkm_loader_target.sh` properly.** It loads Cypress's stack on
     every boot keyed on the device tree, not on a card being present, and
     `cy_cfg80211` then owns `cfg80211`'s symbols so no SoftMAC driver can
     load. `wifi_loader.sh` unloads it again, which works but is backwards.
     The file is in `device/nvidia/foster`, which is **not one of our forks** —
     so this costs a thirteenth fork, or a way to override one file from
     porg. Worth doing before any second card is tested.
1a. **Test the Broadcom cards when they arrive.** BCM94356Z (`14e4:43ec`) is
   the only chip whose Bluetooth firmware the image already ships
   (`brcmfmac4356-pcie.bin`, `BCM4356A3.hcd`), so it is the card that should
   make a SHIELD remote pair without any further firmware work — which is the
   fastest route to item 2. Check: that `perform_enumeration` reports
   `0x14e4`, that the Broadcom branch of `load_modules` picks the right
   module for the device id, that the Cypress stack this time is *kept* rather
   than unloaded, and that Bluetooth comes up where Intel's does not. Then
   compare 5 GHz throughput against the 8265.
1b. ~~**Bluetooth on the Intel 8265.**~~ *Closed 4 October.* The adapter
   reaches ON at boot. One sepolicy line: AOSP grants the HAL
   `self:bluetooth_socket`, a class that only exists from Linux 4.13, so on
   this 4.9 kernel the access arrived as generic `self:socket` and the rule
   was never consulted. `docs/wireless.md`. This also closes item 5 — the
   Bluetooth crash loop was the HAL failing to start, not the absent radio.
2. **BLE pairing works** — *answered 8 October.* An Xbox Wireless Controller
   (`045E:0B13`) bonded over **HID over GATT** (`le_acl:true`,
   `hogp_available:true`, `bredr_acl:false`), produced an input device through
   `uhid`, and drives the launcher. That settles the long-standing "BLE does
   not work on ARM64 Tegra" question, and confirms the kernel fork's
   `CONFIG_BT_LE` booted. `docs/wireless.md`. Still open underneath it:
   - ~~**A SHIELD remote specifically.**~~ *Done 8 October.* The 2019 remote
     (`0955:7217`) bonded over BLE and `hid-jarvis-remote` bound to it. All
     sixteen keys in `Vendor_0955_Product_7217.kl` fire, including the gear
     key as `SETTINGS` and the Netflix key as `BUTTON_4` — the latter reaches
     the kernel and nothing handles it, as predicted. `docs/wireless.md`.
     Left over from it:
     - **The microphone is not an ALSA capture card.** An earlier note here
       claimed `hid-atv-jarvis` makes one; it does not. Android gives the
       device a `MIC` class and the button reports `ASSIST`, but
       `/proc/asound/cards` never gains an entry. Voice search is unproven,
       and would need a different audio path.
     - **Decide what `BUTTON_4` should do.** It is a free, labelled button on
       every SHIELD remote. Obvious candidates: a configurable app launch, or
       Jellyfin/Kodi.
     - The 2015 and 2017 remotes (`7212`, `7213`) are still untested, as is
       the IR blaster and remote finder, which need NVIDIA's own app.
   - ~~**Complete the setup wizard for real.**~~ *Done 8 October.* On a
     genuine first boot after `cmd recovery wipe ext4`, the accessory step
     paired the SHIELD remote by itself in 42 seconds and advanced, with no
     key pressed. The wizard set `tv_user_setup_complete` itself and is not
     left disabled. It was never inherently captive; it was captive because
     there was no radio. Full detail and the recovery procedure are in
     `CLAUDE.md`. Two things that came out of it:
     - **A factory reset turns adb off** (`adb_enabled` is in `/data`). The
       serial console is the way back: `/dev/ttyUSB0`, uid 2000, no login,
       `settings put global adb_enabled 1`, then accept the RSA prompt on the
       TV with the HID injector.
     - **Our Settings.Secure defaults survive a wipe**, because they are code
       defaults rather than stored values: the Netflix key still launched
       Kodi on the freshly reset device with `jetsontv_button4_package` null.
   - **The Xbox button as Home.** `Vendor_045e_Product_0b13.kl` maps it to
     `BUTTON_MODE`, the keycode our `GamepadKeyHandler` intercepts, so the
     Settings toggle ought to work for this pad too. Needs one deliberate
     press to confirm.
   - **Rumble over BLE.** Unproven, and `bta_hh_co` logs
     `Invalid event from internal uhid-dev: 115` repeatedly, which is likely
     the output report. See `docs/wireless.md`.
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
6. **Miracast sink**, for Windows and Android senders. It needs Wi-Fi
   Direct; the 8265 advertises a `P2P-device` interface, so this is now
   startable. See `docs/streaming-targets.md`.

## Waiting on Apple hardware

7. **AirPlay — mostly closed, 10 October.** An iPhone 13 Pro Max paired,
   played audio and mirrored: all three `pair-setup-pin` steps, a returning
   device let through without a code, metadata and a progress bar on the TV,
   RMS 0.17 measured on HDMI, FairPlay passed, and
   `OMX.Nvidia.h264.decode` with the picture letterboxed and legible.
   `docs/airplay.md`, "Verified with a real sender".

   It needed a fix first: PIN pairing was impossible for any client, because
   upstream demanded a 64-byte SRP proof where the configured SHA-1 produces
   20. Ours is `external/uxplay` `c4e3844`, and unlike upstream's it also
   closes the heap over-read that upstream master still has. **Worth
   sending** — see `patches/README.md`.

   Left over, cheap to do with the phone in hand:
   - ~~**The now-playing panel drops one element per session.**~~ *Fixed and
     verified 10 October.* Three partial-DAAP defects, all presenting as one
     symptom; `docs/airplay.md`, "Partial DAAP updates". Left over from it:
     a track with genuinely no artwork keeps the previous track's cover,
     because `image/none` is ignored rather than clearing. Not observed in
     use, and fixing it risks a flicker, so it is recorded rather than done.
   - ~~A wrong code being refused~~ *done 10 October: refused at
     `client proof not validated`, nothing entered the register.* But **a
     mistyped code cannot be retried** -- the PIN is spent at step 1, before
     the proof is compared, and the screen is torn down by
     `onConnectionsClosed`. A three-attempt budget was designed and
     **rejected in review**: enforced at step 1 it would allow 12 guesses per
     code, worse than today. **Fixed and verified 12 October** with the budget
     enforced at step 2 behind a generation number: two wrong codes then the
     right one, on the same code. `docs/airplay.md`, "The PIN gate". **The whole PIN matrix is now
     covered** (14 October): code shown, wrong code refused, retry works,
     budget exhausts, spent code retired, fresh code differs, returning device
     skips the code, forget works. The budget is 4 because a sender holding a
     stale password spends one by itself.
   - **Decide what the PIN is for.** `pair-pin-start` is unauthenticated and
     unthrottled and the code is 4 digits, so a LAN attacker needs a mean
     9,361 guesses -- minutes, not hours, at any plausible rate. No retry fix
     changes that. Either throttle and lengthen, or accept it for a home LAN
     and say so in the notices.
   - `FORGET_DEVICES` making a device ask again; `FORGET_DEVICES` making a device ask again;
     `persist.jetsontv.airplay.pin off`.
   - Rotation, lip sync, volume, pause/skip, Back ending the session.
   - ~~A **Mac**, mirror and extend.~~ *Done 10 and 14 October:* MacBookPro18,2 paired with its
     own code, 1920x1080 native, no errors; two devices in the register with
     distinct keys. Extend mode gives a real second desktop at the same 1920x1080, and
     re-creates the decoder three times across the mode switch. Still untried:
     Mac audio output (`Sound > output`), which should give AAC-LC -- the one
     codec of the three not yet seen.

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

13. **VP9 is answered; MPEG-2 still needs a clip.** *18 October.*
    `decodetest` grew a `-s` flag that decodes to a Surface, supplied by
    `AImageReader` — a real `ANativeWindow` with no display, whose frames can
    be acquired back, so a count under `-s` is evidence pictures were produced
    rather than merely that input was accepted.

    **The hypothesis it was built to test turned out to be wrong, which is the
    useful part.** VP9 does not need a Surface: it decodes in both modes, on
    real VP9 content, at 320x240 (712 fps) and 854x480 (257 fps). H.264 in
    both modes confirms the Surface path itself is sound. So the September
    `ERROR(0x80001020)` with zero frames was clip-specific — most plausibly a
    VP8 file fed to the VP9 component by forcing the name — and CLAUDE.md's
    codec table, which had said VP9 fails since September, is corrected.

    **MPEG-2 remains untested, and cannot be tested here**: no `.m2v`, `.mpg`
    or transport stream exists anywhere in the tree, and thebe has no ffmpeg
    to generate one. It needs a clip from outside. Lower stakes than VP9 — on
    a 2026 media box MPEG-2 is DVD rips and old broadcast captures, where VP9
    is YouTube.

~~14. **A virtual BLE remote** on the emulator (Bumble and Rootcanal).~~
    *Closed 18 October as superseded, not done.* Its purpose was to answer
    whether BLE pairing works on ARM64 Tegra without a radio fitted. Real
    hardware answered that on 8 October: an Xbox Wireless Controller and a
    2019 SHIELD remote both bonded over HID-over-GATT on an Intel 8265, and
    `hid-jarvis-remote` drives all sixteen keys of the remote's layout. The
    rig would re-answer a settled question. The emulator Bluetooth harness
    itself still exists and is still useful for audio routing — see
    `docs/emulator-bluetooth.md`.

~~15. **UxPlay hardening pass.**~~ *Done 18 October.* Both remaining findings
    fixed in the fork. The HEVC parameter-set sizes were read off the wire into
    a **signed** short, so anything from 0x8000 up went negative and walked the
    parse pointer backwards, with nothing bounding it against the end of the
    payload; they are unsigned now and every advance is checked.
    `X-Apple-Session-ID`'s allocation was covered by `assert()`, which is a
    no-op because `-DNDEBUG` is a Soong global, so a failed `calloc` fell
    through to a NULL `memcpy` on an attacker-supplied, unbounded header; it
    answers 500 now.

~~16. **Pairing controls on the TV.**~~ Done 1 October: the Streaming tile
    turns the code on and off and lists and forgets paired devices. See
    `docs/airplay.md`, "Pairing with a PIN".
~~17. **Shairport leftovers** in the porg fork.~~ *Done 18 October, and only
    part of it was actually dead.* The `shairport` domain, `shairport_exec`
    type and the binary's `file_contexts` labels are gone — that binary has not
    been built since 30 September. But `shairport_prop` was **not** a leftover:
    it is the label that makes every `persist.jetsontv.airplay.*` setting
    writable, including the pin, open-audio and legacy-service controls.
    Removing it with the rest would have silently broken all of them; it is
    renamed `airplay_prop`. Uid 7500 is kept deliberately — a device flashed
    before September may still own files as 7500, and dropping a uid underneath
    existing files misbehaves only on upgrade.

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
    pointer to it in the device's notices. See `docs/source-release.md`.

    **Two of the three are done, 18 October.** `releases/jetsontv-0.1.0.xml`
    pins all 1177 projects to exact SHAs, every fork is tagged `v0.1.0`, and
    all twelve of our pinned repos were verified fetchable from GitHub, so
    the manifest reproduces rather than merely describing. The pointer is on
    the device too: Settings > About > About JetsonTV carries the source URL
    and the licence statement, and the image's own notices now credit every
    licence in the tree.

    **The bundle script landed 18 October**: `scripts/source-bundle.sh` reads
    a pinned manifest and archives every copyleft project **at the commit the
    manifest names**, adds this repo (GPL-3 counts the scripts that build and
    install the work), writes a `MANIFEST.txt` recording what is included and
    at which commit, and refuses to call an incomplete bundle complete — a
    missing project is a non-zero exit, not a warning.

    Proven on 0.1.0: **36 projects, 0 skipped, 429 MB compressed**, with
    `external/uxplay` present at its pinned commit. The project list is the
    one `compliance_listshare` produced over a real build, kept deliberately
    broader than the strict minimum — angle, icu and freetype are flagged only
    for a few files or a dual licence Android takes the permissive half of,
    and including them costs a few hundred megabytes and removes the argument.

    **So 18b is now a publishing step, not an engineering one.** Manifest,
    device pointer and bundle all exist. What remains is attaching the bundle
    to a GitHub Release beside an image, which cannot happen until there is an
    image to publish (RAIL 22).
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
- **`kernel/0003` — build mac80211 and iwlwifi.** The only one of the wireless
  patches that is tested on hardware and needs no splitting.
- **`tegra-common/0002` — wifi_loader never ran its own code.** The headline
  is that `perform_enumeration()` and `load_modules()` are defined and neither
  is called, on any board, so this path has demonstrably never executed. Split
  off the Intel branch and the Cypress unload; the latter is a workaround for
  a bug in `foster/initfiles/lkm_loader_target.sh` and should go as a report
  instead. `porg/0007` goes with it, and belongs in
  `device/nvidia/sepolicy` rather than porg.

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

## Unauthenticated reachability (new, 14 October)

Found while reviewing the PIN gate, not by looking for them. Both predate
anything we changed and both are reachable from any device on the LAN with
the PIN on, because `pin_gate` only covers `fp-setup` and `SETUP`.

~~24. **An unpaired device can crash the receiver with one request.**~~
    *Done 18 October.* `raop_init` copied the embedder's callback struct
    verbatim, so the six AirPlay-video callbacks the Android embedder does not
    assign stayed NULL while `lib/http_handlers.h` called them from seven
    unguarded sites. Fixed with no-op defaults in `raop_init` rather than
    seven null checks: a check has to be remembered at every future call site,
    a default cannot be forgotten. The second half is fixed too — the gate
    meant to keep non-HLS builds out of those handlers read
    `!cseq && !raop->hls_support && !ble`, so any request carrying a `CSeq`
    header, which is every RTSP request, walked straight past it. The video
    URLs are now refused outright when `hls_support` is off, independently of
    `CSeq`. Two defences, not one.

~~25. **An unpaired device can drop the owner's live session.**~~
    *Narrowed 18 October; the residual is stated rather than closed.* `nohold`
    let **any** `CSeq`-bearing request from anyone evict the live session — a
    bare `OPTIONS` from a stranger ended whatever the owner was watching.
    Eviction now happens only on a request that actually begins a session:
    `ANNOUNCE`, `SETUP`, or a pairing/fp-setup `POST`. Every real sender opens
    with one of those; a prober does not.

    **What is not fixed, and cannot be at that point in the flow:** a
    determined device on the LAN can still displace the current session by
    sending a genuine pairing request. The eviction happens on first contact,
    before any authentication could have occurred, so "make it pair first" is
    not expressible there. Closing it properly would mean deferring the
    eviction until the new sender passes `pin_gate` at SETUP, which is a
    larger change to the connection-slot logic. The bound is unchanged:
    someone already on your network.

    The smaller siblings are unchanged and still true: `GET_PARAMETER` returns
    the current volume to anyone, and `FLUSH`/`TEARDOWN` are ungated
    per-connection no-ops.

## macOS Apple Music audio (new, 15 October)

26. **Done, 18 October. Mirroring, system audio and Apple Music all work at
    once.** No `legacyonly`, no sidecar. Full account in `docs/airplay.md`,
    "macOS audio: two paths, and both work now".

    The finding that resolved it: **macOS has two audio paths, not one.**
    Only Apple Music's own menu uses classic RAOP (FairPlay `0x02`, needs the
    RSA key). `Sound > Output` is an ordinary AirPlay 2 client on `0x03` and
    needs none of the classic machinery. An earlier note here said all macOS
    audio was classic; that was drawn from one test where both failed the same
    way, before `et`/`ek` were touched.

    | path | protocol | served by |
    | --- | --- | --- |
    | Apple Music menu | classic RAOP | `JetsonTV (Audio)` |
    | `Sound > Output` | AirPlay 2 | `JetsonTV` |
    | mirroring | AirPlay 2 | `JetsonTV` |

    Apple Music needed a second `_raop._tcp` advertised as a legacy device
    (`dnssd_register_legacy()`), because a sender that sees an AirPlay 2
    service will not offer RSA. Both services share the RTSP port, so the PIN
    gate, now-playing panel and renderers are the same for either.

    Measured, not judged by ear: L/R non-zero ratio 1.000 and lag-1
    autocorrelation 0.962 / 0.995 on the two audio paths, 0 silent windows in
    119 on both, mirroring clean at 1920x1080 through `OMX.Nvidia.h264.decode`.

    **A bench harness came out of this.**
    `./scripts/airplay/classic-selftest.sh` streams a 440 Hz tone through the
    whole classic path with no Apple device and no LAN, and checks it by
    frequency off the HDMI capture. It covers ANNOUNCE, the SDP parse, the
    unencrypted branch, the Transport SETUP, RECORD, the RTP loop and the L16
    render path; it does not cover the RSA unwrap, Apple-Challenge or ALAC,
    which need a real sender.

    **Still untested:** iPhone audio over AirPlay 2. It uses the same
    `_raop._tcp` record that `et` and `ek` were changed in, so it is the last
    untested regression from this work.

    **Known and not a defect:** system audio cannot connect to
    `JetsonTV (Audio)` -- it will not speak classic RAOP. It does not need to.

## Naming and attribution (new, 17 October)

27. **Done, 18 October. The fork is BearPortExpress, licensed
    GPL-3.0-or-later, with attribution in place.**

    The fork's README now leads with the name, the credits and the licence,
    and upstream's README is preserved unaltered below it rather than
    replaced. JetsonTV's own README and `docs/airplay.md` carry a short
    version with links.

    Credited: **UxPlay** (fduncanh), forked from; **shairport-sync** (Mike
    Brady), not forked but the reference the whole classic RAOP path was
    written from; **shairplay** (Juho Vähä-Herttua), the LGPL core most of
    `lib/` descends from; **David Hammerton** (ALAC) and **Tom Cocagne**
    (SRP).

    **The licence survey corrected what this item originally said.** It had
    UxPlay as GPLv3 and shairport-sync as MIT and stopped there, missing the
    LGPL-2.1 shairplay lineage inside UxPlay itself. Actual counts across
    `lib/` and `android/`: 44 files LGPL-2.1-or-later, 4 MIT (`srp`, `alac`),
    2 GPL (`crypto`). LGPL-2.1+ and MIT are both GPL-3 compatible, so
    GPL-3.0-or-later as the most restrictive is correct — but the reasoning
    now matches the tree.

    **The obligation, which is the easy thing to get wrong:** relicensing the
    whole does not relicense the parts. Every file keeps the copyright and
    permission text it arrived with. `libplist` is linked, not merged, and
    keeps its own LGPL-2.1 terms.

    **Not a licence question:** `lib/airport_key.h` is Apple's AirPort Express
    private key, covered by none of the above, included on the owner's
    explicit decision. `lib/rsakey.c` carries the reasoning. It is still
    gated on RAIL 18b in the sense that publishing an image is what triggers
    any source obligation at all.

## Distribution (new, 13 October)

Both of these turn a bench procedure into something a stranger can run, and
both are gated on **18b**: publishing an image is distribution, which is what
triggers the source obligations. Do not put a release anywhere public before
that is in place.

22. **One file per release.** Today an install is two artifacts and five
    manual steps (`CLAUDE.md`, "Flashing porg, end to end"):
    `p3450_flash_package.txz` (22 MB, tegraflash writes bootloader and
    recovery) and `lineage-22.2-<date>-UNOFFICIAL-porg.zip` (~979 MB,
    sideloaded from recovery). The goal is a single
    `JetsonTV-<date>-<n>.bin` a user downloads and hands to the flasher.

    Prerequisites, because today both artifacts need hand-fixing every time
    and a release process cannot:
    - **`m p3450_flash_package` is not re-runnable.** The signing step does
      `mv $OUT/signed $OUT/signed_boot`, which moves *into* the destination
      once it exists, and packaging ends with `cd $(dir $@); tar -cJf $@ *`,
      which tars the archive into itself on a second run. Fix the rules, or
      wrap them in a script that cleans first; do not keep doing it by hand.
    - **The version files are built wrong.** The recipe uses
      `$(TOYBOX_HOST) cksum` and AOSP's toybox has no `cksum`; the failure is
      swallowed because it sits in a pipeline whose status comes from `awk`.
      `emmc_bootblob_ver.txt` and `qspi_bootblob_ver.txt` are flashed into
      VER/VER_b, so the missing `BYTES:/CRC32:` line has to be appended.

    What the container needs to carry: both payloads, the device
    (`porg`) and a build id, per-payload checksums, the minimum bootloader or
    partition expectations if any, and a pointer to the matching source
    bundle for 18b. Prefer something self-describing and boring — a tar with
    a manifest, or a short header plus concatenated payloads — over a bespoke
    format. Sign it, or at least publish checksums beside it.

23. **JetsonTV Flasher.** A Linux desktop application that takes a `.bin` and
    walks a user through flashing a Nano, starting from recovery mode. **Its
    own repository**, not a directory in this one.

    The steps it has to own, with the traps already known from this bench:
    - **Getting into recovery.** The module needs FC_REC pulled to GND at
      power-on — a jumper on the carrier for a normal user, the recovery
      relay on this bench. The app should detect `0955:7f21` and, until it
      sees it, show how to get there rather than failing.
    - **Running tegraflash.** `flash.sh` exits unless `EUID` is 0 and needs
      `xxd` and `fdtput`. A desktop app therefore needs a privilege story:
      ship a udev rule for the RCM device and drop root, or use pkexec. Decide
      early; it shapes the packaging.
    - **The second stage.** Reboot to recovery, **format cache and data
      first** — CAC is flashed with no filesystem, so recovery cannot stage an
      install and `adb sideload` ends instantly with `Total xfer: 0.00x` and
      `Can't mount /cache/recovery/last_install` — then sideload the zip.
    - **adb in recovery is `unauthorized`** and there is no way to accept the
      prompt, so use `adb reboot sideload-auto-reboot` rather than planning to
      `adb reboot` back out.
    - **Only one adb server may hold the device.** A host adb server and a
      containerised one fight for the USB claim and the device appears in
      neither. The app should detect and say so, not hang.

    **Check the redistribution terms before bundling anything.** The flasher
    would carry NVIDIA's tegraflash from the L4T BSP, which is licensed
    separately from everything else here. If it cannot be redistributed, the
    app has to fetch it on first run instead, which changes its design. Settle
    this before building much.

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
