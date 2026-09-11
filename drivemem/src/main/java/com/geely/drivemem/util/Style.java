package com.geely.drivemem.util;

import com.geely.drivemem.R;

import com.geely.drivemem.art.ArtView;
import com.geely.drivemem.hvac.ComfortRuler;
import com.geely.drivemem.hvac.EffortTable;
import com.geely.drivemem.ui.ComfortActivity;
import com.geely.drivemem.ui.TelemetryActivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Central theme management for the app.
 *
 * The public color and shape fields below are the active palette, swapped by apply().
 * Every screen calls load(Context) at the start of onCreate and draws using these values.
 * Themes define both color AND shape (corner radius, stroke, card style) so recoloring
 * alone maintains visual distinction between themes.
 */
public class Style {
    // ---- active palette (default: Geely) ----
    public static int BG_TOP    = 0xFF303640;  // background gradient (top)
    public static int BG_BOTTOM = 0xFF171B21;  // background gradient (bottom)
    public static int CARD      = 0xFF2E333B;  // card
    public static int CARD_HI   = 0xFF3A4048;  // lighter card / track
    public static int CARD_ON   = 0xFF1E6FFF;  // selected
    public static int TEXT      = 0xFFECEFF3;  // primary text
    public static int TEXT_DIM  = 0xFF8A93A0;  // secondary text / label
    public static int TEXT_ON   = 0xFFECEFF3;  // text OVER a coloured fill
    public static int ACCENT    = 0xFF1E6FFF;  // accent
    public static int COOL      = 0xFF2196F3;  // cool semantics
    public static int HEAT      = 0xFFFF9800;  // heat semantics

    // ---- active shape ----
    public static int  RADIUS_DP    = 14;
    public static int  STROKE_DP    = 1;
    public static int  STROKE_COLOR = 0x22FFFFFF;
    public static boolean OUTLINE   = false;   // card = lit outline, fill almost hollow
    public static boolean LIGHT     = false;   // light background (adjusts the art's glows)
    // Whether the theme lets the cabin ambient light rule the accent. The
    // Noturno theme says no on purpose: its whole point is not to throw blue
    // light in your face at night.
    public static boolean FOLLOW_AMBIENT = true;

    // Which art takes the right panel (see ArtView and its two implementations)
    public static final int ART_SKYLINE = 0;  // skyline only, tinted by the cabin light
    public static final int ART_VAPOR = 1;   // vaporwave: grid, sun, city, car
    public static int ART = ART_SKYLINE;

    // =====================================================================
    // Themes
    // =====================================================================
    // A Theme is shape + colour, and is either ONE fixed look (`light` is
    // null) or a pair — a dark Palette and a light one — that the Appearance
    // setting below (Light/Dark/Auto) switches between. Splitting colour out
    // into Palette is what makes the pair possible without duplicating the
    // shape/id/name/blurb machinery per variant.
    public static final class Palette {
        final int bgTop, bgBottom, card, cardHi, cardOn, text, textDim, textOn, accent, cool, heat;
        final int radiusDp, strokeDp, strokeColor;
        final boolean outline, light, followAmbient;
        final int art;

        Palette(int bgTop, int bgBottom, int card, int cardHi, int cardOn,
                int text, int textDim, int textOn, int accent, int cool, int heat,
                int radiusDp, int strokeDp, int strokeColor,
                boolean outline, boolean light, boolean followAmbient, int art) {
            this.bgTop = bgTop; this.bgBottom = bgBottom; this.card = card;
            this.cardHi = cardHi; this.cardOn = cardOn; this.text = text;
            this.textDim = textDim; this.textOn = textOn; this.accent = accent;
            this.cool = cool; this.heat = heat;
            this.radiusDp = radiusDp; this.strokeDp = strokeDp; this.strokeColor = strokeColor;
            this.outline = outline; this.light = light; this.followAmbient = followAmbient;
            this.art = art;
        }
    }

