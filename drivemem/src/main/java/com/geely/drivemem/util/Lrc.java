package com.geely.drivemem.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Synced lyrics in LRC form ("[01:02.34]words"), read once into two
 * sorted arrays: when each line starts, and its text. Pure Java, no
 * Android, so the unit tests run it directly.
 *
 * What the format allows and this reads:
 * - more than one time tag on a line ("[00:10.00][01:20.00]chorus"),
 *   which repeats the same text at each time;
 * - minutes, seconds, and 0 to 3 digits of fraction;
 * - an "[offset:+500]" tag, in ms. A positive offset makes every line
 *   come earlier, as the LRC format defines it;
 * - an empty text, which is a gap in the singing (the card shows a note).
 * Any other tag ("[ar:...]", "[length:...]") and any line with no time tag
 * is skipped. */
public final class Lrc {
    /** The first line of a cached plain (untimed) text, see plain(). */
    public static final String PLAIN_MARK = "#plain";

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    private final long[] times;   // empty for plain lyrics
    private final String[] texts;

    private Lrc(long[] times, String[] texts) { this.times = times; this.texts = texts; }

    /** Plain lyrics: the lines of the text, with no times. Blank lines (the
     * gaps between verses) are dropped: the driver steps line by line. Null
     * when there is no line. */
    public static Lrc plain(String text) {
        if (text == null) return null;
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) lines.add(t);
        }
        return lines.isEmpty() ? null : new Lrc(new long[0], lines.toArray(new String[0]));
    }

    /** What LyricsClient keeps: LRC text, or PLAIN_MARK and plain text. */
    public static Lrc parseAny(String text) {
        if (text == null) return null;
        if (text.startsWith(PLAIN_MARK + "\n")) return plain(text.substring(PLAIN_MARK.length() + 1));
        return parse(text);
    }

    /** One line down (or back) from index, kept inside the text: how the
     * driver moves through plain lyrics. */
    public int step(int index, boolean down) {
        if (texts.length == 0) return 0;
        return Math.max(0, Math.min(texts.length - 1, index + (down ? 1 : -1)));
    }

    /** False for plain lyrics: no times, the driver moves through them. */
    public boolean timed() { return times.length > 0; }

    /** Null when the text has no timed line at all (plain lyrics, or junk). */
    public static Lrc parse(String lrc) {
        if (lrc == null || lrc.isEmpty()) return null;
        long offset = 0;
        List<long[]> order = new ArrayList<>();   // {time, index into raw}
        List<String> raw = new ArrayList<>();
        for (String line : lrc.split("\r?\n")) {
            Matcher off = OFFSET.matcher(line.trim());
            if (off.find()) {
                try { offset = Long.parseLong(off.group(1)); } catch (NumberFormatException ignored) {}
                continue;
            }
            Matcher m = TIME.matcher(line);
            int end = 0;
            List<Long> stamps = new ArrayList<>();
            // Tags only count at the start of the line, back to back.
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long frac = 0;
                String f = m.group(3);
                if (f != null) {
                    frac = Long.parseLong(f);
                    if (f.length() == 1) frac *= 100;
                    else if (f.length() == 2) frac *= 10;
                }
                stamps.add(min * 60_000L + sec * 1000L + frac);
                end = m.end();
            }
            if (stamps.isEmpty()) continue;
            String text = line.substring(end).trim();
            raw.add(text);
            for (long t : stamps) order.add(new long[]{t, raw.size() - 1});
        }
        if (order.isEmpty()) return null;
        // Stable: two lines with the same time keep the order of the file.
        Collections.sort(order, (a, b) -> Long.compare(a[0], b[0]));
        long[] times = new long[order.size()];
        String[] texts = new String[order.size()];
        for (int i = 0; i < order.size(); i++) {
            times[i] = Math.max(0, order.get(i)[0] - offset);
            texts[i] = raw.get((int) order.get(i)[1]);
        }
        return new Lrc(times, texts);
    }

    /** These lines moved by the owner's timing fix. The same lines when
     * there is none, or when they have no times. */
    public Lrc synced(LyricSync sync) {
        if (sync == null || sync.isEmpty() || times.length == 0) return this;
        long[] t = new long[times.length];
        // LyricSync keeps its points rising, so the times stay sorted.
        for (int i = 0; i < t.length; i++) t[i] = Math.max(0, sync.map(times[i]));
        return new Lrc(t, texts);
    }

    public int size() { return texts.length; }
    /** Only for timed lyrics. */
    public long timeAt(int i) { return times[i]; }
    public String textAt(int i) { return texts[i]; }

    /** What the card shows for line i: "" when there is no such line (before
     * the first, after the last), a note for a gap in the singing ("♪" is a
     * sign, not prose), else the text. */
    public String display(int i) {
        if (i < 0 || i >= texts.length) return "";
        return texts[i].isEmpty() ? "\u266A" : texts[i];
    }

    /** The line sung at positionMs: the last one that started at or before
     * it. -1 before the first line, and always for plain lyrics. */
    public int indexAt(long positionMs) {
        int lo = 0, hi = times.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= positionMs) { found = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return found;
    }
}
