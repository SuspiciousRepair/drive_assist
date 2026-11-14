#!/usr/bin/env python3
"""Search a CAN explorer recording for unnamed bit fields that follow a
reference value (default: the OBD2 dongle's battery temperature).

    tools/can-explore-analyze.py can-explore-*.tsv [--ref obd.batt_temp_c] [--top 25]

The recording (drivemem CanExplorer) has two kinds of lines:
  F <wall_ms> <can_id> <state> <16 hex>   a frame's 8 bytes changed
  R <wall_ms> key=value ...               reference values from the hub

For every frame, every start bit and every width in WIDTHS, in both byte
orders, the field's value is sampled at each reference time (the latest frame
at or before it). Fields are ranked by |Pearson r| against the reference.
A good candidate also gives a plausible linear decode, printed as
ref ~= scale * raw + offset.

It finds behaviour, not names: a field that tracks battery temperature during
one charge may still be something else that rose with it. Confirm a candidate
on a second recording (another charge or a drive) before trusting it.
"""
import argparse
import math
import sys
from bisect import bisect_right
from collections import defaultdict

WIDTHS = (8, 10, 12, 13, 16)


def load(paths):
    frames = defaultdict(list)          # can_id -> [(t, bytes8)]
    refs = []                           # [(t, {key: float})]
    for path in paths:
        with open(path) as f:
            for line in f:
                p = line.rstrip("\n").split("\t")
                if p[0] == "F" and len(p) == 5 and len(p[4]) == 16:
                    frames[p[2]].append((int(p[1]), bytes.fromhex(p[4])))
                elif p[0] == "R":
                    vals = {}
                    for kv in p[2:]:
                        k, _, v = kv.partition("=")
                        try:
                            vals[k] = float(v)
                        except ValueError:
                            pass
                    refs.append((int(p[1]), vals))
    for v in frames.values():
        v.sort(key=lambda x: x[0])
    refs.sort(key=lambda x: x[0])
    return frames, refs


def pearson(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    if sxx == 0 or syy == 0:
        return 0.0, 0.0, 0.0
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    r = sxy / math.sqrt(sxx * syy)
    scale = sxy / sxx
    return r, scale, my - scale * mx


def fields(word, width):
    """All (start, value) of a `width`-bit field in a 64-bit word."""
    mask = (1 << width) - 1
    for start in range(0, 65 - width):
        yield start, (word >> start) & mask


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--ref", default="obd.batt_temp_c")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--min-points", type=int, default=30)
    a = ap.parse_args()

    frames, refs = load(a.files)
    pts = [(t, v[a.ref]) for t, v in refs if a.ref in v]
    if len(pts) < a.min_points:
        sys.exit(f"only {len(pts)} reference points for {a.ref}; need {a.min_points}")
    ys = [y for _, y in pts]
    print(f"{a.ref}: {len(pts)} points, {min(ys)} .. {max(ys)}; {len(frames)} frames")
    if max(ys) - min(ys) < 2:
        print("warning: the reference barely moved; correlations will be weak or chance")

    results = []
    for cid, seq in frames.items():
        times = [t for t, _ in seq]
        sampled = []
        for t, _ in pts:
            i = bisect_right(times, t) - 1
            sampled.append(seq[i][1] if i >= 0 else None)
        keep = [(b, y) for b, y in zip(sampled, ys) if b is not None]
        if len(keep) < a.min_points:
            continue
        yk = [y for _, y in keep]
        for order in ("be", "le"):
            words = [int.from_bytes(b, "big" if order == "be" else "little") for b, _ in keep]
            for w in WIDTHS:
                cols = defaultdict(list)
                for word in words:
                    for start, val in fields(word, w):
                        cols[start].append(val)
                for start, xs in cols.items():
                    if len(set(xs)) < 3:
                        continue
                    r, scale, off = pearson(xs, yk)
                    results.append((abs(r), r, cid, order, start, w, scale, off, len(set(xs))))

    results.sort(reverse=True)
    print(f"{'|r|':>5} {'r':>6}  frame order start width  distinct  decode")
    seen = set()
    shown = 0
    for absr, r, cid, order, start, w, scale, off, distinct in results:
        # Wider fields that contain a narrower good one repeat it; show the first.
        key = (cid, order, start // 8)
        if key in seen:
            continue
        seen.add(key)
        print(f"{absr:5.3f} {r:+6.3f}  0x{cid} {order:>5} {start:5d} {w:5d} {distinct:9d}  "
              f"{a.ref} ~= {scale:.4g} * raw {off:+.4g}")
        shown += 1
        if shown >= a.top:
            break


if __name__ == "__main__":
    main()
