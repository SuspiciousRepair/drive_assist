package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.controls.WindowControls;
import com.geely.drivemem.util.Style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Human-initiated window commands; selected positions always come from the vehicle. */
final class WindowControlsDialog {
    private static final long REFRESH_MS = 750;
    private static final long CONFIRM_TIMEOUT_MS = 8000;
    private static final int[] TARGETS = {0, 50, 100};
    private static final int[] ACTION_LABELS = {
        R.string.windows_close, R.string.windows_half, R.string.windows_full
    };

    private final Context context;
    private final CarActor actor;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Pane> panes = new LinkedHashMap<>();
    private final List<Group> groups = new ArrayList<>();
    private final AlertDialog dialog;
    private volatile boolean active;
    private boolean reading;

    private static final class Pane {
        final int area;
        final int label;
        Integer position;
        boolean hasReading;
        Integer target;
        Integer unconfirmedTarget;
        boolean submitted;
        long requestedAt;
        int error;
        TextView status;
        Button[] buttons;

        Pane(int area, int label) { this.area = area; this.label = label; }
    }

    private static final class Group {
        final int[] areas;
        final Button[] buttons;
        Group(int[] areas, Button[] buttons) { this.areas = areas; this.buttons = buttons; }
    }

    private final EntityBus.Listener listener = (key, value) -> {
        if (value.status != CarActor.Reading.Status.OK || !(value.value instanceof int[])) return;
        int[] event = (int[]) value.value;
        if (event.length != 2 || !WindowControls.isArea(event[0])) return;
        int area = event[0], position = event[1];
        ui.post(() -> {
            if (!active) return;
            updatePosition(panes.get(area), WindowControls.isPosition(position) ? position : null);
            render();
        });
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!active) return;
            render();
            if (!reading) readPositions();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    static AlertDialog show(Context context) {
        WindowControlsDialog controls = new WindowControlsDialog(context);
        controls.open();
        return controls.dialog;
    }

