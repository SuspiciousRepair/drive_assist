package com.geely.drivemem.ui;

/** Where the four cameras are on the car, and the surface the Orbit view
 * paints them on. Pure math, no Android: the shader gets its numbers from
 * here, and the tests check the same numbers.
 *
 * The Orbit view used to treat the four cameras as one point. They are up to
 * 4 m apart, so a thing near the car showed in two different places where two
 * cameras meet. Now each screen ray finds a point on a ball around the car,
 * and each camera looks at that point from its own place. Things about as far
 * away as the ball line up. The two cameras are mixed in a narrow band where
 * they meet, so there is no hard line.
 *
 * A flat floor was tried first and was worse: it bent every thing that
 * stands up (cars, trees, fences) and left a large dark patch under the car.
 *
 * Axes: x to the right, y up, z to the front. The origin is the middle of the
 * car, on the ground. Camera order is yaw order: front, right, rear, left. */
final class OrbitRig {
    static final int FRONT = 0, RIGHT = 1, REAR = 2, LEFT = 3;

    /** Height of the viewpoint above the ground, metres. */
    static final float EYE = 1.0f;
    /** Radius of the ball the picture is painted on, metres. A far thing and
     * a near thing cannot both line up. 6 m doubled the houses and walls down
     * the street (a wall 15 m back showed two times behind the car); 10 m
     * lines those up and looked as good at home, where walls are near. */
    static final float BALL = 10f;
    /** Width of the band where two cameras are mixed, as a part of the ray
     * (0.12 is about 7 degrees). Narrow on purpose: a wide one shows a thing
     * near a corner two times. */
    static final float BAND = 0.12f;

    /** Camera positions, metres. Taken from the car's size (4.14 x 1.81 m),
     * not measured with a tape. */
    private static final float[][] POS = {
        {  0.00f, 0.60f,  2.00f },   // front, in the grille
        {  0.98f, 1.00f,  0.60f },   // right mirror
        {  0.00f, 0.90f, -2.05f },   // rear, above the plate
        { -0.98f, 1.00f,  0.60f },   // left mirror
    };

    /** Shape of the Orbit picture on the screen: a wide stripe. */
    static final float ASPECT = 3.2f;
    /** Degrees from the middle to the side edge of the screen is
     * MID * x + (EDGE - MID) * x^3, x from 0 (middle) to 1 (edge). Near the
     * middle that is MID degrees per half screen, the same as a plain view;
     * toward the edges it grows, so the sides are squeezed and the stripe
     * shows EDGE degrees each way. A thing coming from a side shows early and
     * takes its true shape as it turns to the middle. */
    static final float MID = 75f, EDGE = 110f;

    private OrbitRig() { }

    /** The world direction (length 1) for a point on the screen, x and y
     * from -1 to 1 (y up). Vertical lines stay vertical: the yaw depends on
     * x only, and the pitch moves the picture up and down like a shift lens
     * instead of turning it. pitch is positive looking down. The same steps
     * as FRAG_ORBIT. */
    static double[] screenRay(double x, double y, double yawRad, double pitchRad) {
        double ax = Math.abs(x), mid = Math.toRadians(MID), edge = Math.toRadians(EDGE);
        double yw = yawRad + Math.signum(x) * (mid * ax + (edge - mid) * ax * ax * ax);
        double t = y * mid / ASPECT - Math.tan(pitchRad);
        double sx = Math.sin(yw), cz = Math.cos(yw), n = Math.sqrt(1 + t * t);
        return new double[] { sx / n, t / n, cz / n };
    }

    /** Elevations, degrees, of the top of the picture: COVER entries, one per
     * 360/COVER degrees of yaw from the front. Above it no camera sees (the
     * mirror cameras point down and see only up to the horizon to the side),
     * and the view fades to black there instead of showing a jagged edge. */
    static final int COVER = 128;
    /** The range the table is stored in, in an 8-bit texture. */
    static final float COVER_LO = -20f, COVER_HI = 40f;

