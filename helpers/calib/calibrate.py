#!/usr/bin/env python3
"""Calibrate the four fisheyes from a recording of the printed checkerboard.

WHY THIS EXISTS. The player's dewarp rests on two numbers nobody measured:
LENS_HALF_FOV (an assumption that the lens is a perfect 180-degree equidistant
one) and SRC_SQUASH (an assumption about where the image circle sits in the
960x400 cell). Every attempt to pin them by reasoning from the 5120x800 sensor
size, or by eye off a photograph of the panel, has produced a different answer,
and the answers contradict each other. A printed board of known geometry settles
it in one shot, because the board says what the world is and the pixels say what
the lens did to it.

WHAT IT REPLACES THEM WITH. cv2.fisheye's model:

    theta_d = theta * (1 + k1*theta^2 + k2*theta^4 + k3*theta^6 + k4*theta^8)
    cell_px = (fx*theta_d*cos(phi) + cx,  fy*theta_d*sin(phi) + cy)

fx != fy absorbs the anamorphic squeeze into the cell, so SRC_SQUASH stops being
a separate guess. cx, cy absorb the image circle being off-centre, which nothing
in the current shader can express at all. k1..k4 absorb the lens being something
other than exactly equidistant.

    ./calibrate.py <clip.mp4> --square-mm 28

Run it against the clip shot per the instructions in README.md. Prints the
constants to paste into ClipPlayerActivity, and the reprojection error, which is
the number that says whether to believe them: under ~1 px is good, over ~3 px
means the board was bent, blurred, or too few poses.
"""

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np

W, H = 1920, 800
CW, CH = W // 2, H // 2
CELLS = {"front": (0, 0), "rear": (1, 0), "left": (0, 1), "right": (1, 1)}
BOARD = (9, 6)                      # inner corners


def cell_of(frame, qx, qy):
    top = (1 - qy) * CH             # qy=0 is the BOTTOM row
    return frame[top:top + CH, qx * CW:(qx + 1) * CW]


def frames(clip, every):
    """Every Nth frame, decoded straight from the container."""
    cap = cv2.VideoCapture(str(clip))
    i = 0
    while True:
        ok, f = cap.read()
        if not ok:
            break
        if i % every == 0 and f.shape[:2] == (H, W):
            yield i, f
        i += 1
    cap.release()


# The board ends up SMALL in these cells - a metre away through a fisheye that
# fits 180 degrees into 960x400 leaves squares around 13 px. The classic detector
# gives up at that size; findChessboardCornersSB finds the same board, and a 2x
# upsample rescues the classic one as a fallback. Measured on one frame of the
# 2026-08-26 shoot: raw classic=no SB=yes, 2x both yes.
SCALE = 2

# CALIB_CB_EXHAUSTIVE is what makes a full pass take the better part of an hour:
# a second or two per call, times four cells, times every sampled frame. It only
# earns that when the board is tiny. Once the squares are over ~15 px the plain
# detector finds the same boards in a fraction of the time, so --fast is the
# right choice for any shoot taken at the distance README.md asks for.
EXHAUSTIVE = True


def find(cell):
    g = cv2.cvtColor(cell, cv2.COLOR_BGR2GRAY)
    big = cv2.resize(g, None, fx=SCALE, fy=SCALE, interpolation=cv2.INTER_CUBIC)
    flags = cv2.CALIB_CB_NORMALIZE_IMAGE
    if EXHAUSTIVE:
        flags |= cv2.CALIB_CB_EXHAUSTIVE | cv2.CALIB_CB_ACCURACY
    try:
        ok, corners = cv2.findChessboardCornersSB(big, BOARD, flags)
        if ok:
            return corners / SCALE          # back into real cell pixels
    except cv2.error:
        pass
    # THE FALLBACK IS THE EXPENSIVE PATH, and it runs on every cell where SB
    # found nothing - which is most cells in most frames, since only one camera
    # has the board at a time. Adaptive thresholding a 1920x800 image costs
    # seconds, so leaving it on turned a pass that should take three minutes into
    # one that never finished. It is only worth paying when we are already being
    # thorough; SB is strictly better at everything else.
    if not EXHAUSTIVE:
        return None
    ok, corners = cv2.findChessboardCorners(
        big, BOARD, cv2.CALIB_CB_ADAPTIVE_THRESH | cv2.CALIB_CB_NORMALIZE_IMAGE)
    if not ok:
        return None
    cv2.cornerSubPix(big, corners, (5, 5), (-1, -1),
                     (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 40, 1e-3))
    return corners / SCALE


