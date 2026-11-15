#!/bin/sh
# Made by xPaiiN <3
# github.com/xPaiiN/mst2-carplay-vc
# v2.0
: "${CPVC_ROOT:=}"
TSD="${CPVC_ROOT}/tsd"
STATE="$TSD/var/carplayvc"
RUN_HMI="$TSD/hmi/runHMI.sh"
RUN_HMI_TMP="$TSD/hmi/runHMI.sh.cpvc-new"
VAULT="$TSD/var/persistence/.persistence.vault"
CPU_CONF="$TSD/etc/system/cpu.conf"
IAP2="$TSD/var/sal/carplay/iap2.cfg"
IAP2_ETC="$TSD/etc/sal/carplay/iap2.cfg"
JAR_DIR="$TSD/hmi/HMI/jar"
JAR_DEST="$JAR_DIR/cpvc-bridge.jar"
LIB_DIR="$TSD/lib/carplayvc"
COVER_DEST="$LIB_DIR/cpvc-cover-elf"
KEEPALIVE="$STATE/cpvc-keepalive"
COVER_RUN=/tsd/lib/carplayvc/cpvc-cover-elf
KEEPALIVE_RUN=/tsd/var/carplayvc/cpvc-keepalive
BRIDGE_NAME=cpvc-bridge.jar
COVER_NAME=cpvc-cover-elf
MMIPOD="${CPVC_ROOT}/usr/sbin/mm-ipod"
RAMDISK="${CPVC_ROOT}/ramdisk"
SYSTEM_BASE="$TSD/etc/persistence/esd"
SYSTEM_SCRIPTS="$SYSTEM_BASE/scripts"
ESD_NAME=mibstd2-carplay-vc.esd
SYSTEM_ESD="$SYSTEM_BASE/$ESD_NAME"
SYSTEM_FILES="cpvc_lib.sh cpvc_install.sh cpvc_install_debug.sh cpvc_uninstall.sh cpvc_state.sh cpvc_logs.sh"
PAYLOAD_FILES="$JAR_DEST $COVER_DEST $KEEPALIVE"
OWNER_MARKERS="active cpvc-variant iap2-original-missing iap2-original-empty cpvc-keepalive"
MARKERS="$OWNER_MARKERS debug-enabled"
# HMI builds allowed with this JAR, as <VERSION>:<region>.
TESTED_HMI="H29.344.66:eu H29.344.66:na H29.344.66:cn H29.319.120:eu H29.319.120:na H29.319.120:cn H29.319.93:eu H29.319.93:na H29.319.93:cn H29.319.76:eu H29.319.76:na H29.319.76:cn"
# Builds allowed for PQ and for units whose type is not readable; they also need TESTED_HMI.
TESTED_PQ="H29.344.66 H29.319.120 H29.319.93 H29.319.76"
# Other patches that replace the same stock classes.
CONFLICT_JARS="AAtoKombi.jar"
CPVC_RELEASE=2.0
CPVC_TITLE="MST2 CarPlay VC v$CPVC_RELEASE"
DEBUG=no
CPVC_STRICT_SD=no
SD_ROOT= PAYLOAD= SD_BACKUP= SD_SCRIPTS= SD_ESD=
HU_VERSION= HU_VARIANT= HU_REGION= HU_PLATFORM= HU_NAV= HU_TRAIN=
IAP2_WAS_FILE=unset IAP2_WAS_EMPTY=no

debug_log() {
    [ "$DEBUG" = yes ] || return 0
    mkdir -p "$STATE" 2>/dev/null || return 0
    [ ! -e "$STATE/cpvc-script-output.log" ] || [ -f "$STATE/cpvc-script-output.log" ] || return 0
    echo "$*" >> "$STATE/cpvc-script-output.log" 2>/dev/null || :
}

