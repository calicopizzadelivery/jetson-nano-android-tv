# Upstream patch queue

Changes we intend to send to LineageOS, kept as `git format-patch` output so
they stay rebaseable and can be posted to Gerrit unmodified.

These are **not** applied by `tree-local-changes.sh`. That script carries local
configuration we need for this board; this directory carries changes that
should stop being ours as soon as they land upstream.

## Applying

    cd /srv/build/jetson-tv/lineage/packages/apps/TvSettings
    git am /path/to/patches/TvSettings/0001-*.patch

## TvSettings/0001 — skip accessory pairing when input is attached

The setup wizard's accessory pairing step exists so a user with no input device
can pair a remote. It offers no way to leave. On hardware with no Bluetooth
radio fitted the scan can never succeed, so the step is a dead end.

Adds a "Skip" row when a non-virtual keyboard, d-pad or gamepad is present, and
finishes with `RESULT_OK` so the caller advances.

**Status**: compiles (`m TvSettings`, 1m44s). Not yet run. Validate on
`lineage_sdk_tv_x86_64` before submitting — see "Emulator" in CLAUDE.md.

**Note on the original diagnosis.** This was first investigated because Escape
appeared to do nothing on that screen. That was a red herring:
`PhoneWindowManager` consumes `KEYCODE_ESCAPE` with no modifiers
(`closeSystemDialogs()`, returns true) so it never reaches the activity, and
`Generic.kl` maps Escape to `KEYCODE_ESCAPE` while `KEYCODE_BACK` is keycode
158. A real BACK may well already leave the screen — nothing in TvSettings sets
the `onBackPressed` extra that `BluetoothSetupActivity` re-launches on. The
patch stands on its own as a visible affordance, but it is not a fix for a
confirmed hang, and the commit message does not claim to be.

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
