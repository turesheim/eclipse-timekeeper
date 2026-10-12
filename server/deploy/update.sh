#!/bin/sh
# DSM Task Scheduler entry point: update only the API, one healthy replica at a time.
set -eu
cd "$(dirname "$0")"
# shellcheck source=/dev/null
if [ -f .env ]; then set -a; . ./.env; set +a; fi
state_dir=${TIMEKEEPER_STATE_DIR:-./state}
lock_dir="$state_dir/update.lock"
if ! mkdir "$lock_dir" 2>/dev/null; then echo 'Another update is running.'; exit 0; fi
trap 'rmdir "$lock_dir"' EXIT
trap 'exit 130' HUP INT TERM

wait_ready() {
  attempt=0
  while [ "$attempt" -lt "${TIMEKEEPER_UPDATE_ATTEMPTS:-60}" ]; do
    container=$(docker compose ps -q "$1")
    if [ -n "$container" ] && [ "$(docker inspect --format '{{.State.Health.Status}}' "$container")" = healthy ]; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
  return 1
}

wait_ready api-1
wait_ready api-2
old_1=$(docker inspect --format '{{.Image}}' "$(docker compose ps -q api-1)")
old_2=$(docker inspect --format '{{.Image}}' "$(docker compose ps -q api-2)")
docker compose pull api-1 api-2
image_ref=${TIMEKEEPER_SERVER_IMAGE:-ghcr.io/turesheim/eclipse-timekeeper-server:edge}
new_image=$(docker image inspect --format '{{.Id}}' "$image_ref")
if [ "$old_1" = "$new_image" ] && [ "$old_2" = "$new_image" ]; then echo 'Server image is current.'; exit 0; fi
sh ./backup.sh
rollback() {
  TIMEKEEPER_SERVER_IMAGE="$old_1" docker compose up -d --no-deps api-1
  TIMEKEEPER_SERVER_IMAGE="$old_2" docker compose up -d --no-deps api-2
  wait_ready api-1 && wait_ready api-2
}
if ! docker compose up -d --no-deps api-1 || ! wait_ready api-1; then
  rollback
  echo 'First replica failed readiness; previous images restored.' >&2
  exit 1
fi
# Give the gateway time to mark the replacement healthy before touching the second replica.
sleep 5
if ! docker compose up -d --no-deps api-2 || ! wait_ready api-2; then
  rollback
  echo 'Second replica failed readiness; previous images restored.' >&2
  exit 1
fi
echo 'Both server replicas updated and healthy.'
