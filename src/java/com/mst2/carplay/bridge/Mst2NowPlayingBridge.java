/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v2.0
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
import java.util.Enumeration;
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
    private static final long ASL_WAIT_MS = 2000L;

    private static final long PPS_POLL_INTERVAL_MS = 500L;
    static final String ROOT = System.getProperty("cpvc.test.root", "");
    private static final String PPS_NOWPLAYING = ROOT + "/pps/services/multimedia/iap2/nowplaying";
    private static final String COVER_TARGET_SIZE = ROOT + "/ramdisk/carplayvc/cover-target-size";
    private static final String COVER_TARGET_TMP  = ROOT + "/ramdisk/carplayvc/cover-target-size.tmp";

    private static final int COVER_NOCOVER_TARGET = 16777215;

    private static final int COVER_MAX_JPEG = 327680;

    private static final String COVER_STATE     = ROOT + "/ramdisk/carplayvc/cover-current.path";

    private static final long COVER_DEBOUNCE_MS = 2000L;

    private static final long COVER_ZERO_HOLD_MS = 7000L;

    private static final long COVER_SENTINEL_GAP_MS = 2500L;

    private static final long COVER_RETRY_DELAY_MS         = 10000L;
    private static final long COVER_BOUNCE_HOLD_MS         = 2500L;
    private static final long TAP_STATE_CHECK_THROTTLE_MS  = 1500L;
    private static final long STALE_TIMEOUT_MS = 120000L;
    private static final int ERROR_THRESHOLD = 100;
    private static final long REFRESH_TICK_INTERVAL_MS = 1000L;
    private static final long REFRESH_TICK_POLL_MS     = 250L;

    private static final long HH_OVERFLOW_CAP = 99L;
    private static final long PPS_TICK_STALE_MS = 5000L;

    private static final long ELAPSED_RESYNC_TOLERANCE_MS = 700L;

    private static final long ELAPSED_PREDICT_OFFSET_MS = 500L;

    private static final int TS_LINE_BUDGET = 30;
    private static final int TS_BAR_WIDTH = 30;
    private static final int TS_BAR_MIN = 6;
    private static final char TS_TRACK_CHAR = '.';
    private static final char TS_MARKER_CHAR = '|';

    private static volatile boolean attached = false;
    private static volatile boolean attaching = false;
    private static volatile HsmTarget cachedHsmTarget = null;
    private static volatile int lastDurationMs = 0;
    private static volatile int errorCount = 0;

    private static volatile String cachedTitle = "";
    private static volatile String cachedArtist = "";
    private static volatile String cachedAlbum = "";
    private static volatile int cachedArtworkSize = 0;
    private static volatile boolean committedNoCover = false;
    private static volatile long coverSentinelAtMs = 0L;
    private static volatile long lastValidUpdateMs = 0L;

    private static volatile String lastPushedTitle = "";
    private static volatile String lastPushedAlbum = "";

    private static volatile long cachedDurationMs = 0L;
    private static volatile long cachedElapsedMs = 0L;
    private static volatile long lastPpsElapsedTickMs = 0L;
    private static volatile boolean cachedPlaying = false;

    private static final String BRIDGE_LOG = ROOT + "/dev/shmem/cpvc-nowplaying.log";
    private static final String DEBUG_MARKER = ROOT + "/tsd/var/carplayvc/debug-enabled";
    private static final long LOG_MAX_SIZE = Mst2Log.BIG_CAP;
    private static final long JVM_START_MS = System.currentTimeMillis();

    private static volatile boolean DEBUG = false;
    private static volatile FileOutputStream debugLogStream = null;
    private static volatile long debugBytesWritten = 0L;
    private static volatile boolean debugInitDone = false;

    private static volatile java.lang.reflect.Field cachedPropsField = null;
    private static volatile Method cachedGetCallStatesMethod = null;

    private static volatile Class cachedShadowClass = null;
    private static volatile java.lang.reflect.Field cachedShadowInstanceField = null;
    private static volatile Method cachedShadowProcessMethod = null;
    private static volatile java.lang.reflect.Field cachedPqInstanceField = null;
    private static volatile Method cachedPqProcessMethod = null;
    private static volatile boolean pqSeen = false;
    private static volatile String pqLastKey = null;

    private static volatile boolean cachedCarplayShuffleOn = false;
    private static volatile Class cachedSrcStateClass = null;
    private static volatile java.lang.reflect.Field cachedSrcStateInstanceField = null;
    private static volatile Method cachedSrcStateProcessMethod = null;

    private static volatile boolean shutdownInstalled = false;
    private static volatile boolean ppsPollingStarted = false;
    private static volatile boolean bapRefreshTickerStarted = false;
    private static volatile boolean serviceStateListenerInstalled = false;
    private static volatile DSIServiceStateListener cachedStateListener = null;
    private static volatile DSIProxy cachedDsiProxy = null;

    private Mst2NowPlayingBridge() {  }

    public static void attachOnce(HsmTarget hsmTarget) {
        synchronized (Mst2NowPlayingBridge.class) {
            if (attached || attaching) return;
            if (hsmTarget == null) return;
            attaching = true;
            cachedHsmTarget = hsmTarget;
        }
        initDebugMode();
        BapStr.probe();
        dlog("attachOnce: scheduling deferred attach");
        Thread t = new Thread(new AttachWorker(hsmTarget), "cpvc-w13");
        try { t.setDaemon(true); } catch (Throwable th) {}
        try { t.start(); } catch (Throwable th) {
            dlog("attachOnce: failed: " + th);
            synchronized (Mst2NowPlayingBridge.class) { attaching = false; }
        }
    }

    public static String getCachedTitle() { return cachedTitle; }
    public static String getCachedArtist() { return cachedArtist; }
    public static String getCachedAlbum() { return cachedAlbum; }

    public static String getCurrentTimestamp() {
        long dur = cachedDurationMs;
        if (dur <= 0L) return "";
        long elapsedNow;
        long now = System.currentTimeMillis();
        long tick = lastPpsElapsedTickMs;
        boolean ppsStale = (tick <= 0L) || ((now - tick) > PPS_TICK_STALE_MS);
        if (cachedPlaying && !ppsStale) {
            long deltaSinceTick = now - tick;
            if (deltaSinceTick < 0L) deltaSinceTick = 0L;
            elapsedNow = cachedElapsedMs + deltaSinceTick + ELAPSED_PREDICT_OFFSET_MS;
            if (elapsedNow > dur) elapsedNow = dur;
        } else {
            elapsedNow = cachedElapsedMs;
        }
        if (elapsedNow < 0L) elapsedNow = 0L;
        return formatScrubber(elapsedNow, dur);
    }

    private static String formatScrubber(long elapsedMs, long durMs) {
        boolean useHours = (durMs / 1000L) >= 3600L;
        long elapsedSec = elapsedMs / 1000L;
        long durSec = durMs / 1000L;
        if (durSec < 1L) durSec = 1L;
        String left = formatTimeFixed(elapsedSec * 1000L, useHours);
        long remainingSec = durSec - elapsedSec;
        if (remainingSec < 0L) remainingSec = 0L;
        String right = "-" + formatTimeFixed(remainingSec * 1000L, useHours);
        int timeLen = formatTimeFixed(durMs, useHours).length() + 1;
        int width = TS_LINE_BUDGET - (2 * timeLen) - 2;
        if (width > TS_BAR_WIDTH) width = TS_BAR_WIDTH;
        if (width < TS_BAR_MIN) width = TS_BAR_MIN;
        long range;
        int offset;
        if (durSec < 180L) {
            range = (long)(width - 3);
            offset = 1;
            if (range < 1L) { range = (long)(width - 1); offset = 0; }
        } else {
            range = (long)(width - 1);
            offset = 0;
        }
        long pos = (elapsedSec * range) / durSec;
        if (pos < 0L) pos = 0L;
        else if (pos > range) pos = range;
        int marker = (int) pos + offset;
        if (marker >= width) marker = width - 1;
        StringBuffer sb = new StringBuffer(TS_LINE_BUDGET);
        sb.append(left);
        sb.append(' ');
        for (int i = 0; i < width; i++) sb.append(i == marker ? TS_MARKER_CHAR : TS_TRACK_CHAR);
        sb.append(' ');
        sb.append(right);
        return sb.toString();
    }

    private static String formatTimeFixed(long ms, boolean useHours) {
        long totalSec = ms / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > HH_OVERFLOW_CAP) { h = HH_OVERFLOW_CAP; m = 59L; s = 59L; }
        StringBuffer sb = new StringBuffer(9);
        if (useHours) {
            sb.append(h);
            sb.append(':');
            if (m < 10L) sb.append('0');
            sb.append(m);
        } else {
            sb.append(m);
        }
        sb.append(':');
        if (s < 10L) sb.append('0');
        sb.append(s);
        return sb.toString();
    }

