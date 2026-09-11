# HVAC Hardware Characteristics & Control Invariants

Technical reference for physical characteristics, VHAL property behaviors, actuator constraints, and operational invariants of the climate control subsystem on the Geely IHU629G.

---

## 1. Verified Readable Properties

| Subsystem | Property ID | Type | Conversion / Range | Notes |
|---|---|---|---|---|
| **Outside Ambient Temp** | `557884279` | `int` | `(raw - 80) / 2 = °C` | Reliable hardware thermistor reading. Area `0`. |
| **Blower Speed** | `356517120` | `int` | `0` = off, `1..8` = speed level | Alias: `557846559`. Truthful read/write. |
| **Airflow Direction** | `557846560` | `int` | Bitmask (see table below) | Alias: `356517121`. Area index is ignored. |
| **Target Setpoint** | `0x15600503` | `float` | `16..32` °C | Quantized to whole 1.0 °C steps. |
| **Cabin Recirculation** | `354419976` | `boolean` | `true` / `false` | Physical flap actuator. |
| **A/C Compressor** | `354419973` | `boolean` | `true` / `false` | Controls high-voltage AC compressor clutch/inverter. |
| **HVAC Master Power** | `354419984` | `boolean` | `true` / `false` | Master climate system enable. |

### Airflow Direction Bitmask

The vehicle ECU strictly enforces valid damper position combinations:

| Value | Mask Definition | Damper Actuation | Physical Status |
|---|---|---|---|
| `1` | `FACE` | Dash vents only | Valid |
| `2` | `FLOOR` | Footwell vents only | Valid |
| `3` | `FACE \| FLOOR` | Bi-level (dash + footwell) | Valid |
| `4` | `DEFROST` | Windshield defrost vent only | Valid |
| `5` | `DEFROST \| FACE` | Windshield + dash vents | **Rejected** (actively refused by vehicle ECU) |
| `6` | `DEFROST \| FLOOR` | Windshield + footwell vents | Valid |

---

## 2. Unexposed Sensors & Stubbed Interfaces

* **Cabin Temperature**: `HVAC_IN_OUT_TEMP` and `AC_INSIDE_TEMP` (`557884281`) return `NOT_AVAILABLE` or constant sentinel `0` (-40 °C). The vehicle ECU regulates cabin temperature using an internal thermistor, but does not surface the value across VHAL.
* **Duct Temperature**: Vent output temperature is unmapped across VHAL.
* **Environmental Sensors**: No humidity sensors or solar load / sun-angle sensors are present.
* **Zoning & Seat Heating**: Dual-zone temperature controls and seat heating/ventilation properties declared in AOSP tables are unwired on this vehicle trim.
* **`HVAC_AUTO_ON` (`354419978`)**: Accepts writes without error but executes no physical actuation (NOOP).

---

## 3. Physical & Actuator Invariants

1. **Internal Closed-Loop Regulation**: The factory HVAC ECU actively controls compressor displacement and vent blending to converge on the target setpoint (`0x15600503`). External software cannot close its own temperature feedback loop and must treat the setpoint as an absolute thermal target.
2. **Heat-Pump Mode Boundary**: The vehicle uses a single refrigerant heat-pump loop for both heating and cooling. The center of the setpoint range acts as an operational mode boundary; crossing it causes the heat-pump four-way reversing valve to switch cycles.
3. **Blower Autonomous Modulation**: While pursuing a newly applied setpoint, the vehicle ECU may adjust blower speeds autonomously to manage thermal transfer over the evaporator/heater core.
4. **Actuator Churn on Redundant Writes**: Writing an identical state flag to `CarPropertyManager` (e.g., commanding `HVAC_AC_ON = true` when already active) causes the HVAC controller to re-plan airflow and re-cycle dampers, creating audible actuator noise. All software writes must be transition-gated.
5. **Actuator Cost Hierarchy**:
   * **Level 1 (Free / Silent)**: Air direction dampers and recirculation flap. Zero electrical penalty, no cabin acoustic noise.
   * **Level 2 (Electrical Cost)**: A/C compressor activation. Consumes high-voltage battery power.
   * **Level 3 (Target Boundary)**: Setpoint adjustment. Directs thermal convergence.
   * **Level 4 (Acoustic Cost)**: Blower fan level. Direct acoustic disturbance; higher fan speeds should only be applied when lower-tier levers cannot satisfy thermal demand.