    public static final class Theme {
        // id = the persistence key, name = a proper name: neither of the two is
        // translatable text. The supporting blurb IS a resource: since THEMES is
        // static and has no Context, we keep the resource ID and whoever draws
        // it (TelemetryActivity.themeTile) resolves it with getString.
        public final String id, name;
        public final int blurbRes;
        final Palette dark, light;   // light == null: this theme ignores Appearance

        Theme(String id, String name, int blurbRes, Palette dark, Palette light) {
            this.id = id; this.name = name; this.blurbRes = blurbRes;
            this.dark = dark; this.light = light;
        }

        public boolean hasLightVariant() { return light != null; }
    }

    public static final Theme[] THEMES = new Theme[] {
        // Default: gunmetal grey with blue at night (car's native tone) or
        // cool off-white for bright daylight. Appearance setting switches between them.
        new Theme("geely", "Default", R.string.theme_geely_blurb,
            new Palette(0xFF303640, 0xFF171B21, 0xFF2E333B, 0xFF3A4048, 0xFF1E6FFF,
                0xFFECEFF3, 0xFF8A93A0, 0xFFECEFF3, 0xFF1E6FFF, 0xFF2196F3, 0xFFFF9800,
                30, 1, 0x22FFFFFF, false, false, true, ART_SKYLINE),
            // Light variant: cool off-white palette matched to instrument cluster appearance.
            new Palette(0xFFEEF1F4, 0xFFDBE0E5, 0xFFFFFFFF, 0xFFE7EAEE, 0xFF1668E3,
                0xFF1B1F24, 0xFF62697A, 0xFFFFFFFF, 0xFF1668E3, 0xFF0277BD, 0xFFE65100,
                34, 1, 0x1A000000, false, true, true, ART_SKYLINE)),

        // true black + amber: driving at night without taking blue light in the
        // face. Ignores the cabin light — if the car is violet, the app stays
        // amber — and ignores Appearance too, on purpose: it already IS a
        // night theme, so there is no light variant for it to switch to.
        new Theme("noturno", "Noturno", R.string.theme_noturno_blurb,
            new Palette(0xFF0B0B0D, 0xFF000000, 0xFF141416, 0xFF25252A, 0xFF7A4A08,
                0xFFE4DCCB, 0xFF7E7568, 0xFFFFF3DF, 0xFFFFA726, 0xFF7FA8BF, 0xFFFFB74D,
                22, 1, 0x1AFFFFFF, false, false, false, ART_VAPOR),
            null),

        // hollow card with a lit outline, very round corner, cyan/magenta — one
        // fixed scheme, same reasoning as Noturno: Appearance does not apply.
        new Theme("neon", "Neon", R.string.theme_neon_blurb,
            new Palette(0xFF10143A, 0xFF04050D, 0xFF0C1030, 0xFF1B2358, 0xFF0B4C5E,
                0xFFE6FBFF, 0xFF7C8FB8, 0xFFE6FBFF, 0xFF00E5FF, 0xFF00E5FF, 0xFFFF2D95,
                46, 2, 0x5500E5FF, true, false, true, ART_SKYLINE),
            null),
    };

    private static Theme current = THEMES[0];

    /** Returns the currently active theme. */
    public static Theme current() { return current; }

    /** Returns whether a theme ID is secret (not listed in Config). Noturno is
     * only accessible via the Konami code and is not shown in the theme picker. */
    public static boolean secret(String id) { return "noturno".equals(id); }

    /** Returns the Theme with the given ID, or the default theme if not found. */
    public static Theme byId(String id) {
        for (Theme t : THEMES) if (t.id.equals(id)) return t;
        return THEMES[0];
    }

    // Transient theme override (e.g., Noturno from Konami code) that survives onCreate
    // recreate() but is lost on process death. Set without writing SharedPreferences.
    private static String transientId = null;

