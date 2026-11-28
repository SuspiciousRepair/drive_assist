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
        return showGateCard(available, connected, false);
    }

    /** Hidden while Valet Mode is active (only Climate + Valet card remain). */
    public static boolean showGateCard(boolean available, boolean connected, boolean valetActive) {
        return !valetActive && available && connected;
    }

    /**
     * Turbo card (and Strong Regen alongside it) only means anything while driving.
     * Hidden while parked or while Valet Mode is active.
     */
    public static boolean showTurboCard(boolean turboEnabledAtBuild, boolean parked) {
        return showTurboCard(turboEnabledAtBuild, parked, false);
    }

    public static boolean showTurboCard(boolean turboEnabledAtBuild, boolean parked, boolean valetActive) {
        return !valetActive && turboEnabledAtBuild && !parked;
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
        return showMusicCard(title, false);
    }

    public static boolean showMusicCard(String title, boolean valetActive) {
        return !valetActive && title != null && !title.isEmpty();
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
     * Where the jumbo card goes, and when it goes there with no room.
     *
     * It is placed when it fits. When the other cards took its room, it gives
     * way (the music card goes back to its own size) until the driver asks for
     * the lyrics again: then it is placed anyway, over columns 2 and 3, and
     * the cards there are covered. {@code askedAtColumns} is the
     * number of columns the others used at that ask (0 = no ask). A crowd that
     * grows after the ask takes the room again.
     */
    public static boolean jumboPlaced(int columnsUsedByOthers, int askedAtColumns) {
        return jumboFits(columnsUsedByOthers)
            || (askedAtColumns > 0 && columnsUsedByOthers <= askedAtColumns);
    }

    /** What is left of the driver's ask after a pack: kept only while it is
     * still what holds the card up. */
    public static int jumboAskAfterPack(int columnsUsedByOthers, int askedAtColumns) {
        if (askedAtColumns <= 0 || jumboFits(columnsUsedByOthers)) return 0;
        return columnsUsedByOthers <= askedAtColumns ? columnsUsedByOthers : 0;
    }

    /**
     * Charge card shows active charging progress when parked and actively charging,
     * unless Valet is active (where the purple "End Valet" card stands alone).
     * Charging only makes sense while parked — see CarState's header for why this
     * is a real check, not just tidiness.
     */
    public static boolean showActiveCharging(boolean parked, boolean charging) {
        return showActiveCharging(parked, charging, false);
    }

    public static boolean showActiveCharging(boolean parked, boolean charging, boolean valetActive) {
        return parked && charging && !valetActive;
    }

    /**
     * Charge card shows completed charge session when one exists and hasn't been dismissed,
     * unless Valet is active.
     * The dismissal is local (stored in SharedPreferences), doesn't depend on broker.
     */
    public static boolean showCompletedCharging(boolean hasRetainedSession) {
        return showCompletedCharging(hasRetainedSession, false);
    }

    public static boolean showCompletedCharging(boolean hasRetainedSession, boolean valetActive) {
        return showCompletedCharging(hasRetainedSession, valetActive, true);
    }

    /** Only while parked: leaving Park closes a finished charge's card for
     * good (see dismissOnLeavingPark). */
    public static boolean showCompletedCharging(boolean hasRetainedSession, boolean valetActive, boolean parked) {
        return showCompletedCharging(hasRetainedSession, valetActive, parked, false);
    }

    /** The finished card is there to ask for the cost: once the charge has
     * one (zero too), it is done and goes (see dismissWhenCosted). */
    public static boolean showCompletedCharging(boolean hasRetainedSession, boolean valetActive, boolean parked,
                                                boolean hasCost) {
        return hasRetainedSession && !valetActive && parked && !hasCost;
    }

    /** A finished charge with a cost (zero counts) is closed for good. */
    public static boolean dismissWhenCosted(boolean hasFinishedCharge, Double cost) {
        return hasFinishedCharge && cost != null && cost >= 0;
    }

    /** A finished charge's card is closed for good when the car leaves Park:
     * the next time the car parks, the Parked card is there, not an old
     * charge. Its cost can still be entered in Charging stats. */
    public static boolean dismissOnLeavingPark(boolean parked, boolean hasFinishedCharge) {
        return !parked && hasFinishedCharge;
    }

    /**
     * Journey/Drive card visibility. The card shows:
     * - When Valet is active (any time, moving or not)
     * - When parked and not actively charging (always available as entry point, even if drive card is disabled;
     *   while actively charging, the Charging card replaces the Parked card)
     * - When driving and drive card is enabled
     *
     * The parked entry point remains available even when the optional live drive card
     * is disabled; otherwise Valet could become unreachable.
     */
    public static boolean showJourneyCard(boolean valetActive, boolean parked, boolean driveCardEnabled,
                                         boolean driving) {
        return showJourneyCard(valetActive, parked, driveCardEnabled, driving, false);
    }

    public static boolean showJourneyCard(boolean valetActive, boolean parked, boolean driveCardEnabled,
                                         boolean driving, boolean activeCharging) {
        return valetActive || (parked && !activeCharging) || (driveCardEnabled && driving);
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
        return showPanelCard(isEmpty, isDismissed, false);
    }

    public static boolean showPanelCard(boolean isEmpty, boolean isDismissed, boolean valetActive) {
        return !valetActive && !isEmpty && !isDismissed;
    }

    /** Dock tabs for Stats and Charging history are hidden during Valet Mode. */
    public static boolean showDockStats(boolean valetActive) {
        return !valetActive;
    }
}
