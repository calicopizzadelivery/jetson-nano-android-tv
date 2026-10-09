#!/usr/bin/env python3
"""Move focus to a named row in a leanback UI and press it.

    ./tvnav.py list                 # what is on screen, and what has focus
    ./tvnav.py go "System"          # focus that row, then DPAD_CENTER
    ./tvnav.py focus "AirPlay"      # focus it, do not press

Leanback screens are driven by the d-pad, and pressing blind is how you end
up bonding to someone else's Bluetooth speaker. So this reads the view
hierarchy, works out how many steps away the row is, moves that far, checks
it actually landed, and only then presses.

The focused node is usually a container rather than the text itself, so a row
counts as focused when its text's vertical midpoint falls inside the focused
node's bounds.

SPDX-License-Identifier: Apache-2.0
"""

import re
import subprocess
import sys
import time

ADB = ["docker", "exec", "adbnode", "adb"]
BOUNDS = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def sh(*args, timeout=60):
    return subprocess.run(ADB + list(args), capture_output=True, text=True,
                          timeout=timeout).stdout


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/_nav.xml")
    return sh("shell", "cat", "/sdcard/_nav.xml")


def parse(xml):
    """Returns (rows, focus) where rows is [(text, ymid)] in screen order."""
    rows, focus = [], None
    for node in xml.split("<"):
        m = BOUNDS.search(node)
        if not m:
            continue
        x1, y1, x2, y2 = (int(g) for g in m.groups())
        if 'focused="true"' in node:
            # The innermost focused node wins: it is the tightest box.
            if focus is None or (y2 - y1) <= (focus[1] - focus[0]):
                focus = (y1, y2)
        t = re.search(r'text="([^"]*)"', node)
        if t and t.group(1).strip():
            rows.append((t.group(1), (y1 + y2) // 2))
    # Keep screen order, drop duplicates that share a midpoint.
    seen, out = set(), []
    for text, y in rows:
        if y not in seen:
            seen.add(y)
            out.append((text, y))
    return out, focus


def focused_index(rows, focus):
    if not focus:
        return None
    for i, (_, y) in enumerate(rows):
        if focus[0] <= y <= focus[1]:
            return i
    return None


def find(rows, want):
    want = want.lower()
    for i, (text, _) in enumerate(rows):
        if want == text.lower():
            return i
    for i, (text, _) in enumerate(rows):
        if want in text.lower():
            return i
    return None


def move_to(want, press, tries=40):
    for _ in range(tries):
        rows, focus = parse(dump())
        tgt = find(rows, want)
        if tgt is None:
            sys.exit(f"tvnav: no row matching {want!r}. On screen: "
                     + ", ".join(repr(t) for t, _ in rows[:12]))
        cur = focused_index(rows, focus)
        if cur is None:
            sh("shell", "input", "keyevent", "KEYCODE_DPAD_DOWN")
            time.sleep(0.6)
            continue
        if cur == tgt:
            print(f"focused {rows[tgt][0]!r}")
            if press:
                sh("shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
                time.sleep(1.5)
            return
        # One step per iteration, then look again. The row list counts
        # titles AND summaries, while focus moves by whole preferences, so
        # any attempt to jump abs(tgt-cur) rows overshoots on a dense screen
        # and oscillates. Stepping is slower and always converges.
        key = "KEYCODE_DPAD_DOWN" if tgt > cur else "KEYCODE_DPAD_UP"
        sh("shell", "input", "keyevent", key)
        time.sleep(0.6)
    sys.exit(f"tvnav: could not settle focus on {want!r}")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == "list":
        rows, focus = parse(dump())
        cur = focused_index(rows, focus)
        for i, (text, y) in enumerate(rows):
            print(f"{'>' if i == cur else ' '} {text}")
        return
    if cmd in ("go", "focus"):
        move_to(sys.argv[2], press=(cmd == "go"))
        return
    sys.exit(__doc__)


if __name__ == "__main__":
    main()
