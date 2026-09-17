# Drive Assist: a guide for drivers

**English** · [Português (Brasil)](DRIVER-GUIDE.pt-BR.md)

Drive Assist adds the dashboard Geely owners wish the car had shipped with. It
runs directly on the EX2 / Geometry E centre screen and brings climate shortcuts,
driving history, charging costs, useful status information, and optional smart-home
connections into one place.

This guide covers the product and its operation. Protocols, vehicle property IDs,
and reverse-engineering evidence remain available in the
[technical library](README.md#technical-library).

## Your everyday dashboard

![Drive Assist comfort dashboard](screenshots/comfort-dashboard-live.png)

The first screen is designed for quick use in the car:

- The large temperature reading shows the outdoor temperature.
- The comfort scale lets you ask for cooler or warmer air without managing every
  climate setting separately.
- Large buttons provide direct access to cooling, heating, recirculation, and
  window clearing.
- When the car is charging, a battery card shows progress and an estimated time
  to full.
- Optional cards can show your garage gate, live information sent by Home
  Assistant, or other context that matters at your location.

The controls use large touch targets and avoid filling the screen with switches.
Most of the screen stays calm so the useful information is readable at a glance.

## The car remembers your preferences

![Drive mode and regeneration settings](screenshots/settings-overview-live.png)

Choose the drive mode and regeneration level you prefer. Drive Assist can restore
them when the car starts, instead of making you repeat the same setup on every
trip. You can also choose whether optional cards, status icons, and background art
appear on the dashboard.

Settings that affect the vehicle are applied through the small ModeHelper
companion installed with Drive Assist. The main dashboard and the privileged
helper remain separate for safety and reliability.

### Choose the app language

Open Settings using the gear icon, then select
**Language** (or **ภาษา** when using Thai) in the Display group. Open the dropdown and choose
**English**, **ไทย** (Thai), or **Follow system**. The selection is saved automatically and the settings
screen refreshes immediately, with no gear or parking restriction. The dashboard
also refreshes when you return to it.
Your choice is remembered after closing and reopening Drive Assist; it does not
change the language of the car's own menus. Follow system is the default for
existing and new installations.

English uses Unitext Regular; Thai uses Noto Sans Thai. The font changes with
the app language, including after a restart. Both fonts are bundled for offline use.

The default Minimal theme uses white and grey surfaces in Light mode, and matte
charcoal cards with soft text and muted colours in Dark mode. Select
**Settings → Appearance** to choose Light, Dark, or Auto. New installations start
in Light mode. The former Neon theme is retired; a saved Neon choice switches
to Minimal in Dark mode automatically. Other appearance preferences are preserved.

## Understand each day, not just the odometer

![Daily statistics in dark mode](screenshots/daily-statistics-live.png)

Daily Statistics turns the car's raw readings into a useful diary:

- distance, driving time, average speed, and battery change;
- energy used and recovered through regeneration;
- efficiency by speed range;
- climbing, descending, and the day's altitude balance;
- a timeline of drives and recharges, with parked time shown between them;
- distance driven during every hour of the day, coloured by speed range.

Parked climate use is kept out of driving efficiency. A zero-speed sample still
counts when the car is in a driving gear, so queues and stop-and-go traffic remain
part of the trip.

## Keep track of charging and cost

![Charging statistics and 30-day energy balance](screenshots/charging-statistics-live.png)

The Charging Statistics screen keeps previous charging sessions, including their
energy, duration, battery change, and average power. Add or correct the price of a
past recharge by tapping its session while the car is parked. A free recharge can
be recorded as zero; it remains different from a recharge whose price was never
entered.

The 30-day energy chart compares what left the battery with what came back through
regeneration, AC charging, and DC fast charging. Charging that crosses midnight is
split between the calendar days it actually occupied.

## Bring your own connected services

These integrations are optional. Drive Assist works as an in-car dashboard
without them.

### Home Assistant

Connect Drive Assist to your own Home Assistant server to see battery, range,
location, charging, climate, and other vehicle information at home. Home Assistant
can also send a contextual panel to the car—for example a ferry wait time or a
doorbell image—and can make a garage-gate button appear when the car reaches home.

Vehicle data goes to the server you configure. Drive Assist does not send it to an
analytics service operated by this project. Setup instructions are in the
[Home Assistant guide](HOME-ASSISTANT.md).

### A Better Routeplanner

Drive Assist can send live battery and position information to ABRP so long-trip
planning reflects what the car is doing now. An optional Bluetooth OBD2 adapter
can provide more precise battery-management data. See the [ABRP guide](ABRP.md).

### Dashcam

The optional dashcam uses the car's existing cameras and can add time, position,
and speed information to recordings. Clips can be exported to USB. Read the
[dashcam guide](DASHCAM.md) before enabling it, because storage and local recording
rules vary by region.

## What Drive Assist can control

Drive Assist is limited to comfort and convenience functions such as climate,
drive mode, regeneration, cabin lighting, charging preferences, and the optional
Home Assistant gate command.

It does not steer, brake, accelerate, unlock doors, or release the boot. Door and
body signals shown by the app are read-only. The [safety and privacy guide](WELCOME.md)
lists the boundaries and their implementation.

## Getting it onto the car

The easiest route for most owners is the single USB installer. Follow the
[short installation guide](QUICK-INSTALL.md). The car must already be unlocked
for third-party application installation.

There are also computer-assisted installation methods for owners who already use
ADB. Start with the short choice guide in [Installation](INSTALL-GUIDE.md); the
command-by-command reference is further down that page.

## Where to go next

- [Install Drive Assist](QUICK-INSTALL.md)
- [Understand root ADB access](ADB-ROOT-SAFETY.md)
- [Connect Home Assistant](HOME-ASSISTANT.md)
- [Connect ABRP](ABRP.md)
- [Read the safety and privacy explanation](WELCOME.md)
- [Browse the technical discoveries](README.md#technical-library)

### Vehicle controls and appearance

The app opens directly on Vehicle Control. The same controls are also available
in Settings → Driving mode. The Geely EX2 image
is an offline preview: tap it to cycle immediately through the five Thai EX2 MAX
colours (Moon White, Star Silver, Comet Gray, Nebula Beige, Aurora Green, all with
a black roof). The last colour is saved automatically and follows the preview
into Settings. Eco is green, Comfort is yellow, and Sport is red. Dark mode uses
softer versions of these colours while keeping each mode easy to distinguish.

Tap the vehicle heading or its pencil icon above the car on Home or in
Settings → Driving mode. Choose **Geely EX2** or **Geely EX2 Max** using the
two side-by-side model buttons, then enter a nickname of up to 40 characters. The model stays
on the first line, with your nickname on the line below. Tap **Save** to update
both immediately and remember them after reopening the app. **Cancel** keeps
the previous model and nickname. A blank nickname keeps the selected model
and shows the add-nickname prompt. Both are independent of the app language
and vehicle colour.

A filled button is the saved choice; an outline indicates the mode reported
by the vehicle. Tap any driving or regeneration mode to send the change and
save it automatically for the next startup. **Restore defaults** selects
**Comfort** with **Medium** regeneration and applies both. A brief popup with a
green checkmark and heading says
**Defaults restored** and lists the saved driving and regeneration choices. It
closes automatically; the vehicle's reported mode remains separate. Manually
selecting a driving mode ends an active Turbo boost, so its timer
cannot later replace your choice. Regeneration changes leave Turbo running. Turbo duration in
Settings also saves automatically as you edit it.

The Controls / Energy / Display tabs open the corresponding existing app panels.
Display settings use a two-column card grid on wide head units. Appearance has
a theme preview and Light / Dark / Auto buttons. Main headings are larger than
navigation labels and control captions in both English and Thai.

The horizontal climate strip at the bottom of Home provides Cooler, Warmer,
Recirculate, and Open/Close windows using the existing controls. It stays visible
while the vehicle controls above it scroll on shorter displays. More controls,
trip statistics, charging statistics, and Settings remain in the left dock.

Home uses two columns on wide head units: driving controls take about 68% of
the row on the left, and Battery & energy takes 32% on the right. The larger
driving card has a larger vehicle preview and mode buttons. Energy remains
visible without opening a tab; narrower windows stack the cards so labels and
controls remain readable. It shows battery percentage, estimated remaining range, charging
power, battery temperature (when OBD2 supplies it), and recovered energy for the
current trip. The existing telemetry stream refreshes the view automatically;
missing or invalid values show **—**, not sample data. Charging power uses the
measured voltage/current and ignores latched current after a known unplug event.
The energy panel is read-only and does not change charge limits or schedules.
