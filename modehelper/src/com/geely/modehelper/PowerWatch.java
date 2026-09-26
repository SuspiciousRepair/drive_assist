package com.geely.modehelper;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;

/** Listens to CarPowerManager's power states, by reflection: the class is
 * not in car-stubs, but it exists on the car. Plain Java otherwise, so the
 * wiring can be tested on a desktop JVM against a stand-in manager.
 *
 * The state values are Android 9's CarPowerStateListener, read off this
 * head unit with reflection on 2026-09-26. Android 10 renumbered them, so
 * they are pinned here rather than assumed. */
final class PowerWatch {
    static final String LISTENER = "android.car.hardware.power.CarPowerManager$CarPowerStateListener";
    static final int SHUTDOWN_CANCELLED = 0, SHUTDOWN_ENTER = 1, SUSPEND_ENTER = 2, SUSPEND_EXIT = 3;

    interface Handler { void onState(int state); }

    private PowerWatch() { }

    /** Registers `h` with `powerManager` (a CarPowerManager). Returns false
     * when there is nothing to register with; throws when the API is not
     * the one this was written against. */
    static boolean register(Object powerManager, Handler h) throws Exception {
        return register(powerManager, LISTENER, h);
    }

    static boolean register(Object powerManager, String listenerClass, Handler h) throws Exception {
        if (powerManager == null) return false;
        Class<?> listener = Class.forName(listenerClass, false, powerManager.getClass().getClassLoader());
        Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener},
            (self, m, args) -> {
                switch (m.getName()) {
                    case "onStateChanged": h.onState((Integer) args[0]); return null;
                    case "hashCode": return System.identityHashCode(self);
                    case "equals": return self == args[0];
                    case "toString": return "PowerWatch";
                    default: return null;
                }
            });
        Executor direct = Runnable::run;
        Method set = powerManager.getClass().getMethod("setListener", listener, Executor.class);
        set.invoke(powerManager, proxy, direct);
        return true;
    }
}
