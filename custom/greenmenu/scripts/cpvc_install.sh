#!/bin/sh
# Made by xPaiiN <3
# github.com/xPaiiN/mst2-carplay-vc
# v2.0
case "$0" in */*) CPVC_SCRIPT_DIR="${0%/*}" ;; *) CPVC_SCRIPT_DIR=. ;; esac
exec 2>/dev/null
[ -f "$CPVC_SCRIPT_DIR/cpvc_lib.sh" ] && . "$CPVC_SCRIPT_DIR/cpvc_lib.sh" || { echo "MST2 CarPlay VC v2.0 - install script"; echo "-----------------------------------------"; echo "Error: the package on the SD card is incomplete (E07)"; echo "cpvc_lib.sh is missing."; echo ""; echo "No changes were made."; exit 1; }

DEBUG=no
for _a in "$@"; do [ "$_a" = --debug ] && DEBUG=yes; done
SEP="-----------------------------------------"
step() { echo "step $1/6 $2..."; }
ok() { echo "          ok"; }
info() { echo "          $1"; }
if [ "$DEBUG" = yes ]; then echo "$CPVC_TITLE - install script (debug)"; else echo "$CPVC_TITLE - install script"; fi
echo "$SEP"
_jar_ok=no _iap2_ok=no _runhmi_ok=no _cover_started=no _system_started=no _state_started=no
_dest=no _missing=

installed_ok() {
    for _s in $SYSTEM_FILES; do [ -s "$SYSTEM_SCRIPTS/$_s" ] && sh -n "$SYSTEM_SCRIPTS/$_s" 2>/dev/null || return 1; done
    [ -s "$SYSTEM_ESD" ] && [ -s "$JAR_DEST" ] && [ -s "$COVER_DEST" ] && [ -f "$KEEPALIVE" ] &&
        [ -f "$STATE/active" ] && grep -q "^v2/$HU_REGION$" "$STATE/cpvc-variant" 2>/dev/null &&
        _iap2_complete "$IAP2" "$_dest" &&
        [ "$(_bridge_count "$RUN_HMI")" = 1 ] && [ "$(_cover_count "$RUN_HMI")" = 1 ] &&
        _same_file "$PAYLOAD/$BRIDGE_NAME" "$JAR_DEST" && _same_file "$PAYLOAD/$COVER_NAME" "$COVER_DEST"
}

explain() {
    case "$1" in
        E01) echo "Error: runHMI.sh is missing or damaged (E01)"; echo "The unit start file is not as expected." ;;
        E02) echo "Error: this unit is not supported (E02)"; echo "The unit variant is not known to this patch." ;;
        E05) echo "Error: this firmware is not supported yet (E05)"; echo "It was not checked with this release." ;;
        E06) echo "Error: no SD card found (E06)"; echo "Insert the SD card with the patch and try again." ;;
        E07) echo "Error: the package on the SD card is incomplete (E07)"; echo "$_missing" ;;
        E08) echo "Error: mst2-carplay-vc v1 is installed (E08)"; echo "Remove it in the v1 menu with 'Disable + Cleanup',"; echo "reboot, then select 'Install' again." ;;
        E09) echo "Error: an older patch is installed (E09)"; echo "Remove it with its own menu first." ;;
        E10) echo "Error: iap2.cfg is not original (E10)"; echo "Another patch changed iap2.cfg before." ;;
        E11) echo "Error: old install found (E11)"; echo "Select 'Uninstall', reboot, then select 'Install'." ;;
        E12) echo "Error: cannot write to the unit (E12)"; echo "The /tsd partition could not be opened for writing." ;;
        E13) echo "Error: cannot clean up an old install (E13)"; echo "Old patch files could not be removed." ;;
        E14) echo "Error: cannot write to the unit (E14)"; echo "The patch state could not be saved." ;;
        E15) echo "Error: iap2.cfg could not be prepared (E15)"; echo "A working copy of iap2.cfg could not be made." ;;
        E16) echo "Error: iap2.cfg does not exist yet (E16)"; echo "CarPlay never ran. Connect it once, then install." ;;
        E17) echo "Error: backup of runHMI.sh failed (E17)"; echo "The original runHMI.sh could not be saved." ;;
        E18) echo "Error: backup of iap2.cfg failed (E18)"; echo "The original iap2.cfg could not be saved." ;;
        E19) echo "Error: iap2.cfg could not be patched (E19)"; echo "The new iap2.cfg could not be built." ;;
        E20) echo "Error: iap2.cfg could not be written (E20)"; echo "Copying failed, storage may be full or read-only." ;;
        E21) echo "Error: iap2.cfg check failed (E21)"; echo "iap2.cfg is not as expected after writing." ;;
        E22) echo "Error: copy failed (cpvc-bridge.jar) (E22)"; echo "The bridge file could not be copied." ;;
        E23) echo "Error: copy failed (cpvc-cover-elf) (E23)"; echo "The cover helper could not be copied." ;;
        E24) echo "Error: runHMI.sh could not be patched (E24)"; echo "runHMI.sh is not as expected after writing." ;;
        E25) echo "Error: menu could not be installed (E25)"; echo "The CarPlay VC menu could not be copied." ;;
        E26) echo "Error: cannot write to the unit (E26)"; echo "The final state could not be saved." ;;
    esac
}

fail() {
    _code=$1 _why=$2 _rollback_failed=no
    debug_log "FAIL install: $_why"
    rm -f "$STATE/.tmp/iap2.base" "$STATE/.tmp/iap2.new" "$STATE/.tmp/runhmi.stock" "$RUN_HMI_TMP" "$RUN_HMI_TMP.clean"
    if [ "$_system_started" = yes ]; then restore_system || _rollback_failed=yes; fi
    if [ "$_runhmi_ok" = yes ]; then
        if restore_runhmi_bak; then chmod 755 "$RUN_HMI" 2>/dev/null || _rollback_failed=yes
        else _rollback_failed=yes; _jar_ok=keep
        fi
    fi
    if [ "$_iap2_ok" = yes ]; then restore_iap2_state || _rollback_failed=yes; fi
    if [ "$_jar_ok" = yes ]; then rm -f "$JAR_DEST" || _rollback_failed=yes; fi
    if [ "$_cover_started" = yes ]; then rm -f "$COVER_DEST" "$KEEPALIVE" || _rollback_failed=yes; fi
    if [ "$_state_started" = yes ]; then remove_markers || _rollback_failed=yes; fi
    echo "          error"
    echo ""
    explain "$_code"
    echo ""
    if [ "$_rollback_failed" = yes ]; then
        echo "Undo incomplete, the unit is partly patched."
        echo "Do not reboot the HU yet."
        echo "Run the uninstall script first and verify."
    elif [ "$_state_started" = yes ]; then
        echo "All changes were undone."
    else
        echo "No changes were made."
    fi
    exit 1
}

refuse() {
    _rep=no
    find_sd && write_report "$2" && _rep=yes
    echo "          error"
    echo ""
    explain "$1"
    echo ""
    echo "The unit was not changed."
    if [ "$_rep" = yes ]; then
        echo "Report saved to the SD card: custom/report/"
        echo "Please attach that folder to a GitHub issue."
    fi
    exit 1
}

step 1 "checking sd and unit"
_valid_runhmi "$RUN_HMI" || fail E01 "runHMI pre-verify"
_known=yes
detect_hu || _known=no
info "${HU_VERSION:-unknown} ${HU_VARIANT:-unknown}"
[ "$_known" = yes ] || refuse E02 "unit variant unknown"
hu_tested || refuse E05 "firmware not on the tested list"
dest_wanted && _dest=yes
CPVC_STRICT_SD=yes
if ! find_sd; then
    CPVC_STRICT_SD=no
    find_sd || fail E06 "SD card not found"
fi
CPVC_STRICT_SD=no
_missing=$(payload_ok) || fail E07 "SD package incomplete"
v1_present && fail E08 "v1 installed"
old_full_present && fail E09 "older patch installed"

if installed_ok; then
    if [ "$DEBUG" = yes ]; then
        remount_rw >/dev/null 2>&1
        touch "$STATE/debug-enabled" || fail E14 "debug marker"
    fi
    ok
    echo ""
    echo "Everything ok. Patch is already installed."
    if [ "$DEBUG" = yes ]; then echo "Debug logs are now on."; else echo "No changes were made."; fi
    echo ""
    echo "Made by xPaiiN <3"
    exit 0
fi

[ -s "$IAP2" ] && ! _valid_iap2 "$IAP2" && fail E10 "iap2 stock invalid"
patch_present && fail E11 "existing or partial patch"
if [ -d "$STATE" ]; then
    tsd_rw || fail E12 "/tsd not writable"
    rm -rf "$STATE" 2>/dev/null
    [ -d "$STATE" ] && fail E13 "leftover state not removable"
fi
if conflicts_found; then
    info "warning: $CONFLICTS uses the same media classes"
    info "only one of both can show its data in the cluster"
fi
ok

step 2 "backing up original files"
remount_rw || fail E12 "/tsd not writable"
debug_log "install $CPVC_RELEASE $HU_VERSION $HU_VARIANT"
touch "$STATE/active" || fail E14 "state marker"
_state_started=yes
IAP2_WAS_FILE=no IAP2_WAS_EMPTY=no
if [ -f "$IAP2" ]; then
    IAP2_WAS_FILE=yes
    [ -s "$IAP2" ] || IAP2_WAS_EMPTY=yes
fi
if [ "$IAP2_WAS_FILE" != yes ]; then touch "$STATE/iap2-original-missing" || fail E14 "iap2 state"
elif [ "$IAP2_WAS_EMPTY" = yes ]; then touch "$STATE/iap2-original-empty" || fail E14 "iap2 state"
fi
echo "v2/$HU_REGION" > "$STATE/cpvc-variant" || fail E14 "variant marker"
if _iap2_has_accessory "$IAP2"; then cp "$IAP2" "$STATE/.tmp/iap2.base" || fail E15 "iap2 stage"
elif _iap2_has_accessory "$IAP2_ETC"; then cp "$IAP2_ETC" "$STATE/.tmp/iap2.base" || fail E15 "iap2 stage"
else fail E16 "iap2.cfg missing"
fi
backup_runhmi || fail E17 "runHMI backup"
backup_iap2 || fail E18 "iap2 backup"
info "HU: /tsd/hmi/runHMI.sh.bak"
[ -f "$IAP2.bak" ] && info "HU: /tsd/var/sal/carplay/iap2.cfg.bak"
info "SD: backup/carplay-vc/runHMI.sh"
[ -f "$SD_BACKUP/iap2.cfg" ] && info "SD: backup/carplay-vc/iap2.cfg"
ok

step 3 "patching iap2.cfg"
build_iap2 "$STATE/.tmp/iap2.base" "$STATE/.tmp/iap2.new" "$_dest" || fail E19 "iap2 build"
_iap2_ok=yes
atomic_copy "$STATE/.tmp/iap2.new" "$IAP2" || fail E20 "iap2 install"
rm -f "$STATE/.tmp/iap2.base" "$STATE/.tmp/iap2.new"
_iap2_complete "$IAP2" "$_dest" || fail E21 "iap2 post-verify"
info "medialib=yes nowplaying=yes"
info "navigation=$_dest"
ok

step 4 "copying patch files"
_jar_ok=yes
atomic_copy "$PAYLOAD/$BRIDGE_NAME" "$JAR_DEST" || fail E22 "JAR install"
chmod 755 "$JAR_DEST" 2>/dev/null || fail E22 "JAR mode"
info "cpvc-bridge.jar -> /tsd/hmi/HMI/jar/"
_cover_started=yes
atomic_copy "$PAYLOAD/$COVER_NAME" "$COVER_DEST" || fail E23 "cover helper install"
chmod 755 "$COVER_DEST" 2>/dev/null || fail E23 "cover helper mode"
touch "$KEEPALIVE" || fail E23 "keepalive marker"
info "cpvc-cover-elf -> /tsd/lib/carplayvc/"
ok

step 5 "patching runHMI.sh"
build_runhmi "$RUN_HMI" "$RUN_HMI_TMP" || fail E24 "runHMI build"
mv "$RUN_HMI_TMP" "$RUN_HMI" || fail E24 "runHMI install"
_runhmi_ok=yes
chmod 755 "$RUN_HMI" 2>/dev/null || fail E24 "runHMI mode"
_valid_runhmi "$RUN_HMI" || fail E24 "runHMI post-verify"
info "2 lines added, check ok"
ok

step 6 "verifying"
backup_system || fail E25 "system backup"
_system_started=yes
install_system || fail E25 "system install"
remove_markers || fail E26 "marker cleanup"
touch "$STATE/active" || fail E26 "active marker"
if [ "$DEBUG" = yes ]; then touch "$STATE/debug-enabled" || fail E26 "debug marker"; fi
if [ "$IAP2_WAS_FILE" != yes ]; then touch "$STATE/iap2-original-missing" || fail E26 "iap2 state"; fi
if [ "$IAP2_WAS_EMPTY" = yes ]; then touch "$STATE/iap2-original-empty" || fail E26 "iap2 state"; fi
touch "$KEEPALIVE" || fail E26 "keepalive marker"
echo "v2/$HU_REGION" > "$STATE/cpvc-variant" || fail E26 "variant marker"
clear_system_backup
remove_old_menu_files
debug_log "installed v2/$HU_REGION"
remount_ro
ok

echo ""
if [ "$DEBUG" = yes ]; then
    echo "Everything ok. Patch installed in debug-mode :)"
    echo "Only use this when troubleshooting."
else
    echo "Everything ok. Patch successfully installed :)"
fi
echo "Now reboot the HU to apply (Hold volume/power knob 12 sec)"
echo ""
echo "Made by xPaiiN <3"
echo "github.com/xPaiiN/mst2-carplay-vc"
exit 0
