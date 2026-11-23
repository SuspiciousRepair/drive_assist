package com.geely.drivemem.ui;

import com.geely.drivemem.state.CarState;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.state.GateState;
import com.geely.drivemem.state.TripSession;
import com.geely.drivemem.state.ValetSession;

/**
 * Pure-function visibility rules for comfort activity cards.
 *
 * Each method encapsulates the decision logic for whether a particular card
 * or UI element should be visible. These are extracted from ComfortActivity's
 * inline expressions to be independently testable, without Android runtime
 * dependencies (all inputs are simple primitives or booleans derived from
 * vehicle state).
 */
public final class ComfortCardVisibility {
    private ComfortCardVisibility() {}

    /**
     * Gate (Portão) card visible when HA says gate is reachable AND car has broker connection.
     * Connection gating is a security control, not just convenience: a stale
     * "available" the car can no longer verify (Wi-Fi dropped after HA last said yes)
     * must hide the card.
     */
    public static boolean showGateCard(boolean available, boolean connected) {
        return available && connected;
    }

    /**
     * Turbo card (and Strong Regen alongside it) only means anything while driving.
     * Hidden while parked. Visibility depends on config: turbo card feature must be
     * enabled in settings. Only applies when enabled; when disabled, card doesn't exist.
     */
    public static boolean showTurboCard(boolean turboEnabledAtBuild, boolean parked) {
        return turboEnabledAtBuild && !parked;
    }

    /**
     * The buttons the Turbo card shows, in order, as one char each:
     * 'T' Turbo, 'R' Regen, 'A' AVAS. Empty means the card has nothing to show,
     * so it is not built at all (see turboCardBuilt).
     */
    public static String turboCardSlots(boolean turbo, boolean regen, boolean avas) {
        return (turbo ? "T" : "") + (regen ? "R" : "") + (avas ? "A" : "");
    }

    /** The card is built only when it is enabled AND has at least one button. */
    public static boolean turboCardBuilt(boolean turboCardEnabled, String slots) {
        return turboCardEnabled && slots != null && !slots.isEmpty();
    }

    /**
     * Music card visible when the phone's music session has a track.
     * Starts GONE — nothing to show until a real title arrives.
     */
    public static boolean showMusicCard(String title) {
        return title != null && !title.isEmpty();
    }

    /**
     * The jumbo music card (two columns, full height, lyrics), before the
     * room check: Config's Lyrics search on, left open (the driver's last choice),
     * music playing, lines for the track. Never with the Home Assistant panel
     * up: the panel needs the room. Parked or driving alike (owner's call,
     * 2026-11-22).
     */
    public static boolean showLyrics(boolean enabled, boolean open, boolean musicShown,
                                     boolean haveLines, boolean panelShown) {
        return enabled && open && musicShown && haveLines && !panelShown;
    }

    /** The screen shows three columns; a fourth means scrolling sideways. */
    public static final int SCREEN_COLUMNS = 3;
    /** The jumbo music card's width, in columns. */
    public static final int JUMBO_COLUMNS = 2;

    /** The jumbo card fits only when the other cards fit in the columns it
     * leaves; else the music card keeps its own size. */
    public static boolean jumboFits(int columnsUsedByOthers) {
        return columnsUsedByOthers + JUMBO_COLUMNS <= SCREEN_COLUMNS;
    }

    /**
     * Charge card shows active charging progress when parked and actively charging.
     * Charging only makes sense while parked — see CarState's header for why this
     * is a real check, not just tidiness.
     */
    public static boolean showActiveCharging(boolean parked, boolean charging) {
        return parked && charging;
    }

    /**
     * Charge card shows completed charge session when one exists and hasn't been dismissed.
     * The dismissal is local (stored in SharedPreferences), doesn't depend on broker.
     */
    public static boolean showCompletedCharging(boolean hasRetainedSession) {
        return hasRetainedSession;
    }

    /**
     * Journey/Drive card visibility. The card shows:
     * - When Valet is active (any time, moving or not)
     * - When parked (always available as entry point, even if drive card is disabled)
     * - When driving and drive card is enabled, UNLESS dismissed for this specific trip
     *
     * The parked entry point remains available even when the optional live drive card
     * is disabled; otherwise Valet could become unreachable.
     */
    public static boolean showJourneyCard(boolean valetActive, boolean parked, boolean driveCardEnabled,
                                         boolean driving, boolean dismissed) {
        return valetActive || parked || (driveCardEnabled && driving && !dismissed);
    }

    /**
     * Journey card dismiss button (×) visible only while driving, and not when Valet is active.
     * Can't dismiss while parked or in Valet mode.
     */
    public static boolean showJourneyDismissButton(boolean driving, boolean valetActive) {
        return driving && !valetActive;
    }

    /**
     * Journey card action button (Start/Stop Valet, or Start Valet from parked state).
     * Visible when parked (Start/Stop) or when Valet is active (Stop).
     * Not visible while driving normally (no action to show).
     */
    public static boolean showJourneyActionButton(boolean parked, boolean valetActive) {
        return parked || valetActive;
    }

    /**
     * HA context panel card visible when there's actual HTML content AND it hasn't been dismissed.
     * Empty payload ("", "{}", null) hides it. A different content brings it back even if previously dismissed.
     * Dismissal is local only, stored in SharedPreferences, doesn't depend on broker.
     */
    public static boolean showPanelCard(boolean isEmpty, boolean isDismissed) {
        return !isEmpty && !isDismissed;
    }
}
