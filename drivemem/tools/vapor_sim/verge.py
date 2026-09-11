"""No road markings. The verge is rough NEAR THE CAMERA and flattens with
distance, so the road is described by the contrast between rough and smooth
rather than by paint — and the far field, which was nearly all the cost, is
never displaced at all.

Amplitude falls to zero by Z_FADE. Rows past it are drawn exactly as today, so
the saving is not an approximation: those rows run the original code path.
"""
import math
from PIL import Image, ImageDraw, ImageFont
import sim, roadmark as rm, roadmark2 as rm2

AMP = 0.12


def fade(z, z_fade):
    """1 at the nearest ground, 0 at z_fade, smoothstepped between."""
    z0 = 1.0 - sim.CELL                       # depth of the nearest visible row
    if z >= z_fade:
        return 0.0
    t = (z_fade - z) / max(1e-6, z_fade - z0)
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def draw_grid(d, z_fade):
    near = 1.0 - sim.CELL
    rows, pts = [], 0
    for k in range(1, rm.GRID_ROWS + 1):
        z = k * sim.CELL
        if z <= 0.02:
            continue
        y = sim.horizon + sim.f * sim.CAM_Y / z
        if y > sim.H:
            continue
        rows.append((k, z, y, fade(z, z_fade)))
    for (k, z, y, fz) in rows:
        a = min(225.0, 225.0 * near / z)
        t = (y - sim.horizon) / sim.groundH
        col = rm.over(rm.ground_at(y), sim.GRID_C, a)
        if fz <= 0.001:                        # untouched: one full-width line, as today
            d.line([(0, y), (sim.W, y)], fill=col, width=1)
            continue
        half = rm.ROAD_I * rm.COLSTEP * t
        d.line([(max(0, sim.cx - half), y), (min(sim.W, sim.cx + half), y)], fill=col, width=1)
        for side in (-1, 1):
            p, i = [], rm.ROAD_I
            while True:
                x = sim.cx + side * i * rm.COLSTEP * t
                if x < -60 or x > sim.W + 60 or i > 70:
                    break
                p.append((x, y + sim.f * rm2.height(side * i, k, AMP * fz) / z))
                i += 1
            if len(p) > 1:
                d.line(p, fill=col, width=1)
                pts += len(p)
    for i in range(-60, 61):
        a = max(55, 170 - abs(i) * 3)
        col = rm.over(rm.ground_at(sim.H * 0.8), sim.GRID_C, a)
        if abs(i) <= rm.ROAD_I:
            d.line([(sim.cx, sim.horizon), (sim.cx + i * rm.COLSTEP, sim.H)], fill=col, width=1)
            continue
        p = [(sim.cx, sim.horizon)]
        moved = False
        for (k, z, y, fz) in rows:
            t = (y - sim.horizon) / sim.groundH
            dy = sim.f * rm2.height(i, k, AMP * fz) / z if fz > 0.001 else 0.0
            if fz > 0.001:
                moved = True
                pts += 1
            p.append((sim.cx + i * rm.COLSTEP * t, y + dy))
        # a column that never leaves the plane is still the straight line of today
        d.line(p if moved else [(sim.cx, sim.horizon), (sim.cx + i * rm.COLSTEP, sim.H)],
               fill=col, width=1)
    return pts, sum(1 for r in rows if r[3] > 0.001), len(rows)


def panel(title, note, z_fade):
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
        d.line([(0, y), (sim.W, y)], fill=rm.ground_at(y))
    pts, live, total = draw_grid(d, z_fade)
    import wedge, tail, road
    v, f = wedge.build_wedge(sim.ZR)
    road.draw_car_ao(d, v, f)
    tail.draw_split(d, sim.ZR)
    big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 42)
    sm = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 29)
    d.text((36, 26), title, font=big, fill=(255, 255, 255))
    d.text((36, 80), note, font=sm, fill=(255, 220, 120))
    return img, pts, live, total


V = [("K   ridge fades by z=2.5", 2.5), ("L   ridge fades by z=4.0", 4.0),
     ("M   ridge fades by z=6.0", 6.0)]
imgs = []
for title, zf in V:
    y_at = sim.horizon + sim.f / zf
    note = f"no road markings - rough ground ends at y={y_at:.0f} of {sim.H}"
    im, pts, live, total = panel(title, note, zf)
    imgs.append(im)
    print(f"{title:<28} rows displaced {live:>3}/{total}   points/frame {pts:>6}"
          f"   (E was 24432, today 237 drawLine)")
g = Image.new("RGB", (sim.W // 2, (sim.H // 2) * 3 + 24), (10, 10, 14))
for n, im in enumerate(imgs):
    g.paste(im.resize((sim.W // 2, sim.H // 2), Image.LANCZOS), (0, n * (sim.H // 2 + 12)))
g.save("verge.png")
print("wrote verge.png")
