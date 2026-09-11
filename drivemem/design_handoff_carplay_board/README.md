# Handoff: Drive Assist — CarPlay-style comfort board

## Overview
Redesign of Drive Assist's comfort screen (`ComfortActivity`) and config screen (`TelemetryActivity`)
for the Geely EX2 head unit (IHU629G, Android 9, 1920×1080 @ density 1.00), brought closer to the
Apple CarPlay visual language: content lives in a few large rounded translucent widgets floating on
the panel art, instead of bare controls on a dark slab.

Two fixed pieces are NOT ours and must not change: the car's bottom bar (y 975→1080) and the
head-unit status bar (y 0→72). The app owns only the 1920×903 band between them.

## About the Design Files
`CarPlay Redesign.dc.html` is a **design reference written in HTML** — a prototype of the intended
look, not production code. Drive Assist is an installed APK that builds its UI in Java code
(`Style.java` + Views), so the task is to **reproduce these layouts with the existing Java view
code and the existing `Style` palette**, not to embed HTML. Every number below is px in the mock and
therefore dp/sp 1:1 in the app, because this panel runs at density 1.00.

## Fidelity
**High-fidelity.** Colours, radii, type sizes and spacing are final and taken from
`drivemem/src/com/geely/drivemem/Style.java`. Two intentional deviations from today's code, both
deliberate design decisions:
- `RADIUS_DP` grows (geely 14 → 30, noturno 10 → 22, claro 16 → 34, neon 22 → 46 if added).
- `CARD` becomes translucent so the art reads through it (see Design Tokens).

## Screens / Views

### 1. Comfort board — `ComfortActivity` (mock ids 1a, 2a Noturno, 2b Claro)
**Purpose:** read how hard the car is working, ask for colder/warmer, throw the two air switches,
open the gate.

**Layout:** app band 1920×903, padding 32px top/bottom, 36px left/right, `gap: 24`.
Two children, top-aligned (cards hug their content — do NOT stretch them to the column height):
1. a **600px-wide vertical stack** (`gap: 24`) holding the Clima card and the Portão card;
2. the **remaining ~1224px left empty** so the panel art is visible. This is the point of the
   layout — do not fill it.

**Clima card** — `padding: 38`, `radius: RADIUS_DP`, fill `CARD` (translucent), internal `gap: 26`,
`box-sizing: border-box`, contents top-to-bottom:
- Title "Clima" — 28sp, weight 600, `TEXT`.
- Temperature row, baseline-aligned, `gap: 16`: "28.0°" at 112sp weight 300, letter-spacing −0.04em,
  line-height 0.9, `TEXT`; then "fora" at 26sp `TEXT_DIM`. The value is `AC_AMBIENT_TEMP`
  (`(raw − 80) / 2`).
- **Effort scale** (replaces `Style.comfortRuler`): 11 pills in a row, `gap: 8`, height 15,
  radius 8. Order C5 C4 C3 C2 C1 · 0 · W1 W2 W3 W4 W5. The ten side pills are `flex: 1`; the zero
  pill is a fixed 15×15. Cold pills are left of zero, warm right.
  - unlit: `CARD_HI`
  - lit: `COOL` when `level > 0`, `HEAT` when `level < 0` — lit from the zero pill outwards to
    `|level|`
  - zero pill: 2dp inset stroke in `CARD_HI` normally; **filled `TEXT`** when `level == 0`, because
    zero means the HVAC is switched OFF (COMFORT-TABLE.md, "Zero is off")
  - `approx` ("C4ish"): the next pill outward is hollow with a 2dp stroke in the side colour
  - there is **no label, no pointer, no neutral ring and no mode-boundary tick**. The old relative
    ruler is gone; this is the absolute `EffortTable` scale. Requires exposing `approx` from
    `ComfortRuler` (it is private today).
- **Ask row**: two tiles, `flex: 1` each, height 104, `gap: 14`, radius `RADIUS_DP − 8`,
  fill `CARD_HI`-ish (see tokens), 1dp inset hairline. Glyph only, 34sp, centred: **❄** in `COOL`
  (`tap(+1)`), **☀** in `HEAT` (`tap(−1)`). No text labels, no coloured outline — side and colour
  carry the direction.
