#!/usr/bin/env bash

set -Eeuo pipefail

readonly WAIT_TIMEOUT_SECONDS="${COMPOSE_WAIT_TIMEOUT:-300}"
readonly COMPOSE=(docker compose)
services=("$@")

report_failure() {
  local failed_services=()
  local service

  echo "Services did not become healthy within ${WAIT_TIMEOUT_SECONDS} seconds." >&2
  "${COMPOSE[@]}" ps --all >&2 || true

  mapfile -t failed_services < <(
    "${COMPOSE[@]}" ps --all --format json 2>/dev/null \
      | jq -r '
          select(
            .State != "running" or
            (((.Health // "") != "") and .Health != "healthy")
          )
          | .Service
        ' \
      | sort -u
  )

  if ((${#failed_services[@]} == 0)); then
    failed_services=("${services[@]}")
  fi

  for service in "${failed_services[@]}"; do
    [[ -n "${service}" ]] || continue
    echo "---- ${service}: recent logs ----" >&2
    "${COMPOSE[@]}" logs --no-color --tail=100 "${service}" >&2 || true
  done
}

trap report_failure ERR

if ((${#services[@]} == 0)); then
  "${COMPOSE[@]}" up -d --wait --wait-timeout "${WAIT_TIMEOUT_SECONDS}"
else
  "${COMPOSE[@]}" up -d --wait --wait-timeout "${WAIT_TIMEOUT_SECONDS}" \
    "${services[@]}"
fi

trap - ERR
