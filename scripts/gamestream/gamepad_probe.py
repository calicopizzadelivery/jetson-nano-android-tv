#!/usr/bin/env python3
"""
Read and rumble a local evdev gamepad, with no third-party modules.

Written for the far end of a Moonlight stream: with `CONTROLLER=enabled`,
Sunshine creates a virtual gamepad on this host for the client's pad, so

    gamepad_probe.py watch --seconds 30

prints what the Jetson's controller is doing *after* a full round trip
(pad -> Moonlight -> network -> Sunshine -> virtual pad), and

    gamepad_probe.py rumble

drives force feedback the other way, which Sunshine relays back to Moonlight
and the real pad. That is the only way to exercise rumble here without a
game: nothing else on the bench produces it.

thebe has no python3-evdev, evtest or fftest, hence the raw ioctls.

    gamepad_probe.py list
    gamepad_probe.py watch [--name SUBSTR] [--seconds N]
    gamepad_probe.py rumble [--name SUBSTR] [--ms N] [--strong 0..1] [--weak 0..1]

--name defaults to "Sunshine", which matches the virtual pad it creates
("Sunshine (libvirtualhid) X-Box Series Controller"). Only devices this user
can read are listed: most /dev/input nodes are root:input, and udev's uaccess
tags only some of them for the seat.

SPDX-License-Identifier: Apache-2.0
"""

import argparse
import fcntl
import glob
import os
import struct
import sys
import time

# linux/input.h
EV_SYN, EV_KEY, EV_ABS, EV_FF = 0x00, 0x01, 0x03, 0x15
FF_RUMBLE = 0x50


def _ioc(direction, typ, nr, size):
    return (direction << 30) | (size << 16) | (typ << 8) | nr


def _eviocgname(length):
    return _ioc(2, ord("E"), 0x06, length)      # _IOR('E', 0x06, len)


# struct ff_effect is 48 bytes on 64-bit: a 16-byte header (with padding so the
# union is 8-aligned) followed by a 32-byte union, sized by ff_periodic_effect.
FF_EFFECT_SIZE = 48
EVIOCSFF = _ioc(1, ord("E"), 0x80, FF_EFFECT_SIZE)   # _IOW('E', 0x80, ff_effect)

# struct input_event: struct timeval (two longs) + u16 type + u16 code + s32 value
INPUT_EVENT = "llHHi"
INPUT_EVENT_SIZE = struct.calcsize(INPUT_EVENT)

# A few names, so output reads as buttons rather than numbers. Only the ones a
# gamepad actually reports; anything else is printed as its raw code.
KEYS = {
    0x130: "A/south", 0x131: "B/east", 0x133: "X/north", 0x134: "Y/west",
    0x136: "L1", 0x137: "R1", 0x138: "L2", 0x139: "R2",
    0x13a: "Select", 0x13b: "Start", 0x13c: "Mode/Home",
    0x13d: "L3", 0x13e: "R3",
}
AXES = {
    0x00: "LeftX", 0x01: "LeftY", 0x02: "LeftTrigger",
    0x03: "RightX", 0x04: "RightY", 0x05: "RightTrigger",
    0x10: "DpadX", 0x11: "DpadY",
}


def device_name(path):
    try:
        with open(path, "rb") as fd:
            buf = bytearray(256)
            fcntl.ioctl(fd, _eviocgname(len(buf)), buf)
            return buf.split(b"\x00", 1)[0].decode("utf-8", "replace")
    except OSError:
        return None


def devices():
    out = []
    for path in sorted(glob.glob("/dev/input/event*"),
                       key=lambda p: int(p.rsplit("event", 1)[1])):
        name = device_name(path)
        if name:
            out.append((path, name))
    return out


def pick(substr):
    """The newest device whose name contains substr, case-insensitively.

    Newest, because Sunshine's virtual pad is created when the stream starts,
    so it is the last one to appear.
    """
    found = [(p, n) for p, n in devices() if substr.lower() in n.lower()]
    if not found:
        sys.exit(f"no input device matching {substr!r}. Try: {sys.argv[0]} list")
    return found[-1]


def cmd_list(args):
    for path, name in devices():
        print(f"{path:<20} {name}")


def cmd_watch(args):
    path, name = pick(args.name)
    print(f"watching {path}  [{name}]  for {args.seconds}s")
    print("press buttons / move sticks on the controller\n")
    seen_keys, seen_axes = {}, {}
    deadline = time.time() + args.seconds
    with open(path, "rb", buffering=0) as fd:
        os.set_blocking(fd.fileno(), False)
        while time.time() < deadline:
            data = fd.read(INPUT_EVENT_SIZE)
            if not data or len(data) < INPUT_EVENT_SIZE:
                time.sleep(0.004)
                continue
            _, _, typ, code, value = struct.unpack(INPUT_EVENT, data)
            if typ == EV_KEY:
                label = KEYS.get(code, f"key_0x{code:x}")
                if value == 1:
                    seen_keys[label] = seen_keys.get(label, 0) + 1
                    print(f"  {label} down")
            elif typ == EV_ABS:
                label = AXES.get(code, f"abs_0x{code:x}")
                lo, hi = seen_axes.get(label, (value, value))
                seen_axes[label] = (min(lo, value), max(hi, value))
    print("\n--- buttons seen:")
    for k, c in sorted(seen_keys.items()):
        print(f"  {k:<12} x{c}")
    if not seen_keys:
        print("  (none)")
    print("--- axis ranges:")
    for a, (lo, hi) in sorted(seen_axes.items()):
        print(f"  {a:<14} {lo:>7} .. {hi}")
    if not seen_axes:
        print("  (none)")


def cmd_rumble(args):
    path, name = pick(args.name)
    strong = max(0, min(65535, int(args.strong * 65535)))
    weak = max(0, min(65535, int(args.weak * 65535)))
    # ff_effect: type, id(-1 = allocate), direction, trigger(button,interval),
    #            replay(length,delay), then the union, here ff_rumble_effect.
    effect = struct.pack(
        "HhH" "HH" "HH" "HH",
        FF_RUMBLE, -1, 0,
        0, 0,
        args.ms, 0,
        strong, weak,
    ).ljust(FF_EFFECT_SIZE, b"\x00")
    with open(path, "r+b", buffering=0) as fd:
        buf = bytearray(effect)
        try:
            fcntl.ioctl(fd, EVIOCSFF, buf, True)
        except OSError as e:
            sys.exit(f"{path} [{name}]: uploading the effect failed: {e}\n"
                     "Does this device report FF_RUMBLE? Sunshine only creates "
                     "its virtual pad once a stream with a controller is running.")
        effect_id = struct.unpack_from("Hh", buf)[1]
        print(f"{path} [{name}]: effect {effect_id}, "
              f"strong={strong} weak={weak} for {args.ms} ms")
        fd.write(struct.pack(INPUT_EVENT, 0, 0, EV_FF, effect_id, 1))
        time.sleep(args.ms / 1000 + 0.2)
    print("sent -- the pad at the other end should have rumbled")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="verb", required=True)
    sub.add_parser("list").set_defaults(func=cmd_list)
    w = sub.add_parser("watch")
    w.add_argument("--name", default="Sunshine")
    w.add_argument("--seconds", type=int, default=30)
    w.set_defaults(func=cmd_watch)
    r = sub.add_parser("rumble")
    r.add_argument("--name", default="Sunshine")
    r.add_argument("--ms", type=int, default=1500)
    r.add_argument("--strong", type=float, default=1.0)
    r.add_argument("--weak", type=float, default=1.0)
    r.set_defaults(func=cmd_rumble)
    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
