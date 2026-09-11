package com.geely.drivemem.sensors;

import com.geely.drivemem.support.CarTrace;
import com.geely.drivemem.support.FakeCarAccess;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class CarTraceConsumptionTest {
    @Test public void replaySeparatesParkTrafficChargingAndSpeedBoundaries() throws Exception {
        DrivingConsumption stats = new DrivingConsumption();
        FakeCarAccess car = new FakeCarAccess();
        try (InputStreamReader input = new InputStreamReader(
                getClass().getResourceAsStream("/car/park-traffic-charge.csv"), StandardCharsets.UTF_8)) {
            CarTrace.read(input).replay(car, sample -> stats.add(sample.elapsedMs,
                sample.odoKm, car.readSpeed() == null ? Double.NaN : car.readSpeed(),
                car.readGear(), Boolean.TRUE.equals(sample.charging),
                sample.spentKwh, sample.regenKwh, sample.powerKw));
        }
        assertEquals(1.4, stats.totalSpent, 1e-9);
        assertEquals(.05, stats.totalRegen, 1e-9);
        assertArrayEquals(new double[]{.5, .2, .3, .4}, stats.spent, 1e-9);
        assertArrayEquals(new double[]{2, 1, 1, 1}, stats.distance, 1e-9);
    }
}
