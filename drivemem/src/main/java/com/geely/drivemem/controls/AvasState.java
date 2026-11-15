package com.geely.drivemem.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.geely.drivemem.car.CarActor;

/** AVAS (the pedestrian warning sound) as the car has it now.
 *
 * Drive Assist cannot read AVAS itself: CarAudioManager.getAVASMode needs
 * android.car.permission.CAR_CONTROL_AUDIO_VOLUME, a system permission
 * (measured on the car 2026-11-15: SecurityException). modehelper (uid
 * system) reads it every tick and sends it here when it changes, or when
 * asked ({@link HelperSync#queryAvas}). It goes into the hub as
 * {@link #KEY}; screens read the hub, not this class.
 *
 * Exported because modehelper is another app. A spoofed broadcast can only
 * mislabel the icon: the flip itself is decided in modehelper, from the car. */
public final class AvasState {
    /** Hub key: Integer AVAS mode, 0 = muted, 1.. = an active sound type. */
    public static final String KEY = "car.avas_mode";
    static final String ACTION = "com.geely.drivemem.AVAS_STATE";

    private AvasState() {}

    /** On / off from a hub reading; null when not known yet. */
    public static Boolean on(CarActor.Reading r) {
        if (r == null || r.status != CarActor.Reading.Status.OK || !(r.value instanceof Integer)) return null;
        return (Integer) r.value != 0;
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent i) {
            if (!ACTION.equals(i.getAction()) || !i.hasExtra("mode")) return;
            CarActor.get(ctx).inject(KEY, i.getIntExtra("mode", 0));
        }
    }
}
