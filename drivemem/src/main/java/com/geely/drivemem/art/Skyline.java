package com.geely.drivemem.art;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;

import java.util.Random;

// The skyline, shared by every art that wants one.
//
// COMPOSITION, NOT INHERITANCE, and the difference is not stylistic. `ArtView`
// is the contract EVERY art has to obey; a city is something an art may OWN.
// Putting this in the base class would force a bitmap onto every future theme,
// including ones that never draw a building.
//
// WHY IT IS SHAREABLE AT ALL: of the whole camera in ARTE.md section 4, the
// skyline touches exactly two numbers — `horizon` and `unit`. No cx, no focal
// length, no CELL, no px()/py(). It never goes through the projection, which is
// also why it can never be anything but scenery: something at the horizon that
// is never reached. An element that DID need the projection could not be lifted
// out like this without turning into a sticker.
//
// The plate is rasterised ONCE and after that only blitted (section 5: bake what
// is static, code what reacts). Thousands of little squares cost one drawBitmap
// per frame, not thousands of edges to tessellate. Recolouring is a
// PorterDuffColorFilter, never a re-raster.
public class Skyline {
    // Height of the plate, as a fraction of `unit`.
    private static final float CITY_TOP = 0.68f;
    // The city does not move: driving forward toward a distant skyline does
    // not slide it sideways. Only turning (heading) would sweep a distant object;
    // speed alone cannot do it. Parallax would require side-to-side motion.

    // plane 0 = front (rare and large), 2 = back (common and small)
    // The front entry is 0.0030, not 0.0036: giving the front plane both the
    // biggest buildings AND the biggest windows made it read as a zoomed copy of a
    // small one. Finer windows on a bigger body is what reads as TALL.
    private static final float[] PLANE_UNIT = { 0.0030f, 0.0024f, 0.0015f };
    private static final float[] PLANE_MIN  = { 0.52f, 0.32f, 0.14f };
    private static final float[] PLANE_MAX  = { 0.68f, 0.50f, 0.30f };
    private static final int[]   PLANE_COLS = { 10, 8, 6 };
    private static final int[]   PLANE_COLR = { 9, 8, 7 };

    // NO WIDE BUILDINGS. Column count and height were rolled independently, so a
    // short wide slab was a legal outcome — and the reference has none: every
    // building there is markedly taller than it is wide. Guarding the RATIO beats
    // shrinking PLANE_COLS, because the fault is the proportion, not the count:
    // fewer columns would still produce a slab whenever the height roll came in low.
    private static final float MIN_ASPECT = 1.55f;

    // A skewed building is a BOX CORNER, not one sheared plane: a vertical edge
    // with a face either side. The near face is foreshortened and its rows RISE
    // towards the corner; the far face is wider and its rows FALL away from it, so
    // the silhouette peaks at the edge — which is what the screen print shows.
    private static final float NEAR_FACE_COLS   = 0.34f;  // share of columns on the near face
    private static final float NEAR_FORESHORTEN = 0.60f;  // its windows, seen at an angle
    private static final float FAR_SHEAR        = 0.5f;   // the far face slants less
    private static final float ANGLED_CHANCE    = 0.28f;

    // A few towers carry a spire: the columns narrow to one or two and keep going.
    private static final float SPIRE_CHANCE = 0.17f;

    private final int fallback;          // colour used when the cabin light is off
    private final Runnable onInvalidate; // the host View asks for its own frames

    private Bitmap plate;
    private final Paint platePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int w = 0;
    private float unit = 0f;

    private int color, target;
    // The city does not display until the first real cabin light reading arrives.
    // The fallback color is used for the paint, but drawing is suppressed until
    // a real value is known, to avoid a visible transition to the real color.
    private boolean known = false;
    private float appear = 0f;     // 0..1, one gentle fade the first time only
    private android.animation.ValueAnimator appearAnim;
    private android.animation.ValueAnimator anim;

    // `onInvalidate` is injected rather than the View being passed in: the
    // colour transition is the ONE thing here that asks for frames on its own,
    // and section 6's corollary (a colour change must invalidate explicitly)
    // then lives in exactly one place per art instead of being reinvented.
    public Skyline(int fallbackColor, Runnable onInvalidate) {
        this.fallback = fallbackColor;
        this.onInvalidate = onInvalidate;
        this.color = this.target = fallbackColor;
        platePaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }

