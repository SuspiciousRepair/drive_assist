# Comfort ruler v2: one tap, one change

Status: agreed 2026-11-27, in code. Issue: #209. The reference is `docs/COMFORT-TABLE.md`.

## The problem

Today each step of the ruler is a full recipe (AC, temperature, fan,
recirculation, air direction). One tap swaps the whole recipe.

- One tap can take the fan from 1 to 6.
- After a change on the car's own climate screen, the app guesses the nearest
  recipe (the hollow mark). The next tap applies that full recipe.

What one driver's log says (434 ruler changes, 11 weeks):

| Finding | Numbers |
| :--- | :--- |
| Fan 6 is the only fan speed left within 2 minutes more often than kept | 16 left, 9 kept |
| A tap from a hollow mark needs more taps right away | 60 of 90 |
| At 20-24 °C outside, fan-only steps lead up, not down | 32 up, 3 down |
| A change moves 2 or more steps at once | 112 of 434 |
| At the same outside temperature, 10:00-16:00 settles about 1 step cooler than later | at 29, 32 and 35 °C |

## The idea

1. **One list of small moves**, in order from quiet to loud.
2. **One mark on the ruler for each move.** A mark always means the same thing.
3. **One tap = the next mark.** One setting changes, nothing else.
4. **One list for all weather.** The weather does not change a mark.

## The cool side: 10 marks

Four things change how the air feels in fine steps: the AC, where the air
goes (glass or face), fresh air or recirculation, and the fan. The set
temperature works too, but it is blunt: one change is a large change in how
it feels. So the list uses it once, late, as one clear step.

Each mark changes one setting from the mark before.

| Mark | AC | Air | Direction | Fan | Temperature | How it feels |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| C1 | off | fresh | face | 1 | - | outside air, low |
| C2 | on | fresh | glass | 1 | 24 | gentle breeze, nothing cold on the arm |
| C3 | on | fresh | glass | 2 | 24 | cool, not chilling |
| C4 | on | recirculation | glass | 2 | 24 | colder cabin, still indirect |
| C5 | on | recirculation | glass | 3 | 24 | |
| C6 | on | recirculation | face | 2 | 24 | direct air, fan one step down |
| C7 | on | recirculation | face | 3 | 24 | |
| C8 | on | recirculation | face | 4 | 24 | |
| C9 | on | recirculation | face | 6 | 22 | colder and loud |
| C10 | on | recirculation | face | 8 | 17 | all-out |

- AC to the glass is indirect: the cabin gets cool and no cold air hits the
  arm while driving. AC to the face is direct.
- AC with fresh air is gentle: the warm outside air softens the cold.
  AC with recirculation is strong: the same air is cooled again and again.
- The temperature step and the loud fan speeds come last.
- The move to the face (C5 -> C6) takes the fan one step down: direct air
  feels stronger, so the step stays small.
- Four steps change two settings: C1 -> C2 (AC and direction), C5 -> C6
  (direction and fan), C8 -> C9 and C9 -> C10 (temperature and fan).
- A mark always means the same thing. On a hot day the driver taps further
  along.

What the log says (state saved every 5 minutes, 148 hours of cooling):

- 81 % of the time the car was on a state that is a mark of this list
  (counted before the direction was added; the log does not save it).
- "AC, fresh air, fan 1" was set by hand for about 8 hours, in all weather,
  mostly after 16:00. It is now C2.
- Fan 2 with AC is one third of the AC time. Fan 6 and fan 8 are rare.
- "No AC, fan 2" and "no AC, fan 4" came from the old presets and were left
  quickly. They are not on the list.

## Recirculation and the weather

Decided 2026-11-27: one list for all weather. C4 turns recirculation on on
a mild day too. The warm side never uses recirculation, as today.

Why: an earlier draft kept recirculation off below 24 °C and passed over
C4. That made a mark mean two things, and the ruler changed the car by
itself when the weather moved. In the log, the time of day explains the
wishes better than the outside temperature. A list per weather follows the
weaker signal and is v1 again. The log now saves recirculation and the air
direction, so the list can change later with facts.

## A tap when the car is not on a mark

This is the #209 case. Each setting has its own place on the list.

- **Cool tap**: the setting that is furthest behind moves one place forward.
- **Warm tap**: the setting that is furthest ahead moves one place back.

Example: the driver sets fan 1 and turns the AC on by hand, air to the face.

- Today: the next tap applies a full recipe. The fan jumps.
- v2: one tap moves one setting by one place. The fan never jumps.

The mark stays hollow until all settings are on the same place.

## The first tap from off

When the car wakes, it puts back its last climate by itself, and the ruler
shows that mark. So a real "off" is not the usual start: 50 of 233 wishes in
the log (21 %).

From a real "off", the first Cool tap always goes to C1 and the first Warm
tap always goes to W1. No surprise: a tap always does the same thing.

