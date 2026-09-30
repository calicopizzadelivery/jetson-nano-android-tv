#!/usr/bin/env python3
"""
The picture Sunshine streams for the game-streaming tests.

A moving test pattern, with a timing strip along the bottom that carries this
machine's clock. The strip is its own X window, raised over the pattern and
redrawn 60 times a second straight after reading the clock: the current time
in milliseconds (24 bits) and a frame counter (8 bits), as black and white
blocks. measure.py reads the strip back off the Jetson's HDMI output through
the capture card, on this same machine and clock, so the difference is the
whole trip: capture, encode, network, decode, display, and the capture card
itself.

The strip is drawn through libX11 by ctypes, which needs nothing installed:
there is no python3-gi-cairo or python-xlib on thebe, and GStreamer buffers
map read-only from Python without gst-python.

Strip layout, left to right, 34 equal blocks: white and black references,
24 time bits (MSB first), 8 counter bits. A block is white for a 1.

    DISPLAY=:99 scripts/gamestream/pattern.py --size 1920x1080 [--pattern ball]

Patterns: "ball" (light: a moving ball) or "zone" (a moving zone plate: fine
detail everywhere, hard on an encoder).
"""

import argparse
import ctypes
import sys
import threading
import time

import gi

gi.require_version("Gst", "1.0")
from gi.repository import GLib, Gst  # noqa: E402

BITS_TIME = 24
BITS_COUNT = 8
BLOCKS = 2 + BITS_TIME + BITS_COUNT

x11 = ctypes.CDLL("libX11.so.6")
x11.XOpenDisplay.restype = ctypes.c_void_p
x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
x11.XDefaultScreen.argtypes = [ctypes.c_void_p]
x11.XRootWindow.restype = ctypes.c_ulong
x11.XRootWindow.argtypes = [ctypes.c_void_p, ctypes.c_int]
for _f in (x11.XBlackPixel, x11.XWhitePixel):
    _f.restype = ctypes.c_ulong
    _f.argtypes = [ctypes.c_void_p, ctypes.c_int]
x11.XCreateSimpleWindow.restype = ctypes.c_ulong
x11.XCreateSimpleWindow.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_int,
                                    ctypes.c_uint, ctypes.c_uint, ctypes.c_uint, ctypes.c_ulong,
                                    ctypes.c_ulong]
x11.XMapRaised.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
x11.XRaiseWindow.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
x11.XCreateGC.restype = ctypes.c_void_p
x11.XCreateGC.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p]
x11.XSetForeground.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_ulong]
x11.XFillRectangle.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_int,
                               ctypes.c_int, ctypes.c_uint, ctypes.c_uint]
x11.XFlush.argtypes = [ctypes.c_void_p]


def strip_loop(w, h, strip, fps, stop):
    dpy = x11.XOpenDisplay(None)
    if not dpy:
        print("pattern: cannot open the X display", file=sys.stderr)
        return
    scr = x11.XDefaultScreen(dpy)
    black, white = x11.XBlackPixel(dpy, scr), x11.XWhitePixel(dpy, scr)
    win = x11.XCreateSimpleWindow(dpy, x11.XRootWindow(dpy, scr), 0, h - strip, w, strip, 0,
                                  black, black)
    x11.XMapRaised(dpy, win)
    gc_w = x11.XCreateGC(dpy, win, 0, None)
    gc_b = x11.XCreateGC(dpy, win, 0, None)
    x11.XSetForeground(dpy, gc_w, white)
    x11.XSetForeground(dpy, gc_b, black)
    edges = [round(i * w / BLOCKS) for i in range(BLOCKS + 1)]
    period = 1.0 / fps
    t_next = time.monotonic()
    count = 0
    while not stop.is_set():
        t_next += period
        delay = t_next - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        else:
            t_next = time.monotonic()  # fell behind; do not try to catch up
        x11.XRaiseWindow(dpy, win)  # stay above the pattern's window
        now_ms = time.time_ns() // 1_000_000
        value = ((now_ms & ((1 << BITS_TIME) - 1)) << BITS_COUNT) | (count & 0xFF)
        count += 1
        for i in range(BLOCKS):
            on = True if i == 0 else False if i == 1 else (value >> (BLOCKS - 1 - i)) & 1
            x11.XFillRectangle(dpy, win, gc_w if on else gc_b, edges[i], 0,
                               edges[i + 1] - edges[i], strip)
        x11.XFlush(dpy)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", default="1920x1080")
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--pattern", choices=["ball", "zone"], default="ball")
    args = ap.parse_args()
    w, h = (int(v) for v in args.size.split("x"))
    strip = h // 18  # 60 px at 1080p

    if args.pattern == "ball":
        src = "videotestsrc is-live=true pattern=ball motion=sweep background-color=0xff303848"
    else:
        src = "videotestsrc is-live=true pattern=zone-plate kx2=40 ky2=40 kt=3"

    Gst.init(None)
    pipe = Gst.parse_launch(
        f"{src} ! video/x-raw,width={w},height={h},framerate={args.fps}/1 "
        "! clockoverlay time-format=\"%H:%M:%S\" font-desc=\"Sans 48\" "
        "! videoconvert ! ximagesink sync=false qos=false")
    pipe.set_state(Gst.State.PLAYING)
    stop = threading.Event()
    threading.Thread(target=strip_loop, args=(w, h, strip, args.fps, stop), daemon=True).start()

    loop = GLib.MainLoop()
    bus = pipe.get_bus()
    bus.add_signal_watch()

    def on_msg(_bus, msg):
        if msg.type == Gst.MessageType.ERROR:
            err, dbg = msg.parse_error()
            print(f"pattern: {err} {dbg}", file=sys.stderr)
            loop.quit()

    bus.connect("message", on_msg)
    try:
        loop.run()
    finally:
        stop.set()
        pipe.set_state(Gst.State.NULL)


if __name__ == "__main__":
    main()