    // Rebuilds the plate. `unit` is the scene's vertical unit (groundH in vaporwave).
    // The name abstracts this so other arts can reuse Skyline with different horizons.
    public void layout(int width, float vertUnit) {
        // Recycle the bitmap but not release() the animation: a layout pass right
        // after setColor() starts the fade-in, and releasing the animation would
        // freeze it at partial opacity. Only the bitmap needs rebuilding.
        if (plate != null) { plate.recycle(); plate = null; }
        w = width; unit = vertUnit;
        int ch = (int) Math.ceil(unit * CITY_TOP * 1.18f) + 8;   // slack for the slanted facade
        if (w <= 0 || ch <= 8) return;
        plate = Bitmap.createBitmap(w, ch, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(plate);
        float baseY = ch;                    // the street sits at the foot of the plate

        Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        cp.setColor(0xFFFFFFFF);   // rasterised WHITE; SRC_IN paints the real colour
        Random rnd = new Random(99172026L);   // fixed seed: the same city every time

        // Sweeps the X axis end to end and lines up buildings from different
        // planes, side by side. Since each one occupies its own strip there is no
        // overlap at all — no transparency and no erased footprint. The PYRAMID
        // comes out of the plane draw: the front plane is rare, the back plane is
        // common, so there are few large buildings and many small ones.
        float x = 0f;
        while (x < w) {
            int plane = pickPlane(rnd);
            float span = building(cc, cp, rnd, x, baseY, plane, w - x);
            if (span <= 0f) {                       // not even the smallest one fit
                if (plane == 2) break;
                span = building(cc, cp, rnd, x, baseY, 2, w - x);
                if (span <= 0f) break;
            }
            x += span;
        }
    }

    private int pickPlane(Random rnd) {
        int k = rnd.nextInt(7);
        return (k == 0) ? 0 : (k <= 2 ? 1 : 2);
    }

    // Draws ONE building with its base at (x, baseY). Returns the width it took
    // up, or 0 if it does not fit in `avail`.
    //
    // Three rules, all three from the screen print on the car's own glass:
    //   1. DENSITY IS THE OPPOSITE of instinct: a building DISSOLVES AT THE
    //      BOTTOM and CONCENTRATES AT THE TOP. The sparse base lets the city
    //      float above the horizon instead of settling onto it.
    //   2. EVERY BUILDING HAS ITS OWN FACADE — its own shear, its own window
    //      size. Neighbours at different angles are what read as volume;
    //      identical windows everywhere flatten the whole thing.
    //   3. One colour only.
    //
    // The shear of an angled facade is VERTICAL, not horizontal: the ROW
    // descends as it moves sideways and the columns stay plumb. Offsetting x as
    // a function of height is literally a building toppling over — that mistake
    // turned the city into a row of leaning towers twice.
    private float building(Canvas cc, Paint cp, Random rnd, float x, float baseY,
                           int plane, float avail) {
        float u = Math.max(1.4f, w * PLANE_UNIT[plane]);
        boolean angled = rnd.nextFloat() < ANGLED_CHANCE;
        float pw = u * (0.85f + rnd.nextFloat() * 0.5f);
        float ph = angled ? pw * (0.75f + rnd.nextFloat() * 0.5f) : pw;
        float gx = pw * 0.45f, gy = ph * 0.45f;
        // s = vertical drop per pixel travelled sideways (only the row slants)
        float s = angled ? 0.18f + rnd.nextFloat() * 0.20f : 0f;
        int cols = PLANE_COLS[plane] + rnd.nextInt(PLANE_COLR[plane]);
        float top = unit * (PLANE_MIN[plane] + rnd.nextFloat() * (PLANE_MAX[plane] - PLANE_MIN[plane]));
        int rows = Math.max(6, (int) (top / (ph + gy)));
        float rad = pw * 0.22f;                  // rounded corner, as in the screen print

        // ASPECT GUARD — drop columns until it is taller than it is wide.
        float bodyH = rows * (ph + gy);
        while (cols > 3 && bodyH < MIN_ASPECT * cols * (pw + gx)) cols--;

        Path straight = new Path(), leaning = new Path();
        RectF q = new RectF();
        float span;

        if (angled) {
            // ---- box corner: two faces meeting at a vertical edge ----
            // Both faces DROP AWAY from the corner, so the silhouette peaks there.
            // The near one is foreshortened, which is what stops it reading as two
            // unrelated buildings that happen to touch.
            int nearCols = Math.max(1, Math.round(cols * NEAR_FACE_COLS));
            int farCols  = Math.max(2, cols - nearCols);
            float pwN = pw * NEAR_FORESHORTEN, gxN = pwN * 0.45f;
            float nearW = nearCols * (pwN + gxN), farW = farCols * (pw + gx);
            span = nearW + farW + pw * 1.6f;
            if (span > avail) return 0f;
            float corner = x + nearW;

            // near face: column 0 is the far left, so the drop grows with the
            // DISTANCE FROM THE CORNER; the window's own right edge sits higher.
            for (int c = 0; c < nearCols; c++) {
                float cxx = x + c * (pwN + gxN);
                float dy = s * (nearCols - 1 - c) * (pwN + gxN);
                int rowsC = setback(rnd, rows, c == 0 ? 0 : (c == 1 ? 1 : 2));
                face(rnd, leaning, cxx, baseY, dy, rowsC, pwN, ph, gy, -s * pwN);
            }
            // far face: the drop grows going right, away from the corner.
            for (int c = 0; c < farCols; c++) {
                float cxx = corner + c * (pw + gx);
                float dy = s * FAR_SHEAR * c * (pw + gx);
                int e = farCols - 1 - c;
                int rowsC = setback(rnd, rows, e == 0 ? 0 : (e == 1 ? 1 : 2));
                face(rnd, leaning, cxx, baseY, dy, rowsC, pw, ph, gy, s * FAR_SHEAR * pw);
            }
            if (rnd.nextFloat() < SPIRE_CHANCE)
                spire(rnd, leaning, corner - pw, baseY, rows, pw, ph, gy, 0f);
        } else {
            // ---- one flat facade, straight on ----
            span = cols * (pw + gx) + pw * 1.6f;
            if (span > avail) return 0f;
            for (int c = 0; c < cols; c++) {
                int edge = Math.min(c, cols - 1 - c);
                int rowsC = setback(rnd, rows, edge);
                float cxx = x + c * (pw + gx);
                for (int rw = 0; rw < rowsC; rw++) {
                    if (!lit(rnd, rw, rowsC)) continue;
                    float by = baseY - rw * (ph + gy) - ph;
                    q.set(cxx, by, cxx + pw, by + ph);
                    straight.addRoundRect(q, rad, rad, Path.Direction.CW);
                }
            }
            if (rnd.nextFloat() < SPIRE_CHANCE)
                spire(rnd, straight, x + cols * (pw + gx) * 0.5f - pw, baseY, rows, pw, ph, gy, rad);
        }

        cc.drawPath(straight, cp);
        cc.drawPath(leaning, cp);
        return span;
    }

    // setbacks: the columns at the outer edges do not rise as high as the middle
    private int setback(Random rnd, int rows, int edge) {
        return Math.max(1, rows - (edge == 0 ? 3 + rnd.nextInt(6)
                                             : (edge == 1 ? rnd.nextInt(3) : 0)));
    }

    // DENSITY IS THE OPPOSITE of instinct: full up high, dissolving downwards. The
    // base still holds ~40% — thinning all the way to empty turned into confetti.
    private boolean lit(Random rnd, int rw, int rowsC) {
        float t = rw / (float) rowsC;                 // 0 = base, 1 = top
        return rnd.nextFloat() <= 0.40f + 0.60f * (float) Math.pow(t, 1.2f);
    }

    // one column of an angled face
    private void face(Random rnd, Path into, float cxx, float baseY, float dy,
                      int rowsC, float pw, float ph, float gy, float sk) {
        for (int rw = 0; rw < rowsC; rw++) {
            if (!lit(rnd, rw, rowsC)) continue;
            float by = baseY - rw * (ph + gy) - ph + dy;
            quadV(into, cxx, by, pw, ph, sk);
        }
    }

    // The tower on top: the columns narrow to one or two and keep climbing, still
    // dissolving as they go. In the screen print it is what separates a tower from
    // a block — without it every building ends in the same flat top.
    private void spire(Random rnd, Path into, float cxx, float baseY, int rows,
                       float pw, float ph, float gy, float rad) {
        int extra = 4 + rnd.nextInt(Math.max(2, rows / 2));
        int wide  = 1 + rnd.nextInt(2);               // one or two columns
        RectF q = new RectF();
        for (int c = 0; c < wide; c++) {
            float sx = cxx + c * (pw + pw * 0.45f);
            for (int rw = rows; rw < rows + extra; rw++) {
                // thins out towards the tip, so it tapers instead of stopping dead
                float t = (rw - rows) / (float) extra;
                if (rnd.nextFloat() > 1f - 0.75f * t) continue;
                float by = baseY - rw * (ph + gy) - ph;
                if (rad > 0f) { q.set(sx, by, sx + pw, by + ph); into.addRoundRect(q, rad, rad, Path.Direction.CW); }
                else quadV(into, sx, by, pw, ph, 0f);
            }
        }
    }

    // window on a side facade: sides PLUMB, top and bottom slanted
    private void quadV(Path into, float x, float y, float bw, float bh, float sk) {
        into.moveTo(x, y);
        into.lineTo(x + bw, y + sk);
        into.lineTo(x + bw, y + bh + sk);
        into.lineTo(x, y + bh);
        into.close();
    }

    // One plate, blitted once at the horizon. It is still built so that no
    // building crosses its border — that costs nothing and is what a heading-driven
    // sweep would need if the city is ever given a reason to move.
    public void draw(Canvas cv, float horizon, int alpha) {
        if (plate == null || alpha <= 0 || !known) return;
        alpha = Math.round(alpha * appear);
        if (alpha <= 0) return;
        platePaint.setAlpha(alpha);
        float y = horizon - plate.getHeight();
        cv.drawBitmap(plate, 0f, y, platePaint);
        platePaint.setAlpha(255);      // hand the state back (section 8)
    }

    // Cabin colour -> city colour, over 700 ms. Cheap because the plate is one
    // colour with alpha: SRC_IN swaps the tone and keeps the antialiased edges,
    // with no need to rasterise the city again.
    public void setColor(int argb) {
        int c = 0xFF000000 | (argb & 0xFFFFFF);
        // First reading: adopt the color immediately without animation to avoid
        // an awkward transition from the fallback color.
        if (!known) {
            known = true;
            color = target = c;
            platePaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            if (appearAnim != null) appearAnim.cancel();
            appearAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            appearAnim.setDuration(600);   // it arrives, it does not pop
            appearAnim.addUpdateListener(a -> {
                appear = (Float) a.getAnimatedValue();
                if (onInvalidate != null) onInvalidate.run();
            });
            appearAnim.start();
            return;
        }
        if (c == target) return;
        target = c;
        if (anim != null) anim.cancel();
        anim = android.animation.ValueAnimator.ofObject(
            new android.animation.ArgbEvaluator(), color, c);
        anim.setDuration(700);         // it transitions, it does not jump
        anim.addUpdateListener(a -> {
            color = (Integer) a.getAnimatedValue();
            platePaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            if (onInvalidate != null) onInvalidate.run();
        });
        anim.start();
    }

    public int fallbackColor() { return fallback; }

    // When hidden, settle animations to their targets (never mid-way), so
    // subsequent setColor() calls can detect a real change by comparing target.
    public void settle() {
        // The fade-in settles the same way the colour does: on its END, never
        // half-way, or the city is left permanently semi-transparent with nothing
        // scheduled to finish it.
        if (appearAnim != null) { appearAnim.cancel(); appearAnim = null; }
        if (known) appear = 1f;
        if (anim == null) return;
        anim.cancel(); anim = null;
        color = target;
        platePaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }

    public void release() {
        if (appearAnim != null) { appearAnim.cancel(); appearAnim = null; }
        if (plate != null) { plate.recycle(); plate = null; }
    }
}
