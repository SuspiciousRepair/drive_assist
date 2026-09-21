# Config screen — asset sources

## Background render

`drivemem/src/main/res/drawable-nodpi/config_bg_day.webp` (light) and
`config_bg_night.webp` (dark) — the Config screen's full-bleed background,
replacing the previous plain two-stop gradient for the Default theme only
(Neon and Noturno keep their own gradient -- this specific OEM scene doesn't
match either).

Source: an OEM Geely app's own `bg_connect` resource (day/night pair),
extracted from a local decompile of an app pulled from this car (prefix
`geely-settings-` on the extracted files). Copyright remains with Geely; not
purchased or separately licensed, only extracted from software the vehicle
already runs -- same rights gap as every other OEM asset in this file, see
"Status" below.

Both files are the exact 1920x1080 this panel renders at (density 1.0), so
they're placed unscaled. Re-encoded from the extracted PNGs (582KB day /
597KB night) to WEBP quality 90 -- 37.1KB / 31.5KB, no visible quality loss.

Superseded the previous approach: an earlier commit (2026-09-21, since
amended) placed a small cropped car cutout beside the mode cards instead --
now redundant, since this background already puts a (larger) car in that
same space.

## Theme color

The Default theme's light-appearance background (`Style.THEMES[0].light`,
in `Style.java`) was an approximation (`0xFFEEF1F4`/`0xFFDBE0E5`) picked
before this asset existed. Replaced with the flat color measured directly
from `config_bg_day.webp` itself (RGB 211/219/229 = `0xFFD3DBE5`, sampled as
a flat patch average, not eyeballed) -- this now IS the instrument cluster's
own off-white, not just a color chosen to resemble it. Only the Default
theme's light variant changed; dark variant and every other theme are
untouched.

## Status

Included on the owner's explicit request, aware of the above -- same
reasoning as every OEM asset already in this project (see git history:
`d0e0a6a`/`5d57e31` on `next`, reverted 2026-09-21, was a marketing-site
scrape with the same rights gap; the EX5 car render this file superseded
had the same gap too). Revisit if/when this project needs to stand on
clearer rights (e.g. before accepting outside contributions that build on
this screen, or before a wider release).
