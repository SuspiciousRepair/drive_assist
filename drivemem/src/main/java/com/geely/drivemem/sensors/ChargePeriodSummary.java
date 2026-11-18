package com.geely.drivemem.sensors;

import com.geely.drivemem.state.ChargeSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The numbers behind the charge page's four cards (owner's mock, 2026-11-18):
 * charges split AC/DC, cost split AC/DC, how low the battery went, and how
 * long since a charge to 100 %. Pure: built from the charge log and the daily
 * SoC ranges, unit tested.
 *
 * A charge with no port voltage counts as AC: the type is unknown, and the
 * page has no third colour for it.
 */
public final class ChargePeriodSummary {

    /** A charge that ended at or above this is a "full" one (LFP: the BMS
     * re-learns its % only at the top). */
    public static final int FULL_PCT = 100;

    public int count, acCount, dcCount;
    public double acKwh, dcKwh, acPowerW, dcPowerW;     // power: average over the charges
    public double acCost, dcCost;                       // only charges with a cost
    public double acKwhCosted, dcKwhCosted;             // their kWh, for R$/kWh
    public boolean anyCost;

    public SocHistory.DayRange lowest;                  // null without daily data
    public int daysBelow10, daysBelow20;
    public double avgPlugPct = -1;                      // average SoC when plugged in

    public long lastFullMs = -1;                        // over ALL charges, not just the period
    public long lastFullInPeriodMs = -1;                // the last one inside the period
    public int fullInPeriod;
    public long longestGapFromMs = -1, longestGapToMs = -1;  // to = -1 while still open

    public double totalKwh() { return acKwh + dcKwh; }
    public double totalCost() { return acCost + dcCost; }
    public double acPerKwh() { return acKwhCosted > 0 ? acCost / acKwhCosted : -1; }
    public double dcPerKwh() { return dcKwhCosted > 0 ? dcCost / dcKwhCosted : -1; }
    public double perKwh() {
        double k = acKwhCosted + dcKwhCosted;
        return k > 0 ? totalCost() / k : -1;
    }
    /** DC's share of the money spent, 0..1, or -1 with no cost at all. */
    public double dcCostShare() { return totalCost() > 0 ? dcCost / totalCost() : -1; }

    /**
     * @param all     every charge, any order (for the last full charge and
     *                the gaps, which reach back before the period)
     * @param fromMs  the period's start, inclusive
     * @param toMs    the period's end, exclusive; only charges starting in
     *                [fromMs, toMs) count in the period's numbers
     * @param nowMs   now: an open gap (no full charge since) runs to here
     * @param days    the period's daily SoC ranges (SocHistory.dailyRanges)
     */
    public static ChargePeriodSummary of(List<ChargeSession.Summary> all, long fromMs, long toMs,
                                         long nowMs, List<SocHistory.DayRange> days) {
        ChargePeriodSummary r = new ChargePeriodSummary();
        double plugSum = 0;
        int plugN = 0;
        List<Long> fullEnds = new ArrayList<>();
        for (ChargeSession.Summary s : all) {
            if (s.socEnd >= FULL_PCT && s.endWallMs > 0) fullEnds.add(s.endWallMs);
            if (s.startWallMs < fromMs || s.startWallMs >= toMs) continue;
            r.count++;
            boolean dc = s.isDcfc();
            if (dc) { r.dcCount++; r.dcKwh += s.kwh; r.dcPowerW += s.avgPowerW; }
            else    { r.acCount++; r.acKwh += s.kwh; r.acPowerW += s.avgPowerW; }
            if (s.cost != null && s.cost >= 0) {
                r.anyCost = true;
                if (dc) { r.dcCost += s.cost; r.dcKwhCosted += s.kwh; }
                else    { r.acCost += s.cost; r.acKwhCosted += s.kwh; }
            }
            if (s.socStart > 0 && s.socStart <= 100) { plugSum += s.socStart; plugN++; }
            if (s.socEnd >= FULL_PCT) {
                r.fullInPeriod++;
                if (s.endWallMs > r.lastFullInPeriodMs) r.lastFullInPeriodMs = s.endWallMs;
            }
        }
        if (r.acCount > 0) r.acPowerW /= r.acCount;
        if (r.dcCount > 0) r.dcPowerW /= r.dcCount;
        if (plugN > 0) r.avgPlugPct = plugSum / plugN;

        r.lowest = SocHistory.lowest(days);
        r.daysBelow10 = SocHistory.daysBelow(days, 10);
        r.daysBelow20 = SocHistory.daysBelow(days, 20);

        // Gaps between full charges. Only gaps that overlap the period count,
        // and the one since the last full charge is open to now.
        Collections.sort(fullEnds);
        if (!fullEnds.isEmpty()) r.lastFullMs = fullEnds.get(fullEnds.size() - 1);
        long best = -1;
        for (int i = 0; i < fullEnds.size(); i++) {
            long a = fullEnds.get(i);
            long b = i + 1 < fullEnds.size() ? fullEnds.get(i + 1) : nowMs;
            if (b < fromMs || a >= toMs) continue;
            if (b - a > best) {
                best = b - a;
                r.longestGapFromMs = a;
                r.longestGapToMs = i + 1 < fullEnds.size() ? b : -1;
            }
        }
        return r;
    }
}
