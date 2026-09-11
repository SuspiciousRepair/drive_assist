package com.geely.drivemem.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Numeric CSV recorded from telemetry_sample, or authored as a synthetic scenario.
 * Each row is a complete observation; blanks mean unavailable, never carry-forward.
 * Replay advances virtual time without sleeping or connecting to Android services. */
public final class CarTrace {
    public static final String HEADER = "elapsed_ms,odo_km,speed_kmh,gear,is_charging,energy_spent_kwh,energy_regen_kwh,instant_power_kw_est";
    public static final class Sample {
        public final long elapsedMs;
        public final double odoKm, speedKmh, spentKwh, regenKwh, powerKw;
        public final Integer gear;
        public final Boolean charging;
        private Sample(String[] v) {
            elapsedMs = Long.parseLong(v[0]);
            odoKm = number(v[1]); speedKmh = number(v[2]);
            gear = v[3].isEmpty() ? null : Integer.valueOf(v[3]);
            if (!v[4].isEmpty() && !v[4].equals("0") && !v[4].equals("1"))
                throw new IllegalArgumentException("is_charging must be blank, 0 or 1");
            charging = v[4].isEmpty() ? null : v[4].equals("1");
            spentKwh = number(v[5]); regenKwh = number(v[6]); powerKw = number(v[7]);
        }
        private static double number(String s) { return s.isEmpty() ? Double.NaN : Double.parseDouble(s); }
    }
    private final List<Sample> samples = new ArrayList<>();
    public static CarTrace read(Reader input) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        if (!HEADER.equals(reader.readLine())) throw new IOException("Unexpected car trace header");
        CarTrace trace = new CarTrace();
        String line; long previous = -1; int row = 1;
        while ((line = reader.readLine()) != null) {
            row++;
            String[] values = line.split(",", -1);
            try {
                if (values.length != 8) throw new IllegalArgumentException("Expected 8 columns");
                Sample sample = new Sample(values);
                if (sample.elapsedMs < 0 || sample.elapsedMs < previous)
                    throw new IllegalArgumentException("Time must be nonnegative and chronological");
                previous = sample.elapsedMs;
                trace.samples.add(sample);
            } catch (IllegalArgumentException e) { throw new IOException("Invalid car trace row " + row, e); }
        }
        if (trace.samples.isEmpty()) throw new IOException("Empty car trace");
        return trace;
    }
    public void replay(FakeCarAccess car, Consumer<Sample> consumer) {
        for (Sample sample : samples) {
            car.observe(sample);
            consumer.accept(sample);
        }
    }
}
