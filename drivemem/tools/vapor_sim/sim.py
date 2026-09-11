"""Faithful CPU port of VaporArtView's car pipeline, for comparing hulls off-car.

Ports exactly: px/py projection, buildCar's world assembly, drawCar's average-z
painter sort, the normal-from-verts-0-1-2 shading, Style.blend and the 1.6px
neon stroke on every face. Sky/ground/grid are approximations of the real
shaders -- only there to give the geometry its context.
"""
import math
from PIL import Image, ImageDraw, ImageFont

# ---- constants, verbatim from VaporArtView.java ----
CELL = 0.175
CAM_Y = 1.0
VP_X = 0.74
HORIZON_Y = 0.46
ZK = (1.0 - CELL) / 0.5           # 1.65
ZR, ZF = 0.86 * ZK, 2.10 * ZK     # 1.419, 3.465
WB, WC = 0.46, 0.30
Y0, Y1, Y2 = 0.10, 0.32, 0.46
WHEEL_R = 0.13
WHEEL_SEG = 8

NEON = (0x00, 0xD9, 0xFF)
CAR_DARK = (0x0A, 0x10, 0x30)
CAR_LIT = (0x2A, 0x4E, 0x9E)
TAIL = (0xFF, 0x2D, 0x55)
SKY_MID, SKY_LOW, SKY_HOR = (0x7A, 0x2A, 0x8C), (0xFF, 0x6F, 0xA5), (0x8F, 0xE3, 0xFF)
GROUND_T, GROUND_B = (0x2A, 0x0A, 0x48), (0x06, 0x00, 0x0E)
GRID_C = (0xFF, 0x2D, 0x95)

W, H = 1920, 1080
horizon = H * HORIZON_Y
groundH = H - horizon
cx = W * VP_X
f = groundH * (1.0 - CELL) / CAM_Y


def px(x, z):
    return cx + f * x / z


def py(y, z):
    return horizon + f * (CAM_Y - y) / z


