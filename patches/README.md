# Upstream patch queue

Changes we intend to send to LineageOS, kept as `git format-patch` output so
they stay rebaseable and can be posted to Gerrit unmodified.

These are **not** applied by `tree-local-changes.sh`, and as of the fork
migration they are **not how the code gets into the build** either — see
`docs/forks.md`. The four projects these patch are synced from our own forks by
`scripts/in-container/local-manifest.sh`, so the tree already has the commits.

What this directory is for is the other half of the job: changes formatted for
posting upstream. `git format-patch` against the fork branch is what
regenerates them, and the drift between the two is checked by hand.

## Applying

    cd /srv/build/jetson-tv/lineage/packages/apps/TvSettings
    git am /path/to/patches/TvSettings/0001-*.patch

## TvSettings/0001 — say that Back skips accessory pairing

The pairing step is shown during setup so a user with no input device can pair
a remote, and it scans until something is found. **Back does leave it** — the
activity finishes and the caller advances — but nothing says so, and on
hardware with no Bluetooth radio the scan never succeeds, so it reads as a
dead end.

When a non-virtual keyboard, d-pad or gamepad is attached, the summary gains
"Press Back to skip this step." With no input attached the wording is
unchanged.

**Status**: verified running on `lineage_sdk_tv_x86_64`. The emulator
enumerates `id -1 "Virtual"` (skipped) and `id 0 "qwerty2"` (sources 0x301,
keyboard type 2); the hint renders, and `input keyevent 4` leaves the screen.

**This replaces an earlier, wrong version of this patch.** The first attempt
added a "Skip" row to `AddAccessoryPreferenceFragment` on the theory that the
screen was captive. Two things were wrong with that:

- The screen is not captive. Escape appeared to do nothing because
  `PhoneWindowManager` consumes `KEYCODE_ESCAPE` with no modifiers
  (`closeSystemDialogs()`, returns true) and `Generic.kl` maps Escape to
  `KEYCODE_ESCAPE`, not `KEYCODE_BACK` (keycode 158). Our HID injector is a
  boot-protocol keyboard and cannot send BACK at all. BACK was never tested
  until the emulator provided one.
- The row could never have been seen. On this screen the view tree contains
  only `content_fragment`; there is no `action_fragment` node, so the
  preference list is not laid out and any row added to it is invisible.

It also introduced a regression: `updateView()` takes
`prevNumDevices = screen.getPreferenceCount()` and starts the autopair
countdown only when that was 0, so a permanent extra row would have silently
disabled autopair in no-input mode — the one case the screen exists for.

## Catapult/0002 — screensaver, accessibility and audio output tiles

Three tiles that report state rather than just linking to a settings screen,
laid out two per row alongside the existing ones.

- **Audio output** reads the live output from `AudioManager`, ordered by what
  overrides what (A2DP / wired headphones > HDMI > built-in speaker). Opens
  `DisplaySoundActivity` via `com.android.settings.SOUND_SETTINGS`.
- **Screensaver** shows "Not set" when `screensaver_components` is empty —
  which it is out of the box, while `screensaver_enabled` is already 1, so the
  screensaver looks on and has nothing to show. Opens `DaydreamActivity` by
  explicit name: it is exported but has no intent filter, and
  `ACTION_DREAM_SETTINGS` resolves to nothing on TV.
- **Accessibility** counts running services, because "no services on" is the
  common case and worth seeing at a glance.

**Status**: verified on `lineage_sdk_tv_x86_64`. All three render with live
state and both new intents resolve and launch.

## porg/0001 — let the screensaver actually run

Device-level screensaver policy: point `config_dreamsDefaultComponent` at a
dream that exists on TV, set `config_dreamsActivatedOnSleepByDefault`, and
override `def_stay_on_while_plugged_in` back to false.

**Status**: verified in the built RROs (`framework-res__lineage_porg…` and
`SettingsProvider__lineage_porg…`). Runtime behaviour confirmed separately on
the emulator, where the same combination yields `mWakefulness=Dreaming`.

### Why this one is device-level and not upstream

Worth writing down, because the instinct is to push it up and it would be
wrong here. `device/google/atv` sets `def_stay_on_while_plugged_in` to true
for every Android TV device, with the comment "Keep screen on at all times by
default". That is a deliberate AOSP decision and it stays correct for
always-on panels and digital signage. Changing it upstream would alter
behaviour for every TV device to suit ours.

