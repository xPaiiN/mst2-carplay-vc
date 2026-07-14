#!/bin/sh
# cpvc_deactivate.sh -- restore runHMI, remove the jar + cover-bridge, restore iap2.cfg, wipe state.
. "${CPVC_ROOT}/tsd/etc/persistence/esd/scripts/cpvc_common.sh"

echo "mst2-carplay-vc disable"

find_stick   # ok if it fails -- restore falls back to FS .bak / awk-strip
remount_rw

# 0) stop cover-bridge keepalive: remove sentinel FIRST (no restart window), then slay.
[ -f "$SENTINEL" ] && { rm -f "$SENTINEL"; echo " [OK]   /tsd/var/carplayvc/tap-keepalive removed"; }
slay -s SIGTERM cpvc-cover-bridge </dev/null >/dev/null 2>&1
rm -f "$STATE/cpvc-cover-bridge.pid" 2>/dev/null

# 1) restore runHMI (SD -> FS -> awk-strip). If ALL stages fail, keep everything untouched.
if ! restore_runhmi; then
    echo "FAIL: runHMI restore failed; keeping jar and backups untouched."
    echo "      Insert the SD card with backup/carplayvc and retry."
    exit 0
fi
rm -f "$RUN_HMI.bak"
echo " [OK]   runHMI.sh restored (source ${RESTORE_VIA:-?})"

# 2) remove the bridge jar (only reached when runHMI is safely restored)
[ -e "$JAR_DEST" ] && rm -f "$JAR_DEST"
echo " [OK]   jar removed from /tsd/hmi/HMI/jar/"

# 3) remove cover-bridge ELF (no-op if lite never deployed it)
[ -e "$COVER_DEST" ] && { rm -f "$COVER_DEST"; echo " [OK]   cpvc-cover-bridge removed from /tsd/lib/carplayvc/"; }

# 4) revert iap2.cfg nowplaying tokens to =no + strip [destination] (active, no backup read, no freeze)
disable_iap2_nowplaying
disable_iap2_destination
rm -f "$IAP2.bak" 2>/dev/null   # drop any leftover backup from older versions

# 5) wipe runtime state -- guarded rm -rf (path MUST end in /tsd/var/carplayvc)
case "$STATE" in
    */tsd/var/carplayvc) [ -d "$STATE" ] && rm -rf "$STATE" ;;
    *) echo "FAIL: refusing rm -rf on unexpected path: $STATE"; exit 1 ;;
esac
echo " [OK]   runtime state removed (sd backup kept)"

echo ""
echo "MST2 CarPlay VC Patch v1 successfully disabled"
echo "Reboot now to apply"
echo ""
echo "Made by xPaiiN <3"
echo "https://github.com/xPaiiN/mst2-carplay-vc"
exit 0
