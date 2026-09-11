package com.geely.modehelper;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.Surface;

/** Client for the vendor camera system via bdstar.render.engine. Protocol details
 * are documented in docs/EVS-CAMERA.md. This binder is the only way to access
 * camera frames; V4L2 devices are not available on this unit. */
public final class EvsClient {
    static final String TAG = "ModeHelper";

    // Service name AND interface descriptor are the same string.
    private static final String DESC = "bdstar.render.engine";
    public static final String CAMERA_AVM = "avm";
    // DVR function: 2x2 view of all four cameras at 1920x800 (see docs/EVS-CAMERA.md).
    public static final String TYPE_DVR = "dvr";

    private static final int TX_OPEN_CAMERA = 1;
    private static final int TX_ATTACH      = 2;

    private IBinder binder;

    public boolean connect() {
        if (binder != null && binder.isBinderAlive()) return true;
        try {
            Object o = Class.forName("android.os.ServiceManager")
                            .getMethod("getService", String.class).invoke(null, DESC);
            binder = (o instanceof IBinder) ? (IBinder) o : null;
        } catch (Throwable t) {
            Log.w(TAG, "evs: getService failed: " + t);
            binder = null;
        }
        if (binder == null) Log.w(TAG, "evs: no " + DESC);
        return binder != null;
    }

    public boolean alive() { return binder != null && binder.isBinderAlive(); }

    public boolean openCamera(String cameraId) {
        if (binder == null) return false;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC);
            cstr(d, cameraId);
            boolean ok = binder.transact(TX_OPEN_CAMERA, d, r, 0);
            // The reply is an EMPTY parcel, always. There is no status code to
            // read: transact() returning true is the only signal the engine gives.
            Log.i(TAG, "evs: openCamera(" + cameraId + ") = " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "evs: openCamera failed: " + t);
            return false;
        } finally { d.recycle(); r.recycle(); }
    }

    public boolean attach(Surface surface, String previewType, String cameraId) {
        if (binder == null) return false;
        IBinder igbp = igbp(surface);
        if (igbp == null) { Log.w(TAG, "evs: no IGraphicBufferProducer in surface"); return false; }
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC);
            d.writeStrongBinder(igbp);
            cstr(d, previewType);
            cstr(d, cameraId);
            boolean ok = binder.transact(TX_ATTACH, d, r, 0);
            Log.i(TAG, "evs: attach(" + previewType + "," + cameraId + ") = " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "evs: attach failed: " + t);
            return false;
        } finally { d.recycle(); r.recycle(); }
    }

    // NOT Parcel.writeString(). The engine is native C++ and reads a C string:
    // UTF-8 bytes, NUL-terminated, zero-padded up to a 4-byte boundary, written
    // as little-endian ints. Getting this wrong is a silent no-op, not an error.
    private static void cstr(Parcel p, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] z = new byte[((b.length + 1 + 3) / 4) * 4];
        System.arraycopy(b, 0, z, 0, b.length);
        for (int i = 0; i < z.length; i += 4) {
            p.writeInt((z[i] & 0xFF) | ((z[i + 1] & 0xFF) << 8)
                     | ((z[i + 2] & 0xFF) << 16) | ((z[i + 3] & 0xFF) << 24));
        }
    }

    /** Extracts the raw IGraphicBufferProducer IBinder from a Surface. The engine
     * requires this binder, not the Surface itself. Tries offset 12 first (known
     * valid location), then scans the parcelled data if not found. */
    private static IBinder igbp(Surface s) {
        Parcel p = Parcel.obtain();
        try {
            s.writeToParcel(p, 0);
            int size = p.dataSize();
            IBinder hit = at(p, 12);
            if (hit != null) return hit;
            for (int off = 0; off < size; off += 4) {
                hit = at(p, off);
                if (hit != null) { Log.i(TAG, "evs: igbp at offset " + off); return hit; }
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "evs: igbp extraction failed: " + t);
            return null;
        } finally { p.recycle(); }
    }

    private static IBinder at(Parcel p, int off) {
        try { p.setDataPosition(off); return p.readStrongBinder(); }
        catch (Throwable ignored) { return null; }
    }
}
