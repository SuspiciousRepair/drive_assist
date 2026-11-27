# The Comfort Ruler

The car has no cabin temperature sensor. So the driver is the sensor: a
**Cooler** tap means "a little more cooling", a **Warmer** tap means "a little
less". The ruler is one list of small moves, from quiet to loud
(`EffortTable.java`):

```
C10 C9 C8 C7 C6 C5 C4 C3 C2 C1   0   W1 W2 W3 W4 W5
```

![Drive Assist Comfort View - Comfort Ruler UI](screenshots/comfort-view.png)

The rules:

1. **One mark for each move.** A mark always means the same thing.
2. **One tap = the next mark.** One setting changes (two at a few steps, see below).
3. **The first tap from off is always C1 or W1.**
4. **One list for all weather.** The outside temperature does not change a
   mark. A list that changes by itself is a surprise.
5. **Nothing is learned.** The list is fixed.

---

## 1. The cool side: 10 marks

| Mark | AC | Air | Direction | Fan | Temperature | How it feels |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| C1 | off | fresh | face | 1 | - | outside air, low |
| C2 | on | fresh | glass | 1 | 24 | gentle, nothing cold on the arm |
| C3 | on | fresh | glass | 2 | 24 | cool, not chilling |
| C4 | on | recirculation | glass | 2 | 24 | colder cabin, still indirect |
| C5 | on | recirculation | glass | 3 | 24 | |
| C6 | on | recirculation | face | 2 | 24 | direct air, fan one step down |
| C7 | on | recirculation | face | 3 | 24 | |
| C8 | on | recirculation | face | 4 | 24 | |
| C9 | on | recirculation | face | 6 | 22 | colder and loud |
| C10 | on | recirculation | face | 8 | 17 | all-out |

* AC to the glass is indirect: the cabin gets cool and no cold air hits the arm.
  AC to the face is direct.
* AC with fresh air is gentle: the warm outside air softens the cold. AC with
  recirculation is strong: the same air is cooled again and again.
* The set temperature is blunt, so the list uses it late, with the loud fan
  speeds.
* The move to the face (C5 -> C6) takes the fan one step down: direct air feels
  stronger, so the step stays small.
* Four steps change two settings: C1 -> C2 (AC and direction), C5 -> C6
  (direction and fan), C8 -> C9 and C9 -> C10 (temperature and fan).

**Recirculation and the weather.** C4 turns recirculation on in all weather.
On a mild day it helps less than on a hot day, but the tap still makes one
change that the driver can see. The climate log saves recirculation and the
air direction, so the list can change later with facts.

## 2. The warm side: 5 marks

| Mark | Heat | Air | Direction | Fan | Temperature | How it feels |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| W1 | on | fresh | feet | 1 | 26 | a little warm at the feet |
| W2 | on | fresh | feet | 2 | 26 | |
| W3 | on | fresh | feet | 3 | 26 | |
| W4 | on | fresh | feet | 3 | 28 | warmer air, same noise |
| W5 | on | fresh | face + feet | 6 | 32 | all-out |

* The heat is `HVAC_AC_ON` too: this is an EV, and the heat pump runs through
  the same compressor flag.
* The warm side never uses recirculation: outside air keeps the glass clear.
* W1 to W4 blow on the feet in all weather. For fog on a cold day, use the
  car's defrost button.

## 3. The middle

The machine reverses at 24.5 °C (`MIDDLE_C`). This is a property of the car and
does not move with the weather. Every setpoint of the list is clear of it: 24
and lower cools, 26 and higher heats.

## 4. A tap when the car is not on a mark

A change on the car's own climate screen can leave the car between marks.
`EffortTable.fit()` shows the mark with the fewest settings out of line, and the
ruler draws it hollow.

Every setting has its own place on the list. Then (`EffortTable.step()`):

* **A tap away from off**: the setting that is furthest behind moves one place
  forward.
* **A tap towards off**: the setting that is furthest ahead moves one place
  back.
* A value that is on no mark (fan 5, air to the feet while cooling) is put on
  the list by the first tap.

Nothing else moves, so the fan never jumps. Example (issue #209): fan 1 and the
AC turned on by hand, air to the face, at 20 °C outside. **Cooler** moves only
the air to the glass: that is C2. **Warmer** turns only the AC off: that is C1.

## 5. The bar

`Style.effortScale()` draws the bar as a level meter:

* **Off**: one long grey bar.
* **Cooling**: 10 marks. They fill with the cool colour from right to left; C1
  is the mark at the right end.
* **Warming**: 5 marks. They fill with the warm colour from left to right.
* **Not on a mark**: the last filled mark is an outline.
* **Front defrost button on**: the bar is dimmed and the ruler waits.

## 6. How a tap reaches the car

* A tap changes only the state the ruler wants (`ComfortRuler.tap`, its own
  thread). A burst of taps becomes one write.
* The write runs on `CarActor`'s thread, in this order: power, setpoint, AC,
  recirculation, direction, fan. The setpoint goes first so the machine never
  runs in the wrong mode. One check 800 ms later writes again what did not
  hold.
* The first tap after a quiet minute reads the car first, so a car that woke
  with its own climate is not treated as "off".
* Every 30 s the ruler reads the car and follows a hand change.
* The ruler never changes the car by itself. Only a tap writes to the car.

## 7. The climate log

Every tap, write and check is a `comfort_event` row in `car.db`, and a
`sample` row every 5 minutes. The `detail` of an `apply` row reads
`col=C4@24/f2/rec/glass out=30.0`; a `~` after the mark (`C8~@24/f5/rec/face`)
means the state is not exactly on it. This log is the data the list was built
from.

## 8. Where the values come from

The list is a first version, made from one driver's log (434 ruler changes in
11 weeks). What the log said about the ruler before it:

* fan 6 was left within 2 minutes more often than kept;
* 60 of 90 taps from a hollow mark needed more taps right away;
* at 20-24 °C outside, the fan-only steps led up, not down;
* one tap changed 2 or more settings 76 % of the time.

The warm side has little data. Its values are a guess until a cold season.
