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

    @Test public void aRayDownLandsOnTheGroundAndALevelRayOnTheWall() {
        double s = Math.sqrt(0.5);
        double[] g = OrbitRig.surface(0, -s, s);            // 45 degrees down
        assertEquals(0.0, g[1], 1e-9);
        assertEquals(OrbitRig.EYE, g[2], 1e-9);
        double[] w = OrbitRig.surface(1, 0, 0);
        assertEquals(OrbitRig.WALL, w[0], 1e-9);
        assertEquals(OrbitRig.EYE, w[1], 1e-9);
        // Up is the wall too, above the eye.
        double[] u = OrbitRig.surface(0, s, s);
        assertEquals(OrbitRig.WALL, u[2], 1e-9);
        assertTrue(u[1] > OrbitRig.EYE);
    }

    @Test public void theGroundMeetsTheWallWithNoJump() {
        // The ray that hits the foot of the wall: a hair above and a hair
        // below must land at the same place.
        double a = Math.atan2(OrbitRig.EYE, OrbitRig.WALL);
        double[] lo = OrbitRig.surface(0, -Math.sin(a + 1e-6), Math.cos(a + 1e-6));
        double[] hi = OrbitRig.surface(0, -Math.sin(a - 1e-6), Math.cos(a - 1e-6));
        for (int k = 0; k < 3; k++) assertEquals(lo[k], hi[k], 1e-3);
    }

    @Test public void underTheCarIsOnlyTheGroundUnderTheCar() {
        assertTrue(OrbitRig.underCar(new double[] { 0.5, 0, 1.0 }));
        assertFalse(OrbitRig.underCar(new double[] { 1.5, 0, 1.0 }));
        assertFalse(OrbitRig.underCar(new double[] { 0.5, 0, 3.0 }));
        assertFalse(OrbitRig.underCar(new double[] { 0.5, 1.2, 1.0 }));
    }

    @Test public void eachCameraOwnsTheGroundOnItsSide() {
        double[][] spot = { { 0, 0, 4 }, { 2.5, 0, 0.6 }, { 0, 0, -4 }, { -2.5, 0, 0.6 } };
        for (int own = 0; own < 4; own++) {
            double best = OrbitRig.weight(own, spot[own], cell(own, spot[own]));
            assertTrue("cam " + own, best > 0.05);
            for (int other = 0; other < 4; other++) {
                if (other == own) continue;
                double w = OrbitRig.weight(other, spot[own], cell(other, spot[own]));
                assertTrue(own + " over " + other, best > 20 * w);
            }
        }
    }

    @Test public void aCameraDoesNotShowWhatIsBehindItsSide() {
        // The right mirror camera sees the left side of the road only
        // through the car. It must not count there.
        double[] left = { -3, 0, 0.6 };
        assertEquals(0.0, OrbitRig.weight(OrbitRig.RIGHT, left, cell(OrbitRig.RIGHT, left)), 0.0);
        // Nor the front camera a point behind the car.
        double[] behind = { 0, 0, -4 };
        assertEquals(0.0, OrbitRig.weight(OrbitRig.FRONT, behind, cell(OrbitRig.FRONT, behind)), 0.0);
    }

    @Test public void twoCamerasShareACornerAndTheMixHasNoStep() {
        // Walk on the ground round the front right corner, 3 m out. The
        // front camera's share goes from most to least with no jump.
        double last = -1, first = -1, max = 0;
        for (int deg = 10; deg <= 80; deg++) {
            double a = Math.toRadians(deg);
            double[] p = { 3 * Math.sin(a), 0, 3 * Math.cos(a) };
            double wf = OrbitRig.weight(OrbitRig.FRONT, p, cell(OrbitRig.FRONT, p));
            double wr = OrbitRig.weight(OrbitRig.RIGHT, p, cell(OrbitRig.RIGHT, p));
            assertTrue("a camera sees " + deg, wf + wr > 0);
            double share = wf / (wf + wr);
            if (first < 0) first = share;
            if (last >= 0) max = Math.max(max, Math.abs(share - last));
            last = share;
        }
        assertTrue("front leads ahead: " + first, first > 0.9);
        assertTrue("right leads at the side: " + last, last < 0.1);
        // The mix is narrow on purpose (about 8 degrees): a wide one shows a
        // thing that stands up from the ground two times. Narrow, not a cut.
        assertTrue("largest step per degree: " + max, max < 0.25);
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
}
