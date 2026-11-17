package com.geely.drivemem.art;

import com.geely.drivemem.R;
import com.geely.drivemem.ui.ComfortActivity;
import com.geely.drivemem.util.Style;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

import java.util.Random;

// Art of the Noturno theme: vaporwave. The EX2 (the car's own render) running straight down
// the grid towards the sun, with the city cut out on the horizon and the sun
// BEHIND it.
//
// ONE CAMERA ONLY. Grid, city and car all go through the same perspective
// projection (px/py below): camera at height CAM_Y looking at the vanishing
// point (cx, horizon), focal length f. That is why the car "belongs" to the
// ground instead of being a sticker pasted on top — and why the grid's squares
// are actually square (see CELL).
//
// The city is scenery you never reach: still on the horizon, made of small
// slanted rectangles, in Geely's visual vocabulary. Height reads through
// DENSITY: dense at the bottom, thinning out towards the top.
//
// The rear wheel reports the pedal: a RED breath while accelerating, BLUE energy
// converging into the wheel while slowing down (on an electric, regenerating).
// The signal is the derivative of the speed read at 1 Hz — the only
// accelerometer this car gives. To use real current/regen, just change `accel`
// in ArtView.
//
// STOPPED, IT STOPS. With no speed and no pedal the scene freezes and the View
// stops asking for frames: a live screen only when the car is alive.
public class VaporArtView extends ArtView {
    // ---- palette ----
    private static final int SKY_TOP  = 0xFF241056;   // deep violet
    private static final int SKY_MID  = 0xFF7A2A8C;
    private static final int SKY_LOW  = 0xFFFF6FA5;   // pastel pink
    private static final int SKY_HOR  = 0xFF8FE3FF;   // pastel cyan at the horizon
    private static final int SUN_TOP  = 0xFFFFE86D;
    private static final int SUN_MID  = 0xFFFF9A5B;
    // The sun ends in ORANGE, not in magenta: with the bottom of the disc pink
    // and the sky behind it also pink, the slices vanished — the only one that
    // showed was the one falling in the cyan band of the horizon, and it looked
    // like a single stray blue line.
    private static final int SUN_BOT  = 0xFFFF5E3A;
    private static final int GROUND_T = 0xFF2A0A48;
    private static final int GROUND_B = 0xFF06000E;
    private static final int GRID_C   = 0xFFFF2D95;   // pink grid
    // Half-width of the FLAT CORRIDOR, in columns. Not a painted road any more:
    // it is where the ground stops being rough, which is what makes it read.
    //
    // 8 -> 4, and this is a LAYOUT constraint, not taste. At 8 the corridor is
    // 8*colStep = 816 px each side of cx=1420, i.e. 604..2236, while the art is
    // only visible from ~640 (the AC column covers the left third) to 1920. The
    // whole visible ground fell inside the flat strip and the verge was drawn
    // off-screen on the right and under the controls on the left — the grid
    // looked exactly as it always had. The simulator missed it because it draws
    // all 1920 px as open ground and models no control column.
    // 8 -> 4 -> 6. It is a squeeze from both ends: too wide and the verge falls
    // outside the visible art entirely (that was 8), too narrow and the car fills
    // its own lane (that was 4 — the corridor came out 354 px at the car's depth
    // against a 312 px car). 6 gives ~1.05 world units of shoulder, a bit over
    // twice the car's half-width, and still leaves verge in frame in the
    // mid-field where the corridor has narrowed.
    private static final int ROAD_I   = 6;
    // city: the screen print on this car's own glass has ONE colour only, violet
    // — neither gold (that was a reflection in the photo) nor two shades of blue
    // (that was me)
    private static final int CITY     = 0xFF6C5BE8;
    private static final int ACCEL_C  = 0xFFFF1E3C;   // breath while accelerating
    private static final int REGEN_C  = 0xFF49E0FF;   // energy coming in

