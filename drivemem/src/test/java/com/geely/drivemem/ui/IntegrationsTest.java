package com.geely.drivemem.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/** The Integrations page's tile list: each tile opens its own page, and the
 * sidebar highlight (forSection) finds it. */
public class IntegrationsTest {

    @Test public void everyTileOpensADifferentPage() {
        Set<Integer> seen = new HashSet<>();
        for (Integrations.Integration i : Integrations.ALL) {
            assertTrue("two tiles open section " + i.section, seen.add(i.section));
        }
    }

    @Test public void forSectionFindsEveryTile() {
        for (Integrations.Integration i : Integrations.ALL) {
            assertEquals(i, Integrations.forSection(i.section));
        }
    }

    @Test public void theOldSidebarPagesAreNowTiles() {
        // MQTT and OBD2 were sidebar items; a "section" extra that
        // still names one must land on that tile's page, under Integrations.
        assertNotNull(Integrations.forSection(TelemetryActivity.SEC_MQTT));
        assertNotNull(Integrations.forSection(TelemetryActivity.SEC_OBD));
        assertNotNull(Integrations.forSection(TelemetryActivity.SEC_ABRP));
        assertNotNull(Integrations.forSection(TelemetryActivity.SEC_TOLLS));
    }

    @Test public void musicHasOneTileAndSpotifyNone() {
        assertNotNull(Integrations.forSection(TelemetryActivity.SEC_MUSIC));
        assertNull("9 was Spotify: retired", Integrations.forSection(9));
        int music = 0;
        for (Integrations.Integration i : Integrations.ALL)
            if (i.section == TelemetryActivity.SEC_MUSIC) music++;
        assertEquals(1, music);
    }

    @Test public void carPagesAreNotIntegrations() {
        assertNull(Integrations.forSection(0));    // Drive
        assertNull(Integrations.forSection(10));   // System, where CAN lives
    }

    // The page asks every tile for its state every 2 s while it is shown,
    // and redraws a tile only when the answer changed.
    @Test public void aTileIsRedrawnOnlyWhenItsStateChanged() {
        Integrations.Status connecting = new Integrations.Status(Integrations.State.BUSY, "Connecting...");
        assertTrue(connecting.sameAs(new Integrations.Status(Integrations.State.BUSY, "Connecting...")));
        assertFalse(connecting.sameAs(new Integrations.Status(Integrations.State.OK, "Connected")));
        assertFalse(connecting.sameAs(new Integrations.Status(Integrations.State.BUSY, "Searching")));
        assertFalse(connecting.sameAs(null));   // nothing shown yet: draw
    }

    @Test public void theTickIsLight() {
        assertTrue(TelemetryIntegrationsSection.REFRESH_MS >= 2000);
    }
}
