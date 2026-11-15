#!/bin/sh
# Made by xPaiiN <3
# github.com/xPaiiN/mst2-carplay-vc
# v2.0
case "$0" in */*) CPVC_SCRIPT_DIR="${0%/*}" ;; *) CPVC_SCRIPT_DIR=. ;; esac
[ -f "$CPVC_SCRIPT_DIR/cpvc_lib.sh" ] && . "$CPVC_SCRIPT_DIR/cpvc_lib.sh" || { echo "MST2 CarPlay VC v2.0 - collect logs script"; echo "-----------------------------------------"; echo "Error: the package on the SD card is incomplete (E07)"; echo "cpvc_lib.sh is missing."; echo ""; echo "No logs were saved."; exit 1; }

SEP="-----------------------------------------"
step() { echo "step $1/3 $2..."; }
ok() { echo "          ok"; }
info() { echo "          $1"; }
echo "$CPVC_TITLE - collect logs script"
echo "$SEP"
echo "collecting logs, please wait a few minutes..."
echo ""
SHM="${CPVC_ROOT}/dev/shmem"
RAMSTATE="$RAMDISK/carplayvc"
_MAXBYTES=20971520
_failed=no
_files=0

failed() {
    echo "          error"
    echo ""
    case "$1" in
        L01) echo "Error: no SD card found (L01)"; echo "Insert the SD card with the patch and try again." ;;
        L02) echo "Error: cannot write to the SD card (L02)"; echo "The log folder could not be created." ;;
        L03) echo "Error: logs incomplete (L03)"; echo "Some logs could not be copied to the SD card." ;;
    esac
    echo ""
    if [ "$1" = L03 ]; then echo "The logs on the SD card are incomplete."; else echo "No logs were saved."; fi
    case "$1" in L02|L03) echo "Check that the SD card is not full or locked." ;; esac
    exit 1
}

_cp_if() {
    [ -f "$1" ] || return 0
    _s=$(file_size "$1")
    if [ "$_s" -gt "$_MAXBYTES" ]; then
        echo "skipped=$1 bytes=$_s" >> "$DEST/SKIPPED-too-large.txt" 2>/dev/null
        return 0
    fi
    if cp "$1" "$2" 2>/dev/null && [ -f "$2" ]; then
        _files=$((_files + 1))
    else
        _failed=yes
        echo "copy-failed=$1" >> "$DEST/copy-failed.txt" 2>/dev/null
    fi
    return 0
}

step 1 "finding the sd card"
find_sd || failed L01
_stamp=$(date +%Y%m%d-%H%M%S 2>/dev/null)
: "${_stamp:=manual}"
DEST="$SD_ROOT/cpvc-logs-$CPVC_RELEASE-$_stamp"
[ -e "$DEST" ] && DEST="$DEST-$$"
mkdir -p "$DEST/logs" "$DEST/files" "$DEST/files/ramdisk" "$DEST/files/state" 2>/dev/null || failed L02
info "$SD_ROOT"
ok

step 2 "reading the unit state"
detect_hu
_tested=no
hu_tested && _tested=yes
_act=no; [ -f "$STATE/active" ] && _act=yes
_dbg=no; [ -f "$STATE/debug-enabled" ] && _dbg=yes
_compat=unknown
[ -s "$RAMSTATE/cpvc-compat" ] && read _compat < "$RAMSTATE/cpvc-compat"
{
    echo "release=$CPVC_RELEASE"
    echo "stamp=$_stamp"
    echo "hmi=${HU_VERSION:-unknown} variant=${HU_VARIANT:-unknown} train=${HU_TRAIN:-unknown}"
    echo "region=${HU_REGION:-unknown} platform=${HU_PLATFORM:-unknown} tested=$_tested"
    echo "active=$_act debug=$_dbg compat=$_compat"
} > "$DEST/SUMMARY.txt" 2>/dev/null || _failed=yes
sh "$CPVC_SCRIPT_DIR/cpvc_state.sh" > "$DEST/status.txt" 2>/dev/null
ls -l /proc 2>/dev/null > "$DEST/proc-listing.txt"
_slog_bin="${CPVC_ROOT}/bin/sloginfo"
if [ -x "$_slog_bin" ]; then
    "$_slog_bin" -t 2>/dev/null > "$DEST/logs/sloginfo.txt"
fi
if [ "$_dbg" = yes ]; then info "${HU_VERSION:-unknown} ${HU_VARIANT:-unknown}, debug on"
else info "${HU_VERSION:-unknown} ${HU_VARIANT:-unknown}, debug off"
fi
ok

step 3 "copying files and logs"
_cp_if "$RUN_HMI" "$DEST/files/runHMI.sh"
_cp_if "$RUN_HMI.bak" "$DEST/files/runHMI.sh.bak"
_cp_if "$IAP2" "$DEST/files/iap2.cfg"
_cp_if "$IAP2.bak" "$DEST/files/iap2.cfg.bak"
_cp_if "$IAP2_ETC" "$DEST/files/iap2.cfg.etc"
_cp_if "$JAR_DEST" "$DEST/files/cpvc-bridge.jar"
_cp_if "$COVER_DEST" "$DEST/files/cpvc-cover-elf"
for _m in $MARKERS; do
    _cp_if "$STATE/$_m" "$DEST/files/state/$_m"
done
for _n in cpvc-compat cover-current.path cover-target-size cover.jpg; do
    _cp_if "$RAMSTATE/$_n" "$DEST/files/ramdisk/$_n"
done
_cp_if "${CPVC_ROOT}/pps/services/multimedia/iap2/nowplaying" "$DEST/files/pps-nowplaying.txt"
for _n in "$STATE"/*.log "$SHM"/cpvc-*.log "$SHM"/cpvc-*.log.1 "$RAMSTATE"/cpvc-*.log "$RAMSTATE"/cpvc-*.log.1; do
    [ -f "$_n" ] || continue
    _cp_if "$_n" "$DEST/logs/${_n##*/}"
done
_cp_if "$SHM/iap2_carplay.log" "$DEST/logs/iap2_carplay.log"
sync 2>/dev/null
[ "$_failed" = no ] || failed L03
info "$_files files"
ok

echo ""
echo "Logs saved on sd to: /${DEST##*/}"
echo "Contains sensitive data like VIN, serial numbers, location"
echo "and phone name. Clean it with the provided cpvc_clean_logs.py"
echo "before you create an issue and attach them."
[ "$_dbg" = yes ] || echo "For full logs: install debug version, reproduce the error and collect again."
echo ""
echo "Made by xPaiiN <3"
exit 0
