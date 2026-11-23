package com.geely.drivemem.ui;

import android.app.AlertDialog;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.controls.GeelySwitch;
import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Modal dialog for adding a Car Diary entry, modeled on {@link ChargeCostDialog}.
 *
 * Uses a 2-level Category -> Subcategory drill-down (5 top-level groups in Row 1,
 * sub-items in Row 2), adaptive fields per subcategory, and a universal optional
 * Cost field across every event. Park-gated because it accepts keyboard input. */
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

        private CarDiary.Group selectedGroup;
        private CarDiary.Category selectedCat;

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

        private LinearLayout groupRow;
        private LinearLayout subRow;
        private LinearLayout adaptiveBox;
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
            } else if (initialCat != null && initialCat.userPickable) {
                this.selectedCat = initialCat;
                this.selectedGroup = initialCat.group;
            } else {
                this.selectedGroup = CarDiary.Group.TIRES;
                this.selectedCat = CarDiary.Category.TIRE_PRESSURE;
            }
        }

        AlertDialog show() {
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Style.card(Style.CARD, c));
            int pad = Style.dp(c, 26);
            card.setPadding(pad, pad, pad, pad);

            // Header: "Add entry" + "Today · 14,820 km"
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(c);
            title.setText(c.getString(R.string.diary_add_title));
            title.setTextColor(Style.TEXT);
            title.setTextSize(26f);
            title.setTypeface(null, Typeface.BOLD);
            head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Double curOdo = (jolt != null && jolt.odoKm != null) ? jolt.odoKm : CarDiary.latestOdometerKm(c);
            long stampMs = (jolt != null) ? jolt.tsMs : System.currentTimeMillis();
            TextView meta = new TextView(c);
            meta.setTextSize(17f);
            meta.setTextColor(Style.TEXT_DIM);
            String dateLbl = new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(stampMs));
            if (curOdo != null && curOdo > 0) {
                meta.setText(dateLbl + "  ·  " + String.format(Locale.getDefault(), "%,.0f km", curOdo));
            } else {
                meta.setText(dateLbl);
            }
            head.addView(meta);
            card.addView(head);

            // Scrollable body for comfortable fit on 1080p even with keyboard open
            ScrollView scroll = new ScrollView(c);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Style.dp(c, 16);
            card.addView(scroll, slp);

            // Row 1: Top-level Groups
            groupRow = new LinearLayout(c);
            groupRow.setOrientation(LinearLayout.HORIZONTAL);
            HorizontalScrollView gScroll = new HorizontalScrollView(c);
            gScroll.setHorizontalScrollBarEnabled(false);
            gScroll.addView(groupRow);
            body.addView(gScroll);

            // Row 2: Subcategories for selected group
            subRow = new LinearLayout(c);
            subRow.setOrientation(LinearLayout.HORIZONTAL);
            HorizontalScrollView sScroll = new HorizontalScrollView(c);
            sScroll.setHorizontalScrollBarEnabled(false);
            sScroll.addView(subRow);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = Style.dp(c, 10);
            body.addView(sScroll, subLp);

            // Adaptive middle block (PSI stepper / Noise tags / Incident toggles / Service bundles)
            adaptiveBox = new LinearLayout(c);
            adaptiveBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams adLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            adLp.topMargin = Style.dp(c, 16);
            body.addView(adaptiveBox, adLp);

            // Universal fields row: Odometer + Cost (on EVERY entry)
            LinearLayout numRow = new LinearLayout(c);
            numRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            numLp.topMargin = Style.dp(c, 16);
            body.addView(numRow, numLp);

            LinearLayout odoCol = fieldColumn(c.getString(R.string.diary_field_odo));
            odoInput = styledInput(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
                c.getString(R.string.diary_field_odo_hint));
            if (curOdo != null && curOdo > 0) {
                odoInput.setText(String.format(Locale.US, "%.0f", curOdo));
            }
            odoCol.addView(odoInput);
            numRow.addView(odoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View hGap = new View(c);
            numRow.addView(hGap, new LinearLayout.LayoutParams(Style.dp(c, 14), 1));

            LinearLayout costCol = fieldColumn(c.getString(R.string.diary_field_cost, Prefs.getCurrency(c)));
            costInput = styledInput(InputType.TYPE_CLASS_NUMBER,
                c.getString(R.string.diary_field_cost_hint, Prefs.getCurrency(c)));
            installCentsFormatter(costInput);
            costCol.addView(costInput);
            numRow.addView(costCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // Universal Note field
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
            noteLp.topMargin = Style.dp(c, 12);
            body.addView(noteCol, noteLp);

            // Footer buttons: Cancel + Save
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

            View bGap = new View(c);
            btnRow.addView(bGap, new LinearLayout.LayoutParams(Style.dp(c, 14), 1));

            TextView saveBtn = Style.cardButton(c, c.getString(R.string.diary_btn_save), true, this::save);
            btnRow.addView(saveBtn);

            rebuildChipsAndAdaptive();

            dialog = new AlertDialog.Builder(c).setView(card).create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(Style.dp(c, 820), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            return dialog;
        }

        private void rebuildChipsAndAdaptive() {
            groupRow.removeAllViews();
            for (CarDiary.Group g : CarDiary.Group.values()) {
                boolean active = (g == selectedGroup);
                TextView chip = pillChip(c.getString(g.labelRes), active, g == CarDiary.Group.NOTES, () -> {
                    if (selectedGroup == g) return;
                    selectedGroup = g;
                    List<CarDiary.Category> subs = CarDiary.Category.forGroup(g);
                    if (!subs.isEmpty()) selectedCat = subs.get(0);
                    rebuildChipsAndAdaptive();
                });
                addWithGap(groupRow, chip, 10);
            }

            subRow.removeAllViews();
            for (CarDiary.Category cat : CarDiary.Category.forGroup(selectedGroup)) {
                boolean active = (cat == selectedCat);
                TextView chip = pillChip(c.getString(cat.labelRes), active,
                    cat == CarDiary.Category.NOISE || cat == CarDiary.Category.INCIDENT, () -> {
                        if (selectedCat == cat) return;
                        selectedCat = cat;
                        rebuildChipsAndAdaptive();
                    });
                addWithGap(subRow, chip, 10);
            }

            rebuildAdaptive();
        }

        private void rebuildAdaptive() {
            adaptiveBox.removeAllViews();
            if (selectedCat == CarDiary.Category.TIRE_PRESSURE) {
                buildTirePressureControls();
            } else if (selectedCat == CarDiary.Category.NOISE) {
                buildNoiseTags();
            } else if (selectedCat == CarDiary.Category.INCIDENT) {
                buildIncidentToggles();
            } else if (selectedCat == CarDiary.Category.SERVICE) {
                buildServiceBundles();
            }
        }

        private void buildTirePressureControls() {
            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView lbl = sectionLabel(c.getString(R.string.diary_field_pressure));
            top.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView allBtn = pillChip(c.getString(R.string.diary_psi_all_four), !perWheel, false, () -> {
                perWheel = false;
                rebuildAdaptive();
            });
            TextView perBtn = pillChip(c.getString(R.string.diary_psi_per_wheel), perWheel, false, () -> {
                perWheel = true;
                rebuildAdaptive();
            });
            addWithGap(top, allBtn, 8);
            addWithGap(top, perBtn, 8);

            TextView coldBtn = pillChip(c.getString(R.string.diary_psi_cold), coldTires, false, () -> {
                coldTires = true;
                rebuildAdaptive();
            });
            TextView warmBtn = pillChip(c.getString(R.string.diary_psi_warm), !coldTires, false, () -> {
                coldTires = false;
                rebuildAdaptive();
            });
            addWithGap(top, coldBtn, 16);
            addWithGap(top, warmBtn, 8);
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
                LinearLayout r1 = new LinearLayout(c);
                r1.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout r2 = new LinearLayout(c);
                r2.setOrientation(LinearLayout.HORIZONTAL);
                r1.addView(buildPsiStepper(wheelLabels[0], 0, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                r1.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 12), 1));
                r1.addView(buildPsiStepper(wheelLabels[1], 1, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                r2.addView(buildPsiStepper(wheelLabels[2], 2, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                r2.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 12), 1));
                r2.addView(buildPsiStepper(wheelLabels[3], 3, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp1.topMargin = Style.dp(c, 10);
                adaptiveBox.addView(r1, lp1);
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp2.topMargin = Style.dp(c, 10);
                adaptiveBox.addView(r2, lp2);
            }
        }

        private LinearLayout buildPsiStepper(String wheelLabel, int idx, boolean allFour) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER);
            box.setBackground(Style.card(Style.CARD_HI, c, 14));
            int p = Style.dp(c, 12);
            box.setPadding(p, p, p, p);

            if (wheelLabel != null) {
                TextView wl = new TextView(c);
                wl.setText(wheelLabel);
                wl.setTextColor(Style.TEXT_DIM);
                wl.setTextSize(16f);
                box.addView(wl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }

            TextView val = new TextView(c);
            val.setTextSize(allFour ? 26f : 22f);
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
                Style.dp(c, allFour ? 130 : 96), ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = Style.dp(c, 10);
            vlp.rightMargin = Style.dp(c, 10);
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
            int sz = Style.dp(c, 48);
            b.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            b.setOnClickListener(v -> onTap.run());
            return b;
        }

        private void buildNoiseTags() {
            adaptiveBox.addView(sectionLabel(c.getString(R.string.diary_noise_where)));
            LinearLayout r1 = new LinearLayout(c);
            r1.setOrientation(LinearLayout.HORIZONTAL);
            for (int res : NOISE_WHERE_RES) {
                String tag = c.getString(res);
                addWithGap(r1, pillChip(tag, selectedTags.contains(tag), false, () -> {
                    if (!selectedTags.remove(tag)) selectedTags.add(tag);
                    rebuildAdaptive();
                }), 8);
            }
            HorizontalScrollView s1 = new HorizontalScrollView(c);
            s1.setHorizontalScrollBarEnabled(false);
            s1.addView(r1);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp1.topMargin = Style.dp(c, 6);
            adaptiveBox.addView(s1, lp1);

            TextView l2 = sectionLabel(c.getString(R.string.diary_noise_when));
            LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l2p.topMargin = Style.dp(c, 12);
            adaptiveBox.addView(l2, l2p);

            LinearLayout r2 = new LinearLayout(c);
            r2.setOrientation(LinearLayout.HORIZONTAL);
            for (int res : NOISE_WHEN_RES) {
                String tag = c.getString(res);
                addWithGap(r2, pillChip(tag, selectedTags.contains(tag), false, () -> {
                    if (!selectedTags.remove(tag)) selectedTags.add(tag);
                    rebuildAdaptive();
                }), 8);
            }
            HorizontalScrollView s2 = new HorizontalScrollView(c);
            s2.setHorizontalScrollBarEnabled(false);
            s2.addView(r2);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = Style.dp(c, 6);
            adaptiveBox.addView(s2, lp2);
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
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (CarDiary.Category item : CarDiary.SERVICE_BUNDLABLE) {
                boolean checked = bundled.contains(item);
                addWithGap(r, pillChip((checked ? "✓ " : "") + c.getString(item.labelRes),
                    checked, false, () -> {
                        if (!bundled.remove(item)) bundled.add(item);
                        rebuildAdaptive();
                    }), 8);
            }
            HorizontalScrollView sv = new HorizontalScrollView(c);
            sv.setHorizontalScrollBarEnabled(false);
            sv.addView(r);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Style.dp(c, 6);
            adaptiveBox.addView(sv, lp);
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

            long tsMs = (jolt != null && selectedCat == CarDiary.Category.INCIDENT)
                ? jolt.tsMs : System.currentTimeMillis();
            Double outC = CarDiary.latestOutsideTempC();

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

        private TextView pillChip(String label, boolean active, boolean warnDot, Runnable onTap) {
            TextView tv = new TextView(c);
            tv.setText(warnDot && !active ? "● " + label : label);
            tv.setTextSize(17f);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setTextColor(active ? Style.onFill(Style.ACCENT) : Style.TEXT);
            tv.setBackground(Style.card(active ? Style.ACCENT : Style.CARD_HI, c, 22));
            int h = Style.dp(c, 18);
            int v = Style.dp(c, 11);
            tv.setPadding(h, v, h, v);
            tv.setMinHeight(Style.dp(c, 46));
            tv.setGravity(Gravity.CENTER);
            tv.setOnClickListener(view -> onTap.run());
            return tv;
        }

        private void addWithGap(LinearLayout row, View child, int gapDp) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row.getChildCount() > 0) lp.leftMargin = Style.dp(c, gapDp);
            row.addView(child, lp);
        }

        private TextView sectionLabel(String s) {
            TextView t = new TextView(c);
            t.setText(s);
            t.setTextColor(Style.TEXT_DIM);
            t.setTextSize(15f);
            return t;
        }

        private LinearLayout fieldColumn(String label) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView l = sectionLabel(label);
            l.setPadding(0, 0, 0, Style.dp(c, 4));
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
