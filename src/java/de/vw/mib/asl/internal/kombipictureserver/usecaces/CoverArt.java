/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v2.0
 */
package de.vw.mib.asl.internal.kombipictureserver.usecaces;

import de.vw.mib.asl.internal.kombipictureserver.common.services.KombiPictureServerServicesProvider;
import de.vw.mib.genericevents.ThreadSwitchingTarget;
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

    public static volatile CoverArt INSTANCE = null;

    static final String MST2_ROOT = System.getProperty("cpvc.test.root", "");

    private static final String MST2_LIVE_STATE = MST2_ROOT + "/ramdisk/carplayvc/cover-current.path";

    private static final String MST2_LIVE_COVER = MST2_ROOT + "/ramdisk/carplayvc/cover.jpg";

    private static final long MST2_CYCLE_MS = 2000L;

    private static final String MST2_DEBUG_MARKER = MST2_ROOT + "/tsd/var/carplayvc/debug-enabled";
    private static final String MST2_COVER_LOG = MST2_ROOT + "/tsd/var/carplayvc/cpvc-cover.log";
    private static final long MST2_LOG_MAX = 1048576L;

    private static final Object MST2_TICKER_LOCK = new Object();
    private static volatile boolean mst2TickerStarted = false;
    private static volatile boolean mst2DebugChecked = false;
    private static volatile boolean mst2Debug = false;
    private static volatile int mst2LastTickSrc = -999;
    private static volatile String mst2LastBuiltPath = null;
    private static volatile String mst2LastPushedUrl = null;
    private static volatile boolean mst2LastPushedNoCover = false;
    private static volatile int mst2TicksSincePush = 0;

    private static final int  MST2_KEEPALIVE_TICKS = 10;
    private static volatile ThreadSwitchingTarget mst2Asl = null;
    private static boolean mst2AslMissing = false;
    public static volatile boolean mst2CarplayAudioActive = false;
    public static volatile boolean mst2ClearPending = false;

    public static volatile boolean mst2ForcePush = false;

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
            if (mst2IsCarplaySourceType(n)) {
                mst2ForcePush = true;
            }
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
        boolean local = mst2IsLocalMedia(this.getBapActiveSourceType());
        mst2Log("ind-pull: handle=" + l + " n=" + n + " src=" + n2 + " local=" + local);
        if (local) {
            mst2ForcePush = true;
        }
        if (bl) {
            this._updateCoverArt(true);
        } else {
            boolean bl3;
            boolean bl4 = bl3 = n == 0 && n2 == this.getBapActiveSourceType() && l == (long)this.getBapAudioSdService().getCurrentStationHandle().getCurrentStationHandleCurrentStationHandle() && this.getMediaCoverArtService().isCoverValid();
            if (bl3) {
                this._transmitCurrentCoverArt();
            } else if (!local) {
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

    private void _transmitCurrentCoverArt() {
        int st;
        try { st = this.getBapActiveSourceType(); } catch (Throwable t) { st = -1; }
        if (mst2IsLocalMedia(st)) {
            if (mst2IsNoCover()) {
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
                if (mst2IsLocalMedia(this.getBapActiveSourceType())) {

                    mst2ForcePush = true;
                } else {
                    this._transmitCoverArt(null);
                }
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

        try {
            mst2Asl = KombiPictureServerServicesProvider.getKombiPictureServerServices().getThreadSwitchingTarget();
        } catch (Throwable t) {
            mst2Asl = null;
        }
        this.mst2StartCycleTickerOnce();
    }

    public void updateMediaCoverArt(MediaCoverArtService mediaCoverArtService, int n) {
        this.setMediaCoverArtResourceLocator(mediaCoverArtService.getCoverArt());
    }

    public void updateBapAudioSd(BapAudioSdService bapAudioSdService, int n) {
        if (n == 16) {
            this.setBapActiveSourceType(bapAudioSdService.getActiveSource().getActiveSourceSourceType());
        } else if (n == 22 && this.getBapAudioSdService().getCurrentStationHandle().getCurrentStationHandleCurrentStationHandle() != -1 && CoverArt._isActiveSourceSupportsCoverArt(this.getBapActiveSourceType())) {
            if (mst2IsLocalMedia(this.getBapActiveSourceType())) {
                mst2ForcePush = true;
            }
            this._updateCoverArt();
            if (!this.getMediaCoverArtService().isCoverValid()) {
                this.isWaitingForIsCoverValid = true;
            }
        }
    }

    private static boolean mst2IsCarplaySourceType(int st) {
        return st == 11 || st == 16 || st == 18 || st == 19;
    }

    private static boolean mst2IsLocalMedia(int st) {

        if (!mst2CarplayAudioActive) return false;
        return mst2IsCarplaySourceType(st);
    }

    private static ResourceLocator mst2BuildLiveRL() {
        String path = mst2ReadLivePath();
        if (path == null) return null;
        if (!path.equals(mst2LastBuiltPath)) {
            mst2LastBuiltPath = path;
            mst2Log("cover-live: path=" + path);
        }
        try { return new ResourceLocator(path); } catch (Throwable t) { return null; }
    }

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
                        mst2Log("tick-fail: " + th);
                    }
                }
            }
        }, "cpvc-w02");
        try { t.setDaemon(true); } catch (Throwable th) {}
        try { t.start(); } catch (Throwable th) { mst2Log("ticker-start-fail: " + th); }
    }

    private void mst2TickPush() {
        if (mst2ClearPending) {
            mst2ClearPending = false;
            try { this.mst2Transmit((ResourceLocator) null); } catch (Throwable th) {}
            mst2LastPushedUrl = null;
            mst2LastPushedNoCover = true;
        }
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

        boolean force = mst2ForcePush;
        if (force) mst2ForcePush = false;
        mst2TicksSincePush++;
        boolean keepalive = mst2TicksSincePush >= MST2_KEEPALIVE_TICKS;

        if (mst2IsNoCover()) {
            boolean stateChanged = !mst2LastPushedNoCover;
            if (!stateChanged && !force && !keepalive) {
                return;
            }
            mst2LastPushedNoCover = true;
            mst2LastPushedUrl = null;
            mst2TicksSincePush = 0;
            if (force) mst2Log("force-push: null (no-cover) src=" + st);
            else if (stateChanged) mst2Log("cover-push: null (no-cover) src=" + st);
            else mst2Log("keepalive-push: null (no-cover) src=" + st);
            this.mst2Transmit((ResourceLocator) null);
            return;
        }
        mst2LastPushedNoCover = false;
        ResourceLocator rl = mst2BuildLiveRL();
        if (rl == null) {
            if (force) mst2ForcePush = true;
            return;
        }
        String u = (rl.url != null) ? rl.url : "";
        boolean urlChanged = !u.equals(mst2LastPushedUrl);
        if (!urlChanged && !force && !keepalive) {
            return;
        }
        mst2LastPushedUrl = u;
        mst2TicksSincePush = 0;
        if (force) mst2Log("force-push: src=" + st);
        else if (urlChanged) mst2Log("cover-push: src=" + st);
        else mst2Log("keepalive-push: src=" + st);
        this.mst2Transmit(rl);
    }

    public static void mst2AudioChanged(boolean active) {
        if (active == mst2CarplayAudioActive) return;
        mst2Log("audio: carplay=" + (active ? 1 : 0));
        mst2CarplayAudioActive = active;
        if (!active) {
            mst2ClearPending = true;
        } else {
            mst2ForcePush = true;
        }
    }

    // DSI calls belong to the ASL task; the file reads stay on the ticker.
    private void mst2Transmit(final ResourceLocator rl) {
        ThreadSwitchingTarget asl = mst2AslTarget();
        final CoverArt self = this;
        Runnable push = new Runnable() {
            public void run() {
                try { self._transmitCoverArt(rl); } catch (Throwable t) { mst2Log("transmit-fail: " + t); }
            }
        };
        try {
            if (asl != null && asl.isThreadChangeNecessary()) asl.enqueue(push);
            else push.run();
        } catch (Throwable t) {
            mst2Log("transmit-fail: " + t);
            mst2ForcePush = true;
        }
    }

    private ThreadSwitchingTarget mst2AslTarget() {
        ThreadSwitchingTarget asl = mst2Asl;
        if (asl != null) return asl;
        try {
            asl = KombiPictureServerServicesProvider.getKombiPictureServerServices().getThreadSwitchingTarget();
            mst2Asl = asl;
        } catch (Throwable t) {
        }
        if (asl == null && !mst2AslMissing) {
            mst2AslMissing = true;
            mst2Log("asl-missing");
        }
        return asl;
    }

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

    static  Class class$(String string) {
        try {
            return Class.forName(string);
        }
        catch (ClassNotFoundException classNotFoundException) {
            throw new NoClassDefFoundError(classNotFoundException.getMessage());
        }
    }
}
