package com.geely.drivemem.ui;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.state.TollTracker;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

/** Config -> Integrations -> OpenStreetMap (map backgrounds & automatic tolls).
 *
 * Both features talk to OpenStreetMap servers and are strictly opt-in (off by
 * default). Trip GPS routes stay local in car.db regardless of these toggles:
 * - Map backgrounds (tile.openstreetmap.org): cached on disk so repeated drives
 *   reuse saved tiles without contacting the server.
 * - Automatic tolls (Overpass API): downloads toll booth and Free Flow gantry
 *   locations using coarse ~55 km map tiles only, caches them locally in car.db,
 *   and matches every recurrent passage 100% locally without contacting any server. */
final class TelemetryTollsSection extends LinearLayout {

    TelemetryTollsSection(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);

        LinearLayout left = ConfigCards.column(activity);
        LinearLayout right = ConfigCards.column(activity);
        addView(ConfigCards.columns(activity, left, right));

        // ---- Left card: Map backgrounds (OpenStreetMap, opt-in) ----
        LinearLayout mapCard = ConfigCards.card(activity, activity.getString(R.string.osm_maps_header));
        TextView mapSize = ConfigCards.hint(activity, "");
        mapSize.setTextSize(15);

        Runnable refreshMapSize = () -> new Thread(() -> {
            long b = TileCache.get(activity).diskBytes();
            String mb = String.format(java.util.Locale.getDefault(), "%.1f", b / (1024.0 * 1024.0));
            activity.runOnUiThread(() -> mapSize.setText(activity.getString(R.string.routes_map_size, mb)));
        }, "map-size").start();

        mapCard.addView(Style.toggleRow(activity, activity.getString(R.string.osm_maps_enable_label),
            Prefs.getOsmMapsEnabled(activity),
            on -> {
                Prefs.setOsmMapsEnabled(activity, on);
                if (on) MapPreload.retryPending(activity);
                refreshMapSize.run();
            }));
        mapCard.addView(ConfigCards.hint(activity, activity.getString(R.string.osm_maps_privacy_hint)));
        mapCard.addView(mapSize);
        refreshMapSize.run();

        LinearLayout mapRow = new LinearLayout(activity);
        mapRow.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Style.dp(activity, 10);
        mapRow.addView(Style.button(activity, activity.getString(R.string.routes_map_clear), 0xFF3A5A7A,
            () -> TileCache.get(activity).clear(() -> {
                Toast.makeText(activity, activity.getString(R.string.routes_map_cleared), Toast.LENGTH_SHORT).show();
                refreshMapSize.run();
            })));
        mapCard.addView(mapRow, mlp);
        ConfigCards.add(left, mapCard);

        // ---- Right card: Automatic tolls (OpenStreetMap, opt-in) ----
        LinearLayout tollCard = ConfigCards.card(activity, activity.getString(R.string.toll_cfg_header));
        TextView status = ConfigCards.hint(activity, "");
        status.setTextSize(15);

        Runnable refreshStatus = () -> new Thread(() -> {
            int[] st = TollTracker.cacheStats(activity);
            activity.runOnUiThread(() -> status.setText(
                activity.getString(R.string.toll_cfg_status, st[0], st[1], st[2])));
        }, "toll-stats").start();

        TollTracker.ProgressCallback onProgress = (done, total) -> activity.runOnUiThread(() -> status.setText(
            activity.getString(R.string.toll_cfg_scanning_progress, done, total)));
        TollTracker.ScanCallback onScanDone = r -> activity.runOnUiThread(() -> {
            if (r.offline) {
                status.setText(R.string.toll_cfg_offline_queued);
            } else if (!r.ran) {
                refreshStatus.run();
            } else if (r.tilesFailed > 0) {
                String err = TollTracker.lastError();
                String msg = activity.getString(
                    R.string.toll_cfg_scan_partial, r.tilesFailed, r.boothsCached, r.passagesAdded);
                if (err != null && !err.isEmpty()) msg += " [" + err + "]";
                status.setText(msg);
            } else {
                status.setText(activity.getString(
                    R.string.toll_cfg_scan_done, r.passagesAdded, r.tripsScanned, r.boothsCached));
            }
        });

        Runnable runScan = () -> {
            status.setText(R.string.toll_cfg_scanning);
            TollTracker.scanPastTripsAsync(activity, true, onProgress, onScanDone);
        };

        tollCard.addView(Style.toggleRow(activity, activity.getString(R.string.toll_cfg_enable_label),
            Prefs.getTollsEnabled(activity),
            on -> {
                Prefs.setTollsEnabled(activity, on);
                if (on) {
                    runScan.run();
                } else {
                    refreshStatus.run();
                }
            }));
        tollCard.addView(ConfigCards.hint(activity, activity.getString(R.string.toll_cfg_privacy_hint)));
        tollCard.addView(status);
        if (TollTracker.isScanning()) {
            int done = TollTracker.currentScanDone(), total = TollTracker.currentScanTotal();
            status.setText(total > 0
                ? activity.getString(R.string.toll_cfg_scanning_progress, done, total)
                : activity.getString(R.string.toll_cfg_scanning));
            TollTracker.attachIfScanning(onProgress, onScanDone);
        } else {
            refreshStatus.run();
        }

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Style.dp(activity, 10);

        btnRow.addView(Style.button(activity, activity.getString(R.string.toll_cfg_scan_btn), Style.ACCENT, () -> {
            if (!Prefs.getTollsEnabled(activity)) {
                Toast.makeText(activity, R.string.toll_cfg_enable_first, Toast.LENGTH_SHORT).show();
                return;
            }
            runScan.run();
        }));
        btnRow.addView(Style.button(activity, activity.getString(R.string.toll_cfg_refresh_btn), 0xFF3A5A7A, () ->
            TollTracker.clearTileCache(activity, () -> activity.runOnUiThread(() -> {
                Toast.makeText(activity, R.string.toll_cfg_cache_cleared, Toast.LENGTH_SHORT).show();
                refreshStatus.run();
            }))));
        tollCard.addView(btnRow, blp);

        tollCard.addView(ConfigCards.hint(activity, activity.getString(R.string.toll_cfg_offline_hint)));
        ConfigCards.add(right, tollCard);
    }
}
