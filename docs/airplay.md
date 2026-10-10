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
  System > About > Device name, "JetsonTV" on a fresh install), or
  `persist.jetsontv.airplay.name` if that is set, on `_airplay._tcp` and
  `_raop._tcp`. Renaming the box restarts the receiver under the new name
  within about 0.1 s; the service watches the device name. The device id is
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

**On the TV**, in two places that always agree:

- **Settings > System > AirPlay** (TvSettings/0003): the receiver's switch;
  the name iPhones and Macs show, which opens the device rename; **New
  devices**, a page choosing between *Ask for a code* and *Don't ask*, with
  the consequence spelled out under each; and the paired devices, each
  opening a page to forget it, with *Forget all* when there are several.
  The page follows changes made anywhere else while it is open.
- **The Streaming tile** in the system options panel (Menu on the remote):
  the receiver's switch, **Ask new devices for a code**, and **Paired
  devices (N)**, which lists them by name and forgets one or all. Turning
  the code off there asks first, since one unticked box is easy to hit.

A change of the code setting restarts a running receiver, so it applies at
once, ending any stream in progress (the restart takes about 0.25 s). With
the receiver off, the setting is saved and used when it next starts.

Neither screen keeps any of this itself. The AirPlay app owns the state and
exposes it through `AirPlaySettingsProvider` (`call()` only, behind
`WRITE_SECURE_SETTINGS`, the same permission as the service). Every change,
including one from a device pairing, is announced with `notifyChange()` on
`content://org.lineageos.tv.airplay.settings`, which is how the Settings page
stays current. adb sees the same thing:

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
provider and the log. The Settings page likewise, every path, including a
rename and a change made from adb while it was open (patches/README.md,
TvSettings/0003). Not verified: that an app without
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

## The test harness

`scripts/airplay/` holds what is needed to get the box ready and to judge a
session honestly. Written 9 October, after a factory reset showed how many
things quietly reset with it.

| | |
| --- | --- |
| `preflight.sh` | is the box ready for an Apple device? Run this first. `--tone` also exercises the renderers and listens to HDMI. |
| `mdns_probe.py` | what a sender actually sees. No avahi-browse or python zeroconf on thebe, so it speaks enough mDNS itself. |
| `tone_check.py` | records the MS2109 and reports 440 Hz per 100 ms window. |
| `tvnav.py` | focuses a named row in a leanback menu and presses it, after checking it landed. |
| `watch.sh` | follows the session log, with a legend of the markers to expect. |

Four things that cost time on 9 October and are worth knowing:

- **A factory reset turns AirPlay off.** `persist.jetsontv.airplay.enabled`
  is a `persist.` property, so it lives in `/data` and goes with a wipe. The
  receiver process still runs; it simply advertises nothing, which looks
  exactly like a broken mDNS stack. `preflight.sh` checks this first.
  `setprop` from `adb shell` is refused, correctly, so it has to be turned on
  from Settings > System > AirPlay (or `tvnav.py go AirPlay`).
- **Discard the first self-test run.** A cold codec costs real time: the
  first `pcm` run after an idle service measured `mean +38.4 ms, 46 dropped
  late`, and the two immediately after it were `mean ±0.0 ms, 0 dropped`.
  Judging the build on a first run would report a regression that is not
  there.
- **A wipe also removes `selftest-eld.bin`** and the uxplay key pair. The
  fresh key is good for pairing tests; the missing fixture just makes the
  `eld` self-test fail until it is regenerated.
- **`adb root` needs re-enabling** after a wipe: Developer options are hidden
  again (seven presses on "Android TV OS build" in About), then "Rooted
  debugging". Without it the app directory cannot be pushed to.

State on 9 October 2026, after the harness was built, on a freshly reset box:

| check | result |
| --- | --- |
| advertising | `JetsonTV._airplay._tcp` and `1AC7894718D4@JetsonTV._raop._tcp`, port 42263 |
| `pcm` self-test | 440 Hz in 130/130 windows on HDMI, no gaps; 0 dropped, mean ±0.0 ms; video 480/480, worst +0.0 ms |
| `eld` self-test | 440 Hz in 120/120 windows; `c2.android.aac.decoder for AAC-ELD`; 0 dropped; video 479/479 |
| `pin` self-test | the code screen renders, four digits, with the "each device asks once" line |
| paired devices | none, and a new key pair, so pairing starts clean |

