"""Apply what the references actually establish: measure r3's tail-light bar,
then render the shipped hull against a variant carrying the three changes the
art direction asks for (wing, wider segmented tail, compressed lighting)."""
import math
from PIL import Image, ImageDraw, ImageFont
import sim

# ---- 1. measure the tail bar in r3 -------------------------------------------
r3 = Image.open("/tmp/vapor_sim/refs/r3.jpg").convert("RGB")
CAR = (195, 215, 395, 300)                      # car bbox, by inspection
px3 = r3.load()
rows = []
for y in range(CAR[1], CAR[3]):
    bright = [x for x in range(CAR[0], CAR[2])
              if sum(px3[x, y]) > 330 and px3[x, y][0] > 90]
    rows.append((y, len(bright), min(bright) if bright else 0, max(bright) if bright else 0))
band = [r for r in rows if r[1] > 20]
if band:
    y0, y1 = band[0][0], band[-1][0]
    x0, x1 = min(r[2] for r in band), max(r[3] for r in band)
    car_w = CAR[2] - CAR[0]
    print(f"r3 tail band: y {y0}..{y1} ({y1-y0+1} px)  x {x0}..{x1} ({x1-x0} px)")
    print(f"  bar width / car width = {(x1-x0)/car_w:.2f}")
    print(f"  bar height / car width = {(y1-y0+1)/car_w:.3f}")

w_ship = sim.f * (2 * sim.WB) / sim.ZR
ty = sim.py(sim.Y1 - 0.100, sim.ZR)
by = sim.py(sim.Y1 - 0.125, sim.ZR)
bar_w = sim.px(sim.WB * 0.70, sim.ZR) - sim.px(-sim.WB * 0.70, sim.ZR)
print(f"\nshipped drawTail: {bar_w:.0f} x {by-ty:.1f} px on a {w_ship:.0f} px car")
print(f"  bar width / car width = {bar_w/w_ship:.2f}   height/width = {(by-ty)/w_ship:.3f}")

# ---- 2. the variant hull: shipped + rear wing --------------------------------
WING_Y = sim.Y1 + 0.075          # floats above the rear deck, as in r3
WING_HALF = sim.WB * 0.86
WING_T = 0.022                   # slab thickness


def build_wedge(zr):
    v, faces = sim.build_shipped(zr)
    v = list(v)
    faces = [list(q) for q in faces]
    zb = zr + 0.10                                   # just behind the rear deck
    zf_ = zb + 0.16
    base = len(v)
    for (x, y, z) in [(-WING_HALF, WING_Y, zb), (WING_HALF, WING_Y, zb),
                      (WING_HALF, WING_Y, zf_), (-WING_HALF, WING_Y, zf_),
                      (-WING_HALF, WING_Y - WING_T, zb), (WING_HALF, WING_Y - WING_T, zb),
                      (WING_HALF, WING_Y - WING_T, zf_), (-WING_HALF, WING_Y - WING_T, zf_)]:
        v.append((x, y, z))
    b = base
    faces += [[b+0, b+1, b+2, b+3],                  # wing top
              [b+4, b+5, b+1, b+0],                  # wing rear edge
              [b+7, b+6, b+5, b+4]]                  # wing underside
    for sx in (-1, 1):                               # two struts to the deck
        x = sx * WING_HALF * 0.72
        s = len(v)
        for (yy, zz) in ((WING_Y - WING_T, zb + 0.02), (sim.Y1, zb + 0.02),
                         (sim.Y1, zb + 0.11), (WING_Y - WING_T, zb + 0.11)):
            v.append((x, yy, zz))
        faces.append([s, s+1, s+2, s+3])
    return v, faces


def draw_tail(d, zr, wide):
    """wide=False ports drawTail verbatim; wide=True widens and segments it."""
    half = sim.WB * (0.95 if wide else 0.70)   # 0.95: measured off r3
    top, bot = (sim.Y1 - 0.095, sim.Y1 - 0.135) if wide else (sim.Y1 - 0.100, sim.Y1 - 0.125)
    ty, by = sim.py(top, zr), sim.py(bot, zr)
    x0, x1 = sim.px(-half, zr), sim.px(half, zr)
    halo = (by - ty) * 1.8
    d.rounded_rectangle([x0, ty - halo, x1, by + halo], radius=halo, fill=(60, 12, 26))
    d.rounded_rectangle([x0, ty, x1, by], radius=(by - ty) * 0.5, fill=sim.TAIL)
    if wide:                                          # r3's segmented slats
        n, gap = 7, (x1 - x0) * 0.012
        seg = ((x1 - x0) - gap * (n - 1)) / n
        for i in range(n):
            sx = x0 + i * (seg + gap)
            d.rectangle([sx, ty + (by - ty) * 0.18, sx + seg, by - (by - ty) * 0.18],
                        fill=(255, 150, 175))


