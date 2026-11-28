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
import static org.junit.Assert.assertNull;
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
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.COSMETIC).contains(CarDiary.Category.WASHER_FLUID));
        assertFalse(CarDiary.Category.forGroup(CarDiary.Group.ELECTRICAL).contains(CarDiary.Category.WASHER_FLUID));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.NOTES).contains(CarDiary.Category.NOISE));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.NOTES).contains(CarDiary.Category.INCIDENT));
        assertFalse(CarDiary.Category.forGroup(CarDiary.Group.NOTES).contains(CarDiary.Category.TPMS_AUTO));
    }

    @Test
    public void filterByGroupAndOdometerForDateResolution() {
        long now = 1_800_000_000_000L;
        List<CarDiary.Entry> all = Arrays.asList(
            entry(1, CarDiary.Category.TIRE_PRESSURE, now, 14_000.0, 20.0, null),
            entry(2, CarDiary.Category.WASHER_FLUID, now, 14_000.0, 20.0, null),
            entry(3, CarDiary.Category.NOISE, now, 14_000.0, 20.0, null)
        );
        assertEquals(3, CarDiary.filterByGroup(all, null).size());
        assertEquals(1, CarDiary.filterByGroup(all, CarDiary.Group.COSMETIC).size());
        assertEquals(CarDiary.Category.WASHER_FLUID,
            CarDiary.filterByGroup(all, CarDiary.Group.COSMETIC).get(0).category);

        // Exact sample wins over stat
        CarDiary.OdoLookup exact = CarDiary.resolveOdometerForDate(14_250.0, 14_200.0, 14_000.0, 14_300.0);
        assertTrue(exact.exact);
        assertEquals(14_250.0, exact.odoKm, 0.01);

        // Parked day between two known days interpolates / matches parked reading
        CarDiary.OdoLookup parked = CarDiary.resolveOdometerForDate(null, null, 14_200.0, 14_200.0);
        assertFalse(parked.exact);
        assertEquals(14_200.0, parked.odoKm, 0.01);

        // Date before any car history returns null
        CarDiary.OdoLookup before = CarDiary.resolveOdometerForDate(null, null, null, 14_200.0);
        assertEquals(null, before.odoKm);
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
        String psiMeta = CarDiary.buildTirePressureMeta(36, 36, 35, 35, Boolean.TRUE);
        assertArrayEquals(new int[]{36, 36, 35, 35}, CarDiary.parsePsi(psiMeta));
        assertEquals(Boolean.TRUE, CarDiary.parseCold(psiMeta));

        String psiNoCold = CarDiary.buildTirePressureMeta(36, 36, 36, 36, null);
        assertArrayEquals(new int[]{36, 36, 36, 36}, CarDiary.parsePsi(psiNoCold));
        assertEquals(null, CarDiary.parseCold(psiNoCold));

        String svcMeta = CarDiary.buildServiceBundledMeta(
            Arrays.asList(CarDiary.Category.COOLANT, CarDiary.Category.BRAKE_FLUID));
        assertEquals(Arrays.asList(CarDiary.Category.COOLANT, CarDiary.Category.BRAKE_FLUID),
            CarDiary.parseBundled(svcMeta));
    }

    @Test
    public void officialEx2IntervalsAndColdTireInference() {
        assertEquals(20_000.0, CarDiary.Category.SERVICE.kmInterval, 0.01);
        assertEquals(Integer.valueOf(12), CarDiary.Category.SERVICE.monthInterval);
        assertEquals(40_000.0, CarDiary.Category.BRAKE_FLUID.kmInterval, 0.01);
        assertEquals(Integer.valueOf(24), CarDiary.Category.BRAKE_FLUID.monthInterval);
        assertEquals(80_000.0, CarDiary.Category.COOLANT.kmInterval, 0.01);
        assertEquals(Integer.valueOf(48), CarDiary.Category.COOLANT.monthInterval);
        assertEquals(80_000.0, CarDiary.Category.GEAR_OIL.kmInterval, 0.01);
        assertEquals(Integer.valueOf(48), CarDiary.Category.GEAR_OIL.monthInterval);
        assertFalse(CarDiary.Category.BRAKE_PADS.hasInterval());

        long now = 1_800_000_000_000L;
        long h = 3600_000L;
        assertEquals(Boolean.TRUE, CarDiary.inferColdTires(now, now - 4 * h, now - 4 * h));
        assertEquals(Boolean.FALSE, CarDiary.inferColdTires(now, now - 1 * h, now - 1 * h));
        assertEquals(null, CarDiary.inferColdTires(now, null, now - 1 * h));
        assertEquals(null, CarDiary.inferColdTires(now, null, null));
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

    @Test
    public void newCarFactoryMilestonesAndMagicSortByTimeOrDistance() {
        long now = 1_800_000_000_000L;
        // Brand-new EX2 at 7,077 km with only Tire pressure logged 12 days ago (18 days left)
        List<CarDiary.Entry> entries = Collections.singletonList(
            entry(1, CarDiary.Category.TIRE_PRESSURE, now - 12 * DAY, 6_600.0, 22.0, null)
        );
        List<CarDiary.DueStatus> statuses = CarDiary.computeDueStatuses(entries, 7_077.0, now, 22.0);

        // Factory milestones count down automatically from 0 km on a new car
        CarDiary.DueStatus rot = find(statuses, CarDiary.Category.TIRE_ROTATION);
        assertEquals(2_923.0, rot.kmRemaining, 0.01);
        assertFalse(rot.overdue);

        CarDiary.DueStatus svc = find(statuses, CarDiary.Category.SERVICE);
        assertEquals(12_923.0, svc.kmRemaining, 0.01);
        assertFalse(svc.overdue);

        CarDiary.DueStatus bf = find(statuses, CarDiary.Category.BRAKE_FLUID);
        assertEquals(32_923.0, bf.kmRemaining, 0.01);

        // Magic Sort at 40 km/day: Tire pressure (~18 d) -> Rotation (~73 d) -> Service (~323 d) -> Brake fluid (~823 d)
        List<CarDiary.DueStatus> sorted = CarDiary.scheduleRows(statuses, 40.0, 4);
        assertEquals(4, sorted.size());
        assertEquals(CarDiary.Category.TIRE_PRESSURE, sorted.get(0).category);
        assertEquals(CarDiary.Category.TIRE_ROTATION, sorted.get(1).category);
        assertEquals(CarDiary.Category.SERVICE, sorted.get(2).category);
        assertEquals(CarDiary.Category.BRAKE_FLUID, sorted.get(3).category);

        // With no Tire pressure logged on a 7,077 km car: Rotation -> Service -> Brake fluid -> Tire pressure (untracked)
        List<CarDiary.DueStatus> emptyStatuses = CarDiary.computeDueStatuses(Collections.emptyList(), 7_077.0, now, 22.0);
        List<CarDiary.DueStatus> sortedNew = CarDiary.scheduleRows(emptyStatuses, 40.0, 4);
        assertEquals(CarDiary.Category.TIRE_ROTATION, sortedNew.get(0).category);
        assertEquals(CarDiary.Category.SERVICE, sortedNew.get(1).category);
        assertEquals(CarDiary.Category.BRAKE_FLUID, sortedNew.get(2).category);
        assertEquals(CarDiary.Category.TIRE_PRESSURE, sortedNew.get(3).category);
        assertEquals(CarDiary.Category.TIRE_ROTATION, CarDiary.mostUrgent(emptyStatuses, 40.0).category);
    }

    @Test
    public void coolantTopUpDoesNotResetFourYearFlushClock() {
        long now = 1_800_000_000_000L;
        List<CarDiary.Entry> entries = Collections.singletonList(
            entry(1, CarDiary.Category.COOLANT, now - 5 * DAY, 7_000.0, 25.0,
                CarDiary.buildCoolantMeta(true))
        );
        List<CarDiary.DueStatus> statuses = CarDiary.computeDueStatuses(entries, 7_077.0, now, 25.0);
        CarDiary.DueStatus cool = find(statuses, CarDiary.Category.COOLANT);
        // Still counting down to the factory 80,000 km flush, not reset to 7,000 + 80,000
        assertEquals(null, cool.lastEntry);
        assertEquals(72_923.0, cool.kmRemaining, 0.01);

        // Full change DOES reset the 80,000 km / 48 mo clock
        List<CarDiary.Entry> fullEntries = Collections.singletonList(
            entry(2, CarDiary.Category.COOLANT, now - 5 * DAY, 80_000.0, 25.0,
                CarDiary.buildCoolantMeta(false))
        );
        List<CarDiary.DueStatus> fullStatuses = CarDiary.computeDueStatuses(fullEntries, 81_000.0, now, 25.0);
        CarDiary.DueStatus coolFull = find(fullStatuses, CarDiary.Category.COOLANT);
        assertNotNull(coolFull.lastEntry);
        assertEquals(79_000.0, coolFull.kmRemaining, 0.01);
    }

    @Test
    public void scheduleConfigCustomIntervalsAndOffDisablesCategories() {
        long now = 1_800_000_000_000L;
        // Turn off Tire Rotation & Wipers; shorten Tire Pressure to 15 days and disable cold-snap
        CarDiary.ScheduleConfig cfg = new CarDiary.ScheduleConfig(
            7, 15, false,
            0, 10_000, 12,
            20_000, 12,
            40_000, 24,
            80_000, 48,
            80_000, 48,
            0, 48);
        List<CarDiary.Entry> entries = Collections.singletonList(
            entry(1, CarDiary.Category.TIRE_PRESSURE, now - 16 * DAY, 5_000.0, 30.0, null)
        );
        List<CarDiary.DueStatus> statuses = CarDiary.computeDueStatuses(entries, 5_200.0, now, 15.0, cfg);
        for (CarDiary.DueStatus d : statuses) {
            assertFalse("Disabled Tire Rotation must be omitted", d.category == CarDiary.Category.TIRE_ROTATION);
            assertFalse("Disabled Wipers must be omitted", d.category == CarDiary.Category.WIPERS);
        }

        CarDiary.DueStatus tp = find(statuses, CarDiary.Category.TIRE_PRESSURE);
        assertNotNull(tp);
        assertTrue(tp.overdue);
        assertEquals(-1, tp.daysRemaining.intValue());
        assertFalse(tp.coldSnapNudge);

        // Factory countdown uses custom 10k service interval on a 5,200 km car -> 4,800 km remaining
        CarDiary.DueStatus svc = find(statuses, CarDiary.Category.SERVICE);
        assertEquals(4_800.0, svc.kmRemaining, 0.01);
    }

    @Test
    public void tcoSummaryComputesCostPerKmAndEnergyShare() {
        CarDiary.TcoSummary empty = CarDiary.TcoSummary.compute(0, 0, 5_000);
        assertFalse(empty.hasCosts());
        assertEquals(-1.0, empty.costPerKm, 0.001);

        CarDiary.TcoSummary tco = CarDiary.TcoSummary.compute(600.0, 200.0, 4_000.0);
        assertTrue(tco.hasCosts());
        assertEquals(800.0, tco.totalCost, 0.001);
        assertEquals(0.20, tco.costPerKm, 0.0001);
        assertEquals(0.75, tco.chargeShare, 0.0001);
    }

    @Test
    public void costUnitCycleSkipsPerKmWithoutDistance() {
        int money = CarDiary.TcoSummary.UNIT_MONEY, perKm = CarDiary.TcoSummary.UNIT_PER_KM,
            pct = CarDiary.TcoSummary.UNIT_PCT;
        assertEquals(perKm, CarDiary.TcoSummary.nextUnit(money, 100.0));
        assertEquals(pct, CarDiary.TcoSummary.nextUnit(perKm, 100.0));
        assertEquals(money, CarDiary.TcoSummary.nextUnit(pct, 100.0));
        assertEquals(pct, CarDiary.TcoSummary.nextUnit(money, 0.0));
        assertEquals(money, CarDiary.TcoSummary.shownUnit(perKm, 0.0));
        assertEquals(perKm, CarDiary.TcoSummary.shownUnit(perKm, 100.0));
        assertEquals(money, CarDiary.TcoSummary.shownUnit(7, 100.0));
    }

    @Test
    public void bureaucracyCategoriesProrateAnnualAndMonthlyCostsInTco() {
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.COSMETIC).contains(CarDiary.Category.ACCESSORIES));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.BUREAUCRACY).contains(CarDiary.Category.INSURANCE));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.BUREAUCRACY).contains(CarDiary.Category.TAX_LICENSE));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.ROAD).contains(CarDiary.Category.TRAFFIC_TICKET));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.ROAD).contains(CarDiary.Category.MONTHLY_PARKING));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.ROAD).contains(CarDiary.Category.PARKING));
        assertTrue(CarDiary.Category.forGroup(CarDiary.Group.ROAD).contains(CarDiary.Category.TOLL));

        long now = 1_800_000_000_000L;
        long winStart = now - 73 * DAY; // exactly 73 days = 1/5 of 365 days

        CarDiary.Entry insurance = new CarDiary.Entry(1, "u1", now - 100 * DAY,
            CarDiary.KIND_CARE, CarDiary.Category.INSURANCE, CarDiary.STATUS_DONE,
            4000.0, null, null, null, 3650.0, null, null, null, null, null, null, 1);
        CarDiary.Entry parking = new CarDiary.Entry(2, "u2", now - 15 * DAY,
            CarDiary.KIND_CARE, CarDiary.Category.MONTHLY_PARKING, CarDiary.STATUS_DONE,
            6000.0, null, null, null, 300.0, null, null, null, null, null, null, 1);
        CarDiary.Entry ticket = new CarDiary.Entry(3, "u3", now - 10 * DAY,
            CarDiary.KIND_CARE, CarDiary.Category.TRAFFIC_TICKET, CarDiary.STATUS_DONE,
            6500.0, null, null, null, 200.0, null, null, null, null, null, null, 1);
        CarDiary.Entry accessory = new CarDiary.Entry(4, "u4", now - 20 * DAY,
            CarDiary.KIND_CARE, CarDiary.Category.ACCESSORIES, CarDiary.STATUS_DONE,
            5800.0, null, null, null, 150.0, null, null, null, null, null, null, 1);

        // Insurance: 73/365 * 3650 = 730 -> Bureaucracy; Parking: 15/30 * 300 = 150 + Ticket: 200 -> Road = 350
        // Accessories -> Maintenance = 150; Charge = 770 -> Total = 2000 over 2000 km = 1.00/km
        CarDiary.TcoSummary tco = CarDiary.computeTco(
            770.0, Arrays.asList(insurance, parking, ticket, accessory),
            2000.0, winStart, now, true);
        assertTrue(tco.prorated);
        assertEquals(770.0, tco.chargeCost, 0.01);
        assertEquals(150.0, tco.maintenanceCost, 0.01);
        assertEquals(350.0, tco.roadCost, 0.01);
        assertEquals(730.0, tco.bureaucracyCost, 0.01);
        assertEquals(2000.0, tco.totalCost, 0.01);
        assertEquals(1.00, tco.costPerKm, 0.001);
        assertEquals(0.175, tco.roadShare, 0.001);
        assertEquals(0.365, tco.bureaucracyShare, 0.001);
    }

    @Test
    public void periodFilterScopesDiaryEntriesAndAvailableYears() {
        java.time.ZoneId utc = java.time.ZoneId.of("UTC");
        long now = java.time.LocalDate.of(2026, 11, 24).atTime(12, 0).atZone(utc).toInstant().toEpochMilli();
        long d10 = now - 10 * DAY;
        long d60 = now - 60 * DAY;
        long yr2025 = java.time.LocalDate.of(2025, 6, 15).atTime(12, 0).atZone(utc).toInstant().toEpochMilli();

        List<CarDiary.Entry> all = Arrays.asList(
            entry(1, CarDiary.Category.TIRE_PRESSURE, d10, 7000.0, null, null),
            entry(2, CarDiary.Category.INSURANCE, d60, 6000.0, null, null),
            entry(3, CarDiary.Category.SERVICE, yr2025, 2000.0, null, null)
        );
        assertEquals(3, CarDiary.filterEntries(all, null, CarDiary.PeriodFilter.ALL, now, utc).size());
        assertEquals(1, CarDiary.filterEntries(all, null, CarDiary.PeriodFilter.DAYS_30, now, utc).size());
        assertEquals(2, CarDiary.filterEntries(all, null, CarDiary.PeriodFilter.DAYS_90, now, utc).size());
        assertEquals(1, CarDiary.filterEntries(all, null, CarDiary.PeriodFilter.ofYear(2025), now, utc).size());
        assertEquals(1, CarDiary.filterEntries(all, CarDiary.Group.BUREAUCRACY, CarDiary.PeriodFilter.DAYS_90, now, utc).size());
        assertEquals(Arrays.asList(2026, 2025), CarDiary.availableYears(all, now, utc));
    }

    @Test
    public void kmOnlyItemComesDueFromTheOdometerOfItsDateWhenTheEntryHasNone() {
        long now = 1_800_000_000_000L;
        // Tire rotation has a km interval only. No odometer typed: nothing to count from.
        CarDiary.Entry bare = entry(1, CarDiary.Category.TIRE_ROTATION, now - 200 * DAY, null, null, null);
        CarDiary.DueStatus none = find(
            CarDiary.computeDueStatuses(Collections.singletonList(bare), 25_000.0, now, null),
            CarDiary.Category.TIRE_ROTATION);
        assertEquals(null, none.kmSinceLast);
        assertFalse(none.overdue);

        // Same entry with the odometer the car's history has for that date.
        CarDiary.Entry est = new CarDiary.Entry(1, "u-1", now - 200 * DAY,
            CarDiary.KIND_CARE, CarDiary.Category.TIRE_ROTATION, CarDiary.STATUS_DONE,
            null, null, null, null, null, null, null, null, null, null, null, 1,
            now - 200 * DAY, 14_000.0);
        CarDiary.DueStatus due = find(
            CarDiary.computeDueStatuses(Collections.singletonList(est), 25_000.0, now, null),
            CarDiary.Category.TIRE_ROTATION);
        assertEquals(11_000.0, due.kmSinceLast, 0.01);
        assertEquals(-1_000.0, due.kmRemaining, 0.01);
        assertTrue(due.overdue);

        // An entry odometer above the current one is not usable: same fallback.
        CarDiary.Entry typo = new CarDiary.Entry(2, "u-2", now - 200 * DAY,
            CarDiary.KIND_CARE, CarDiary.Category.TIRE_ROTATION, CarDiary.STATUS_DONE,
            140_000.0, null, null, null, null, null, null, null, null, null, null, 1,
            now - 200 * DAY, 20_000.0);
        CarDiary.DueStatus fixed = find(
            CarDiary.computeDueStatuses(Collections.singletonList(typo), 25_000.0, now, null),
            CarDiary.Category.TIRE_ROTATION);
        assertEquals(5_000.0, fixed.kmSinceLast, 0.01);
        assertFalse(fixed.overdue);

        // The entry's own odometer wins when it is usable.
        assertEquals(9_000.0, CarDiary.odoForReminder(9_000.0, 14_000.0, 25_000.0), 0.01);
        assertEquals(null, CarDiary.odoForReminder(null, 30_000.0, 25_000.0));
        assertTrue(CarDiary.needsOdoEstimate(CarDiary.Category.TIRE_BALANCING, null, 25_000.0));
        assertTrue(CarDiary.needsOdoEstimate(CarDiary.Category.SERVICE, 99_000.0, 25_000.0));
        assertFalse(CarDiary.needsOdoEstimate(CarDiary.Category.TIRE_ROTATION, 9_000.0, 25_000.0));
        assertFalse(CarDiary.needsOdoEstimate(CarDiary.Category.WASH_DETAIL, null, 25_000.0));
    }

    @Test
    public void pendingJoltsSurviveAnEncodeDecodeRoundTrip() {
        CarDiary.PendingJolt full = new CarDiary.PendingJolt("id-1", 1_800_000_000_000L,
            2.5f, -0.25f, 0.5f, 0.75f, 12.5f, 48f, 14_000.5, -23.55052, -46.633308);
        CarDiary.PendingJolt bare = new CarDiary.PendingJolt("id-2", 1_800_000_060_000L,
            1.5f, 0f, 0f, 0f, 0f, null, null, null, null);
        List<CarDiary.PendingJolt> back = CarDiary.decodeJolts(
            CarDiary.encodeJolts(Arrays.asList(full, bare)));
        assertEquals(2, back.size());
        assertEquals("id-1", back.get(0).id);
        assertEquals(1_800_000_000_000L, back.get(0).tsMs);
        assertEquals(2.5f, back.get(0).peakZG, 0.0001f);
        assertEquals(-0.25f, back.get(0).peakXG, 0.0001f);
        assertEquals(12.5f, back.get(0).peakJerkZGs, 0.0001f);
        assertEquals(48f, back.get(0).speedKmh, 0.0001f);
        assertEquals(14_000.5, back.get(0).odoKm, 0.0001);
        assertEquals(-46.633308, back.get(0).lon, 0.0000001);
        assertEquals("id-2", back.get(1).id);
        assertEquals(null, back.get(1).speedKmh);
        assertEquals(null, back.get(1).lat);

        // Nothing saved, or a damaged line: no jolt, no exception.
        assertTrue(CarDiary.decodeJolts(null).isEmpty());
        assertTrue(CarDiary.decodeJolts("").isEmpty());
        assertEquals(1, CarDiary.decodeJolts("garbage\n" + CarDiary.encodeJolts(
            Collections.singletonList(bare))).size());
    }

    @Test
    public void coverageMetaRoundTripsAndEndDateDrivesCostAndReminder() {
        long day = 86_400_000L;
        long t0 = 1_800_000_000_000L;
        long until = t0 + 245 * day; // an 8-month renewal, not a full year
        String meta = CarDiary.buildCoverageMeta(until, "Acme \"Auto\"", "517-A\\9", "0800 130 700");
        assertEquals(Long.valueOf(until), CarDiary.parseValidUntilMs(meta));
        assertEquals("Acme \"Auto\"", CarDiary.parseMetaText(meta, CarDiary.META_PROVIDER));
        assertEquals("517-A\\9", CarDiary.parseMetaText(meta, CarDiary.META_POLICY_NO));
        assertEquals("0800 130 700", CarDiary.parseMetaText(meta, CarDiary.META_ASSIST_PHONE));
        assertNull(CarDiary.buildCoverageMeta(null, " ", null, ""));
        assertNull(CarDiary.parseValidUntilMs("{\"topUp\":true}"));

        CarDiary.Entry e = new CarDiary.Entry(1, "u", t0, CarDiary.KIND_CARE,
            CarDiary.Category.INSURANCE, CarDiary.STATUS_DONE, null, null, null, null,
            600.0, null, null, null, null, null, meta, 1);
        assertEquals(until, CarDiary.coverageEndMs(e));
        // The cost is spread over the 245 days of the policy: all of it is inside them.
        assertEquals(600.0, CarDiary.entryCostForWindow(e, t0, until, false), 0.01);
        assertEquals(300.0, CarDiary.entryCostForWindow(e, t0, t0 + 245 * day / 2, false), 0.5);

        // The reminder counts down to the end date, not to entry date + 12 months.
        long now = t0 + 240 * day;
        for (CarDiary.DueStatus d : CarDiary.computeDueStatuses(
                java.util.Collections.singletonList(e), null, now, null, null)) {
            if (d.category == CarDiary.Category.INSURANCE) {
                assertEquals(Integer.valueOf(5), d.daysRemaining);
                assertTrue(d.dueSoon);
            }
        }
    }

    // ---- Traffic ticket: the hour on the notice ----

    @Test public void ticketClockToMinuteOfDay() {
        assertEquals(Integer.valueOf(14 * 60 + 35), CarDiary.clockMinute("14", "35"));
        assertEquals(Integer.valueOf(9 * 60), CarDiary.clockMinute("9", ""));
        assertNull(CarDiary.clockMinute("", "30"));
        assertNull(CarDiary.clockMinute("24", "00"));
        assertNull(CarDiary.clockMinute("12", "60"));
    }

    @Test public void ticketMetaKeepsHourAndSpeed() {
        String meta = CarDiary.buildTicketMeta(875, 63.4);
        assertEquals(Integer.valueOf(875), CarDiary.parseTicketMinute(meta));
        assertEquals(Integer.valueOf(63), CarDiary.parseTicketSpeed(meta));
        // No hour given: no meta, and nothing to read back.
        assertNull(CarDiary.buildTicketMeta(null, 50.0));
        assertNull(CarDiary.parseTicketMinute(null));
        assertNull(CarDiary.parseTicketSpeed(CarDiary.buildTicketMeta(600, null)));
    }

    // ---- Schedules the owner adds (polishing every 30 days) ----

    @Test public void extrasRoundTripAndSkipWhatIsNotTheirs() {
        java.util.Map<CarDiary.Category, int[]> m =
            CarDiary.parseExtras("POLISHING:0:0:30;TIRE_ALIGNMENT:15000:24:0;NO_SUCH_ITEM:1:1:1;SERVICE:1:1:1;broken");
        assertEquals(2, m.size());
        assertArrayEquals(new int[]{0, 0, 30}, m.get(CarDiary.Category.POLISHING));
        assertEquals("POLISHING:0:0:30;TIRE_ALIGNMENT:15000:24:0", CarDiary.formatExtras(m));
        assertTrue(CarDiary.parseExtras(null).isEmpty());
    }

    @Test public void anOwnerScheduleMakesAnItemDue() {
        CarDiary.ScheduleConfig cfg = CarDiary.ScheduleConfig.defaults();
        assertNull(cfg.dayInterval(CarDiary.Category.POLISHING));
        cfg.extras = CarDiary.parseExtras("POLISHING:0:0:30");
        assertEquals(Integer.valueOf(30), cfg.dayInterval(CarDiary.Category.POLISHING));
        assertNull(cfg.kmInterval(CarDiary.Category.POLISHING));

        // No entry yet: it is in the due list, marked as the owner's, so the card shows it.
        List<CarDiary.DueStatus> due = CarDiary.computeDueStatuses(
            new java.util.ArrayList<>(), 7_000.0, 1_000_000_000_000L, null, cfg);
        CarDiary.DueStatus pol = null;
        for (CarDiary.DueStatus d : due) if (d.category == CarDiary.Category.POLISHING) pol = d;
        assertNotNull(pol);
        assertTrue(pol.ownerScheduled);
    }

    @Test public void anExtraOverridesABuiltInIntervalAndCanTurnItOff() {
        CarDiary.ScheduleConfig cfg = CarDiary.ScheduleConfig.defaults();
        assertEquals(10_000.0, cfg.kmInterval(CarDiary.Category.TIRE_ALIGNMENT), 1e-9);
        cfg.extras = CarDiary.parseExtras("TIRE_ALIGNMENT:15000:24:0");
        assertEquals(15_000.0, cfg.kmInterval(CarDiary.Category.TIRE_ALIGNMENT), 1e-9);
        cfg.extras = CarDiary.parseExtras("TIRE_ALIGNMENT:0:0:0");
        assertFalse(cfg.isEnabled(CarDiary.Category.TIRE_ALIGNMENT));
    }

    @Test public void theKeyBatteryIsAnItemToRecordWithNoSchedule() {
        assertFalse(CarDiary.Category.KEY_BATTERY.hasInterval());
        assertTrue(CarDiary.Category.KEY_BATTERY.userPickable);
        assertEquals(CarDiary.Group.ELECTRICAL, CarDiary.Category.KEY_BATTERY.group);
    }

    private static CarDiary.DueStatus statusOf(List<CarDiary.DueStatus> all, CarDiary.Category cat) {
        for (CarDiary.DueStatus d : all) if (d.category == cat) return d;
        return null;
    }

    @Test
    public void pickupDateStartsTheMonthsOfAFirstTimeItem() {
        long now = 1_800_000_000_000L;
        long day = 24L * 3600_000L;
        CarDiary.ScheduleConfig cfg = CarDiary.ScheduleConfig.defaults();
        cfg.pickupMs = now - 150 * day;   // 5 months with the car, 7,000 km
        List<CarDiary.DueStatus> all = CarDiary.computeDueStatuses(
            Collections.emptyList(), 7_000.0, now, 22.0, cfg);

        // Service is 20,000 km or 12 months: both count, the time comes first.
        CarDiary.DueStatus service = statusOf(all, CarDiary.Category.SERVICE);
        org.junit.Assert.assertEquals(13_000.0, service.kmRemaining, 0.01);
        org.junit.Assert.assertEquals(Integer.valueOf(365 - 150), service.daysRemaining);
        org.junit.Assert.assertFalse(service.overdue);
        // 7,000 km in 150 days: by distance it is 278 days away, by time 215.
        org.junit.Assert.assertEquals(215.0,
            CarDiary.estimatedDaysUntilDue(service, 7_000.0 / 150.0), 0.01);

        // A short item (tire pressure, 30 days) is not "late since delivery".
        CarDiary.DueStatus pressure = statusOf(all, CarDiary.Category.TIRE_PRESSURE);
        org.junit.Assert.assertFalse(pressure.overdue);
        org.junit.Assert.assertNull(pressure.daysRemaining);

        // Past the 12 months with no service entry: late.
        cfg.pickupMs = now - 400 * day;
        service = statusOf(CarDiary.computeDueStatuses(
            Collections.emptyList(), 9_000.0, now, 22.0, cfg), CarDiary.Category.SERVICE);
        org.junit.Assert.assertTrue(service.overdue);
    }

    @Test
    public void usedCarCountsTheKmFromThePickupOdometer() {
        long now = 1_800_000_000_000L;
        CarDiary.ScheduleConfig cfg = CarDiary.ScheduleConfig.defaults();
        cfg.pickupKm = 30_000.0;   // no date: km only, never a false overdue
        CarDiary.DueStatus service = statusOf(CarDiary.computeDueStatuses(
            Collections.emptyList(), 37_000.0, now, 22.0, cfg), CarDiary.Category.SERVICE);
        org.junit.Assert.assertEquals(13_000.0, service.kmRemaining, 0.01);
        org.junit.Assert.assertNull(service.daysRemaining);
        org.junit.Assert.assertFalse(service.overdue);
    }

    private static CarDiary.Entry entryAt(long id, CarDiary.Category cat, long tsMs, Double odo, String meta) {
        return new CarDiary.Entry(id, "u" + id, tsMs, cat.kind, cat, CarDiary.STATUS_DONE, odo, null,
            null, null, null, null, null, null, null, null, meta, 0);
    }

    @Test
    public void postponedEntryMovesTheDuePointAndKeepsTheOldChange() {
        long now = 1_800_000_000_000L;
        long day = 24L * 3600_000L;
        CarDiary.ScheduleConfig cfg = CarDiary.ScheduleConfig.defaults();
        // Brake fluid changed long ago: late by time (24 months) at 20,000 km.
        CarDiary.Entry change = entryAt(1, CarDiary.Category.BRAKE_FLUID, now - 800 * day, 500.0, null);
        List<CarDiary.Entry> entries = new java.util.ArrayList<>();
        entries.add(change);
        CarDiary.DueStatus d = statusOf(CarDiary.computeDueStatuses(entries, 20_000.0, now, 22.0, cfg),
            CarDiary.Category.BRAKE_FLUID);
        org.junit.Assert.assertTrue(d.overdue);

        // The shop looks at it: "wait until 30,000 km".
        String meta = CarDiary.buildPostponedMeta(30_000, null);
        org.junit.Assert.assertTrue(CarDiary.isPostponed(meta));
        entries.add(entryAt(2, CarDiary.Category.BRAKE_FLUID, now - 2 * day, 20_000.0, meta));
        d = statusOf(CarDiary.computeDueStatuses(entries, 20_000.0, now, 22.0, cfg),
            CarDiary.Category.BRAKE_FLUID);
        org.junit.Assert.assertFalse(d.overdue);
        org.junit.Assert.assertEquals(10_000.0, d.kmRemaining, 0.01);
        org.junit.Assert.assertNull(d.daysRemaining);          // the time rule waits too
        org.junit.Assert.assertSame(change, d.lastEntry);       // the fluid is still the old one
        org.junit.Assert.assertNotNull(d.postponedBy);

        // At 30,000 km it is due again.
        d = statusOf(CarDiary.computeDueStatuses(entries, 30_050.0, now, 22.0, cfg),
            CarDiary.Category.BRAKE_FLUID);
        org.junit.Assert.assertTrue(d.overdue);

        // A real change after it ends the postponement.
        entries.add(entryAt(3, CarDiary.Category.BRAKE_FLUID, now - day, 20_100.0, null));
        d = statusOf(CarDiary.computeDueStatuses(entries, 20_200.0, now, 22.0, cfg),
            CarDiary.Category.BRAKE_FLUID);
        org.junit.Assert.assertNull(d.postponedBy);
        org.junit.Assert.assertEquals(39_900.0, d.kmRemaining, 0.01);
    }

    @Test
    public void postponedToADayForATimeOnlyItem() {
        long now = 1_800_000_000_000L;
        long today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().toEpochDay();
        String meta = CarDiary.buildPostponedMeta(null, today + 90);
        org.junit.Assert.assertEquals(Long.valueOf(today + 90), CarDiary.parsePostponedDay(meta));
        org.junit.Assert.assertNull(CarDiary.parsePostponedKm(meta));
        List<CarDiary.Entry> entries = new java.util.ArrayList<>();
        entries.add(entryAt(1, CarDiary.Category.WIPERS, now - 1000, null, meta));
        CarDiary.DueStatus d = statusOf(CarDiary.computeDueStatuses(entries, 20_000.0, now, 22.0,
            CarDiary.ScheduleConfig.defaults()), CarDiary.Category.WIPERS);
        org.junit.Assert.assertFalse(d.overdue);
        org.junit.Assert.assertTrue(d.daysRemaining >= 89 && d.daysRemaining <= 90);
    }
}
