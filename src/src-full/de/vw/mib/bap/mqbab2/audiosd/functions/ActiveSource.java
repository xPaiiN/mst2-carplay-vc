/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v1
 */
package de.vw.mib.bap.mqbab2.audiosd.functions;

import de.vw.mib.bap.array.timer.Timer;
import de.vw.mib.bap.array.timer.TimerNotifier;
import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.audiosd.api.ASLAudioSDConstants;
import de.vw.mib.bap.mqbab2.common.api.exbox.ExboxService;
import de.vw.mib.bap.mqbab2.common.api.exbox.ExboxServiceListener;
import de.vw.mib.bap.mqbab2.common.api.media.MediaService;
import de.vw.mib.bap.mqbab2.common.api.media.MediaServiceListener;
import de.vw.mib.bap.mqbab2.common.api.radio.RadioService;
import de.vw.mib.bap.mqbab2.common.api.radio.RadioServiceListener;
import de.vw.mib.bap.mqbab2.common.api.sound.SoundService;
import de.vw.mib.bap.mqbab2.common.api.sound.SoundServiceListener;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.common.api.system.SystemService;
import de.vw.mib.bap.mqbab2.common.api.system.SystemServiceListener;
import de.vw.mib.bap.mqbab2.common.arrays.FsgArrayListComplete;
import de.vw.mib.bap.mqbab2.generated.audiosd.serializer.ActiveSource_Status;
import de.vw.mib.bap.mqbab2.generated.audiosd.serializer.SourceList_Data;

