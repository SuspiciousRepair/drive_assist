package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.controls.GeelySwitch;
import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Calm 2-column automotive modal for adding a Car Diary entry.
 *
 * Left column toggles between:
 *  - Collapsed item list (`<- [icon] Class` + plain rows, crisp accent outline on active item)
 *  - Expanded 5-class picker (`x Add entry` + Config-sidebar pills with chevrons)
 *
 * Right column is the live Details card:
 *  - Title + read-only auto-looked-up odometer (`Today · 14,820 km` / `21 Nov · ~14,747 km`)
 *  - `Today | Yesterday | Pick date` segmented pill + inline scrollable 6-day strip
 *  - Adaptive per-item control (no Cold/Warm toggle — inferred automatically from CarDb)
 *  - Compact `Cost` + `Note` pair and full-width primary `Save` pill.
 *
 * Park-gated for keyboard safety. */
public final class DiaryEntryDialog {
    private DiaryEntryDialog() {}

    private static final int DEFAULT_PSI = 36;
    private static final int MIN_PSI = 20;
    private static final int MAX_PSI = 55;

    private static final int[] NOISE_WHERE_RES = {
        R.string.diary_tag_fl, R.string.diary_tag_fr,
        R.string.diary_tag_rear, R.string.diary_tag_cabin
    };
    private static final int[] NOISE_WHEN_RES = {
        R.string.diary_tag_bumps, R.string.diary_tag_braking, R.string.diary_tag_highway
    };

    public static AlertDialog show(Context c, CarDiary.Category initialCat,
                                   CarDiary.PendingJolt jolt, Runnable onSaved) {
        return show(c, initialCat, jolt, null, onSaved);
    }

    public static AlertDialog show(Context c, CarDiary.Category initialCat,
                                   CarDiary.PendingJolt jolt,
                                   List<CarDiary.DueStatus> dueList,
                                   Runnable onSaved) {
        if (!CarState.isParked()) {
            Toast.makeText(c, c.getString(R.string.diary_park_required), Toast.LENGTH_SHORT).show();
            return null;
        }
        return new Builder(c, initialCat, jolt, dueList, onSaved).show();
    }

    private static final class Builder {
        private final Context c;
        private final CarDiary.PendingJolt jolt;
        private final Runnable onSaved;
        private final DateTimeFormatter shortDateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());
        private final Set<CarDiary.Category> dueCats = EnumSet.noneOf(CarDiary.Category.class);
        private final Set<CarDiary.Group> dueGroups = EnumSet.noneOf(CarDiary.Group.class);
        private final Map<CarDiary.Group, CarDiary.Category> pickedByGroup = new EnumMap<>(CarDiary.Group.class);

        private boolean showingClasses = false;
        private CarDiary.Group selectedGroup;
        private CarDiary.Category selectedCat;
        private LocalDate selectedDate;
        private boolean stripOpen = false;
        private int stripPage = 0;

        // Auto-attached car odometer for selectedDate (never manually edited)
        private Double resolvedOdoKm = null;
        private boolean resolvedOdoExact = true;
        private int odoLookupGen = 0;

        // Tire pressure state (cold/warm inferred from CarDb on save when logging today)
        private final int[] psi = {DEFAULT_PSI, DEFAULT_PSI, DEFAULT_PSI, DEFAULT_PSI};
        private boolean perWheel = false;

        // Noise tag state
        private final Set<String> selectedTags = new LinkedHashSet<>();

        // Incident state
        private boolean saveClip = true;

        // Service bundled state
        private final Set<CarDiary.Category> bundled = new LinkedHashSet<>();

        // UI references
        private LinearLayout leftHost;
        private TextView paneTitle;
        private TextView paneCtx;
        private LinearLayout dateSeg;
        private LinearLayout dateStrip;
        private LinearLayout adaptiveBox;
        private EditText costInput;
        private EditText noteInput;
        private AlertDialog dialog;

        Builder(Context c, CarDiary.Category initialCat, CarDiary.PendingJolt jolt,
                List<CarDiary.DueStatus> dueList, Runnable onSaved) {
            this.c = c;
            this.jolt = jolt;
            this.onSaved = onSaved;

            if (dueList != null) {
                for (CarDiary.DueStatus d : dueList) {
                    if (d.overdue || d.dueSoon) {
                        dueCats.add(d.category);
                        dueGroups.add(d.category.group);
                    }
                }
            }

            if (jolt != null) {
                this.selectedCat = CarDiary.Category.INCIDENT;
                this.selectedGroup = CarDiary.Group.NOTES;
                this.selectedDate = Instant.ofEpochMilli(jolt.tsMs)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            } else if (initialCat != null && initialCat.userPickable) {
                this.selectedCat = initialCat;
                this.selectedGroup = initialCat.group;
                this.selectedDate = LocalDate.now();
            } else {
                // Open directly on the most urgent due/overdue item so logging it is 1 tap (Save).
                CarDiary.DueStatus urgent = CarDiary.mostUrgent(dueList);
                if (urgent != null && (urgent.overdue || urgent.dueSoon) && urgent.category.userPickable) {
                    this.selectedCat = urgent.category;
                    this.selectedGroup = urgent.category.group;
                } else {
                    this.selectedGroup = CarDiary.Group.TIRES;
                    this.selectedCat = CarDiary.Category.TIRE_PRESSURE;
                }
                this.selectedDate = LocalDate.now();
            }
            pickedByGroup.put(selectedGroup, selectedCat);
        }

