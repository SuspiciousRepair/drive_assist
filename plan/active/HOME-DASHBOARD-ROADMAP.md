# Home screen as a dashboard (cards from a YAML file) — plan, not started

Status: **plan only**. Written 2026-11-16. Nothing below is built.

Related: `INTEGRATIONS-DSL-ROADMAP.md` (settings pages as data). Both want the
same two pieces — a card renderer and a registry of built-in actions — so
build them once and share them.

## The goal

Today the home screen (`ui/ComfortActivity.java`, ~2600 lines) builds every
card in Java: Clima, Gate, Turbo, Journey, Music, Charge, the HA panel. To
add, remove or move a card you change Java and ship an apk.

The goal is a home screen like Home Assistant's Lovelace:

- a **dashboard file** (YAML) says which cards show, in what order, and what
  each card shows;
- a card **reads** hub keys (the hub is our "entities");
- a card **triggers** named actions (our "services"), with optional data;
- the app ships a **default dashboard** that looks the same as today.

## Lovelace words → our words

| Lovelace | Drive Assist | Already exists? |
|---|---|---|
| entity (`sensor.x`) | hub key (`car.gear`, `telemetry.outside_temp`) | yes — `CarActor` + `EntityBus`, `docs/HUB-DATA.md` |
| service (`light.turn_on`) | **action** (`hvac.recirc_toggle`) | no — today each tap is a lambda in `ComfortActivity` |
| card type (`entities`, `button`) | card type (`builtin`, `button`, `value`, ...) | no |
| `conditional` card | `visible_when:` on any card | partly — rules in `ComfortCardVisibility` |
| `ui-lovelace.yaml` | `dashboard.yaml` | no |

## The hard line: data, not code

Same rule as the integrations DSL. The file can only **arrange** what the app
already knows how to do:

- `action:` names a built-in action from the registry. An unknown name
  draws a disabled button, never a crash.
- `entity:` names a hub key. An unknown key shows `--`.
- No scripts, no expressions beyond the small condition list below, no URLs
  that receive car data.

Why: a dashboard file may come from Home Assistant over MQTT or from a USB
stick. A file that could run code or send data out is a way to leak location
and charging habits, or to open the gate.

## Sketch of a dashboard file

```yaml
# dashboard.yaml — the default one ships in assets/
columns: 3            # the panel is 1920 dp wide; 640 dp per column
cards:
  - type: builtin     # an existing Java card, unchanged
    card: clima

  - type: builtin
    card: gate
    visible_when: gate.available

  - type: buttons     # a new generic card: a row of tiles
    title: Drive
    visible_when: car.driving
    buttons:
      - icon: turbo
        label: Turbo
        action: drive.turbo_toggle
        state: car.drive_mode        # tile lights up when "on"
        on_value: 3
      - icon: regen
        label: Regen
        action: drive.regen_cycle
        state: car.regen_mode

  - type: value       # one big number from the hub
    title: Outside
    entity: telemetry.outside_temp
    unit: "°C"
    decimals: 0

  - type: button
    label: Rear defrost
    icon: rear_defrost
    action: hvac.rear_defrost
    data: { on: true }
    confirm: false

  - type: builtin
    card: ha_panel     # lowest priority, as today
```

## Pieces to build

1. **Action registry** (`dash/Actions.java`). One map: name → Java handler.
   Each entry also says:
   - `allowed_while_driving` (true/false) — the screen checks
     `CarState.isParked()` before it runs a parked-only action;
   - `needs_confirm` — gate, doors and other outside-the-car actions always
     ask, whatever the file says;
   - the data fields it accepts (so a bad file is caught at load, not at tap).

   First actions — all already exist as lambdas in `ComfortActivity` or in
   `controls/`:
   `hvac.cooler`, `hvac.warmer`, `hvac.recirc_toggle`, `hvac.purge_toggle`,
   `hvac.wind_direction` (data: dir), `hvac.rear_defrost` (data: on),
   `drive.turbo_toggle`, `drive.regen_cycle`, `drive.avas_toggle`,
   `gate.open`, `media.play_pause`, `media.next`, `media.previous`,
   `screen.config`, `screen.driving_stats`, `screen.charge_stats`,
   `trip.valet_stop`.

2. **Card types** (`dash/cards/`). Each type is a small Java class:
   build the view, subscribe to its hub keys in `onResume`, unsubscribe in
   `onPause` (hub rule: change-only, cheap listeners).
   - `builtin` — wraps an existing card (`clima`, `gate`, `turbo`, `journey`,
     `music`, `charge`, `ha_panel`). This is how the move starts with zero
     visible change.
   - `button`, `buttons` — tiles that run actions, optional `state:` key.
   - `value` — one hub key, big type.
   - `glance` — 2–4 small hub values in one card.
   - later: `gauge`, `chart` (reuse `MPAndroidChart`), `stack`.

3. **Conditions** (`visible_when:`). A fixed list, not an expression
   language: `car.parked`, `car.driving`, `car.charging`, `gate.available`,
   `music.playing`, `charge.session_retained`, `drive.card_enabled`. These
   are the rules that live in `ComfortCardVisibility` today — they just get
   names. Allow `not:` and a list (all must be true).

4. **Loader + validator** (`dash/DashboardLoader.java`). Pure Java, no
   Android, so it gets unit tests. Reads the file, checks every card type,
   action name, data field and hub key, and returns a list of problems.
   A broken file **never** blanks the home screen: it falls back to the
   default dashboard and shows the problems on the Config screen.

5. **Layout**. Keep `Style.packIntoColumns()` as is. The dashboard only
   decides which cards go into the `cards` list and in what order;
   packing, the scroll row, the sidebar and the Konami zone stay where they
   are.

## Where the file comes from

In this order, first one that loads cleanly wins:

1. `drivemem/<vin>/dashboard/set` on MQTT, retained — Home Assistant pushes
   it. Saved to the app's files dir so it works offline.
2. A file imported from USB (same flow as the lock-sound import).
3. `assets/dashboard.yaml` in the apk — the default, same look as today.

Before an imported or pushed file replaces the current one, the Config screen
lists **every action it uses** that is outside the car (gate, doors) and the
owner confirms once.

## Order of work (each step ships on its own)

1. Action registry. Move the existing lambdas in `ComfortActivity` behind
   names. No new behaviour, no visible change. Unit test the registry.
2. Loader + validator + `assets/dashboard.yaml` that lists only `builtin`
   cards. `ComfortActivity` builds `cards` from the file. Still no visible
   change — this is the big refactor, done alone.
3. Generic cards: `button`, `buttons`, `value`, `glance` + `visible_when`.
4. Load from MQTT and USB, with the confirm screen.
5. Split the big built-in cards (Turbo → `buttons`, etc.) only where the
   generic card does the job equally well. No need to convert all of them.
6. Later: a simple editor on the Config screen (reorder, hide, add a card).

## Open questions

- **YAML parser.** Android has JSON built in (`org.json`), not YAML. Choices:
  SnakeYAML's Android build (`org.yaml:snakeyaml:<ver>:android`, ~300 KB,
  drop the jar in `libs/` so `build.sh` sees it too), or accept JSON on the
  car and let HA convert YAML to JSON before it publishes.
- Do the generic cards need their own look, or only reuse `Style.card()` and
  the existing tile helpers? (Reuse first.)
- Size rules: every new card type uses car-sized type and touch targets
  (1 dp = 1 px here; see `CLAUDE.md` "The display").
