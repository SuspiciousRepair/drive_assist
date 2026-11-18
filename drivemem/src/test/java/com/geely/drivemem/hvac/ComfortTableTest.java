package com.geely.drivemem.hvac;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** The comfort table file: fixed columns per weather band, read back as
 * written, and a file the ruler cannot use is written again. */
public class ComfortTableTest {

    private static final ComfortTable DEF = ComfortTable.defaults();

    @Test public void eachBandIsTheRulesAtOneTemperatureInsideIt() {
        for (int f = 0; f < ComfortTable.BANDS; f++) {
            for (int sign : new int[]{1, -1}) {
                float out = ComfortLearn.bandOutC(sign, f);
                assertEquals(f, EffortTable.freeColumns(out, sign > 0));
                for (int k = 1; k <= EffortTable.MAX_LEVEL; k++) {
                    assertEquals(EffortTable.column(out, sign * k).toString(), DEF.get(sign * k, f).toString());
                }
            }
        }
    }

    @Test public void theSetpointNoLongerMovesInsideABand() {
        // 20-24 C outside: the rules gave C4 18, 19, 19, 20 C. The table: one value.
        float c4 = EffortTable.at(DEF.build(20.5f), 4).setpointC;
        for (float out = 20.5f; out <= 24f; out += 0.5f) {
            assertEquals(c4, EffortTable.at(DEF.build(out), 4).setpointC, 0f);
        }
    }

    @Test public void everyColumnReadsBackAsItselfAtEveryTemperature() {
        for (float out = 5f; out <= 42f; out += 0.5f) {
            EffortTable.Column[] t = DEF.build(out);
            for (int lv = -5; lv <= 5; lv++) {
                if (lv == 0) continue;
                assertTrue(EffortTable.name(lv) + " at " + out, ComfortLearn.selfFits(t, out, lv));
            }
        }
    }

    @Test public void theFileReadsBackAsWritten() throws Exception {
        ComfortLearn.Patch p = new ComfortLearn.Patch();
        p.fan = 4;
        ComfortTable t = DEF.with(3, 1, ComfortLearn.patched(DEF.get(3, 1), p));
        ComfortTable back = ComfortTable.fromJson(t.toJson());
        assertTrue(back.sameColumns(t));
        assertEquals(ComfortTable.VERSION, back.version);
        assertEquals(4, back.get(3, 1).fan);
    }

    @Test public void aGoodFileIsUsedAsItIs() {
        ComfortTable.Resolved r = ComfortTable.resolve(DEF.toJson(), new ComfortLearn.Book());
        assertFalse(r.write);
        assertTrue(r.table.sameColumns(DEF));
    }

    @Test public void noFileOrABadFileIsWrittenAgainWithWhatWasLearned() {
        ComfortLearn.Book b = ComfortLearn.Book.decode("L 3@1 fan=4\n");
        for (String text : new String[]{null, "", "{not json", "{\"version\":1}"}) {
            ComfortTable.Resolved r = ComfortTable.resolve(text, b);
            assertTrue(String.valueOf(text), r.write);
            assertEquals(4, r.table.get(3, 1).fan);
            assertEquals(DEF.get(2, 1).toString(), r.table.get(2, 1).toString());
        }
    }

    @Test public void valuesTheRulerCannotUseMakeTheFileBad() throws Exception {
        assertBad("fan", 9);
        assertBad("sp", 30);       // a warm setpoint on the cold side
        assertBad("dir", "up");
        assertBad("ac", false);    // C3 at 24-28 C is an AC column
    }

    private static void assertBad(String key, Object value) throws Exception {
        JSONObject root = new JSONObject(DEF.toJson());
        root.getJSONArray("cold").getJSONObject(1).getJSONObject("C3").put(key, value);
        ComfortTable.Resolved r = ComfortTable.resolve(root.toString(), new ComfortLearn.Book());
        assertTrue(key + "=" + value, r.write);
        assertTrue(r.table.sameColumns(DEF));
    }

    @Test public void anOtherVersionIsReplacedAndTheLearnedValuesTriedAgain() throws Exception {
        JSONObject root = new JSONObject(DEF.toJson());
        root.put("version", ComfortTable.VERSION - 1);
        // Fan 4 fits; fan 8 at C2 would blow harder than C3, so it is left out.
        ComfortLearn.Book b = ComfortLearn.Book.decode("L 3@1 fan=4\nL 2@1 fan=8\n");
        ComfortTable.Resolved r = ComfortTable.resolve(root.toString(), b);
        assertTrue(r.write);
        assertTrue(r.why, r.why.startsWith("version"));
        assertEquals(4, r.table.get(3, 1).fan);
        assertEquals(DEF.get(2, 1).fan, r.table.get(2, 1).fan);
        assertTrue(b.learned().containsKey("2@1"));   // still in the record
    }

    @Test public void bandLabelsAreReadable() {
        assertEquals("28 C and up", ComfortTable.bandLabel(1, 0));
        assertEquals("24-28 C", ComfortTable.bandLabel(1, 1));
        assertEquals("below 20 C", ComfortTable.bandLabel(1, 3));
        assertEquals("below 23 C", ComfortTable.bandLabel(-1, 0));
    }
}
