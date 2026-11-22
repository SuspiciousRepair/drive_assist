package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.*;

/** OrbitRig: the geometry the Orbit view paints the four cameras with. */
public class OrbitRigTest {
    private static final float[] MOUNT = {
        ClipPlayerActivity.MOUNT_FRONT, ClipPlayerActivity.MOUNT_SIDES,
        ClipPlayerActivity.MOUNT_REAR, ClipPlayerActivity.MOUNT_SIDES };
    private static final float[] ROT = OrbitRig.rotations(MOUNT);
    private static final float[][] CAM = {
        ClipPlayerActivity.FRONT, ClipPlayerActivity.RIGHT,
        ClipPlayerActivity.REAR, ClipPlayerActivity.LEFT };

    private static double[] cell(int cam, double[] p) {
        return OrbitRig.cell(cam, p, ROT, Math.toRadians(CAM[cam][6]), CAM[cam][7]);
    }

    /** A point 3 m along the line a camera looks down. */
    private static double[] onAxis(int cam) {
        float[] pos = OrbitRig.positions();
        double w = cam * Math.PI / 2, m = Math.toRadians(MOUNT[cam]);
        return new double[] {
            pos[cam * 3]     + 3 * Math.sin(w) * Math.cos(m),
            pos[cam * 3 + 1] - 3 * Math.sin(m),
            pos[cam * 3 + 2] + 3 * Math.cos(w) * Math.cos(m) };
    }

    @Test public void aPointOnTheCamerasAxisIsTheMiddleOfItsCell() {
        for (int cam = 0; cam < 4; cam++) {
            double[] c = cell(cam, onAxis(cam));
            assertEquals("cam " + cam, 0.5, c[0], 1e-4);
            assertEquals("cam " + cam, 0.5, c[1], 1e-4);
            assertEquals("cam " + cam, 0.0, c[2], 1e-4);
        }
    }

    @Test public void thePictureIsUpsideDownInTheCell() {
        // The clip arrives turned 180 degrees. A point above the front
        // camera's axis is in the lower half of its cell, and a point to its
        // right is in the left half.
        double[] mid = onAxis(OrbitRig.FRONT);
        double[] up = { mid[0], mid[1] + 1, mid[2] };
        double[] right = { mid[0] + 1, mid[1], mid[2] };
        assertTrue(cell(OrbitRig.FRONT, up)[1] < 0.5);
        assertTrue(cell(OrbitRig.FRONT, right)[0] < 0.5);
    }

    @Test public void theHorizonIsTheMountAngleFromTheMiddle() {
        // Far ahead at the camera's own height: off the axis by the mount.
        double[] far = { 0, 0.60, 1000 };
        double want = MOUNT[OrbitRig.FRONT] / CAM[OrbitRig.FRONT][6];
        assertEquals(want, cell(OrbitRig.FRONT, far)[2], 1e-3);
    }

