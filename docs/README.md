# Drive Assist guides

**English** · [Português (Brasil)](README.pt-BR.md)

Drive Assist runs on the Geely EX2 / Geometry E centre screen. It remembers
preferences the factory software forgets, makes climate controls easier to use,
records driving and charging history, and can connect the car to services you
already control.

![Drive Assist dashboard](screenshots/comfort-dashboard-live.png)

## Start here

You only need four guides for normal use:

1. **[See what Drive Assist does](DRIVER-GUIDE.md)** — dashboard, climate,
   statistics, charging costs, Home Assistant, ABRP, and dashcam.
2. **[Install it](QUICK-INSTALL.md)** — use the one-file USB method. ADB
   instructions remain available for experienced users.
3. **[Understand root ADB security](ADB-ROOT-SAFETY.md)** — why this access is
   powerful and how to protect the car and your home network.
4. **[Understand app safety and privacy](WELCOME.md)** — what Drive Assist reads,
   what it can control, and the things it cannot do.
5. **[Connect another service](#optional-connections)** — only if you want Home
   Assistant or ABRP.

## What drivers get

- A large, glanceable climate dashboard and a simple cooler-to-warmer comfort
  control.
- Drive mode and regeneration preferences that can return automatically after
  startup.
- Daily distance, energy, efficiency, altitude, drive, parking, and recharge
  history.
- Recharge cost tracking, including editing earlier sessions and recording free
  charging correctly.
- Optional vehicle data in Home Assistant, a location-aware garage button, and
  information cards sent from your home server to the car.
- Optional live data for A Better Routeplanner and an integrated dashcam.

The [driver guide](DRIVER-GUIDE.md) explains each feature with real screenshots
from the car.

## Optional connections

- **[Home Assistant](HOME-ASSISTANT.md)** brings battery, range, position, charging,
  and other readings into your own server. It can also send contextual cards and
  a garage command to the car.
- **[A Better Routeplanner](ABRP.md)** receives live battery and location
  information for more useful long-trip planning. OBD2 support is optional.
- **[Dashcam](DASHCAM.md)** explains recording, storage, telemetry subtitles, and
  USB export.

## Help and troubleshooting

- [Installation and update problems](INSTALL-GUIDE.md#troubleshooting)
- [In-app diagnostics](DIAGNOSTICS.md)
- [ADB access to the head unit](GUIA-ADB-IHU629G.md)
- [Security when installing third-party car software](SECURITY-SAFETY.md)
- [Plain-language root ADB safety notice](ADB-ROOT-SAFETY.md)

## Technical library

The documents below explain how the app and vehicle work. They retain the
technical detail needed to verify and reproduce each discovery.

### Vehicle signals and reverse engineering

- [Verified vehicle data catalog](DATA-CATALOG.md)
- [Full field catalog](field-catalog.md)
- [Discovery history and experiments](field-history.md)
- [OEM module analysis](OEM-MODULES.md)
- [Status-bar icon mechanism](STATUS-ICONS.md)

### Climate, cameras, and interface

- [Comfort scale and HVAC state machine](COMFORT-TABLE.md)
- [Verified climate behaviour](CLIMATE-FACTS.md)
- [Daily statistics calculations](TRIP-STATISTICS.md)
- [Camera and EVS interface](EVS-CAMERA.md)
- [Dashcam architecture](DASHCAM.md)
- [Dashboard artwork](ARTE.md)

### Engineering and project quality

- [Software architecture and safety audit](ARCHITECTURE-SAFETY-AUDIT.md)
- [MQTT and Home Assistant protocol reference](MQTT-GUIDE.md)
- [ABRP and OBD2 protocol reference](ABRP-GUIDE.md)
- [Code quality and test report](CODE-QUALITY-REPORT.md)
- [Release readiness](RELEASE-READINESS-MATRIX.md)
- [ModeHelper companion](../modehelper/README.md)
- [Camera calibration tools](../helpers/calib/README.md)

## Community foundations

Drive Assist builds on head-unit access and research shared by Jean na Estrada,
the 4PDA community, XDA Developers, and XeThongMinh.net. Those communities explain
how owners gained access to the IHU629G. Drive Assist and ModeHelper are independent
open-source applications built on top of that work.
