#!/bin/sh
# Consistent logical backup plus the token registry.
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
  exec pg_dump -h postgres -U timekeeper -d timekeeper --format=custom
' > "$backup_dir/database.dump.tmp"
mv "$backup_dir/database.dump.tmp" "$backup_dir/database.dump"
cp "$state_dir/config/tokens.json" "$backup_dir/tokens.json"
cp "$state_dir/config/mail.properties" "$backup_dir/mail.properties"
cp "$state_dir/secrets/timekeeper_smtp_password" "$backup_dir/smtp_password"
# Read deployed identities from containers: after a mutable tag is replaced,
# Compose's image listing may fail to inspect the old image by its ID.
containers=$(docker compose ps --all --quiet)
printf 'CONTAINER IMAGE IMAGE_ID\n' > "$backup_dir/images.txt"
for container in $containers; do
  docker inspect --type container --format '{{.Name}} {{.Config.Image}} {{.Image}}' "$container" >> "$backup_dir/images.txt"
done
chmod 600 "$backup_dir"/*
printf '%s\n' "$backup_dir"