def blend(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


# ---- the shipped hull: buildCar() + FACES, transcribed ----
SHIPPED_FACES = [
    [0, 1, 2, 3], [5, 4, 7, 6], [4, 0, 3, 7], [1, 5, 6, 2], [3, 2, 6, 7], [4, 5, 1, 0],
    [8, 9, 10, 11], [13, 12, 15, 14], [12, 8, 11, 15], [9, 13, 14, 10], [11, 10, 14, 15],
    [16, 17, 18, 19, 20, 21, 22, 23],
    [24, 25, 26, 27, 28, 29, 30, 31],
]


def build_shipped(zr):
    v = [None] * 32
    zf = zr + (ZF - ZR)

    def s(i, x, y, z):
        v[i] = (x, y, z)

    s(0, -WB, Y0, zr); s(1, WB, Y0, zr); s(2, WB, Y1, zr); s(3, -WB, Y1, zr)
    s(4, -WB, Y0, zf); s(5, WB, Y0, zf); s(6, WB, Y1, zf); s(7, -WB, Y1, zf)
    s(8, -WC, Y1, zr + 0.34); s(9, WC, Y1, zr + 0.34)
    s(10, WC, Y2, zr + 0.62); s(11, -WC, Y2, zr + 0.62)
    s(12, -WC, Y1, zf - 0.34); s(13, WC, Y1, zf - 0.34)
    s(14, WC, Y2, zf - 0.68); s(15, -WC, Y2, zf - 0.68)
    for base, x in ((16, -WB - 0.012), (24, WB + 0.012)):
        for i in range(WHEEL_SEG):
            a = math.pi * 2 * i / WHEEL_SEG
            s(base + i, x, WHEEL_R + WHEEL_R * math.sin(a), zr + 0.30 + WHEEL_R * math.cos(a))
    return v, SHIPPED_FACES


# ---- the proposed hull, verbatim from the OBJ (1-indexed -> 0-indexed) ----
PROPOSED_V = [
    (-0.8, 0.2, 3.0), (0.8, 0.2, 3.0), (-0.8, 0.5, 3.0), (0.8, 0.5, 3.0),
    (-0.7, 0.6, 3.3), (0.7, 0.6, 3.3), (-0.8, 0.2, 3.3), (0.8, 0.2, 3.3),
    (-0.5, 1.0, 3.8), (0.5, 1.0, 3.8), (-0.8, 0.2, 3.8), (0.8, 0.2, 3.8),
    (-0.5, 1.0, 4.8), (0.5, 1.0, 4.8), (-0.8, 0.2, 4.8), (0.8, 0.2, 4.8),
    (-0.7, 0.4, 5.5), (0.7, 0.4, 5.5), (-0.7, 0.2, 5.5), (0.7, 0.2, 5.5),
    (-0.85, 0.0, 3.1), (-0.60, 0.0, 3.1), (-0.85, 0.2, 3.1), (-0.60, 0.2, 3.1),
    (0.60, 0.0, 3.1), (0.85, 0.0, 3.1), (0.60, 0.2, 3.1), (0.85, 0.2, 3.1),
]
PROPOSED_F = [[i - 1 for i in q] for q in [
    [1, 2, 4, 3], [3, 4, 6, 5], [5, 6, 10, 9], [9, 10, 14, 13],
    [1, 7, 5, 3], [2, 4, 6, 8], [7, 11, 9, 5], [8, 6, 10, 12],
    [11, 15, 13, 9], [12, 10, 14, 16], [15, 19, 17, 13], [16, 14, 18, 20],
    [21, 22, 24, 23], [25, 26, 28, 27],
]]

# tapered panels that are not planar -> split into triangles for the fixed build
TAPERED = {4, 5, 6, 7, 10, 11}


def build_proposed(zr, fix):
    """fix=False: the table as given. fix=True: x,y scaled 0.5, z remapped onto
    zr + t*(ZF-ZR), tapered quads triangulated."""
    if not fix:
        return list(PROPOSED_V), [list(q) for q in PROPOSED_F]
    z0, z1 = 3.0, 5.5
    span = ZF - ZR
    v = [(x * 0.5, y * 0.5, zr + (z - z0) / (z1 - z0) * span) for (x, y, z) in PROPOSED_V]
    faces = []
    for i, q in enumerate(PROPOSED_F):
        if i in TAPERED and len(q) == 4:
            faces.append([q[0], q[1], q[2]])
            faces.append([q[0], q[2], q[3]])
        else:
            faces.append(list(q))
    return v, faces


# ---- drawCar(), ported ----
def draw_car(d, verts, faces, stroke=1.6):
    sx = [px(v[0], v[2]) for v in verts]
    sy = [py(v[1], v[2]) for v in verts]
    order = sorted(range(len(faces)),
                   key=lambda i: sum(verts[k][2] for k in faces[i]) / len(faces[i]),
                   reverse=True)                       # back to front
    degenerate = 0
    for fi in order:
        fc = faces[fi]
        a = [verts[fc[1]][k] - verts[fc[0]][k] for k in range(3)]
        b = [verts[fc[2]][k] - verts[fc[0]][k] for k in range(3)]
        n = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
        nl = math.sqrt(sum(c * c for c in n))
        if nl < 1e-6:
            continue
        n = [c / nl for c in n]
        c = [sum(verts[k][j] for k in fc) / len(fc) for j in range(3)]
        v0 = [-c[0], CAM_Y - c[1], -c[2]]
        vl = math.sqrt(sum(t * t for t in v0))
        v0 = [t / vl for t in v0]
        facing = sum(n[i] * v0[i] for i in range(3))
        if facing < 0:
            facing = -facing
            n = [-t for t in n]
        lit = max(0.0, n[1]) * 0.55 + facing * 0.45
        poly = [(sx[k], sy[k]) for k in fc]
        ys = [p[1] for p in poly]
        if max(ys) - min(ys) < 0.5:
            degenerate += 1
        d.polygon(poly, fill=blend(CAR_DARK, CAR_LIT, min(1.0, lit)))
        d.line(poly + [poly[0]], fill=NEON, width=max(1, round(stroke)))
    return degenerate


def draw_scene(d):
    for y in range(int(horizon)):
        t = y / max(1.0, horizon)
        col = blend(SKY_MID, SKY_LOW, min(1.0, t * 1.25)) if t < 0.8 else blend(SKY_LOW, SKY_HOR, (t - 0.8) / 0.2)
        d.line([(0, y), (W, y)], fill=col)
    for y in range(int(horizon), H):
        t = (y - horizon) / groundH
        d.line([(0, y), (W, y)], fill=blend(GROUND_T, GROUND_B, t))
    n = 0
    while True:                                        # horizontals at z = (1-CELL) + n*CELL
        z = (1.0 - CELL) + n * CELL
        y = py(0.0, z)
        if y <= horizon + 0.6:
            break
        d.line([(0, y), (W, y)], fill=GRID_C, width=1)
        n += 1
    for i in range(-60, 61):                           # verticals at x = i*CELL
        x_w = i * CELL
        a = (px(x_w, 1.0 - CELL), py(0.0, 1.0 - CELL))
        b = (px(x_w, 40.0), py(0.0, 40.0))
        d.line([a, b], fill=GRID_C, width=1)
    d.line([(0, horizon), (W, horizon)], fill=SKY_HOR, width=2)


# ---- what the panel actually SHOWS -------------------------------------------
# Measured on the unit, 2026-08-09. The art full-bleeds 1920x1080, but the column
# of controls sits on top of it and the system nav bar covers the bottom strip.
# Anything outside this is drawn every frame and never seen.
#
# This cost two wrong picks in a row before anyone noticed. ROAD_I=8 put the
# whole rough verge off-screen right and under the controls on the left — the
# panel looked completely unchanged. Then the verge amplitude was tuned against
# a near field that the nav bar hides, which is where the displacement is
# largest. A simulator that treats all 1920x1080 as open ground will keep
# flattering every number that depends on where the edges are. Call mask_chrome()
# before judging anything positional.
CHROME_LEFT = 640      # control column covers the art left of this
CHROME_BOT  = 967      # system nav bar starts here (~113 px tall)


def mask_chrome(d, label=True):
    d.rectangle([0, 0, CHROME_LEFT, H], fill=(12, 12, 18))
    d.rectangle([0, CHROME_BOT, W, H], fill=(238, 242, 246))
    if label:
        try:
            fnt = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 22)
            d.text((16, CHROME_BOT + 42), "system nav bar", font=fnt, fill=(90, 95, 105))
            d.text((16, H // 2), "control column", font=fnt, fill=(120, 120, 135))
        except OSError:
            pass


def panel(verts, faces, title, note):
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    draw_scene(d)
    deg = draw_car(d, verts, faces)
    try:
        big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 46)
        small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 32)
    except OSError:
        big = small = ImageFont.load_default()
    d.text((40, 34), title, font=big, fill=(255, 255, 255))
    d.text((40, 96), note, font=small, fill=(255, 220, 120))
    return img, deg


