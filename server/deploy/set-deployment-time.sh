#!/bin/sh
# Record a new deployment in Compose's environment; do not run for ordinary restarts.
set -eu
cd "$(dirname "$0")"
timestamp=${1:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}
case "$timestamp" in
  ????-??-??T??:??:??Z) ;;
  *) echo 'Expected a UTC deployment timestamp (yyyy-MM-ddTHH:mm:ssZ).' >&2; exit 1 ;;
esac
case "$timestamp" in *[!0-9TZ:-]*) echo 'Invalid deployment timestamp.' >&2; exit 1 ;; esac
temporary=".env.deployment.$$"
umask 077
trap 'rm -f "$temporary"' EXIT
{
  if [ -f .env ]; then awk '!/^[[:space:]]*(export[[:space:]]+)?TIMEKEEPER_DEPLOYED_AT=/' .env; fi
  printf 'TIMEKEEPER_DEPLOYED_AT=%s\n' "$timestamp"
} > "$temporary"
mv "$temporary" .env
