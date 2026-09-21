# Driving mode screen — asset sources

## Vehicle image

`drivemem/src/main/res/drawable-nodpi/car_ex5.webp` — a top-3/4 render of a
Geely EX5, sourced from `res/drawable-mdpi/ic_car_bg.png` inside
`~/dev/geely/decompiled/energy/`, a decompile of an OEM "energy" app that
ships as part of this car's own software stack. Copyright remains with
Geely; the asset was not purchased or separately licensed, only extracted
from software this vehicle already runs. Decorative only -- does not reflect
door, lock, or charging state.

Note the depicted trim is an EX5 render, not necessarily an exact match for
the owner's own EX2 -- the OEM asset is what the on-car software ships, not
a photo of this specific car.

Original asset: 2560x1344, RGBA PNG, 862KB, car occupying roughly the right
half of an otherwise fully transparent canvas. Cropped to the car's own
bounding box, resized to 700x522 (its actual on-screen size in the Config >
Drive layout, at this panel's 1:1 dp/px density), and re-encoded as WEBP
(quality 85) -- 36.7KB, a ~96% reduction from the source file with no visible
quality loss at this size. See `drivemem/src/main/res/drawable-nodpi/`.

## Status

Included on the owner's explicit request, aware of the above (this project
previously shipped a different vehicle image scraped from a marketing site
with the same rights gap -- see git history, `d0e0a6a`/`5d57e31` on the
`next` branch, reverted 2026-09-21). Revisit if/when this project needs to
stand on clearer rights (e.g. before accepting outside contributions that
build on this screen, or before a wider release) -- a personally-photographed
vehicle image remains the cleanest option.