The rest of the timing in that same AOSP overlay already assumes a
screensaver — `def_screen_off_timeout` is 900000 "when setting screensaver",
`def_sleep_timeout` is 86400000 so a hard sleep does not pre-empt the dream —
so there is nothing to change there either. The 15-minute figure we wanted is
already the upstream default.

What that leaves genuinely upstreamable is the *behaviour*, not the policy:
the launcher tiles and the pairing hint, which are in the queue above. The
policy stays with the product.

One thing arguably *is* an upstream bug rather than policy:
`config_dreamsDefaultComponent` pointing at DeskClock, which no Android TV
build installs. That belongs in `device/google/atv` rather than here, and is
worth raising separately if we ever have a reason to send patches to AOSP.

## porg/0002 — ship AmbientDream as the screensaver

`porg/0001` pointed `config_dreamsDefaultComponent` at
`com.android.dreams.basic.Colors` because it was the only dream installed.
This repoints it at `AmbientDream` (see `docs/screensaver.md`) and pulls the
package in through `vendor/jetson-tv/jetson-tv.mk`.

The inherit is `inherit-product-if-exists`, so a checkout of
`device/nvidia/porg` without `vendor/jetson-tv` still configures. It then has
no dream installed — which is exactly where upstream is today, so the guard
costs nothing and loses nothing.

The dream component is set in **one** place, porg's overlay, on purpose. It is
tempting to put it in a second overlay dir under `vendor/jetson-tv` so the
package and the pointer travel together, but `generate_enforce_rro.mk` builds
**one** RRO per (target, partition) with every overlay dir handed to aapt2 as
`LOCAL_RESOURCE_DIR`. Two dirs setting the same resource would then be settled
by aapt2's argument ordering rather than by anything written down.

**Status**: verified in the built image —
`system/product/app/AmbientDream/AmbientDream.apk` is installed, and
`framework-res__lineage_porg__auto_generated_rro_vendor` dumps
`config_dreamsDefaultComponent` as
`org.lineageos.tv.ambient/org.lineageos.tv.ambient.AmbientDreamService` with
`config_dreamsActivatedOnSleepByDefault` true. Not yet run on hardware; the
bench is powered down.

This one is **not upstreamable at all**, and unlike `porg/0001` there is no
argument to be had about it: AmbientDream lives in `vendor/jetson-tv`, which
is ours.

## TvSettings/0002 — a row for the screen saver's own settings

Upstream reads a dream's `settingsActivity` into
`DreamBackend.DreamInfo.settingsComponentName` and `setActiveDream()` launches
it — but `onPreferenceChange` fires only when the selection *changes*. Once a
dream is the active one, selecting it again is not a change, so its settings
become unreachable. A screen saver with anything to configure is configurable
exactly once, at the moment it is first chosen.

Adds a "Screen saver options" row, visible only when the active dream declares
a `settingsActivity`. Dreams without one see no change.

Only `daydream.xml` gains the row. The X flavour has no dream picker at all —
it delegates to an `ambient_settings` slice — so there is nothing there to
attach to.

**Status**: verified on `lineage_sdk_tv_x86_64` with AmbientDream installed.
The row appears, launches the settings activity, disappears when Colors is
selected, and returns on reselecting. This one **is** upstreamable: it is a
generic gap, not a porg policy, and it is what makes
`patches/porg/0002`'s screensaver configurable.

## tegra-common/0001 — wifi_loader could not load any module

Two defects in `initfiles/wifi_loader.sh`. It insmods from
`/system/lib/modules`, which does not exist on a current build — modules go to
`/vendor/lib/modules` — so every branch tests a path that is never there and it
silently loads nothing. And `perform_enumeration()` matches only Broadcom
vendor ids, so a Realtek card is never detected and `$device` is never set,
while the tree ships a working `rtl8822ce.ko` that sits unloaded.

Adds the Realtek PCIe vendor id and an insmod branch for `10ec:c822`, loading
`cfg80211` first because the Realtek driver is built against it and nothing
else pulls it in on a board with no Broadcom radio.

**Status**: the path fix is unambiguous and upstreamable as-is. The Realtek
branch is **untested** — no radio is fitted to the bench. Do not send that half
upstream until it has run on hardware.

## kernel/0001 — enable CONFIG_BT_LE

