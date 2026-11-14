# Car data for other apps (`@roadcast`)

When Drive Assist's **Exact energy** switch is on, the car runs one shared,
read-only CAN reader: [roadcast](https://github.com/Timoteohss/roadcast) by
Timoteo Sousa (Apache-2.0, vendored unchanged in `third_party/roadcast/`;
credits in `THIRD-PARTY-NOTICES.md`). **Any app on the head unit
may connect and read it.** No permission, no key, no Drive Assist API.

Why open: one reader that every app shares is better than apps fighting over
who owns the car (who starts the daemon, who keeps ADB open). Read-only means
sharing it cannot change the car.

## Connect

- Socket: abstract Unix socket `@roadcast`
  (Java: `new LocalSocketAddress("roadcast", LocalSocketAddress.Namespace.ABSTRACT)`).
- Protocol: roadcast protocol 3, documented in
  `third_party/roadcast/docs/PROTOCOL.md`. Flow: HELLO, GET_SCHEMA pages,
  GET_SNAPSHOT pages, GET_SIGNAL_SNAPSHOT pages, SUBSCRIBE_ALL, then updates
  and a heartbeat every second.
- A small pure-Java client you can copy:
  `drivemem/src/main/java/com/geely/drivemem/sensors/RoadcastClient.java`.
- Works from an ordinary app (`untrusted_app`) under Enforcing SELinux.
  Checked on an EX2, 2026-11-14.

## Useful signals

All calibrated in roadcast's catalog (`third_party/roadcast/data/dbc.json`).

| Signal | Frame | Unit | Note |
|---|---|---|---|
| `BMSH_BattVolt` | 0x178 | V | pack voltage |
| `BMSH_BattCurr` | 0x250 | A | positive = discharge, negative = charging |
| `VCU_DrvPwrAct` | 0x315 | kW | drive power |
| `VCU_ThermalPwrAct` | 0x315 | kW | climate (compressor + heater) |
| `OBC_uInAct`, `OBC_iInAct` | 0x221 | V, A | on-board charger's mains input |

A value arrives only when it changes. Until a frame changes once after the
daemon starts, its signals report "never observed" (state 1), not valid.

## Rules for sharing

- **Do not kill a running roadcastd.** If `@roadcast` answers protocol 3,
  use it.
- If nothing answers and you start your own, do not use
  `/data/local/tmp/drive_assist/roadcastd`: Drive Assist's helper owns and
  rewrites that path.
- If your reader is still in adbd's cgroup (`/acct/uid_0/pid_<adbd>`),
  Drive Assist's helper **moves it to the root group** while ADB is on
  anyway ("adopts" it), so ADB off no longer kills it. It does nothing else
  to it. Better: move it yourself at launch, as Drive Assist does:
  `setsid sh -c 'echo $$ > /acct/cgroup.procs; exec <your roadcastd> ...'`.
- Do not rely on Drive Assist being installed. The daemon runs only while its
  switch is on.

## Privacy

Any installed app can read this data while the switch is on: speed, pedals,
doors, charging, climate and more. The switch text in Drive Assist says so.
