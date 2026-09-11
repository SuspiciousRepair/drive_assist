"""Round two on the verge.

Round one displaced only the horizontals, so the verticals crossed them dead
straight and the eye read a flat plane with wobbly lines on it. Height has to be
a property of the GROUND, which means both families of lines ride the same
field. And since a coarser mesh with bigger displacement reads as low-poly
terrain (r3's mountains) while costing less, sparseness is tested as a feature
rather than an economy.
"""
import math
from PIL import Image, ImageDraw, ImageFont
import sim, roadmark as rm

ROAD_C, ROAD_W, ROAD_A = (0xFF, 0xE2, 0xF1), 2, 190      # variant C's line


def height(i, row, amp):
    return rm.terrain(i, row) * amp


def draw_grid(d, amp, step, rowstep):
    """step: verge column stride. rowstep: verge row stride. Both 1 = the dense
    mesh of round one."""
    near = 1.0 - sim.CELL
    pts = 0
    rows = []
    for k in range(1, rm.GRID_ROWS + 1):
        z = k * sim.CELL
        if z <= 0.02:
            continue
        y = sim.horizon + sim.f * sim.CAM_Y / z
        if y > sim.H:
            continue
        rows.append((k, z, y))
    # --- road surface + verge horizontals ---
    for (k, z, y) in rows:
        a = min(225.0, 225.0 * near / z)
        t = (y - sim.horizon) / sim.groundH
        half = rm.ROAD_I * rm.COLSTEP * t
        col = rm.over(rm.ground_at(y), sim.GRID_C, a)
        d.line([(max(0, sim.cx - half), y), (min(sim.W, sim.cx + half), y)], fill=col, width=1)
        if k % rowstep:
            continue
        for side in (-1, 1):
            p, i = [], rm.ROAD_I
            while True:
                x = sim.cx + side * i * rm.COLSTEP * t
                if x < -60 or x > sim.W + 60 or i > 70:
                    break
                p.append((x, y + sim.f * height(side * i, k, amp) / z))
                i += step
            if len(p) > 1:
                d.line(p, fill=col, width=1); pts += len(p)
    # --- verticals: inside the road dead straight, outside riding the field ---
    for i in range(-60, 61):
        a = max(55, 170 - abs(i) * 3)
        col = rm.over(rm.ground_at(sim.H * 0.8), sim.GRID_C, a)
        if abs(i) <= rm.ROAD_I:
            d.line([(sim.cx, sim.horizon), (sim.cx + i * rm.COLSTEP, sim.H)], fill=col, width=1)
            continue
        if (abs(i) - rm.ROAD_I) % step:
            continue
        p = [(sim.cx, sim.horizon)]
        for (k, z, y) in rows:
            t = (y - sim.horizon) / sim.groundH
            p.append((sim.cx + i * rm.COLSTEP * t, y + sim.f * height(i, k, amp) / z))
        if len(p) > 1:
            d.line(p, fill=col, width=1); pts += len(p)
    for s in (-1, 1):
        d.line([(sim.cx, sim.horizon), (sim.cx + s * rm.ROAD_I * rm.COLSTEP, sim.H)],
               fill=rm.over(rm.ground_at(sim.H * 0.8), ROAD_C, ROAD_A), width=ROAD_W)
    return pts


def panel(title, note, amp, step, rowstep):
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
    n = draw_grid(d, amp, step, rowstep)
    import wedge, tail, road
    v, f = wedge.build_wedge(sim.ZR)
    road.draw_car_ao(d, v, f)
    tail.draw_split(d, sim.ZR)
    big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 42)
    sm = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 29)
    d.text((36, 26), title, font=big, fill=(255, 255, 255))
    d.text((36, 80), note, font=sm, fill=(255, 220, 120))
    return img, n


V = [
    ("E  full mesh on the height field", "both families displaced - terrain 0.09, every column/row", 0.09, 1, 1),
    ("F  low-poly verge", "every 3rd column, every 2nd row - terrain 0.20", 0.20, 3, 2),
]
imgs = []
for title, note, amp, step, rowstep in V:
    im, n = panel(title, note, amp, step, rowstep)
    imgs.append(im)
    print(f"{title:<36} verge points/frame: {n}")
g = Image.new("RGB", (sim.W // 2, (sim.H // 2) * 2 + 12), (10, 10, 14))
for n, im in enumerate(imgs):
    g.paste(im.resize((sim.W // 2, sim.H // 2), Image.LANCZOS), (0, n * (sim.H // 2 + 12)))
g.save("roadmark2.png")
print("reference: today's whole grid = 237 drawLine calls")
print("wrote roadmark2.png")