`net/bluetooth/Kconfig` has `config BT_LE ... default y`, but every tegra
defconfig ships `# CONFIG_BT_LE is not set`, so the built kernel has no BLE at
all. Android TV remotes are BLE, so this breaks pairing whatever radio is
fitted, and is a plausible root cause for the "BLE does not work on ARM64
Tegra" reports that held back 19.1 and 20.

**Status**: verified present in the built kernel via `/proc/config.gz`.
Pairing itself is still unproven — that needs a radio.

## Catapult/0004 — make the audio output tile a picker

The tile reported the live output and then opened Sound settings, which on this
build has nothing to do with choosing an output device. It now lists the
built-in output and every connected Bluetooth sink.

Switching output *is* activating or deactivating a Bluetooth sink: A2DP
outranks HDMI in the platform's routing policy, so "use HDMI" means "have no
active Bluetooth audio device". The calls are
`BluetoothAdapter.setActiveDevice` / `removeActiveDevice` with
`ACTIVE_DEVICE_AUDIO` — the same pair TvSettings uses from `AccessoryUtils`.

Reading state stays on public API (`AudioManager.getDevices` plus
`AudioDeviceInfo.getAddress`); only the switch needs `BLUETOOTH_PRIVILEGED`, so
a build where that is refused still reports correctly. The A2DP profile proxy
is bound because `AudioManager` lists a Bluetooth sink only while it is the
*active* route and so cannot enumerate connected-but-idle ones.

The list is **bonded** devices, not connected ones: deactivating a sink also
drops its profile connection, so a connected-only list empties the moment you
switch to HDMI and you can never switch back. Selecting a bonded-but-
disconnected sink connects it — `BluetoothA2dp.connect` is `@hide` and Bluetooth
is a mainline module, so it is absent from the stubs even for a platform app;
`BluetoothDevice.connect` is the `@SystemApi` equivalent.

It needs **all three** of `BLUETOOTH_CONNECT`, `BLUETOOTH_PRIVILEGED` and
`MODIFY_PHONE_STATE`. The framework declares them `allOf`, and a missing one
fails at the binder with a `SecurityException` rather than at build time.

**Status**: verified on `lineage_sdk_tv_x86_64` against a virtual A2DP sink —
see `docs/emulator-bluetooth.md`, which explains how to attach one. Round trip
confirmed both ways: speaker → Bluetooth connects the profile and routes audio,
Bluetooth → speaker clears the active device and routes back, with the tile
repainting itself each way and ~495 KB of SBC over RTP arriving at the sink.
Still unproven on real hardware, where a physical speaker may behave differently
on reconnect.

## Catapult/0005 — make the accessibility tile toggle things

The tile counted enabled accessibility *services*. On a stock LineageOS TV
build that count is always zero — **no accessibility service is installed at
all**, TalkBack being a Google app — so it could only ever read "No services
on" and then hand off to the settings screen.

What the platform does have is switches. The tile now toggles the five the
TvSettings Accessibility screen exposes: bold text, high contrast text, colour
correction, captions and audio description. All are `Settings.Secure` writes
covered by the `WRITE_SECURE_SETTINGS` this package already holds, so no new
permission. "More settings" still reaches the full screen for font scale,
text-to-speech and any sideloaded service, and an enabled service still counts
towards the summary.

Two traps:

- **Bold text is not 0/1.** `FONT_WEIGHT_ADJUSTMENT` is a weight delta and
  TvSettings uses 300, so each toggle carries its own on/off pair rather than
  assuming a boolean.
- **Toggling it raises `CONFIG_FONT_WEIGHT_ADJUSTMENT`**, which recreated the
  activity and destroyed the open dialog — the first toggle applied and the
  dialog vanished, which looks like a dismiss bug and is not.
  `SystemOptionsActivity` now declares `configChanges="fontWeightAdjustment"`.

Also worth knowing: the constant names do not match the setting keys.
`ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED` is `"high_text_contrast_enabled"`,
and `ENABLED_ACCESSIBILITY_AUDIO_DESCRIPTION_BY_DEFAULT` is
`"enabled_accessibility_audio_description_by_default"`. Checking the wrong key
makes a working toggle look broken.

**Status**: verified on `lineage_sdk_tv_x86_64`. All five write their setting
and apply live — the panel visibly renders bold and high-contrast once those
are on — the dialog survives the bold-text toggle, and the summary updates on
dismiss.

## vendor_lineage/0001 — unblock the TV SDK products

