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

### End to end — a correction

This section used to say the chain was proven on `lineage_sdk_tv_x86_64`, on
the strength of a Bluetooth speaker's received-byte counter moving by 121,528
bytes during a 10-second stream against a flat idle baseline. **That was a
false positive**, and it hid two real defects for a week.

The counter measured the A2DP link waking up, not the tone. SBC is constant
bit rate, so encoded silence and encoded signal cost the same bytes, and the
receiver *does* open and start an AAudio stream at the beginning of every
session — which is enough to bring the A2DP output out of standby. Nothing was
ever written to that stream (see "Why it was silent" below). The idle baseline
was taken with no stream open at all, so it could not tell the difference.

The lesson is in the method: a byte counter or a level meter cannot tell
silence from signal. What can is a frequency check on the captured audio —
the tests below look for 440 Hz specifically, and on porg the capture comes
off the HDMI output through the MS2109, which is what the viewer hears.

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

### Metadata, and what goes on screen

Shairport is headless. It emits metadata on a pipe and leaves the display to
whatever is reading it, which is what `AirPlayReceiver` is for.

The hub carries more than enough for a now-playing screen:

| | |
| --- | --- |
| `track_name`, `artist_name`, `album_name`, `album_artist_name` | the track |
| `genre`, `composer`, `comment`, `file_kind` | the rest of the tags |
| `cover_art_pathname` / `PICT` items | artwork, as JPEG |
| **`client_name`** | **the sending device — "Phil's iPhone"** |
| `client_ip`, `server_ip`, `stream_type` | the session |
| `source_format` | e.g. `AAC/44100/S16_LE/2` |
| `progress_string` | position |

The wire format is a stream of
`<item><type>hex</type><code>hex</code><length>n</length><data encoding="base64">…</data></item>`,
where type and code are four-character codes packed into 32 bits. `core` items
are what the sender told us about the track; `ssnc` items are Shairport's own
notifications — `pbeg`/`pend` for play begin and end, `PICT` for artwork,
`snam` for the sending device's name.

`AirPlayReceiver` parses those and publishes a **MediaSession**, which is the
Android-native way to get a now-playing surface: the system media controls
pick it up, and the panel or the screensaver can read the same session rather
than each growing its own AirPlay support.

### The control service

`AirPlayReceiver` (`vendor/jetson-tv/AirPlayReceiver`) is a foreground service
that does the two things a headless daemon cannot do for itself:

- **Holds audio focus**, so AirPlay and Kodi do not talk over each other. It
  takes focus when a stream actually starts rather than when the receiver is
  enabled — the box may sit advertising for hours, and holding focus that whole
  time would silence everything else for no reason. On `AUDIOFOCUS_LOSS` it
  stops receiving rather than fighting whatever took over.
- **Publishes the MediaSession** from the metadata pipe.

It never execs anything. The daemon is an init service and this only sets
`persist.jetsontv.airplay.enabled`, which keeps the SELinux story small — an
app that could exec a system binary would need far more.

Two things worth knowing if you touch it:

- **`SystemProperties.set` throws** rather than returning a failure when the
  write is refused, which it is on any build without our property label. Left
  unguarded it takes the whole service down at startup, so the receiver dies
  instead of merely having no daemon to talk to.
- Opening a FIFO blocks until a writer appears and returns EOF every time the
  writer closes; both are normal, so the reader loops. When the pipe does not
  exist at all it backs off and says so once, rather than spinning.

### Deployed, 30 September

Flashed to the porg hardware and booted. Present and correct: the daemon, its
init `.rc`, `AirPlayReceiver`, and the property label `shairport_prop`.

**One defect found only on hardware.** porg has no separate system_ext
partition — `/system_ext` is a symlink to `/system/system_ext` — and file
contexts are matched against the *real* path when the image is built. The
`/system_ext/bin/shairport-sync` entry therefore matched nothing and the binary
shipped as plain `system_file`. Silent at build time, fatal at runtime: with
the wrong exec label init cannot transition the service into the `shairport`
domain, so it never starts and `/data/misc/airplay` is never created.

The property was labelled correctly throughout, because property contexts are
not path-matched — which is what made the failure look partial and confusing.

Fixed in `patches/porg/0003` by listing both path forms. Needs a rebuild and
reflash to verify.

The emulator could never have caught this: the policy is porg-only and was
verified by compilation alone.

### Metadata end to end, 29 September

Proven on the emulator, from sender to screen. `raopsend` now sends metadata
as well as audio, which is what had been missing: a receiver writes to its
metadata pipe only what a sender gives it, so a tone-only sender leaves the
pipe silent and there is no way to tell a broken pipe from an idle one.

