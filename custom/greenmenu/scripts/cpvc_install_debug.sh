#!/bin/sh
# Made by xPaiiN <3
# github.com/xPaiiN/mst2-carplay-vc
# v2.0
case "$0" in */*) CPVC_SCRIPT_DIR="${0%/*}" ;; *) CPVC_SCRIPT_DIR=. ;; esac
exec sh "$CPVC_SCRIPT_DIR/cpvc_install.sh" --debug "$@" 2>/dev/null