One difference worth watching: we advertise `flags=0x4` where real Apple
hardware on the same LAN advertises `0x204`. The extra bit is not one of the
well-attested status flags, and our fork enforces the PIN itself rather than
relying on the advertisement, so this may be nothing. If a sender declines to
show a code entry box, look here first.

## Verified with a real sender, 10 October 2026

An **iPhone 13 Pro Max** (`iPhone14,3`, `AirPlay/960.13.1`) paired, played
audio and mirrored. This is the first time the stack met Apple hardware.

| | |
| --- | --- |
| PIN pairing | code on the TV, entered on the phone, all three `pair-setup-pin` steps, `remembering BA:1E:47:E8:00:27` |
| Returning device | `a paired device is back` — no code asked, so the register our fork keeps does persist |
| Audio | now-playing panel with title, artist and a live progress bar; **RMS 0.17 on HDMI, 60/60 windows non-silent** off the MS2109 |
| Mirroring | FairPlay passed, `video decoder OMX.Nvidia.h264.decode`, `mirrored picture is 500x1080` letterboxed into 1920x1080, text legible |
| Mirroring audio | `AAC decoder c2.android.aac.decoder for AAC-ELD` |

**It did not work on the first attempt**, and the reason is worth keeping:
PIN pairing was impossible for *any* client. See "The SRP proof length bug"
below.

Still unproven after this session, in rough order of how much they matter:

- **Cover art never arrives.** Title, artist and progress all render; the
  artwork box stays empty. Metadata works, artwork does not.
- A **wrong code** being refused. We never entered one.
- `FORGET_DEVICES` making a known device ask again.
- `persist.jetsontv.airplay.pin off` skipping the code entirely.
- A **Mac**, in both mirror and extend modes.
- Rotation, lip sync, volume, pause/skip, and Back on the remote ending the
  session.

## The SRP proof length bug

Worth reading before touching pairing, because it broke everything and the
symptom pointed somewhere else entirely.

The phone showed "AirPlay Password" and looped, rejecting every entry. That
looks like a wrong PIN. It was not: the PIN was never checked. UxPlay's
`SRP_SHA` is `SRP_SHA1` (`lib/pairing.h:29`), so the client proof `<M>` is one
20-byte digest, but upstream commit `e0b5309` added

    if (client_proof_len != sizeof(proof))      /* proof[64] */

which demands 64, the SHA-512 size of the local stack buffer. **No client can
satisfy it.** Real iOS sends 20 and is rejected at
`Client Authentication Failure (client_proof_len 20 invalid)` before the PIN
is compared.

Upstream's own fix (`d29dfab`) deletes the check. That restores pairing but
re-arms the line the check protected: `memcpy(proof, client_proof,
sizeof(proof))` copies 64 bytes out of the 20 libplist allocated, a 44-byte
heap over-read on every pairing attempt, still present in upstream master.

Our fix (`external/uxplay` `c4e3844`) does both. The handler keeps a capacity
bound and copies `client_proof_len` bytes; the exact length check moves into
`srp_validate_proof`, which derives it from the verifier's session key length
so it tracks `SRP_SHA` instead of hardcoding 20. `proof_len` became in/out,
and the response sends the real length rather than a literal 20.

Three things to know if you work on this path:

- **A length mismatch returns -3, not -1**, and leaves `session->srp` intact.
  Freeing it there turned a recoverable 470 into a crash for the next request
  on the same connection — and a looping client sends many.
- **`srp_validate_proof` and `srp_confirm_pair_setup` now guard
  `session->srp`.** Steps 2 and 3 dereferenced it unconditionally even though
  a client can send them without step 1.
- **The PIN is single-use.** `raop_handler_pairpinstart` only issues one when
  `raop->pin <= 9999`, step 1 zeroes it, and our `c17d869` refuses any later
  step 1 with "no pin on screen". A phone already stuck in a password loop can
  never succeed, whatever you fix — dismiss it and start a fresh attempt.

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
