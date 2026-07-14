#!/bin/sh
# collect_logs.sh -- MANUAL log collector. GATED: if debug logging was never enabled (no
# debug-enabled sentinel) there are no fresh logs to collect -> error out instead of copying an
# empty/stale set. Enable with 'enable_patch.sh --debug', reboot, reproduce, then collect.
# NEVER cat /pps/* (freezes the MIB2 console) -- PPS snapshots are taken via cp only.
# Whitelist-safe self-dir resolution (parameter expansion only -- no external dirname/basename).
case "$0" in
    */*) CPVC_SELFDIR="${0%/*}" ;;
    *)   CPVC_SELFDIR="." ;;
esac
. "$CPVC_SELFDIR/cpvc_common.sh"

if [ ! -f "$STATE/debug-enabled" ]; then
    echo "FAIL: --debug is not enabled - nothing to copy."
    echo "Activate with 'enable_patch.sh --debug'"
    echo "Reboot, reproduce errors, then collect logs."
    echo ""
    exit 1
fi

find_stick || { echo "FAIL: toolbox SD card not found. Insert the SD card in any slot and retry."; exit 1; }
TS=$(date +%Y%m%d-%H%M%S 2>/dev/null)
: "${TS:=manual}"
DEST="$STICK/cpvc-logs-$TS"
mkdir -p "$DEST" 2>/dev/null || { echo "FAIL: cannot create $DEST"; exit 1; }

# logs + runtime state (each [ -f ]-gated -> absent files are simply skipped, e.g. lite has no cover)
for f in bridge.log cpvc-cover-bridge.log cpvc-cover-bridge.pid \
         cover-current.path cover-target-size cover.jpg \
         cpvc-variant debug-enabled tap-keepalive active; do
    [ -f "$STATE/$f" ] && cp "$STATE/$f" "$DEST/$f" 2>/dev/null
done

# system files (read-only copies)
[ -f "$IAP2" ]     && cp "$IAP2"     "$DEST/iap2.cfg" 2>/dev/null
[ -f "$IAP2.bak" ] && cp "$IAP2.bak" "$DEST/iap2.cfg.bak" 2>/dev/null
[ -f "$RUN_HMI" ]  && cp "$RUN_HMI"  "$DEST/runHMI.sh" 2>/dev/null
# PPS snapshot via cp (NEVER cat /pps/* -- console freeze)
cp "${CPVC_ROOT}/pps/services/multimedia/iap2/nowplaying" "$DEST/pps-nowplaying.snap" 2>/dev/null
[ -e "${CPVC_ROOT}/dev/shmem/iap2_carplay.log" ] && cp "${CPVC_ROOT}/dev/shmem/iap2_carplay.log" "$DEST/iap2_carplay.log" 2>/dev/null

echo "debug: logs copied to $DEST"
exit 0
