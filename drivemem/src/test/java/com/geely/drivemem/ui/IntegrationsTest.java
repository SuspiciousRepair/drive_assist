package com.geely.drivemem.ui;

import static org.junit.Assert.assertEquals;
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
        // MQTT, OBD2 and Spotify were sidebar items; a "section" extra that
        // still names one must land on that tile's page, under Integrations.
        assertNotNull(Integrations.forSection(TelemetryActivity.SEC_MQTT));
        assertNotNull(Integrations.forSection(TelemetryActivity.SEC_OBD));
        assertNotNull(Integrations.forSection(TelemetryActivity.SEC_SPOTIFY));
        assertNotNull(Integrations.forSection(TelemetryActivity.SEC_ABRP));
    }

    @Test public void carPagesAreNotIntegrations() {
        assertNull(Integrations.forSection(0));    // Drive
        assertNull(Integrations.forSection(10));   // System, where CAN lives
    }
}
