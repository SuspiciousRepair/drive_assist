package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.StatFs;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.controls.GeelySwitch;
import com.geely.drivemem.util.CarCaps;
import com.geely.drivemem.util.ClipExport;
import com.geely.drivemem.util.ClipRecovery;
import com.geely.drivemem.util.Clips;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.util.List;

/** Dashcam recordings panel — the clips gallery and recording settings.
 *
 * Rebuilt on every entry rather than cached: the recorder is a different
 * process and can close a segment or evict one at any moment, so anything
 * held here would be a guess about another app's directory.
 *
 * Extracted from TelemetryActivity.buildClips() and clipRow(), same pattern as
 * TelemetryDoorsSection. */
public final class TelemetryClipsSection extends LinearLayout {
    private final Activity activity;
    private final Runnable onRefresh;
    // The Record switch, and its one re-check after a tap. The re-check only
    // moves the switch to the real state. It used to rebuild the whole
    // section (onRefresh), 10.5 s after the tap, wherever the owner was by
    // then: it threw them back here from another section, and wiped a GB
    // limit they were typing. Cancelled on detach and by cancelPending().
    private GeelySwitch recordSwitch;
    private final Runnable recordRecheck = () -> {
        if (recordSwitch != null) recordSwitch.setCheckedSilently(Clips.shownOn(getContext()));
    };