remount_rw() {
    if [ -z "$CPVC_ROOT" ]; then
        mount -o rw,remount "$TSD" >/dev/null 2>&1 || return 1
    fi
    mkdir -p "$STATE/.tmp" "$LIB_DIR" "$JAR_DIR" "$SYSTEM_SCRIPTS" "$TSD/var/sal/carplay" 2>/dev/null || return 1
    echo rw > "$STATE/.rwtest" 2>/dev/null || return 1
    rm -f "$STATE/.rwtest" 2>/dev/null
}

tsd_rw() {
    [ -n "$CPVC_ROOT" ] && return 0
    mount -o rw,remount "$TSD" >/dev/null 2>&1
}

remount_ro() {
    [ -n "$CPVC_ROOT" ] && return 0
    mount -o ro,remount "$TSD" >/dev/null 2>&1
    return 0
}

file_size() {
    [ -f "$1" ] || { echo 0; return 0; }
    _s=$(ls -l "$1" 2>/dev/null | awk 'NR==1 {print $5}' 2>/dev/null)
    case "$_s" in ''|*[!0-9]*) echo 0 ;; *) echo "$_s" ;; esac
}

_same_file() {
    cmp "$1" "$2" >/dev/null 2>&1
}

atomic_copy() {
    [ -f "$1" ] && [ -s "$1" ] || return 1
    _tmp="$2.cpvc-new"
    rm -f "$_tmp" 2>/dev/null
    cp "$1" "$_tmp" 2>/dev/null || { rm -f "$_tmp" 2>/dev/null; return 1; }
    [ -s "$_tmp" ] || { rm -f "$_tmp" 2>/dev/null; return 1; }
    [ "$(file_size "$1")" = "$(file_size "$_tmp")" ] || { rm -f "$_tmp" 2>/dev/null; return 1; }
    mv "$_tmp" "$2" 2>/dev/null || { rm -f "$_tmp" 2>/dev/null; return 1; }
    return 0
}

_valid_runhmi() {
    [ -f "$1" ] && [ -s "$1" ] && grep -q '^BOOTCLASSPATH=' "$1" 2>/dev/null &&
        grep -q 'de.vw.mib.MIBMain' "$1" 2>/dev/null && sh -n "$1" 2>/dev/null
}

# Some PQ units report a ZR variant; only the train name tells them apart. Unknown means media only.
detect_hu() {
    HU_VERSION= HU_VARIANT= HU_REGION= HU_PLATFORM= HU_NAV= HU_TRAIN=
    [ -s "$VAULT" ] && HU_TRAIN=$(awk '/46924065 401 25/ {print $4}' "$VAULT" 2>/dev/null | sed 's/[^[:print:]]//g')
    [ -s "$RUN_HMI" ] || return 1
    HU_VERSION=$(awk 'NR<=10 && /^VERSION=/ {v=$0; sub(/^VERSION=/, "", v); sub(/\r$/, "", v); sub(/[[:space:]]+$/, "", v); print v; exit}' "$RUN_HMI" 2>/dev/null)
    HU_VARIANT=$(awk '/^# HMI Variant: / {v=$0; sub(/^# HMI Variant: /, "", v); sub(/\r$/, "", v); sub(/[[:space:]]+$/, "", v); print v; exit}' "$RUN_HMI" 2>/dev/null)
    case "$HU_VARIANT" in
        STD2Nav_EU)     HU_REGION=eu HU_PLATFORM=mqb HU_NAV=yes ;;
        STD2Plus_EU)    HU_REGION=eu HU_PLATFORM=mqb HU_NAV=no ;;
        STD2Nav_NAR)    HU_REGION=na HU_PLATFORM=mqb HU_NAV=yes ;;
        STD2Plus_NAR)   HU_REGION=na HU_PLATFORM=mqb HU_NAV=no ;;
        STD2Nav_CHN)    HU_REGION=cn HU_PLATFORM=mqb HU_NAV=yes ;;
        STD2Plus_CHN)   HU_REGION=cn HU_PLATFORM=mqb HU_NAV=no ;;
        STD2GPNav_CHN)  HU_REGION=cn HU_PLATFORM=mqb HU_NAV=yes ;;
        STD2NavPQ_EU)   HU_REGION=eu HU_PLATFORM=pq HU_NAV=yes ;;
        STD2PlusPQ_EU)  HU_REGION=eu HU_PLATFORM=pq HU_NAV=no ;;
        STD2NavPQ_NAR)  HU_REGION=na HU_PLATFORM=pq HU_NAV=yes ;;
        STD2PlusPQ_NAR) HU_REGION=na HU_PLATFORM=pq HU_NAV=no ;;
        STD2NavPQ_CHN)  HU_REGION=cn HU_PLATFORM=pq HU_NAV=yes ;;
        STD2PlusPQ_CHN) HU_REGION=cn HU_PLATFORM=pq HU_NAV=no ;;
        *) HU_REGION=unknown HU_PLATFORM=unknown HU_NAV=no; return 1 ;;
    esac
    case "$HU_TRAIN" in *_PQ_*) HU_PLATFORM=pq ;; *_ZR_*) ;; *) HU_PLATFORM=unknown ;; esac
    [ -n "$HU_VERSION" ] || return 1
    return 0
}

