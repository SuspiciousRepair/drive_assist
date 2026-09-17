package com.geely.drivemem.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.controls.GeelySwitch;
import com.geely.drivemem.util.Clips;
import com.geely.drivemem.util.DashcamSettings;
import com.geely.drivemem.util.Style;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dashcam settings and a recycled gallery. Clip scans and image decoding stay off the UI thread. */
@android.annotation.SuppressLint("ViewConstructor") // Created with its Activity, never inflated from XML.
public final class DashcamPanel extends LinearLayout {
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Gallery adapter = new Gallery();
    private final List<TextView> durationButtons = new ArrayList<>();
    private TextView recordState;
    private TextView storageButton;
    private TextView storageState;
    private TextView usage;
    private TextView empty;
    private GeelySwitch recordSwitch;
    private List<DashcamSettings.StorageOption> storageOptions = Collections.emptyList();
    private PopupWindow storagePopup;
    private AlertDialog clipDialog;
    private boolean active;
    private boolean loading;
    private int generation;
    private final Runnable refresh = () -> {
        if (active) {
            loadClips();
        }
    };

    public DashcamPanel(Activity activity) {
        super(activity);
        this.activity = activity;
        prefs = activity.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        setPadding(dp(32), Style.statusBarHeight(activity) + dp(24), dp(32), dp(20));

        LinearLayout heading = row();
        heading.addView(Style.title(activity, s(R.string.clips_title)), weight());
        TextView preview = button(s(R.string.dash_preview_open), () -> activity.startActivity(
            new Intent(activity, DashcamPreviewActivity.class)));
        LayoutParams previewLp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(54));
        previewLp.rightMargin = dp(12);
        heading.addView(preview, previewLp);
        heading.addView(button(s(R.string.dash_refresh), () -> loadClips()));
        addView(heading);

        LinearLayout settings = row();
        LinearLayout recording = card();
        LinearLayout state = row();
        state.addView(label(s(R.string.clips_record), 24, Style.TEXT), weight());
        recordSwitch = new GeelySwitch(activity);
        recordSwitch.setOnToggle(on -> {
            activity.sendBroadcast(DashcamSettings.configurationIntent(activity).putExtra("on", on ? 1 : 0));
            recordState.setText(s(R.string.dash_waiting_recorder));
            ui.removeCallbacks(refresh);
            ui.postDelayed(refresh, 1800);
        });
        state.addView(recordSwitch);
        recording.addView(state);
        recordState = label(s(R.string.dash_loading), 18, Style.TEXT_DIM);
        recording.addView(recordState);
        TextView duration = label(s(R.string.dash_segment_length), 20, Style.TEXT);
        duration.setPadding(0, dp(12), 0, dp(6));
        recording.addView(duration);
        LinearLayout choices = row();
        for (int minutes : DashcamSettings.SEGMENT_MINUTES) {
            TextView choice = button(s(R.string.dash_minutes, minutes), () -> {
                DashcamSettings.setSegmentMinutes(activity, minutes);
                paintDuration();
                Toast.makeText(activity, s(R.string.dash_next_clip), Toast.LENGTH_SHORT).show();
            });
            choice.setTag(minutes);
            LinearLayout.LayoutParams lp = new LayoutParams(0, dp(56), 1);
            lp.setMargins(0, 0, dp(8), 0);
            choices.addView(choice, lp);
            durationButtons.add(choice);
        }
        recording.addView(choices);
        paintDuration();

