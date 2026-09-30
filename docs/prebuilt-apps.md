# Third-party apps in the image

Apps we do not build, shipped in every image as system apps under
`/product/app`. As of 30 September 2026:

| App | Version | Licence | APK | On `/system` | Native libraries |
| --- | --- | --- | --- | --- | --- |
| Moonlight (`com.limelight`) | 12.2 | GPL-3.0 | 11 MB | 16 MB | 1 |
| Kodi (`org.xbmc.kodi`) | 21.2 "Omega" | GPL-2.0-or-later | 65 MiB | 146 MB | 46 (`libkodi.so` alone is 79 MB) |
| Lemuroid (`com.swordfish.lemuroid`) | 1.17.0 | GPL-3.0 | 11 MB | 13 MB | 2 |
| Jellyfin for Android TV (`org.jellyfin.androidtv`, module `JellyfinTV`) | 0.19.10 | GPL-2.0 | 21 MB | 23 MB | 2 |

With all four, the image is 978 MB (817 MB without them), and `/system` has
about 490 MB free. Lemuroid downloads its emulator cores (libretro) when a
game first needs one, so its size here does not grow with the systems it
supports. Jellyfin is for users who run a Jellyfin server. Kodi's digest is the one its mirror network publishes
(`<apk>.sha256` on mirrors.kodi.tv); Moonlight's is GitHub's per-asset
digest.

## How it works

`scripts/in-container/PrebuiltApps/apps.json` pins each app: the APK's URL
and SHA-256, its licence file's URL and SHA-256, and where its source is.
`scripts/in-container/prebuilt_apps.py` runs on every build (called by
`tree-local-changes.sh`). For each app it:

1. fetches the APK and licence into `/dlcache/prebuilt-apps/` (a cache hit
   needs no network), and refuses anything that does not match the pin;
2. stages them in `vendor/jetson-tv/PrebuiltApps/<Name>/`, and extracts the
   APK's `lib/arm64-v8a/*.so` beside it;
3. generates `Android.bp` (the app and its licence), `Android.mk` (one module
   per JNI library) and `prebuilt-apps.mk` (the `PRODUCT_PACKAGES`), which
   `jetson-tv.mk` includes. It uses a plain `include`, so a build that
   skipped this step fails instead of shipping without the apps.

The binaries never enter this repo. They live in the download cache and the
build tree, like the NVIDIA blobs.

### Why three kinds of module

- **The APK goes in byte for byte** (`android_app_import`,
  `preprocessed: true`). For an app targeting SDK 30 or later, any repacking
  breaks its v2/v3 signature. Keeping the authors' signature is also what
  lets their own later releases install over ours. Verified: installing
  official Moonlight over the image's copy gives an `UPDATED_SYSTEM_APP`, and
  `pm uninstall-system-updates` goes back to the image's copy.
- **JNI libraries are installed beside it.** Apps built with
  `extractNativeLibs=true` (Moonlight is one) keep their libraries compressed
  in the APK. The package manager never extracts libraries for an app bundled
  in the image (`PackageAbiHelperImpl.shouldExtractLibs` returns false for
  one), and nothing can load a compressed library straight from the APK. So
  the build extracts them into `/product/app/<Name>/lib/arm64/`, which is
  where the package manager looks for a bundled app's libraries. Soong has no
  module type that installs there, so these are Make `BUILD_PREBUILT`
  modules.
- **`skip_preprocessed_apk_checks: true`** is then needed. Soong's check
  refuses compressed JNI libraries in a preprocessed APK, which is right for
  libraries loaded from the APK but not for ones installed beside it. Make's
  own presigned path is no use here: it either rejects the APK (compressed
  dex or JNI) or repacks it, breaking the signature.
- **`uses_libs` / `optional_uses_libs`** in `apps.json` must match the APK's
  `<uses-library>` tags (`aapt2 dump badging`), or Soong stops the build.
  Moonlight declares one optional library, `com.sec.android.app.multiwindow`.

### Verified on porg (30 September): Moonlight

After a full build and sideload, with no other copy installed:

- Moonlight runs from `/system/product/app/Moonlight` with `pkgFlags` `SYSTEM`
  and ABI `arm64-v8a`, its library in `lib/arm64`, and its signature verified
  as v2: the APK was not touched.
- It paired with a Sunshine host and streamed 1080p60 HEVC: median 92 ms
  end to end (the range from `docs/game-streaming.md`), with no
  `UnsatisfiedLinkError` or `dlopen` failure in logcat.
