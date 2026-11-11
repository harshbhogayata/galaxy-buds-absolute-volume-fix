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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads or sets the LE Audio connection policy of bonded Bluetooth devices.
 * Run as the adb shell user:
 *   CLASSPATH=/data/local/tmp/lea.dex app_process / LeaPolicy list
 *   CLASSPATH=/data/local/tmp/lea.dex app_process / LeaPolicy set 0 AA:BB CC:DD
 * Policy values: 0 = forbidden, 100 = allowed, -1 = unknown.
 */
public class LeaPolicy {
    static final String PKG = "com.android.shell";
    static final int LE_AUDIO = 22;
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

    static volatile boolean watching = false;
    static volatile BluetoothProfile P;

    static void log(String s) {
        System.out.println(new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date()) + " " + s);
    }

    /**
     * Stays running. Every 3 seconds, for each matching bonded device:
     * if its LE Audio policy has been put back to allowed, forbid it again; otherwise, for the
     * first suffix (the main A2DP address) while connected, force variable volume behaviour and
     * turn Samsung's per-device volume control flag off.
     */
    static void startWatch(final BluetoothProfile proxy, final BluetoothAdapter adapter, final Context ctx,
                           final List<String> suffixes) throws Exception {
        final Method get = proxy.getClass().getMethod("getConnectionPolicy", BluetoothDevice.class);
        final Method set = proxy.getClass().getMethod("setConnectionPolicy", BluetoothDevice.class, int.class);
        Constructor<android.media.AudioManager> amc =
                android.media.AudioManager.class.getDeclaredConstructor(Context.class);
        amc.setAccessible(true);
        final android.media.AudioManager am = amc.newInstance(ctx);
        final Class<?> adaC = Class.forName("android.media.AudioDeviceAttributes");
        final Constructor<?> adaCtor = adaC.getDeclaredConstructor(int.class, int.class, String.class);
        adaCtor.setAccessible(true);
        final Method getB = android.media.AudioManager.class.getMethod("getDeviceVolumeBehavior", adaC);
        final Method setB = android.media.AudioManager.class.getMethod("setDeviceVolumeBehavior", adaC, int.class);
        final Method isConn = BluetoothDevice.class.getMethod("isConnected");
        final Method getVcs = BluetoothDevice.class.getMethod("getVolumeControlSupport");
        final Method setVcs = BluetoothDevice.class.getMethod("setVolumeControlSupport", boolean.class);
        final String main = suffixes.isEmpty() ? "" : suffixes.get(0);
        final Handler h = new Handler(L);
        log("watch started for " + suffixes);
        h.post(new Runnable() {
            @Override public void run() {
                try {
                    for (BluetoothDevice d : adapter.getBondedDevices()) {
                        String addr = d.getAddress().toUpperCase();
                        boolean match = false;
                        for (String s : suffixes) if (addr.endsWith(s)) match = true;
                        if (!match) continue;
                        String tag = addr.substring(12);
                        if (((Integer) get.invoke(P, d)) == 100) {
                            log(tag + " LE Audio policy 100 -> 0: " + set.invoke(P, d, 0));
                            continue;
                        }
                        if (!addr.endsWith(main) || !((Boolean) isConn.invoke(d))) continue;
                        Object ada = adaCtor.newInstance(2 /* output */, 8 /* bt a2dp */, d.getAddress());
                        if (((Integer) getB.invoke(am, ada)) == 3) {
                            setB.invoke(am, ada, 0);
                            log(tag + " volume behaviour absolute -> variable");
                        }
                        if ((Boolean) getVcs.invoke(d)) {
                            log(tag + " volume control support -> off: " + setVcs.invoke(d, false));
                        }
                    }
                } catch (Throwable t) {
                    log("tick error: " + (t.getCause() != null ? t.getCause() : t));
                }
                h.postDelayed(this, 3000);
            }
        });
    }

    public static void main(String[] args) throws Exception {
        final String mode = args.length > 0 ? args[0] : "list";
        final int PROFILE = Integer.parseInt(System.getProperty("profile", "22"));
        final int policy = "set".equals(mode) ? Integer.parseInt(args[1]) : 0;
        final List<String> suffixes = new ArrayList<>();
        for (int i = "set".equals(mode) ? 2 : 1; i < args.length; i++) suffixes.add(args[i].toUpperCase());

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
        } catch (Throwable t) {
            System.out.println("warn: bind data: " + t);
        }
        try {
            Class<?> ccc = Class.forName("android.app.ConfigurationController");
            Class<?> ati = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> ccCtor = ccc.getDeclaredConstructor(ati);
            ccCtor.setAccessible(true);
            Field mcc = atc.getDeclaredField("mConfigurationController");
            mcc.setAccessible(true);
            if (mcc.get(at) == null) mcc.set(at, ccCtor.newInstance(at));
        } catch (Throwable t) {
            System.out.println("warn: config controller: " + t);
        }
        Context base = null;
        try {
            Method gsc = atc.getDeclaredMethod("getSystemContext");
            gsc.setAccessible(true);
            base = (Context) gsc.invoke(at);
        } catch (Throwable t) {
            System.out.println("warn: no system context, using bare context: " + (t.getCause() != null ? t.getCause() : t));
        }
        final Context ctx = new Fake(base);

        try {
            Method init = atc.getDeclaredMethod("initializeMainlineModules");
            init.setAccessible(true);
            init.invoke(null);
        } catch (Throwable t) {
            System.out.println("warn: mainline init: " + (t.getCause() != null ? t.getCause() : t));
        }

        Constructor<BluetoothManager> bmc = BluetoothManager.class.getDeclaredConstructor(Context.class);
        bmc.setAccessible(true);
        final BluetoothAdapter adapter = bmc.newInstance(ctx).getAdapter();
        if (adapter == null) { System.out.println("no adapter"); System.exit(3); }

        new Handler(L).postDelayed(() -> {
            if (watching) return;
            System.out.println("timeout waiting for LE Audio proxy");
            System.exit(2);
        }, 12000);

        boolean ok = adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    if ("watch".equals(mode)) {
                        P = proxy;
                        if (!watching) {
                            watching = true;
                            startWatch(proxy, adapter, ctx, suffixes);
                        } else {
                            log("profile proxy refreshed");
                        }
                        return;
                    }
                    Method get = proxy.getClass().getMethod("getConnectionPolicy", BluetoothDevice.class);
                    Method set = proxy.getClass().getMethod("setConnectionPolicy", BluetoothDevice.class, int.class);
                    Map<String, BluetoothDevice> devs = new LinkedHashMap<>();
                    for (BluetoothDevice d : adapter.getBondedDevices()) devs.put(d.getAddress(), d);
                    for (BluetoothDevice d : proxy.getDevicesMatchingConnectionStates(new int[]{0, 1, 2, 3}))
                        devs.put(d.getAddress(), d);
                    for (BluetoothDevice d : devs.values()) {
                        String addr = d.getAddress();
                        String name;
                        try { name = d.getName(); } catch (Throwable t) { name = "?"; }
                        boolean match = false;
                        for (String s : suffixes) if (addr.toUpperCase().endsWith(s)) match = true;
                        String line = addr.substring(12) + " [" + name + "] policy=" + get.invoke(proxy, d)
                                + " state=" + proxy.getConnectionState(d);
                        if (("set".equals(mode) || "fix".equals(mode)) && match) {
                            Object r = set.invoke(proxy, d, policy);
                            line += " -> set(" + policy + ")=" + r + " now=" + get.invoke(proxy, d);
                        }
                        System.out.println(line);
                    }
                    if ("fix".equals(mode)) {
                        Thread.sleep(1500);
                        Constructor<android.media.AudioManager> amc =
                                android.media.AudioManager.class.getDeclaredConstructor(Context.class);
                        amc.setAccessible(true);
                        android.media.AudioManager am = amc.newInstance(ctx);
                        Class<?> adaC = Class.forName("android.media.AudioDeviceAttributes");
                        Constructor<?> adaCtor = adaC.getDeclaredConstructor(int.class, int.class, String.class);
                        adaCtor.setAccessible(true);
                        Method getB = android.media.AudioManager.class.getMethod("getDeviceVolumeBehavior", adaC);
                        Method setB = android.media.AudioManager.class.getMethod("setDeviceVolumeBehavior", adaC, int.class);
                        for (BluetoothDevice d : devs.values()) {
                            String addr = d.getAddress();
                            boolean match = false;
                            for (String s : suffixes) if (addr.toUpperCase().endsWith(s)) match = true;
                            if (!match) continue;
                            Object ada = adaCtor.newInstance(2 /* output */, 8 /* bt a2dp */, addr);
                            Object before = getB.invoke(am, ada);
                            try {
                                setB.invoke(am, ada, 0 /* variable */);
                                System.out.println(addr.substring(12) + " a2dp volume behavior " + before
                                        + " -> " + getB.invoke(am, ada));
                            } catch (Throwable t) {
                                System.out.println(addr.substring(12) + " a2dp volume behavior " + before
                                        + " set failed: " + (t.getCause() != null ? t.getCause() : t));
                            }
                        }
                    }
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    System.out.println("error: " + cause);
                }
                System.exit(0);
            }
            @Override public void onServiceDisconnected(int profile) { }
        }, PROFILE);
        System.out.println("getProfileProxy=" + ok);
        if (!ok) System.exit(4);
        Looper.loop();
    }
}
