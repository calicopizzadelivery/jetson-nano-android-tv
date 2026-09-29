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

### Audio output

`audio_aaudio.c`, in our fork. Deliberately not ALSA or tinyalsa: going
straight to the hardware bypasses AudioFlinger, which means no system volume,
no routing, and no cooperation with anything else playing. As an ordinary
`AAUDIO_USAGE_MEDIA` stream it follows whatever output the user picked in the
panel — including a Bluetooth speaker — and mixes like any other app. Volume
is left to Shairport's software volume; the hardware volume belongs to
Android's media stream.

### Running it

A system_ext init service (`android/shairport-sync.rc`), started when
`persist.jetsontv.airplay.enabled` goes true. Init does the starting, so the
controlling app only sets a property rather than exec'ing a system binary.

SELinux policy lives in `device/nvidia/porg/sepolicy/private` — porg is ours,
`device/nvidia/sepolicy` is LineageOS's. The domain **must** carry
`coredomain`: it is on system_ext, and without that attribute it is classed as
a vendor component, at which point executing its own binary trips three
neverallows in `domain.te` about vendor components touching `/system`.

### End to end

Proven on `lineage_sdk_tv_x86_64`: a 440 Hz tone sent over AirPlay arrives at a
Bluetooth speaker.

    sender (raopsend) -> RTSP/RTP -> shairport-sync -> AAudio
      -> AudioFlinger -> A2DP -> Bluetooth speaker

The receiver logs `aaudio: started 2 channels at 44100 fps, device 26`, the
audio route is `bt_a2dp`, and the speaker's received-byte counter moves by
121,528 bytes in 148 RTP packets across a 10-second stream — against a
measured idle baseline where it does not move at all. The daemon survives the
session and does not crash.

That is the whole chain, including the output picker: AirPlay audio follows
whatever output was chosen in the panel, which is exactly what the AAudio
back end was for.

### Testing it

`scripts/in-container/raopsend` is a small classic-RAOP sender, built with
`m raopsend` and pushed to the device. It has to run **on** the device: RAOP
carries audio over UDP, `adb forward` is TCP only, and the emulator console's
`redir` targets eth0 while the emulator's IPv4 address lands on wlan0.

Two things make it short. Shairport accepts uncompressed `L16/44100/2`, so
there is no ALAC encoder; and a stream carrying neither `a=aesiv` nor
`a=rsaaeskey` is treated as unencrypted, so there is no RSA key exchange.

**The SDP must not carry an `a=fmtp:` line.** Shairport's ANNOUNCE handler
does `if (pfmtp) { conn->stream.type = ast_apple_lossless; }` — the mere
presence of fmtp selects ALAC whatever the rtpmap says. The ALAC decoder then
chokes on PCM with *"unhandled prediction type for compressed case: 3"* and
segfaults, taking the receiver with it. The uncompressed branch sets frames
per packet, rate, channels and depth itself, so fmtp has nothing to add.

Before any of this works the emulator needs IPv4, which it does not have until
wifi is associated:

    adb shell cmd wifi connect-network AndroidWifi open

Without it there is no IPv4 at all and tinysvcmdns cannot join the multicast
group — the `IP_ADD_MEMBERSHIP: No such device` failure.

`pyatv` cannot be the sender: it probes `GET /info` first, which is AirPlay 2,
and a Classic receiver never answers it.

### Earlier findings

Proven on `lineage_sdk_tv_x86_64`: the daemon starts in Classic AirPlay mode
with `audio backend is "aaudio"`, registers with tinysvcmdns and holds 5353,
listens for RTSP on 5000 over IPv4 and IPv6, and **answers RTSP correctly** —
`Server: AirTunes/105.1` with the full method list. The SELinux policy
compiles for porg.

Not proven: **audio actually reaching the speakers.** That needs a sender, and
the emulator's network topology blocks one from the host:

- The emulator has **no IPv4 until wifi is associated**. `cmd wifi
  connect-network AndroidWifi open` brings up wlan0 on 10.0.2.16. Without it
  there is no IPv4 at all and tinysvcmdns cannot join the multicast group —
  which is what the "no such device" failure from `IP_ADD_MEMBERSHIP` means.
- The emulator console's `redir` forwards to **eth0** (10.0.2.15), but the
  address is on **wlan0** (10.0.2.16) — a separate virtio-wifi network. So
  host→guest redirection does not reach the daemon.
- `adb forward` does reach it, because it goes through adbd *inside* the guest,
  and RTSP works that way. But adb forwards TCP only, and RAOP carries audio
  over UDP.

So the sender has to run **on the device**. `pyatv` cannot be that sender in
any case: it probes `GET /info` first, which is AirPlay 2, and a Classic
receiver never answers it.

The good news for writing one: Shairport accepts **uncompressed `L16/44100/2`**
and treats a stream with neither `a=aesiv` nor `a=rsaaeskey` as unencrypted
(`rtsp.c`), so a test sender needs no ALAC encoder and no RSA key exchange.
`scripts/raop-send.py` is that sender, written against those two facts; it
works as far as the network lets it and wants rebuilding as a small on-device
binary.

### Still to do

1. **An on-device RAOP sender** to close the end-to-end test.
2. **An Android service** to hold audio focus, so AirPlay and Kodi do not talk
   over each other, and to set the property.
3. **A Streaming tile** in the panel — what is advertised, and an off switch.
4. **FCast**, for video.
