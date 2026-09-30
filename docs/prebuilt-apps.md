# Third-party apps in the image

Apps we do not build, shipped in every image as system apps under
`/product/app`. As of 30 September 2026 that is **Moonlight 12.2**.

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

### Verified on porg (30 September)

After a full build and sideload, with no other copy installed:

- Moonlight runs from `/system/product/app/Moonlight` with `pkgFlags` `SYSTEM`
  and ABI `arm64-v8a`, its library in `lib/arm64`, and its signature verified
  as v2: the APK was not touched.
- It paired with a Sunshine host and streamed 1080p60 HEVC: median 92 ms
  end to end (the range from `docs/game-streaming.md`), with no
  `UnsatisfiedLinkError` or `dlopen` failure in logcat.
- The image grew by 14 MB (817 to 831 MB). `/system` had about 740 MB free
  before this.

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
corresponding source offered alongside. For Moonlight (GPL-3.0) that is
moonlight-android at `v12.2`, commit `b48494cb`, **with its submodules**
(moonlight-common-c and its dependencies). Pointing at upstream is the
common practice, but the robust way is our own mirror (a fork at that tag),
so the source stays available as long as we distribute the binary. That is
on the RAIL list. Apps' trademark rules also apply: ship them unmodified.
