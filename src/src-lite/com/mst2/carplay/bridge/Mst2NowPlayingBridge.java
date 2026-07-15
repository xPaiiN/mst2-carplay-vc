/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v1
 */
package com.mst2.carplay.bridge;

import de.vw.mib.asl.ASLPropertyManager;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyAPI;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyAdapterFactory;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.asl.framework.api.dsiproxy.DSIServiceStateListener;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.carplay.target.HsmTarget;
import de.vw.mib.asl.internal.media.clients.player.TrackInfo;
import generated.de.vw.mib.asl.internal.avdc.audio.transformer.AVDCAudioCurrentTrackInfoCollector;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Hashtable;
import org.dsi.ifc.base.DSIListener;
import org.dsi.ifc.carplay.DSICarplay;
import org.dsi.ifc.carplay.DSICarplayListener;
import org.dsi.ifc.carplay.PlaybackInfo;
import org.dsi.ifc.carplay.TrackData;
import org.dsi.ifc.global.ResourceLocator;

public final class Mst2NowPlayingBridge {

    private static final int[] NOWPLAYING_ATTR = new int[]{3, 4, 5, 6};
    private static final int PROP_PLAYBACK_STATE = 29;
    private static final int PROP_POSITION_CURRENT = 13;
    private static final int PROP_POSITION_REMAINING = 14;
    private static final int PROP_HAS_DETAIL_INFOS = 2781;
    private static final int VALID_REAL = 1;
    private static final int RETRY_ATTEMPTS = 60;
    private static final long RETRY_INTERVAL_MS = 500L;

    // PPS polling at 2 Hz (direct in-memory FileInputStream read, no cp, no per-cycle writes).
    private static final long PPS_POLL_INTERVAL_MS = 500L;
    private static final String PPS_NOWPLAYING = "/pps/services/multimedia/iap2/nowplaying";
    private static final long STALE_TIMEOUT_MS = 120000L;
    private static final int ERROR_THRESHOLD = 100;
    private static final long REFRESH_TICK_INTERVAL_MS = 1000L;

    private static volatile boolean attached = false;
    private static volatile boolean attaching = false;
    private static volatile HsmTarget cachedHsmTarget = null;
    private static volatile int lastDurationMs = 0;
    private static volatile int errorCount = 0;

    private static volatile String cachedTitle = "";
    private static volatile String cachedArtist = "";
    private static volatile String cachedAlbum = "";
    private static volatile long lastValidUpdateMs = 0L;

    // Debug-Logging: sentinel-file gated. The check fires once at the first attachOnce call;
    // toggling the sentinel afterwards has no effect until power-cycle.
    private static final String BRIDGE_LOG = "/tsd/var/carplayvc/bridge.log";
    private static final String DEBUG_MARKER = "/tsd/var/carplayvc/debug-enabled";
    private static final long LOG_MAX_SIZE = 1048576L;
    private static final long JVM_START_MS = System.currentTimeMillis();

    // Volatile so a successful initDebugMode() is visible to all dlog() callers.
    private static volatile boolean DEBUG = false;
    private static volatile FileOutputStream debugLogStream = null;
    private static volatile long debugBytesWritten = 0L;
    private static volatile boolean debugInitDone = false;

    // Reflection-cache for isCallActive (avoids ~18k lookups/h on the PPS-poll thread).
    private static volatile java.lang.reflect.Field cachedPropsField = null;
    private static volatile Method cachedGetCallStatesMethod = null;

    // triggerBapRefresh reflection: cache Class/Field/Method once but re-read INSTANCE every
    // call so a Shadow re-init cannot leave us with a stale reference.
    private static volatile Class cachedShadowClass = null;
    private static volatile java.lang.reflect.Field cachedShadowInstanceField = null;
    private static volatile Method cachedShadowProcessMethod = null;

    private static volatile boolean shutdownInstalled = false;
    private static volatile boolean ppsPollingStarted = false;
    private static volatile boolean bapRefreshTickerStarted = false;
    private static volatile boolean serviceStateListenerInstalled = false;
    private static volatile DSIServiceStateListener cachedStateListener = null;
    private static volatile DSIProxy cachedDsiProxy = null;