    private WindowControlsDialog(Context context) {
        this.context = context;
        actor = CarActor.get(context);
        panes.put(WindowControls.FRONT_LEFT, new Pane(WindowControls.FRONT_LEFT, R.string.windows_front_left));
        panes.put(WindowControls.FRONT_RIGHT, new Pane(WindowControls.FRONT_RIGHT, R.string.windows_front_right));
        panes.put(WindowControls.REAR_LEFT, new Pane(WindowControls.REAR_LEFT, R.string.windows_rear_left));
        panes.put(WindowControls.REAR_RIGHT, new Pane(WindowControls.REAR_RIGHT, R.string.windows_rear_right));

        LinearLayout surface = column();
        surface.setPadding(dp(24), dp(20), dp(24), dp(20));
        surface.setBackground(Style.card(Style.CARD, context, 24));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(R.string.windows_title, 34, true);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button done = button(context.getString(R.string.windows_done));
        heading.addView(done, new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        surface.addView(heading);
        TextView hint = text(R.string.windows_hint, 22, false);
        hint.setTextColor(Style.TEXT_DIM);
        hint.setPadding(0, dp(8), 0, dp(16));
        surface.addView(hint);

        LinearLayout content = column();
        boolean wide = context.getResources().getConfiguration().screenWidthDp >= 760;
        Pane[] ordered = panes.values().toArray(new Pane[0]);
        for (int row = 0; row < 2; row++) {
            LinearLayout pair = new LinearLayout(context);
            pair.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            for (int side = 0; side < 2; side++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, wide ? 1f : 0f);
                if (wide && side == 0) lp.rightMargin = dp(16);
                lp.bottomMargin = dp(16);
                pair.addView(paneCard(ordered[row * 2 + side]), lp);
            }
            content.addView(pair);
        }
        TextView groupTitle = text(R.string.windows_groups, 28, true);
        groupTitle.setAccessibilityHeading(true);
        groupTitle.setPadding(0, 0, 0, dp(12));
        content.addView(groupTitle);
        LinearLayout groupRow = new LinearLayout(context);
        groupRow.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int[][] areas = {
            {WindowControls.FRONT_LEFT, WindowControls.FRONT_RIGHT},
            {WindowControls.REAR_LEFT, WindowControls.REAR_RIGHT}, WindowControls.areas()
        };
        int[] labels = {R.string.windows_front_pair, R.string.windows_rear_pair, R.string.windows_all};
        for (int i = 0; i < areas.length; i++) {
            LinearLayout group = card();
            TextView label = text(labels[i], 26, true);
            label.setPadding(0, 0, 0, dp(12));
            group.addView(label);
            Button[] buttons = addActions(group, areas[i], labels[i]);
            groups.add(new Group(areas[i], buttons));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, wide ? 1f : 0f);
            if (wide && i < areas.length - 1) lp.rightMargin = dp(12);
            lp.bottomMargin = dp(12);
            groupRow.addView(group, lp);
        }
        content.addView(groupRow);
        TextView note = text(R.string.windows_feedback_note, 20, false);
        note.setTextColor(Style.TEXT_DIM);
        note.setPadding(0, dp(4), 0, dp(4));
        content.addView(note);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(content);
        surface.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog = new AlertDialog(context) {
            @Override public void dismiss() {
                // OnDismissListener is dispatched later. Cancel synchronously so
                // a blocked vehicle read cannot submit a write in that interval.
                stop();
                super.dismiss();
            }
        };
        dialog.setView(surface);
        done.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> stop());
        render();
    }

    private void open() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(0.65f);
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            window.setLayout(Math.min(dp(1360), metrics.widthPixels - dp(32)),
                Math.min(dp(860), metrics.heightPixels - dp(64)));
        }
        active = true;
        EntityBus.subscribe("car.window_pos", listener);
        refresh.run();
    }

    private void stop() {
        active = false;
        EntityBus.unsubscribe("car.window_pos", listener);
        ui.removeCallbacksAndMessages(null);
    }

    private LinearLayout paneCard(Pane pane) {
        LinearLayout card = card();
        TextView title = text(pane.label, 28, true);
        card.addView(title);
        pane.status = text(R.string.windows_reading, 22, false);
        pane.status.setTextColor(Style.TEXT_DIM);
        pane.status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pane.status.setMinHeight(dp(64));
        pane.status.setPadding(0, dp(8), 0, dp(12));
        card.addView(pane.status);
        pane.buttons = addActions(card, new int[]{pane.area}, pane.label);
        return card;
    }

    private Button[] addActions(LinearLayout parent, int[] areas, int label) {
        LinearLayout row = new LinearLayout(context);
        Button[] buttons = new Button[TARGETS.length];
        for (int i = 0; i < TARGETS.length; i++) {
            final int target = TARGETS[i];
            Button button = button(context.getString(ACTION_LABELS[i]));
            button.setContentDescription(context.getString(label) + ": " + context.getString(ACTION_LABELS[i]));
            button.setOnClickListener(v -> request(areas, target));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(button, lp);
            buttons[i] = button;
        }
        parent.addView(row);
        return buttons;
    }

    private void request(int[] areas, int target) {
        if (!active) return;
        for (int area : areas) if (panes.get(area).target != null) return;
        for (int area : areas) {
            Pane pane = panes.get(area);
            pane.target = target;
            pane.unconfirmedTarget = null;
            pane.submitted = false;
            pane.error = 0;
        }
        render();
        actor.runOnCarThread(() -> {
            for (int area : areas) {
                // A dismissed/backgrounded popup must not start another command.
                if (!active) return;
                WindowControls.Result result = WindowControls.request(actor.rawAccess(), area, target, () -> active);
                ui.post(() -> {
                    if (!active) return;
                    Pane pane = panes.get(area);
                    if (result == WindowControls.Result.REQUESTED) {
                        pane.submitted = true;
                        pane.requestedAt = SystemClock.elapsedRealtime();
                    } else {
                        pane.target = null;
                        if (result == WindowControls.Result.UNAVAILABLE) {
                            pane.position = null;
                            pane.hasReading = true;
                            pane.error = R.string.windows_unavailable;
                        } else if (result == WindowControls.Result.REJECTED) {
                            pane.error = R.string.windows_rejected;
                        }
                    }
                    render();
                });
            }
            ui.post(() -> { if (active && !reading) readPositions(); });
        });
    }

    private void readPositions() {
        reading = true;
        actor.runOnCarThread(() -> {
            if (!active) return;
            Map<Integer, Integer> positions = new LinkedHashMap<>();
            for (int area : WindowControls.areas()) positions.put(area, WindowControls.read(actor.rawAccess(), area));
            ui.post(() -> {
                if (!active) return;
                reading = false;
                for (Map.Entry<Integer, Integer> entry : positions.entrySet()) {
                    updatePosition(panes.get(entry.getKey()), entry.getValue());
                }
                render();
            });
        });
    }

    private void updatePosition(Pane pane, Integer position) {
        pane.position = position;
        pane.hasReading = true;
        if (pane.submitted && pane.target != null && pane.target.equals(position)) {
            pane.target = null;
            pane.error = 0;
        }
        if (pane.unconfirmedTarget != null && pane.unconfirmedTarget.equals(position)) {
            pane.unconfirmedTarget = null;
            pane.error = 0;
        }
        if (position != null && pane.error == R.string.windows_unavailable) pane.error = 0;
    }

    private void render() {
        long now = SystemClock.elapsedRealtime();
        for (Pane pane : panes.values()) {
            if (pane.status == null) continue;
            if (pane.target != null && pane.submitted && now - pane.requestedAt >= CONFIRM_TIMEOUT_MS) {
                pane.unconfirmedTarget = pane.target;
                pane.target = null;
                pane.error = R.string.windows_unconfirmed;
            }
            String position = pane.position == null ? context.getString(pane.hasReading
                ? R.string.windows_unavailable : R.string.windows_reading)
                : pane.position == 0 ? context.getString(R.string.windows_closed)
                : context.getString(R.string.windows_position, pane.position);
            String status = pane.target != null
                ? position + "\n" + (pane.submitted ? context.getString(R.string.windows_requested, pane.target)
                    : context.getString(R.string.windows_sending))
                : pane.error != 0 && pane.error != R.string.windows_unavailable
                    ? position + "\n" + context.getString(pane.error) : position;
            if (!status.contentEquals(pane.status.getText())) pane.status.setText(status);
            for (int i = 0; i < pane.buttons.length; i++) {
                styleButton(pane.buttons[i], pane.position != null && pane.target == null,
                    pane.position != null && pane.position == TARGETS[i]);
            }
        }
        for (Group group : groups) {
            boolean readable = false, busy = false;
            for (int area : group.areas) {
                Pane pane = panes.get(area);
                readable |= pane.position != null;
                busy |= pane.target != null;
            }
            for (Button button : group.buttons) styleButton(button, readable && !busy, false);
        }
    }

    private void styleButton(Button button, boolean enabled, boolean selected) {
        if (button.getTag() != null && button.isEnabled() == enabled && button.isSelected() == selected) return;
        button.setTag(Boolean.TRUE);
        button.setEnabled(enabled);
        button.setSelected(selected);
        button.setAlpha(enabled ? 1f : 0.45f);
        int fill = selected ? Style.CARD_ON : Style.CARD;
        button.setTextColor(Style.onFill(fill));
        GradientDrawable background = Style.card(fill, context, 12);
        background.setStroke(dp(selected ? 2 : 1), selected ? Style.ACCENT : Style.STROKE_COLOR);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Style.blend(fill, Style.TEXT, 0.16f)), background, null));
    }

    private Button button(String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(22);
        button.setTypeface(Style.font(context));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(64));
        button.setPadding(dp(8), dp(12), dp(8), dp(12));
        button.setStateListAnimator(null);
        styleButton(button, true, false);
        return button;
    }

    private TextView text(int resource, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(resource);
        view.setTextSize(size);
        view.setTextColor(Style.TEXT);
        view.setTypeface(Style.font(context), bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(16), dp(20), dp(16));
        card.setBackground(Style.card(Style.CARD_HI, context, 20));
        return card;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private int dp(int value) { return Style.dp(context, value); }
}
