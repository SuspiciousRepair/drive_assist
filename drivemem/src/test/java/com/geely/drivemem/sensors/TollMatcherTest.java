package com.geely.drivemem.sensors;

import com.geely.drivemem.state.CarDiary;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TollMatcherTest {

    @Test public void coarsePrivacyTilesSnapToHalfDegreeGrid() {
        assertEquals("-23.5,-46.5", TollMatcher.tileKey(-23.1234, -46.4891));
        double[] b = TollMatcher.tileBounds("-23.5,-46.5");
        assertEquals(-23.51, b[0], 1e-6);
        assertEquals(-46.51, b[1], 1e-6);
        assertEquals(-22.99, b[2], 1e-6);
        assertEquals(-45.99, b[3], 1e-6);
    }

    @Test public void parsesSimpleAndWeekdayWeekendOsmCharges() {
        assertEquals(13.00, TollMatcher.parseMotorcarCharge(
            "13.00BRL/motorcar;6.50BRL/motorcycle;13.00BRL/hgv/axle", false), 1e-4);
        assertEquals(5.44, TollMatcher.parseMotorcarCharge(
            "Mo-Fr 5.44BRL/motorcar;Sa-Su 1.81BRL/motorcar;Mo-Fr 0.00BRL/motorcycle", false), 1e-4);
        assertEquals(1.81, TollMatcher.parseMotorcarCharge(
            "Mo-Fr 5.44BRL/motorcar;Sa-Su 1.81BRL/motorcar;Mo-Fr 0.00BRL/motorcycle", true), 1e-4);
        assertNull(TollMatcher.parseMotorcarCharge("", false));
    }

    @Test public void resolvesRealPlazaNameFromNoteWhenNameBlankOrGeneric() {
        assertEquals("Jambeiro - 2", TollMatcher.resolveName(null, "Jambeiro - 2", null, "Tamoios"));
        assertEquals("Tatuapé", TollMatcher.resolveName("Pedágio free flow", "Tatuapé", null, "CCR RioSP"));
        assertEquals("Plaza Alpha", TollMatcher.resolveName("Plaza Alpha", "note", "SP-070", "Op"));
    }

    @Test public void parsesOverpassJsonAndDedupesStaleDuplicateOperatorNode() {
        String json = "{\"elements\":["
            + "{\"type\":\"node\",\"id\":101,\"lat\":-20.0000,\"lon\":-40.0000,\"tags\":{"
            + "  \"barrier\":\"toll_booth\",\"operator\":\"OldOp\",\"direction\":\"243\","
            + "  \"charge\":\"5.40BRL/motorcar\",\"check_date\":\"2025-07-10\",\"note\":\"Plaza A\"}},"
            + "{\"type\":\"node\",\"id\":102,\"lat\":-20.0010,\"lon\":-40.0010,\"tags\":{"
            + "  \"barrier\":\"toll_booth\",\"operator\":\"NewOp\",\"direction\":\"245\","
            + "  \"charge\":\"5.70BRL/motorcar\",\"check_date\":\"2026-07-01\",\"note\":\"Plaza A - 2\"}},"
            + "{\"type\":\"node\",\"id\":103,\"lat\":-20.0010,\"lon\":-40.0008,\"tags\":{"
            + "  \"barrier\":\"toll_booth\",\"operator\":\"NewOp\",\"direction\":\"62\","
            + "  \"charge\":\"5.70BRL/motorcar\",\"check_date\":\"2026-07-01\",\"note\":\"Plaza A - 1\"}}"
            + "]}";
        List<TollMatcher.Booth> booths = TollMatcher.parseOverpassJson(json);
        // Same-direction duplicate collapsed (keeping newer 2026-07-01 node); opposite direction kept separate.
        assertEquals(2, booths.size());
        TollMatcher.Booth wb = booths.get(0).bearingDeg > 180 ? booths.get(0) : booths.get(1);
        assertEquals("Plaza A - 2", wb.name);
        assertEquals(5.70, wb.catalogPrice, 1e-4);
    }

    @Test public void matchesExpressLane65mAwayWhileRejectingCloserOppositeCarriageway() {
        // Synthetic north-bound road along lon=-40.0000; heading 0 deg.
        // Express lane is ~65 m west of north-bound cash canopy (dir=0) and ~18 m east of south-bound booth (dir=180).
        TollMatcher.Booth northBooth = new TollMatcher.Booth(1, "node/1", "North Plaza", "Op",
            TollMatcher.KIND_PLAZA, -20.0000, -39.99938, 80.0, 0.0, 45.0,
            "5.70BRL/motorcar", 5.70, null, "2026-07-01");
        TollMatcher.Booth southBooth = new TollMatcher.Booth(2, "node/2", "South Plaza", "Op",
            TollMatcher.KIND_PLAZA, -20.0000, -40.00017, 80.0, 180.0, 45.0,
            "5.70BRL/motorcar", 5.70, null, "2026-07-01");
        TollMatcher.SpatialIndex idx = new TollMatcher.SpatialIndex(Arrays.asList(northBooth, southBooth));

        // 120 km/h track with ~110 m spacing straddling lat=-20.0000
        long t0 = Instant.parse("2026-11-22T15:00:00Z").toEpochMilli(); // Sunday
        long[] ts = {t0, t0 + 4_000, t0 + 8_000, t0 + 12_000};
        double[] lat = {-20.0020, -20.0006, -19.9994, -19.9980};
        double[] lon = {-40.0000, -40.0000, -40.0000, -40.0000};
        double[] spd = {110, 110, 110, 110};

        List<TollMatcher.Hit> hits = TollMatcher.matchTrack(idx, ts, lat, lon, spd, ZoneOffset.UTC);
        assertEquals(1, hits.size());
        assertEquals("North Plaza", hits.get(0).booth.name);
        assertEquals(5.70, hits.get(0).cost, 1e-4);
    }

    @Test public void freeFlowEvaluatesWeekendVsWeekdayTariffByCrossingTimestamp() {
        TollMatcher.Booth gantry = new TollMatcher.Booth(10, "node/10", "Dutra Gantry", "CCR",
            TollMatcher.KIND_FREE_FLOW, -20.0000, -40.0000, 45.0, 0.0, 45.0,
            "Mo-Fr 5.44BRL/motorcar;Sa-Su 1.81BRL/motorcar", 5.44, null, "2026-07-01");
        TollMatcher.SpatialIndex idx = new TollMatcher.SpatialIndex(Collections.singletonList(gantry));

        long thu = Instant.parse("2026-11-19T20:55:00Z").toEpochMilli();
        long sun = Instant.parse("2026-11-22T15:55:00Z").toEpochMilli();
        double[] lat = {-20.0010, -19.9990};
        double[] lon = {-40.0000, -40.0000};
        double[] spd = {90, 90};

        List<TollMatcher.Hit> thuHits = TollMatcher.matchTrack(idx, new long[]{thu, thu + 8_000}, lat, lon, spd, ZoneOffset.UTC);
        List<TollMatcher.Hit> sunHits = TollMatcher.matchTrack(idx, new long[]{sun, sun + 8_000}, lat, lon, spd, ZoneOffset.UTC);
        assertEquals(1, thuHits.size());
        assertEquals(5.44, thuHits.get(0).cost, 1e-4);
        assertEquals(1, sunHits.size());
        assertEquals(1.81, sunHits.get(0).cost, 1e-4);
    }

    @Test public void matchesShortTunnelGpsGapButRejectsImpossibleTeleport() {
        TollMatcher.Booth plaza = new TollMatcher.Booth(1, "node/1", "Tunnel Exit Plaza", "Op",
            TollMatcher.KIND_PLAZA, -20.0000, -40.0000, 80.0, 0.0, 45.0,
            "13.00BRL/motorcar", 13.00, null, "2026-07-01");
        TollMatcher.SpatialIndex idx = new TollMatcher.SpatialIndex(Collections.singletonList(plaza));

        long t0 = Instant.parse("2026-11-22T16:00:00Z").toEpochMilli();
        // 800 m gap over 30 s (~96 km/h) -> plausible tunnel/canopy gap, MUST match
        List<TollMatcher.Hit> ok = TollMatcher.matchTrack(idx,
            new long[]{t0, t0 + 30_000},
            new double[]{-20.0036, -19.9964},
            new double[]{-40.0000, -40.0000},
            new double[]{95, 95}, ZoneOffset.UTC);
        assertEquals(1, ok.size());

        // Same 800 m gap in 2 s (~1440 km/h) -> GPS teleport, MUST reject
        List<TollMatcher.Hit> bad = TollMatcher.matchTrack(idx,
            new long[]{t0, t0 + 2_000},
            new double[]{-20.0036, -19.9964},
            new double[]{-40.0000, -40.0000},
            new double[]{95, 95}, ZoneOffset.UTC);
        assertTrue(bad.isEmpty());
    }

    @Test public void carDiaryTcoIncludesAutoTollCostInRoadBucketWithoutDiaryEvents() {
        CarDiary.TcoSummary s = CarDiary.computeTco(
            100.0, 36.60, Collections.emptyList(), 500.0,
            0L, Long.MAX_VALUE, 0L, Long.MAX_VALUE, true);
        assertEquals(100.0, s.chargeCost, 1e-4);
        assertEquals(36.60, s.roadCost, 1e-4);
        assertEquals(136.60, s.totalCost, 1e-4);
    }

    @Test public void collapsesConsecutiveFreeFlowCorridorGantriesIntoDropOffPassage() {
        long t0 = Instant.parse("2026-11-19T23:53:00Z").toEpochMilli();
        TollMatcher.Booth g1 = new TollMatcher.Booth(1, "node/1", "Vila Maria", "CCR RioSP",
            TollMatcher.KIND_FREE_FLOW, -23.51, -46.58, 45, 60.0, 45, "Mo-Fr 1.05BRL/motorcar", 1.05, null, null);
        TollMatcher.Booth g2 = new TollMatcher.Booth(2, "node/2", "Aricanduva", "CCR RioSP",
            TollMatcher.KIND_FREE_FLOW, -23.50, -46.55, 45, 60.0, 45, "Mo-Fr 5.44BRL/motorcar", 5.44, null, null);
        TollMatcher.Booth g3 = new TollMatcher.Booth(3, "node/3", "CCR RioSP", "CCR RioSP",
            TollMatcher.KIND_FREE_FLOW, -23.46, -46.46, 45, 60.0, 45, null, null, null, null);
        TollMatcher.Booth plaza = new TollMatcher.Booth(4, "node/4", "Arujá - P10", "CCR RioSP",
            TollMatcher.KIND_PLAZA, -23.44, -46.42, 80, 60.0, 45, "4.30BRL/motorcar", 4.30, null, null);

        List<TollMatcher.Hit> raw = Arrays.asList(
            new TollMatcher.Hit(g1, t0, g1.lat, g1.lon, 90, 5, 1.05),
            new TollMatcher.Hit(g2, t0 + 120_000, g2.lat, g2.lon, 95, 5, 5.44),
            new TollMatcher.Hit(g3, t0 + 360_000, g3.lat, g3.lon, 90, 5, null),
            new TollMatcher.Hit(plaza, t0 + 480_000, plaza.lat, plaza.lon, 40, 10, 4.30)
        );
        List<TollMatcher.Hit> out = TollMatcher.collapseFreeFlowCorridors(raw);
        assertEquals(2, out.size());
        assertEquals("Vila Maria → CCR RioSP", out.get(0).booth.name);
        assertEquals(g3.id, out.get(0).booth.id);
        assertNull(out.get(0).cost);
        assertEquals("Arujá - P10", out.get(1).booth.name);
        assertEquals(4.30, out.get(1).cost, 1e-4);
    }

    @Test public void rescanOfAPartlyScannedCorridorDoesNotChargeItTwice() {
        long t0 = Instant.parse("2026-11-19T23:53:00Z").toEpochMilli();
        TollMatcher.Booth a = new TollMatcher.Booth(1, "node/1", "Vila Maria", "CCR RioSP",
            TollMatcher.KIND_FREE_FLOW, -23.51, -46.58, 45, 60.0, 45, null, 1.05, null, null);
        TollMatcher.Booth m = new TollMatcher.Booth(2, "node/2", "Penha", "CCR RioSP",
            TollMatcher.KIND_FREE_FLOW, -23.48, -46.52, 45, 60.0, 45, null, null, null, null);
        TollMatcher.Booth b = new TollMatcher.Booth(3, "node/3", "Itaquera", "CCR RioSP",
            TollMatcher.KIND_FREE_FLOW, -23.46, -46.46, 45, 60.0, 45, null, null, null, null);

        // First scan had only gantry A cached: it saved "A" at A.
        List<TollMatcher.Saved> saved = Collections.singletonList(
            new TollMatcher.Saved(a.id, t0, a.lat, a.lon, TollMatcher.KIND_FREE_FLOW));

        // The backfill fetched M and B (each hop ~7 km): the rescan collapses
        // A..B into "A → B" at B, 6 min later and ~13 km from A, so only the
        // corridor entry is near the saved "A". Same corridor, not a new toll.
        List<TollMatcher.Hit> rescan = TollMatcher.collapseFreeFlowCorridors(Arrays.asList(
            new TollMatcher.Hit(a, t0, a.lat, a.lon, 90, 5, 1.05),
            new TollMatcher.Hit(m, t0 + 180_000, m.lat, m.lon, 90, 5, null),
            new TollMatcher.Hit(b, t0 + 360_000, b.lat, b.lon, 90, 5, null)));
        assertEquals(1, rescan.size());
        assertEquals(t0, rescan.get(0).entryTsMs);
        assertTrue(TollMatcher.isDuplicate(rescan.get(0), saved));

        // A Free Flow gantry well past the corridor gap is a toll of its own.
        TollMatcher.Hit later = new TollMatcher.Hit(b, t0 + 40 * 60_000L, b.lat, b.lon, 90, 5, null);
        assertFalse(TollMatcher.isDuplicate(later, saved));

        // A plaza right after a Free Flow passage is never folded into it.
        TollMatcher.Booth plaza = new TollMatcher.Booth(4, "node/4", "Arujá - P10", "CCR RioSP",
            TollMatcher.KIND_PLAZA, -23.44, -46.42, 80, 60.0, 45, null, 4.30, null, null);
        TollMatcher.Hit p = new TollMatcher.Hit(plaza, t0 + 300_000, plaza.lat, plaza.lon, 40, 10, 4.30);
        assertFalse(TollMatcher.isDuplicate(p, saved));

        // The same booth again within 3 min is still a duplicate (unchanged rule).
        TollMatcher.Hit again = new TollMatcher.Hit(a, t0 + 60_000, a.lat, a.lon, 90, 5, 1.05);
        assertTrue(TollMatcher.isDuplicate(again, saved));
    }
}
