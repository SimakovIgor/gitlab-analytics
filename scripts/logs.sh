#!/usr/bin/env bash
# logs.sh — стримит логи приложения (локально или удалённо)
#
# Использование:
#   ./scripts/logs.sh                  # локальные логи (docker compose)
#   ./scripts/logs.sh --remote         # логи с удалённого сервера
#   ./scripts/logs.sh --remote -n 200  # последние 200 строк + follow
#   ./scripts/logs.sh --service grafana  # логи другого контейнера локально

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[logs]${NC} $*"; }
error() { echo -e "${RED}[logs]${NC} $*" >&2; }

REMOTE=false
SERVICE="app"
TAIL_LINES=100

while [[ $# -gt 0 ]]; do
  case "$1" in
    --remote)          REMOTE=true; shift ;;
    --service)         SERVICE="$2"; shift 2 ;;
    -n|--tail)         TAIL_LINES="$2"; shift 2 ;;
    --help|-h)
      sed -n '2,7p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
      exit 0
      ;;
    *) error "Неизвестный аргумент: $1"; exit 1 ;;
  esac
done

if [[ "$REMOTE" == true ]]; then
  ENV_FILE="${PROJECT_DIR}/.env.prod"
  if [[ ! -f "${ENV_FILE}" ]]; then
    error ".env.prod не найден."
    exit 1
  fi
  # shellcheck source=/dev/null
  source "${ENV_FILE}"
  SERVER_IP="${SERVER_IP:?SERVER_IP не задан в .env.prod}"
  SERVER_USER="${SERVER_USER:-root}"
  REMOTE_DIR="/opt/gitlab-analytics"

  info "Логи ${SERVICE} на ${SERVER_IP} (tail=${TAIL_LINES}, follow)..."
  ssh -t "${SERVER_USER}@${SERVER_IP}" \
    "docker compose -f ${REMOTE_DIR}/docker/docker-compose.prod.yml logs -f --tail=${TAIL_LINES} ${SERVICE}"
else
  export DOCKER_CONTEXT=orbstack
  info "Локальные логи ${SERVICE} (tail=${TAIL_LINES}, follow)..."
  docker compose -f "${PROJECT_DIR}/docker/docker-compose.yml" \
    -f "${PROJECT_DIR}/docker/docker-compose.monitoring.yml" \
    logs -f --tail="${TAIL_LINES}" "${SERVICE}" 2>/dev/null || \
  docker compose -f "${PROJECT_DIR}/docker/docker-compose.yml" \
    logs -f --tail="${TAIL_LINES}" "${SERVICE}"
fi
