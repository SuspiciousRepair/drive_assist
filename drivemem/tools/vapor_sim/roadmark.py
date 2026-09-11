"""Lighter road markings, and a verge that actually reads as rough ground.

Two open questions, tested together:

  "lighter"  -- weight (thinner, less alpha) or colour (paler toward white)?
                Both are rendered; pick from the picture.

  roughness  -- the first attempt (road.py F4) hashed each verge cell
                independently and dimmed it to 0.45 alpha. It was invisible and
                cost 11k polyline points. Here the noise is COHERENT along the
                column direction, so it reads as ridges instead of static, and
                nothing is dimmed -- dimming the verge is what emptied the scene
                in F1/F2.
"""
import math
from PIL import Image, ImageDraw, ImageFont
import sim

GRID_ROWS = 120
COLSTEP = sim.groundH * sim.CELL
ROAD_I = 8

SHIPPED_C, SHIPPED_W = (0xFF, 0x9F, 0xD2), 3     # what is on the car today


def ground_at(y):
    t = max(0.0, min(1.0, (y - sim.horizon) / sim.groundH))
    return sim.blend(sim.GROUND_T, sim.GROUND_B, t)


def over(base, col, a):
    return sim.blend(base, col, max(0.0, min(1.0, a / 255.0)))


def h2(a, b):
    n = (a * 73856093) ^ (b * 19349663)
    n = (n ^ (n >> 13)) & 0x7FFFFFFF
    return ((n % 2003) / 2003.0) * 2.0 - 1.0


def terrain(i, row):
    """Two octaves of interpolated value noise across columns, changing slowly
    along z, so the verge reads as ridges running beside the road rather than
    per-cell fuzz. Independent hashes were the reason F4 looked like noise."""
    def octave(wavelength, amp):
        x = abs(i) / wavelength
        x0 = int(math.floor(x))
        t = x - x0
        t = t * t * (3 - 2 * t)                      # smoothstep
        r0 = h2(x0 if i > 0 else -x0, row // 4)
        r1 = h2((x0 + 1) if i > 0 else -(x0 + 1), row // 4)
        return (r0 + (r1 - r0) * t) * amp
    return octave(5.0, 1.0) + octave(1.9, 0.38)


def draw_grid(d, road_c, road_w, road_a, amp):
    near = 1.0 - sim.CELL
    pts_drawn = 0
    for k in range(1, GRID_ROWS + 1):
        z = k * sim.CELL
        if z <= 0.02:
            continue
        y = sim.horizon + sim.f * sim.CAM_Y / z
        if y > sim.H:
            continue
        a = min(225.0, 225.0 * near / z)
        t = (y - sim.horizon) / sim.groundH
        half = ROAD_I * COLSTEP * t
        col = over(ground_at(y), sim.GRID_C, a)
        # road surface: dead flat, full strength
        d.line([(max(0, sim.cx - half), y), (min(sim.W, sim.cx + half), y)], fill=col, width=1)
        if amp <= 0:
            d.line([(0, y), (max(0, sim.cx - half), y)], fill=col, width=1)
            d.line([(min(sim.W, sim.cx + half), y), (sim.W, y)], fill=col, width=1)
            continue
        # verge: same strength, riding terrain height (falls off as 1/z on its own)
        for side in (-1, 1):
            pts, i = [], ROAD_I
            while True:
                x = sim.cx + side * i * COLSTEP * t
                if x < -60 or x > sim.W + 60 or i > 70:
                    break
                pts.append((x, y + sim.f * (terrain(side * i, k) * amp) / z))
                i += 1
            if len(pts) > 1:
                d.line(pts, fill=col, width=1)
                pts_drawn += len(pts)
    for i in range(-60, 61):
        a = max(55, 170 - abs(i) * 3)
        d.line([(sim.cx, sim.horizon), (sim.cx + i * COLSTEP, sim.H)],
               fill=over(ground_at(sim.H * 0.8), sim.GRID_C, a), width=1)
    for s in (-1, 1):
        d.line([(sim.cx, sim.horizon), (sim.cx + s * ROAD_I * COLSTEP, sim.H)],
               fill=over(ground_at(sim.H * 0.8), road_c, road_a), width=road_w)
    return pts_drawn


def panel(title, note, road_c, road_w, road_a, amp):
    img = Image.new("RGB", (sim.W, sim.H))
    d = ImageDraw.Draw(img)
    for y in range(int(sim.horizon)):
        t = y / max(1.0, sim.horizon)
        d.line([(0, y), (sim.W, y)],
               fill=sim.blend(sim.SKY_MID, sim.SKY_LOW, min(1.0, t * 1.25)) if t < 0.8
               else sim.blend(sim.SKY_LOW, sim.SKY_HOR, (t - 0.8) / 0.2))
    sunR = min(sim.W * 0.17, sim.horizon * 0.62)
    sunCy = sim.horizon - sunR * 0.28
    for n in range(int(sunR * 2)):
        y = sunCy - sunR + n
        if y > sim.horizon:
            break
        hh = math.sqrt(max(0.0, sunR * sunR - (y - sunCy) ** 2))
        d.line([(sim.cx - hh, y), (sim.cx + hh, y)],
               fill=sim.blend((255, 232, 109), (255, 94, 58), n / (sunR * 2)))
    for y in range(int(sim.horizon), sim.H):
        d.line([(0, y), (sim.W, y)], fill=ground_at(y))
    n = draw_grid(d, road_c, road_w, road_a, amp)
    import wedge, tail, road
    v, f = wedge.build_wedge(sim.ZR)
    road.draw_car_ao(d, v, f)
    tail.draw_split(d, sim.ZR)
    big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 42)
    sm = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 29)
    d.text((36, 26), title, font=big, fill=(255, 255, 255))
    d.text((36, 80), note, font=sm, fill=(255, 220, 120))
    return img, n


VARIANTS = [
    ("A  shipped today", "#FF9FD2 w3 a255 - verge dead flat", SHIPPED_C, 3, 255, 0.0),
    ("B  lighter WEIGHT + ridges", "same hue, w2 a140 - terrain 0.09", SHIPPED_C, 2, 140, 0.09),
    ("C  lighter COLOUR + ridges", "#FFE2F1 w2 a190 - terrain 0.09", (0xFF, 0xE2, 0xF1), 2, 190, 0.09),
    ("D  lighter WEIGHT + strong ridges", "same as B - terrain 0.17", SHIPPED_C, 2, 140, 0.17),
]

imgs = []
for title, note, c, w, a, amp in VARIANTS:
    im, n = panel(title, note, c, w, a, amp)
    imgs.append(im)
    print(f"{title:<36} verge polyline points/frame: {n}")

g = Image.new("RGB", (sim.W, sim.H + 12), (10, 10, 14))
g = Image.new("RGB", (sim.W, (sim.H // 2) * 2 + 12), (10, 10, 14))
for n, im in enumerate(imgs[:2]):
    g.paste(im.resize((sim.W // 2, sim.H // 2), Image.LANCZOS), ((n % 2) * (sim.W // 2), 0))
for n, im in enumerate(imgs[2:]):
    g.paste(im.resize((sim.W // 2, sim.H // 2), Image.LANCZOS), ((n % 2) * (sim.W // 2), sim.H // 2 + 12))
g.save("roadmark.png")
print("today's grid costs 237 drawLine calls in total, for reference")
print("wrote roadmark.png")