hu_tested() {
    if [ "$HU_PLATFORM" != mqb ]; then
        case " $TESTED_PQ " in *" $HU_VERSION "*) ;; *) return 1 ;; esac
    fi
    for _t in $TESTED_HMI; do
        [ "$_t" = "$HU_VERSION:$HU_REGION" ] && return 0
    done
    return 1
}

# Share to External Maps: ZR with factory navigation only; not on CN, not on PQ.
dest_wanted() {
    [ "$HU_PLATFORM" = mqb ] && [ "$HU_NAV" = yes ] && [ "$HU_REGION" != cn ]
}

v1_present() {
    grep -q -e 'mst2-carplay-vc\.jar' -e 'cpvc-cover-bridge' "$RUN_HMI" 2>/dev/null && return 0
    [ -f "$JAR_DIR/mst2-carplay-vc.jar" ] || [ -f "$LIB_DIR/cpvc-cover-bridge" ]
}

old_full_present() {
    grep -q -e 'carplayvc' -e 'cpvc-' "$CPU_CONF" 2>/dev/null && return 0
    grep -q -e 'carplayvc' -e 'cpvc-' -e 'mm-ipod\.orig' "$MMIPOD" 2>/dev/null && return 0
    grep 'cpvc-[a-z-]*\.jar' "$RUN_HMI" 2>/dev/null | grep -q -v 'cpvc-bridge\.jar' && return 0
    for _f in "$JAR_DIR"/cpvc-*.jar "$LIB_DIR"/* "$STATE"/*.so "$STATE"/*-elf; do
        [ -f "$_f" ] || continue
        case "$_f" in
            "$JAR_DEST"*|"$COVER_DEST"*|"$LIB_DIR/cpvc-cover-bridge"*) ;;
            *) return 0 ;;
        esac
    done
    return 1
}

conflicts_found() {
    CONFLICTS=
    for _j in $CONFLICT_JARS; do
        grep -q "$_j" "$RUN_HMI" 2>/dev/null && CONFLICTS="$CONFLICTS $_j"
    done
    CONFLICTS=${CONFLICTS# }
    [ -n "$CONFLICTS" ]
}

_iap2_has_accessory() {
    [ -f "$1" ] && [ -s "$1" ] && grep -q '^\[accessory\]' "$1" 2>/dev/null
}

_valid_iap2() {
    _iap2_has_accessory "$1" && grep -q '^\[medialib\]' "$1" 2>/dev/null &&
        grep -q '^\[nowplaying\]' "$1" 2>/dev/null && grep -q '^\[commands\]' "$1" 2>/dev/null &&
        grep -q '^\[communications\]' "$1" 2>/dev/null
}

_iap2_patched() {
    [ -f "$1" ] || return 1
    awk '
        {sub(/\r$/, "")}
        /^\[medialib\]/{s="media"; next}
        /^\[nowplaying\]/{s="now"; next}
        /^\[communications\]/{s="comm"; next}
        /^\[commands\]/{s="cmd"; next}
        /^\[power\]/{s="power"; next}
        /^\[destination\]/{s="dest"; next}
        /^\[ident\]/{s="ident"; next}
        /^\[/{s=""; next}
        s=="media" && /^enable=yes$/{ok=1}
        s=="now" && /^enable=yes$/{ok=1}
        s=="comm" && (/^lists=yes$/ || /^recentslistmax=25$/ || /^recentslistcombine=yes$/){ok=1}
        s=="cmd" && (/^iap2_nowplay_set=yes$/ || /^iap2_medialib_/ && /=yes$/ || /^iap2_communications_/ && /=yes$/){ok=1}
        s=="power" && /BatteryChargingState,BatteryChargeLevel$/{ok=1}
        s=="dest" && /^enable=yes$/{ok=1}
        s=="ident" && /^recvmsgs=.*0x5204/{ok=1}
        END{exit ok?0:1}
    ' "$1" 2>/dev/null
}

_stock_iap2() {
    _valid_iap2 "$1" && ! _iap2_patched "$1"
}

_iap2_complete() {
    _valid_iap2 "$1" || return 1
    awk -v dest="$2" '
        {sub(/\r$/, "")}
        /^\[medialib\]/{s="media"; next}
        /^\[nowplaying\]/{s="now"; next}
        /^\[commands\]/{s="cmd"; next}
        /^\[destination\]/{s="dest"; next}
        /^\[/{s=""; next}
        s=="media" && /^enable=yes$/{media=1}
        s=="now" && /^enable=yes$/{now=1}
        s=="cmd" && /^iap2_nowplay_set=yes$/{nowplay=1}
        s=="cmd" && /^iap2_medialib_/ {media_cmd=1; if ($0 !~ /=yes$/) media_bad=1}
        s=="dest" && /^enable=yes$/{d=1}
        END{
            ok=media && now && nowplay && media_cmd && !media_bad
            if (dest=="yes") ok=ok && d; else ok=ok && !d
            exit ok ? 0 : 1
        }
    ' "$1" 2>/dev/null
}

build_iap2() {
    _iap2_has_accessory "$1" || return 1
    awk -v dest="$3" '
        {sub(/\r$/, "")}
        /^\[/{
            sec=""
            if ($0=="[medialib]") sec="media"
            if ($0=="[nowplaying]") sec="now"
            if ($0=="[commands]") sec="cmd"
            if ($0=="[destination]") {sec="dest"; sawdest=1}
            print; next
        }
        sec=="media" && /^enable=no$/{print "enable=yes"; next}
        sec=="now" && /^enable=no$/{print "enable=yes"; next}
        sec=="cmd" && /^iap2_nowplay_set=no$/{print "iap2_nowplay_set=yes"; next}
        sec=="cmd" && /^iap2_medialib_/ && /=no$/{sub(/=no$/, "=yes"); print; next}
        dest=="yes" && sec=="dest" && /^enable=no$/{print "enable=yes"; next}
        dest!="yes" && sec=="dest" && /^enable=yes$/{print "enable=no"; next}
        {print}
        END{
            if (dest=="yes" && !sawdest) {print "[destination]"; print "enable=yes"}
        }
    ' "$1" > "$2" 2>/dev/null || { rm -f "$2"; return 1; }
    [ -s "$2" ] && _valid_iap2 "$2" || return 1
    _ac=$(grep -c '^\[accessory\]' "$2" 2>/dev/null) || _ac=0
    [ "$_ac" = 1 ]
}

_bridge_count() {
    [ -f "$1" ] || { echo 0; return; }
    _bc=$(grep -c 'cpvc-bridge\.jar' "$1" 2>/dev/null) || _bc=0
    echo "$_bc"
}

_cover_count() {
    [ -f "$1" ] || { echo 0; return; }
    _cc=$(grep -c 'cpvc-cover-elf' "$1" 2>/dev/null) || _cc=0
    echo "$_cc"
}

_stock_runhmi() {
    _valid_runhmi "$1" && [ "$(_bridge_count "$1")" = 0 ] && [ "$(_cover_count "$1")" = 0 ]
}

strip_runhmi() {
    [ -f "$1" ] || return 1
    awk '{
        if ($0 ~ /cpvc-bridge\.jar/ || $0 ~ /cpvc-cover-elf/) next
        print
    }' "$1"
}

build_runhmi() {
    rm -f "$2" "$2.clean" || return 1
    strip_runhmi "$1" > "$2.clean" 2>/dev/null || return 1
    _line=$(awk '/^BOOTCLASSPATH=/{n=NR} END{print n+0}' "$2.clean" 2>/dev/null)
    [ "${_line:-0}" -gt 0 ] 2>/dev/null || { rm -f "$2.clean"; return 1; }
    awk -v n="$_line" -v cover="$COVER_RUN" -v keep="$KEEPALIVE_RUN" '{
        print
        if (NR == n) {
            print "BOOTCLASSPATH=\"$BOOTCLASSPATH -Xbootclasspath/p:$MIBJAR/cpvc-bridge.jar\""
            print "[ -x \"" cover "\" ] && ( while [ -f \"" keep "\" ]; do \"" cover "\" </dev/null >/dev/null 2>&1; sleep 10; done ) </dev/null >/dev/null 2>&1 &"
        }
    }' "$2.clean" > "$2" 2>/dev/null
    _rc=$?
    rm -f "$2.clean"
    [ "$_rc" = 0 ] && [ -s "$2" ] && _valid_runhmi "$2" || return 1
    [ "$(_bridge_count "$2")" = 1 ] && [ "$(_cover_count "$2")" = 1 ]
}

find_sd() {
    SD_ROOT= PAYLOAD= SD_BACKUP= SD_SCRIPTS= SD_ESD=
    case "$0" in */*) _self="${0%/*}" ;; *) _self=. ;; esac
    _seen=:
    for _root in "${CPVC_SD_ROOT:-}" "$_self/../../.." "${CPVC_ROOT}/media/mp001" "${CPVC_ROOT}/media/mp000"; do
        [ -n "$_root" ] || continue
        _rr=$(cd "$_root" 2>/dev/null && pwd 2>/dev/null) || continue
        case "$_seen" in *"$_rr:"*) continue ;; esac
        _seen="$_seen$_rr:"
        if [ -d "$_rr/custom/java" ] && [ -d "$_rr/custom/greenmenu/scripts" ]; then
            SD_ROOT="$_rr"; PAYLOAD="$_rr/custom/java"; SD_BACKUP="$_rr/backup/carplay-vc"
            SD_SCRIPTS="$_rr/custom/greenmenu/scripts"; SD_ESD="$_rr/custom/greenmenu/$ESD_NAME"
            if [ "$CPVC_STRICT_SD" = yes ]; then
                payload_ok >/dev/null 2>&1 || continue
            fi
            return 0
        fi
    done
    return 1
}

