#!/bin/sh
# cpvc_status.sh -- show the current mst2-carplay-vc install state.
. "${CPVC_ROOT}/tsd/etc/persistence/esd/scripts/cpvc_common.sh"

J=no; R=no; N=no; C=no; S=no; B=no; D=no
VARIANT_NOW="none"

[ -e "$JAR_DEST" ] && J=yes
grep -q "mst2-carplay-vc.jar" "$RUN_HMI" 2>/dev/null && R=yes
np=$(awk '/^\[nowplaying\]/{f=1;next} /^\[/{f=0} f && /^enable=/{print; exit}' "$IAP2" 2>/dev/null)
[ "$np" = "enable=yes" ] && N=yes
[ -e "$COVER_DEST" ] && C=yes
[ -f "$SENTINEL" ] && S=yes
[ -f "$STATE/debug-enabled" ] && D=yes
find_stick && [ -f "$SD_BACKUP/runHMI.sh" ] && B=yes
[ -f "$STATE/cpvc-variant" ] && VARIANT_NOW=$(awk '{print $1}' "$STATE/cpvc-variant" 2>/dev/null)

echo "mst2-carplay-vc status:"
echo "  head-unit region:                       $(detect_region)"
echo "  /tsd/var/sal/carplay/iap2.cfg patched:  $N"
echo "  /tsd/hmi/HMI/jar/mst2-carplay-vc.jar:    $J"
echo "  /tsd/hmi/runHMI.sh patched:             $R"
echo "  /tsd/lib/carplayvc/cpvc-cover-bridge:   $C"
echo "  /tsd/var/carplayvc/tap-keepalive:       $S"
echo "  --debug logging enabled:                $D"
echo "  SD backup present:                      $B"
if [ "$J" = yes ] && [ "$R" = yes ]; then echo "=> ACTIVE (variant: ${VARIANT_NOW})"; else echo "=> INACTIVE"; fi
exit 0
