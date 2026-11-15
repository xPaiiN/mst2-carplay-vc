/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v2.0
 */
package com.mst2.carplay.bridge;

import java.io.File;
import java.io.FileOutputStream;

public final class Mst2Log {
    static final String ROOT = System.getProperty("cpvc.test.root", "");
    public static final String TSD_DIR = ROOT + "/tsd/var/carplayvc";
    public static final String RAM_DIR = ROOT + "/ramdisk/carplayvc";
    public static final String SHM_DIR = ROOT + "/dev/shmem";
    public static final String DEBUG_MARKER = TSD_DIR + "/debug-enabled";
    public static final String QUIET_LOG = RAM_DIR + "/cpvc-java.log";
    public static final long QUIET_CAP = 65536L;
    public static final long BIG_CAP = 2097152L;
    private static final long DEBUG_CHECK_MS = 5000L;

    private static final Object LOCK = new Object();
    private static long debugCheckedMs = 0L;
    private static boolean debugOn = false;

    private Mst2Log() {
    }

    public static boolean debug() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (debugCheckedMs == 0L || now - debugCheckedMs >= DEBUG_CHECK_MS || now < debugCheckedMs) {
                debugOn = new File(DEBUG_MARKER).exists();
                debugCheckedMs = now;
            }
            return debugOn;
        }
    }

    public static String debugPath(String name, boolean big) {
        return (big ? SHM_DIR : TSD_DIR) + "/cpvc-" + name;
    }

    public static String ramFile(String name) {
        ensureRamDir();
        return RAM_DIR + "/" + name;
    }

    public static void ensureRamDir() {
        try {
            File d = new File(RAM_DIR);
            if (!d.isDirectory()) d.mkdirs();
        } catch (Throwable t) {
        }
    }

    public static void quiet(String tag, String line) {
        ensureRamDir();
        append(QUIET_LOG, System.currentTimeMillis() + " " + tag + " " + line, QUIET_CAP);
    }

    public static void append(String path, String line, long cap) {
        synchronized (LOCK) {
            FileOutputStream fos = null;
            try {
                File f = new File(path);
                if (f.length() > cap) {
                    File old = new File(path + ".1");
                    old.delete();
                    if (!f.renameTo(old)) f.delete();
                }
                fos = new FileOutputStream(path, true);
                fos.write((line + "\n").getBytes("UTF-8"));
            } catch (Throwable t) {
            } finally {
                if (fos != null) { try { fos.close(); } catch (Throwable t) {} }
            }
        }
    }
}