        LinearLayout budget = row();
        budget.setPadding(0, dp(12), 0, 0);
        budget.addView(label(s(R.string.clips_limit_label), 18, Style.TEXT_DIM), weight());
        EditText limit = new EditText(activity);
        limit.setTypeface(Style.font(activity));
        limit.setTextSize(22);
        limit.setTextColor(Style.TEXT);
        limit.setSingleLine(true);
        limit.setInputType(InputType.TYPE_CLASS_NUMBER);
        limit.setContentDescription(s(R.string.clips_limit_label));
        limit.setText(String.valueOf(prefs.getInt("dashcam_limit_gb", 10)));
        limit.setBackground(Style.card(Style.CARD_HI, activity));
        limit.setPadding(dp(12), 0, dp(12), 0);
        budget.addView(limit, new LayoutParams(dp(100), dp(56)));
        TextView save = button(s(R.string.clips_limit_save), () -> {
            int gb;
            try {
                gb = Integer.parseInt(limit.getText().toString().trim());
            } catch (NumberFormatException e) {
                gb = 0;
            }
            if (gb < 1 || gb > 500) {
                limit.setError(s(R.string.dash_budget_range));
                return;
            }
            prefs.edit().putInt("dashcam_limit_gb", gb).apply();
            DashcamSettings.sendConfiguration(activity);
            limit.clearFocus();
            Toast.makeText(activity, s(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
        });
        save.setBackground(Style.card(0xFF009B46, activity));
        save.setTextColor(0xFFFFFFFF);
        LayoutParams saveLp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(56));
        saveLp.leftMargin = dp(10);
        budget.addView(save, saveLp);
        recording.addView(budget);
        settings.addView(recording, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

        LinearLayout storage = card();
        storage.addView(label(s(R.string.dash_storage_title), 24, Style.TEXT));
        storageButton = button(s(R.string.dash_loading), this::showStorage);
        storageButton.setGravity(Gravity.CENTER_VERTICAL);
        storageButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        storageButton.setMinHeight(dp(60));
        storageButton.setContentDescription(s(R.string.dash_storage_title));
        storageButton.setBackground(Style.card(Style.CARD_HI, activity));
        android.graphics.drawable.Drawable chevron = activity.getDrawable(R.drawable.ic_chevron_down).mutate();
        chevron.setTint(Style.TEXT_DIM);
        chevron.setBounds(0, 0, dp(24), dp(24));
        storageButton.setCompoundDrawablesRelative(null, null, chevron, null);
        storageButton.setCompoundDrawablePadding(dp(12));
        LayoutParams selectLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        selectLp.topMargin = dp(12);
        storage.addView(storageButton, selectLp);
        storageState = label(s(R.string.dash_loading), 18, Style.TEXT_DIM);
        storageState.setPadding(0, dp(12), 0, dp(8));
        storage.addView(storageState);
        storage.addView(label(s(R.string.dash_next_clip), 18, Style.TEXT_DIM));
        TextView loop = label(s(R.string.dash_loop_title), 20, Style.TEXT);
        loop.setPadding(0, dp(16), 0, dp(4));
        storage.addView(loop);
        storage.addView(label(s(R.string.dash_loop_hint), 18, Style.TEXT_DIM));
        LayoutParams storageLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        storageLp.leftMargin = dp(18);
        settings.addView(storage, storageLp);
        LayoutParams settingsLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        settingsLp.topMargin = dp(10);
        addView(settings, settingsLp);

        LinearLayout parked = row();
        parked.setPadding(0, dp(6), 0, 0);
        parked.addView(label(s(R.string.cfg_park_monitor_label), 20, Style.TEXT_DIM), weight());
        GeelySwitch monitor = new GeelySwitch(activity);
        monitor.setCheckedSilently(prefs.getBoolean("parked_monitoring", false));
        monitor.setOnToggle(enabled -> {
            prefs.edit().putBoolean("parked_monitoring", enabled).apply();
            activity.sendBroadcast(new Intent("com.geely.modehelper.PARKED_MONITORING")
                .setClassName("com.geely.modehelper", "com.geely.modehelper.ParkedMonitoringReceiver")
                .putExtra("on", enabled ? 1 : 0));
        });
        parked.addView(monitor);
        addView(parked);

        usage = label(s(R.string.dash_loading), 18, Style.TEXT_DIM);
        usage.setPadding(0, dp(4), 0, dp(12));
        addView(usage);
        FrameLayout gallery = new FrameLayout(activity);
        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setDividerHeight(dp(10));
        list.setAdapter(adapter);
        gallery.addView(list, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        empty = label(s(R.string.dash_loading), 22, Style.TEXT_DIM);
        empty.setGravity(Gravity.CENTER);
        gallery.addView(empty, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        addView(gallery, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
    }

    private int dp(int value) {
        return Style.dp(activity, value);
    }
    private String s(int id, Object... args) {
        return activity.getString(id, args);
    }
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(20), dp(16), dp(20), dp(16));
        card.setBackground(Style.card(Style.CARD, activity));
        return card;
    }
    private LayoutParams weight() {
        return new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
    }
    private TextView label(String text, int size, int color) {
        TextView view = Style.label(activity, text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private TextView button(String text, Runnable action) {
        TextView view = Style.cardButton(activity, text, false, action);
        view.setTypeface(Style.font(activity));
        view.setTextSize(20);
        view.setMinHeight(dp(52));
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        return view;
    }
    private void paintDuration() {
        int selected = DashcamSettings.segmentMinutes(activity);
        for (TextView button : durationButtons) {
            boolean on = ((Integer) button.getTag()) == selected;
            int fill = on ? Style.CARD_ON : Style.CARD_HI;
            button.setSelected(on);
            button.setTextColor(Style.onFill(fill));
            button.setBackground(Style.card(fill, activity));
        }
    }

    private String storageName(DashcamSettings.StorageOption option) {
        return option.removable ? s(R.string.dash_usb, option.id) : s(R.string.dash_internal);
    }
    private void showStorage() {
        if (storagePopup != null) {
            storagePopup.dismiss();
        }
        LinearLayout items = card();
        String preferred = DashcamSettings.preferredStorage(activity);
        for (DashcamSettings.StorageOption option : storageOptions) {
            TextView item = button(storageName(option), () -> {
                DashcamSettings.setStorage(activity, option.id);
                storageButton.setText(storageName(option));
                storagePopup.dismiss();
                generation++;
                loading = false;
                loadClips();
            });
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinHeight(dp(60));
            if (option.id.equals(preferred)) {
                android.graphics.drawable.Drawable check = activity.getDrawable(R.drawable.ic_language_check).mutate();
                check.setTint(Style.GOOD);
                check.setBounds(0, 0, dp(26), dp(26));
                item.setCompoundDrawablesRelative(null, null, check, null);
                item.setCompoundDrawablePadding(dp(18));
                item.setSelected(true);
            }
            items.addView(item, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        if (storageOptions.size() < 2) {
            TextView note = label(s(R.string.dash_usb_missing), 18, Style.TEXT_DIM);
            note.setPadding(dp(18), dp(14), dp(18), dp(8));
            items.addView(note);
        }
        TextView formats = label(s(R.string.dash_usb_filesystems), 18, Style.TEXT_DIM);
        formats.setPadding(dp(18), dp(12), dp(18), dp(8));
        items.addView(formats);
        storagePopup = new PopupWindow(items, storageButton.getWidth(), LayoutParams.WRAP_CONTENT, true);
        storagePopup.setBackgroundDrawable(Style.card(Style.CARD, activity));
        storagePopup.setOutsideTouchable(true);
        storagePopup.setElevation(dp(8));
        storagePopup.showAsDropDown(storageButton, 0, dp(6));
    }

    public void start() {
        if (!active) {
            active = true;
            loadClips();
        }
    }
    public void stop() {
        active = false;
        generation++;
        loading = false;
        ui.removeCallbacks(refresh);
        if (storagePopup != null) {
            storagePopup.dismiss();
        }
        if (clipDialog != null) {
            clipDialog.dismiss();
        }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }
    @Override protected void onDetachedFromWindow() {
        stop();
        files.shutdown();
        images.shutdownNow();
        thumbnails.evictAll();
        super.onDetachedFromWindow();
    }

    @android.annotation.SuppressLint("UsableSpace") // Display current free bytes; do not evict other apps' caches.
    private void loadClips() {
        if (!active || loading) {
            return;
        }
        loading = true;
        ui.removeCallbacks(refresh);
        int request = generation;
        files.execute(() -> {
            try {
                List<Clips.Clip> clips = Clips.list(activity);
                List<DashcamSettings.StorageOption> options = DashcamSettings.storageOptions(activity);
                DashcamSettings.StorageOption resolved = DashcamSettings.resolveStorage(activity);
                boolean fallback = DashcamSettings.isStorageFallback(activity);
                long used = Clips.usedBytes(activity);
                long held = Clips.heldBytes(activity);
                File spaceRoot = resolved.directory;
                while (!spaceRoot.exists() && spaceRoot.getParentFile() != null) {
                    spaceRoot = spaceRoot.getParentFile();
                }
                long free = spaceRoot.getUsableSpace();
                Map<String, String> imageKeys = new HashMap<>();
                Set<String> pendingHolds = new HashSet<>();
                Set<String> writableClips = new HashSet<>();
                for (Clips.Clip clip : clips) {
                    try {
                        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(clip.mp4))
                                && clip.mp4.getParentFile().canWrite()) {
                            writableClips.add(clip.mp4.getAbsolutePath());
                        }
                    } catch (IllegalArgumentException | SecurityException unavailable) {
                        // A drive can disappear after the library scan.
                    }
                    if (clip.thumb.isFile()) {
                        imageKeys.put(clip.mp4.getAbsolutePath(),
                            clip.thumb.getAbsolutePath() + ":" + clip.thumb.lastModified());
                    }
                    if (clip.kind == Clips.Kind.RECORDING && Clips.isPending(activity, clip)) {
                        pendingHolds.add(clip.mp4.getAbsolutePath());
                    }
                }
                boolean helper;
                try {
                    activity.getPackageManager().getPackageInfo("com.geely.modehelper", 0);
                    helper = true;
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    helper = false;
                }
                final boolean hasHelper = helper;
                ui.post(() -> {
                    if (!active || request != generation) {
                        return;
                    }
                    loading = false;
                    storageOptions = options;
                    boolean recording = false;
                    for (Clips.Clip clip : clips) {
                        if (clip.kind == Clips.Kind.RECORDING) {
                            recording = true;
                        }
                    }
                    recordSwitch.setCheckedSilently(recording);
                    recordSwitch.setEnabled(hasHelper);
                    recordState.setText(s(recording ? R.string.clips_recording
                        : hasHelper ? R.string.dash_stopped : R.string.dash_helper_missing));
                    recordState.setTextColor(recording ? Style.GOOD : Style.TEXT_DIM);
                    storageButton.setText(fallback ? s(R.string.dash_usb_unavailable,
                        DashcamSettings.preferredStorage(activity)) : storageName(resolved));
                    String space = s(R.string.dash_free_space, storageName(resolved), Clips.mb(free));
                    storageState.setText(fallback ? s(R.string.dash_storage_fallback, space) : space);
                    usage.setText(s(R.string.dash_library_usage, clips.size(), Clips.mb(used), Clips.mb(held)));
                    empty.setText(s(R.string.clips_none));
                    adapter.replace(clips, imageKeys, pendingHolds, writableClips);
                    ui.postDelayed(refresh, 5000);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (!active || request != generation) {
                        return;
                    }
                    loading = false;
                    empty.setText(s(R.string.dash_load_failed));
                    usage.setText(s(R.string.dash_load_failed));
                    ui.postDelayed(refresh, 5000);
                });
            }
        });
    }

    private void mutate(Runnable operation) {
        if (!active || files.isShutdown()) {
            return;
        }
        generation++;
        loading = false;
        files.execute(() -> {
            try {
                operation.run();
            } catch (Exception e) {
                ui.post(() -> {
                    if (active) {
                        Toast.makeText(activity, s(R.string.dash_file_failed), Toast.LENGTH_LONG).show();
                    }
                });
            }
            ui.post(() -> {
                if (active) {
                    loadClips();
                }
            });
        });
    }

    private final class Gallery extends BaseAdapter {
        private List<Clips.Clip> clips = Collections.emptyList();
        private Map<String, String> imageKeys = Collections.emptyMap();
        private Set<String> pendingHolds = Collections.emptySet();
        private Set<String> writableClips = Collections.emptySet();
        void replace(List<Clips.Clip> values, Map<String, String> keys, Set<String> pending,
                     Set<String> writable) {
            clips = values;
            imageKeys = keys;
            pendingHolds = pending;
            writableClips = writable;
            notifyDataSetChanged();
        }
        @Override public int getCount() {
            return clips.size();
        }
        @Override public Object getItem(int position) {
            return clips.get(position);
        }
        @Override public long getItemId(int position) {
            return position;
        }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                holder = new Holder();
                convertView = holder.root;
                convertView.setTag(holder);
            } else {
                holder = (Holder) convertView.getTag();
            }
            holder.bind(clips.get(position));
            return convertView;
        }
    }

    private final class Holder {
        final LinearLayout root = row();
        final ImageView thumbnail = new ImageView(activity);
        final TextView title = label("", 24, Style.TEXT);
        final TextView detail = label("", 19, Style.TEXT_DIM);
        final TextView state = label("", 18, Style.TEXT_DIM);
        final TextView play = button(s(R.string.clips_play), null);
        final TextView keep = button(s(R.string.clips_hold), null);
        final TextView delete = button(s(R.string.clips_delete), null);
        String imageKey;
        Holder() {
            root.setPadding(dp(16), dp(14), dp(16), dp(14));
            root.setMinimumHeight(dp(128));
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams imageLp = new LayoutParams(dp(168), dp(90));
            imageLp.rightMargin = dp(18);
            root.addView(thumbnail, imageLp);
            LinearLayout captions = new LinearLayout(activity);
            captions.setOrientation(VERTICAL);
            captions.addView(title);
            captions.addView(detail);
            captions.addView(state);
            root.addView(captions, weight());
            for (TextView action : new TextView[]{play, keep, delete}) {
                LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(56));
                lp.leftMargin = dp(10);
                root.addView(action, lp);
            }
        }
        void bind(Clips.Clip clip) {
            root.setBackground(clip.held ? Style.outlinedCard(Style.GOOD, activity) : Style.card(Style.CARD, activity));
            title.setText(clip.title(activity));
            detail.setText(clip.subtitle(activity));
            boolean writable = adapter.writableClips.contains(clip.mp4.getAbsolutePath());
            keep.setEnabled(writable);
            delete.setEnabled(writable);
            keep.setAlpha(writable ? 1f : .45f);
            delete.setAlpha(writable ? 1f : .45f);
            boolean pending = adapter.pendingHolds.contains(clip.mp4.getAbsolutePath());
            state.setText(clip.kind == Clips.Kind.RECORDING ? s(R.string.clips_recording)
                : clip.kind == Clips.Kind.ORPHAN ? s(R.string.clips_orphan)
                : !writable ? s(R.string.dash_read_only)
                : clip.held ? s(R.string.dash_protected) : s(R.string.dash_ready));
            state.setTextColor(clip.held || pending ? Style.GOOD : Style.TEXT_DIM);
            play.setVisibility(clip.playable() ? VISIBLE : GONE);
            play.setOnClickListener(v -> activity.startActivity(new Intent(activity, ClipPlayerActivity.class)
                .putExtra(ClipPlayerActivity.EXTRA_PATH, clip.mp4.getAbsolutePath())));
            keep.setVisibility(clip.kind == Clips.Kind.ORPHAN ? GONE : VISIBLE);
            keep.setText(s(clip.held || pending ? R.string.clips_release : R.string.clips_hold));
            keep.setOnClickListener(v -> mutate(() -> {
                if (clip.kind == Clips.Kind.RECORDING) {
                    if (pending) {
                        Clips.clearPending(activity, clip);
                    } else {
                        Clips.markPending(activity, clip);
                    }
                } else if (!Clips.hold(activity, clip, !clip.held)) {
                    ui.post(() -> Toast.makeText(activity, s(R.string.dash_file_failed), Toast.LENGTH_LONG).show());
                }
            }));
            delete.setVisibility(clip.kind == Clips.Kind.RECORDING ? GONE : VISIBLE);
            delete.setOnClickListener(v -> clipDialog = new AlertDialog.Builder(activity)
                .setTitle(s(R.string.clips_delete_q, clip.title(activity))).setMessage(clip.subtitle(activity))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clips_delete, (dialog, which) -> mutate(() -> Clips.delete(clip))).show());
            imageKey = adapter.imageKeys.get(clip.mp4.getAbsolutePath());
            String requested = imageKey;
            Bitmap cached = requested == null ? null : thumbnails.get(requested);
            thumbnail.setImageBitmap(cached);
            thumbnail.setBackground(Style.card(Style.CARD_HI, activity));
            if (cached == null && requested != null && !images.isShutdown()) {
                images.execute(() -> {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    Bitmap decoded = BitmapFactory.decodeFile(clip.thumb.getAbsolutePath(), options);
                    if (decoded != null) {
                        thumbnails.put(requested, decoded);
                        ui.post(() -> {
                            if (active && requested.equals(imageKey)) {
                                thumbnail.setImageBitmap(decoded);
                            }
                        });
                    }
                });
            }
        }
    }
}
