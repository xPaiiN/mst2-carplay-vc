#!/bin/sh
# cpvc_activate.sh -- universal activate. The single mst2-carplay-vc-<variant>-<region>-v<N>.jar on
# the stick decides the variant (full/full-no-prog/lite) and region (eu/cn). full/full-no-prog/noprog
# deploy the native cover-bridge + keepalive; lite does not. Plain activate = debug logging OFF.
. "${CPVC_ROOT}/tsd/etc/persistence/esd/scripts/cpvc_common.sh"

echo "mst2-carplay-vc enable --debug=${CPVC_DEBUG:-0}"

find_stick || { echo "FAIL: No mst2-carplay-vc-*.jar found in sd/custom/java"; exit 0; }
find_patch_jar || exit 0
remount_rw
echo " [OK]   sd=$STICK"
echo " [OK]   variant=$VARIANT region=$REGION"

USE_COVER=no
if variant_uses_cover "$VARIANT"; then USE_COVER=yes; fi
COVER_SRC="$STICK/custom/java/cpvc-cover-bridge"
[ -s "$PATCH_JAR" ] || { echo "FAIL: jar is invalid/empty: $PATCH_JAR"; exit 0; }
if [ "$USE_COVER" = yes ] && [ ! -s "$COVER_SRC" ]; then
    echo "FAIL: cpvc-cover-bridge missing (required for $VARIANT)."; exit 0
fi

# 1) backups (skip-if-exists) BEFORE any change
if ! backup_runhmi; then
    echo "FAIL: could not backup runHMI.sh (missing or unreadable), aborting."
    exit 0
fi
backup_plain "$IAP2" "iap2.cfg"   # safety net; disable actively reverts (does NOT read this back)
echo " [OK]   backup created:"
if [ -f "$RUN_HMI.bak" ]; then echo "          FS: $RUN_HMI.bak"; else echo "          FS: WARN runHMI.sh.bak missing"; fi
[ -f "$IAP2.bak" ] && echo "          FS: $IAP2.bak"
if [ -n "$SD_BACKUP" ]; then
    [ -f "$SD_BACKUP/runHMI.sh" ] && echo "          SD: $SD_BACKUP/runHMI.sh"
    [ -f "$SD_BACKUP/iap2.cfg" ]  && echo "          SD: $SD_BACKUP/iap2.cfg"
else
    echo "          SD: none (SD backup dir unavailable)"
fi

# 2) copy jar (staged: tmp then move)
mkdir -p "$TSD/hmi/HMI/jar" 2>/dev/null
if ! { cp "$PATCH_JAR" "$STATE/.jar.tmp" && rm -f "$JAR_DEST" && mv "$STATE/.jar.tmp" "$JAR_DEST"; }; then
    echo "FAIL: jar copy failed."; exit 0
fi
chmod a+rwx "$JAR_DEST" 2>/dev/null
echo " [OK]   jar copied to /tsd/hmi/HMI/jar/"

# 3) copy cover-bridge ELF (full/full-no-prog/noprog only, staged: tmp then move)
if [ "$USE_COVER" = yes ]; then
    mkdir -p "$TSD/lib/carplayvc" 2>/dev/null
    if ! { cp "$COVER_SRC" "$STATE/.cb.tmp" && rm -f "$COVER_DEST" && mv "$STATE/.cb.tmp" "$COVER_DEST"; }; then
        echo "FAIL: cpvc-cover-bridge copy failed."; exit 0
    fi
    chmod 755 "$COVER_DEST" 2>/dev/null
    [ -x "$COVER_DEST" ] || { echo "FAIL: cover-bridge not executable after chmod. Aborting."; exit 0; }
    echo " [OK]   cpvc-cover-bridge copied to /tsd/lib/carplayvc/ (chmod 755 OK)"
fi

