package com.geely.drivemem.net;

import java.util.HashMap;
import java.util.Map;

/** Skips an MQTT message that repeats the last one sent on the same topic.
 *
 * The telemetry loop publishes every 10 s, but the car data behind it only
 * changes every 30 s, and the park / adb / location topics went out on every
 * tick with no change check — the same bytes up to three times (performance
 * review finding 6). A repeat is still sent once {@link #KEEPALIVE_MS} has
 * passed, so Home Assistant keeps seeing "still here". Clear on every
 * (re)connect so a restarted broker gets the full state again.
 *
 * Not thread-safe: MqttReporter calls it from its own handler thread only. */
final class PubDedupe {
    static final long KEEPALIVE_MS = 5 * 60_000L;

    private final Map<String, String> lastPayload = new HashMap<>();
    private final Map<String, Long> lastAt = new HashMap<>();

    /** True when this message should go out; records it as sent. */
    boolean shouldSend(String topic, String payload, long nowMs) {
        String prev = lastPayload.get(topic);
        Long at = lastAt.get(topic);
        if (prev != null && at != null && prev.equals(payload)
                && nowMs >= at && nowMs - at < KEEPALIVE_MS) {
            return false;
        }
        lastPayload.put(topic, payload);
        lastAt.put(topic, nowMs);
        return true;
    }

    /** A send that failed must not count as sent. */
    void forget(String topic) {
        lastPayload.remove(topic);
        lastAt.remove(topic);
    }

    void clear() {
        lastPayload.clear();
        lastAt.clear();
    }
}
