package com.geely.modehelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/** Compiles Drive Assist and this helper fully ("speed") after each install.
 *
 * This head unit ships with the JIT off (dalvik.vm.usejit=false) and
 * installs apps as "speed-profile". That filter compiles only the methods a
 * usage profile names, and the profile is collected by the JIT — so with
 * the JIT off there is never a profile, nothing is compiled, and every line
 * of both apps runs in the interpreter forever. Measured on the car: clip
 * recovery went from ~0.4 MB/s to ~16 MB/s once compiled.
 *
 * An APK cannot carry compiled code; the device compiles it. This asks the
 * package manager to do it, as uid system, the same call `cmd package
 * compile -m speed -f` makes. It runs once per installed versionCode, so
 * adb installs, OTA installs and installer runs are all covered. */
final class Dexopt {
    static final String TAG = "ModeHelper";
    private static final String[] PACKAGES = {"com.geely.drivemem", "com.geely.modehelper"};

    private Dexopt() { }

    /** Cheap when nothing changed: one getPackageInfo per package. Blocks
     * for the compile itself (tens of seconds), so call it off the main
     * thread. */
    static void ensure(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("modehelper", Context.MODE_PRIVATE);
        for (String pkg : PACKAGES) {
            try {
                PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg, 0);
                String key = "dexopt_vc_" + pkg;
                if (p.getInt(key, -1) == info.versionCode) continue;
                long t0 = System.currentTimeMillis();
                boolean ok = compile(pkg);
                Log.i(TAG, "dexopt: " + pkg + " vc=" + info.versionCode + " speed="
                    + ok + " in " + (System.currentTimeMillis() - t0) + " ms");
                // Recorded either way: a refusal is not going to change for
                // this version, and retrying every minute would only burn CPU.
                p.edit().putInt(key, info.versionCode).apply();
            } catch (Throwable t) {
                Log.w(TAG, "dexopt: " + pkg + ": " + t);
            }
        }
    }

    // IPackageManager.performDexOptMode(packageName, checkProfiles,
    // targetCompilerFilter, force, bootComplete, splitName) — hidden API,
    // reachable because this app is platform-signed.
    static boolean compile(String pkg) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "package");
        Object pm = Class.forName("android.content.pm.IPackageManager$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Method m = pm.getClass().getMethod("performDexOptMode", String.class, boolean.class,
            String.class, boolean.class, boolean.class, String.class);
        return (Boolean) m.invoke(pm, pkg, false, "speed", true, true, null);
    }
}
