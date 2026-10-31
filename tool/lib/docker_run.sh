#!/usr/bin/env bash
# Shared by every tool/*_in_docker.sh: run one compose service, time-limited.
#
#   run_service <default-limit> <service> [args…]
#
# GCM_TIMEOUT overrides the limit for one run (e.g. GCM_TIMEOUT=90m). When the
# limit hits, the run is killed, its container removed, and the script exits
# 124 with a message saying so. Nothing started here can outlive the script (D21).

# Git Bash rewrites anything that looks like a Unix path (/src/x.jpg) into a
# Windows one before docker sees it; container paths must pass through as typed.
export MSYS_NO_PATHCONV=1

# Globals, not locals: the EXIT trap runs after run_service has returned.
GCM_COMPOSE_FILE=docker-compose.test.yml
GCM_PROJECT=garment-colour-test

gcm_cleanup() {
  docker compose -f "$GCM_COMPOSE_FILE" down --remove-orphans > /dev/null 2>&1 || true
  docker ps -aq --filter "label=com.docker.compose.project=$GCM_PROJECT" \
    | xargs -r docker rm -f > /dev/null 2>&1 || true
}

run_service() {
  local default_limit="$1" service="$2"
  shift 2
  local limit="${GCM_TIMEOUT:-$default_limit}"
  local caller
  caller="$(basename "${BASH_SOURCE[1]}")"
  trap gcm_cleanup EXIT

  local status=0
  timeout --kill-after=30s "$limit" docker compose -f "$GCM_COMPOSE_FILE" run --rm "$service" "$@" \
    || status=$?
  [ "$status" = 124 ] && echo "tool/$caller: stopped after $limit" >&2
  return "$status"
}