payload_ok() {
    _rc=0
    for _n in "$BRIDGE_NAME" "$COVER_NAME"; do
        [ -s "$PAYLOAD/$_n" ] || { echo "custom/java/$_n is missing."; _rc=1; }
    done
    if [ -s "$SD_ESD" ]; then
        grep -q 'screen carplay-vc ' "$SD_ESD" 2>/dev/null || { echo "custom/greenmenu/$ESD_NAME is wrong."; _rc=1; }
    else
        echo "custom/greenmenu/$ESD_NAME is missing."; _rc=1
    fi
    for _s in $SYSTEM_FILES; do
        if [ -s "$SD_SCRIPTS/$_s" ]; then
            sh -n "$SD_SCRIPTS/$_s" 2>/dev/null || { echo "$_s is damaged."; _rc=1; }
        else
            echo "$_s is missing."; _rc=1
        fi
    done
    return $_rc
}

write_report() {
    [ -n "$SD_ROOT" ] || return 1
    _rep="$SD_ROOT/custom/report"
    mkdir -p "$_rep" 2>/dev/null || return 1
    {
        echo "$CPVC_TITLE - not installed"
        echo "hmi: ${HU_VERSION:-unknown}  variant: ${HU_VARIANT:-unknown}  train: ${HU_TRAIN:-unknown}"
        echo "reason: $1"
        echo "please attach this folder to a GitHub issue"
    } > "$_rep/cpvc-report.txt" 2>/dev/null || return 1
    cp "$RUN_HMI" "$_rep/runHMI.sh" 2>/dev/null
    ls -l "$TSD/hmi/ifs" > "$_rep/hmi-ifs.txt" 2>/dev/null
    return 0
}

