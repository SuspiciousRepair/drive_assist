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
import com.geely.drivemem.controls.GeelySwitch;
import com.geely.drivemem.state.CarDiary;
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
 * The dialog opens in any gear; it has no Park gate. */
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

    // Noise tags are saved in diary_event.tags as these keys, not as the label
    // on the pill, so a saved tag still matches its pill after a language
    // change. Same order as the *_RES arrays above. Never rename a key.
    private static final String[] NOISE_WHERE_KEYS = {"FRONT_LEFT", "FRONT_RIGHT", "REAR", "CABIN"};
    private static final String[] NOISE_WHEN_KEYS = {"BUMPS", "BRAKING", "HIGHWAY"};

    private static final int[] WHEEL_LABEL_RES = {
        R.string.diary_wheel_fl, R.string.diary_wheel_fr,
        R.string.diary_wheel_rl, R.string.diary_wheel_rr
    };

    /** Smallest touch target in this dialog, in px (1 dp = 1 px on the panel). */
    private static final int TOUCH_DP = 60;

    /** The key for one saved tag. Rows written before the keys existed hold the
     * label text instead: those are matched against the labels of the current
     * language. A tag that matches nothing is kept as it is. */
    private static String noiseKeyOf(Context c, String token) {
        String t = token.trim();
        for (String k : NOISE_WHERE_KEYS) if (k.equalsIgnoreCase(t)) return k;
        for (String k : NOISE_WHEN_KEYS) if (k.equalsIgnoreCase(t)) return k;
        for (int i = 0; i < NOISE_WHERE_RES.length; i++) {
            if (c.getString(NOISE_WHERE_RES[i]).equalsIgnoreCase(t)) return NOISE_WHERE_KEYS[i];
        }
        for (int i = 0; i < NOISE_WHEN_RES.length; i++) {
            if (c.getString(NOISE_WHEN_RES[i]).equalsIgnoreCase(t)) return NOISE_WHEN_KEYS[i];
        }
        return t;
    }

    private static String noiseLabelOf(Context c, String key) {
        for (int i = 0; i < NOISE_WHERE_KEYS.length; i++) {
            if (NOISE_WHERE_KEYS[i].equals(key)) return c.getString(NOISE_WHERE_RES[i]);
        }
        for (int i = 0; i < NOISE_WHEN_KEYS.length; i++) {
            if (NOISE_WHEN_KEYS[i].equals(key)) return c.getString(NOISE_WHEN_RES[i]);
        }
        return key;
    }

    /** Saved noise tags ("FRONT_LEFT, BUMPS") as text in the current language. */
    static String noiseTagsLabel(Context c, String tags) {
        if (tags == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String tok : tags.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(noiseLabelOf(c, noiseKeyOf(c, t)));
        }
        return sb.toString();
    }

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

        // Auto-attached car odometer for selectedDate (never manually edited).
        // odoPending: the lookup for selectedDate has not come back yet; Save
        // waits for it, so an entry never gets the odometer of another date.
        private Double resolvedOdoKm = null;
        private boolean resolvedOdoExact = true;
        private int odoLookupGen = 0;
        private boolean odoPending = false;
        private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

        // Tire pressure state (cold/warm inferred from CarDb on save when logging today)
        private final int[] psi = {DEFAULT_PSI, DEFAULT_PSI, DEFAULT_PSI, DEFAULT_PSI};
        private boolean perWheel = false;

        // Noise tag state (stable keys, see NOISE_WHERE_KEYS)
        private final Set<String> selectedTags = new LinkedHashSet<>();

        // Incident state
        private boolean saveClip = true;

        // Service bundled state
        private final Set<CarDiary.Category> bundled = new LinkedHashSet<>();

        // Coverage (insurance, tax and licensing): end date of the cover, and
        // for insurance who to call. Null date = entry date + one year.
        private LocalDate validUntil;
        private String insurer, policyNo, assistPhone;
        private EditText insurerInput, policyInput, phoneInput;

        // Traffic ticket: the hour on the notice, as a minute of the day (null = not given).
        private Integer ticketMinute;
        private EditText ticketHourInput, ticketMinInput;

        // Coolant mode (default Top-up so topping off doesn't reset the 4-year / 80k flush clock)
        private boolean coolantTopUp = true;

        // "Postponed": the shop looked at the item and said to wait until a km or a day.
        private boolean postponed;
        private Integer postponeKm;
        private LocalDate postponeDate;
        private EditText postponeKmInput;

        // UI references
        private LinearLayout leftHeaderHost;
        private LinearLayout leftListHost;
        private LinearLayout paneBox;
        private TextView paneTitle;
        private TextView paneCtx;
        private LinearLayout dateSeg;
        private ScrollView paneBodyScroll;
        private LinearLayout adaptiveBox;
        private EditText costInput;
        private EditText noteInput;
        private TextView saveBtn;
        private AlertDialog dialog;
        private AlertDialog calDialog;

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
                this.ticketMinute = CarDiary.parseTicketMinute(editing.metaJson);
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
                if (CarDiary.isPostponed(editing.metaJson)) {
                    postponed = true;
                    Double pk = CarDiary.parsePostponedKm(editing.metaJson);
                    if (pk != null) postponeKm = (int) Math.round(pk);
                    Long pd = CarDiary.parsePostponedDay(editing.metaJson);
                    if (pd != null) postponeDate = LocalDate.ofEpochDay(pd);
                }
                bundled.addAll(CarDiary.parseBundled(editing.metaJson));
                Long until = CarDiary.parseValidUntilMs(editing.metaJson);
                if (until != null) {
                    validUntil = Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).toLocalDate();
                }
                insurer = CarDiary.parseMetaText(editing.metaJson, CarDiary.META_PROVIDER);
                policyNo = CarDiary.parseMetaText(editing.metaJson, CarDiary.META_POLICY_NO);
                assistPhone = CarDiary.parseMetaText(editing.metaJson, CarDiary.META_ASSIST_PHONE);
                if (editing.tags != null && !editing.tags.trim().isEmpty()) {
                    for (String tok : editing.tags.split(",")) {
                        String t = tok.trim();
                        if (!t.isEmpty()) selectedTags.add(noiseKeyOf(c, t));
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
            paneCtx.setTextSize(18f);
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
            costWell.setMinimumHeight(Style.dp(c, TOUCH_DP));

            TextView cur = new TextView(c);
            cur.setText(Prefs.getCurrency(c));
            cur.setTextColor(Style.TEXT_DIM);
            cur.setTextSize(18f);
            cur.setTypeface(null, Typeface.BOLD);
            cur.setPadding(0, 0, Style.dp(c, 8), 0);
            costWell.addView(cur);

            costInput = bareInput(InputType.TYPE_CLASS_NUMBER,
                c.getString(R.string.diary_hint_cost_short), 22f, true);
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
            noteWell.setMinimumHeight(Style.dp(c, TOUCH_DP));

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
            saveBtn = new TextView(c);
            saveBtn.setText(c.getString(R.string.diary_btn_save));
            saveBtn.setTextColor(Style.onFill(Style.ACCENT));
            saveBtn.setTextSize(20f);
            saveBtn.setTypeface(null, Typeface.BOLD);
            saveBtn.setGravity(Gravity.CENTER);
            saveBtn.setBackground(roundedRect(Style.ACCENT, 0, 0, 99));
            saveBtn.setOnClickListener(v -> save());
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, TOUCH_DP));
            saveLp.topMargin = Style.dp(c, 10);
            pane.addView(saveBtn, saveLp);
            paneBox = pane;

            // Mirror left/right columns in RHD so the category picker stays on the driver's edge
            Style.addMirrored(sheet, 22, leftCol, pane);

            rebuildLeft();
            rebuildPaneHeader();
            rebuildDateSeg();
            rebuildAdaptive();
            refreshOdometerForSelectedDate();

            dialog = new AlertDialog.Builder(c).create();
            dialog.setView(sheet, 0, 0, 0, 0);
            // The calendar is a second dialog on top: it goes when this one goes.
            dialog.setOnDismissListener(d -> {
                odoLookupGen++;
                if (calDialog != null) {
                    try { calDialog.dismiss(); } catch (Throwable ignored) {}
                    calDialog = null;
                }
            });
            Window win = dialog.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            dialog.show();
            if (win != null) {
                DisplayMetrics dm = c.getResources().getDisplayMetrics();
                int w = Math.min(Style.dp(c, 1380), (int) (dm.widthPixels * 0.88f));
                int h = Math.min(Style.dp(c, 760), (int) (dm.heightPixels * 0.80f));
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
            syncPaneVisibility();
        }

        /** On the list of classes no item is picked: the form (title, date,
         * fields, Save) is not shown, only the empty pane. With an item picked,
         * the date bar is still hidden for a jolt incident (it has its own time).
         * One place sets these, so no later rebuild shows a part again. */
        private void syncPaneVisibility() {
            if (paneBox == null) return;
            boolean joltIncident = jolt != null && selectedCat == CarDiary.Category.INCIDENT;
            for (int i = 0; i < paneBox.getChildCount(); i++) {
                View v = paneBox.getChildAt(i);
                if (showingClasses) v.setVisibility(View.INVISIBLE);
                else v.setVisibility(v == dateSeg && joltIncident ? View.GONE : View.VISIBLE);
            }
        }

        private void buildItemsList() {
            // Pinned header: [<- circle] [accent icon] ClassName (tap anywhere to expand 5 classes)
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(Style.dp(c, 4), Style.dp(c, 4), Style.dp(c, 8), Style.dp(c, 4));
            head.setMinimumHeight(Style.dp(c, TOUCH_DP + 8));
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
                row.setMinimumHeight(Style.dp(c, TOUCH_DP));
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
                    due.setTextSize(18f);
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
            head.setMinimumHeight(Style.dp(c, TOUCH_DP + 8));
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
                row.setMinimumHeight(Style.dp(c, TOUCH_DP));
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
                    due.setTextSize(18f);
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
            int sz = Style.dp(c, TOUCH_DP);
            box.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            box.addView(iconView(drawableRes, 24, Style.TEXT));
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
            // Drops the odometer of the old date and looks up the new one.
            refreshOdometerForSelectedDate();
        }

        private LocalDate joltDate() {
            return Instant.ofEpochMilli(jolt.tsMs).atZone(ZoneId.systemDefault()).toLocalDate();
        }

        /** Save is off while the odometer for the picked date is still being read. */
        private void setOdoPending(boolean pending) {
            odoPending = pending;
            if (saveBtn != null) {
                saveBtn.setEnabled(!pending);
                saveBtn.setAlpha(pending ? 0.4f : 1f);
            }
        }

        private TextView segBtn(String label, boolean active, Runnable onTap) {
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextSize(18f);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setTextColor(active ? Style.TEXT : Style.TEXT_DIM);
            tv.setGravity(Gravity.CENTER);
            tv.setMinimumHeight(Style.dp(c, 56));
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
        /** The entry date: no future days. */
        private void openCalendarPicker() {
            openCalendar(selectedDate, false, this::applyDate);
        }

        /** Month calendar. Future days can be picked only when allowFuture is set
         * (the end date of an insurance policy). */
        private void openCalendar(LocalDate calSelected, boolean allowFuture,
                                  java.util.function.Consumer<LocalDate> onPick) {
            if (calDialog != null) {
                try { calDialog.dismiss(); } catch (Throwable ignored) {}
            }
            AlertDialog calDlg = DiaryCalendar.open(c, calSelected, allowFuture, onPick);
            calDialog = calDlg;
            calDlg.setOnDismissListener(d -> { if (calDialog == calDlg) calDialog = null; });
        }

        /** Sets the odometer for selectedDate. The value of the old date is
         * dropped at once; while the new one is being read (diary worker
         * thread, never the hub thread) Save stays off. */
        private void refreshOdometerForSelectedDate() {
            final int gen = ++odoLookupGen;
            if (editing != null && editing.odoKm != null && editing.odoKm > 0
                    && selectedDate.equals(originalDate)) {
                resolvedOdoKm = editing.odoKm;
                resolvedOdoExact = true;
                setOdoPending(false);
                rebuildPaneHeader();
                return;
            }
            if (jolt != null && jolt.odoKm != null && jolt.odoKm > 0
                    && selectedDate.equals(joltDate())) {
                resolvedOdoKm = jolt.odoKm;
                resolvedOdoExact = true;
                setOdoPending(false);
                rebuildPaneHeader();
                return;
            }
            resolvedOdoKm = null;
            resolvedOdoExact = true;
            setOdoPending(true);
            rebuildPaneHeader();

            final LocalDate target = selectedDate;
            final Context app = c.getApplicationContext();
            CarDiary.runAsync(() -> {
                CarDiary.OdoLookup found = null;
                try {
                    if (target.equals(LocalDate.now())) {
                        Double live = CarDiary.latestOdometerKm(app);
                        found = (live != null && live > 0)
                            ? new CarDiary.OdoLookup(live, true)
                            : CarDiary.odometerForDate(app, target);
                    } else {
                        found = CarDiary.odometerForDate(app, target);
                    }
                } catch (Throwable ignored) {
                    // No odometer for this date: the entry is saved without one.
                }
                final CarDiary.OdoLookup res = found;
                main.post(() -> {
                    if (gen != odoLookupGen) return;
                    resolvedOdoKm = (res != null && res.odoKm != null && res.odoKm > 0) ? res.odoKm : null;
                    resolvedOdoExact = res == null || res.exact;
                    setOdoPending(false);
                    rebuildPaneHeader();
                });
            });
        }

        // ---- Adaptive Controls (only shown for categories that need them) ----

        private void rebuildAdaptive() {
            // A jolt has its own time: the entry and its clip use it, so there
            // is no date to pick while the category is Incident.
            boolean joltIncident = jolt != null && selectedCat == CarDiary.Category.INCIDENT;
            syncPaneVisibility();
            if (joltIncident && !selectedDate.equals(joltDate())) applyDate(joltDate());

            stashCoverageInputs();
            stashTicketInputs();
            stashPostponeInput();
            adaptiveBox.removeAllViews();
            boolean canPostpone = canPostpone(selectedCat);
            if (canPostpone) buildPostponeControls();
            if (selectedCat == CarDiary.Category.TRAFFIC_TICKET) {
                adaptiveBox.setVisibility(View.VISIBLE);
                buildTicketControls();
            } else if (hasCoverage(selectedCat)) {
                adaptiveBox.setVisibility(View.VISIBLE);
                buildCoverageControls();
            } else if (selectedCat == CarDiary.Category.TIRE_PRESSURE) {
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
                // Top-up or change is a question for a done job only.
                if (!postponed) buildCoolantControls();
            } else {
                adaptiveBox.setVisibility(canPostpone ? View.VISIBLE : View.GONE);
            }
        }

        /** An item with a schedule can be postponed. Not the service visit
         * itself, and not tire pressure (nothing to wait for). */
        private boolean canPostpone(CarDiary.Category cat) {
            if (cat == null || cat == CarDiary.Category.SERVICE
                    || cat == CarDiary.Category.TIRE_PRESSURE || hasCoverage(cat)) return false;
            CarDiary.ScheduleConfig cfg = CarDiary.ScheduleConfig.fromPrefs(c);
            return cfg.kmInterval(cat) != null || cfg.dayInterval(cat) != null;
        }

        /** True when the item is due by distance: it is postponed to a km, else to a day. */
        private boolean postponeByKm() {
            return CarDiary.ScheduleConfig.fromPrefs(c).kmInterval(selectedCat) != null;
        }

        private void stashPostponeInput() {
            if (postponeKmInput != null) {
                String t = postponeKmInput.getText().toString().replaceAll("[^0-9]", "");
                try { postponeKm = t.isEmpty() ? null : Integer.valueOf(t); }
                catch (NumberFormatException e) { postponeKm = null; }
            }
            postponeKmInput = null;
        }

        /** [Done | Postponed], and for Postponed the point to wait for:
         * "Until  [30000] km" or "Until  [12 May 27]". */
        private void buildPostponeControls() {
            LinearLayout seg = new LinearLayout(c);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setBackground(roundedRect(wellFill(), borderColor(), 1, 99));
            int sp = Style.dp(c, 4);
            seg.setPadding(sp, sp, sp, sp);
            seg.addView(segBtn(c.getString(R.string.diary_postpone_done), !postponed, () -> {
                postponed = false;
                rebuildAdaptive();
            }));
            seg.addView(segBtn(c.getString(R.string.diary_postpone_later), postponed, () -> {
                postponed = true;
                rebuildAdaptive();
            }));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.bottomMargin = Style.dp(c, 12);
            adaptiveBox.addView(seg, slp);
            if (!postponed) return;

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView lbl = new TextView(c);
            lbl.setText(c.getString(R.string.diary_postpone_until));
            lbl.setTextColor(Style.TEXT);
            lbl.setTextSize(18f);
            row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout well = new LinearLayout(c);
            well.setOrientation(LinearLayout.HORIZONTAL);
            well.setGravity(Gravity.CENTER);
            well.setBackground(roundedRect(wellFill(), borderColor(), 1, 18));
            well.setPadding(Style.dp(c, 18), Style.dp(c, 8), Style.dp(c, 18), Style.dp(c, 8));
            well.setMinimumHeight(Style.dp(c, TOUCH_DP));
            if (postponeByKm()) {
                // First value: 10,000 km on from the odometer of the entry, in round thousands.
                if (postponeKm == null && resolvedOdoKm != null) {
                    postponeKm = (int) (Math.ceil((resolvedOdoKm + 10_000.0) / 1_000.0) * 1_000.0);
                }
                postponeKmInput = bareInput(InputType.TYPE_CLASS_NUMBER, "", 22f, true);
                postponeKmInput.setFilters(new android.text.InputFilter[]{
                    new android.text.InputFilter.LengthFilter(6)});
                postponeKmInput.setGravity(Gravity.CENTER);
                if (postponeKm != null) postponeKmInput.setText(String.valueOf(postponeKm));
                well.addView(postponeKmInput, new LinearLayout.LayoutParams(
                    Style.dp(c, 120), ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView unit = new TextView(c);
                unit.setText("km");
                unit.setTextColor(Style.TEXT_DIM);
                unit.setTextSize(18f);
                unit.setPadding(Style.dp(c, 6), 0, 0, 0);
                well.addView(unit);
            } else {
                if (postponeDate == null) postponeDate = selectedDate.plusMonths(6);
                TextView day = new TextView(c);
                day.setText(postponeDate.format(java.time.format.DateTimeFormatter
                    .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)));
                day.setTextColor(Style.TEXT);
                day.setTextSize(22f);
                day.setTypeface(null, Typeface.BOLD);
                well.addView(day);
                well.setOnClickListener(v -> openCalendar(postponeDate, true, d -> {
                    postponeDate = d;
                    rebuildAdaptive();
                }));
            }
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                Style.dp(c, 220), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(well, wlp);
            adaptiveBox.addView(row);
        }

        /** The meta of a postponed entry, or null when the entry is a done job
         * (or has no point to wait for yet). */
        private String postponeMeta(CarDiary.Category cat) {
            if (!postponed || !canPostpone(cat)) return null;
            stashPostponeInput();
            return postponeByKm()
                ? CarDiary.buildPostponedMeta(postponeKm, null)
                : CarDiary.buildPostponedMeta(null, postponeDate != null ? postponeDate.toEpochDay() : null);
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
                List<View> steppers = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    steppers.add(buildPsiStepper(c.getString(WHEEL_LABEL_RES[i]), i, false));
                }
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
            box.setMinimumHeight(Style.dp(c, allFour ? 84 : 76));
            int px = Style.dp(c, allFour ? 20 : 14);
            int py = Style.dp(c, allFour ? 12 : 8);
            box.setPadding(px, py, px, py);

            if (wheelLabel != null) {
                TextView wl = new TextView(c);
                wl.setText(wheelLabel);
                wl.setTextColor(Style.TEXT_DIM);
                wl.setTextSize(18f);
                wl.setTypeface(null, Typeface.BOLD);
                wl.setSingleLine(true);
                wl.setEllipsize(TextUtils.TruncateAt.END);
                // The label takes the room left by the two buttons and the value.
                box.addView(wl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }

            TextView val = new TextView(c);
            val.setTextSize(allFour ? 36f : 22f);
            val.setTypeface(null, Typeface.BOLD);
            val.setTextColor(Style.TEXT);
            val.setGravity(Gravity.CENTER);
            val.setSingleLine(true);
            val.setText(Style.valueWithUnit(String.valueOf(psi[idx]), Style.TEXT, "PSI",
                allFour ? Style.UNIT_SCALE_HERO : Style.UNIT_SCALE_ROW));

            int btnSz = TOUCH_DP;
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
            b.setTextSize(24f);
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
            for (int i = 0; i < NOISE_WHERE_RES.length; i++) {
                final String tag = NOISE_WHERE_KEYS[i];
                where.add(tagPill(c.getString(NOISE_WHERE_RES[i]), selectedTags.contains(tag), () -> {
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
            for (int i = 0; i < NOISE_WHEN_RES.length; i++) {
                final String tag = NOISE_WHEN_KEYS[i];
                when.add(tagPill(c.getString(NOISE_WHEN_RES[i]), selectedTags.contains(tag), () -> {
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
            tv.setTextSize(18f);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setGravity(Gravity.CENTER);
            tv.setMinimumHeight(Style.dp(c, TOUCH_DP));
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
                jb.setTextSize(18f);
                jRow.addView(jb);
                adaptiveBox.addView(jRow);
            }

            // A car with no dashcam has no file to cut the clip from: no switch.
            if (noDashcam(c)) return;

            // Full-width recessed well toggle: Save 5s dashcam clip
            LinearLayout clipWell = new LinearLayout(c);
            clipWell.setOrientation(LinearLayout.HORIZONTAL);
            clipWell.setGravity(Gravity.CENTER_VERTICAL);
            clipWell.setBackground(roundedRect(wellFill(), borderColor(), 1, 18));
            clipWell.setPadding(Style.dp(c, 18), Style.dp(c, 10), Style.dp(c, 14), Style.dp(c, 10));
            clipWell.setMinimumHeight(Style.dp(c, TOUCH_DP));

            TextView lbl = new TextView(c);
            lbl.setText(c.getString(R.string.diary_incident_keep_clip));
            lbl.setTextColor(Style.TEXT);
            lbl.setTextSize(18f);
            clipWell.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            GeelySwitch sw = new GeelySwitch(c);
            sw.setLockSeconds(0);
            sw.setCheckedSilently(saveClip);
            sw.setOnToggle(on -> saveClip = on);
            clipWell.addView(sw);
            adaptiveBox.addView(clipWell);
        }

        private static boolean noDashcam(Context c) {
            return com.geely.drivemem.util.CarCaps.blocksDashcam(com.geely.drivemem.util.CarCaps.read(c));
        }

        /** Yearly cover with its own end date: insurance, tax and licensing. */
        private static boolean hasCoverage(CarDiary.Category cat) {
            return cat != null && cat.coverageDays >= 365;
        }

        private LocalDate effectiveValidUntil() {
            return (validUntil != null && validUntil.isAfter(selectedDate))
                ? validUntil : selectedDate.plusYears(1);
        }

        /** The box is rebuilt often: keep the hour that was typed. */
        private void stashTicketInputs() {
            if (ticketHourInput != null) {
                ticketMinute = CarDiary.clockMinute(ticketHourInput.getText().toString(),
                    ticketMinInput != null ? ticketMinInput.getText().toString() : null);
            }
            ticketHourInput = ticketMinInput = null;
        }

        /** "Time on the notice" + [hh] : [mm]. With it the entry gets the
         * place where the car was (from the saved route). */
        private void buildTicketControls() {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView lbl = new TextView(c);
            lbl.setText(c.getString(R.string.diary_ticket_time));
            lbl.setTextColor(Style.TEXT);
            lbl.setTextSize(18f);
            row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout well = new LinearLayout(c);
            well.setOrientation(LinearLayout.HORIZONTAL);
            well.setGravity(Gravity.CENTER);
            well.setBackground(roundedRect(wellFill(), borderColor(), 1, 18));
            well.setPadding(Style.dp(c, 18), Style.dp(c, 8), Style.dp(c, 18), Style.dp(c, 8));
            well.setMinimumHeight(Style.dp(c, TOUCH_DP));

            ticketHourInput = clockInput("hh", ticketMinute != null ? ticketMinute / 60 : null);
            ticketMinInput = clockInput("mm", ticketMinute != null ? ticketMinute % 60 : null);
            final EditText minIn = ticketMinInput;
            // Two digits of the hour: go on to the minutes.
            ticketHourInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence t, int a, int b, int d) {}
                @Override public void onTextChanged(CharSequence t, int a, int b, int d) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    if (e.length() == 2 && minIn.getText().length() == 0) minIn.requestFocus();
                }
            });
            well.addView(ticketHourInput, new LinearLayout.LayoutParams(
                Style.dp(c, 56), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView colon = new TextView(c);
            colon.setText(":");
            colon.setTextColor(Style.TEXT_DIM);
            colon.setTextSize(22f);
            colon.setPadding(Style.dp(c, 6), 0, Style.dp(c, 6), 0);
            well.addView(colon);
            well.addView(ticketMinInput, new LinearLayout.LayoutParams(
                Style.dp(c, 56), ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(well, new LinearLayout.LayoutParams(
                Style.dp(c, 220), ViewGroup.LayoutParams.WRAP_CONTENT));
            adaptiveBox.addView(row);
        }

        private EditText clockInput(String hint, Integer value) {
            EditText e = bareInput(InputType.TYPE_CLASS_NUMBER, hint, 22f, true);
            e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2)});
            e.setGravity(Gravity.CENTER);
            if (value != null) e.setText(String.format(Locale.US, "%02d", value));
            return e;
        }

        /** The box is rebuilt often: keep what was typed. */
        private void stashCoverageInputs() {
            if (insurerInput != null) insurer = insurerInput.getText().toString().trim();
            if (policyInput != null) policyNo = policyInput.getText().toString().trim();
            if (phoneInput != null) assistPhone = phoneInput.getText().toString().trim();
            insurerInput = policyInput = phoneInput = null;
        }

        private String coverageMeta() {
            stashCoverageInputs();
            long untilMs = effectiveValidUntil().atTime(23, 59, 59)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            boolean ins = selectedCat == CarDiary.Category.INSURANCE;
            return CarDiary.buildCoverageMeta(untilMs, ins ? insurer : null,
                ins ? policyNo : null, ins ? assistPhone : null);
        }

        private void buildCoverageControls() {
            boolean ins = selectedCat == CarDiary.Category.INSURANCE;
            int gap = Style.dp(c, 12);

            LinearLayout row1 = new LinearLayout(c);
            row1.setOrientation(LinearLayout.HORIZONTAL);

            // "Valid until  27 Jan 27": tap for the calendar (future days allowed).
            TextView until = new TextView(c);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(
                c.getString(R.string.diary_cov_valid_until) + "   ");
            sb.setSpan(new android.text.style.ForegroundColorSpan(Style.TEXT_DIM), 0, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int st = sb.length();
            sb.append(shortYearDateFmt.format(effectiveValidUntil()));
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            until.setText(sb);
            until.setTextColor(Style.TEXT);
            until.setTextSize(20f);
            until.setGravity(Gravity.CENTER_VERTICAL);
            until.setSingleLine(true);
            until.setBackground(roundedRect(wellFill(), borderColor(), 1, 18));
            until.setPadding(Style.dp(c, 18), Style.dp(c, 8), Style.dp(c, 18), Style.dp(c, 8));
            until.setMinimumHeight(Style.dp(c, TOUCH_DP));
            until.setOnClickListener(v -> openCalendar(effectiveValidUntil(), true, d -> {
                validUntil = d;
                rebuildAdaptive();
            }));
            row1.addView(until, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (ins) {
                insurerInput = coverageInput(R.string.diary_cov_insurer, insurer,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = gap;
                row1.addView(coverageWell(insurerInput), lp);
            }
            adaptiveBox.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (!ins) return;

            LinearLayout row2 = new LinearLayout(c);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            policyInput = coverageInput(R.string.diary_cov_policy, policyNo,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            phoneInput = coverageInput(R.string.diary_cov_phone, assistPhone, InputType.TYPE_CLASS_PHONE);
            row2.addView(coverageWell(policyInput),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            plp.leftMargin = gap;
            row2.addView(coverageWell(phoneInput), plp);
            LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            r2.topMargin = gap;
            adaptiveBox.addView(row2, r2);
        }

        private EditText coverageInput(int hintRes, String value, int inputType) {
            EditText e = bareInput(inputType, c.getString(hintRes), 20f, false);
            if (value != null) e.setText(value);
            return e;
        }

        private LinearLayout coverageWell(EditText input) {
            LinearLayout well = new LinearLayout(c);
            well.setOrientation(LinearLayout.HORIZONTAL);
            well.setGravity(Gravity.CENTER_VERTICAL);
            well.setBackground(roundedRect(wellFill(), borderColor(), 1, 18));
            well.setPadding(Style.dp(c, 18), Style.dp(c, 8), Style.dp(c, 18), Style.dp(c, 8));
            well.setMinimumHeight(Style.dp(c, TOUCH_DP));
            well.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return well;
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
            row.setMinimumHeight(Style.dp(c, TOUCH_DP));
            row.setBackground(checked
                ? roundedRect(accentTint(), Style.ACCENT, 2, 16)
                : roundedRect(wellFill(), borderColor(), 1, 16));

            TextView box = new TextView(c);
            box.setText(checked ? "✓" : "");
            box.setTextColor(Style.onFill(Style.ACCENT));
            box.setTextSize(18f);
            box.setTypeface(null, Typeface.BOLD);
            box.setGravity(Gravity.CENTER);
            box.setBackground(checked
                ? roundedRect(Style.ACCENT, Style.ACCENT, 1, 6)
                : roundedRect(Color.TRANSPARENT, withAlpha(Style.TEXT_DIM, 0.7f), 2, 6));
            int bsz = Style.dp(c, 28);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(bsz, bsz);
            blp.rightMargin = Style.dp(c, 12);
            row.addView(box, blp);

            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextColor(Style.TEXT);
            tv.setTextSize(18f);
            tv.setTypeface(null, checked ? Typeface.BOLD : Typeface.NORMAL);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(v -> onTap.run());
            return row;
        }

        // ---- Save ----

        private void save() {
            // The odometer for the picked date is still being read: Save is
            // greyed out until it lands (see setOdoPending).
            if (odoPending) return;
            // Postponed with no km to wait for: nothing to record yet.
            if (postponed && canPostpone(selectedCat) && postponeMeta(selectedCat) == null) {
                rebuildAdaptive();
                if (postponeKmInput != null) postponeKmInput.requestFocus();
                return;
            }
            final Double odo = resolvedOdoKm;
            long cents = ChargeCostDialog.centsFromText(costInput.getText().toString());
            final Double cost = cents > 0 ? (cents / 100.0) : null;
            String rawNote = noteInput.getText().toString().trim();
            final String note = rawNote.isEmpty() ? null : rawNote;

            final boolean isToday = selectedDate.equals(LocalDate.now());
            stashTicketInputs();
            final Integer ticketMin = selectedCat == CarDiary.Category.TRAFFIC_TICKET ? ticketMinute : null;
            final long tsMs;
            if (ticketMin != null) {
                // The hour on the notice: the entry is at that minute, and its
                // place is looked up there (CarDiary.insertTicket).
                tsMs = selectedDate.atTime(ticketMin / 60, ticketMin % 60).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            } else if (editing != null && selectedDate.equals(originalDate)) {
                tsMs = editing.tsMs;
            } else if (jolt != null && selectedCat == CarDiary.Category.INCIDENT) {
                // An incident logged from a jolt is the jolt: same time, and the
                // clip is cut around that time (never around noon of a picked day).
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

            // The jolt that opened this dialog is used up by any save, also when
            // the driver changed the category. The Incident branch below hands
            // it to insertIncidentWithOptionalClip, which removes it itself.
            if (jolt != null && cat != CarDiary.Category.INCIDENT) {
                com.geely.drivemem.sensors.JoltFeedback.record(app, jolt, true);
                CarDiary.dismissPendingJolt(jolt.id);
            }

            if (editing != null) {
                String status = editing.status;
                if (editing.category != cat) {
                    status = (cat == CarDiary.Category.NOISE || cat == CarDiary.Category.INCIDENT)
                        ? CarDiary.STATUS_OPEN : CarDiary.STATUS_DONE;
                }
                String meta = null;
                String pmeta = postponeMeta(cat);
                if (pmeta != null) {
                    meta = pmeta;
                } else if (hasCoverage(cat)) {
                    meta = coverageMeta();
                } else if (cat == CarDiary.Category.TIRE_PRESSURE) {
                    meta = CarDiary.buildTirePressureMeta(psi[0], psi[1], psi[2], psi[3], originalCold);
                } else if (cat == CarDiary.Category.SERVICE && !bundled.isEmpty()) {
                    meta = CarDiary.buildServiceBundledMeta(new ArrayList<>(bundled));
                } else if (cat == CarDiary.Category.COOLANT) {
                    meta = CarDiary.buildCoolantMeta(coolantTopUp);
                } else if (cat == CarDiary.Category.INCIDENT && editing.category == CarDiary.Category.INCIDENT) {
                    meta = editing.metaJson;
                }
                String title = (editing.category == cat) ? editing.title : null;
                if (cat == CarDiary.Category.TRAFFIC_TICKET) {
                    // Same writer thread, in order: the fields, then the place for the hour.
                    CarDiary.update(app, editing.id, cat, status, tsMs, odo, cost, title, note, tags,
                        CarDiary.buildTicketMeta(ticketMin, null), null);
                    CarDiary.relocateTicket(app, editing.id, tsMs, ticketMin, onSaved);
                } else {
                    CarDiary.update(app, editing.id, cat, status, tsMs, odo, cost, title, note, tags, meta, onSaved);
                }
            } else if (cat == CarDiary.Category.TIRE_PRESSURE) {
                final int[] snap = {psi[0], psi[1], psi[2], psi[3]};
                Runnable commit = () -> {
                    Boolean cold = isToday ? CarDiary.inferColdTires(app, tsMs) : null;
                    String meta = CarDiary.buildTirePressureMeta(
                        snap[0], snap[1], snap[2], snap[3], cold);
                    CarDiary.insert(app, cat, CarDiary.STATUS_DONE, tsMs, odo, outC, cost,
                        null, note, null, null, null, meta, onSaved);
                };
                // inferColdTires reads the database: diary worker thread, not the hub thread.
                CarDiary.runAsync(commit);
            } else if (cat == CarDiary.Category.TRAFFIC_TICKET) {
                CarDiary.insertTicket(app, tsMs, ticketMin, odo, cost, note, onSaved);
            } else if (cat == CarDiary.Category.INCIDENT) {
                Double lat = jolt != null ? jolt.lat : null;
                Double lon = jolt != null ? jolt.lon : null;
                String meta = jolt != null ? CarDiary.buildJoltMeta(jolt.maxG(), jolt.speedKmh) : null;
                boolean captureClip = (jolt != null && saveClip && !noDashcam(app));
                if (jolt != null) {
                    com.geely.drivemem.sensors.JoltFeedback.record(app, jolt, true);
                }
                CarDiary.insertIncidentWithOptionalClip(
                    app, tsMs, captureClip, true, odo, outC, lat, lon, cost,
                    null, note, tags, meta, jolt != null ? jolt.id : null, onSaved);
            } else {
                String meta = null;
                String pmeta = postponeMeta(cat);
                if (pmeta != null) {
                    meta = pmeta;
                } else if (hasCoverage(cat)) {
                    meta = coverageMeta();
                } else if (cat == CarDiary.Category.SERVICE && !bundled.isEmpty()) {
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
            tv.setTextSize(18f);
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

        // All three come from the active theme (themes are dynamic), no fixed colours.

        /** A recessed field: the card colour pulled toward the screen background. */
        private int wellFill() {
            return Style.blend(Style.cardFillColor(), Style.BG_BOTTOM, 0.35f);
        }

        private int borderColor() {
            return Style.tileStrokeColor();
        }

        private int strongBorderColor() {
            return withAlpha(Style.TEXT, 0.16f);
        }

        private int accentTint() {
            return withAlpha(Style.ACCENT, Style.LIGHT ? 0.14f : 0.22f);
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
