# Safety and privacy

This page says what Drive Assist reads from the car, what it can change, and
where it sends data. Each statement here was checked against the source code.
If you find one that is wrong, please open an issue.

Drive Assist has no account, no sign-up and no analytics. The developer gets
no data from your car.

## What it reads

| Data | Where it comes from |
| --- | --- |
| Battery level, charging state, plug, charge current limit | The car's own Android interface (VHAL) |
| Speed, gear, odometer, range, drive mode, regen level | VHAL |
| Doors, windows, climate settings, outside temperature | VHAL |
| Low-tyre warning, one per wheel | VHAL. This car has no tyre pressure sensors, so there is a warning but no pressure value |
| Battery voltage, current and power, climate power, charger power, brake pedal switch | The car's CAN bus, read-only |
| Finer battery level, battery temperature, battery health | An OBD2 Bluetooth dongle, if you pair one |
| Position, altitude, heading | The head unit's GPS |
| Front and rear camera video | The car's camera service, for the dashcam |
| The song that is playing | Android media, and Spotify if you sign in to it |

### The CAN reader

Since v0.5.0 Drive Assist reads the CAN bus, and the switch **Car data (CAN)**
is on by default (Config > System). The reader is
[roadcast](../third_party/roadcast/README.md), a small program that runs as
root on the head unit. ModeHelper starts it through the car's local ADB. It
only listens: it never sends anything to the car. Turn the switch off and
ModeHelper stops starting it.

## What it can change

- **Climate**: temperature, fan, A/C, recirculation and rear defrost.
- **Drive mode and regen level**, including putting your saved choice back
  when the car wakes.
- **AEB (automatic emergency braking)**: a switch can turn it off, after a
  confirmation. This is the only setting in the app that reduces a safety
  system. It is on unless you turn it off.
- **AVAS (pedestrian warning sound)**: can be muted.
- **Windows**: opens them a crack when a door opens (off by default), and a
  button opens them halfway to let hot air out.
- **Cabin ambient light**: colour, brightness and mode.
- **Charging**: start and stop AC charging, and set the current limit from
  5 A to 32 A.
- **Parking mode**: keeps the car's accessories on, with a timer.
- **Lock sound**: the sound the car plays when it locks.
- **The text of the car's own apps** (Energy, Wi-Fi, Settings), only after
  you tap Install in **Car app translations**. A patched copy is put in
  front of each app; the car's own files are not changed. Remove (or a
  reboot with the integration off) gives the factory text back.
- **Screen brightness**: Extra dark dims the panel at night.
- **Wi-Fi and ADB** on the head unit (see below).
- **Its own updates**: it downloads and installs new versions, only in Park.

## What it cannot do

The app has no code for any of these:

- Locking or unlocking the doors, or opening the trunk.
- Steering, acceleration or motor torque.
- Applying or releasing the brakes. (It reads the brake pedal switch. It can
  turn AEB off, as said above.)
- Exterior lights, wipers or the horn.
- Airbags or any other restraint system.

## Where data goes

Nothing leaves the car unless one of the rows below applies.

| Destination | What is sent | When | How to stop it |
| --- | --- | --- | --- |
| Your own MQTT broker (Home Assistant) | Car data, position, app version | Only after you enter a broker. Every 10 seconds by default | Remove the broker, or turn the integration off |
| A Better Routeplanner (`api.iternio.com`) | Battery level, power, speed, and position if you allow it | Only after you enter your ABRP token | Turn the integration off. Position has its own switch |
| OpenStreetMap (`tile.openstreetmap.org`) | Requests for map pictures. A request names the map squares, so the map server can see which area your trips cover. It gets no car data | When a trip map is shown, while driving (at most once a minute) and at the end of a trip. Pictures are kept on the car and not asked for again | There is no switch for this yet |
| GitHub (`github.com`) | A request for the newest version | About once a day, and when you tap update | Set your own update address in Config > System |
| Spotify | Requests for playback state and commands | Only after you sign in to Spotify in the app | Sign out |
| A link you paste for a lock sound | One download of that file | Only when you tap Download | Do not use it |
| GitHub (`raw.githubusercontent.com`) | Requests for the car app translation files. It gets no car data | Only when you tap Install or Update in **Car app translations** | Do not use it |

### Phone access

The car can show a page to your phone (backups, clips, trip files). It is off
by default and then runs only while its QR code is on the car's screen. It
answers only in Park, only to addresses on the local network, and only to a
phone that scanned the QR code.

## Commands from Home Assistant

**Accept commands from Home Assistant** (Config > Integrations > Home
Assistant) is on by default. When it is off, the app does not listen to any
command topic. When it is on, your broker can:

- set the charge current limit and start or stop charging;
- set the cabin ambient light;
- turn parking mode on or off and set its timer;
- move the climate ruler;
- start an app update;
- open ADB for a limited time, and only while the car is on your home Wi-Fi.

Anyone who can publish to your broker can send these. Protect the broker.

## ADB

On this head unit, network ADB is a root shell for anyone on the same
network. Drive Assist needs it to install itself, and ModeHelper controls it:

- It opens for 5 minutes after the head unit boots.
- It stays open while the car is on the network named in **Privileged Wi-Fi
  (SSID)** (Config > System). That name is `car` until you change it, so set
  it to your own network.
- The ADB switch in Config > System opens it for 15 minutes.
- The CAN reader opens it for a few seconds when it has to start, then closes
  it again.
- **Keep ADB always on** is off by default. It exists for other apps that need
  ADB. While it is on, ADB never closes.

Read the [root ADB safety notice](ADB-ROOT-SAFETY.md) before you enable ADB.

## The Bluetooth PIN change

Pairing an OBD2 dongle can need a change to a system file
(`bt-pin-fix/apply-pin-1234.sh`). It is optional, the installer never runs
it, and it is the one change that survives an uninstall and a factory reset.
Put it back with `bt-pin-fix/revert-pin-0000.sh` as soon as the dongle is
paired. The [ABRP guide](ABRP-GUIDE.md) has the steps.

## More

- [Root ADB safety notice](ADB-ROOT-SAFETY.md)
- [Security architecture](SECURITY-SAFETY.md)
- [Home Assistant and MQTT](MQTT-GUIDE.md)
- [A Better Routeplanner](ABRP-GUIDE.md)
- [Unlocking ADB on the head unit](GUIA-ADB-IHU629G.md)
