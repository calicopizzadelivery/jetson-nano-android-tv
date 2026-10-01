# AirPlay receiver

The box shows up on iPhones, iPads and Macs under its device name, **JetsonTV**
unless renamed: Screen Mirroring
from Control Center, and AirPlay audio from Music or anything else with an
AirPlay button. The protocol is UxPlay's, hosted inside the `AirPlayReceiver`
app. It replaced the Shairport Sync daemon on 30 September 2026. Shairport's
history, including why it was silent and then why it dropped out, is in
[streaming-targets.md](streaming-targets.md).

This is a reverse-engineered implementation. Fine for a box you own; it cannot
be described as AirPlay-compatible.

## Why UxPlay

The goal is native screencasting from the devices people already carry.
Shairport is audio only. FCast needs its own sender apps, with no iOS, which
means a second ecosystem. Google Cast is impossible without a per-device
certificate. UxPlay covers mirroring and audio from any Apple device, with
nothing to install on the sender. Windows and Samsung phones are for Miracast,
once a Wi-Fi radio is fitted.

What was given up: **classic AirPlay 1 senders.** UxPlay implements the
protocol current Apple devices speak and answers the old RAOP `ANNOUNCE` with
`501 Not Implemented`. That rules out `raopsend`, pyatv's `stream_file` and
other open-source senders, but no Apple device.

## Licensing

- UxPlay is **GPLv3** (parts of `lib/` are LGPL-2.1+, playfair is GPL, llhttp
  is MIT). libplist is **LGPL-2.1+**. The ALAC decoder is **MIT** (David
  Hammerton, via shairport-sync).
- `AirPlayReceiver` loads `libuxplay_jni` into its own process, so the app as a
  whole is GPLv3. Its UxPlay-facing sources say `GPL-3.0-or-later`;
  `BootReceiver` is Apache-2.0, which is compatible in that direction.
- UxPlay links Android's **BoringSSL** as the system crypto library,
  dynamically (`libcrypto.so`). That is the GPLv3 System Library reading, and
  it was decided explicitly on 30 September. Keep the linkage dynamic, since
  static linking weakens the argument.
- **Distributing an image** means publishing the corresponding source for the
  GPL parts: the kernel, UxPlay and the libplist fork. It also means GPLv3's
  installation information, which the plan already covers: images are meant to
  be re-flashable and modifiable by their owners, and nothing is locked.

## How it fits together

```
iPhone / Mac ── RTSP, pairing, FairPlay, NTP ──▶ UxPlay lib/         (libuxplay_airplay)
                                                   │ raop_callbacks_t
                                                   ▼
                                     android/uxplay_jni.c                (libuxplay_jni)
                                       time → CLOCK_MONOTONIC, ALAC → PCM
                                                   │ JNI
                                                   ▼
                     UxPlay.java ── Listener ──▶ AirPlayService
                                                   ├─ VideoRenderer  MediaCodec → MirrorActivity's Surface
                                                   ├─ AudioRenderer  AAC via MediaCodec, AudioTrack, timed
                                                   └─ MediaSession   → AmbientDream's now-playing panel

mDNS: lib/dnssd.c → dns_sd shim → UxPlay.nsdRegister → NsdManager
```

