# Game streaming: Moonlight and Sunshine

Moonlight 12.2 on the Jetson, streaming from Sunshine on thebe (RTX 2080
SUPER, NVENC) over wired gigabit Ethernet. Measured 30 September 2026.

**Verdict: viable.**
- **1080p60** is solid. The stream arrives and renders at a full 60 fps with
  0% network drops and about 1 ms to decode a frame. The latency the stream
  adds is lost in the frame-to-frame noise of the measurement.
- **4K60** also decodes and renders at a full 60 fps, at the same low CPU
  load. The test host could not capture 4K smoothly, so 4K latency and
  pacing were not measured cleanly.
- **The Jetson is barely working:** about 70% of its 400% CPU, and
  temperatures up 2 °C, at either resolution. The hardware decoder does the
  work.

## What was set up

| Where | What |
| --- | --- |
| Jetson | Moonlight for Android v12.2 (`app-nonRoot-release.apk` from moonlight-stream/moonlight-android, SHA-256 checked), installed as an ordinary app. It found thebe by mDNS on its own. |
| thebe | Sunshine v2026.914.233613 AppImage (LizardByte/Sunshine, SHA-256 checked), run as the desktop user with no root. It captures a nested X display (Xephyr `:99`), so nothing of the real desktop is streamed, and encodes with NVENC. Keyboard, mouse, gamepad and audio are disabled, and the web UI answers on localhost only. |
| Test picture | `scripts/gamestream/pattern.py`: a moving pattern with a **timing strip**, 34 black/white blocks carrying thebe's clock in ms and a frame counter, redrawn 60 times a second. |
| Measurement | `scripts/gamestream/measure.py`: reads the strip back off the Jetson's HDMI through the MS2109 on thebe, on the same clock. Arrival time minus strip time is end-to-end latency. The counter shows repeated or skipped frames. It also snapshots Moonlight's performance overlay. |

Scripts, all in `scripts/gamestream/`:

```
host.sh start [WxH] [ball|zone]   # Xephyr + pattern + Sunshine on thebe
host.sh pin NNNN                  # Moonlight's pairing PIN, via Sunshine's local API
host.sh stop
moonlight_prefs.py key=value ...  # Moonlight's settings over adb (needs adb root)
run_config.sh NAME RES FPS KBPS FORMAT PACING [SECONDS]   # one run, start to finish
measure.py --seconds N --snapshot F.png > F.json
clock_offset.py                   # thebe's clock minus the Jetson's, over one adb shell
StripClock/                       # calibration app, below; not shipped
```

On this bench `ADB="docker exec -i adbnode adb"`. Sunshine and Moonlight
live in `~/.cache/jetson-tv/gamestream/`. Sunshine's config, keys, pairing
and log are in `state/` there, so the Jetson stays paired between runs.

## Results

Each run is 30 s: 1,800 frames captured at 60 fps unless noted. "Clean
steps" is the share of captured frames that advanced exactly one stream
frame.

### 1080p60 (moving ball pattern)

| Config | End-to-end, run medians | Clean steps | Decode (Moonlight) | Host processing | Network | Drops |
| --- | --- | --- | --- | --- | --- | --- |
| HEVC 20 Mbps, latency pacing | 88, 95, 90, 88 ms | 97.9-99.8% | 1.0-1.5 ms | 5.3-5.5 ms | 1 ms | 0% |
| H.264 20 Mbps, latency pacing | 88, 90, 89 ms | 99.3-99.9% | not reported (0.00) | 5.6 ms | 1 ms | 0% |
| HEVC 20 Mbps, smoothness pacing | 76, 88, 93 ms | 99.5-99.9% | 1.2 ms | 5.2 ms | 1 ms | 0% |
| HEVC 20 Mbps, balanced pacing | 129 ms (1 run) | 99.9% | 1.0 ms | 5.7 ms | 1 ms | 0% |

Codec and latency-vs-smoothness pacing make no measurable difference.
Balanced pacing adds about 40 ms, so leave Moonlight on its default,
latency. Moonlight reports 0.00 ms decode for `OMX.Nvidia.h264.decode`; its
timing hook evidently does not fire for that component, which does not mean
it is instant.

### 1080p60 stress (moving zone plate, fine detail everywhere)

HEVC at 20 and 50 Mbps, and H.264 at 50 Mbps. Moonlight: 60.00 fps in and
rendered, 0% drops, decode 0.4-1.2 ms, host processing ~5.5 ms. End to end
93-100 ms. The strip's pacing looked poor (16-17% irregular steps), but it
was the **capture card**: on this content the MS2109's MJPEG could only
deliver 53.3 fps. Read straight off the source display, the strip was 99.7%
regular.