What Shairport recognises, and what `raopsend` therefore emits (`rtsp.c`,
`handle_set_parameter`):

| sent | arrives as |
| --- | --- |
| `X-Apple-Client-Name:` header on ANNOUNCE | `ssnc`/`snam`, the sender's name |
| `application/x-dmap-tagged` | `core` items, the DAAP track tags |
| `image/jpeg` | `ssnc`/`PICT`, the cover art |
| `text/parameters` `progress: a/b/c` | `ssnc`/`prgr`, position in frames |

**The DMAP framing has one trap.** `handle_set_parameter_metadata` starts at
`off = 8`: it assumes and discards exactly one container header before the
first tag. Tags sent at the top level lose their first eight bytes and the run
desynchronises. They have to sit inside a wrapper, which iTunes sends as
`mlit`, a listing item. The tags themselves are `minm`, `asar`, `asal` and
`astm` (track length in milliseconds, big-endian) — `astm` is what gives a
progress bar something to measure against, since the progress items only carry
a position.

`RTP-Info: rtptime=` is optional — Shairport only logs its absence — but
without it every item arrives outside the `mdst`/`mden` brackets that tell a
reader which items describe one track, so `raopsend` always sends it.

`--second-track` sends a different track at the halfway point, which exercises
a reader's update path as well as its first read.

Three defects in the reader, all found by actually running it:

- `onArtwork` re-published the metadata with the title, artist and album
  wiped. `MediaMetadata` has no partial update — each `setMetadata` replaces
  the lot — and the art arrives *after* the tags, so the panel would have
  shown a cover with no words under it. Both the service and the dream now
  assemble from a cache rather than from whatever call is in hand.
- Fields were never cleared between tracks, so a track that omits one
  inherited the previous track's. Cleared on `mdst` now.
- Items were published per tag rather than per group, so one track caused four
  MediaSession updates. Accumulated between `mdst` and `mden` instead, with an
  immediate publish retained for anything arriving outside a group.

**Verified on the emulator** with `setenforce 0`, because the porg sepolicy is
not in that build and the daemon has to be started by hand rather than by
init. That isolates the logic from the policy; the policy itself is what the
next porg flash tests, `patches/porg/0003` included.

### The Streaming tile

`patches/Catapult/0006`. Shows what the box is advertising and offers a switch
per target, because until now the receiver could only be enabled by setting a
system property by hand over adb.

The switch starts and stops the *service*, not the property, even though the
panel is allowed to write it. The property is only what init watches to run
the daemon; the service holds audio focus and publishes the MediaSession, and
a daemon running without it plays over whatever else is on and shows nothing.
The service sets the property once it is up, so the property is still what the
tile reads back.

`startService`, not `startForegroundService`: the panel is on screen so the
background-start restriction does not apply, and `startForegroundService` is
wrong for the stop case — it promises a `startForeground()` that a service
shutting itself down never makes, and the platform kills it for that.

### The now-playing surface

AmbientDream draws it, over the photograph: cover art, title, artist, the
sender, and a progress bar. It watches `MediaSessionManager` rather than
anything AirPlay-shaped, so Kodi and Plex get the same panel for the same
code.

That costs `MEDIA_CONTENT_CONTROL`, which is `signature|privileged`, so
AmbientDream is now platform-signed and privileged with its own privapp
allowlist. The alternative — a notification listener — needs a setting the
viewer has to go and find and turn on.

Positions arrive about once a second. `PlaybackState` carries the moment each
one was taken, and the panel extrapolates from it, which is what keeps the bar
moving smoothly rather than stepping.

A session with no title is ignored rather than drawn: a game's background
music should leave the photograph alone.

### Two defects only hardware could find, 30 September

Both were invisible to the emulator for the same reason: there the daemon is
started by hand, so nothing ever exercised init.

**The exec label.** Fixed and confirmed — `init.svc.shairport-sync` now reports
a state instead of nothing, so init does transition the binary into the
`shairport` domain, `/data/misc/airplay` is created, and there are no denials
anywhere in the boot log. See the 30 September note above for why listing both
`/system/system_ext/...` and `/system_ext/...` is what it took.

**init tokenises before it expands.** The next failure in line, and a much
better disguise:

    F init: cannot expand arguments: unexpected end of string in
            '${persist.jetsontv.airplay.name:-Jetson', looking for }
    I init: Service 'shairport-sync' (pid 5641) exited with status 6

The service line carried `--name ${persist.jetsontv.airplay.name:-Jetson TV}`.
init splits the line into arguments *first* and expands each one after, so the
default value's space ended it: the first token is a `${` with no `}` and
expansion fails. The service exited on every start and init restarted it
forever.

