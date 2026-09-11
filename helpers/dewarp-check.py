#!/usr/bin/env python3
"""Check ClipPlayerActivity's dewarp against ffmpeg's v360, without the car.

Three builds went out raising the front pitch from 12 to 26 because the view
kept coming back aimed at the tarmac, and every one of them made it worse: the
shader was tilting the wrong way, so the correction was the thing digging the
hole. Photographs of the panel could not tell us that - they show a garage,
which looks plausibly wrong at any pitch - and one of them I read as the orbit
when it was the raw 2x2.

So judge it against a source whose answer is known. Build a 180-degree fisheye
whose colour encodes ELEVATION, with a red line on the horizon, run both this
port of the shader and v360 over it, and report which row the horizon lands on.
Two implementations of the same geometry either agree or one is wrong.

    python3 helpers/dewarp-check.py

Needs numpy, pillow and ffmpeg. Keep this in step with FRAG_DEWARP by hand -
it is a port, not the shader itself, which is the one weakness of the method.
"""

import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

N = 800                       # square test fisheye: no anamorphic squash here,
LENS_HALF = np.pi / 2         # the squash is orthogonal to the sign question
OUT_W, OUT_H = 900, 430
HFOV, VFOV = 115.0, 55.0      # the front camera's framing


def fisheye():
    """A 180-degree fisheye of a world painted by elevation."""
    yy, xx = np.mgrid[0:N, 0:N]
    px = (xx + 0.5) / N * 2 - 1
    py = 1 - (yy + 0.5) / N * 2                    # +1 is the TOP row
    r = np.hypot(px, py)
    theta, phi = r * LENS_HALF, np.arctan2(py, px)
    up = np.sin(theta) * np.sin(phi)               # lens axis +Z, image +y = up
    elev = np.degrees(np.arcsin(np.clip(up, -1, 1)))

    img = np.zeros((N, N, 3), np.uint8)
    band = np.where(np.floor(elev / 10.0).astype(int) % 2 == 0, 40, 0)
    img[..., 0] = np.where(elev > 0, 235, 60) - band
    img[..., 1] = np.where(elev > 0, 200, 70) - band
    img[..., 2] = np.where(elev > 0, 120, 150) - band
    img[np.abs(elev) < 1.2] = (255, 0, 0)          # the horizon, to measure
    img[r > 1] = 0
    return img


def shader(src, pitch_deg, rot_deg=0.0, squash=1.0):
    """FRAG_DEWARP, ported. The order of the last three steps is the whole point:
    pitch the ray in SCREEN space, then turn the direction into the cell."""
    tanx = np.tan(np.radians(HFOV) / 2)
    tany = np.tan(np.radians(VFOV) / 2)
    yy, xx = np.mgrid[0:OUT_H, 0:OUT_W]
    p = np.stack([(xx + 0.5) / OUT_W * 2 - 1, 1 - (yy + 0.5) / OUT_H * 2], -1)

    ray = np.stack([p[..., 0] * tanx, p[..., 1] * tany, np.ones_like(p[..., 0])], -1)
    ray /= np.linalg.norm(ray, axis=-1, keepdims=True)

    cm, sm = np.cos(np.radians(pitch_deg)), np.sin(np.radians(pitch_deg))
    ray = np.stack([ray[..., 0],
                    ray[..., 1] * cm + ray[..., 2] * sm,
                    -ray[..., 1] * sm + ray[..., 2] * cm], -1)

    rr = np.arccos(np.clip(ray[..., 2], -1, 1)) / LENS_HALF
    dl = np.linalg.norm(ray[..., :2], axis=-1, keepdims=True)
    d = np.where(dl > 1e-6, ray[..., :2] / np.maximum(dl, 1e-9), 0.0)

    c, s = np.cos(np.radians(rot_deg)), np.sin(np.radians(rot_deg))
    d = np.stack([c * d[..., 0] + s * d[..., 1], -s * d[..., 0] + c * d[..., 1]], -1)

    cell = 0.5 + d * rr[..., None] * np.array([0.5, 0.5 * squash])
    u = np.clip((cell[..., 0] * N).astype(int), 0, N - 1)
    v = np.clip(((1 - cell[..., 1]) * N).astype(int), 0, N - 1)
    out = src[v, u].copy()
    out[(rr > 1) | (cell[..., 0] < 0) | (cell[..., 0] > 1)
        | (cell[..., 1] < 0) | (cell[..., 1] > 1)] = 0
    return out


def horizon(a):
    """Which row the red line landed on. Low number = high on screen = the view
    is pointing DOWN, because everything drawn is below the horizon."""
    m = (a[..., 0] > 180) & (a[..., 1] < 80) & (a[..., 2] < 80)
    rows = np.where(m.sum(axis=1) > a.shape[1] * 0.3)[0]
    return (rows.min() + rows.max()) // 2 if len(rows) else None


def v360(path, pitch_deg, tmp):
    out = tmp / f"v360_{pitch_deg}.png"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(path), "-vf",
         f"v360=fisheye:flat:ih_fov=180:iv_fov=180:h_fov={HFOV}:v_fov={VFOV}"
         f":pitch={pitch_deg}:w={OUT_W}:h={OUT_H}", str(out)],
        check=True)
    return horizon(np.asarray(Image.open(out).convert("RGB")))


def main():
    src = fisheye()
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        f = tmp / "fish.png"
        Image.fromarray(src).save(f)
        # As the car stores it: front and rear cameras are mounted upside down,
        # so the cell arrives rotated 180 and the table compensates with rot.
        flip = src[::-1, ::-1].copy()

        print(f"horizon row, out of {OUT_H}. lower on screen = looking higher up.")
        print(f"{'pitch':>6} {'v360':>8} {'shader':>8} {'rot180':>8}")
        # Only pitches under VFOV/2 - past that the horizon is off the bottom of
        # the frame and there is no red line left to measure. That is a limit of
        # the marker, not of the shader: the sides run at 34, which this cannot
        # check directly, but a sign error is a sign error at every angle.
        bad = 0
        for pitch in (0, 13, 26):
            a, b = v360(f, pitch, tmp), horizon(shader(src, pitch))
            c = horizon(shader(flip, pitch, rot_deg=180.0))
            print(f"{pitch:>6} {a:>8} {b:>8} {c:>8}")
            if None in (a, b, c) or abs(a - b) > 2 or abs(a - c) > 2:
                bad += 1
    print("\nMISMATCH - the shader is not doing v360's geometry" if bad
          else "\nagreed on every row")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
