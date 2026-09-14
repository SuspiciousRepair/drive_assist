# How Drive Assist talks to the car

This document describes the interfaces that the current code actually uses on the Geely EX2 / Geometry E IHU629G. It separates the in-car Android head unit, the Android Vehicle HAL (VHAL), the external OBD2 adapter, and the OEM TBOX. Those are different systems; having access to one does not imply access to the others.

## At a glance

```text
Vehicle ECUs ── OEM vehicle network ── VHAL / OEM adaptation ── Binder ── Drive Assist
     │                                                                   │
     ├── diagnostic CAN ── OBD2 port ── Bluetooth ELM327 ───────────────┤
     │                                                                   │
     └── OEM telematics path ── TBOX ── OEM backend / phone app          │
                                                                         │
                                                             Android head unit UI,
                                                             history, MQTT and ABRP
```

Drive Assist runs on the head unit. It does **not** directly connect to the vehicle CAN bus, nor does it currently use the TBOX or an OEM cloud API.

## Android head unit (IHU629G)

The IHU is the Android 9 centre display and the host for this project. The normal app is `drivemem` (`com.geely.drivemem`); it owns the UI, local history, telemetry publishing, and most vehicle interaction. Its manifest requests the automotive powertrain, energy, and window-control permissions as well as Bluetooth and location permissions (`drivemem/src/main/AndroidManifest.xml`).

The unit's firmware configuration permits these automotive permissions for the installed application on the researched device. That is device-specific, not a portable Android-app guarantee. Another firmware or vehicle may deny the calls, expose different property IDs, or implement them differently.

`modehelper` is a separate, headless companion. It is platform-signed and runs as `android.uid.system`, because durable drive/regen restoration, privileged Bluetooth pairing, settings, and silent installation need system privileges. The UI remains a normal app: Android 9 WebView cannot run under the system UID. See `modehelper/AndroidManifest.xml` and `ARCHITECTURE-SAFETY-AUDIT.md`.

## VHAL: the main read/write path

VHAL is Android Automotive's Vehicle Hardware Abstraction Layer. Here, `android.car.CarPropertyManager` calls the OEM vehicle adaptation layer over Binder; that layer is the boundary which communicates with vehicle-side systems. Drive Assist calls Android's property API, not CAN frames directly.

### Reading

`CarAccess` connects to `Car.PROPERTY_SERVICE` and wraps typed property reads. `CarActor` is the only intended owner of that connection: it puts reads, writes, periodic sampling, and change callbacks on one `HandlerThread` (`drivemem/src/main/java/com/geely/drivemem/car/CarAccess.java` and `CarActor.java`). This prevents concurrent synchronous VHAL Binder calls, which are unreliable on the head unit.

The actor polls sampled properties and registers on-change callbacks where available. It publishes normalized values on `EntityBus`; UI views, local history, MQTT, and ABRP consume those values without each making their own VHAL calls. Confirmed IDs, areas, units, and limitations are maintained in [the vehicle data catalog](DATA-CATALOG.md).

Examples read through this path include gear and speed, odometer, battery state of charge and range, charge-port/charging state, climate state, door/window state, drive and regeneration selections, and ambient-light state. A failed VHAL read is treated as unavailable data, not as a safe inferred value.

### Writing

The app writes only known, reverse-engineered VHAL/adaptation properties. Implemented controls include climate settings, window/door comfort actions, drive and regeneration mode, charge-current limit and start/stop commands, parking mode, and ambient lighting. The exact supported set and known no-op properties are in [DATA-CATALOG.md](DATA-CATALOG.md).

A successful `set*Property()` call means only that the VHAL accepted the Binder request; it does **not** prove an ECU performed the action. Where practical the code reads back the result (ambient colour is one example), clamps inputs such as the 5--32 A charge-current limit, and uses validated area fallbacks. Values and areas must not be generalized to another vehicle without testing.

Safety rules apply above the raw interface: gear tracks vehicle state, updates and mode restoration are Park-gated, and the actor serializes access. There are diagnostic/raw helpers in `CarAccess`; they are not a license to add arbitrary controls. Do not expose braking, steering, restraint, or other safety-critical writes through the UI or network integrations.

## OBD2: optional diagnostic read path

OBD2 is separate from VHAL. With an owner-provided ELM327-compatible Bluetooth adapter in the diagnostic port, `Obd2Reader` opens Classic Bluetooth first and falls back to BLE. It configures ISO 15765-4 CAN at 500 kbit/s and queries the battery/BMS ECU using the known diagnostic header (`drivemem/src/main/java/com/geely/drivemem/sensors/Obd2Reader.java`).

The current implementation is read-only. It obtains battery SOC, voltage, current, power, battery temperature, and reported speed; it does not send ECU coding, actuator, or configuration commands. The reader is optional and independent of VHAL. Its finer-grained battery readings can supplement telemetry and ABRP when fresh, while VHAL remains usable without a dongle.

The adapter is paired through Bluetooth. `modehelper` can assist with pairing because the factory UI may hide devices it discovers; that capability does not make the reader a general CAN writer. See [ABRP and OBD2 protocol reference](ABRP-GUIDE.md) for its externally visible telemetry behavior.

## TBOX: not an integration path in this project

The TBOX is the vehicle's telematics control unit: it generally owns cellular connectivity and OEM remote-service communication. It is not the Android head unit, VHAL API, or an OBD2 adapter.

There is no TBOX protocol client, TBOX credential store, OEM cloud API client, or direct TBOX bus interface in this repository. Accordingly, Drive Assist does not read vehicle data from the TBOX and does not issue remote commands through it. Data sent to Home Assistant or ABRP originates on the head unit from VHAL, optional OBD2, and Android location services, then travels over the owner-configured network.

Treat TBOX reverse engineering or cloud/telematics control as a separate, higher-risk project. It requires vehicle- and account-specific protocol validation, explicit security review, authentication handling, rate limits, and an independent safety model before it could be supported.

## Operational boundaries

- This architecture is verified for the researched IHU629G firmware only.
- VHAL values are OEM-defined; a property may be absent, read-only, area-specific, or accept a write with no physical effect.
- OBD2 is optional and read-only in the current code.
- The project has no TBOX control path.
- Root ADB and platform privileges are powerful but are not part of the normal vehicle-control data path. Follow [ADB root safety](ADB-ROOT-SAFETY.md) and [security guidance](SECURITY-SAFETY.md) before installing or modifying code.