A first tap to "the driver's usual mark" was looked at and refused. It saves
taps from off (see the replay), but the driver cannot know what the tap will
do.

## Replay of the log against this list

A wish = taps less than 2 minutes apart. 233 cool wishes end on a mark of
this list (83 % of 280). The log does not have the air direction, so fan 2
and fan 3 with recirculation are counted as glass.

| | v1 (today) | v2, first tap to C1 | v2, first tap to the usual mark |
| :--- | :--- | :--- | :--- |
| Taps for one wish (mean) | 2.3 | 3.4 | 3.0 |
| Taps from a real "off" (mean) | 2.9 | 4.5 | 2.8 |
| The fan goes louder than start and end on the way | 12 % | 0 | 0 |
| One tap changes 2 or more settings | 76 % | 4 of 9 steps | 4 of 9 steps |

- v2 needs more taps: 10 marks, not 5. Each tap is small and none overshoots.
- The count is hard on v2: the end states are the ones v1 offered. With
  finer marks the driver can stop earlier.
- The first tap to the usual mark saves taps from "off", but it is refused:
  no surprises.

## The indicator

- **Off**: one long grey bar.
- **Cooling**: the bar splits into 10 marks, one for each cool step. C1 turns the mark at the
  right end blue. C2 turns the two marks on the right blue. Each tap fills
  one more mark, from right to left, until all marks are blue at the top
  step.
- The bar shows how much effort is in use, like a level meter. It replaces
  today's pointer on an 11-mark scale.

Open:

- **Warming**: the bar splits into 5 marks. They fill with the warm colour
  from left to right, W1 at the left end.
- **The car is not on a mark.** Idea: fill up to the nearest mark and draw
  the last filled mark as an outline.

## The warm side: 5 marks

Warming needs fewer steps than cooling. It starts as a little warm air to
the feet. Each mark changes one setting, but the last one.

| Mark | Heat | Air | Direction | Fan | Temperature | How it feels |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| W1 | on | fresh | feet | 1 | 26 | a little warm at the feet |
| W2 | on | fresh | feet | 2 | 26 | |
| W3 | on | fresh | feet | 3 | 26 | |
| W4 | on | fresh | feet | 3 | 28 | warmer air, same noise |
| W5 | on | fresh | face + feet | 6 | 32 | all-out |

- The warm side never uses recirculation, as today: outside air keeps the
  glass clear.
- W1 to W4 blow on the feet in all weather. An earlier draft added the
  glass below 12 °C. It went away with the other weather logic (see
  "Recirculation and the weather").
- Today W1 blows on the glass and W2 to W4 use fans 2, 4 and 6. The new list
  is quieter and starts at the feet.
- The log has only 23 warm changes. The values are a guess until a cold
  season.

## What stays the same

- The Cool and Warm buttons, 0 = off.
- The defrost button, the write order to the car, the check after each write.
- The climate log (`comfort_event`).

## What changes in the code

| Part | Change |
| :--- | :--- |
| `EffortTable` | The rules become one fixed list. `fit()` becomes "place of each setting". The fan-only bands go away. |
| `ComfortRuler` | The tap code uses the two tap rules above. |
| `Style.comfortRuler()` | New drawing: a grey bar when off, marks that fill with colour when on. |
| Overlay button, MQTT/HA, ABRP | They read a step from -5 to +5 today. The cool range becomes 1 to 10. The warm range stays 1 to 5. |
| `ComfortTable` (the file) | Removed. The list is in `EffortTable`. An old file is deleted. |
| `ComfortLearn` | Removed. Values learned for v1 do not carry over. |
| Tests | The 3 table/tap/learn test classes are rewritten. |
| Docs | `COMFORT-TABLE.md`, the driver guides, CHANGELOG. |

## Open questions

1. **Learning.** Decided: v2 does not learn. The marks are fixed and the
   first tap is fixed. `ComfortLearn` and its settings card go away.
2. **Time of day.** The log shows a clear effect (C8 at midday, C1 to C6 in
   other hours). v2 does not act on it. The log keeps the data.
3. **The fan goes down once (C5 -> C6).** The tap rule for a car that is not
   on a mark uses "the place of each setting". Fan 2 and fan 3 now have two
   places each; the direction says which one.
4. **10 cool marks and 5 warm marks.** The bar shows only one side at a
   time, so the uneven count does not show.
5. **The values in the list** (24 / 22 / 17 °C, fan 1, 2, 3, 4, 6, 8) are a
   first guess from one log.
6. **Recirculation on a mild day.** C4 turns it on in all weather. If the
   log shows that the driver turns it off by hand on mild days, the list
   changes.

## Order of work

0. The climate log saves the air direction with each row (it does not
   today). This goes first, alone, so that data starts to build up.
1. This note is agreed.
2. Replay the log against the list: count the fast corrections v2 would
   remove. The values change until the count is clearly lower.
3. Code and unit tests.
4. Real drives in cool, mild and hot weather. Then release.