- **Switch row**: two tiles, `flex: 1`, height 96, `gap: 14`, same fill/hairline, labels 25sp
  weight 600 `TEXT`: "Recircular ar" (`HVAC_RECIRC_ON`, override), "Fechar vidros".
- **Cog**: 38×38, `Style.cogButton` as it exists (2 rings + 8 teeth, `TEXT_DIM`), bottom-left of the
  card, opens config.

**Portão card** — its own card (different category from climate), `padding: 38`, radius
`RADIUS_DP`, single row, `align-items: center`, `gap: 22`:
lamp dot 14×14 round in `ACCENT` with a 16px/4px glow at 50% → title "Portão" 28sp/600 `TEXT`
(`flex: 1`) → button 180×88, radius `RADIUS_DP − 8`, fill **`CARD_ON`** with `Style.onFill()` text
at 26sp/600. Card is present only while `drivemem/<vin>/gate/available` is "online"; the lamp is the
availability cue (no "disponível" label). Tap publishes `gate/open`, 3s debounce, wording stays
"pedido enviado".

⚠ Use `CARD_ON` for that button fill, never `ACCENT`: in Noturno `ACCENT` #FFA726 with
`TEXT_ON` #FFF3DF is 1.77:1 and unreadable, while `CARD_ON` #7A4A08 gives 7:1. Neon has the same
trap (`CARD_ON` #0B4C5E vs `ACCENT` #00E5FF).

### 2. Comfort board, alternative rhythm (mock id 1b)
Same parts, two rows: a wide Clima card (1176px) with the scale and the ❄/☀ pair side by side, the
Portão card at 600px beside it, and the two switches in a full-width 200px strip below. Kept only
as a comparison — 1a is the recommended one.

### 3. Notification / HA window (mock id 1d)
The board unchanged, plus the HA card as a **600px column at the far right**, art visible between
the two. Per ARTE.md §3.1 the window has **no background of its own** — 26px radius, 1dp inset
hairline, a 60px `ACCENT` glow, and two 34×34 corner marks (top-left, bottom-right) in the cabin
colour. Content, `padding: 30`, `gap: 18`: title 32sp/600; ✕ button 54×54 radius 18 `CARD`;
"30" 64sp/600 in `ACCENT` + "min de espera" 25sp; status row (14px dot + "Embarque ativo" 25sp);
camera frame 230px tall, radius 18; caption 21sp/1.45 `TEXT_DIM`. `dismissedHash` behaviour
unchanged.

### 4. Config — `TelemetryActivity` (mock id 1c)
No art, just the theme's vertical background gradient. Left list 360px: back button 58×58 radius 18
`CARD`, "Config" 32sp/600, then six rows 70px tall, radius 18, `padding: 0 24`; the selected row is
filled `CARD_ON` with `onFill()` text 25sp/600, the rest plain `TEXT_DIM` 25sp with no background.
Right pane is **left-leaning**: `max-width: 700`, so the buttons stay small and close together —
group header 21sp/700 uppercase letter-spacing .06em in `blend(TEXT_DIM, TEXT, .45)`, a note
"Reaplicado a cada partida" 21sp `TEXT_DIM` right-aligned, then a 3-column grid of 192×88 cards,
`gap: 14`, labels 27sp/600, the selected one filled `CARD_ON`. Groups: Modo de condução
(Eco/Comfort/Sport → 570491137/8/9), Regeneração (Fraca/Média/Forte → 537003265/6/7). Actions at the
bottom: "Aplicar agora" 290×76 filled `CARD_ON`, "Salvar padrão" 290×76 `CARD` + hairline, both
25sp/600.

### 5. Panel art
`SkylineArtView` keeps horizon 0.66h, the city mirrored below at 0.34 alpha, and cabin brightness
as city transparency (floor 8%). **One change:** the veil is much softer now that the widgets carry
their own contrast — `{bg×0.82, bg×0.5, bg×0.1}` at stops `{0, 0.45, 0.78}` instead of opaque →
clear. Claro pulls the city 45% toward black, as today.

`VaporArtView` (Noturno, the easter egg) is **unchanged by design** — grid, sliced sun, city, car,
horizon 0.46, CELL 0.5, one camera. The only edit is `VP_X` 0.5 → 0.62 so the scene sits in the open
right side instead of behind the widget stack. Noturno's veil stays opaque to 0.34, clear from 0.72.
(The mock does not draw the car — that is the 13-face mesh and stays in code.)

## Interactions & Behavior
- ❄ → `ruler.tap(+1)`, ☀ → `ruler.tap(−1)`. Every press re-fits from the car first, so a hand on the
  OEM panel is just the current state. From an approximate column, pressing toward zero settles it.
- The scale repaints on `ComfortRuler`'s change callback and on the 30s maintenance tick (the number
  must follow the car when a hand moves something).