def _flag(name):
    """cv2.fisheye.X on OpenCV 4, cv2.X on 5. Fail loudly rather than silently
    calibrating with flags=0, which converges to something plausible and wrong."""
    for holder in (cv2.fisheye, cv2):
        if hasattr(holder, name):
            return getattr(holder, name)
    raise RuntimeError(f"no {name} in cv2 {cv2.__version__}")


def _shader_constants(name, fx, fy, cx, cy, rms, n, how):
    """Turn fx/fy into the two numbers ClipPlayerActivity's shader actually uses.

    The shader samples at
        cell.x = 0.5 + cos(phi) * (theta/LENS_HALF) * 0.5
        cell.y = 0.5 + sin(phi) * (theta/LENS_HALF) * 0.5 * SRC_SQUASH
    in normalised cell coordinates, so in pixels that is
        px = cos(phi) * theta * (0.5*CW/LENS_HALF)
        py = sin(phi) * theta * (0.5*CH*SRC_SQUASH/LENS_HALF)
    and an equidistant lens gives px = fx*theta*cos(phi), py = fy*theta*sin(phi).
    Equate and the two constants fall out."""
    lens_half = 0.5 * CW / fx
    squash = 2.0 * fy * lens_half / CH
    print(f"\n  {name}: {n} views, {how}, reprojection error {rms:.2f} px"
          f"{'  <-- too high' if rms > 3 else ''}")
    print(f"    fx {fx:7.2f}  fy {fy:7.2f}  cx {cx:6.1f}  cy {cy:6.1f}"
          f"   (centre off by {cx-CW/2:+.0f},{cy-CH/2:+.0f} px)")
    print(f"    -> LENS_HALF_FOV {np.degrees(lens_half):5.1f} deg   "
          f"SRC_SQUASH {squash:.3f}      (code has 90.0 / 0.667)")
    return dict(name=name, rms=float(rms), fx=float(fx), fy=float(fy),
                cx=float(cx), cy=float(cy),
                lens_half_deg=float(np.degrees(lens_half)), squash=float(squash))


def _pinhole(name, corner_sets, square_mm):
    obj = np.zeros((BOARD[0] * BOARD[1], 3), np.float32)
    obj[:, :2] = np.mgrid[0:BOARD[0], 0:BOARD[1]].T.reshape(-1, 2) * square_mm
    imgs = [np.asarray(c, dtype=np.float32).reshape(-1, 1, 2) for c in corner_sets]
    try:
        rms, K, _, _, _ = cv2.calibrateCamera([obj] * len(imgs), imgs, (CW, CH),
                                              None, None)
    except cv2.error as e:
        print(f"  {name}: pinhole fallback also failed - {str(e).splitlines()[-1][:60]}")
        return None
    return _shader_constants(name, K[0, 0], K[1, 1], K[0, 2], K[1, 2],
                             rms, len(imgs), "pinhole fallback")


