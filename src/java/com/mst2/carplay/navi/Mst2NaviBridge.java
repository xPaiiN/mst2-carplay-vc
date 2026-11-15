/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v2.0
 */
package com.mst2.carplay.navi;

import com.mst2.carplay.bridge.Mst2Asl;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class Mst2NaviBridge {

    private static final String PPS_IAP2_DIR    = "/pps/services/multimedia/iap2";
    private static final String PPS_DEST_PREFIX = "destination-";
    private static final String VAR_DIR         = "/tsd/var/carplayvc";
    private static final String LOG_FILE        = VAR_DIR + "/navi-bridge.log";

    private static final String DEBUG_MARKER    = VAR_DIR + "/debug-enabled";
    private static final long   BOOT_GRACE_MS   = 20000L;
    private static final long   POLL_MS         = 500L;
    private static final long   HANDOFF_GAP_MS  = 3000L;
    private static final long   LOG_MAX         = 262144L;
    private static final long   JVM_START_MS    = System.currentTimeMillis();

    private static volatile boolean attached = false;
    private static volatile Object  cachedHsmTarget = null;
    private static double destLat = 0.0, destLon = 0.0;
    private static String destId = "", destName = "";
    private static String lastHandoffId = "";
    private static long   lastHandoffMs = 0L;
    private static volatile boolean handoffQueued = false;
    private static final long ASL_WAIT_MS = 3000L;

    private Mst2NaviBridge() {  }

    public static void attachOnce(Object hsmTarget, Object properties) {
        synchronized (Mst2NaviBridge.class) {
            if (attached) return;
            if (hsmTarget == null) return;
            attached = true;
            cachedHsmTarget = hsmTarget;
        }
        log("attachOnce: starting destination worker");
        Thread t = new Thread(new DestinationWorker(), "cpvc-w05");
        try { t.setDaemon(true); } catch (Throwable th) {}
        try { t.start(); }
        catch (Throwable th) {
            log("attach: thread start failed: " + th);
            synchronized (Mst2NaviBridge.class) { attached = false; }
        }
    }

    private static final class DestinationWorker implements Runnable {
        private final byte[] buf = new byte[8192];

        public void run() {
            sleep(BOOT_GRACE_MS);
          log("worker: grace done, destination polling active");
            for (;;) {
                try { tick(); } catch (Throwable th) { log("tick error: " + th); }
                sleep(POLL_MS);
            }
        }

        private void tick() {
            File dir = new File(PPS_IAP2_DIR);
            if (!dir.exists() || !dir.isDirectory()) return;
            String[] names = dir.list();
            if (names == null) return;

            File latest = null;
            long best = -1L;
            for (int i = 0; i < names.length; i++) {
                String nm = names[i];
                if (nm == null || !nm.startsWith(PPS_DEST_PREFIX)) continue;
                long num;
                try { num = Long.parseLong(nm.substring(PPS_DEST_PREFIX.length())); }
                catch (Throwable th) { continue; }
                if (num <= best) continue;
                best = num;
                latest = new File(dir, nm);
            }
            if (latest == null) return;

            String content = readFile(latest);
            if (content == null || content.length() == 0) return;

            String id  = extractJsonString(content, "UniqueIdentifier");
            String nm  = extractJsonString(content, "DisplayName");
            double lat = extractCoord(content, "Latitude");
            double lon = extractCoord(content, "Longitude");
            if (Double.isNaN(lat) || Double.isNaN(lon)) return;

            boolean coordChanged = lat != destLat || lon != destLon || !equalsSafe(id, destId);
            destLat = lat; destLon = lon;
            destId  = (id != null) ? id : "";
            destName = (nm != null) ? nm : "";

            boolean freshId   = destId.length() > 0 && !equalsSafe(destId, lastHandoffId);
            boolean debounced = System.currentTimeMillis() - lastHandoffMs > HANDOFF_GAP_MS;
            if ((coordChanged || freshId) && debounced) handoff();
        }

        private String readFile(File f) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(f);
                int n = in.read(this.buf);
                if (n <= 0) return "";
                return new String(this.buf, 0, n, "UTF-8");
            } catch (Throwable th) {
                return null;
            } finally {
                if (in != null) { try { in.close(); } catch (Throwable th) {} }
            }
        }

        private static String extractJsonString(String s, String key) {
            int k = s.indexOf("\"" + key + "\"");
            if (k < 0) {
                k = s.indexOf(key + "::");
                if (k < 0) return "";
                int a = s.indexOf("::", k);
                if (a < 0) return "";
                int e = s.indexOf(10, a + 2);
                if (e < 0) e = s.length();
                return s.substring(a + 2, e).trim();
            }
            int colon = s.indexOf(58, k + key.length() + 2);
            if (colon < 0) return "";
            int q1 = s.indexOf(34, colon + 1);
            if (q1 < 0) return "";
            int q2 = s.indexOf(34, q1 + 1);
            if (q2 < 0) return "";
            return s.substring(q1 + 1, q2);
        }

        private static double extractCoord(String s, String key) {
            char c;
            int start;
            int k = s.indexOf("\"" + key + "\"");
            if (k < 0) {
                int a = s.indexOf(key + "::");
                if (a < 0) return Double.NaN;
                start = s.indexOf("::", a) + 2;
            } else {
                start = s.indexOf(58, k + key.length() + 2) + 1;
            }
            if (start <= 0) return Double.NaN;
            int len = s.length();
            while (start < len && (s.charAt(start) == ' ' || s.charAt(start) == '\t')) start++;
            int end;
            for (end = start; end < len && (c = s.charAt(end)) != ',' && c != '}' && c != '\n' && c != ' ' && c != '\r'; end++) { }
            if (end <= start) return Double.NaN;
            try { return Double.parseDouble(s.substring(start, end)); }
            catch (Throwable th) { return Double.NaN; }
        }

        private static boolean equalsSafe(String a, String b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            return a.equals(b);
        }
    }

    private static void handoff() {
        if (handoffQueued) return;
        handoffQueued = true;
        Mst2Asl.Job job = new Mst2Asl.Job() {
            protected void work() {
                try { handoffNow(); } finally { handoffQueued = false; }
            }
        };
        int r;
        try {
            r = Mst2Asl.run(job, ASL_WAIT_MS);
        } catch (Throwable t) {
            r = Mst2Asl.NO_ASL;
        }
        if (r == Mst2Asl.NO_ASL) job.run();
    }

    private static void handoffNow() {
        Object hsm = cachedHsmTarget;
        if (hsm == null) return;
        try {
            Field f = hsm.getClass().getDeclaredField("navServices");
            f.setAccessible(true);
            Object nav = f.get(hsm);
            if (nav == null) { log("handoff: navServices null"); return; }

            try {
                Method avail = nav.getClass().getMethod("isServiceAvailable", new Class[0]);
                Object r = avail.invoke(nav, new Object[0]);
                if (r instanceof Boolean && !((Boolean) r).booleanValue()) {
                    log("handoff: nav service not available yet");
                    return;
                }
            } catch (NoSuchMethodException nsme) {  }

            Class navAddr = Class.forName("de.vw.mib.asl.api.navigation.NavAddress");
            Object addr = navAddr.newInstance();
            navAddr.getMethod("setLatitude",  new Class[]{ Double.TYPE }).invoke(addr, new Object[]{ new Double(destLat) });
            navAddr.getMethod("setLongitude", new Class[]{ Double.TYPE }).invoke(addr, new Object[]{ new Double(destLon) });
            if (destName != null && destName.length() > 0) {
                try { navAddr.getMethod("setName", new Class[]{ String.class }).invoke(addr, new Object[]{ destName }); }
                catch (Throwable th) {  }
            }

            Object arr = Array.newInstance(navAddr, 1);
            Array.set(arr, 0, addr);

            Method sg = nav.getClass().getMethod("startGuidance", new Class[]{ arr.getClass(), Boolean.TYPE, Integer.TYPE });
            Object rc = sg.invoke(nav, new Object[]{ arr, Boolean.TRUE, new Integer(7) });

            lastHandoffMs = System.currentTimeMillis();
            lastHandoffId = (destId != null) ? destId : "";
          log("handoff: startGuidance rc=" + rc + " destination-present="
            + ((destName != null && destName.length() > 0) ? "1" : "0"));
        } catch (Throwable th) {
            log("handoff error: " + th);
        }
    }

    private static boolean exists(String p) {
        try { return new File(p).exists(); } catch (Throwable th) { return false; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {}
    }

    private static void log(String msg) {
        if (!exists(DEBUG_MARKER)) return;
        FileOutputStream out = null;
        try {
            File d = new File(VAR_DIR);
            if (!d.exists()) d.mkdirs();
            File f = new File(LOG_FILE);
            if (f.exists() && f.length() > LOG_MAX) f.delete();
            out = new FileOutputStream(LOG_FILE, true);
            long uptime = System.currentTimeMillis() - JVM_START_MS;
            out.write(("[+" + uptime + "ms] " + msg + "\n").getBytes());
        } catch (Throwable th) {

        } finally {
            if (out != null) { try { out.close(); } catch (Throwable th) {} }
        }
    }
}
