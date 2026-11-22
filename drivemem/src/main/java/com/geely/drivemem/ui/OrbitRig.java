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
     * a near thing cannot both line up; 6 m looked best on parked clips. */
    static final float BALL = 6f;
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

    private OrbitRig() { }

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