def calibrate(name, corner_sets, square_mm):
    n = len(corner_sets)
    if n < 6:
        print(f"  {name}: only {n} usable views - need at least 6, ideally 15.")
        # The mirrors are the same module in mirrored brackets (Roberto,
        # 2026-08-27), so the LENS is identical and fx/fy/cx/cy/k copy across
        # unchanged - each camera images its own scene, so nothing is flipped.
        # Only the mounting angle could differ, and the table already shares
        # that. So the left mirror never has to be shot: it is the awkward one,
        # and it has come back empty from both attempts.
        if name == "left":
            print("         left can just take right's numbers - same lens, and"
                  "\n         intrinsics do not mirror. Do not reshoot it.")
        return None

    obj = np.zeros((1, BOARD[0] * BOARD[1], 3), np.float64)
    obj[0, :, :2] = np.mgrid[0:BOARD[0], 0:BOARD[1]].T.reshape(-1, 2) * square_mm
    objs = [obj] * n
    imgs = [c.astype(np.float64).reshape(1, -1, 2) for c in corner_sets]

    K = np.zeros((3, 3))
    D = np.zeros((4, 1))
    # OpenCV 5 moved these to the top level; 4.x had them under cv2.fisheye.
    flags = (_flag("CALIB_RECOMPUTE_EXTRINSIC") | _flag("CALIB_FIX_SKEW"))
    try:
        rms, K, D, *_ = cv2.fisheye.calibrate(
            objs, imgs, (CW, CH), K, D, flags=flags,
            criteria=(cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 60, 1e-6))
    except cv2.error as e:
        # THE FISHEYE SOLVER NEEDS POSES WE CANNOT EASILY SHOOT. Its extrinsics
        # init is homography-based and gives up (`fabs(norm_u1) > 0`) when every
        # view of the board sits in the same small patch of frame - which is what
        # happens here, because getting the board close enough to resolve at 30 cm
        # and getting it out to the frame corners pull against each other. With
        # the flags off it "converges" instead, to rms 1.3e12 and a negative fy.
        #
        # So fall back to the PINHOLE fit, which converges on the same corners at
        # well under 1 px. That is not a worse answer for what we need: for an
        # equidistant fisheye r = f*theta, and near the axis r ~= f*tan(theta), so
        # the pinhole focal length IS the fisheye's linear term. It gives fx, fy
        # and the centre; what it cannot give is k1..k4, the departure from
        # equidistant out at the rim, which no board in the middle of the frame
        # was ever going to constrain anyway.
        print(f"  {name}: fisheye solver failed ({str(e).splitlines()[-1][:48]}...)")
        return _pinhole(name, corner_sets, square_mm)

    return _shader_constants(name, K[0, 0], K[1, 1], K[0, 2], K[1, 2],
                             rms, n, "fisheye model")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("clip", type=Path)
    ap.add_argument("--square-mm", type=float, required=True,
                    help="MEASURED size of one square, not the nominal one")
    ap.add_argument("--every", type=int, default=10, help="use every Nth frame")
    ap.add_argument("--max-views", type=int, default=25)
    # Detection is the slow half - an exhaustive search over 2400 frames is
    # minutes - and it does not change while the calibration flags do. Cache it
    # so a mistake in the solver costs seconds to retry, not another coffee.
    ap.add_argument("--cache", type=Path, help="npz of detected corners")
    ap.add_argument("--spacing", type=int, default=20,
                    help="min frames between accepted views of one camera")
    ap.add_argument("--fast", action="store_true",
                    help="skip the exhaustive search; fine once squares are >15 px")
    args = ap.parse_args()

    global EXHAUSTIVE
    EXHAUSTIVE = not args.fast

    if args.cache and args.cache.exists():
        z = np.load(args.cache, allow_pickle=True)
        found = {n: list(z[n]) for n in CELLS}
        print(f"loaded corners from {args.cache}")
        print("board found in: " + ", ".join(f"{n} {len(v)}" for n, v in found.items()))
        results = [calibrate(n, v, args.square_mm) for n, v in found.items()]
        return 0 if any(results) else 1

    found = {n: [] for n in CELLS}
    last = {n: -999 for n in CELLS}
    for i, f in frames(args.clip, args.every):
        for name, (qx, qy) in CELLS.items():
            if len(found[name]) >= args.max_views:
                continue
            # Space the views out in TIME, not in samples. Twenty frames of the
            # board sitting still is one view as far as the solver is concerned,
            # and stacking near-duplicates makes the error look better than it
            # is. This used to be `args.every * 4`, which quietly coupled the two
            # knobs: raising --every to make a pass finish sooner also widened
            # the spacing, and a run at --every 40 kept three views out of a
            # shoot that contained plenty.
            if i - last[name] < args.spacing:
                continue
            c = find(cell_of(f, qx, qy))
            if c is not None:
                found[name].append(c)
                last[name] = i
        if all(len(v) >= args.max_views for v in found.values()):
            break

    print(f"\nboard found in: " + ", ".join(f"{n} {len(v)}" for n, v in found.items()))
    if args.cache:
        np.savez(args.cache, **{n: np.array(v, dtype=object) for n, v in found.items()})
        print(f"corners cached to {args.cache}")
    results = [calibrate(n, v, args.square_mm) for n, v in found.items()]
    results = [r for r in results if r]
    if not results:
        print("\nNothing calibrated. Check that the board fills a good part of the"
              "\nframe and is lit evenly - the detector needs all 9x6 corners.")
        return 1
    print("\nfx/fy/cx/cy and k1..k4 go straight into the shader; they replace"
          "\nLENS_HALF_FOV and SRC_SQUASH, which were never measurements.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
