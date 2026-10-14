#!/bin/sh
set -eu
if [ -n "${TIMEKEEPER_MAINTENANCE_FILE:-}" ] && [ -e "$TIMEKEEPER_MAINTENANCE_FILE" ]; then
    echo "Timekeeper maintenance is incomplete; keep the package stopped and retry the maintenance operation." >&2
    exit 1
fi
if [ -n "${TIMEKEEPER_SERVER_DB_PASSWORD_FILE:-}" ]; then
    TIMEKEEPER_SERVER_DB_PASSWORD=$(cat "$TIMEKEEPER_SERVER_DB_PASSWORD_FILE")
    export TIMEKEEPER_SERVER_DB_PASSWORD
fi
set -f
# Options are deliberately word-split; no eval or pathname expansion is performed.
# shellcheck disable=SC2086
set -- ${JAVA_OPTS:--Xms64m -Xmx384m}
exec java "$@" -jar /app/quarkus-run.jar
