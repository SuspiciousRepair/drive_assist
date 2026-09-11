package com.geely.drivemem.util;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDataHub;
import com.geely.drivemem.controls.TurboMode;
import com.geely.drivemem.hvac.ComfortRuler;
import com.geely.drivemem.hvac.EffortTable;
import com.geely.drivemem.net.MqttReporter;
import com.geely.drivemem.sensors.PowerProbe;
import com.geely.drivemem.state.ChargeSession;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Diagnostic actions for car property probing and testing via adb.
 *
 * Centralizes debug actions that were previously inline in BootReceiver. The receiver
 * still accepts the same action names for backward compatibility but delegates to handlers here.
 */
public final class Diagnostics {

    static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
        "com.geely.drivemem.CHARGE",
        "com.geely.drivemem.HVAC",
        "com.geely.drivemem.DISCOVER",
        "com.geely.drivemem.FANTEST",
        "com.geely.drivemem.READPROP",
        "com.geely.drivemem.READGENERIC",
        "com.geely.drivemem.WRAPREAD",
        "com.geely.drivemem.WRITEPROP",
        "com.geely.drivemem.PROPLIST",
        "com.geely.drivemem.AUDIOPROBE",
        "com.geely.drivemem.HUBTEST",
        "com.geely.drivemem.ACTORTEST",
        "com.geely.drivemem.CHARGETEST",
        "com.geely.drivemem.POWERPROBE",
        "com.geely.drivemem.CONFIGCHECK",
        "com.geely.drivemem.FAKESET"
    ));

    // Default area list for READPROP/READGENERIC/WRAPREAD when not specified.
    // Includes: 0=global, 1/4/16/32/64=seat+door (front L/R, rear L/C/R),
    // 75=HVAC zone, 256/1024=third-row seat/rear window. A superset to catch
    // all properties; unused areas return null/unavailable (harmless and cheap).
    public static final String DEFAULT_AREAS = "0,1,4,16,32,64,75,256,1024";

    // Called from BootReceiver.onReceive right after it obtains a goAsync()
    // PendingResult. onDone must run exactly once, from whichever thread the
    // probe finishes on — same shape every one of these had on its own before
    // this file existed, just no longer holding its own PendingResult.
    static void handle(Context ctx, Intent intent, Runnable onDone) {
        String a = intent.getAction();
        Context app = ctx.getApplicationContext();
        switch (a) {
            case "com.geely.drivemem.CHARGE":      charge(app, intent, onDone); break;
            case "com.geely.drivemem.HVAC":        hvac(app, intent, onDone); break;
            case "com.geely.drivemem.DISCOVER":    discover(app, intent, onDone); break;
            case "com.geely.drivemem.FANTEST":     fantest(app, intent, onDone); break;
            case "com.geely.drivemem.READPROP":    readProp(app, intent, onDone); break;
            case "com.geely.drivemem.READGENERIC": readGeneric(app, intent, onDone); break;
            case "com.geely.drivemem.WRAPREAD":    wrapRead(app, intent, onDone); break;
            case "com.geely.drivemem.WRITEPROP":   writeProp(app, intent, onDone); break;
            case "com.geely.drivemem.PROPLIST":    propList(app, intent, onDone); break;
            case "com.geely.drivemem.AUDIOPROBE":  audioProbe(app, intent, onDone); break;
            case "com.geely.drivemem.HUBTEST":     hubTest(app, intent, onDone); break;
            case "com.geely.drivemem.ACTORTEST":   actorTest(app, intent, onDone); break;
            case "com.geely.drivemem.CHARGETEST":  chargeTest(app, intent, onDone); break;
            case "com.geely.drivemem.POWERPROBE":  powerProbe(app, intent, onDone); break;
            case "com.geely.drivemem.CONFIGCHECK": configCheck(app, intent, onDone); break;
            case "com.geely.drivemem.FAKESET":     fakeSet(app, intent, onDone); break;
            default: onDone.run(); // unreachable — BootReceiver gates on ACTIONS first
        }
    }

    // debug: reads/writes the charging switch with no UI (adb broadcast --ei v <value>)
    private static void charge(Context app, Intent intent, Runnable onDone) {
        final int v = intent.getIntExtra("v", -1);
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "charge: no car"); return; }
                Log.i(CarAccess.TAG, "charge: state before = " + c.readCharging());
                if (v > 0) {
                    c.setCharging(v);
                    try { Thread.sleep(2500); } catch (InterruptedException e) {}
                    Log.i(CarAccess.TAG, "charge: state after = " + c.readCharging());
                }
            } catch (Throwable t) { Log.w(CarAccess.TAG, "charge debug: " + t);
            } finally { onDone.run(); }
        });
    }

    // HVAC debug: reads (without --ei v) or writes (--ei v 0|1) the on/off.
    // --es p power|ac|auto picks which property.
    private static void hvac(Context app, Intent intent, Runnable onDone) {
        final int v = intent.getIntExtra("v", -1);
        final String which = intent.getStringExtra("p");
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "hvac: no car"); return; }
                int prop = "ac".equals(which) ? CarAccess.HVAC_AC_ON
                         : "auto".equals(which) ? CarAccess.HVAC_AUTO_ON
                         : "wake".equals(which) ? CarAccess.HVAC_WAKE_REQ
                         : "rmt".equals(which) ? CarAccess.AC_REMOTE_SET
                         : "rmtsts".equals(which) ? CarAccess.AC_REMOTE_CTRL
                         : CarAccess.HVAC_POWER_ON;
                Log.i(CarAccess.TAG, "hvac[" + which + "] before = " + c.readHvacFlag(prop));
                if (v >= 0) {
                    c.setHvacFlag(prop, v == 1);
                    try { Thread.sleep(2500); } catch (InterruptedException e) {}
                    Log.i(CarAccess.TAG, "hvac[" + which + "] after = " + c.readHvacFlag(prop));
                }
            } catch (Throwable t) { Log.w(CarAccess.TAG, "hvac debug: " + t);
            } finally { onDone.run(); }
        });
    }

    // assisted discovery (snapshot diffing):
    //   step=a -> takes and KEEPS the snapshot (with the control turned OFF)
    //   [you turn parking mode / the headlights on in the car]
    //   step=b -> takes another one and logs the DIFF; whatever prop changed
    //   is a candidate
    private static void discover(Context app, Intent intent, Runnable onDone) {
        final String step = intent.getStringExtra("step");
        final String label = intent.getStringExtra("label");
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "disc: no car"); return; }
                if ("b".equals(step) && Discovery.pendingA != null) {
                    Discovery.Snapshot b = Discovery.snapshot(c);
                    Discovery.diff(Discovery.pendingA, b, label == null ? "?" : label);
                } else {
                    Discovery.pendingA = Discovery.snapshot(c);
                    Log.i(CarAccess.TAG, "disc: snapshot A saved — operate the control and run step=b");
                }
            } catch (Throwable t) { Log.w(CarAccess.TAG, "disc debug: " + t);
            } finally { onDone.run(); }
        });
    }

    /** Test whether the car accepts sustained fan/AC settings without fighting back.
     * Writes all controls simultaneously to test resistance, and does not restore.
     * Baseline is logged first for manual restoration if needed.
     * Arguments: --ei fan <1..7> --ei secs <seconds to watch, capped>
     */
    private static void fantest(Context app, Intent intent, Runnable onDone) {
        final int wantFan = Math.max(Modes.FAN_MIN,
            Math.min(Modes.FAN_MAX, intent.getIntExtra("fan", Modes.FAN_MAX)));
        final int secs = Math.max(20, Math.min(180, intent.getIntExtra("secs", 120)));
        final CarActor actor = CarActor.get(app);
        actor.runOnCarThread(() -> {
            CarAccess c = actor.rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "fantest: no car"); onDone.run(); return; }
                Log.i(CarAccess.TAG, "fantest: BASELINE " + hvacLine(c));

                // Only write a flag value that differs from the current state.
                // These are edge-triggered (toggled), not absolute-set; writing the
                // same value flips it, so only write when change is needed.
                c.nudgeSetpoint(EffortTable.TEMP_MIN - c.readSetpoint());
                Boolean powerNow = c.readHvacFlag(CarAccess.HVAC_POWER_ON);
                if (powerNow == null || !powerNow) c.setHvacFlag(CarAccess.HVAC_POWER_ON, true);
                Boolean acNow = c.readHvacFlag(CarAccess.HVAC_AC_ON);
                if (acNow == null || !acNow) c.setHvacFlag(CarAccess.HVAC_AC_ON, true);
                Boolean recNow = c.readHvacFlag(CarAccess.HVAC_RECIRC_ON);
                boolean recOk = (recNow != null && recNow) || c.setHvacFlag(CarAccess.HVAC_RECIRC_ON, true);
                c.setIntRaw(EffortTable.DIR_PROP, 0, EffortTable.DIR_FACE);
                int fanNow = c.readFan();
                int fanOk = (fanNow >= 0) ? c.nudgeFan(wantFan - fanNow) : -1;
                Log.i(CarAccess.TAG, "fantest: WROTE fan=" + wantFan + " (got " + fanOk
                                   + ") recirc=" + recOk + " sp=" + EffortTable.TEMP_MIN);

                // Sample on a clock, but reschedule on the actor thread rather
                // than Thread.sleep — a real sleep here would hold the SHARED
                // actor thread hostage for up to `secs` (3 minutes), stalling
                // every other read/write/telemetry tick in the app for the
                // whole test, same trap PowerProbe's old sleep-loop had.
                fantestSample(actor, wantFan, 5, secs, onDone);
            } catch (Throwable t) { Log.w(CarAccess.TAG, "fantest: " + t, t); onDone.run(); }
        });
    }

    // 18 s is the number from the old measurement that started this test.
    private static void fantestSample(CarActor actor, int wantFan, int t, int secs, Runnable onDone) {
        if (t > secs) {
            Log.i(CarAccess.TAG, "fantest: DONE — asked fan=" + wantFan
                               + ", ended " + hvacLine(actor.rawAccess()));
            onDone.run();
            return;
        }
        int delayS = (t < 30 ? 5 : 15);
        actor.runOnCarThreadDelayed(() -> {
            Log.i(CarAccess.TAG, "fantest: t+" + t + "s " + hvacLine(actor.rawAccess()));
            fantestSample(actor, wantFan, t + delayS, secs, onDone);
        }, delayS * 1000L);
    }

    // READ debug: reads props x areas and logs each one. READ ONLY — it is
    // the half of WRITEPROP that moves nothing, which is what you want when
    // the property drives glass and you only wanted to look.
    //   --es props "591405227,322964417" --es areas "16,64,256,1024"
    // car-window.sh already greps for "rp:" and expects this to exist; it
    // did not, which is why reading window positions was impossible.
    private static void readProp(Context app, Intent intent, Runnable onDone) {
        final String props = intent.getStringExtra("props");
        final String areas = intent.getStringExtra("areas");
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "rp: no car"); return; }
                if (props == null) { Log.w(CarAccess.TAG, "rp: no props"); return; }
                for (String ps : props.split(",")) {
                    int prop;
                    try { prop = Integer.parseInt(ps.trim()); }
                    catch (NumberFormatException e) { continue; }
                    for (String as : (areas == null ? DEFAULT_AREAS : areas).split(",")) {
                        int area;
                        try { area = Integer.parseInt(as.trim()); }
                        catch (NumberFormatException e) { continue; }
                        Log.i(CarAccess.TAG, "rp: " + prop + "@" + area
                                           + " = " + c.readIntRaw(prop, area));
                    }
                }
            } catch (Throwable t) { Log.w(CarAccess.TAG, "rp: " + t);
            } finally { onDone.run(); }
        });
    }

    // READ debug, TYPE-AGNOSTIC: same shape as READPROP, but goes through
    // CarAccess.readGeneric (float tried before int, see its own comment)
    // instead of forcing getIntProperty — READPROP silently reads 0 on a
    // real float property instead of throwing, which looked like "exists,
    // reads zero" when it was actually "wrong accessor, coerced". Same
    // args as READPROP.
    private static void readGeneric(Context app, Intent intent, Runnable onDone) {
        final String props = intent.getStringExtra("props");
        final String areas = intent.getStringExtra("areas");
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "rg: no car"); return; }
                if (props == null) { Log.w(CarAccess.TAG, "rg: no props"); return; }
                for (String ps : props.split(",")) {
                    int prop;
                    try { prop = Integer.parseInt(ps.trim()); }
                    catch (NumberFormatException e) { continue; }
                    for (String as : (areas == null ? DEFAULT_AREAS : areas).split(",")) {
                        int area;
                        try { area = Integer.parseInt(as.trim()); }
                        catch (NumberFormatException e) { continue; }
                        Log.i(CarAccess.TAG, "rg: " + prop + "@" + area
                                           + " = " + c.readGeneric(prop, area));
                    }
                }
            } catch (Throwable t) { Log.w(CarAccess.TAG, "rg: " + t);
            } finally { onDone.run(); }
        });
    }

    // READ debug for a GEELY FUNCTIONID (not a raw property id) — resolves
    // it through the OEM's own wrapper first (CarAccess.wrapFuncId — see
    // its own comment for why this is a separate step from READGENERIC),
    // then reads whatever real property id that resolves to, at each of
    // the given areas.
    //   --es funcIds "356518789,356518795" --ei areaType 1 --es areas "0,1,4"
    // areaType is the wrapper call's own area concept (found in the OEM's
    // decompiled source, NOT the same thing as the VHAL area the final
    // read uses) — one value for the whole call, since every functionId
    // in one call is normally the same feature (e.g. all driver-seat).
    private static void wrapRead(Context app, Intent intent, Runnable onDone) {
        final String funcIds = intent.getStringExtra("funcIds");
        final int areaType = intent.getIntExtra("areaType", 1);
        final String areas = intent.getStringExtra("areas");
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "wr: no car"); return; }
                if (funcIds == null) { Log.w(CarAccess.TAG, "wr: no funcIds"); return; }
                for (String fs : funcIds.split(",")) {
                    int funcId;
                    try { funcId = Integer.parseInt(fs.trim()); }
                    catch (NumberFormatException e) { continue; }
                    Integer real = c.wrapFuncId(app, areaType, funcId);
                    if (real == null) {
                        Log.i(CarAccess.TAG, "wr: " + funcId + "@areaType" + areaType
                                           + " -> could not resolve");
                        continue;
                    }
                    for (String as : (areas == null ? DEFAULT_AREAS : areas).split(",")) {
                        int area;
                        try { area = Integer.parseInt(as.trim()); }
                        catch (NumberFormatException e) { continue; }
                        Log.i(CarAccess.TAG, "wr: " + funcId + " -> " + real + "@" + area
                                           + " = " + c.readGeneric(real, area));
                    }
                }
            } catch (Throwable t) { Log.w(CarAccess.TAG, "wr: " + t);
            } finally { onDone.run(); }
        });
    }

    // WRITE debug: writes an int into a prop and reads it back.
    //   --ei prop <id> --ei area <a> --ei val <value>
    // --ei val <int> for an int property (unchanged), OR --ef fval <float>
    // for a float one — needed because some properties (e.g. 557884450,
    // cruise ARMED/ACTIVE) are float-typed, and writing them through an
    // int-only path either throws or silently truncates the value, exactly
    // the quiet data-loss the method warns about in step 6. Only one of
    // --ei val / --ef fval should be
    // passed; fval wins if both are (unlikely, but int's default is 0 which
    // would otherwise shadow a real 0.0 float write and look identical).
    private static void writeProp(Context app, Intent intent, Runnable onDone) {
        final int prop = intent.getIntExtra("prop", CarAccess.PARK_MODE);
        final int area = intent.getIntExtra("area", 0);
        final boolean asFloat = intent.hasExtra("fval");
        final int val  = intent.getIntExtra("val", 0);
        final float fval = intent.getFloatExtra("fval", 0f);
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "wp: no car"); return; }
                if (asFloat) {
                    Log.i(CarAccess.TAG, "wp(float): before " + prop + "@" + area + " = " + c.readFloatRaw(prop, area));
                    boolean ok = c.setFloatRaw(prop, area, fval);
                    try { Thread.sleep(1500); } catch (InterruptedException e) {}
                    Log.i(CarAccess.TAG, "wp(float): wrote=" + ok + " (" + fval + ") after = " + c.readFloatRaw(prop, area));
                } else {
                    Log.i(CarAccess.TAG, "wp: before " + prop + "@" + area + " = " + c.readIntRaw(prop, area));
                    boolean ok = c.setIntRaw(prop, area, val);
                    try { Thread.sleep(1500); } catch (InterruptedException e) {}
                    Log.i(CarAccess.TAG, "wp: wrote=" + ok + " after = " + c.readIntRaw(prop, area));
                }
            } catch (Throwable t) { Log.w(CarAccess.TAG, "wp: " + t);
            } finally { onDone.run(); }
        });
    }

    // debug: getPropertyList() — the plan for a distributable app assumes it
    // returns areaIds and min/max for Geely's functionIds (hardcoded today).
    // This checks whether that premise holds on THIS unit.
    private static void propList(Context app, Intent intent, Runnable onDone) {
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "proplist: no car"); return; }
                c.dumpPropertyList();
            } catch (Throwable t) { Log.w(CarAccess.TAG, "proplist: " + t, t);
            } finally { onDone.run(); }
        });
    }

    // debug: is a given id DECLARED at all in getPropertyList(), separate
    // from what it reads. A property that never even appears in the
    // VHAL's own config list is a stronger "not on this car" signal than
    // one that's declared but always reads a fixed value — the first
    // means the config itself never mentions it (no sensor wired in at
    // all, at the level Android can see); the second is ambiguous (could
    // be a live property nobody's driving, not necessarily hardware-less).
    //   --es props "356518789,356518795"
    // Read-only probe of android.car.media.CarAudioManager's warning/beep
    // volume controls (CarAccess.audioCall) — the same class the third-party app uses
    // for AVAS, but with a much bigger surface: getBeepLevel,
    // getVehicleAlarmLevel, and a per-"warn type" getWarnVolume family.
    // Logs the general levels, then probes warn types 0..19 and only
    // logs a type if getMaxWarnVolume(i) actually returns a real value —
    // most indices are expected to error out, that's fine, it's the
    // "declaration exercise" for this API instead of CONFIGCHECK.
    private static void audioProbe(Context app, Intent intent, Runnable onDone) {
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "ap: no car"); return; }
                Log.i(CarAccess.TAG, "ap: isVehicleAlarmSupported = " + c.audioCall("isVehicleAlarmSupported"));
                Log.i(CarAccess.TAG, "ap: getVehicleAlarmLevel = " + c.audioCall("getVehicleAlarmLevel"));
                Log.i(CarAccess.TAG, "ap: getBeepLevel = " + c.audioCall("getBeepLevel"));
                Log.i(CarAccess.TAG, "ap: isAVASModeSupported = " + c.audioCall("isAVASModeSupported"));
                for (int i = 0; i < 20; i++) {
                    Object max = c.audioCall("getMaxWarnVolume", i);
                    if (max == null || max.toString().startsWith("ERR")) continue;
                    Object levels = c.audioCall("getSupportWarnVolumeLevel", i);
                    String levelsStr = (levels instanceof int[])
                            ? java.util.Arrays.toString((int[]) levels) : String.valueOf(levels);
                    Log.i(CarAccess.TAG, "ap: warnType " + i
                            + " max=" + max
                            + " min=" + c.audioCall("getMinWarnVolume", i)
                            + " cur=" + c.audioCall("getWarnVolume", i)
                            + " level=" + c.audioCall("getWarnVolumeLevel", i)
                            + " setMode=" + c.audioCall("getWarnVolumeSetMode", i)
                            + " supportedLevels=" + levelsStr);
                }
            } catch (Throwable t) { Log.w(CarAccess.TAG, "ap: " + t, t);
            } finally { onDone.run(); }
        });
    }

    // debug: exercise CarDataHub directly, bypassing MqttReporter/TurboMode.
    //   --es key ambient_color --es value 16711680   (write)
    //   --es key ambient_color                         (read, no "value" extra)
    // Note for any new entity added later: a WriteResult.applied=true here
    // is not proof the car actually changed — ambient_color's own history
    // (see CarDataHub's comment on it) is why a write is trusted as sent,
    // not confirmed by an immediate read-back.
    // Now runs on CarActor's shared connection like everything else here —
    // ACTORTEST below does the same round trip through CarActor.read/cast
    // instead of calling CarDataHub directly; kept both since they exercise
    // different call shapes, not different connections.
    private static void hubTest(Context app, Intent intent, Runnable onDone) {
        final String key = intent.getStringExtra("key");
        final String value = intent.getStringExtra("value");
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "hubtest: no car"); return; }
                if (key == null) { Log.w(CarAccess.TAG, "hubtest: no key"); return; }
                if (value == null) {
                    Log.i(CarAccess.TAG, "hubtest: read " + key + " = " + CarDataHub.read(c, key));
                } else {
                    Object requested;
                    try { requested = Integer.parseInt(value); }
                    catch (NumberFormatException e) { requested = value; }
                    CarDataHub.WriteResult r = CarDataHub.apply(c, key, requested);
                    Log.i(CarAccess.TAG, "hubtest: apply " + key + "=" + value
                        + " -> applied=" + r.applied + " value=" + r.value + " error=" + r.error);
                }
            } catch (Throwable t) { Log.w(CarAccess.TAG, "hubtest: " + t, t);
            } finally { onDone.run(); }
        });
    }

    // Same shape as HUBTEST, but through CarActor.read/cast instead of
    // calling CarDataHub directly — exercises the actor's own public API,
    // the one every screen actually calls, rather than reaching past it.
    //   --es key ambient_color --es value 16711680   (write)
    //   --es key ambient_color                         (read, no "value" extra)
    private static void actorTest(Context app, Intent intent, Runnable onDone) {
        final String key = intent.getStringExtra("key");
        final String value = intent.getStringExtra("value");
        if (key == null) { Log.w(CarAccess.TAG, "actortest: no key"); onDone.run(); return; }
        if (value == null) {
            CarActor.get(app).read(key, v -> {
                Log.i(CarAccess.TAG, "actortest: read " + key + " = " + v);
                onDone.run();
            });
        } else {
            Object requested;
            try { requested = Integer.parseInt(value); }
            catch (NumberFormatException e) { requested = value; }
            CarActor.get(app).cast(key, requested, r -> {
                Log.i(CarAccess.TAG, "actortest: apply " + key + "=" + value
                    + " -> applied=" + r.applied + " value=" + r.value + " error=" + r.error);
                onDone.run();
            });
        }
    }

    // Peeks ChargeSession's in-progress accumulator, so its correctness can
    // be checked mid-session instead of waiting for a real one to finish.
    private static void chargeTest(Context app, Intent intent, Runnable onDone) {
        Log.i(CarAccess.TAG, "chargetest: " + ChargeSession.debugState());
        onDone.run();
    }

    // Kicks off PowerProbe.run() and returns right away — see PowerProbe's
    // own header for why it can't hold this broadcast's goAsync() open for
    // its whole duration.
    //   --ei secs 300 --ei interval_ms 1000
    // Cap is 14400s (4h): a drive-to-a-fast-charger session (drive there,
    // charge, drive back) does not fit in 10 minutes, and this tool is meant
    // to be armed before the car leaves and left running for the whole
    // outing, not re-triggered mid-drive (adb is gone the moment the car
    // leaves the home network).
    private static void powerProbe(Context app, Intent intent, Runnable onDone) {
        int secs = Math.max(10, Math.min(14400, intent.getIntExtra("secs", 300)));
        int intervalMs = Math.max(200, Math.min(5000, intent.getIntExtra("interval_ms", 1000)));
        Log.i(CarAccess.TAG, "powerprobe: starting, secs=" + secs + " interval_ms=" + intervalMs);
        PowerProbe.run(app, secs, intervalMs);
        onDone.run();
    }

    private static void configCheck(Context app, Intent intent, Runnable onDone) {
        final String props = intent.getStringExtra("props");
        CarActor.get(app).runOnCarThread(() -> {
            CarAccess c = CarActor.get(app).rawAccess();
            try {
                if (!c.isReady() && !c.connect(app)) { Log.w(CarAccess.TAG, "cc: no car"); return; }
                if (props == null) { Log.w(CarAccess.TAG, "cc: no props"); return; }
                java.util.List<android.car.hardware.CarPropertyConfig> all = c.propertyList();
                if (all == null) { Log.w(CarAccess.TAG, "cc: no property list"); return; }
                java.util.HashMap<Integer, android.car.hardware.CarPropertyConfig> byId = new java.util.HashMap<>();
                for (android.car.hardware.CarPropertyConfig cfg : all) byId.put(cfg.getPropertyId(), cfg);
                for (String ps : props.split(",")) {
                    int prop;
                    try { prop = Integer.parseInt(ps.trim()); }
                    catch (NumberFormatException e) { continue; }
                    android.car.hardware.CarPropertyConfig cfg = byId.get(prop);
                    if (cfg == null) { Log.i(CarAccess.TAG, "cc: " + prop + " -> NOT DECLARED"); continue; }
                    StringBuilder sb = new StringBuilder("cc: " + prop + " -> DECLARED, areas=[");
                    int[] areas = cfg.getAreaIds();
                    for (int i = 0; i < areas.length; i++) { if (i > 0) sb.append(","); sb.append(areas[i]); }
                    sb.append("]");
                    try { sb.append(", min=").append(cfg.getMinValue()).append(" max=").append(cfg.getMaxValue()); }
                    catch (Throwable ignored) {}
                    Log.i(CarAccess.TAG, sb.toString());
                }
            } catch (Throwable t) { Log.w(CarAccess.TAG, "cc: " + t, t);
            } finally { onDone.run(); }
        });
    }

    // One line of HVAC truth, for FANTEST. Reads every lever the test writes, so
    // a sample says which one the car took back rather than just that something
    // changed.
    private static String hvacLine(CarAccess c) {
        Integer dir = c.readIntRaw(EffortTable.DIR_PROP, 0);
        return "fan=" + c.readFan()
             + " sp=" + c.readSetpoint()
             + " ac=" + c.readHvacFlag(CarAccess.HVAC_AC_ON)
             + " recirc=" + c.readHvacFlag(CarAccess.HVAC_RECIRC_ON)
             + " dir=" + dir
             + " out=" + c.readOutsideTempC();
    }

    // Injects a value straight into CarActor's cache, bypassing the car
    // entirely — for UI testing on a plain emulator (no real vehicle HAL)
    // or previewing a state on the real car without waiting for it to
    // happen naturally. Value is parsed as Integer, then Float, then left
    // as a String — whichever a card's own code expects to unbox.
    //   adb shell am broadcast -a com.geely.drivemem.FAKESET \
    //     -n com.geely.drivemem/.BootReceiver \
    //     --es key telemetry.battery --es value 55
    private static void fakeSet(Context app, Intent intent, Runnable onDone) {
        String key = intent.getStringExtra("key");
        String value = intent.getStringExtra("value");
        if (key == null || value == null) {
            Log.w(CarAccess.TAG, "fakeset: need --es key and --es value");
            onDone.run();
            return;
        }
        Object v;
        try { v = Integer.parseInt(value); }
        catch (NumberFormatException e1) {
            try { v = Float.parseFloat(value); }
            catch (NumberFormatException e2) { v = value; }
        }
        CarActor.get(app).inject(key, v);
        Log.i(CarAccess.TAG, "fakeset: " + key + " = " + v);
        onDone.run();
    }

    private Diagnostics() {}
}
