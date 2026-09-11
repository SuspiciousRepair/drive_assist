package com.geely.drivemem;

import com.geely.drivemem.car.Telemetry;

import org.junit.Test;
import static org.junit.Assert.*;

public class TelemetryTest {

    @Test public void parkTimerLabelKnownCodes() {
        assertEquals("30 min", Telemetry.parkTimerLabel(0x02));
        assertEquals("1 h", Telemetry.parkTimerLabel(0x04));
        assertEquals("2 h", Telemetry.parkTimerLabel(0x06));
        // 3h/4h/5h: measured live from the car's own menu on 2026-09-02,
        // not extrapolated — see the comment above parkTimerLabel().
        assertEquals("3 h", Telemetry.parkTimerLabel(0x08));
        assertEquals("4 h", Telemetry.parkTimerLabel(0x0A));
        assertEquals("5 h", Telemetry.parkTimerLabel(0x0C));
        assertEquals("Ilimitado", Telemetry.parkTimerLabel(0x13));
    }

    @Test public void parkTimerLabelUnknownCodeFallsBackToHex() {
        assertEquals("cod 0x7F", Telemetry.parkTimerLabel(0x7F));
    }

    @Test public void parkTimerCodeRoundTripsWithLabel() {
        for (int code : new int[]{0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x13}) {
            String label = Telemetry.parkTimerLabel(code);
            assertEquals(code, Telemetry.parkTimerCode(label));
        }
    }

    @Test public void parkTimerCodeDefaultsToUnlimited() {
        assertEquals(0x13, Telemetry.parkTimerCode(null));
        assertEquals(0x13, Telemetry.parkTimerCode("nonsense"));
    }

    @Test public void gearLabelKnownValues() {
        assertEquals("N", Telemetry.gearLabel(1));
        assertEquals("R", Telemetry.gearLabel(2));
        assertEquals("P", Telemetry.gearLabel(4));
        assertEquals("D", Telemetry.gearLabel(8));
    }
}