backup_runhmi() {
    mkdir -p "$STATE/.tmp" 2>/dev/null
    [ -n "$SD_BACKUP" ] && mkdir -p "$SD_BACKUP" 2>/dev/null
    strip_runhmi "$RUN_HMI" > "$STATE/.tmp/runhmi.stock" 2>/dev/null || return 1
    _stock_runhmi "$STATE/.tmp/runhmi.stock" || return 1
    if [ -f "$RUN_HMI.bak" ]; then _stock_runhmi "$RUN_HMI.bak" || return 1; else atomic_copy "$STATE/.tmp/runhmi.stock" "$RUN_HMI.bak" || return 1; fi
    if [ -n "$SD_BACKUP" ]; then
        if [ -f "$SD_BACKUP/runHMI.sh" ]; then _stock_runhmi "$SD_BACKUP/runHMI.sh" || return 1; else atomic_copy "$STATE/.tmp/runhmi.stock" "$SD_BACKUP/runHMI.sh" || return 1; fi
    fi
    rm -f "$STATE/.tmp/runhmi.stock"
}

backup_iap2() {
    mkdir -p "$STATE/.tmp" 2>/dev/null
    [ -n "$SD_BACKUP" ] && mkdir -p "$SD_BACKUP" 2>/dev/null
    if [ ! -f "$STATE/iap2-original-missing" ] && [ ! -f "$STATE/iap2-original-empty" ]; then
        [ -s "$IAP2" ] || return 1
        if [ -f "$IAP2.bak" ]; then _stock_iap2 "$IAP2.bak" || return 1; else atomic_copy "$IAP2" "$IAP2.bak" || return 1; fi
        if [ -n "$SD_BACKUP" ]; then
            if [ -f "$SD_BACKUP/iap2.cfg" ]; then _stock_iap2 "$SD_BACKUP/iap2.cfg" || return 1; else atomic_copy "$IAP2.bak" "$SD_BACKUP/iap2.cfg" || return 1; fi
        fi
    fi
}

