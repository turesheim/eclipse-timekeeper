#!/bin/sh
set -eu
# This SDK-defined file carries wizard JSON, not a routine installation log.
exec "$(dirname "$0")/../scripts/timekeeper-dsm" wizard > "$SYNOPKG_TEMP_LOGFILE"
