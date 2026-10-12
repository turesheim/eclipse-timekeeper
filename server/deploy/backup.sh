#!/bin/sh
# Consistent logical backup through the current primary, plus the token registry.
set -eu
cd "$(dirname "$0")"
# shellcheck source=/dev/null
if [ -f .env ]; then set -a; . ./.env; set +a; fi
state_dir=${TIMEKEEPER_STATE_DIR:-./state}
backup_dir="$state_dir/backups/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$backup_dir"
chmod 700 "$backup_dir"
docker compose run --rm --no-deps -T --entrypoint sh maintenance -ec '
  export PGPASSWORD="$(cat /run/secrets/timekeeper_database_password)"
  exec pg_dump -h gateway -U timekeeper -d timekeeper --format=custom
' > "$backup_dir/database.dump.tmp"
mv "$backup_dir/database.dump.tmp" "$backup_dir/database.dump"
cp "$state_dir/config/tokens.json" "$backup_dir/tokens.json"
docker compose images > "$backup_dir/images.txt"
chmod 600 "$backup_dir"/*
printf '%s\n' "$backup_dir"