    public TelemetryClipsSection(Activity activity, Runnable onRefresh) {
        super(activity);
        this.activity = activity;
        this.onRefresh = onRefresh;
        setOrientation(VERTICAL);

        // Two columns: the recording settings on the left, the clip list on
        // the right, so the list starts at the top of the screen instead of
        // below the settings.
        LinearLayout leftCol = ConfigCards.column(activity);
        LinearLayout rightCol = ConfigCards.column(activity);
        addView(ConfigCards.columns(activity, leftCol, rightCol));
        List<Clips.Clip> clips = Clips.list(activity);

        // No 360 cameras, no dashcam: ModeHelper refuses to record (its
        // CarCaps), so the settings would be switches that do nothing. The
        // card says why and shows what the car reported. Clips already on
        // the disk stay listed.
        CarCaps caps = CarCaps.read(activity);
        if (CarCaps.blocksDashcam(caps)) {
            ConfigCards.add(leftCol, CarCapsCard.card(activity, caps));
            addClipList(rightCol, clips);
            return;
        }

        LinearLayout settings = ConfigCards.card(activity, activity.getString(R.string.clips_title));
        ConfigCards.add(leftCol, settings);

        boolean on = Clips.shownOn(activity);

        // A real toggle, not a momentary button: modehelper now persists
        // whatever is sent here ("dashcam_on") and checks it before
        // auto-starting on the next boot too — see ModeHelperService's own
        // comment on maybeAutoStart(). Displayed state is the recorder's own
        // (Clips.recording(): recorder.state, then the live .mp4.tmp), the same
        // honest-over-cached approach as the AVAS toggle, not a guess.
        LinearLayout recordRow = Style.toggleRow(activity, activity.getString(R.string.clips_record), on, wantOn -> {
            // Drive Assist does not record — modehelper does. Ask over the same
            // broadcast adb uses. Starting takes seconds, so the switch keeps
            // the request for Clips.ASK_GRACE_MS (a re-read at 1.5 s used to
            // flip it back to off), then shows the real state again.
            Prefs.setDashcamOn(activity, wantOn);   // for the backup only
            Clips.asked(wantOn);
            activity.sendBroadcast(new Intent("com.geely.modehelper.DASHCAM")
                .setClassName("com.geely.modehelper", "com.geely.modehelper.DashReceiver")
                .putExtra("on", wantOn ? 1 : 0));
            removeCallbacks(recordRecheck);
            postDelayed(recordRecheck, Clips.RECHECK_MS);
        });
        recordSwitch = (GeelySwitch) recordRow.getChildAt(0);   // Style.toggleRow: switch first, then label
        settings.addView(recordRow);

        // A GB count is a couple of digits, not a URL -- a field and a
        // button that both stretch to the full row width (field()'s and
        // cardButton()'s usual shape, right for every OTHER field on this
        // screen) just leaves both looking like empty bars with their
        // content stranded in a corner. One compact row instead.
        TextView limitLbl = new TextView(activity);
        limitLbl.setText(activity.getString(R.string.clips_limit_label));
        limitLbl.setTextColor(Style.TEXT_DIM);
        limitLbl.setTextSize(14);
        limitLbl.setPadding(0, Style.dp(activity, 10), 0, Style.dp(activity, 2));
        settings.addView(limitLbl);

        LinearLayout limitRow = new LinearLayout(activity);
        limitRow.setOrientation(LinearLayout.HORIZONTAL);
        limitRow.setGravity(Gravity.CENTER_VERTICAL);
        // Explicit bottom margin: previously this gap came from button()'s
        // own stray top margin (removed below, see saveBtn), which was
        // incidental spacing, not a deliberate one.
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = Style.dp(activity, 10);
        limitRow.setLayoutParams(rowLp);
        settings.addView(limitRow);

        final EditText fDashLimit = new EditText(activity);
        fDashLimit.setText(String.valueOf(Prefs.getDashcamLimitGb(activity)));
        fDashLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        fDashLimit.setTextColor(Style.TEXT);
        fDashLimit.setTextSize(17);
        fDashLimit.setBackground(Style.card(Style.CARD, activity));
        int fp = Style.dp(activity, 12);
        fDashLimit.setPadding(fp, fp, fp, fp);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
            Style.dp(activity, 120), ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.setMarginEnd(Style.dp(activity, 12));
        fDashLimit.setLayoutParams(fLp);
        limitRow.addView(fDashLimit);

        TextView saveBtn = Style.cardButton(activity, activity.getString(R.string.clips_limit_save), false, () -> {
            int gb;
            try { gb = Integer.parseInt(fDashLimit.getText().toString().trim()); }
            catch (NumberFormatException e) { gb = -1; }
            if (gb < 1) { fDashLimit.setText(String.valueOf(Prefs.getDashcamLimitGb(activity))); return; }
            gb = Math.min(gb, 500); // storage is real; a typo shouldn't ask for the whole disk
            Prefs.setDashcamLimitGb(activity, gb);
            Intent i = new Intent("com.geely.modehelper.SET_MODE").setPackage("com.geely.modehelper");
            i.putExtra("dashcam_limit_gb", gb);
            activity.sendBroadcast(i);
            Toast.makeText(activity, activity.getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
        });
        // button() bakes in an 8dp TOP margin (meant for buttons stacked
        // vertically with a gap between them) and no bottom margin. In this
        // horizontal, CENTER_VERTICAL row that margin just pushes the
        // button down and out of limitRow's own measured height -- not a
        // rendering artifact, an actual position bug: it was overlapping
        // (getting drawn under) the Park monitoring row right below.
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveBtn.setLayoutParams(sLp);
        limitRow.addView(saveBtn);

        LinearLayout parkedMonitor = Style.toggleRow(activity, activity.getString(R.string.cfg_park_monitor_label),
            Prefs.getParkedMonitoring(activity), enabled -> {
                Prefs.setParkedMonitoring(activity, enabled);
                activity.sendBroadcast(new Intent("com.geely.modehelper.PARKED_MONITORING")
                    .setClassName("com.geely.modehelper",
                        "com.geely.modehelper.ParkedMonitoringReceiver")
                    .putExtra("on", enabled ? 1 : 0));
            });
        settings.addView(parkedMonitor);

        // Issue #18: held clips are the ones worth keeping, so one button
        // copies all of them to the USB stick. Single clips have their own
        // USB button in the list.
        TextView heldUsb = Style.cardButton(activity, activity.getString(R.string.clips_usb_held), false, () -> {
            List<Clips.Clip> held = new java.util.ArrayList<>();
            for (Clips.Clip c : Clips.list(activity)) if (c.held && c.playable()) held.add(c);
            if (held.isEmpty()) {
                Toast.makeText(activity, activity.getString(R.string.clips_usb_no_held), Toast.LENGTH_LONG).show();
                return;
            }
            exportToUsb(activity, held);
        });
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = Style.dp(activity, 10);
        heldUsb.setLayoutParams(hLp);
        settings.addView(heldUsb);

        addClipList(rightCol, clips);
    }

    private void addClipList(LinearLayout rightCol, List<Clips.Clip> clips) {
        TextView listHeader = Style.header(activity, activity.getString(R.string.clips_list_header));
        listHeader.setPadding(0, 0, 0, Style.dp(activity, 6));
        rightCol.addView(listHeader);
        // Count/size is a fact about the clip list below, not the settings
        // above it -- moved down here to sit with what it describes.
        rightCol.addView(Style.label(activity, activity.getString(R.string.clips_usage,
            clips.size(), Clips.mb(Clips.usedBytes(activity)), Clips.mb(Clips.heldBytes(activity)),
            Clips.mb(new StatFs(Clips.dir(activity).getAbsolutePath()).getAvailableBytes()))));
        Style.gap(rightCol, activity, 8);

        if (clips.isEmpty()) { rightCol.addView(Style.label(activity, activity.getString(R.string.clips_none))); return; }
        for (Clips.Clip c : clips) rightCol.addView(clipRow(activity, c));
    }

    // One toast when the copy starts (a clip is hundreds of MB, so it takes
    // a while) and one with the result.
    static void exportToUsb(Activity activity, List<Clips.Clip> clips) {
        long bytes = 0;
        for (Clips.Clip c : clips) bytes += c.bytes;
        Toast.makeText(activity, activity.getString(R.string.clips_usb_copying, clips.size(), Clips.mb(bytes)),
            Toast.LENGTH_LONG).show();
        ClipExport.toUsb(activity, clips, (r, n) -> activity.runOnUiThread(() -> {
            String text;
            switch (r) {
                case OK:       text = activity.getString(R.string.clips_usb_done, n); break;
                case NO_DRIVE: text = activity.getString(R.string.clips_usb_no_drive); break;
                case NO_SPACE: text = activity.getString(R.string.clips_usb_no_space); break;
                default:       text = activity.getString(R.string.clips_usb_failed); break;
            }
            Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
        }));
    }

    /** Drops the pending Record switch re-check. The Activity calls this
     * when it replaces this section and in its own teardown. */
    public void cancelPending() {
        removeCallbacks(recordRecheck);
    }

    @Override protected void onDetachedFromWindow() {
        cancelPending();
        super.onDetachedFromWindow();
    }

    /** Triggers a refresh of the clips section. Called when the user performs an action
     * that changes clip state (hold/release/delete). */
    private void refresh() {
        onRefresh.run();
    }

    private View clipRow(Activity activity, final Clips.Clip c) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        // Held clips are outlined in the accent: the point of holding is seeing
        // at a glance which ones survive the ring buffer.
        card.setBackground(c.held ? Style.outlinedCard(Style.ACCENT, activity)
                                  : Style.card(Style.CARD, activity));
        int p = Style.dp(activity, 14);
        card.setPadding(p, p, p, p);
        // Fills its column, and the column is what caps it (ConfigCards):
        // a thumbnail, a couple of text lines and two or three small buttons
        // never need the whole screen width.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Style.dp(activity, 10);
        card.setLayoutParams(lp);

        if (c.thumb.exists()) {
            android.widget.ImageView shot = new android.widget.ImageView(activity);
            shot.setImageBitmap(android.graphics.BitmapFactory.decodeFile(c.thumb.getAbsolutePath()));
            shot.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams ip =
                new LinearLayout.LayoutParams(Style.dp(activity, 150), Style.dp(activity, 62));
            ip.rightMargin = Style.dp(activity, 14);
            shot.setLayoutParams(ip);
            card.addView(shot);
        }

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean pendingHold = c.kind == Clips.Kind.RECORDING && Clips.isPending(activity, c);
        String tag = c.kind == Clips.Kind.RECORDING
                         ? "  ● " + activity.getString(R.string.clips_recording) + (pendingHold ? "  ★" : "")
                   : c.kind == Clips.Kind.ORPHAN ? "  ⚠ " + activity.getString(R.string.clips_orphan)
                   : c.held ? "  ★" : "";
        if (c.motion) tag += "  ◆ " + activity.getString(R.string.clips_motion);
        text.addView(Style.header(activity, c.title() + tag));
        text.addView(Style.label(activity, c.subtitle()));
        card.addView(text);

        // Play only for a finished clip: a live one has no moov atom and an
        // orphan needs remuxing before anything can open it. Hold works on
        // both a finished clip (moves it right away) and a recording one
        // (Clips.markPending — see its own comment for why a live file can't
        // just be moved; it gets swept into keep/ once the segment closes).
        if (c.playable()) {
            card.addView(Style.cardButton(activity, activity.getString(R.string.clips_play), false,
                () -> activity.startActivity(new Intent(activity, ClipPlayerActivity.class)
                        .putExtra(ClipPlayerActivity.EXTRA_PATH, c.mp4.getAbsolutePath()))));
            card.addView(Style.cardButton(activity,
                activity.getString(c.held ? R.string.clips_release : R.string.clips_hold), c.held,
                () -> { Clips.hold(activity, c, !c.held); refresh(); }));
            card.addView(Style.cardButton(activity, activity.getString(R.string.clips_usb), false,
                () -> exportToUsb(activity, java.util.Collections.singletonList(c))));
        } else if (c.kind == Clips.Kind.RECORDING) {
            final boolean pending = pendingHold;
            card.addView(Style.cardButton(activity,
                activity.getString(pending ? R.string.clips_release : R.string.clips_hold), pending,
                () -> {
                    if (pending) Clips.clearPending(activity, c); else Clips.markPending(activity, c);
                    refresh();
                }));
        } else if (c.kind == Clips.Kind.ORPHAN) {
            // c.mp4 is actually the .h264 for an orphan row -- see Clip's own
            // constructor comment on why the field keeps that name regardless.
            card.addView(Style.cardButton(activity, activity.getString(R.string.clips_recover), false, () -> {
                Toast.makeText(activity, activity.getString(R.string.clips_recovering), Toast.LENGTH_SHORT).show();
                ClipRecovery.recover(activity, c.mp4, (ok, message) -> {
                    Toast.makeText(activity, ok
                        ? activity.getString(R.string.clips_recover_ok)
                        : activity.getString(R.string.clips_recover_failed, message), Toast.LENGTH_LONG).show();
                    refresh();
                });
            }));
        }
        if (c.kind != Clips.Kind.RECORDING) {
            card.addView(Style.cardButton(activity, activity.getString(R.string.clips_delete), false, () ->
                new android.app.AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.clips_delete_q, c.title()))
                    .setMessage(c.subtitle())
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.clips_delete,
                        (d, w) -> { Clips.delete(c); refresh(); })
                    .show()));
        }
        return card;
    }
}
