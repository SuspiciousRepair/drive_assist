"""Road corridor + rough verge, on top of the ported grid.

drawGrid today: horizontals are full-width `drawLine(0, y, w, y)`; verticals fan
from (cx, horizon) to (cx + i*colStep, h). A road is therefore just a contiguous
band of i, and the verge is everything outside it -- no new pass, same loops.
"""
import math
from PIL import Image, ImageDraw, ImageFont
import sim, wedge, tail

GRID_ROWS = 120
COLSTEP = sim.groundH * sim.CELL   # as in onSizeChanged
AMP = 0.030          # terrain height, world units -- screen offset falls off as 1/z


def ground_at(y):
    t = max(0.0, min(1.0, (y - sim.horizon) / sim.groundH))
    return sim.blend(sim.GROUND_T, sim.GROUND_B, t)


def over(base, col, a):
    return sim.blend(base, col, a / 255.0)


def h2(a, b):
    """deterministic hash -> [-1, 1]; stands in for a Java int hash"""
    n = (a * 73856093) ^ (b * 19349663)
    n = (n ^ (n >> 13)) & 0x7FFFFFFF
    return ((n % 2003) / 2003.0) * 2.0 - 1.0


def draw_road_grid(d, road_i, rough=True, scroll_row=0):
    near = 1.0 - sim.CELL
    # ---- horizontals ----
    for k in range(1, GRID_ROWS + 1):
        z = k * sim.CELL
        if z <= 0.02:
            continue
        y = sim.horizon + sim.f * sim.CAM_Y / z
        if y > sim.H:
            continue
        a = min(225.0, 225.0 * near / z)
        t = (y - sim.horizon) / sim.groundH
        half = road_i * COLSTEP * t                 # road half-width at this row
        x0, x1 = sim.cx - half, sim.cx + half
        col = over(ground_at(y), sim.GRID_C, a)
        d.line([(max(0, x0), y), (min(sim.W, x1), y)], fill=col, width=1)
        if not rough:
            continue
        # verge: same row, dimmer, displaced by terrain height (hashed on a
        # WORLD-fixed row index so it travels with the ground)
        vcol = over(ground_at(y), sim.GRID_C, a * 0.45)
        wr = k + scroll_row
        for side in (-1, 1):
            pts, i = [], road_i
            while True:
                x = sim.cx + side * i * COLSTEP * t
                if x < -40 or x > sim.W + 40:
                    break
                dy = sim.f * (h2(side * i, wr) * AMP) / z
                pts.append((x, y + dy))
                i += 1
                if i > 60:
                    break
            if len(pts) > 1:
                d.line(pts, fill=vcol, width=1)
    # ---- verticals ----
    for i in range(-60, 61):
        inside = abs(i) < road_i
        edge = abs(i) == road_i
        if not inside and not edge and i % 2:
            continue                                    # verge reads sparser
        a = max(55, 170 - abs(i) * 3)
        if edge:
            a = 255
        elif not inside:
            a = int(a * 0.5)
        top = (sim.cx, sim.horizon)
        bot = (sim.cx + i * COLSTEP, sim.H)
        col = over(ground_at(sim.H * 0.8), (0x7A, 0xFF, 0xC8) if edge else sim.GRID_C, a)
        d.line([top, bot], fill=col, width=3 if edge else 1)


def draw_car_ao(d, verts, faces):
    import solid
    fd = solid.face_data(verts, faces)
    sx = [sim.px(v[0], v[2]) for v in verts]
    sy = [sim.py(v[1], v[2]) for v in verts]
    for fi in sorted([i for i in range(len(faces)) if fd[i]],
                     key=lambda i: fd[i]["z"], reverse=True):
        fc, lit = faces[fi], fd[fi]["lit"]
        cy = sum(verts[k][1] for k in fc) / len(fc)
        lit *= 0.62 + 0.38 * max(0.0, min(1.0, (cy - sim.Y0) / (sim.Y2 - sim.Y0)))
        poly = [(sx[k], sy[k]) for k in fc]
        d.polygon(poly, fill=sim.blend(sim.CAR_DARK, sim.CAR_LIT, min(1.0, lit)))
        d.line(poly + [poly[0]], fill=sim.NEON, width=2)