    private static double[] ray(double yawDeg, double pitchDownDeg) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDownDeg);
        return new double[] { Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p) };
    }

    private static double weight(int cam, double[] r) {
        return OrbitRig.weight(cam, r[0], r[2], cell(cam, OrbitRig.surface(r[0], r[1], r[2])));
    }

    @Test public void everyRayLandsOnTheBall() {
        for (double[] r : new double[][] { ray(0, 0), ray(77, 30), ray(200, -20) }) {
            double[] p = OrbitRig.surface(r[0], r[1], r[2]);
            assertEquals(OrbitRig.BALL, Math.sqrt(p[0] * p[0]
                + (p[1] - OrbitRig.EYE) * (p[1] - OrbitRig.EYE) + p[2] * p[2]), 1e-9);
        }
    }

    @Test public void eachCameraOwnsItsOwnSide() {
        for (int own = 0; own < 4; own++) {
            double[] r = ray(own * 90, 15);
            assertEquals("cam " + own, 1.0, weight(own, r), 1e-9);
            for (int other = 0; other < 4; other++)
                if (other != own) assertEquals(own + " not " + other, 0.0, weight(other, r), 0.0);
        }
    }

    @Test public void aCameraDoesNotCountOffItsLens() {
        // Straight up is off the front lens: it points 26 degrees down and
        // sees about 95 degrees from its middle.
        double[] up = ray(0, -85);
        assertEquals(0.0, weight(OrbitRig.FRONT, up), 0.0);
    }

    @Test public void twoCamerasShareACornerAndTheMixHasNoStep() {
        // Turn round the front right corner. The front camera's share goes
        // from all to none with no jump, and only near the corner.
        double last = -1, max = 0;
        int mixed = 0;
        for (double deg = 30; deg <= 60; deg += 0.25) {
            double[] r = ray(deg, 15);
            double wf = weight(OrbitRig.FRONT, r), wr = weight(OrbitRig.RIGHT, r);
            assertTrue("a camera sees " + deg, wf + wr > 0);
            double share = wf / (wf + wr);
            if (deg == 30) assertEquals(1.0, share, 1e-9);
            if (deg == 45) assertEquals(0.5, share, 1e-9);
            if (deg == 60) assertEquals(0.0, share, 1e-9);
            if (share > 0 && share < 1) mixed++;
            if (last >= 0) max = Math.max(max, Math.abs(share - last));
            last = share;
        }
        assertTrue("largest step per quarter degree: " + max, max < 0.05);
        // Narrow: less than 20 degrees of the turn is a mix.
        assertTrue("mixed quarter degrees: " + mixed, mixed > 8 && mixed < 80);
    }

    @Test public void theUniformArraysHaveTheShaderLayout() {
        assertEquals(36, ROT.length);
        assertEquals(12, OrbitRig.positions().length);
        float[] ax = OrbitRig.axes();
        assertEquals(0f, ax[0], 1e-6f); assertEquals(1f, ax[1], 1e-6f);     // front: +z
        assertEquals(1f, ax[2], 1e-6f); assertEquals(0f, ax[3], 1e-6f);     // right: +x
        assertEquals(-1f, ax[5], 1e-6f);                                    // rear: -z
        assertEquals(-1f, ax[6], 1e-6f);                                    // left: -x
        // Column order: the right camera's lens x is world -z.
        assertEquals(-1f, ROT[9 + 6], 1e-6f);
    }

    // Where on the screen camera cam shows world point w: the viewpoint's
    // direction to the spot where cam's line of sight to w crosses the ball.
    private static double[] seenAt(int cam, double[] w) {
        float[] pos = OrbitRig.positions();
        double[] p = { pos[cam * 3], pos[cam * 3 + 1], pos[cam * 3 + 2] };
        double[] d = { w[0] - p[0], w[1] - p[1], w[2] - p[2] };
        double n = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        for (int k = 0; k < 3; k++) d[k] /= n;
        double[] o = { p[0], p[1] - OrbitRig.EYE, p[2] };
        double b = o[0] * d[0] + o[1] * d[1] + o[2] * d[2];
        double c = o[0] * o[0] + o[1] * o[1] + o[2] * o[2] - OrbitRig.BALL * OrbitRig.BALL;
        double t = -b + Math.sqrt(b * b - c);
        double[] x = { o[0] + t * d[0], o[1] + t * d[1], o[2] + t * d[2] };
        double m = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
        return new double[] { x[0] / m, x[1] / m, x[2] / m };
    }

    @Test public void aWallDownTheStreetShowsOnceWhereRearAndRightMeet() {
        // A wall 15 m back, right of the car, near the rear/right seam. On a
        // 6 m ball the two cameras put it 15 degrees apart: it showed two times.
        double a = Math.toRadians(140);
        double[] w = { 15 * Math.sin(a), OrbitRig.EYE, 15 * Math.cos(a) };
        double[] r = seenAt(OrbitRig.RIGHT, w), q = seenAt(OrbitRig.REAR, w);
        double deg = Math.toDegrees(Math.acos(r[0] * q[0] + r[1] * q[1] + r[2] * q[2]));
        assertTrue("apart " + deg, deg < 6);
    }
}