    static float[] coverageTop(float[] rot, double[] lensHalfRad, double[] squash) {
        float[] out = new float[COVER];
        for (int k = 0; k < COVER; k++) {
            double yw = k * 2 * Math.PI / COVER;
            out[k] = COVER_LO;
            for (double el = COVER_HI; el >= COVER_LO; el -= 0.25) {
                double e = Math.toRadians(el);
                double rx = Math.sin(yw) * Math.cos(e), ry = Math.sin(e), rz = Math.cos(yw) * Math.cos(e);
                double[] p = surface(rx, ry, rz);
                double ws = 0;
                for (int cam = 0; cam < 4; cam++)
                    ws += weight(cam, rx, rz, cell(cam, p, rot, lensHalfRad[cam], squash[cam]));
                if (ws > 0.5) { out[k] = (float) el; break; }
            }
        }
        return out;
    }

    /** The four positions, x y z each, for a vec3[4] uniform. */
    static float[] positions() {
        float[] out = new float[12];
        for (int i = 0; i < 4; i++) System.arraycopy(POS[i], 0, out, i * 3, 3);
        return out;
    }

    /** The direction each camera looks, on the ground plane (x, z), for a
     * vec2[4] uniform. The camera whose direction is nearest the ray shows. */
    static float[] axes() {
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            double w = i * Math.PI / 2;
            out[i * 2]     = (float) Math.sin(w);
            out[i * 2 + 1] = (float) Math.cos(w);
        }
        return out;
    }

    /** World direction to lens direction for each camera, as four mat3 in
     * column order (what glUniformMatrix3fv takes with transpose = false).
     * The lens looks along +z. mountDeg is how far down each camera points. */
    static float[] rotations(float[] mountDeg) {
        float[] out = new float[36];
        for (int i = 0; i < 4; i++) {
            double w = i * Math.PI / 2, m = Math.toRadians(mountDeg[i]);
            double cw = Math.cos(w), sw = Math.sin(w), cm = Math.cos(m), sm = Math.sin(m);
            // Rows: turn to the camera's side, then tip down by the mount.
            double[][] r = {
                { cw,       0,   -sw      },
                { sw * sm,  cm,   cw * sm },
                { sw * cm, -sm,   cw * cm },
            };
            for (int col = 0; col < 3; col++)
                for (int row = 0; row < 3; row++)
                    out[i * 9 + col * 3 + row] = (float) r[row][col];
        }
        return out;
    }

    /** The world point a ray from the viewpoint lands on: the ball. The
     * ray must have length 1. */
    static double[] surface(double rx, double ry, double rz) {
        return new double[] { rx * BALL, EYE + ry * BALL, rz * BALL };
    }

    /** Where camera cam sees world point p: {u, v, rad}. u and v are in the
     * camera's cell, 0..1; rad is 1 at the edge of the lens. The same steps
     * as FRAG_ORBIT, for the tests. */
    static double[] cell(int cam, double[] p, float[] rot, double lensHalfRad, double squash) {
        double dx = p[0] - POS[cam][0], dy = p[1] - POS[cam][1], dz = p[2] - POS[cam][2];
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= n; dy /= n; dz /= n;
        int o = cam * 9;
        double x = rot[o] * dx + rot[o + 3] * dy + rot[o + 6] * dz;
        double y = rot[o + 1] * dx + rot[o + 4] * dy + rot[o + 7] * dz;
        double z = rot[o + 2] * dx + rot[o + 5] * dy + rot[o + 8] * dz;
        double rad = Math.acos(Math.max(-1, Math.min(1, z))) / lensHalfRad;
        double dl = Math.hypot(x, y);
        double ux = dl > 1e-9 ? -x / dl : 0, uy = dl > 1e-9 ? -y / dl : 0;
        return new double[] { 0.5 + ux * rad * 0.5, 0.5 + uy * rad * 0.5 * squash, rad };
    }

    /** How much camera cam counts for a ray (length 1): all of it where the
     * ray points most to that camera's side, none where another camera's side
     * is nearer by more than BAND, and a smooth mix between. Nothing where
     * the point is off its lens or off its cell. The same steps as FRAG_ORBIT. */
    static double weight(int cam, double rx, double rz, double[] cell) {
        if (cell[2] > 1.0 || cell[0] < 0 || cell[0] > 1 || cell[1] < 0 || cell[1] > 1) return 0;
        double w = cam * Math.PI / 2;
        double mine = rx * Math.sin(w) + rz * Math.cos(w);
        double best = Math.max(Math.abs(rx), Math.abs(rz));
        return smooth(0, 1, 1 - (best - mine) / BAND);
    }

    private static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