restore_runhmi_bak() {
    _stock_runhmi "$RUN_HMI.bak" && atomic_copy "$RUN_HMI.bak" "$RUN_HMI" && _stock_runhmi "$RUN_HMI"
}

restore_iap2_bak() {
    _stock_iap2 "$IAP2.bak" && atomic_copy "$IAP2.bak" "$IAP2" && _stock_iap2 "$IAP2"
}

restore_iap2_state() {
    if [ "$IAP2_WAS_FILE" = no ]; then rm -f "$IAP2"; return; fi
    if [ "$IAP2_WAS_EMPTY" = yes ]; then rm -f "$IAP2" && touch "$IAP2"; return; fi
    if [ -f "$STATE/iap2-original-missing" ]; then rm -f "$IAP2"; return; fi
    if [ -f "$STATE/iap2-original-empty" ]; then rm -f "$IAP2" && touch "$IAP2"; return; fi
    restore_iap2_bak
}

backup_system() {
    mkdir -p "$STATE/.tmp" 2>/dev/null || return 1
    for _s in $SYSTEM_FILES; do
        rm -f "$STATE/.tmp/system.$_s"
        if [ -f "$SYSTEM_SCRIPTS/$_s" ]; then atomic_copy "$SYSTEM_SCRIPTS/$_s" "$STATE/.tmp/system.$_s" || return 1; fi
    done
    if [ -f "$SYSTEM_ESD" ]; then atomic_copy "$SYSTEM_ESD" "$STATE/.tmp/system.esd" || return 1; else rm -f "$STATE/.tmp/system.esd"; fi
}

