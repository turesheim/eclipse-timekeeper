#!/bin/sh
# Explicit maintenance operation; restore only a verified backup, with API replicas stopped.
set -eu
cd "$(dirname "$0")"
if [ "$#" -ne 1 ]; then echo "Usage: $0 /absolute/path/to/backup-directory" >&2; exit 2; fi
backup_dir=$1
test -s "$backup_dir/database.dump"
test -s "$backup_dir/tokens.json"
# shellcheck source=/dev/null
if [ -f .env ]; then set -a; . ./.env; set +a; fi
state_dir=${TIMEKEEPER_STATE_DIR:-./state}
docker compose stop api-1 api-2
docker compose run --rm --no-deps -T --entrypoint sh maintenance -ec '
  export PGPASSWORD="$(cat /run/secrets/timekeeper_database_password)"
  exec pg_restore -h postgres -U timekeeper -d timekeeper --clean --if-exists --no-owner --exit-on-error
' < "$backup_dir/database.dump"
cp "$backup_dir/tokens.json" "$state_dir/config/tokens.json.new"
chmod 644 "$state_dir/config/tokens.json.new"
mv "$state_dir/config/tokens.json.new" "$state_dir/config/tokens.json"
# Wait for both members before returning; a single ready load-balanced request
# does not prove that the other restored replica has completed startup.
docker compose up -d --no-recreate --wait --wait-timeout 180 api-1 api-2
