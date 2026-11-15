#!/bin/sh
# Made by xPaiiN <3
# github.com/xPaiiN/mst2-carplay-vc
# v2.0
case "$0" in */*) CPVC_SCRIPT_DIR="${0%/*}" ;; *) CPVC_SCRIPT_DIR=. ;; esac
exec 2>/dev/null
[ -f "$CPVC_SCRIPT_DIR/cpvc_lib.sh" ] && . "$CPVC_SCRIPT_DIR/cpvc_lib.sh" || { echo "MST2 CarPlay VC v2.0 - uninstall script"; echo "-----------------------------------------"; echo "Error: the package on the SD card is incomplete (E07)"; echo "cpvc_lib.sh is missing."; echo ""; echo "No changes were made."; exit 1; }

DEBUG=no
[ -f "$STATE/debug-enabled" ] && DEBUG=yes
_mutating=no
SEP="-----------------------------------------"
step() { echo "step $1/4 $2..."; }
ok() { echo "          ok"; }
info() { echo "          $1"; }
echo "$CPVC_TITLE - uninstall script"
echo "$SEP"

fail() {
    _code=$1 _why=$2
    _restore_failed=no
    debug_log "FAIL uninstall: $_why"
    rm -f "$RUN_HMI_TMP" "$RUN_HMI_TMP.clean"
    if [ "$_mutating" = yes ]; then
        restore_snapshot >/dev/null 2>&1 || _restore_failed=yes
        [ "$_restore_failed" = no ] && cleanup_snapshot
    fi
    echo "          error"
    echo ""
    case "$_code" in
        D01) echo "Error: an older patch is installed (D01)"; echo "Remove it with its own menu first." ;;
        D02) echo "Error: mst2-carplay-vc v1 is installed (D02)"; echo "Remove it in the v1 menu with 'Disable + Cleanup'." ;;
        D04) echo "Error: no valid backups found (D04)"; echo "The original files to restore are missing." ;;
        D05) echo "Error: iap2.cfg state unknown (D05)"; echo "It is unclear how iap2.cfg looked before." ;;
        D06) echo "Error: cannot write to the unit (D06)"; echo "The /tsd partition could not be opened for writing." ;;
        D07) echo "Error: cannot write to the unit (D07)"; echo "A working copy could not be made." ;;
        D08) echo "Error: runHMI.sh could not be restored (D08)"; echo "The original runHMI.sh could not be written back." ;;
        D09) echo "Error: iap2.cfg could not be restored (D09)"; echo "The original iap2.cfg could not be written back." ;;
        D10) echo "Error: patch files could not be removed (D10)"; echo "Some patch files are still on the unit." ;;
        D11) echo "Error: state files could not be removed (D11)"; echo "Some state files are still on the unit." ;;
    esac
    echo ""
    if [ "$_restore_failed" = yes ]; then
        echo "Undo incomplete, the unit is partly patched."
        echo "Do not reboot the HU yet."
        echo "Run the uninstall script again and verify."
        echo "If it fails again, run Collect Logs before you reboot."
    else
        echo "No changes were made."
    fi
    exit 1
}

save_file() {
    rm -f "$2" || return 1
    [ -f "$1" ] || return 0
    if [ -s "$1" ]; then atomic_copy "$1" "$2"; else touch "$2" 2>/dev/null; fi
}

restore_file() {
    if [ -s "$2" ]; then atomic_copy "$2" "$1"
    elif [ -f "$2" ]; then rm -f "$1" && touch "$1"
    else rm -f "$1"
    fi
}

snapshot() {
    mkdir -p "$STATE/.tmp" || return 1
    save_file "$RUN_HMI" "$STATE/.tmp/disable.runHMI" || return 1
    save_file "$IAP2" "$STATE/.tmp/disable.iap2" || return 1
    for _f in $PAYLOAD_FILES; do save_file "$_f" "$STATE/.tmp/disable.${_f##*/}" || return 1; done
    for _m in $MARKERS; do save_file "$STATE/$_m" "$STATE/.tmp/disable.marker.$_m" || return 1; done
}

restore_snapshot() {
    _rc=0
    restore_file "$RUN_HMI" "$STATE/.tmp/disable.runHMI" || _rc=1
    restore_file "$IAP2" "$STATE/.tmp/disable.iap2" || _rc=1
    for _f in $PAYLOAD_FILES; do restore_file "$_f" "$STATE/.tmp/disable.${_f##*/}" || _rc=1; done
    for _m in $MARKERS; do restore_file "$STATE/$_m" "$STATE/.tmp/disable.marker.$_m" || _rc=1; done
    return $_rc
}

cleanup_snapshot() {
    rm -f "$STATE/.tmp/disable.runHMI" "$STATE/.tmp/disable.iap2"
    for _f in $PAYLOAD_FILES; do rm -f "$STATE/.tmp/disable.${_f##*/}"; done
    for _m in $MARKERS; do rm -f "$STATE/.tmp/disable.marker.$_m"; done
}

clean_ram() {
    rm -rf "$RAMDISK/carplayvc" 2>/dev/null
    for _n in "${CPVC_ROOT}/dev/shmem"/cpvc-*.log "${CPVC_ROOT}/dev/shmem"/cpvc-*.log.*; do
        rm -f "$_n" 2>/dev/null
    done
}

_runhmi_clean() {
    [ "$(_bridge_count "$1")" = 0 ] && [ "$(_cover_count "$1")" = 0 ]
}

