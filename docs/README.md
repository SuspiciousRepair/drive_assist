# Drive Assist Documentation Index

Comprehensive engineering reference, hardware reverse-engineering logs, and subsystem documentation for the Geely IHU629G (Geely EX2 / Geometry E) head unit platform.

---

## 📖 User Guides & Transparency

- **[WELCOME.md](WELCOME.md)**  
  Welcome and transparency disclosure: what data the app sees (battery, speed, location, cameras, body), what it does, what actuators it controls, safety boundaries (what it cannot touch), and built-in security guardrails.

- **[INSTALL-GUIDE.md](INSTALL-GUIDE.md)**  
  Post-unlock installation guide: fast setup instructions for drivers who enabled ADB, including the 30-second automated one-shot script, ADB AppControl instructions, and standalone USB install.

- **[MQTT-GUIDE.md](MQTT-GUIDE.md)**  
  Comprehensive guide for Home Assistant integration: broker setup, fallback hosts, Mutual TLS (mTLS) client certificates, full auto-discovery entity list, topic architecture (`drivemem/<vin>/...`), and smart garage automations.

- **[ABRP-GUIDE.md](ABRP-GUIDE.md)**  
  Guide for live telemetry with A Better Routeplanner: generic user tokens, GPS toggles, offline queueing, and pairing Bluetooth OBD2 dongles for precision BMS readings.

- **[TRIP-STATISTICS.md](TRIP-STATISTICS.md)**  
  Trip statistics, daily bar charts, trail-running altimetry (D+/D-), ABRP-style session logs, and roadmap for continuous segments and trip assistant.

- **[SECURITY-SAFETY.md](SECURITY-SAFETY.md)**  
  Vehicle software security and sideloading guide: depth of data exposed in the vehicle (GPS, battery, cameras, home Wi-Fi), platform test key vulnerability, APK repackaging/trojanization risks, and defense-in-depth best practices.

---

## 🚗 Vehicle Data & Hardware Probing

- **[DATA-CATALOG.md](DATA-CATALOG.md)**  
  Consolidated reference of verified vehicle properties and Geely adaptation layer function IDs (`VehicleModules.getAdaptValue`). Grouped cleanly by automotive domain (Powertrain & Energy, Odometer & Speed, HVAC & Climate, Lights & Controls, Body & Doors, Tires & Sensors).

- **[field-catalog.md](field-catalog.md)**  
  Exhaustive catalog of hardware signals, VHAL property IDs, probe results, and adaptation mechanics discovered across vehicle probing sessions. Includes exact function IDs, raw ranges, and safety boundaries.

- **[field-history.md](field-history.md)**  
  Chronological narrative of the hardware reverse-engineering journey: how each finding in the field catalog was uncovered, dead ends encountered, and breakthrough logs.

- **[OEM-MODULES.md](OEM-MODULES.md)**  
  Analysis of decompiled OEM head unit packages (ECARX, NJDA, Settings, SystemUI). Documents internal binder services, hidden intents, and system-level permissions.

- **[STATUS-ICONS.md](STATUS-ICONS.md)**  
  Documentation of the IHU629G status bar icon injection mechanism, priority ranking, and notification slot allocation.

---

## ❄️ Climate & Comfort

- **[COMFORT-TABLE.md](COMFORT-TABLE.md)**  
  The complete architecture and design rationale behind the **Comfort Ruler** and absolute thermal effort scale (`C5..0..W5`). Explains the neutral-zero boundary step and actuator compensation.

- **[CLIMATE-FACTS.md](CLIMATE-FACTS.md)**  
  Verified physical characteristics, limitations, and operational rules of the vehicle's HVAC system and actuators.

- **[historical/COMFORT.md](historical/COMFORT.md)**  
  Historical design log and early prototypes for climate control before the unified `EffortTable` was finalized.

- **[historical/hvac-auto-test.md](historical/hvac-auto-test.md)**  
  Empirical test run logs evaluating factory AUTO HVAC behavior over time.

---

## 📷 Cameras & Dashcam

- **[EVS-CAMERA.md](EVS-CAMERA.md)**  
  Deep dive into the vehicle's Extended View System (EVS): camera topology, hardware HAL, native binder interface (`android.hardware.automotive.evs@1.0`), and frame capture pipeline.

- **[DASHCAM.md](DASHCAM.md)**  
  Architecture for continuous dashcam recording: MediaCodec encoding, hardware-accelerated transformation, dynamic WebVTT telemetry subtitle generation, and local HTTP serving.

---

## 🎨 UI & Visualization

- **[ARTE.md](ARTE.md)**  
  Design and implementation details of the full-bleed vector art background (`SkylineArtView`), perspective framing, and integration with the embedded Home Assistant card.

---

## 🔧 Diagnostics & System Setup

- **[GUIA-ADB-IHU629G.md](GUIA-ADB-IHU629G.md)**  
  Step-by-step setup guide for ADB access over Wi-Fi/Ethernet, root environment verification, and package management on the IHU629G unit.

- **[DIAGNOSTICS.md](DIAGNOSTICS.md)**  
  Guide to internal diagnostic probes (`HvacProbe`, `HvacSet`), property sweeps, remote triggering via Home Assistant, and log extraction.

---

## 🛠️ Related Subsystems & Tooling

- **[modehelper/README.md](../modehelper/README.md)**: Privileged companion app (`android.uid.system`) handling drive mode memory, silent OTA package installation, hardware dashcam recording, and network ADB security guards.
- **[helpers/calib/README.md](../helpers/calib/README.md)**: Fisheye camera calibration tooling, checkerboard target generation, and intrinsic parameter solving.

---

## 🤝 References & Community Credits

Drive Assist builds upon foundational research and access techniques developed by the automotive and reverse-engineering community. We gratefully acknowledge the following sources:

- **Jean na Estrada**: Brazilian community tutorials ([YouTube Video](https://youtu.be/T-77g9hn5LU)) and repository providing verified OTA unlock packages (`1111` and `1114` firmware patches).
- **4PDA Community**: Forum thread *«Автомобильное ГУ Geely EX2 IHU629G - Обсуждение»* for hardware analysis, partition layouts, recovery logs, and Flyme Auto exploration.
- **XDA Developers Community**: Documentation of the dynamic engineering mode password calculation algorithm.
- **XeThongMinh.net**: Vietnamese community tutorials detailing IHU629G screen unlock procedures and ADB AppControl workflows.

*Note: The community resources above document head unit unlock and ADB activation. Drive Assist and ModeHelper are independent open-source software applications developed for telemetry, HVAC automation, dashcam recording, and Home Assistant integration.*
