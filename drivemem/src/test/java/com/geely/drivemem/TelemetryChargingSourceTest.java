package com.geely.drivemem;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.Telemetry;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Map;

/** Telemetry.read() must trust ONLY the authoritativeCharging it's handed
 * (CarActor's single "car.is_charging" poll) and never re-derive its own
 * charging guess from the raw charge_a prop — see the 2026-09-23 bug where
 * a second, uncorrected copy of that guess kept reporting "charging" while
 * driving with nothing plugged in, because charge_a latches non-zero and
 * doesn't fall back to 0 on its own. */
public class TelemetryChargingSourceTest {

    /** Answers only charge_a (605291008); every other prop reads as
     * unavailable so Telemetry.read() skips those fields entirely. */
    static class FakeCar extends CarAccess {
        private final float chargeA;
        FakeCar(float chargeA) { this.chargeA = chargeA; }
        @Override public String readAny(int prop, int area, char t) {
            return (prop == 605291008) ? String.valueOf(chargeA) : null;
        }
        @Override public Boolean readBool(int prop, int area) { return null; }
    }

    @Test public void latchedCurrentIgnoredWhenAuthoritySaysNotCharging() {
        // Same shape as the live bug: charge_a still reads real current from
        // an earlier charge (latched), but CarActor's own poll -- which
        // cross-checks the plug -- says charging has stopped.
        Map<String, Object> data = Telemetry.read(new FakeCar(11.4f), false);
        assertEquals(0, data.get("is_charging"));
        assertEquals(0f, (Float) data.get("charge_a"), 0.001f);
    }

    @Test public void authoritySaysChargingIsTrusted() {
        Map<String, Object> data = Telemetry.read(new FakeCar(11.4f), true);
        assertEquals(1, data.get("is_charging"));
        assertEquals(11.4f, (Float) data.get("charge_a"), 0.001f);
    }

    @Test public void unknownAuthorityIsLeftOutNotGuessed() {
        // Cold start: CarActor's poll hasn't produced a reading yet. Unknown
        // must stay unknown -- not collapsed into a guessed "not charging",
        // which is the same mistake this fix removes, just relocated.
        Map<String, Object> data = Telemetry.read(new FakeCar(11.4f), null);
        assertFalse("is_charging must be absent, not defaulted, when unknown",
            data.containsKey("is_charging"));
        // Raw sensor reading is still reported as-is -- only the derived
        // is_charging claim is withheld, not the underlying data.
        assertEquals(11.4f, (Float) data.get("charge_a"), 0.001f);
    }
}
