package com.geely.drivemem;

import com.geely.drivemem.net.Updater;

import org.junit.Test;
import static org.junit.Assert.*;

/** CloudFlare caches /local with a 31-day max-age. cacheBust() is the one
 * place both check() and update() force a fresh fetch for a URL that has no
 * query of its own -- see Updater.cacheBust()'s own comment. Before this,
 * only update() (the install step) did this; check() (the "is there an
 * update" step, including the manually-typed Config URL field) did not,
 * so it could report "up to date" off a stale cached file. */
public class UpdaterCacheBustTest {

    @Test public void bareUrlGetsATimestampQuery() {
        String bust = Updater.cacheBust("https://ha.example.com/local/drive_assist.apk");
        assertTrue(bust.startsWith("https://ha.example.com/local/drive_assist.apk?t="));
    }

    @Test public void versionedUrlIsLeftAlone() {
        // An announced "?v=<versionCode>" URL must stay stable -- check()'s
        // own [?&]v=(\d+) parsing, and update()'s "already applied" check,
        // both depend on this exact string not changing between calls.
        String u = "https://ha.example.com/local/drive_assist.apk?v=29836427";
        assertEquals(u, Updater.cacheBust(u));
    }

    @Test public void anyExistingQueryIsLeftAlone() {
        // Not just "?v=" -- any query at all means "don't touch it", same
        // rule update() already followed before this fix existed.
        String u = "https://ha.example.com/local/drive_assist.apk?t=123";
        assertEquals(u, Updater.cacheBust(u));
    }
}
