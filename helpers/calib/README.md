# Calibrating the four fisheyes

The player's dewarp rested on two numbers nobody ever measured — a lens assumed
to be exactly 180° equidistant, and an image circle assumed to sit in a
particular place inside the 960×400 cell. Deriving them from the sensor size, and
reading them off photographs of the panel, gave different and mutually
contradictory answers over several days. A printed board of known geometry ends
that: the board says what the world is, the pixels say what the lens did to it.

## 1. Print the board

`checkerboard-a3.pdf` (40 mm squares) if you can print A3, otherwise
`checkerboard-a4.pdf` (28 mm). A3 buys margin on the distance below; A4 works
provided you get genuinely close.

**Print at 100%.** "Fit to page" rescales silently and every number downstream
inherits the error. Then **measure one square with a ruler** and use that figure,
not the one in the filename.

Mount it on something rigid and flat — foam board, a clipboard, a stiff folder.
A board that bows by a couple of millimetres is a board that reports a lens
distortion that isn't there.

**Closer than feels right.** This lens fits 180° into 960 px — about 5.3 px per
degree — so distance is punishing in a way an ordinary camera never is. For the
A4 sheet's 28 mm squares:

| held at | square | board fills | |
|---|---|---|---|
| 25 cm | 34 px | 312 px of 960 | good |
| **30 cm** | **28 px** | **267 px of 960** | **good** |
| 35 cm | 24 px | 233 px | marginal |
| 50 cm | 17 px | 167 px | too far |
| 1.0 m | 9 px | 85 px | hopeless |

**A4's long side is 29.7 cm, so hold the sheet about its own length from the
lens.** No tape measure needed, and at that distance it fills roughly a quarter
of the camera's width — which is what to look for on the car's own 360 screen
while positioning it.

The first attempt (2026-08-26) was shot at roughly a metre, mostly laid flat on
the ground. Squares came out **6–9.5 px** and the detector found ten frames in
the whole clip. Nothing could be calibrated from it.

If the board looks soft that close, back off to 40 cm and accept ~21 px rather
than shooting a blurred one — but check first, since these lenses have huge
depth of field and almost certainly hold focus at 30 cm.

## 2. Shoot it

Recording captures all four cameras at once, so one clip covers the lot.

1. Park somewhere evenly lit. Open shade beats direct sun: no glare, no hard
   shadow across the board.
2. Start recording in the app.
3. **Three cameras, not four: right mirror, front, rear.** The two mirrors are
   the same module in mirrored brackets, so the lens is identical and
   `fx, fy, cx, cy, k1..k4` copy from right to left unchanged — nothing is
   flipped, because each camera images its own scene rather than the same one.
   Only the mounting angle could differ, and the table shares that between them
   already. Skip the left one; it is the awkward one to reach and it has come
   back with nothing usable from both attempts.

4. For **each** of those, hold the board **30 cm from the lens** and work through
   roughly 15 poses:
   - centred, filling as much of the frame as you can;
   - pushed into each corner of the frame, one at a time;
   - tilted maybe 30–40° left, right, up and down.
   **The corners and the tilts are the whole point.** A fisheye's distortion
   lives at the edge of the frame, and a board only ever shown flat and centred
   leaves it unmeasured — the solver will happily return confident nonsense.
   Every detection in the first attempt sat between x=343 and x=469 of 960, so
   even had they been sharp enough they would all have been saying the same
   thing about the same small patch of lens.
5. Hold each pose still for ~2 seconds. Motion blur loses the corners.
6. Stop recording.

Two extra shots that pay for themselves, per camera:

- the board lying **flat on the ground**;
- the board held **vertical and level**, face-on.

Those pin the mounting angle, which is the other thing we have only ever
estimated.

## 3. Run it

```bash
cd ~/dev/geely
./.venv-calib/bin/python drive_assist/helpers/calib/calibrate.py <clip.mp4> --square-mm 28
```

Reprojection error under ~1 px is good. Over ~3 px means the board was bent,
blurred, or shown in too few genuinely different poses — reshoot rather than use
it, because a bad calibration is harder to spot later than no calibration.

## What comes out

`fx, fy, cx, cy` and `k1..k4` per camera. These **replace** `LENS_HALF_FOV` and
`SRC_SQUASH` rather than correcting them:

- `fx ≠ fy` absorbs the anamorphic squeeze into the cell, so the squash stops
  being a separate guess;
- `cx, cy` absorb the image circle being off-centre — something the current
  shader cannot express at all, and a candidate for distortion that survived
  every adjustment to the two constants;
- `k1..k4` absorb the lens not being exactly equidistant.

After that the framing numbers in the table mean what they say, and picking them
is a matter of taste rather than a search for a bug.