restore_system() {
    for _s in $SYSTEM_FILES; do
        if [ -f "$STATE/.tmp/system.$_s" ]; then atomic_copy "$STATE/.tmp/system.$_s" "$SYSTEM_SCRIPTS/$_s" || return 1
        else rm -f "$SYSTEM_SCRIPTS/$_s" || return 1; fi
    done
    if [ -f "$STATE/.tmp/system.esd" ]; then atomic_copy "$STATE/.tmp/system.esd" "$SYSTEM_ESD" || return 1; else rm -f "$SYSTEM_ESD" || return 1; fi
}

remove_old_menu_files() {
    for _f in carplay-vc.esd scripts/cpvc_activate.sh scripts/cpvc_activate_debug.sh scripts/cpvc_common.sh scripts/cpvc_copy_logs.sh scripts/cpvc_deactivate.sh scripts/cpvc_status.sh; do rm -f "$SYSTEM_BASE/$_f" || return 1; done
}

install_system() {
    for _s in $SYSTEM_FILES; do atomic_copy "$SD_SCRIPTS/$_s" "$SYSTEM_SCRIPTS/$_s" || return 1; done
    for _s in $SYSTEM_FILES; do chmod 755 "$SYSTEM_SCRIPTS/$_s" 2>/dev/null || return 1; done
    atomic_copy "$SD_ESD" "$SYSTEM_ESD"
}

clear_system_backup() {
    for _s in $SYSTEM_FILES; do rm -f "$STATE/.tmp/system.$_s"; done
    rm -f "$STATE/.tmp/system.esd"
}

remove_payload() {
    for _f in $PAYLOAD_FILES; do rm -f "$_f" 2>/dev/null || return 1; done
}

remove_markers() {
    _rc=0
    for _m in $MARKERS; do rm -f "$STATE/$_m" || _rc=1; done
    return $_rc
}

patch_present() {
    for _m in $OWNER_MARKERS; do [ -f "$STATE/$_m" ] && return 0; done
    [ "$(_bridge_count "$RUN_HMI")" != 0 ] || [ "$(_cover_count "$RUN_HMI")" != 0 ] ||
        [ -f "$JAR_DEST" ] || [ -f "$COVER_DEST" ] || _iap2_patched "$IAP2"
}

iap2_owned() {
    [ -f "$STATE/iap2-original-missing" ] || [ -f "$STATE/iap2-original-empty" ] ||
        { { [ -f "$STATE/active" ] || [ -s "$STATE/cpvc-variant" ]; } &&
            [ -s "$IAP2.bak" ] && _valid_iap2 "$IAP2.bak"; }
}

patch_owned() {
    [ -f "$STATE/active" ] && [ -f "$STATE/cpvc-variant" ] || return 1
    awk '/^v2\/(eu|na|cn)$/{ok=1} END{exit ok?0:1}' "$STATE/cpvc-variant" 2>/dev/null || return 1
    _stock_runhmi "$RUN_HMI.bak" || return 1
    if [ ! -f "$STATE/iap2-original-missing" ] && [ ! -f "$STATE/iap2-original-empty" ]; then
        _stock_iap2 "$IAP2.bak" || return 1
    fi
}

recovery_backups_ok() {
    ! _iap2_patched "$IAP2" || _stock_iap2 "$IAP2.bak"
}
