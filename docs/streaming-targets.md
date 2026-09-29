# Streaming targets

Making the box something other devices can point at. Audio first (AirPlay),
then video (FCast), both bundled into the image.

## What is and is not possible

The dividing line is whether a protocol needs a per-device certificate from a
licensor.

| Protocol | Verdict |
| --- | --- |
| **AirPlay 1 audio (RAOP)** | Possible. Reverse-engineered, well-established, shairport-sync. **In progress.** |
| **FCast** | Possible. Open protocol, open receiver. Next. |
| **Bluetooth A2DP sink** | Possible and cheap — Android implements the whole profile, gated on one sysprop. Not started. |
| **UPnP/DLNA renderer** | Possible. Kodi already does it; no platform work. |
| AirPlay 2 | Possible but a different size of project — nqptp, libplist, ffmpeg. |
| AirPlay mirroring (video) | Possible. UxPlay/RPiPlay solved the FairPlay handshake; the work is replacing GStreamer with MediaCodec. Months. |
| **Google Cast** | **Dead.** The cast certificate is provisioned per device by a licensed OEM. |
| Miracast sink | Not worth it. Never in AOSP (Android is a source only), needs Wi-Fi P2P autonomous GO, and our wifi is the least-proven part of the stack. |

AirPlay here is a reverse-engineered implementation. Fine for a box you own;
it cannot be described as AirPlay-compatible.

## Shairport Sync

`external/shairport-sync` and `external/popt`, both forks on
`android-jetson-tv`, synced by the local manifest like everything else. Scope
is AirPlay 1 audio.

**Status**: builds and runs on the emulator. `shairport-sync -V` reports
`4.3.7-android-OpenSSL-tinysvcmdns-dummy-stdout-pipe`. There is no audio back
end Android can play through yet.

### The four things Android forces

- **Crypto is BoringSSL**, via AOSP's `libcrypto`. `external/mbedtls` is
  vendored in AOSP but has **no Soong build at all**, so shairport's other
  supported backend is not available. BoringSSL has everything used here
  except `BIO_f_base64`, which it *declares and does not implement* — so it
  compiles and then fails at link. `base64_enc`/`base64_dec` are rewritten on
  `EVP_EncodeBlock`/`EVP_DecodeBase64`. Apple omits the padding from its
  challenges and `EVP_DecodeBase64` rejects that where the BIO decoder did
  not, so the restore-the-padding step still has to happen by hand.

- **mDNS is the bundled tinysvcmdns.** Android has neither Avahi nor D-Bus.
  This is the option that makes the port tractable at all.

- **ALAC is the bundled Hammerton decoder.** AOSP defines the `audio/alac`
  MIME type but ships no decoder, so there is nothing to borrow.

- **bionic has no thread cancellation**, and this code uses it at ~100 call
  sites: 19 `pthread_cancel`, 77 `pthread_setcancelstate`, 6
  `pthread_testcancel`. It *does* implement `pthread_cleanup_push`/`pop`
  (they run on `pthread_exit`), so the 60 cleanup sites work untouched and
  only three functions needed shimming —
  `android/pthread_cancel_shim.{c,h}`, force-included, upstream sources
  unmodified.

  **The shim tracks cancel state rather than stubbing it.** Shairport brackets
  its critical sections in `pthread_setcancelstate(PTHREAD_CANCEL_DISABLE)`
  precisely so a cancel cannot land while a mutex is held. A no-op stub
  compiles and then leaves locks held by dead threads under load. A cancel
  that arrives while disabled is recorded and delivered at the next enable or
  testcancel.

### libconfig

Bundled under `android/libconfig`. AOSP's copy is `cc_library_host_static` —
host only, with visibility restricted to `//external/wmediumd` — so there is no
device variant to link. It is not optional either: 24 of its functions are used
across 202 unguarded call sites. Bundling it inside the shairport fork keeps
the change in the one repository that already exists for the Android port,
rather than forking an AOSP project or duplicating source into the product tree.

### Still to do

1. **An AAudio back end** (`audio_aaudio.c`). Deliberately not ALSA or
   tinyalsa: going straight to the hardware bypasses AudioFlinger, which would
   fight the audio policy and silently break the output picker in the panel.
   Through AAudio the box can be an AirPlay target *and* send the result to a
   Bluetooth speaker.
2. **An Android service** to start and stop the daemon and hold audio focus,
   so AirPlay and Kodi do not talk over each other.
3. **SELinux policy** for a daemon that binds sockets and plays audio.
4. **A Streaming tile** in the panel — what is advertised, and an off switch.
