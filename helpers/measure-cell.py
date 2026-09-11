#!/usr/bin/env python3
"""Measure the dvr quad's real geometry, instead of deriving it from a file size.

Two constants in ClipPlayerActivity were never measured, only inferred from
5120x800 = four 1280x800 sensors:

    SRC_SQUASH = 0.667      how much shorter the image circle is than it is wide
    +-56 degrees            how much vertical field the cell holds

Both follow from ONE guess - that the fisheye circle's diameter equals the
sensor's 1280-px width. If that guess is wrong then the squash is wrong, and a
wrong squash bends straight lines: it stretches the radial mapping unevenly, so
a kerb comes out bowed however well the view is aimed. It also puts the vertical
limit in the wrong place, which is what makes the rear and the mirrors look
impossible to lift off the tarmac.

This measures both, off a frame, by finding where the image circle actually is.
The cell corners fall outside it and read black, and that dark border is the
circle's edge. Fit an ellipse to it and everything else follows.

DO NOT SKIP THIS AND REASON IT OUT INSTEAD. On 2026-08-25 I derived, from that
same 5120x800, that SRC_SQUASH had to be 1.6 rather than 0.667 - 0.667 is a
pixel-aspect ratio where the shader wants a normalised-radius one, and the two
differ by the cell's 2.4:1. A synthetic scene agreed: at 1.6 the horizon landed
within one row of prediction and straight kerbs came out straight. It was still
wrong, because the whole chain rests on the circle spanning the sensor's width,
and the car contradicts that: under it, Frente at pitch 26 could not show a
horizon at all, and the panel plainly does. Two constants that are each other's
only evidence prove nothing. Measure the circle.

    python3 helpers/measure-cell.py <clip.mp4 | frame.png> [--at 30] [--dump DIR]

Prints, per cell, what to put in the table. --dump writes the cells out so the
horizon can be found by eye; give its distance from the cell centre back as
--horizon and it reports the mount angle to use as that camera's pitch.
"""

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

W, H = 1920, 800
CELL_W, CELL_H = W // 2, H // 2
# front bottom-left, rear bottom-right, left top-left, right top-right, with
# qy=0 the BOTTOM row - the same convention as the shader's uQuadSelect.
CELLS = {"front": (0, 0), "rear": (1, 0), "left": (0, 1), "right": (1, 1)}
DARK = 24               # below this is outside the image circle, not scene


def frame_of(path, at):
    if path.suffix.lower() in (".png", ".jpg", ".jpeg"):
        return np.asarray(Image.open(path).convert("RGB"))
    with tempfile.TemporaryDirectory() as td:
        out = Path(td) / "f.png"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-ss", str(at),
                        "-i", str(path), "-frames:v", "1", str(out)], check=True)
        return np.asarray(Image.open(out).convert("RGB"))


def cell_of(frame, qx, qy):
    # qy=0 is the bottom row, so it is the LOWER half of the array.
    top = (1 - qy) * CELL_H
    return frame[top:top + CELL_H, qx * CELL_W:(qx + 1) * CELL_W]


def edge_points(cell):
    """Where the scene gives way to the black outside the image circle.

    Only rows and columns that actually START dark contribute: a cell whose
    circle is cropped at an edge has no border there to measure, and guessing
    one is how you fit an ellipse to the cell instead of to the lens."""
    lum = np.asarray(Image.fromarray(cell).convert("L"), dtype=float)
    h, w = lum.shape
    cy, cx = (h - 1) / 2.0, (w - 1) / 2.0
    pts = []
    for y in range(h):
        lit = np.where(lum[y] > DARK)[0]
        if len(lit) == 0:
            continue
        if lit[0] > 0:
            pts.append((lit[0] - cx, y - cy))
        if lit[-1] < w - 1:
            pts.append((lit[-1] - cx, y - cy))
    for x in range(w):
        lit = np.where(lum[:, x] > DARK)[0]
        if len(lit) == 0:
            continue
        if lit[0] > 0:
            pts.append((x - cx, lit[0] - cy))
        if lit[-1] < h - 1:
            pts.append((x - cx, lit[-1] - cy))
    return np.array(pts, dtype=float)


def fit_ellipse(pts):
    """Centred axis-aligned ellipse: least squares on (x/a)^2 + (y/b)^2 = 1."""
    if len(pts) < 12:
        return None
    A = np.stack([pts[:, 0] ** 2, pts[:, 1] ** 2], axis=1)
    sol, *_ = np.linalg.lstsq(A, np.ones(len(pts)), rcond=None)
    if np.any(sol <= 0):
        return None
    return 1 / np.sqrt(sol[0]), 1 / np.sqrt(sol[1])      # a (x), b (y)


def report(name, cell, horizon_px=None):
    print(f"\n--- {name} ---")
    pts = edge_points(cell)
    fit = fit_ellipse(pts)
    if fit is None:
        print(f"  {len(pts)} border points - not enough. The circle probably fills")
        print("  the cell on every side, so nothing here bounds it. Try a frame")
        print("  with sky or an overexposed edge, or trust the corners only.")
        return
    a, b = fit
    print(f"  {len(pts)} border points, image circle {2*a:.0f} x {2*b:.0f} px"
          f" in a {CELL_W}x{CELL_H} cell")

    # The shader reaches `0.5` across and `0.5*SRC_SQUASH` up at theta=90, in
    # NORMALISED cell coordinates. Both terms are measurements, and the 0.5 is
    # as much of a guess as the squash: it says the circle spans the full cell
    # width. Report what the frame actually says about each.
    xhalf = a / CELL_W
    squash = (b / (CELL_H / 2)) / (a / (CELL_W / 2))
    print(f"  shader x term = {xhalf:.3f}     (code hard-codes 0.5)")
    print(f"  SRC_SQUASH    = {squash:.3f}     (code has 0.667)")
    print(f"  half-field    = {90*(CELL_W/2)/a:5.1f} deg across,"
          f" {90*(CELL_H/2)/b:5.1f} deg up   (code assumes 90 / 56)")
    print(f"  so pitch + vfov/2 <= {90*(CELL_H/2)/b:.0f}, not 56")

    if horizon_px is not None:
        print(f"  horizon {horizon_px:.0f} px from the cell centre"
              f"  ->  mount {90*horizon_px/b:.0f} deg below level")
        print(f"  put that in the table as this camera's pitch")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source", type=Path)
    ap.add_argument("--at", default="30", help="seconds into a clip")
    ap.add_argument("--dump", type=Path, help="write the four cells out as PNGs")
    ap.add_argument("--horizon", type=float,
                    help="vertical px from cell centre to the horizon, read off "
                         "a dumped cell; remember the cell arrives ROTATED 180, "
                         "so the horizon sits BELOW centre in the stored image")
    args = ap.parse_args()

    frame = frame_of(args.source, args.at)
    if frame.shape[:2] != (H, W):
        print(f"expected a {W}x{H} quad, got {frame.shape[1]}x{frame.shape[0]}")
        return 2

    for name, (qx, qy) in CELLS.items():
        cell = cell_of(frame, qx, qy)
        if args.dump:
            args.dump.mkdir(parents=True, exist_ok=True)
            Image.fromarray(cell).save(args.dump / f"{name}.png")
        report(name, cell, args.horizon)

    if args.dump:
        print(f"\ncells written to {args.dump}")
    print("\nThe four cells should agree on squash and half-field - they are one")
    print("lens type on one sensor. If they do not, this fit is wrong, not the")
    print("cameras.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
