package com.geely.drivemem.controls;

import com.geely.drivemem.car.CarAccess;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Explicit, one-shot window commands. Call {@link #read} and {@link #request}
 * on the car actor thread, never on the UI thread. This class does not connect,
 * retain targets, retry writes, or move windows automatically.
 */
public final class WindowControls {
    public static final int FRONT_LEFT = 16;
    public static final int FRONT_RIGHT = 64;
    public static final int REAR_LEFT = 256;
    public static final int REAR_RIGHT = 1024;

    private static final int[] AREAS = {FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT};

    private WindowControls() {}

    /** REQUESTED means the write was accepted, not that the glass reached its target. */
    public enum Result { REQUESTED, UNCHANGED, UNAVAILABLE, REJECTED, CANCELLED }

    public static int[] areas() {
        return AREAS.clone();
    }

    public static boolean isArea(int area) {
        return area == FRONT_LEFT || area == FRONT_RIGHT
            || area == REAR_LEFT || area == REAR_RIGHT;
    }

    public static boolean isPosition(Integer position) {
        return position != null && position >= 0 && position <= 100;
    }

    /** Returns the measured position (0 = shut, 100 = fully open), or null if unknown. */
    public static Integer read(CarAccess car, int area) {
        requireArea(area);
        if (car == null || !car.isReady()) return null;
        Integer position = car.readIntRaw(CarAccess.WINDOW_POS, area);
        return car.isReady() && isPosition(position) ? position : null;
    }

    /**
     * Requests shut (0), halfway (50), or fully open (100) for one pane.
     * Reads the actual position first and leaves unreadable panes alone. UI must
     * continue to use readings, rather than the requested target, as the position.
     */
    public static Result request(CarAccess car, int area, int target) {
        return request(car, area, target, () -> true);
    }

    /**
     * As above, but abandons a queued or in-flight read when its initiating UI
     * is no longer active. The predicate must safely reflect lifecycle changes
     * across threads. An already submitted vehicle write cannot be recalled.
     */
    public static Result request(CarAccess car, int area, int target,
                                 BooleanSupplier shouldContinue) {
        requireArea(area);
        if (target != 0 && target != 50 && target != 100) {
            throw new IllegalArgumentException("Unsupported window target: " + target);
        }
        Objects.requireNonNull(shouldContinue, "shouldContinue");
        if (!shouldContinue.getAsBoolean()) return Result.CANCELLED;
        Integer position = read(car, area);
        if (!shouldContinue.getAsBoolean()) return Result.CANCELLED;
        if (position == null || !car.isReady()) return Result.UNAVAILABLE;
        if (position == target) return Result.UNCHANGED;
        if (!shouldContinue.getAsBoolean()) return Result.CANCELLED;
        return car.setIntRaw(CarAccess.WINDOW_POS, area, target)
            ? Result.REQUESTED : Result.REJECTED;
    }

    private static void requireArea(int area) {
        if (!isArea(area)) throw new IllegalArgumentException("Unsupported window area: " + area);
    }
}
