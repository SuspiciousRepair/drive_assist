package com.geely.drivemem.ui;

/** Where the four cameras are on the car, and the surface the Orbit view
 * paints them on. Pure math, no Android: the shader gets its numbers from
 * here, and the tests check the same numbers.
 *
 * The Orbit view used to treat the four cameras as one point. They are up to
 * 4 m apart, so a thing near the car showed in two different places where two
 * cameras meet. Now each screen ray first finds a point in the world (the
 * ground, or a wall around the car), and each camera then looks at that point
 * from its own place. The ground is where the cameras agree best, and the
 * ground is most of what is near a car.
 *
 * Axes: x to the right, y up, z to the front. The origin is the middle of the
 * car, on the ground. Camera order is yaw order: front, right, rear, left. */
final class OrbitRig {
    static final int FRONT = 0, RIGHT = 1, REAR = 2, LEFT = 3;

    /** Height of the viewpoint above the ground, metres. */
    static final float EYE = 1.0f;
    /** Distance of the wall that stands in for everything above the ground. A
     * far thing and a near thing cannot both line up; 10 m splits the error. */
    static final float WALL = 10f;
    /** Half the car's width and length: the ground no camera can see. */
    static final float HALF_W = 0.92f, HALF_L = 2.08f;

    /** Camera positions, metres. Taken from the car's size (4.14 x 1.81 m),
     * not measured with a tape. Checked against paving stones in parked
     * clips: the stones line up between the side and rear cameras. */
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
     * vec2[4] uniform. A camera shows only what is on its own side; behind
     * that line it sees the car's own body. */
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

    /** The world point a ray from the viewpoint lands on: the ground if it
     * gets there first, else the wall. The ray must have length 1. */
    static double[] surface(double rx, double ry, double rz) {
        double flat = Math.max(Math.hypot(rx, rz), 1e-4);
        double t = WALL / flat;
        if (ry < -1e-4) t = Math.min(t, EYE / -ry);
        return new double[] { rx * t, EYE + ry * t, rz * t };
    }

    /** True when the point is on the ground under the car. */
    static boolean underCar(double[] p) {
        return p[1] < 1e-3 && Math.abs(p[0]) < HALF_W && Math.abs(p[2]) < HALF_L;
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

    /** How much camera cam counts at a point: most in the middle of its lens,
     * nothing at the edge, at the border of its cell, or behind its own side.
     * Two cameras that both see a point are mixed by these weights, so there
     * is no line where one stops and the next starts. */
    static double weight(int cam, double[] p, double[] cell) {
        double dx = p[0] - POS[cam][0], dy = p[1] - POS[cam][1], dz = p[2] - POS[cam][2];
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double w = cam * Math.PI / 2;
        double side = smooth(0.02, 0.22, (dx * Math.sin(w) + dz * Math.cos(w)) / n);
        double edge = Math.min(Math.min(cell[0], 1 - cell[0]), Math.min(cell[1], 1 - cell[1]));
        double border = Math.max(0, Math.min(1, edge / 0.06));
        double k = Math.max(0, 1 - cell[2] / 0.97);
        double k2 = k * k, k4 = k2 * k2;
        return k4 * k4 * border * side;
    }

    private static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