        AlertDialog show() {
            LinearLayout sheet = new LinearLayout(c);
            sheet.setOrientation(LinearLayout.HORIZONTAL);
            sheet.setBackground(roundedRect(Style.CARD, borderColor(), 1, 28));
            int pad = Style.dp(c, 22);
            sheet.setPadding(pad, pad, pad, pad);

            // ---- LEFT COLUMN (35%): collapses between 5 Classes <-> [<- Icon Class + Items] ----
            ScrollView leftScroll = new ScrollView(c);
            leftScroll.setVerticalScrollBarEnabled(false);
            leftScroll.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 35f));

            leftHost = new LinearLayout(c);
            leftHost.setOrientation(LinearLayout.VERTICAL);
            leftScroll.addView(leftHost, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // ---- RIGHT COLUMN (65%): calm Details card ----
            LinearLayout pane = new LinearLayout(c);
            pane.setOrientation(LinearLayout.VERTICAL);
            pane.setBackground(roundedRect(Style.CARD_HI, borderColor(), 1, 22));
            int pp = Style.dp(c, 24);
            pane.setPadding(pp, pp, pp, pp);
            pane.setMinimumHeight(Style.dp(c, 520));
            pane.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 65f));

            // Header: Item title + quiet read-only Date · Odometer
            LinearLayout pHead = new LinearLayout(c);
            pHead.setOrientation(LinearLayout.HORIZONTAL);
            pHead.setGravity(Gravity.CENTER_VERTICAL);

            paneTitle = new TextView(c);
            paneTitle.setTextColor(Style.TEXT);
            paneTitle.setTextSize(26f);
            paneTitle.setTypeface(null, Typeface.BOLD);
            paneTitle.setMaxLines(1);
            paneTitle.setEllipsize(TextUtils.TruncateAt.END);
            pHead.addView(paneTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            paneCtx = new TextView(c);
            paneCtx.setTextColor(Style.TEXT_DIM);
            paneCtx.setTextSize(17f);
            paneCtx.setPadding(Style.dp(c, 14), 0, 0, 0);
            pHead.addView(paneCtx);
            pane.addView(pHead);

            // Date segmented pill: Today | Yesterday | Pick date
            dateSeg = new LinearLayout(c);
            dateSeg.setOrientation(LinearLayout.HORIZONTAL);
            dateSeg.setBackground(roundedRect(wellFill(), borderColor(), 1, 99));
            int sp = Style.dp(c, 4);
            dateSeg.setPadding(sp, sp, sp, sp);
            LinearLayout.LayoutParams dslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dslp.topMargin = Style.dp(c, 18);
            pane.addView(dateSeg, dslp);

            // Expandable 6-day date strip
            dateStrip = new LinearLayout(c);
            dateStrip.setOrientation(LinearLayout.HORIZONTAL);
            dateStrip.setGravity(Gravity.CENTER_VERTICAL);
            dateStrip.setVisibility(View.GONE);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.topMargin = Style.dp(c, 10);
            pane.addView(dateStrip, stLp);

            // Adaptive per-item control container
            adaptiveBox = new LinearLayout(c);
            adaptiveBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams adLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            adLp.topMargin = Style.dp(c, 18);
            pane.addView(adaptiveBox, adLp);

            // Compact Cost + Note pair (single row, no stacked labels)
            LinearLayout pair = new LinearLayout(c);
            pair.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pairLp.topMargin = Style.dp(c, 18);

            // Cost well (35%)
            LinearLayout costWell = new LinearLayout(c);
            costWell.setOrientation(LinearLayout.HORIZONTAL);
            costWell.setGravity(Gravity.CENTER_VERTICAL);
            costWell.setBackground(roundedRect(wellFill(), borderColor(), 1, 18));
            costWell.setPadding(Style.dp(c, 16), Style.dp(c, 8), Style.dp(c, 14), Style.dp(c, 8));
            costWell.setMinimumHeight(Style.dp(c, 58));

            TextView cur = new TextView(c);
            cur.setText(Prefs.getCurrency(c));
            cur.setTextColor(Style.TEXT_DIM);
            cur.setTextSize(15f);
            cur.setTypeface(null, Typeface.BOLD);
            cur.setPadding(0, 0, Style.dp(c, 8), 0);
            costWell.addView(cur);

            costInput = bareInput(InputType.TYPE_CLASS_NUMBER,
                c.getString(R.string.diary_hint_cost_short), 19f, true);
            installCentsFormatter(costInput);
            costWell.addView(costInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pair.addView(costWell, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 35f));

            pair.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 12), 1));

            // Note well (65%)
            LinearLayout noteWell = new LinearLayout(c);
            noteWell.setOrientation(LinearLayout.HORIZONTAL);
            noteWell.setGravity(Gravity.CENTER_VERTICAL);
            noteWell.setBackground(roundedRect(wellFill(), borderColor(), 1, 18));
            noteWell.setPadding(Style.dp(c, 18), Style.dp(c, 8), Style.dp(c, 18), Style.dp(c, 8));
            noteWell.setMinimumHeight(Style.dp(c, 58));

            noteInput = bareInput(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                c.getString(R.string.diary_hint_note_short), 18f, false);
            if (jolt != null) {
                String pre = jolt.speedKmh != null
                    ? c.getString(R.string.diary_jolt_prefill_note, jolt.maxG(), jolt.speedKmh)
                    : c.getString(R.string.diary_jolt_prefill_note_nospeed, jolt.maxG());
                noteInput.setText(pre);
            }
            noteWell.addView(noteInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pair.addView(noteWell, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 65f));
            pane.addView(pair, pairLp);

            // Flexible spacer so Save sits pinned cleanly at the bottom of the Details card
            View spacer = new View(c);
            pane.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 16), 1f));

            // Full-width primary Save pill
            TextView saveBtn = new TextView(c);
            saveBtn.setText(c.getString(R.string.diary_btn_save));
            saveBtn.setTextColor(Style.onFill(Style.ACCENT));
            saveBtn.setTextSize(20f);
            saveBtn.setTypeface(null, Typeface.BOLD);
            saveBtn.setGravity(Gravity.CENTER);
            saveBtn.setBackground(roundedRect(Style.ACCENT, 0, 0, 99));
            saveBtn.setMinimumHeight(Style.dp(c, 58));
            saveBtn.setOnClickListener(v -> save());
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 58));
            saveLp.topMargin = Style.dp(c, 8);
            pane.addView(saveBtn, saveLp);

            // Mirror left/right columns in RHD so the category picker stays on the driver's edge
            Style.addMirrored(sheet, 22, leftScroll, pane);

            rebuildLeft();
            rebuildPaneHeader();
            rebuildDateSeg();
            rebuildAdaptive();
            refreshOdometerForSelectedDate(true);

            dialog = new AlertDialog.Builder(c).setView(sheet).create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
            dialog.show();
            if (dialog.getWindow() != null) {
                int w = Math.min(Style.dp(c, 1260),
                    (int) (c.getResources().getDisplayMetrics().widthPixels * 0.84f));
                dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            return dialog;
        }

        // ---- Left Column: Collapsed Items <-> Expanded 5 Classes ----

        private void rebuildLeft() {
            leftHost.removeAllViews();
            if (showingClasses) {
                buildClassesList();
            } else {
                buildItemsList();
            }
        }

        private void buildItemsList() {
            // Header: [<- circle] [accent icon] ClassName  (tap anywhere to expand 5 classes)
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(Style.dp(c, 4), Style.dp(c, 4), Style.dp(c, 8), Style.dp(c, 4));
            head.setMinimumHeight(Style.dp(c, 52));
            head.setOnClickListener(v -> {
                showingClasses = true;
                rebuildLeft();
            });

            head.addView(circleIconButton(R.drawable.ic_arrow_back));

            ImageView clsIco = iconView(selectedGroup.iconRes, 24, Style.ACCENT);
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                Style.dp(c, 24), Style.dp(c, 24));
            icLp.leftMargin = Style.dp(c, 14);
            head.addView(clsIco, icLp);

            TextView clsTitle = new TextView(c);
            clsTitle.setText(c.getString(selectedGroup.labelRes));
            clsTitle.setTextColor(Style.TEXT);
            clsTitle.setTextSize(21f);
            clsTitle.setTypeface(null, Typeface.BOLD);
            clsTitle.setMaxLines(1);
            clsTitle.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlLp.leftMargin = Style.dp(c, 12);
            head.addView(clsTitle, tlLp);
            leftHost.addView(head);

            leftHost.addView(leftDivider());

            // Plain calm rows; only active item gets the crisp accent outline + CARD_HI fill
            List<CarDiary.Category> items = CarDiary.Category.forGroup(selectedGroup);
            for (int i = 0; i < items.size(); i++) {
                CarDiary.Category cat = items.get(i);
                boolean active = (cat == selectedCat);
                boolean isDue = dueCats.contains(cat);

                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Style.dp(c, 18), Style.dp(c, 12), Style.dp(c, 18), Style.dp(c, 12));
                row.setMinimumHeight(Style.dp(c, 56));
                if (active) {
                    row.setBackground(roundedRect(Style.CARD_HI, Style.ACCENT, 2, 18));
                }

                TextView lbl = new TextView(c);
                lbl.setText(c.getString(cat.labelRes));
                lbl.setTextColor(Style.TEXT);
                lbl.setTextSize(18.5f);
                lbl.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
                lbl.setMaxLines(1);
                lbl.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (isDue) {
                    TextView due = new TextView(c);
                    due.setText(c.getString(R.string.diary_due_badge));
                    due.setTextColor(Style.WARN);
                    due.setTextSize(15f);
                    due.setTypeface(null, Typeface.BOLD);
                    due.setPadding(Style.dp(c, 10), 0, 0, 0);
                    row.addView(due);
                }

                row.setOnClickListener(v -> {
                    if (selectedCat == cat) return;
                    selectedCat = cat;
                    pickedByGroup.put(selectedGroup, cat);
                    rebuildLeft();
                    rebuildPaneHeader();
                    rebuildAdaptive();
                });

                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Style.dp(c, 8);
                leftHost.addView(row, rlp);
            }
        }

        private void buildClassesList() {
            // Header: [x circle] Add entry  (tap closes the dialog)
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(Style.dp(c, 4), Style.dp(c, 4), Style.dp(c, 8), Style.dp(c, 4));
            head.setMinimumHeight(Style.dp(c, 52));
            head.setOnClickListener(v -> { if (dialog != null) dialog.dismiss(); });

            head.addView(circleIconButton(R.drawable.ic_close));

            TextView title = new TextView(c);
            title.setText(c.getString(R.string.diary_add_title));
            title.setTextColor(Style.TEXT);
            title.setTextSize(20f);
            title.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlLp.leftMargin = Style.dp(c, 14);
            head.addView(title, tlLp);
            leftHost.addView(head);

            leftHost.addView(leftDivider());

            CarDiary.Group[] groups = CarDiary.Group.values();
            for (int i = 0; i < groups.length; i++) {
                CarDiary.Group g = groups[i];
                boolean active = (g == selectedGroup);
                boolean isDue = dueGroups.contains(g);
                int fg = active ? Style.onFill(Style.ACCENT) : Style.TEXT_DIM;

                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Style.dp(c, 18), Style.dp(c, 12), Style.dp(c, 16), Style.dp(c, 12));
                row.setMinimumHeight(Style.dp(c, 58));
                if (active) {
                    row.setBackground(roundedRect(Style.ACCENT, 0, 0, 99));
                }

                ImageView ico = iconView(g.iconRes, 22, fg);
                row.addView(ico, new LinearLayout.LayoutParams(Style.dp(c, 22), Style.dp(c, 22)));

                TextView lbl = new TextView(c);
                lbl.setText(c.getString(g.labelRes));
                lbl.setTextColor(active ? fg : Style.TEXT);
                lbl.setTextSize(19f);
                lbl.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
                lbl.setMaxLines(1);
                lbl.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                llp.leftMargin = Style.dp(c, 14);
                row.addView(lbl, llp);

                if (isDue) {
                    TextView due = new TextView(c);
                    due.setText(c.getString(R.string.diary_due_badge));
                    due.setTextColor(active ? fg : Style.WARN);
                    due.setTextSize(14.5f);
                    due.setTypeface(null, Typeface.BOLD);
                    due.setPadding(Style.dp(c, 8), 0, Style.dp(c, 6), 0);
                    row.addView(due);
                }

                ImageView chev = iconView(R.drawable.ic_chevron_right, 18,
                    active ? withAlpha(fg, 0.8f) : withAlpha(Style.TEXT_DIM, 0.6f));
                row.addView(chev, new LinearLayout.LayoutParams(Style.dp(c, 18), Style.dp(c, 18)));

                row.setOnClickListener(v -> {
                    selectedGroup = g;
                    CarDiary.Category remembered = pickedByGroup.get(g);
                    if (remembered != null && remembered.group == g) {
                        selectedCat = remembered;
                    } else {
                        List<CarDiary.Category> subs = CarDiary.Category.forGroup(g);
                        if (!subs.isEmpty()) selectedCat = subs.get(0);
                    }
                    pickedByGroup.put(g, selectedCat);
                    showingClasses = false;
                    rebuildLeft();
                    rebuildPaneHeader();
                    rebuildAdaptive();
                });

                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Style.dp(c, 8);
                leftHost.addView(row, rlp);
            }
        }

        private LinearLayout circleIconButton(int drawableRes) {
            LinearLayout box = new LinearLayout(c);
            box.setGravity(Gravity.CENTER);
            box.setBackground(roundedRect(Color.TRANSPARENT, strongBorderColor(), 1, 99));
            int sz = Style.dp(c, 42);
            box.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            box.addView(iconView(drawableRes, 18, Style.TEXT));
            return box;
        }

        private View leftDivider() {
            View v = new View(c);
            v.setBackgroundColor(borderColor());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 1));
            lp.topMargin = Style.dp(c, 12);
            lp.bottomMargin = Style.dp(c, 14);
            lp.leftMargin = Style.dp(c, 4);
            lp.rightMargin = Style.dp(c, 4);
            v.setLayoutParams(lp);
            return v;
        }

        // ---- Right Column: Header + Auto-Odometer + Date Segmented & Strip ----

        private void rebuildPaneHeader() {
            paneTitle.setText(c.getString(selectedCat.labelRes));
            LocalDate today = LocalDate.now();
            String dateLabel = selectedDate.equals(today)
                ? c.getString(R.string.diary_date_today)
                : (selectedDate.equals(today.minusDays(1))
                    ? c.getString(R.string.diary_date_yesterday)
                    : shortDateFmt.format(selectedDate));

            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(dateLabel);
            if (resolvedOdoKm != null && resolvedOdoKm > 0) {
                sb.append(" · ");
                String num = (resolvedOdoExact ? "" : "~")
                    + String.format(Locale.getDefault(), "%,.0f", resolvedOdoKm);
                int start = sb.length();
                sb.append(Style.valueWithUnit(num, Style.TEXT, "km", Style.UNIT_SCALE_ROW));
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, start + num.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            paneCtx.setText(sb);
        }

        private void rebuildDateSeg() {
            dateSeg.removeAllViews();
            LocalDate today = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);
            boolean isToday = selectedDate.equals(today);
            boolean isYesterday = selectedDate.equals(yesterday);
            boolean isCustom = !isToday && !isYesterday;

            dateSeg.addView(segBtn(c.getString(R.string.diary_date_today), isToday, () -> {
                selectedDate = today;
                stripOpen = false;
                rebuildDateSeg();
                rebuildDateStrip();
                rebuildPaneHeader();
                refreshOdometerForSelectedDate(false);
            }));
            dateSeg.addView(segBtn(c.getString(R.string.diary_date_yesterday), isYesterday, () -> {
                selectedDate = yesterday;
                stripOpen = false;
                rebuildDateSeg();
                rebuildDateStrip();
                rebuildPaneHeader();
                refreshOdometerForSelectedDate(false);
            }));
            String pickLbl = isCustom
                ? shortDateFmt.format(selectedDate)
                : c.getString(R.string.diary_date_pick);
            dateSeg.addView(segBtn(pickLbl, isCustom || stripOpen, () -> {
                stripOpen = true;
                if (!isCustom) {
                    selectedDate = today.minusDays(2);
                    stripPage = 0;
                    refreshOdometerForSelectedDate(false);
                }
                rebuildDateSeg();
                rebuildDateStrip();
                rebuildPaneHeader();
            }));
        }

        private TextView segBtn(String label, boolean active, Runnable onTap) {
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextSize(16.5f);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setTextColor(active ? Style.TEXT : Style.TEXT_DIM);
            tv.setGravity(Gravity.CENTER);
            tv.setMinimumHeight(Style.dp(c, 46));
            tv.setMaxLines(1);
            if (active) {
                tv.setBackground(roundedRect(Style.CARD_HI, strongBorderColor(), 1, 99));
            }
            tv.setOnClickListener(v -> onTap.run());
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return tv;
        }

        private void rebuildDateStrip() {
            dateStrip.removeAllViews();
            if (!stripOpen) {
                dateStrip.setVisibility(View.GONE);
                return;
            }
            dateStrip.setVisibility(View.VISIBLE);
            LocalDate today = LocalDate.now();
            Locale loc = Locale.getDefault();
            int gap = Style.dp(c, 8);

            // Earlier button (<)
            dateStrip.addView(stripPageBtn("‹", true, () -> {
                stripPage++;
                rebuildDateStrip();
            }));

            // 6 day tiles (oldest -> newest left to right, starting at 2 days ago)
            for (int k = 5; k >= 0; k--) {
                int daysAgo = 2 + stripPage * 6 + k;
                LocalDate d = today.minusDays(daysAgo);
                boolean active = d.equals(selectedDate);

                LinearLayout tile = new LinearLayout(c);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setMinimumHeight(Style.dp(c, 56));
                tile.setBackground(active
                    ? roundedRect(accentTint(), Style.ACCENT, 2, 14)
                    : roundedRect(wellFill(), borderColor(), 1, 14));

                TextView wd = new TextView(c);
                wd.setText(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, loc));
                wd.setTextColor(Style.TEXT_DIM);
                wd.setTextSize(12.5f);
                wd.setMaxLines(1);
                tile.addView(wd);

                TextView dn = new TextView(c);
                dn.setText(String.valueOf(d.getDayOfMonth()));
                dn.setTextColor(Style.TEXT);
                dn.setTextSize(17.5f);
                dn.setTypeface(null, Typeface.BOLD);
                tile.addView(dn);

                tile.setOnClickListener(v -> {
                    selectedDate = d;
                    rebuildDateSeg();
                    rebuildDateStrip();
                    rebuildPaneHeader();
                    refreshOdometerForSelectedDate(false);
                });

                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tlp.leftMargin = gap;
                dateStrip.addView(tile, tlp);
            }

            // Later button (>)
            TextView later = stripPageBtn("›", stripPage > 0, () -> {
                if (stripPage > 0) {
                    stripPage--;
                    rebuildDateStrip();
                }
            });
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                Style.dp(c, 42), Style.dp(c, 56));
            llp.leftMargin = gap;
            dateStrip.addView(later, llp);
        }

        private TextView stripPageBtn(String glyph, boolean enabled, Runnable onTap) {
            TextView tv = new TextView(c);
            tv.setText(glyph);
            tv.setTextColor(enabled ? Style.TEXT_DIM : withAlpha(Style.TEXT_DIM, 0.3f));
            tv.setTextSize(24f);
            tv.setGravity(Gravity.CENTER);
            tv.setBackground(roundedRect(Color.TRANSPARENT, borderColor(), 1, 14));
            tv.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, 42), Style.dp(c, 56)));
            if (enabled) tv.setOnClickListener(v -> onTap.run());
            return tv;
        }

        private void refreshOdometerForSelectedDate(boolean initial) {
            if (initial && jolt != null && jolt.odoKm != null && jolt.odoKm > 0) {
                resolvedOdoKm = jolt.odoKm;
                resolvedOdoExact = true;
                rebuildPaneHeader();
                return;
            }
            final LocalDate target = selectedDate;
            final int gen = ++odoLookupGen;
            final Context app = c.getApplicationContext();
            Runnable lookup = () -> {
                CarDiary.OdoLookup res;
                if (target.equals(LocalDate.now())) {
                    Double live = CarDiary.latestOdometerKm(app);
                    res = (live != null && live > 0)
                        ? new CarDiary.OdoLookup(live, true)
                        : CarDiary.odometerForDate(app, target);
                } else {
                    res = CarDiary.odometerForDate(app, target);
                }
                paneCtx.post(() -> {
                    if (gen != odoLookupGen) return;
                    resolvedOdoKm = (res.odoKm != null && res.odoKm > 0) ? res.odoKm : null;
                    resolvedOdoExact = res.exact;
                    rebuildPaneHeader();
                });
            };
            CarActor actor = CarActor.get(app);
            if (actor != null) actor.runOnCarThread(lookup);
            else new Thread(lookup, "diary-odo").start();
        }

        // ---- Adaptive Controls (only shown for categories that need them) ----

        private void rebuildAdaptive() {
            adaptiveBox.removeAllViews();
            if (selectedCat == CarDiary.Category.TIRE_PRESSURE) {
                adaptiveBox.setVisibility(View.VISIBLE);
                buildTirePressureControls();
            } else if (selectedCat == CarDiary.Category.NOISE) {
                adaptiveBox.setVisibility(View.VISIBLE);
                buildNoiseTags();
            } else if (selectedCat == CarDiary.Category.INCIDENT) {
                adaptiveBox.setVisibility(View.VISIBLE);
                buildIncidentControls();
            } else if (selectedCat == CarDiary.Category.SERVICE) {
                adaptiveBox.setVisibility(View.VISIBLE);
                buildServiceBundles();
            } else {
                adaptiveBox.setVisibility(View.GONE);
            }
        }

        private void buildTirePressureControls() {
            // Header: Pressure (left) + Per wheel switch (right). No Cold/Warm toggle.
            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView lbl = subtleLabel(c.getString(R.string.diary_field_pressure));
            top.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView pwLbl = subtleLabel(c.getString(R.string.diary_psi_per_wheel));
            pwLbl.setPadding(0, 0, Style.dp(c, 12), 0);
            top.addView(pwLbl);

            GeelySwitch sw = new GeelySwitch(c);
            sw.setLockSeconds(0);
            sw.setCheckedSilently(perWheel);
            sw.setOnToggle(on -> {
                if (on && !perWheel) {
                    int base = psi[0];
                    for (int i = 0; i < 4; i++) psi[i] = base;
                }
                perWheel = on;
                rebuildAdaptive();
            });
            top.addView(sw);
            adaptiveBox.addView(top);

            if (!perWheel) {
                LinearLayout stepper = buildPsiStepper(null, 0, true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Style.dp(c, 12);
                adaptiveBox.addView(stepper, lp);
            } else {
                String[] wheelLabels = {"FL", "FR", "RL", "RR"};
                List<View> steppers = new ArrayList<>();
                for (int i = 0; i < 4; i++) steppers.add(buildPsiStepper(wheelLabels[i], i, false));
                LinearLayout wrap = new LinearLayout(c);
                wrap.setOrientation(LinearLayout.VERTICAL);
                addNColumnGrid(wrap, steppers, 2, 12);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Style.dp(c, 12);
                adaptiveBox.addView(wrap, lp);
            }
        }

        private LinearLayout buildPsiStepper(String wheelLabel, int idx, boolean allFour) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER);
            box.setBackground(roundedRect(wellFill(), borderColor(), 1, 18));
            box.setMinimumHeight(Style.dp(c, allFour ? 82 : 62));
            int px = Style.dp(c, allFour ? 20 : 12);
            int py = Style.dp(c, allFour ? 12 : 8);
            box.setPadding(px, py, px, py);

            if (wheelLabel != null) {
                TextView wl = new TextView(c);
                wl.setText(wheelLabel);
                wl.setTextColor(Style.TEXT_DIM);
                wl.setTextSize(14f);
                wl.setTypeface(null, Typeface.BOLD);
                wl.setMinWidth(Style.dp(c, 28));
                box.addView(wl);
                box.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            }

            TextView val = new TextView(c);
            val.setTextSize(allFour ? 36f : 22f);
            val.setTypeface(null, Typeface.BOLD);
            val.setTextColor(Style.TEXT);
            val.setGravity(Gravity.CENTER);
            val.setText(Style.valueWithUnit(String.valueOf(psi[idx]), Style.TEXT, "PSI",
                allFour ? Style.UNIT_SCALE_HERO : Style.UNIT_SCALE_ROW));

            int btnSz = allFour ? 50 : 40;
            TextView minus = roundStepButton("−", btnSz, () -> {
                int next = Math.max(MIN_PSI, psi[idx] - 1);
                if (allFour) { for (int i = 0; i < 4; i++) psi[i] = next; }
                else psi[idx] = next;
                val.setText(Style.valueWithUnit(String.valueOf(psi[idx]), Style.TEXT, "PSI",
                    allFour ? Style.UNIT_SCALE_HERO : Style.UNIT_SCALE_ROW));
            });
            TextView plus = roundStepButton("+", btnSz, () -> {
                int next = Math.min(MAX_PSI, psi[idx] + 1);
                if (allFour) { for (int i = 0; i < 4; i++) psi[i] = next; }
                else psi[idx] = next;
                val.setText(Style.valueWithUnit(String.valueOf(psi[idx]), Style.TEXT, "PSI",
                    allFour ? Style.UNIT_SCALE_HERO : Style.UNIT_SCALE_ROW));
            });

            box.addView(minus);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                Style.dp(c, allFour ? 160 : 96), ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = Style.dp(c, allFour ? 20 : 8);
            vlp.rightMargin = Style.dp(c, allFour ? 20 : 8);
            box.addView(val, vlp);
            box.addView(plus);
            return box;
        }

        private TextView roundStepButton(String glyph, int sizeDp, Runnable onTap) {
            TextView b = new TextView(c);
            b.setText(glyph);
            b.setTextColor(Style.TEXT);
            b.setTextSize(sizeDp >= 48 ? 24f : 20f);
            b.setTypeface(null, Typeface.BOLD);
            b.setGravity(Gravity.CENTER);
            b.setBackground(roundedRect(Style.CARD_HI, strongBorderColor(), 1, 99));
            int sz = Style.dp(c, sizeDp);
            b.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            b.setOnClickListener(v -> onTap.run());
            return b;
        }

        private void buildNoiseTags() {
            adaptiveBox.addView(subtleLabel(c.getString(R.string.diary_noise_where)));
            List<View> where = new ArrayList<>();
            for (int res : NOISE_WHERE_RES) {
                String tag = c.getString(res);
                where.add(tagPill(tag, selectedTags.contains(tag), () -> {
                    if (!selectedTags.remove(tag)) selectedTags.add(tag);
                    rebuildAdaptive();
                }));
            }
            LinearLayout wWrap = new LinearLayout(c);
            wWrap.setOrientation(LinearLayout.VERTICAL);
            addNColumnGrid(wWrap, where, 4, 8);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp1.topMargin = Style.dp(c, 8);
            adaptiveBox.addView(wWrap, lp1);

            TextView l2 = subtleLabel(c.getString(R.string.diary_noise_when));
            LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l2p.topMargin = Style.dp(c, 14);
            adaptiveBox.addView(l2, l2p);

            List<View> when = new ArrayList<>();
            for (int res : NOISE_WHEN_RES) {
                String tag = c.getString(res);
                when.add(tagPill(tag, selectedTags.contains(tag), () -> {
                    if (!selectedTags.remove(tag)) selectedTags.add(tag);
                    rebuildAdaptive();
                }));
            }
            LinearLayout whWrap = new LinearLayout(c);
            whWrap.setOrientation(LinearLayout.VERTICAL);
            addNColumnGrid(whWrap, when, 3, 8);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = Style.dp(c, 8);
            adaptiveBox.addView(whWrap, lp2);
        }

        private TextView tagPill(String label, boolean active, Runnable onTap) {
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextColor(Style.TEXT);
            tv.setTextSize(16f);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setGravity(Gravity.CENTER);
            tv.setMinimumHeight(Style.dp(c, 48));
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(Style.dp(c, 10), Style.dp(c, 8), Style.dp(c, 10), Style.dp(c, 8));
            tv.setBackground(active
                ? roundedRect(accentTint(), Style.ACCENT, 2, 99)
                : roundedRect(wellFill(), borderColor(), 1, 99));
            tv.setOnClickListener(v -> onTap.run());
            return tv;
        }

        private void buildIncidentControls() {
            if (jolt != null) {
                LinearLayout jRow = new LinearLayout(c);
                jRow.setOrientation(LinearLayout.HORIZONTAL);
                jRow.setGravity(Gravity.CENTER_VERTICAL);
                jRow.setPadding(Style.dp(c, 2), 0, 0, Style.dp(c, 12));

                String timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(jolt.tsMs));
                SpannableStringBuilder sb = new SpannableStringBuilder();
                sb.append("▲  ");
                sb.setSpan(new ForegroundColorSpan(Style.WARN), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(timeStr).append(" · ");
                sb.append(Style.valueWithUnit(
                    String.format(Locale.US, "%.1f", jolt.maxG()), Style.TEXT, "g", Style.UNIT_SCALE_ROW));
                if (jolt.speedKmh != null) {
                    sb.append(" · ");
                    sb.append(Style.valueWithUnit(
                        String.format(Locale.US, "%.0f", jolt.speedKmh), Style.TEXT, "km/h", Style.UNIT_SCALE_ROW));
                }

                TextView jb = new TextView(c);
                jb.setText(sb);
                jb.setTextColor(Style.TEXT_DIM);
                jb.setTextSize(17f);
                jRow.addView(jb);
                adaptiveBox.addView(jRow);
            }

            // Full-width recessed well toggle: Save 5s dashcam clip
            LinearLayout clipWell = new LinearLayout(c);
            clipWell.setOrientation(LinearLayout.HORIZONTAL);
            clipWell.setGravity(Gravity.CENTER_VERTICAL);
            clipWell.setBackground(roundedRect(wellFill(), borderColor(), 1, 18));
            clipWell.setPadding(Style.dp(c, 18), Style.dp(c, 10), Style.dp(c, 14), Style.dp(c, 10));
            clipWell.setMinimumHeight(Style.dp(c, 58));

            TextView lbl = new TextView(c);
            lbl.setText(c.getString(R.string.diary_incident_keep_clip));
            lbl.setTextColor(Style.TEXT);
            lbl.setTextSize(17.5f);
            clipWell.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            GeelySwitch sw = new GeelySwitch(c);
            sw.setLockSeconds(0);
            sw.setCheckedSilently(saveClip);
            sw.setOnToggle(on -> saveClip = on);
            clipWell.addView(sw);
            adaptiveBox.addView(clipWell);
        }

        private void buildServiceBundles() {
            adaptiveBox.addView(subtleLabel(c.getString(R.string.diary_service_done_items)));
            List<View> items = new ArrayList<>();
            for (CarDiary.Category item : CarDiary.SERVICE_BUNDLABLE) {
                boolean checked = bundled.contains(item);
                items.add(checkTile(c.getString(item.labelRes), checked, () -> {
                    if (!bundled.remove(item)) bundled.add(item);
                    rebuildAdaptive();
                }));
            }
            LinearLayout wrap = new LinearLayout(c);
            wrap.setOrientation(LinearLayout.VERTICAL);
            addNColumnGrid(wrap, items, 2, 10);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Style.dp(c, 10);
            adaptiveBox.addView(wrap, lp);
        }

        private LinearLayout checkTile(String label, boolean checked, Runnable onTap) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Style.dp(c, 16), Style.dp(c, 10), Style.dp(c, 14), Style.dp(c, 10));
            row.setMinimumHeight(Style.dp(c, 52));
            row.setBackground(checked
                ? roundedRect(accentTint(), Style.ACCENT, 2, 16)
                : roundedRect(wellFill(), borderColor(), 1, 16));

            TextView box = new TextView(c);
            box.setText(checked ? "✓" : "");
            box.setTextColor(Style.onFill(Style.ACCENT));
            box.setTextSize(13f);
            box.setTypeface(null, Typeface.BOLD);
            box.setGravity(Gravity.CENTER);
            box.setBackground(checked
                ? roundedRect(Style.ACCENT, Style.ACCENT, 1, 6)
                : roundedRect(Color.TRANSPARENT, withAlpha(Style.TEXT_DIM, 0.7f), 2, 6));
            int bsz = Style.dp(c, 22);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(bsz, bsz);
            blp.rightMargin = Style.dp(c, 12);
            row.addView(box, blp);

            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextColor(Style.TEXT);
            tv.setTextSize(16.5f);
            tv.setTypeface(null, checked ? Typeface.BOLD : Typeface.NORMAL);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(v -> onTap.run());
            return row;
        }

        // ---- Save ----

        private void save() {
            final Double odo = resolvedOdoKm;
            long cents = ChargeCostDialog.centsFromText(costInput.getText().toString());
            final Double cost = cents > 0 ? (cents / 100.0) : null;
            String rawNote = noteInput.getText().toString().trim();
            final String note = rawNote.isEmpty() ? null : rawNote;

            final boolean isToday = selectedDate.equals(LocalDate.now());
            final long tsMs;
            if (jolt != null && selectedCat == CarDiary.Category.INCIDENT
                    && selectedDate.equals(Instant.ofEpochMilli(jolt.tsMs)
                        .atZone(ZoneId.systemDefault()).toLocalDate())) {
                tsMs = jolt.tsMs;
            } else if (isToday) {
                tsMs = System.currentTimeMillis();
            } else {
                tsMs = selectedDate.atTime(12, 0).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            }

            final Double outC = isToday ? CarDiary.latestOutsideTempC() : null;
            final CarDiary.Category cat = selectedCat;
            final String tags = (cat == CarDiary.Category.NOISE && !selectedTags.isEmpty())
                ? String.join(", ", selectedTags) : null;
            final Context app = c.getApplicationContext();

            if (cat == CarDiary.Category.TIRE_PRESSURE) {
                // Infer cold/warm automatically from CarDb trip & park history when logging today;
                // omit (null) for past dates or when no data exists.
                final int[] snap = {psi[0], psi[1], psi[2], psi[3]};
                Runnable commit = () -> {
                    Boolean cold = isToday ? CarDiary.inferColdTires(app, tsMs) : null;
                    String meta = CarDiary.buildTirePressureMeta(
                        snap[0], snap[1], snap[2], snap[3], cold);
                    CarDiary.insert(app, cat, CarDiary.STATUS_DONE, tsMs, odo, outC, cost,
                        null, note, null, null, null, meta, onSaved);
                };
                CarActor actor = CarActor.get(app);
                if (actor != null) actor.runOnCarThread(commit);
                else new Thread(commit, "diary-psi-save").start();
            } else if (cat == CarDiary.Category.INCIDENT) {
                Double lat = jolt != null ? jolt.lat : null;
                Double lon = jolt != null ? jolt.lon : null;
                String meta = jolt != null ? CarDiary.buildJoltMeta(jolt.maxG(), jolt.speedKmh) : null;
                CarDiary.insertIncidentWithOptionalClip(
                    app, tsMs, saveClip, true, odo, outC, lat, lon, cost,
                    null, note, tags, meta, jolt != null ? jolt.id : null, onSaved);
            } else {
                String meta = (cat == CarDiary.Category.SERVICE && !bundled.isEmpty())
                    ? CarDiary.buildServiceBundledMeta(new ArrayList<>(bundled)) : null;
                String status = cat == CarDiary.Category.NOISE
                    ? CarDiary.STATUS_OPEN : CarDiary.STATUS_DONE;
                CarDiary.insert(app, cat, status, tsMs, odo, outC, cost,
                    null, note, tags, null, null, meta, onSaved);
            }
            if (dialog != null) dialog.dismiss();
        }

        // ---- Small Visual Helpers ----

        private void addNColumnGrid(LinearLayout host, List<View> views, int cols, int gapDp) {
            int gap = Style.dp(c, gapDp);
            for (int i = 0; i < views.size(); i += cols) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int j = 0; j < cols; j++) {
                    if (j > 0) row.addView(new View(c), new LinearLayout.LayoutParams(gap, 1));
                    int idx = i + j;
                    if (idx < views.size()) {
                        row.addView(views.get(idx), new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    } else {
                        row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
                    }
                }
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = gap;
                host.addView(row, rlp);
            }
        }

        private ImageView iconView(int resId, int sizeDp, int tintColor) {
            ImageView iv = new ImageView(c);
            iv.setImageResource(resId);
            iv.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
            int sz = Style.dp(c, sizeDp);
            iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            return iv;
        }

        private TextView subtleLabel(String text) {
            TextView tv = new TextView(c);
            tv.setText(text);
            tv.setTextColor(Style.TEXT_DIM);
            tv.setTextSize(15.5f);
            tv.setTypeface(null, Typeface.BOLD);
            return tv;
        }

        private EditText bareInput(int inputType, String hint, float sizeSp, boolean bold) {
            EditText e = new EditText(c);
            e.setInputType(inputType);
            e.setHint(hint);
            e.setTextColor(Style.TEXT);
            e.setHintTextColor(withAlpha(Style.TEXT_DIM, 0.72f));
            e.setTextSize(sizeSp);
            if (bold) e.setTypeface(null, Typeface.BOLD);
            e.setBackground(null);
            e.setPadding(0, 0, 0, 0);
            e.setSingleLine(true);
            return e;
        }

        private GradientDrawable roundedRect(int fill, int stroke, int strokeDp, int radiusDp) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(Style.dp(c, radiusDp));
            g.setColor(fill);
            if (stroke != 0 && strokeDp > 0) {
                g.setStroke(Style.dp(c, strokeDp), stroke);
            }
            return g;
        }

        private int wellFill() {
            return Style.LIGHT ? 0xFFF2F5F9 : darken(Style.CARD, 0.78f);
        }

        private int borderColor() {
            return Style.LIGHT ? 0x16000000 : 0x14FFFFFF;
        }

        private int strongBorderColor() {
            return Style.LIGHT ? 0x26000000 : 0x29FFFFFF;
        }

        private int accentTint() {
            return withAlpha(Style.ACCENT, Style.LIGHT ? 0.14f : 0.22f);
        }

        private static int darken(int color, float factor) {
            int r = Math.max(0, Math.round(Color.red(color) * factor));
            int g = Math.max(0, Math.round(Color.green(color) * factor));
            int b = Math.max(0, Math.round(Color.blue(color) * factor));
            return Color.argb(Color.alpha(color), r, g, b);
        }

        private static int withAlpha(int color, float alpha) {
            int a = Math.max(0, Math.min(255, Math.round(255f * alpha)));
            return (color & 0x00FFFFFF) | (a << 24);
        }

        private static void installCentsFormatter(EditText input) {
            input.setFilters(new InputFilter[0]);
            input.addTextChangedListener(new TextWatcher() {
                private boolean busy;
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
                @Override public void afterTextChanged(Editable s) {
                    if (busy) return;
                    long cents = ChargeCostDialog.centsFromText(s.toString());
                    busy = true;
                    String formatted = cents > 0 ? ChargeCostDialog.formatCents(cents) : "";
                    input.setText(formatted);
                    input.setSelection(formatted.length());
                    busy = false;
                }
            });
        }
    }
}
