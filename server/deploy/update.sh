#!/bin/sh
# DSM Task Scheduler: drain, replace and verify one API replica at a time.
set -eu
cd "$(dirname "$0")"
# shellcheck source=/dev/null
if [ -f .env ]; then set -a; . ./.env; set +a; fi
state_dir=${TIMEKEEPER_STATE_DIR:-./state}
lock_dir="$state_dir/update.lock"
if ! mkdir "$lock_dir" 2>/dev/null; then echo 'Another update is running.'; exit 0; fi
drained=
cleanup() {
  if [ -n "$drained" ]; then
    gateway_command "set server api/$drained state ready" >/dev/null 2>&1 || true
  fi
  rmdir "$lock_dir"
}
trap cleanup EXIT
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

gateway_command() {
  docker compose exec -T gateway sh -c 'printf "%s\n" "$1" | nc -w 1 127.0.0.1 9999' sh "$1"
}

wait_routed() {
  attempt=0
  while [ "$attempt" -lt "${TIMEKEEPER_UPDATE_ATTEMPTS:-60}" ]; do
    status=$(gateway_command 'show stat' | awk -F, -v member="$1" '$1 == "api" && $2 == member {print $18}')
    if [ "$status" = UP ]; then return 0; fi
    attempt=$((attempt + 1))
    sleep 2
  done
  return 1
}

drain() {
  gateway_command "set server api/$1 state drain" || return 1
  drained=$1
  attempt=0
  while [ "$attempt" -lt "${TIMEKEEPER_UPDATE_ATTEMPTS:-60}" ]; do
    sessions=$(gateway_command 'show stat' | awk -F, -v member="$1" '$1 == "api" && $2 == member {print $5}')
    if [ "$sessions" = 0 ]; then return 0; fi
    attempt=$((attempt + 1))
    sleep 2
  done
  gateway_command "set server api/$1 state ready" || return 1
  drained=
  return 1
}

replace() {
  drain "$1" || return 1
  TIMEKEEPER_SERVER_IMAGE="$2" TIMEKEEPER_DEPLOYED_AT="${3-$deployment_time}" docker compose up -d --no-deps --pull never "$1" || return 1
  wait_ready "$1" || return 1
  gateway_command "set server api/$1 state ready" || return 1
  wait_routed "$1" || return 1
  drained=
}

wait_ready api-1
wait_ready api-2
wait_routed api-1
wait_routed api-2
old_id_1=$(docker inspect --format '{{.Image}}' "$(docker compose ps -q api-1)")
old_id_2=$(docker inspect --format '{{.Image}}' "$(docker compose ps -q api-2)")
# A running container alone does not retain an addressable image in every
# Docker image store. Keep immutable local tags before a pull moves edge.
retain_image() {
  retained="timekeeper-deployed:${1#sha256:}"
  docker image tag "$1" "$retained" || return 1
  printf '%s\n' "$retained"
}
old_1=$(retain_image "$old_id_1")
old_2=$(retain_image "$old_id_2")
deployment_date() {
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$(docker compose ps -q "$1")" |
    awk '/^TIMEKEEPER_DEPLOYED_AT=/ {sub(/^TIMEKEEPER_DEPLOYED_AT=/, ""); print; exit}'
}
old_date_1=$(deployment_date api-1)
old_date_2=$(deployment_date api-2)
docker compose pull api-1 api-2
image_ref=${TIMEKEEPER_SERVER_IMAGE:-ghcr.io/turesheim/eclipse-timekeeper-server:edge}
new_id=$(docker image inspect --format '{{.Id}}' "$image_ref")
if [ "$old_id_1" = "$new_id" ] && [ "$old_id_2" = "$new_id" ]; then echo 'Server image is current.'; exit 0; fi
new_image=$(retain_image "$new_id")
sh ./backup.sh
deployment_time=$(date -u +%Y-%m-%dT%H:%M:%SZ)
rollback() {
  # Recover the failed replica first, keeping the surviving replica in service.
  if [ "$1" = api-2 ]; then
    replace api-2 "$old_2" "$old_date_2" && replace api-1 "$old_1" "$old_date_1"
  else
    replace api-1 "$old_1" "$old_date_1" && replace api-2 "$old_2" "$old_date_2"
  fi
}
if ! replace api-1 "$new_image"; then
  rollback api-1
  echo 'First replica failed replacement; previous images restored.' >&2
  exit 1
fi
if ! replace api-2 "$new_image"; then
  rollback api-2
  echo 'Second replica failed replacement; previous images restored.' >&2
  exit 1
fi
sh ./set-deployment-time.sh "$deployment_time"
echo 'Both server replicas updated and healthy.'