- Nothing decays: no relax, no commit. The column only moves on a press or a sustained re-slide
  (2 °C, 10 min, never within a minute of a press).
- Gate: tap → confirm → publish, 3s debounce, never retained, QoS 1.
- HA window: frame lights up and scales 0.94 → 1, content fades in 150ms later; reverse on close.
- Theme switch recreates the Activity; the art and palette are re-read in `onCreate`.

## State Management
`ComfortRuler.level` (−5…+5), `approx`, `status`; `GateState.available`; cabin colour + brightness
from the 4s ambient poll; outside temp from the existing poll; `Style.current()` / `transientId` for
the theme. No new state is introduced by this design.

## Design Tokens
Straight from `Style.java`, per theme — `bgTop / bgBottom / CARD / CARD_HI / CARD_ON / TEXT /
TEXT_DIM / TEXT_ON / ACCENT / COOL / HEAT / radius / stroke`:
- **geely**: #303640 / #171B21 / #2E333B / #3A4048 / #1E6FFF / #ECEFF3 / #8A93A0 / #ECEFF3 /
  #1E6FFF / #2196F3 / #FF9800 / 30 (was 14) / 1dp #22FFFFFF
- **noturno**: #0B0B0D / #000000 / #141416 / #25252A / #7A4A08 / #E4DCCB / #7E7568 / #FFF3DF /
  #FFA726 / #7FA8BF / #FFB74D / 22 (was 10) / 1dp #1AFFFFFF
- **claro**: #F8F6F1 / #E6E2D9 / #FFFFFF / #EDEAE3 / #1668E3 / #1B1F24 / #62697A / #FFFFFF /
  #1668E3 / #0277BD / #E65100 / 34 (was 16) / 1dp #1A000000
- **neon** (not mocked): #10143A / #04050D / #0C1030 / #1B2358 / #0B4C5E / #E6FBFF / #7C8FB8 /
  #E6FBFF / #00E5FF / #00E5FF / #FF2D95 / 46 (was 22) / 2dp #5500E5FF, `OUTLINE`

Card fill: `CARD` at ~58% alpha over the art (dark themes) / ~82% (Claro), with a blur behind it.
Tile fill inside a card: white at 7% (dark) / black at 4.5% (Claro), plus a 1dp hairline at
white 9% / black 10%.
Spacing scale: 8 / 14 / 18 / 24 / 26 / 32 / 38.
Type scale (sp): 112 · 64 · 34 · 32 · 28 · 27 · 26 · 25 · 21 · 20. Nothing below 20.
Radii: `RADIUS_DP` for cards, `RADIUS_DP − 8` for tiles and buttons, 18 for small chrome.

## Assets
None. Every glyph is drawn in code (`Style.cogButton`, the ❄/☀ are text) and the art is generated.
The camera frame in the HA window is a placeholder for the live MJPEG still that HA already sends.

## Screenshots
`screens/` — one per frame, in the same order as the design file:
01 2a Noturno · 02 2b Claro · 03 1a home · 04 1b alternative · 05 1c config · 06 1d notification.
They are captures of the preview at browser zoom, so treat the README numbers as authoritative for
measurements, not the pixels in these images.

## Files
- `CarPlay Redesign.dc.html` — all six frames: 2a Noturno, 2b Claro, 1a home, 1b alternative,
  1c config, 1d notification. Tweak controls in the preview move the effort column (−5…+5), toggle
  `approx`, change cabin colour/brightness and show/hide the alert.
- Source of truth for behaviour, in the repo: `docs/COMFORT-TABLE.md` (the scale),
  `docs/ARTE.md` (art, veil, HA window), `drivemem/src/main/java/com/geely/drivemem/ui/Style.java` (palette),
  `ComfortRuler.java` (levels, fitting, re-slide). `docs/historical/COMFORT.md` is history — do not implement from it.
