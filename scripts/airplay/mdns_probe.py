#!/usr/bin/env python3
"""Show what an iPhone sees when it looks for AirPlay receivers.

    ./mdns_probe.py                 # both service types, 5 s
    ./mdns_probe.py -t 10 --raw     # longer, and dump every TXT key

thebe has no avahi-browse and no python zeroconf, and the question "is the
box advertising, and with which flags" is the first one to ask whenever a
sender cannot see it. So this speaks just enough mDNS to answer that: a PTR
query for each service type, then whatever SRV/TXT/A records come back.

The TXT record is the interesting part. `sf` (or `flags`) carries the
receiver's status, and comparing ours against a real Apple device on the same
LAN is the quickest way to see whether we look the way a sender expects. As
of 9 October we advertise `0x4` where Apple hardware here advertises `0x204`;
the extra bit is not one of the well-attested ones, and our fork enforces the
PIN itself rather than relying on the flag, so this is a difference to keep
an eye on rather than a known fault.

SPDX-License-Identifier: Apache-2.0
"""

import argparse
import socket
import struct
import sys
import time

MCAST = "224.0.0.251"
PORT = 5353
SERVICES = ("_airplay._tcp.local.", "_raop._tcp.local.")

# Bits in the AirPlay `sf`/`flags` TXT value. This mapping is PARTIAL and the
# low four are the only ones well attested across implementations; anything
# else is printed as hex rather than guessed at. Do not add a name here
# without a source -- a confidently wrong label is worse than a bare number.
SF_BITS = {
    0x1: "problem-detected",
    0x2: "not-configured",
    0x4: "audio-cable-attached",
    0x8: "pin-required",
}

def encode_name(name):
    out = b""
    for label in name.rstrip(".").split("."):
        out += bytes([len(label)]) + label.encode()
    return out + b"\x00"


def read_name(buf, off):
    """Decode a possibly-compressed DNS name. Returns (name, next_offset)."""
    labels = []
    jumped = False
    nxt = off
    seen = 0
    while True:
        if off >= len(buf):
            break
        ln = buf[off]
        if ln == 0:
            off += 1
            if not jumped:
                nxt = off
            break
        if ln & 0xC0 == 0xC0:                      # pointer
            ptr = struct.unpack("!H", buf[off:off + 2])[0] & 0x3FFF
            if not jumped:
                nxt = off + 2
            off = ptr
            jumped = True
            seen += 1
            if seen > 32:                          # malformed / loop
                break
            continue
        labels.append(buf[off + 1:off + 1 + ln].decode("utf-8", "replace"))
        off += 1 + ln
        if not jumped:
            nxt = off
    return ".".join(labels), nxt


def query(sock, service):
    hdr = struct.pack("!6H", 0, 0, 1, 0, 0, 0)
    q = encode_name(service) + struct.pack("!HH", 12, 1)   # PTR, IN
    sock.sendto(hdr + q, (MCAST, PORT))


def parse(buf, found):
    try:
        _, flags, qd, an, ns, ar = struct.unpack("!6H", buf[:12])
    except struct.error:
        return
    if not (flags & 0x8000):                       # responses only
        return
    off = 12
    for _ in range(qd):
        _, off = read_name(buf, off)
        off += 4
    for _ in range(an + ns + ar):
        name, off = read_name(buf, off)
        if off + 10 > len(buf):
            return
        rtype, _cls, _ttl, rdlen = struct.unpack("!HHIH", buf[off:off + 10])
        off += 10
        rdata = buf[off:off + rdlen]
        end = off + rdlen
        if rtype == 12:                            # PTR
            tgt, _ = read_name(buf, off)
            found.setdefault(tgt, {})
        elif rtype == 33 and rdlen >= 6:           # SRV
            _pri, _w, port = struct.unpack("!HHH", rdata[:6])
            host, _ = read_name(buf, off + 6)
            e = found.setdefault(name, {})
            e["port"], e["host"] = port, host
        elif rtype == 16:                          # TXT
            txt = {}
            i = 0
            while i < len(rdata):
                ln = rdata[i]
                item = rdata[i + 1:i + 1 + ln].decode("utf-8", "replace")
                if "=" in item:
                    k, v = item.split("=", 1)
                    txt[k] = v
                i += 1 + ln
            found.setdefault(name, {}).setdefault("txt", {}).update(txt)
        elif rtype == 1 and rdlen == 4:            # A
            found.setdefault(name, {})["a"] = socket.inet_ntoa(rdata)
        off = end


def describe_sf(value):
    try:
        v = int(value, 0)
    except ValueError:
        return ""
    names = [n for bit, n in SF_BITS.items() if v & bit]
    rest = v & ~sum(SF_BITS)
    if rest:
        names.append(f"+{rest:#x} unnamed")
    return f"  <- {', '.join(names)}" if names else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-t", "--timeout", type=float, default=5.0)
    ap.add_argument("--raw", action="store_true", help="print every TXT key")
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", PORT))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                    struct.pack("4sl", socket.inet_aton(MCAST), socket.INADDR_ANY))
    sock.settimeout(0.5)

    found = {}
    for s in SERVICES:
        query(sock, s)
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        try:
            buf, _ = sock.recvfrom(9000)
        except socket.timeout:
            continue
        parse(buf, found)

    # Only entries we learned something about beyond the bare PTR.
    real = {k: v for k, v in found.items() if v}
    if not real:
        print("no AirPlay or RAOP services answered")
        return 1
    for name in sorted(real):
        e = real[name]
        print(f"\n{name}")
        if "host" in e:
            print(f"  host {e['host']}:{e.get('port')}")
        if "a" in e:
            print(f"  addr {e['a']}")
        txt = e.get("txt", {})
        for key in ("deviceid", "model", "features", "ft", "srcvers", "pk", "vv"):
            if key in txt:
                print(f"  {key:9s} {txt[key]}")
        for key in ("sf", "flags"):
            if key in txt:
                print(f"  {key:9s} {txt[key]}{describe_sf(txt[key])}")
        if args.raw:
            for k in sorted(txt):
                print(f"      {k} = {txt[k]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
