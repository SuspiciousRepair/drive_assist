package com.geely.modehelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Small dependency-free test runner for {@link PowerWatch}. */
public final class PowerWatchTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** Stand-in for CarPowerManager$CarPowerStateListener. */
    public interface FakeListener { void onStateChanged(int state); }

    /** Stand-in for CarPowerManager: same setListener shape. */
    public static final class FakeManager {
        FakeListener listener;
        Executor executor;
        public void setListener(FakeListener l, Executor e) { listener = l; executor = e; }
    }

    public static void main(String[] args) throws Exception {
        check(!PowerWatch.register(null, h -> { }), "null manager must not register");

        FakeManager pm = new FakeManager();
        List<Integer> seen = new ArrayList<>();
        check(PowerWatch.register(pm, FakeListener.class.getName(), seen::add), "register failed");
        check(pm.listener != null && pm.executor != null, "listener not set");

        pm.executor.execute(() -> pm.listener.onStateChanged(PowerWatch.SUSPEND_ENTER));
        pm.listener.onStateChanged(PowerWatch.SUSPEND_EXIT);
        check(seen.size() == 2 && seen.get(0) == PowerWatch.SUSPEND_ENTER
              && seen.get(1) == PowerWatch.SUSPEND_EXIT, "states not delivered: " + seen);

        // The framework keeps listeners in collections and logs them.
        pm.listener.hashCode();
        check(pm.listener.equals(pm.listener), "equals");
        check("PowerWatch".equals(pm.listener.toString()), "toString");

        System.out.println("PowerWatchTest OK");
    }
}
