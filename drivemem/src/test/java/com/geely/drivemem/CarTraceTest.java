package com.geely.drivemem;

import com.geely.drivemem.support.CarTrace;
import com.geely.drivemem.support.FakeCarAccess;
import java.io.IOException;
import java.io.StringReader;
import org.junit.Test;
import static org.junit.Assert.*;

public class CarTraceTest {
    @Test public void missingReadingsDoNotReusePreviousValues() throws Exception {
        CarTrace trace = CarTrace.read(new StringReader(CarTrace.HEADER + "\n"
            + "0,100,20,8,0,.1,0,12\n15,,,,,,,\n"));
        FakeCarAccess car = new FakeCarAccess();
        trace.replay(car, row -> {
            if (row.elapsedMs == 0) {
                assertEquals(Integer.valueOf(8), car.readGear());
                assertEquals(Float.valueOf(20), car.readSpeed());
                assertEquals(Integer.valueOf(FakeCarAccess.CHARGE_OFF), car.readCharging());
            } else {
                assertNull(car.readGear()); assertNull(car.readSpeed()); assertNull(car.readCharging());
                assertTrue(Double.isNaN(row.spentKwh));
            }
        });
    }
    @Test public void disconnectReturnsUnavailableAndReconnectRestoresObservation() throws Exception {
        FakeCarAccess car = new FakeCarAccess();
        CarTrace.read(new StringReader(CarTrace.HEADER + "\n0,100,0,4,1,0,0,-7\n")).replay(car, row -> {
            assertEquals(Integer.valueOf(FakeCarAccess.CHARGE_ON), car.readCharging());
            car.disconnect();
            assertFalse(car.isReady()); assertNull(car.readGear()); assertNull(car.readCharging());
            assertTrue(car.connect(null));
            assertEquals(Integer.valueOf(4), car.readGear());
        });
    }
    @Test(expected = IOException.class) public void rejectsOutOfOrderTime() throws Exception {
        CarTrace.read(new StringReader(CarTrace.HEADER + "\n20,,,,,,,\n10,,,,,,,\n"));
    }
    @Test(expected = IOException.class) public void rejectsUnknownChargingCodes() throws Exception {
        CarTrace.read(new StringReader(CarTrace.HEADER + "\n0,100,0,4,2,0,0,0\n"));
    }
    @Test(expected = IOException.class) public void rejectsTruncatedRows() throws Exception {
        CarTrace.read(new StringReader(CarTrace.HEADER + "\n0,100\n"));
    }
}
