#!/bin/sh
# cpvc_common.sh -- shared helpers for the universal MST2 CarPlay-VC deploy scripts.
# Region- and variant-aware: the single JAR dropped in custom/java/ decides everything.
# On-car whitelist only (ls cp mv mount awk grep sed rm mkdir echo [ test date sleep slay chmod touch);
# variant/region parsing uses shell built-ins (no wc/printf/head/tail/tr/basename/stat).
: "${CPVC_ROOT:=}"
TSD="${CPVC_ROOT}/tsd"
RUN_HMI="$TSD/hmi/runHMI.sh"
JAR_DEST="$TSD/hmi/HMI/jar/mst2-carplay-vc.jar"
COVER_DEST="$TSD/lib/carplayvc/cpvc-cover-bridge"
SENTINEL="$TSD/var/carplayvc/tap-keepalive"
IAP2="$TSD/var/sal/carplay/iap2.cfg"
STATE="$TSD/var/carplayvc"
ESD_SCRIPTS="$TSD/etc/persistence/esd/scripts"
STICK=""
SD_BACKUP=""
# Set by find_patch_jar:
PATCH_JAR=""
VARIANT=""
REGION=""

say() { echo "$1"; }

remount_rw() {
    mount -o rw,remount "$TSD" 2>/dev/null
    mkdir -p "$STATE" 2>/dev/null
}

# Locate the toolbox stick: first /media/mp00* that carries a patch JAR.
find_stick() {
    for d in "${CPVC_ROOT}"/media/mp00*; do
        for j in "$d"/custom/java/mst2-carplay-vc-*.jar; do
            if [ -f "$j" ]; then
                STICK="$d"
                SD_BACKUP="$d/backup/carplayvc"
                return 0
            fi
        done
    done
    return 1
}

# Region from the runHMI.sh HMI-Variant marker. Whitelist-safe (grep/sed/case).
# RUNHMI_PATH overrides for sandbox tests; default = the vehicle path.
detect_region() {
    rh="${RUNHMI_PATH:-$RUN_HMI}"
    raw=$(grep "# HMI Variant: STD2Nav_" "$rh" 2>/dev/null | sed 's/.*STD2Nav_//;s/[[:space:]]*$//')
    case "$raw" in
        EU)      echo "eu" ;;
        CHN)     echo "cn" ;;
        NAR|US)  echo "eu" ;;
        *)       echo "unknown" ;;
    esac
}

# Find EXACTLY ONE mst2-carplay-vc-<variant>-<region>-v<N>.jar on the stick, parse variant+region,
# and cross-check the region against the head unit. Sets PATCH_JAR / VARIANT / REGION.
# 0 JARs  -> fail. >1 JAR -> fail (ambiguous revision). Region mismatch -> hard fail.
find_patch_jar() {
    [ -n "$STICK" ] || { echo "FAIL: no sd located (run find_stick first)."; return 1; }
    _found=""
    _count=0
    for j in "$STICK"/custom/java/mst2-carplay-vc-*.jar; do
        [ -f "$j" ] || continue
        _found="$j"
        _count=$((_count + 1))
    done
    if [ "$_count" -eq 0 ]; then
        echo "FAIL: No mst2-carplay-vc-*.jar found in sd/custom/java"
        return 1
    fi
    if [ "$_count" -gt 1 ]; then
        echo "FAIL: More than 1 mst2-carplay-vc-*.jar found in sd/custom/java"
        echo "      Copy only 1 mst2-carplay-vc-*.jar file and retry."
        return 1
    fi
    PATCH_JAR="$_found"
    # Parse variant + region from the file name via shell built-ins (whitelist-safe).
    # Format: mst2-carplay-vc-<variant>-<region>-v<N>.jar  (variant may contain hyphens, e.g. full-no-prog)
    _base="${PATCH_JAR##*/}"          # mst2-carplay-vc-<variant>-<region>-v<N>.jar
    _stem="${_base%.jar}"             # mst2-carplay-vc-<variant>-<region>-v<N>
    _tag="${_stem#mst2-carplay-vc-}"  # <variant>-<region>-v<N>
    # Strip -v<N> version suffix if present (backward-compat: missing suffix is ok)
    _core="$_tag"
    case "$_tag" in
        *-v[0-9]*) _core="${_tag%-v[0-9]*}" ;;
    esac
    # Region = last 2-char segment (eu/cn); variant = everything before the region
    REGION="${_core##*-}"
    VARIANT="${_core%-$REGION}"
    case "$VARIANT" in
        full|no-navi|no-navi-no-prog|no-prog|lite) ;;
        *) echo "FAIL: unknown variant '$VARIANT' in $_base (EU: full|no-navi|no-navi-no-prog|lite; CN: full|no-prog|lite)."; return 1 ;;
    esac
    case "$REGION" in
        eu|cn) ;;
        *) echo "FAIL: unknown region '$REGION' in $_base (expected eu|cn)."; return 1 ;;
    esac
    _hu=$(detect_region)
    if [ "$_hu" = unknown ]; then
        echo "FAIL: could not detect the head-unit region from runHMI.sh."
        echo "      Verify the HMI variant region is compatible and retry."
        return 1
    fi
    if [ "$REGION" != "$_hu" ]; then
        echo "FAIL: JAR region '$REGION' does not match this head-unit ('$_hu')."
        echo "      Copy a compatible version of mst2-carplay-vc-${VARIANT}-${_hu}.jar."
        return 1
    fi
    return 0
}

