#!/bin/sh
set -eu
exec "$(dirname "$0")/../scripts/timekeeper-dsm" wizard > "$SYNOPKG_TEMP_LOGFILE"