private static long parseLongOrZero(String s) {
        if (s == null || s.length() == 0) return 0L;
        try { return Long.parseLong(s.trim()); }
        catch (Throwable t) { return 0L; }
    }

    public static void resetAttach() {
        synchronized (Mst2NowPlayingBridge.class) {
            attached = false;
            attaching = false;
            errorCount = 0;
        }
        cachedTitle = "";
        pqLastKey = null;
        cachedArtist = "";
        cachedAlbum = "";
        cachedArtworkSize = COVER_NOCOVER_TARGET;
        writeTargetSize(COVER_NOCOVER_TARGET);
        committedNoCover = true;
        lastValidUpdateMs = 0L;

        cachedDurationMs = 0L;
        cachedElapsedMs = 0L;
        lastPpsElapsedTickMs = 0L;
        cachedPlaying = false;
        dlog("reset-attach: state cleared");
    }

    private static final class AttachWorker implements Runnable {
        private final HsmTarget hsmTarget;
        AttachWorker(HsmTarget t) { this.hsmTarget = t; }

        public void run() {
            try {
                Thread.currentThread().setContextClassLoader(Mst2NowPlayingBridge.class.getClassLoader());
            } catch (Throwable th) {}
            AttachJob pending = null;
            for (int i = 0; i < RETRY_ATTEMPTS; i++) {
                try {
                    AttachJob job = pending;
                    boolean finished;
                    if (job == null) {
                        job = new AttachJob(hsmTarget);
                        int r = Mst2Asl.run(job, ASL_WAIT_MS);
                        if (r == Mst2Asl.NO_ASL && i == 0) dlog("attach waits for the ASL task");
                        if (r == Mst2Asl.PENDING && i == 0) dlog("attach queued on the ASL task");
                        finished = r == Mst2Asl.DONE;
                        pending = r == Mst2Asl.PENDING ? job : null;
                    } else {
                        finished = job.await(ASL_WAIT_MS);
                        if (finished) pending = null;
                    }
                    if (finished && job.error() != null) throw job.error();
                    if (finished && job.attached) {
                        onAttached(i + 1);
                        return;
                    }
                } catch (Throwable th) {
                    if (i == 0) dlog("attempt 1 error: " + th);
                }
                try { Thread.sleep(RETRY_INTERVAL_MS); }
                catch (InterruptedException ie) {
                    if (pending != null) pending.abandon();
                    synchronized (Mst2NowPlayingBridge.class) { attaching = false; }
                    return;
                }
            }
            // A job that already started may still attach; a second attach would double the listeners.
            if (pending != null && !pending.abandon() && pending.await(ASL_WAIT_MS) && pending.attached) {
                onAttached(RETRY_ATTEMPTS);
                return;
            }
            synchronized (Mst2NowPlayingBridge.class) { attaching = false; }
            dlog("AttachWorker gave up after " + RETRY_ATTEMPTS + " attempts");
        }

        private static void onAttached(int attempt) {
            synchronized (Mst2NowPlayingBridge.class) {
                attached = true;
                attaching = false;
            }
            dlog("attached on attempt " + attempt);
            // Nothing here may reach the retry loop: a second attach doubles the DSI subscription.
            try { BapStr.probe(); } catch (Throwable t) { dlog("after attach: " + t); }
            try { clearSlot0Junk(); } catch (Throwable t) { dlog("after attach: " + t); }
            try { startPpsPollingThread(); } catch (Throwable t) { dlog("after attach: " + t); }
            try { startBapRefreshTicker(); } catch (Throwable t) { dlog("after attach: " + t); }
            try { installShutdownHook(); } catch (Throwable t) { dlog("after attach: " + t); }
            try { installServiceStateListener(); } catch (Throwable t) { dlog("after attach: " + t); }
        }
    }

    private static final class AttachJob extends Mst2Asl.Job {
        private final HsmTarget hsmTarget;
        volatile boolean attached;
        private boolean abandoned;
        private boolean started;

        AttachJob(HsmTarget t) { this.hsmTarget = t; }

        private synchronized boolean begin() {
            if (abandoned) return false;
            started = true;
            return true;
        }

        synchronized boolean abandon() {
            abandoned = true;
            return !started;
        }

        protected void work() throws Exception {
            if (begin()) attached = tryAttach(hsmTarget);
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
                    if ("toString".equals(name)) return "np";
                    if ("hashCode".equals(name)) return new Integer(System.identityHashCode(proxyObj));
                    if ("equals".equals(name)) return new Boolean(proxyObj == args[0]);
                    return null;
                }

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
                boolean lastHadTitle = false;
                long ppsMissingSinceMs = 0L;

                String coverTrackTitle = "";
                String coverTrackArtist = "";
                String coverTrackAlbum = "";
                long coverLastEnsureMs = 0L;
                int coverTrackArtSize = 0;
                long coverSettleSinceMs = 0L;
                long coverArtSinceMs = 0L;
                long coverZeroSinceMs = 0L;
                long coverCommittedAtMs = 0L;
                boolean coverRetryDone  = false;
                boolean coverEnsureBouncePending = false;
                long coverBounceStartedMs = 0L;
                boolean tapInNocover    = false;
                long tapNocoverCheckTs  = 0L;
                committedNoCover = false;
                while (true) {
                    try {
                        if (isCallActive()) {
                            if (coverEnsureBouncePending) {
                                restoreCoverTargetAfterBounce();
                                coverEnsureBouncePending = false;
                                coverBounceStartedMs = 0L;
                            }
                            Thread.sleep(PPS_POLL_INTERVAL_MS);
                            continue;
                        }
                        String content = readPpsDirect();
                        long now = System.currentTimeMillis();
                        if (content == null) {

                            if (coverEnsureBouncePending) {
                                restoreCoverTargetAfterBounce();
                                coverEnsureBouncePending = false;
                                coverBounceStartedMs = 0L;
                            }
                            if (ppsMissingSinceMs == 0L) ppsMissingSinceMs = now;
                            if (lastValidUpdateMs > 0 && (now - lastValidUpdateMs > STALE_TIMEOUT_MS)
                                && (now - ppsMissingSinceMs > STALE_TIMEOUT_MS)) {
                                content = "";
                            } else {
                                Thread.sleep(PPS_POLL_INTERVAL_MS);
                                continue;
                            }
                        } else {
                            ppsMissingSinceMs = 0L;
                        }

                        if (!content.equals(lastContent)) {
                            lastContent = content;
                            String title  = "";
                            String artist = "";
                            String album  = "";
                            int artSize   = 0;
                            if (content.length() > 0) {
                                title  = extract(content, "MediaItemTitle::");
                                artist = extract(content, "MediaItemArtist::");
                                album  = extract(content, "MediaItemAlbumTitle::");
                                artSize = parseIntSafe(extract(content, "MediaItemArtworkSize::"));

                                long newDurationMs = parseLongOrZero(extract(content, "MediaItemPlaybackDurationInMilliseconds::"));
                                long newElapsedMs  = parseLongOrZero(extract(content, "PlaybackElapsedTimeInMilliseconds::"));
                                boolean newPlaying = "Playing".equalsIgnoreCase(extract(content, "PlaybackStatus::"));
                                long nowTickMs = System.currentTimeMillis();

                                if (cachedPlaying && newPlaying && lastPpsElapsedTickMs > 0L) {
                                    long predictedElapsedMs = cachedElapsedMs + (nowTickMs - lastPpsElapsedTickMs);
                                    long signedDiffMs = newElapsedMs - predictedElapsedMs;
                                    long elapsedDiffMs = signedDiffMs < 0L ? -signedDiffMs : signedDiffMs;
                                    boolean smoothed = elapsedDiffMs <= ELAPSED_RESYNC_TOLERANCE_MS;
                                    if (DEBUG) dlog("ts-resync: raw=" + newElapsedMs + " pred=" + predictedElapsedMs
                                                    + " diff=" + signedDiffMs + (smoothed ? " SMOOTH" : " HARD"));
                                    if (smoothed) newElapsedMs = predictedElapsedMs;
                                }
                                cachedDurationMs = newDurationMs;
                                cachedElapsedMs = newElapsedMs;
                                cachedPlaying = newPlaying;
                                lastPpsElapsedTickMs = nowTickMs;

                                String shuf = extract(content, "PlaybackShuffleMode::");
                                boolean shuffleOn = (shuf.length() > 0) && !"Off".equalsIgnoreCase(shuf);
                                if (shuffleOn != cachedCarplayShuffleOn) {
                                    cachedCarplayShuffleOn = shuffleOn;
                                    dlog("shuffle: PlaybackShuffleMode=" + shuf + " -> on=" + shuffleOn);
                                    triggerSourceStateRefresh();
                                }
                            }
                            lastHadTitle = title.length() > 0;
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

                                boolean trackChanged = !title.equals(coverTrackTitle)
                                    || !artist.equals(coverTrackArtist)
                                    || !album.equals(coverTrackAlbum);
                                if (trackChanged) {
                                    coverTrackTitle = title;
                                    coverTrackArtist = artist;
                                    coverTrackAlbum = album;
                                    coverSettleSinceMs = now;
                                    coverRetryDone = false;
                                    coverLastEnsureMs = 0L;
                                    if (coverEnsureBouncePending) {

                                        restoreCoverTargetAfterBounce();
                                    }
                                    coverEnsureBouncePending = false;
                                    coverBounceStartedMs = 0L;
                                    tapInNocover = false;
                                    tapNocoverCheckTs = 0L;
                                    dlog("cover-debounce: track-change artSize=" + artSize
                                        + " cached=" + cachedArtworkSize);
                                }
                                if (artSize != coverTrackArtSize) coverArtSinceMs = now;
                                boolean noArt = (artSize == 0 || artSize > COVER_MAX_JPEG);
                                if (!noArt) coverZeroSinceMs = 0L;
                                else if (coverZeroSinceMs == 0L) coverZeroSinceMs = now;
                                coverTrackArtSize = artSize;
                            }
                        }
                        if (!lastHadTitle) {
                            if (lastValidUpdateMs > 0 && (now - lastValidUpdateMs > STALE_TIMEOUT_MS)) {
                                cachedTitle = "";
                                pqLastKey = null;
                                cachedArtist = "";
                                cachedAlbum = "";
                                lastValidUpdateMs = 0L;

                                cachedDurationMs = 0L;
                                cachedElapsedMs = 0L;
                                lastPpsElapsedTickMs = 0L;
                                cachedPlaying = false;
                                cachedCarplayShuffleOn = false;
                                lastRenderedTitle = "";
                                lastRenderedArtist = "";
                                lastRenderedAlbum = "";
                                coverTrackTitle = "";
                                coverTrackArtist = "";
                                coverTrackAlbum = "";
                                coverTrackArtSize = 0;
                                coverArtSinceMs = 0L;
                                coverZeroSinceMs = 0L;
                                coverCommittedAtMs = 0L;
                                coverEnsureBouncePending = false;
                                coverBounceStartedMs = 0L;
                                if (cachedArtworkSize != COVER_NOCOVER_TARGET) { cachedArtworkSize = COVER_NOCOVER_TARGET; writeTargetSize(COVER_NOCOVER_TARGET); }
                                committedNoCover = true;
                                triggerBapRefresh();
                            }
                        }

                        if (coverTrackTitle.length() > 0) {
                            if (coverTrackArtSize > 0 && coverTrackArtSize <= COVER_MAX_JPEG) {

                                if (coverTrackArtSize != cachedArtworkSize
                                    && (now - coverSettleSinceMs) >= COVER_DEBOUNCE_MS
                                    && (now - coverArtSinceMs) >= COVER_DEBOUNCE_MS
                                    && (now - coverSentinelAtMs) >= COVER_SENTINEL_GAP_MS) {
                                    committedNoCover = false;
                                    cachedArtworkSize = coverTrackArtSize;
                                    writeTargetSize(coverTrackArtSize);
                                    dlog("cover-commit: target=" + coverTrackArtSize + " after settle");
                                    coverCommittedAtMs = now;
                                    coverRetryDone = false;
                                    coverEnsureBouncePending = false;
                                    tapInNocover = false;
                                    tapNocoverCheckTs = 0L;
                                }
                            } else if (coverZeroSinceMs > 0L && (now - coverZeroSinceMs) >= COVER_ZERO_HOLD_MS
                                       && cachedArtworkSize != COVER_NOCOVER_TARGET) {

                                cachedArtworkSize = COVER_NOCOVER_TARGET;
                                committedNoCover = true;
                                coverCommittedAtMs = 0L;
                                coverEnsureBouncePending = false;
                                coverBounceStartedMs = 0L;
                                writeTargetSize(COVER_NOCOVER_TARGET);
                                dlog("cover-commit: NO-COVER (artSize=" + coverTrackArtSize
                                    + " for " + (now - coverZeroSinceMs) + " ms -> sentinel)");
                            }
                        }

                        if (coverEnsureBouncePending) {
                            boolean bounceStillValid = coverTrackArtSize > 0
                                && coverTrackArtSize <= COVER_MAX_JPEG
                                && coverTrackArtSize == cachedArtworkSize
                                && coverCommittedAtMs > 0L && !committedNoCover;
                            if (!bounceStillValid) {
                                restoreCoverTargetAfterBounce();
                                coverEnsureBouncePending = false;
                                coverBounceStartedMs = 0L;
                            } else if ((now - coverBounceStartedMs) >= COVER_BOUNCE_HOLD_MS) {
                                long heldMs = now - coverBounceStartedMs;
                                writeTargetSize(coverTrackArtSize);
                                coverEnsureBouncePending = false;
                                coverBounceStartedMs = 0L;
                                coverLastEnsureMs = now;
                                coverRetryDone = false;
                                dlog("cover-ensure-bounce: restore size=" + coverTrackArtSize
                                     + " held_ms=" + heldMs
                                     + " stuck_ms=" + (now - coverCommittedAtMs));
                            }
                        } else if (coverTrackArtSize > 0 && coverTrackArtSize <= COVER_MAX_JPEG
                                && coverTrackArtSize == cachedArtworkSize
                                && coverCommittedAtMs > 0L && !committedNoCover) {
                            if ((now - tapNocoverCheckTs) >= TAP_STATE_CHECK_THROTTLE_MS) {
                                tapInNocover = readCoverStateIsNocover();
                                tapNocoverCheckTs = now;
                            }
                            if (!tapInNocover) {
                                coverRetryDone = true;
                                coverLastEnsureMs = 0L;
                                coverEnsureBouncePending = false;
                            } else if ((now - coverCommittedAtMs) >= COVER_RETRY_DELAY_MS
                                    && (coverLastEnsureMs == 0L
                                        || (now - coverLastEnsureMs) >= COVER_RETRY_DELAY_MS)) {
                                writeTargetSize(COVER_NOCOVER_TARGET);
                                coverEnsureBouncePending = true;
                                coverBounceStartedMs = now;
                                coverLastEnsureMs = now;
                                coverRetryDone = false;
                                dlog("cover-ensure-bounce: nudge NOCOVER title-present="
                                     + ((coverTrackTitle != null && coverTrackTitle.length() > 0) ? "1" : "0")
                                     + " size=" + coverTrackArtSize
                                     + " stuck_ms=" + (now - coverCommittedAtMs));
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
        }, "cpvc-w15");
        try { t.setDaemon(true); } catch (Throwable th) {}
        try { t.start(); } catch (Throwable th) { dlog("pps-thread start failed: " + th); }
    }

    private static void triggerBapRefresh() {
        triggerMqbRefresh();
        triggerPqRefresh();
    }

    private static void triggerPqRefresh() {
        java.lang.reflect.Field instanceField = cachedPqInstanceField;
        Method m = cachedPqProcessMethod;
        if (instanceField == null || m == null) {
            try {
                Class c = Class.forName("de.vw.mib.bap.mqbpq.audiosd.functions.CurrentStationInfo");
                instanceField = c.getField("INSTANCE");
                m = c.getMethod("process", new Class[]{Integer.TYPE});
                cachedPqInstanceField = instanceField;
                cachedPqProcessMethod = m;
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
        if (!pqSeen) {
            pqSeen = true;
            Mst2Log.quiet("nowplaying", "pq stack active");
        }
        String key = (attached ? "1" : "0") + "\u0001" + cachedTitle + "\u0001" + cachedArtist + "\u0001" + cachedAlbum;
        if (key.equals(pqLastKey)) return;
        pqLastKey = key;
        final Object target = instance;
        final Method process = m;
        Mst2Asl.Job job = new Mst2Asl.Job() {
            protected void work() throws Exception {
                try {
                    process.invoke(target, new Object[]{new Integer(-1)});
                } catch (Throwable t) {
                    pqLastKey = null;
                }
            }
        };
        try {
            if (Mst2Asl.run(job, 0L) == Mst2Asl.NO_ASL) pqLastKey = null;
        } catch (Throwable t) {
            pqLastKey = null;
        }
    }

    private static void triggerMqbRefresh() {
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

        }
    }

    public static boolean isCarplayShuffleOn() {
        return attached && cachedCarplayShuffleOn;
    }

    private static void triggerSourceStateRefresh() {
        Class shadowClass = cachedSrcStateClass;
        java.lang.reflect.Field instanceField = cachedSrcStateInstanceField;
        Method m = cachedSrcStateProcessMethod;
        if (shadowClass == null || instanceField == null || m == null) {
            try {
                shadowClass = Class.forName(
                    "de.vw.mib.bap.mqbab2.audiosd.functions.SourceState");
                instanceField = shadowClass.getField("INSTANCE");
                m = shadowClass.getMethod("process", new Class[]{Integer.TYPE});
                cachedSrcStateClass = shadowClass;
                cachedSrcStateInstanceField = instanceField;
                cachedSrcStateProcessMethod = m;
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

        }
    }

    private static void startBapRefreshTicker() {
        synchronized (Mst2NowPlayingBridge.class) {
            if (bapRefreshTickerStarted) return;
            bapRefreshTickerStarted = true;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {

                String lastTs = "";
                long lastForcedMs = 0L;
                long lastTsChangeMs = 0L;
                while (true) {
                    try {
                        if (attached && !isCallActive()) {
                            long now = System.currentTimeMillis();
                            String ts = "";
                            try { ts = getCurrentTimestamp(); } catch (Throwable t2) { ts = ""; }
                            if (ts == null) ts = "";
                            boolean tsChanged = !ts.equals(lastTs);
                            if (tsChanged || (now - lastForcedMs) >= REFRESH_TICK_INTERVAL_MS) {
                                if (DEBUG && tsChanged && ts.length() > 0 && lastTsChangeMs > 0L)
                                    dlog("ts-cadence: dt=" + (now - lastTsChangeMs) + " disp=" + ts);
                                if (DEBUG && tsChanged) {
                                    String lTitle = cachedTitle == null ? "" : cachedTitle;
                                    String lAlbum = cachedAlbum == null ? "" : cachedAlbum;
                                    if (!lTitle.equals(lastPushedTitle) || !lAlbum.equals(lastPushedAlbum)) {
                                        lastPushedTitle = lTitle;
                                        lastPushedAlbum = lAlbum;
                                        dlog("bap-push: disp=" + ts + " dur=" + cachedDurationMs
                                            + " elapsed=" + cachedElapsedMs
                                            + " playing=" + (cachedPlaying ? 1 : 0)
                                            + " title='" + lTitle + "'"
                                            + " album='" + lAlbum + "'"
                                            + " art=" + cachedArtworkSize
                                            + " nocc=" + (committedNoCover ? 1 : 0));
                                    }
                                }
                                if (tsChanged) lastTsChangeMs = now;
                                lastTs = ts;
                                lastForcedMs = now;
                                triggerBapRefresh();
                            }
                        }
                        Thread.sleep(REFRESH_TICK_POLL_MS);
                    } catch (InterruptedException ie) { return; }
                    catch (Throwable th) {
                        incrementErrorCount("bap-tick: " + th);
                        try { Thread.sleep(REFRESH_TICK_INTERVAL_MS); }
                        catch (InterruptedException ie) { return; }
                    }
                }
            }
        }, "cpvc-w14");
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

        return new String(content.substring(start, end).trim());
    }

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        int v = 0, i = 0, n = s.length();
        boolean any = false;
        while (i < n) {
            char c = s.charAt(i++);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            any = true;
            if (v > 16777216) return 0;
        }
        return any ? v : 0;
    }

    private static void restoreCoverTargetAfterBounce() {
        if (!committedNoCover && cachedArtworkSize > 0 && cachedArtworkSize <= COVER_MAX_JPEG) {
            writeTargetSize(cachedArtworkSize);
        }
    }

    private static void writeTargetSize(int size) {
        if (size == COVER_NOCOVER_TARGET) coverSentinelAtMs = System.currentTimeMillis();
        FileOutputStream f = null;
        try {
            Mst2Log.ensureRamDir();
            f = new FileOutputStream(COVER_TARGET_TMP, false);
            f.write((Integer.toString(size) + "\n").getBytes("UTF-8"));
            f.close();
            f = null;
            new File(COVER_TARGET_TMP).renameTo(new File(COVER_TARGET_SIZE));
        } catch (Throwable t) {

        } finally {
            if (f != null) try { f.close(); } catch (Throwable t2) {}
        }
    }

    private static boolean readCoverStateIsNocover() {
        java.io.FileInputStream f = null;
        try {
            f = new java.io.FileInputStream(COVER_STATE);
            byte[] buf = new byte[16];
            int n = f.read(buf);
            if (n < 7) return false;
            return buf[0] == 'N' && buf[1] == 'O' && buf[2] == 'C' && buf[3] == 'O'
                && buf[4] == 'V' && buf[5] == 'E' && buf[6] == 'R';
        } catch (Throwable t) {
            return false;
        } finally {
            if (f != null) try { f.close(); } catch (Throwable ignore) {}
        }
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
            if (states == null || states.size() == 0) return false;
            Enumeration e = states.elements();
            while (e.hasMoreElements()) {
                Object call = e.nextElement();
                if (call == null) continue;
                try {
                    Method gs = call.getClass().getMethod("getStatus", new Class[0]);
                    Object st = gs.invoke(call, new Object[0]);
                    if (!(st instanceof Integer)) continue;
                    int status = ((Integer) st).intValue();
                    if (status == 1 || status == 2 || status == 3) return true;
                } catch (Throwable t2) {
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
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
            }, "cpvc-w16"));
        } catch (Throwable t) {  }
    }

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
                        pqLastKey = null;
                        cachedArtist = "";
                        cachedAlbum = "";
                        cachedArtworkSize = COVER_NOCOVER_TARGET;
                        writeTargetSize(COVER_NOCOVER_TARGET);
                        committedNoCover = true;
                        lastValidUpdateMs = 0L;

                        cachedDurationMs = 0L;
                        cachedElapsedMs = 0L;
                        lastPpsElapsedTickMs = 0L;
                        cachedPlaying = false;
                    }
                }
            };
            final DSIProxy p = proxy;
            final DSIServiceStateListener l = cachedStateListener;
            Mst2Asl.Job job = new Mst2Asl.Job() {
                protected void work() {
                    try {
                        p.addServiceStateListener(dsiCarplayClass, l);
                        serviceStateListenerInstalled = true;
                        dlog("dsi-state-listener installed for DSICarplay");
                    } catch (Throwable t) {
                        dlog("installServiceStateListener failed: " + t);
                    }
                }
            };
            int r = Mst2Asl.run(job, ASL_WAIT_MS);
            if (r == Mst2Asl.NO_ASL) dlog("installServiceStateListener: no ASL task");
            else if (r == Mst2Asl.PENDING) dlog("dsi-state-listener queued");
        } catch (Throwable t) {
            dlog("installServiceStateListener failed: " + t);
        }
    }

    private static void initDebugMode() {
        synchronized (Mst2NowPlayingBridge.class) {
            if (debugInitDone) return;
            debugInitDone = true;
            rotateNowPlayingLog();
            Mst2Log.quiet("nowplaying", "start debug=" + (new File(DEBUG_MARKER).exists() ? 1 : 0));
            if (!new File(DEBUG_MARKER).exists()) return;
            try {
                debugLogStream = new FileOutputStream(BRIDGE_LOG, true);
                debugBytesWritten = 0L;
                DEBUG = true;
            } catch (Throwable t) {
                debugLogStream = null;
                DEBUG = false;
            }
        }
    }

    private static void rotateNowPlayingLog() {
        try {
            File f = new File(BRIDGE_LOG);
            if (!f.exists() || f.length() == 0L) return;
            File oldF = new File(BRIDGE_LOG + ".old");
            if (oldF.exists()) oldF.delete();
            f.renameTo(oldF);
        } catch (Throwable t) {}
    }

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
                rotateNowPlayingLog();
                debugLogStream = new FileOutputStream(BRIDGE_LOG, true);
                debugBytesWritten = 0L;
            }
        } catch (Throwable t) {}
    }
}
