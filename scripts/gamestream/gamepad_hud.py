#!/usr/bin/env python3
"""
A live gamepad display for the Sunshine test screen.

Draws the controller's state -- sticks, triggers, d-pad and every button --
on the X display Sunshine captures, so the pad's input comes back down the
stream and shows up on the television. That makes a controller testable by
looking at it: press a button here, see it light up there, which also puts
the whole round trip (pad -> Moonlight -> network -> Sunshine -> virtual pad
-> this -> encode -> network -> decode -> TV) on screen at once.

It reads Sunshine's virtual pad directly from /dev/input, the same device
gamepad_probe.py uses, so it needs a stream to be running with
CONTROLLER=enabled. With no device it draws an idle screen and keeps looking,
which is what happens between streams.

Drawn through libX11 by ctypes and double-buffered through a pixmap: thebe
has no python-xlib, and nothing here needs a toolkit.

    DISPLAY=:99 scripts/gamestream/gamepad_hud.py [--size 1920x1080] [--name Sunshine]

SPDX-License-Identifier: Apache-2.0
"""

import argparse
import ctypes
import fcntl
import glob
import os
import struct
import sys
import time

EV_KEY, EV_ABS = 0x01, 0x03
INPUT_EVENT = "llHHi"
INPUT_EVENT_SIZE = struct.calcsize(INPUT_EVENT)

BTN = {
    "A": 0x130, "B": 0x131, "X": 0x134, "Y": 0x133,
    "L1": 0x136, "R1": 0x137,
    "SELECT": 0x13a, "START": 0x13b, "HOME": 0x13c,
    "L3": 0x13d, "R3": 0x13e,
}
ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ = 0, 1, 2, 3, 4, 5
ABS_HAT0X, ABS_HAT0Y = 0x10, 0x11


def _ioc(d, t, nr, size):
    return (d << 30) | (size << 16) | (t << 8) | nr


EVIOCGNAME = lambda n: _ioc(2, ord("E"), 0x06, n)
EVIOCGABS = lambda a: _ioc(2, ord("E"), 0x40 + a, 24)   # struct input_absinfo


# ---- X11 ------------------------------------------------------------------

x11 = ctypes.CDLL("libX11.so.6")


class XColor(ctypes.Structure):
    _fields_ = [("pixel", ctypes.c_ulong), ("red", ctypes.c_ushort),
                ("green", ctypes.c_ushort), ("blue", ctypes.c_ushort),
                ("flags", ctypes.c_char), ("pad", ctypes.c_char)]


def _sig():
    v, u, i, L, c = ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong, ctypes.c_char_p
    x11.XOpenDisplay.restype, x11.XOpenDisplay.argtypes = v, [c]
    x11.XDefaultScreen.argtypes = [v]
    x11.XDefaultDepth.argtypes = [v, i]
    x11.XRootWindow.restype, x11.XRootWindow.argtypes = L, [v, i]
    x11.XBlackPixel.restype, x11.XBlackPixel.argtypes = L, [v, i]
    x11.XDefaultColormap.restype, x11.XDefaultColormap.argtypes = L, [v, i]
    x11.XParseColor.argtypes = [v, L, c, ctypes.POINTER(XColor)]
    x11.XAllocColor.argtypes = [v, L, ctypes.POINTER(XColor)]
    x11.XCreateSimpleWindow.restype = L
    x11.XCreateSimpleWindow.argtypes = [v, L, i, i, u, u, u, L, L]
    x11.XStoreName.argtypes = [v, L, c]
    x11.XMapRaised.argtypes = [v, L]
    x11.XCreateGC.restype, x11.XCreateGC.argtypes = v, [v, L, L, v]
    x11.XSetForeground.argtypes = [v, v, L]
    x11.XFillRectangle.argtypes = [v, L, v, i, i, u, u]
    x11.XDrawRectangle.argtypes = [v, L, v, i, i, u, u]
    x11.XFillArc.argtypes = [v, L, v, i, i, u, u, i, i]
    x11.XDrawArc.argtypes = [v, L, v, i, i, u, u, i, i]
    x11.XDrawString.argtypes = [v, L, v, i, i, c, i]
    x11.XCreatePixmap.restype, x11.XCreatePixmap.argtypes = L, [v, L, u, u, u]
    x11.XCopyArea.argtypes = [v, L, L, v, i, i, u, u, i, i]
    x11.XFlush.argtypes = [v]
    x11.XLoadFont.restype, x11.XLoadFont.argtypes = L, [v, c]
    x11.XSetFont.argtypes = [v, v, L]
    x11.XSetErrorHandler.restype = ctypes.c_void_p
    x11.XSetErrorHandler.argtypes = [ctypes.c_void_p]