    private Mst2NowPlayingBridge() { /* static only */ }

    public static void attachOnce(HsmTarget hsmTarget) {
        synchronized (Mst2NowPlayingBridge.class) {
            if (attached || attaching) return;
            if (hsmTarget == null) return;
            attaching = true;
            cachedHsmTarget = hsmTarget;
        }
        initDebugMode();
        dlog("attachOnce: scheduling deferred attach");
        Thread t = new Thread(new AttachWorker(hsmTarget), "Mst2NowPlayingBridge-Attach");
        try { t.setDaemon(true); } catch (Throwable th) {}
        try { t.start(); } catch (Throwable th) {
            dlog("attachOnce: failed: " + th);
            synchronized (Mst2NowPlayingBridge.class) { attaching = false; }
        }
    }

    public static String getCachedTitle() { return cachedTitle; }
    public static String getCachedArtist() { return cachedArtist; }
    public static String getCachedAlbum() { return cachedAlbum; }

    public static void resetAttach() {
        synchronized (Mst2NowPlayingBridge.class) {
            attached = false;
            attaching = false;
            errorCount = 0;
        }
        cachedTitle = "";
        cachedArtist = "";
        cachedAlbum = "";
        lastValidUpdateMs = 0L;
        dlog("reset-attach: state cleared");
    }

    private static final class AttachWorker implements Runnable {
        private final HsmTarget hsmTarget;
        AttachWorker(HsmTarget t) { this.hsmTarget = t; }

        public void run() {
            try {
                Thread.currentThread().setContextClassLoader(Mst2NowPlayingBridge.class.getClassLoader());
            } catch (Throwable th) {}
            for (int i = 0; i < RETRY_ATTEMPTS; i++) {
                try {
                    if (tryAttach(hsmTarget)) {
                        synchronized (Mst2NowPlayingBridge.class) {
                            attached = true;
                            attaching = false;
                        }
                        dlog("attached on attempt " + (i + 1));
                        clearSlot0Junk();
                        startPpsPollingThread();
                        startBapRefreshTicker();
                        installShutdownHook();
                        installServiceStateListener();
                        return;
                    }
                } catch (Throwable th) {
                    if (i == 0) dlog("attempt 1 error: " + th);
                }
                try { Thread.sleep(RETRY_INTERVAL_MS); }
                catch (InterruptedException ie) {
                    synchronized (Mst2NowPlayingBridge.class) { attaching = false; }
                    return;
                }
            }
            synchronized (Mst2NowPlayingBridge.class) { attaching = false; }
            dlog("AttachWorker gave up after " + RETRY_ATTEMPTS + " attempts");
        }
    }

    private static boolean tryAttach(HsmTarget hsmTarget) throws Exception {
        DSIProxyAPI api = DSIProxyFactory.getDSIProxyAPI();
        if (api == null) return false;
        DSIProxy proxy = api.getDSIProxy();
        if (proxy == null) return false;
        cachedDsiProxy = proxy;
        Class dsiCarplayClass = Class.forName("org.dsi.ifc.carplay.DSICarplay");
        Class dsiCarplayListenerClass = Class.forName("org.dsi.ifc.carplay.DSICarplayListener");
        DSICarplay svc = (DSICarplay) proxy.getService(hsmTarget, dsiCarplayClass);
        if (svc == null) return false;
        DSIProxyAdapterFactory adapterFactory = proxy.getAdapterFactory();
        if (adapterFactory == null) return false;
        DSIListener adapter = adapterFactory.createDSIListenerMethodAdapter(hsmTarget, dsiCarplayListenerClass);
        if (adapter == null) return false;
        DSIListener wrapped = (DSIListener) Proxy.newProxyInstance(
                Mst2NowPlayingBridge.class.getClassLoader(),
                new Class[]{DSIListener.class, DSICarplayListener.class},
                new BridgeHandler(adapter));
        proxy.addResponseListener(hsmTarget, dsiCarplayListenerClass, wrapped);
        svc.setNotification(NOWPLAYING_ATTR, wrapped);
        return true;
    }

