# Right-hand drive layout toggle roadmap

**Scope, decided:** card position on the Comfort screen, **and** the status
sidebar's edge. Not KonamiView, not text direction/icon orientation — those
stay out on purpose (see below for why Konami specifically is excluded on
its own merits, not just narrowing for narrowing's sake).

## Idea

The app's interface was designed for left-hand drive (LHD) vehicles where the
driver sits on the left side of the cabin. Geely sells vehicles in multiple
markets, including right-hand drive (RHD) territories where the driver sits on
the right. Add a toggle in **TelemetryActivity** (alongside the existing theme
selector) that lets the owner mark their vehicle as RHD, so:

- **ComfortActivity's card order** places the climate controls closer to the
  driver's actual side, and
- **The status sidebar** (`statusSidebar()` — not a footer; it's a vertical
  rail pinned to the physical left edge) moves to the driver's side too,
  since it holds the settings cog and view-mode dock (cards/stats/charge) —
  reachability matters for these the same way it does for the climate card.

## What exists today (grounding)

- `ComfortActivity.statusSidebar()` builds a vertical rail: HA/ABRP/OBD2
  status icons on top, a "flexible space" spacer, a CarPlay-style dock
  (cards/stats/charge view buttons), another spacer, and the settings cog
  at the bottom.
- It's placed via `FrameLayout.LayoutParams` pinned `Gravity.START` with a
  `leftMargin` (`SIDEBAR_EDGE_MARGIN_DP`), width `SIDEBAR_WIDTH_DP` (84dp) —
  see the block right before it in `onCreate`. The comment there notes the
  main scrolling card row's own left padding "already clears this width" —
  so flipping the sidebar to the right edge likely also means flipping
  that padding, not just the sidebar's own `Gravity`/margin.
- **KonamiView stays fixed on the physical right third regardless of RHD**
  — this isn't just deferred scope, the code's own comment says why: it's
  "pinned to the physical right third of the SCREEN... muscle memory," and
  explicitly not tied to wherever any card or the sidebar happens to be.
  Moving it would break the existing muscle-memory guarantee for LHD
  owners. Leave it alone in both states.

## Intended presentation

A new **RHD toggle** in the Appearance section of TelemetryActivity (the
settings screen), positioned near the existing theme picker. When enabled:

- **ComfortActivity's three-column layout** (Clima/HVAC controls, Gate/quick
  commands, Art panel) reorders so the climate controls appear on the right
  side of the screen instead of the left.
- **The status sidebar** moves from the left edge to the right edge —
  `Gravity.START`+`leftMargin` becomes `Gravity.END`+`rightMargin`, and the
  scrolling card row's compensating padding moves from its left side to its
  right side to match.

KonamiView, text direction, and icon orientation stay exactly where they
are, regardless of this toggle — decided, not just out of scope for a
first pass.

## Discovery work

1. Confirm the current ComfortActivity layout order and how `repackColumns()`
   fills columns left-to-right: the method in `ComfortActivity.java` (~line
   385) maintains a `cards` list (currently Clima, Gate, Turbo, Music, Charge,
   HA panel) and packs them into equal-width columns in order.
2. Confirm `cards` is the only thing that needs reversing for the card side
   — no other hardcoded left/right assumption inside `repackColumns()`
   itself that would fight a reversed list.
3. Find exactly where the scrolling card row's left padding is set to
   "clear" the sidebar's width (referenced in the sidebar placement
   comment) — this has to become right padding when the sidebar flips, or
   the cards will collide with (or leave an ugly gap where) the sidebar
   used to be.
4. Locate where the theme setting is persisted in SharedPreferences
   (`drivemem` preferences file, key `"theme"`); confirm the same mechanism
   can store an RHD boolean (e.g., key `"rhd"`).

## Candidate mechanism

Store a persistent boolean setting, `rhd`, in the same SharedPreferences store
where the theme is kept (`drivemem` preferences, alongside `"theme"` and
`"appearance"`). Add helper methods to `Style.java`:

- `isRhd(Context c)`: reads the `"rhd"` key, defaults to `false`.
- `setRhd(Context c, boolean rhd)`: persists the choice.

In `ComfortActivity`:
- When RHD is enabled at `onCreate`, reverse the order of items in the
  `cards` list before `repackColumns()` processes it.
- Also at `onCreate`, build the sidebar's `FrameLayout.LayoutParams` with
  `Gravity.END`/`rightMargin` instead of `Gravity.START`/`leftMargin` when
  RHD is enabled, and swap the card row's compensating padding to the
  opposite side to match.

Both changes are the same shape: read one boolean once at `onCreate`,
branch two already-existing layout computations on it. No new views, no
new state beyond the one preference.

## Decisions needed later

- **Toggle wording**: English and Portuguese names for the setting (e.g.,
  "Right-hand drive", "Right-side driver", "Right-hand drive layout" vs.
  "Driving position" or similar — must be distinguishable from theme and
  Appearance settings, and clear to a non-technical owner). Translatable
  strings live in `res/values/strings.xml` (English) and
  `res/values-pt/strings.xml` (Portuguese).

## Implementation direction

Build the feature as a stateless, layout-only transformation applied at
`ComfortActivity.onCreate`:

1. Add `isRhd(Context)` and `setRhd(Context, boolean)` to `Style.java`,
   following the same pattern as `appearance()` and `setAppearance()`.
2. In `ComfortActivity.onCreate`, read the RHD state before `repackColumns()`
   is called. If RHD is enabled, reverse the order of the `cards` list
   before packing.
3. In the same `onCreate`, branch the sidebar's `LayoutParams` (edge +
   margin) and the card row's compensating padding on the RHD state.
4. Add the toggle to TelemetryActivity's Appearance panel, in the same
   section as the theme selector, wired to `Style.setRhd()` and a
   `recreate()` call to re-layout.
5. Test with both LHD and RHD states, confirming the column order **and**
   the sidebar both swap, and nothing collides or leaves a gap where the
   sidebar used to sit.

Do NOT touch KonamiView, text direction, or icon orientation — out of
scope, decided, not deferred. If feedback later asks for any of that, it's
a separate follow-up feature, not an extension of this one.
