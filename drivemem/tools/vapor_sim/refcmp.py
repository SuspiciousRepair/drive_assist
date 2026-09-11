"""Sample the reference art and the ported render on the same terms:
median body colour, and how much of the frame the car occupies."""
from PIL import Image
import statistics as st
import sim


def median_patch(img, box):
    px = img.convert("RGB").crop(box).getdata()
    return tuple(int(st.median([p[i] for p in px])) for i in range(3))


def lum(c):
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


r3 = Image.open("/tmp/vapor_sim/refs/r3.jpg")
r1 = Image.open("/tmp/vapor_sim/refs/r1.png")
print("r3", r3.size, " r1", r1.size)

# r3: the wedge is centred; rear deck / upper body / lower fascia
r3_patches = {
    "rear deck (upper)": (250, 232, 340, 244),
    "body mid":          (245, 250, 345, 262),
    "lower fascia":      (250, 270, 340, 280),
}
# r1: the blue car on the right, body panel above the lights
r1_patches = {
    "blue car body":  (350, 370, 400, 395),
    "red car body":   (175, 375, 225, 400),
}
for name, box in r3_patches.items():
    c = median_patch(r3, box)
    print(f"  r3 {name:<20} {c}  #{c[0]:02X}{c[1]:02X}{c[2]:02X}  lum {lum(c):5.1f}")
for name, box in r1_patches.items():
    c = median_patch(r1, box)
    print(f"  r1 {name:<20} {c}  #{c[0]:02X}{c[1]:02X}{c[2]:02X}  lum {lum(c):5.1f}")

print("\nshipped palette:")
for name, c in (("CAR_DARK", sim.CAR_DARK), ("CAR_LIT", sim.CAR_LIT)):
    print(f"  {name:<22} {c}  #{c[0]:02X}{c[1]:02X}{c[2]:02X}  lum {lum(c):5.1f}")
print("  faces actually rendered on the shipped hull:")
v, f = sim.build_shipped(sim.ZR)
import math
lits = []
for fc in f:
    a = [v[fc[1]][k] - v[fc[0]][k] for k in range(3)]
    b = [v[fc[2]][k] - v[fc[0]][k] for k in range(3)]
    n = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
    nl = math.sqrt(sum(c*c for c in n))
    if nl < 1e-6:
        continue
    n = [c/nl for c in n]
    ctr = [sum(v[k][j] for k in fc)/len(fc) for j in range(3)]
    v0 = [-ctr[0], sim.CAM_Y-ctr[1], -ctr[2]]
    vl = math.sqrt(sum(t*t for t in v0)); v0 = [t/vl for t in v0]
    facing = sum(n[i]*v0[i] for i in range(3))
    if facing < 0:
        facing, n = -facing, [-t for t in n]
    lits.append(max(0.0, n[1])*0.55 + facing*0.45)
for q, lab in ((min(lits), "darkest"), (st.median(lits), "median"), (max(lits), "brightest")):
    c = sim.blend(sim.CAR_DARK, sim.CAR_LIT, min(1.0, q))
    print(f"    {lab:<10} lit {q:.3f} -> {c}  #{c[0]:02X}{c[1]:02X}{c[2]:02X}  lum {lum(c):5.1f}")

# framing: car width as a fraction of frame width
print("\nframing (car width / frame width):")
print(f"  r3 reference        {(395-195)/588:.3f}")
print(f"  r1 reference (blue) {(430-330)/600:.3f}")
w_ship = sim.f * (2*sim.WB) / sim.ZR
print(f"  shipped hull        {w_ship/sim.W:.3f}   ({w_ship:.0f} px of {sim.W})")
print(f"  ...of the right third alone: {w_ship/(sim.W/3):.3f}")

# where the roof sits relative to the sun disc
sunR = min(sim.W*0.17, sim.horizon*0.62)
sunCy = sim.horizon - sunR*0.28
roof_y = sim.py(sim.Y2, sim.ZR)
print(f"\nsun disc: cy {sunCy:.0f}  r {sunR:.0f}  -> bottom {sunCy+sunR:.0f}")
print(f"shipped roofline at ZR: y {roof_y:.0f}  (horizon {sim.horizon:.0f})")
print(f"gap between sun bottom and roofline: {roof_y-(sunCy+sunR):.0f} px")
