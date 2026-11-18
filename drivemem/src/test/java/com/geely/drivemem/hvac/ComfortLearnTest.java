package com.geely.drivemem.hvac;

import org.junit.Test;
import static org.junit.Assert.*;

/** Learn mode: what held hand changes teach the ruler, when, and what they never may. */
public class ComfortLearnTest {

    private static final float OUT = 26f;   // free = 1: C1 fan only, C2..C5 machine
    private static final int FACE = EffortTable.DIR_FACE, GLASS = EffortTable.DIR_GLASS;
    private static final long T0 = 1_800_000_000_000L;
    private static final long WEEK = ComfortLearn.PERIOD_MS;

    private static int fitted(EffortTable.Column[] t, float out, ComfortLearn.State s) {
        return EffortTable.fit(t, out, true, s.machine, s.sp, s.fan, s.dir, s.recirc);
    }

    private static final ComfortTable DEF = ComfortTable.defaults();

    private static String hold(ComfortLearn.Book b, float out, long now, int lastApplied, ComfortLearn.State s) {
        EffortTable.Column[] t = DEF.build(out);
        return ComfortLearn.hold(b, t, out, now, fitted(t, out, s), lastApplied, s);
    }

    private static ComfortLearn.Outcome decide(ComfortLearn.Book b, long now) {
        return ComfortLearn.decide(b, now, DEF);
    }

    private static final ComfortLearn.State FAN4 = new ComfortLearn.State(true, 21f, 4, FACE, true); // C3 is 21/f3/face/rec

    @Test public void holdsCountButTheRulerWaitsForTheWeek() {
        ComfortLearn.Book b = new ComfortLearn.Book();
        assertEquals("hold C3@1 fan=4 x1", hold(b, OUT, T0, 3, FAN4));
        assertEquals("hold C3@1 fan=4 x2", hold(b, 27f, T0 + 60_000, 3, FAN4));
        assertEquals(2, b.holdCount());

        ComfortLearn.Outcome early = decide(b, T0 + WEEK - 1);
        assertFalse(early.changed);
        assertTrue(early.notes.isEmpty());
        assertSame(DEF, early.table);

        ComfortLearn.Outcome o = decide(b, T0 + WEEK);
        assertTrue(o.changed);
        assertEquals("learn C3@1 fan=4 x2", o.notes.get(0));
        assertEquals(T0 + WEEK, b.lastChangeMs());
        assertEquals(0, b.holdCount());
        assertEquals(T0 + 2 * WEEK, b.nextDecisionMs());
        // Same band (24-28 C): learned. Other band (30 C): the default.
        assertEquals(4, EffortTable.at(o.table.build(OUT), 3).fan);
        assertEquals(3, EffortTable.at(o.table.build(30f), 3).fan);
        assertEquals(Integer.valueOf(4), b.learned().get("3@1").fan);   // the record, for replay
        // Nothing else moved.
        EffortTable.Column[] def = DEF.build(OUT), now = o.table.build(OUT);
        for (int lv = -5; lv <= 5; lv++) {
            if (lv != 3) assertEquals(EffortTable.at(def, lv).toString(), EffortTable.at(now, lv).toString());
        }
    }

    @Test public void theValueHeldMostOftenWins() {
        ComfortLearn.Book b = new ComfortLearn.Book();
        ComfortLearn.State fan5 = new ComfortLearn.State(true, 21f, 5, FACE, true);
        hold(b, OUT, T0, 3, FAN4);
        hold(b, OUT, T0, 3, fan5);
        hold(b, OUT, T0, 3, FAN4);
        ComfortLearn.Outcome o = decide(b, T0 + WEEK);
        assertTrue(o.changed);
        assertEquals(4, EffortTable.at(o.table.build(OUT), 3).fan);
    }

    @Test public void oneHoldOrATieLearnsNothing() {
        ComfortLearn.Book one = new ComfortLearn.Book();
        hold(one, OUT, T0, 3, FAN4);
        ComfortLearn.Outcome o1 = decide(one, T0 + WEEK);
        assertFalse(o1.changed);
        assertTrue(o1.notes.get(0).startsWith("undecided"));
        assertEquals(0, one.holdCount());   // the week is over either way

        ComfortLearn.Book tie = new ComfortLearn.Book();
        ComfortLearn.State fan5 = new ComfortLearn.State(true, 21f, 5, FACE, true);
        for (int i = 0; i < 2; i++) { hold(tie, OUT, T0, 3, FAN4); hold(tie, OUT, T0, 3, fan5); }
        ComfortLearn.Outcome o2 = decide(tie, T0 + WEEK);
        assertFalse(o2.changed);
        assertTrue(o2.notes.get(0).endsWith("tie"));
    }

