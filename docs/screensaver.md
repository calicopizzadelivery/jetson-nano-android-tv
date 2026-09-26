# Screensaver on Android TV

Findings from `lineage_sdk_tv_x86_64`, 2026-09-26. Every value below was
observed, not inferred from documentation.

## The settings that actually matter

| Setting | Value | Why |
| --- | --- | --- |
| `system screen_off_timeout` | `900000` | **This is the idle timer that starts the dream.** 15 minutes. |
| `secure sleep_timeout` | `-1` | **Never sleep.** `PowerManagerService.getSleepTimeoutLocked()` returns -1 for any value <= 0, removing the sleep transition. A positive value blanks the box when it expires — the upstream TV default of `86400000` means it dreams for a day, then shows no signal. |
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

**The box must never blank.** A dream is video output, so dreaming is the
resting state; sleeping is not. Verified: with `sleep_timeout` at -1 the box
reaches `mWakefulness=Dreaming` and stays on the same `DreamRecord`
indefinitely. With a positive value it goes to `Asleep` when that expires.

**Only one dream is installed**: `com.android.dreams.basic.Colors`, a colour
gradient. Fine as a proof of life, not what a MythTV box should ship.

The framework default, `com.android.deskclock/…Screensaver`, is the DeskClock
app's dream — a large digital clock. DeskClock is AOSP's stock Clock app
(alarms, timer, stopwatch, world clock) and is **not installed on Android TV**,
which is the whole reason `screensaver_components` comes up empty. Its source
is in the tree at `packages/apps/DeskClock`, and `packages/screensavers/`
holds two more: `Basic` (Colors) and `PhotoTable` (a photo slideshow, with
`PhotoTableDream` and `FlipperDream`). Any of the three could be built into
the product; none is a good MythTV screen as-is.

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

## AmbientDream

`vendor/jetson-tv/AmbientDream` (source in `scripts/in-container/AmbientDream`,
installed into the tree by `tree-local-changes.sh`) is the dream this project
ships: a clock over a slow slideshow of NASA photographs, modelled on the
Chromecast ambient screen.

| Behaviour | Value | Where |
| --- | --- | --- |
| Image rotation | 30 min | `DWELL_MS` |
| Crossfade | 2 s | `FADE_MS` |
| Weather refresh | 10 min | `WEATHER_MS` |
| Disk cache | 40 images | `CACHE_MAX` |

Layout follows the Chromecast reference: full-bleed photo, a clock in
`sans-serif-black` at 104sp in the bottom-right corner with the weather
directly beneath it, and a photo credit bottom-left. A gradient scrim
(`res/drawable/scrim.xml`, 360dp, `#CC000000` → transparent) sits behind the
text — a flat scrim leaves a visible horizontal edge on bright photos.

### Adjusting it

No new settings screen was needed. TvSettings already ships one at Device
Preferences → Screen saver (`Settings/res/xml/daydream.xml`,
`DaydreamFragment`), with three rows:

| Row | Writes | Options |
| --- | --- | --- |
| Screen saver | `secure screensaver_components` | every installed dream, so AmbientDream appears once it is in the image |
| Start screen saver after | `system screen_off_timeout` | 5 / 15 / 30 / 60 / 120 minutes |
| Start now | — | calls `DreamBackend.startDreaming()` |

15 minutes is the middle option and already the upstream default
(`def_screen_off_timeout`), so the requested behaviour is what a box does out
of the box.

There is deliberately **no** UI for `sleep_timeout`, the hard sleep that the
porg overlay pins to -1. That is the setting that would blank the television,
and nothing in TvSettings can reach it.

Two numbers are easy to confuse. `screen_off_timeout` is how long the box
waits before the dream *starts*; `DWELL_MS` is how long each photograph stays
up *once it is running*. They are unrelated.

### Weather location

`AmbientSettingsActivity` is a leanback `GuidedStepSupportFragment` — the same
full-screen look as the rest of TV settings. One editable row for a place name
and one row to go back to automatic. Typing a place resolves it immediately
rather than leaving it to the next fetch, so a name that geocodes to nothing
says "Could not find that place" then and there instead of quietly blanking the
weather line.

It is declared as the dream's `settingsActivity` and reached from
Settings → Screen saver → **Screen saver options** (see
`patches/TvSettings/0002`).

Two leanback details that are easy to get wrong:

- **`title` and `editTitle` are different fields.** `title` is what the row
  shows; `editTitle` is what the edit box opens with. Setting only `title`
  means the placeholder "Automatic" is already in the box, and typing appends
  to it — the first attempt here stored `AutomaticSeattle`.
- **The typed text comes back in whichever field was non-null.**
  `GuidedActionAdapterGroup.updateTextIntoAction` writes to `editTitle` if
  `getEditTitle() != null`, and only otherwise to `title`. Since `editTitle` is
  always set here, `onGuidedActionEditedAndProceed` must read `getEditTitle()`;
  reading `getTitle()` returns the stale display text.

### NASA images

`NasaFeed` searches `images-api.nasa.gov` for a random one of seven topics and
picks a random hit. Three things about that API that cost time:

- The `href` in a search result is **not an image**. It ends in
  `collection.json`, a manifest of that item's renditions; the image URL has to
  be read out of it.
- **Renditions vary per item.** `~large` is usual but not universal — `PIA24433`
  has none — so `resolveImageUrl()` walks `~large`, `~medium`, `~orig`,
  `~small` in order rather than string-substituting a suffix.
- `collection.json` lists the files as **`http://`**, which Android blocks
  under the default cleartext policy. The scheme is upgraded to `https` before
  the fetch; the same host serves both.

Search results are a mix of photography and scientific documentation plates,
so some frames land better than others. Topic curation is the lever if the
selection wants tightening.

### Weather

Open-Meteo — no API key, no account. `Weather` resolves a location once via
`ipwho.is` (or a geocoding lookup if a place name has been set), caches
lat/lon/city in `SharedPreferences`, then polls
`api.open-meteo.com/v1/forecast` for `temperature_2m` and `weather_code`. The
WMO code is mapped to a short label by `describe()`.

Both services are anonymous GETs over HTTPS. Neither is configured with an
account, and the only thing leaving the box is an approximate location.
