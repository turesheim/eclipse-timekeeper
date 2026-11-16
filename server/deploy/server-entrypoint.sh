#!/bin/sh
set -eu
. "$(dirname "$0")/environment.sh"
if [ -n "${DAGSVERK_MAINTENANCE_FILE:-}" ] && [ -e "$DAGSVERK_MAINTENANCE_FILE" ]; then
    echo "Timekeeper maintenance is incomplete; keep the package stopped and retry the maintenance operation." >&2
    exit 1
fi
if [ -n "${DAGSVERK_SERVER_DB_PASSWORD_FILE:-}" ]; then
    DAGSVERK_SERVER_DB_PASSWORD=$(cat "$DAGSVERK_SERVER_DB_PASSWORD_FILE")
    export DAGSVERK_SERVER_DB_PASSWORD
fi
if [ -n "${DAGSVERK_SMTP_PASSWORD_FILE:-}" ]; then
    DAGSVERK_SMTP_PASSWORD=$(cat "$DAGSVERK_SMTP_PASSWORD_FILE")
    export DAGSVERK_SMTP_PASSWORD
fi
set -f
# Options are deliberately word-split; no eval or pathname expansion is performed.
# shellcheck disable=SC2086
set -- ${JAVA_OPTS:--Xms128m -Xmx2g}
if [ -n "${TMPDIR:-}" ]; then
    # Container /tmp is RAM-backed. Keep large private ZIPs on the state volume.
    (umask 077; mkdir -p "$TMPDIR")
    set -- "-Djava.io.tmpdir=$TMPDIR" "$@"
fi
exec java -XX:+ExitOnOutOfMemoryError "$@" -jar /app/quarkus-run.jar