| Where | What |
| --- | --- |
| `external/uxplay` `Android.bp` | `libuxplay_airplay` (lib/, llhttp, playfair, the dns_sd shim) and `libuxplay_jni` |
| `external/uxplay` `android/uxplay_jni.c` | The callbacks turned into Java calls. Presentation times cross already shifted from lib/'s `CLOCK_REALTIME` to `CLOCK_MONOTONIC`, the base of `System.nanoTime()` and MediaCodec's timed release |
| `external/uxplay` `android/dns_sd_android.c` | The dns_sd subset `lib/dnssd.c` uses. UxPlay builds its own TXT records; registering them is handed to Java |
| `external/uxplay` `android/alac/` | ALAC decoder, because Android has none. Hardened, see below |
| `external/libplist` | `libplist-2.0`, static, C only |
| `AirPlayReceiver/…/UxPlay.java` | Loads the library; turns registrations into `NsdServiceInfo`s; counts connections |
| `AirPlayReceiver/…/AirPlayService.java` | Foreground service: the server's lifecycle, audio focus, MediaSession, what goes on screen |
| `AirPlayReceiver/…/VideoRenderer.java` | Async MediaCodec. Queues frames from the last parameter sets until a Surface exists, then releases each at its presentation time |
| `AirPlayReceiver/…/AudioRenderer.java` | AudioTrack plus a writer thread that pads with silence when early and drops when late, measured against `AudioTrack.getTimestamp()` |
| `AirPlayReceiver/…/MirrorActivity.java` | Black full-screen Surface, letterboxed to the sender's aspect ratio |
| `AirPlayReceiver/…/SelfTest.java` | A session with no sender, for the bench. See below |
| `AirPlayReceiver/tests/eldgen` | Host tool: the AAC-ELD stream the self-test needs |

### Behaviour

- **Name and identity.** Advertised under the device name (Settings >
  Device Preferences > About > Device name, "JetsonTV" on a fresh install),
  or `persist.jetsontv.airplay.name` if that is set, on `_airplay._tcp` and
  `_raop._tcp`. Either is read when the receiver starts, so a rename shows
  after the Streaming tile is turned off and on, or a reboot. The device id is
  a random locally administered MAC, kept in the app's preferences. The
  pairing key is in `files/uxplay.pem`. Both persist, so a phone that has seen
  the box before still recognises it.
- **Mirroring takes the screen.** When a mirroring stream starts, the service
  wakes the screensaver if it is running and starts `MirrorActivity` from the
  background. **Back** on the remote ends the session, and the phone shows
  mirroring as stopped.
- **Audio only** plays with the now-playing panel when it starts on the home
  screen, as it did with Shairport. Title, artist, album, cover art and
  progress go to the MediaSession.
- **Losing audio focus** for good, for example a film started in Kodi, ends the
  session. The receiver restarts and **keeps advertising**.
- **Volume.** The sender's volume applies to this stream only; the
  television's volume stays the master.
