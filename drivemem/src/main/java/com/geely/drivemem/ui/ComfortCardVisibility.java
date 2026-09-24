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
     * Music card visible when Spotify has an actual track loaded.
     * Starts GONE — nothing to show until a real title arrives.
     */
    public static boolean showMusicCard(String title) {
        return title != null && !title.isEmpty();
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