All three `lineage_sdk_tv_*` products set
`PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := relaxed` but carry no allowed
list, so building any of them fails immediately on
`system/etc/permissions/android.software.credentials.xml`. The
`lineage_gsi_car_*` products already carry the entry; the TV ones were missed.

**Status**: required to build at all. `lineage_sdk_tv_x86_64-bp1a-userdebug`
builds (28m53s) and boots.

## Catapult/0001 — open the panel from a remote or keyboard button

The system options panel is currently reachable only by focusing the
notification indicator and selecting it: several d-pad presses away, and
undiscoverable.

Handles `KEYCODE_SETTINGS` (remotes with a settings button) and `KEYCODE_MENU`
(the application key on a USB or Bluetooth keyboard) in `MainActivity`, and
adds a shortcut row to the panel so it can be turned off. The toggle removes
the shortcut, not the panel — the notification indicator still works, so it
cannot strand a user who just disabled their only way in. Defaults to on.

**Status**: compiles (`m Catapult`, 49s). Not yet run.

Note for bench testing: our FRDM-K64F injector is a boot-protocol keyboard
(usage page 0x07 only) and its table has no Application key, so it cannot send
`KEYCODE_MENU` as shipped. HID usage 0x65 (Keyboard Application) maps to it —
adding that one entry to the firmware's key table is enough to drive this from
the bench.

## Catapult/0006 — a Streaming tile

The panel had no way to reach the AirPlay receiver: it was enabled by setting a
system property by hand over adb. The tile shows what the box is advertising as
a streaming target and offers a switch per target, with the second slot in the
row left for the video receiver.

The switch starts and stops the target's *service* rather than writing the
property, even though this package is allowed to write it. The property is only
what init watches to run the daemon; the service is what holds audio focus and
turns the daemon's metadata into a MediaSession, and a daemon started without
it plays over whatever else is on and puts nothing on screen. The service sets
the property once it is up, which is why the property is still what the tile
reads back.

Upstreamable as-is: the tile resolves each receiver's service before offering
it and hides itself when none is installed, so the patch is inert on a tree
without them.

## Catapult/0007 — every row of the panel the same width

The panel's first two rows (Sleep, Settings, Power; Network, Accessories)
had 16dp of side padding, and the tile rows added by 0002-0006 had none, so it
read as two widths. No row has side padding now. The top row is two columns
like the rest, Sleep on the left and Settings and Power sharing the right, so
Sleep is exactly as wide as the tile under it: 2:1:1 across three buttons had
left it 3dp short. The Network row takes the same 6dp top margin as the rows
below, so the rows are evenly spaced.

**Status**: verified on porg by HDMI capture: every row the same width, and
the columns aligned. **Known issue:** a focused tile's zoom now reaches the
panel's edge, so a focused edge tile's outer corner is clipped. The unpadded
tile rows from 0002-0006 always had this; the 16dp the first two rows had
was what gave the zoom room. The fix is a small uniform side padding on every
row; that decision is pending.

## porg/0003 — label shairport-sync by its real path

porg has no separate system_ext partition; `/system_ext` is a symlink to
`/system/system_ext`, and file contexts are matched against the real path when
the image is built. The `/system_ext/...` entry matched nothing, the binary
shipped as `system_file`, and init could not transition it into the `shairport`
domain. Both path forms are listed. (Exported on 29 September into the fork's
working tree by mistake; it only reached this directory on the 30th.)

## porg/0004 — a uid of its own for the AirPlay receiver

`AID_SYSTEM_EXT_AIRPLAY` (7500) in a porg `config.fs`. The daemon cannot run as
`audioserver` — libaudioclient treats that uid as the audioserver process
itself and hangs waiting for an in-process service — and should not run as
`media`, which audioserver trusts to attribute audio to other uids. See
`docs/streaming-targets.md`, "Why it was silent".

## porg/0005 — the policy the AirPlay daemon needs once audio flows

audioserver's callbacks into the daemon, PlayerBase's registration with
AudioService, and mediametrics — the last because a refused lookup costs ten
seconds inside `openStream`, not because anything uses the metrics.

## porg/0006 — label the AirPlay session-interrupt property

`jetsontv.airplay.interrupt`, written by the controlling app when another app
takes audio focus for good, and watched by init to restart the daemon — ending
the session without switching the receiver off. Same `shairport_prop` label as
the enable flag.
