/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v1
 */
package de.vw.mib.asl.internal.kombipictureserver.usecaces;

import de.vw.mib.asl.internal.kombipictureserver.common.api.bap.audiosd.BapAudioSdDelegate;
import de.vw.mib.asl.internal.kombipictureserver.common.api.bap.audiosd.BapAudioSdService;
import de.vw.mib.asl.internal.kombipictureserver.common.api.media.asl.MediaCoverArtDelegate;
import de.vw.mib.asl.internal.kombipictureserver.common.api.media.asl.MediaCoverArtService;
import de.vw.mib.asl.internal.kombipictureserver.common.util.KombiPictureServerUtil;
import de.vw.mib.asl.internal.kombipictureserver.usecaces.PictureServerUseCase;
import org.dsi.ifc.base.DSIBase;
import org.dsi.ifc.base.DSIListener;
import org.dsi.ifc.global.ResourceLocator;
import org.dsi.ifc.kombipictureserver.DSIKombiPictureServer;

public class CoverArt
extends PictureServerUseCase
implements MediaCoverArtDelegate,
BapAudioSdDelegate {
    private DSIKombiPictureServer _kombiPictureServer;
    private DSIListener _kombiPictureServerServiceResponder;
    private MediaCoverArtService _mediaCoverArtService;
    private BapAudioSdService _bapAudioSdService;
    private static final int[] BAP_AUDIO_SD_SERVICES = new int[]{16, 22};
    private int _bapActiveSourceType;
    private ResourceLocator _mediaCoverArtResourceLocator;
    private boolean isWaitingForIsCoverValid;
    static Class class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServer;
    static Class class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServerListener;
    static Class class$de$vw$mib$asl$internal$kombipictureserver$common$api$media$asl$MediaCoverArtService;
    static Class class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService;

    // ====================== Live cover push (markerless) ======================
    public static volatile CoverArt INSTANCE = null;

    // State file written atomically by the native cpvc-cover-tap: 1 line "<hardlink-path>\n".
    private static final String MST2_LIVE_STATE = "/tsd/var/carplayvc/cover-current.path";
    // Canonical live cover (fallback if the state file is missing, without cache-bust).
    private static final String MST2_LIVE_COVER = "/tsd/var/carplayvc/cover.jpg";
    // No-cover sentinel: cover-current.path contains either a hardlink path OR the literal string
    // "NOCOVER" (written atomically by Bridge on a track change / by Tap on a miss). When the file
    // starts with "NOCOVER", the shadow pushes null (stock no-cover) instead of the previous track's cover.
    private static final String MST2_NOCOVER_SENTINEL = "NOCOVER";
    private static final long MST2_CYCLE_MS = 2000L;          // push ticker interval

    private static final String MST2_DEBUG_MARKER = "/tsd/var/carplayvc/debug-enabled";
    private static final String MST2_COVER_LOG = "/tsd/var/carplayvc/cpvc-cover.log";
    private static final long MST2_LOG_MAX = 1048576L;

    private static final Object MST2_TICKER_LOCK = new Object();
    private static volatile boolean mst2TickerStarted = false;
    private static volatile boolean mst2DebugChecked = false;
    private static volatile boolean mst2Debug = false;
    private static volatile int mst2LastTickSrc = -999;
    private static volatile String mst2LastBuiltPath = null;  // log-on-change
    private static volatile String mst2LastPushedUrl = null;  // push gate (only push on path change)
    private static volatile boolean mst2LastPushedNoCover = false; // (B) transition gate for the null push
    private static volatile int mst2TickCounter = 0;          // keepalive counter
    // v2 -- dynamisches Keepalive. NORMAL = 8 Ticks (~16 s) im stabilen Cover-Fall.
    // STUCK = 2 Ticks (~4 s) sobald NOCOVER laenger als STUCK_THRESHOLD anhaelt -> hoehere
    // Push-Frequenz haelt die IC-NOCOVER-Anzeige stabiler.
    private static final int  MST2_KEEPALIVE_TICKS_NORMAL = 8;
    private static final int  MST2_KEEPALIVE_TICKS_STUCK  = 2;
    private static final long MST2_STUCK_THRESHOLD_MS     = 5000L;
    private static volatile long mst2NocoverSince = 0L;
    // ==================== END live cover push (fields) ====================

    private DSIKombiPictureServer getKombiPictureServer() {
        if (this._kombiPictureServer == null) {
            this._kombiPictureServer = (DSIKombiPictureServer)this.getCategoryDelegate().getDsiService(this, class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServer == null ? (class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServer = CoverArt.class$("org.dsi.ifc.kombipictureserver.DSIKombiPictureServer")) : class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServer);
            this._kombiPictureServerServiceResponder = this.getCategoryDelegate().addDsiServiceResponder(this, (DSIBase)this._kombiPictureServer, class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServerListener == null ? (class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServerListener = CoverArt.class$("org.dsi.ifc.kombipictureserver.DSIKombiPictureServerListener")) : class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServerListener, null);
        }
        return this._kombiPictureServer;
    }

    private DSIListener getKombiPictureServerServiceResponder() {
        return this._kombiPictureServerServiceResponder;
    }

    private MediaCoverArtService getMediaCoverArtService() {
        if (this._mediaCoverArtService == null) {
            this._mediaCoverArtService = (MediaCoverArtService)this.getCategoryDelegate().getService(this, class$de$vw$mib$asl$internal$kombipictureserver$common$api$media$asl$MediaCoverArtService == null ? (class$de$vw$mib$asl$internal$kombipictureserver$common$api$media$asl$MediaCoverArtService = CoverArt.class$("de.vw.mib.asl.internal.kombipictureserver.common.api.media.asl.MediaCoverArtService")) : class$de$vw$mib$asl$internal$kombipictureserver$common$api$media$asl$MediaCoverArtService);
            this.getCategoryDelegate().registerServiceListener(this, class$de$vw$mib$asl$internal$kombipictureserver$common$api$media$asl$MediaCoverArtService == null ? (class$de$vw$mib$asl$internal$kombipictureserver$common$api$media$asl$MediaCoverArtService = CoverArt.class$("de.vw.mib.asl.internal.kombipictureserver.common.api.media.asl.MediaCoverArtService")) : class$de$vw$mib$asl$internal$kombipictureserver$common$api$media$asl$MediaCoverArtService, MediaCoverArtDelegate.MEDIA_COVER_ART_PROPERTIES);
        }
        return this._mediaCoverArtService;
    }

    private BapAudioSdService getBapAudioSdService() {
        if (this._bapAudioSdService == null) {
            this._bapAudioSdService = (BapAudioSdService)this.getCategoryDelegate().getService(this, class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService == null ? (class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService = CoverArt.class$("de.vw.mib.asl.internal.kombipictureserver.common.api.bap.audiosd.BapAudioSdService")) : class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService);
            this.getCategoryDelegate().registerServiceListener(this, class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService == null ? (class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService = CoverArt.class$("de.vw.mib.asl.internal.kombipictureserver.common.api.bap.audiosd.BapAudioSdService")) : class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService, BAP_AUDIO_SD_SERVICES);
        }
        return this._bapAudioSdService;
    }

    public void initialize() {
        INSTANCE = this;
    }

    public void uninitialize() {
        this.getCategoryDelegate().removeDsiServiceResponder(this, class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServerListener == null ? (class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServerListener = CoverArt.class$("org.dsi.ifc.kombipictureserver.DSIKombiPictureServerListener")) : class$org$dsi$ifc$kombipictureserver$DSIKombiPictureServerListener, this.getKombiPictureServerServiceResponder(), null, (DSIBase)this._kombiPictureServer);
        this.getCategoryDelegate().removeServiceListener(this, class$de$vw$mib$asl$internal$kombipictureserver$common$api$media$asl$MediaCoverArtService == null ? (class$de$vw$mib$asl$internal$kombipictureserver$common$api$media$asl$MediaCoverArtService = CoverArt.class$("de.vw.mib.asl.internal.kombipictureserver.common.api.media.asl.MediaCoverArtService")) : class$de$vw$mib$asl$internal$kombipictureserver$common$api$media$asl$MediaCoverArtService, MediaCoverArtDelegate.MEDIA_COVER_ART_PROPERTIES);
        this.getCategoryDelegate().removeServiceListener(this, class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService == null ? (class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService = CoverArt.class$("de.vw.mib.asl.internal.kombipictureserver.common.api.bap.audiosd.BapAudioSdService")) : class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService, BAP_AUDIO_SD_SERVICES);
    }

    private void setBapActiveSourceType(int n) {
        boolean bl;
        boolean bl2 = bl = this._bapActiveSourceType == 0;
        if (this._bapActiveSourceType != n) {
            this._bapActiveSourceType = n;
            if (bl) {
                this._updateCoverArt();
            }
        }
    }

    private int getBapActiveSourceType() {
        return this._bapActiveSourceType;
    }

    private void setMediaCoverArtResourceLocator(ResourceLocator resourceLocator) {
        if (!KombiPictureServerUtil.compareResourceLocator(resourceLocator, this._mediaCoverArtResourceLocator)) {
            this._mediaCoverArtResourceLocator = resourceLocator;
            this._updateCoverArt();
        } else if (this.isWaitingForIsCoverValid && this.getMediaCoverArtService().isCoverValid()) {
            this.isWaitingForIsCoverValid = false;
            this._updateCoverArt();
        }
    }

    public void dsiKombiPictureServerIndicationCoverArt(long l, int n, int n2) {
        boolean bl;
        boolean bl2 = bl = l == 0L && n == 0 && n2 == 255;
        if (bl) {
            this._updateCoverArt(true);
        } else {
            boolean bl3;
            boolean bl4 = bl3 = n == 0 && n2 == this.getBapActiveSourceType() && l == (long)this.getBapAudioSdService().getCurrentStationHandle().getCurrentStationHandleCurrentStationHandle() && this.getMediaCoverArtService().isCoverValid();
            if (bl3) {
                this._transmitCurrentCoverArt();
            } else {
                this._transmitCoverArt(l, n, n2, 2, null);
            }
        }
    }

    private static boolean _isActiveSourceSupportsCoverArt(int n) {
        boolean bl;
        switch (n) {
            case 6:
            case 7:
            case 8:
            case 10:
            case 11:
            case 15:
            case 16:
            case 18:
            case 19:
            case 20:
            case 21:
            case 22: {
                bl = true;
                break;
            }
            default: {
                bl = false;
            }
        }
        return bl;
    }

    private void _transmitCoverArt(ResourceLocator resourceLocator) {
        int n = resourceLocator != null ? 1 : 2;
        this._transmitCoverArt(this.getBapAudioSdService().getCurrentStationHandle().getCurrentStationHandleCurrentStationHandle(), 0, this.getBapActiveSourceType(), n, resourceLocator);
    }

    private void _transmitCoverArt(long l, int n, int n2, int n3, ResourceLocator resourceLocator) {
        this.getKombiPictureServer().responseCoverArt(l, n, n2, n3, resourceLocator);
        this.isWaitingForIsCoverValid = false;
    }

    // RL swap rider: for a local media source (with CarPlay spoof src=11) + an available live cover,
    // ONLY the ResourceLocator is swapped for the cover captured by the tap. handle/sourceType/coverType
    // are inherited by the downstream _transmitCoverArt(rl) from the stock path. No live cover -> exactly stock.
    private void _transmitCurrentCoverArt() {
        int st;
        try { st = this.getBapActiveSourceType(); } catch (Throwable t) { st = -1; }
        if (mst2IsLocalMedia(st)) {
            if (mst2IsNoCover()) {   // provably no artwork -> stock no-cover (null)
                mst2Log("stock-trigger-nocover: src=" + st);
                this._transmitCoverArt((ResourceLocator) null);
                return;
            }
            ResourceLocator rl = mst2BuildLiveRL();
            if (rl != null) {
                mst2Log("stock-trigger-swap: src=" + st);
                this._transmitCoverArt(rl);
                return;
            }
        }
        this._transmitCoverArt(this.getMediaCoverArtService().getCoverArt());
    }

    private void _updateCoverArt(boolean bl) {
        if (CoverArt._isActiveSourceSupportsCoverArt(this.getBapActiveSourceType())) {
            if (this.getMediaCoverArtService().isCoverValid()) {
                this._transmitCurrentCoverArt();
            } else if (bl) {
                this._transmitCoverArt(null);
            }
        } else {
            this._transmitCoverArt(-1L, 0, 0, 0, null);
        }
    }

    private void _updateCoverArt() {
        this._updateCoverArt(false);
    }

    public int getIdentifier() {
        return 1480001;
    }

    public void executeSequence() {
        INSTANCE = this;
        this.getKombiPictureServer();
        this.getMediaCoverArtService();
        this.getBapAudioSdService();
        this.getCategoryDelegate().updateAllServiceListeners(class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService == null ? (class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService = CoverArt.class$("de.vw.mib.asl.internal.kombipictureserver.common.api.bap.audiosd.BapAudioSdService")) : class$de$vw$mib$asl$internal$kombipictureserver$common$api$bap$audiosd$BapAudioSdService);
        // v2: kein "cover-nocover"-Flag-File mehr -- der NOCOVER-State sitzt jetzt im cover-current.path-Sentinel.
        // Tap's boot_cleanup loescht den State im Tap-Boot; der Bridge schreibt ihn ggf. neu bei track-change.
        this.mst2StartCycleTickerOnce();
    }

    public void updateMediaCoverArt(MediaCoverArtService mediaCoverArtService, int n) {
        this.setMediaCoverArtResourceLocator(mediaCoverArtService.getCoverArt());
    }

    public void updateBapAudioSd(BapAudioSdService bapAudioSdService, int n) {
        if (n == 16) {
            this.setBapActiveSourceType(bapAudioSdService.getActiveSource().getActiveSourceSourceType());
        } else if (n == 22 && this.getBapAudioSdService().getCurrentStationHandle().getCurrentStationHandleCurrentStationHandle() != -1 && CoverArt._isActiveSourceSupportsCoverArt(this.getBapActiveSourceType())) {
            this._updateCoverArt();
            if (!this.getMediaCoverArtService().isCoverValid()) {
                this.isWaitingForIsCoverValid = true;
            }
        }
    }

    // ====================== Live cover push (methods) ======================

    private static boolean mst2IsLocalMedia(int st) {
        // Cluster-FW mid whitelist: SD/GENERIC/DVD_CHANGER/USB. For CarPlay the ActiveSource
        // shadow returns src=11 -> true here. CarPlay (22) NOT included (outer-only, not the mid domain).
        return st == 11 || st == 16 || st == 18 || st == 19;
    }

    // Builds the live cover RL from cover-current.path (written atomically by the tap). null = no
    // capture yet -> caller falls back to the stock path. Cache-bust is handled natively (each capture
    // -> new cover-<mtime>.jpg -> new path -> new URL -> cluster re-render).
    private static ResourceLocator mst2BuildLiveRL() {
        String path = mst2ReadLivePath();
        if (path == null) return null;
        if (!path.equals(mst2LastBuiltPath)) {
            mst2LastBuiltPath = path;
            mst2Log("cover-live: path=" + path);
        }
        try { return new ResourceLocator(path); } catch (Throwable t) { return null; }
    }

    // v2 (t64-Architektur): cover-current.path enthaelt entweder den Hardlink-Pfad oder die
    // "NOCOVER"-Sentinel-Zeichenkette (von Tap oder Bridge geschrieben). Liest die ersten 8 Bytes
    // und prueft auf "NOCOVER". File missing / read error / kein NOCOVER-Praefix -> false (= normal live path).
    private static boolean mst2IsNoCover() {
        java.io.FileInputStream fis = null;
        try {
            java.io.File f = new java.io.File(MST2_LIVE_STATE);
            if (!f.exists()) return false;
            fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[16];
            int n = fis.read(buf);
            if (n < 7) return false;
            return buf[0] == 'N' && buf[1] == 'O' && buf[2] == 'C' && buf[3] == 'O'
                && buf[4] == 'V' && buf[5] == 'E' && buf[6] == 'R';
        } catch (Throwable t) {
            return false;
        } finally {
            if (fis != null) try { fis.close(); } catch (Throwable t2) {}
        }
    }

    // self-contained 2s push ticker. Starts exactly once in executeSequence.
    private void mst2StartCycleTickerOnce() {
        synchronized (MST2_TICKER_LOCK) {
            if (mst2TickerStarted) return;
            mst2TickerStarted = true;
        }
        final CoverArt self = this;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(MST2_CYCLE_MS);
                        self.mst2TickPush();
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Throwable th) {
                        // best-effort; never let the ticker die on a transient
                    }
                }
            }
        }, "Mst2CoverPush");
        try { t.setDaemon(true); } catch (Throwable th) {}
        try { t.start(); } catch (Throwable th) { mst2Log("ticker-start-fail: " + th); }
    }

    // From the ticker: pushes the current live cover, only for a local media source (CarPlay spoof src=11)
    // and only on a real path change (+ ~16s keepalive) -> no re-push churn (anti-flicker).
    private void mst2TickPush() {
        int st;
        try { st = this.getBapActiveSourceType(); } catch (Throwable t) { return; }
        if (!mst2IsLocalMedia(st)) {
            if (st != mst2LastTickSrc) {
                mst2Log("tick-skip: src=" + st + " not-pushable");
                mst2LastTickSrc = st;
            }
            return;
        }
        mst2LastTickSrc = st;
        // Track without artwork -> NOCOVER-Sentinel in cover-current.path -> push null (stock no-cover)
        // statt des alten live covers. Transition-gated + keepalive (~16 s) wie der cover-Pfad.
        // v2: dyn. Keepalive -- bei stuck-NOCOVER >5s wechselt das Intervall auf 4s (haeufigere Pushes
        // halten die IC-NOCOVER-Anzeige stabiler).
        if (mst2IsNoCover()) {
            long nowMs = System.currentTimeMillis();
            if (mst2NocoverSince == 0L) mst2NocoverSince = nowMs;
            int interval = (nowMs - mst2NocoverSince) >= MST2_STUCK_THRESHOLD_MS
                           ? MST2_KEEPALIVE_TICKS_STUCK
                           : MST2_KEEPALIVE_TICKS_NORMAL;
            mst2TickCounter++;
            boolean stateChanged = !mst2LastPushedNoCover;
            if (!stateChanged && (mst2TickCounter % interval != 0)) {
                return;   // already pushed no-cover, no keepalive due -> no re-push
            }
            mst2LastPushedNoCover = true;
            mst2LastPushedUrl = null;   // forces re-push as soon as a cover track plays again
            mst2Log("cover-push: null (no-cover) src=" + st);
            this._transmitCoverArt((ResourceLocator) null);
            return;
        }
        mst2NocoverSince = 0L;   // Reset wenn Cover wieder da
        mst2LastPushedNoCover = false;
        ResourceLocator rl = mst2BuildLiveRL();
        if (rl == null) return;   // no live cover -> stock path takes over
        String u = (rl.url != null) ? rl.url : "";
        mst2TickCounter++;
        boolean urlChanged = !u.equals(mst2LastPushedUrl);
        if (!urlChanged && (mst2TickCounter % MST2_KEEPALIVE_TICKS_NORMAL != 0)) {
            return;   // same cover, no keepalive due -> no re-push
        }
        mst2LastPushedUrl = u;
        mst2Log("cover-push: src=" + st);
        this._transmitCoverArt(rl);
    }

    // reads cover-current.path (written atomically by cpvc-cover-tap: "<hardlink>\n") and returns the
    // distinct hardlink path cover-<mtime>.jpg. Fallback cover.jpg, otherwise null. Trims trailing
    // whitespace; validates File.exists.
    private static String mst2ReadLivePath() {
        java.io.FileInputStream fis = null;
        try {
            java.io.File sf = new java.io.File(MST2_LIVE_STATE);
            if (sf.exists()) {
                int len = (int) sf.length();
                if (len > 0 && len < 4096) {
                    byte[] buf = new byte[len];
                    fis = new java.io.FileInputStream(sf);
                    int off = 0, n;
                    while (off < len && (n = fis.read(buf, off, len - off)) > 0) off += n;
                    String p = new String(buf, 0, off);
                    int e = p.length();
                    while (e > 0) {
                        char c = p.charAt(e - 1);
                        if (c == '\n' || c == '\r' || c == ' ' || c == '\t') e--; else break;
                    }
                    p = p.substring(0, e);
                    if (p.length() > 0 && new java.io.File(p).exists()) return p;
                }
            }
        } catch (Throwable t) {
        } finally {
            if (fis != null) try { fis.close(); } catch (Throwable t2) {}
        }
        try { if (new java.io.File(MST2_LIVE_COVER).exists()) return MST2_LIVE_COVER; }
        catch (Throwable t) {}
        return null;
    }

    // self-contained log, gated on the debug-enabled marker (production = no IO after the 1st check).
    private static void mst2Log(String s) {
        if (!mst2DebugChecked) {
            mst2DebugChecked = true;
            try { mst2Debug = new java.io.File(MST2_DEBUG_MARKER).exists(); }
            catch (Throwable t) { mst2Debug = false; }
        }
        if (!mst2Debug) return;
        java.io.FileOutputStream fos = null;
        try {
            java.io.File lf = new java.io.File(MST2_COVER_LOG);
            if (lf.length() > MST2_LOG_MAX) lf.delete();
            fos = new java.io.FileOutputStream(MST2_COVER_LOG, true);
            fos.write(("[" + System.currentTimeMillis() + "] " + s + "\n").getBytes());
        } catch (Throwable t) {
        } finally {
            if (fos != null) try { fos.close(); } catch (Throwable t2) {}
        }
    }
    // ==================== END live cover push (methods) ====================

    static /* synthetic */ Class class$(String string) {
        try {
            return Class.forName(string);
        }
        catch (ClassNotFoundException classNotFoundException) {
            throw new NoClassDefFoundError(classNotFoundException.getMessage());
        }
    }
}