def panel(road_i, rough, title, note):
    img = Image.new("RGB", (sim.W, sim.H))
    d = ImageDraw.Draw(img)
    for y in range(int(sim.horizon)):
        t = y / max(1.0, sim.horizon)
        c = sim.blend(sim.SKY_MID, sim.SKY_LOW, min(1.0, t * 1.25)) if t < 0.8 \
            else sim.blend(sim.SKY_LOW, sim.SKY_HOR, (t - 0.8) / 0.2)
        d.line([(0, y), (sim.W, y)], fill=c)
    sunR = min(sim.W * 0.17, sim.horizon * 0.62)
    sunCy = sim.horizon - sunR * 0.28
    for n in range(int(sunR * 2)):
        y = sunCy - sunR + n
        if y > sim.horizon:
            break
        half = math.sqrt(max(0.0, sunR * sunR - (y - sunCy) ** 2))
        d.line([(sim.cx - half, y), (sim.cx + half, y)],
               fill=sim.blend((255, 232, 109), (255, 94, 58), n / (sunR * 2)))
    for y in range(int(sim.horizon), sim.H):
        d.line([(0, y), (sim.W, y)], fill=ground_at(y))
    draw_road_grid(d, road_i, rough)
    v, f = wedge.build_wedge(sim.ZR)
    draw_car_ao(d, v, f)          # S4 shading: no compression, ground occlusion
    tail.draw_split(d, sim.ZR)
    big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 44)
    sm = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 30)
    d.text((40, 30), title, font=big, fill=(255, 255, 255))
    d.text((40, 88), note, font=sm, fill=(255, 220, 120))
    return img


imgs = [
    panel(4, True, "F1 - road ROAD_I = 4", "road 2.0 car widths - both edges stay on screen"),
    panel(8, True, "F2 - road ROAD_I = 8", "road 3.9 car widths - right edge exits at y=853"),
]
out = Image.new("RGB", (sim.W // 2, (sim.H // 2) * 2 + 12), (10, 10, 14))
for n, im in enumerate(imgs):
    out.paste(im.resize((sim.W // 2, sim.H // 2), Image.LANCZOS), (0, n * (sim.H // 2 + 12)))
out.save("/tmp/vapor_sim/road.png")

for ri in (4, 8):
    halfb = ri * COLSTEP
    w_car = sim.f * (2 * sim.WB) / sim.ZR
    right = sim.cx + halfb
    tex = (sim.W - sim.cx) / halfb if halfb else 9
    ye = sim.horizon + min(1.0, tex) * sim.groundH
    print(f"ROAD_I={ri}: half {halfb:.0f}px at bottom, {2*halfb/w_car:.2f} car widths, "
          f"right edge {'exits at y=%.0f' % ye if right > sim.W else 'stays on screen'}")

# cost: segments drawn per frame, today vs with a rough verge
rows = sum(1 for k in range(1, GRID_ROWS + 1)
           if k * sim.CELL > 0.02 and sim.horizon + sim.f / (k * sim.CELL) <= sim.H)
print(f"\nvisible rows: {rows}")
print(f"today       : {rows} horizontal + 121 vertical drawLine = {rows+121} calls")
seg = 0
for k in range(1, GRID_ROWS + 1):
    z = k * sim.CELL
    y = sim.horizon + sim.f / z
    if z <= 0.02 or y > sim.H:
        continue
    t = (y - sim.horizon) / sim.groundH
    for side in (-1, 1):
        i = 4
        while True:
            x = sim.cx + side * i * COLSTEP * t
            if x < -40 or x > sim.W + 40 or i > 60:
                break
            seg += 1
            i += 1
print(f"rough verge : + {seg} polyline points across {rows*2} polylines")
print("wrote /tmp/vapor_sim/road.png")
