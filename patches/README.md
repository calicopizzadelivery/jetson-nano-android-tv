# Upstream patch queue

Changes we intend to send to LineageOS, kept as `git format-patch` output so
they stay rebaseable and can be posted to Gerrit unmodified.

These are **not** applied by `tree-local-changes.sh`. That script carries local
configuration we need for this board; this directory carries changes that
should stop being ours as soon as they land upstream.

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
