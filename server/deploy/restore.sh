#!/bin/sh
# Explicit maintenance operation; restore only a verified backup, with API replicas stopped.
set -eu
cd "$(dirname "$0")"
if [ "$#" -ne 1 ]; then echo "Usage: $0 /absolute/path/to/backup-directory" >&2; exit 2; fi
backup_dir=$1
test -s "$backup_dir/database.dump"
test -s "$backup_dir/tokens.json"
if [ -e "$backup_dir/mail.properties" ] || [ -e "$backup_dir/smtp_password" ]; then
  test -f "$backup_dir/mail.properties"
  test -f "$backup_dir/smtp_password"
fi
# shellcheck source=/dev/null
if [ -f .env ]; then set -a; . ./.env; set +a; fi
if [ "${TIMEKEEPER_COMPOSE_LOCK_HELD:-}" != 1 ]; then
  exec python3 ./instance_material.py locked "$0" "$@"
fi
state_dir=${TIMEKEEPER_STATE_DIR:-./state}
docker compose stop api-1 api-2
python3 ./instance_material.py prepare "$backup_dir"
python3 ./restore-database.py "$backup_dir/database.dump"
cp "$backup_dir/tokens.json" "$state_dir/config/tokens.json.new"
chmod 644 "$state_dir/config/tokens.json.new"
mv "$state_dir/config/tokens.json.new" "$state_dir/config/tokens.json"
if [ -f "$backup_dir/mail.properties" ]; then
  cp "$backup_dir/mail.properties" "$state_dir/config/mail.properties.new"
  chmod 644 "$state_dir/config/mail.properties.new"
  mv "$state_dir/config/mail.properties.new" "$state_dir/config/mail.properties"
  cp "$backup_dir/smtp_password" "$state_dir/secrets/timekeeper_smtp_password.new"
  chmod 444 "$state_dir/secrets/timekeeper_smtp_password.new"
  mv "$state_dir/secrets/timekeeper_smtp_password.new" "$state_dir/secrets/timekeeper_smtp_password"
fi
python3 ./instance_material.py finish "$backup_dir"
# Wait for both members before returning; a single ready load-balanced request
# does not prove that the other restored replica has completed startup.
docker compose up -d --no-recreate --wait --wait-timeout 180 api-1 api-2