    private static final class BridgeHandler implements InvocationHandler {
        private final DSIListener adapter;
        BridgeHandler(DSIListener adapter) { this.adapter = adapter; }

        public Object invoke(Object proxyObj, Method method, Object[] args) {
            String name = method.getName();
            Class declaring = method.getDeclaringClass();
            try {
                if (declaring == Object.class) {
                    if ("toString".equals(name)) return "Mst2NowPlayingBridge.Proxy";
                    if ("hashCode".equals(name)) return new Integer(System.identityHashCode(proxyObj));
                    if ("equals".equals(name)) return new Boolean(proxyObj == args[0]);
                    return null;
                }
                // Skip all hooks while a phone call is active.
                if (isCallActive()) return null;
                if ("updateNowPlayingData".equals(name)) {
                    int valid = args[1] != null ? ((Integer) args[1]).intValue() : 0;
                    if (valid == VALID_REAL) handleNowPlayingData((TrackData) args[0]);
                } else if ("updatePlaybackState".equals(name)) {
                    int valid = args[1] != null ? ((Integer) args[1]).intValue() : 0;
                    if (valid == VALID_REAL) handlePlaybackState((PlaybackInfo) args[0]);
                } else if ("updatePlayposition".equals(name)) {
                    int valid = args[1] != null ? ((Integer) args[1]).intValue() : 0;
                    int positionMs = args[0] != null ? ((Integer) args[0]).intValue() : 0;
                    if (valid == VALID_REAL) handlePlayPosition(positionMs);
                } else if ("updateCoverArtUrl".equals(name)) {
                    int valid = args[1] != null ? ((Integer) args[1]).intValue() : 0;
                    if (valid == VALID_REAL) handleCoverArtUrl((ResourceLocator) args[0]);
                }
            } catch (Throwable t) {
                incrementErrorCount("invoke[" + name + "]: " + t);
            }
            return null;
        }
    }

    private static void handleNowPlayingData(TrackData td) {
        if (td == null) return;
        AVDCAudioCurrentTrackInfoCollector slot = slot0();
        if (slot == null) return;
        slot.avdc_audio_current_track_info__title = nullToEmpty(td.title);
        slot.avdc_audio_current_track_info__artist = nullToEmpty(td.artist);
        slot.avdc_audio_current_track_info__album = nullToEmpty(td.album);
        slot.avdc_audio_current_track_info__total_time = td.duration;
        slot.avdc_audio_current_track_info__filename = "";
        slot.avdc_audio_current_track_info__tracknumber = 0;
        slot.avdc_audio_current_track_info__is_video_podcast = false;
        slot.avdc_audio_current_track_info__is_vbr_coded = false;
        lastDurationMs = td.duration;
        ASLPropertyManager pm = ServiceManager.aslPropertyManager;
        if (pm != null) {
            try { pm.valueChangedBoolean(PROP_HAS_DETAIL_INFOS, true); } catch (Throwable t) {}
        }
        try { TrackInfo.CURRENT_TRACK_INFO.updateList(TrackInfo.mMetaInfos); } catch (Throwable t) {}
        cachedTitle = nullToEmpty(td.title);
        cachedArtist = nullToEmpty(td.artist);
        cachedAlbum = nullToEmpty(td.album);
        lastValidUpdateMs = System.currentTimeMillis();
        triggerBapRefresh();
    }

    private static void handlePlaybackState(PlaybackInfo info) {
        if (info == null || info.status == 0) return;
        ASLPropertyManager pm = ServiceManager.aslPropertyManager;
        if (pm != null) {
            try { pm.valueChangedInteger(PROP_PLAYBACK_STATE, info.status); }
            catch (Throwable t) { incrementErrorCount("valueChangedInteger(29): " + t); }
        }
    }