    @Test public void aValueThatBreaksTheOrderIsRefused() {
        // Fan 8 at C3 would blow harder than C4 (fan 6).
        ComfortLearn.Book b = new ComfortLearn.Book();
        ComfortLearn.State fan8 = new ComfortLearn.State(true, 21f, 8, FACE, true);
        hold(b, OUT, T0, 3, fan8);
        hold(b, OUT, T0, 3, fan8);
        ComfortLearn.Outcome o = decide(b, T0 + WEEK);
        assertFalse(o.changed);
        assertTrue(o.notes.get(0).startsWith("refuse"));
        assertTrue(b.isEmpty());
        assertSame(DEF, o.table);
    }

    @Test public void moreThanTwoChangesIsLoggedAndIgnored() {
        ComfortLearn.Book b = new ComfortLearn.Book();
        ComfortLearn.State far = new ComfortLearn.State(true, 22f, 5, GLASS, true);
        String note = hold(b, OUT, T0, 3, far);
        assertNotNull(note);
        assertTrue(note, note.startsWith("skip "));
        assertEquals(0, b.holdCount());
    }

    @Test public void fanOnlyOnAHotDayTeachesNothing() {
        // 2026-11-16 15:40: AC off, fan 7, recirc at 33 C. Fits 0: no column to teach.
        ComfortLearn.Book b = new ComfortLearn.Book();
        assertNull(hold(b, 33f, T0, 0, new ComfortLearn.State(false, 20f, 7, FACE, true)));
        assertEquals(0, b.holdCount());
    }

    @Test public void allOutIsNeverLearned() {
        ComfortLearn.Book b = new ComfortLearn.Book();
        assertNull(hold(b, OUT, T0, 5, new ComfortLearn.State(true, 17f, 7, FACE, true)));
        assertEquals(0, b.holdCount());
    }

    @Test public void learningTheDefaultAgainForgetsIt() {
        ComfortLearn.Book b = ComfortLearn.Book.decode("S " + T0 + "\nL 3@1 fan=4\n");
        ComfortTable learned = ComfortLearn.replay(b, DEF);
        assertEquals(4, EffortTable.at(learned.build(OUT), 3).fan);
        ComfortLearn.State fan3 = new ComfortLearn.State(true, 21f, 3, FACE, true);
        EffortTable.Column[] t = learned.build(OUT);
        for (int i = 0; i < 2; i++) ComfortLearn.hold(b, t, OUT, T0, fitted(t, OUT, fan3), 3, fan3);
        ComfortLearn.Outcome o = ComfortLearn.decide(b, T0 + WEEK, learned);
        assertTrue(o.changed);
        assertTrue(b.isEmpty());
        assertEquals(3, EffortTable.at(o.table.build(OUT), 3).fan);
    }

    @Test public void aLateDecisionStillRunsAndTheNextWeekStartsThen() {
        // The car was never awake with the climate off on day 7: it runs on day 23.
        ComfortLearn.Book b = new ComfortLearn.Book();
        hold(b, OUT, T0, 3, FAN4);
        hold(b, OUT, T0, 3, FAN4);
        long late = T0 + 3 * WEEK + 2 * 86_400_000L;
        assertTrue(decide(b, late).changed);
        assertEquals(late + WEEK, b.nextDecisionMs());
        assertFalse(decide(b, late + 60_000).changed);   // once, not every beat
    }

    @Test public void theFirstLookStartsTheWeek() {
        ComfortLearn.Book b = new ComfortLearn.Book();
        assertEquals(0, b.nextDecisionMs());
        assertFalse(decide(b, T0).changed);
        assertEquals(T0 + WEEK, b.nextDecisionMs());
    }

    @Test public void aLearnedValueGoesIntoTheTableNextToAHandEdit() {
        // By hand: C3 at 24-28 C blows at the glass. The week then learns fan 4.
        ComfortTable hand = DEF.with(3, 1, ComfortLearn.patched(DEF.get(3, 1), dir(GLASS)));
        ComfortLearn.Book b = new ComfortLearn.Book();
        ComfortLearn.State s = new ComfortLearn.State(true, 21f, 4, GLASS, true);
        EffortTable.Column[] t = hand.build(OUT);
        for (int i = 0; i < 2; i++) ComfortLearn.hold(b, t, OUT, T0, fitted(t, OUT, s), 3, s);
        ComfortLearn.Outcome o = ComfortLearn.decide(b, T0 + WEEK, hand);
        assertTrue(o.notes.toString(), o.changed);
        EffortTable.Column c3 = o.table.get(3, 1);
        assertEquals(4, c3.fan);
        assertEquals(GLASS, c3.direction);   // the hand edit stays
    }

    private static ComfortLearn.Patch dir(int d) {
        ComfortLearn.Patch p = new ComfortLearn.Patch();
        p.dir = d;
        return p;
    }

