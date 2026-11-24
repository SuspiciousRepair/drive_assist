package com.geely.drivemem.util;

import com.geely.drivemem.sensors.DailyStatsProvider.DriveSession;
import com.geely.drivemem.sensors.EnergySource;
import com.geely.drivemem.state.ChargeSession;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public class CsvExportTest {
    private static final TimeZone SP = TimeZone.getTimeZone("America/Sao_Paulo");
    // 2026-11-14 10:00 in Sao Paulo (UTC-3, no DST).
    private static final long T0 = 1_794_661_200_000L;
    private static final long MIN = 60_000L;

    private static final java.util.Map<Long, double[]> NONE = Collections.emptyMap();

    private static String[] lines(String csv) { return csv.split("\r\n", -1); }

    @Test public void chargeRowCarriesTypedCostAndDerivedRate() {
        ChargeSession.Summary ac = new ChargeSession.Summary(7, T0, T0 + 95 * MIN, 20, 80,
            30.0, 7200.0, 228.0, 100, 12345.6, 45.0, false);
        String[] l = lines(CsvExport.chargesCsv(Collections.singletonList(ac), -1, NONE, SP));
        assertEquals(CsvExport.CHARGES_HEADER, l[0]);
        assertEquals("7,2026-11-14 10:00,2026-11-14 11:35,95,AC,20,80,30.00,7.20,228,12345.6,45.00,1.50,,", l[1]);
        assertEquals("", l[2]);
        assertEquals(3, l.length);
    }

    // No cost typed and no voltage recorded: the fields stay empty, not 0,
    // so a spreadsheet SUM/AVERAGE does not count them as free charges.
    @Test public void unknownValuesAreEmptyNotZero() {
        ChargeSession.Summary legacy = new ChargeSession.Summary(3, T0, T0 + 30 * MIN, 50, 60,
            5.0, 10_000.0, Double.NaN, 10, -1, null, false);
        String row = lines(CsvExport.chargesCsv(Collections.singletonList(legacy), -1, NONE, SP))[1];
        assertEquals("3,2026-11-14 10:00,2026-11-14 10:30,30,,50,60,5.00,10.00,,,,,,", row);
    }

    @Test public void dcFromVoltage() {
        ChargeSession.Summary dc = new ChargeSession.Summary(4, T0, T0 + 20 * MIN, 10, 70,
            40.0, 120_000.0, 395.0, 10, 100.0, null, false);
        String row = lines(CsvExport.chargesCsv(Collections.singletonList(dc), -1, NONE, SP))[1];
        assertEquals("DC", row.split(",", -1)[4]);
    }

    @Test public void chargeInProgressIsLeftOut() {
        ChargeSession.Summary done = new ChargeSession.Summary(1, T0, T0 + MIN, 1, 2,
            1.0, 1000.0, 230.0, 1, 1.0, null, false);
        ChargeSession.Summary open = new ChargeSession.Summary(2, T0 + 2 * MIN, T0 + 3 * MIN, 2, 3,
            1.0, 1000.0, 230.0, 1, 1.0, null, false);
        String[] l = lines(CsvExport.chargesCsv(Arrays.asList(done, open), 2, NONE, SP));
        assertEquals(3, l.length);
        assertEquals("1,", l[1].substring(0, 2));
    }

    @Test public void tripRow() {
        DriveSession t = new DriveSession(9, T0, T0 + 42 * MIN, 31.24, 80, 72,
            120.4, 98.6, 0.8, 5.3, 4.5, 14.4, EnergySource.MEASURED);
        String[] l = lines(CsvExport.tripsCsv(Collections.singletonList(t), NONE, SP));
        assertEquals(CsvExport.TRIPS_HEADER, l[0]);
        assertEquals("9,2026-11-14 10:00,2026-11-14 10:42,42,31.2,80,72,4.50,5.30,0.80,144,measured,120,99,,,,", l[1]);
    }

    // A trip still being driven has end 0; a trip with no energy data
    // leaves its energy columns empty instead of claiming 0 kWh.
    @Test public void openTripSkippedAndNoDataEnergyEmpty() {
        DriveSession open = new DriveSession(1, T0, 0, 0, 80, -1, 0, 0, 0, 0, 0, 0, EnergySource.NO_DATA);
        DriveSession noData = new DriveSession(2, T0, T0 + 10 * MIN, 5.0, -1, -1, 0, 0, 0, 0, 0, 0, EnergySource.NO_DATA);
        String[] l = lines(CsvExport.tripsCsv(Arrays.asList(open, noData), NONE, SP));
        assertEquals(3, l.length);
        assertEquals("2,2026-11-14 10:00,2026-11-14 10:10,10,5.0,,,,,,,no_data,0,0,,,,", l[1]);
    }

    @Test public void placesGoInTheLastColumns() {
        ChargeSession.Summary ac = new ChargeSession.Summary(7, T0, T0 + 95 * MIN, 20, 80,
            30.0, 7200.0, 228.0, 100, 12345.6, 45.0, false);
        java.util.Map<Long, double[]> at = new java.util.HashMap<>();
        at.put(7L, new double[]{-10.5, -50.25});
        String charge = lines(CsvExport.chargesCsv(Collections.singletonList(ac), -1, at, SP))[1];
        assertEquals(true, charge.endsWith(",1.50,-10.500000,-50.250000"));

        DriveSession t = new DriveSession(9, T0, T0 + 42 * MIN, 31.24, 80, 72,
            120.4, 98.6, 0.8, 5.3, 4.5, 14.4, EnergySource.MEASURED);
        java.util.Map<Long, double[]> ends = new java.util.HashMap<>();
        ends.put(9L, new double[]{-10.5, -50.25, -10.6, -50.3});
        String trip = lines(CsvExport.tripsCsv(Collections.singletonList(t), ends, SP))[1];
        assertEquals(true, trip.endsWith(",120,99,-10.500000,-50.250000,-10.600000,-50.300000"));
    }

    @Test public void fieldQuoting() {
        assertEquals("plain", CsvExport.field("plain"));
        assertEquals("\"a,b\"", CsvExport.field("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.field("say \"hi\""));
        assertEquals("", CsvExport.field(null));
    }

    @Test public void numberUsesDotWhateverTheLocale() {
        java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("pt", "BR"));
            assertEquals("1.50", CsvExport.num(1.5, 2));
            assertEquals("", CsvExport.num(Double.NaN, 2));
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }

    @Test public void diaryCsvQuotesNotesAndOrdersChronologically() {
        com.geely.drivemem.state.CarDiary.Entry older = new com.geely.drivemem.state.CarDiary.Entry(
            1, "u1", T0, com.geely.drivemem.state.CarDiary.KIND_CARE,
            com.geely.drivemem.state.CarDiary.Category.INSURANCE,
            com.geely.drivemem.state.CarDiary.STATUS_DONE,
            6128.5, 24.0, null, null, 3600.0, "Seguro anual", "Porto, 12x", null, null, null, null, 1);
        com.geely.drivemem.state.CarDiary.Entry newer = new com.geely.drivemem.state.CarDiary.Entry(
            2, "u2", T0 + 60 * MIN, com.geely.drivemem.state.CarDiary.KIND_INCIDENT,
            com.geely.drivemem.state.CarDiary.Category.INCIDENT,
            com.geely.drivemem.state.CarDiary.STATUS_OPEN,
            6150.0, null, -23.550520, -46.633308, null, null, "Pothole", null, "incident_abc.mp4", null, null, 1);
        String[] l = lines(CsvExport.diaryCsv(Arrays.asList(newer, older), SP));
        assertEquals(CsvExport.DIARY_HEADER, l[0]);
        assertEquals("1,2026-11-14 10:00,bureaucracy,insurance,Seguro anual,done,6128.5,3600.00,\"Porto, 12x\",,24.0,,,,1", l[1]);
        assertEquals("2,2026-11-14 11:00,notes,incident,,open,6150.0,,Pothole,,,-23.550520,-46.633308,incident_abc.mp4,1", l[2]);
    }

    @Test public void textGuardsSpreadsheetFormulasButNumbersStayNumbers() {
        assertEquals("'=1+1", CsvExport.text("=1+1"));
        assertEquals("'+55 11", CsvExport.text("+55 11"));
        assertEquals("'-x", CsvExport.text("-x"));
        assertEquals("'@cmd", CsvExport.text("@cmd"));
        assertEquals("'\t=1", CsvExport.text("\t=1"));
        assertEquals("'\r=1", CsvExport.text("\r=1"));
        assertEquals("a=b", CsvExport.text("a=b"));
        assertEquals("", CsvExport.text(null));
        // Numeric columns do not go through text(): a negative stays a number.
        assertEquals("-23.550520", CsvExport.field(CsvExport.num(-23.55052, 6)));
    }

    @Test public void diaryCsvKeepsFormulaQuotesAndNewlineAsText() {
        com.geely.drivemem.state.CarDiary.Entry e = new com.geely.drivemem.state.CarDiary.Entry(
            3, "u3", T0, com.geely.drivemem.state.CarDiary.KIND_CARE,
            com.geely.drivemem.state.CarDiary.Category.OTHER,
            com.geely.drivemem.state.CarDiary.STATUS_DONE,
            null, null, -23.550520, -46.633308, null,
            "=SUM(A1)", "He said \"hi\"\nline2", "-a", null, null, null, 1);
        String csv = CsvExport.diaryCsv(Collections.singletonList(e), SP);
        String[] l = lines(csv);
        assertEquals(3, l.length); // header, one row (its newline is inside quotes), end
        assertEquals("3,2026-11-14 10:00,notes,other,'=SUM(A1),done,,,"
            + "\"He said \"\"hi\"\"\nline2\",'-a,,-23.550520,-46.633308,,1", l[1]);
    }
}
