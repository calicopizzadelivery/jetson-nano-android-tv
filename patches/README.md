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
