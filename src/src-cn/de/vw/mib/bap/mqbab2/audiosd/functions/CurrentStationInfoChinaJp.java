/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v1
 */
package de.vw.mib.bap.mqbab2.audiosd.functions;

// CN firmware instantiates this language variant instead of the stock CurrentStationInfo (the
// China binding factory builds ChinaJp; JXE pre-linking would otherwise bind it to the stock base).
// Extending OUR shadowed CurrentStationInfo makes it inherit INSTANCE / process /
// setStationInfoForMirrorLink -- and therefore the CarPlay render path -- from our patched base.
// Radio methods are intentionally NOT overridden (CarPlay-only concern; base radio behaviour is
// preserved unchanged via inheritance). This one class is the entire CN delta on the Java side;
// it is compiled together with each variant's source, so it inherits that variant's render block.
public class CurrentStationInfoChinaJp extends CurrentStationInfo {
    public CurrentStationInfoChinaJp() {
        super();
    }
}
