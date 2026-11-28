package com.geely.voiceprobe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

final class BinderProbe {
    private static final String HVAC_PACKAGE = "com.flyme.auto.hvac";
    private static final ComponentName AI_CONTROL = new ComponentName(
        "com.geely.aicarcontrol", "com.geely.aicarcontrol.service.AiCarControlService");

    private BinderProbe() { }

    static void run(Context context) {
        try {
            ServiceInfo service = context.getPackageManager().getServiceInfo(AI_CONTROL, 0);
            ProbeLog.write(context, "IAiCarControl service installed exported=" + service.exported
                + " permission=" + service.permission);
            tryBind(context, AI_CONTROL, service.permission);
        } catch (PackageManager.NameNotFoundException e) {
            ProbeLog.write(context, "IAiCarControl service package is not installed: "
                + AI_CONTROL.flattenToShortString());
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(HVAC_PACKAGE,
                PackageManager.GET_SERVICES | PackageManager.GET_PERMISSIONS);
            if (info.services == null || info.services.length == 0) {
                ProbeLog.write(context, "HVAC package exposes no declared services");
                return;
            }
            for (ServiceInfo service : info.services) {
                String details = "HVAC service=" + service.name + " exported=" + service.exported
                    + " permission=" + service.permission;
                ProbeLog.write(context, details);
                if (!service.exported) continue;
                tryBind(context, new ComponentName(HVAC_PACKAGE, service.name), service.permission);
            }
        } catch (Throwable t) {
            ProbeLog.write(context, "HVAC package inspection failed: " + t);
        }
    }

    private static void tryBind(Context context, ComponentName component, String permission) {
                Intent intent = new Intent().setComponent(component);
                ServiceConnection connection = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                        ProbeLog.write(context, "bind connected=" + name.flattenToShortString()
                            + " descriptor=" + descriptor(binder));
                        context.unbindService(this);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        ProbeLog.write(context, "bind disconnected=" + name.flattenToShortString());
                    }
                };
                try {
                    boolean accepted = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                    ProbeLog.write(context, "bind requested=" + component.flattenToShortString()
                        + " accepted=" + accepted);
                } catch (SecurityException e) {
                    ProbeLog.write(context, "bind denied=" + component.flattenToShortString()
                        + " permission=" + permission + " error=" + e.getMessage());
                } catch (Throwable t) {
                    ProbeLog.write(context, "bind failed=" + component.flattenToShortString()
                        + " error=" + t);
                }
    }

    private static String descriptor(IBinder binder) {
        try { return binder.getInterfaceDescriptor(); }
        catch (Throwable t) { return "unavailable:" + t.getClass().getSimpleName(); }
    }
}
