#!/usr/bin/env python3
"""
This machine's clock minus the Jetson's, in milliseconds, for StripClock.

Asks the Jetson for $EPOCHREALTIME many times over one persistent adb shell
and keeps the sample with the shortest round trip, whose midpoint is the best
estimate. Prints the offset and the round trip it came from (the error is at
most half of that). ADB overrides the adb command, as for moonlight_prefs.py.
"""

import os
import shlex
import subprocess
import time

adb = shlex.split(os.environ.get("ADB", "adb"))
p = subprocess.Popen(adb + ["shell"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
                     bufsize=1)
best = None
for _ in range(60):
    t0 = time.time()
    p.stdin.write("echo $EPOCHREALTIME\n")
    p.stdin.flush()
    line = p.stdout.readline()
    t1 = time.time()
    try:
        jet = float(line)
    except ValueError:
        continue
    rtt = t1 - t0
    if best is None or rtt < best[0]:
        best = (rtt, (t0 + t1) / 2 - jet)
    time.sleep(0.02)
p.stdin.write("exit\n")
p.stdin.flush()
p.wait(timeout=5)
print(f"{best[1] * 1000:.1f} {best[0] * 1000:.1f}")