- The image grew by 14 MB (817 to 831 MB). `/system` had about 740 MB free
  before this.

### Verified on porg (30 September): Kodi

After a full build and sideload: Kodi runs from
`/system/product/app/Kodi` (`SYSTEM`, `arm64-v8a`, signature v3, so the APK
is untouched). It opens to its home screen on first run. H.264 test clips at
1080p60 and 3840x2160p30 play on the hardware decoder: the player overlay
shows `amc-h264(S) (HW)` with pixel format `Surface`, and the log shows
`Using codec: OMX.Nvidia.h264.decode`. Audio (AAC, stereo) reached HDMI
without gaps. HEVC was not tried: there is no x265 on thebe to make a clip.

Two things about Kodi to know:

- **Kodi has its own AirPlay server** (`libshairplay.so`, Settings > Services
  > AirPlay). It is off by default. Turned on, it would advertise a second
  AirPlay receiver next to ours, so leave it off. It speaks the older AirPlay
  1 (audio, and photo/video casting), not screen mirroring.
- **Kodi writes a lot of small files once it has a library:** thumbnails,
  and the SQLite databases for its library and textures. Measure it with
  `scripts/emmc-*` after pointing it at a real library (RAIL).

### Verified on porg (30 September): Lemuroid and Jellyfin

Both run from `/system/product/app` as `SYSTEM`, `arm64-v8a`, signature v2.
Jellyfin opens to its connect screen ("No servers found on local network",
as expected). Lemuroid opens to its TV home, and **choosing a games folder
works**, but only because of two fixes this needed:

- **DocumentsUI is now in the image** (`jetson-tv.mk`). Lemuroid asks for a
  folder with `OPEN_DOCUMENT_TREE`. TV builds leave DocumentsUI out and
  install `TvFrameworkPackageStubs`, whose `DocumentsStub` claims that intent
  (priority 99) and does nothing, so no folder ever came back. DocumentsUI
  claims the same intents at priority 100 and wins. Verified: Directory ->
  the picker -> `ROMs` -> Use this folder -> Allow, and Lemuroid saved
  `content://com.android.externalstorage.documents/tree/primary%3AROMs`. It
  also puts a "Files" tile on the home screen, a basic file browser.
- **The launcher showed Lemuroid twice**, because it has separate TV and
  phone activities. Catapult/0009 skips an app's phone launcher when the
  package has a TV one.

Games themselves are untested: no ROM was loaded.

## Adding or updating an app

Add an entry to `apps.json`, or change the version, URL and both digests. Get
the digests from the release (GitHub publishes a `digest` per asset) or from
a copy you have checked. Run a build: the new APK is fetched and checked, and
a mismatch stops the build with both digests printed. Then:

- `aapt2 dump badging <apk> | grep uses-library`, and copy what it lists into
  `uses_libs` / `optional_uses_libs`.
- Check the app on the device: `pm path`, `dumpsys package` (flags and
  ABI), and actually use it, since a missing library only shows at runtime.

## Licences, and what publishing an image requires

Each app's licence text is fetched with it and attached to its modules, so
it appears in the image's licence notices.

**Before an image is published**, every GPL app in it needs its
corresponding source offered alongside:

- **Moonlight (GPL-3.0):** moonlight-android at `v12.2`, commit `b48494cb`,
  **with its submodules** (moonlight-common-c and its dependencies).
- **Lemuroid (GPL-3.0):** Lemuroid at `1.17.0`, commit `4266d373`. The
  libretro cores it downloads are distributed by their own projects, not by
  us.
- **Jellyfin for Android TV (GPL-2.0):** jellyfin-androidtv at `v0.19.10`,
  commit `984181a3`.
- **Kodi (GPL-2.0-or-later):** xbmc at `21.2-Omega`, commit `d1a1d48c`,
  **plus the third-party source tarballs** its Android build pulls in through
  `tools/depends` (ffmpeg, Python and the rest). The APK carries their
  binaries, so their source is part of the obligation.

Pointing at upstream is the common practice, but the robust way is our own
mirror (a fork at the tag, and the depends tarballs), so the source stays
available for as long as we distribute the binaries. That is RAIL 18b.

Apps' trademark rules also apply. Ship them unmodified, and for Kodi
especially, with no third-party add-ons preinstalled: the Kodi Foundation's
trademark policy is aimed at boxes sold "fully loaded".
