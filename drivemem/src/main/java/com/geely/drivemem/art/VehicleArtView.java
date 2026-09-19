package com.geely.drivemem.art;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Style;
import com.geely.drivemem.util.VehicleProfile;

/** Offline EX2 MAX preview with an optional, immediately applied colour selector. */
public final class VehicleArtView extends ArtView {
    private static final String COLOR_KEY = "ex2_preview_color";
    private static final String[] COLORS = {"moon_white", "star_silver", "comet_grey",
        "nebula_beige", "aurora_green"};
    private static final int[] IMAGES = {R.drawable.geely_ex2_moon_white,
        R.drawable.geely_ex2_star_silver, R.drawable.geely_ex2_comet_grey,
        R.drawable.geely_ex2_nebula_beige, R.drawable.geely_ex2_aurora_green};
    private static final int[] LABELS = {R.string.ui_ex2_moon_white,
        R.string.ui_ex2_star_silver, R.string.ui_ex2_comet_grey,
        R.string.ui_ex2_nebula_beige, R.string.ui_ex2_aurora_green};
    // Shared by Home and Settings; bounded to two full-resolution renders.
    private static final android.util.LruCache<Integer, Bitmap> IMAGES_CACHE =
        new android.util.LruCache<Integer, Bitmap>(16 * 1024 * 1024) {
            @Override protected int sizeOf(Integer key, Bitmap bitmap) { return bitmap.getByteCount(); }
        };
    private final android.text.TextPaint paint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    // Display the car and its shadow, excluding the source image's empty margins.
    private final Rect source = new Rect(440, 255, 1790, 880);
    private final RectF destination = new RectF();
    private Bitmap vehicle;
    private final SharedPreferences prefs;
    private int colorIndex = -1;
    private Runnable colorChanged;
    private boolean interactive;
    private final boolean preview;
    private final String subtitle;
    private final Typeface headingFont;
    private String vehicleName;
    private String vehicleNickname;

    public VehicleArtView(Context context) { this(context, false); }

    public VehicleArtView(Context context, boolean preview) {
        super(context);
        this.preview = preview;
        prefs = context.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        vehicleName = VehicleProfile.modelName(prefs);
        vehicleNickname = VehicleProfile.customName(prefs);
        loadColor();
        subtitle = context.getString(R.string.ui_vehicle_overview);
        headingFont = Typeface.create(Style.font(context), Typeface.BOLD);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public String colorLabel() {
        return getContext().getString(R.string.ui_vehicle_color_tap,
            getContext().getString(LABELS[colorIndex]));
    }

    public void enableColorSelection(Runnable onChanged) {
        interactive = true;
        colorChanged = onChanged;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setContentDescription(colorLabel());
        setOnClickListener(v -> {
            prefs.edit().putString(COLOR_KEY, COLORS[(colorIndex + 1) % COLORS.length]).apply();
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                setAlpha(0.7f);
                animate().alpha(1f).setDuration(180).start();
            }
        });
    }

    private void loadColor() {
        String saved = prefs.getString(COLOR_KEY, COLORS[0]);
        if ("aether_green".equals(saved)) saved = "aurora_green";
        int index = 0;
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i].equals(saved)) index = i;
        if (index == colorIndex) return;
        colorIndex = index;
        vehicle = IMAGES_CACHE.get(IMAGES[index]);
        if (vehicle == null) {
            vehicle = BitmapFactory.decodeResource(getResources(), IMAGES[index]);
            IMAGES_CACHE.put(IMAGES[index], vehicle);
        }
        if (interactive) setContentDescription(colorLabel());
        if (colorChanged != null) colorChanged.run();
        invalidate();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener colorListener = (preferences, key) -> {
        if (COLOR_KEY.equals(key)) loadColor();
        if (key == null || VehicleProfile.NAME_KEY.equals(key) || VehicleProfile.MODEL_KEY.equals(key)) {
            vehicleName = VehicleProfile.modelName(preferences);
            vehicleNickname = VehicleProfile.customName(preferences);
            invalidate();
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        prefs.registerOnSharedPreferenceChangeListener(colorListener);
        vehicleName = VehicleProfile.modelName(prefs);
        vehicleNickname = VehicleProfile.customName(prefs);
        loadColor();
    }

    @Override protected void onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(colorListener);
        animate().cancel();
        super.onDetachedFromWindow();
    }

    @Override public boolean fullBleed() { return !preview; }

    @Override public void setAmbient(int rgb) { /* Preserve the vehicle's actual colour. */ }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() * (preview ? 0.5f : 0.73f);
        float centerY = getHeight() * (preview ? 0.5f : 0.56f);
        float maxWidth = getWidth() * (preview ? 0.96f : 0.46f);
        float maxHeight = getHeight() * (preview ? 0.96f : 0.56f);
        float scale = Math.min(maxWidth / source.width(), maxHeight / source.height());
        float halfWidth = source.width() * scale / 2;
        float halfHeight = source.height() * scale / 2;
        destination.set(centerX - halfWidth, centerY - halfHeight,
            centerX + halfWidth, centerY + halfHeight);
        canvas.drawBitmap(vehicle, source, destination, paint);

        if (!preview) {
            float titleY = Style.statusBarHeight(getContext()) + 90 * dens;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(headingFont);
            paint.setTextSize(34 * getResources().getDisplayMetrics().scaledDensity);
            paint.setColor(Style.TEXT);
            String heading = android.text.TextUtils.ellipsize(vehicleName, paint,
                getWidth() * 0.46f, android.text.TextUtils.TruncateAt.END).toString();
            canvas.drawText(heading, centerX, titleY, paint);
            paint.setTypeface(Style.font(getContext()));
            paint.setTextSize(22 * getResources().getDisplayMetrics().scaledDensity);
            paint.setColor(Style.TEXT_DIM);
            String caption = android.text.TextUtils.ellipsize(
                vehicleNickname.isEmpty() ? subtitle : vehicleNickname, paint,
                getWidth() * 0.46f, android.text.TextUtils.TruncateAt.END).toString();
            canvas.drawText(caption, centerX, titleY + 42 * dens, paint);
        }
    }
}
