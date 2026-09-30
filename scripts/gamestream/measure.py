#!/usr/bin/env python3
"""
Read pattern.py's timing strip back off the Jetson's HDMI output.

The capture card (MS2109, /dev/video0) is on the same machine as the pattern,
so each captured frame's arrival time minus the time in its strip is the
whole path: Sunshine's capture and encode, the network, Moonlight's decode
and display, HDMI, and the capture card's own delay (a constant we cannot
remove, so the result is an upper bound). The strip's frame counter shows
what reached the screen: repeats and gaps against the 60 fps source.

    scripts/gamestream/measure.py --seconds 30 --snapshot out.png > result.json

Prints a JSON summary; the snapshot is a colour frame from the middle of the
run, which is where Moonlight's performance overlay gets read from.
"""

import argparse
import json
import statistics
import struct
import sys
import time
import zlib

import gi

gi.require_version("Gst", "1.0")
from gi.repository import GLib, Gst  # noqa: E402

BITS_TIME = 24
BITS_COUNT = 8
BLOCKS = 2 + BITS_TIME + BITS_COUNT
W, H = 1280, 720


def write_png(path, rgb, w, h):
    raw = b"".join(b"\x00" + rgb[y * w * 3:(y + 1) * w * 3] for y in range(h))

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 6)))
        f.write(chunk(b"IEND", b""))


def decode(rgb, strip_frac):
    """The strip's value, or None if there is no readable strip."""
    y = int(H * (1 - strip_frac / 2))
    levels = []
    for i in range(BLOCKS):
        x = int((i + 0.5) * W / BLOCKS)
        s = 0
        for dy in (-2, 0, 2):
            for dx in (-3, 0, 3):
                s += rgb[((y + dy) * W + (x + dx)) * 3 + 1]  # green channel
        levels.append(s / 9)
    white, black = levels[0], levels[1]
    if white - black < 60:
        return None
    mid = (white + black) / 2
    value = 0
    for lv in levels[2:]:
        value = (value << 1) | (1 if lv > mid else 0)
    return value


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=30)
    ap.add_argument("--snapshot")
    ap.add_argument("--device", default="/dev/video0")
    ap.add_argument("--strip", type=float, default=1 / 18,
                    help="strip height as a fraction of the picture (pattern.py: 1/18)")
    args = ap.parse_args()

    Gst.init(None)
    pipe = Gst.parse_launch(
        f"v4l2src device={args.device} ! image/jpeg,width={W},height={H},framerate=60/1 "
        "! jpegdec ! videoconvert ! video/x-raw,format=RGB "
        "! appsink name=sink emit-signals=true sync=false max-buffers=4 drop=false")
    sink = pipe.get_by_name("sink")
    samples = []  # (arrival_ms, value or None)
    arrivals = []
    snap = {"done": False}
    t_end = [None]
    loop = GLib.MainLoop()

    def on_sample(s):
        now_ms = time.time_ns() / 1_000_000
        buf = s.emit("pull-sample").get_buffer()
        ok, info = buf.map(Gst.MapFlags.READ)
        if not ok:
            return Gst.FlowReturn.OK
        try:
            rgb = bytes(info.data)
        finally:
            buf.unmap(info)
        if t_end[0] is None:
            t_end[0] = now_ms + args.seconds * 1000
        arrivals.append(now_ms)
        samples.append((now_ms, decode(rgb, args.strip)))
        if args.snapshot and not snap["done"] and now_ms > t_end[0] - args.seconds * 500:
            write_png(args.snapshot, rgb, W, H)
            snap["done"] = True
        if now_ms >= t_end[0]:
            GLib.idle_add(loop.quit)
        return Gst.FlowReturn.OK

    sink.connect("new-sample", on_sample)
    pipe.set_state(Gst.State.PLAYING)
    GLib.timeout_add_seconds(int(args.seconds) + 15, loop.quit)
    loop.run()
    pipe.set_state(Gst.State.NULL)

    mask_t = (1 << BITS_TIME) - 1
    lat, counters = [], []
    for arrival, value in samples:
        if value is None:
            continue
        sent = value >> BITS_COUNT
        d = (int(arrival) - sent) & mask_t
        if d < 5000:  # anything larger is a misread
            lat.append(d)
        counters.append(value & 0xFF)
    steps = [((b - a) & 0xFF) for a, b in zip(counters, counters[1:])]
    hist = {k: steps.count(k) for k in sorted(set(steps))}
    frames = len(samples)
    dur = (arrivals[-1] - arrivals[0]) / 1000 if len(arrivals) > 1 else 0
    q = statistics.quantiles(lat, n=100) if len(lat) >= 100 else None
    out = {
        "captured_frames": frames,
        "capture_fps": round((frames - 1) / dur, 2) if dur else None,
        "readable_frames": len(counters),
        "latency_ms": {
            "min": min(lat) if lat else None,
            "median": statistics.median(lat) if lat else None,
            "mean": round(statistics.fmean(lat), 1) if lat else None,
            "p95": round(q[94], 1) if q else None,
            "max": max(lat) if lat else None,
        },
        # counter step between consecutive captured frames: 1 is ideal; 0 is
        # the same stream frame seen twice, 2+ is a stream frame never seen
        "counter_steps": hist,
    }
    json.dump(out, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