    @Test public void bookSurvivesTextAndIgnoresJunk() {
        ComfortLearn.Book b = ComfortLearn.Book.decode(
            "S 5\nC 7\nL 3@1 fan=4 sp=22.0 dir=1 rec=0\nH -2@0/fan 3 1\nH -2@0/fan 4 2\n"
            + "garbage\nL x\nH a b c\nS x\n");
        ComfortLearn.Book again = ComfortLearn.Book.decode(b.encode());
        assertEquals(b.encode(), again.encode());
        assertEquals(5L + WEEK, again.nextDecisionMs());
        assertEquals(7L, again.lastChangeMs());
        assertEquals(3, again.holdCount());
        ComfortLearn.Patch p = again.learned().get("3@1");
        assertEquals(Integer.valueOf(4), p.fan);
        assertEquals(22f, p.sp, 0f);
        assertEquals(Integer.valueOf(1), p.dir);
        assertEquals(Boolean.FALSE, p.rec);
        assertEquals(1, again.learned().size());
        assertTrue(ComfortLearn.Book.decode(null).isEmpty());
        assertTrue(ComfortLearn.Book.decode("").isEmpty());
    }

    @Test public void holdCountsOnceAfterTenMinutes() {
        ComfortLearn.Watcher w = new ComfortLearn.Watcher();
        long t = 1_000_000L;
        for (long m = 0; m < ComfortLearn.HOLD_MS; m += 30_000) assertNull(w.observe(t + m, FAN4, true));
        assertNotNull(w.observe(t + ComfortLearn.HOLD_MS, FAN4, true));
        assertNull(w.observe(t + ComfortLearn.HOLD_MS + 30_000, FAN4, true));
    }

    @Test public void suspendOrChangeOrIneligibleStartsTheHoldAgain() {
        ComfortLearn.State other = new ComfortLearn.State(true, 21f, 5, FACE, true);
        long t = 1_000_000L;

        ComfortLearn.Watcher gap = new ComfortLearn.Watcher();
        gap.observe(t, FAN4, true);
        assertNull(gap.observe(t + ComfortLearn.HOLD_MS, FAN4, true));   // one long gap: a suspend

        ComfortLearn.Watcher change = new ComfortLearn.Watcher();
        for (long m = 0; m < ComfortLearn.HOLD_MS; m += 30_000) change.observe(t + m, FAN4, true);
        assertNull(change.observe(t + ComfortLearn.HOLD_MS, other, true));

        ComfortLearn.Watcher tap = new ComfortLearn.Watcher();
        for (long m = 0; m < ComfortLearn.HOLD_MS; m += 30_000) tap.observe(t + m, FAN4, true);
        assertNull(tap.observe(t + ComfortLearn.HOLD_MS, FAN4, false));
        assertNull(tap.observe(t + ComfortLearn.HOLD_MS + 30_000, FAN4, true));
    }

    @Test public void aBreakInTheHoldDoesNotCountTheSameChangeTwice() {
        ComfortLearn.State other = new ComfortLearn.State(true, 21f, 5, FACE, true);
        ComfortLearn.Watcher w = new ComfortLearn.Watcher();
        long t = 1_000_000L, m = 0;
        int votes = 0;
        for (; m <= ComfortLearn.HOLD_MS; m += 30_000) if (w.observe(t + m, FAN4, true) != null) votes++;
        w.observe(t + m, FAN4, false); m += 30_000;            // defrost press
        w.observe(t + m, other, true); m += 30_000;            // passenger, and back
        w.observe(t + m, null, true);  m += 30_000;            // one failed read
        for (long end = m + 2 * ComfortLearn.HOLD_MS; m <= end; m += 30_000) {
            if (w.observe(t + m, FAN4, true) != null) votes++;
        }
        assertEquals(1, votes);

        // The next drive (after a suspend) counts it again.
        m += 8 * 60 * 60_000L;
        for (long end = m + ComfortLearn.HOLD_MS; m <= end; m += 30_000) {
            if (w.observe(t + m, FAN4, true) != null) votes++;
        }
        assertEquals(2, votes);
    }

    @Test public void aNewStateAfterAVoteStillCounts() {
        ComfortLearn.State other = new ComfortLearn.State(true, 21f, 5, FACE, true);
        ComfortLearn.Watcher w = new ComfortLearn.Watcher();
        long t = 1_000_000L, m = 0;
        int votes = 0;
        for (; m <= ComfortLearn.HOLD_MS; m += 30_000) if (w.observe(t + m, FAN4, true) != null) votes++;
        for (long end = m + ComfortLearn.HOLD_MS; m <= end; m += 30_000) {
            if (w.observe(t + m, other, true) != null) votes++;
        }
        assertEquals(2, votes);
    }

    @Test public void bandsMatchTheFreeColumns() {
        assertArrayEquals(new float[]{24f, 28f}, ComfortLearn.band(3, EffortTable.freeColumns(26f, true)), 0f);
        assertEquals(28f, ComfortLearn.band(3, EffortTable.freeColumns(30f, true))[0], 0f);
        assertTrue(Float.isNaN(ComfortLearn.band(3, 0)[1]));
        assertArrayEquals(new float[]{23f, 25f}, ComfortLearn.band(-2, EffortTable.freeColumns(24f, false)), 0f);
        for (int free = 0; free <= 3; free++) {
            assertEquals(free, EffortTable.freeColumns(ComfortLearn.bandOutC(3, free), true));
            assertEquals(free, EffortTable.freeColumns(ComfortLearn.bandOutC(-3, free), false));
        }
    }
}
