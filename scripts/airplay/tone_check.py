#!/usr/bin/env python3
"""Is the tone actually coming out of HDMI? Record the MS2109 and say so.

    ./tone_check.py -s 14                 # record 14 s, look for 440 Hz
    ./tone_check.py -s 14 --freq 1000
    ./tone_check.py --file capture.wav    # check a recording instead

The standing lesson on this bench is that audio must be verified by
FREQUENCY, not by bytes and not by level: a byte counter on a Bluetooth
speaker once "proved" a chain that was silent, because SBC silence costs the
same bytes as signal. So this looks for the actual tone, window by window,
and reports the gaps.

Goertzel rather than an FFT because we only care about one bin, and thebe has
no numpy. Pure Python handles 48 kHz comfortably.

Exit status is 0 only if the tone is present in every window after the first,
which makes it usable as a check in a script.

SPDX-License-Identifier: Apache-2.0
"""

import argparse
import array
import math
import subprocess
import sys
import wave

DEVICE = "plughw:MS2109,0"
RATE = 48000


def goertzel(samples, rate, freq):
    """Magnitude of `freq` in `samples`, normalised to 0..1 of full scale."""
    n = len(samples)
    if n == 0:
        return 0.0
    k = int(0.5 + n * freq / rate)
    w = 2.0 * math.pi * k / n
    coeff = 2.0 * math.cos(w)
    s1 = s2 = 0.0
    for x in samples:
        s0 = x + coeff * s1 - s2
        s2, s1 = s1, s0
    power = s1 * s1 + s2 * s2 - coeff * s1 * s2
    # 2/n normalises an N-point sum; 32768 takes it to fraction of full scale.
    return (2.0 * math.sqrt(max(power, 0.0)) / n) / 32768.0


def rms(samples):
    if not samples:
        return 0.0
    return math.sqrt(sum(x * x for x in samples) / len(samples)) / 32768.0


def record(seconds, device):
    cmd = ["arecord", "-D", device, "-f", "S16_LE", "-r", str(RATE),
           "-c", "2", "-d", str(seconds), "-t", "raw", "-q"]
    p = subprocess.run(cmd, capture_output=True)
    if p.returncode != 0:
        sys.exit("arecord failed:\n" + p.stderr.decode(errors="replace"))
    return p.stdout, 2


def read_wav(path):
    with wave.open(path, "rb") as w:
        if w.getsampwidth() != 2:
            sys.exit("tone_check: need 16-bit audio")
        return w.readframes(w.getnframes()), w.getnchannels()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--seconds", type=int, default=14)
    ap.add_argument("-f", "--freq", type=float, default=440.0)
    ap.add_argument("-d", "--device", default=DEVICE)
    ap.add_argument("--file")
    ap.add_argument("--window", type=float, default=0.1, help="seconds")
    ap.add_argument("--threshold", type=float, default=0.01,
                    help="magnitude counted as 'tone present', 0..1")
    args = ap.parse_args()

    if args.file:
        raw, channels = read_wav(args.file)
    else:
        print(f"recording {args.seconds}s from {args.device} ...",
              file=sys.stderr)
        raw, channels = record(args.seconds, args.device)

    pcm = array.array("h")
    pcm.frombytes(raw[:len(raw) // 2 * 2])
    if sys.byteorder == "big":
        pcm.byteswap()
    mono = pcm[::channels] if channels > 1 else pcm

    per = int(RATE * args.window)
    nwin = len(mono) // per
    if nwin == 0:
        sys.exit("tone_check: nothing recorded")

    present, gaps, run, worst, mags = 0, 0, 0, 0, []
    for i in range(nwin):
        w = mono[i * per:(i + 1) * per]
        m = goertzel(w, RATE, args.freq)
        mags.append(m)
        if m >= args.threshold:
            present += 1
            run = 0
        else:
            run += 1
            worst = max(worst, run)
            if run == 1:
                gaps += 1

    dur = nwin * args.window
    peak = max(mags)
    mean_on = (sum(m for m in mags if m >= args.threshold) / present
               if present else 0.0)
    print(f"{dur:.1f}s captured, {args.window*1000:.0f} ms windows")
    print(f"  {args.freq:.0f} Hz present in {present}/{nwin} windows "
          f"({100.0*present/nwin:.1f}%)")
    print(f"  peak magnitude {peak:.4f}, mean while present {mean_on:.4f}")
    print(f"  overall RMS {rms(mono):.4f}")
    if gaps:
        print(f"  {gaps} gap(s), longest {worst*args.window*1000:.0f} ms")
    else:
        print("  no gaps")
    # Allow the first window to be partial/ramping.
    return 0 if present >= nwin - 1 else 1


if __name__ == "__main__":
    sys.exit(main())
