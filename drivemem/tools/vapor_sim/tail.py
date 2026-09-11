"""Split tail: red clusters flanking a pale centre panel, proportioned off r3."""
import math
from PIL import Image, ImageDraw, ImageFont
import sim, wedge

CAR_W = 2 * sim.WB                       # 0.92 world units

# measured off r3, as fractions of car width
OUTER   = 0.4875 * CAR_W                 # outermost lit edge from centre
CLUSTER = 0.230 * CAR_W                  # width of each red cluster
PLATE_H = 0.180 * CAR_W                  # half-width of the centre panel
RED_H   = 0.040 * CAR_W                  # heights, also vs car width
PLT_H   = 0.050 * CAR_W
MID     = sim.Y1 - 0.1125                # same midline the shipped bar uses

PLATE_C = (0x9F, 0xC7, 0xDB)             # measured #9FC7DB
PALE_C  = (0xAC, 0xCA, 0xDA)             # measured #ACCADA (reverse lamps)


def draw_split(d, zr, segs=4, pale_at=2):
    def band(x0, x1, half_h, col, radius=True):
        ty, by = sim.py(MID + half_h, zr), sim.py(MID - half_h, zr)
        a, b = sim.px(x0, zr), sim.px(x1, zr)
        if radius:
            d.rounded_rectangle([a, ty, b, by], radius=(by - ty) * 0.35, fill=col)
        else:
            d.rectangle([a, ty, b, by], fill=col)
        return ty, by

    # halo now only wraps the clusters, so it cannot read as one open mouth
    for s in (-1, 1):
        xo, xi = s * OUTER, s * (OUTER - CLUSTER)
        ty, by = sim.py(MID + RED_H / 2, zr), sim.py(MID - RED_H / 2, zr)
        halo = (by - ty) * 0.75
        a, b = sorted((sim.px(xo, zr), sim.px(xi, zr)))
        d.rounded_rectangle([a - halo * 0.4, ty - halo, b + halo * 0.4, by + halo],
                            radius=halo, fill=(58, 12, 26))
    # red clusters, with one pale segment each (reverse lamp)
    for s in (-1, 1):
        xo, xi = s * OUTER, s * (OUTER - CLUSTER)
        lo, hi = min(xo, xi), max(xo, xi)
        gap = (hi - lo) * 0.035
        seg = ((hi - lo) - gap * (segs - 1)) / segs
        for i in range(segs):
            x0 = lo + i * (seg + gap)
            idx = i if s < 0 else segs - 1 - i          # mirror the pale one
            band(x0, x0 + seg, RED_H / 2,
                 PALE_C if idx == pale_at else sim.TAIL, radius=False)
    # centre panel
    band(-PLATE_H, PLATE_H, PLT_H / 2, PLATE_C)


def panel(mode, title, note):
    img = Image.new("RGB", (sim.W, sim.H))
    d = ImageDraw.Draw(img)
    sim.draw_scene(d)
    sunR = min(sim.W * 0.17, sim.horizon * 0.62)
    sunCy = sim.horizon - sunR * 0.28
    for i in range(int(sunR * 2)):
        y = sunCy - sunR + i
        if y > sim.horizon:
            break
        half = math.sqrt(max(0.0, sunR * sunR - (y - sunCy) ** 2))
        d.line([(sim.cx - half, y), (sim.cx + half, y)],
               fill=sim.blend((255, 232, 109), (255, 94, 58), i / (sunR * 2)))
    v, f = wedge.build_wedge(sim.ZR)
    wedge.draw_car(d, v, f, compress=True)
    if mode == "bar":
        wedge.draw_tail(d, sim.ZR, wide=True)
    else:
        draw_split(d, sim.ZR)
    big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 44)
    small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 30)
    d.text((40, 30), title, font=big, fill=(255, 255, 255))
    d.text((40, 88), note, font=small, fill=(255, 220, 120))
    return img


D = panel("bar", "D - one wide segmented bar", "0.95 width solid span - halo 1.8x reads as an open mouth")
E = panel("split", "E - split: clusters + centre panel",
          "red 0.23w each - panel 0.36w #9FC7DB - halo only on the clusters")
for img, tag in ((D, "D"), (E, "E")):
    img.crop((1210, 700, 1640, 840)).resize((1290, 420), Image.LANCZOS).save(f"/tmp/vapor_sim/tail_{tag}.png")
z = Image.new("RGB", (1290, 860), (10, 10, 14))
z.paste(Image.open("/tmp/vapor_sim/tail_D.png"), (0, 0))
z.paste(Image.open("/tmp/vapor_sim/tail_E.png"), (0, 440))
z.save("/tmp/vapor_sim/tail_cmp.png")
out = Image.new("RGB", (sim.W // 2, sim.H // 2), (10, 10, 14))
out.paste(E.resize((sim.W // 2, sim.H // 2), Image.LANCZOS), (0, 0))
out.save("/tmp/vapor_sim/scene_E.png")

print(f"plate   : {2*PLATE_H/CAR_W:.3f} of car width, centred")
print(f"cluster : {CLUSTER/CAR_W:.3f} each, outer edge at {OUTER/CAR_W*2:.3f} of car width")
print(f"gap     : {(OUTER-CLUSTER-PLATE_H)/CAR_W:.3f} of car width per side")
w_px = sim.f * CAR_W / sim.ZR
print(f"on screen at ZR ({w_px:.0f} px car): plate {2*PLATE_H/CAR_W*w_px:.0f} px, "
      f"cluster {CLUSTER/CAR_W*w_px:.0f} px, red band {RED_H/CAR_W*w_px:.1f} px tall")
print("wrote tail_cmp.png, scene_E.png")
