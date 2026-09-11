"""Numbers behind the pictures: planarity error, projected face height, and the
`lit` value each mirrored pair of faces receives."""
import math
from PIL import Image, ImageDraw, ImageFont
import sim  # reuses the ported pipeline

V, F = sim.PROPOSED_V, sim.PROPOSED_F
NAMES = ["rear bumper", "trunk deck", "rear windshield", "roof",
         "L rear quarter", "R rear quarter", "L C-pillar", "R C-pillar",
         "L door", "R door", "L front quarter", "R front quarter",
         "L tire", "R tire"]


def normal(verts, fc):
    a = [verts[fc[1]][k] - verts[fc[0]][k] for k in range(3)]
    b = [verts[fc[2]][k] - verts[fc[0]][k] for k in range(3)]
    n = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
    nl = math.sqrt(sum(c * c for c in n))
    return [c / nl for c in n], nl


def report(verts, faces, label):
    print(f"\n=== {label} ===")
    print(f"{'face':<18}{'out-of-plane':>13}{'ny':>8}{'lit':>8}{'proj h px':>11}")
    for i, fc in enumerate(faces):
        n, nl = normal(verts, fc)
        err = max(abs(sum(n[k] * (verts[j][k] - verts[fc[0]][k]) for k in range(3)))
                  for j in fc)
        c = [sum(verts[k][j] for k in fc) / len(fc) for j in range(3)]
        v0 = [-c[0], sim.CAM_Y - c[1], -c[2]]
        vl = math.sqrt(sum(t * t for t in v0))
        v0 = [t / vl for t in v0]
        facing = sum(n[k] * v0[k] for k in range(3))
        nn = n
        if facing < 0:
            facing, nn = -facing, [-t for t in n]
        lit = max(0.0, nn[1]) * 0.55 + facing * 0.45
        ys = [sim.py(verts[k][1], verts[k][2]) for k in fc]
        print(f"{NAMES[i]:<18}{err:>13.3f}{nn[1]:>8.3f}{lit:>8.3f}{max(ys)-min(ys):>11.1f}")


report(V, F, "proposed, verbatim")
fv, ff = sim.build_proposed(sim.ZR, fix=True)
report(fv, [q for q in sim.PROPOSED_F], "proposed, x/y & z corrected (quads kept, to isolate scale)")

# close-up: same hull pulled to a distance where the panels are readable
CLOSE = 0.55 * sim.ZR
for tag, fix in (("B_close", False), ("C_close", True)):
    verts, faces = sim.build_proposed(sim.ZR, fix=fix)
    if not fix:                       # slide the verbatim hull nearer, shape untouched
        dz = 3.0 - CLOSE
        verts = [(x, y, z - dz) for (x, y, z) in verts]
    else:
        verts, faces = sim.build_proposed(CLOSE, fix=True)
    img = Image.new("RGB", (sim.W, sim.H))
    d = ImageDraw.Draw(img)
    sim.draw_scene(d)
    sim.draw_car(d, verts, faces, stroke=2.0)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 40)
    d.text((40, 34), "verbatim, pulled near" if not fix else "corrected, same distance",
           font=font, fill=(255, 255, 255))
    img.crop((520, 250, 1900, 1080)).resize((1104, 664), Image.LANCZOS).save(f"/tmp/vapor_sim/{tag}.png")

a = Image.open("/tmp/vapor_sim/B_close.png")
b = Image.open("/tmp/vapor_sim/C_close.png")
out = Image.new("RGB", (a.width, a.height * 2 + 10), (10, 10, 14))
out.paste(a, (0, 0)); out.paste(b, (0, a.height + 10))
out.save("/tmp/vapor_sim/closeup.png")
print("\nwrote /tmp/vapor_sim/closeup.png")