    // ---- camera ----
    // A SQUARE cell in front of the camera, for any CELL: with focal length
    // f = groundH*(1-CELL), the gap between the first two horizontals and the
    // spacing of the verticals both come out as groundH*CELL. It is not a guess,
    // it is arithmetic — and that is why CELL becomes the single knob for grid
    // density: smaller = more lines in BOTH directions, without ceasing to be
    // square.
    // 0.15 -> 0.175: a bit sparser. On this screen that widens both the gap
    // between the first two horizontals and the spacing of the verticals at the
    // bottom edge from 87 px to 102 px. Everything else that depends on it
    // follows on its own — the column count off colStep, the focal length, and
    // the car's size through ZK — which is the whole point of there being one knob.
    private static final float CELL  = 0.175f;
    private static final float CAM_Y = 1f;
    private static final float VP_X  = 0.74f;   // vanishing point: in the visible third
    private static final float HORIZON_Y = 0.46f;

    private static final int   GRID_ROWS = 120;
    private static final int   STARS = 26;
    // Grid rows per second per km/h. Chosen for readability, not world scale
    // accuracy: 0.154 produces rows/s = km/h * 0.154 (e.g., 50 km/h = 7.7 rows/s).
    // This must move with CELL: the eye reads ground covered (rows/s * CELL),
    // not rows/s alone. So when CELL changes, scale CELLS_PER_KMH to preserve
    // the sensation of speed.
    private static final float CELLS_PER_KMH = 0.154f;

    // ---- car (world, metres-ish) ----
    // Larger ZR = car further away = smaller and more settled on the ground; the
    // first attempt (0.62) filled the right corner and touched the edge.
    // ZK rescales ALL the z values along with the focal length: changing CELL
    // changes f, and without this the car would change size along with the grid
    // density.
    private static final float ZK = (1f - CELL) / 0.5f;
    private static final float ZR = 0.86f * ZK;
    private static final float WB = 0.46f;                // half-width of the body
    private static final float WHEEL_R = 0.13f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    // The city moved out to Skyline: it is the one element here that does NOT go
    // through the projection (it needs only `horizon` and a vertical unit), which
    // is exactly why it can be shared with the other themes. Everything else in
    // this scene — grid, car, sun — is welded to px()/py() and stays.
    private final Skyline skyline = new Skyline(CITY, this::invalidate);

    private Shader sky, ground, sunSh, hglow, haze, scrim, gridFade, glowAccel, glowRegen;
    private int w, h;
    private float horizon, cx, groundH, sunR, sunCy, f, colStep, glowR;
    private int iMin, iMax;                       // range of grid columns

    private float scroll = 0f;   // 0..1 within one cell
    private int   rowEpoch = 0;  // whole cells travelled; makes the verge ground-fixed
    private float pulse = 0f;    // accumulated seconds

    // Entry: the reference frame shift. The camera starts still and the car is
    // out of frame. As the car drives, it backs away and the camera catches up,
    // until both move at the same speed and the ground runs past.
    // Fraction of ZR where the car starts: right on the camera, out of frame. It
    // does not "appear" — it ENTERS, coming off the camera as it drives.
    private static final float INTRO_Z0 = 0.34f;
    // How fast the separation closes, in 1/s. It is not a duration: it is how
    // much of what the car travels still turns into distance instead of running
    // ground.
    private static final float INTRO_K  = 1.5f;

    // The entry has no clock — it IS the car's journey. The car starts out
    // of frame and enters as it drives. Stopped, it does not enter. This survives
    // Activity recreation (theme change, config screen) via static storage.
    private static boolean introPlayed = false;

    // Entry trigger: motion. When parked, the car fades into position instead
    // of driving in. ENTRY_GRACE (1.6s) clears the speed poll interval so that
    // the Activity's ~1s speed read and potential Konami recreation do not
    // trigger a false "car is parked" condition right after entering.
    private static final float MOVING_KMH  = 0.5f;   // the same "is moving" used below
    private static final float ENTRY_GRACE = 1.6f;   // stillness before we give up on a journey
    private static final float SETTLE_S    = 0.45f;  // the fade, when there was no journey
    private float stillFor = 0f;
    private boolean settling = false;

    // Rearms the entry. Called BEFORE the Activity is recreated, because
    // onSizeChanged reads introPlayed while the fresh View is being measured.
    public static void playIntro() { introPlayed = false; sunriseT0 = 0L; }

