# Drive Assist: a guide for drivers

**English** · [Português (Brasil)](DRIVER-GUIDE.pt-BR.md)

Drive Assist adds the dashboard Geely owners wish the car had shipped with. It
runs on the EX2 / Geometry E centre screen and brings climate shortcuts, a real
driving and charging history, trip maps, useful status, and optional connections
to services you already use — all in one place.

This guide is about using the app. Protocols, vehicle property IDs, and the
reverse-engineering evidence are in the
[technical library](README.md#technical-library).

## Your everyday dashboard

![Drive Assist comfort dashboard](screenshots/comfort-dashboard-live.png)

The home screen is made for quick use while you drive:

- The big number is the outside temperature.
- The comfort scale asks for cooler or warmer air in one move, instead of
  setting fan, temperature and A/C one by one.
- Large buttons do cooling, heating, recirculation and a window clear.
- While the car charges, a battery card shows the percent and an estimated time
  to full.
- Optional cards can show your garage gate, a panel sent by Home Assistant, or
  what is playing on Spotify.

The buttons are large, and most of the screen stays calm, so the useful part is
easy to read at arm's length.

## The car remembers your settings

![Drive mode and regeneration](screenshots/settings-overview-live.png)

Pick the drive mode and the regeneration level you like. Drive Assist puts them
back when the car wakes, so you do not set them again every trip. Each setting
has a **Restore at start** switch (on by default); turn it off for a setting you
would rather change in the car's own screen.

Settings are grouped in **Config**: Doors, Climate, Driving mode, Elements (what
shows on the home screen), Appearance (themes and Auto day/night), and System.

Vehicle settings are applied through the small **ModeHelper** companion that
installs with Drive Assist. The dashboard and the privileged helper stay
separate, for safety and reliability.

## Understand each day, not just the odometer

![Driving stats](screenshots/daily-statistics-live.png)

**Driving stats** turns the car's raw readings into a diary:

- distance, driving time, average speed and battery change;
- **Energy by use**: where a trip's energy went — drive, climate, and other —
  and how much regeneration brought back (measured by CAN);
- efficiency by speed range, in Wh/km;
- climbing, descending and the day's height balance;
- a timeline of drives and charges, with parked time between them;
- a **year of days**: every day coloured by how far you drove, with a week,
  month and year view.

Tap a finished trip (in Park) to see its **route** on an OpenStreetMap map,
coloured by speed. The map needs the internet the first time a place is shown;
after that it is kept in the car, so a trip that ends in a garage still shows its
map. Under the route is the height along the trip, and the trip's numbers.

Tap the trip count to open **all routes in one picture**. Routes are recorded
from **Config → System** (on by default) and stay in the car. You can export
them as **GPX** (one file per day) and the trip list as **CSV**, to a USB stick
or to your phone.

If the car was lent out in **Valet** mode, tap its card for a detail screen: a
map of every move, a timeline, the dashcam clips from that time, and the numbers
(moves, time moving and parked, distance, top speed and power, battery and
temperature low to high).

## Charging and cost

![Charging stats](screenshots/charging-statistics-live.png)

**Charging stats** keeps every past charge — energy, time, battery change and
average power. Tap a charge (in Park) to add or fix its price; a free charge can
be saved as zero, and that stays different from a charge whose price you never
entered. There is a month and year view, a battery-temperature card, and a map
of the places where you charge.

A **battery health** card shows kWh per charge, the hours the battery spent below
10% and 20%, and — with an OBD2 dongle — the battery's state of health. Without a
dongle, the charge screen still shows the outside temperature during the charge.

## Bring your own services

These are optional. Drive Assist works as an in-car dashboard without any of
them. Set them up in **Config → Integrations**, which shows each one's status
(connected, needs setup, or failing).

### Home Assistant

Connect your own Home Assistant server to see battery, range, location, charging,
climate and more at home. Home Assistant can also send a panel to the car — a
ferry wait, a doorbell image — and make a garage-gate button appear when the car
reaches home. Vehicle data goes only to the server you set. See the
[Home Assistant guide](HOME-ASSISTANT.md).

### A Better Routeplanner

Drive Assist can send live battery and position to ABRP, so long-trip planning
matches what the car is doing now. An optional Bluetooth OBD2 dongle gives finer
battery data. See the [ABRP guide](ABRP.md).

### Spotify

Sign in once and the home screen shows what is playing, with controls.

### Dashcam

The optional dashcam uses the car's own cameras and can burn in time, position
and speed. Clips copy to a USB stick, or download to your phone. Read the
[dashcam guide](DASHCAM.md) first: storage and local recording rules vary by
region.

## More you can do

- **Phone access**: scan a QR code on the car screen to get a page on your phone
  for backups, clips and trip files. It runs only in Park, on the local network.
- **Backup and restore**: save your settings, trips and certificates to a USB
  stick and to the car, locked with a password you choose.
- **Darkest mode**: at night, after a minute of driving with no touch, the
  screen goes to its lowest light and a dark layer covers the Dashboard. A
  touch or a gear change brings it back. Its Try button shows it at once.
- **Lock sound**: change the sound the car plays when it locks, or import your
  own.
- **Cleanup**: a screen that removes Drive Assist, its helper and the CAN reader,
  and gives the car's own lock sound back — useful before a workshop visit.

## What Drive Assist can control

Drive Assist mostly touches comfort and convenience: climate, drive mode,
regeneration, cabin lights, windows, charging, and the optional Home Assistant
gate.

It does not steer, brake, accelerate, unlock doors, or release the boot. Door and
body signals it shows are read-only.

Two switches affect safety systems, and both are your choice: one can turn off
AEB (automatic emergency braking), and one can mute AVAS (the pedestrian warning
sound). The [safety and privacy guide](WELCOME.md) lists everything the app can
read, change and send.

## Getting it onto the car

The easiest way for most owners is the single USB installer — see the
[short installation guide](QUICK-INSTALL.md). The car must already be unlocked
for third-party app installation.

Owners who already use ADB have computer-assisted methods too. Start with the
choice guide in [Installation](INSTALL-GUIDE.md).

## Where to go next

- [Install Drive Assist](QUICK-INSTALL.md)
- [Understand root ADB access](ADB-ROOT-SAFETY.md)
- [Connect Home Assistant](HOME-ASSISTANT.md)
- [Connect ABRP](ABRP.md)
- [Read the safety and privacy explanation](WELCOME.md)
- [Browse the technical discoveries](README.md#technical-library)
