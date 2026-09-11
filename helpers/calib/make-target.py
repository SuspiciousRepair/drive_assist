#!/usr/bin/env python3
"""Generate the checkerboard to print for calibrating the four fisheyes.

9x6 INNER CORNERS (10x7 squares). OpenCV wants inner corners, and an even-by-odd
square count makes the board's orientation unambiguous, so a pose can never be
detected 180 degrees out.

    python3 make-target.py          # writes checkerboard-a4.pdf and -a3.pdf

PRINT AT 100%. "Fit to page" silently rescales and every number we derive from
the board inherits the error. Measure one square with a ruler afterwards and
tell me what it actually is - that measurement is the one that matters, not the
one in the filename.
"""
from PIL import Image, ImageDraw

DPI = 300
COLS, ROWS = 10, 7          # squares; inner corners are 9 x 6


def board(page_mm, square_mm, name):
    pw, ph = (round(m / 25.4 * DPI) for m in page_mm)
    sq = round(square_mm / 25.4 * DPI)
    im = Image.new("RGB", (pw, ph), "white")
    d = ImageDraw.Draw(im)
    ox, oy = (pw - COLS * sq) // 2, (ph - ROWS * sq) // 2
    for r in range(ROWS):
        for c in range(COLS):
            if (r + c) % 2 == 0:
                d.rectangle([ox + c*sq, oy + r*sq, ox + (c+1)*sq - 1, oy + (r+1)*sq - 1],
                            fill="black")
    # A white border all round matters: the detector needs quiet space outside
    # the outermost squares or it will not find the board at all.
    d.rectangle([ox, oy, ox + COLS*sq - 1, oy + ROWS*sq - 1], outline="black", width=3)
    d.text((ox, oy + ROWS*sq + 18),
           f"9x6 inner corners, {square_mm} mm squares - PRINT AT 100%, DO NOT SCALE",
           fill="black")
    im.save(name, "PDF", resolution=DPI)
    print(f"{name}: {COLS}x{ROWS} squares of {square_mm} mm "
          f"= {COLS*square_mm} x {ROWS*square_mm} mm of ink")


if __name__ == "__main__":
    board((297, 210), 28, "checkerboard-a4.pdf")
    board((420, 297), 40, "checkerboard-a3.pdf")
