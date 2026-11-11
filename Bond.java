import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Inspect, remove and re-create the Buds pairing so the bond stays classic-only (no LE Audio).
 * Run as the adb shell user:
 *   CLASSPATH=/data/local/tmp/bond.dex app_process / Bond info
 *   CLASSPATH=/data/local/tmp/bond.dex app_process / Bond remove <address suffix>...
 *   CLASSPATH=/data/local/tmp/bond.dex app_process / Bond removename <name part>
 *   CLASSPATH=/data/local/tmp/bond.dex app_process / Bond sdp <full address>     (-Dwait=ms)
 *   CLASSPATH=/data/local/tmp/bond.dex app_process / Bond bond <full address>    (-Dautoconfirm=1 to auto-accept)
 */
public class Bond {
    static final String PKG = "com.android.shell";
    static final int TRANSPORT_BREDR = 1;
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


    static void fixSource(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("setAttributionSource", AttributionSource.class);
            m.invoke(d, new AttributionSource.Builder(Process.myUid()).setPackageName(PKG).build());
        } catch (Throwable t) {
            System.out.println("warn: setAttributionSource: " + t);
        }
    }

    static String uuids(BluetoothDevice d) {
        ParcelUuid[] u = d.getUuids();
        if (u == null) return "uuids=null";
        boolean le = false;
        for (ParcelUuid p : u) if (p.toString().startsWith("0000184e")) le = true;
        return "uuids=" + u.length + " LE_AUDIO=" + (le ? "yes" : "no");
    }

    static String type(int t) {
        switch (t) { case 1: return "CLASSIC"; case 2: return "LE"; case 3: return "DUAL"; default: return "UNKNOWN"; }
    }

    public static void main(String[] args) throws Exception {
        final String mode = args.length > 0 ? args[0] : "info";
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


        if ("info".equals(mode)) {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                System.out.println(d.getAddress() + " [" + d.getName() + "] type=" + type(d.getType())
                        + " bond=" + d.getBondState() + " " + uuids(d));
            }
            System.exit(0);
        }
        if ("removename".equals(mode)) {
            Method rb = BluetoothDevice.class.getMethod("removeBond");
            String want = args[1].toLowerCase();
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                fixSource(d);
                String n = d.getName() == null ? "" : d.getName().toLowerCase();
                if (n.contains(want)) System.out.println("removeBond " + d.getAddress() + " [" + d.getName() + "] -> " + rb.invoke(d));
            }
            Thread.sleep(3000);
            System.exit(0);
        }
        if ("remove".equals(mode)) {
            Method rb = BluetoothDevice.class.getMethod("removeBond");
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                fixSource(d);
                String a = d.getAddress().toUpperCase();
                for (int i = 1; i < args.length; i++) {
                    if (a.endsWith(args[i].toUpperCase())) {
                        System.out.println("removeBond " + a + " -> " + rb.invoke(d));
                    }
                }
            }
            Thread.sleep(3000);
            for (BluetoothDevice d : adapter.getBondedDevices()) System.out.println("still bonded: " + d.getAddress() + " [" + d.getName() + "]");
            System.exit(0);
        }
        if ("bond".equals(mode)) {
            BluetoothDevice d = adapter.getRemoteDevice(args[1].toUpperCase());
            fixSource(d);
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            Method cb = BluetoothDevice.class.getMethod("createBond", int.class);
            System.out.println("createBond(BR/EDR) " + d.getAddress() + " -> " + cb.invoke(d, TRANSPORT_BREDR));
            boolean confirmed = false;
            for (int i = 0; i < 90; i++) {
                Thread.sleep(1000);
                int s = d.getBondState();
                if (System.getProperty("autoconfirm") != null && s == BluetoothDevice.BOND_BONDING && !confirmed && i >= 1) {
                    try {
                        System.out.println("setPairingConfirmation -> " + d.setPairingConfirmation(true));
                    } catch (Throwable t) {
                        System.out.println("setPairingConfirmation failed: " + t);
                    }
                    confirmed = true;
                }
                if (s == BluetoothDevice.BOND_BONDED) {
                    Thread.sleep(6000);
                    System.out.println("BONDED type=" + type(d.getType()) + " " + uuids(d));
                    System.exit(0);
                }
                if (s == BluetoothDevice.BOND_NONE && i > 4) {
                    System.out.println("bond failed (state NONE) after " + i + "s");
                    System.exit(4);
                }
            }
            System.out.println("timeout, state=" + d.getBondState());
            System.exit(5);
        }
        if ("sdp".equals(mode)) {
            BluetoothDevice d = adapter.getRemoteDevice(args[1].toUpperCase());
            fixSource(d);
            Method f = BluetoothDevice.class.getMethod("fetchUuidsWithSdp", int.class);
            System.out.println("fetchUuidsWithSdp(BR/EDR) -> " + f.invoke(d, TRANSPORT_BREDR));
            Thread.sleep(Integer.parseInt(System.getProperty("wait", "8000")));
            System.out.println(d.getAddress() + " type=" + type(d.getType()) + " bond=" + d.getBondState() + " " + uuids(d));
            System.exit(0);
        }
        System.out.println("unknown mode " + mode);
        System.exit(1);
    }
}
