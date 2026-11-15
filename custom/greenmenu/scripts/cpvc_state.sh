#!/bin/sh
# Made by xPaiiN <3
# github.com/xPaiiN/mst2-carplay-vc
# v2.0
case "$0" in */*) CPVC_SCRIPT_DIR="${0%/*}" ;; *) CPVC_SCRIPT_DIR=. ;; esac
exec 2>/dev/null
[ -f "$CPVC_SCRIPT_DIR/cpvc_lib.sh" ] && . "$CPVC_SCRIPT_DIR/cpvc_lib.sh" || { echo "MST2 CarPlay VC v2.0 - status script"; echo "-----------------------------------------"; echo "Error: the package on the SD card is incomplete (E07)"; echo "cpvc_lib.sh is missing."; exit 1; }

detect_hu
_tested=no
hu_tested && _tested=yes
_dest=no
dest_wanted && _dest=yes
[ -f "$STATE/active" ] && _state=yes || _state=no
[ -f "$STATE/debug-enabled" ] && _debug=on || _debug=off
_iap2=stock
if _iap2_complete "$IAP2" "$_dest"; then _iap2=patched
elif _iap2_patched "$IAP2"; then _iap2="not ok"
fi
_bc=$(_bridge_count "$RUN_HMI")
_cc=$(_cover_count "$RUN_HMI")
_rh=stock
if [ "$_bc$_cc" = 11 ]; then _rh=patched
elif [ "$_bc$_cc" != 00 ] || [ ! -s "$RUN_HMI" ]; then _rh="not ok"
fi
_jar=missing
[ -s "$JAR_DEST" ] && _jar=ok
_cover=missing
[ -s "$COVER_DEST" ] && _cover=ok
_compat=unknown
[ -s "$RAMDISK/carplayvc/cpvc-compat" ] && read _compat < "$RAMDISK/carplayvc/cpvc-compat"
_other=
v1_present && _other="$_other v1"
old_full_present && _other="$_other older-patch"
conflicts_found && _other="$_other $CONFLICTS"
_other=${_other# }
[ -n "$_other" ] || _other=none

_bk() { if [ "$1" = yes ] && [ "$2" = yes ]; then echo "hu, sd"; elif [ "$1" = yes ]; then echo hu; elif [ "$2" = yes ]; then echo sd; else echo none; fi; }
find_sd || SD_BACKUP=
_b_rh=no; _s_rh=no; _b_iap=no; _s_iap=no
_stock_runhmi "$RUN_HMI.bak" && _b_rh=yes
_stock_iap2 "$IAP2.bak" && _b_iap=yes
if [ -n "$SD_BACKUP" ]; then
    _stock_runhmi "$SD_BACKUP/runHMI.sh" && _s_rh=yes
    _stock_iap2 "$SD_BACKUP/iap2.cfg" && _s_iap=yes
fi
_no_iap2=no
{ [ -f "$STATE/iap2-original-missing" ] || [ -f "$STATE/iap2-original-empty" ] || [ "$_state" = no ]; } && _no_iap2=yes
_b_all=no; _s_all=no
[ "$_b_rh" = yes ] && { [ "$_b_iap" = yes ] || [ "$_no_iap2" = yes ]; } && _b_all=yes
[ "$_s_rh" = yes ] && { [ "$_s_iap" = yes ] || [ "$_no_iap2" = yes ]; } && _s_all=yes
_backups=$(_bk $_b_all $_s_all)
if [ "$_backups" = none ]; then
    for _v in $_b_rh $_s_rh $_b_iap $_s_iap; do [ "$_v" = yes ] && _backups=incomplete; done
fi

if [ "$_state" = yes ]; then _want=patched; else _want=stock; fi
_sys=ok
for _v in "$_rh" "$_iap2"; do [ "$_v" = "$_want" ] || _sys="not ok"; done
if [ "$_jar" = ok ] && [ "$_cover" = ok ]; then _pf=ok
elif [ "$_jar" = missing ] && [ "$_cover" = missing ]; then _pf=missing
else _pf="not ok"
fi

SEP="-----------------------------------------"
echo "$CPVC_TITLE - status script"
echo "$SEP"
case "$HU_PLATFORM" in
    mqb) _unit="ZR, region $HU_REGION" ;;
    pq) _unit="PQ, region $HU_REGION (song info only)" ;;
    *) _unit="not readable (song info only)" ;;
esac
[ "$_compat" = unknown ] && [ "$_state" = yes ] && _compat="unknown (shows after the first reboot)"
[ "$_jar" = ok ] && _jar="cpvc-bridge.jar ($(file_size "$JAR_DEST") bytes)"
[ "$_cover" = ok ] && _cover="cpvc-cover-elf"
echo "installed:        $_state"
echo "firmware:         ${HU_VERSION:-unknown} ${HU_VARIANT:-unknown}"
echo "train:            ${HU_TRAIN:-unknown}"
echo "unit:             $_unit"
echo "navigation:       $_dest"
echo "tested:           $_tested"
echo "runHMI.sh:        $_rh"
echo "iap2.cfg:         $_iap2"
echo "backups:          $_backups"
echo "patch files:      $_pf"
if [ "$_pf" != missing ]; then
    echo "   bridge         $_jar"
    echo "   cover          $_cover"
fi
echo "debug logs:       $_debug"
echo "compat:           $_compat"
echo "other patches:    $_other"
echo ""
if [ "$_state" = yes ] && [ "$_sys" = ok ] && [ "$_pf" = ok ]; then
    echo "Everything ok. Patch is active :)"
elif [ "$_state" = no ] && [ "$_sys" = ok ] && [ "$_pf" = missing ]; then
    echo "Patch is not installed."
else
    echo "Patch is installed, but not complete."
    echo "Run the uninstall script, then install again."
fi
echo ""
echo "Made by xPaiiN <3"
exit 0
