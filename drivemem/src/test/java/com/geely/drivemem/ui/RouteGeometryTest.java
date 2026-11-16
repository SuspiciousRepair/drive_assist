package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RouteGeometryTest {

    @Test public void speedBandsIncludeTheirUpperLimit() {
        assertEquals(0, RouteGeometry.band(0));
        assertEquals(0, RouteGeometry.band(20));
        assertEquals(1, RouteGeometry.band(20.1));
        assertEquals(2, RouteGeometry.band(80));
        assertEquals(3, RouteGeometry.band(110));
        assertEquals(4, RouteGeometry.band(140));
    }

    // 0.01 deg of latitude is about 1112 m; at -23.5 a degree of longitude
    // is cos(23.5) = 0.917 of that.
    @Test public void projectionIsMetresAroundTheCentreNorthUp() {
        double[][] xy = RouteGeometry.project(
            new double[]{-23.51, -23.50}, new double[]{-46.61, -46.60});
        assertEquals(-556, xy[1][0], 2);   // south of centre
        assertEquals(556, xy[1][1], 2);    // north of centre
        assertEquals(-510, xy[0][0], 2);   // west
        assertEquals(510, xy[0][1], 2);    // east
    }

    @Test public void fitKeepsAspectAndPadding() {
        // 1000 m wide, 500 m tall, into 1000 x 1000 px with 100 px padding.
        RouteGeometry.Fit f = RouteGeometry.fit(
            new double[]{-500, 500}, new double[]{-250, 250}, 1000, 1000, 100);
        assertEquals(0.8, f.pxPerM, 1e-9);
        assertEquals(100, f.x(-500), 1e-3);
        assertEquals(900, f.x(500), 1e-3);
        assertEquals(300, f.y(250), 1e-3);   // north is up
        assertEquals(700, f.y(-250), 1e-3);
    }

    @Test public void tinyRouteIsNotBlownUp() {
        RouteGeometry.Fit f = RouteGeometry.fit(new double[]{0, 5}, new double[]{0, 5}, 1000, 1000, 0);
        assertTrue(f.pxPerM <= 1000 / 200.0 + 1e-9);
    }

    @Test public void scaleBarLengthsAreRound() {
        assertEquals(500, RouteGeometry.niceLengthM(740), 1e-9);
        assertEquals(2000, RouteGeometry.niceLengthM(2600), 1e-9);
        assertEquals(1000, RouteGeometry.niceLengthM(1000), 1e-9);
        assertEquals("2 km", RouteGeometry.lengthLabel(2000));
        assertEquals("500 m", RouteGeometry.lengthLabel(500));
    }
}