### 4K60 (moving ball pattern)

| Config | End-to-end | Clean steps | In / rendered | Decode | Host processing (min/max/avg) |
| --- | --- | --- | --- | --- | --- |
| HEVC 80 Mbps | 112 ms | 77% | 60.28 / 60.28 fps | 1.03 ms | 16.0 / 28.0 / 19.9 ms |
| H.264 80 Mbps | 127 ms | 64% | 60.26 / 60.26 fps | not reported | 18.4 / 35.2 / 23.5 ms |

The Jetson keeps up: it receives and renders a full 60 fps. The extra
latency and the uneven frames come from **the test host**. Sunshine is
copying 33 MB frames out of a software X server (Xephyr) through XShm 60
times a second, and its processing time goes from 5.5 ms at 1080p to 20-24
ms. A gaming PC capturing on the GPU (KMS or NvFBC) would not pay that, and
neither is possible here without root. **4K needs repeating against a real
host.**

### How much of the latency is the stream?

The end-to-end figures include things a player would not have: the MS2109
capture card's own delay, and scanout down to the strip at the bottom of the
screen. To separate them, **StripClock**, a small app, draws the same strip
on the Jetson itself, from the Jetson's clock corrected to thebe's
(`clock_offset.py`: offset 1130.6 ms, good to ±0.6 ms). No streaming is
involved.

- Local draw, through the same capture path: 80, 93, 94, 96, 97 ms, mean
  **92.0 ms**.
- Streamed 1080p60, latency or smoothness pacing: mean **88.6 ms** over nine
  runs.

The streamed picture is no later than one the Jetson draws itself. A normal
app frame goes through Android's UI pipeline (draw at vsync, render thread,
composition at the next vsync), which is a frame or two longer than
Moonlight's path, where decoded video goes to a display layer directly. So
the capture chain dominates the measurement, and the stream's own cost is
below its frame-level noise. Moonlight's accounting puts it at about 8 ms
(host 5.5, network 1, decode 1), plus up to a frame of waiting for the next
capture. On a real television, expect roughly that plus the TV's input lag.

Each session lands at a random phase between three unsynchronised 60 Hz
clocks (Sunshine's capture, the Jetson's display, the capture card), which
is why single-run medians vary by a frame. Compare means over several runs,
not single runs.

### Load on the Jetson

| | Total CPU (of 400%) | Moonlight | Composer | Codec service | SurfaceFlinger | CPU temp |
| --- | --- | --- | --- | --- | --- | --- |
| Idle | ~20% | — | — | — | — | 26 °C |
| 1080p60 HEVC 20 Mbps | ~70% | 17% | 13% | 7% | 7% | 28 °C |
| 4K60 HEVC 80 Mbps | ~70% | 13% | 7% | 10% | 7% | 27.5 °C |

CPUs stayed at 1.479 GHz. The bench has the fan fitted, so this says nothing
yet about a fanless case, but there is little heat to deal with.

## Traps

- **Sunshine's PIN API takes a `pairing_id`**, from GET `/api/pin`'s
  `pairings` list. A bare `{"pin": ...}` is refused with "pairing_id must
  contain exactly 32 hexadecimal characters". `host.sh pin` does both
  steps.
- **Restarting Xephyr needs the old one gone first.** Otherwise the new one
  finds `/tmp/.X99-lock`, exits, and Sunshine then fails with "could not
  connect to display :99". `host.sh` waits.
- **Nothing to draw with in Python on thebe:** no python3-gi-cairo, no
  python-xlib, and GStreamer buffers map read-only without gst-python. The
  strip is drawn through libX11 by ctypes.
- **The capture card is a measuring instrument with limits.** 60 fps at
  1280x720 MJPEG, until the picture is dense enough that it is not. Check
  `capture_fps` in every result.
- **Moonlight's option values**, from its resource table (aapt2): video_format
  `auto forceav1 forceh265 neverh265`, frame_pacing
  `latency balanced cap-fps smoothness`.

## Not tested yet

- **4K against a real gaming PC,** with GPU capture. The one result here that
  is limited by the test rig.
- **Controllers.** Sunshine's input was disabled for these tests, since
  uinput devices on thebe would have driven the real desktop. Bluetooth pads
  wait on the radio (RAIL 2 and 4); USB pads should work now.
- **Audio,** including 5.1/7.1 surround (RAIL 8), and **HDR** (RAIL 9).
- **Wi-Fi,** once a radio is fitted. Wired gigabit showed 1 ms network
  latency and no drops.
- **Steam Link.** It is distributed through the Play Store, which this box
  does not have.
