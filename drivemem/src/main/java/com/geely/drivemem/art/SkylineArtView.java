package com.geely.drivemem.art;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.util.Style;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

// Art for every theme except Noturno: the skyline, and nothing else.
//
// It is Noturno's STRUCTURE with Noturno's CONTENT removed. Same full-bleed,
// same veil — but no grid, no sun, no stars and no car.
// Those four belong to a scene with a camera; a skyline does not need one (see
// Skyline: it touches only `horizon` and a vertical unit), and dragging them
// into the other themes would have made three copies of a scene that only
// Noturno earns.
//
// A SCENE WITH NO GROUND HAS NO FOREGROUND. That is the real design problem
// here, and it has two answers, both used: the theme's own background gradient
// runs the full height so the bottom is a deliberate emptiness rather than a
// missing floor, and the city is mirrored under the horizon so the lower half
// has something to be.
//
// BRIGHTNESS IS TRANSPARENCY. The cabin light is often kept low on purpose, and
// a skyline that stayed at full strength would be a decoration nobody asked for.
// Low light means the city sinks into the background — present, but almost
// invisible. It never reaches zero: a floor keeps it from disappearing, because
// "gone" and "dim" are different statements about the scene.
public class SkylineArtView extends ArtView {
    // Lower than Noturno's 0.46: with no grid to fill the ground there is no
    // reason to spend two thirds of the screen on it, and a low horizon leaves
    // the sky doing the work.
    private static final float HORIZON_Y = 0.66f;

    // Buildings scale off Noturno's horizon, not this art's. Skyline.layout uses
    // unit to control building height while width comes from screen size.
    // This preserves building proportions when the horizon is repositioned.
    private static final float CITY_UNIT_Y = 0.46f;

    private static final int   ALPHA_FLOOR = 20;    // ~8%: dim, never absent
    private static final float REFLECT_A   = 0.34f; // reflection, relative to the city
    private static final int   BRIGHT_MAX  = CarAccess.AMBIENT_BRIGHT_MAX;  // 20

    // One Paint per shader, always — swapping a shader mid-frame does not take on
    // this accelerated canvas (section 8).
    private final Paint bg    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veilP = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Skyline skyline = new Skyline(Style.ACCENT, this::invalidate);

    private Shader bgSh, glowSh, veilSh;
    private int w, h;
    private float horizon, unit;

    private int ambient = Style.ACCENT;
    private int alpha = 255;              // driven by the cabin light brightness

    public SkylineArtView(Context c) { super(c); }

    // The art is the whole screen, so blanking the art would blank everything.
    @Override public boolean fullBleed() { return true; }

    @Override protected void onHidden() { skyline.settle(); }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        w = nw; h = nh;
        if (w == 0 || h == 0) return;
        horizon = h * HORIZON_Y;
        unit    = h - horizon;

        // The theme's own vertical gradient, full height. This is the trick the
        // art it replaced used and it is what makes the art's left edge match the
        // control columns exactly, with no seam to hide.
        bgSh = new LinearGradient(0, 0, 0, h,
            Style.BG_TOP, Style.BG_BOTTOM, Shader.TileMode.CLAMP);

        buildGlow();
        buildVeil();
        skyline.layout(w, h * (1f - CITY_UNIT_Y));
    }

    // The veil, which hands the left of the screen back to the theme background.
    // Column 2 of the new layout is sparser than the old single control column,
    // so the opaque part reaches further right than Noturno's 0.34.
    private void buildVeil() {
        int bgc = Style.BG_BOTTOM;
        veilSh = new LinearGradient(0, 0, w, 0,
            new int[]{ bgc, bgc, bgc & 0x00FFFFFF },
            new float[]{ 0f, 0.45f, 0.78f }, Shader.TileMode.CLAMP);
    }

    // A soft band of cabin colour straddling the horizon. Without it the horizon
    // is a hard seam now that no ground plane meets it.
    private void buildGlow() {
        if (h == 0) return;
        int c = ambient & 0xFFFFFF;
        glowSh = new LinearGradient(0, horizon - unit * 0.22f, 0, horizon + unit * 0.16f,
            new int[]{ c, c | (Style.LIGHT ? 0x38000000 : 0x4C000000), c },
            null, Shader.TileMode.CLAMP);
    }

    // Clamp the raw cabin color first, then compare the result. Skyline.setColor
    // does the comparison, so it sees only the final clamped color and avoids
    // restarting animations for equivalent but slightly different inputs.
    @Override public void setAmbient(int rgb) {
        int c = 0xFF000000 | (rgb & 0xFFFFFF);
        // light off would leave the city black on black: fall back to the accent
        if (Color.red(c) + Color.green(c) + Color.blue(c) < 60) c = Style.ACCENT;
        // Claro is ivory: a bright city on a bright background is invisible, so
        // pull it dark instead of inventing a special case elsewhere
        if (Style.LIGHT) c = Style.blend(c, 0xFF000000, 0.45f);
        if (c != ambient) { ambient = c; buildGlow(); }
        skyline.setColor(c);
    }

    @Override public void setAmbientBrightness(int level) {
        int lv = Math.max(0, Math.min(BRIGHT_MAX, level));
        int a = ALPHA_FLOOR + Math.round((255 - ALPHA_FLOOR) * (lv / (float) BRIGHT_MAX));
        if (a == alpha) return;         // unchanged means no repaint (section 6)
        alpha = a;
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        if (w == 0 || h == 0) return;

        bg.setShader(bgSh);
        cv.drawRect(0, 0, w, h, bg);
        bg.setShader(null);

        glowP.setShader(glowSh);
        glowP.setAlpha(alpha);
        cv.drawRect(0, horizon - unit * 0.22f, w, horizon + unit * 0.16f, glowP);
        glowP.setShader(null);
        glowP.setAlpha(255);

        skyline.draw(cv, horizon, alpha);

        // Mirror the city below the horizon to fill the lower half without
        // pretending there is a ground plane.
        cv.save();
        cv.scale(1f, -1f, 0f, horizon);
        skyline.draw(cv, horizon, Math.round(alpha * REFLECT_A));
        cv.restore();

        // The veil, last before the window (sections 3 and 10).
        veilP.setShader(veilSh);
        cv.drawRect(0, 0, w, h, veilP);
        veilP.setShader(null);

        // No animations here. The city is static. Color transitions and brightness
        // changes invalidate explicitly. No frame updates are needed on speed.
    }

}
