# Screensaver on Android TV

Findings from `lineage_sdk_tv_x86_64`, 2026-09-26. Every value below was
observed, not inferred from documentation.

## The settings that actually matter

| Setting | Value | Why |
| --- | --- | --- |
| `system screen_off_timeout` | `900000` | **This is the idle timer that starts the dream.** 15 minutes. |
| `secure sleep_timeout` | `86400000` | Must stay *longer* than the above, or it wins the race and the box hard-sleeps instead of dreaming. |
| `secure screensaver_enabled` | `1` | On by default already. |
| `secure screensaver_activate_on_sleep` | `1` | `0` out of the box. |
| `secure screensaver_components` | a dream | **Unset out of the box**, so the screensaver reads as enabled with nothing to show. |
| `global stay_on_while_plugged_in` | `0` | See below. |

## Three traps

**`stay_on_while_plugged_in` blocks everything.** It is `1` on this image, and
a set-top box is permanently "charging" (`mIsPowered=true`, `mPlugType=1`), so
`mStayOn=true` and the idle timer never fires. With it set, the screensaver can
never activate on a mains-powered Jetson no matter what else is configured.

**The two timeouts race.** `sleep_timeout` is a hard sleep, `screen_off_timeout`
is what naps into a dream. Setting `sleep_timeout` short produces
`mWakefulness=Asleep` with `mCurrentDream=null` — the box blanks but never
dreams. Correct ordering gives `mWakefulness=Dreaming`.

**Only one dream is installed**: `com.android.dreams.basic.Colors`, a colour
gradient. Fine as a proof of life, not what a MythTV box should ship.

## Starting a dream on demand

`ACTION_DREAM_SETTINGS` resolves to nothing on TV. Two usable entry points,
both in TvSettings and both exported:

- `com.google.android.pano.action.SLEEP` → `DaydreamVoiceAction`, which calls
  `DreamBackend.startDreaming()`. No permission needed by the caller, which is
  why the launcher tile uses it.
- `…device.display.daydream.DaydreamActivity` by explicit component (no intent
  filter) for the picker.

Calling `IDreamManager.dream()` directly needs `WRITE_DREAM_STATE`, so the
indirection is worth keeping.

## Detecting playback, so the screensaver does not fire mid-film

**The platform already does most of this.** A player that holds the screen on
(`FLAG_KEEP_SCREEN_ON`, or a `SCREEN_BRIGHT` wake lock) keeps the user-activity
timer from expiring, so the dream never starts. `dumpsys power` shows it as
`Wake Locks: size=` and `mWakeLockSummary=`. Explicit detection is only needed
for players that fail to do this.

There is **no API that reports "a video is on screen"**. The available signals,
all observed working on this build:

| Signal | API | Permission | Good for |
| --- | --- | --- | --- |
| Wake locks | `dumpsys power`, `PowerManager` | — | What the platform itself uses; catches any app holding the screen on |
| Audio players | `AudioManager.getActivePlaybackConfigurations()` | none | `state:started` plus `AudioAttributes` usage/content type — `CONTENT_TYPE_MOVIE` is the closest thing to "video" |
| Media sessions | `MediaSessionManager.getActiveSessions()` | `MEDIA_CONTENT_CONTROL` (a privileged, platform-signed app such as Catapult can hold it) | Per-app `PlaybackState.STATE_PLAYING`, and which app it is |

`dumpsys audio` prints the same `AudioPlaybackConfiguration` records the API
returns, so it is the quickest way to check what a given player reports.

Caveat worth testing on real hardware: silent or muted video sets no audio
attributes, and an app can play video while reporting
`CONTENT_TYPE_UNKNOWN`. Wake locks remain the most reliable single signal.