# everything except lite uses the native cover-bridge.
variant_uses_cover() {
    case "$1" in
        full|no-navi|no-navi-no-prog|no-prog) return 0 ;;
        *) return 1 ;;
    esac
}

# EU "full" is the complete variant = media + the Prio-4 "Share to vehicle" destination bridge
# (needs iap2 [destination] enabled). CN "full" is media-only (no navi) -> region-gated to eu.
variant_uses_navi() {
    [ "$REGION" = eu ] || return 1
    case "$1" in
        full) return 0 ;;
        *) return 1 ;;
    esac
}

# A runHMI backup is valid only if non-empty AND it has the BOOTCLASSPATH line AND the launch line.
_valid_runhmi() { [ -s "$1" ] && grep -q '^BOOTCLASSPATH=' "$1" && grep -q 'de.vw.mib.MIBMain' "$1"; }

# Back up runHMI.sh (FS .bak + SD) using the STOCK-equivalent (stripped) content. Skip-if-exists.
backup_runhmi() {
    mkdir -p "$STATE" 2>/dev/null
    awk '!/mst2-carplay-vc\.jar/ && !/cpvc-cover-bridge/ { print }' "$RUN_HMI" > "$STATE/.runhmi.clean" 2>/dev/null || return 1
    [ -f "$RUN_HMI.bak" ] || cp "$STATE/.runhmi.clean" "$RUN_HMI.bak"
    if [ -n "$SD_BACKUP" ]; then
        mkdir -p "$SD_BACKUP" 2>/dev/null
        [ -f "$SD_BACKUP/runHMI.sh" ] || cp "$STATE/.runhmi.clean" "$SD_BACKUP/runHMI.sh"
    fi
    rm -f "$STATE/.runhmi.clean"
}

# Back up a plain file ($1) to FS (.bak) and SD as <name> ($2), skip-if-exists.
backup_plain() {
    [ -f "$1.bak" ] || cp "$1" "$1.bak"
    if [ -n "$SD_BACKUP" ]; then
        mkdir -p "$SD_BACKUP" 2>/dev/null
        [ -f "$SD_BACKUP/$2" ] || cp "$1" "$SD_BACKUP/$2"
    fi
}

