# Vehicle Data Catalog

A consolidated reference of confirmed vehicle properties and Geely adaptation layer function IDs on the IHU629G. All properties are accessed via `CarPropertyManager` inside the installed APK. For detailed probing methodologies, logs, and historical investigations, see `field-catalog.md`.

## Powertrain & Driving

| Property / Function ID | Name | Type | Access | Values / Scaling | Notes |
|---|---|---|---|---|---|
| `570491136` | DRIVE | int | RW | Eco=`570491137`, Comfort=`570491138`, Sport=`570491139` | Areas `0, 1`. Writes require vehicle in ready/drive state to hold. |
| `537003264` | REGEN | int | RW | Low=`537003265`, Mid=`537003266`, High=`537003267` | Areas `0, 1`. |
| `289408001` | GEAR_SELECTION | int | R | `1`=N, `2`=R, `4`=P, `8`=D | Adapted value (`VehicleGear` enum). Alias: `0x2140800f`. |
| `291504647` | PERF_VEHICLE_SPEED | float | R | km/h | Area `0`. Already scaled (`div=1`). |
| `289407492` | ODOMETER | float | R | km | Cumulative vehicle distance. Area `0/1`. |
| `658548345` | TRIP_KM | float | R | km | Current trip distance. Area `0/1`. |
| `557884450` | CRUISE_STATE | float | R | `0.0`=OFF, `1.0`=ARMED, `7.0`=ACTIVE | Area `0`. Integer alias: `557887630`. Set speed property remains unmapped. |
| `557885463` | PARK_MODE | int | RW | `0`=off; `0x201B0100 \| duration` | Duration: `0x02`=30m, `0x04`=1h, `0x06`=2h, `0x08`=3h, `0x0A`=4h, `0x0C`=5h, `0x13`=unlimited. |

## Battery & Charging

| Property / Function ID | Name | Type | Access | Values / Scaling | Notes |
|---|---|---|---|---|---|
| `557885165` | BATTERY_SOC | float | R | % (0..100) | Adaptation layer pre-scaled float. Area `0/1`. |
| `289407752` | RANGE | float | R | km | Estimated remaining range. Area `0/1`. |
| `557887621` | PORT_CONNECTED | int | R | `3`=connected, `0`=disconnected | Physical plug connection status. Reliable indicator independent of charging current. |
| `605029888` | CHARGE_LIMIT | int | RW | 5..32 A | User-selectable charge current limit. Area `0/1`. |
| `605291008` | CHARGE_CURRENT | float | R | A | Actual charging current. Zeroed when not actively charging to avoid residual sense drift. |
| `605290752` | CHARGE_VOLTAGE | float | R | V | Charging voltage. Zeroed when not actively charging. |
| `605028608` | CHARGE_SWITCH | int | RW | `609`=STOP, `610`=RESTART, `611`=START_NOW | Explicit charge control commands. Area `0`. |

## Climate Control (HVAC)

| Property / Function ID | Name | Type | Access | Values / Scaling | Notes |
|---|---|---|---|---|---|
| `354419984` | HVAC_POWER_ON | boolean | RW | true / false | Master AC power switch. Adapted boolean (raw value is `2` when off). |
| `354419973` | HVAC_AC_ON | boolean | RW | true / false | AC compressor enable. |
| `0x15600503` | ZONED_TEMP_SETPOINT | float | RW | 16..32 °C (`°C = 17 + raw / 2`) | Target setpoint. Quantized to 1 °C steps; writes of half-degrees read back truncated. Area 4 is not physical (reverts to synced value). The center point is a mode boundary crossing between heating and cooling. |
| `557884279` | AC_AMBIENT_TEMP | int | R | °C: `(raw - 80) / 2` | Outside ambient temperature sensor. Area `0`. |
| `356517120` | HVAC_FAN_SPEED | int | RW | 0..8 | Blower speed: `0`=off, `1`..`8` active levels. Alias: `557846559` (`0x2140101F`). |
| `557846560` | ZONED_FAN_DIRECTION | int | RW | Bitmask: `1`=FACE, `2`=FLOOR, `3`=FACE\|FLOOR, `4`=DEFROST, `6`=DEFROST\|FLOOR | Area parameter is ignored. Value `5` (DEFROST+FACE) is rejected by vehicle. Alias: `356517121` (`0x15400501`). |
| `354419976` | RECIRCULATION | boolean | RW | true / false | Cabin air recirculation (`0x15200508`). |
| `354419978` | HVAC_AUTO_ON | boolean | RW | NOOP | Accepts writes with no effect; do not delegate fan or mode logic to it. (`354419975` is `MAX_DEFROST`, not AUTO). |
| `HVAC_IN_OUT_TEMP` | CABIN_TEMP | - | - | NOT_AVAILABLE | Vent and cabin temperatures are not exposed via VHAL. |

## Cabin Lighting & Interior

| Property / Function ID | Name | Type | Access | Values / Scaling | Notes |
|---|---|---|---|---|---|
| `537528576` | AMBIENT_COLOR | int | RW | `0xRRGGBB` hex color | Area candidates `{5, 0, 1}` must be swept and read back to confirm execution. |
| `704708864` | AMBIENT_BRIGHTNESS | int | RW | 0..20 | `0`=off. Setting brightness to non-zero is required before color writes will take effect. |