_sig()
_ERR = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p)(lambda d, e: 0)


class Canvas:
    """A double-buffered window with named colours."""

    def __init__(self, w, h, title):
        self.w, self.h = w, h
        self.dpy = x11.XOpenDisplay(None)
        if not self.dpy:
            sys.exit("gamepad_hud: cannot open the X display "
                     f"({os.environ.get('DISPLAY', 'unset')})")
        x11.XSetErrorHandler(_ERR)   # a missing font must not be fatal
        self.scr = x11.XDefaultScreen(self.dpy)
        depth = x11.XDefaultDepth(self.dpy, self.scr)
        root = x11.XRootWindow(self.dpy, self.scr)
        black = x11.XBlackPixel(self.dpy, self.scr)
        self.win = x11.XCreateSimpleWindow(self.dpy, root, 0, 0, w, h, 0, black, black)
        x11.XStoreName(self.dpy, self.win, title.encode())
        x11.XMapRaised(self.dpy, self.win)
        self.buf = x11.XCreatePixmap(self.dpy, self.win, w, h, depth)
        self.gc = x11.XCreateGC(self.dpy, self.win, 0, None)
        for name in (b"10x20", b"9x15bold", b"fixed"):
            fid = x11.XLoadFont(self.dpy, name)
            if fid:
                x11.XSetFont(self.dpy, self.gc, fid)
                break
        self.cmap = x11.XDefaultColormap(self.dpy, self.scr)
        self._cache = {}

    def colour(self, spec):
        if spec not in self._cache:
            c = XColor()
            x11.XParseColor(self.dpy, self.cmap, spec.encode(), ctypes.byref(c))
            x11.XAllocColor(self.dpy, self.cmap, ctypes.byref(c))
            self._cache[spec] = c.pixel
        return self._cache[spec]

    def _fg(self, spec):
        x11.XSetForeground(self.dpy, self.gc, self.colour(spec))

    def rect(self, spec, x, y, w, h, fill=True):
        self._fg(spec)
        f = x11.XFillRectangle if fill else x11.XDrawRectangle
        f(self.dpy, self.buf, self.gc, int(x), int(y), int(w), int(h))

    def circle(self, spec, cx, cy, r, fill=True):
        self._fg(spec)
        f = x11.XFillArc if fill else x11.XDrawArc
        f(self.dpy, self.buf, self.gc, int(cx - r), int(cy - r),
          int(2 * r), int(2 * r), 0, 360 * 64)

    def text(self, spec, x, y, s):
        self._fg(spec)
        b = s.encode()
        x11.XDrawString(self.dpy, self.buf, self.gc, int(x), int(y), b, len(b))

    def present(self):
        x11.XCopyArea(self.dpy, self.buf, self.win, self.gc, 0, 0, self.w, self.h, 0, 0)
        x11.XFlush(self.dpy)


# ---- the pad --------------------------------------------------------------

