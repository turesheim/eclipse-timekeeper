#!/bin/sh
set -eu
exec "$(dirname "$0")/../scripts/timekeeper-dsm" uninstall-wizard > "$SYNOPKG_TEMP_LOGFILE"
