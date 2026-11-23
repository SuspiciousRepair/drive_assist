package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Wide 2-column automotive modal for adding a Car Diary entry.
 *
 * Left column: 5 Class pills -> filtered 2-column grid of items in that class
 * (no horizontal scrolling, large touch targets).
 * Right column: Date selector (Today / Yesterday / calendar picker) that
 * automatically looks up the car's odometer for the chosen date from CarDb,
 * adaptive per-category controls, universal Cost + Odometer + Note fields,
 * and pinned Cancel / Save buttons. Park-gated for keyboard safety. */
public final class DiaryEntryDialog {
    private DiaryEntryDialog() {}

    private static final int DEFAULT_PSI = 36;
    private static final int MIN_PSI = 20;
    private static final int MAX_PSI = 55;

    private static final int[] NOISE_WHERE_RES = {
        R.string.diary_tag_fl, R.string.diary_tag_fr, R.string.diary_tag_rear,
        R.string.diary_tag_cabin, R.string.diary_tag_brakes
    };
    private static final int[] NOISE_WHEN_RES = {
        R.string.diary_tag_bumps, R.string.diary_tag_braking, R.string.diary_tag_turning,
        R.string.diary_tag_highway, R.string.diary_tag_cold
    };

    public static AlertDialog show(Context c, CarDiary.Category initialCat,
                                   CarDiary.PendingJolt jolt, Runnable onSaved) {
        if (!CarState.isParked()) {
            Toast.makeText(c, c.getString(R.string.diary_park_required), Toast.LENGTH_SHORT).show();
            return null;
        }
        return new Builder(c, initialCat, jolt, onSaved).show();
    }

    private static final class Builder {
        private final Context c;
        private final CarDiary.PendingJolt jolt;
        private final Runnable onSaved;
        private final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault());

        private CarDiary.Group selectedGroup;
        private CarDiary.Category selectedCat;
        private LocalDate selectedDate;
        private boolean userEditedOdo = false;
        private boolean settingOdoProgrammatically = false;
        private int odoLookupGen = 0;

        // Tire pressure state
        private final int[] psi = {DEFAULT_PSI, DEFAULT_PSI, DEFAULT_PSI, DEFAULT_PSI};
        private boolean perWheel = false;
        private boolean coldTires = true;

        // Noise tag state
        private final Set<String> selectedTags = new LinkedHashSet<>();

        // Incident state
        private boolean saveClip = true;
        private boolean flagDealer = true;

        // Service bundled state
        private final Set<CarDiary.Category> bundled = new LinkedHashSet<>();

        private LinearLayout classBox;
        private LinearLayout itemBox;
        private LinearLayout dateRow;
        private LinearLayout adaptiveBox;
        private TextView odoHintView;
        private EditText odoInput;
        private EditText costInput;
        private EditText noteInput;
        private AlertDialog dialog;