def main():
    shipped_v, shipped_f = build_shipped(ZR)
    prop_v, prop_f = build_proposed(ZR, fix=False)
    fixed_v, fixed_f = build_proposed(ZR, fix=True)

    panels = [
        panel(shipped_v, shipped_f, "A - shipped hull (buildCar, carZ = ZR)",
              "13 faces, 32 verts - reference"),
        panel(prop_v, prop_f, "B - proposed OBJ, verbatim",
              "14 faces, 28 verts - z 3.0..5.5 absolute, roof y = 1.0 = CAM_Y"),
        panel(fixed_v, fixed_f, "C - proposed OBJ, corrected",
              "x,y x0.5 - z remapped to zr + t*(ZF-ZR) - tapered quads triangulated"),
    ]
    for img, _ in panels:
        pass
    print("degenerate faces (projected height < 0.5px):",
          {n: d for n, (_, d) in zip("ABC", panels)})

    sw, sh = W // 2, H // 2
    out = Image.new("RGB", (sw, sh * 3 + 24), (10, 10, 14))
    for i, (img, _) in enumerate(panels):
        out.paste(img.resize((sw, sh), Image.LANCZOS), (0, i * (sh + 12)))
    out.save("/tmp/vapor_sim/compare.png")
    for name, (img, _) in zip("ABC", panels):
        img.crop((900, 380, 1900, 1000)).save(f"/tmp/vapor_sim/{name}.png")
    print("wrote /tmp/vapor_sim/compare.png and A/B/C.png")


if __name__ == "__main__":   # importable without re-rendering
    main()
