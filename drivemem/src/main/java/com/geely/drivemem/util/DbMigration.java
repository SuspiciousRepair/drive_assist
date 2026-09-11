package com.geely.drivemem.util;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.OdoStats;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.state.ChargeSession;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** One-time migration of legacy flat-file logs into CarDb.
 *
 * Imports charge.log and odo.log into the database on first run.
 * Guarded by SharedPreferences flag to run only once. Legacy files are left
 * untouched for manual inspection if needed.
 */
public final class DbMigration {
    private static final String TAG = CarAccess.TAG;
    private static final String PREF_KEY = "db_migrated_v1";

    /** Runs the migration once if not already completed. */
    public static void runOnce(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final SharedPreferences p = app.getSharedPreferences("drivemem", Context.MODE_PRIVATE);

        // Guard check must run on the writer thread to prevent race conditions
        // where TelemetryService.onStartCommand() fires twice in quick succession,
        // causing both calls to import and duplicate all rows.
        CarDb.get(app).write(() -> {
            if (p.getBoolean(PREF_KEY, false)) return;
            try {
                migrateCharge(app);
                migrateOdo(app);
                p.edit().putBoolean(PREF_KEY, true).apply();
                Log.i(TAG, "dbmigration: done");
            } catch (Throwable t) {
                // Deliberately does NOT set the flag on failure — a broken
                // migration should retry next start, not silently give up
                // and pretend the old data was imported.
                Log.w(TAG, "dbmigration: " + t);
            }
        });
    }

    private static void migrateCharge(Context ctx) {
        List<ChargeSession.Summary> rows = new ArrayList<>();
        ChargeSession.readOneFile(ChargeSession.rotatedFile(ctx), rows);
        ChargeSession.readOneFile(ChargeSession.file(ctx), rows);
        android.database.sqlite.SQLiteDatabase db = CarDb.get(ctx).db();
        db.beginTransaction();
        try {
            for (ChargeSession.Summary s : rows) {
                ContentValues v = new ContentValues();
                v.put("start_ms", s.startWallMs);
                v.put("end_ms", s.endWallMs);
                v.put("soc_start", s.socStart);
                v.put("soc_end", s.socEnd);
                v.put("kwh", s.kwh);
                v.put("avg_power_w", s.avgPowerW);
                v.put("samples", s.samples);
                if (s.odoStart >= 0) v.put("odo_start_km", s.odoStart);
                // start_sample_id/end_sample_id left NULL — there is no
                // sample history to point at for anything migrated in.
                db.insert("charge_session", null, v);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        Log.i(TAG, "dbmigration: charge_session <- " + rows.size() + " rows");
    }

    // odo.log's old format is just "date\todometer_km" — one calendar day
    // per line, no time-of-day. No table of its own for that any more (see
    // OdoStats's header): these become sparse telemetry_sample rows (ts_ms +
    // odo_km only), same fact, same table as everything else. A day's
    // FIRST reading wins (matching the live writer's own semantics, fixed
    // this session) — the old file has several dates repeated (each app
    // restart re-logged the day once), collapsed here with an in-memory
    // seen-set rather than a DB constraint, since telemetry_sample has no
    // natural unique key to hang CONFLICT_IGNORE off for this.
    private static final java.text.SimpleDateFormat DAY_FMT =
        new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);

    private static void migrateOdo(Context ctx) {
        List<String[]> rows = new ArrayList<>();   // [date, odoKm]
        java.io.File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        readOldOdoFile(new java.io.File(dir, "odo.log.1"), rows);
        readOldOdoFile(new java.io.File(dir, "odo.log"), rows);
        android.database.sqlite.SQLiteDatabase db = CarDb.get(ctx).db();
        db.beginTransaction();
        int inserted = 0;
        try {
            java.util.Set<String> seenDates = new java.util.HashSet<>();
            for (String[] r : rows) {
                if (!seenDates.add(r[0])) continue;   // keep only the first reading per day
                long tsMs;
                double odoKm;
                try {
                    tsMs = DAY_FMT.parse(r[0]).getTime();
                    odoKm = Double.parseDouble(r[1]);
                } catch (Throwable t) { continue; }   // survives a malformed/hand-edited line
                ContentValues v = new ContentValues();
                v.put("ts_ms", tsMs);
                v.put("odo_km", odoKm);
                db.insert("telemetry_sample", null, v);
                inserted++;
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        Log.i(TAG, "dbmigration: telemetry_sample (odo history) <- " + inserted + " rows");
    }

    private static void readOldOdoFile(java.io.File f, List<String[]> out) {
        if (!f.exists()) return;
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line; boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; }   // header
                String[] c = line.split("\t", -1);
                if (c.length >= 2) out.add(c);
            }
        } catch (Throwable t) {
            Log.w(TAG, "dbmigration: readOldOdoFile " + f + ": " + t);
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    private DbMigration() {}
}
