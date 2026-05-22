#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/infra/docker/compose.dev.yml"

readonly ROOT_DIR COMPOSE_FILE

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

usage() {
  cat <<'EOF'
Usage:
  ./scripts/backend-dev.sh up [--migrate]
  ./scripts/backend-dev.sh sync [--migrate] <service...>
  ./scripts/backend-dev.sh migrate [all|<service...>]
  ./scripts/backend-dev.sh logs [service...]
  ./scripts/backend-dev.sh ps
  ./scripts/backend-dev.sh down
EOF
}

is_valid_service() {
  case "$1" in
    api|identity-service|friendship-service|post-service|media-service|guess-service|notification-service)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

flyway_service_for() {
  case "$1" in
    identity-service) echo "flyway-identity-service" ;;
    friendship-service) echo "flyway-friendship-service" ;;
    post-service) echo "flyway-post-service" ;;
    media-service) echo "flyway-media-service" ;;
    guess-service) echo "flyway-guess-service" ;;
    notification-service) echo "flyway-notification-service" ;;
    *) return 1 ;;
  esac
}

validate_services() {
  local service

  for service in "$@"; do
    if [[ "${service}" == "all" ]]; then
      continue
    fi
    if ! is_valid_service "${service}"; then
      echo "Unknown service: ${service}" >&2
      exit 1
    fi
  done
}

start_infra() {
  compose up -d mariadb redis
}

run_migrations_for() {
  local service
  local flyway_service

  for service in "$@"; do
    if flyway_service="$(flyway_service_for "${service}" 2>/dev/null)"; then
      compose --profile migration run --rm "${flyway_service}"
    fi
  done
}

run_all_migrations() {
  run_migrations_for \
    identity-service \
    friendship-service \
    post-service \
    media-service \
    guess-service \
    notification-service
}

main() {
  local command="${1:-}"
  local migrate=false
  local -a services=()

  if [[ -z "${command}" ]]; then
    usage
    exit 1
  fi
  if [[ "${command}" == "-h" || "${command}" == "--help" ]]; then
    usage
    exit 0
  fi
  shift

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --migrate)
        migrate=true
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        services+=("$1")
        ;;
    esac
    shift
  done

  if [[ ${#services[@]} -gt 0 ]]; then
    validate_services "${services[@]}"
  fi

  case "${command}" in
    up)
      start_infra
      if [[ "${migrate}" == "true" ]]; then
        run_all_migrations
      fi
      compose up -d --build
      ;;
    sync)
      if [[ ${#services[@]} -eq 0 ]]; then
        echo "sync requires at least one service." >&2
        exit 1
      fi
      start_infra
      if [[ "${migrate}" == "true" ]]; then
        run_migrations_for "${services[@]}"
      fi
      compose build "${services[@]}"
      compose up -d --no-deps "${services[@]}"
      ;;
    migrate)
      start_infra
      if [[ ${#services[@]} -eq 0 || "${services[0]}" == "all" ]]; then
        run_all_migrations
      else
        run_migrations_for "${services[@]}"
      fi
      ;;
    logs)
      if [[ ${#services[@]} -eq 0 ]]; then
        compose logs -f
      else
        compose logs -f "${services[@]}"
      fi
      ;;
    ps)
      compose ps
      ;;
    down)
      compose down
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