    /** Temporarily switches to a theme without saving (survives recreate, lost on restart). */
    public static void setTransient(Context c, String id) { transientId = id; apply(c, byId(id)); }
    /** Returns whether the current theme is a transient override. */
    public static boolean isTransient() { return transientId != null; }

    // Appearance setting (Light/Dark/Auto) applies only to themes with both variants.
    public static final String APPEARANCE_LIGHT = "light";
    public static final String APPEARANCE_DARK  = "dark";
    public static final String APPEARANCE_AUTO  = "auto";

    /** Returns the saved appearance setting (Light/Dark/Auto). */
    public static String appearance(Context c) {
        return c.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
                .getString("appearance", APPEARANCE_DARK);
    }

    /** Saves and applies an appearance setting (Light/Dark/Auto). */
    public static void setAppearance(Context c, String mode) {
        c.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
         .edit().putString("appearance", mode).apply();
    }

    // Reads Android's system day/night setting (Configuration.uiMode).
    private static boolean systemIsLight(Context c) {
        int night = c.getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Returns true if the theme's light Palette should be used based on appearance setting. */
    public static boolean resolvedLight(Context c, Theme t) {
        if (t.light == null) return false;
        String mode = appearance(c);
        if (APPEARANCE_LIGHT.equals(mode)) return true;
        if (APPEARANCE_DARK.equals(mode)) return false;
        return systemIsLight(c);
    }

    private static Palette resolvePalette(Context c, Theme t) {
        return resolvedLight(c, t) ? t.light : t.dark;
    }

    /** Loads the saved theme (or transient override) and applies it. Called at onCreate start. */
    public static void load(Context c) {
        SharedPreferences p = c.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        String savedTheme = p.getString("theme", THEMES[0].id);
        if ("claro".equals(savedTheme)) {
            // Legacy: Claro theme merged into Default's light Palette; migrate to APPEARANCE_LIGHT.
            p.edit().putString("theme", THEMES[0].id).putString("appearance", APPEARANCE_LIGHT).apply();
            savedTheme = THEMES[0].id;
        }
        apply(c, byId(transientId != null ? transientId : savedTheme));
    }

    /** Returns the saved theme ID, ignoring any transient override. */
    public static String savedId(Context c) {
        return c.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
                .getString("theme", THEMES[0].id);
    }

    /** Saves and applies a theme (caller should recreate the screen). Clears transient overrides. */
    public static void save(Context c, String id) {
        transientId = null;
        c.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
         .edit().putString("theme", id).apply();
        apply(c, byId(id));
    }

    /** Applies a theme's palette to the active static fields. */
    public static void apply(Context c, Theme t) {
        current = t;
        Palette pal = resolvePalette(c, t);
        BG_TOP = pal.bgTop; BG_BOTTOM = pal.bgBottom;
        CARD = pal.card; CARD_HI = pal.cardHi; CARD_ON = pal.cardOn;
        TEXT = pal.text; TEXT_DIM = pal.textDim; TEXT_ON = pal.textOn;
        ACCENT = pal.accent; COOL = pal.cool; HEAT = pal.heat;
        RADIUS_DP = pal.radiusDp; STROKE_DP = pal.strokeDp; STROKE_COLOR = pal.strokeColor;
        OUTLINE = pal.outline; LIGHT = pal.light; FOLLOW_AMBIENT = pal.followAmbient;
        ART = pal.art;
    }

    /** Converts density-independent pixels to screen pixels. */
    public static int dp(Context c, int v) {
        return (int)(v * c.getResources().getDisplayMetrics().density);
    }

    /** Makes the status bar transparent so app content can extend underneath it. */
    public static void edgeToEdge(android.app.Activity a) {
        android.view.Window w = a.getWindow();
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        // The car's status-icon renderer tints every status icon based on the app's
        // LIGHT_STATUS_BAR signal. Omitting this flag caused icons to stay light-themed
        // regardless of the app's palette, so set it to match the current theme's style.
        if (LIGHT) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /** Returns the system status bar height in pixels. */
    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }

    /** Blends two colors with weight t (0=a, 1=b), preserving alpha of a. */
    public static int blend(int a, int b, float t) {
        int r = (int)(Color.red(a)   + (Color.red(b)   - Color.red(a))   * t);
        int g = (int)(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl= (int)(Color.blue(a)  + (Color.blue(b)  - Color.blue(a))  * t);
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    /** Lightens a color toward white for better legibility on dark backgrounds. */
    public static int mixWhite(int c, float t) { return blend(c, 0xFFFFFFFF, t); }

    /** Returns the appropriate text color for content on top of a filled background. */
    public static int onFill(int fill) {
        return (fill == CARD || fill == CARD_HI) ? TEXT : TEXT_ON;
    }

    /** Creates a vertical gradient background drawable. */
    public static GradientDrawable screenBg() {
        return new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM});
    }

    /** Creates a card drawable in the theme's current shape. */
    public static GradientDrawable card(int fill, Context c) { return card(fill, c, RADIUS_DP); }

    /** Creates a card drawable with a custom corner radius (in dp). */
    public static GradientDrawable card(int fill, Context c, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp));
        if (Color.alpha(fill) == 0) { g.setColor(fill); return g; }  // invisible touch area
        if (OUTLINE) {
            boolean neutral = (fill == CARD);
            g.setColor(blend(fill, BG_BOTTOM, neutral ? 0.55f : 0.72f));
            g.setStroke(dp(c, STROKE_DP), neutral ? STROKE_COLOR : fill);
        } else {
            g.setColor(fill);
            g.setStroke(dp(c, STROKE_DP), STROKE_COLOR);
        }
        return g;
    }

    /** Returns the fill color for large translucent cards with art showing through. */
    public static int cardFillColor() {
        int alpha = LIGHT ? 0xD1 : 0x94;
        return (CARD & 0x00FFFFFF) | (alpha << 24);
    }

    /** Returns the fill color for tile elements inside cards. */
    public static int tileFillColor() { return LIGHT ? 0x0C000000 : 0x12FFFFFF; }
    /** Returns the stroke color for tile elements inside cards. */
    public static int tileStrokeColor() { return LIGHT ? 0x1A000000 : 0x17FFFFFF; }

    /** Creates an unlit tile drawable (faint fill and stroke). */
    public static GradientDrawable tile(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, RADIUS_DP - 8));
        g.setColor(tileFillColor());
        g.setStroke(dp(c, 1), tileStrokeColor());
        return g;
    }

    /** Creates a card with a colored accent strip on the left side. */
    public static android.graphics.drawable.LayerDrawable accentCard(int accent, Context c) {
        return stripCard(accent, c, true);
    }

    /** Creates a card with a colored accent strip on the top (for tall cards). */
    public static android.graphics.drawable.LayerDrawable accentCardTop(int accent, Context c) {
        return stripCard(accent, c, false);
    }

    private static android.graphics.drawable.LayerDrawable stripCard(int accent, Context c, boolean left) {
        GradientDrawable base = card(CARD, c);
        GradientDrawable strip = new GradientDrawable();
        strip.setColor(accent);
        strip.setCornerRadius(dp(c, RADIUS_DP));
        android.graphics.drawable.LayerDrawable ld =
            new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{strip, base});
        if (left) ld.setLayerInset(1, dp(c, 5), 0, 0, 0);
        else      ld.setLayerInset(1, 0, dp(c, 5), 0, 0);
        return ld;
    }

    /** Creates a card with a colored stroke (used for comfort control buttons). */
    public static GradientDrawable outlinedCard(int accent, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(OUTLINE ? blend(accent, BG_BOTTOM, 0.86f) : CARD);
        g.setCornerRadius(dp(c, RADIUS_DP));
        g.setStroke(dp(c, OUTLINE ? STROKE_DP + 1 : 2), accent);
        return g;
    }

    /** Creates a large title text view. */
    public static TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextColor(TEXT); t.setTextSize(26);
        t.setPadding(0, 0, 0, dp(c, 4));
        return t;
    }

    /** Creates a section header text view. */
    public static TextView header(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s.toUpperCase()); t.setTextColor(blend(TEXT_DIM, TEXT, 0.45f)); t.setTextSize(17);
        t.setLetterSpacing(0.06f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(c, 16), 0, dp(c, 6));
        return t;
    }

    /** Creates a label text view. */
    public static TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextColor(TEXT); t.setTextSize(22);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    /** Creates a clickable card button. */
    public static TextView cardButton(Context c, String s, boolean selected, Runnable onClick) {
        TextView t = new TextView(c);
        int fill = selected ? CARD_ON : CARD;
        t.setText(s); t.setTextColor(onFill(fill)); t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setBackground(card(fill, c));
        int pv = dp(c, 18);
        t.setPadding(pv, pv, pv, pv);
        if (onClick != null) t.setOnClickListener(v -> onClick.run());
        return t;
    }

    /** Creates a back arrow button. */
    public static android.widget.ImageView backButton(Context c, Runnable onClick) {
        int px = dp(c, 34);
        android.graphics.Bitmap bmp =
            android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(TEXT);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(px * 0.085f);
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);

        float cy = px / 2f;
        float xTip = px * 0.28f, xEnd = px * 0.76f;
        cv.drawLine(xTip, cy, xEnd, cy, p);              // the shaft
        android.graphics.Path head = new android.graphics.Path();
        head.moveTo(xTip + px * 0.20f, cy - px * 0.20f); // the arrow head
        head.lineTo(xTip, cy);
        head.lineTo(xTip + px * 0.20f, cy + px * 0.20f);
        cv.drawPath(head, p);

        android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setImageBitmap(bmp);
        int pad = dp(c, 10);
        iv.setPadding(pad, pad, pad, pad);
        iv.setBackground(card(CARD, c));
        if (onClick != null) iv.setOnClickListener(v -> onClick.run());
        return iv;
    }

    /** Creates a settings/cog button. */
    public static android.widget.ImageView cogButton(Context c, Runnable onClick) {
        int px = dp(c, 34);
        android.graphics.Bitmap bmp =
            android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(TEXT_DIM);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(px * 0.075f);
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);

        float cx = px / 2f, cy = px / 2f;
        float rIn = px * 0.16f;    // the centre hole
        float rMid = px * 0.30f;   // the body
        float rOut = px * 0.42f;   // the tip of the teeth
        cv.drawCircle(cx, cy, rIn, p);
        cv.drawCircle(cx, cy, rMid, p);
        // 8 radial teeth
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8.0;
            float sx = cx + (float) Math.cos(a) * rMid;
            float sy = cy + (float) Math.sin(a) * rMid;
            float ex = cx + (float) Math.cos(a) * rOut;
            float ey = cy + (float) Math.sin(a) * rOut;
            cv.drawLine(sx, sy, ex, ey, p);
        }

        android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setImageBitmap(bmp);
        int pad = dp(c, 10);
        iv.setPadding(pad, pad, pad, pad);
        // a touch area that is comfortable for a car screen, without calling attention
        iv.setBackground(card(0x00000000, c));
        if (onClick != null) iv.setOnClickListener(v -> onClick.run());
        return iv;
    }

    /** Creates the effort scale (11 pills from C5 to W5) showing HVAC level. */
    public static android.graphics.Bitmap effortScale(Context c, int w, int h,
            int level, boolean approx) {
        return effortScale(c, w, h, level, approx, false);
    }

    /** Darkens a color to indicate frozen/disabled state. */
    private static int darken(int color) {
        int a = Color.alpha(color);
        int r = Color.red(color)   / 2;
        int g = Color.green(color) / 2;
        int b = Color.blue(color)  / 2;
        return Color.argb(a, r, g, b);
    }

    /** Creates the effort scale with optional defrosting (dimmed) state. */
    public static android.graphics.Bitmap effortScale(Context c, int w, int h,
            int level, boolean approx, boolean defrosting) {
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
            Math.max(w, 1), Math.max(h, 1), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        int max = EffortTable.MAX_LEVEL;                 // 5, fixed both sides
        float gap = dp(c, 8);
        float radius = dp(c, 8);
        boolean off = (level == 0);
        // The zero pill is fixed 15dp ONLY while a side is lit (it is then just
        // the divider between them). At level 0 — HVAC off — it takes an equal
        // flex:1 share like every other pill instead, so "off" reads as one
        // wide filled bar, not a small dot lost among ten empty ones. Straight
        // from the mock's own render script, which the README's one-line "fixed
        // 15x15" undersells.
        float totalGap = gap * 10;
        float zeroW = off ? (w - totalGap) / 11f : dp(c, 15);
        float sideW = off ? zeroW : (w - totalGap - zeroW) / 10f;
        boolean coldSide = level > 0;                     // which side is "current"

        float x = 0;
        for (int i = 0; i < 11; i++) {
            int n = max - i;                              // C5..C1, 0, W1..W5
            float pw = (n == 0) ? zeroW : sideW;
            android.graphics.RectF r = new android.graphics.RectF(x, 0, x + pw, h);

            if (n == 0) {
                if (level == 0) {
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setColor(defrosting ? darken(TEXT) : TEXT);
                } else {
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(dp(c, 2));
                    p.setColor(defrosting ? darken(CARD_HI) : CARD_HI);
                }
            } else {
                boolean cold = n > 0;
                int abs = Math.abs(n);
                int side = cold ? COOL : HEAT;
                boolean lit = level != 0 && cold == coldSide && abs <= Math.abs(level);
                boolean approxNext = approx && level != 0 && cold == coldSide
                                    && abs == Math.abs(level) + 1;
                if (lit) {
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setColor(defrosting ? darken(side) : side);
                } else if (approxNext) {
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(dp(c, 2));
                    p.setColor(defrosting ? darken(side) : side);
                } else {
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setColor(defrosting ? darken(CARD_HI) : CARD_HI);
                }
            }
            cv.drawRoundRect(r, radius, radius, p);
            x += pw + gap;
        }
        return bmp;
    }

    /** Creates a turbo mode bar (shows active fraction). */
    public static android.graphics.Bitmap turboBar(Context c, int w, int h, float fraction, int fill) {
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
            Math.max(w, 1), Math.max(h, 1), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float radius = h / 2f;

        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(tileFillColor());
        cv.drawRoundRect(new android.graphics.RectF(0, 0, w, h), radius, radius, p);

        float fw = Math.max(h, w * Math.max(0f, Math.min(1f, fraction)));   // never thinner than tall
        if (fraction > 0f) {
            p.setColor(fill);
            cv.drawRoundRect(new android.graphics.RectF(0, 0, fw, h), radius, radius, p);
        }
        return bmp;
    }

    /** Creates a charge bar showing start position and current charge level. */
    public static android.graphics.Bitmap chargeBar(Context c, int w, int h,
            float startFraction, float nowFraction, int fill) {
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
            Math.max(w, 1), Math.max(h, 1), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float radius = h / 2f;

        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(tileFillColor());
        cv.drawRoundRect(new android.graphics.RectF(0, 0, w, h), radius, radius, p);

        float now = Math.max(0f, Math.min(1f, nowFraction));
        float fw = Math.max(h, w * now);
        if (now > 0f) {
            p.setColor(fill);
            cv.drawRoundRect(new android.graphics.RectF(0, 0, fw, h), radius, radius, p);
        }

        float nx = w * Math.max(0f, Math.min(1f, startFraction));
        p.setColor(TEXT);
        p.setStrokeWidth(Math.max(2f, h * 0.12f));
        cv.drawLine(nx, 0, nx, h, p);

        return bmp;
    }

    /** Creates a charge range bar showing history from start to end SOC with duration label. */
    public static android.graphics.Bitmap chargeRangeBar(Context c, int w, int h,
            float startFraction, float endFraction, int fill, String durationLabel) {
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
            Math.max(w, 1), Math.max(h, 1), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float radius = h / 2f;

        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(tileFillColor());
        cv.drawRoundRect(new android.graphics.RectF(0, 0, w, h), radius, radius, p);

        float x0 = w * Math.max(0f, Math.min(1f, startFraction));
        float x1 = w * Math.max(0f, Math.min(1f, endFraction));
        if (x1 < x0) { float t = x0; x0 = x1; x1 = t; }   // defensive; socEnd should never be < socStart
        x1 = Math.max(x1, x0 + h);   // never thinner than tall, so a near-zero gain still shows
        p.setColor(fill);
        cv.drawRoundRect(new android.graphics.RectF(x0, 0, x1, h), radius, radius, p);

        if (durationLabel != null && !durationLabel.isEmpty()) {
            android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            tp.setColor(onFill(fill));
            tp.setTextSize(h * 0.45f);
            tp.setTextAlign(android.graphics.Paint.Align.CENTER);
            tp.setFakeBoldText(true);
            float cy = h / 2f - (tp.descent() + tp.ascent()) / 2f;
            cv.drawText(durationLabel, (x0 + x1) / 2f, cy, tp);
        }

        return bmp;
    }

    /** Creates a thumbnail preview of a theme. */
    public static android.graphics.Bitmap themeSwatch(Context c, Theme t, int w, int h) {
        w = Math.max(w, 1); h = Math.max(h, 1);
        Palette pal = resolvePalette(c, t);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float r = dp(c, pal.radiusDp) * 0.55f;   // the corner at the thumbnail's scale
        float m = h * 0.10f;

        // the background
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
            pal.bgTop, pal.bgBottom, android.graphics.Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // the accent stroke (the same one that sits under the AC screen's title)
        p.setColor(pal.accent);
        cv.drawRect(m, m, m + w * 0.22f, m + h * 0.045f, p);

        // two lines of "text"
        p.setColor(pal.text);
        cv.drawRect(m, m + h * 0.14f, m + w * 0.42f, m + h * 0.20f, p);
        p.setColor(pal.textDim);
        cv.drawRect(m, m + h * 0.24f, m + w * 0.30f, m + h * 0.285f, p);

        // two cards: one neutral and one selected — in the theme's shape
        float cardTop = h * 0.52f, cardBot = h - m;
        float gap = w * 0.04f, cw = (w - 2 * m - gap) / 2f;
        drawSwatchCard(cv, p, pal, m, cardTop, m + cw, cardBot, r, pal.card);
        drawSwatchCard(cv, p, pal, m + cw + gap, cardTop, m + 2 * cw + gap, cardBot, r, pal.cardOn);
        return bmp;
    }

    private static void drawSwatchCard(android.graphics.Canvas cv, android.graphics.Paint p,
            Palette pal, float l, float top, float rgt, float bot, float r, int fill) {
        boolean neutral = (fill == pal.card);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(pal.outline ? blend(fill, pal.bgBottom, neutral ? 0.55f : 0.72f) : fill);
        cv.drawRoundRect(new android.graphics.RectF(l, top, rgt, bot), r, r, p);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, pal.strokeDp * 1.4f));
        p.setColor(pal.outline && !neutral ? fill : pal.strokeColor);
        cv.drawRoundRect(new android.graphics.RectF(l, top, rgt, bot), r, r, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
    }

    /** Adds vertical spacing between elements in a layout. */
    public static void gap(LinearLayout col, Context c, int d) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, d)));
        col.addView(v);
    }

    /** Applies margin values to an already-added view. */
    public static void margin(View v, int l, int t, int r, int b, Context c) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(dp(c,l), dp(c,t), dp(c,r), dp(c,b));
            v.setLayoutParams(lp);
        }
    }
}