What made it hard to see from outside: `getprop init.svc.shairport-sync` says
`running` in the gap between attempts, so it reads as a daemon that is up but
not listening. `connect: Connection refused` from `raopsend` on loopback was
the first honest symptom. The giveaway in `getprop` is `restarting` rather than
`running`, which you only catch if you look at the right moment; the init log
says it plainly every time.

Quoting the whole expansion keeps it one token, and `${x:-default}` expands
normally after that — `tokenizer.cpp` treats a `"` run as a single token
including spaces, and `ExpandProps` runs per argument afterwards.

Two habits worth keeping from this:

- **A defect in an init `.rc` cannot be found by running the binary by hand.**
  Anything only init parses — argument expansion, `seclabel`, the user and
  group list, the trigger — needs a device.
- **Read `init.svc.<name>` more than once.** A crash loop and a healthy daemon
  look identical in a single sample.

### Why it was silent, and then why it dropped out

**Status: working on porg.** A 12-second 440 Hz stream from `raopsend`
arrives on the television's HDMI audio as 12.0 s of tone at the level sent,
with no gaps, in every session including the first after boot, alongside the
now-playing panel — daemon started by init as its own uid under enforcing
SELinux, no denials. Verified by frequency analysis of the MS2109's audio
capture, which a byte counter or level meter cannot fake.

Getting there took seven defects, most of them hidden behind the one before.
In the order they surfaced:

**1. `raopsend` never sent RTP SYNC.** `debuggerd -b` put the player in
`buffer_get_frame()`, which is gated on `have_timestamp_timing_information()`.
For Classic AirPlay that becomes true only when a SYNC packet (`0x80 0xd4` on
the control port) arrives after a timing exchange — the sender's statement
that "at this NTP time, frame `now − latency` is playing". Without it the
player waits forever and writes nothing, not even lead-in silence, to *any*
backend: `-o stdout` produced zero bytes. ("synced by first packet" in the
log is buffer sequencing, a different thing.) `raopsend` now sends one before
the first packet and once a second after, and drains the latency before
TEARDOWN. It also sends a volume: without one, Shairport's default of −24 is
about −55 dB, and the tone arrived at RMS 23.

**2. The daemon ran as `audioserver`**, which libaudioclient takes to mean
"this process *is* audioserver": its service getters skip the binder lookup
and wait INT32_MAX ms for an in-process service that never arrives, so
`openStream` hung. A/B'd with only the uid changing: 1041 never produced a
track in four of four sessions; any other uid played every time. It now runs
as **`system_ext_airplay` (7500)**, a uid from the range reserved for
system_ext, defined in porg's `config.fs` so it resolves from
`/system_ext/etc/passwd` beside the binary (`patches/porg/0004`). Not `media`,
which would have worked: audioserver trusts media to attribute tracks and
recordings to other uids, and this daemon parses untrusted network input. Its
only group is `inet`.

**3. Three policy gaps**, unreachable until the daemon got past `openStream`
(`patches/porg/0005`), collected in one permissive session and granted
individually: audioserver's mixer thread calling back into the client;
PlayerBase registering the stream with AudioService in system_server, which
is how focus and ducking see the player; and mediametrics. That last one
matters although nothing needs the metrics — a refused `find` looks to the
client like a service that has not started, so it waited five seconds,
*twice*, inside `openStream`, and the first session after the daemon started
was ten seconds overdue against a two-second buffer. I first silenced it with
`dontaudit`, which only hid the cause.

**4. The service crashed at boot.** Android 15 refuses a `mediaPlayback`
foreground service started from `BOOT_COMPLETED`, so the boot receiver
crashed it twice and ActivityManager backed off for 30 minutes — daemon up,
nothing holding focus or publishing metadata. It is `specialUse` now, which
is also the honest type: it spends its life waiting for a sender, and the
audio is played by the daemon, not this app.

**5. `raopsend` started RTP timestamps and sequence numbers at 0**, which
Shairport uses as "unset": its first-packet setup re-ran every loop pass and
the first packets were misordered. Real senders start at random, and so does
`raopsend` now (`--rtp-start` forces a value; `0xFFFF0000` wraps 32 bits a
second in). The same wrap would have broken `MetadataReader`'s progress
arithmetic with a real sender; it is modulo 2³² now. This was not the cause of
the dropouts, which is worth saying because it looked like it.

**6. `delay()` left out the output path.** It reported
`framesWritten − framesRead`: what sits in AAudio's client buffer, not the
~50 ms of mixer, HAL and HDMI behind it. It now projects the presentation
timestamp from `AAudioStream_getTimestamp()` to now, as the ALSA backend does
with `snd_pcm_status`. The player's steady-state sync error went to 36 µs.