public class ActiveSource
extends Function
implements TimerNotifier,
Property,
ASLAudioSDConstants,
RadioServiceListener,
MediaServiceListener,
SystemServiceListener,
SoundServiceListener,
ExboxServiceListener {
    private int currentSourceListReference = 0;
    private int currentPresetIndex = -1;
    private boolean currentStationIndicesWereUpdated = false;
    private FsgArrayListComplete fullSourceList = null;
    private static final int TYPE_OF_NUMBER_OFFSET = 1;
    private static final int PRESET_INDEX_INVALID = -1;
    private boolean presetListAvailable = false;
    private boolean receptionListAvailable = false;
    private int lastAudioComponent = 1;
    private boolean _mediaBrowserActiveComponent;
    private static final int TIMER_IGNORE_AUDIO_SUPPRESSION_TIME = 2000;
    private static final int TIMER_WAIT_FOR_CI_TRANSMISSION_TIME = 150;
    private Timer ignoreAudioSuppressionTimer = null;
    private Timer waitForCITransmissionTimer = null;
    private static final int TIMER_ACTION_NOTHING = 0;
    private static final int TIMER_ACTION_STOP_IGNORE_ENTERTAINMENT_SUPRESSION = 1;
    private static final int TIMER_ACTION_TRANSMIT_NEW_VALUE_AFTER_COMPONENT_CHANGE = 2;
    protected final int BAP_STAGE_UPDATE_ID_CURRENT_STATION_INDICES;
    protected static final int[] RADIO_LISTENER_IDS = new int[]{1341, 1317, 1324, 2486};
    protected static final int[] MEDIA_LISTENER_IDS = new int[]{810016, 810013, 8, 6, 58};
    protected static final int[] SYSTEM_LISTENER_IDS = new int[]{335};
    protected static final int[] SOUND_LISTENER_IDS = new int[]{1470};
    protected static final int[] EXBOX_LISTENER_IDS = new int[]{-2147478648};
    static Class class$de$vw$mib$bap$mqbab2$generated$audiosd$serializer$ActiveSource_Status;

    // For CarPlay (audioComponent==6) the source is spoofed as a full USB media session:
    // sourceType=19 (USB) so the cluster picks the mid-cover-capable local widget, number=0 for
    // the plain-USB cluster source icon (no digit). For any other source: byte-stock (no override).
    private static final int MST2_SPOOF_SOURCE_TYPE = 19;
    private static final int MST2_SPOOF_NUMBER = 0;

    public ActiveSource() {
        this.BAP_STAGE_UPDATE_ID_CURRENT_STATION_INDICES = 0;
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        this.getRadioService().addRadioServiceListener(this, RADIO_LISTENER_IDS);
        this.getMediaService().addMediaServiceListener(this, MEDIA_LISTENER_IDS);
        this.getSystemService().addSystemServiceListener(this, SYSTEM_LISTENER_IDS);
        this.getSoundService().addSoundServiceListener(this, SOUND_LISTENER_IDS);
        this.getExboxService().addExboxServiceListener(this, EXBOX_LISTENER_IDS);
        this.ignoreAudioSuppressionTimer = this.getTimerService().createTimer(this, 2000);
        this.waitForCITransmissionTimer = this.getTimerService().createTimer(this, 150);
        ActiveSource_Status activeSource_Status = this.computeActiveSourceStatus();
        this.setActiveSourceListReference(new Long(activeSource_Status.sourceList_Reference));
        return activeSource_Status;
    }

    protected void setActiveSourceListReference(Long l) {
        int[] nArray = new int[]{34, 38};
        this.context.updateStages(this, nArray, l);
    }

    protected void setNewActiveSourceStatus(ActiveSource_Status activeSource_Status) {
        int[] nArray = new int[]{23};
        this.context.updateStages(this, nArray, activeSource_Status);
    }

    protected void setReportedSourceListReference(Integer n) {
        int[] nArray = new int[]{17, 45, 34};
        this.context.updateStages(this, nArray, n);
    }

    protected final ActiveSource_Status dequeueBAPEntity() {
        return (ActiveSource_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$audiosd$serializer$ActiveSource_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$audiosd$serializer$ActiveSource_Status = ActiveSource.class$("de.vw.mib.bap.mqbab2.generated.audiosd.serializer.ActiveSource_Status")) : class$de$vw$mib$bap$mqbab2$generated$audiosd$serializer$ActiveSource_Status);
    }

    public int getFunctionId() {
        return 16;
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
        switch (bAPStage.getFunctionId()) {
            case 32: {
                this.setFullSourceList((FsgArrayListComplete)object);
                break;
            }
            case 33: {
                this.setFullPresetList((FsgArrayListComplete)object);
                break;
            }
            case 23: {
                this.setFullReceptionList((FsgArrayListComplete)object);
                break;
            }
            case 38: {
                this.setMediaBrowserActiveComponent((Boolean)object);
                break;
            }
        }
    }

    private int findSourceListReferenceForTv() {
        int n = 0;
        if (this.fullSourceList != null) {
            n = this.fullSourceList.getBapPosID(-6L);
        }
        return n;
    }

    private int findFirstUSBSourceListReference() {
        int n = 0;
        if (this.fullSourceList != null) {
            int n2 = this.fullSourceList.getBapPosID(-3L);
            while (n2 != 0) {
                SourceList_Data sourceList_Data = (SourceList_Data)this.fullSourceList.getElement(n2);
                if (sourceList_Data.sourceType == 19) {
                    n = sourceList_Data.getPos();
                    break;
                }
                n2 = this.fullSourceList.nextBapPosID(n2);
            }
        }
        return n;
    }

    private int findSourceListReference() {
        int n;
        block0 : switch (this.getSoundService().getCurrentAudioComponent()) {
            case 1: {
                if (this.fullSourceList != null) {
                    switch (this.getRadioService().getCurrentStationBand()) {
                        case 1: {
                            n = this.fullSourceList.getBapPosID(-3L);
                            break block0;
                        }
                        case 0: {
                            n = this.fullSourceList.getBapPosID(-4L);
                            break block0;
                        }
                        case 2: {
                            n = this.fullSourceList.getBapPosID(-2L);
                            break block0;
                        }
                        case 3: {
                            n = this.fullSourceList.getBapPosID(-5L);
                            break block0;
                        }
                    }
                    n = 0;
                    break;
                }
                n = 0;
                break;
            }
            case 2: {
                if (this.fullSourceList != null) {
                    int n2 = this.getMediaService().getCurrentAudioSource();
                    n = this.fullSourceList.getBapPosID(n2);
                    break;
                }
                n = 0;
                break;
            }
            case 3: {
                n = this.findSourceListReferenceForTv();
                break;
            }
            case 4: {
                n = 0;
                break;
            }
            case 5: {
                n = this.findSourceListReferenceForTv();
                break;
            }
            case 6: {
                n = this.findFirstUSBSourceListReference();
                break;
            }
            default: {
                if (this.currentSourceListReference != 0) {
                    n = this.currentSourceListReference;
                    if (this.ignoreAudioSuppressionTimer.isRunning()) break;
                    this.ignoreAudioSuppressionTimer.retrigger(new Integer(1));
                    break;
                }
                n = 0;
            }
        }
        return n;
    }

    private void setSourceTypeAndReference(ActiveSource_Status activeSource_Status, int n) {
        SourceList_Data sourceList_Data = n == 0 || this.fullSourceList == null ? null : (SourceList_Data)this.fullSourceList.getElement(n);
        if (sourceList_Data != null) {
            activeSource_Status.sourceType = sourceList_Data.sourceType;
            activeSource_Status.sourceList_Reference = sourceList_Data.getPos();
            activeSource_Status.number = this.getSoundService().getCurrentAudioComponent() == 6 ? 0 : sourceList_Data.instance_Id;
        } else if (this.getSoundService().getCurrentAudioComponent() == 4) {
            this.setSourceTypeAndReferenceForExbox(activeSource_Status);
        } else {
            activeSource_Status.sourceType = 0;
            activeSource_Status.sourceList_Reference = 0;
        }
    }

    private void setSourceTypeAndReferenceForExbox(ActiveSource_Status activeSource_Status) {
        int n = this.getExboxService().getExboxActiveSource();
        switch (n) {
            case 40: {
                activeSource_Status.sourceType = 16;
                break;
            }
            case 22: {
                activeSource_Status.sourceType = 22;
                break;
            }
            case 21: {
                activeSource_Status.sourceType = 21;
                break;
            }
            default: {
                activeSource_Status.sourceType = 0;
            }
        }
        activeSource_Status.sourceList_Reference = 0;
    }

    private void setTypeOfNumberAndNumber(ActiveSource_Status activeSource_Status) {
        switch (activeSource_Status.sourceType) {
            case 18: {
                activeSource_Status.typeOfNumber = 5;
                activeSource_Status.number = this.getMediaService().getCurrentTrackInfo().getCurrentTrackNumber();
                break;
            }
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 9: {
                if (this.presetListAvailable) {
                    activeSource_Status.number = this.currentPresetIndex + 1;
                    activeSource_Status.typeOfNumber = 2;
                    break;
                }
                activeSource_Status.number = 0;
                activeSource_Status.typeOfNumber = 0;
                break;
            }
            default: {
                activeSource_Status.typeOfNumber = 0;
            }
        }
        if (activeSource_Status.number == 0) {
            activeSource_Status.typeOfNumber = 0;
        }
    }

    private void setListAvailable(ActiveSource_Status activeSource_Status) {
        boolean bl = this.getMediaService().isBapMediaBrowserAvailable();
        activeSource_Status.listAvailable.presetListAvailable = this.presetListAvailable;
        activeSource_Status.listAvailable.receptionListAvailable = this.receptionListAvailable;
        activeSource_Status.listAvailable.mediaBrowserListAvailable = bl;
    }

    private void setListStateForMediaBrowser(ActiveSource_Status activeSource_Status) {
        switch (this.getMediaService().getBapMediaBrowserState()) {
            case 0:
            case 2: {
                activeSource_Status.list_State = 1;
                break;
            }
            case 3:
            case 4: {
                activeSource_Status.list_State = 3;
                break;
            }
            default: {
                activeSource_Status.list_State = 0;
            }
        }
    }

    private void setListState(ActiveSource_Status activeSource_Status) {
        if (this.getMediaBrowserActiveComponent()) {
            this.setListStateForMediaBrowser(activeSource_Status);
        } else {
            switch (this.getSoundService().getCurrentAudioComponent()) {
                case 1: {
                    int n = this.getRadioService().getCurrentStationBand();
                    if (n == 2 && this.getRadioService().getDABRadioListState() == 1) {
                        activeSource_Status.list_State = 4;
                        break;
                    }
                    if (n == 0 && this.getRadioService().getAMRadioListState() == 1) {
                        activeSource_Status.list_State = 4;
                        break;
                    }
                    activeSource_Status.list_State = 3;
                    break;
                }
                case 2: {
                    this.setListStateForMediaBrowser(activeSource_Status);
                    break;
                }
                case 3:
                case 4:
                case 5:
                case 6: {
                    activeSource_Status.list_State = 3;
                    break;
                }
                default: {
                    activeSource_Status.list_State = 0;
                }
            }
        }
    }

    private void sendActiveSourceStatus(ActiveSource_Status activeSource_Status) {
        this.getDelegate().getPropertyListener(this).statusProperty(activeSource_Status, this);
        if (this.currentSourceListReference != activeSource_Status.sourceList_Reference) {
            this.currentSourceListReference = activeSource_Status.sourceList_Reference;
            this.setActiveSourceListReference(new Long(this.currentSourceListReference));
            this.setNewActiveSourceStatus(activeSource_Status);
        }
    }

    public BAPEntity lastReportedData() {
        return null;
    }

    public void getProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void requestAcknowledge() {
        this.setReportedSourceListReference(new Integer(this.currentSourceListReference));
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
    }

    public void errorAcknowledge() {
    }

    public void initialize(boolean bl) {
    }

    public void uninitialize() {
        this.ignoreAudioSuppressionTimer.stop();
        this.waitForCITransmissionTimer.stop();
        this.getRadioService().removeRadioServiceListener(this, RADIO_LISTENER_IDS);
        this.getMediaService().removeMediaServiceListener(this, MEDIA_LISTENER_IDS);
        this.getSystemService().removeSystemServiceListener(this, SYSTEM_LISTENER_IDS);
        this.getSoundService().removeSoundServiceListener(this, SOUND_LISTENER_IDS);
        this.getExboxService().removeExboxServiceListener(this, EXBOX_LISTENER_IDS);
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void ackProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void process(int n) {
        if (n == 0) {
            this.currentStationIndicesWereUpdated = true;
        }
        if (!this.waitForCITransmissionTimer.isRunning()) {
            if (this.getSoundService().getCurrentAudioComponent() != this.lastAudioComponent) {
                this.waitForCITransmissionTimer.retrigger(new Integer(2));
            } else {
                if (n == 0 || this.currentStationIndicesWereUpdated) {
                    this.currentPresetIndex = this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesActivePresetIndex();
                    this.currentStationIndicesWereUpdated = false;
                } else if (this.currentSourceListReference != 0 && this.currentSourceListReference != this.findSourceListReference() && n != 0) {
                    this.currentPresetIndex = -1;
                }
                this.sendActiveSourceStatus(this.computeActiveSourceStatus());
            }
        }
    }

    private ActiveSource_Status computeActiveSourceStatus() {
        ActiveSource_Status activeSource_Status = this.dequeueBAPEntity();
        this.setSourceTypeAndReference(activeSource_Status, this.findSourceListReference());
        this.setTypeOfNumberAndNumber(activeSource_Status);
        this.setListAvailable(activeSource_Status);
        this.setListState(activeSource_Status);
        // Spoof CarPlay (component 6) as a full USB media session: sourceType=19 (mid-cover-
        // capable local widget), number=0 (plain-USB icon), media fields. The matching handle (fsgHandle=1)
        // is supplied by the CurrentStationHandle shadow. For any other source: byte-stock (no override).
        if (this.getSoundService().getCurrentAudioComponent() == 6) {
            activeSource_Status.sourceType = MST2_SPOOF_SOURCE_TYPE;
            activeSource_Status.sourceList_Reference = 0;
            activeSource_Status.number = MST2_SPOOF_NUMBER;
            activeSource_Status.typeOfNumber = 5;
            activeSource_Status.listAvailable.mediaBrowserListAvailable = true;
            activeSource_Status.list_State = 3;
        }
        return activeSource_Status;
    }

    protected void setFullPresetList(FsgArrayListComplete fsgArrayListComplete) {
        this.presetListAvailable = fsgArrayListComplete.getSize() != 0;
        this.process(-1);
    }

    protected void setFullSourceList(FsgArrayListComplete fsgArrayListComplete) {
        boolean bl = this.fullSourceList != null;
        this.fullSourceList = fsgArrayListComplete;
        if (bl) {
            this.process(-1);
        }
    }

    protected void setFullReceptionList(FsgArrayListComplete fsgArrayListComplete) {
        boolean bl;
        boolean bl2 = this.ignoreAudioSuppressionTimer != null;
        boolean bl3 = bl = fsgArrayListComplete.getSize() != 0;
        if (bl2 && this.receptionListAvailable != bl) {
            this.receptionListAvailable = bl;
            this.process(-1);
        }
    }

    protected void setMediaBrowserActiveComponent(Boolean bl) {
        boolean bl2 = bl.booleanValue();
        if (this._mediaBrowserActiveComponent != bl2) {
            this._mediaBrowserActiveComponent = bl2;
            this.process(-1);
        }
    }

    private boolean getMediaBrowserActiveComponent() {
        return this._mediaBrowserActiveComponent;
    }

    public void processHMIEvent(int n) {
    }

    public void updateSoundData(SoundService soundService, int n) {
        this.process(-1);
    }

    public void updateSystemData(SystemService systemService, int n) {
        this.process(-1);
    }

    public void updateMediaData(MediaService mediaService, int n) {
        this.process(-1);
    }

    public void updateRadioData(RadioService radioService, int n) {
        if (n == 2486) {
            this.process(0);
        } else {
            this.process(-1);
        }
    }

    public void updateExboxData(ExboxService exboxService, int n) {
        this.process(-1);
    }

    public void timerFired(Timer timer) {
        int n = ((Integer)timer.getUserInfo()).intValue();
        switch (n) {
            case 1: {
                if (this.currentSourceListReference == 0 || this.getSoundService().getCurrentAudioComponent() != 0) break;
                this.process(-1);
                break;
            }
            case 2: {
                this.lastAudioComponent = this.getSoundService().getCurrentAudioComponent();
                this.process(-1);
                break;
            }
        }
    }

    static /* synthetic */ Class class$(String string) {
        try {
            return Class.forName(string);
        }
        catch (ClassNotFoundException classNotFoundException) {
            NoClassDefFoundError err = new NoClassDefFoundError();
            err.initCause(classNotFoundException);
            throw err;
        }
    }
}