step 1 "checking installed patch"
old_full_present && fail D01 "older patch installed"
v1_present && fail D02 "v1 installed"
_patch_state=no _recover=no
patch_present && _patch_state=yes
if [ "$_patch_state" = yes ] && ! patch_owned; then
    recovery_backups_ok || fail D04 "patch owner unknown and stock backups invalid"
    _recover=yes
fi
[ "$_recover" = yes ] || { _iap2_patched "$IAP2" && ! iap2_owned && fail D05 "iap2.cfg owner unknown"; }
if [ "$_patch_state" != yes ]; then
    _left=no
    if [ -d "$STATE" ]; then
        tsd_rw && rm -rf "$STATE" 2>/dev/null
        [ -d "$STATE" ] || _left=yes
        remount_ro
    fi
    clean_ram
    ok
    echo ""
    echo "Everything ok. No patch was installed."
    [ "$_left" = yes ] && echo "Leftover files were removed."
    echo ""
    echo "Made by xPaiiN <3"
    exit 0
fi
ok

step 2 "restoring runHMI.sh"
remount_rw || fail D06 "/tsd not writable"
snapshot || fail D07 "snapshot"
_mutating=yes
_runhmi_done=no _rh_info=
if _valid_runhmi "$RUN_HMI.bak" && restore_runhmi_bak; then
    chmod 755 "$RUN_HMI" 2>/dev/null
    _runhmi_clean "$RUN_HMI" && { _runhmi_done=yes; _rh_info="from /tsd/hmi/runHMI.sh.bak"; }
fi
if [ "$_runhmi_done" != yes ] && _runhmi_clean "$RUN_HMI"; then
    _runhmi_done=yes
    _rh_info="no patch lines found"
fi
if [ "$_runhmi_done" != yes ] && find_sd && [ -s "$SD_BACKUP/runHMI.sh" ] && _stock_runhmi "$SD_BACKUP/runHMI.sh"; then
    if atomic_copy "$SD_BACKUP/runHMI.sh" "$RUN_HMI"; then
        chmod 755 "$RUN_HMI" 2>/dev/null
        _runhmi_clean "$RUN_HMI" && { _runhmi_done=yes; _rh_info="from SD: backup/carplay-vc/runHMI.sh"; }
    fi
fi
if [ "$_runhmi_done" != yes ]; then
    strip_runhmi "$RUN_HMI" > "$RUN_HMI_TMP" 2>/dev/null || fail D08 "runHMI strip"
    _valid_runhmi "$RUN_HMI_TMP" && _runhmi_clean "$RUN_HMI_TMP" || fail D08 "runHMI strip verify"
    chmod 755 "$RUN_HMI_TMP" 2>/dev/null || fail D08 "runHMI mode"
    mv "$RUN_HMI_TMP" "$RUN_HMI" || fail D08 "runHMI install"
    _runhmi_done=yes
    _rh_info="patch lines removed"
fi
{ [ -x "$RUN_HMI" ] && _valid_runhmi "$RUN_HMI" && _runhmi_clean "$RUN_HMI"; } || fail D08 "runHMI post-verify"
info "$_rh_info"
ok

step 3 "restoring iap2.cfg, removing patch files"
_iap2_done=no _iap2_info="iap2.cfg already original"
if [ -f "$STATE/iap2-original-missing" ]; then
    rm -f "$IAP2" && _iap2_done=yes
    _iap2_info="iap2.cfg removed, it was not there before"
elif [ -f "$STATE/iap2-original-empty" ]; then
    rm -f "$IAP2" && touch "$IAP2" && _iap2_done=yes
    _iap2_info="iap2.cfg emptied, it was empty before"
elif [ "$_recover" = yes ] && ! _iap2_patched "$IAP2"; then
    _iap2_done=yes
elif [ ! -s "$IAP2" ] || ! _valid_iap2 "$IAP2"; then
    fail D05 "iap2.cfg state unknown"
elif ! _iap2_patched "$IAP2"; then
    _iap2_done=yes
elif restore_iap2_state; then
    _iap2_done=yes
    _iap2_info="iap2.cfg restored"
fi
[ "$_iap2_done" = yes ] || fail D09 "iap2 restore"
remove_payload || fail D10 "payload removal"
for _f in $PAYLOAD_FILES; do [ ! -f "$_f" ] || fail D10 "payload removal verify"; done
remove_markers || fail D11 "marker removal"
for _m in $OWNER_MARKERS; do [ ! -f "$STATE/$_m" ] || fail D11 "marker removal verify"; done
cleanup_snapshot
info "$_iap2_info"
info "cpvc-bridge.jar removed"
info "cpvc-cover-elf removed"
ok

step 4 "cleaning up"
clean_ram
rm -rf "$STATE" 2>/dev/null
rm -rf "$LIB_DIR" 2>/dev/null
remount_ro
ok

echo ""
[ "$_recover" = yes ] && echo "Restored from the original backups."
[ -d "$STATE" ] && echo "The state folder is partly kept."
echo "Everything ok. Patch successfully removed :)"
echo "Backups and logs on sd not removed."
echo "Now reboot the HU to apply (Hold volume/power knob 12 sec)"
echo "To remove this menu, run \"Restore GEM from backup\" after reboot"
echo ""
echo "Made by xPaiiN <3"
echo "github.com/xPaiiN/mst2-carplay-vc"
exit 0
