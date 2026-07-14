#!/bin/sh
# cpvc_activate_debug.sh -- same as cpvc_activate.sh, then turn debug logging ON for the next boot.
. "${CPVC_ROOT}/tsd/etc/persistence/esd/scripts/cpvc_common.sh"
# Delegate to the universal activate with the debug flag. The debug-enabled marker is set ONLY on a
# successful activation (inside cpvc_activate.sh), so a failed/aborted activate (region mismatch,
# >1 JAR, ...) never leaves a stale debug state that would defeat the copy-logs gate.
CPVC_DEBUG=1 CPVC_ROOT="$CPVC_ROOT" sh "$ESD_SCRIPTS/cpvc_activate.sh"
exit 0
