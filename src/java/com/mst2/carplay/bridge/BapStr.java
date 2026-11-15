/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v2.0
 */
package com.mst2.carplay.bridge;

import de.vw.mib.bap.datatypes.BAPString;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

// setContent returns void on some HMI builds and BAPString on others, so it is resolved at runtime.
public final class BapStr {
    static final String COMPAT_FILE = Mst2Log.RAM_DIR + "/cpvc-compat";
    private static Method setString;
    private static Method setBap;
    private static boolean resolved;
    private static boolean failed;
    private static String api = "unknown";

    private BapStr() {
    }

    public static void set(BAPString target, String value) {
        if (target == null || !resolve()) return;
        try {
            setString.invoke(target, new Object[]{value});
        } catch (Throwable t) {
        }
    }

    public static void set(BAPString target, BAPString value) {
        if (target == null || !resolve()) return;
        try {
            setBap.invoke(target, new Object[]{value});
        } catch (Throwable t) {
        }
    }

    // The first set() runs before the RAM disk is ready, so the witness is written here.
    public static void probe() {
        resolve();
        writeCompat("setContent=" + api);
        Mst2Log.quiet("compat", "setContent=" + api);
    }

    private static synchronized boolean resolve() {
        if (resolved) return !failed;
        resolved = true;
        api = "missing";
        try {
            setString = BAPString.class.getMethod("setContent", new Class[]{String.class});
            setBap = BAPString.class.getMethod("setContent", new Class[]{BAPString.class});
            api = setString.getReturnType() == Void.TYPE ? "void" : "builder";
        } catch (Throwable t) {
            failed = true;
        }
        return !failed;
    }

    private static void writeCompat(String line) {
        FileOutputStream f = null;
        try {
            Mst2Log.ensureRamDir();
            f = new FileOutputStream(COMPAT_FILE);
            f.write((line + "\n").getBytes("UTF-8"));
        } catch (Throwable t) {
        } finally {
            if (f != null) { try { f.close(); } catch (Throwable t) {} }
        }
    }
}
