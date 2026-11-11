import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads or changes Samsung's absolute-volume ("media volume sync") switches.
 *   CLASSPATH=/data/local/tmp/avc.dex app_process / Avc get  AA:BB
 *   CLASSPATH=/data/local/tmp/avc.dex app_process / Avc off  AA:BB     (global switch off)
 *   CLASSPATH=/data/local/tmp/avc.dex app_process / Avc on   AA:BB
 *   CLASSPATH=/data/local/tmp/avc.dex app_process / Avc devoff AA:BB   (per-device volume control off)
 *   CLASSPATH=/data/local/tmp/avc.dex app_process / Avc devon  AA:BB
 */
public class Avc {
    static final String PKG = "com.android.shell";
    static Looper L;

    static class Fake extends ContextWrapper {
        Fake(Context base) { super(base); }
        @Override public String getPackageName() { return PKG; }
        @Override public String getOpPackageName() { return PKG; }
        @Override public Context getApplicationContext() { return this; }
        @Override public Looper getMainLooper() { return L; }
        @Override public java.util.concurrent.Executor getMainExecutor() {
            final Handler h = new Handler(L);
            return h::post;
        }
        @Override public String getAttributionTag() { return null; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.myUid()).setPackageName(PKG).build();
        }
    }

    static Object call(Object target, String name, Class<?>[] types, Object... a) {
        try {
            Method m = target.getClass().getMethod(name, types);
            return m.invoke(target, a);
        } catch (Throwable t) {
            return "ERR " + (t.getCause() != null ? t.getCause() : t);
        }
    }

    public static void main(String[] args) throws Exception {
        final String mode = args.length > 0 ? args[0] : "get";
        final String suffix = args.length > 1 ? args[1].toUpperCase() : "";

        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        L = Looper.myLooper();
        Class<?> atc = Class.forName("android.app.ActivityThread");
        Constructor<?> c = atc.getDeclaredConstructor();
        c.setAccessible(true);
        Object at = c.newInstance();
        Field cur = atc.getDeclaredField("sCurrentActivityThread");
        cur.setAccessible(true);
        cur.set(null, at);
        try {
            Class<?> abd = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> ac = abd.getDeclaredConstructor();
            ac.setAccessible(true);
            Object bd = ac.newInstance();
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = PKG;
            Field aif = abd.getDeclaredField("appInfo");
            aif.setAccessible(true);
            aif.set(bd, ai);
            Field mb = atc.getDeclaredField("mBoundApplication");
            mb.setAccessible(true);
            mb.set(at, bd);
            Class<?> ccc = Class.forName("android.app.ConfigurationController");
            Constructor<?> ccCtor = ccc.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
            ccCtor.setAccessible(true);
            Field mcc = atc.getDeclaredField("mConfigurationController");
            mcc.setAccessible(true);
            if (mcc.get(at) == null) mcc.set(at, ccCtor.newInstance(at));
        } catch (Throwable t) {
            System.out.println("warn: bootstrap: " + t);
        }
        Context base = null;
        try {
            Method gsc = atc.getDeclaredMethod("getSystemContext");
            gsc.setAccessible(true);
            base = (Context) gsc.invoke(at);
        } catch (Throwable t) {
            System.out.println("warn: bare context");
        }
        final Context ctx = new Fake(base);
        try {
            Method init = atc.getDeclaredMethod("initializeMainlineModules");
            init.setAccessible(true);
            init.invoke(null);
        } catch (Throwable t) {
            System.out.println("warn: mainline init: " + t);
        }
        Constructor<BluetoothManager> bmc = BluetoothManager.class.getDeclaredConstructor(Context.class);
        bmc.setAccessible(true);
        final BluetoothAdapter adapter = bmc.newInstance(ctx).getAdapter();
        if (adapter == null) { System.out.println("no adapter"); System.exit(3); }

        new Handler(L).postDelayed(() -> { System.out.println("timeout"); System.exit(2); }, 12000);

        adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile a2dp) {
                BluetoothDevice dev = null;
                for (BluetoothDevice d : adapter.getBondedDevices())
                    if (!suffix.isEmpty() && d.getAddress().toUpperCase().endsWith(suffix)) dev = d;
                Class<?>[] none = new Class<?>[0];
                System.out.println("global absVolControl=" + call(a2dp, "semIsAbsoluteVolumeControl", none)
                        + " absVolDeviceActive=" + call(a2dp, "isAvrcpAbsoluteVolumeDeviceActive", none)
                        + (dev != null ? " deviceVolumeControlSupport=" + call(dev, "getVolumeControlSupport", none) : ""));
                if (dev != null) {
                    System.out.println("isSsDualBuds=" + call(dev, "isSsDualBuds", none)
                            + " isLeAudioDualMode=" + call(dev, "isLeAudioDualMode", none)
                            + " isSsLeAudioBuds=" + call(dev, "isSsLeAudioBuds", none)
                            + " audioType=" + call(dev, "semGetAudioType", none)
                            + " autoSwitchMode=" + call(dev, "semGetAutoSwitchMode", none));
                }
                if (dev != null && "audiotype".equals(mode)) {
                    int v = Integer.parseInt(args[2]);
                    System.out.println("semSetAudioType(" + v + ") -> "
                            + call(dev, "semSetAudioType", new Class<?>[]{int.class}, v));
                }
                if ("off".equals(mode) || "on".equals(mode)) {
                    System.out.println("setAbsoluteVolumeControl(" + "on".equals(mode) + ") -> "
                            + call(a2dp, "setAbsoluteVolumeControl", new Class<?>[]{boolean.class}, "on".equals(mode)));
                }
                if (dev != null && ("devoff".equals(mode) || "devon".equals(mode))) {
                    System.out.println("setVolumeControlSupport(" + "devon".equals(mode) + ") -> "
                            + call(dev, "setVolumeControlSupport", new Class<?>[]{boolean.class}, "devon".equals(mode)));
                }
                if (!"get".equals(mode)) {
                    try { Thread.sleep(800); } catch (InterruptedException e) { }
                    System.out.println("now: global absVolControl=" + call(a2dp, "semIsAbsoluteVolumeControl", none)
                            + " absVolDeviceActive=" + call(a2dp, "isAvrcpAbsoluteVolumeDeviceActive", none)
                            + (dev != null ? " deviceVolumeControlSupport=" + call(dev, "getVolumeControlSupport", none) : ""));
                }
                System.exit(0);
            }
            @Override public void onServiceDisconnected(int profile) { }
        }, BluetoothProfile.A2DP);
        Looper.loop();
    }
}
