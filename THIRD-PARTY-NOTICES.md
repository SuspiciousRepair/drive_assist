# Third-party notices and credits

Drive Assist is GPLv3. It includes, or is built on the work of, the projects
below. Their licenses are compatible with GPLv3 and are kept with their code.

## roadcast — by Timoteo Sousa

- https://github.com/Timoteohss/roadcast
- Copyright 2026 Timoteo Sousa. Apache License 2.0.
- **What we use:** the whole CAN reader daemon, `roadcastd`, in
  `third_party/roadcast/` (upstream commit 22153ec). Its C code is unchanged.
  It ships inside the ModeHelper apk and powers "Car data (CAN)".
- Its signal catalog (`data/dbc.json`), protocol design and the research
  behind them (reading the VHAL's `MMI_Rx_*` buffers) are Timoteo's work.
- Full license and notice: `third_party/roadcast/LICENSE`,
  `third_party/roadcast/NOTICE`.

## Capy — by Timoteo Sousa

- https://github.com/Timoteohss/capy
- Apache License 2.0.
- **What we use:** `modehelper/.../LocalAdbShell.java` is a Java port of
  Capy's `LocalAdbShell.kt` (the local ADB shell-v2 client). The way the
  daemon is installed and started through the car's own adbd, the checks on
  it, and the signal choices and bands for battery power follow Capy's
  roadcast integration. Capy proved all of this on EX2 cars first.

Thank you, Timoteo. Without roadcast and Capy, Drive Assist would have no
exact energy without a dongle.

## libuv

- https://github.com/libuv/libuv, v1.51.0
- Copyright the libuv project contributors. MIT License (plus the notices in
  `LICENSE-extra`).
- **What we use:** the event-loop library roadcastd is built on. Not stored
  in this repo: `tools/build-roadcastd.sh` downloads the official v1.51.0
  source and checks its SHA-256. Its license ships in the helper apk with
  the binary (`assets/licenses/libuv-LICENSE`).

## Eclipse Paho MQTT client

- https://github.com/eclipse-paho/paho.mqtt.java, v1.2.5
- Copyright the Eclipse Paho contributors. Dual licensed: Eclipse Public
  License 2.0 or Eclipse Distribution License 1.0 (see `about.html` inside
  the jar).
- **What we use:** the library, unchanged, as `drivemem/libs/paho.jar`. It is
  the MQTT connection to Home Assistant.

## MPAndroidChart — by Philipp Jahoda

- https://github.com/PhilJay/MPAndroidChart, v3.1.0
- Copyright Philipp Jahoda. Apache License 2.0.
- **What we use:** the library, unchanged, for charts in the statistics
  screens.

## OpenStreetMap

- https://www.openstreetmap.org/copyright
- Map data © OpenStreetMap contributors, Open Database License (ODbL).
- **What we use:** map pictures from `tile.openstreetmap.org` under the trip
  routes, within its tile usage policy. The map shows the
  "© OpenStreetMap" credit.

## QR Code generator library — by Project Nayuki

- https://www.nayuki.io/page/qr-code-generator-library
- Copyright (c) Project Nayuki. MIT License.
- **What we use:** its Java version (upstream commit bcfe1b0), unchanged, in
  `drivemem/src/main/java/io/nayuki/qrcodegen/`. It draws the QR code a
  phone scans for Phone access. The license is at the top of each file.

## Fart mode sound — by JohnSumisu (Wikimedia Commons)

- `modehelper/assets/locksound/fart.wav` — from
  https://commons.wikimedia.org/wiki/File:Flatulence.wav
- By JohnSumisu, own work. CC BY-SA 3.0
  (https://creativecommons.org/licenses/by-sa/3.0/).
- **Changes:** silence trimmed at both ends, cut to 3 s with a short fade
  out, made louder (loudness normalised), and saved as 44.1 kHz 16-bit mono
  WAV. This edited file is shared under the same CC BY-SA 3.0 license.
