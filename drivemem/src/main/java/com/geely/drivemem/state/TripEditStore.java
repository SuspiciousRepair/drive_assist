package com.geely.drivemem.state;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.TripEdits;

import java.util.ArrayList;
import java.util.List;

/** Saves and undoes the hand-made joins and splits of trips (issue #210)
 * in {@code trip_edit}. The trip rows are never touched, so every undo
 * gives back exactly what was recorded. The rules live in TripEdits. */
public final class TripEditStore {
    private static final String TAG = "DriveMem";

    /** EntityBus key published after every change. */
    public static final String CHANGED = "trip_edit.changed";

    private TripEditStore() {}

    /** Joins every trip from the one that starts at firstStartMs to the one
     * that ends at lastEndMs, with all that lies between. A join it overlaps
     * is folded into the new one. */
    public static void join(Context ctx, long firstStartMs, long lastEndMs, Runnable onDone) {
        Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            try {
                SQLiteDatabase db = CarDb.get(app).db();
                List<TripEdits.Join> old = new ArrayList<>();
                try (Cursor c = db.rawQuery("SELECT id, start_ms, end_ms FROM trip_edit WHERE kind = ?",
                        new String[]{TripEdits.KIND_JOIN})) {
                    while (c.moveToNext()) old.add(new TripEdits.Join(c.getLong(0), c.getLong(1), c.getLong(2)));
                }
                long[] w = TripEdits.joinWindow(firstStartMs, lastEndMs, old);
                db.beginTransaction();
                try {
                    db.delete("trip_edit", "kind = ? AND start_ms >= ? AND end_ms <= ?",
                        new String[]{TripEdits.KIND_JOIN, String.valueOf(w[0]), String.valueOf(w[1])});
                    ContentValues v = new ContentValues();
                    v.put("kind", TripEdits.KIND_JOIN);
                    v.put("start_ms", w[0]);
                    v.put("end_ms", w[1]);
                    v.put("created_ms", System.currentTimeMillis());
                    db.insert("trip_edit", null, v);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (Throwable t) {
                Log.w(TAG, "trip edit: join: " + t);
            }
            changed(onDone);
        });
    }

    /** Splits recorded trip tripId: the first part ends at aMs, the next
     * starts at bMs (aMs == bMs while moving, the two ends of a stop). */
    public static void cut(Context ctx, long tripId, long aMs, long bMs, Runnable onDone) {
        Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put("kind", TripEdits.KIND_CUT);
                v.put("trip_id", tripId);
                v.put("start_ms", Math.min(aMs, bMs));
                v.put("end_ms", Math.max(aMs, bMs));
                v.put("created_ms", System.currentTimeMillis());
                CarDb.get(app).db().insert("trip_edit", null, v);
            } catch (Throwable t) {
                Log.w(TAG, "trip edit: cut: " + t);
            }
            changed(onDone);
        });
    }

    /** Undo join: the trips show one by one again. */
    public static void undoJoin(Context ctx, long joinId, Runnable onDone) {
        delete(ctx, "id = ? AND kind = '" + TripEdits.KIND_JOIN + "'", String.valueOf(joinId), onDone);
    }

    /** Undo split: every split of recorded trip tripId goes, so it shows
     * whole again. */
    public static void undoCuts(Context ctx, long tripId, Runnable onDone) {
        delete(ctx, "trip_id = ? AND kind = '" + TripEdits.KIND_CUT + "'", String.valueOf(tripId), onDone);
    }

    private static void delete(Context ctx, String where, String arg, Runnable onDone) {
        Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            try {
                CarDb.get(app).db().delete("trip_edit", where, new String[]{arg});
            } catch (Throwable t) {
                Log.w(TAG, "trip edit: undo: " + t);
            }
            changed(onDone);
        });
    }

    /** A past day's sessions are kept in memory as frozen history; an edit
     * changes them, so they are dropped before the screens hear of it. */
    private static void changed(Runnable onDone) {
        DailyStatsProvider.forgetDays();
        EntityBus.publish(CHANGED, CarActor.Reading.ok(System.currentTimeMillis()));
        if (onDone != null) new Handler(Looper.getMainLooper()).post(onDone);
    }
}