**7. The dropouts: the backend blocked under `ab_mutex`.** Instrumenting the
player showed the sync error *stepping* by −48, −96, −136 ms, each step a run
of 6–11 packets reported "not ready" — never arrived. On loopback. The kernel's
`RcvbufErrors` rose by exactly the number of missing packets. Cause: during
the two-second lead-in the player writes silence 100 ms at a time *while
holding `ab_mutex`*, which the RTP receiver needs to file each packet.
AAudio's ~32 ms buffer made every write block ~100 ms, the receiver filed one
packet per write while twelve arrived, and the 212 KB socket buffer
overflowed. The lost packets were exactly the ones due to play first.

A bigger AAudio buffer does not fix it: an AudioTrack will not start pulling
until its start threshold — by default the whole buffer — is filled, the NDK
cannot change the threshold, and with three seconds of buffer the track never
started. So `play()` now copies into a three-second ring of the backend's own
and returns at once, and AAudio drains it from its data callback. Kernel
drops, missing packets and resyncs all went to zero.

**What the first "end to end" was.** The byte counter on the emulator's
Bluetooth speaker moved because the daemon opens and starts its stream at the
beginning of every session, which brings A2DP out of standby — see the
correction under "End to end" above.

**Earlier on the way.** The timing responder was polled once per audio packet,
so loopback round trips sawtoothed from 0 to 8 ms and Shairport rejected every
clock sample. It is its own thread now, at a steady 0.2 ms.

### When something else takes over

If another app takes audio focus for good — a film started in Kodi — the
current AirPlay session ends and **the receiver stays on**. It used to switch
itself off, so the Streaming tile read "Off" afterwards and the next person to
AirPlay found nothing to stream to.

Mechanism: the daemon plays through its own AAudio stream and knows nothing of
focus, so the controlling app ends the session by writing a fresh value to
`jetsontv.airplay.interrupt`, and init restarts the daemon. That drops the
sender and has the daemon advertising again within a second. The trigger is
guarded on `persist.jetsontv.airplay.enabled`, because init's `restart` starts
a stopped service and an interrupt must never bring up a receiver that has been
switched off. The next AirPlay session requests focus in turn and takes it
back, which is right: starting a stream is an explicit act.

Verified on porg with a scratch app standing in for the video player: focus
loss logged, daemon restarted (new pid), tile still "AirPlay", and a second
stream played.

### What is on screen while streaming

When a stream starts while the box is sitting on its home screen, the receiver
starts the screensaver immediately, so AmbientDream's now-playing panel is on
the television within a moment. Before this the panel only appeared after
`screen_off_timeout` — fifteen minutes here — and the stream played with
nothing on screen but a notification count.

It does this only from the home activity itself. If someone is in an app, or
has the panel open (which is the launcher's too, so the check compares the
full component, not the package), the screen is theirs and is left alone. If
the screen is off, it stays off.

Two details that cost a build each:

- **DreamManager, not TvSettings' SLEEP intent.** The Screensaver tile uses
  the intent because the panel is in the foreground. A background service may
  not start activities, foreground service or not, so this calls
  `IDreamManager.dream()`, which needs `WRITE_DREAM_STATE`; seeing another
  app's activity needs `REAL_GET_TASKS`. Both are in a privapp allowlist of the
  app's own, and **must** be: this build has
  `ro.control_privapp_permissions=enforce`, under which a privileged app
  requesting a privileged permission without an entry stops the device
  booting, platform signature or not.
- **No `isDreaming()`.** It needs a third permission, `READ_DREAM_STATE`, and
  the first build threw on it. It is also unnecessary: since Android 12 a dream
  runs as `DreamActivity` on top, so "home is in front" is already false while
  dreaming.

Verified on porg: from the home screen the dream started 18 ms after the
stream; with the panel open, and inside Settings, nothing on screen changed
and the stream played.

### Still to do

1. **A real sender.** Everything so far is `raopsend` on loopback. An iPhone
   exercises what it cannot: ALAC, encryption (`a=rsaaeskey`), DACP remote
   control, and a network with real jitter and loss.
2. **Real-time priority.** The player thread asks for SCHED_FIFO and is
   refused (`pthread_create sched_setscheduler ... Operation not permitted`);
   harmless so far, but worth `capabilities SYS_NICE` if audio ever glitches
   under load.
3. **FCast**, for video.
4. **Bonjour advertisement.** The daemon uses the bundled tinysvcmdns; nothing
   has yet confirmed a real iOS sender discovers the box by itself, as opposed
   to being pointed at it.
