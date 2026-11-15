# Searching the CAN frames for unnamed values

The head unit receives 111 CAN frames, and the roadcast catalog names only
part of their bits (`third_party/roadcast/docs/CATALOG_SURFACE.md`). This
page explains how to record the frames and search them for a value that has
no name yet. The first target was the **battery temperature**, which today
only the OBD2 dongle reads.

## Tools

- `tools/canrec/`: the recorder. It runs on the car as a root process
  (`app_process`), outside the app, so app installs and turning ADB off do not
  stop it. It reads roadcastd's socket like any other client.
- `tools/can-explore-analyze.py`: the search. For every frame, every start bit,
  width and byte order, it compares the field with a reference value and ranks
  the fields by correlation (|r|).

## How to run a search

1. The car must be on the home Wi-Fi, with adb connected, and with the CAN
   reader ("Exact energy") on. For a dongle value, the dongle must be on too.
2. Start the recorder for N minutes. It stops by itself:

   ```bash
   tools/canrec/canrec.sh start 600
   tools/canrec/canrec.sh status
   ```

3. Drive or charge. The recorder writes only while the head unit is awake.
4. Get the files. Pull the dongle log one file at a time (the whole `files/`
   folder is too big):

   ```bash
   tools/canrec/canrec.sh pull /tmp/canrec
   adb pull /sdcard/Android/data/com.geely.drivemem/files/obd2-reading.log /tmp/canrec/
   ```

5. Search:

   ```bash
   tools/can-explore-analyze.py /tmp/canrec/can-explore-*.tsv \
       --obd2 /tmp/canrec/obd2-reading.log --ref obd.batt_temp_c
   ```

   `--ref` can be any reference key: `obd.soc`, `obd.pack_v`,
   `obd.pack_a`, `obd.power_kw`, `obd.batt_temp_c`, or a roadcastd signal
   that the recorder writes (`can.BMSH_BattVolt`, ...).
6. Stop early, if you want: `tools/canrec/canrec.sh stop`.

The search skips the bits that the catalog already names. Use `--all` to
include them.

## Read the results with care

A high |r| shows that a field moves **together with** the reference. It does
not show that the field **is** the reference. Many values move slowly in one
direction during a recording: the clock, trip counters, the odometer, the
range estimate, the SoC. If the reference also moves in one direction, all of
these correlate with it.

So record a session where the reference moves **against** the time and the
SoC. For battery temperature, the best sessions are:

- a long, fast drive or a DC fast charge: the temperature goes **up** a lot,
  and the SoC goes down (drive) or the charge is short (DC);
- a recording that has the temperature both going up and coming down.

Confirm a candidate on a second, different recording before you trust it.

## Results so far

**2026-11-14: battery temperature, not found.**

- Data: an AC charge (12:18–13:10, 27 → 30 °C) and an evening with three short
  drives (15:42–23:00, 30 → 25 °C).
- Every top candidate was a named clock, odometer, trip, range or charger field
  (`0x3F0`, `0x3F1`, `0x2C5`, `0x2C6`, `0x214`, `0x3F7`, `0x221`), or an unnamed
  field that follows the SoC or a trip counter (`0x160` bytes 2–4, `0x2C5` bits
  32–55, `0x17A`).
- No field followed the temperature itself. The battery computer probably
  sends the temperature only on a network that the head unit does not receive.
  The dongle stays the only source (`obd.batt_temp_c`).
- Not yet checked: `0x339` and `0x176` (|r| 0.86–0.87, few distinct values).
  During the V2L work, `0x339` byte 0 looked like a temperature.
- Next try, if wanted: a long fast drive or a DC fast charge (see above).

Cost on the car: the recorder used about 1–2 % of one CPU core while awake,
and about 2 MB per hour of recording.