class Pad:
    """Sunshine's virtual pad, reopened whenever the stream restarts."""

    def __init__(self, match):
        self.match = match
        self.fd = None
        self.path = self.name = None
        self.absinfo = {}
        self.buttons, self.axes = {}, {}

    def _candidates(self):
        for path in sorted(glob.glob("/dev/input/event*"),
                           key=lambda p: int(p.rsplit("event", 1)[1])):
            try:
                with open(path, "rb") as fd:
                    buf = bytearray(256)
                    fcntl.ioctl(fd, EVIOCGNAME(len(buf)), buf)
                    name = buf.split(b"\x00", 1)[0].decode("utf-8", "replace")
            except OSError:
                continue
            if self.match.lower() in name.lower():
                yield path, name

    def open(self):
        for path, name in self._candidates():
            try:
                fd = open(path, "rb", buffering=0)
            except OSError:
                continue
            os.set_blocking(fd.fileno(), False)
            self.fd, self.path, self.name = fd, path, name
            self.absinfo = {}
            for a in (ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ):
                buf = bytearray(24)
                try:
                    fcntl.ioctl(fd, EVIOCGABS(a), buf)
                    _, lo, hi, _, flat, _ = struct.unpack("6i", buf)
                    if hi > lo:
                        self.absinfo[a] = (lo, hi, flat)
                except OSError:
                    pass
            return True
        return False

    def close(self):
        if self.fd:
            try:
                self.fd.close()
            except OSError:
                pass
        self.fd = None
        self.buttons, self.axes = {}, {}

    def pump(self):
        """Drain pending events. False if the device went away."""
        if not self.fd:
            return False
        while True:
            try:
                data = self.fd.read(INPUT_EVENT_SIZE)
            except OSError:
                return False
            if not data or len(data) < INPUT_EVENT_SIZE:
                return True
            _, _, typ, code, value = struct.unpack(INPUT_EVENT, data)
            if typ == EV_KEY:
                self.buttons[code] = value != 0
            elif typ == EV_ABS:
                self.axes[code] = value

    def stick(self, ax, ay):
        """A stick as -1..1, with its own dead zone taken out."""
        out = []
        for a in (ax, ay):
            v = self.axes.get(a, 0)
            lo, hi, flat = self.absinfo.get(a, (-32768, 32767, 0))
            mid = (lo + hi) / 2
            span = (hi - lo) / 2 or 1
            n = (v - mid) / span
            if flat and abs(v - mid) < flat:
                n = 0.0
            out.append(max(-1.0, min(1.0, n)))
        return out[0], out[1]

    def trigger(self, a):
        v = self.axes.get(a, 0)
        lo, hi, _ = self.absinfo.get(a, (0, 255, 0))
        return max(0.0, min(1.0, (v - lo) / ((hi - lo) or 1)))


# ---- drawing --------------------------------------------------------------

BG, DIM, LIT, EDGE, TEXT = "#101418", "#2a3340", "#38e08b", "#5a6878", "#c8d4e0"