        Builder(Context c, CarDiary.Category initialCat, CarDiary.PendingJolt jolt, Runnable onSaved) {
            this.c = c;
            this.jolt = jolt;
            this.onSaved = onSaved;
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
                this.selectedGroup = CarDiary.Group.TIRES;
                this.selectedCat = CarDiary.Category.TIRE_PRESSURE;
                this.selectedDate = LocalDate.now();
            }
        }

        AlertDialog show() {
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Style.card(Style.CARD, c));
            int pad = Style.dp(c, 28);
            card.setPadding(pad, pad, pad, pad);

            // Header
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(c);
            title.setText(c.getString(R.string.diary_add_title));
            title.setTextColor(Style.TEXT);
            title.setTextSize(28f);
            title.setTypeface(null, Typeface.BOLD);
            head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(head);

            // Scrollable 2-column body
            ScrollView scroll = new ScrollView(c);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout cols = new LinearLayout(c);
            cols.setOrientation(LinearLayout.HORIZONTAL);
            scroll.addView(cols, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            slp.topMargin = Style.dp(c, 18);
            card.addView(scroll, slp);

            // ---- LEFT COLUMN (42%): Class -> Filtered Items ----
            LinearLayout left = new LinearLayout(c);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 42f));

            left.addView(sectionLabel(c.getString(R.string.diary_pick_class)));
            classBox = new LinearLayout(c);
            classBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = Style.dp(c, 8);
            left.addView(classBox, clp);

            TextView itemLbl = sectionLabel(c.getString(R.string.diary_pick_item));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.topMargin = Style.dp(c, 18);
            left.addView(itemLbl, ilp);

            itemBox = new LinearLayout(c);
            itemBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ibLp.topMargin = Style.dp(c, 8);
            left.addView(itemBox, ibLp);

            // Vertical divider between left & right columns
            View divider = new View(c);
            divider.setBackgroundColor(Style.LIGHT ? 0x1A000000 : 0x18FFFFFF);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                Style.dp(c, 1), ViewGroup.LayoutParams.MATCH_PARENT);
            divLp.leftMargin = Style.dp(c, 22);
            divLp.rightMargin = Style.dp(c, 22);

            // ---- RIGHT COLUMN (58%): Date + Auto-Odo + Adaptive + Cost + Note ----
            LinearLayout right = new LinearLayout(c);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 58f));

            // Date selector
            right.addView(sectionLabel(c.getString(R.string.diary_field_date)));
            dateRow = new LinearLayout(c);
            dateRow.setOrientation(LinearLayout.HORIZONTAL);
            dateRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            drLp.topMargin = Style.dp(c, 8);
            right.addView(dateRow, drLp);

            // Adaptive block
            adaptiveBox = new LinearLayout(c);
            adaptiveBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams adLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            adLp.topMargin = Style.dp(c, 16);
            right.addView(adaptiveBox, adLp);

            // Odometer + Cost side-by-side
            LinearLayout numRow = new LinearLayout(c);
            numRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            numLp.topMargin = Style.dp(c, 16);
            right.addView(numRow, numLp);

            LinearLayout odoCol = new LinearLayout(c);
            odoCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout odoHead = new LinearLayout(c);
            odoHead.setOrientation(LinearLayout.HORIZONTAL);
            odoHead.addView(sectionLabel(c.getString(R.string.diary_field_odo)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            odoHintView = new TextView(c);
            odoHintView.setTextColor(Style.ACCENT);
            odoHintView.setTextSize(14f);
            odoHead.addView(odoHintView);
            odoHead.setPadding(0, 0, 0, Style.dp(c, 5));
            odoCol.addView(odoHead);

            odoInput = styledInput(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
                c.getString(R.string.diary_field_odo_hint));
            odoInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
                @Override public void afterTextChanged(Editable s) {
                    if (!settingOdoProgrammatically) {
                        userEditedOdo = true;
                        odoHintView.setText("");
                    }
                }
            });
            odoCol.addView(odoInput);
            numRow.addView(odoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            numRow.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 16), 1));

            LinearLayout costCol = fieldColumn(c.getString(R.string.diary_field_cost, Prefs.getCurrency(c)));
            costInput = styledInput(InputType.TYPE_CLASS_NUMBER,
                c.getString(R.string.diary_field_cost_hint, Prefs.getCurrency(c)));
            installCentsFormatter(costInput);
            costCol.addView(costInput);
            numRow.addView(costCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // Note field
            LinearLayout noteCol = fieldColumn(c.getString(R.string.diary_field_note));
            noteInput = styledInput(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                c.getString(R.string.diary_field_note_hint));
            if (jolt != null) {
                String pre = jolt.speedKmh != null
                    ? c.getString(R.string.diary_jolt_prefill_note, jolt.maxG(), jolt.speedKmh)
                    : c.getString(R.string.diary_jolt_prefill_note_nospeed, jolt.maxG());
                noteInput.setText(pre);
            }
            noteCol.addView(noteInput);
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noteLp.topMargin = Style.dp(c, 14);
            right.addView(noteCol, noteLp);

            cols.addView(left);
            cols.addView(divider, divLp);
            cols.addView(right);

            // Pinned footer: Cancel + Save
            LinearLayout btnRow = new LinearLayout(c);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            brLp.topMargin = Style.dp(c, 22);
            card.addView(btnRow, brLp);

            TextView cancelBtn = Style.cardButton(c, c.getString(android.R.string.cancel), false,
                () -> { if (dialog != null) dialog.dismiss(); });
            btnRow.addView(cancelBtn);
            btnRow.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 16), 1));
            TextView saveBtn = Style.cardButton(c, c.getString(R.string.diary_btn_save), true, this::save);
            btnRow.addView(saveBtn);

            rebuildClassesAndItems();
            rebuildDateRow();
            refreshOdometerForSelectedDate(true);

            dialog = new AlertDialog.Builder(c).setView(card).create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
            dialog.show();
            if (dialog.getWindow() != null) {
                int w = Math.min(Style.dp(c, 1360),
                    (int) (c.getResources().getDisplayMetrics().widthPixels * 0.88f));
                dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            return dialog;
        }

        private void rebuildClassesAndItems() {
            // 2-column grid of Class pills (no horizontal scrolling)
            classBox.removeAllViews();
            List<View> classPills = new ArrayList<>();
            for (CarDiary.Group g : CarDiary.Group.values()) {
                boolean active = (g == selectedGroup);
                classPills.add(gridChip(c.getString(g.labelRes), active, g == CarDiary.Group.NOTES, () -> {
                    if (selectedGroup == g) return;
                    selectedGroup = g;
                    List<CarDiary.Category> subs = CarDiary.Category.forGroup(g);
                    if (!subs.isEmpty()) selectedCat = subs.get(0);
                    rebuildClassesAndItems();
                }));
            }
            addTwoColumnGrid(classBox, classPills, 10);

            // 2-column grid of Subcategories filtered by selectedGroup
            itemBox.removeAllViews();
            List<View> itemPills = new ArrayList<>();
            for (CarDiary.Category cat : CarDiary.Category.forGroup(selectedGroup)) {
                boolean active = (cat == selectedCat);
                itemPills.add(gridChip(c.getString(cat.labelRes), active,
                    cat == CarDiary.Category.NOISE || cat == CarDiary.Category.INCIDENT, () -> {
                        if (selectedCat == cat) return;
                        selectedCat = cat;
                        rebuildClassesAndItems();
                    }));
            }
            addTwoColumnGrid(itemBox, itemPills, 10);

            rebuildAdaptive();
        }

        private void rebuildDateRow() {
            dateRow.removeAllViews();
            LocalDate today = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);
            boolean isToday = selectedDate.equals(today);
            boolean isYesterday = selectedDate.equals(yesterday);
            boolean isCustom = !isToday && !isYesterday;

            dateRow.addView(dateChip(c.getString(R.string.diary_date_today), isToday, () -> {
                selectedDate = today;
                userEditedOdo = false;
                rebuildDateRow();
                refreshOdometerForSelectedDate(false);
            }));
            dateRow.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 10), 1));

            dateRow.addView(dateChip(c.getString(R.string.diary_date_yesterday), isYesterday, () -> {
                selectedDate = yesterday;
                userEditedOdo = false;
                rebuildDateRow();
                refreshOdometerForSelectedDate(false);
            }));
            dateRow.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 10), 1));

            String pickLabel = isCustom
                ? dateFmt.format(selectedDate) + " ▾"
                : c.getString(R.string.diary_date_pick) + " ▾";
            TextView pickBtn = dateChip(pickLabel, isCustom, this::openDatePicker);
            dateRow.addView(pickBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        private void openDatePicker() {
            DatePickerDialog picker = new DatePickerDialog(
                c,
                (view, year, month, dayOfMonth) -> {
                    selectedDate = LocalDate.of(year, month + 1, dayOfMonth);
                    userEditedOdo = false;
                    rebuildDateRow();
                    refreshOdometerForSelectedDate(false);
                },
                selectedDate.getYear(),
                selectedDate.getMonthValue() - 1,
                selectedDate.getDayOfMonth());
            picker.getDatePicker().setMaxDate(System.currentTimeMillis());
            picker.show();
        }

        private void refreshOdometerForSelectedDate(boolean initial) {
            if (initial && jolt != null && jolt.odoKm != null && jolt.odoKm > 0) {
                setOdoValue(jolt.odoKm, c.getString(R.string.diary_odo_from_car));
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
                odoInput.post(() -> {
                    if (gen != odoLookupGen || userEditedOdo) return;
                    if (res.odoKm != null && res.odoKm > 0) {
                        setOdoValue(res.odoKm, c.getString(
                            res.exact ? R.string.diary_odo_from_car : R.string.diary_odo_estimated));
                    } else {
                        setOdoValue(null, c.getString(R.string.diary_odo_not_recorded));
                    }
                });
            };
            CarActor actor = CarActor.get(app);
            if (actor != null) actor.runOnCarThread(lookup);
            else new Thread(lookup, "diary-odo").start();
        }

        private void setOdoValue(Double km, String hint) {
            settingOdoProgrammatically = true;
            odoInput.setText(km != null ? String.format(Locale.US, "%.0f", km) : "");
            odoHintView.setText(hint != null ? hint : "");
            settingOdoProgrammatically = false;
        }

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
                buildIncidentToggles();
            } else if (selectedCat == CarDiary.Category.SERVICE) {
                adaptiveBox.setVisibility(View.VISIBLE);
                buildServiceBundles();
            } else {
                adaptiveBox.setVisibility(View.GONE);
            }
        }

        private void buildTirePressureControls() {
            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(sectionLabel(c.getString(R.string.diary_field_pressure)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            top.addView(inlineChip(c.getString(R.string.diary_psi_all_four), !perWheel, () -> {
                perWheel = false; rebuildAdaptive();
            }));
            top.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 8), 1));
            top.addView(inlineChip(c.getString(R.string.diary_psi_per_wheel), perWheel, () -> {
                perWheel = true; rebuildAdaptive();
            }));
            top.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 14), 1));
            top.addView(inlineChip(c.getString(R.string.diary_psi_cold), coldTires, () -> {
                coldTires = true; rebuildAdaptive();
            }));
            top.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 8), 1));
            top.addView(inlineChip(c.getString(R.string.diary_psi_warm), !coldTires, () -> {
                coldTires = false; rebuildAdaptive();
            }));
            adaptiveBox.addView(top);

            if (!perWheel) {
                LinearLayout stepper = buildPsiStepper(null, 0, true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Style.dp(c, 10);
                adaptiveBox.addView(stepper, lp);
            } else {
                String[] wheelLabels = {
                    c.getString(R.string.diary_tag_fl), c.getString(R.string.diary_tag_fr),
                    c.getString(R.string.diary_tag_rl), c.getString(R.string.diary_tag_rr)
                };
                List<View> steppers = new ArrayList<>();
                for (int i = 0; i < 4; i++) steppers.add(buildPsiStepper(wheelLabels[i], i, false));
                LinearLayout wrap = new LinearLayout(c);
                wrap.setOrientation(LinearLayout.VERTICAL);
                addTwoColumnGrid(wrap, steppers, 10);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Style.dp(c, 10);
                adaptiveBox.addView(wrap, lp);
            }
        }

        private LinearLayout buildPsiStepper(String wheelLabel, int idx, boolean allFour) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER);
            box.setBackground(Style.card(Style.CARD_HI, c, 14));
            int p = Style.dp(c, 10);
            box.setPadding(p, p, p, p);

            if (wheelLabel != null) {
                TextView wl = new TextView(c);
                wl.setText(wheelLabel);
                wl.setTextColor(Style.TEXT_DIM);
                wl.setTextSize(15f);
                box.addView(wl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }

            TextView val = new TextView(c);
            val.setTextSize(allFour ? 26f : 20f);
            val.setTypeface(null, Typeface.BOLD);
            val.setTextColor(Style.TEXT);
            val.setGravity(Gravity.CENTER);
            val.setText(Style.valueWithUnit(String.valueOf(psi[idx]), Style.TEXT, "PSI",
                Style.UNIT_SCALE_ROW));

            TextView minus = stepButton("−", () -> {
                int next = Math.max(MIN_PSI, psi[idx] - 1);
                if (allFour) { for (int i = 0; i < 4; i++) psi[i] = next; }
                else psi[idx] = next;
                val.setText(Style.valueWithUnit(String.valueOf(psi[idx]), Style.TEXT, "PSI",
                    Style.UNIT_SCALE_ROW));
            });
            TextView plus = stepButton("+", () -> {
                int next = Math.min(MAX_PSI, psi[idx] + 1);
                if (allFour) { for (int i = 0; i < 4; i++) psi[i] = next; }
                else psi[idx] = next;
                val.setText(Style.valueWithUnit(String.valueOf(psi[idx]), Style.TEXT, "PSI",
                    Style.UNIT_SCALE_ROW));
            });

            box.addView(minus);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                Style.dp(c, allFour ? 130 : 88), ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = Style.dp(c, 8);
            vlp.rightMargin = Style.dp(c, 8);
            box.addView(val, vlp);
            box.addView(plus);
            return box;
        }

        private TextView stepButton(String glyph, Runnable onTap) {
            TextView b = new TextView(c);
            b.setText(glyph);
            b.setTextColor(Style.TEXT);
            b.setTextSize(22f);
            b.setTypeface(null, Typeface.BOLD);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Style.card(Style.CARD, c, 24));
            int sz = Style.dp(c, 46);
            b.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            b.setOnClickListener(v -> onTap.run());
            return b;
        }

        private void buildNoiseTags() {
            adaptiveBox.addView(sectionLabel(c.getString(R.string.diary_noise_where)));
            List<View> where = new ArrayList<>();
            for (int res : NOISE_WHERE_RES) {
                String tag = c.getString(res);
                where.add(gridChip(tag, selectedTags.contains(tag), false, () -> {
                    if (!selectedTags.remove(tag)) selectedTags.add(tag);
                    rebuildAdaptive();
                }));
            }
            LinearLayout wWrap = new LinearLayout(c);
            wWrap.setOrientation(LinearLayout.VERTICAL);
            addThreeColumnGrid(wWrap, where, 8);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp1.topMargin = Style.dp(c, 6);
            adaptiveBox.addView(wWrap, lp1);

            TextView l2 = sectionLabel(c.getString(R.string.diary_noise_when));
            LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l2p.topMargin = Style.dp(c, 12);
            adaptiveBox.addView(l2, l2p);

            List<View> when = new ArrayList<>();
            for (int res : NOISE_WHEN_RES) {
                String tag = c.getString(res);
                when.add(gridChip(tag, selectedTags.contains(tag), false, () -> {
                    if (!selectedTags.remove(tag)) selectedTags.add(tag);
                    rebuildAdaptive();
                }));
            }
            LinearLayout whWrap = new LinearLayout(c);
            whWrap.setOrientation(LinearLayout.VERTICAL);
            addThreeColumnGrid(whWrap, when, 8);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = Style.dp(c, 6);
            adaptiveBox.addView(whWrap, lp2);
        }

        private void buildIncidentToggles() {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(Style.card(Style.CARD_HI, c, 14));
            int p = Style.dp(c, 14);
            box.setPadding(p, p, p, p);

            if (jolt != null) {
                TextView jb = new TextView(c);
                jb.setTextColor(Style.WARN);
                jb.setTextSize(16f);
                jb.setTypeface(null, Typeface.BOLD);
                jb.setText(jolt.speedKmh != null
                    ? c.getString(R.string.diary_jolt_banner_detail, jolt.maxG(), jolt.speedKmh)
                    : c.getString(R.string.diary_jolt_prefill_note_nospeed, jolt.maxG()));
                jb.setPadding(0, 0, 0, Style.dp(c, 10));
                box.addView(jb);
            }
            box.addView(switchRow(c.getString(R.string.diary_incident_keep_clip), saveClip,
                on -> saveClip = on));
            box.addView(switchRow(c.getString(R.string.diary_incident_flag_dealer), flagDealer,
                on -> flagDealer = on));
            adaptiveBox.addView(box);
        }

        private void buildServiceBundles() {
            adaptiveBox.addView(sectionLabel(c.getString(R.string.diary_service_done_items)));
            List<View> items = new ArrayList<>();
            for (CarDiary.Category item : CarDiary.SERVICE_BUNDLABLE) {
                boolean checked = bundled.contains(item);
                items.add(gridChip((checked ? "✓ " : "") + c.getString(item.labelRes),
                    checked, false, () -> {
                        if (!bundled.remove(item)) bundled.add(item);
                        rebuildAdaptive();
                    }));
            }
            LinearLayout wrap = new LinearLayout(c);
            wrap.setOrientation(LinearLayout.VERTICAL);
            addTwoColumnGrid(wrap, items, 8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Style.dp(c, 6);
            adaptiveBox.addView(wrap, lp);
        }

        private LinearLayout switchRow(String label, boolean initial, GeelySwitch.OnToggle cb) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Style.dp(c, 6), 0, Style.dp(c, 6));
            TextView lbl = new TextView(c);
            lbl.setText(label);
            lbl.setTextColor(Style.TEXT);
            lbl.setTextSize(18f);
            row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            GeelySwitch sw = new GeelySwitch(c);
            sw.setLockSeconds(0);
            sw.setCheckedSilently(initial);
            sw.setOnToggle(cb);
            row.addView(sw);
            return row;
        }

        private void save() {
            Double odo = parsePositiveDouble(odoInput.getText().toString());
            long cents = ChargeCostDialog.centsFromText(costInput.getText().toString());
            Double cost = cents > 0 ? (cents / 100.0) : null;
            String note = noteInput.getText().toString().trim();
            if (note.isEmpty()) note = null;

            long tsMs;
            if (jolt != null && selectedCat == CarDiary.Category.INCIDENT
                    && selectedDate.equals(Instant.ofEpochMilli(jolt.tsMs)
                        .atZone(ZoneId.systemDefault()).toLocalDate())) {
                tsMs = jolt.tsMs;
            } else if (selectedDate.equals(LocalDate.now())) {
                tsMs = System.currentTimeMillis();
            } else {
                tsMs = selectedDate.atTime(12, 0).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            }

            Double outC = selectedDate.equals(LocalDate.now()) ? CarDiary.latestOutsideTempC() : null;

            String tags = null;
            String metaJson = null;
            if (selectedCat == CarDiary.Category.TIRE_PRESSURE) {
                metaJson = CarDiary.buildTirePressureMeta(psi[0], psi[1], psi[2], psi[3], coldTires);
            } else if (selectedCat == CarDiary.Category.NOISE && !selectedTags.isEmpty()) {
                tags = String.join(", ", selectedTags);
            } else if (selectedCat == CarDiary.Category.SERVICE && !bundled.isEmpty()) {
                metaJson = CarDiary.buildServiceBundledMeta(new ArrayList<>(bundled));
            } else if (selectedCat == CarDiary.Category.INCIDENT && jolt != null) {
                metaJson = CarDiary.buildJoltMeta(jolt.maxG(), jolt.speedKmh);
            }

            if (selectedCat == CarDiary.Category.INCIDENT) {
                Double lat = jolt != null ? jolt.lat : null;
                Double lon = jolt != null ? jolt.lon : null;
                CarDiary.insertIncidentWithOptionalClip(
                    c, tsMs, saveClip, flagDealer, odo, outC, lat, lon, cost,
                    null, note, tags, metaJson, jolt != null ? jolt.id : null, onSaved);
            } else {
                String status = selectedCat == CarDiary.Category.NOISE
                    ? CarDiary.STATUS_OPEN : CarDiary.STATUS_DONE;
                CarDiary.insert(c, selectedCat, status, tsMs, odo, outC, cost,
                    null, note, tags, null, null, metaJson, onSaved);
            }
            if (dialog != null) dialog.dismiss();
        }

        private void addTwoColumnGrid(LinearLayout host, List<View> views, int gapDp) {
            addNColumnGrid(host, views, 2, gapDp);
        }

        private void addThreeColumnGrid(LinearLayout host, List<View> views, int gapDp) {
            addNColumnGrid(host, views, 3, gapDp);
        }

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

        private TextView gridChip(String label, boolean active, boolean warnDot, Runnable onTap) {
            TextView tv = new TextView(c);
            tv.setText(warnDot && !active ? "● " + label : label);
            tv.setTextSize(17f);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setTextColor(active ? Style.onFill(Style.ACCENT) : Style.TEXT);
            tv.setBackground(Style.card(active ? Style.ACCENT : Style.CARD_HI, c, 16));
            int h = Style.dp(c, 12);
            int v = Style.dp(c, 12);
            tv.setPadding(h, v, h, v);
            tv.setMinHeight(Style.dp(c, 50));
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setOnClickListener(view -> onTap.run());
            return tv;
        }

        private TextView dateChip(String label, boolean active, Runnable onTap) {
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextSize(17f);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setTextColor(active ? Style.onFill(Style.ACCENT) : Style.TEXT);
            tv.setBackground(Style.card(active ? Style.ACCENT : Style.CARD_HI, c, 18));
            int h = Style.dp(c, 18);
            int v = Style.dp(c, 11);
            tv.setPadding(h, v, h, v);
            tv.setMinHeight(Style.dp(c, 48));
            tv.setGravity(Gravity.CENTER);
            tv.setOnClickListener(view -> onTap.run());
            return tv;
        }

        private TextView inlineChip(String label, boolean active, Runnable onTap) {
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setTextSize(15f);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setTextColor(active ? Style.onFill(Style.ACCENT) : Style.TEXT);
            tv.setBackground(Style.card(active ? Style.ACCENT : Style.CARD_HI, c, 18));
            int h = Style.dp(c, 14);
            int v = Style.dp(c, 8);
            tv.setPadding(h, v, h, v);
            tv.setMinHeight(Style.dp(c, 42));
            tv.setGravity(Gravity.CENTER);
            tv.setOnClickListener(view -> onTap.run());
            return tv;
        }

        private TextView sectionLabel(String s) {
            TextView t = new TextView(c);
            t.setText(s);
            t.setTextColor(Style.TEXT_DIM);
            t.setTextSize(15f);
            t.setTypeface(null, Typeface.BOLD);
            return t;
        }

        private LinearLayout fieldColumn(String label) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView l = sectionLabel(label);
            l.setPadding(0, 0, 0, Style.dp(c, 5));
            col.addView(l);
            return col;
        }

        private EditText styledInput(int inputType, String hint) {
            EditText e = new EditText(c);
            e.setInputType(inputType);
            e.setHint(hint);
            e.setTextColor(Style.TEXT);
            e.setHintTextColor(Style.TEXT_DIM);
            e.setTextSize(19f);
            e.setBackground(Style.card(Style.CARD_HI, c, 12));
            int p = Style.dp(c, 12);
            e.setPadding(p, p, p, p);
            e.setSingleLine(true);
            return e;
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

        private static Double parsePositiveDouble(String raw) {
            if (raw == null || raw.trim().isEmpty()) return null;
            try {
                double v = Double.parseDouble(raw.trim().replace(',', '.'));
                return v > 0 ? v : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