def draw_car(d, verts, faces, compress):
    sx = [sim.px(v[0], v[2]) for v in verts]
    sy = [sim.py(v[1], v[2]) for v in verts]
    order = sorted(range(len(faces)),
                   key=lambda i: sum(verts[k][2] for k in faces[i]) / len(faces[i]),
                   reverse=True)
    for fi in order:
        fc = faces[fi]
        a = [verts[fc[1]][k] - verts[fc[0]][k] for k in range(3)]
        b = [verts[fc[2]][k] - verts[fc[0]][k] for k in range(3)]
        n = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
        nl = math.sqrt(sum(c*c for c in n))
        if nl < 1e-6:
            continue
        n = [c/nl for c in n]
        c = [sum(verts[k][j] for k in fc)/len(fc) for j in range(3)]
        v0 = [-c[0], sim.CAM_Y-c[1], -c[2]]
        vl = math.sqrt(sum(t*t for t in v0)); v0 = [t/vl for t in v0]
        facing = sum(n[i]*v0[i] for i in range(3))
        if facing < 0:
            facing, n = -facing, [-t for t in n]
        lit = max(0.0, n[1])*0.55 + facing*0.45
        if compress:
            lit = 0.20 + lit*0.40                     # lands on r3's measured band
        poly = [(sx[k], sy[k]) for k in fc]
        d.polygon(poly, fill=sim.blend(sim.CAR_DARK, sim.CAR_LIT, min(1.0, lit)))
        d.line(poly + [poly[0]], fill=sim.NEON, width=2)


def panel(build, compress, wide, title, note):
    img = Image.new("RGB", (sim.W, sim.H))
    d = ImageDraw.Draw(img)
    sim.draw_scene(d)
    # the sun, omitted from sim.draw_scene -- it is why the roofline matters
    sunR = min(sim.W*0.17, sim.horizon*0.62)
    sunCy = sim.horizon - sunR*0.28
    for i in range(int(sunR*2)):
        t = i/(sunR*2)
        y = sunCy - sunR + i
        if y > sim.horizon:
            break
        col = sim.blend((255, 232, 109), (255, 94, 58), t)
        half = math.sqrt(max(0.0, sunR*sunR - (y-sunCy)**2))
        d.line([(sim.cx-half, y), (sim.cx+half, y)], fill=col)
    v, fcs = build(sim.ZR)
    draw_car(d, v, fcs, compress)
    draw_tail(d, sim.ZR, wide)
    big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 44)
    small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 30)
    d.text((40, 30), title, font=big, fill=(255, 255, 255))
    d.text((40, 88), note, font=small, fill=(255, 220, 120))
    return img


a = panel(sim.build_shipped, False, False, "A - shipped, as it renders today",
          "13 faces - drawTail verbatim - lit 0.054..0.807")
d_ = panel(build_wedge, True, True, "D - references applied",
           "+ rear wing (5 faces, 16 verts) - tail 0.86w segmented - lit compressed to 0.20 + 0.40*lit")
out = Image.new("RGB", (sim.W//2, sim.H + 12), (10, 10, 14))
out = Image.new("RGB", (sim.W//2, (sim.H//2)*2 + 12), (10, 10, 14))
out.paste(a.resize((sim.W//2, sim.H//2), Image.LANCZOS), (0, 0))
out.paste(d_.resize((sim.W//2, sim.H//2), Image.LANCZOS), (0, sim.H//2 + 12))
out.save("/tmp/vapor_sim/wedge.png")
a.crop((1150, 560, 1700, 880)).resize((1100, 640), Image.LANCZOS).save("/tmp/vapor_sim/A_zoom.png")
d_.crop((1150, 560, 1700, 880)).resize((1100, 640), Image.LANCZOS).save("/tmp/vapor_sim/D_zoom.png")
z = Image.new("RGB", (1100, 1290), (10, 10, 14))
z.paste(Image.open("/tmp/vapor_sim/A_zoom.png"), (0, 0))
z.paste(Image.open("/tmp/vapor_sim/D_zoom.png"), (0, 650))
z.save("/tmp/vapor_sim/zoom.png")
v, fcs = build_wedge(sim.ZR)
print(f"\nvariant hull: {len(v)} verts, {len(fcs)} faces  (NV must grow from 32)")
print("wrote /tmp/vapor_sim/wedge.png and zoom.png")