- **The Streaming tile** starts and stops the whole service.
- Advertised display: 1920x1080, 60 Hz, up to 30 fps, **H.264 only**. H.265
  is not offered. Nor is URL video casting (UxPlay's `-hls`): senders are
  offered mirroring and audio.
- **A PIN, once per device** (on by default). The first time a phone or Mac
  connects, the TV shows a four-digit code to type on it. After that the device
  is remembered. See "Pairing with a PIN" below.

## Pairing with a PIN

New devices must enter a code shown on the TV. It works the way an Apple TV
set to require a passcode does: a new random code for each attempt, entered
once per device. After that, the device is known by the Ed25519 key it proved
the code with.

**On the TV:** the Streaming tile in the system options panel (Menu on the
remote). Its dialog has the receiver's switch, **Ask new devices for a
code**, and **Paired devices (N)**, which lists them by name and forgets one
or all. Turning the code off asks first, since anyone on the network could
then stream. A change restarts a running receiver, so it applies at once,
ending any stream in progress (the restart takes about 0.25 s). With the
receiver off, the setting is saved and used when it next starts.

The tile does not keep any of this itself. The AirPlay app owns the state and
exposes it through `AirPlaySettingsProvider` (`call()` only, behind
`WRITE_SECURE_SETTINGS`, the same permission as the service), so the tile,
adb and anything added later all see the same thing:

| To | adb |
| --- | --- |
| See the state | `content call --uri content://org.lineageos.tv.airplay.settings --method get` |
| Turn the code off (anyone on the network may stream) | `... --method set_require_pin --extra value:b:false` |
| Turn it back on | `... --method set_require_pin --extra value:b:true` |
| Forget one device | `... --method forget --arg <its key, from get>` |
| Forget every device | `... --method forget` |

The setting itself is `persist.jetsontv.airplay.pin` (`on`/`off`). Setting it
with `setprop` still works, but only takes effect when the receiver restarts.
The log says which mode is in force whenever the receiver starts: `PIN
required for new devices, N paired` or `no PIN`. Paired devices are in the
app's `airplay_clients` shared preferences.

**Enforced, not advisory.** UxPlay's own PIN mode only asked clients to pair.
A client could skip pairing and go straight to streaming, and asking for
pairing without ever requesting a code got it the code "0000". Our fork
closes both gaps. In PIN mode, a connection gets no FairPlay setup and no
`SETUP` until it has completed pair-verify. It must first either prove the
code on that connection or be found among the paired devices. A code only
exists from the moment it is shown until its first use, for at most two
minutes. Pairing without a code is refused. The details are in the fork
commit `Pin mode: make pairing required, not just offered`.

The code screen (`PinActivity`) comes up over whatever is showing, the
screensaver included. It goes away when the device pairs, when it gives up and
disconnects, or after the same two minutes. Back hides it.

**Verified here:** the receiver starts in either mode, and the advertisement
switches between `pw=true` and `pw=false`. The code screen appears over the
launcher in under half a second (`pin` self-test mode) and dismisses when the
device counts as paired. Forgetting devices works. The tile (1 October),
driven with the remote's keys against two seeded devices: the code switch
off with its confirmation, backing out of it, back on, the list, forgetting
one, forgetting all, and the empty list. Each step was checked against the
provider and the log. Not verified: that an app without
`WRITE_SECURE_SETTINGS` is refused. The device has no way to run a command
as another app's uid, so that rests on the manifest and the check in
`call()`.

**Not verified:** the pairing itself with a real client. The enforcement
code is compiled and running, but no client has yet paired with it. This is
part of the hardware checklist. One thing to watch: in PIN mode the `_raop`
record's `sf` flags read `0x4`, not the PIN value `0x8c`, because upstream
sets `sf` twice and the second wins. That is exactly what upstream UxPlay
advertises with `-pin`, the configuration its users run with iPhones, so it
is left alone. If an iPhone connects without being asked for the code, look
here first.

## Security: the ALAC decoder

Pairing needs no PIN, so every byte of an audio packet comes from whoever is on
the network. The receiving app is privileged. Hammerton's decoder trusted the
stream in four places, all fixed in the fork (`android: ALAC decoder, bounded
against crafted frames`):

1. A frame's own sample count was checked against the caller's 16 KB output
   buffer, while the work buffers hold 352 samples. It is now bounded by the
   frame length the stream was set up with.
2. A run of zeros in the Rice decoder was memset for up to 64 K samples,
   regardless of frame size. It is now clamped, as ffmpeg's decoder does.
3. An "uncompressed bytes" field wider than the sample walked the bit reader
   backwards. Such frames are refused.
4. The bit reader has no end. The bridge now copies each frame into a
   zero-padded buffer sized so that no frame can read out of it.

Two handshake bugs came up in the same review, fixed in `Bound the fp-setup
mode, and the SETUP ekey and eiv sizes`. The `fp-setup` mode byte indexed a
four-entry table unchecked, which would echo back up to 36 KB of memory.
`SETUP` copied fixed lengths out of a client-sized `ekey` and `eiv`.

Still open, noted while reading the stream code, for a later hardening pass.
The mirroring codec packet (SPS/PPS, and the H.265 VPS/SPS/PPS) takes its
parameter-set lengths from the stream without checking them against the
packet. A request carrying `X-Apple-Session-ID` on an RTSP connection reaches
an `assert` on a null string. Both need a paired client, but pairing needs no
PIN.

`android/tests/alac_check.c` in the fork decodes a verbatim stereo frame
(bit-exact) and then fuzzes the decoder under AddressSanitizer. The unhardened
decoder hits a heap-buffer-overflow within seconds; the hardened one ran
250,000 frames clean.

## The self-test

Mirroring can only be driven by an Apple device; no open-source sender does
FairPlay. So `SelfTest` covers everything after the protocol, calling the same
`UxPlay.Listener` methods UxPlay does. Debuggable builds only:

```
adb shell am start-foreground-service -a org.lineageos.tv.airplay.SELFTEST \
    -n org.lineageos.tv.airplay/.AirPlayService --ei seconds 15 --es audio <mode>
```

| mode | picture | sound |
| --- | --- | --- |
| `pcm` (default) | a moving test card, encoded by the Tegra's own H.264 encoder | 440 Hz as PCM, the ALAC route |
| `eld` | the same | 440 Hz as AAC-ELD 480, the mirroring route |
| `music` | none: the now-playing panel | 440 Hz as PCM, with a title, cover art and progress; the RTP clock wraps past 2³² three seconds in |
| `pin` | the pairing code screen with a sample code, then paired after `seconds` | none |

`eld` needs `files/selftest-eld.bin`. **Android's own AAC encoder frames ELD
at 512 samples** (config `f8e84000`), while senders use 480 (`f8e85000`). The
receiver decodes with a fixed config, as UxPlay does, because senders never
send one. So the stream is made on the host with the tree's FDK library:

```
m airplay_eldgen && out/host/linux-x86/bin/airplay_eldgen 20 eld.bin
# prints: config f8e85000, 480 samples a frame
adb push eld.bin /data/user/0/org.lineageos.tv.airplay/files/selftest-eld.bin
# then chown it to the app's uid, and restorecon
```

The renderers log their timing, and these logs are what to read after a real
session too:

```
AirPlay : video: 450 frames shown, mean +0.0 ms from due, worst +8.0 ms, 0 over 50 ms
AirPlay : audio: 1880 chunks played, mean -7.1 ms from due, 0 dropped late, 185 ms of silence padded
```

"From due" is when a frame actually reached the screen (MediaCodec's
`OnFrameRenderedListener`) or was written for playback, against the sender's
presentation time. Positive means behind. The video line comes every 900 frames
and when the decoder stops; the audio line comes when the stream ends.

## Verified on porg, 30 September 2026

- The server starts at boot, advertises both services (seen from the host with
  zeroconf, TXT `ft=0x5A7FFEE6`, `model=AppleTV3,2`), and answers `GET /info`
  with a binary plist. pyatv sees an AirPlay/RAOP device with pairing
  "NotNeeded".
- Self-test, each run captured off HDMI with the MS2109 and checked for 440 Hz
  in 100 ms windows:

  | mode | tone on HDMI | video | audio |
  | --- | --- | --- | --- |
  | `pcm` | 15 s, no gaps, amplitude 17,783 of 18,000 | 450/450, mean 0.0 ms, worst +8 ms | mean −7.1 ms, none dropped |
  | `eld` | 14.9 s, no gaps | 449/449, mean 0.0 ms | mean +7.9 ms, none dropped |
  | `music` | 18 s, no gaps | — | mean +0.3 ms, none dropped |

  The mirroring screen came up over the launcher 0.5 s after the first frame
  (`BAL_ALLOW_PERMISSION`, so the allowlist is doing its job). It used
  `OMX.Nvidia.h264.decode` and dismissed itself at the end. In `music` the
  panel appeared on its own with title, artist, cover art, the "AirPlay ·
  Self-test" source line and a moving progress bar. The MediaSession read
  PLAYING at 8000 ms, eight seconds in, across the 2³² wrap.
- Ending the session from the television, twice in a row: the same process,
  re-advertised within a second on a new port, and `GET /info` answered each
  time.

What only an Apple device can show: pairing, FairPlay, decryption, the NTP
clock against a real sender, compressed ALAC (the self-test's PCM goes around
the decoder, and the host check only covers verbatim frames), AAC-LC, and
behaviour on real Wi-Fi.

## Testing with an iPhone or Mac

Keep a log open first:

```
adb logcat -s AirPlay UxPlay
```

1. **Pairing, first.** With the PIN on (the default), the first attempt from
   each device should put a four-digit code on the TV and ask for it on the
   device. Check each of these:
   - A wrong code is refused.
   - The right one connects. The log says `remembering <device id>`, and the
     code screen goes away.
   - A second connection from the same device asks for nothing (`a paired
     device is back`).
   - After `FORGET_DEVICES`, the device asks again.
   - With `persist.jetsontv.airplay.pin off` and the receiver restarted, no
     code is asked for at all.
2. **Mirroring.** On the iPhone, open Control Center → Screen Mirroring →
   JetsonTV. Expect `connection from "<phone>"`, then `video decoder
   OMX.Nvidia.h264.decode`, then `mirrored picture is WxH`. Once the phone
   plays sound you should also see `AAC decoder … for AAC-ELD`. Then:
   - Rotate the phone and check the letterboxing follows.
   - Play a video with speech to check lip sync.
   - Stop from the phone, and separately with Back on the remote. Each should
     return to where you were, and the phone should show mirroring stopped.
3. **Audio.** In Music, tap AirPlay → JetsonTV. The now-playing panel should
   come up from the home screen with the track. Try the phone's volume slider,
   pause and skip. The log names the codec: ALAC arrives as PCM, and AAC-LC
   shows `AAC decoder … for AAC-LC`.
4. **Mac.** Control Center → Screen Mirroring → JetsonTV (mirror or extend),
   and Sound → output → JetsonTV.
5. **If something fails**, save `adb logcat -d` and look at the `UxPlay` lines
   around the failure. Pairing and FairPlay problems show there, before
   anything reaches the renderers.

## Deploying without a full flash

A userdebug build lets `/system` be remounted, so an app change is a push and a
reboot rather than a sideload:

```
adb root && adb remount     # "Error setting verity state" is printed, but / is remounted rw
adb push out/.../system/system_ext/priv-app/AirPlayReceiver/. /system_ext/priv-app/AirPlayReceiver/
adb push out/.../system/lib64/libuxplay_jni.so /system/lib64/
adb shell restorecon -R /system_ext/priv-app/AirPlayReceiver /system/lib64/libuxplay_jni.so
adb reboot
```

**The JNI library is not in the app directory.** `lib/arm64/libuxplay_jni.so`
there is a symlink into `/system/lib64`, so pushing the app directory alone
runs the new Java against the old native code. That cost a round. Push the
odex and vdex with the APK (they are in the same `out` directory), or ART
rejects the stale ones and runs the app interpreted.

## Traps

- **BoringSSL declares `BIO_f_base64` but does not implement it.** It fails at
  link time, not compile time. `pk_to_base64` uses `EVP_EncodeBlock` instead.
- **R8 strips the methods only JNI calls.** The symptom is `NoSuchMethodError`
  for `nsdRegister` as the server starts. `proguard.flags` keeps them.
- **libplist's `.gitignore` matches `config.*`**, which includes
  `android/config.h`. It is force-added.
- **playfair trips `-Wsometimes-uninitialized`** on branch chains the compiler
  cannot prove exhaustive. The warning is silenced; the code is not wrong.
- **Android's mDNS ignores unicast queries.** pyatv's `--scan-hosts` finds
  nothing, but a plain multicast scan finds the box.
- **The HDMI capture must be held open** across anything that should appear
  on screen (see `jetson-flash-node/tools/hdmi.py`), and audio is checked by
  frequency, not byte counts.

## Still to do

1. **Validate with real Apple hardware — the open item for this whole
   receiver.** Everything so far runs without a sender: the self-test drives
   the renderers, and the protocol side is known only to start, advertise and
   answer `GET /info`. PIN pairing, FairPlay, decryption, the clock sync,
   and the lip sync between an Apple sender's audio and video timestamps have
   never met a real device. When an iPhone, iPad or Mac is available (a Mac is not
   required: an iPhone alone can mirror from Control Center), run the
   checklist in "Testing with an iPhone or Mac" above. Record the `video:`
   and `audio:` timing lines, and whether speech is in sync. Until then, treat
   the AirPlay feature as unproven.
2. **A way to manage paired devices from the TV**, not just adb: forget all,
   and the PIN on or off. The Streaming tile is the natural home.
3. **Clean up after Shairport** in the porg fork: the `shairport` SELinux
   domain, the `system_ext_airplay` uid (7500) in `config.fs` and the
   `jetsontv.airplay.interrupt` label. None of it is used any more. Keep the
   `jetsontv.airplay.` property label: the Streaming tile uses `enabled`.
4. **Miracast**, once a radio is fitted, for Windows and Android senders.
