#!/usr/bin/env python3
"""Diff two emmc-snapshot.sh outputs: eMMC writes per partition, per process
and per uid over the interval. Usage: emmc-diff.py t0.txt t1.txt [uids.txt]
where uids.txt is `adb shell pm list packages -U`. See docs/emmc-writes.md."""
import sys, re, collections
def load(fn):
    d = {"disk": {}, "proc": {}, "uid": {}, "t": 0.0}
    sec = None
    for line in open(fn):
        line = line.rstrip("\n")
        if line.startswith("## time"):
            d["t"] = float(line.split()[2]); continue
        if line.startswith("## "):
            sec = line[3:].split()[0]; continue
        if sec == "diskstats":
            f = line.split()
            if len(f) >= 10: d["disk"][f[2]] = (int(f[7]), int(f[9]))  # write ios, sectors written
        elif sec == "procio":
            p = line.split("|", 3)
            if len(p) == 4: d["proc"][p[0]] = (p[1], int(p[2]), p[3])
        elif sec == "uidio":
            f = line.split()
            if len(f) >= 11: d["uid"][f[0]] = (int(f[4]) + int(f[8]), int(f[9]) + int(f[10]))  # write_bytes fg+bg, fsyncs
    return d
names = {}
try:
    for line in open(sys.argv[3]):
        m = re.match(r"package:(\S+) uid:(\d+)", line.strip())
        if m: names.setdefault(m.group(2), []).append(m.group(1))
except Exception: pass
a, b = load(sys.argv[1]), load(sys.argv[2])
dt = b["t"] - a["t"]
print(f"window {dt/60:.1f} min")
labels = {"mmcblk0": "whole eMMC", "mmcblk0p22": "UDA /data", "mmcblk0p16": "CAC /cache", "mmcblk0p1": "APP /", "mmcblk0p17": "vendor", "mmcblk0p18": "MSC misc", "mmcblk0p20": "MDA"}
for dev, lab in labels.items():
    if dev in a["disk"] and dev in b["disk"]:
        w = b["disk"][dev][0] - a["disk"][dev][0]; s = b["disk"][dev][1] - a["disk"][dev][1]
        if w or dev == "mmcblk0":
            kb = s / 2
            print(f"  {lab:12s} {w:6d} writes {kb:10.0f} KB  = {kb/dt*3600/1024:8.1f} MB/h  {kb/dt*86400/1024/1024:6.2f} GB/day")
print("per process (write_bytes delta, processes alive at both ends):")
rows = []
for pid, (u, w, c) in b["proc"].items():
    if pid in a["proc"] and a["proc"][pid][2] == c:
        dw = w - a["proc"][pid][1]
        if dw > 0: rows.append((dw, pid, u, c))
    elif pid not in a["proc"] and w > 0:
        rows.append((w, pid, u, c + "  (new)"))
for dw, pid, u, c in sorted(rows, reverse=True)[:20]:
    print(f"  {dw/1024:9.1f} KB  {pid:>6} {u:10s} {c}")
print("per uid (write_bytes fg+bg, fsyncs):")
rows = []
for uid, (w, fs) in b["uid"].items():
    w0, fs0 = a["uid"].get(uid, (0, 0))
    if w - w0 > 0 or fs - fs0 > 0: rows.append((w - w0, fs - fs0, uid))
for dw, dfs, uid in sorted(rows, reverse=True)[:15]:
    print(f"  {dw/1024:9.1f} KB {dfs:5d} fsync  uid {uid:6s} {','.join(names.get(uid, []))[:70]}")
