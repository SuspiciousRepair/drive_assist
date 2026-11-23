package com.geely.drivemem.state;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Unit tests for Car Diary category hierarchy, due-status engine (km, time,
 * bundled service visits, cold-snap tire nudge), meta JSON helpers, and the
 * pending IMU jolt ring buffer. */
public class CarDiaryTest {

    private static final long DAY = 86_400_000L;

    @After
    public void tearDown() {
        CarDiary.clearPendingJoltsForTest();
    }

    private static CarDiary.Entry entry(long id, CarDiary.Category cat, long tsMs,
                                        Double odoKm, Double outC, String metaJson) {
        return new CarDiary.Entry(id, "u-" + id, tsMs, cat.kind, cat,
            CarDiary.STATUS_DONE, odoKm, outC, null, null, null,
            null, null, null, null, null, metaJson, 1);
    }

    private static CarDiary.DueStatus find(List<CarDiary.DueStatus> list, CarDiary.Category c) {
        for (CarDiary.DueStatus d : list) if (d.category == c) return d;
        throw new AssertionError("Missing category " + c);
    }

    @Test
    public void twoLevelCategoryHierarchyCoversAllGroups() {
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.TIRES).contains(CarDiary.Category.TIRE_PRESSURE));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.TIRES).contains(CarDiary.Category.TIRE_BALANCING));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.COSMETIC).contains(CarDiary.Category.POLISHING));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.NOTES).contains(CarDiary.Category.NOISE));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.NOTES).contains(CarDiary.Category.INCIDENT));
        assertFalse(CarDiary.Category.forGroup(CarDiary.Group.NOTES).contains(CarDiary.Category.TPMS_AUTO));
    }

    @Test
    public void untrackedTypesNeverWarnAndOverdueTriggersOnKmOrTime() {
        long now = 1_800_000_000_000L;
        List<CarDiary.Entry> entries = Arrays.asList(
            // Tire pressure checked 36 days ago -> past 1-month limit (overdue)
            entry(1, CarDiary.Category.TIRE_PRESSURE, now - 36 * DAY, 14_000.0, 22.0, null),
            // Tire rotation 9,400 km ago -> within 10% of 10,000 km (dueSoon)
            entry(2, CarDiary.Category.TIRE_ROTATION, now - 60 * DAY, 5_600.0, 20.0, null),
            // Wipers replaced 2 months ago -> ok
            entry(3, CarDiary.Category.WIPERS, now - 60 * DAY, 13_000.0, 20.0, null)
        );

        List<CarDiary.DueStatus> statuses = CarDiary.computeDueStatuses(entries, 15_000.0, now, 20.0);

        CarDiary.DueStatus tp = find(statuses, CarDiary.Category.TIRE_PRESSURE);
        assertTrue(tp.overdue);
        assertFalse(tp.dueSoon);

        CarDiary.DueStatus rot = find(statuses, CarDiary.Category.TIRE_ROTATION);
        assertFalse(rot.overdue);
        assertTrue(rot.dueSoon);
        assertEquals(600.0, rot.kmRemaining, 0.01);

        CarDiary.DueStatus wip = find(statuses, CarDiary.Category.WIPERS);
        assertFalse(wip.overdue);
        assertFalse(wip.dueSoon);

        // Untracked coolant is never flagged
        CarDiary.DueStatus cool = find(statuses, CarDiary.Category.COOLANT);
        assertFalse(cool.overdue);
        assertFalse(cool.dueSoon);

        // Most urgent picks overdue tire pressure first
        CarDiary.DueStatus urgent = CarDiary.mostUrgent(statuses);
        assertNotNull(urgent);
        assertEquals(CarDiary.Category.TIRE_PRESSURE, urgent.category);
    }

    @Test
    public void bundledServiceVisitResetsIncludedCareClocks() {
        long now = 1_800_000_000_000L;
        String meta = CarDiary.buildServiceBundledMeta(
            Arrays.asList(CarDiary.Category.CABIN_FILTER, CarDiary.Category.TIRE_ROTATION));
        List<CarDiary.Entry> entries = Arrays.asList(
            // Old overdue rotation from 15,000 km ago
            entry(1, CarDiary.Category.TIRE_ROTATION, now - 200 * DAY, 5_000.0, 20.0, null),
            // Recent service visit bundling rotation & cabin filter
            entry(2, CarDiary.Category.SERVICE, now - 10 * DAY, 19_500.0, 20.0, meta)
        );

        List<CarDiary.DueStatus> statuses = CarDiary.computeDueStatuses(entries, 20_000.0, now, 20.0);
        CarDiary.DueStatus rot = find(statuses, CarDiary.Category.TIRE_ROTATION);
        assertFalse(rot.overdue);
        assertEquals(9_500.0, rot.kmRemaining, 0.01);

        CarDiary.DueStatus cf = find(statuses, CarDiary.Category.CABIN_FILTER);
        assertNotNull(cf.lastEntry);
        assertFalse(cf.overdue);
    }

    @Test
    public void coldSnapTriggersTirePressureNudgeEarly() {
        long now = 1_800_000_000_000L;
        List<CarDiary.Entry> entries = Collections.singletonList(
            entry(1, CarDiary.Category.TIRE_PRESSURE, now - 10 * DAY, 14_500.0, 26.0, null)
        );
        // Outside temp dropped 12 C since last check
        List<CarDiary.DueStatus> statuses = CarDiary.computeDueStatuses(entries, 14_800.0, now, 14.0);
        CarDiary.DueStatus tp = find(statuses, CarDiary.Category.TIRE_PRESSURE);
        assertFalse(tp.overdue);
        assertTrue(tp.dueSoon);
        assertTrue(tp.coldSnapNudge);
    }

    @Test
    public void metaHelpersRoundTripPsiColdAndBundled() {
        String psiMeta = CarDiary.buildTirePressureMeta(36, 36, 35, 35, true);
        assertArrayEquals(new int[]{36, 36, 35, 35}, CarDiary.parsePsi(psiMeta));
        assertEquals(Boolean.TRUE, CarDiary.parseCold(psiMeta));

        String svcMeta = CarDiary.buildServiceBundledMeta(
            Arrays.asList(CarDiary.Category.COOLANT, CarDiary.Category.BRAKE_PADS));
        assertEquals(Arrays.asList(CarDiary.Category.COOLANT, CarDiary.Category.BRAKE_PADS),
            CarDiary.parseBundled(svcMeta));
    }

    @Test
    public void pendingJoltRingBufferDeduplicatesCapsAndExpires() {
        long t0 = 1_800_000_000_000L;
        CarDiary.PendingJolt j1 = CarDiary.recordPendingJolt(t0, 1.5f, 0.1f, 0.1f, 50f, 14000.0, null, null);
        // Stronger shock 5 s later replaces j1 within cooldown
        CarDiary.PendingJolt j2 = CarDiary.recordPendingJolt(t0 + 5_000L, 2.1f, 0.2f, 0.1f, 52f, 14000.1, null, null);
        assertEquals(1, CarDiary.pendingJolts(t0 + 10_000L).size());
        assertEquals(j2.id, CarDiary.pendingJolts(t0 + 10_000L).get(0).id);

        // Add 3 more past cooldown -> capped at 3
        CarDiary.recordPendingJolt(t0 + 60_000L, 1.6f, 0f, 0f, 40f, 14001.0, null, null);
        CarDiary.recordPendingJolt(t0 + 120_000L, 1.7f, 0f, 0f, 42f, 14002.0, null, null);
        CarDiary.recordPendingJolt(t0 + 180_000L, 1.8f, 0f, 0f, 45f, 14003.0, null, null);
        assertEquals(3, CarDiary.pendingJolts(t0 + 200_000L).size());

        // Dismiss newest
        String newestId = CarDiary.pendingJolts(t0 + 200_000L).get(0).id;
        CarDiary.dismissPendingJolt(newestId);
        assertEquals(2, CarDiary.pendingJolts(t0 + 200_000L).size());

        // Expire after 24 h
        assertTrue(CarDiary.pendingJolts(t0 + 25 * 3600_000L).isEmpty());
    }
}