def draw(c, pad, connected):
    w, h = c.w, c.h
    c.rect(BG, 0, 0, w, h)
    # Moonlight's own performance overlay sits in the top-left corner, so the
    # header and the left-hand controls stay clear of it.
    c.text(TEXT, w * 0.42, 56, "JetsonTV  -  controller through Moonlight")
    c.text(TEXT if connected else "#e05a5a", w * 0.42, 88,
           f"{pad.name}" if connected else
           f"waiting for a stream with a controller ({pad.match})")
    if not connected:
        c.present()
        return

    on = lambda n: pad.buttons.get(BTN[n], False)
    sx, sy = w / 1920, h / 1080          # laid out at 1080p, scaled
    S = lambda v: v * min(sx, sy)

    # Sticks, each a ring with a dot at its position.
    for cx, cy, (ax, ay), click, label in (
            (w * 0.22, h * 0.50, (ABS_X, ABS_Y), "L3", "left stick"),
            (w * 0.78, h * 0.50, (ABS_RX, ABS_RY), "R3", "right stick")):
        r = S(160)
        c.circle(EDGE, cx, cy, r, fill=False)
        c.circle(LIT if on(click) else DIM, cx, cy, S(26))
        px, py = pad.stick(ax, ay)
        c.circle(LIT, cx + px * r, cy + py * r, S(34))
        c.text(TEXT, cx - S(60), cy + r + S(44), label)
        c.text(TEXT, cx - S(60), cy + r + S(70), f"{px:+.2f} {py:+.2f}")

    # Triggers, as bars that fill.
    for bx, a, name in ((w * 0.04, ABS_Z, "LT"), (w * 0.94, ABS_RZ, "RT")):
        bw, bh = S(44), h * 0.36
        by = h * 0.34
        v = pad.trigger(a)
        c.rect(DIM, bx, by, bw, bh)
        c.rect(LIT, bx, by + bh * (1 - v), bw, bh * v)
        c.rect(EDGE, bx, by, bw, bh, fill=False)
        c.text(TEXT, bx, by - S(14), f"{name} {v:.2f}")

    # Shoulders.
    for bx, name in ((w * 0.14, "L1"), (w * 0.80, "R1")):
        c.rect(LIT if on(name) else DIM, bx, h * 0.30, w * 0.06, S(40))
        c.text(TEXT, bx, h * 0.30 - S(12), name)

    # D-pad, from the hat axes.
    hx, hy = pad.axes.get(ABS_HAT0X, 0), pad.axes.get(ABS_HAT0Y, 0)
    cx, cy, a = w * 0.40, h * 0.79, S(52)
    for dx, dy, lit in ((0, -1, hy < 0), (0, 1, hy > 0), (-1, 0, hx < 0), (1, 0, hx > 0)):
        c.rect(LIT if lit else DIM, cx + dx * a - a / 2, cy + dy * a - a / 2, a, a)
    c.rect(EDGE, cx - a / 2, cy - a / 2, a, a, fill=False)
    c.text(TEXT, cx - S(34), cy + S(110), "d-pad")

    # Face buttons, Xbox positions: A bottom, B right, X left, Y top.
    cx, cy, r, off = w * 0.60, h * 0.79, S(40), S(78)
    for name, dx, dy in (("A", 0, 1), ("B", 1, 0), ("X", -1, 0), ("Y", 0, -1)):
        c.circle(LIT if on(name) else DIM, cx + dx * off, cy + dy * off, r)
        c.text(BG if on(name) else TEXT, cx + dx * off - S(5), cy + dy * off + S(6), name)

    # Select / Start / Home. Home stays dark on purpose: see the note below.
    for i, name in enumerate(("SELECT", "START", "HOME")):
        bx = w * 0.42 + i * S(130)
        c.rect(LIT if on(name) else DIM, bx, h * 0.90, S(110), S(40))
        c.text(TEXT, bx + S(6), h * 0.90 + S(27), name)
    c.text("#8a97a8", w * 0.42, h * 0.90 + S(74),
           "HOME never lights: Android consumes it before Moonlight sees it")
    c.present()


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", default="1920x1080")
    ap.add_argument("--name", default="Sunshine",
                    help="substring of the input device's name")
    ap.add_argument("--fps", type=int, default=60)
    args = ap.parse_args()
    w, h = (int(v) for v in args.size.split("x"))

    c = Canvas(w, h, "JetsonTV gamepad")
    pad = Pad(args.name)
    connected = pad.open()
    period = 1.0 / args.fps
    next_scan = 0.0

    while True:
        t0 = time.monotonic()
        if connected:
            if pad.pump() is False:
                pad.close()
                connected = False
        elif t0 >= next_scan:
            connected = pad.open()
            next_scan = t0 + 1.0
        draw(c, pad, connected)
        delay = period - (time.monotonic() - t0)
        if delay > 0:
            time.sleep(delay)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