# 4) patch runHMI: strip old bridge+cover-bridge lines, insert after LAST BOOTCLASSPATH=, sh -n gate.
awk '!/mst2-carplay-vc\.jar/ && !/cpvc-cover-bridge/ { print }' "$RUN_HMI" > "$STATE/.rh.stripped" 2>/dev/null
LAST=$(awk '/^BOOTCLASSPATH=/{n=NR} END{print n+0}' "$STATE/.rh.stripped" 2>/dev/null)
if [ "${LAST:-0}" -le 0 ]; then
    echo "FAIL: no BOOTCLASSPATH line in runHMI.sh. Aborting."; rm -f "$STATE/.rh.stripped"; exit 0
fi
if [ "$USE_COVER" = yes ]; then
    awk -v ln="$LAST" -v cb="$COVER_DEST" -v sent="$SENTINEL" '
        { print }
        NR == ln {
            print "BOOTCLASSPATH=\"$BOOTCLASSPATH -Xbootclasspath/p:$MIBJAR/mst2-carplay-vc.jar\""
            print "[ -x \"" cb "\" ] && ( while [ -f \"" sent "\" ]; do \"" cb "\" </dev/null >/dev/null 2>&1; sleep 10; done ) </dev/null >/dev/null 2>&1 &"
        }
    ' "$STATE/.rh.stripped" > "$STATE/.rh.patched" 2>/dev/null
else
    awk -v ln="$LAST" '
        { print }
        NR == ln {
            print "BOOTCLASSPATH=\"$BOOTCLASSPATH -Xbootclasspath/p:$MIBJAR/mst2-carplay-vc.jar\""
        }
    ' "$STATE/.rh.stripped" > "$STATE/.rh.patched" 2>/dev/null
fi
rm -f "$STATE/.rh.stripped"
if ! sh -n "$STATE/.rh.patched" 2>/dev/null; then
    echo "FAIL: patched runHMI.sh failed sh -n. Aborting."; rm -f "$STATE/.rh.patched"; exit 0
fi
cnt=$(grep -c "mst2-carplay-vc.jar" "$STATE/.rh.patched" 2>/dev/null)
if [ "$cnt" != 1 ]; then
    echo "FAIL: expected 1 bridge line, got $cnt. Aborting."; rm -f "$STATE/.rh.patched"; exit 0
fi
if [ "$USE_COVER" = yes ]; then
    cbcnt=$(grep -c "cpvc-cover-bridge" "$STATE/.rh.patched" 2>/dev/null)
    if [ "$cbcnt" != 1 ]; then
        echo "FAIL: expected 1 cover-bridge keepalive line, got $cbcnt. Aborting."; rm -f "$STATE/.rh.patched"; exit 0
    fi
fi
mv "$STATE/.rh.patched" "$RUN_HMI" || { echo "FAIL: could not install patched runHMI.sh. Aborting."; exit 0; }
echo " [OK]   /tsd/hmi/runHMI.sh patched"

# 5) cover-bridge keepalive sentinel (full/full-no-prog/noprog only)
if [ "$USE_COVER" = yes ]; then
    touch "$SENTINEL"
    echo " [OK]   /tsd/var/carplayvc/tap-keepalive set"
fi

# 6) iap2.cfg -- enable nowplaying (+ [destination] for the "full" navi variant)
enable_iap2_nowplaying
if variant_uses_navi "$VARIANT"; then enable_iap2_destination; fi

# 7) debug marker: reached only on success (all failure paths exit above). ON iff --debug requested.
if [ "${CPVC_DEBUG:-0}" = 1 ]; then
    touch "$STATE/debug-enabled"
    echo " [LOG]  --debug flag enabled. Only use this when troubleshooting."
else
    rm -f "$STATE/debug-enabled" 2>/dev/null
    echo " [LOG]  --debug flag not set"
fi

# 8) variant marker
echo "$VARIANT" > "$STATE/cpvc-variant"
touch "$STATE/active"
echo ""
echo "MST2 Carplay VC Patch v1 successfully enabled :)"
echo "${PATCH_JAR##*/}"
echo "variant=$VARIANT, region=$REGION"
echo "Reboot now to apply"
echo ""
echo "Made by xPaiiN <3"
echo "https://github.com/xPaiiN/mst2-carplay-vc"
exit 0
