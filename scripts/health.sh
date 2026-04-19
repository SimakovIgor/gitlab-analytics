#!/usr/bin/env bash
# health.sh — проверяет состояние приложения (локально или удалённо)
#
# Использование:
#   ./scripts/health.sh           # локальный health check
#   ./scripts/health.sh --remote  # health check на удалённом сервере
#   ./scripts/health.sh --watch   # обновлять каждые 5 секунд

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[health]${NC} $*"; }
success() { echo -e "${GREEN}[health]${NC} $*"; }
warn()    { echo -e "${YELLOW}[health]${NC} $*"; }
error()   { echo -e "${RED}[health]${NC} $*" >&2; }

REMOTE=false
WATCH=false

for arg in "$@"; do
  case "$arg" in
    --remote) REMOTE=true ;;
    --watch)  WATCH=true ;;
    --help|-h)
      sed -n '2,7p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
      exit 0
      ;;
    *) error "Неизвестный аргумент: $arg"; exit 1 ;;
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
  BASE_URL="http://${SERVER_IP}:8080"
  TARGET="${SERVER_IP}"
else
  BASE_URL="http://localhost:8080"
  TARGET="localhost"
fi

check_once() {
  local ts
  ts=$(date '+%H:%M:%S')
  echo -e "\n${CYAN}── ${ts} · ${TARGET} ──────────────────────────────────────${NC}"

  # /actuator/health
  local health_raw
  if health_raw=$(curl -sf --max-time 5 "${BASE_URL}/actuator/health" 2>/dev/null); then
    local status
    status=$(echo "$health_raw" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [[ "$status" == "UP" ]]; then
      success "Health:    UP   ${BASE_URL}/actuator/health"
    else
      warn    "Health:    ${status}   ${BASE_URL}/actuator/health"
      echo    "           $health_raw"
    fi
  else
    error   "Health:    UNREACHABLE  ${BASE_URL}/actuator/health"
  fi

  # liveness / readiness
  for probe in liveness readiness; do
    if curl -sf --max-time 3 "${BASE_URL}/actuator/health/${probe}" >/dev/null 2>&1; then
      success "${probe^}: UP"
    else
      warn    "${probe^}: DOWN / not available"
    fi
  done

  # metrics endpoint
  if curl -sf --max-time 3 "${BASE_URL}/actuator/prometheus" >/dev/null 2>&1; then
    success "Prometheus scrape endpoint OK"
  else
    warn    "Prometheus scrape endpoint not available"
  fi
}

if [[ "$WATCH" == true ]]; then
  info "Наблюдаем за ${TARGET} (Ctrl+C для выхода)..."
  while true; do
    check_once
    sleep 5
  done
else
  check_once
fi