    // The car starts out of frame via opacity, not distance. The nose projects
    // near the horizon even at close Z, so distance alone never hides it. The
    // car materializes as it backs away from the camera.
    // The sunrise is the only animation tied to wall time, not the car's motion.
    // It starts at the horizon and takes six minutes to climb. While running
    // alone, it redraws at 5 Hz instead of 60, keeping CPU light.
    private static final float SUNRISE_MS      = 6f * 60f * 1000f;
    private static final long  SUNRISE_TICK_MS = 200L;   // under ArtView's 250 ms
                                                         // accumulator clamp, so no
                                                         // time is dropped
    private static final float SUNRISE_DROP    = 1.28f;  // in sunR: horizon+R -> rest
    private float sunUp = 1f;                            // 0 = below, 1 = in place

    // Static for the same reason introPlayed is: recreating the Activity for a
    // theme change is not a new dawn. 0 means "not started yet".
    private static long sunriseT0 = 0L;

    private static final float FADE_SPAN = 0.35f;   // fraction of the entry until fully opaque
    private float carFade = 1f;

    private float carZ = ZR;
    private boolean introDone = true;               // onSizeChanged arms the entry
    private float gridV = 0f;                       // cells/s currently in effect
    private float introAccel = 0f;                  // pedal of the pull-away

    private final float[] starX = new float[STARS], starY = new float[STARS];
    private final int[] starA = new int[STARS];

    // projected rear wheels (centre and radius on screen), for the effects
    private float rwx1, rwx2, rwy, rwr;

