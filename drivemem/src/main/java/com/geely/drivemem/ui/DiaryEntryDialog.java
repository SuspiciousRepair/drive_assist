package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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
 * Fixed-height modal with independently scrollable panes so content never clips
 * or jumps across categories, locales, or when the soft keyboard opens:
 *
 * Left column toggles between:
 *  - Collapsed item list (`<- [icon] Class` + plain rows, accent outline on active)
 *  - Expanded 5-class picker (`x Add entry` + Config-sidebar pills with chevrons)
 *
 * Right column is the live Details card:
 *  - Pinned header: Item title + read-only auto-odometer (`Today · 14,820 km`)
 *    and `Today | Yesterday | Pick date` (opens a clean dark/light Month Calendar)
 *  - Scrollable middle: adaptive per-item control (Cold/Warm inferred from CarDb)
 *    + compact `Cost` & `Note` pair
 *  - Pinned footer: full-width primary `Save` pill.
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
        return new Builder(c, null, initialCat, jolt, dueList, onSaved).show();
    }

    public static AlertDialog showEdit(Context c, CarDiary.Entry existing,
                                       List<CarDiary.DueStatus> dueList,
                                       Runnable onSaved) {
        if (existing == null) return null;
        return new Builder(c, existing, existing.category, null, dueList, onSaved).show();
    }

    private static final class Builder {
        private final Context c;
        private final CarDiary.Entry editing;
        private final CarDiary.PendingJolt jolt;
        private final Runnable onSaved;
        private final DateTimeFormatter shortDateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());
        private final DateTimeFormatter shortYearDateFmt = DateTimeFormatter.ofPattern("d MMM yy", Locale.getDefault());
        private final DateTimeFormatter monthYearFmt = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault());
        private final Set<CarDiary.Category> dueCats = EnumSet.noneOf(CarDiary.Category.class);
        private final Set<CarDiary.Group> dueGroups = EnumSet.noneOf(CarDiary.Group.class);
        private final Map<CarDiary.Group, CarDiary.Category> pickedByGroup = new EnumMap<>(CarDiary.Group.class);

        private boolean showingClasses = false;
        private CarDiary.Group selectedGroup;
        private CarDiary.Category selectedCat;
        private LocalDate selectedDate;
        private LocalDate originalDate;
        private Boolean originalCold = null;

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

        // Coolant mode (default Top-up so topping off doesn't reset the 4-year / 80k flush clock)
        private boolean coolantTopUp = true;

        // UI references
        private LinearLayout leftHeaderHost;
        private LinearLayout leftListHost;
        private TextView paneTitle;
        private TextView paneCtx;
        private LinearLayout dateSeg;
        private ScrollView paneBodyScroll;
        private LinearLayout adaptiveBox;
        private EditText costInput;
        private EditText noteInput;
        private AlertDialog dialog;

        Builder(Context c, CarDiary.Entry editing, CarDiary.Category initialCat,
                CarDiary.PendingJolt jolt, List<CarDiary.DueStatus> dueList, Runnable onSaved) {
            this.c = c;
            this.editing = editing;
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

            if (editing != null) {
                CarDiary.Category cat = editing.category != null && editing.category.userPickable
                    ? editing.category : CarDiary.Category.OTHER;
                this.selectedCat = cat;
                this.selectedGroup = cat.group;
                this.selectedDate = Instant.ofEpochMilli(editing.tsMs)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
                this.originalDate = this.selectedDate;
                this.showingClasses = false;
                if (editing.odoKm != null && editing.odoKm > 0) {
                    this.resolvedOdoKm = editing.odoKm;
                    this.resolvedOdoExact = true;
                }
                int[] parsedPsi = CarDiary.parsePsi(editing.metaJson);
                if (parsedPsi != null) {
                    System.arraycopy(parsedPsi, 0, psi, 0, 4);
                    perWheel = !(psi[0] == psi[1] && psi[1] == psi[2] && psi[2] == psi[3]);
                }
                originalCold = CarDiary.parseCold(editing.metaJson);
                Boolean topUp = CarDiary.parseTopUp(editing.metaJson);
                if (topUp != null) coolantTopUp = topUp;
                bundled.addAll(CarDiary.parseBundled(editing.metaJson));
                if (editing.tags != null && !editing.tags.trim().isEmpty()) {
                    for (String tok : editing.tags.split(",")) {
                        String t = tok.trim();
                        if (!t.isEmpty()) selectedTags.add(t);
                    }
                }
            } else if (jolt != null) {
                this.selectedCat = CarDiary.Category.INCIDENT;
                this.selectedGroup = CarDiary.Group.NOTES;
                this.selectedDate = Instant.ofEpochMilli(jolt.tsMs)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
                this.showingClasses = false;
            } else if (initialCat != null && initialCat.userPickable) {
                this.selectedCat = initialCat;
                this.selectedGroup = initialCat.group;
                this.selectedDate = LocalDate.now();
                this.showingClasses = false;
            } else {
                // Start on the 5-class list when tapping "+ Add entry" so the driver
                // picks a class first (which then collapses into that class's items).
                CarDiary.DueStatus urgent = CarDiary.mostUrgent(dueList);
                if (urgent != null && (urgent.overdue || urgent.dueSoon) && urgent.category.userPickable) {
                    this.selectedCat = urgent.category;
                    this.selectedGroup = urgent.category.group;
                } else {
                    this.selectedGroup = CarDiary.Group.TIRES;
                    this.selectedCat = CarDiary.Category.TIRE_PRESSURE;
                }
                this.selectedDate = LocalDate.now();
                this.showingClasses = true;
            }
            pickedByGroup.put(selectedGroup, selectedCat);
        }

        AlertDialog show() {
            LinearLayout sheet = new LinearLayout(c);
            sheet.setOrientation(LinearLayout.HORIZONTAL);
            sheet.setBackground(roundedRect(Style.CARD, borderColor(), 1, 28));
            int pad = Style.dp(c, 22);
            sheet.setPadding(pad, pad, pad, pad);

            // ---- LEFT COLUMN (33%): pinned header + scrollable list ----
            LinearLayout leftCol = new LinearLayout(c);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            leftCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 33f));

            leftHeaderHost = new LinearLayout(c);
            leftHeaderHost.setOrientation(LinearLayout.VERTICAL);
            leftCol.addView(leftHeaderHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ScrollView leftScroll = new ScrollView(c);
            leftScroll.setVerticalScrollBarEnabled(false);
            leftListHost = new LinearLayout(c);
            leftListHost.setOrientation(LinearLayout.VERTICAL);
            leftScroll.addView(leftListHost, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            leftCol.addView(leftScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // ---- RIGHT COLUMN (67%): pinned header + scrollable body + pinned Save ----
            LinearLayout pane = new LinearLayout(c);
            pane.setOrientation(LinearLayout.VERTICAL);
            pane.setBackground(roundedRect(Style.CARD_HI, borderColor(), 1, 22));
            int pp = Style.dp(c, 24);
            pane.setPadding(pp, pp, pp, pp);
            pane.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 67f));

            // Pinned top: Item title + quiet read-only Date · Odometer
            LinearLayout pHead = new LinearLayout(c);
            pHead.setOrientation(LinearLayout.HORIZONTAL);
            pHead.setGravity(Gravity.CENTER_VERTICAL);

            paneTitle = new TextView(c);
            paneTitle.setTextColor(Style.TEXT);
            paneTitle.setTextSize(26f);
            paneTitle.setTypeface(null, Typeface.BOLD);
            paneTitle.setSingleLine(true);
            paneTitle.setEllipsize(TextUtils.TruncateAt.END);
            pHead.addView(paneTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            paneCtx = new TextView(c);
            paneCtx.setTextColor(Style.TEXT_DIM);
            paneCtx.setTextSize(17.5f);
            paneCtx.setSingleLine(true);
            paneCtx.setPadding(Style.dp(c, 16), 0, 0, 0);
            pHead.addView(paneCtx);
            pane.addView(pHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Pinned date segmented pill: Today | Yesterday | Pick date
            dateSeg = new LinearLayout(c);
            dateSeg.setOrientation(LinearLayout.HORIZONTAL);
            dateSeg.setBackground(roundedRect(wellFill(), borderColor(), 1, 99));
            int sp = Style.dp(c, 4);
            dateSeg.setPadding(sp, sp, sp, sp);
            LinearLayout.LayoutParams dslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dslp.topMargin = Style.dp(c, 16);
            pane.addView(dateSeg, dslp);

            // Scrollable middle body: Adaptive controls + Cost/Note pair (never clips!)
            paneBodyScroll = new ScrollView(c);
            paneBodyScroll.setVerticalScrollBarEnabled(false);
            paneBodyScroll.setFillViewport(false);
            LinearLayout paneBody = new LinearLayout(c);
            paneBody.setOrientation(LinearLayout.VERTICAL);
            paneBody.setPadding(0, Style.dp(c, 18), 0, Style.dp(c, 12));

            adaptiveBox = new LinearLayout(c);
            adaptiveBox.setOrientation(LinearLayout.VERTICAL);
            paneBody.addView(adaptiveBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
            if (editing != null && editing.cost != null && editing.cost > 0) {
                long cents = Math.round(editing.cost * 100.0);
                costInput.setText(ChargeCostDialog.formatCents(cents));
            }
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
            if (editing != null && editing.note != null) {
                noteInput.setText(editing.note);
            } else if (jolt != null) {
                String pre = jolt.speedKmh != null
                    ? c.getString(R.string.diary_jolt_prefill_note, jolt.maxG(), jolt.speedKmh)
                    : c.getString(R.string.diary_jolt_prefill_note_nospeed, jolt.maxG());
                noteInput.setText(pre);
            }
            noteWell.addView(noteInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pair.addView(noteWell, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 65f));
            paneBody.addView(pair, pairLp);

            paneBodyScroll.addView(paneBody, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            pane.addView(paneBodyScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // Pinned full-width primary Save pill
            TextView saveBtn = new TextView(c);
            saveBtn.setText(c.getString(R.string.diary_btn_save));
            saveBtn.setTextColor(Style.onFill(Style.ACCENT));
            saveBtn.setTextSize(20f);
            saveBtn.setTypeface(null, Typeface.BOLD);
            saveBtn.setGravity(Gravity.CENTER);
            saveBtn.setBackground(roundedRect(Style.ACCENT, 0, 0, 99));
            saveBtn.setOnClickListener(v -> save());
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 58));
            saveLp.topMargin = Style.dp(c, 10);
            pane.addView(saveBtn, saveLp);

            // Mirror left/right columns in RHD so the category picker stays on the driver's edge
            Style.addMirrored(sheet, 22, leftCol, pane);

            rebuildLeft();
            rebuildPaneHeader();
            rebuildDateSeg();
            rebuildAdaptive();
            refreshOdometerForSelectedDate(true);

            dialog = new AlertDialog.Builder(c).create();
            dialog.setView(sheet, 0, 0, 0, 0);
            Window win = dialog.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            dialog.show();
            if (win != null) {
                DisplayMetrics dm = c.getResources().getDisplayMetrics();
                int w = Math.min(Style.dp(c, 1380), (int) (dm.widthPixels * 0.88f));
                int h = Math.min(Style.dp(c, 680), (int) (dm.heightPixels * 0.78f));
                win.setLayout(w, h);
            }
            return dialog;
        }

        // ---- Left Column: Collapsed Items <-> Expanded 5 Classes ----

        private void rebuildLeft() {
            leftHeaderHost.removeAllViews();
            leftListHost.removeAllViews();
            if (showingClasses) {
                buildClassesList();
            } else {
                buildItemsList();
            }
        }

        private void buildItemsList() {
            // Pinned header: [<- circle] [accent icon] ClassName (tap anywhere to expand 5 classes)
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
            clsTitle.setSingleLine(true);
            clsTitle.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlLp.leftMargin = Style.dp(c, 12);
            head.addView(clsTitle, tlLp);
            leftHeaderHost.addView(head);
            leftHeaderHost.addView(leftDivider());

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
                lbl.setSingleLine(true);
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
                    paneBodyScroll.scrollTo(0, 0);
                });

                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Style.dp(c, 8);
                leftListHost.addView(row, rlp);
            }
        }

        private void buildClassesList() {
            // Pinned header: [x circle] Add entry (tap closes the dialog)
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(Style.dp(c, 4), Style.dp(c, 4), Style.dp(c, 8), Style.dp(c, 4));
            head.setMinimumHeight(Style.dp(c, 52));
            head.setOnClickListener(v -> { if (dialog != null) dialog.dismiss(); });

            head.addView(circleIconButton(R.drawable.ic_close));

            TextView title = new TextView(c);
            title.setText(c.getString(editing != null ? R.string.diary_edit_title : R.string.diary_add_title));
            title.setTextColor(Style.TEXT);
            title.setTextSize(20f);
            title.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlLp.leftMargin = Style.dp(c, 14);
            head.addView(title, tlLp);
            leftHeaderHost.addView(head);
            leftHeaderHost.addView(leftDivider());

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
                lbl.setSingleLine(true);
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
                    paneBodyScroll.scrollTo(0, 0);
                });

                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Style.dp(c, 8);
                leftListHost.addView(row, rlp);
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

        // ---- Right Column: Header + Auto-Odometer + Date Segmented & Month Calendar ----

        private String formatShortDate(LocalDate d) {
            LocalDate today = LocalDate.now();
            return (d.getYear() == today.getYear())
                ? shortDateFmt.format(d)
                : shortYearDateFmt.format(d);
        }

        private void rebuildPaneHeader() {
            paneTitle.setText(c.getString(selectedCat.labelRes));
            LocalDate today = LocalDate.now();
            String dateLabel = selectedDate.equals(today)
                ? c.getString(R.string.diary_date_today)
                : (selectedDate.equals(today.minusDays(1))
                    ? c.getString(R.string.diary_date_yesterday)
                    : formatShortDate(selectedDate));

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
                applyDate(today);
            }));
            dateSeg.addView(segBtn(c.getString(R.string.diary_date_yesterday), isYesterday, () -> {
                applyDate(yesterday);
            }));
            String pickLbl = isCustom
                ? formatShortDate(selectedDate)
                : c.getString(R.string.diary_date_pick);
            dateSeg.addView(segBtn(pickLbl, isCustom, this::openCalendarPicker));
        }

        private void applyDate(LocalDate d) {
            selectedDate = d;
            rebuildDateSeg();
            rebuildPaneHeader();
            refreshOdometerForSelectedDate(false);
        }

        private TextView segBtn(String label, boolean active, Runnable onTap) {
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextSize(17f);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setTextColor(active ? Style.TEXT : Style.TEXT_DIM);
            tv.setGravity(Gravity.CENTER);
            tv.setMinimumHeight(Style.dp(c, 48));
            tv.setSingleLine(true);
            if (active) {
                tv.setBackground(roundedRect(Style.CARD_HI, strongBorderColor(), 1, 99));
            }
            tv.setOnClickListener(v -> onTap.run());
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return tv;
        }

        /** Clean Drive Assist dark/light Month Calendar overlay — large touch cells,
         * clear Month + Year header, instant month stepping, future days disabled. */
        private void openCalendarPicker() {
            final LocalDate today = LocalDate.now();
            final YearMonth currentYm = YearMonth.from(today);
            final YearMonth[] shown = {YearMonth.from(selectedDate)};

            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(roundedRect(Style.CARD, strongBorderColor(), 1, 24));
            int p = Style.dp(c, 22);
            card.setPadding(p, p, p, p);

            // Header: [<]  Month YYYY  [>]
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView prevBtn = calNavBtn("‹");
            TextView monthTitle = new TextView(c);
            monthTitle.setTextColor(Style.TEXT);
            monthTitle.setTextSize(22f);
            monthTitle.setTypeface(null, Typeface.BOLD);
            monthTitle.setGravity(Gravity.CENTER);
            TextView nextBtn = calNavBtn("›");

            head.addView(prevBtn);
            head.addView(monthTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(nextBtn);
            card.addView(head);

            // Weekday row (Sun .. Sat)
            LinearLayout wdRow = new LinearLayout(c);
            wdRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = Style.dp(c, 16);
            wlp.bottomMargin = Style.dp(c, 6);
            Locale loc = Locale.getDefault();
            DayOfWeek[] order = {
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
            };
            for (DayOfWeek dow : order) {
                TextView w = new TextView(c);
                w.setText(dow.getDisplayName(TextStyle.SHORT, loc));
                w.setTextColor(Style.TEXT_DIM);
                w.setTextSize(14f);
                w.setTypeface(null, Typeface.BOLD);
                w.setGravity(Gravity.CENTER);
                wdRow.addView(w, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            card.addView(wdRow, wlp);

            // 6x7 day grid container
            LinearLayout gridHost = new LinearLayout(c);
            gridHost.setOrientation(LinearLayout.VERTICAL);
            card.addView(gridHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Footer: Today (left) + Cancel (right)
            LinearLayout foot = new LinearLayout(c);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            foot.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.topMargin = Style.dp(c, 14);

            AlertDialog calDlg = new AlertDialog.Builder(c).create();
            calDlg.setView(card, 0, 0, 0, 0);

            TextView todayBtn = Style.cardButton(c, c.getString(R.string.diary_date_today), false, () -> {
                applyDate(today);
                calDlg.dismiss();
            });
            foot.addView(todayBtn);
            foot.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            TextView closeBtn = Style.cardButton(c, c.getString(android.R.string.cancel), false, calDlg::dismiss);
            foot.addView(closeBtn);
            card.addView(foot, flp);

            Runnable renderMonth = () -> {
                YearMonth ym = shown[0];
                String raw = monthYearFmt.format(ym.atDay(1));
                String cap = raw.isEmpty() ? raw
                    : raw.substring(0, 1).toUpperCase(loc) + raw.substring(1);
                monthTitle.setText(cap);

                boolean canNext = ym.isBefore(currentYm);
                nextBtn.setEnabled(canNext);
                nextBtn.setAlpha(canNext ? 1f : 0.28f);

                gridHost.removeAllViews();
                LocalDate first = ym.atDay(1);
                int leadBlanks = first.getDayOfWeek().getValue() % 7; // Sunday=0..Saturday=6
                int daysInMonth = ym.lengthOfMonth();
                int rows = (leadBlanks + daysInMonth + 6) / 7;

                for (int r = 0; r < rows; r++) {
                    LinearLayout row = new LinearLayout(c);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    for (int col = 0; col < 7; col++) {
                        int dayNum = r * 7 + col - leadBlanks + 1;
                        TextView cell = new TextView(c);
                        cell.setGravity(Gravity.CENTER);
                        cell.setTextSize(18f);
                        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            0, Style.dp(c, 54), 1f);
                        clp.setMargins(Style.dp(c, 3), Style.dp(c, 3), Style.dp(c, 3), Style.dp(c, 3));

                        if (dayNum < 1 || dayNum > daysInMonth) {
                            cell.setText("");
                        } else {
                            LocalDate d = ym.atDay(dayNum);
                            boolean future = d.isAfter(today);
                            boolean isSel = d.equals(selectedDate);
                            boolean isTod = d.equals(today);
                            cell.setText(String.valueOf(dayNum));
                            if (future) {
                                cell.setTextColor(withAlpha(Style.TEXT_DIM, 0.30f));
                            } else if (isSel) {
                                cell.setTextColor(Style.onFill(Style.ACCENT));
                                cell.setTypeface(null, Typeface.BOLD);
                                cell.setBackground(roundedRect(Style.ACCENT, 0, 0, 14));
                            } else if (isTod) {
                                cell.setTextColor(Style.TEXT);
                                cell.setTypeface(null, Typeface.BOLD);
                                cell.setBackground(roundedRect(Color.TRANSPARENT, Style.ACCENT, 2, 14));
                            } else {
                                cell.setTextColor(Style.TEXT);
                                cell.setBackground(roundedRect(Style.CARD_HI, 0, 0, 14));
                            }
                            if (!future) {
                                cell.setOnClickListener(v -> {
                                    applyDate(d);
                                    calDlg.dismiss();
                                });
                            }
                        }
                        row.addView(cell, clp);
                    }
                    gridHost.addView(row);
                }
            };

            prevBtn.setOnClickListener(v -> {
                shown[0] = shown[0].minusMonths(1);
                renderMonth.run();
            });
            nextBtn.setOnClickListener(v -> {
                if (shown[0].isBefore(currentYm)) {
                    shown[0] = shown[0].plusMonths(1);
                    renderMonth.run();
                }
            });
            renderMonth.run();

            Window win = calDlg.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            calDlg.show();
            if (win != null) {
                win.setLayout(Style.dp(c, 540), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }

        private TextView calNavBtn(String glyph) {
            TextView b = new TextView(c);
            b.setText(glyph);
            b.setTextColor(Style.TEXT);
            b.setTextSize(26f);
            b.setGravity(Gravity.CENTER);
            b.setBackground(roundedRect(Style.CARD_HI, strongBorderColor(), 1, 99));
            int sz = Style.dp(c, 48);
            b.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            return b;
        }

        private void refreshOdometerForSelectedDate(boolean initial) {
            if (initial && editing != null && editing.odoKm != null && editing.odoKm > 0) {
                resolvedOdoKm = editing.odoKm;
                resolvedOdoExact = true;
                rebuildPaneHeader();
                return;
            }
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
            } else if (selectedCat == CarDiary.Category.INCIDENT && jolt != null) {
                adaptiveBox.setVisibility(View.VISIBLE);
                buildIncidentControls();
            } else if (selectedCat == CarDiary.Category.SERVICE) {
                adaptiveBox.setVisibility(View.VISIBLE);
                buildServiceBundles();
            } else if (selectedCat == CarDiary.Category.COOLANT) {
                adaptiveBox.setVisibility(View.VISIBLE);
                buildCoolantControls();
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
            box.setMinimumHeight(Style.dp(c, allFour ? 84 : 64));
            int px = Style.dp(c, allFour ? 20 : 14);
            int py = Style.dp(c, allFour ? 12 : 8);
            box.setPadding(px, py, px, py);

            if (wheelLabel != null) {
                TextView wl = new TextView(c);
                wl.setText(wheelLabel);
                wl.setTextColor(Style.TEXT_DIM);
                wl.setTextSize(15f);
                wl.setTypeface(null, Typeface.BOLD);
                wl.setMinWidth(Style.dp(c, 32));
                box.addView(wl);
                box.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            }

            TextView val = new TextView(c);
            val.setTextSize(allFour ? 36f : 22f);
            val.setTypeface(null, Typeface.BOLD);
            val.setTextColor(Style.TEXT);
            val.setGravity(Gravity.CENTER);
            val.setSingleLine(true);
            val.setText(Style.valueWithUnit(String.valueOf(psi[idx]), Style.TEXT, "PSI",
                allFour ? Style.UNIT_SCALE_HERO : Style.UNIT_SCALE_ROW));

            int btnSz = allFour ? 52 : 44;
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
                Style.dp(c, allFour ? 170 : 108), ViewGroup.LayoutParams.WRAP_CONTENT);
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
            // 2x2 grid so longer localized labels (e.g. "Dianteira esq.") never clip
            addNColumnGrid(wWrap, where, 2, 10);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp1.topMargin = Style.dp(c, 8);
            adaptiveBox.addView(wWrap, lp1);

            TextView l2 = subtleLabel(c.getString(R.string.diary_noise_when));
            LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l2p.topMargin = Style.dp(c, 16);
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
            addNColumnGrid(whWrap, when, 3, 10);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = Style.dp(c, 8);
            adaptiveBox.addView(whWrap, lp2);
        }

        private TextView tagPill(String label, boolean active, Runnable onTap) {
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextColor(Style.TEXT);
            tv.setTextSize(17f);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setGravity(Gravity.CENTER);
            tv.setMinimumHeight(Style.dp(c, 50));
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(Style.dp(c, 14), Style.dp(c, 10), Style.dp(c, 14), Style.dp(c, 10));
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

        private void buildCoolantControls() {
            LinearLayout seg = new LinearLayout(c);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setBackground(roundedRect(wellFill(), borderColor(), 1, 99));
            int sp = Style.dp(c, 4);
            seg.setPadding(sp, sp, sp, sp);
            seg.addView(segBtn(c.getString(R.string.diary_coolant_topup), coolantTopUp, () -> {
                coolantTopUp = true;
                rebuildAdaptive();
            }));
            seg.addView(segBtn(c.getString(R.string.diary_coolant_change), !coolantTopUp, () -> {
                coolantTopUp = false;
                rebuildAdaptive();
            }));
            adaptiveBox.addView(seg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
            row.setMinimumHeight(Style.dp(c, 54));
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
            tv.setTextSize(17f);
            tv.setTypeface(null, checked ? Typeface.BOLD : Typeface.NORMAL);
            tv.setSingleLine(true);
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
            if (editing != null && selectedDate.equals(originalDate)) {
                tsMs = editing.tsMs;
            } else if (jolt != null && selectedCat == CarDiary.Category.INCIDENT
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

            if (editing != null) {
                String status = editing.status;
                if (editing.category != cat) {
                    status = (cat == CarDiary.Category.NOISE || cat == CarDiary.Category.INCIDENT)
                        ? CarDiary.STATUS_OPEN : CarDiary.STATUS_DONE;
                }
                String meta = null;
                if (cat == CarDiary.Category.TIRE_PRESSURE) {
                    meta = CarDiary.buildTirePressureMeta(psi[0], psi[1], psi[2], psi[3], originalCold);
                } else if (cat == CarDiary.Category.SERVICE && !bundled.isEmpty()) {
                    meta = CarDiary.buildServiceBundledMeta(new ArrayList<>(bundled));
                } else if (cat == CarDiary.Category.COOLANT) {
                    meta = CarDiary.buildCoolantMeta(coolantTopUp);
                } else if (cat == CarDiary.Category.INCIDENT && editing.category == CarDiary.Category.INCIDENT) {
                    meta = editing.metaJson;
                }
                String title = (editing.category == cat) ? editing.title : null;
                CarDiary.update(app, editing.id, cat, status, tsMs, odo, cost, title, note, tags, meta, onSaved);
            } else if (cat == CarDiary.Category.TIRE_PRESSURE) {
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
                boolean captureClip = (jolt != null && saveClip);
                if (jolt != null) {
                    com.geely.drivemem.sensors.JoltFeedback.record(app, jolt, true);
                }
                CarDiary.insertIncidentWithOptionalClip(
                    app, tsMs, captureClip, true, odo, outC, lat, lon, cost,
                    null, note, tags, meta, jolt != null ? jolt.id : null, onSaved);
            } else {
                String meta = null;
                if (cat == CarDiary.Category.SERVICE && !bundled.isEmpty()) {
                    meta = CarDiary.buildServiceBundledMeta(new ArrayList<>(bundled));
                } else if (cat == CarDiary.Category.COOLANT) {
                    meta = CarDiary.buildCoolantMeta(coolantTopUp);
                }
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