    private static void handlePlayPosition(int positionMs) {
        ASLPropertyManager pm = ServiceManager.aslPropertyManager;
        if (pm == null) return;
        try { pm.valueChangedInteger(PROP_POSITION_CURRENT, positionMs); }
        catch (Throwable t) { incrementErrorCount("valueChangedInteger(13): " + t); }
        int total = lastDurationMs;
        if (total <= 0) return;
        int remainingMs = (total > positionMs && positionMs >= 0)
            ? ((total / 1000) - (positionMs / 1000)) * 1000 : 0;
        try { pm.valueChangedInteger(PROP_POSITION_REMAINING, remainingMs); }
        catch (Throwable t) { incrementErrorCount("valueChangedInteger(14): " + t); }
    }

    private static void handleCoverArtUrl(ResourceLocator url) {
        AVDCAudioCurrentTrackInfoCollector slot = slot0();
        if (slot == null) return;
        slot.avdc_audio_current_track_info__cover = url;
        slot.avdc_audio_current_track_info__is_cover_available = (url != null);
        try { TrackInfo.CURRENT_TRACK_INFO.updateList(TrackInfo.mMetaInfos); }
        catch (Throwable t) {}
    }

    private static void startPpsPollingThread() {
        synchronized (Mst2NowPlayingBridge.class) {
            if (ppsPollingStarted) return;
            ppsPollingStarted = true;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                String lastRenderedTitle = "";
                String lastRenderedArtist = "";
                String lastRenderedAlbum = "";
                String lastContent = "";
                while (true) {
                    try {
                        if (isCallActive()) {
                            Thread.sleep(PPS_POLL_INTERVAL_MS);
                            continue;
                        }
                        String content = readPpsDirect();
                        if (content == null) {
                            Thread.sleep(PPS_POLL_INTERVAL_MS);
                            continue;
                        }
                        // Early-out: byte-identical PPS content -> nothing to do this cycle.
                        if (content.equals(lastContent)) {
                            Thread.sleep(PPS_POLL_INTERVAL_MS);
                            continue;
                        }
                        lastContent = content;
                        String title  = "";
                        String artist = "";
                        String album  = "";
                        if (content.length() > 0) {
                            title  = extract(content, "MediaItemTitle::");
                            artist = extract(content, "MediaItemArtist::");
                            album  = extract(content, "MediaItemAlbumTitle::");
                        }
                        long now = System.currentTimeMillis();
                        if (title.length() > 0) {
                            cachedTitle = title;
                            cachedArtist = artist;
                            cachedAlbum = album;
                            lastValidUpdateMs = now;
                            boolean changed = !title.equals(lastRenderedTitle)
                                           || !artist.equals(lastRenderedArtist)
                                           || !album.equals(lastRenderedAlbum);
                            if (changed) {
                                lastRenderedTitle = title;
                                lastRenderedArtist = artist;
                                lastRenderedAlbum = album;
                                triggerBapRefresh();
                            }
                        } else {
                            if (lastValidUpdateMs > 0 && (now - lastValidUpdateMs > STALE_TIMEOUT_MS)) {
                                cachedTitle = "";
                                cachedArtist = "";
                                cachedAlbum = "";
                                lastValidUpdateMs = 0L;
                                lastRenderedTitle = "";
                                lastRenderedArtist = "";
                                lastRenderedAlbum = "";
                                triggerBapRefresh();
                            }
                        }
                        Thread.sleep(PPS_POLL_INTERVAL_MS);
                    } catch (InterruptedException ie) { return; }
                    catch (Throwable th) {
                        incrementErrorCount("pps-poll: " + th);
                        try { Thread.sleep(PPS_POLL_INTERVAL_MS); } catch (InterruptedException ie) { return; }
                    }
                }
            }
        }, "Mst2NowPlayingBridge-PpsPoll");
        try { t.setDaemon(true); } catch (Throwable th) {}
        try { t.start(); } catch (Throwable th) { dlog("pps-thread start failed: " + th); }
    }

    // Force Shadow.process(-1) to render the BAP slots immediately. Without this, a PPS-cache
    // update is invisible until an unrelated Stock-property-change. Shadow has its own 200 ms
    // throttle, so 1 Hz invocation is safe.
    private static void triggerBapRefresh() {
        Class shadowClass = cachedShadowClass;
        java.lang.reflect.Field instanceField = cachedShadowInstanceField;
        Method m = cachedShadowProcessMethod;
        if (shadowClass == null || instanceField == null || m == null) {
            try {
                shadowClass = Class.forName(
                    "de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo");
                instanceField = shadowClass.getField("INSTANCE");
                m = shadowClass.getMethod("process", new Class[]{Integer.TYPE});
                cachedShadowClass = shadowClass;
                cachedShadowInstanceField = instanceField;
                cachedShadowProcessMethod = m;
            } catch (Throwable t) {
                return;
            }
        }
        Object instance;
        try {
            instance = instanceField.get(null);
        } catch (Throwable t) {
            return;
        }
        if (instance == null) return;
        try {
            m.invoke(instance, new Object[]{new Integer(-1)});
        } catch (Throwable t) {
            // best-effort
        }
    }

    private static void startBapRefreshTicker() {
        synchronized (Mst2NowPlayingBridge.class) {
            if (bapRefreshTickerStarted) return;
            bapRefreshTickerStarted = true;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        if (attached && !isCallActive()) {
                            triggerBapRefresh();
                        }
                        Thread.sleep(REFRESH_TICK_INTERVAL_MS);
                    } catch (InterruptedException ie) { return; }
                    catch (Throwable th) {
                        incrementErrorCount("bap-tick: " + th);
                        try { Thread.sleep(REFRESH_TICK_INTERVAL_MS); }
                        catch (InterruptedException ie) { return; }
                    }
                }
            }
        }, "Mst2NowPlayingBridge-BapTick");
        try { t.setDaemon(true); } catch (Throwable th) {}
        try { t.start(); } catch (Throwable th) { dlog("bap-tick start failed: " + th); }
    }

    private static String readPpsDirect() {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(PPS_NOWPLAYING);
            byte[] buf = new byte[4096];
            int n = fis.read(buf);
            if (n > 0) return new String(buf, 0, n, "UTF-8");
            return "";
        } catch (Throwable t) {
            return null;
        } finally {
            if (fis != null) try { fis.close(); } catch (Throwable t2) {}
        }
    }

    private static String extract(String content, String key) {
        int idx = content.indexOf(key);
        if (idx < 0) return "";
        int start = idx + key.length();
        int end = content.indexOf('\n', start);
        if (end < 0) end = content.length();
        // Java 1.4 String.substring shares the backing char[]; the explicit new String() forces a
        // copy so the small cached title/artist/album do not retain the full 4 KB content array.
        return new String(content.substring(start, end).trim());
    }

    private static AVDCAudioCurrentTrackInfoCollector slot0() {
        AVDCAudioCurrentTrackInfoCollector[] meta = TrackInfo.mMetaInfos;
        if (meta == null || meta.length == 0) return null;
        return meta[0];
    }

    private static void clearSlot0Junk() {
        AVDCAudioCurrentTrackInfoCollector slot = slot0();
        if (slot == null) return;
        try {
            slot.avdc_audio_current_track_info__title = "";
            slot.avdc_audio_current_track_info__artist = "";
            slot.avdc_audio_current_track_info__album = "";
            slot.avdc_audio_current_track_info__filename = "";
        } catch (Throwable t) {}
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // public so the Shadow CurrentStationInfo can apply the same Call-Gate.
    public static boolean isCallActive() {
        HsmTarget ht = cachedHsmTarget;
        if (ht == null) return false;
        try {
            if (cachedPropsField == null) {
                cachedPropsField = ht.getClass().getDeclaredField("properties");
                cachedPropsField.setAccessible(true);
            }
            Object props = cachedPropsField.get(ht);
            if (props == null) return false;
            if (cachedGetCallStatesMethod == null) {
                cachedGetCallStatesMethod = props.getClass().getMethod("getCurrentCallStates", new Class[0]);
            }
            Hashtable states = (Hashtable) cachedGetCallStatesMethod.invoke(props, new Object[0]);
            return states != null && states.size() > 0;
        } catch (Throwable t) {
            return false; // fail-open
        }
    }

    private static synchronized void incrementErrorCount(String detail) {
        errorCount++;
        dlog("err[" + errorCount + "]: " + detail);
        if (errorCount > ERROR_THRESHOLD) {
            dlog("error-threshold reached -- resetting attach");
            resetAttach();
            HsmTarget ht = cachedHsmTarget;
            if (ht != null) attachOnce(ht);
        }
    }

    private static void installShutdownHook() {
        if (shutdownInstalled) return;
        shutdownInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                public void run() {
                    dlog("shutdown-hook: bridge cleanup");
                    resetAttach();
                }
            }, "Mst2NowPlayingBridge-Shutdown"));
        } catch (Throwable t) { /* swallow */ }
    }

    // Covers iPhone-Disconnect/Reconnect: DSI service unregisters on unplug, re-registers on
    // replug. Error-Counter alone misses this because no errors are thrown -- callbacks just stop arriving.
    private static void installServiceStateListener() {
        if (serviceStateListenerInstalled) return;
        DSIProxy proxy = cachedDsiProxy;
        if (proxy == null) return;
        try {
            final Class dsiCarplayClass = Class.forName("org.dsi.ifc.carplay.DSICarplay");
            cachedStateListener = new DSIServiceStateListener() {
                public void registered(String svcName, int channel) {
                    if (svcName != null && svcName.equals(dsiCarplayClass.getName())) {
                        dlog("dsi-state: DSICarplay registered (ch=" + channel + ") -> reattach");
                        HsmTarget ht = cachedHsmTarget;
                        synchronized (Mst2NowPlayingBridge.class) {
                            attached = false;
                            attaching = false;
                        }
                        if (ht != null) attachOnce(ht);
                    }
                }
                public void unregistered(String svcName, int channel) {
                    if (svcName != null && svcName.equals(dsiCarplayClass.getName())) {
                        dlog("dsi-state: DSICarplay unregistered -> clear cache");
                        cachedTitle = "";
                        cachedArtist = "";
                        cachedAlbum = "";
                        lastValidUpdateMs = 0L;
                    }
                }
            };
            proxy.addServiceStateListener(dsiCarplayClass, cachedStateListener);
            serviceStateListenerInstalled = true;
            dlog("dsi-state-listener installed for DSICarplay");
        } catch (Throwable t) {
            dlog("installServiceStateListener failed: " + t);
        }
    }

    // One-shot debug initialisation. Checks the sentinel file exactly once per JVM and, if present,
    // opens the persistent bridge.log stream in append mode. All later dlog() calls reuse it.
    private static void initDebugMode() {
        synchronized (Mst2NowPlayingBridge.class) {
            if (debugInitDone) return;
            debugInitDone = true;
            if (!new File(DEBUG_MARKER).exists()) return;
            try {
                File f = new File(BRIDGE_LOG);
                if (f.length() > LOG_MAX_SIZE) f.delete();
                debugLogStream = new FileOutputStream(BRIDGE_LOG, true);
                debugBytesWritten = 0L;
                DEBUG = true;
            } catch (Throwable t) {
                debugLogStream = null;
                DEBUG = false;
            }
        }
    }

    // Debug log helper. Production mode (DEBUG=false) returns immediately on the first check --
    // no File touch anywhere on the path.
    private static void dlog(String s) {
        if (!DEBUG) return;
        FileOutputStream fos = debugLogStream;
        if (fos == null) return;
        try {
            long ms = System.currentTimeMillis() - JVM_START_MS;
            byte[] b = ("[" + ms + "] " + s + "\n").getBytes("UTF-8");
            fos.write(b);
            fos.flush();
            debugBytesWritten += b.length;
            if (debugBytesWritten > LOG_MAX_SIZE) {
                try { fos.close(); } catch (Throwable t2) {}
                debugLogStream = null;
                new File(BRIDGE_LOG).delete();
                debugLogStream = new FileOutputStream(BRIDGE_LOG, true);
                debugBytesWritten = 0L;
            }
        } catch (Throwable t) {}
    }
}