# Restore runHMI.sh -- LINE-PROOF. PRIMARY path = strip ONLY our own bridge + cover-bridge lines from
# the LIVE runHMI. This preserves every OTHER patch's -Xbootclasspath line (Auto-Brightness,
# NavActiveIgnore, ...), so disabling CarPlay-VC can never clobber them -- regardless of install/
# disable order. The FS .bak / SD backup are only fallbacks if the live strip somehow yields an
# invalid file. Every stage validates (BOOTCLASSPATH + MIBMain) + writes atomically (tmp in $STATE
# -> mv same fs). Returns 1 only if all stages fail.
restore_runhmi() {
    mkdir -p "$STATE" 2>/dev/null
    RESTORE_VIA=""
    # PRIMARY: own-line strip from the live file (line-proof; never touches a foreign patch line).
    awk '!/mst2-carplay-vc\.jar/ && !/cpvc-cover-bridge/ { print }' "$RUN_HMI" > "$STATE/.rh.restore" 2>/dev/null
    if _valid_runhmi "$STATE/.rh.restore"; then
        mv "$STATE/.rh.restore" "$RUN_HMI"; RESTORE_VIA="own-line strip"; return 0
    fi
    # FALLBACK 1: FS .bak (backup_runhmi stores it already-stripped, so it too preserves foreign lines).
    if _valid_runhmi "$RUN_HMI.bak"; then
        cp "$RUN_HMI.bak" "$STATE/.rh.restore" && _valid_runhmi "$STATE/.rh.restore" \
            && mv "$STATE/.rh.restore" "$RUN_HMI" && { RESTORE_VIA=".bak fallback"; return 0; }
    fi
    # FALLBACK 2: SD backup (deep last resort).
    if [ -n "$SD_BACKUP" ] && _valid_runhmi "$SD_BACKUP/runHMI.sh"; then
        cp "$SD_BACKUP/runHMI.sh" "$STATE/.rh.restore" && _valid_runhmi "$STATE/.rh.restore" \
            && mv "$STATE/.rh.restore" "$RUN_HMI" && { RESTORE_VIA="SD backup fallback"; return 0; }
    fi
    rm -f "$STATE/.rh.restore"
    return 1
}

# Restore a plain file ($1) from SD <name> ($2), else FS .bak. 1 if no backup.
restore_plain() {
    if [ -n "$SD_BACKUP" ] && [ -s "$SD_BACKUP/$2" ]; then cp "$SD_BACKUP/$2" "$1"; return 0; fi
    [ -s "$1.bak" ] && { cp "$1.bak" "$1"; return 0; }
    return 1
}