    public VaporArtView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        Random rnd = new Random(20260807L);        // fixed seed: always the same sky
        for (int i = 0; i < STARS; i++) {
            starX[i] = rnd.nextFloat();
            starY[i] = rnd.nextFloat() * 0.55f;
            starA[i] = 60 + rnd.nextInt(120);
        }
        // Skyline.draw() withholds every building until its first setColor()
        // call (`known`) — right for a theme that paints the car's REAL cabin
        // colour, wrong here: setAmbient() always hands it CITY anyway (see
        // that method below), so waiting for a genuine ambient-light reading
        // just means the city never appears at all when the cabin light is
        // low enough that the car stops reporting a colour for it. Seed it
        // now, with the same fixed colour setAmbient() would give it.
        skyline.setColor(CITY);
        // Decoded once per View. nodpi, so it arrives at its real 725 x 845; the
        // mipmaps keep it clean when the projection shrinks it to ~half that.
        carBmp = BitmapFactory.decodeResource(c.getResources(), R.drawable.noturno_car);
        if (carBmp != null) carBmp.setHasMipMap(true);
    }

    @Override public boolean fullBleed() { return true; }

    // HIDDEN DOES NOT ANIMATE. The city's colour animator is this art's own,
    // so it settles here whenever this view stops being visible.
    @Override protected void onHidden() { skyline.settle(); }

    // The city does not follow the cabin light. Every other theme's skyline
    // tracks the cabin's interior RGB, but Noturno deliberately keeps the
    // screen independent of cabin colour (see the class comment on why the
    // controls stay off the cabin colour) — the skyline follows the same rule.
    // Always CITY, regardless of
    // what the cabin light is actually doing right now.
    @Override public void setAmbient(int rgb) {
        skyline.setColor(CITY);          // no-ops when the colour has not changed
    }

    // And brightness never touched the skyline here either (unlike Skyline
    // ArtView's own ALPHA_FLOOR..255 fade) — now explicit rather than a
    // silently-inherited ArtView no-op, so a future reader sees a decision,
    // not an omission.
    @Override public void setAmbientBrightness(int level) {}

    // ---- projection ----
    private float px(float x, float z) { return cx + f * x / z; }
    private float py(float y, float z) { return horizon + f * (CAM_Y - y) / z; }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        w = nw; h = nh;
        if (w == 0 || h == 0) return;
        horizon = h * HORIZON_Y;
        groundH = h - horizon;
        cx = w * VP_X;
        f = groundH * (1f - CELL) / CAM_Y;
        colStep = groundH * CELL;        // gap of the verticals in the first row
        sunR = Math.min(w * 0.17f, horizon * 0.62f);
        sunCy = horizon - sunR * 0.28f;

        // enough columns to cover the whole screen (the grid runs underneath the
        // controls all the way to the left edge)
        // HOW MANY VERTICALS. Counting only the ones that reach the BOTTOM EDGE
        // left a void to the right of the car: the following lines do not
        // disappear, they exit through the SIDE EDGE, closer to the horizon, and
        // those were the ones I was not drawing. So we count out to depth T_MIN
        // of the ground (a fraction measured from the horizon) — the smaller it
        // is, the more lines covering the corner.
        final float T_MIN = 0.18f;
        iMax = Math.min(60, (int) Math.ceil((w - cx) / (colStep * T_MIN)));
        iMin = -Math.min(60, (int) Math.ceil(cx / (colStep * T_MIN)));

        sky = new LinearGradient(0, 0, 0, horizon,
            new int[]{ SKY_TOP, SKY_MID, SKY_LOW, SKY_HOR },
            new float[]{ 0f, 0.44f, 0.80f, 1f }, Shader.TileMode.CLAMP);
        ground = new LinearGradient(0, horizon, 0, h,
            new int[]{ GROUND_T, GROUND_B }, null, Shader.TileMode.CLAMP);
        sunSh = new LinearGradient(0, sunCy - sunR, 0, sunCy + sunR,
            new int[]{ SUN_TOP, SUN_MID, SUN_BOT },
            new float[]{ 0f, 0.42f, 1f }, Shader.TileMode.CLAMP);
        // Haze over the sun: transparent to opaque, adding over the sun's disc.
        // Ramping from clear avoids doubling up the sky gradient.
        haze = new LinearGradient(0, horizon * 0.80f, 0, horizon,
            new int[]{ SKY_HOR & 0x00FFFFFF, SKY_HOR }, null, Shader.TileMode.CLAMP);
        hglow = new LinearGradient(0, horizon - groundH * 0.03f, 0, horizon + groundH * 0.035f,
            new int[]{ 0x0000E5FF, 0x5EFF7BC8, 0x0000E5FF }, null, Shader.TileMode.CLAMP);
        // scrim that gives the left-hand column back to the theme background: the
        // scene bleeds underneath the controls and fades out, instead of ending
        // in a hard edge
        int bg = Style.BG_BOTTOM;
        scrim = new LinearGradient(0, 0, w, 0,
            new int[]{ bg, bg, bg & 0x00FFFFFF },
            new float[]{ 0f, 0.34f, 0.72f }, Shader.TileMode.CLAMP);

        // The verticals are born invisible at the horizon and only light up as
        // they come towards us. Without this, 15 lines meet in a white knot and
        // the vanishing point looks like it is right there; dissolved, it recedes
        // into the distance.
        gridFade = new LinearGradient(0, horizon, 0, horizon + groundH * 0.45f,
            new int[]{ GRID_C & 0xFFFFFF, GRID_C }, null, Shader.TileMode.CLAMP);

        glowR = f * WHEEL_R / ZR * 2.6f;
        glowAccel = new RadialGradient(0, 0, glowR,
            0xFF000000 | (ACCEL_C & 0xFFFFFF), ACCEL_C & 0xFFFFFF, Shader.TileMode.CLAMP);
        glowRegen = new RadialGradient(0, 0, glowR,
            0xFF000000 | (REGEN_C & 0xFFFFFF), REGEN_C & 0xFFFFFF, Shader.TileMode.CLAMP);

        skyline.layout(w, groundH);   // groundH IS this scene's vertical unit
        gridV = 0f; introAccel = 0f;
        // First time this journey: it is born OUT OF FRAME, waiting for the car to
        // move. Having already entered, it comes back settled — recreating the
        // Activity (theme, config) is not an event of the car and must not
        // re-stage anything.
        introDone = introPlayed;
        carZ = introPlayed ? ZR : ZR * INTRO_Z0;
        carFade = introPlayed ? 1f : 0f;
        stillFor = 0f; settling = false;
        // Starts with the SCENE, not with the car: unlike the entry it does not
        // wait to be driven. Only ever set once — a recreate finds it already
        // running and picks it up wherever it had got to.
        if (sunriseT0 == 0L) sunriseT0 = SystemClock.uptimeMillis();
        buildCar(carZ);
    }

    // ================= car =================
    // THE CAR'S OWN PICTURE OF ITSELF. This used to be a 13-face neon wireframe;
    // it is now the EX2 render the head unit ships for its ADAS settings screen
    // (com.flyme.auto.settings, drawable/bg_car_adas, night variant): cut out of
    // its background, upscaled ~4.8x with an AI upscaler (the original car is
    // only 158 px wide) and saved as res/drawable-nodpi/noturno_car.webp, with
    // the soft shadow kept.
    //
    // It still goes through the ONE CAMERA: the rear of the body is pinned to
    // the projected rear face of the old model (x = ±WB, ground at y = 0, depth
    // carZ), so size, position and the entry's approach come from px()/py()
    // exactly as before. Only what is drawn there changed.
    //
    // The picture is shot from higher up than this camera, so it stands taller
    // than the wireframe did. That is the render's own perspective, not a bug.
    //
    // Measured off noturno_car.webp (725 x 845): the widest row of the rear
    // body (below the mirrors) runs from x = 50 to x = 674, and the body meets
    // the ground at row 788 — everything under that is shadow.
    private static final float IMG_REAR_L = 50f, IMG_REAR_R = 674f;
    private static final float IMG_GROUND = 788f;

    private Bitmap carBmp;
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF carDst = new RectF();

    // Places the picture and the rear wheels (for the pedal glow) at depth zr.
    private void buildCar(float zr) {
        float xl = px(-WB, zr), xr = px(WB, zr);
        if (carBmp != null) {
            float s = (xr - xl) / (IMG_REAR_R - IMG_REAR_L);
            float left = xl - IMG_REAR_L * s;
            float top  = py(0f, zr) - IMG_GROUND * s;
            carDst.set(left, top, left + carBmp.getWidth() * s, top + carBmp.getHeight() * s);
        }
        rwx1 = px(-WB, zr + 0.30f);
        rwx2 = px( WB, zr + 0.30f);
        rwy  = py(WHEEL_R, zr + 0.30f);
        rwr  = f * WHEEL_R / (zr + 0.30f);
    }

    private void drawCar(Canvas cv) {
        if (carFade <= 0.004f || carBmp == null) return;   // has not come off the camera yet
        bmp.setAlpha(Math.round(255f * Math.min(1f, carFade)));
        cv.drawBitmap(carBmp, null, carDst, bmp);
    }

    // ================= animation =================
    private void advance(int n) {
        float dt = n * STEP_S;

        // Outside the step machinery on purpose: this is wall time, so it must not
        // inherit the accumulator's clamp, and it has to keep advancing on a frame
        // where n came out 0.
        if (sunUp < 1f || sunriseT0 != 0L) {
            float t = (SystemClock.uptimeMillis() - sunriseT0) / SUNRISE_MS;
            sunUp = t <= 0f ? 0f : (t >= 1f ? 1f : t);
        }

        if (!introDone) {
            // THE SAME IDENTITY AS BEFORE, WITHOUT A CLOCK. Of what the car
            // travels, one part becomes SEPARATION (it coming off the camera) and
            // the rest becomes RUNNING GROUND (the camera catching up) — it is
            // still one single number changing owner, not two scripted phases.
            //
            // What changed is where the total comes from: it used to be
            // 3*span/DUR, a fixed number; now it is the car's REAL speed on the
            // scene's scale. From that the three cases fall out on their own,
            // without a single `if`:
            //   stopped    -> travels nothing, the car stays out of frame
            //   starting   -> lots of distance still to go, everything becomes
            //                 separation and gridV = 0 (ground still, only the
            //                 car pulling out of the way)
            //   in frame   -> separation exhausted, gridV = the real speed
            // There is no step at the end: the entry FLOWS OUT into the cruising
            // speed because in the limit the two sums are the same one.
            if (speedKmh > MOVING_KMH) stillFor = 0f; else stillFor += dt;
            // NO JOURNEY TO SHOW. The car has not moved since the code landed, so
            // it does not drive in: it is put where it belongs and faded up. The
            // guard is carFade, not stillFor alone — once the car is genuinely on
            // its way, stopping at a light must not teleport it forward. It waits,
            // which is what "the entry IS the journey" means, and resumes when the
            // car does.
            if (!settling && carFade <= 0.004f && stillFor >= ENTRY_GRACE) {
                settling = true;
                carZ = ZR;
                buildCar(carZ);
            }
            if (settling) {
                carFade = Math.min(1f, carFade + dt / SETTLE_S);
                gridV = 0f;
                introAccel = 0f;
                if (carFade >= 1f) { introDone = true; introPlayed = true; settling = false; }
            } else {
                float span  = ZR - ZR * INTRO_Z0;
                float vCar  = speedKmh * CELLS_PER_KMH * CELL;   // the REAL speed, only
                float remain = ZR - carZ;
                float vSep  = Math.min(vCar, INTRO_K * remain);  // what is still separation
                if (vSep < 0f) vSep = 0f;
                carZ += vSep * dt;
                gridV = (vCar - vSep) / CELL;
                // wheel lit while it is pulling away, and only if it really is moving
                introAccel = 0.9f * (remain / span) * Math.min(1f, speedKmh / 10f);
                // materialises as it comes off the camera: stopped, it does not exist
                carFade = Math.min(1f, (span - remain) / (span * FADE_SPAN));
                buildCar(carZ);
                if (remain <= span * 0.004f) {
                    carZ = ZR; introDone = true; introPlayed = true;
                    introAccel = 0f; carFade = 1f;
                    buildCar(carZ);
                }
            }
        } else {
            // settles on the real speed — stopped, it goes back to stopped
            gridV += (speedKmh * CELLS_PER_KMH - gridV) * Math.min(1f, dt * 1.8f);
        }

        scroll += gridV * dt;
        // rowEpoch counts WHOLE CELLS travelled. scroll alone wraps, so it cannot
        // identify a piece of ground; the verge terrain is hashed on k + rowEpoch
        // so the ridges move with the road instead of sitting still on the glass.
        while (scroll >= 1f) { scroll -= 1f; rowEpoch++; }
        pulse += dt;
    }

    @Override protected void onDraw(Canvas cv) {
        if (w == 0 || h == 0) return;
        advance(steps());

        p.setStyle(Paint.Style.FILL);
        p.setShader(sky);
        cv.drawRect(0, 0, w, horizon, p);
        p.setShader(null);

        drawStars(cv);
        drawSun(cv);

        // Haze between the two: it goes over the SUN, so the disc sinks into the
        // horizon band — but UNDER the city, so the skyline keeps cutting a crisp
        // silhouette out of the disc, which is the whole reason the city is drawn
        // after the sun in the first place (section 5).
        p.setShader(haze);
        cv.drawRect(0, horizon * 0.80f, w, horizon, p);
        p.setShader(null);

        // city AFTER the sun: it is the city that cuts into the disc
        skyline.draw(cv, horizon, 255);   // Noturno keeps the city fully opaque

        p.setShader(ground);
        cv.drawRect(0, horizon, w, h, p);
        p.setShader(null);
        p.setShader(hglow);
        cv.drawRect(0, horizon - groundH * 0.03f, w, horizon + groundH * 0.035f, p);
        p.setShader(null);

        drawGrid(cv);
        drawWheelFx(cv);       // the glow comes out BEHIND the car
        drawCar(cv);

        // scrim on the left: melts the scene into the control column
        p.setShader(scrim);
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // Only animate when there is something to animate. The grid, accel effects
        // and speed changes drive frames. The entry does not ask for frames while
        // waiting for motion. Settling (a bounded fade) and the grace period
        // (bounded by ENTRY_GRACE) are exceptions, but both are finite.
        if (gridV > 0.01f || speedKmh > 0.5f || Math.abs(accel) > 0.04f
                || settling || (!introDone && stillFor < ENTRY_GRACE))
            postInvalidateOnAnimation();
        else if (sunUp < 1f)
            postInvalidateDelayed(SUNRISE_TICK_MS);   // the dawn, on its own slow clock
    }

    private void drawStars(Canvas cv) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < STARS; i++) {
            p.setColor(0xFFFFFF | (starA[i] << 24));
            cv.drawCircle(starX[i] * w, starY[i] * horizon, 1.3f, p);
        }
        // ESSENTIAL: every star leaves its own alpha behind in `p`, and the
        // Paint's alpha MULTIPLIES the shader. Without restoring the alpha here,
        // everything that comes afterwards (sun, city, ground, glow) comes out
        // translucent — that is how the ground turned into a ~40% scrim and let
        // the sun show through below the horizon.
        p.setAlpha(255);
    }

    private void drawSun(Canvas cv) {
        cv.save();
        // THE CANVAS MOVES, NOT THE SUN. sunSh is a LinearGradient built between
        // sunCy-sunR and sunCy+sunR, so a rising sun would mean a new shader every
        // frame — an allocation in onDraw, and section 5's one-Paint-one-shader gone.
        // Translating draws the identical disc somewhere else instead: the clip
        // circle and the gradient shift together and stay registered, for free.
        if (sunUp < 1f) cv.translate(0f, (1f - sunUp) * SUNRISE_DROP * sunR);
        path.rewind();
        path.addCircle(cx, sunCy, sunR, Path.Direction.CW);
        cv.clipPath(path);
        p.setShader(sunSh);
        cv.drawRect(cx - sunR, sunCy - sunR, cx + sunR, sunCy + sunR, p);
        // NO SLICES. The classic horizontal cut of the vaporwave sun was tried and
        // comes out badly here: the bottom half of the disc sits behind the city,
        // so either the stripes disappear or, raised into the visible part, they
        // cut precisely what the buildings' silhouette is already cutting — it
        // turns into dirt. A clean disc, and the city does the cutting.
        p.setShader(null);
        cv.restore();
    }

    // The road is not painted; it is the flat strip between rough ground,
    // read by contrast. Roughness is near-field only: past Z_FADE the rows
    // are drawn flat, saving CPU. This also matches the visual expectation
    // that a receding mesh should resolve as it recedes.
    private static final float VERGE_AMP = 0.22f;   // terrain height, world units
    private static final float Z_FADE    = 5.5f;    // depth at which ground goes flat

    // Deterministic — NOT Math.random, or the ground would boil frame to frame.
    private static float vnoise(int a, int b) {
        int n = (a * 73856093) ^ (b * 19349663);
        n = (n ^ (n >>> 13)) & 0x7FFFFFFF;
        return (n % 2003) / 1001.5f - 1f;
    }

    private static float octave(int i, int row, float wavelength, float amp) {
        float x = Math.abs(i) / wavelength;
        int x0 = (int) Math.floor(x);
        float t = x - x0;
        t = t * t * (3f - 2f * t);                   // smoothstep
        int s = i > 0 ? 1 : -1;
        float r0 = vnoise(s * x0, row), r1 = vnoise(s * (x0 + 1), row);
        return (r0 + (r1 - r0) * t) * amp;
    }

    // Coherent across columns and slow along Z to read as ridges beside the road.
    // Hashing each cell independently reads as static noise.
    private static float terrain(int i, int row) {
        return octave(i, row >> 2, 5f, 1f) + octave(i, row >> 2, 1.9f, 0.38f);
    }

    private float vergeFade(float z) {
        if (z >= Z_FADE) return 0f;
        float t = (Z_FADE - z) / (Z_FADE - (1f - CELL));
        if (t <= 0f) return 0f;
        if (t > 1f) t = 1f;
        return t * t * (3f - 2f * t);
    }

    // Sideways fade from the road edge out VERGE_RAMP columns. Without it the
    // road steps off instead of transitioning to rough ground.
    private static final float VERGE_RAMP = 3f;     // columns from edge to full height

    private static float vergeSide(int i) {
        float t = (Math.abs(i) - ROAD_I) / VERGE_RAMP;
        if (t <= 0f) return 0f;
        if (t > 1f) t = 1f;
        return t * t * (3f - 2f * t);
    }

    // Grid: the horizontals slide (z = k - scroll), the verticals stay put,
    // converging on the vanishing point. Square cell by construction (see CELL).
    private void drawGrid(Canvas cv) {
        line.setStyle(Paint.Style.STROKE);
        float near = 1f - CELL;                   // depth of the nearest row
        for (int k = 1; k <= GRID_ROWS; k++) {
            float z = (k - scroll) * CELL;
            if (z <= 0.02f) continue;
            float y = horizon + f * CAM_Y / z;
            if (y > h) continue;
            int a = (int) Math.min(225f, 225f * near / z);
            line.setColor((GRID_C & 0xFFFFFF) | (a << 24));
            line.setStrokeWidth(Math.max(1f, 2.6f * near / z));
            float fz = vergeFade(z);
            if (fz <= 0.001f) { cv.drawLine(0, y, w, y, line); continue; }
            float t = (y - horizon) / groundH;
            float half = ROAD_I * colStep * t;
            cv.drawLine(Math.max(0, cx - half), y, Math.min(w, cx + half), y, line);
            // rowEpoch, not k: `scroll` wraps within one cell (see the animation),
            // so k is a SCREEN row, not a piece of ground. Hashing on it would pin
            // the ridges to the glass and let the ground slide through them.
            int row = k + rowEpoch;
            for (int s = -1; s <= 1; s += 2) {
                path.rewind();
                boolean started = false;
                for (int i = ROAD_I; i <= 70; i++) {
                    float x = cx + s * i * colStep * t;
                    if (x < -60f || x > w + 60f) break;
                    float yy = y + f * terrain(s * i, row) * (VERGE_AMP * fz * vergeSide(i)) / z;
                    if (started) path.lineTo(x, yy); else { path.moveTo(x, yy); started = true; }
                }
                if (started) cv.drawPath(path, line);
            }
        }
        line.setShader(gridFade);
        int kFade = (int) Math.ceil(Z_FADE / CELL) + 1;   // last row the verge touches
        for (int i = iMin; i <= iMax; i++) {
            line.setAlpha(Math.max(55, 170 - Math.abs(i) * 3));
            line.setStrokeWidth(1.5f);
            if (Math.abs(i) <= ROAD_I) {                  // the road stays dead flat
                cv.drawLine(cx, horizon, cx + i * colStep, h, line);
                continue;
            }
            path.rewind();
            path.moveTo(cx, horizon);
            for (int k = kFade; k >= 1; k--) {            // far to near, then the edge
                float z = (k - scroll) * CELL;
                if (z <= 0.02f) continue;
                float y = horizon + f * CAM_Y / z;
                if (y > h) continue;
                float fz = vergeFade(z);
                float t = (y - horizon) / groundH;
                float dy = fz <= 0.001f ? 0f
                         : f * terrain(i, k + rowEpoch) * (VERGE_AMP * fz * vergeSide(i)) / z;
                path.lineTo(cx + i * colStep * t, y + dy);
            }
            path.lineTo(cx + i * colStep, h);
            cv.drawPath(path, line);
        }
        line.setShader(null);
        line.setAlpha(255);      // do not leak state to whoever draws next
    }


    private void drawWheelFx(Canvas cv) {
        // the pull-away of the entry counts as pedal: the wheel lights up as it arrives
        float acc = Math.max(Math.max(0f, accel), introAccel), rgn = Math.max(0f, -accel);
        if (acc <= 0.04f && rgn <= 0.04f) return;
        wheelFx(cv, rwx1, acc, rgn);
        wheelFx(cv, rwx2, acc, rgn);
    }

    private void wheelFx(Canvas cv, float x, float acc, float rgn) {
        if (acc > 0.04f) {
            float br = 0.55f + 0.45f * (float) Math.sin(pulse * 6.3f);   // breath at ~1 Hz
            glow(cv, x, rwy, glowAccel, (int) (130f * acc * br));
        }
        if (rgn > 0.04f) {
            glow(cv, x, rwy, glowRegen, (int) (70f * rgn));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2.2f);
            for (int i = 0; i < 5; i++) {
                float ph = (pulse * 1.7f + i * 0.2f) % 1f;        // 0 = far away, 1 = arrived
                float rad = rwr * 3.2f * (1f - ph) + rwr * 0.9f * ph;
                double ang = Math.PI * 2 * (i / 5f) + pulse * 0.7f;
                float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang) * 0.75f;
                int a = (int) (215f * rgn * (0.30f + 0.70f * ph));
                line.setColor((REGEN_C & 0xFFFFFF) | (a << 24));
                cv.drawLine(x + dx * rad, rwy + dy * rad,
                            x + dx * (rad - rwr * 0.9f), rwy + dy * (rad - rwr * 0.9f), line);
            }
        }
    }

    private void glow(Canvas cv, float x, float y, Shader sh, int alpha) {
        if (alpha <= 0) return;
        cv.save();
        cv.translate(x, y);
        p.setStyle(Paint.Style.FILL);
        p.setShader(sh);
        p.setAlpha(Math.min(255, alpha));
        cv.drawCircle(0, 0, glowR, p);
        p.setShader(null);
        p.setAlpha(255);
        cv.restore();
    }

}
