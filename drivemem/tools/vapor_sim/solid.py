"""Why the faces don't read as solid, and which lever fixes it.

Suspects: (a) the lighting compression I proposed off the reference luminance,
which narrows the value spread between roof / side / rear -- and that spread IS
the volume; (b) every face outlined at full NEON, which reads as a lit wireframe
with translucent infill rather than a solid mass.
"""
import math
from PIL import Image, ImageDraw, ImageFont
import sim, wedge, tail


def face_data(verts, faces):
    out = []
    for fc in faces:
        a = [verts[fc[1]][k] - verts[fc[0]][k] for k in range(3)]
        b = [verts[fc[2]][k] - verts[fc[0]][k] for k in range(3)]
        n = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
        nl = math.sqrt(sum(c*c for c in n))
        if nl < 1e-6:
            out.append(None)
            continue
        n = [c/nl for c in n]
        c = [sum(verts[k][j] for k in fc)/len(fc) for j in range(3)]
        v0 = [-c[0], sim.CAM_Y-c[1], -c[2]]
        vl = math.sqrt(sum(t*t for t in v0)); v0 = [t/vl for t in v0]
        raw = sum(n[i]*v0[i] for i in range(3))
        nn, facing = ([-t for t in n], -raw) if raw < 0 else (n, raw)
        out.append({"z": c[2], "front": raw >= 0,
                    "lit": max(0.0, nn[1])*0.55 + facing*0.45})
    return out


def silhouette_edges(faces, fd):
    """an edge is silhouette when it separates a front face from a back one,
    or has only one neighbour at all (open shell)"""
    adj = {}
    for i, fc in enumerate(faces):
        if fd[i] is None:
            continue
        for k in range(len(fc)):
            e = tuple(sorted((fc[k], fc[(k+1) % len(fc)])))
            adj.setdefault(e, []).append(i)
    sil = set()
    for e, fs in adj.items():
        if len(fs) == 1 or len({fd[i]["front"] for i in fs}) > 1:
            sil.add(e)
    return sil


def draw(d, verts, faces, compress, split_edges, floor=None):
    fd = face_data(verts, faces)
    sx = [sim.px(v[0], v[2]) for v in verts]
    sy = [sim.py(v[1], v[2]) for v in verts]
    sil = silhouette_edges(faces, fd) if split_edges else None
    order = sorted([i for i in range(len(faces)) if fd[i]],
                   key=lambda i: fd[i]["z"], reverse=True)
    dim = sim.blend((6, 10, 26), sim.NEON, 0.34)      # interior folds, muted
    for fi in order:
        fc, lit = faces[fi], fd[fi]["lit"]
        if compress:
            lit = 0.20 + lit*0.40
        elif floor is not None:
            lit = floor + lit*(1.0 - floor)
        poly = [(sx[k], sy[k]) for k in fc]
        d.polygon(poly, fill=sim.blend(sim.CAR_DARK, sim.CAR_LIT, min(1.0, lit)))
        if not split_edges:
            d.line(poly + [poly[0]], fill=sim.NEON, width=2)
        else:
            for k in range(len(fc)):
                e = tuple(sorted((fc[k], fc[(k+1) % len(fc)])))
                if e not in sil:
                    d.line([poly[k], poly[(k+1) % len(fc)]], fill=dim, width=1)
    if split_edges:                                   # silhouette last, on top
        for e in sil:
            d.line([(sx[e[0]], sy[e[0]]), (sx[e[1]], sy[e[1]])], fill=sim.NEON, width=3)


def panel(title, note, **kw):
    img = Image.new("RGB", (sim.W, sim.H))
    d = ImageDraw.Draw(img)
    sim.draw_scene(d)
    v, f = wedge.build_wedge(sim.ZR)
    draw(d, v, f, **kw)
    tail.draw_split(d, sim.ZR)
    big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 40)
    sm = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 28)
    d.text((36, 26), title, font=big, fill=(255, 255, 255))
    d.text((36, 80), note, font=sm, fill=(255, 220, 120))
    return img.crop((1180, 560, 1680, 860)).resize((1000, 600), Image.LANCZOS), img


cells = [
    panel("S0  compressed (what you saw)", "lit = 0.20 + 0.40*lit - every edge NEON",
          compress=True, split_edges=False),
    panel("S1  shipped lighting", "lit unchanged - every edge NEON",
          compress=False, split_edges=False),
    panel("S2  shipped + raised floor", "lit = 0.10 + 0.90*lit - every edge NEON",
          compress=False, split_edges=False, floor=0.10),
    panel("S3  silhouette vs fold", "lit = 0.10 + 0.90*lit - folds muted, outline NEON 3px",
          compress=False, split_edges=True, floor=0.10),
]
g = Image.new("RGB", (2020, 1220), (10, 10, 14))
for i, (c, _) in enumerate(cells):
    g.paste(c, [(0, 0), (1020, 0), (0, 620), (1020, 620)][i])
g.save("/tmp/vapor_sim/solid.png")
cells[3][1].resize((sim.W//2, sim.H//2), Image.LANCZOS).save("/tmp/vapor_sim/scene_S3.png")

v, f = wedge.build_wedge(sim.ZR)
fd = face_data(v, f)
sil = silhouette_edges(f, fd)
tot = {tuple(sorted((fc[k], fc[(k+1) % len(fc)]))) for fc in f for k in range(len(fc))}
lits = sorted(x["lit"] for x in fd if x)
print(f"faces {len(f)}  edges {len(tot)}  silhouette {len(sil)}  interior {len(tot)-len(sil)}")
print(f"lit spread shipped   : {min(lits):.3f} .. {max(lits):.3f}  ratio {max(lits)/max(1e-6,min(lits)):.1f}x")
c = [0.20 + x*0.40 for x in lits]
print(f"lit spread compressed: {min(c):.3f} .. {max(c):.3f}  ratio {max(c)/min(c):.1f}x")
fl = [0.10 + x*0.90 for x in lits]
print(f"lit spread floor 0.10: {min(fl):.3f} .. {max(fl):.3f}  ratio {max(fl)/min(fl):.1f}x")
print("wrote /tmp/vapor_sim/solid.png")