# Enable nowplaying + medialib in iap2.cfg (idempotent awk). Shared by activate.
enable_iap2_nowplaying() {
    np=$(awk '/^\[nowplaying\]/{f=1;next} /^\[/{f=0} f && /^enable=/{print; exit}' "$IAP2" 2>/dev/null)
    if [ "$np" = "enable=yes" ]; then
        echo " [SKIP] /tsd/var/sal/carplay/iap2.cfg already patched"
        return 0
    fi
    awk '
        /^\[medialib\]/   { sec="medialib";   print; next }
        /^\[nowplaying\]/ { sec="nowplaying"; print; next }
        /^\[commands\]/   { sec="commands";   print; next }
        /^\[/             { sec="";           print; next }
        sec=="medialib"   && /^enable=no$/                        { print "enable=yes"; next }
        sec=="nowplaying" && /^enable=no$/                        { print "enable=yes"; next }
        sec=="commands"   && /^iap2_nowplay_set=no$/              { print "iap2_nowplay_set=yes"; next }
        sec=="commands"   && /^iap2_medialib_play_resume=no$/     { print "iap2_medialib_play_resume=yes"; next }
        sec=="commands"   && /^iap2_medialib_play_collection=no$/ { print "iap2_medialib_play_collection=yes"; next }
        sec=="commands"   && /^iap2_medialib_play_tracks=no$/     { print "iap2_medialib_play_tracks=yes"; next }
        sec=="commands"   && /^iap2_medialib_play_all=no$/        { print "iap2_medialib_play_all=yes"; next }
        { print }
    ' "$IAP2" > "$STATE/.iap2.new" 2>/dev/null
    if [ -s "$STATE/.iap2.new" ]; then
        mv "$STATE/.iap2.new" "$IAP2"
        echo " [OK]   /tsd/var/sal/carplay/iap2.cfg patched"
    else
        echo " [..]   iap2 transform empty; left unchanged."; rm -f "$STATE/.iap2.new"
    fi
}

# Reverse of enable_iap2_nowplaying: set our managed tokens back to =no on the LIVE iap2.cfg.
# Makes disable symmetric -> no iap2.cfg backup needed (that stale-SD-read was the disable freeze).
disable_iap2_nowplaying() {
    awk '
        /^\[medialib\]/   { sec="medialib";   print; next }
        /^\[nowplaying\]/ { sec="nowplaying"; print; next }
        /^\[commands\]/   { sec="commands";   print; next }
        /^\[/             { sec="";           print; next }
        sec=="medialib"   && /^enable=yes$/                        { print "enable=no"; next }
        sec=="nowplaying" && /^enable=yes$/                        { print "enable=no"; next }
        sec=="commands"   && /^iap2_nowplay_set=yes$/              { print "iap2_nowplay_set=no"; next }
        sec=="commands"   && /^iap2_medialib_play_resume=yes$/     { print "iap2_medialib_play_resume=no"; next }
        sec=="commands"   && /^iap2_medialib_play_collection=yes$/ { print "iap2_medialib_play_collection=no"; next }
        sec=="commands"   && /^iap2_medialib_play_tracks=yes$/     { print "iap2_medialib_play_tracks=no"; next }
        sec=="commands"   && /^iap2_medialib_play_all=yes$/        { print "iap2_medialib_play_all=no"; next }
        { print }
    ' "$IAP2" > "$STATE/.iap2.new" 2>/dev/null
    if [ -s "$STATE/.iap2.new" ]; then
        mv "$STATE/.iap2.new" "$IAP2"
        echo " [OK]   /tsd/var/sal/carplay/iap2.cfg reverted"
    else
        echo " [..]   iap2 transform empty; left unchanged."; rm -f "$STATE/.iap2.new"
    fi
}

# "full" variant only: enable [destination] so Apple pushes the shared destination (0x10DB) on a
# "Share -> vehicle". Flips an existing [destination] to yes, or appends the section. Idempotent.
enable_iap2_destination() {
    dest=$(awk '/^\[destination\]/{f=1;next} /^\[/{f=0} f && /^enable=/{print; exit}' "$IAP2" 2>/dev/null)
    if [ "$dest" = "enable=yes" ]; then
        echo " [SKIP] /tsd/var/sal/carplay/iap2.cfg [destination] already enabled"
        return 0
    fi
    if [ -n "$dest" ]; then
        awk '/^\[destination\]/{s=1;print;next} /^\[/{s=0} s&&/^enable=no$/{print "enable=yes";next} {print}' "$IAP2" > "$STATE/.iap2.new" 2>/dev/null
    else
        cp "$IAP2" "$STATE/.iap2.new" 2>/dev/null && { echo "[destination]"; echo "enable=yes"; } >> "$STATE/.iap2.new"
    fi
    if [ -s "$STATE/.iap2.new" ]; then
        mv "$STATE/.iap2.new" "$IAP2"
        echo " [OK]   /tsd/var/sal/carplay/iap2.cfg [destination] enable=yes"
    else
        rm -f "$STATE/.iap2.new"
    fi
}

# Reverse of enable_iap2_destination: strip the whole [destination] section (clean, no leftover).
disable_iap2_destination() {
    grep -q "^\[destination\]" "$IAP2" 2>/dev/null || return 0
    awk '/^\[destination\]/{s=1;next} s&&/^\[/{s=0} s{next} {print}' "$IAP2" > "$STATE/.iap2.new" 2>/dev/null
    if [ -s "$STATE/.iap2.new" ]; then
        mv "$STATE/.iap2.new" "$IAP2"
        echo " [OK]   /tsd/var/sal/carplay/iap2.cfg [destination] removed"
    else
        rm -f "$STATE/.iap2.new"
    fi
}
